package com.sherpaz.faro

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.ContentValues
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName == "com.ubercab.driver") {

            floatingServiceInstance?.bringOverlayToFront()
            tryReadViewTree()
        }
    }

    private fun tryReadViewTree() {
        try {
            val root = rootInActiveWindow
            if (root == null) {
                floatingServiceInstance?.log("ÁRBOL: rootInActiveWindow null")
                return
            }

            if (root.packageName != "com.ubercab.driver") {
                floatingServiceInstance?.log("ÁRBOL: ventana activa no es Uber (${root.packageName})")
                root.recycle()
                return
            }

            val textos = mutableListOf<String>()
            collectTexts(root, textos)
            root.recycle()

            if (textos.isEmpty()) {
                floatingServiceInstance?.log("ÁRBOL: vacío — Uber probablemente bloquea sus nodos")
                return
            }

            val textoCompleto = textos.joinToString("\n")
            floatingServiceInstance?.log("ÁRBOL OK — textos encontrados (${textos.size} nodos):")
            floatingServiceInstance?.log("ÁRBOL texto: ${textoCompleto.take(500)}")

            val tripData = extractTripData(textoCompleto)
            if (tripData != null) {
                floatingServiceInstance?.log(
                    "ÁRBOL datos — clpHora=${tripData.clpHora} clpKm=${tripData.clpKm} " +
                    "min=${tripData.minTotales} km=${tripData.kmTotales}"
                )
                Handler(Looper.getMainLooper()).post {
                    floatingServiceInstance?.updateCircles(
                        tripData.clpHora,
                        tripData.clpKm,
                        tripData.clpMin,
                        tripData.minTotales,
                        tripData.kmTotales
                    )
                }
            } else {
                floatingServiceInstance?.log("ÁRBOL: texto encontrado pero no se pudieron extraer datos de viaje")
            }

        } catch (e: Exception) {
            floatingServiceInstance?.log("ÁRBOL error: ${e.message}")
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (!text.isNullOrBlank()) result.add(text.trim())
        if (!desc.isNullOrBlank() && desc != text) result.add(desc.trim())

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, result)
            child.recycle()
        }
    }

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

                        val cropTop = (bitmap.height * 0.20).toInt()
                        val cropped = Bitmap.createBitmap(
                            bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop
                        )
                        floatingServiceInstance?.log("Recorte: desde y=$cropTop — ${cropped.width}x${cropped.height}")

                        saveBitmapToGallery(bitmap)
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

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "faro_$timestamp.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Faro")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                floatingServiceInstance?.log("Screenshot guardado: $filename")
            } else {
                floatingServiceInstance?.log("ERROR: no se pudo crear URI en MediaStore")
            }
        } catch (e: Exception) {
            floatingServiceInstance?.log("ERROR al guardar screenshot: ${e.message}")
        }
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
                        // Verificar zona y actualizar indicadores
                        val comunas = floatingServiceInstance?.getComunasZona() ?: emptySet()
                        val origenEnZona  = comunas.any { tripData.origenComuna.contains(it) }
                        val destinoEnZona = comunas.any { tripData.destinoComuna.contains(it) }
                        floatingServiceInstance?.updateZoneIndicators(origenEnZona, destinoEnZona)
                        floatingServiceInstance?.log(
                            "Zona — comunas:$comunas | " +
                            "origen:\"${tripData.origenComuna}\" (${if (origenEnZona) "✓" else "✗"}) | " +
                            "destino:\"${tripData.destinoComuna}\" (${if (destinoEnZona) "✓" else "✗"})"
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
        cleaned = cleaned.replace(".,", ".").replace(",.", ".").replace("..", ".")
        cleaned = cleaned.replace("knm", "km")
        cleaned = cleaned.replace(Regex("""(\d+[.,]\d+)\s+m\)""")) { match ->
            "${match.groupValues[1]} km)"
        }
        cleaned = cleaned.replace(Regex("""[a-zA-Z](\d+)\s+min""")) { match ->
            "${match.groupValues[1]} min"
        }
        cleaned = cleaned.replace(Regex("""([lIO\d][lIO\d.,]*)\s*(min|km)""")) { match ->
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
        val clean = raw.replace(".", "").replace(",", "")
        return clean.toIntOrNull() ?: 0
    }

    /**
     * Extrae la comuna de una línea de dirección.
     * Las direcciones de Uber tienen formato: "Calle X 1234, Comuna, Ciudad"
     * La comuna es el penúltimo segmento separado por coma.
     */
    private fun extractComuna(addressLine: String): String {
        val partes = addressLine.split(",").map { it.trim() }
        return if (partes.size >= 2) partes[partes.size - 2].lowercase() else ""
    }

    /**
     * Busca las líneas de dirección en el texto OCR.
     * Retorna par (origenComuna, destinoComuna).
     */
    private fun extractComunas(text: String): Pair<String, String> {
        val addressRegex = Regex("""[A-ZÁÉÍÓÚÑ][^,\n]+,\s*[A-ZÁÉÍÓÚÑ][^,\n]+,\s*[A-ZÁÉÍÓÚÑ][^\n]+""")
        val direcciones = addressRegex.findAll(text).map { it.value }.toList()
        val origen  = if (direcciones.isNotEmpty()) extractComuna(direcciones[0]) else ""
        val destino = if (direcciones.size >= 2)    extractComuna(direcciones[1]) else ""
        return Pair(origen, destino)
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

            // Extraer comunas de origen y destino del texto OCR original
            val (origenComuna, destinoComuna) = extractComunas(rawText)

            floatingServiceInstance?.log(
                "Datos extraídos — tarifa=$tarifa " +
                "buscar=${minBuscar}min/${kmBuscar}km viaje=${minViaje}min/${kmViaje}km " +
                "origen=\"$origenComuna\" destino=\"$destinoComuna\""
            )

            val totalMin = (minBuscar + minViaje).toDouble().coerceAtLeast(1.0)
            val totalKm  = (kmBuscar + kmViaje).coerceAtLeast(0.1)

            TripData(
                clpHora       = ((tarifa / totalMin) * 60).toInt(),
                clpKm         = (tarifa / totalKm).toInt(),
                clpMin        = (tarifa / totalMin).toInt(),
                minTotales    = minBuscar + minViaje,
                kmTotales     = kmBuscar + kmViaje,
                origenComuna  = origenComuna,
                destinoComuna = destinoComuna
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
    val kmTotales: Double,
    val origenComuna: String = "",
    val destinoComuna: String = ""
)
