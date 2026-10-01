package com.example.a3dmodelviewer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.view.View
import androidx.compose.remote.creation.dsl.hypot
import com.example.a3dmodelviewer.render.ModelContainer
import com.example.a3dmodelviewer.render.ModelSceneHost
import kotlin.math.max
import kotlin.math.min

/**
 * Transparent view on top of the Filament SurfaceView. It
 *  1. draws the container borders, the 3 buttons, the part labels + connector lines
 *  2. owns ALL touch handling (hit-test container -> route by mode)
 *
 * Because containers are just rectangles, dragging never triggers an Android layout pass.
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayView(context: Context, private val host: ModelSceneHost) : View(context) {

    private companion object {
        const val ACCENT = 0xFF4FC3F7.toInt()
        const val BTN_INTERACT = 0
        const val BTN_LABELS = 1
        const val BTN_CLOSE = 2
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val btnW = dp(36f)
    private val btnH = dp(32f)
    private val btnGap = dp(4f)
    private val pad = dp(6f)

    // ---- paints (allocated once) ----
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color_WHITE; textAlign = Paint.Align.CENTER; textSize = dp(13f); isFakeBoldText = true
    }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color_WHITE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND
    }
    private val info = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color_WHITE; textSize = dp(11f); setShadowLayer(dp(2f), 0f, 0f, 0xFF000000.toInt())
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color_WHITE; textAlign = Paint.Align.LEFT }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC11151C.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); strokeWidth = dp(1.2f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }

    private val r = RectF()
    private val br = RectF()

    // ---- touch state ----
    private var active: ModelContainer? = null
    private var pressedButton = -1
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        host.onViewSize(w, h)
    }

    // =====================================================================
    // drawing
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        val list = host.containers
        for (i in list.indices) {
            val c = list[i]
            canvas.save()
            canvas.clipRect(c.left, c.top, c.left + c.size, c.top + c.size)
            // containers above this one hide our overlay (their 3D pixels are below us otherwise)
            for (j in i + 1 until list.size) clipOut(canvas, list[j])
            drawContainer(canvas, c)
            canvas.restore()
        }
        canvas.drawText("fps ${host.fps.toInt()}", dp(8f), host.topInset + dp(14f), info)
    }

    @Suppress("DEPRECATION")
    private fun clipOut(canvas: Canvas, c: ModelContainer) {
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipOutRect(c.left, c.top, c.left + c.size, c.top + c.size)
        } else {
            canvas.clipRect(c.left, c.top, c.left + c.size, c.top + c.size, Region.Op.DIFFERENCE)
        }
    }

    private fun drawContainer(canvas: Canvas, c: ModelContainer) {
        r.set(c.left + dp(1f), c.top + dp(1f), c.left + c.size - dp(1f), c.top + c.size - dp(1f))
        border.color = if (c.interactionMode) ACCENT else 0x66FFFFFF
        canvas.drawRoundRect(r, dp(6f), dp(6f), border)

        when (c.state) {
            ModelContainer.State.LOADING ->
                drawCentered(canvas, c, "Loading… ${(c.progress * 100).toInt()}%")
            ModelContainer.State.FAILED ->
                drawCentered(canvas, c, "Failed to load")
            ModelContainer.State.READY ->
                if (c.labelsVisible) drawLabels(canvas, c)
        }

        drawButtons(canvas, c)

        info.color = if (c.interactionMode) ACCENT else 0xCCFFFFFF.toInt()
        val caption = if (c.interactionMode) "INTERACTION: drag=rotate  pinch=zoom" else c.title
        canvas.drawText(caption, c.left + pad, c.top + c.size - pad, info)
    }

    private fun drawCentered(canvas: Canvas, c: ModelContainer, text: String) {
        info.color = 0xFFFFFFFF.toInt()
        val w = info.measureText(text)
        canvas.drawText(text, c.left + (c.size - w) / 2, c.top + c.size / 2, info)
    }

    private fun drawButtons(canvas: Canvas, c: ModelContainer) {
        for (i in 0..2) {
            buttonRect(c, i, br)
            fill.color = when {
                i == BTN_INTERACT && c.interactionMode -> ACCENT
                i == BTN_LABELS && c.labelsVisible -> ACCENT
                i == BTN_CLOSE -> 0xCC8E2A32.toInt()
                else -> 0xCC202632.toInt()
            }
            canvas.drawRoundRect(br, dp(6f), dp(6f), fill)
            val cx = br.centerX()
            val cy = br.centerY()
            when (i) {
                BTN_INTERACT -> canvas.drawText("3D", cx, cy + dp(4.5f), glyph)
                BTN_LABELS -> canvas.drawText("Aa", cx, cy + dp(4.5f), glyph)
                else -> {
                    val k = dp(5f)
                    canvas.drawLine(cx - k, cy - k, cx + k, cy + k, xPaint)
                    canvas.drawLine(cx - k, cy + k, cx + k, cy - k, xPaint)
                }
            }
        }
    }

    private fun drawLabels(canvas: Canvas, c: ModelContainer) {
        labelText.textSize = (c.size * 0.055f).coerceIn(dp(9f), dp(13f))
        val margin = dp(3f)
        val offset = dp(26f)
        val mx = c.left + c.size / 2
        val my = c.top + c.size / 2

        for (i in 0 until c.labelCount) {
            if (!c.labelOnScreen[i]) continue
            val ax = c.labelScreen[i * 2]
            val ay = c.labelScreen[i * 2 + 1]

            var dx = ax - mx
            var dy = ay - my
            var len = hypot(dx, dy)
            if (len < 1f) { dx = 0f; dy = -1f; len = 1f }

            val text = c.labelText[i]
            val tw = labelText.measureText(text)
            val th = labelText.textSize
            val bw = tw + margin * 2
            val bh = th + margin * 2

            var bl = ax + dx / len * offset - bw / 2
            var bt = ay + dy / len * offset - bh / 2
            bl = max(c.left + margin, min(bl, c.left + c.size - bw - margin))
            bt = max(c.top + margin, min(bt, c.top + c.size - bh - margin))

            // connector: nearest point of the label box to the anchor
            val px = max(bl, min(ax, bl + bw))
            val py = max(bt, min(ay, bt + bh))
            canvas.drawLine(ax, ay, px, py, line)
            canvas.drawCircle(ax, ay, dp(2.5f), dot)

            r.set(bl, bt, bl + bw, bt + bh)
            canvas.drawRoundRect(r, dp(4f), dp(4f), labelBg)
            canvas.drawText(text, bl + margin, bt + margin + th * 0.82f, labelText)
        }
    }


    // =====================================================================
    // buttons
    // =====================================================================

    private fun buttonRect(c: ModelContainer, index: Int, out: RectF) {
        val total = 3 * btnW + 2 * btnGap
        val startX = c.left + c.size - pad - total
        out.left = startX + index * (btnW + btnGap)
        out.right = out.left + btnW
        out.top = c.top + pad
        out.bottom = out.top + btnH
    }

    private fun buttonAt(c: ModelContainer, x: Float, y: Float): Int {
        val slop = dp(3f)
        for (i in 0..2) {
            buttonRect(c, i, br)
            if (x >= br.left - slop && x <= br.right + slop && y >= br.top - slop && y <= br.bottom + slop) return i
        }
        return -1
    }
}
private const val Color_WHITE = 0xFFFFFFFF.toInt()