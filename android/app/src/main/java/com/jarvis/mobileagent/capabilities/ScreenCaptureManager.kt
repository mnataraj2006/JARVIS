package com.jarvis.mobileagent.capabilities

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class ScreenCaptureManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenCapture"
        var mediaProjection: MediaProjection? = null
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var isStreaming = false
    private var streamJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun hasPermission(): Boolean = mediaProjection != null

    suspend fun captureScreenshot(quality: Int = 80): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val mp = mediaProjection ?: return@withContext Pair(false, "Screen capture permission not granted. Open JARVIS app on phone to allow.")

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val vd = mp.createVirtualDisplay(
            "JarvisScreenshot",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        // Wait brief moment for virtual display buffer
        delay(150)

        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: reader.acquireNextImage()
            if (image == null) {
                delay(100)
                image = reader.acquireLatestImage()
            }

            if (image == null) {
                return@withContext Pair(false, "Could not acquire screen image frame.")
            }

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)

            val stream = ByteArrayOutputStream()
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val bytes = stream.toByteArray()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            bitmap.recycle()
            croppedBitmap.recycle()

            Pair(true, b64)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed: ${e.message}")
            Pair(false, "Screenshot error: ${e.message}")
        } finally {
            image?.close()
            vd.release()
            reader.close()
        }
    }

    fun startScreenStream(fps: Int = 8, onFrame: (ByteArray) -> Unit): Boolean {
        val mp = mediaProjection ?: return false
        if (isStreaming) return true

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        // Lower resolution for real-time streaming bandwidth
        val width = metrics.widthPixels / 3
        val height = metrics.heightPixels / 3
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "JarvisStream",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        isStreaming = true
        val frameIntervalMs = (1000 / fps.coerceIn(1, 15)).toLong()

        streamJob = scope.launch {
            while (isStreaming && isActive) {
                try {
                    val img = imageReader?.acquireLatestImage()
                    if (img != null) {
                        val planes = img.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val bmp = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bmp.copyPixelsFromBuffer(buffer)
                        val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)

                        val out = ByteArrayOutputStream()
                        cropped.compress(Bitmap.CompressFormat.JPEG, 60, out)
                        val bytes = out.toByteArray()

                        onFrame(bytes)

                        bmp.recycle()
                        cropped.recycle()
                        img.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Stream loop error: ${e.message}")
                }
                delay(frameIntervalMs)
            }
        }
        return true
    }

    fun stopScreenStream() {
        isStreaming = false
        streamJob?.cancel()
        streamJob = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }
}
