package org.fossify.camera.implementations

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Display
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.Builder
import androidx.camera.core.ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
import androidx.camera.core.ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
import androidx.camera.core.ImageCapture.ERROR_CAPTURE_FAILED
import androidx.camera.core.ImageCapture.FLASH_MODE_AUTO
import androidx.camera.core.ImageCapture.FLASH_MODE_OFF
import androidx.camera.core.ImageCapture.FLASH_MODE_ON
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.core.ImageCapture.OnImageCapturedCallback
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
import androidx.camera.core.resolutionselector.ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.camera.view.PreviewView.ScaleType
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.window.layout.WindowMetricsCalculator
import com.bumptech.glide.load.ImageHeaderParser.UNKNOWN_ORIENTATION
import org.fossify.camera.R
import org.fossify.camera.extensions.checkLocationPermission
import org.fossify.camera.extensions.config
import org.fossify.camera.extensions.toAppFlashMode
import org.fossify.camera.extensions.toCameraSelector
import org.fossify.camera.extensions.toCameraXFlashMode
import org.fossify.camera.extensions.toCameraXQuality
import org.fossify.camera.extensions.toLensFacing
import org.fossify.camera.helpers.BitmapUtils
import org.fossify.camera.helpers.CameraErrorHandler
import org.fossify.camera.helpers.FLASH_ALWAYS_ON
import org.fossify.camera.helpers.FLASH_ON
import org.fossify.camera.helpers.ImageQualityManager
import org.fossify.camera.helpers.ImageSaver
import org.fossify.camera.helpers.ImageUtil
import org.fossify.camera.helpers.MediaOutputHelper
import org.fossify.camera.helpers.MediaSizeStore
import org.fossify.camera.helpers.MediaSoundHelper
import org.fossify.camera.helpers.PinchToZoomOnScaleGestureListener
import org.fossify.camera.helpers.SimpleLocationManager
import org.fossify.camera.helpers.VideoQualityManager
import org.fossify.camera.interfaces.MyPreview
import org.fossify.camera.models.CaptureMode
import org.fossify.camera.models.MediaOutput
import org.fossify.camera.models.MySize
import org.fossify.camera.models.ResolutionOption
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.PERMISSION_ACCESS_FINE_LOCATION
import org.fossify.commons.helpers.ensureBackgroundThread
import kotlin.math.abs

class CameraXPreview(
    private val activity: BaseSimpleActivity,
    private val previewView: PreviewView,
    private val mediaSoundHelper: MediaSoundHelper,
    private val mediaOutputHelper: MediaOutputHelper,
    private val cameraErrorHandler: CameraErrorHandler,
    private val listener: CameraXPreviewListener,
    private val isThirdPartyIntent: Boolean,
    initInPhotoMode: Boolean,
) : MyPreview, DefaultLifecycleObserver {

    companion object {
        // Auto focus is 1/6 of the area.
        private const val AF_SIZE = 1.0f / 6.0f
        private const val AE_SIZE = AF_SIZE * 1.5f
        private const val CAMERA_MODE_SWITCH_WAIT_TIME = 500L

        // Timing tag for tracking down the stutter when switching capture modes.
        private const val SWITCH_TAG = "CameraModeSwitch"
    }

    private val config = activity.config
    private val contentResolver = activity.contentResolver
    private val mainExecutor = ContextCompat.getMainExecutor(activity)
    private val displayManager =
        activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val windowMetricsCalculator = WindowMetricsCalculator.getOrCreate()
    private val videoQualityManager = VideoQualityManager(activity)
    private val imageQualityManager = ImageQualityManager(activity)
    private val mediaSizeStore = MediaSizeStore(config)

    private val orientationEventListener =
        object : OrientationEventListener(activity, SensorManager.SENSOR_DELAY_NORMAL) {
            @SuppressLint("RestrictedApi")
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == UNKNOWN_ORIENTATION) {
                    return
                }

                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                if (lastRotation != rotation) {
                    preview?.targetRotation = rotation
                    imageCapture?.targetRotation = rotation
                    videoCapture?.targetRotation = rotation
                    lastRotation = rotation
                    // The still capture crop is expressed in the orientation of the output image,
                    // so it has to be recomputed when the device turns between portrait and
                    // landscape, otherwise a portrait crop would be applied to a landscape frame.
                    refreshPhotoAspectRatio()
                }
            }
        }
    private val cameraHandler = Handler(Looper.getMainLooper())
    private val photoModeRunnable = Runnable {
        val modeChanged = !isPhotoCapture
        isPhotoCapture = true
        if (!isThirdPartyIntent) { // we don't want to store the state for 3rd party intents
            config.initPhotoMode = true
        }
        if (imageCapture == null) {
            startCamera()
        } else {
            if (modeChanged) {
                updateModeDependentUi()
            }
            listener.onInitPhotoMode()
        }
    }
    private val videoModeRunnable = Runnable {
        val modeChanged = isPhotoCapture
        isPhotoCapture = false
        if (!isThirdPartyIntent) { // we don't want to store the state for 3rd party intents
            config.initPhotoMode = false
        }
        if (videoCapture == null) {
            startCamera()
        } else {
            if (modeChanged) {
                updateModeDependentUi()
            }
            listener.onInitVideoMode()
        }
    }

    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var currentRecording: Recording? = null
    private var recordingState: VideoRecordEvent? = null
    private var cameraSelector = config.lastUsedCameraLens.toCameraSelector()
    private var flashMode = FLASH_MODE_OFF
    private var isPhotoCapture = initInPhotoMode
    private var lastRotation = 0
    private var lastCameraStartTime = 0L
    private var switchRequestedAt = 0L
    private var combinedBindSucceeded = false
    private var simpleLocationManager: SimpleLocationManager? = null

    init {
        bindToLifeCycle()
        // Measures the delay the user actually perceives: from tapping a mode tab until the
        // preview is streaming frames again.
        previewView.previewStreamState.observe(activity) { state ->
            if (state == PreviewView.StreamState.STREAMING && switchRequestedAt > 0L) {
                Log.i(
                    SWITCH_TAG,
                    "preview STREAMING +${SystemClock.elapsedRealtime() - switchRequestedAt}ms " +
                        "after tap (end-to-end perceived switch)"
                )
            }
        }
    }

    private fun bindToLifeCycle() {
        activity.lifecycle.addObserver(this)
    }

    private fun startCamera(switching: Boolean = false) {
        val requestedAt = SystemClock.elapsedRealtime()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity.applicationContext)
        cameraProviderFuture.addListener({
            try {
                val providerReadyAt = SystemClock.elapsedRealtime()
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val t0 = SystemClock.elapsedRealtime()
                imageQualityManager.initSupportedQualities()
                val t1 = SystemClock.elapsedRealtime()
                videoQualityManager.initSupportedQualities(provider)
                val t2 = SystemClock.elapsedRealtime()
                bindCameraUseCases()
                val t3 = SystemClock.elapsedRealtime()
                setupCameraObservers()
                val t4 = SystemClock.elapsedRealtime()
                Log.i(
                    SWITCH_TAG,
                    "startCamera(switching=$switching, video=${!isPhotoCapture}) " +
                        "providerWait=${providerReadyAt - requestedAt}ms " +
                        "imageQualities=${t1 - t0}ms videoQualities=${t2 - t1}ms " +
                        "bindUseCases=${t3 - t2}ms observers=${t4 - t3}ms " +
                        "mainThreadTotal=${t4 - providerReadyAt}ms"
                )
            } catch (e: Exception) {
                Log.i(SWITCH_TAG, "startCamera FAILED: ${e::class.java.simpleName}: ${e.message}")
                val errorMessage =
                    if (switching) R.string.camera_switch_error else R.string.camera_open_error
                activity.toast(errorMessage)
            }
        }, mainExecutor)
    }

    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val buildUseCasesStartedAt = SystemClock.elapsedRealtime()
        // The still capture and the preview are always configured for the photo resolution, even
        // when the app starts in video mode, because the two capture use cases are bound together
        // and must not depend on which mode happened to be active while binding.
        val photoResolution = imageQualityManager.getUserSelectedResolution(cameraSelector)
        val videoQuality = videoQualityManager.getUserSelectedQuality(cameraSelector)
        val resolution = if (isPhotoCapture) {
            photoResolution.also { listener.displaySelectedResolution(it.toResolutionOption()) }
        } else {
            videoQuality.also { listener.displaySelectedResolution(it.toResolutionOption()) }
                .let { MySize(it.width, it.height) }
        }

        listener.adjustPreviewView(resolution.requiresCentering(), previewAspectRatio(resolution))

        val isFullSize = resolution.isFullScreen
        previewView.scaleType =
            if (isPhotoCapture || isFullSize) ScaleType.FILL_CENTER else ScaleType.FIT_CENTER
        // The activity is locked to portrait, so previewView.display.rotation is always
        // ROTATION_0 and must not be used to orient the capture. The rotation tracked by the
        // sensor listener is the only meaningful source here, otherwise a photo taken while
        // holding the phone in landscape is saved in portrait whenever the camera is rebuilt
        // (photo/video mode switch, camera switch, returning to the app).
        val rotation = lastRotation
        val targetResolution = Size(resolution.width, resolution.height)
        val photoTargetResolution = Size(photoResolution.width, photoResolution.height)

        val previewUseCase = buildPreview(targetResolution, rotation)
        // The still capture is bound at the largest supported resolution instead of the currently
        // selected one, so that a later aspect ratio change is only a crop update. Rebinding the
        // still capture for a new aspect ratio would make CameraX close and reopen the camera
        // device, which blacks the preview out for about 340ms.
        val maxPhotoResolution = imageQualityManager.getMaxPhotoResolution(cameraSelector)
            ?: photoResolution
        Log.i(
            SWITCH_TAG,
            "bound still capture resolution request=${maxPhotoResolution.width}x" +
                "${maxPhotoResolution.height} preview=${targetResolution.width}x${targetResolution.height}"
        )
        val imageCaptureUseCase = buildImageCapture(
            Size(maxPhotoResolution.width, maxPhotoResolution.height),
            rotation
        )
        val videoCaptureUseCase = buildVideoCapture()
        val captureUseCase = if (isPhotoCapture) imageCaptureUseCase else videoCaptureUseCase

        val unbindStartedAt = SystemClock.elapsedRealtime()
        cameraProvider.unbindAll()
        val unbindDoneAt = SystemClock.elapsedRealtime()
        val probeViewPort = if (isFullSize) {
            val metrics = windowMetricsCalculator.computeCurrentWindowMetrics(activity).bounds
            ViewPort.Builder(Rational(metrics.width(), metrics.height()), rotation).build()
        } else {
            null
        }
        // Bind the preview together with both capture use cases when the device supports it.
        // A mode switch then only changes which capture use case is used, so CameraX does not
        // have to close and reopen the camera device (see the CXCP "Closing Camera 0" /
        // "Opening CameraId-0" pair in logcat) which freezes the preview for roughly 350ms.
        // No ViewPort is used here on purpose: it would crop the video capture as well.
        try {
            val group = UseCaseGroup.Builder()
                .addUseCase(previewUseCase)
                .addUseCase(imageCaptureUseCase)
                .addUseCase(videoCaptureUseCase)
                .build()
            camera = cameraProvider.bindToLifecycle(activity, cameraSelector, group)
            combinedBindSucceeded = true
            Log.i(SWITCH_TAG, "combined bind OK (preview + JPEG + video)")
        } catch (e: IllegalArgumentException) {
            // The device cannot run the preview, JPEG and video streams at the same time, so
            // fall back to binding only the use case of the current mode.
            combinedBindSucceeded = false
            Log.i(SWITCH_TAG, "combined bind rejected, using per-mode bind: ${e.message?.take(160)}")
            camera = null
        }
        if (camera == null) {
            camera = if (isFullSize) {
                val group = UseCaseGroup.Builder()
                    .addUseCase(previewUseCase)
                    .addUseCase(captureUseCase)
                    .setViewPort(probeViewPort!!)
                    .build()
                cameraProvider.bindToLifecycle(activity, cameraSelector, group)
            } else {
                cameraProvider.bindToLifecycle(
                    activity,
                    cameraSelector,
                    previewUseCase,
                    captureUseCase,
                )
            }
        }
        preview = previewUseCase
        // Only the capture use cases that are actually bound may be exposed, otherwise the mode
        // runnables would skip the rebind that a non-combined bind still needs.
        imageCapture = imageCaptureUseCase.takeIf { combinedBindSucceeded || isPhotoCapture }
        videoCapture = videoCaptureUseCase.takeIf { combinedBindSucceeded || !isPhotoCapture }
        // The crop has to be applied only after the bind: CameraX sizes the still capture stream
        // from the crop aspect ratio that is set while binding, so setting a narrow aspect first
        // would make it allocate a smaller stream that every later aspect ratio has to crop from.
        if (imageCapture != null) {
            applyPhotoAspectRatio(imageCaptureUseCase, photoResolution)
        }
        val bindDoneAt = SystemClock.elapsedRealtime()
        Log.i(
            SWITCH_TAG,
            "bindCameraUseCases(video=${!isPhotoCapture}) " +
                "resolution=${resolution.width}x${resolution.height} fullSize=$isFullSize " +
                "unbindAll=${unbindDoneAt - unbindStartedAt}ms " +
                "bindToLifecycle=${bindDoneAt - unbindDoneAt}ms " +
                "selectCases=${unbindStartedAt - buildUseCasesStartedAt}ms"
        )
        setupZoomAndFocus()
        setFlashlightState(config.flashlightState)
    }

    /**
     * Refreshes everything that depends on the current capture mode without touching the camera.
     *
     * When the preview, the still capture and the video capture use cases are bound together, a
     * mode switch only changes which capture use case is used, so the camera does not have to be
     * closed and reopened (which freezes the preview for roughly 350ms). The parts of the UI that
     * used to be refreshed by [bindCameraUseCases] are updated here instead.
     */
    private fun updateModeDependentUi() {
        val resolution = if (isPhotoCapture) {
            imageQualityManager.getUserSelectedResolution(cameraSelector)
        } else {
            val selectedQuality = videoQualityManager.getUserSelectedQuality(cameraSelector)
            MySize(selectedQuality.width, selectedQuality.height)
        }
        listener.displaySelectedResolution(resolution.toResolutionOption())
        listener.adjustPreviewView(resolution.requiresCentering(), previewAspectRatio(resolution))
        previewView.scaleType =
            if (isPhotoCapture || resolution.isFullScreen) ScaleType.FILL_CENTER
            else ScaleType.FIT_CENTER
    }

    /**
     * Turns the aspect ratio of [size] into the crop rectangle of the still capture.
     *
     * The captured image is oriented for the display rotation, so a portrait display needs the
     * taller of the two ratios. The "full screen" entry is defined by the screen itself.
     */
    private fun applyPhotoAspectRatio(imageCapture: ImageCapture, size: MySize) {
        val ratio = if (size.isFullScreen) {
            val metrics = windowMetricsCalculator.computeCurrentWindowMetrics(activity).bounds
            val width = metrics.width()
            val height = metrics.height()
            if (isPortraitDisplay()) Rational(width, height) else Rational(height, width)
        } else {
            val short = minOf(size.width, size.height)
            val long = maxOf(size.width, size.height)
            if (isPortraitDisplay()) Rational(short, long) else Rational(long, short)
        }
        imageCapture.setCropAspectRatio(ratio)
        Log.i(
            SWITCH_TAG,
            "still capture aspect: size=${size.width}x${size.height} " +
                "fullScreen=${size.isFullScreen} portraitDisplay=${isPortraitDisplay()} crop=$ratio"
        )
    }

    private fun isPortraitDisplay(): Boolean {
        return lastRotation == Surface.ROTATION_0 || lastRotation == Surface.ROTATION_180
    }

    /**
     * Width divided by height of the area that is actually captured, in the current display
     * orientation. Zero means "leave the viewfinder shape alone", which is what the video mode
     * uses because its preview can no longer be rebound either.
     */
    private fun previewAspectRatio(size: MySize): Float {
        if (!isPhotoCapture) {
            return 0f
        }
        if (size.isFullScreen) {
            val metrics = windowMetricsCalculator.computeCurrentWindowMetrics(activity).bounds
            val width = metrics.width().toFloat()
            val height = metrics.height().toFloat()
            return if (isPortraitDisplay()) width / height else height / width
        }
        val short = minOf(size.width, size.height).toFloat()
        val long = maxOf(size.width, size.height).toFloat()
        return if (isPortraitDisplay()) short / long else long / short
    }

    private fun refreshPhotoAspectRatio() {
        val imageCapture = imageCapture ?: return
        applyPhotoAspectRatio(
            imageCapture,
            imageQualityManager.getUserSelectedResolution(cameraSelector)
        )
    }

    /**
     * Called when the user picks another resolution or aspect ratio.
     *
     * In photo mode the still capture is bound at a fixed, maximum resolution, so the new aspect
     * ratio is applied by updating its crop rectangle and the mode dependent UI only. Calling
     * [startCamera] here would unbind everything and make CameraX close and reopen the camera
     * device, which freezes the preview for roughly 340ms. Video qualities still select a
     * different recording resolution, so they keep the full rebind.
     */
    private fun onResolutionChanged() {
        val imageCapture = imageCapture
        if (isPhotoCapture && combinedBindSucceeded && imageCapture != null) {
            val size = imageQualityManager.getUserSelectedResolution(cameraSelector)
            applyPhotoAspectRatio(imageCapture, size)
            listener.displaySelectedResolution(size.toResolutionOption())
            listener.adjustPreviewView(size.requiresCentering(), previewAspectRatio(size))
            previewView.scaleType =
                if (isPhotoCapture || size.isFullScreen) ScaleType.FILL_CENTER
                else ScaleType.FIT_CENTER
            return
        }

        currentRecording?.stop()
        startCamera()
    }

    private fun buildPreview(resolution: Size, rotation: Int): Preview {
        return Preview.Builder()
            .setTargetRotation(rotation)
            .setResolutionSelector(getResolutionSelector(resolution))
            .build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
    }

    private fun getCaptureUseCase(resolution: Size, rotation: Int): UseCase {
        return if (isPhotoCapture) {
            buildImageCapture(resolution, rotation).also {
                imageCapture = it
                videoCapture = null
            }
        } else {
            buildVideoCapture().also {
                videoCapture = it
                imageCapture = null
            }
        }
    }

    private fun buildImageCapture(resolution: Size, rotation: Int): ImageCapture {
        return Builder()
            .setCaptureMode(getCaptureMode())
            .setFlashMode(flashMode)
            .setJpegQuality(config.photoQuality)
            .setTargetRotation(rotation)
            .setResolutionSelector(getResolutionSelector(resolution))
            .build()
    }

    private fun getCaptureMode(): Int {
        return when (config.captureMode) {
            CaptureMode.MINIMIZE_LATENCY -> CAPTURE_MODE_MINIMIZE_LATENCY
            CaptureMode.MAXIMIZE_QUALITY -> CAPTURE_MODE_MAXIMIZE_QUALITY
        }
    }

    private fun getResolutionSelector(resolution: Size): ResolutionSelector {
        return ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(resolution, FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .setResolutionFilter { supportedSizes, rotationDegrees ->
                // Sort by closest image ratio
                supportedSizes.sortedBy {
                    size -> abs(size.width / size.height.toFloat() - resolution.width / resolution.height.toFloat())
                }
            }
            .setAllowedResolutionMode(getAllowedResolutionMode())
            .build()
    }

    private fun getAllowedResolutionMode(): Int {
        return when (config.captureMode) {
            CaptureMode.MINIMIZE_LATENCY -> PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
            CaptureMode.MAXIMIZE_QUALITY -> PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
        }
    }

    private fun buildVideoCapture(): VideoCapture<Recorder> {
        val qualitySelector = QualitySelector.from(
            videoQualityManager.getUserSelectedQuality(cameraSelector).toCameraXQuality(),
            FallbackStrategy.higherQualityOrLowerThan(Quality.SD),
        )

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()

        return VideoCapture.withOutput(recorder)
    }

    private fun setupCameraObservers() {
        listener.setFlashAvailable(camera?.cameraInfo?.hasFlashUnit() ?: false)
        listener.onChangeCamera(isFrontCameraInUse())
        if (isPhotoCapture) {
            listener.onInitPhotoMode()
        } else {
            listener.onInitVideoMode()
        }
        camera?.cameraInfo?.cameraState?.observe(activity) { cameraState ->
            if (cameraState.error == null) {
                when (cameraState.type) {
                    CameraState.Type.OPENING,
                    CameraState.Type.OPEN -> {
                        listener.setHasFrontAndBackCamera(hasFrontCamera() && hasBackCamera())
                        listener.setCameraAvailable(true)
                    }

                    CameraState.Type.PENDING_OPEN,
                    CameraState.Type.CLOSING,
                    CameraState.Type.CLOSED -> {
                        listener.setCameraAvailable(false)
                    }
                }
            } else {
                listener.setCameraAvailable(false)
                cameraErrorHandler.handleCameraError(cameraState.error)
            }
        }
    }

    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    private fun isFrontCameraInUse(): Boolean {
        return cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
    }

    @SuppressLint("ClickableViewAccessibility")
    // source: https://stackoverflow.com/a/60095886/10552591
    private fun setupZoomAndFocus() {
        val scaleGesture = camera?.let {
            ScaleGestureDetector(
                activity,
                PinchToZoomOnScaleGestureListener(it.cameraInfo, it.cameraControl)
            )
        }
        val gestureDetector = GestureDetector(activity, object : SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                listener.onTouchPreview()
                return super.onDown(event)
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                return camera?.cameraInfo?.let {
                    val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    val width = previewView.width.toFloat()
                    val height = previewView.height.toFloat()
                    val factory = DisplayOrientedMeteringPointFactory(display, it, width, height)
                    val xPos = event.x
                    val yPos = event.y
                    val autoFocusPoint = factory.createPoint(xPos, yPos, AF_SIZE)
                    val autoExposurePoint = factory.createPoint(xPos, yPos, AE_SIZE)
                    val focusMeteringAction =
                        FocusMeteringAction.Builder(autoFocusPoint, FocusMeteringAction.FLAG_AF)
                            .addPoint(autoExposurePoint, FocusMeteringAction.FLAG_AE)
                            .disableAutoCancel()
                            .build()
                    camera?.cameraControl?.startFocusAndMetering(focusMeteringAction)
                    listener.onFocusCamera(event.rawX, event.rawY)
                    true
                } ?: false
            }
        })
        previewView.setOnTouchListener { _, event ->
            val handledGesture = gestureDetector.onTouchEvent(event)
            val handledScaleGesture = scaleGesture?.onTouchEvent(event)
            handledGesture || handledScaleGesture ?: false
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        orientationEventListener.enable()
        previewView.doOnLayout {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                startCamera()
            }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        if (config.savePhotoVideoLocation) {
            if (simpleLocationManager == null) {
                simpleLocationManager = SimpleLocationManager(activity)
            }
            requestLocationUpdates()
        }
    }

    private fun requestLocationUpdates() {
        activity.apply {
            if (checkLocationPermission()) {
                simpleLocationManager?.requestLocationUpdates()
            } else {
                handlePermission(PERMISSION_ACCESS_FINE_LOCATION) { _ ->
                    if (checkLocationPermission()) {
                        simpleLocationManager?.requestLocationUpdates()
                    } else {
                        config.savePhotoVideoLocation = false
                    }
                }
            }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        simpleLocationManager?.dropLocationUpdates()
    }

    override fun onStop(owner: LifecycleOwner) {
        orientationEventListener.disable()
    }

    override fun isInPhotoMode(): Boolean {
        return isPhotoCapture
    }

    override fun showChangeResolution() {
        val selectedResolution = if (isPhotoCapture) {
            imageQualityManager.getUserSelectedResolution(cameraSelector).toResolutionOption()
        } else {
            videoQualityManager.getUserSelectedQuality(cameraSelector).toResolutionOption()
        }

        val resolutions = if (isPhotoCapture) {
            imageQualityManager.getSupportedResolutions(cameraSelector)
                .map { it.toResolutionOption() }
        } else {
            videoQualityManager.getSupportedQualities(cameraSelector)
                .map { it.toResolutionOption() }
        }

        if (resolutions.size > 2) {
            listener.showImageSizes(
                selectedResolution = selectedResolution,
                resolutions = resolutions,
                isPhotoCapture = isPhotoCapture,
                isFrontCamera = isFrontCameraInUse()
            ) { index, changed ->
                mediaSizeStore.storeSize(isPhotoCapture, isFrontCameraInUse(), index)
                if (changed) {
                    onResolutionChanged()
                }
            }
        } else {
            toggleResolutions(resolutions)
        }
    }

    private fun toggleResolutions(resolutions: List<ResolutionOption>) {
        if (resolutions.size >= 2) {
            val currentIndex =
                mediaSizeStore.getCurrentSizeIndex(isPhotoCapture, isFrontCameraInUse())

            val nextIndex = if (currentIndex >= resolutions.lastIndex) {
                0
            } else {
                currentIndex + 1
            }

            mediaSizeStore.storeSize(isPhotoCapture, isFrontCameraInUse(), nextIndex)
            onResolutionChanged()
        }
    }

    override fun toggleFrontBackCamera() {
        val newCameraSelector = if (isFrontCameraInUse()) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        cameraSelector = newCameraSelector
        config.lastUsedCameraLens = newCameraSelector.toLensFacing()
        startCamera(switching = true)
    }

    override fun handleFlashlightClick() {
        if (isPhotoCapture) {
            listener.showFlashOptions(true)
        } else {
            toggleFlashlight()
        }
    }

    private fun toggleFlashlight() {
        val newFlashMode = if (isPhotoCapture) {
            when (flashMode) {
                FLASH_MODE_OFF -> FLASH_MODE_ON
                FLASH_MODE_ON -> FLASH_MODE_AUTO
                else -> FLASH_MODE_OFF
            }
        } else {
            when (flashMode) {
                FLASH_MODE_OFF -> FLASH_MODE_ON
                else -> FLASH_MODE_OFF
            }
        }
        setFlashlightState(newFlashMode.toAppFlashMode())
    }

    override fun setFlashlightState(state: Int) {
        var flashState = state
        if (isPhotoCapture) {
            camera?.cameraControl?.enableTorch(flashState == FLASH_ALWAYS_ON)
        } else {
            camera?.cameraControl?.enableTorch(flashState == FLASH_ON || flashState == FLASH_ALWAYS_ON)
            // reset to the FLASH_ON for video capture
            if (flashState == FLASH_ALWAYS_ON) {
                flashState = FLASH_ON
            }
        }
        val newFlashMode = flashState.toCameraXFlashMode()
        flashMode = newFlashMode
        imageCapture?.flashMode = newFlashMode

        config.flashlightState = flashState
        listener.onChangeFlashMode(flashState)
    }

    override fun tryTakePicture() {
        if (imageCapture == null) {
            activity.toast(R.string.camera_open_error)
            return
        }

        val imageCapture = imageCapture

        val metadata = Metadata().apply {
            isReversedHorizontal = isFrontCameraInUse() && config.flipPhotos
            if (config.savePhotoVideoLocation) {
                location = simpleLocationManager?.getLocation()
            }
        }

        val mediaOutput = mediaOutputHelper.getImageMediaOutput()
        imageCapture!!.takePicture(mainExecutor, object : OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                listener.shutterAnimation()
                playShutterSoundIfEnabled()
                ensureBackgroundThread {
                    image.use {
                        if (mediaOutput is MediaOutput.BitmapOutput) {
                            val imageBytes = ImageUtil.jpegImageToJpegByteArray(image)
                            val bitmap = BitmapUtils.makeBitmap(imageBytes)
                            activity.runOnUiThread {
                                listener.onPhotoCaptureEnd()
                                if (bitmap != null) {
                                    listener.onImageCaptured(bitmap)
                                } else {
                                    cameraErrorHandler.handleImageCaptureError(ERROR_CAPTURE_FAILED)
                                }
                            }
                        } else {
                            ImageSaver.saveImage(
                                contentResolver = contentResolver,
                                image = image,
                                mediaOutput = mediaOutput,
                                metadata = metadata,
                                jpegQuality = config.photoQuality,
                                saveExifAttributes = config.savePhotoMetadata,
                                addTimestampWatermark = config.addTimestampWatermark,
                                onImageSaved = { savedUri ->
                                    activity.runOnUiThread {
                                        listener.onPhotoCaptureEnd()
                                        listener.onMediaSaved(savedUri)
                                    }
                                },
                                onError = ::handleImageCaptureError
                            )
                        }
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                handleImageCaptureError(exception)
            }
        })
    }

    private fun handleImageCaptureError(exception: ImageCaptureException) {
        listener.onPhotoCaptureEnd()
        cameraErrorHandler.handleImageCaptureError(exception.imageCaptureError)
    }

    override fun initPhotoMode() {
        switchRequestedAt = SystemClock.elapsedRealtime()
        debounceChangeCameraMode(photoModeRunnable)
    }

    override fun initVideoMode() {
        switchRequestedAt = SystemClock.elapsedRealtime()
        debounceChangeCameraMode(videoModeRunnable)
    }

    private fun debounceChangeCameraMode(cameraModeRunnable: Runnable) {
        val currentTime = System.currentTimeMillis()
        val target = if (cameraModeRunnable === videoModeRunnable) "video" else "photo"
        if (currentTime - lastCameraStartTime > CAMERA_MODE_SWITCH_WAIT_TIME) {
            Log.i(SWITCH_TAG, "tap($target): executed immediately")
            cameraModeRunnable.run()
        } else {
            val delay = CAMERA_MODE_SWITCH_WAIT_TIME - (currentTime - lastCameraStartTime)
            Log.i(SWITCH_TAG, "tap($target): DEBOUNCED, delayed by ${delay}ms")
            cameraHandler.removeCallbacks(photoModeRunnable)
            cameraHandler.removeCallbacks(videoModeRunnable)
            cameraHandler.postDelayed(cameraModeRunnable, CAMERA_MODE_SWITCH_WAIT_TIME)
        }
        lastCameraStartTime = currentTime
    }

    override fun toggleRecording() {
        if (currentRecording == null || recordingState is VideoRecordEvent.Finalize) {
            if (config.isSoundEnabled) {
                mediaSoundHelper.playStartVideoRecordingSound(onPlayComplete = {
                    startRecording()
                })
                listener.onVideoRecordingStarted()
            } else {
                startRecording()
            }
        } else {
            currentRecording?.stop()
            currentRecording = null
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun startRecording() {
        if (videoCapture == null) {
            activity.toast(R.string.camera_open_error)
            return
        }

        val videoCapture = videoCapture

        val recording = when (val mediaOutput = mediaOutputHelper.getVideoMediaOutput()) {
            is MediaOutput.FileDescriptorMediaOutput -> {
                FileDescriptorOutputOptions.Builder(mediaOutput.fileDescriptor).apply {
                    if (config.savePhotoVideoLocation) {
                        setLocation(simpleLocationManager?.getLocation())
                    }
                }.build().let { videoCapture!!.output.prepareRecording(activity, it) }
            }

            is MediaOutput.FileMediaOutput -> {
                FileOutputOptions.Builder(mediaOutput.file).apply {
                    if (config.savePhotoVideoLocation) {
                        setLocation(simpleLocationManager?.getLocation())
                    }
                }.build().let { videoCapture!!.output.prepareRecording(activity, it) }
            }

            is MediaOutput.MediaStoreOutput -> {
                MediaStoreOutputOptions.Builder(contentResolver, mediaOutput.contentUri).apply {
                    setContentValues(mediaOutput.contentValues)
                    if (config.savePhotoVideoLocation) {
                        setLocation(simpleLocationManager?.getLocation())
                    }
                }.build().let { videoCapture!!.output.prepareRecording(activity, it) }
            }
        }

        currentRecording = recording.withAudioEnabled()
            .start(mainExecutor) { recordEvent ->
                recordingState = recordEvent
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        listener.onVideoRecordingStarted()
                    }

                    is VideoRecordEvent.Status -> {
                        listener.onVideoDurationChanged(recordEvent.recordingStats.recordedDurationNanos)
                    }

                    is VideoRecordEvent.Finalize -> {
                        playStopVideoRecordingSoundIfEnabled()
                        listener.onVideoRecordingStopped()
                        if (recordEvent.hasError()) {
                            cameraErrorHandler.handleVideoRecordingError(recordEvent.error)
                        } else {
                            listener.onMediaSaved(recordEvent.outputResults.outputUri)
                        }
                    }
                }
            }
    }

    private fun playShutterSoundIfEnabled() {
        if (config.isSoundEnabled) {
            mediaSoundHelper.playShutterSound()
        }
    }

    private fun playStopVideoRecordingSoundIfEnabled() {
        if (config.isSoundEnabled) {
            mediaSoundHelper.playStopVideoRecordingSound()
        }
    }
}
