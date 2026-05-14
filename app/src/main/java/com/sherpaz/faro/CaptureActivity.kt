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
import android.util.Log

class CaptureActivity : Activity() {

    companion object {
        const val TAG = "CaptureActivity"
        const val REQUEST_CODE = 100

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate — solicitando permiso MediaProjection")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode resultCode=$resultCode")

        if (requestCode != REQUEST_CODE || resultCode != RESULT_OK || data == null) {
            Log.e(TAG, "Permiso denegado o cancelado")
            FloatingService.floatingServiceInstance?.onCaptureResult(null)
            finish()
            return
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FaroCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        Log.d(TAG, "VirtualDisplay creado — esperando frame...")

        // Esperar 1.5 segundos para que la pantalla muestre Uber Driver
        // y recién entonces capturar
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Capturando frame...")
            val bitmap = captureFrame(width, height)
            Log.d(TAG, "Frame capturado: ${bitmap != null}")
            cleanup()
            FloatingService.floatingServiceInstance?.onCaptureResult(bitmap)
            finish()
        }, 1500)
    }

    private fun captureFrame(width: Int, height: Int): Bitmap? {
        return try {
            val image = imageReader?.acquireLatestImage() ?: run {
                Log.e(TAG, "acquireLatestImage devolvió null")
                return null
            }
            val planes = image.planes
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
            image.close()
            Log.d(TAG, "Bitmap creado: ${bmp.width}x${bmp.height}")

            // Escalar para reducir tamaño
            val maxDim = 1080
            val scale = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt(),
                    (bmp.height * scale).toInt(),
                    true
                )
            } else bmp
        } catch (e: Exception) {
            Log.e(TAG, "Error capturando frame: ${e.message}")
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
        cleanup()
    }
}
