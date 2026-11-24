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
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import fr.elvis.roll35.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var filmCamera: FilmCamera

    /** Ordre du sélecteur : « Sans filtre » puis toutes les pellicules. */
    private val choices: List<Film?> = listOf<Film?>(null) + FilmLibrary.all
    private val chips = mutableListOf<TextView>()
    private var currentIndex = 0
    private val prefs by lazy { getSharedPreferences("roll35", Context.MODE_PRIVATE) }

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showPermissionPanel(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filmCamera = FilmCamera(this, this, binding.viewFinder)

        binding.shutter.setOnClickListener { takePhoto() }
        binding.switchCamera.setOnClickListener { filmCamera.switchLens() }
        binding.grantPermission.setOnClickListener {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
        setupFilmPicker()
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

    /** Toucher = mise au point ; glisser à gauche / à droite = pellicule suivante / précédente. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupViewfinderGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                filmCamera.focusAt(e.x, e.y)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY)) return false
                val next = if (velocityX < 0) currentIndex + 1 else currentIndex - 1
                if (next in choices.indices) selectFilm(next)
                return true
            }
        })
        binding.viewFinder.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    override fun onDestroy() {
        super.onDestroy()
        filmCamera.release()
    }

    private companion object {
        const val KEY_FILM = "film"
    }
}
