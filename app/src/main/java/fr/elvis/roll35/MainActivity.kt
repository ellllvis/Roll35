package fr.elvis.roll35

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import fr.elvis.roll35.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var filmCamera: FilmCamera

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
        setupTapToFocus()

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
                    getString(R.string.photo_failed, e.message ?: "?"),
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        binding.viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                filmCamera.focusAt(event.x, event.y)
            }
            true
        }
    }
}
