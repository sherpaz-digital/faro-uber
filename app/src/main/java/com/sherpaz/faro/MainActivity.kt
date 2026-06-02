package com.sherpaz.faro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // Opciones de cada círculo
    private val opcionesTiempo = listOf("$/min", "$/hora", "Total min", "ninguno")
    private val opcionesDist   = listOf("$/km", "Total km", "ninguno")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("faro_prefs", Context.MODE_PRIVATE)

        // --- Tramos ---
        val etRojo     = findViewById<EditText>(R.id.etTramoRojo)
        val etAmarillo = findViewById<EditText>(R.id.etTramoAmarillo)
        val etVerde    = findViewById<EditText>(R.id.etTramoVerde)
        val etMorado   = findViewById<EditText>(R.id.etTramoMorado)
        etRojo.setText(prefs.getInt("tramo_rojo", 9999).toString())
        etAmarillo.setText(prefs.getInt("tramo_amarillo", 12999).toString())
        etVerde.setText(prefs.getInt("tramo_verde", 14999).toString())
        etMorado.setText(prefs.getInt("tramo_morado", 19999).toString())

        // --- Spinners indicadores ---
        val spinTiempoArriba = findViewById<Spinner>(R.id.spinTiempoArriba)
        val spinTiempoCentro = findViewById<Spinner>(R.id.spinTiempoCentro)
        val spinTiempoAbajo  = findViewById<Spinner>(R.id.spinTiempoAbajo)
        val spinDistArriba   = findViewById<Spinner>(R.id.spinDistArriba)
        val spinDistCentro   = findViewById<Spinner>(R.id.spinDistCentro)
        val spinDistAbajo    = findViewById<Spinner>(R.id.spinDistAbajo)

        fun makeAdapter(opciones: List<String>): ArrayAdapter<String> {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, opciones)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            return adapter
        }

        spinTiempoArriba.adapter = makeAdapter(opcionesTiempo)
        spinTiempoCentro.adapter = makeAdapter(opcionesTiempo)
        spinTiempoAbajo.adapter  = makeAdapter(opcionesTiempo)
        spinDistArriba.adapter   = makeAdapter(opcionesDist)
        spinDistCentro.adapter   = makeAdapter(opcionesDist)
        spinDistAbajo.adapter    = makeAdapter(opcionesDist)

        // Cargar valores guardados (defaults = comportamiento actual)
        spinTiempoArriba.setSelection(opcionesTiempo.indexOf(prefs.getString("ind_tiempo_arriba", "$/min")))
        spinTiempoCentro.setSelection(opcionesTiempo.indexOf(prefs.getString("ind_tiempo_centro", "$/hora")))
        spinTiempoAbajo.setSelection(opcionesTiempo.indexOf(prefs.getString("ind_tiempo_abajo", "Total min")))
        spinDistArriba.setSelection(opcionesDist.indexOf(prefs.getString("ind_dist_arriba", "ninguno")))
        spinDistCentro.setSelection(opcionesDist.indexOf(prefs.getString("ind_dist_centro", "$/km")))
        spinDistAbajo.setSelection(opcionesDist.indexOf(prefs.getString("ind_dist_abajo", "Total km")))

        // --- Botones permisos ---
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            )
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // --- Guardar tramos ---
        findViewById<Button>(R.id.btnSaveTramos).setOnClickListener {
            val r = etRojo.text.toString().toIntOrNull() ?: 9999
            val a = etAmarillo.text.toString().toIntOrNull() ?: 12999
            val v = etVerde.text.toString().toIntOrNull() ?: 14999
            val m = etMorado.text.toString().toIntOrNull() ?: 19999
            if (r >= a || a >= v || v >= m) {
                Toast.makeText(this, "Los tramos deben ser ascendentes", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putInt("tramo_rojo", r)
                .putInt("tramo_amarillo", a)
                .putInt("tramo_verde", v)
                .putInt("tramo_morado", m)
                .apply()
            Toast.makeText(this,
                "Tramos guardados:\n🔴 0–$r  🟡 ${r+1}–$a\n🟢 ${a+1}–$v  🟣 ${v+1}–$m  🔵 ${m+1}+",
                Toast.LENGTH_LONG).show()
        }

        // --- Guardar indicadores ---
        findViewById<Button>(R.id.btnSaveIndicadores).setOnClickListener {
            prefs.edit()
                .putString("ind_tiempo_arriba", spinTiempoArriba.selectedItem.toString())
                .putString("ind_tiempo_centro", spinTiempoCentro.selectedItem.toString())
                .putString("ind_tiempo_abajo",  spinTiempoAbajo.selectedItem.toString())
                .putString("ind_dist_arriba",   spinDistArriba.selectedItem.toString())
                .putString("ind_dist_centro",   spinDistCentro.selectedItem.toString())
                .putString("ind_dist_abajo",    spinDistAbajo.selectedItem.toString())
                .apply()
            Toast.makeText(this, "Indicadores guardados", Toast.LENGTH_SHORT).show()
        }

        // --- Iniciar / Detener ---
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Activa primero el permiso flotante", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startForegroundService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Faro iniciado", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "Faro detenido", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val tvO = findViewById<TextView>(R.id.tvOverlayStatus)
        val tvA = findViewById<TextView>(R.id.tvAccessStatus)
        tvO.text = if (Settings.canDrawOverlays(this))
            "Permiso flotante: ACTIVO" else "Permiso flotante: INACTIVO"
        val svc = "${packageName}/${UberAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        tvA.text = if (enabled.contains(svc))
            "Accesibilidad: ACTIVA" else "Accesibilidad: INACTIVA"
    }
}
