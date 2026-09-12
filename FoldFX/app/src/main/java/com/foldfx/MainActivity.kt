package com.foldfx

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Outline
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {

    companion object {
        private const val REQ_INNER = 10
        private const val REQ_COVER = 11
    }

    private lateinit var sensorManager: SensorManager
    private var hinge: Sensor? = null

    private lateinit var statusText: TextView
    private lateinit var angleText: TextView
    private lateinit var serviceSwitch: Switch
    private lateinit var preview: FoldEffectView
    private lateinit var previewSeek: SeekBar
    private var followHinge = false
    private var syncingUi = false
    private var previewCover = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SensorManager::class.java)
        hinge = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        setContentView(buildUi())
        refreshPreviewImage()

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        hinge?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(40))
        }

        fun text(t: String, size: Float = 15f, bold: Boolean = false, alpha: Float = 1f) =
            TextView(this).apply {
                text = t
                textSize = size
                this.alpha = alpha
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }

        fun section(t: String) = text(t, 17f, bold = true).apply {
            setPadding(0, dp(24), 0, dp(6))
        }

        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        col.addView(text("FoldFX", 30f, bold = true))
        col.addView(text("La transition de l'iPhone Duo, sur ton Fold.", 15f, alpha = 0.75f))

        statusText = text("", 14f, alpha = 0.9f).apply { setPadding(0, dp(12), 0, 0) }
        col.addView(statusText)
        angleText = text("Angle de charnière : —", 14f, alpha = 0.75f)
        col.addView(angleText)

        col.addView(section("Autorisations"))
        col.addView(button("Autoriser l'affichage par-dessus les apps") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })
        col.addView(button("Ne jamais mettre en veille FoldFX") { askBatteryExemption() })

        col.addView(section("Effet"))
        serviceSwitch = Switch(this).apply {
            text = "Activer la transition"
            textSize = 16f
            isChecked = Prefs.enabled(this@MainActivity)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on -> if (!syncingUi) toggleService(on) }
        }
        col.addView(serviceSwitch)
        col.addView(Switch(this).apply {
            text = "La moitié qui bouge est à gauche"
            isChecked = Prefs.leftMoving(this@MainActivity)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on ->
                Prefs.get(this@MainActivity).edit().putBoolean(Prefs.KEY_LEFT, on).apply()
                preview.setGeometry(0f, if (on) -1f else 1f)
            }
        })

        col.addView(text("Intensité du flou", 14f, alpha = 0.8f).apply {
            setPadding(0, dp(12), 0, 0)
        })
        col.addView(SeekBar(this).apply {
            max = 100
            progress = (((Prefs.intensity(this@MainActivity) - 0.4f) / 1.2f) * 100).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    val v = 0.4f + value / 100f * 1.2f
                    preview.intensity = v
                    if (fromUser) {
                        Prefs.get(this@MainActivity).edit()
                            .putFloat(Prefs.KEY_INTENSITY, v).apply()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })

        col.addView(section("Fonds d'écran"))
        col.addView(text(
            "Astuce : fais une capture de ton écran d'accueil (interne et externe) " +
                "et choisis-la ici. La transition se fondra alors dans ton vrai écran.",
            13f, alpha = 0.7f
        ))
        col.addView(button("Choisir l'image de l'écran interne") { pickImage(REQ_INNER) })
        col.addView(button("Choisir l'image de l'écran externe") { pickImage(REQ_COVER) })

        col.addView(section("Aperçu"))
        preview = FoldEffectView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
            )
            intensity = Prefs.intensity(this@MainActivity)
            setGeometry(0f, if (Prefs.leftMoving(this@MainActivity)) -1f else 1f)
            tauMs = 30f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat())
                }
            }
            clipToOutline = true
        }
        col.addView(preview)
        previewSeek = SeekBar(this).apply {
            max = 1000
            progress = 350
            setPadding(dp(8), dp(16), dp(8), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) preview.setProgress(value / 1000f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        col.addView(previewSeek)
        col.addView(text("Fermé ← glisse → ouvert", 12f, alpha = 0.6f))
        col.addView(Switch(this).apply {
            text = "Piloter l'aperçu avec la charnière"
            setPadding(0, dp(12), 0, dp(8))
            setOnCheckedChangeListener { _, on -> followHinge = on }
        })
        col.addView(Switch(this).apply {
            text = "Aperçu de l'écran externe"
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on ->
                previewCover = on
                preview.mode = if (on) 1f else 0f
                refreshPreviewImage()
            }
        })
        preview.setProgress(0.35f, immediate = true)

        return ScrollView(this).apply {
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val lines = mutableListOf<String>()
        lines += if (hinge != null) "✓ Capteur de charnière détecté"
        else "✗ Aucun capteur de charnière : l'effet ne peut pas fonctionner sur cet appareil"
        lines += if (overlayOk) "✓ Affichage par-dessus les apps autorisé"
        else "✗ Autorise l'affichage par-dessus les apps"
        lines += if (batteryOk) "✓ Pas de mise en veille"
        else "• Conseillé : empêcher la mise en veille de FoldFX"
        statusText.text = lines.joinToString("\n")

        // Relance le service s'il a été tué par le système alors qu'il est activé
        val enabled = Prefs.enabled(this)
        if (enabled && !FoldFxService.running && overlayOk && hinge != null) {
            startForegroundService(Intent(this, FoldFxService::class.java))
        }
        syncingUi = true
        serviceSwitch.isChecked = enabled
        syncingUi = false
    }

    // ---------------------------------------------------------------- Actions

    private fun toggleService(on: Boolean) {
        val intent = Intent(this, FoldFxService::class.java)
        if (on) {
            if (hinge == null) {
                toast("Pas de capteur de charnière sur cet appareil.")
                serviceSwitch.isChecked = false
                return
            }
            if (!Settings.canDrawOverlays(this)) {
                toast("Autorise d'abord l'affichage par-dessus les apps.")
                serviceSwitch.isChecked = false
                return
            }
            Prefs.get(this).edit().putBoolean(Prefs.KEY_ENABLED, true).apply()
            startForegroundService(intent)
        } else {
            Prefs.get(this).edit().putBoolean(Prefs.KEY_ENABLED, false).apply()
            stopService(intent)
        }
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("C'est déjà fait.")
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun pickImage(req: Int) {
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        startActivityForResult(intent, req)
    }

    @Deprecated("API Activity simple, sans AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = if (requestCode == REQ_INNER) ImageStore.INNER else ImageStore.COVER
        Thread {
            val ok = ImageStore.save(this, uri, name)
            runOnUiThread {
                if (ok) {
                    Prefs.bumpImages(this)
                    refreshPreviewImage()
                    toast("Image enregistrée.")
                } else {
                    toast("Impossible de lire cette image.")
                }
            }
        }.start()
    }

    private fun refreshPreviewImage() {
        Thread {
            val inner = ImageStore.load(this, ImageStore.INNER)
            val cover = ImageStore.load(this, ImageStore.COVER)
            val bmp = (if (previewCover) cover ?: inner else inner) ?: ImageStore.fallback()
            runOnUiThread { preview.setBitmap(bmp) }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- Capteur

    override fun onSensorChanged(event: SensorEvent) {
        val a = event.values[0]
        angleText.text = "Angle de charnière : ${a.roundToInt()}°"
        if (followHinge) {
            val p = FoldFxService.progressFor(a)
            preview.setProgress(p)
            previewSeek.progress = (p * 1000).roundToInt()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
