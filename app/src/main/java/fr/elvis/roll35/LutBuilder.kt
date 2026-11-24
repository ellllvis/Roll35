package fr.elvis.roll35

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

/**
 * Fabrique la table de couleurs 3D (LUT 33×33×33) d'une pellicule.
 * Le processeur graphique s'en sert ensuite pour transformer chaque pixel
 * en une seule lecture. Ordre : balance, couleurs par teinte, saturation,
 * courbe de tons, virage, dégradé (sépia / redscale).
 */
object LutBuilder {
    const val SIZE = 33

    /** Table rangée rouge d'abord, puis vert, puis bleu (format OpenGL). */
    fun build(film: Film?): FloatArray {
        val n = SIZE
        val out = FloatArray(n * n * n * 3)
        val c = FloatArray(3)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            c[0] = r / (n - 1f)
            c[1] = g / (n - 1f)
            c[2] = b / (n - 1f)
            if (film != null) develop(film, c)
            out[i++] = c[0]
            out[i++] = c[1]
            out[i++] = c[2]
        }
        return out
    }

    fun develop(f: Film, c: FloatArray) {
        if (f.wbShift != 0f) {
            val k = 0.006f * f.wbShift
            c[0] *= 1f + k
            c[2] *= 1f - k
        }

        val mono = f.mono
        if (mono != null) {
            val y = c[0] * mono.r + c[1] * mono.g + c[2] * mono.b
            c[0] = y; c[1] = y; c[2] = y
        } else {
            if (f.hues.isNotEmpty()) adjustHues(f.hues, c)
            saturate(f, c)
        }

        for (k in 0..2) c[k] = tone(c[k], f.contrast, f.shadows, f.highlights)

        f.shadowTint?.let { addTint(c, it) { y -> 1f - smoothstep(0f, 0.55f, y) } }
        f.highlightTint?.let { addTint(c, it) { y -> smoothstep(0.45f, 1f, y) } }

        f.gradient?.let { mapGradient(c, it) }

        for (k in 0..2) c[k] = c[k].coerceIn(0f, 1f)
    }

    private fun luma(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Courbe de tons d'une composante. */
    private fun tone(x: Float, contrast: Float, shadows: Float, highlights: Float): Float {
        var v = x.coerceIn(0f, 1f)
        // Contraste : courbe en S autour du gris moyen.
        val s = v * v * (3f - 2f * v)
        v += contrast / 100f * 1.2f * (s - v)
        // Ombres : bosse centrée vers 1/3, noirs et blancs restent en place.
        val sh = 6.75f * v * (1f - v) * (1f - v)
        v += shadows / 100f * 0.25f * sh
        // Hautes lumières : bosse centrée vers 2/3.
        val hl = 6.75f * v * v * (1f - v)
        v += highlights / 100f * 0.25f * hl
        return v.coerceIn(0f, 1f)
    }

    private fun saturate(f: Film, c: FloatArray) {
        val y = luma(c)
        val dark = f.darkSaturation ?: f.saturation
        val s = dark + (f.saturation - dark) * smoothstep(0.1f, 0.5f, y)
        if (s == 1f) return
        for (k in 0..2) c[k] = (y + (c[k] - y) * s).coerceIn(0f, 1f)
    }

    private fun adjustHues(list: List<HueShift>, c: FloatArray) {
        val hsv = FloatArray(3)
        rgbToHsv(c, hsv)
        if (hsv[1] < 1e-4f) return
        var shift = 0f
        var sat = 1f
        for (h in list) {
            var d = abs(hsv[0] - h.hue) % 360f
            if (d > 180f) d = 360f - d
            val q = d / h.width
            val w = exp(-q * q)
            shift += h.shift * w
            sat *= 1f + (h.sat - 1f) * w
        }
        hsv[0] = ((hsv[0] + shift) % 360f + 360f) % 360f
        hsv[1] = (hsv[1] * sat).coerceIn(0f, 1f)
        hsvToRgb(hsv, c)
    }

    private inline fun addTint(c: FloatArray, tint: Tint, weight: (Float) -> Float) {
        val w = weight(luma(c)) * tint.strength / 100f * 0.6f
        if (w <= 0f) return
        val t = FloatArray(3)
        hsvToRgb(floatArrayOf(tint.hue, 1f, 1f), t)
        val ty = luma(t)
        for (k in 0..2) c[k] += (t[k] - ty) * w
    }

    private fun mapGradient(c: FloatArray, stops: List<Rgb>) {
        val y = luma(c).coerceIn(0f, 1f)
        val pos = y * (stops.size - 1)
        val i = floor(pos).toInt().coerceIn(0, stops.size - 2)
        val t = pos - i
        val a = stops[i]
        val b = stops[i + 1]
        c[0] = a.r + (b.r - a.r) * t
        c[1] = a.g + (b.g - a.g) * t
        c[2] = a.b + (b.b - a.b) * t
    }

    private fun rgbToHsv(c: FloatArray, out: FloatArray) {
        val r = c[0]; val g = c[1]; val b = c[2]
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d < 1e-6f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        out[0] = (h + 360f) % 360f
        out[1] = if (max <= 0f) 0f else d / max
        out[2] = max
    }

    private fun hsvToRgb(hsv: FloatArray, out: FloatArray) {
        val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
        val cc = v * s
        val x = cc * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - cc
        val (r, g, b) = when {
            h < 60f -> Triple(cc, x, 0f)
            h < 120f -> Triple(x, cc, 0f)
            h < 180f -> Triple(0f, cc, x)
            h < 240f -> Triple(0f, x, cc)
            h < 300f -> Triple(x, 0f, cc)
            else -> Triple(cc, 0f, x)
        }
        out[0] = r + m
        out[1] = g + m
        out[2] = b + m
    }
}
