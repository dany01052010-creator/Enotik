package com.modhelper.app

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Сохраняет клип в Галерею (Movies/ModHelper). Если видео не получилось — кадры картинками. */
object Clips {
    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun write(ctx: Context, frames: List<CaptureService.Frame>, fps: Int): String? {
        if (frames.isEmpty()) return null
        return try {
            writeVideo(ctx, frames, fps)
        } catch (e: Exception) {
            try {
                writeImages(ctx, frames)
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun writeVideo(ctx: Context, frames: List<CaptureService.Frame>, fps: Int): String {
        val first = BitmapFactory.decodeByteArray(frames[0].jpeg, 0, frames[0].jpeg.size)
            ?: throw IllegalStateException("не читается кадр")
        val w = first.width and 1.inv()
        val h = first.height and 1.inv()
        first.recycle()

        val cr = ctx.contentResolver
        val values = ContentValues()
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "ModHelper_" + stamp() + ".mp4")
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ModHelper")
        values.put(MediaStore.Video.Media.IS_PENDING, 1)
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("не создался файл")

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null
        var started = false
        var ok = false
        try {
            val fd = cr.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("нет доступа к файлу")
            pfd = fd
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            val mux = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val info = MediaCodec.BufferInfo()
            var track = -1
            var written = 0L

            fun drain(eos: Boolean) {
                var tries = 0
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, 10_000)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!eos) return
                        tries++
                        if (tries > 300) return
                    } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(enc.outputFormat)
                        mux.start()
                        started = true
                    } else if (idx >= 0) {
                        val buf = enc.getOutputBuffer(idx)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (info.size > 0 && started && buf != null) {
                            info.presentationTimeUs = written * 1_000_000L / fps
                            written++
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            mux.writeSampleData(track, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                    }
                }
            }

            val dst = Rect(0, 0, w, h)
            for (f in frames) {
                val bmp = BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) ?: continue
                val canvas = surface.lockCanvas(null)
                canvas.drawBitmap(bmp, null, dst, null)
                surface.unlockCanvasAndPost(canvas)
                bmp.recycle()
                drain(false)
            }
            enc.signalEndOfInputStream()
            drain(true)
            ok = started
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            try {
                codec?.release()
            } catch (e: Exception) {
            }
            try {
                if (started) muxer?.stop()
            } catch (e: Exception) {
                ok = false
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
            }
            try {
                pfd?.close()
            } catch (e: Exception) {
            }
            if (ok) {
                val done = ContentValues()
                done.put(MediaStore.Video.Media.IS_PENDING, 0)
                cr.update(uri, done, null, null)
            } else {
                cr.delete(uri, null, null)
            }
        }
        if (!ok) throw IllegalStateException("видео не записалось")
        return uri.toString()
    }

    private fun writeImages(ctx: Context, frames: List<CaptureService.Frame>): String? {
        val step = maxOf(1, frames.size / 24)
        val st = stamp()
        var first: String? = null
        var i = 0
        var n = 0
        while (i < frames.size) {
            val v = ContentValues()
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "ModHelper_" + st + "_" + n + ".jpg")
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ModHelper")
            val u = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (u != null) {
                ctx.contentResolver.openOutputStream(u)?.use { it.write(frames[i].jpeg) }
                if (first == null) first = u.toString()
            }
            i += step
            n++
        }
        return first
    }
}
