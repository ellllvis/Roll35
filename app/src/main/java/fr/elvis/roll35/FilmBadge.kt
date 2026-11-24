package fr.elvis.roll35

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Petit badge dessiné sous le nom de chaque pellicule.
 * - Simulations : lettres blanches dans un cadre fin, à la manière de l'écran de l'appareil.
 * - Pellicules : chiffre étroit et gras sur la couleur de la boîte, avec un liseré en bas.
 */
class FilmBadge private constructor(
    private val spec: Spec,
    private val density: Float,
    private val big: Boolean,
) : Drawable() {

    enum class Style { SCREEN, BOX, NONE }

    class Spec(
        val style: Style,
        val text: String,
        val sub: String? = null,
        val subColor: Int = 0,
        val fill: Int = 0xFF000000.toInt(),
        val textColor: Int = 0xFFFFFFFF.toInt(),
        val stripe: Int = 0,
    )

    private val scale = if (big) 1.15f else 1f
    private val h = 18f * density * scale
    private val padX = 6f * density * scale

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (if (spec.style == Style.BOX) 13f else 10.5f) * density * scale
        typeface = if (spec.style == Style.BOX) {
            Typeface.create("sans-serif-condensed", Typeface.BOLD)
        } else {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        color = when (spec.style) {
            Style.NONE -> 0xFF9A9A94.toInt()
            Style.SCREEN -> 0xFFFFFFFF.toInt()
            Style.BOX -> spec.textColor
        }
    }
    private val subPaint = Paint(textPaint).apply {
        textSize = textPaint.textSize * 0.72f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = spec.subColor
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val dotsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = 0x8CFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(2f * density, 2f * density), 0f)
    }

    private val textW = textPaint.measureText(spec.text)
    private val subW = spec.sub?.let { subPaint.measureText(it) + 2f * density } ?: 0f
    private val w = maxOf(34f * density * scale, textW + subW + padX * 2)

    override fun getIntrinsicWidth() = w.toInt()
    override fun getIntrinsicHeight() = h.toInt()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = RectF(b.left.toFloat(), b.top.toFloat(), b.left + w, b.top + h)
        val half = strokePaint.strokeWidth / 2
        when (spec.style) {
            Style.NONE -> {
                strokePaint.color = 0xFF9A9A94.toInt()
                r.inset(half, half)
                canvas.drawRoundRect(r, 4 * density, 4 * density, strokePaint)
            }
            Style.SCREEN -> {
                fillPaint.color = 0xFF000000.toInt()
                canvas.drawRoundRect(r, 3 * density, 3 * density, fillPaint)
                strokePaint.color = 0xFFFFFFFF.toInt()
                val o = RectF(r).apply { inset(half, half) }
                canvas.drawRoundRect(o, 3 * density, 3 * density, strokePaint)
                // Perforations en haut et en bas, comme un bout de film.
                val inset = 4f * density
                val dy = 2.6f * density * scale
                canvas.drawLine(r.left + inset, r.top + dy, r.right - inset, r.top + dy, dotsPaint)
                canvas.drawLine(r.left + inset, r.bottom - dy, r.right - inset, r.bottom - dy, dotsPaint)
            }
            Style.BOX -> {
                fillPaint.color = spec.fill
                val rad = 2 * density
                canvas.drawRoundRect(r, rad, rad, fillPaint)
                fillPaint.color = spec.stripe
                val sh = 3f * density * scale
                canvas.save()
                canvas.clipRect(r.left, r.bottom - sh, r.right, r.bottom)
                canvas.drawRoundRect(r, rad, rad, fillPaint)
                canvas.restore()
            }
        }
        val total = textW + subW
        val x = r.centerX() - total / 2
        val fm = textPaint.fontMetrics
        val lift = if (spec.style == Style.BOX) 1.2f * density * scale else 0f
        val baseline = r.centerY() - (fm.ascent + fm.descent) / 2 - lift
        canvas.drawText(spec.text, x, baseline, textPaint)
        spec.sub?.let { canvas.drawText(it, x + textW + 2f * density, baseline, subPaint) }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val YELLOW = 0xFFF2C94C.toInt()
        private const val RED = 0xFFE0453A.toInt()
        private const val GREEN = 0xFF4CAF6A.toInt()

        private fun screen(text: String, sub: String? = null, subColor: Int = 0) =
            Spec(Style.SCREEN, text, sub, subColor)

        private fun box(text: String, fill: Long, textColor: Long, stripe: Long) =
            Spec(Style.BOX, text, fill = fill.toInt(), textColor = textColor.toInt(), stripe = stripe.toInt())

        private val SPECS = mapOf(
            "standard" to screen("STD"),
            "vif" to screen("V"),
            "doux" to screen("S"),
            "chrome" to screen("CC"),
            "realiste" to screen("RA"),
            "studio_contraste" to screen("Nh"),
            "studio_doux" to screen("Ns"),
            "neg_classique" to screen("NC"),
            "neg_nostalgique" to screen("NN"),
            "cinema" to screen("E"),
            "sans_blanchiment" to screen("EB"),
            "nb_fin" to screen("A"),
            "nb_fin_jaune" to screen("A", "Ye", YELLOW),
            "nb_fin_rouge" to screen("A", "R", RED),
            "nb_fin_vert" to screen("A", "G", GREEN),
            "nb" to screen("B"),
            "nb_jaune" to screen("B", "Ye", YELLOW),
            "nb_rouge" to screen("B", "R", RED),
            "nb_vert" to screen("B", "G", GREEN),
            "sepia" to screen("SEPIA"),
            "nuit_tungstene" to box("800T", 0xFFC8102E, 0xFFFFFFFF, 0xFF14181C),
            "jour_polyvalent" to box("400D", 0xFFF08A1C, 0xFF1A1208, 0xFFFFD23F),
            "jour_fin" to box("50D", 0xFFF6D021, 0xFF123A73, 0xFF2B6CC4),
            "nb_cine" to box("BwXX", 0xFF0D0D0D, 0xFFFFFFFF, 0xFFFFFFFF),
            "redscale" to box("RED", 0xFF7A0C0C, 0xFFFFD9A8, 0xFFE2511F),
            "pastel_pro" to box("400H", 0xFFE9F3EE, 0xFF1F6B4E, 0xFF3FA37A),
        )

        private val NONE = Spec(Style.NONE, "—")

        fun forFilm(film: Film?, res: Resources, big: Boolean = false): FilmBadge {
            val spec = film?.let { SPECS[it.id] } ?: NONE
            return FilmBadge(spec, res.displayMetrics.density, big).apply {
                setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            }
        }
    }
}
