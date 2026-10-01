package com.example.a3dmodelviewer.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.example.a3dmodelviewer.render.ModelContainer
import com.example.a3dmodelviewer.render.ModelSceneHost

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