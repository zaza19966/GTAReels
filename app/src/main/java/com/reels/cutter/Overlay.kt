package com.reels.cutter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.effect.BitmapOverlay
import kotlin.math.max

/** سطر ترجمة: الأوقات بالميكروثانية نسبةً لبداية الريلز */
data class Sub(val startUs: Long, val endUs: Long, val text: String)

object OverlayUtil {

    /** خلفية ضبابية ثابتة من لقطة المقطع، مع فتحة شفافة يظهر منها الفيديو الحقيقي */
    fun makeBars(
        ctx: Context, uri: Uri, timeUs: Long, vw: Int, vh: Int, outW: Int, outH: Int
    ): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val a = vw.toFloat() / vh.toFloat()

        var frame: Bitmap? = null
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            frame = r.getScaledFrameAtTime(
                timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 192, max(1, (192 / a).toInt())
            )
        } catch (_: Exception) {
        } finally {
            r.release()
        }

        if (frame != null) {
            val sw = max(8, outW / 32)
            val sh = max(8, outH / 32)
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val s = max(sw.toFloat() / frame.width, sh.toFloat() / frame.height)
            val dw = frame.width * s
            val dh = frame.height * s
            Canvas(small).drawBitmap(
                frame, null,
                RectF((sw - dw) / 2f, (sh - dh) / 2f, (sw + dw) / 2f, (sh + dh) / 2f),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            c.drawBitmap(small, null, Rect(0, 0, outW, outH), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawColor(Color.argb(110, 0, 0, 0))
        } else {
            c.drawColor(Color.rgb(20, 20, 20))
        }

        val fitW: Float
        val fitH: Float
        if (a >= outW.toFloat() / outH.toFloat()) {
            fitW = outW.toFloat(); fitH = outW / a
        } else {
            fitH = outH.toFloat(); fitW = outH * a
        }
        val left = (outW - fitW) / 2f
        val top = (outH - fitH) / 2f
        val hole = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        c.drawRect(left - 1f, top - 1f, left + fitW + 1f, top + fitH + 1f, hole)
        return out
    }
}

/** طبقة واحدة فوق الفيديو: الخلفية الضبابية + نص الجذب + الترجمة */
class ReelOverlay(
    private val outW: Int,
    private val outH: Int,
    private val base: Bitmap?,
    private val hook: String,
    private val hookUs: Long,
    private val subs: List<Sub>
) : BitmapOverlay() {

    private var lastKey = -1
    private var lastBmp: Bitmap? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val hookOn = hook.isNotEmpty() && presentationTimeUs < hookUs
        val si = subs.indexOfFirst { presentationTimeUs >= it.startUs && presentationTimeUs < it.endUs }
        val key = (if (hookOn) 1 else 0) + (si + 1) * 2
        val cached = lastBmp
        if (cached != null && key == lastKey) return cached
        val bmp = render(hookOn, si)
        lastKey = key
        lastBmp = bmp
        return bmp
    }

    private fun render(hookOn: Boolean, si: Int): Bitmap {
        val bmp = base?.copy(Bitmap.Config.ARGB_8888, true)
            ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (hookOn) drawText(c, hook, outH * 0.10f, outW * 0.075f)
        if (si >= 0) drawText(c, subs[si].text, outH * 0.66f, outW * 0.055f)
        return bmp
    }

    private fun drawText(c: Canvas, text: String, topY: Float, size: Float) {
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
        }
        val stroke = TextPaint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.18f
            color = Color.BLACK
        }
        val w = (outW * 0.88f).toInt()
        val l1 = StaticLayout.Builder.obtain(text, 0, text.length, stroke, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        val l2 = StaticLayout.Builder.obtain(text, 0, text.length, fill, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        c.save()
        c.translate((outW - w) / 2f, topY)
        l1.draw(c)
        l2.draw(c)
        c.restore()
    }
}
