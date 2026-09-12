package com.foldfx

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import java.io.File
import kotlin.math.max

object Prefs {
    private const val FILE = "foldfx"
    const val KEY_ENABLED = "enabled"
    const val KEY_LEFT = "left_moving"
    const val KEY_INTENSITY = "intensity"
    const val KEY_IMAGES = "images_version"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = get(ctx).getBoolean(KEY_ENABLED, false)
    fun leftMoving(ctx: Context) = get(ctx).getBoolean(KEY_LEFT, false)
    fun intensity(ctx: Context) = get(ctx).getFloat(KEY_INTENSITY, 1f)

    fun bumpImages(ctx: Context) {
        val p = get(ctx)
        p.edit().putInt(KEY_IMAGES, p.getInt(KEY_IMAGES, 0) + 1).apply()
    }
}

object ImageStore {
    const val INNER = "inner.img"
    const val COVER = "cover.img"
    private const val MAX_DIM = 1600

    /** Copie l'image choisie dans le stockage privé de l'app (plus besoin de permission ensuite). */
    fun save(ctx: Context, uri: Uri, name: String): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            File(ctx.filesDir, name).outputStream().use { input.copyTo(it) }
        } != null
    } catch (e: Exception) {
        false
    }

    fun load(ctx: Context, name: String): Bitmap? {
        val f = File(ctx.filesDir, name)
        if (!f.exists()) return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val m = max(info.size.width, info.size.height)
                var s = 1
                while (m / (s * 2) >= MAX_DIM) s *= 2
                decoder.setTargetSampleSize(s)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Fond par défaut si aucune image n'a été choisie. */
    fun fallback(): Bitmap {
        val size = 900
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFF14123A.toInt(), 0xFF4B2A8C.toInt(), 0xFFE0674F.toInt()),
            null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = RadialGradient(
            size * 0.72f, size * 0.3f, size * 0.35f,
            0x88FFD8A8.toInt(), 0x00FFD8A8, Shader.TileMode.CLAMP
        )
        c.drawCircle(size * 0.72f, size * 0.3f, size * 0.35f, p)
        p.shader = RadialGradient(
            size * 0.25f, size * 0.78f, size * 0.3f,
            0x6680C8FF, 0x0080C8FF, Shader.TileMode.CLAMP
        )
        c.drawCircle(size * 0.25f, size * 0.78f, size * 0.3f, p)
        return b
    }
}
