package com.reels.cutter

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val BIN_MS = 2000L

data class Moment(val timeMs: Long, val score: Float, val reason: String, var selected: Boolean = true)

fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

fun parseTime(text: String): Long? {
    val parts = text.trim().split(":").map { it.trim().toIntOrNull() ?: return null }
    if (parts.isEmpty() || parts.size > 3) return null
    var sec = 0L
    for (p in parts) sec = sec * 60 + p
    return sec * 1000
}

object Analyzer {

    /** المدة والعرض والارتفاع (بعد تطبيق التدوير) */
    fun info(ctx: Context, uri: Uri): Triple<Long, Int, Int> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            return Triple(d, w, h)
        } finally {
            r.release()
        }
    }

    /** نافذة المقطع حول اللحظة: 40% قبلها و60% بعدها */
    fun window(timeMs: Long, lenMs: Long, durationMs: Long): Pair<Long, Long> {
        var start = max(0L, timeMs - (lenMs * 0.4).toLong())
        var end = min(durationMs, start + lenMs)
        if (end - start < lenMs) start = max(0L, end - lenMs)
        return start to end
    }

    /** شدة الصوت لكل 2 ثانية (فك ترميز الصوت إلى PCM) */
    suspend fun audioBins(ctx: Context, uri: Uri, bins: Int, progress: (Int) -> Unit): FloatArray =
        withContext(Dispatchers.Default) {
            val out = FloatArray(bins)
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                ex.setDataSource(ctx, uri, null)
                var track = -1
                var fmt: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
                }
                if (track < 0 || fmt == null) return@withContext out
                ex.selectTrack(track)
                codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val sumSq = DoubleArray(bins)
                val cnt = LongArray(bins)
                var inDone = false
                var outDone = false
                var lastPct = -1
                while (!outDone && isActive) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val buf = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 10_000)
                    if (oi >= 0) {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(oi)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val b = (info.presentationTimeUs / 1000 / BIN_MS).toInt().coerceIn(0, bins - 1)
                            var s = 0.0
                            var c = 0L
                            while (sb.remaining() > 0) {
                                val v = sb.get().toInt()
                                s += v.toDouble() * v
                                c++
                            }
                            sumSq[b] += s
                            cnt[b] += c
                            val pct = (b * 100 / bins)
                            if (pct != lastPct) { lastPct = pct; progress(pct) }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
                for (i in 0 until bins) if (cnt[i] > 0) out[i] = sqrt(sumSq[i] / cnt[i]).toFloat()
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                codec?.release()
                ex.release()
            }
            out
        }

    /** حجم الحركة بين إطارين متتاليين (كل 2 ثانية) */
    suspend fun motionBins(ctx: Context, uri: Uri, bins: Int, progress: (Int) -> Unit): FloatArray =
        withContext(Dispatchers.Default) {
            val out = FloatArray(bins)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                var prev: IntArray? = null
                for (b in 0 until bins) {
                    if (!isActive) break
                    val bmp = r.getScaledFrameAtTime(
                        b * BIN_MS * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 64, 36
                    ) ?: continue
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    bmp.recycle()
                    val p = prev
                    if (p != null && p.size == px.size) {
                        var d = 0L
                        for (i in px.indices) {
                            val a = px[i]; val c = p[i]
                            val la = ((a shr 16 and 255) + (a shr 8 and 255) + (a and 255)) / 3
                            val lc = ((c shr 16 and 255) + (c shr 8 and 255) + (c and 255)) / 3
                            d += abs(la - lc)
                        }
                        out[b] = d.toFloat() / (px.size * 255f)
                    }
                    prev = px
                    progress(b * 100 / bins)
                }
            } finally {
                r.release()
            }
            out
        }

    /** البحث عن كلمات على الشاشة (WASTED, MISSION PASSED ...) بالتعرف الضوئي على النص */
    suspend fun ocrHits(
        ctx: Context, uri: Uri, bins: Int, keywords: List<String>, progress: (Int) -> Unit
    ): Map<Int, String> = withContext(Dispatchers.Default) {
        val hits = HashMap<Int, String>()
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            for (b in 0 until bins) {
                if (!isActive) break
                val bmp: Bitmap = r.getScaledFrameAtTime(
                    b * BIN_MS * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 960, 540
                ) ?: continue
                val text = rec.process(InputImage.fromBitmap(bmp, 0)).await().text.uppercase()
                bmp.recycle()
                keywords.firstOrNull { text.contains(it) }?.let { hits[b] = it }
                progress(b * 100 / bins)
            }
        } finally {
            r.release()
            rec.close()
        }
        hits
    }

    private fun norm(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        val s = x.sortedArray()
        var p = s[((s.size - 1) * 0.95).toInt()]
        if (p <= 0f) p = max(1e-6f, s.last())
        return FloatArray(x.size) { min(1.5f, x[it] / p) }
    }

    /** اختيار أقوى اللحظات مع ترك مسافة كافية بينها */
    fun pick(
        audio: FloatArray, motion: FloatArray, ocr: Map<Int, String>, count: Int, clipMs: Long
    ): List<Moment> {
        val n = min(audio.size, motion.size)
        if (n == 0) return emptyList()
        val a = norm(audio)
        val m = norm(motion)
        val score = FloatArray(n)
        val why = Array(n) { "" }
        for (i in 0 until n) {
            var base = 0f
            var k = 0
            for (j in 1..5) if (i - j >= 0) { base += a[i - j]; k++ }
            if (k > 0) base /= k
            val rise = max(0f, a[i] - base)
            val ap = 0.35f * a[i] + 0.35f * rise
            val mp = 0.30f * m[i]
            var s = ap + mp
            var r = if (ap >= mp) "صوت قوي مفاجئ (انفجار / إطلاق نار / صراخ)"
            else "حركة سريعة على الشاشة (مطاردة / تغيّر مشهد)"
            ocr[i]?.let { s += 2f; r = "ظهر على الشاشة: $it" }
            score[i] = s
            why[i] = r
        }
        val gap = max(1, (clipMs / BIN_MS).toInt())
        val chosen = mutableListOf<Int>()
        for (i in (0 until n).sortedByDescending { score[it] }) {
            if (chosen.size >= count) break
            if (score[i] <= 0f) continue
            if (chosen.none { abs(it - i) < gap }) chosen.add(i)
        }
        val top = chosen.maxOfOrNull { score[it] } ?: 1f
        return chosen.sorted().map { Moment(it * BIN_MS, score[it] / top * 100f, why[it]) }
    }
}
