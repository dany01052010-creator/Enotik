package com.modhelper.app

import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Маленькое окно поверх игры. Режимы: compact (колонка), table (широкая таблица), dot (кружок). */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var lp: WindowManager.LayoutParams
    private var mode = "compact"
    private var lastFull = "compact"
    private var toast = ""
    private var panelOpen = false
    private var panelText = ""
    private var panelStatus = ""
    private var extra = ArrayList<Bitmap>()
    private var panelInput: EditText? = null
    private val refresh: () -> Unit = { render() }
    private var voiceSv: ScrollView? = null
    private var voiceY = 0
    private var voiceStick = true
    private val clearToast = Runnable {
        toast = ""
        render()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, Hub.notification("Мини-окно включено"))
        if (root == null) create()
        if (intent?.action == "toast") flash(intent.getStringExtra("text") ?: "")
        return START_STICKY
    }

    private fun create() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mode = Hub.s("ov_mode", "compact")
        if (mode != "table" && mode != "dot") mode = "compact"
        lastFull = if (mode == "dot") "compact" else mode
        lp = WindowManager.LayoutParams(
            WRAP, WRAP, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Hub.i("ov_x", dp(12))
        lp.y = Hub.i("ov_y", dp(120))
        lp.alpha = 0.95f
        val r = LinearLayout(this)
        r.orientation = LinearLayout.VERTICAL
        root = r
        wm.addView(r, lp)
        Hub.overlay = this
        Hub.listeners.add(refresh)
        Hub.speechListeners.add(refresh)
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    fun flash(text: String) {
        toast = text
        Hub.main.removeCallbacks(clearToast)
        Hub.main.postDelayed(clearToast, 6000)
        render()
    }

    private fun setMode(m: String) {
        if (m != "dot") lastFull = m
        mode = m
        Hub.setS("ov_mode", m)
        render()
    }

    private fun setFocusable(on: Boolean) {
        lp.flags = if (on) {
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        try {
            wm.updateViewLayout(root, lp)
        } catch (e: Exception) {
        }
    }

    /** Перетаскивание пальцем. Короткое касание — onTap. */
    private fun makeDraggable(v: View, onTap: (() -> Unit)? = null) {
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x
                    oy = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - sx).toInt()
                    val dy = (e.rawY - sy).toInt()
                    if (Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = ox + dx
                        lp.y = oy + dy
                        try {
                            wm.updateViewLayout(root, lp)
                        } catch (ex: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        Hub.setI("ov_x", lp.x)
                        Hub.setI("ov_y", lp.y)
                    } else {
                        onTap?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun tap(text: String, size: Float = 16f, color: Int = C.MUTED, onClick: () -> Unit): TextView {
        val t = label(text, size, color, true)
        t.setPadding(dp(10), dp(4), dp(10), dp(4))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun render() {
        val r = root ?: return
        val oldV = voiceSv
        if (oldV != null) {
            voiceY = oldV.scrollY
            val ch = oldV.getChildAt(0)
            voiceStick = ch == null || voiceY + oldV.height >= ch.height - dp(10)
        } else {
            voiceStick = true
        }
        voiceSv = null
        r.removeAllViews()
        r.background = rounded(C.BG, 12)
        val items = Hub.violations.items.filter { !it.hidden }

        if (mode == "dot") {
            val d = label(if (items.isEmpty()) "⚖" else "⚖ " + items.size, 18f, Color_WHITE, true)
            d.setPadding(dp(16), dp(10), dp(16), dp(10))
            d.background = rounded(C.ACCENT, 24)
            r.background = null
            r.addView(d)
            makeDraggable(d) { setMode(lastFull) }
            return
        }

        val head = hbox()
        head.background = rounded(C.PANEL2, 12)
        val title = label("⚖ ModHelper", 12f, C.MUTED, true)
        title.setPadding(dp(10), dp(6), dp(10), dp(6))
        head.addView(title, linp(0, WRAP, 1f))
        makeDraggable(head)
        makeDraggable(title)
        head.addView(tap("🚩", 16f, C.WARN) { openPanel() })
        head.addView(tap(if (mode == "compact") "▦" else "▭") { setMode(if (mode == "compact") "table" else "compact") })
        head.addView(tap("●") { setMode("dot") })
        head.addView(tap("✕", 16f, C.DANGER) { stopSelf() })
        r.addView(head, linp(MATCH, WRAP))

        val width = dp(if (mode == "table") 340 else 250)
        val body = vbox()
        body.setPadding(dp(8), dp(6), dp(8), dp(8))

        if (panelOpen) body.addView(buildPanel(), linp(MATCH, WRAP, 0f, dp(4)))

        if (toast.isNotEmpty()) {
            val t = label(toast, 12f, C.WARN)
            t.setPadding(dp(8), dp(6), dp(8), dp(6))
            t.background = rounded(C.PANEL, 8)
            body.addView(t, linp(MATCH, WRAP, 0f, dp(4)))
        }

        if (items.isEmpty()) {
            val e = label("Нарушений нет.\nНажми 🚩 для жалобы.", 12f, C.MUTED)
            e.setPadding(dp(8), dp(10), dp(8), dp(10))
            body.addView(e)
        } else if (mode == "compact") {
            val groups = LinkedHashMap<String, ArrayList<Violation>>()
            for (v in items) groups.getOrPut(v.nick) { ArrayList() }.add(v)
            for ((nick, list) in groups) {
                val card = vbox()
                card.background = rounded(C.PANEL, 8)
                card.setPadding(dp(8), dp(6), dp(8), dp(6))
                val top = hbox()
                top.addView(label(nick, 15f, C.ACCENT, true), linp(0, WRAP, 1f))
                top.addView(tap("✕", 14f) {
                    Hub.violations.hideNick(nick)
                    Hub.changed()
                })
                card.addView(top)
                for (v in list) {
                    val row = hbox()
                    val txt = vbox()
                    val rl = ruleLabel(v.rule)
                    txt.addView(label((if (rl.isNotEmpty()) "$rl · " else "") + v.reason, 12f, C.FG))
                    txt.addView(label("→ " + v.punishment.ifBlank { "срок не указан" }, 12f, C.WARN, true))
                    row.addView(txt, linp(0, WRAP, 1f))
                    row.addView(tap("✕", 13f) {
                        v.hidden = true
                        Hub.violations.save()
                        Hub.changed()
                    })
                    card.addView(row, linp(MATCH, WRAP, 0f, dp(4)))
                }
                body.addView(card, linp(MATCH, WRAP, 0f, dp(6)))
            }
        } else {
            for (v in items) {
                val card = vbox()
                card.background = rounded(C.PANEL, 8)
                card.setPadding(dp(8), dp(6), dp(8), dp(6))
                val top = hbox()
                top.addView(label(v.nick, 14f, C.ACCENT, true), linp(0, WRAP, 1f))
                top.addView(label(ruleLabel(v.rule), 12f, C.MUTED))
                top.addView(tap("✕", 13f) {
                    v.hidden = true
                    Hub.violations.save()
                    Hub.changed()
                })
                card.addView(top)
                card.addView(label(v.reason, 12f, C.FG))
                card.addView(label("→ " + v.punishment.ifBlank { "срок не указан" }, 12f, C.WARN, true))
                body.addView(card, linp(MATCH, WRAP, 0f, dp(6)))
            }
        }

        if (Hub.voiceOn || Hub.transcript.size() > 0) {
            val all = Hub.transcript.all().takeLast(500)
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            val head2 = label("🎤 Речь (листай вверх — старые слова)", 11f, C.MUTED, true)
            head2.setPadding(dp(4), dp(8), dp(4), dp(2))
            body.addView(head2)
            val box = vbox()
            box.setPadding(dp(6), dp(4), dp(6), dp(4))
            if (all.isEmpty()) box.addView(label("Жду слова…", 11f, C.MUTED))
            for ((t, x) in all) {
                val tv = label(fmt.format(java.util.Date(t)) + "  " + x, 12f, C.FG)
                tv.setPadding(0, dp(2), 0, dp(2))
                box.addView(tv)
            }
            val vs = ScrollView(this)
            vs.background = rounded(C.PANEL, 8)
            vs.addView(box)
            body.addView(vs, linp(MATCH, dp(150)))
            voiceSv = vs
            vs.post {
                if (voiceStick) vs.fullScroll(View.FOCUS_DOWN) else vs.scrollTo(0, voiceY)
            }
        }

        val sv = ScrollView(this)
        sv.addView(body)
        val maxH = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        r.addView(sv, LinearLayout.LayoutParams(width, WRAP).also { })
        sv.post {
            if (sv.height > maxH) {
                sv.layoutParams = LinearLayout.LayoutParams(width, maxH)
            }
        }
    }

    // ---------- жалоба из мини-окна ----------
    private fun openPanel() {
        if (Hub.capture == null) {
            flash("Сначала включи захват экрана в приложении (вкладка «Запись»)")
            return
        }
        panelOpen = true
        panelText = ""
        panelStatus = ""
        extra = ArrayList()
        // скриншот делаем сразу, пока экран не изменился
        val cap = Hub.capture
        thread {
            val shot = cap?.snapshot()
            Hub.main.post {
                shotBitmap = shot
                panelStatus = if (shot == null) "Не удалось снять экран" else "Скриншот сделан ✔ Напиши ник/пояснение и жми «Анализ»"
                render()
            }
        }
        render()
    }

    private var shotBitmap: Bitmap? = null

    private fun buildPanel(): View {
        val p = vbox()
        p.background = rounded(C.PANEL, 8)
        p.setPadding(dp(8), dp(8), dp(8), dp(8))
        p.addView(label("🚩 Жалоба", 13f, C.FG, true))
        p.addView(label(panelStatus.ifEmpty { "Снимаю экран…" }, 11f, C.WARN))
        if (extra.isNotEmpty()) p.addView(label("Доп. скриншотов: " + extra.size, 11f, C.OK))
        val et = edit("Ник или пояснение (можно пусто)", panelText)
        et.textSize = 12f
        et.setOnFocusChangeListener { _, f -> if (f) setFocusable(true) }
        et.setOnClickListener { setFocusable(true) }
        panelInput = et
        p.addView(et, linp(MATCH, WRAP, 0f, dp(4)))
        val row = hbox()
        row.addView(button("📋 Вставить скрин") { pasteExtra() }, linp(0, WRAP, 1f))
        p.addView(row, linp(MATCH, WRAP, 0f, dp(4)))
        val row2 = hbox()
        row2.addView(button("Отмена") { closePanel() }, linp(0, WRAP, 1f))
        row2.addView(button("Анализ", true) { submit() }, linp(0, WRAP, 1f))
        p.addView(row2, linp(MATCH, WRAP, 0f, dp(4)))
        return p
    }

    private fun closePanel() {
        panelOpen = false
        panelInput = null
        shotBitmap = null
        setFocusable(false)
        render()
    }

    private fun pasteExtra() {
        panelText = panelInput?.text?.toString() ?: ""
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = cm.primaryClip?.getItemAt(0)
            val uri = item?.uri
            if (uri != null) {
                contentResolver.openInputStream(uri)?.use { s ->
                    val b = BitmapFactory.decodeStream(s)
                    if (b != null) {
                        extra.add(b)
                        panelStatus = "Скриншот добавлен (" + extra.size + ")"
                    } else panelStatus = "В буфере не картинка"
                }
            } else {
                panelStatus = "В буфере нет картинки. Скопируй скриншот и нажми снова."
            }
        } catch (e: Exception) {
            panelStatus = "Не получилось вставить: " + e.message
        }
        render()
    }

    private fun submit() {
        val shot = shotBitmap
        if (shot == null) {
            panelStatus = "Скриншота нет, закрой и открой 🚩 заново"
            render()
            return
        }
        val note = (panelInput?.text?.toString() ?: panelText).trim()
        val ex = ArrayList(extra)
        setFocusable(false)
        panelStatus = "Анализирую…"
        panelText = note
        render()
        val before = Hub.i("clip_before", 40)
        val cap = Hub.capture
        val holder = ClipHolder()
        thread {
            try {
                holder.path = cap?.exportClip(before, 0) ?: ""
            } catch (e: Exception) {
            }
            holder.latch.countDown()
        }
        val prompt = "ЖАЛОБА. Первый скриншот — экран игры в момент жалобы. " +
            (if (ex.isNotEmpty()) "Остальные скриншоты — доказательства модератора. " else "") +
            "Определи ник нарушителя, нарушенный пункт правил, причину и срок наказания." +
            (if (note.isNotEmpty()) "\nПояснение модератора: $note" else "")
        val imgs = ArrayList<Bitmap>()
        imgs.add(shot)
        imgs.addAll(ex)
        Hub.chatLog.add("user" to ("🚩 Жалоба" + (if (note.isNotEmpty()) ": $note" else "") + " [скриншотов: " + imgs.size + "]"))
        Hub.askAi(Ai.Msg("user", prompt, imgs), holder, true, { found, added ->
            panelOpen = false
            panelInput = null
            shotBitmap = null
            toast = if (added.isNotEmpty()) "Новое нарушение: " + added.joinToString { it.nick }
            else if (found.isEmpty()) "Нарушение не найдено — смотри ответ в чате" else "Такое нарушение уже есть"
            render()
            Hub.main.postDelayed(clearToast, 6000)
        }, { err ->
            panelStatus = "⚠ $err"
            render()
        })
    }

    override fun onDestroy() {
        Hub.listeners.remove(refresh)
        Hub.speechListeners.remove(refresh)
        Hub.main.removeCallbacks(clearToast)
        try {
            root?.let { wm.removeView(it) }
        } catch (e: Exception) {
        }
        root = null
        if (Hub.overlay === this) Hub.overlay = null
        Hub.changed()
        super.onDestroy()
    }

    companion object {
        const val Color_WHITE = -1
    }
}
