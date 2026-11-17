package com.reels.cutter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

/** يجب استدعاؤه من الخيط الرئيسي. يصدّر المقاطع واحدًا تلو الآخر بصيغة 9:16 */
class Exporter(private val ctx: Context) {

    fun export(
        uri: Uri,
        clips: List<Pair<Long, Long>>,
        videoW: Int,
        videoH: Int,
        cropPos: Float, // 0..1 موضع الاقتصاص أفقيًا
        keepOriginal: Boolean, // true = بدون تكبير
        onClipStart: (Int) -> Unit,
        onDone: (List<Uri>) -> Unit,
        onFail: (String) -> Unit
    ) {
        val results = mutableListOf<Uri>()
        val stamp = System.currentTimeMillis()

        fun next(i: Int) {
            if (i >= clips.size) { onDone(results); return }
            onClipStart(i)
            val (s, e) = clips[i]
            val media = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(s)
                        .setEndPositionMs(e)
                        .build()
                ).build()

            val effects = mutableListOf<Effect>()
            val target = 9f / 16f
            val srcAspect = videoW.toFloat() / videoH.toFloat()
            var croppedH = videoH
            if (srcAspect > target + 0.001f) {
                val vf = target / srcAspect
                val cx = -1f + vf + (2f - 2f * vf) * cropPos.coerceIn(0f, 1f)
                effects.add(Crop(cx - vf, cx + vf, -1f, 1f))
            } else if (srcAspect < target - 0.001f) {
                val vfv = srcAspect / target
                effects.add(Crop(-1f, 1f, -vfv, vfv))
                croppedH = (videoW * 16f / 9f).toInt()
            }
            var outH = if (keepOriginal) minOf(croppedH, 2160) else 1920
            outH = outH / 2 * 2
            val outW = (outH * 9 / 16) / 2 * 2
            effects.add(Presentation.createForHeight(outH))
            val bitrate = (outW.toLong() * outH * 30L / 5L).coerceIn(2_000_000L, 25_000_000L).toInt()
            val encoderFactory = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .build()

            val edited = EditedMediaItem.Builder(media)
                .setEffects(Effects(emptyList(), effects))
                .build()
            val outFile = File(ctx.cacheDir, "reel_${stamp}_$i.mp4")

            val transformer = Transformer.Builder(ctx)
                .setEncoderFactory(encoderFactory)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        saveToGallery(outFile, "Reel_${stamp}_${i + 1}.mp4")?.let { results.add(it) }
                        outFile.delete()
                        next(i + 1)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        outFile.delete()
                        onFail(exportException.message ?: "فشل التصدير")
                    }
                })
                .build()
            transformer.start(edited, outFile.absolutePath)
        }
        next(0)
    }

    private fun saveToGallery(file: File, name: String): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GTAReels")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val res = ctx.contentResolver
        val u = res.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        res.openOutputStream(u)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        cv.clear()
        cv.put(MediaStore.Video.Media.IS_PENDING, 0)
        res.update(u, cv, null, null)
        return u
    }
}
