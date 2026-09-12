package com.foldfx

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs

/**
 * Écoute le capteur d'angle de charnière et, pendant l'ouverture ou la fermeture,
 * affiche par-dessus l'écran une fenêtre transparente aux touches qui joue l'effet.
 */
class FoldFxService : Service(), SensorEventListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val CHANNEL = "foldfx"
        private const val NOTIF_ID = 1

        const val FULL_OPEN = 166f      // au-delà : considéré ouvert
        const val FULL_CLOSED = 8f      // en dessous : considéré fermé
        const val TRIGGER_DELTA = 9f    // mouvement mini pour déclencher
        const val PROG_START = 12f
        const val PROG_END = 162f
        const val STALL_MS = 650L       // immobile = mode Flex -> on retire l'effet

        @Volatile var running = false

        fun progressFor(angle: Float): Float =
            ((angle - PROG_START) / (PROG_END - PROG_START)).coerceIn(0f, 1f)
    }

    private lateinit var sensorManager: SensorManager
    private var hinge: Sensor? = null
    private lateinit var windowContext: Context
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var overlay: FoldEffectView? = null
    private var animating = false
    private var finishing = false
    private var anchor = -1f
    private var lastAngle = -1f
    private var lastMove = 0L

    private var innerBmp: Bitmap? = null
    private var coverBmp: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startInForeground()

        val dm = getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        windowContext = createWindowContext(
            display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
        )
        wm = windowContext.getSystemService(WindowManager::class.java)

        sensorManager = getSystemService(SensorManager::class.java)
        hinge = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

        loadImages()
        Prefs.get(this).registerOnSharedPreferenceChangeListener(this)

        val s = hinge
        if (s == null) {
            stopSelf()
        } else {
            sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        sensorManager.unregisterListener(this)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Capteur

    override fun onSensorChanged(event: SensorEvent) {
        val a = event.values[0]
        val now = SystemClock.uptimeMillis()
        if (lastAngle < 0f || abs(a - lastAngle) > 1.2f) lastMove = now
        lastAngle = a
        if (anchor < 0f) anchor = a

        if (!animating) {
            if (a >= FULL_OPEN || a <= FULL_CLOSED) {
                anchor = a
                return
            }
            if (abs(a - anchor) >= TRIGGER_DELTA && canAnimate()) startAngleAnim(a)
            return
        }

        if (finishing) return
        overlay?.setProgress(progressFor(a))
        when {
            a >= FULL_OPEN -> fadeOutAndRemove()
            a <= FULL_CLOSED -> finishClosed()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- Animation

    private fun canAnimate(): Boolean {
        if (overlay != null) return false
        if (!Settings.canDrawOverlays(this)) return false
        val km = getSystemService(KeyguardManager::class.java)
        // Les overlays ne s'affichent pas au-dessus de l'écran de verrouillage
        return !km.isKeyguardLocked
    }

    private fun startAngleAnim(angle: Float) {
        val bmp = innerBmp ?: return
        val v = FoldEffectView(windowContext)
        v.intensity = Prefs.intensity(this)
        v.mode = 0f
        v.tauMs = 40f
        v.setBitmap(bmp)
        v.setProgress(progressFor(angle), immediate = true)
        v.onGeometryChange = { applyGeometry(v) }
        applyGeometry(v)

        try {
            wm.addView(v, overlayParams())
        } catch (e: Exception) {
            return
        }
        overlay = v
        animating = true
        finishing = false
        lastMove = SystemClock.uptimeMillis()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 120)
    }

    /** Oriente l'effet selon la rotation de l'écran et le réglage gauche/droite. */
    private fun applyGeometry(v: FoldEffectView) {
        val rot = windowContext.display?.rotation ?: Surface.ROTATION_0
        var (axis, side) = when (rot) {
            Surface.ROTATION_90 -> 1f to -1f
            Surface.ROTATION_180 -> 0f to -1f
            Surface.ROTATION_270 -> 1f to 1f
            else -> 0f to 1f
        }
        if (Prefs.leftMoving(this)) side = -side
        v.setGeometry(axis, side)
    }

    /** Si l'angle ne bouge plus (téléphone posé à moitié ouvert), on rend la main. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!animating || finishing) return
            if (SystemClock.uptimeMillis() - lastMove > STALL_MS) {
                fadeOutAndRemove()
            } else {
                handler.postDelayed(this, 120)
            }
        }
    }

    /** Ouverture terminée : l'effet se "met au point" puis disparaît. */
    private fun fadeOutAndRemove() {
        val v = overlay ?: return
        finishing = true
        anchor = lastAngle
        handler.removeCallbacks(watchdog)
        v.setProgress(1f)
        v.animate().alpha(0f).setStartDelay(60).setDuration(220)
            .withEndAction { removeOverlay() }.start()
    }

    /** Fermeture terminée : on bascule sur l'écran externe avec une mise au point. */
    private fun finishClosed() {
        val v = overlay ?: return
        finishing = true
        anchor = lastAngle
        handler.removeCallbacks(watchdog)
        v.setProgress(0f, immediate = true)

        handler.postDelayed({
            val pm = getSystemService(PowerManager::class.java)
            val cover = coverBmp
            if (overlay !== v || !pm.isInteractive || cover == null || !canShowOnCover()) {
                removeOverlay()
                return@postDelayed
            }
            v.mode = 1f
            v.tauMs = 110f
            v.setBitmap(cover)
            v.setProgress(0f, immediate = true)
            v.setProgress(1f)
            v.animate().alpha(0f).setStartDelay(420).setDuration(200)
                .withEndAction { removeOverlay() }.start()
        }, 160)
    }

    private fun canShowOnCover(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        return !km.isKeyguardLocked
    }

    private fun removeOverlay() {
        val v = overlay
        overlay = null
        animating = false
        finishing = false
        handler.removeCallbacks(watchdog)
        if (v != null) {
            v.animate().cancel()
            try {
                wm.removeViewImmediate(v)
            } catch (_: Exception) {
            }
        }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        windowAnimations = 0
        title = "FoldFX"
    }

    // ---------------------------------------------------------------- Images

    private fun loadImages() {
        val fallback by lazy { ImageStore.fallback() }
        innerBmp = ImageStore.load(this, ImageStore.INNER) ?: fallback
        coverBmp = ImageStore.load(this, ImageStore.COVER) ?: innerBmp
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == Prefs.KEY_IMAGES) loadImages()
    }

    // ---------------------------------------------------------------- Notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "FoldFX actif", NotificationManager.IMPORTANCE_MIN)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("FoldFX est actif")
            .setContentText("Transition à l'ouverture et à la fermeture")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }
}
