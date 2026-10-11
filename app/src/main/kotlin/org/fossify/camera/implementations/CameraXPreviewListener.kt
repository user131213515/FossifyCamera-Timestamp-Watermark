package org.fossify.camera.implementations

import android.graphics.Bitmap
import android.net.Uri
import org.fossify.camera.models.ResolutionOption

interface CameraXPreviewListener {
    fun onInitPhotoMode()
    fun onInitVideoMode()
    fun setCameraAvailable(available: Boolean) {}
    fun setHasFrontAndBackCamera(hasFrontAndBack: Boolean)
    fun setFlashAvailable(available: Boolean)
    fun onChangeCamera(frontCamera: Boolean) {}
    fun shutterAnimation()
    fun onMediaSaved(uri: Uri)
    fun onImageCaptured(bitmap: Bitmap)
    fun onChangeFlashMode(flashMode: Int)
    fun onPhotoCaptureStart()
    fun onPhotoCaptureEnd()
    fun onVideoRecordingStarted()
    fun onVideoRecordingStopped()
    fun onVideoDurationChanged(durationNanos: Long)
    fun onFocusCamera(xPos: Float, yPos: Float)
    fun onTouchPreview()
    fun displaySelectedResolution(resolutionOption: ResolutionOption)
    fun showImageSizes(
        selectedResolution: ResolutionOption,
        resolutions: List<ResolutionOption>,
        isPhotoCapture: Boolean,
        isFrontCamera: Boolean,
        onSelect: (index: Int, changed: Boolean) -> Unit,
    )

    fun showFlashOptions(photoCapture: Boolean)

    /**
     * @param requiresCentering whether the preview has to stay between the top bar and the mode
     * tabs
     * @param displayAspectRatio width divided by height of the area that is actually captured, in
     * the current display orientation, so the viewfinder can be shaped to match the photo
     */
    fun adjustPreviewView(requiresCentering: Boolean, displayAspectRatio: Float)
}
