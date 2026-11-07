package com.example.filedropper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Следит, какое приложение вышло на экран. Если для него есть кнопка с включённым
 * "ставить автоматически" — сразу ставит файл и показывает уведомление с текстом кнопки.
 * Нужен доступ "Статистика использования" в настройках Android.
 */
class WatchService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var lastTs = 0L
    private val lastPlaced = HashMap<String, Long>()

    private val tick = object : Runnable {
        override fun run() {
            try {
                poll()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 700)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("watch", "Слежение", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("result", "Результат", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(this, "watch")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("FileDropper следит за запуском приложений")
            .setOngoing(true)
            .build()
        startForeground(1, n)
        lastTs = System.currentTimeMillis()
        thread = HandlerThread("watch").also { it.start() }
        handler = Handler(thread.looper)
        handler.post(tick)
    }

    @Suppress("DEPRECATION")
    private fun poll() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = usm.queryEvents(lastTs, now)
        lastTs = now
        val e = UsageEvents.Event()
        val launched = LinkedHashSet<String>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) launched.add(e.packageName)
        }
        if (launched.isEmpty()) return
        val store = Store(this)
        val presets = store.all().filter { it.auto && it.triggerPackage.isNotEmpty() }
        for (pkg in launched) {
            for (p in presets.filter { it.triggerPackage == pkg }) {
                val prev = lastPlaced[p.id] ?: 0L
                if (now - prev < 20_000) continue
                lastPlaced[p.id] = now
                val r = Placer.place(this, store.dirFor(p.id), p)
                notify(p, r)
            }
        }
    }

    private fun notify(p: Preset, r: Placer.Result) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = (if (p.note.isNotBlank()) p.note + "\n" else "") + r.message
        val n = Notification.Builder(this, "result")
            .setSmallIcon(if (r.ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle((if (r.ok) "Поставлено: " else "Ошибка: ") + p.name)
            .setContentText(r.message)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(p.id.hashCode(), n)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
    }
}
