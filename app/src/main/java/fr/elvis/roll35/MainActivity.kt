package fr.elvis.roll35

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.elvis.roll35.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var filmCamera: FilmCamera

    /** Ordre du sélecteur : « Sans filtre » puis toutes les pellicules. */
    private val choices: List<Film?> = listOf<Film?>(null) + FilmLibrary.all
    private val chips = mutableListOf<TextView>()
    private var currentIndex = 0
    private val zoomPresets = floatArrayOf(0.6f, 1f, 2f, 3f, 5f, 10f)
    private val zoomChips = mutableListOf<Pair<Float, TextView>>()
    private var zoomRange = 0f to 0f
    private var zoomTarget = 1f
    private val prefs by lazy { getSharedPreferences("roll35", Context.MODE_PRIVATE) }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionPanel(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Garde l'interface hors de la barre d'état et de la barre de navigation.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        filmCamera = FilmCamera(this, this, binding.viewFinder)

        binding.shutter.setOnClickListener { takePhoto() }
        binding.switchCamera.setOnClickListener { filmCamera.switchLens() }
        binding.grantPermission.setOnClickListener {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
        setupFilmPicker()
        setupFraming()
        filmCamera.onZoomChanged = ::showZoom
        filmCamera.onCameraBound = { closeValueStrip(); showExposure() }
        binding.isoButton.setOnClickListener { toggleValueStrip(Setting.ISO) }
        binding.speedButton.setOnClickListener { toggleValueStrip(Setting.SPEED) }
        setupViewfinderGestures()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        showPermissionPanel(false)
        filmCamera.start(
            onReady = {
                binding.switchCamera.visibility =
                    if (filmCamera.canSwitchLens()) View.VISIBLE else View.INVISIBLE
            },
            onError = {
                Toast.makeText(this, R.string.camera_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun showPermissionPanel(show: Boolean) {
        binding.permissionPanel.visibility = if (show) View.VISIBLE else View.GONE
        binding.controls.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }

    private fun takePhoto() {
        binding.shutter.isEnabled = false
        flashEffect()
        filmCamera.takePhoto(
            onSaved = {
                binding.shutter.isEnabled = true
                Toast.makeText(this, R.string.photo_saved, Toast.LENGTH_SHORT).show()
            },
            onError = { e ->
                binding.shutter.isEnabled = true
                Toast.makeText(
                    this,
                    getString(R.string.photo_failed, e.message ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG,
                ).show()
            },
        )
    }

    /** Petit éclair blanc sur le viseur, pour sentir le déclenchement. */
    private fun flashEffect() {
        binding.flash.animate().cancel()
        binding.flash.alpha = 0.8f
        binding.flash.animate().alpha(0f).setDuration(180).start()
    }

    // --- Sélecteur de pellicule ---

    private fun setupFilmPicker() {
        val row = binding.filmChips
        var lastGroup: Film.Group? = null
        choices.forEachIndexed { index, film ->
            if (film != null && film.group != lastGroup) {
                lastGroup = film.group
                row.addView(groupLabel(
                    if (film.group == Film.Group.SIMULATION) R.string.group_simulations
                    else R.string.group_films,
                ))
            }
            val chip = TextView(this).apply {
                text = film?.name ?: getString(R.string.film_none)
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setBackgroundResource(R.drawable.chip_film)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                gravity = Gravity.CENTER
                setOnClickListener { selectFilm(index) }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) })
            chips += chip
        }

        val saved = prefs.getString(KEY_FILM, null)
        val start = choices.indexOfFirst { it?.id == saved }.coerceAtLeast(0)
        selectFilm(start)
    }

    private fun groupLabel(res: Int) = TextView(this).apply {
        setText(res)
        setTextColor(0x99FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        isAllCaps = true
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun selectFilm(index: Int) {
        currentIndex = index.coerceIn(0, choices.lastIndex)
        val film = choices[currentIndex]
        filmCamera.film = film
        binding.filmName.text = film?.name ?: getString(R.string.film_none)
        chips.forEachIndexed { i, chip -> chip.isSelected = i == currentIndex }
        prefs.edit().putString(KEY_FILM, film?.id).apply()

        // Centre la pastille choisie dans la bande.
        val chip = chips[currentIndex]
        binding.filmStrip.post {
            val x = chip.left - (binding.filmStrip.width - chip.width) / 2
            binding.filmStrip.smoothScrollTo(x.coerceAtLeast(0), 0)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // --- ISO et vitesse ---

    private enum class Setting { ISO, SPEED }

    private var openSetting: Setting? = null

    private fun toggleValueStrip(setting: Setting) {
        if (openSetting == setting) {
            closeValueStrip()
            return
        }
        val ranges = filmCamera.manualRanges()
        if (ranges == null) {
            Toast.makeText(this, R.string.manual_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        openSetting = setting
        val (isoRange, expRange) = ranges
        val row = binding.valueChips
        row.removeAllViews()

        // Valeurs proposées : « Auto » puis celles que la caméra accepte.
        val values: List<Long?> = listOf<Long?>(null) + when (setting) {
            Setting.ISO -> ISO_VALUES.filter { it in isoRange }.map { it.toLong() }
            Setting.SPEED -> SPEEDS_NS.filter { it in expRange }
        }
        val current: Long? = when (setting) {
            Setting.ISO -> filmCamera.manualIso?.toLong()
            Setting.SPEED -> filmCamera.manualExposureNs
        }
        var selectedChip: TextView? = null
        values.forEach { value ->
            val chip = TextView(this).apply {
                text = when {
                    value == null -> getString(R.string.auto)
                    setting == Setting.ISO -> value.toString()
                    else -> formatSpeed(value)
                }
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setBackgroundResource(R.drawable.chip_film)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isSelected = value == current
                setOnClickListener { pickValue(setting, value) }
            }
            if (chip.isSelected) selectedChip = chip
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) })
        }
        binding.filmStrip.visibility = View.INVISIBLE
        binding.valueStrip.visibility = View.VISIBLE
        binding.isoButton.isSelected = setting == Setting.ISO
        binding.speedButton.isSelected = setting == Setting.SPEED
        selectedChip?.let { chip ->
            binding.valueStrip.post {
                val x = chip.left - (binding.valueStrip.width - chip.width) / 2
                binding.valueStrip.scrollTo(x.coerceAtLeast(0), 0)
            }
        }
    }

    private fun closeValueStrip() {
        openSetting = null
        binding.valueStrip.visibility = View.GONE
        binding.filmStrip.visibility = View.VISIBLE
        binding.isoButton.isSelected = false
        binding.speedButton.isSelected = false
    }

    /**
     * Applique le choix. La caméra ne règle l'un que si l'autre est fixé aussi :
     * choisir un ISO fixe la vitesse à 1/125 si elle était auto, et choisir une
     * vitesse fixe l'ISO à 400. « Auto » remet les deux en automatique.
     */
    private fun pickValue(setting: Setting, value: Long?) {
        var iso = filmCamera.manualIso
        var exp = filmCamera.manualExposureNs
        if (value == null) {
            iso = null
            exp = null
        } else if (setting == Setting.ISO) {
            iso = value.toInt()
            if (exp == null) exp = DEFAULT_SPEED_NS
        } else {
            exp = value
            if (iso == null) iso = DEFAULT_ISO
        }
        if (!filmCamera.setManualExposure(iso, exp)) {
            Toast.makeText(this, R.string.manual_unsupported, Toast.LENGTH_LONG).show()
        }
        showExposure()
        closeValueStrip()
    }

    private fun showExposure() {
        val iso = filmCamera.manualIso
        val exp = filmCamera.manualExposureNs
        binding.isoButton.text = if (iso == null) getString(R.string.iso_auto) else "ISO $iso"
        binding.speedButton.text = if (exp == null) getString(R.string.speed_auto) else formatSpeed(exp)
    }

    private fun formatSpeed(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds >= 0.95) "${Math.round(seconds)}\"" else "1/${Math.round(1 / seconds)}"
    }

    // --- Format de cadrage ---

    private fun setupFraming() {
        applyFraming(Framing.byName(prefs.getString(KEY_FRAMING, null)))
        binding.framingButton.setOnClickListener {
            val next = filmCamera.framing.next()
            applyFraming(next)
            Toast.makeText(this, getString(R.string.framing_toast, next.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFraming(framing: Framing) {
        filmCamera.framing = framing
        binding.frameMask.framing = framing
        binding.framingButton.text = framing.label
        prefs.edit().putString(KEY_FRAMING, framing.name).apply()
    }

    // --- Zoom et objectifs ---

    /** Met à jour les boutons d'objectif selon le zoom actuel. */
    private fun showZoom(ratio: Float, min: Float, max: Float) {
        if (zoomRange != (min to max)) {
            zoomRange = min to max
            buildZoomButtons(min, max)
        }
        // Le bouton actif est le dernier préréglage atteint ; il affiche le zoom exact.
        val active = zoomChips.lastOrNull { ratio >= it.first - 0.05f } ?: zoomChips.firstOrNull()
        zoomChips.forEach { (preset, chip) ->
            val isActive = chip === active?.second
            chip.isSelected = isActive
            chip.text = formatZoom(if (isActive) ratio else preset)
            chip.setTextColor(if (isActive) 0xFFE8C26A.toInt() else 0xFFFFFFFF.toInt())
        }
    }

    private fun buildZoomButtons(min: Float, max: Float) {
        val row = binding.zoomButtons
        row.removeAllViews()
        zoomChips.clear()
        val presets = zoomPresets.filter { it >= min - 0.01f && it <= max + 0.01f }.toMutableList()
        // L'ultra grand-angle n'est pas toujours à 0,6× pile : on prend le minimum réel.
        if (min < 0.95f && presets.none { it < 1f }) presets.add(0, min)
        else if (min < 0.95f) presets[0] = min
        presets.forEach { preset ->
            val chip = TextView(this).apply {
                text = formatZoom(preset)
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.zoom_button)
                setOnClickListener { filmCamera.setZoom(preset) }
            }
            row.addView(chip, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
            zoomChips += preset to chip
        }
        row.visibility = if (presets.size > 1) View.VISIBLE else View.GONE
    }

    private fun formatZoom(r: Float): String {
        val rounded = Math.round(r * 10f) / 10f
        val text = if (rounded % 1f == 0f) rounded.toInt().toString()
        else String.format(java.util.Locale.FRANCE, "%.1f", rounded)
        return text + "×"
    }

    /**
     * Viseur : toucher = mise au point ; pincer = zoom ;
     * glisser à gauche / à droite = pellicule suivante / précédente.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupViewfinderGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                zoomTarget = filmCamera.zoomRatio
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val (min, max) = zoomRange
                zoomTarget *= detector.scaleFactor
                if (max > 0f) zoomTarget = zoomTarget.coerceIn(min, max)
                filmCamera.setZoom(zoomTarget)
                return true
            }
        })
        var pinched = false
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!pinched) filmCamera.focusAt(e.x, e.y)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (pinched) return false
                if (kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY)) return false
                val next = if (velocityX < 0) currentIndex + 1 else currentIndex - 1
                if (next in choices.indices) selectFilm(next)
                return true
            }
        })
        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pinched = false
            if (event.pointerCount > 1) pinched = true
            scale.onTouchEvent(event)
            detector.onTouchEvent(event)
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        filmCamera.release()
    }

    private companion object {
        const val KEY_FILM = "film"
        const val KEY_FRAMING = "framing"

        val ISO_VALUES = intArrayOf(
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
            800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
        )

        /** Vitesses en nanosecondes, de 1/8000 s à 1 s. */
        val SPEEDS_NS: List<Long> = listOf(
            8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2,
        ).map { 1_000_000_000L / it } + 1_000_000_000L

        const val DEFAULT_ISO = 400
        const val DEFAULT_SPEED_NS = 1_000_000_000L / 125
    }
}
