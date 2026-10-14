package com.example.githubwidget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin

/**
 * V2 unified card rendering. One pipeline feeds the widget, the Studio
 * preview and the share card:
 *
 *   base (solid / gradient / transparent)
 *     -> image (fill or fit, own opacity)
 *     -> blur -> overlay -> rounded corners + border
 */
object CardRenderer {

    /** Background composite WITHOUT border — also feeds image-through-graph. */
    fun background(
        context: android.content.Context,
        w: Int,
        h: Int,
        opacityOverride: Int? = null
    ): Bitmap = composite(context, w, h, opacityOverride).first

    /** Background plus whether it came out complete (false = image unreadable). */
    private fun composite(
        context: android.content.Context,
        w: Int,
        h: Int,
        opacityOverride: Int?
    ): Pair<Bitmap, Boolean> {
        var complete = true
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val type = Studio.getBgType(context)
        val opacity = (opacityOverride ?: Studio.getBgOpacity(context)).coerceIn(0, 100)

        if (type == Studio.BG_TRANSPARENT) {
            return bmp to true // fully transparent; overlay would defeat the point
        }

        if (type == Studio.BG_GRADIENT) {
            drawGradient(canvas, w, h, context)
        } else {
            canvas.drawColor(Studio.getBgColor(context))
        }

        if (type == Studio.BG_IMAGE) {
            val uri = Studio.getImageUri(context)
            val img = if (uri.isNotEmpty()) {
                sampleImage(context, uri, w, h, Studio.getImageScale(context))
            } else {
                null
            }
            if (img != null) {
                val alpha = (opacity * 255 / 100)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha }
                canvas.drawBitmap(img, 0f, 0f, paint)
                if (!img.isRecycled) img.recycle()
            } else {
                complete = false
            }
        } else {
            // Solid + gradient fills: fold the opacity in with DST_OUT so the
            // exact hue survives and text drawn above stays crisp.
            val alpha = (opacity * 255 / 100)
            if (alpha < 255) {
                val dim = Paint().apply {
                    color = Color.argb(255 - alpha, 0, 0, 0)
                    xfermode = android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.DST_OUT
                    )
                }
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), dim)
            }
        }

        val blur = Studio.getBlur(context)
        var out = bmp
        if (blur > 0) {
            out = softBlur(bmp, blur)
            if (out !== bmp) bmp.recycle()
        }

        val overlayAlpha = (Studio.getOverlayOpacity(context).coerceIn(0, 80) * 255 / 100)
        if (overlayAlpha > 0) {
            val c = Studio.getOverlay(context)
            outCanvas(out).drawColor(
                Color.argb(
                    overlayAlpha,
                    Color.red(c), Color.green(c), Color.blue(c)
                )
            )
        }
        return out to complete
    }

    private fun outCanvas(bmp: Bitmap): Canvas = Canvas(bmp)

    /**
     * Full card: background clipped to rounded corners plus the border.
     * With [useCache], expensive results (image or blur) are reused from
     * [RenderCache], keyed by every input that changes the pixels.
     */
    fun card(
        context: android.content.Context,
        w: Int,
        h: Int,
        cornerPx: Float,
        borderColor: Int,
        opacityOverride: Int? = null,
        strokePx: Float = 3f,
        useCache: Boolean = false
    ): Bitmap {
        val key = if (useCache) cardKey(context, w, h, cornerPx, borderColor, opacityOverride, strokePx) else null
        if (key != null) RenderCache.get(context, key)?.let { return it }
        val (bg, complete) = composite(context, w, h, opacityOverride)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val path = Path().apply {
            addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(bg, 0f, 0f, null)
        canvas.restore()
        if (!bg.isRecycled) bg.recycle()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = borderColor
        }
        canvas.drawRoundRect(rect, cornerPx, cornerPx, border)
        // Never cache a card whose image failed to load; retry next time.
        if (key != null && complete) RenderCache.put(context, key, bmp)
        return bmp
    }

    /**
     * Cache key for [card], or null when the render is cheap enough
     * (solid/gradient without blur) that a PNG round-trip wouldn't pay off.
     */
    private fun cardKey(
        context: android.content.Context,
        w: Int,
        h: Int,
        cornerPx: Float,
        borderColor: Int,
        opacityOverride: Int?,
        strokePx: Float
    ): String? {
        val type = Studio.getBgType(context)
        val blur = Studio.getBlur(context)
        if (type == Studio.BG_TRANSPARENT) return null
        if (type != Studio.BG_IMAGE && blur <= 0) return null
        val uri = if (type == Studio.BG_IMAGE) Studio.getImageUri(context) else ""
        return RenderCache.key(
            "card", w, h, cornerPx, borderColor, strokePx, type,
            Studio.getBgColor(context), Studio.getGrad2(context), Studio.getGrad3(context),
            Studio.isGradRadial(context), Studio.getGradAngle(context),
            uri, imageStamp(uri), Studio.getImageScale(context),
            opacityOverride ?: Studio.getBgOpacity(context), blur,
            Studio.getOverlay(context), Studio.getOverlayOpacity(context)
        )
    }

    /** [sampleImage] through [RenderCache]: the image is decoded once per change, not per repaint. */
    fun sampleImageCached(
        context: android.content.Context,
        uriStr: String,
        w: Int,
        h: Int,
        mode: String
    ): Bitmap? {
        val key = RenderCache.key("sample", uriStr, imageStamp(uriStr), w, h, mode)
        RenderCache.get(context, key)?.let { return it }
        val bmp = sampleImage(context, uriStr, w, h, mode) ?: return null
        RenderCache.put(context, key, bmp)
        return bmp
    }

    /** Detects in-place edits of local image files (content URIs are immutable picks). */
    private fun imageStamp(uriStr: String): String {
        if (!uriStr.startsWith("file://")) return ""
        return try {
            val f = File(Uri.parse(uriStr).path ?: return "")
            "${f.lastModified()}:${f.length()}"
        } catch (_: Exception) {
            ""
        }
    }

    private fun drawGradient(canvas: Canvas, w: Int, h: Int, context: android.content.Context) {
        val c1 = Studio.getBgColor(context)
        val c2 = Studio.getGrad2(context)
        val c3 = Studio.getGrad3(context)
        val colors = if (c3 != null) intArrayOf(c1, c2, c3) else intArrayOf(c1, c2)
        val shader = if (Studio.isGradRadial(context)) {
            RadialGradient(
                w / 2f, h / 2f, maxOf(w, h) / 2f,
                colors, null, Shader.TileMode.CLAMP
            )
        } else {
            val angle = Math.toRadians(Studio.getGradAngle(context).toDouble())
            // 90deg = top -> bottom.
            val dx = cos(angle - Math.PI / 2).toFloat()
            val dy = sin(angle - Math.PI / 2).toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val rx = (w / 2f) * kotlin.math.abs(dx) + (h / 2f) * kotlin.math.abs(dy)
            LinearGradient(
                cx - dx * rx, cy - dy * rx, cx + dx * rx, cy + dy * rx,
                colors, null, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })
    }

    /**
     * Memory-safe sampled decode, cropped/fitted to exactly w x h.
     * Returns null for missing or unreadable images (caller falls back).
     */
    fun sampleImage(
        context: android.content.Context,
        uriStr: String,
        w: Int,
        h: Int,
        mode: String
    ): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample / 2 >= w && bounds.outHeight / sample / 2 >= h) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val bw = raw.width.toFloat()
            val bh = raw.height.toFloat()
            if (mode == Studio.SCALE_FIT) {
                val s = minOf(w / bw, h / bh)
                val dw = bw * s
                val dh = bh * s
                canvas.drawBitmap(
                    raw, null,
                    RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            } else {
                val s = maxOf(w / bw, h / bh)
                val dw = bw * s
                val dh = bh * s
                canvas.drawBitmap(
                    raw, null,
                    RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f),
                    Paint(Paint.FILTER_BITMAP_FLAG)
                )
            }
            if (!raw.isRecycled) raw.recycle()
            out
        } catch (_: Exception) {
            null
        }
    }

    /** Cheap blur: downscale then upscale. strength 1..25. */
    fun softBlur(src: Bitmap, strength: Int): Bitmap {
        val s = 1f / (1f + strength / 4f)
        val sw = (src.width * s).toInt().coerceAtLeast(1)
        val sh = (src.height * s).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val out = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (!small.isRecycled) small.recycle()
        return out
    }
}

/**
 * Tiny PNG cache in cacheDir for widget renders that are costly to redo
 * (decoding the user's image, blurring). Entries are content-addressed, so
 * any look change produces a new key; only the newest [KEEP] files stay.
 * Callers serialize on the widget refresh thread.
 */
object RenderCache {

    private const val DIR = "render"
    private const val KEEP = 8

    private fun dir(context: android.content.Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    /** Stable hash of every input that affects the pixels. */
    fun key(vararg parts: Any?): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(parts.joinToString("|").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(context: android.content.Context, key: String): Bitmap? {
        return try {
            val f = File(dir(context), "$key.png")
            if (!f.exists()) return null
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
            f.setLastModified(System.currentTimeMillis()) // keep hot entries from pruning
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun put(context: android.content.Context, key: String, bmp: Bitmap) {
        try {
            val d = dir(context)
            val tmp = File(d, "$key.tmp")
            FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(File(d, "$key.png"))) tmp.delete()
            d.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP)
                ?.forEach { it.delete() }
        } catch (_: Exception) {
            // Cache is an optimization only.
        }
    }

    /** Drops every cached render (e.g. when the last widget is removed). */
    fun clear(context: android.content.Context) {
        try {
            File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
            // Best-effort.
        }
    }
}
