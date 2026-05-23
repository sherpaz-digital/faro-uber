package com.sherpaz.faro

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var tabActual = "inicio"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)

        // ── Tramos $/hora ──
        val etRojo     = findViewById<EditText>(R.id.etTramoRojo)
        val etAmarillo = findViewById<EditText>(R.id.etTramoAmarillo)
        val etVerde    = findViewById<EditText>(R.id.etTramoVerde)
        val etMorado   = findViewById<EditText>(R.id.etTramoMorado)

        etRojo.setText(prefs.getInt("tramo_rojo", 9999).toString())
        etAmarillo.setText(prefs.getInt("tramo_amarillo", 12999).toString())
        etVerde.setText(prefs.getInt("tramo_verde", 14999).toString())
        etMorado.setText(prefs.getInt("tramo_morado", 19999).toString())

        // ── Tabs ──
        val tabInicio  = findViewById<TextView>(R.id.tabInicio)
        val tabAjustes = findViewById<TextView>(R.id.tabAjustes)
        val panelInicio  = findViewById<View>(R.id.panelInicio)
        val panelAjustes = findViewById<View>(R.id.panelAjustes)

        fun showTab(tab: String) {
            tabActual = tab
            panelInicio.visibility  = if (tab == "inicio") View.VISIBLE else View.GONE
            panelAjustes.visibility = if (tab == "ajustes") View.VISIBLE else View.GONE
            tabInicio.setTextColor(if (tab == "inicio") 0xFFFFFFFF.toInt() else 0xFF555555.toInt())
            tabAjustes.setTextColor(if (tab == "ajustes") 0xFFFFFFFF.toInt() else 0xFF555555.toInt())
        }

        tabInicio.setOnClickListener { showTab("inicio") }
        tabAjustes.setOnClickListener { showTab("ajustes") }
        showTab("inicio")

        // ── Círculo de estado en Inicio ──
        val viewStatusCircle = findViewById<View>(R.id.viewStatusCircle)
        val tvStatusLabel    = findViewById<TextView>(R.id.tvStatusLabel)
        actualizarCirculoEstado(viewStatusCircle, tvStatusLabel, false)

        // ── Botones INICIAR / DETENER ──
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Activa primero el permiso flotante", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startForegroundService(Intent(this, FloatingService::class.java))
            actualizarCirculoEstado(viewStatusCircle, tvStatusLabel, true)
            Toast.makeText(this, "Faro iniciado", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            actualizarCirculoEstado(viewStatusCircle, tvStatusLabel, false)
            Toast.makeText(this, "Faro detenido", Toast.LENGTH_SHORT).show()
        }

        // ── Permisos ──
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ── Guardar tramos ──
        findViewById<Button>(R.id.btnSaveTramos).setOnClickListener {
            val r = etRojo.text.toString().toIntOrNull() ?: 9999
            val a = etAmarillo.text.toString().toIntOrNull() ?: 12999
            val v = etVerde.text.toString().toIntOrNull() ?: 14999
            val m = etMorado.text.toString().toIntOrNull() ?: 19999
            if (r >= a || a >= v || v >= m) {
                Toast.makeText(this, "Los tramos deben ser ascendentes", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit().putInt("tramo_rojo", r).putInt("tramo_amarillo", a)
                .putInt("tramo_verde", v).putInt("tramo_morado", m).apply()
            Toast.makeText(this, "Tramos guardados", Toast.LENGTH_SHORT).show()
        }

        // ── Zona — agregar comunas ──
        renderComunas()

        findViewById<Button>(R.id.btnAgregarComuna).setOnClickListener {
            val etNueva = findViewById<EditText>(R.id.etNuevaComuna)
            val nueva = etNueva.text.toString().trim()
            if (nueva.isBlank()) return@setOnClickListener
            val comunas = getComunas().toMutableSet()
            comunas.add(nueva.lowercase())
            saveComunas(comunas)
            etNueva.text.clear()
            renderComunas()
            Toast.makeText(this, "\"$nueva\" agregada a zona", Toast.LENGTH_SHORT).show()
        }
    }

    private fun actualizarCirculoEstado(view: View, label: TextView, activo: Boolean) {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (activo) {
                setColor(0xFF0D47A1.toInt())
                setStroke(4, 0xFF42A5F5.toInt())
            } else {
                setColor(0xFF1a1a1a.toInt())
                setStroke(2, 0xFF333333.toInt())
            }
        }
        view.background = drawable
        label.text = if (activo) "ACTIVO" else "INACTIVO"
        label.setTextColor(if (activo) 0xFF42A5F5.toInt() else 0xFF666666.toInt())
    }

    private fun getComunas(): Set<String> {
        val prefs = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("zona_comunas", "") ?: ""
        return if (raw.isBlank()) emptySet() else raw.split("|").toSet()
    }

    private fun saveComunas(comunas: Set<String>) {
        val prefs = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("zona_comunas", comunas.joinToString("|")).apply()
    }

    private fun renderComunas() {
        val layout = findViewById<LinearLayout>(R.id.layoutComunas)
        layout.removeAllViews()
        val comunas = getComunas()
        if (comunas.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Sin comunas configuradas"
                setTextColor(0xFF444444.toInt())
                textSize = 12f
            }
            layout.addView(tv)
            return
        }
        comunas.sorted().forEach { comuna ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
            }
            val tv = TextView(this).apply {
                text = comuna.replaceFirstChar { it.uppercase() }
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btn = Button(this).apply {
                text = "✕"
                setTextColor(0xFF888888.toInt())
                setBackgroundColor(0x00000000.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    val set = getComunas().toMutableSet()
                    set.remove(comuna)
                    saveComunas(set)
                    renderComunas()
                }
            }
            row.addView(tv)
            row.addView(btn)
            layout.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        val tvO = findViewById<TextView>(R.id.tvOverlayStatus)
        val tvA = findViewById<TextView>(R.id.tvAccessStatus)

        val overlayOk = Settings.canDrawOverlays(this)
        tvO.text = if (overlayOk) "Permiso flotante: ACTIVO" else "Permiso flotante: INACTIVO"
        tvO.setTextColor(if (overlayOk) 0xFF1a9e3a.toInt() else 0xFFe03030.toInt())

        val svc = "${packageName}/${UberAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val accOk = enabled.contains(svc)
        tvA.text = if (accOk) "Accesibilidad: ACTIVA" else "Accesibilidad: INACTIVA"
        tvA.setTextColor(if (accOk) 0xFF1a9e3a.toInt() else 0xFFe03030.toInt())
    }
}
