package fr.elvis.roll35

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Formats de cadrage. [long] / [short] = rapport grand côté / petit côté.
 * L'image vient toujours du capteur entier (4:3), puis elle est recadrée au centre.
 */
enum class Framing(val label: String, val long: Float, val short: Float) {
    F4_3("4:3", 4f, 3f),
    F3_2("3:2", 3f, 2f),
    F5_4("5:4", 5f, 4f),
    F1_1("1:1", 1f, 1f),
    F16_9("16:9", 16f, 9f),
    XPAN("XPan", 65f, 24f);

    val ratio: Float get() = long / short

    /** Rectangle centré de ce format qui tient dans [w]×[h], grand côté dans le même sens. */
    fun fit(w: Float, h: Float): RectF {
        val portrait = h >= w
        // Rapport largeur / hauteur voulu.
        val target = if (portrait) short / long else long / short
        var cw = w
        var ch = w / target
        if (ch > h) {
            ch = h
            cw = h * target
        }
        val l = (w - cw) / 2f
        val t = (h - ch) / 2f
        return RectF(l, t, l + cw, t + ch)
    }

    fun next(): Framing = entries[(ordinal + 1) % entries.size]

    companion object {
        fun byName(name: String?): Framing = entries.firstOrNull { it.name == name } ?: F4_3
    }
}

/** Masque noir posé sur le viseur pour montrer le cadre choisi. */
class FrameMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var framing: Framing = Framing.F4_3
        set(value) {
            field = value
            invalidate()
        }

    private val mask = Paint().apply { color = 0xF0000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = framing.fit(w, h)
        if (r.width() >= w - 1f && r.height() >= h - 1f) return
        canvas.drawRect(0f, 0f, w, r.top, mask)
        canvas.drawRect(0f, r.bottom, w, h, mask)
        canvas.drawRect(0f, r.top, r.left, r.bottom, mask)
        canvas.drawRect(r.right, r.top, w, r.bottom, mask)
        canvas.drawRect(r, line)
    }
}
