package com.modhelper.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}

class Violation(
    val id: String,
    val ts: Long,
    var nick: String,
    var rule: String,
    var reason: String,
    var punishment: String,
    var clip: String,
    var hidden: Boolean
)

class Violations(private val file: File) {
    val items = ArrayList<Violation>()

    init {
        load()
    }

    private fun load() {
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(
                    Violation(
                        o.optString("id"), o.optLong("ts"), o.optString("nick"), o.optString("rule"),
                        o.optString("reason"), o.optString("punishment"), o.optString("clip"),
                        o.optBoolean("hidden")
                    )
                )
            }
        } catch (e: Exception) {
            // файла ещё нет или он повреждён
        }
    }

    fun save() {
        try {
            val arr = JSONArray()
            for (v in items) {
                arr.put(
                    JSONObject().put("id", v.id).put("ts", v.ts).put("nick", v.nick).put("rule", v.rule)
                        .put("reason", v.reason).put("punishment", v.punishment).put("clip", v.clip)
                        .put("hidden", v.hidden)
                )
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // не критично
        }
    }

    fun add(nick: String, rule: String, reason: String, punishment: String, clip: String): Violation? {
        val n = nick.trim().ifEmpty { "?" }
        val r = rule.trim()
        val rs = reason.trim()
        for (v in items) {
            if (v.nick.equals(n, true) && v.rule.equals(r, true) && v.reason.equals(rs, true)) {
                if (v.hidden) {
                    v.hidden = false
                    save()
                }
                return null
            }
        }
        val v = Violation(
            UUID.randomUUID().toString().substring(0, 10), System.currentTimeMillis(),
            n, r, rs, punishment.trim(), clip, false
        )
        items.add(v)
        save()
        return v
    }

    fun addMany(found: List<Engine.Found>, clip: String): List<Violation> {
        val out = ArrayList<Violation>()
        for (f in found) {
            val v = add(f.nick, f.rule, f.reason, f.punishment, clip)
            if (v != null) out.add(v)
        }
        return out
    }

    fun remove(v: Violation) {
        items.remove(v)
        save()
    }

    fun hideNick(nick: String) {
        for (v in items) if (v.nick == nick) v.hidden = true
        save()
    }

    fun clear() {
        items.clear()
        save()
    }
}

class Transcript(private val file: File) {
    private val lines = ArrayList<Pair<Long, String>>()

    init {
        try {
            file.forEachLine { l ->
                val p = l.indexOf('\t')
                if (p > 0) {
                    val t = l.substring(0, p).toLongOrNull()
                    if (t != null) lines.add(t to l.substring(p + 1))
                }
            }
        } catch (e: Exception) {
            // истории ещё нет
        }
    }

    @Synchronized
    fun add(text: String) {
        val clean = text.replace('\n', ' ').replace('\t', ' ')
        val ts = System.currentTimeMillis()
        lines.add(ts to clean)
        try {
            file.appendText("$ts\t$clean\n")
        } catch (e: Exception) {
            // не критично
        }
    }

    @Synchronized
    fun all(): List<Pair<Long, String>> = ArrayList(lines)

    @Synchronized
    fun size(): Int = lines.size

    @Synchronized
    fun clear() {
        lines.clear()
        try {
            file.delete()
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun recent(seconds: Int): List<Pair<Long, String>> {
        val limit = System.currentTimeMillis() - seconds * 1000L
        return lines.filter { it.first >= limit }
    }
}

class ClipHolder {
    @Volatile
    var path: String = ""
    val latch = CountDownLatch(1)

    fun await(sec: Long): String {
        try {
            latch.await(sec, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            // ignore
        }
        return path
    }
}

/** Что проверяем: текст, пояснение (ник или слова), скриншоты, сколько секунд недавней речи добавить. */
class Req(val body: String, val note: String, val images: List<Bitmap>, val speechSeconds: Int)

object Hub {
    lateinit var ctx: Context
    lateinit var prefs: SharedPreferences
    lateinit var violations: Violations
    lateinit var transcript: Transcript
    val speechListeners = CopyOnWriteArrayList<() -> Unit>()
    val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    val chatLog = ArrayList<Pair<String, String>>()

    @Volatile
    var capture: CaptureService? = null

    @Volatile
    var overlay: OverlayService? = null

    @Volatile
    var voiceOn = false

    @Volatile
    var busy = false

    fun init(c: Context) {
        ctx = c.applicationContext
        prefs = ctx.getSharedPreferences("modhelper", Context.MODE_PRIVATE)
        violations = Violations(File(ctx.filesDir, "violations.json"))
        transcript = Transcript(File(ctx.filesDir, "speech_log.txt"))
    }

    fun speechChanged() {
        main.post {
            for (l in speechListeners) l()
        }
    }

    fun s(key: String, def: String = ""): String = prefs.getString(key, def) ?: def
    fun setS(key: String, v: String) = prefs.edit().putString(key, v).apply()
    fun i(key: String, def: Int): Int = prefs.getInt(key, def)
    fun setI(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    fun changed() {
        main.post {
            for (l in listeners) l()
        }
    }

    fun toast(text: String) {
        main.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    }

    fun notification(text: String): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("mod") == null) {
            nm.createNotificationChannel(NotificationChannel("mod", "ModHelper", NotificationManager.IMPORTANCE_LOW))
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, "mod")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ModHelper")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /** Новая фраза из голоса: в историю, и при включённой автопроверке — сразу по правилам. */
    fun onSpeech(text: String) {
        transcript.add(text)
        speechChanged()
        if (i("auto_speech", 0) == 1) {
            val res = Engine.analyze(s("rules"), text, "")
            if (res.found.isNotEmpty()) {
                val added = violations.addMany(res.found, "")
                if (added.isNotEmpty()) {
                    changed()
                    overlay?.flash("Речь: " + added.joinToString { it.rule.ifEmpty { "?" } + " " + it.reason.take(40) })
                }
            }
        }
    }

    /**
     * Проверка: читаем текст со скриншотов (на телефоне), собираем всё в один текст и прогоняем через Engine.
     * Колбэки вызываются в главном потоке.
     */
    fun analyze(
        req: Req,
        clip: ClipHolder?,
        onOk: (List<Engine.Found>, List<Violation>) -> Unit,
        onFail: (String) -> Unit
    ) {
        if (busy) {
            onFail("Подожди, предыдущая проверка ещё идёт.")
            return
        }
        busy = true
        Thread {
            try {
                val parts = ArrayList<String>()
                if (req.body.isNotBlank()) parts.add(req.body)
                val shown = ArrayList<String>()
                for ((i, img) in req.images.withIndex()) {
                    val t = Ocr.read(img)
                    if (t.isNotBlank()) {
                        parts.add(t)
                        shown.add("Прочитал со скриншота " + (i + 1) + ":\n" + t.take(1200))
                    } else {
                        shown.add("Со скриншота " + (i + 1) + " текст не прочитался")
                    }
                }
                if (req.speechSeconds > 0) {
                    val sp = transcript.recent(req.speechSeconds).joinToString("\n") { it.second }
                    if (sp.isNotBlank()) parts.add(sp)
                }
                val res = Engine.analyze(s("rules"), parts.joinToString("\n"), req.note)
                val clipPath = clip?.await(40) ?: ""
                main.post {
                    busy = false
                    for (o in shown) chatLog.add("system" to o)
                    chatLog.add("bot" to res.report)
                    val added = violations.addMany(res.found, clipPath)
                    if (res.found.isNotEmpty()) {
                        chatLog.add(
                            "system" to (if (added.isNotEmpty()) "В таблицу добавлено: " + added.size
                            else "Такие нарушения уже есть в таблице.")
                        )
                    }
                    changed()
                    onOk(res.found, added)
                }
            } catch (e: Throwable) {
                val text = "Не получилось: " + (e.message ?: e.javaClass.simpleName)
                main.post {
                    busy = false
                    chatLog.add("system" to ("⚠ " + text))
                    changed()
                    onFail(text)
                }
            }
        }.start()
    }
}
