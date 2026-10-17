package com.quicklauncher.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Draws [bmp] centre-cropped so it fills [dst] (like a wallpaper). [src] is a scratch rect. */
fun drawCover(c: Canvas, bmp: Bitmap, dst: RectF, paint: Paint, src: Rect) {
    if (dst.width() <= 0f || dst.height() <= 0f) return
    val ar = dst.width() / dst.height()
    val bar = bmp.width.toFloat() / bmp.height
    if (bar > ar) { val cw = (bmp.height * ar).toInt(); val x = (bmp.width - cw) / 2; src.set(x, 0, x + cw, bmp.height) }
    else { val ch = (bmp.width / ar).toInt(); val y = (bmp.height - ch) / 2; src.set(0, y, bmp.width, y + ch) }
    c.drawBitmap(bmp, src, dst, paint)
}

/** The optional picture behind the popup: the home wallpaper or one image the user uploaded. */
object PopupBg {
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var custom: Bitmap? = null
    @Volatile private var customRev = -1

    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "popup_bg.jpg")

    /** The bitmap to draw right now for these settings (null = plain glass). Never loads anything. */
    fun get(s: Settings): Bitmap? = when (s.popBg) {
        "wallpaper" -> Wallpaper.bitmap
        "custom" -> custom
        else -> null
    }

    /** Starts loading what [get] will need; [onReady] runs on the main thread when a bitmap arrived. */
    fun ensure(ctx: Context, s: Settings, onReady: () -> Unit) {
        when (s.popBg) {
            "wallpaper" -> if (Wallpaper.bitmap == null) Wallpaper.load(ctx, onReady)
            "custom" -> if (customRev != s.popBgRev && busy.compareAndSet(false, true)) {
                val app = ctx.applicationContext
                val rev = s.popBgRev
                exec.execute {
                    custom = runCatching { BitmapFactory.decodeFile(file(app).path) }.getOrNull()
                    customRev = rev
                    busy.set(false)
                    main.post(onReady)
                }
            }
        }
    }

    /** Copies the picked image into app storage (scaled down to at most 1600 px). */
    fun save(ctx: Context, uri: Uri): Boolean {
        return runCatching {
            val cr = ctx.contentResolver
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
            var sample = 1
            while (b.outWidth / sample > 1600 || b.outHeight / sample > 1600) sample *= 2
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return@runCatching false
            file(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            true
        }.getOrDefault(false)
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
        custom = null; customRev = -1
    }
}
