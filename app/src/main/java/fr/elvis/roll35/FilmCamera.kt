package fr.elvis.roll35

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Toute la partie caméra : viseur + prise de photo, avec CameraX.
 *
 * Le viseur passe par [FilmProcessor] (filtre GPU en direct). Les photos sont
 * prises en pleine résolution puis développées par [PhotoDeveloper] avec la
 * même pellicule, et le même code GPU.
 */
class FilmCamera(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
) {
    private val processor = FilmProcessor()
    private val developer = PhotoDeveloper(context)

    /** Filtre GPU du viseur, branché en permanence. */
    private val effect = object : CameraEffect(
        CameraEffect.PREVIEW,
        processor.executor,
        processor,
        Consumer<Throwable> { Log.e("Roll35", "Erreur du filtre", it) },
    ) {}

    /** Pellicule active. `null` = image d'origine. */
    var film: Film? = null
        set(value) {
            field = value
            processor.setFilm(value)
        }

    /** Format de cadrage appliqué aux photos. */
    var framing: Framing = Framing.F4_3

    /**
     * Appelé à chaque changement de zoom (ratio actuel, minimum, maximum).
     * Sur les Galaxy Ultra, la caméra arrière regroupe tous les objectifs :
     * le téléphone passe tout seul sur l'ultra grand-angle sous 1× et sur les
     * téléobjectifs à 3× et 5×.
     */
    var onZoomChanged: ((ratio: Float, min: Float, max: Float) -> Unit)? = null

    private var zoomLive: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { z ->
        onZoomChanged?.invoke(z.zoomRatio, z.minZoomRatio, z.maxZoomRatio)
    }

    /** Appelé après chaque (re)démarrage de la caméra, par exemple au changement d'objectif. */
    var onCameraBound: (() -> Unit)? = null

    /** Réglage manuel actuel (`null` = automatique). */
    var manualIso: Int? = null
        private set
    var manualExposureNs: Long? = null
        private set

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null

    private val mainExecutor get() = ContextCompat.getMainExecutor(context)

    // Format 4:3 = tout le capteur du téléphone, comme un appareil photo.
    private val resolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()

    // Photos : environ 12 Mpx au maximum, pour rester rapide et éviter de saturer la mémoire.
    private val photoResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(
                Size(4032, 3024),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
            ),
        )
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
            .setResolutionSelector(photoResolutionSelector)
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .build()
        imageCapture = capture

        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(capture)
            .addEffect(effect)

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        provider.unbindAll()
        val cam = provider.bindToLifecycle(lifecycleOwner, selector, group.build())
        camera = cam
        zoomLive?.removeObserver(zoomObserver)
        zoomLive = cam.cameraInfo.zoomState.also { it.observe(lifecycleOwner, zoomObserver) }
        // Une nouvelle caméra repart en automatique.
        manualIso = null
        manualExposureNs = null
        onCameraBound?.invoke()
    }

    /** Plages ISO et temps de pose (en nanosecondes) acceptées, ou `null` si pas de réglage manuel. */
    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalCamera2Interop::class)
    fun manualRanges(): Pair<IntRange, LongRange>? {
        val cam = camera ?: return null
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if (caps == null || CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in caps) return null
        val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: return null
        val exp = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: return null
        return (iso.lower..iso.upper) to (exp.lower..exp.upper)
    }

    /**
     * Exposition manuelle : ISO et temps de pose ensemble, ou les deux `null`
     * pour revenir en automatique. S'applique au viseur et aux photos.
     */
    @SuppressLint("UnsafeOptInUsageError")
    @OptIn(ExperimentalCamera2Interop::class)
    fun setManualExposure(iso: Int?, exposureNs: Long?): Boolean {
        val cam = camera ?: return false
        val control = Camera2CameraControl.from(cam.cameraControl)
        if (iso == null || exposureNs == null) {
            control.clearCaptureRequestOptions()
            manualIso = null
            manualExposureNs = null
            return true
        }
        val (isoRange, expRange) = manualRanges() ?: return false
        val i = iso.coerceIn(isoRange.first, isoRange.last)
        val e = exposureNs.coerceIn(expRange.first, expRange.last)
        control.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, i)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(e, 33_333_333L))
                .build(),
        )
        manualIso = i
        manualExposureNs = e
        return true
    }

    /** Zoom actuel (1 = objectif principal). */
    val zoomRatio: Float get() = zoomLive?.value?.zoomRatio ?: 1f

    /** Règle le zoom, borné à ce que la caméra accepte. */
    fun setZoom(ratio: Float) {
        val cam = camera ?: return
        val z = cam.cameraInfo.zoomState.value
        val r = if (z != null) ratio.coerceIn(z.minZoomRatio, z.maxZoomRatio) else ratio
        cam.cameraControl.setZoomRatio(r)
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

    /**
     * Prend une photo, la développe avec la pellicule active et l'enregistre
     * dans la galerie (Pictures/Roll35). Les rappels arrivent sur le thread principal.
     */
    fun takePhoto(onSaved: (Uri?) -> Unit, onError: (Throwable) -> Unit) {
        val capture = imageCapture ?: return
        val filmAtShot = film
        val framingAtShot = framing
        val saved = onSaved
        val failed = onError
        val name = "R35_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE)
            .format(System.currentTimeMillis())

        capture.takePicture(developer.executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotation = image.imageInfo.rotationDegrees
                    val bitmap = image.use { it.toBitmap() }
                    val uri = developer.develop(bitmap, rotation, filmAtShot, framingAtShot, name)
                    mainExecutor.execute { saved(uri) }
                } catch (t: Throwable) {
                    Log.e("Roll35", "Développement de la photo", t)
                    mainExecutor.execute { failed(t) }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute { failed(exception) }
            }
        })
    }

    fun release() {
        cameraProvider?.unbindAll()
        processor.release()
        developer.release()
    }
}
