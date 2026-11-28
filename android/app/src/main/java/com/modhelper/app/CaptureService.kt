package com.modhelper.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/** Захват экрана: кольцевой буфер кадров (для клипов) и полноразмерные скриншоты (для жалоб). */
class CaptureService : Service() {
    class Frame(val ts: Long, val jpeg: ByteArray)

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
    }

    private val lock = Any()
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    private var held: Image? = null
    private var dirty = false

    @Volatile
    private var lastFull: Bitmap? = null
    private var lastJpeg: ByteArray? = null
    private val frames = ArrayList<Frame>()

    @Volatile
    private var running = false
    var fps = 2
        private set
    private var keepSec = 180
    private var w = 0
    private var hh = 0
    private var dpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun readData(i: Intent?): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            i?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            i?.getParcelableExtra<Intent>(EXTRA_DATA)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            2, Hub.notification("Идёт захват экрана для записи нарушений"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = readData(intent)
        if (data == null || code == 0 || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        try {
            start(code, data)
        } catch (e: Exception) {
            Hub.toast("Не удалось начать захват экрана: " + e.message)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        fps = Hub.i("fps", 2).coerceIn(1, 6)
        keepSec = Hub.i("buffer_min", 3).coerceIn(1, 15) * 60
        val t = HandlerThread("modhelper-capture")
        t.start()
        thread = t
        val handler = Handler(t.looper)
        h = handler
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Hub.main.post { stopSelf() }
            }
        }, handler)
        running = true
        setupDisplay()
        handler.postDelayed(tick, 500)
        Hub.capture = this
        Hub.changed()
    }

    @Suppress("DEPRECATION")
    private fun setupDisplay() {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val s = minOf(1f, 1920f / maxOf(dm.widthPixels, dm.heightPixels).toFloat())
        w = (dm.widthPixels * s).toInt() and 1.inv()
        hh = (dm.heightPixels * s).toInt() and 1.inv()
        dpi = dm.densityDpi
        val r = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                synchronized(lock) {
                    held?.close()
                    held = img
                    dirty = true
                }
            }
        }, h)
        val old = reader
        val d = vd
        if (d == null) {
            vd = projection!!.createVirtualDisplay(
                "ModHelper", w, hh, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
            )
        } else {
            d.resize(w, hh, dpi)
            d.surface = r.surface
        }
        reader = r
        if (old != null) old.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) {
            h?.post {
                try {
                    setupDisplay()
                } catch (e: Exception) {
                    // оставляем как есть
                }
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                captureTick()
            } catch (e: Exception) {
                // пропускаем кадр
            }
            h?.postDelayed(this, 1000L / fps)
        }
    }

    private fun convertLocked(img: Image) {
        val plane = img.planes[0]
        val rowPadding = plane.rowStride - plane.pixelStride * img.width
        val bw = img.width + rowPadding / plane.pixelStride
        val bmp = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        val full = if (bw != img.width) {
            val c = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
            bmp.recycle()
            c
        } else bmp
        lastFull = full
        val sc = minOf(1f, 960f / maxOf(full.width, full.height).toFloat())
        val small = Bitmap.createScaledBitmap(
            full, maxOf(2, (full.width * sc).toInt()), maxOf(2, (full.height * sc).toInt()), true
        )
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 72, bos)
        if (small !== full) small.recycle()
        lastJpeg = bos.toByteArray()
    }

    private fun captureTick() {
        synchronized(lock) {
            val img = held
            if (img != null && dirty) {
                convertLocked(img)
                dirty = false
            }
            val j = lastJpeg
            if (j != null) {
                val now = System.currentTimeMillis()
                synchronized(frames) {
                    frames.add(Frame(now, j))
                    val limit = now - keepSec * 1000L
                    while (frames.isNotEmpty() && (frames[0].ts < limit || frames.size > 900)) frames.removeAt(0)
                }
            }
        }
    }

    /** Свежий полноразмерный кадр экрана. Вызывать не из главного потока. */
    fun snapshot(): Bitmap? {
        synchronized(lock) {
            val img = held
            if (img != null && dirty) {
                try {
                    convertLocked(img)
                    dirty = false
                } catch (e: Exception) {
                    // вернём предыдущий кадр
                }
            }
            return lastFull
        }
    }

    fun frameCount(): Int = synchronized(frames) { frames.size }

    /** Вырезает отрезок «от startAgo до endAgo секунд назад». Возвращает uri клипа. */
    fun exportClip(startAgo: Int, endAgo: Int): String? {
        val a = maxOf(startAgo, endAgo)
        val b = minOf(startAgo, endAgo)
        val now = System.currentTimeMillis()
        val t0 = now - a * 1000L
        val t1 = now - b * 1000L
        val list: List<Frame> = synchronized(frames) { frames.filter { it.ts in t0..t1 } }
        if (list.isEmpty()) return null
        return Clips.write(applicationContext, list, fps)
    }

    override fun onDestroy() {
        running = false
        h?.removeCallbacksAndMessages(null)
        try {
            vd?.release()
        } catch (e: Exception) {
        }
        try {
            projection?.stop()
        } catch (e: Exception) {
        }
        synchronized(lock) {
            try {
                held?.close()
            } catch (e: Exception) {
            }
            held = null
        }
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        thread?.quitSafely()
        if (Hub.capture === this) Hub.capture = null
        Hub.changed()
        super.onDestroy()
    }
}
