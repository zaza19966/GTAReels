package com.reels.cutter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.sqrt

/** ترجمة تلقائية تجريبية: تعرّف الكلام على الجهاز (Android 13+) على مقاطع من 4 ثوانٍ */
object Subtitles {

    var lastError: Int = 0

    fun available(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)

    suspend fun transcribe(ctx: Context, uri: Uri, startMs: Long, endMs: Long, lang: String): List<Sub> {
        if (!available(ctx)) return emptyList()
        val pcm = withContext(Dispatchers.Default) { decodePcm16k(ctx, uri, startMs, endMs) }
        val segBytes = 16000 * 2 * 4
        val out = mutableListOf<Sub>()
        var off = 0
        while (off < pcm.size) {
            val end = minOf(pcm.size, off + segBytes)
            val chunk = pcm.copyOfRange(off, end)
            if (rms(chunk) > 150) {
                val t = recognizeOnce(ctx, chunk, lang)
                if (!t.isNullOrBlank()) {
                    out.add(Sub(off / 2 * 1_000_000L / 16000, end / 2 * 1_000_000L / 16000, t.trim()))
                }
            }
            off = end
        }
        return out
    }

    private fun rms(b: ByteArray): Double {
        val sb = java.nio.ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var s = 0.0
        var n = 0
        while (sb.remaining() > 0) {
            val v = sb.get().toInt()
            s += v.toDouble() * v
            n++
        }
        return if (n == 0) 0.0 else sqrt(s / n)
    }

    @SuppressLint("NewApi")
    private suspend fun recognizeOnce(ctx: Context, pcm: ByteArray, lang: String): String? =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<String?> { cont ->
                    val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                    val pipe = ParcelFileDescriptor.createPipe()
                    val readSide = pipe[0]
                    val writeSide = pipe[1]
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                    }

                    fun finish(result: String?) {
                        if (cont.isActive) {
                            try { rec.destroy() } catch (_: Exception) {}
                            try { readSide.close() } catch (_: Exception) {}
                            cont.resume(result)
                        }
                    }

                    rec.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            lastError = error
                            finish(null)
                        }
                        override fun onResults(results: Bundle?) {
                            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            finish(list?.firstOrNull())
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    cont.invokeOnCancellation {
                        try { rec.destroy() } catch (_: Exception) {}
                        try { readSide.close() } catch (_: Exception) {}
                    }
                    rec.startListening(intent)
                    Thread {
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { it.write(pcm) }
                        } catch (_: Exception) {
                        }
                    }.start()
                }
            }
        }

    /** فك ترميز الصوت وتحويله إلى PCM أحادي 16kHz (16-bit little-endian) */
    private fun decodePcm16k(ctx: Context, uri: Uri, startMs: Long, endMs: Long): ByteArray {
        val bos = ByteArrayOutputStream()
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i; fmt = f; break
                }
            }
            if (track < 0 || fmt == null) return bos.toByteArray()
            ex.selectTrack(track)
            val startUs = startMs * 1000
            val endUs = endMs * 1000
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var acc = 0.0
            var cnt = 0
            var pos = 0L
            var outCount = 0L
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        val st = ex.sampleTime
                        if (n < 0 || st > endUs) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, st, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (oi >= 0) {
                    if (info.size > 0 && info.presentationTimeUs >= startUs - 20_000) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val ratio = sr / 16000.0
                        while (sb.remaining() >= ch) {
                            var s = 0
                            for (k in 0 until ch) s += sb.get().toInt()
                            acc += s / ch.toDouble()
                            cnt++
                            pos++
                            if (pos >= (outCount + 1) * ratio) {
                                val v = (acc / cnt).toInt().coerceIn(-32768, 32767)
                                bos.write(v and 255)
                                bos.write((v shr 8) and 255)
                                acc = 0.0
                                cnt = 0
                                outCount++
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            ex.release()
        }
        return bos.toByteArray()
    }
}
