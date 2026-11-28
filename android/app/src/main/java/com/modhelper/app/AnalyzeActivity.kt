package com.modhelper.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Экран жалобы: скриншот → текст → ник → пункт правил и срок → «Добавить». Работает без интернета. */
class AnalyzeActivity : Activity() {
    private val REQ_PICK = 21
    private lateinit var nick: EditText
    private lateinit var note: EditText
    private lateinit var ocrBox: EditText
    private lateinit var status: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var thumbs: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var speech: CheckBox
    private var holder: ClipHolder? = null
    private var rules: List<Rule> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules = RuleBook.parse(Hub.s("rules"))
        if (intent.getBooleanExtra("clip", false)) {
            val cap = Hub.capture
            if (cap != null) {
                val h = ClipHolder()
                holder = h
                val before = Hub.i("clip_before", 40)
                thread {
                    try {
                        h.path = cap.exportClip(before, 0) ?: ""
                    } catch (e: Exception) {
                        // клип не получился
                    }
                    h.latch.countDown()
                }
            }
        }
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG

        val col = vbox()
        col.setPadding(dp(14), dp(10), dp(14), dp(28))
        col.addView(label("🚩 Жалоба", 22f, C.FG, true))
        col.addView(label("Экран сохранён. Укажи ник — программа подскажет пункт правил и срок.", 12f, C.MUTED))
        if (rules.isEmpty()) {
            col.addView(label("⚠ Правила пустые — добавь их во вкладке «Правила».", 13f, C.WARN), linp(MATCH, WRAP, 0f, dp(8)))
        }

        thumbs = hbox()
        val hs = HorizontalScrollView(this)
        hs.addView(thumbs)
        col.addView(hs, linp(MATCH, WRAP, 0f, dp(10)))
        val bt = hbox()
        bt.addView(button("📋 Вставить скрин") { paste() }, linp(WRAP, WRAP).also { it.rightMargin = dp(6) })
        bt.addView(button("🖼 Галерея") { pick() }, linp(WRAP, WRAP).also { it.rightMargin = dp(6) })
        bt.addView(button("🔍 Прочитать текст") { runOcr() })
        col.addView(bt, linp(MATCH, WRAP, 0f, dp(8)))
        status = label("", 12f, C.WARN)
        col.addView(status, linp(MATCH, WRAP, 0f, dp(6)))
        ocrBox = edit("Текст со скриншотов (можно поправить)", "", multiline = true)
        ocrBox.textSize = 12f
        ocrBox.minLines = 3
        ocrBox.maxLines = 8
        col.addView(ocrBox, linp(MATCH, WRAP, 0f, dp(6)))

        col.addView(label("Ник нарушителя", 13f, C.MUTED), linp(WRAP, WRAP, 0f, dp(14)))
        nick = edit("Ник", "")
        col.addView(nick, linp(MATCH, WRAP, 0f, dp(4)))
        chipRow = hbox()
        val cs = HorizontalScrollView(this)
        cs.addView(chipRow)
        col.addView(cs, linp(MATCH, WRAP, 0f, dp(6)))

        col.addView(label("Что произошло", 13f, C.MUTED), linp(WRAP, WRAP, 0f, dp(10)))
        note = edit("Например: оскорблял в чате, использовал читы", "", multiline = true)
        col.addView(note, linp(MATCH, WRAP, 0f, dp(4)))
        speech = CheckBox(this)
        speech.text = "Учесть речь за последние 90 секунд"
        speech.setTextColor(C.MUTED)
        speech.isChecked = Hub.transcript.recent(90).isNotEmpty()
        col.addView(speech, linp(WRAP, WRAP, 0f, dp(4)))

        val actions = hbox()
        actions.addView(button("🔎 Подобрать пункт", true) { analyze() }, linp(WRAP, WRAP).also { it.rightMargin = dp(6) })
        actions.addView(button("📜 Выбрать вручную") { manual() })
        col.addView(actions, linp(MATCH, WRAP, 0f, dp(10)))
        results = vbox()
        col.addView(results, linp(MATCH, WRAP, 0f, dp(8)))
        col.addView(button("Закрыть") { finish() }, linp(WRAP, WRAP, 0f, dp(16)))

        val sv = ScrollView(this)
        sv.setBackgroundColor(C.BG)
        sv.addView(col)
        sv.setOnApplyWindowInsetsListener { v, ins ->
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(sv)
        refreshThumbs()
        setChips(recentNicks())
        if (Hub.pendingImages.isNotEmpty()) runOcr()
    }

    override fun onDestroy() {
        if (isFinishing) Hub.pendingImages.clear()
        super.onDestroy()
    }

    private fun recentNicks(): List<String> =
        Hub.violations.items.reversed().map { it.nick }.filter { it != "?" }.distinct().take(6)

    private fun chip(text: String, onClick: () -> Unit): TextView {
        val t = label(text, 13f, C.FG, true)
        t.setPadding(dp(12), dp(6), dp(12), dp(6))
        t.background = rounded(C.PANEL2, 16)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun setChips(list: List<String>) {
        chipRow.removeAllViews()
        for (n in list.distinct().take(12)) {
            chipRow.addView(chip(n) { nick.setText(n); nick.setSelection(n.length) }, linp(WRAP, WRAP).also { it.rightMargin = dp(6) })
        }
    }

    private fun refreshThumbs() {
        thumbs.removeAllViews()
        for ((i, b) in Hub.pendingImages.withIndex()) {
            val iv = ImageView(this)
            iv.setImageBitmap(b)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setOnClickListener {
                Hub.pendingImages.removeAt(i)
                refreshThumbs()
            }
            thumbs.addView(iv, linp(dp(130), dp(84)).also { it.rightMargin = dp(6) })
        }
        if (Hub.pendingImages.isNotEmpty()) thumbs.addView(label("нажми на картинку —\nубрать", 10f, C.MUTED))
    }

    // ------------------------------------------------------------ чтение текста
    private fun runOcr() {
        val imgs = ArrayList(Hub.pendingImages)
        if (imgs.isEmpty()) {
            status.text = "Скриншотов нет — добавь или введи ник вручную."
            return
        }
        status.text = "Читаю текст со скриншота… (до полминуты)"
        thread {
            val sb = StringBuilder()
            var err = ""
            for (img in imgs) {
                try {
                    sb.append(Ocr.read(applicationContext, img)).append('\n')
                } catch (e: Throwable) {
                    err = e.message ?: e.javaClass.simpleName
                    break
                }
            }
            runOnUiThread {
                if (err.isNotEmpty() && sb.isEmpty()) {
                    status.text = "Не удалось прочитать текст ($err). Введи ник вручную."
                } else {
                    ocrBox.setText(sb.toString().trim())
                    status.text = "Текст прочитан ✔ Проверь ник и нажми «Подобрать пункт»."
                    setChips(RuleBook.nickCandidates(sb.toString()) + recentNicks())
                }
            }
        }
    }

    // ------------------------------------------------------------ подбор пункта
    private fun analyze() {
        results.removeAllViews()
        if (rules.isEmpty()) {
            results.addView(label("Сначала добавь правила во вкладке «Правила».", 13f, C.WARN))
            return
        }
        val ev = StringBuilder(note.text).append('\n').append(ocrBox.text)
        if (speech.isChecked) ev.append('\n').append(Hub.transcript.asText(90))
        val list = RuleBook.suggest(rules, ev.toString(), 4)
        if (list.isEmpty()) {
            results.addView(
                label("Подходящий пункт не нашёлся. Опиши нарушение словами в поле «Что произошло» или выбери пункт вручную.", 13f, C.WARN)
            )
            return
        }
        results.addView(label("Подходящие пункты:", 15f, C.FG, true))
        for (s in list) results.addView(card(s), linp(MATCH, WRAP, 0f, dp(8)))
    }

    private fun card(s: Suggestion): View {
        val c = vbox()
        c.background = rounded(C.PANEL, 10)
        c.setPadding(dp(10), dp(8), dp(10), dp(8))
        val r = s.rule
        val rl = ruleLabel(r.number)
        c.addView(label((if (rl.isNotEmpty()) "$rl · " else "") + r.title, 15f, C.FG, true))
        c.addView(label("→ " + r.punishment.ifBlank { "срок не указан в правилах" }, 14f, C.WARN, true))
        if (s.matched.isNotEmpty()) c.addView(label("совпало: " + s.matched.joinToString(", "), 11f, C.MUTED))
        c.addView(button("✔ Добавить в таблицу", true) { add(r) }, linp(WRAP, WRAP, 0f, dp(6)))
        return c
    }

    private fun manual() {
        if (rules.isEmpty()) {
            Toast.makeText(this, "Сначала добавь правила во вкладке «Правила»", Toast.LENGTH_LONG).show()
            return
        }
        val items = rules.map {
            val rl = ruleLabel(it.number)
            (if (rl.isNotEmpty()) "$rl · " else "") + it.title + "  → " + it.punishment.ifBlank { "—" }
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Выбери пункт").setItems(items) { _, i -> add(rules[i]) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun add(rule: Rule) {
        val n = nick.text.toString().trim()
        if (n.isEmpty()) {
            Toast.makeText(this, "Укажи ник нарушителя", Toast.LENGTH_LONG).show()
            nick.requestFocus()
            return
        }
        val extra = note.text.toString().trim()
        val reason = if (extra.isNotEmpty()) rule.title + " — " + extra.take(100) else rule.title
        val h = holder
        Toast.makeText(this, "Сохраняю…", Toast.LENGTH_SHORT).show()
        thread {
            val clip = h?.await(8) ?: ""
            Hub.main.post {
                val v = Hub.violations.add(n, rule.number, reason, rule.punishment, clip)
                Hub.changed()
                val msg = if (v != null) {
                    "Добавлено: $n · " + ruleLabel(rule.number) + " → " + rule.punishment.ifBlank { "срок не указан" }
                } else "Такая запись уже есть"
                Hub.overlay?.flash(msg)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // ------------------------------------------------------------ картинки
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

    private fun paste() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val uri = cm.primaryClip?.getItemAt(0)?.uri
            val b = if (uri != null) decode(uri) else null
            if (b != null) {
                Hub.pendingImages.add(b)
                refreshThumbs()
                runOcr()
                return
            }
        } catch (e: Exception) {
        }
        Toast.makeText(this, "В буфере нет картинки. Скопируй скриншот и нажми снова.", Toast.LENGTH_LONG).show()
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(Intent.createChooser(i, "Выбери скриншоты"), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { uris.add(it) }
            } else data.data?.let { uris.add(it) }
            for (u in uris) decode(u)?.let { Hub.pendingImages.add(it) }
            refreshThumbs()
            runOcr()
        }
    }
}
