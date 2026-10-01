package com.example.modelviewer.render

import com.google.android.filament.Camera
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.TransformManager
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * One on-screen model: a square rectangle (in overlay pixels) + its own Filament
 * Scene / View / Camera. All containers render inside ONE Filament engine,
 * ONE SurfaceView, ONE render loop.
 */
class ModelContainer(val id: Int, val title: String) {

    enum class State { LOADING, READY, FAILED }

    companion object {
        const val FOV_DEG = 40.0
        const val NEAR = 0.05
        const val FAR = 20.0
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 5f
        /** Distance at which a unit-radius sphere just fits in the vertical FOV (+10% margin). */
        val BASE_DIST = (1.1 / sin(Math.toRadians(FOV_DEG / 2))).toFloat()
        private val TAN_HALF_FOV = tan(Math.toRadians(FOV_DEG / 2)).toFloat()
    }

    // ---- state ----
    var state = State.LOADING
    var progress = 0f
    var closed = false

    /** Rectangle in overlay pixels (top-left origin). Always square. */
    var left = 0f
    var top = 0f
    var size = 0f

    var interactionMode = false
    var labelsVisible = false

    var yaw = 30f
    var pitch = 15f
    var zoom = 1f

    // ---- filament handles (created by ModelSceneHost) ----
    lateinit var scene: Scene
    lateinit var view: View
    lateinit var camera: Camera
    var cameraEntity = 0
    var lightEntity = 0
    var skybox: Skybox? = null
    var asset: FilamentAsset? = null
    var loader: ResourceLoader? = null
    /** Keeps the GLB bytes alive until async loading is finished. */
    var pendingBuffer: ByteBuffer? = null

    // ---- model normalisation (model -> unit sphere at origin) ----
    private var normScale = 1f
    private var cx = 0f
    private var cy = 0f
    private var cz = 0f

    // ---- labels ----
    var labelText: Array<String> = emptyArray()
    private var labelPos = FloatArray(0)            // normalised model space xyz
    var labelScreen = FloatArray(0)                 // overlay-pixel x,y per label
    var labelOnScreen = BooleanArray(0)
    val labelCount: Int get() = labelText.size

    // ---- rotation matrix (3x3, rows) ----
    private var r00 = 1f; private var r01 = 0f; private var r02 = 0f
    private var r10 = 0f; private var r11 = 1f; private var r12 = 0f
    private var r20 = 0f; private var r21 = 0f; private var r22 = 1f
    private val rootMatrix = FloatArray(16)

    fun setModelInfo(center: FloatArray, halfExtent: FloatArray, labels: List<PartLabel>) {
        val radius = sqrt(
            halfExtent[0] * halfExtent[0] + halfExtent[1] * halfExtent[1] + halfExtent[2] * halfExtent[2]
        ).coerceAtLeast(1e-4f)
        normScale = 1f / radius
        cx = center[0]; cy = center[1]; cz = center[2]

        labelText = Array(labels.size) { labels[it].text }
        labelPos = FloatArray(labels.size * 3)
        for (i in labels.indices) {
            labelPos[i * 3] = (labels[i].x - cx) * normScale
            labelPos[i * 3 + 1] = (labels[i].y - cy) * normScale
            labelPos[i * 3 + 2] = (labels[i].z - cz) * normScale
        }
        labelScreen = FloatArray(labels.size * 2)
        labelOnScreen = BooleanArray(labels.size)
    }

    private fun updateRotation() {
        val ya = Math.toRadians(yaw.toDouble())
        val pa = Math.toRadians(pitch.toDouble())
        val cyw = cos(ya).toFloat(); val syw = sin(ya).toFloat()
        val cp = cos(pa).toFloat(); val sp = sin(pa).toFloat()
        // R = Rx(pitch) * Ry(yaw)
        r00 = cyw;        r01 = 0f; r02 = syw
        r10 = sp * syw;   r11 = cp; r12 = -sp * cyw
        r20 = -cp * syw;  r21 = sp; r22 = cp * cyw
    }

    /**
     * Pushes rectangle, camera, model transform and label positions into Filament.
     * [scale] = surface pixels / overlay pixels.
     */
    fun update(tm: TransformManager, scale: Float, surfaceHeight: Int) {
        // --- viewport (Filament origin is bottom-left) ---
        val vpSize = (size * scale).roundToInt().coerceAtLeast(1)
        val vpLeft = (left * scale).roundToInt()
        val vpBottom = (surfaceHeight - (top * scale).roundToInt() - vpSize).coerceAtLeast(0)
        view.viewport = Viewport(vpLeft, vpBottom, vpSize, vpSize)

        updateRotation()

        // --- camera: fixed on +Z looking at origin, zoom = dolly ---
        val dist = BASE_DIST / zoom
        camera.setProjection(FOV_DEG, 1.0, NEAR, FAR, Camera.Fov.VERTICAL)
        camera.lookAt(0.0, 0.0, dist.toDouble(), 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        // --- model root: M = R * S * T(-center) ---
        asset?.let { a ->
            val s = normScale
            val m = rootMatrix
            m[0] = s * r00; m[1] = s * r10; m[2] = s * r20; m[3] = 0f
            m[4] = s * r01; m[5] = s * r11; m[6] = s * r21; m[7] = 0f
            m[8] = s * r02; m[9] = s * r12; m[10] = s * r22; m[11] = 0f
            m[12] = -s * (r00 * cx + r01 * cy + r02 * cz)
            m[13] = -s * (r10 * cx + r11 * cy + r12 * cz)
            m[14] = -s * (r20 * cx + r21 * cy + r22 * cz)
            m[15] = 1f
            tm.setTransform(tm.getInstance(a.root), m)
        }

        projectLabels(dist)
    }

    /** Anchor (model space) -> rotate -> perspective project -> overlay pixels. Zero allocation. */
    private fun projectLabels(dist: Float) {
        for (i in 0 until labelCount) {
            val x = labelPos[i * 3]; val y = labelPos[i * 3 + 1]; val z = labelPos[i * 3 + 2]
            val rx = r00 * x + r01 * y + r02 * z
            val ry = r10 * x + r11 * y + r12 * z
            val rz = r20 * x + r21 * y + r22 * z
            val zv = rz - dist                       // view space, camera looks down -Z
            if (zv > -NEAR.toFloat()) { labelOnScreen[i] = false; continue }
            val inv = 1f / (-zv * TAN_HALF_FOV)
            val nx = rx * inv
            val ny = ry * inv
            labelOnScreen[i] = abs(nx) <= 1.02f && abs(ny) <= 1.02f
            labelScreen[i * 2] = left + (nx * 0.5f + 0.5f) * size
            labelScreen[i * 2 + 1] = top + (0.5f - ny * 0.5f) * size
        }
    }
}
