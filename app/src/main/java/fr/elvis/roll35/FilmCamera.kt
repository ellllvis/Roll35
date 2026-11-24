package fr.elvis.roll35

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Toute la partie caméra : viseur + prise de photo, avec CameraX.
 *
 * Point d'entrée de l'étape 3 : [effect]. Un CameraEffect (traitement OpenGL)
 * ajouté au UseCaseGroup s'applique à la fois au viseur ET aux photos
 * enregistrées. Il suffira d'y brancher le shader « pellicule » (LUT 3D,
 * grain, halation) puis d'appeler [rebind].
 */
class FilmCamera(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
) {
    /** Filtre GPU appliqué au viseur et aux photos. `null` = image d'origine. */
    var effect: CameraEffect? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private val mainExecutor get() = ContextCompat.getMainExecutor(context)

    // Format 4:3 = tout le capteur du téléphone, comme un appareil photo.
    private val resolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()

    fun start(onReady: () -> Unit, onError: (Throwable) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bind()
                onReady()
            } catch (t: Throwable) {
                onError(t)
            }
        }, mainExecutor)
    }

    /** Relance la caméra (après un changement de caméra ou de filtre). */
    fun rebind() {
        if (cameraProvider != null) bind()
    }

    private fun bind() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .build()
        imageCapture = capture

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
        effect?.let { group.addEffect(it) }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycleOwner, selector, group.build())
    }

    fun canSwitchLens(): Boolean {
        val provider = cameraProvider ?: return false
        return provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) &&
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
    }

    fun switchLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        rebind()
    }

    /** Mise au point sur un point touché du viseur. */
    fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        control.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    /** Prend une photo et l'enregistre dans la galerie (Pictures/Roll35). */
    fun takePhoto(onSaved: (Uri?) -> Unit, onError: (ImageCaptureException) -> Unit) {
        val capture = imageCapture ?: return

        val name = "R35_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE)
            .format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Roll35")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()

        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onSaved(output.savedUri)
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        })
    }
}
