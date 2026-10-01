package com.example.modelviewer.render

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.example.a3dmodelviewer.render.GlbLabelParser
import android.view.View as AndroidView
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ModelSceneHost(
    context: Context,
    private val surfaceView: SurfaceView
) : Choreographer.FrameCallback {

    companion object {
        private const val TAG = "ModelSceneHost"

        /** Upper bound of frames rendered per second. */
        private const val MAX_FPS = 60

        /** Longest side of the GL surface in pixels. Lower = less GPU fill-rate. */
        private const val MAX_SURFACE_DIM = 1600f
        private const val MAX_SURFACE_DIM_LOW_RAM = 1280f

        // Lighting (tune if models look too dark / bright)
        private const val LIGHT_LUX = 55_000f
        private const val IBL_INTENSITY = 32_000f

        private const val ROT_DEG_PER_PX = 0.4f
        private val BACKGROUND = floatArrayOf(0.035f, 0.04f, 0.055f, 1f)

        init {
            Filament.init()
            Gltfio.init()
        }
    }

    // ---------- public state read by the overlay ----------
    val containers = ArrayList<ModelContainer>()      // index 0 = bottom, last = top
    var overlay: AndroidView? = null
    var topInset = 0f
    val fps: Float get() = fpsEma

    // ---------- filament ----------
    private val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private var swapChain: SwapChain? = null
    private lateinit var environmentTexture: Texture
    private lateinit var indirectLight: IndirectLight

    // ---------- misc ----------
    private val choreographer = Choreographer.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice

    private var running = false
    private var destroyed = false
    private var dirty = true
    private var nextId = 1

    private var viewW = 0
    private var viewH = 0
    private var surfW = 0
    private var surfH = 0

    private val minFrameIntervalNanos = 1_000_000_000L / MAX_FPS - 2_000_000L
    private var lastRenderNanos = 0L
    private var fpsEma = 0f

    private val minSize get() = 140f * density
    private val maxSize get() = min(viewW, viewH).toFloat()

    init {
        renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = BACKGROUND
        }
        createEnvironment()

        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                dirty = true
            }

            override fun onDetachedFromSurface() {
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                    swapChain = null
                }
            }

            override fun onResized(width: Int, height: Int) {
                surfW = width
                surfH = height
                dirty = true
            }
        }
        uiHelper.attachTo(surfaceView)
    }

    // =====================================================================
    // lifecycle
    // =====================================================================

    fun resume() {
        if (destroyed || running) return
        running = true
        dirty = true
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    fun destroy() {
        if (destroyed) return
        pause()
        destroyed = true
        io.shutdownNow()

        for (c in containers.toList()) destroyContainer(c)
        containers.clear()

        uiHelper.detach()   // triggers onDetachedFromSurface -> swapchain destroyed
        swapChain?.let { engine.destroySwapChain(it); swapChain = null }

        engine.destroyIndirectLight(indirectLight)
        engine.destroyTexture(environmentTexture)

        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()

        engine.destroyRenderer(renderer)
        engine.destroy()
    }

    // =====================================================================
    // sizing
    // =====================================================================

    /** Called by the overlay view when its size is known. */
    fun onViewSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        viewW = w
        viewH = h
        val limit = if (lowRam) MAX_SURFACE_DIM_LOW_RAM else MAX_SURFACE_DIM
        val scale = min(1f, limit / max(w, h).toFloat())
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        surfW = bw
        surfH = bh
        surfaceView.holder.setFixedSize(bw, bh)
        for (c in containers) clampInside(c)
        requestRender()
    }

    private fun clampInside(c: ModelContainer) {
        c.size = clampf(c.size, min(minSize, maxSize), maxSize)
        c.left = clampf(c.left, 0f, viewW - c.size)
        c.top = clampf(c.top, 0f, viewH - c.size)
    }

    private fun clampf(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))

    // =====================================================================
    // model management
    // =====================================================================

    fun addModel(assetPath: String, title: String) {
        if (destroyed || viewW == 0) return
        val c = ModelContainer(nextId++, title)

        val idx = containers.size
        c.size = min(viewW, viewH) * 0.46f
        val gap = 12f * density
        c.left = gap + (idx % 2) * (c.size + gap)
        c.top = topInset + gap + (idx / 2) * (c.size * 0.62f)
        clampInside(c)

        createFilamentObjects(c)
        containers.add(c)
        requestRender()

        io.execute {
            try {
                val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                buffer.put(bytes)
                buffer.rewind()
                val labels = GlbLabelParser.parse(buffer)
                mainHandler.post { onModelBytes(c, buffer, labels) }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to read $assetPath", t)
                mainHandler.post { markFailed(c) }
            }
        }
    }

    private fun markFailed(c: ModelContainer) {
        if (c.closed || destroyed) return
        c.state = ModelContainer.State.FAILED
        requestRender()
    }

    private fun onModelBytes(c: ModelContainer, buffer: ByteBuffer, labels: List<PartLabel>) {
        if (c.closed || destroyed) return
        val asset = assetLoader.createAsset(buffer)
        if (asset == null) {
            markFailed(c)
            return
        }
        val box = asset.boundingBox
        c.asset = asset
        c.setModelInfo(box.center, box.halfExtent, labels)
        c.pendingBuffer = buffer

        val rl = ResourceLoader(engine)
        rl.asyncBeginLoad(asset)
        c.loader = rl
        requestRender()
    }

    private fun createFilamentObjects(c: ModelContainer) {
        val em = EntityManager.get()

        c.scene = engine.createScene()
        c.view = engine.createView().also { v ->
            v.scene = c.scene
            configureForLowEnd(v)
        }

        c.cameraEntity = em.create()
        c.camera = engine.createCamera(c.cameraEntity)
        c.view.camera = c.camera

        c.skybox = Skybox.Builder().color(0.12f, 0.135f, 0.17f, 1f).build(engine)
        c.scene.skybox = c.skybox
        c.scene.indirectLight = indirectLight

        c.lightEntity = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 1f, 1f)
            .intensity(LIGHT_LUX)
            .direction(0.3f, -0.6f, -0.75f)
            .castShadows(false)
            .build(engine, c.lightEntity)
        c.scene.addEntity(c.lightEntity)
    }

    /** Disable everything that costs GPU time and isn't needed for this task. */
    private fun configureForLowEnd(v: View) {
        v.setAntiAliasing(View.AntiAliasing.NONE)       // no FXAA pass
        v.setDithering(View.Dithering.NONE)
        v.setShadowingEnabled(false)
        v.renderQuality = View.RenderQuality().apply {
            hdrColorBuffer = View.QualityLevel.LOW      // R11G11B10 instead of RGBA16F
        }
        // MSAA, bloom, SSAO, DoF, TAA, fog are already off by default.
    }

    /** Releases every GPU / native resource of one container. */
    private fun destroyContainer(c: ModelContainer) {
        c.closed = true
        val em = EntityManager.get()

        c.loader?.let {
            it.asyncCancelLoad()
            it.destroy()
        }
        c.loader = null
        c.pendingBuffer = null

        c.asset?.let { a ->
            c.scene.removeEntities(a.entities)
            assetLoader.destroyAsset(a)
        }
        c.asset = null

        c.scene.removeEntity(c.lightEntity)
        engine.lightManager.destroy(c.lightEntity)
        em.destroy(c.lightEntity)

        c.scene.skybox = null
        c.skybox?.let { engine.destroySkybox(it) }
        c.skybox = null
        c.scene.indirectLight = null

        engine.destroyView(c.view)
        engine.destroyScene(c.scene)
        engine.destroyCameraComponent(c.cameraEntity)
        em.destroy(c.cameraEntity)
    }

    // =====================================================================
    // actions coming from the overlay
    // =====================================================================

    fun hitTest(x: Float, y: Float): ModelContainer? {
        for (i in containers.indices.reversed()) {
            val c = containers[i]
            if (x >= c.left && x <= c.left + c.size && y >= c.top && y <= c.top + c.size) return c
        }
        return null
    }

    fun bringToFront(c: ModelContainer) {
        if (containers.lastOrNull() === c) return
        containers.remove(c)
        containers.add(c)
        requestRender()
    }

    fun moveBy(c: ModelContainer, dx: Float, dy: Float) {
        c.left = clampf(c.left + dx, 0f, viewW - c.size)
        c.top = clampf(c.top + dy, 0f, viewH - c.size)
        requestRender()
    }

    fun resizeBy(c: ModelContainer, factor: Float) {
        val newSize = clampf(c.size * factor, min(minSize, maxSize), maxSize)
        val midX = c.left + c.size / 2
        val midY = c.top + c.size / 2
        c.size = newSize
        c.left = clampf(midX - newSize / 2, 0f, viewW - newSize)
        c.top = clampf(midY - newSize / 2, 0f, viewH - newSize)
        requestRender()
    }

    fun rotateBy(c: ModelContainer, dxPx: Float, dyPx: Float) {
        c.yaw = (c.yaw + dxPx * ROT_DEG_PER_PX) % 360f
        c.pitch = clampf(c.pitch + dyPx * ROT_DEG_PER_PX, -89f, 89f)
        requestRender()
    }

    fun zoomBy(c: ModelContainer, factor: Float) {
        c.zoom = clampf(c.zoom * factor, ModelContainer.MIN_ZOOM, ModelContainer.MAX_ZOOM)
        requestRender()
    }

    fun toggleInteraction(c: ModelContainer) {
        c.interactionMode = !c.interactionMode
        overlay?.invalidate()
    }

    fun toggleLabels(c: ModelContainer) {
        c.labelsVisible = !c.labelsVisible
        overlay?.invalidate()
    }

    fun close(c: ModelContainer) {
        if (!containers.remove(c)) return
        destroyContainer(c)
        requestRender()
    }

    fun requestRender() {
        dirty = true
    }

    // =====================================================================
    // frame loop
    // =====================================================================

    override fun doFrame(frameTimeNanos: Long) {
        if (!running || destroyed) return
        choreographer.postFrameCallback(this)

        val loading = advanceLoads()
        if (loading) dirty = true
        if (!dirty) return

        val sc = swapChain ?: return
        if (surfW == 0 || viewW == 0) return
        if (frameTimeNanos - lastRenderNanos < minFrameIntervalNanos) return

        val scale = surfW.toFloat() / viewW
        val tm = engine.transformManager
        for (i in containers.indices) containers[i].update(tm, scale, surfH)

        if (renderer.beginFrame(sc, frameTimeNanos)) {
            for (i in containers.indices) renderer.render(containers[i].view)
            renderer.endFrame()

            val delta = frameTimeNanos - lastRenderNanos
            if (delta in 1..200_000_000L) {
                val inst = 1_000_000_000f / delta
                fpsEma = if (fpsEma == 0f) inst else fpsEma * 0.9f + inst * 0.1f
            }
            lastRenderNanos = frameTimeNanos
            dirty = false
            overlay?.invalidate()
        }
    }

    /** Pumps async texture loading. Returns true while any model is still loading. */
    private fun advanceLoads(): Boolean {
        var stillLoading = false
        for (i in containers.indices) {
            val c = containers[i]
            if (c.state != ModelContainer.State.LOADING) continue
            stillLoading = true
            val rl = c.loader ?: continue
            rl.asyncUpdateLoad()
            c.progress = rl.asyncGetLoadProgress()
            if (c.progress >= 1f) finishLoad(c, rl)
        }
        return stillLoading
    }

    private fun finishLoad(c: ModelContainer, rl: ResourceLoader) {
        val a = c.asset ?: return
        c.scene.addEntities(a.entities)
        a.releaseSourceData()
        rl.destroy()
        c.loader = null
        c.pendingBuffer = null
        c.progress = 1f
        c.state = ModelContainer.State.READY
        dirty = true
    }

    // =====================================================================
    // procedural environment (no KTX file needed)
    // =====================================================================

    /**
     * Tiny 16x16 cube map with a sky -> ground gradient, used as reflection source so metallic
     * parts don't render black, plus a 2-band SH for diffuse ambient.
     */
    private fun createEnvironment() {
        val size = 16
        val levels = 5
        environmentTexture = Texture.Builder()
            .width(size).height(size).levels(levels)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.RGBA8)
            .build(engine)

        for (level in 0 until levels) {
            val s = size shr level
            val faceBytes = s * s * 4
            val buf = ByteBuffer.allocateDirect(faceBytes * 6)
            for (face in 0 until 6) {
                for (y in 0 until s) for (x in 0 until s) {
                    val u = 2f * (x + 0.5f) / s - 1f
                    val v = 2f * (y + 0.5f) / s - 1f
                    val yRaw = when (face) { 2 -> 1f; 3 -> -1f; else -> -v }
                    val len = sqrt(1f + u * u + v * v)
                    val t = (yRaw / len) * 0.5f + 0.5f            // 0 = down, 1 = up
                    val sm = t * t * (3f - 2f * t)
                    val lum = 0.12f + (1.0f - 0.12f) * sm
                    buf.put((lum * 0.95f * 255f).toInt().coerceIn(0, 255).toByte())
                    buf.put((lum * 255f).toInt().coerceIn(0, 255).toByte())
                    buf.put((lum * 1.05f * 255f).toInt().coerceIn(0, 255).toByte())
                    buf.put(255.toByte())
                }
            }
            buf.rewind()
            environmentTexture.setImage(
                engine, level,
                Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE),
                IntArray(6) { it * faceBytes }
            )
        }

        val sh = floatArrayOf(
            0.60f, 0.62f, 0.66f,   // L00
            0.28f, 0.28f, 0.30f,   // y (sky brighter than ground)
            0f, 0f, 0f,            // z
            0f, 0f, 0f             // x
        )
        indirectLight = IndirectLight.Builder()
            .reflections(environmentTexture)
            .irradiance(2, sh)
            .intensity(IBL_INTENSITY)
            .build(engine)
    }
}
