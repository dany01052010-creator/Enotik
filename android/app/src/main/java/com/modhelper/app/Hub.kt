package com.modhelper.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

    fun addMany(found: List<Ai.Found>, clip: String): List<Violation> {
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

    fun asText(seconds: Int): String {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return recent(seconds).joinToString("\n") { "[" + fmt.format(java.util.Date(it.first)) + "] " + it.second }
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

object Hub {
    lateinit var ctx: Context
    lateinit var prefs: SharedPreferences
    lateinit var violations: Violations
    val main = Handler(Looper.getMainLooper())
    lateinit var transcript: Transcript
    val speechListeners = CopyOnWriteArrayList<() -> Unit>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    val history = ArrayList<Ai.Msg>()
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

    /** Общий запрос к ИИ: и для чата, и для жалоб. Колбэки вызываются в главном потоке. */
    fun askAi(
        msg: Ai.Msg,
        clip: ClipHolder?,
        withVoice: Boolean,
        onOk: (List<Ai.Found>, List<Violation>) -> Unit,
        onFail: (String) -> Unit
    ) {
        if (busy) {
            onFail("Подожди, предыдущий запрос ещё идёт.")
            return
        }
        busy = true
        val hist = ArrayList<Ai.Msg>(history)
        hist.add(msg)
        val tr = if (withVoice) transcript.asText(90) else ""
        Thread {
            try {
                val reply = Ai.chat(hist, tr)
                val clipPath = clip?.await(40) ?: ""
                main.post {
                    busy = false
                    history.add(msg)
                    history.add(Ai.Msg("assistant", reply))
                    val found = Ai.extractViolations(reply)
                    val shown = Ai.stripJson(reply)
                    chatLog.add("bot" to (if (shown.isBlank()) "(пустой ответ)" else shown))
                    var added: List<Violation> = emptyList()
                    if (found.isNotEmpty()) {
                        added = violations.addMany(found, clipPath)
                        chatLog.add(
                            "system" to (if (added.isNotEmpty()) "В таблицу добавлено: " + added.size
                            else "Такие нарушения уже есть в таблице.")
                        )
                    }
                    changed()
                    onOk(found, added)
                }
            } catch (e: Exception) {
                val text = if (e is Ai.AiError) (e.message ?: "Ошибка") else "Непредвиденная ошибка: " + e.message
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
