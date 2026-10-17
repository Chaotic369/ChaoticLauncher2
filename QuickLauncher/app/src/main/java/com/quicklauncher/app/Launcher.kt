package com.quicklauncher.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.DocumentsContract
import android.util.Base64
import android.util.LruCache
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/** Executes a shortcut. Returns true if something was started. */
object Launcher {
    fun launch(ctx: Context, it: Item, mgr: OverlayManager): Boolean = when (it.t) {
        "system" -> { SysActions.run(ctx, it.v, mgr); true }
        "app" -> app(ctx, it)
        "call" -> ctx.startSafe(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(it.v))))
        "directDial" -> {
            if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
                ctx.startSafe(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(it.v))))
            else {
                ctx.toast("Phone permission not granted, opening dialer")
                ctx.startSafe(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(it.v))))
            }
        }
        "sms" -> ctx.startSafe(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(it.v))))
        "email" -> ctx.startSafe(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + it.v)))
        "web" -> ctx.startSafe(Intent(Intent.ACTION_VIEW, Uri.parse(if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(it.v)) it.v else "https://" + it.v)))
        "intent" -> runCatching { Intent.parseUri(it.v, Intent.URI_INTENT_SCHEME) }
            .map { i -> i.selector = null; ctx.startSafe(i) }
            .getOrElse { ctx.toast("Invalid intent"); false }
        "folder" -> folder(ctx, it.v)
        "file" -> file(ctx, it.v)
        else -> false
    }

    private fun app(ctx: Context, it: Item): Boolean {
        val act = it.appAction
        if (!act.isNullOrBlank()) {
            if (act.startsWith("shortcut:")) {
                return try {
                    ctx.getSystemService(LauncherApps::class.java)
                        .startShortcut(it.v, act.removePrefix("shortcut:"), null, null, Process.myUserHandle()); true
                } catch (e: Exception) { ctx.toast("Shortcut unavailable (set as default launcher to use these)"); false }
            }
            runCatching { Intent.parseUri(act, 0) }.getOrNull()?.let { i -> return ctx.startSafe(i) }
        }
        val i = ctx.packageManager.getLaunchIntentForPackage(it.v)
        if (i == null) { ctx.toast("App not installed"); return false }
        return ctx.startSafe(i)
    }

    private fun folder(ctx: Context, v: String): Boolean = folderAt(ctx, v, null)

    /** Opens a folder in the file manager. [key] is a sub-folder (document id or absolute path) inside shortcut [v]; null = the shortcut's own folder. */
    fun folderAt(ctx: Context, v: String, key: String?): Boolean {
        val dir = DocumentsContract.Document.MIME_TYPE_DIR
        fun go(i: Intent): Boolean = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            catch (e: Exception) { false }

        if (Fs.isTreeUri(v)) {
            // we hold a persisted grant for this tree, so passing it on is allowed
            val tree = Uri.parse(v)
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, key ?: DocumentsContract.getTreeDocumentId(tree))
            if (go(Intent(Intent.ACTION_VIEW).setDataAndType(uri, dir).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return true
            ctx.toast("No file manager can open this folder"); return false
        }

        // Raw path. Never add a URI grant flag for the externalstorage provider: we can't grant what we don't own,
        // and Android answers with a SecurityException ("Permission denied"). The system file picker app can open it by itself.
        val abs = Fs.normalizePath(key ?: v)
        val rel = abs.removePrefix("/storage/emulated/0").trim('/')
        val docUri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rel")
        for (pkg in listOf("com.google.android.documentsui", "com.android.documentsui")) {
            if (go(Intent(Intent.ACTION_VIEW).setDataAndType(docUri, dir).setPackage(pkg))) return true
        }
        if (go(Intent(Intent.ACTION_VIEW).setDataAndType(docUri, dir))) return true
        // other file managers: hand over the folder through our own FileProvider (we can grant that one)
        runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(abs)) }.getOrNull()?.let { fp ->
            for (mime in listOf("resource/folder", dir))
                if (go(Intent(Intent.ACTION_VIEW).setDataAndType(fp, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return true
        }
        // last resort: the generic file browser
        if (go(Intent(Intent.ACTION_GET_CONTENT).setType("*/*").putExtra("android.provider.extra.INITIAL_URI", docUri))) return true
        ctx.toast("No file manager can open this folder")
        return false
    }

    private fun file(ctx: Context, v: String): Boolean {
        val uri: Uri; val mime: String?
        if (v.startsWith("content://")) {
            uri = Uri.parse(v); mime = ctx.contentResolver.getType(uri)
        } else {
            val f = File(v)
            uri = runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f) }.getOrNull()
                ?: run { ctx.toast("File not accessible"); return false }
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
        }
        return ctx.startSafe(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "No app can open this file")
    }
}

/** Async bitmap cache for real app icons and user supplied custom images. Keeps drawing allocation-free. */
object Icons {
    private const val SIZE = 144           // every icon is normalised to this size: small GPU uploads, predictable memory
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()   // never retry these every frame (uninstalled apps, broken images)
    private val exec = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun peek(it: Item, ctx: Context, onReady: () -> Unit): Bitmap? {
        val key = key(it) ?: return null
        cache.get(key)?.let { return it }
        synchronized(pending) {
            if (key in failed || !pending.add(key)) return null
        }
        val app = ctx.applicationContext
        exec.execute {
            val bmp = try {
                when {
                    it.customImg != null -> decode(it.customImg)
                    it.t == "file" -> fileThumb(app, it.v)
                    else -> appIcon(app, it.v)
                }
            } catch (_: Throwable) { null }
            if (bmp != null) cache.put(key, bmp)
            synchronized(pending) { pending.remove(key); if (bmp == null) failed.add(key) }
            if (bmp != null) main.post(onReady)
        }
        return null
    }

    /** Loads the icons of [items] (and of the apps inside groups) in the background so the popup never pops in half empty. */
    fun prefetch(ctx: Context, items: List<Item>) {
        for (item in items) {
            if (item.t == "group") { for (m in item.memberItems) if (m.hasBitmapIcon) peek(m, ctx) {} }
            else if (item.hasBitmapIcon) peek(item, ctx) {}
        }
    }

    fun clear() {
        cache.evictAll()
        synchronized(pending) { failed.clear() }
    }

    private fun key(it: Item): String? = when {
        it.customImg != null -> "img:${it.id}:${it.customImg.length}:${it.customImg.hashCode()}"
        it.t == "app" && it.i == "app" -> "app:${it.v}"
        it.wantsFileThumb -> "file:${it.v}"
        else -> null
    }

    private fun decode(b64: String): Bitmap? {
        val raw = Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= SIZE * 2 && o.outHeight / (sample * 2) >= SIZE * 2) sample *= 2
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Square, centre-cropped thumbnail of an image file shortcut (content URI or path). null = not an image / unreadable. */
    private fun fileThumb(ctx: Context, v: String): Bitmap? {
        val cr = ctx.contentResolver
        val uri = if (v.startsWith("content://")) Uri.parse(v) else null
        val path = if (uri == null) Fs.normalizePath(v) else null
        val mime = if (uri != null) cr.getType(uri)
            else MimeTypeMap.getSingleton().getMimeTypeFromExtension(File(path!!).extension.lowercase())
        if (mime == null || !mime.startsWith("image/")) return null
        fun open() = if (uri != null) cr.openInputStream(uri) else java.io.FileInputStream(path!!)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE * 2) sample *= 2   // photos are never decoded at full size
        val src = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null

        val side = minOf(src.width, src.height)
        val m = Matrix().apply {
            postScale(SIZE / side.toFloat(), SIZE / side.toFloat())
            val orient = try { open()?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1 } catch (_: Throwable) { 1 }
            when (orient) {   // camera photos: honour the EXIF rotation
                3 -> postRotate(180f, SIZE / 2f, SIZE / 2f)
                6 -> postRotate(90f, SIZE / 2f, SIZE / 2f)
                8 -> postRotate(270f, SIZE / 2f, SIZE / 2f)
            }
        }
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val dx = (src.width - side) / 2; val dy = (src.height - side) / 2
        // crop the centre square first, then scale + rotate it into the SIZE x SIZE tile
        val square = Bitmap.createBitmap(src, dx, dy, side, side)
        c.drawBitmap(square, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        if (square !== src) src.recycle()
        square.recycle()
        return out
    }

    private fun appIcon(ctx: Context, pkg: String): Bitmap? {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, SIZE, SIZE); d.draw(Canvas(bmp))
        return bmp
    }

    fun encodeCustom(bmp: Bitmap): String {
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
