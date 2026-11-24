package fr.elvis.roll35

/**
 * Une pellicule = une liste de réglages, repris de la fiche de l'étape 1.
 * Toutes les valeurs sont des points de départ à retoucher à l'œil.
 *
 * Échelles : contraste / ombres / hautes lumières de -100 à +100 (0 = neutre),
 * saturation en multiplicateur (1 = inchangé), teintes en degrés
 * (0 rouge, 60 jaune, 120 vert, 180 cyan, 240 bleu, 300 magenta).
 */
data class Film(
    val id: String,
    val name: String,
    val group: Group,
    val saturation: Float = 1f,
    /** Saturation dans les tons sombres, si différente (Négatif classique). */
    val darkSaturation: Float? = null,
    val contrast: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val shadowTint: Tint? = null,
    val highlightTint: Tint? = null,
    val hues: List<HueShift> = emptyList(),
    /** Décalage de balance : négatif = plus bleu, positif = plus chaud. */
    val wbShift: Float = 0f,
    /** Noir et blanc : poids des canaux rouge / vert / bleu. */
    val mono: Rgb? = null,
    /** Dégradé appliqué au gris (sépia, redscale), du noir vers le blanc. */
    val gradient: List<Rgb>? = null,
    /** Grain : 0 = aucun, 0,03 = faible, 0,06 = fort. */
    val grain: Float = 0f,
    val grainLarge: Boolean = false,
    /** Halo rouge autour des lumières fortes, de 0 à 1. */
    val halation: Float = 0f,
) {
    enum class Group { SIMULATION, PELLICULE }
}

data class Tint(val hue: Float, val strength: Float)

/** Agit sur les couleurs proches de [hue] : décale la teinte et/ou change la saturation. */
data class HueShift(
    val hue: Float,
    val shift: Float = 0f,
    val sat: Float = 1f,
    val width: Float = 30f,
)

data class Rgb(val r: Float, val g: Float, val b: Float)

object FilmLibrary {

    private val NEUTRAL = Rgb(0.30f, 0.59f, 0.11f)
    private val YELLOW = Rgb(0.40f, 0.52f, 0.08f)
    private val RED = Rgb(0.62f, 0.33f, 0.05f)
    private val GREEN = Rgb(0.22f, 0.70f, 0.08f)

    private const val ACROS_GRAIN = 0.025f

    private val S = Film.Group.SIMULATION
    private val P = Film.Group.PELLICULE

    val all: List<Film> = listOf(
        // --- Les 20 simulations du X-E5 ---
        Film("standard", "Standard", S, contrast = 10f),
        Film(
            "vif", "Vif", S,
            saturation = 1.4f, contrast = 30f, shadows = -15f, highlights = -5f,
            hues = listOf(
                HueShift(220f, sat = 1.3f),
                HueShift(120f, sat = 1.3f),
                HueShift(0f, shift = -4f),
            ),
        ),
        Film(
            "doux", "Doux", S,
            saturation = 1.1f, contrast = -15f, shadows = 10f, highlights = 5f,
            hues = listOf(
                HueShift(220f, sat = 1.15f),
                HueShift(120f, sat = 1.15f),
                HueShift(25f, sat = 0.91f), // peau protégée de la saturation globale
            ),
        ),
        Film(
            "chrome", "Chrome classique", S,
            saturation = 0.7f, contrast = 15f, shadows = -15f, highlights = 5f,
            shadowTint = Tint(30f, 8f),
            hues = listOf(
                HueShift(220f, shift = -8f, sat = 0.7f),
                HueShift(0f, shift = 6f),
                HueShift(120f, shift = -10f, sat = 0.7f),
            ),
        ),
        Film("realiste", "Réaliste", S, saturation = 1.05f, contrast = 20f, shadows = -10f, highlights = -5f),
        Film(
            "studio_contraste", "Studio contrasté", S,
            saturation = 0.9f, contrast = 10f, shadows = -5f,
            hues = listOf(HueShift(25f, shift = 3f)),
        ),
        Film(
            "studio_doux", "Studio doux", S,
            saturation = 0.8f, contrast = -20f, shadows = 10f, highlights = 5f,
            hues = listOf(HueShift(120f, shift = 5f)),
        ),
        Film(
            "neg_classique", "Négatif classique", S,
            saturation = 0.9f, darkSaturation = 0.75f, contrast = 25f, shadows = -15f, highlights = -10f,
            shadowTint = Tint(170f, 10f), highlightTint = Tint(40f, 8f),
            hues = listOf(
                HueShift(0f, shift = -6f),
                HueShift(55f, shift = -3f, sat = 1.1f),
                HueShift(120f, shift = 10f),
                HueShift(220f, shift = -6f),
            ),
        ),
        Film(
            "neg_nostalgique", "Négatif nostalgique", S,
            saturation = 1.05f, contrast = -5f, shadows = 10f, highlights = -5f,
            shadowTint = Tint(0f, 5f), highlightTint = Tint(40f, 15f),
            hues = listOf(
                HueShift(45f, sat = 1.1f, width = 25f),
                HueShift(220f, sat = 0.85f),
            ),
        ),
        Film(
            "cinema", "Cinéma", S,
            saturation = 0.6f, contrast = -35f, shadows = 20f, highlights = 10f,
            shadowTint = Tint(185f, 6f),
            hues = listOf(
                HueShift(0f, sat = 0.7f),
                HueShift(30f, sat = 0.7f),
                HueShift(220f, shift = -5f),
            ),
        ),
        Film(
            "sans_blanchiment", "Sans blanchiment", S,
            saturation = 0.4f, contrast = 50f, shadows = -25f, highlights = -10f,
            shadowTint = Tint(215f, 5f),
        ),
        Film("nb_fin", "N&B fin", S, mono = NEUTRAL, contrast = 20f, shadows = -10f, highlights = -5f, grain = ACROS_GRAIN),
        Film("nb_fin_jaune", "N&B fin + jaune", S, mono = YELLOW, contrast = 20f, shadows = -10f, highlights = -5f, grain = ACROS_GRAIN),
        Film("nb_fin_rouge", "N&B fin + rouge", S, mono = RED, contrast = 25f, shadows = -15f, highlights = -5f, grain = ACROS_GRAIN),
        Film("nb_fin_vert", "N&B fin + vert", S, mono = GREEN, contrast = 20f, shadows = -10f, highlights = -5f, grain = ACROS_GRAIN),
        Film("nb", "N&B", S, mono = NEUTRAL, contrast = 5f),
        Film("nb_jaune", "N&B + jaune", S, mono = YELLOW, contrast = 5f),
        Film("nb_rouge", "N&B + rouge", S, mono = RED, contrast = 10f, shadows = -5f),
        Film("nb_vert", "N&B + vert", S, mono = GREEN, contrast = 5f),
        Film(
            "sepia", "Sépia", S,
            mono = NEUTRAL, shadows = 5f, highlights = -5f,
            gradient = listOf(Rgb(0.20f, 0.13f, 0.08f), Rgb(1.00f, 0.94f, 0.82f)),
        ),

        // --- Pellicules en plus : CineStill et Pro 400H ---
        Film(
            "nuit_tungstene", "Nuit tungstène", P,
            saturation = 1.05f, contrast = 10f, shadows = -10f,
            shadowTint = Tint(185f, 12f), highlightTint = Tint(40f, 6f),
            wbShift = -6f,
            hues = listOf(HueShift(0f, sat = 1.1f), HueShift(120f, shift = 8f)),
            grain = 0.06f, grainLarge = true, halation = 0.6f,
        ),
        Film(
            "jour_polyvalent", "Jour polyvalent", P,
            saturation = 0.95f, contrast = -10f, shadows = 5f, highlights = 5f,
            highlightTint = Tint(40f, 6f),
            hues = listOf(HueShift(20f, shift = 3f)),
            grain = 0.03f, halation = 0.3f,
        ),
        Film(
            "jour_fin", "Jour fin", P,
            saturation = 1.05f, contrast = 5f,
            grain = 0.015f, halation = 0.1f,
        ),
        Film("nb_cine", "N&B ciné", P, mono = NEUTRAL, contrast = 15f, shadows = -15f, grain = 0.03f),
        Film(
            "redscale", "Redscale", P,
            mono = NEUTRAL, contrast = 10f,
            gradient = listOf(
                Rgb(0.25f, 0.03f, 0.01f),
                Rgb(0.85f, 0.35f, 0.08f),
                Rgb(1.00f, 0.90f, 0.60f),
            ),
            grain = 0.06f, grainLarge = true,
        ),
        Film(
            "pastel_pro", "Pastel pro", P,
            saturation = 0.85f, contrast = -15f, shadows = 10f, highlights = 10f,
            shadowTint = Tint(170f, 8f), highlightTint = Tint(210f, 4f),
            hues = listOf(
                HueShift(120f, shift = 8f, sat = 0.8f),
                HueShift(220f, sat = 0.8f),
                HueShift(20f, shift = -2f),
            ),
            grain = 0.03f,
        ),

        // --- Kodak ---
        // UltraMax 400 : chaude, contrastée, couleurs franches (rouges vifs,
        // verts tirant vers le jaune), ombres un peu jaune-vert, grain présent.
        Film(
            "grand_public_400", "Grand public 400", P,
            saturation = 1.12f, contrast = 15f, shadows = -5f,
            shadowTint = Tint(80f, 6f), highlightTint = Tint(40f, 8f),
            wbShift = 6f,
            hues = listOf(
                HueShift(0f, sat = 1.15f),
                HueShift(120f, shift = -8f),
                HueShift(220f, sat = 1.05f),
            ),
            grain = 0.045f,
        ),
    )

    fun byId(id: String?): Film? = all.firstOrNull { it.id == id }
}
