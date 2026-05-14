package com.sherpaz.faro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream

class CaptureActivity : Activity() {

    companion object {
        const val REQUEST_CODE = 100
        const val ACTION_CAPTURE_DONE = "com.sherpaz.faro.CAPTURE_DONE"
        const val EXTRA_FILE_PATH = "file_path"

        fun start(context: Context) {
            val intent = Intent(context, CaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE || resultCode != RESULT_OK || data == null) {
            sendBroadcast(Intent(ACTION_CAPTURE_DONE).apply { setPackage(packageName) })
            finish()
            return
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FaroCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // Esperar 3 segundos para que el VirtualDisplay capture la pantalla
        handler.postDelayed({
            attemptCapture(width, height, retries = 5)
        }, 3000)
    }

    private fun attemptCapture(width: Int, height: Int, retries: Int) {
        val filePath = captureAndSave(width, height)
        if (filePath != null) {
            // Captura exitosa
            cleanup()
            val intent = Intent(ACTION_CAPTURE_DONE).apply {
                setPackage(packageName)
                putExtra(EXTRA_FILE_PATH, filePath)
            }
            sendBroadcast(intent)
            finish()
        } else if (retries > 0) {
            // Reintentar en 500ms
            handler.postDelayed({
                attemptCapture(width, height, retries - 1)
            }, 500)
        } else {
            // Sin más reintentos — enviar broadcast sin archivo
            cleanup()
            sendBroadcast(Intent(ACTION_CAPTURE_DONE).apply { setPackage(packageName) })
            finish()
        }
    }

    private fun captureAndSave(width: Int, height: Int): String? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(buffer)
            image.close()

            // Escalar
            val maxDim = 1080
            val scale = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                )
            } else bmp

            // Guardar en Descargas para que sea accesible
            val file = File(cacheDir, "faro_capture.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanup() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cleanup()
    }
}
