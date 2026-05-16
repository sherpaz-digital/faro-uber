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
                        floatingServiceInstance?.updateCircles(
                            tripData.clpHora,
                            tripData.clpKm,
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

    /**
     * FIX BUG 1: cleanOcrText ahora limpia l/I→1 en CUALQUIER contexto numérico
     * antes de aplicar los regex de pares min/km. Así "1l min" → "11 min" correctamente.
     */
    private fun cleanOcrText(text: String): String {
        var result = text

        // Paso 1: corregir confusiones l/I→1, O→0 dentro de secuencias numéricas generales
        // Esto cubre casos como "1l min", "l4 km", "7l km"
        result = result.replace(Regex("""(?<=\d)[lI](?=\d)"""), "1")  // entre dígitos: 1l4 → 114
        result = result.replace(Regex("""(?<=\d)[lI](?=\s)"""), "1")  // al final: 1l → 11
        result = result.replace(Regex("""(?<=\s)[lI](?=\d)"""), "1")  // al inicio: l4 → 14
        result = result.replace(Regex("""(?<=\()[lI](?=\d)"""), "1")  // tras paréntesis: (l4 → (14

        // Paso 2: corregir dentro de valores CLP
        result = result.replace(Regex("""CLP([A-Za-z0-9,.]*)""")) { match ->
            val inner = match.groupValues[1]
                .replace('l', '1')
                .replace('I', '1')
                .replace('O', '0')
                .replace('Z', '7')
                .replace('S', '5')
            "CLP$inner"
        }

        // Paso 3: corregir dentro de pares min/km
        result = result.replace(Regex("""(\d+)\s*min\s*\(([A-Za-z0-9,.]+)\s*km\)""")) { match ->
            val mins = match.groupValues[1]
            val km = match.groupValues[2]
                .replace('l', '1')
                .replace('I', '1')
                .replace('O', '0')
            "${mins} min (${km} km)"
        }

        return result
    }

    private fun parseCLP(raw: String): Int {
        val clean = raw
            .replace(".", "")
            .replace(",", "")
        return clean.toIntOrNull() ?: 0
    }

    /**
     * FIX BUG 2: bono "incluido" se ignora — ya está en la tarifa.
     * Solo se suma el bono que dice "por inicio de viaje".
     *
     * FIX BUG 3: el regex de pares ahora acepta km sin decimal (ej: "71 km")
     * y aplica corrección automática si km > 50 en viaje corto → inserta punto decimal.
     */
    private fun extractTripData(rawText: String): TripData? {
        return try {
            val text = cleanOcrText(rawText)

            // Tarifa
            val tarifaRegex = Regex("""CLP\s*(\d[\d.,]*)""")
            val tarifaStr = tarifaRegex.find(text)?.groupValues?.get(1) ?: run {
                floatingServiceInstance?.log("No se encontró tarifa CLP")
                return null
            }
            val tarifa = parseCLP(tarifaStr)
            if (tarifa == 0) {
                floatingServiceInstance?.log("Tarifa parseada como 0 — descartando")
                return null
            }

            // FIX BUG 2: bono solo si dice "por inicio de viaje", ignorar "incluido"
            val bonoRegex = Regex("""\+CLP\s*(\d[\d.,]*)(?:[.,]\d{1,2})?\s*por inicio de viaje""", RegexOption.IGNORE_CASE)
            val bonoStr = bonoRegex.find(text)?.groupValues?.get(1)
            val bono = if (bonoStr != null) parseCLP(bonoStr) else 0

            // FIX BUG 3: pares min/km — acepta con o sin decimal
            // Regex principal: con decimal obligatorio (caso normal)
            // Regex fallback: sin decimal (cuando OCR pierde el punto)
            val parConDecimalRegex = Regex("""(\d+)\s*min\s*\((\d+[.,]\d+)\s*km\)""")
            val parSinDecimalRegex = Regex("""(\d+)\s*min\s*\((\d+)\s*km\)""")

            // Buscar todos los pares — primero con decimal, luego sin
            val paresConDecimal = parConDecimalRegex.findAll(text).toList()
            val paresSinDecimal = parSinDecimalRegex.findAll(text).toList()

            // Combinar y ordenar por posición en el texto
            data class Par(val minutos: Int, val km: Double, val pos: Int)

            val todosPares = mutableListOf<Par>()

            paresConDecimal.forEach { m ->
                val km = m.groupValues[2].replace(",", ".").toDoubleOrNull() ?: return@forEach
                todosPares.add(Par(m.groupValues[1].toInt(), km, m.range.first))
            }

            // Agregar sin-decimal solo si no fue capturado ya por el regex con decimal
            paresSinDecimal.forEach { m ->
                val pos = m.range.first
                val yaCapturado = paresConDecimal.any { Math.abs(it.range.first - pos) < 5 }
                if (!yaCapturado) {
                    var km = m.groupValues[2].toDoubleOrNull() ?: return@forEach
                    // FIX: si km >= 50 probablemente el OCR perdió el punto decimal
                    // Ej: "71" en realidad es "7.1", "43" es "4.3"
                    if (km >= 50) km /= 10.0
                    todosPares.add(Par(m.groupValues[1].toInt(), km, pos))
                }
            }

            todosPares.sortBy { it.pos }

            if (todosPares.size < 2) {
                floatingServiceInstance?.log("Pares min/km insuficientes: ${todosPares.size} encontrados")
                return null
            }

            val minBuscar = todosPares[0].minutos
            val kmBuscar  = todosPares[0].km
            val minViaje  = todosPares[1].minutos
            val kmViaje   = todosPares[1].km

            floatingServiceInstance?.log(
                "Datos extraídos — tarifa=$tarifa bono=$bono " +
                "buscar=${minBuscar}min/${kmBuscar}km viaje=${minViaje}min/${kmViaje}km"
            )

            val total    = tarifa + bono
            val totalMin = (minBuscar + minViaje).toDouble().coerceAtLeast(1.0)
            val totalKm  = (kmBuscar + kmViaje).coerceAtLeast(0.1)

            TripData(
                clpHora    = ((total / totalMin) * 60).toInt(),
                clpKm      = (total / totalKm).toInt(),
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
    val minTotales: Int,
    val kmTotales: Double
)
