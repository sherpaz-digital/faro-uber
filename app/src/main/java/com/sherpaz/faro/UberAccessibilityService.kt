package com.sherpaz.faro

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class UberAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()
    private var lastText = ""
    private var isProcessing = false

    companion object {
        var floatingServiceInstance: FloatingService? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isProcessing) return
        val root = rootInActiveWindow ?: return
        val allText = extractAllText(root)
        if (allText == lastText || !looksLikeUberRequest(allText)) return
        lastText = allText
        isProcessing = true
        scope.launch {
            try {
                val data = extractWithClaude(allText)
                if (data != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        floatingServiceInstance?.updateCircles(data.clpHora, data.clpKm)
                    }
                }
            } finally {
                delay(3000)
                isProcessing = false
            }
        }
    }

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun traverse(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (!n.text.isNullOrBlank()) sb.append(n.text).append("\n")
            for (i in 0 until n.childCount) traverse(n.getChild(i))
        }
        traverse(node)
        return sb.toString().trim()
    }

    private fun looksLikeUberRequest(text: String): Boolean {
        val l = text.lowercase()
        return (l.contains("km") || l.contains("min")) &&
               (l.contains("$") || l.contains("tarifa") || l.contains("viaje") || l.contains("recoger"))
    }

    private suspend fun extractWithClaude(screenText: String): TripData? {
        val prefs = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: return null
        if (apiKey.isEmpty()) return null
        val prompt = """
Eres un extractor de datos de solicitudes de Uber Driver en Chile.
Del siguiente texto extrae SOLO estos valores numericos:
- tarifa: monto CLP del viaje
- bono_inicio: bono de inicio en CLP (0 si no hay)
- km_buscar: km para ir a buscar pasajero
- min_buscar: minutos para ir a buscar
- km_viaje: km del viaje
- min_viaje: minutos del viaje
Responde SOLO con JSON valido sin explicaciones:
{"tarifa":0,"bono_inicio":0,"km_buscar":0.0,"min_buscar":0,"km_viaje":0.0,"min_viaje":0}
TEXTO: $screenText""".trimIndent()
        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-20250514")
            put("max_tokens", 200)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", prompt)
            }))
        }
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .build()
        return try {
            val resp = client.newCall(request).execute()
            val json = JSONObject(resp.body?.string() ?: return null)
            val data = JSONObject(json.getJSONArray("content").getJSONObject(0).getString("text").trim())
            val tarifa = data.optInt("tarifa", 0)
            val bono = data.optInt("bono_inicio", 0)
            val kmB = data.optDouble("km_buscar", 0.0)
            val minB = data.optInt("min_buscar", 0)
            val kmV = data.optDouble("km_viaje", 0.0)
            val minV = data.optInt("min_viaje", 0)
            if (tarifa == 0 || kmV == 0.0 || minV == 0) return null
            val total = tarifa + bono
            TripData(
                clpHora = ((total.toDouble() / (minB + minV)) * 60).toInt(),
                clpKm = (total / (kmB + kmV)).toInt()
            )
        } catch (e: Exception) { null }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}

data class TripData(val clpHora: Int, val clpKm: Int)
