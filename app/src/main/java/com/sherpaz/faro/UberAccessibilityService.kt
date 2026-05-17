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

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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

                        // Recortar el 80% inferior — cubre paneles altos de solicitud
                        val cropTop = (bitmap.height * 0.20).toInt()
                        val cropped = Bitmap.createBitmap(
                            bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop
                        )
                        floatingServiceInstance?.log("Recorte: desde y=$cropTop — ${cropped.width}x${cropped.height}")

                        analyzeWithOCR(cropped)
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

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                floatingServiceInstance?.log("OCR OK — texto: ${text.take(500)}")
                val tripData = extractTripData(text)
                Handler(Looper.getMainLooper()).post {
                    if (tripData != null) {
                        floatingServiceInstance?.updateCircles(
                            tripData.clpHora,
                            tripData.clpKm,
                            tripData.clpMin,
                            tripData.minTotales,
                            tripData.kmTotales
                        )
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

    private fun cleanOcrText(text: String): String {
        var cleaned = text.replace(Regex("""(?<!\+)CLP([A-Za-z0-9,.]*)""")) { match ->
            val inner = match.groupValues[1]
                .replace('l', '1')
                .replace('I', '1')
                .replace('O', '0')
                .replace('Z', '7')
            "CLP$inner"
        }

        cleaned = cleaned.replace(Regex("""(\d[lIO\d.,]*)\s*(min|km)""")) { match ->
            val num = match.groupValues[1]
                .replace('l', '1')
                .replace('I', '1')
                .replace('O', '0')
            val unit = match.groupValues[2]
            "$num $unit"
        }

        return cleaned
    }

    private fun parseCLP(raw: String): Int {
        val clean = raw
            .replace(".", "")
            .replace(",", "")
        return clean.toIntOrNull() ?: 0
    }

    private fun extractTripData(rawText: String): TripData? {
        return try {
            val text = cleanOcrText(rawText)
            floatingServiceInstance?.log("Texto limpio: ${text.take(500)}")

            val tarifaRegex = Regex("""(?<!\+)CLP\s*(\d[\d,]*)""")
            val tarifaStr = tarifaRegex.find(text)?.groupValues?.get(1) ?: run {
                floatingServiceInstance?.log("No se encontró tarifa CLP (sin +)")
                return null
            }
            val tarifa = parseCLP(tarifaStr)
            if (tarifa == 0) {
                floatingServiceInstance?.log("Tarifa parseada como 0 — descartando")
                return null
            }

            val parRegex = Regex("""(\d+)\s*min\s*\((\d+[.,]\d+)\s*km\)""")
            var pares = parRegex.findAll(text).toList()

            if (pares.size < 2) {
                floatingServiceInstance?.log("Pares con decimal insuficientes (${pares.size}), probando fallback")
                val fallbackRegex = Regex("""(\d+)\s*min\s*\((\d+(?:[.,]\d+)?)\s*km\)""")
                pares = fallbackRegex.findAll(text).toList()
            }

            if (pares.size < 2) {
                floatingServiceInstance?.log("Pares min/km insuficientes: ${pares.size}")
                return null
            }

            val minBuscar = pares[0].groupValues[1].toInt()
            var kmBuscar  = pares[0].groupValues[2].replace(",", ".").toDouble()
            val minViaje  = pares[1].groupValues[1].toInt()
            var kmViaje   = pares[1].groupValues[2].replace(",", ".").toDouble()

            if (kmBuscar >= 50) {
                floatingServiceInstance?.log("kmBuscar=$kmBuscar sospechoso (>=50), dividiendo por 10")
                kmBuscar /= 10.0
            }
            if (kmViaje >= 50) {
                floatingServiceInstance?.log("kmViaje=$kmViaje sospechoso (>=50), dividiendo por 10")
                kmViaje /= 10.0
            }

            floatingServiceInstance?.log(
                "Datos extraídos — tarifa=$tarifa " +
                "buscar=${minBuscar}min/${kmBuscar}km viaje=${minViaje}min/${kmViaje}km"
            )

            val totalMin = (minBuscar + minViaje).toDouble().coerceAtLeast(1.0)
            val totalKm  = (kmBuscar + kmViaje).coerceAtLeast(0.1)

            TripData(
                clpHora    = ((tarifa / totalMin) * 60).toInt(),
                clpKm      = (tarifa / totalKm).toInt(),
                clpMin     = (tarifa / totalMin).toInt(),
                minTotales = minBuscar + minViaje,
                kmTotales  = kmBuscar + kmViaje
            )
        } catch (e: Exception) {
            floatingServiceInstance?.log("Excepción en extracción: ${e.message}")
            null
        }
    }
}

data class TripData(
    val clpHora: Int,
    val clpKm: Int,
    val clpMin: Int,
    val minTotales: Int,
    val kmTotales: Double
)
