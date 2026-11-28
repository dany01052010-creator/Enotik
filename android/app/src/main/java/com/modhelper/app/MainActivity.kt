package com.modhelper.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val REQ_PROJECTION = 11
    private val REQ_PICK = 12
    private val REQ_PERMS = 13
    private val REQ_RULES_PICK = 14

    private lateinit var content: FrameLayout
    private var tab = 0
    private val attachments = ArrayList<Bitmap>()
    private var draft = ""
    private val refresh: () -> Unit = {
        if (tab == 4) {
            if (speechVoiceShown != Hub.voiceOn) show(4)
        } else if (!typing()) show(tab)
    }
    private val speechRefresh: () -> Unit = { if (tab == 4) updateSpeech() }
    private var speechSv: ScrollView? = null
    private var speechList: LinearLayout? = null
    private var speechShown = 0
    private var speechVoiceShown = false
    private var rulesDraft: EditText? = null
    private var tplDraft: EditText? = null

    private fun typing(): Boolean {
        val f = currentFocus
        return f is EditText
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vbox()
        root.setBackgroundColor(C.BG)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        val bar = hbox()
        val names = listOf("💬 Чат", "📜 Правила", "📋 Таблица", "🎥 Запись", "🎤 Речь", "⚙ Настройки")
        for ((i, n) in names.withIndex()) {
            bar.addView(button(n, false) { saveDrafts(); show(i) }, linp(WRAP, WRAP, 0f).also { it.setMargins(dp(3), dp(6), dp(3), dp(6)) })
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(bar)
        root.addView(hs, linp(MATCH, WRAP))
        content = FrameLayout(this)
        root.addView(content, linp(MATCH, 0, 1f))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
        Hub.listeners.add(refresh)
        Hub.speechListeners.add(speechRefresh)
        tab = 0
        show(tab)
        askBasicPermissions()
    }

    override fun onDestroy() {
        Hub.listeners.remove(refresh)
        Hub.speechListeners.remove(speechRefresh)
        super.onDestroy()
    }

    private fun askBasicPermissions() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    private fun saveDrafts() {
        rulesDraft?.let { Hub.setS("rules", it.text.toString().trim()) }
        tplDraft?.let { Hub.setS("template", it.text.toString().trim()) }
        rulesDraft = null
        tplDraft = null
    }

    private fun show(i: Int) {
        tab = i
        content.removeAllViews()
        val v = when (i) {
            0 -> chatTab()
            1 -> rulesTab()
            2 -> tableTab()
            3 -> recTab()
            4 -> speechTab()
            else -> settingsTab()
        }
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun pad(v: LinearLayout): LinearLayout {
        v.setPadding(dp(12), dp(6), dp(12), dp(12))
        return v
    }

    private fun scroll(inner: View): ScrollView {
        val s = ScrollView(this)
        s.addView(inner)
        return s
    }

    // ------------------------------------------------------------ чат
    private fun chatTab(): View {
        val col = pad(vbox())
        val log = vbox()
        if (Hub.chatLog.isEmpty()) {
            log.addView(
                label(
                    "Как пользоваться (ключи и интернет не нужны):\n1. «Правила» — вставь правила с номерами пунктов и сроками, например:\n" +
                        "   1.2 Оскорбление игроков — мут 2 часа\n   Слова в «кавычках» считаются запрещёнными.\n" +
                        "2. «Запись» — включи мини-окно и захват экрана.\n3. В игре жми 🚩 в мини-окне — прочитаю чат со скриншота, найду ник, пункт и срок.\n" +
                        "Здесь можно прислать скриншот или вставить текст переписки. Ник можно указать строкой «Ник: Vasya».", 13f, C.MUTED
                )
            )
        }
        for ((role, text) in Hub.chatLog) {
            val who = when (role) {
                "user" -> "Ты"
                "bot" -> "Помощник"
                else -> "•"
            }
            val color = when (role) {
                "user" -> C.ACCENT
                "bot" -> C.OK
                else -> C.MUTED
            }
            val b = vbox()
            b.background = rounded(if (role == "bot") C.PANEL else C.BG, 8)
            b.setPadding(dp(8), dp(6), dp(8), dp(6))
            b.addView(label(who, 11f, color, true))
            val t = label(text, 14f, if (role == "system") C.MUTED else C.FG)
            t.setTextIsSelectable(true)
            b.addView(t)
            log.addView(b, linp(MATCH, WRAP, 0f, dp(6)))
        }
        val sv = ScrollView(this)
        sv.addView(log)
        col.addView(sv, linp(MATCH, 0, 1f))
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }

        if (attachments.isNotEmpty()) {
            val row = hbox()
            for ((i, b) in attachments.withIndex()) {
                val iv = android.widget.ImageView(this)
                iv.setImageBitmap(b)
                iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                iv.setOnClickListener { attachments.removeAt(i); show(0) }
                row.addView(iv, linp(dp(70), dp(48)).also { it.rightMargin = dp(6) })
            }
            row.addView(label("нажми на картинку — убрать", 10f, C.MUTED))
            col.addView(row, linp(MATCH, WRAP, 0f, dp(6)))
        }
        val input = edit("Вставь переписку или напиши: Ник: Vasya + что он написал", draft, multiline = true)
        input.minLines = 2
        input.maxLines = 5
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { draft = s.toString() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        col.addView(input, linp(MATCH, WRAP, 0f, dp(6)))
        val row = hbox()
        row.addView(button("📋 Вставить") { pasteClipboard() }, linp(0, WRAP, 1f).also { it.rightMargin = dp(4) })
        row.addView(button("🖼 Галерея") { pickImage(REQ_PICK) }, linp(0, WRAP, 1f).also { it.rightMargin = dp(4) })
        val voice = android.widget.CheckBox(this)
        voice.text = "речь"
        voice.setTextColor(C.MUTED)
        voice.isChecked = Hub.i("chat_voice", 0) == 1
        voice.setOnCheckedChangeListener { _, c -> Hub.setI("chat_voice", if (c) 1 else 0) }
        row.addView(voice)
        row.addView(button("Отправить", true) { send(input) }, linp(0, WRAP, 1.2f).also { it.leftMargin = dp(4) })
        col.addView(row, linp(MATCH, WRAP, 0f, dp(6)))
        val clear = button("Очистить чат") { Hub.chatLog.clear(); Hub.history.clear(); show(0) }
        col.addView(clear, linp(WRAP, WRAP, 0f, dp(6)))
        return col
    }

    private fun send(input: EditText) {
        val text = input.text.toString().trim()
        val imgs = ArrayList(attachments)
        if (text.isEmpty() && imgs.isEmpty()) return
        draft = ""
        attachments.clear()
        Hub.chatLog.add("user" to (text + (if (imgs.isNotEmpty()) "\n[скриншотов: " + imgs.size + "]" else "")).trim())
        Hub.chatLog.add("system" to "Думаю…")
        show(0)
        Hub.analyze(Req(text, "", imgs, if (Hub.i("chat_voice", 0) == 1) 90 else 0), null, { _, added ->
            removeThinking()
            if (added.isNotEmpty()) Hub.overlay?.flash("Новое нарушение: " + added.joinToString { it.nick })
            show(0)
        }, { err ->
            removeThinking()
            draft = text
            attachments.addAll(imgs)
            show(0)
        })
    }

    private fun removeThinking() {
        val i = Hub.chatLog.indexOfLast { it.second == "Думаю…" }
        if (i >= 0) Hub.chatLog.removeAt(i)
    }

    private fun pasteClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val item = clip?.getItemAt(0)
            val uri = item?.uri
            if (uri != null) {
                val b = decode(uri)
                if (b != null) {
                    attachments.add(b)
                    show(0)
                    return
                }
            }
            val text = item?.text?.toString()
            if (!text.isNullOrBlank()) {
                draft += text
                show(0)
                return
            }
        } catch (e: Exception) {
        }
        Toast.makeText(this, "В буфере нет картинки или текста", Toast.LENGTH_SHORT).show()
    }

    private fun decode(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
            val o = BitmapFactory.Options()
            o.inSampleSize = sample
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        } catch (e: Exception) {
            null
        }
    }

    private fun pickedUris(data: Intent?): List<Uri> {
        val out = ArrayList<Uri>()
        val clip = data?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
        } else data?.data?.let { out.add(it) }
        return out
    }

    private fun pickImage(req: Int) {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(Intent.createChooser(i, "Выбери скриншоты (можно несколько)"), req)
    }

    // ------------------------------------------------------------ правила
    private fun rulesTab(): View {
        val col = pad(vbox())
        col.addView(label("Правила игры и сроки наказаний. Чем точнее — тем лучше работает помощник.", 13f, C.MUTED))
        val rules = edit("Вставь правила сюда…", Hub.s("rules"), multiline = true)
        rules.minLines = 10
        rulesDraft = rules
        col.addView(rules, linp(MATCH, WRAP, 0f, dp(6)))
        val row = hbox()
        row.addView(button("💾 Сохранить", true) { saveDrafts(); Toast.makeText(this, "Правила сохранены", Toast.LENGTH_SHORT).show(); show(1) }, linp(0, WRAP, 1f).also { it.rightMargin = dp(4) })
        row.addView(button("🖼 Со скриншотов (много)") { saveDrafts(); pickImage(REQ_RULES_PICK) }, linp(0, WRAP, 1f))
        col.addView(row, linp(MATCH, WRAP, 0f, dp(6)))
        col.addView(label("Как оформлять жалобы (образец, необязательно):", 13f, C.MUTED), linp(WRAP, WRAP, 0f, dp(14)))
        val tpl = edit("Например: Ник — пункт — причина — срок", Hub.s("template"), multiline = true)
        tplDraft = tpl
        col.addView(tpl, linp(MATCH, WRAP, 0f, dp(6)))
        return scroll(col)
    }

    // ------------------------------------------------------------ таблица
    private fun tableTab(): View {
        val col = pad(vbox())
        val items = Hub.violations.items
        if (items.isEmpty()) col.addView(label("Таблица пока пуста. Нарушения появятся после жалобы или анализа в чате.", 13f, C.MUTED))
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        for (v in items.reversed()) {
            val card = vbox()
            card.background = rounded(C.PANEL, 10)
            card.setPadding(dp(10), dp(8), dp(10), dp(8))
            val top = hbox()
            top.addView(label(v.nick, 16f, C.ACCENT, true), linp(0, WRAP, 1f))
            top.addView(label(fmt.format(java.util.Date(v.ts)), 11f, C.MUTED))
            card.addView(top)
            val rl = ruleLabel(v.rule)
            card.addView(label((if (rl.isNotEmpty()) "$rl · " else "") + v.reason, 14f, C.FG))
            card.addView(label("→ " + v.punishment.ifBlank { "срок не указан" }, 14f, C.WARN, true))
            if (v.clip.isNotEmpty()) card.addView(label("🎞 есть клип в Галерее", 11f, C.OK))
            val row = hbox()
            row.addView(button("✏ Изменить") { editDialog(v) }, linp(WRAP, WRAP).also { it.rightMargin = dp(4) })
            row.addView(button(if (v.hidden) "👁 Показать" else "🙈 Скрыть") {
                v.hidden = !v.hidden; Hub.violations.save(); Hub.changed()
            }, linp(WRAP, WRAP).also { it.rightMargin = dp(4) })
            row.addView(button("🗑") { Hub.violations.remove(v); Hub.changed() })
            if (v.clip.isNotEmpty()) row.addView(button("🎞") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.clip)).setDataAndType(Uri.parse(v.clip), "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) {
                    Toast.makeText(this, "Открой клип в Галерее (папка ModHelper)", Toast.LENGTH_LONG).show()
                }
            }, linp(WRAP, WRAP).also { it.leftMargin = dp(4) })
            card.addView(row, linp(MATCH, WRAP, 0f, dp(6)))
            col.addView(card, linp(MATCH, WRAP, 0f, dp(8)))
        }
        val row = hbox()
        row.addView(button("➕ Добавить", true) { editDialog(null) }, linp(WRAP, WRAP).also { it.rightMargin = dp(6) })
        row.addView(button("Очистить всё") {
            AlertDialog.Builder(this).setMessage("Удалить все записи?").setPositiveButton("Да") { _, _ ->
                Hub.violations.clear(); Hub.changed()
            }.setNegativeButton("Нет", null).show()
        })
        col.addView(row, linp(MATCH, WRAP, 0f, dp(10)))
        return scroll(col)
    }

    private fun editDialog(v: Violation?) {
        val col = vbox()
        col.setPadding(dp(16), dp(8), dp(16), 0)
        val n = edit("Ник", v?.nick ?: "")
        val r = edit("Пункт правил", v?.rule ?: "")
        val re = edit("Причина", v?.reason ?: "")
        val p = edit("Наказание (срок)", v?.punishment ?: "")
        for (e in listOf(n, r, re, p)) col.addView(e, linp(MATCH, WRAP, 0f, dp(6)))
        AlertDialog.Builder(this).setTitle(if (v == null) "Новая запись" else "Изменить").setView(col)
            .setPositiveButton("Сохранить") { _, _ ->
                if (v == null) {
                    Hub.violations.add(n.text.toString(), r.text.toString(), re.text.toString(), p.text.toString(), "")
                } else {
                    v.nick = n.text.toString().trim().ifEmpty { "?" }
                    v.rule = r.text.toString().trim()
                    v.reason = re.text.toString().trim()
                    v.punishment = p.text.toString().trim()
                    Hub.violations.save()
                }
                Hub.changed()
            }.setNegativeButton("Отмена", null).show()
    }

    // ------------------------------------------------------------ запись и голос
    private fun recTab(): View {
        val col = pad(vbox())
        col.addView(label("Мини-окно поверх игры", 15f, C.FG, true))
        col.addView(label("Окошко двигается пальцем. В нём: 🚩 жалоба, ▦ вид, ● кружок, ✕ закрыть.", 12f, C.MUTED))
        val on = Hub.overlay != null
        col.addView(
            button(if (on) "■ Выключить мини-окно" else "🪟 Включить мини-окно", !on) { toggleOverlay() },
            linp(WRAP, WRAP, 0f, dp(6))
        )

        col.addView(label("Захват экрана (запись как Fraps)", 15f, C.FG, true), linp(WRAP, WRAP, 0f, dp(18)))
        val cap = Hub.capture
        col.addView(
            label(
                if (cap != null) "Идёт запись · кадров в буфере: " + cap.frameCount() else "Запись выключена",
                12f, if (cap != null) C.OK else C.MUTED
            )
        )
        col.addView(
            button(if (cap != null) "■ Остановить запись" else "● Запустить запись", cap == null) { toggleCapture() },
            linp(WRAP, WRAP, 0f, dp(6))
        )
        val s1 = hbox()
        s1.addView(label("Кадров/с (1–6): ", 12f, C.MUTED))
        val fps = edit("1", Hub.i("fps_v2", 1).toString())
        fps.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        s1.addView(fps, linp(dp(60), WRAP))
        s1.addView(label("  Хранить минут: ", 12f, C.MUTED))
        val mins = edit("2", Hub.i("buffer_min_v2", 2).toString())
        mins.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        s1.addView(mins, linp(dp(60), WRAP))
        col.addView(s1, linp(MATCH, WRAP, 0f, dp(8)))
        col.addView(button("Сохранить (применится при следующем запуске)") {
            Hub.setI("fps_v2", (fps.text.toString().toIntOrNull() ?: 1).coerceIn(1, 6))
            Hub.setI("buffer_min_v2", (mins.text.toString().toIntOrNull() ?: 2).coerceIn(1, 15))
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }, linp(WRAP, WRAP, 0f, dp(6)))

        col.addView(
            button(if (Hub.i("cap_q", 0) == 1) "Нагрузка: обычная (чётче, но игра может лагать)" else "Нагрузка: лёгкая (меньше лагов)") {
                Hub.setI("cap_q", if (Hub.i("cap_q", 0) == 1) 0 else 1)
                Toast.makeText(this, "Применится при следующем запуске записи", Toast.LENGTH_SHORT).show()
                show(3)
            }, linp(WRAP, WRAP, 0f, dp(8))
        )
        col.addView(label("Вырезать нарушение", 15f, C.FG, true), linp(WRAP, WRAP, 0f, dp(18)))
        val s2 = hbox()
        s2.addView(label("от ", 12f, C.MUTED))
        val a = edit("40", Hub.i("clip_before", 40).toString())
        a.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        s2.addView(a, linp(dp(70), WRAP))
        s2.addView(label(" до ", 12f, C.MUTED))
        val b = edit("0", "0")
        b.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        s2.addView(b, linp(dp(70), WRAP))
        s2.addView(label(" сек назад", 12f, C.MUTED))
        col.addView(s2, linp(MATCH, WRAP, 0f, dp(6)))
        col.addView(button("✂ Вырезать клип", true) {
            val c = Hub.capture
            if (c == null) {
                Toast.makeText(this, "Сначала запусти запись", Toast.LENGTH_SHORT).show()
            } else {
                val st = a.text.toString().toIntOrNull() ?: 40
                val en = b.text.toString().toIntOrNull() ?: 0
                Hub.setI("clip_before", st)
                Toast.makeText(this, "Вырезаю…", Toast.LENGTH_SHORT).show()
                thread {
                    val uri = try { c.exportClip(st, en) } catch (e: Exception) { null }
                    Hub.main.post {
                        if (uri == null) {
                            Toast.makeText(this, "Клип не получился: в буфере нет кадров за этот отрезок", Toast.LENGTH_LONG).show()
                        } else {
                            val last = Hub.violations.items.lastOrNull()
                            if (last != null) {
                                last.clip = uri
                                Hub.violations.save()
                            }
                            Toast.makeText(this, "Клип сохранён в Галерею (папка ModHelper)", Toast.LENGTH_LONG).show()
                            Hub.changed()
                        }
                    }
                }
            }
        }, linp(WRAP, WRAP, 0f, dp(6)))
        col.addView(label("Клип прикрепится к последней записи в таблице.", 11f, C.MUTED))

        col.addView(label("Голос и речь игроков — во вкладке «🎤 Речь».", 12f, C.MUTED), linp(WRAP, WRAP, 0f, dp(18)))
        return scroll(col)
    }

    private fun toggleOverlay() {
        if (Hub.overlay != null) {
            stopService(Intent(this, OverlayService::class.java))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши «Поверх других окон» для ModHelper и вернись", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        Hub.main.postDelayed({ show(tab) }, 400)
    }

    private fun toggleCapture() {
        if (Hub.capture != null) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun toggleVoice() {
        if (Hub.voiceOn) {
            startService(Intent(this, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS + 100)
            return
        }
        startForegroundService(Intent(this, VoiceService::class.java))
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_PERMS + 100 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) toggleVoice()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    val i = Intent(this, CaptureService::class.java)
                    i.putExtra(CaptureService.EXTRA_CODE, resultCode)
                    i.putExtra(CaptureService.EXTRA_DATA, data)
                    startForegroundService(i)
                    Hub.main.postDelayed({ show(tab) }, 600)
                }
            }
            REQ_PICK -> {
                if (resultCode == RESULT_OK) {
                    for (u in pickedUris(data)) decode(u)?.let { attachments.add(it) }
                    show(0)
                }
            }
            REQ_RULES_PICK -> {
                val uris = if (resultCode == RESULT_OK) pickedUris(data) else emptyList()
                if (uris.isNotEmpty()) {
                    Toast.makeText(this, "Распознаю правила: скриншотов " + uris.size + "…", Toast.LENGTH_LONG).show()
                    thread {
                        var okCount = 0
                        var lastErr = ""
                        for ((n, u) in uris.withIndex()) {
                            val bmp = decode(u) ?: continue
                            try {
                                val text = Ocr.read(bmp)
                                okCount++
                                Hub.main.post {
                                    val old = Hub.s("rules")
                                    Hub.setS("rules", (if (old.isNotBlank()) old + "\n" else "") + text)
                                    if (tab == 1) show(1)
                                    Toast.makeText(this, "Готово " + (n + 1) + " из " + uris.size, Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                lastErr = e.message ?: "Ошибка"
                                break
                            }
                        }
                        Hub.main.post {
                            if (lastErr.isNotEmpty()) {
                                AlertDialog.Builder(this).setTitle("Не все скриншоты распознались")
                                    .setMessage("Распознано: $okCount из " + uris.size + "\n\n" + lastErr)
                                    .setPositiveButton("Ок", null).show()
                            } else {
                                Toast.makeText(this, "Правила добавлены — проверь текст", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ речь
    private fun speechLine(t: Long, x: String): View {
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val tv = label(fmt.format(java.util.Date(t)) + "  " + x, 14f, C.FG)
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(6), dp(4), dp(6), dp(4))
        return tv
    }

    private fun speechTab(): View {
        val col = pad(vbox())
        speechVoiceShown = Hub.voiceOn
        col.addView(
            label(
                "Здесь вся распознанная речь, без ограничений. Листай вверх — старые слова. История сохраняется на телефоне. " +
                    "Слушает микрофон; звук самой игры Android не отдаёт, поэтому голоса слышны, только если идут из динамика. Нужен интернет.",
                12f, C.MUTED
            )
        )
        val row = hbox()
        row.addView(
            button(if (Hub.voiceOn) "■ Остановить" else "🎤 Слушать", !Hub.voiceOn) { toggleVoice() },
            linp(WRAP, WRAP).also { it.rightMargin = dp(6) }
        )
        row.addView(button("🗑 Очистить историю") {
            AlertDialog.Builder(this).setMessage("Удалить всю историю речи?").setPositiveButton("Да") { _, _ ->
                Hub.transcript.clear(); Hub.speechChanged(); show(4)
            }.setNegativeButton("Нет", null).show()
        })
        col.addView(row, linp(MATCH, WRAP, 0f, dp(8)))
        val auto = android.widget.CheckBox(this)
        auto.text = "Сам проверять речь по правилам (ник неизвестен — будет «?»)"
        auto.setTextColor(C.FG)
        auto.isChecked = Hub.i("auto_speech", 0) == 1
        auto.setOnCheckedChangeListener { _, c -> Hub.setI("auto_speech", if (c) 1 else 0) }
        col.addView(auto, linp(MATCH, WRAP, 0f, dp(4)))
        col.addView(button("🔍 Проверить речь за 10 минут") { checkSpeech() }, linp(WRAP, WRAP, 0f, dp(4)))
        val list = vbox()
        val all = Hub.transcript.all()
        if (all.isEmpty()) list.addView(label("Пока ничего не услышано.", 13f, C.MUTED))
        for ((t, x) in all) list.addView(speechLine(t, x))
        val sv = ScrollView(this)
        sv.background = rounded(C.PANEL, 10)
        sv.addView(list)
        col.addView(sv, linp(MATCH, 0, 1f, dp(8)))
        speechSv = sv
        speechList = list
        speechShown = all.size
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
        return col
    }

    private fun checkSpeech() {
        val et = edit("Ник говорившего (можно пусто)", "")
        val box = vbox()
        box.setPadding(dp(16), dp(8), dp(16), 0)
        box.addView(et)
        AlertDialog.Builder(this).setTitle("Проверить речь").setView(box)
            .setPositiveButton("Проверить") { _, _ ->
                val nick = et.text.toString().trim()
                Hub.chatLog.add("user" to ("🎤 Проверка речи за 10 минут" + (if (nick.isNotEmpty()) " (ник: $nick)" else "")))
                Hub.analyze(Req("", nick, emptyList(), 600), null, { f, _ ->
                    Toast.makeText(this, if (f.isEmpty()) "Нарушений в речи не нашёл" else "Нашёл: " + f.size + " — см. вкладку «Таблица»", Toast.LENGTH_LONG).show()
                    show(0)
                }, { err -> Toast.makeText(this, err, Toast.LENGTH_LONG).show() })
            }.setNegativeButton("Отмена", null).show()
    }

    private fun updateSpeech() {
        val sv = speechSv ?: return
        val list = speechList ?: return
        val all = Hub.transcript.all()
        if (all.size < speechShown) {
            show(4)
            return
        }
        val ch = sv.getChildAt(0)
        val stick = ch == null || sv.scrollY + sv.height >= ch.height - dp(20)
        if (speechShown == 0 && all.isNotEmpty()) list.removeAllViews()
        for (i in speechShown until all.size) list.addView(speechLine(all[i].first, all[i].second))
        speechShown = all.size
        if (stick) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------ настройки
    private fun settingsTab(): View {
        val col = pad(vbox())
        col.addView(label("Приложение работает само: без API-ключей и без интернета (интернет нужен только для голоса).", 13f, C.FG))
        col.addView(label("Чем это работает: твои правила → пункты и сроки; скриншот → текст (распознавание на телефоне) → ник и сообщения → поиск нарушений по словарю и по запрещённым словам из правил.", 12f, C.MUTED), linp(WRAP, WRAP, 0f, dp(8)))

        col.addView(label("Сколько секунд видео до жалобы вырезать в клип", 13f, C.MUTED), linp(WRAP, WRAP, 0f, dp(16)))
        val cb = edit("40", Hub.i("clip_before", 40).toString())
        cb.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        cb.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val v = s.toString().toIntOrNull()
                if (v != null && v in 1..3600) Hub.setI("clip_before", v)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        col.addView(cb, linp(dp(120), WRAP, 0f, dp(6)))

        col.addView(label("Проверка распознавания текста", 13f, C.MUTED), linp(WRAP, WRAP, 0f, dp(16)))
        col.addView(button("🔎 Проверить чтение текста") { testOcr() }, linp(WRAP, WRAP, 0f, dp(6)))

        col.addView(
            label(
                "\nВажно: приложение ищет по словам и признакам и не понимает смысл и шутки. Всегда сверяй ник и пункт по экрану. " +
                    "Оверлей, запись экрана и микрофон могут не нравиться античитам и правилам сервера; запись голоса игроков " +
                    "тоже может быть запрещена. Решение о наказании принимаешь ты.", 12f, C.MUTED
            )
        )
        return scroll(col)
    }

    private fun testOcr() {
        Toast.makeText(this, "Проверяю…", Toast.LENGTH_SHORT).show()
        thread {
            val msg = try {
                val bmp = Bitmap.createBitmap(900, 260, Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp)
                cv.drawColor(android.graphics.Color.WHITE)
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                p.color = android.graphics.Color.BLACK
                p.textSize = 64f
                cv.drawText("Vasya_228: Привет мир", 30f, 110f, p)
                cv.drawText("Pety: проверка 123", 30f, 200f, p)
                val t = Ocr.read(bmp)
                "Должно быть: Vasya_228: Привет мир / Pety: проверка 123\n\nПрочитал:\n" + t
            } catch (e: Throwable) {
                "Ошибка: " + (e.message ?: e.javaClass.simpleName)
            }
            Hub.main.post { AlertDialog.Builder(this).setTitle("Чтение текста").setMessage(msg).setPositiveButton("Ок", null).show() }
        }
    }
}
