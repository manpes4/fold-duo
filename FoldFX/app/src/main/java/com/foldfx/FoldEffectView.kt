package com.foldfx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Dessine le fond d'écran à travers le shader de transition.
 * La progression affichée suit la cible avec un lissage exponentiel basé sur le temps,
 * ce qui rend l'effet fluide même si le capteur de charnière envoie peu de valeurs.
 */
class FoldEffectView(context: Context) : View(context) {

    private val runtimeShader = RuntimeShader(FoldShader.AGSL)
    private val paint = Paint()
    private var bitmap: Bitmap? = null
    private var bitmapShader: BitmapShader? = null
    private var inputReady = false

    var axis = 0f
    var side = 1f
    var mode = 0f
        set(value) { field = value; invalidate() }
    var intensity = 1f
        set(value) { field = value; invalidate() }

    /** Constante de temps du lissage (ms). Petit = réactif, grand = doux. */
    var tauMs = 40f

    /** Appelé quand la taille change (ex. bascule écran externe -> interne). */
    var onGeometryChange: (() -> Unit)? = null

    private var target = 0f
    private var shown = 0f
    private var lastFrame = 0L
    private val t0 = SystemClock.uptimeMillis()

    init {
        paint.shader = runtimeShader
    }

    fun setBitmap(b: Bitmap) {
        bitmap = b
        bitmapShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        inputReady = false
        updateMatrix()
        invalidate()
    }

    fun setProgress(p: Float, immediate: Boolean = false) {
        target = p.coerceIn(0f, 1f)
        if (immediate) shown = target
        postInvalidateOnAnimation()
    }

    fun setGeometry(axis: Float, side: Float) {
        this.axis = axis
        this.side = side
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
        onGeometryChange?.invoke()
    }

    /** Recadre l'image façon "center crop" sur la taille de la vue. */
    private fun updateMatrix() {
        val b = bitmap ?: return
        val bs = bitmapShader ?: return
        if (width == 0 || height == 0) return
        val scale = max(width / b.width.toFloat(), height / b.height.toFloat())
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((width - b.width * scale) / 2f, (height - b.height * scale) / 2f)
        bs.setLocalMatrix(m)
        runtimeShader.setInputShader("image", bs)
        inputReady = true
    }

    override fun onDraw(canvas: Canvas) {
        if (!inputReady || width == 0 || height == 0) return

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else min(50f, (now - lastFrame).toFloat())
        lastFrame = now
        val k = 1f - exp(-dt / tauMs)
        shown += (target - shown) * k
        if (abs(target - shown) < 0.0005f) shown = target

        runtimeShader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
        runtimeShader.setFloatUniform("progress", shown)
        runtimeShader.setFloatUniform("axis", axis)
        runtimeShader.setFloatUniform("side", side)
        runtimeShader.setFloatUniform("mode", mode)
        runtimeShader.setFloatUniform("intensity", intensity)
        runtimeShader.setFloatUniform("time", (now - t0) / 1000f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        if (shown != target) postInvalidateOnAnimation() else lastFrame = 0L
    }
}
