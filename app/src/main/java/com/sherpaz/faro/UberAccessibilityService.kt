package com.sherpaz.faro

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class UberAccessibilityService : AccessibilityService() {

    companion object {
        var floatingServiceInstance: FloatingService? = null
        var currentInstance: UberAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        currentInstance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        currentInstance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun captureAndAnalyze() {
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()

                    if (bitmap != null) {
                        floatingServiceInstance?.log("Captura OK: ${bitmap.width}x${bitmap.height}")
                        analyzeWithOCR(bitmap)
                    } else {
                        floatingServiceInstance?.log("ERROR: bitmap null tras captura")
                        floatingServiceInstance?.showErrorPublic("E:BMP")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    floatingServiceInstance?.log("ERROR captura: código $errorCode")
                    floatingServiceInstance?.showErrorPublic("E:CAP$errorCode")
                }
            }
        )
    }

    private fun analyzeWithOCR(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                floatingServiceInstance?.log("OCR OK — texto: ${text.take(400)}")
                val tripData = extractTripData(text)
                Handler(Looper.getMainLooper()).post {
                    if (tripData != null) {
                        floatingServiceInstance?.updateCircles(tripData.clpHora, tripData.clpKm)
                    } else {
                        floatingServiceInstance?.log("No se encontraron datos de viaje en OCR")
                        floatingServiceInstance?.showErrorPublic("E:OCR")
                    }
                    floatingServiceInstance?.setAnalyzingDone()
                }
            }
            .addOnFailureListener { e ->
                floatingServiceInstance?.log("OCR falló: ${e.message}")
                floatingServiceInstance?.showErrorPublic("E:OCR")
                floatingServiceInstance?.setAnalyzingDone()
            }
    }

    private fun extractTripData(text: String): TripData? {
        return try {
            // Tarifa principal — primer CLP seguido de número
            val tarifaRegex = Regex("""CLP\s*(\d[\d.,]*)""")
            val tarifaStr = tarifaRegex.find(text)?.groupValues?.get(1) ?: run {
                floatingServiceInstance?.log("No se encontró tarifa CLP")
                return null
            }
            val tarifa = tarifaStr.replace(".", "").replace(",", "").toInt()

            // Bono de inicio — +CLP seguido de número (opcional)
            val bonoRegex = Regex("""\+CLP\s*(\d[\d.,]*)""")
            val bonoStr = bonoRegex.find(text)?.groupValues?.get(1)
            val bono = bonoStr?.replace(".", "")?.replace(",", "")?.toIntOrNull() ?: 0

            // Pares "X min (Y,Z km)" — primero = ir a buscar, segundo = viaje
            val parRegex = Regex("""(\d+)\s*min\s*\((\d+[.,]\d+)\s*km\)""")
            val pares = parRegex.findAll(text).toList()

            if (pares.size < 2) {
                floatingServiceInstance?.log("Pares min/km insuficientes: ${pares.size} encontrados")
                return null
            }

            val minBuscar = pares[0].groupValues[1].toInt()
            val kmBuscar  = pares[0].groupValues[2].replace(",", ".").toDouble()
            val minViaje  = pares[1].groupValues[1].toInt()
            val kmViaje   = pares[1].groupValues[2].replace(",", ".").toDouble()

            floatingServiceInstance?.log(
                "Datos extraídos — tarifa=$tarifa bono=$bono " +
                "buscar=${minBuscar}min/${kmBuscar}km viaje=${minViaje}min/${kmViaje}km"
            )

            val total    = tarifa + bono
            val totalMin = (minBuscar + minViaje).toDouble().coerceAtLeast(1.0)
            val totalKm  = (kmBuscar + kmViaje).coerceAtLeast(0.1)

            TripData(
                clpHora = ((total / totalMin) * 60).toInt(),
                clpKm   = (total / totalKm).toInt()
            )
        } catch (e: Exception) {
            floatingServiceInstance?.log("Excepción en extracción: ${e.message}")
            null
        }
    }
}

data class TripData(val clpHora: Int, val clpKm: Int)
