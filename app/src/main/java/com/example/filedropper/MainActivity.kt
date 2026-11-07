package com.example.filedropper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private val REQ_FILES = 1
    private val REQ_DIR = 2
    private val REQ_DEST = 3
    private val REQ_GRANT = 4
    private val REQ_NOTIF = 5

    private lateinit var store: Store
    private lateinit var selectionText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var autoBtn: Button

    private var selectedId: String? = null
    private var onDestPicked: ((Uri) -> Unit)? = null

    private data class AppInfo(val label: String, val pkg: String) {
        override fun toString() = "$label  ($pkg)"
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, weight: Float = 0f, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = if (weight > 0f)
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun label(text: String, size: Float = 14f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        root.addView(label("FileDropper", 26f, true))

        selectionText = label("", 14f)
        root.addView(selectionText)
        root.addView(row(
            btn("Выбрать файлы", 1f) { pickFiles() },
            btn("Выбрать папку", 1f) { pickFolder() }
        ))

        root.addView(label("Действия", 16f, true))
        root.addView(row(
            btn("▶ Поставить файл", 1f) { actionPlace() },
            btn("✚ Записать кнопку", 1f) { actionCreate() }
        ))
        root.addView(row(
            btn("🗑 Удалить кнопку", 1f) { actionDelete() },
            btn("✎ Когда ставить", 1f) { actionEditNote() }
        ))

        root.addView(label("Мои кнопки (нажми, чтобы выбрать)", 16f, true))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        root.addView(label("Настройки", 16f, true))
        autoBtn = btn("") { toggleAuto() }
        root.addView(autoBtn)
        root.addView(btn("Доступ ко всем файлам") { requestAllFiles() })
        root.addView(btn("Доступ к Android/data (если система даёт)") { grantAndroidData() })

        statusText = label("", 13f)
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })

        handleIncoming(intent)
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun status(s: String) {
        statusText.text = s
    }

    private fun refresh() {
        // выбранные файлы
        val names = store.pendingDir().listFiles()?.map { it.name }.orEmpty()
        selectionText.text = if (names.isEmpty()) "Файлы не выбраны. Выбери здесь или через «Поделиться» в любом приложении."
        else "Выбрано (${names.size}): " + names.take(5).joinToString(", ") + if (names.size > 5) "…" else ""

        // кнопки
        listBox.removeAllViews()
        val presets = store.all()
        if (selectedId != null && presets.none { it.id == selectedId }) selectedId = null
        if (presets.isEmpty()) {
            listBox.addView(label("Пока нет кнопок. Выбери файл и нажми «Записать кнопку»."))
        }
        presets.forEach { p -> listBox.addView(card(p)) }

        autoBtn.text = if (isAutoOn()) "Автоставить при запуске приложения: ВКЛ" else "Автоставить при запуске приложения: ВЫКЛ"
    }

    private fun destSummary(p: Preset): String = when (p.destKind) {
        "appdata" -> "Android/data/${p.destPackage}" + (if (p.destSub.isNotEmpty()) "/${p.destSub}" else "")
        "appobb" -> "Android/obb/${p.destPackage}" + (if (p.destSub.isNotEmpty()) "/${p.destSub}" else "")
        else -> {
            val n = try {
                DocumentFile.fromTreeUri(this, Uri.parse(p.destTreeUri))?.name
            } catch (_: Exception) {
                null
            }
            "Папка: " + (n ?: "не выбрана") + (if (p.destSub.isNotEmpty()) "/${p.destSub}" else "")
        }
    }

    private fun card(p: Preset): View {
        val sel = p.id == selectedId
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (sel) Color.parseColor("#E3F2FD") else Color.parseColor("#F5F5F5"))
                setStroke(dp(if (sel) 2 else 1), if (sel) Color.parseColor("#1E88E5") else Color.LTGRAY)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(4)) }
            setOnClickListener {
                selectedId = p.id
                refresh()
            }
        }
        box.addView(TextView(this).apply {
            text = p.name; textSize = 17f; setTypeface(null, Typeface.BOLD)
        })
        box.addView(TextView(this).apply { text = "→ " + destSummary(p); textSize = 13f })
        if (p.note.isNotBlank()) box.addView(TextView(this).apply {
            text = "Когда ставить: " + p.note; textSize = 13f
        })
        if (p.auto && p.triggerPackage.isNotEmpty()) box.addView(TextView(this).apply {
            text = "Авто при запуске: ${p.triggerPackage}"; textSize = 12f
        })
        val files = FileOps.countFiles(store.dirFor(p.id)) .let { if (store.dirFor(p.id).listFiles().isNullOrEmpty()) 0 else it }
        box.addView(TextView(this).apply { text = "Сохранено файлов: $files"; textSize = 12f })
        return box
    }

    // ---------------------------------------------------------------- выбор файлов

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, REQ_FILES)
    }

    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DIR)
    }

    private fun handleIncoming(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            else -> return
        }
        if (uris.isNotEmpty()) {
            importUris(uris, null)
            Toast.makeText(this, "Файлы получены. Выбери кнопку и нажми «Поставить файл»", Toast.LENGTH_LONG).show()
        }
        i.action = null
    }

    private fun importUris(uris: List<Uri>, tree: Uri?) {
        status("Копирую выбранное…")
        Thread {
            var n = 0
            var err: String? = null
            try {
                val dir = store.pendingDir()
                FileOps.clear(dir)
                uris.forEach { if (FileOps.copyUri(this, it, dir) != null) n++ }
                if (tree != null) {
                    FileOps.copyTree(this, tree, dir)
                    n++
                }
            } catch (e: Exception) {
                err = e.message
            }
            runOnUiThread {
                refresh()
                status(if (err != null) "Ошибка при копировании: $err" else "Выбрано: $n")
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_FILES -> {
                val uris = ArrayList<Uri>()
                data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                importUris(uris, null)
            }
            REQ_DIR -> data.data?.let { importUris(emptyList(), it) }
            REQ_DEST -> data.data?.let {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                onDestPicked?.invoke(it)
            }
            REQ_GRANT -> data.data?.let {
                try {
                    contentResolver.takePersistableUriPermission(
                        it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val sp = getSharedPreferences("grants", MODE_PRIVATE)
                    val set = (sp.getStringSet("uris", emptySet()) ?: emptySet()).toMutableSet()
                    set.add(it.toString())
                    sp.edit().putStringSet("uris", set).apply()
                    status("Доступ сохранён: " + DocumentsContract.getTreeDocumentId(it))
                } catch (e: Exception) {
                    status("Не удалось сохранить доступ: ${e.message}")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 4 действия

    private fun selected(): Preset? = store.all().firstOrNull { it.id == selectedId }

    /** 1. Поставить файл */
    private fun actionPlace() {
        val p = selected()
        if (p == null) {
            Toast.makeText(this, "Сначала выбери кнопку из списка", Toast.LENGTH_SHORT).show()
            return
        }
        val pending = store.pendingDir()
        val src = if (!pending.listFiles().isNullOrEmpty()) pending else store.dirFor(p.id)
        status("Ставлю «${p.name}»…")
        Thread {
            val r = Placer.place(this, src, p)
            runOnUiThread {
                status((if (r.ok) "✔ " else "✘ ") + r.message)
                AlertDialog.Builder(this)
                    .setTitle(if (r.ok) "Поставлено" else "Не получилось")
                    .setMessage(r.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    /** 2. Записать (создать) кнопку */
    private fun actionCreate() {
        if (store.pendingDir().listFiles().isNullOrEmpty()) {
            Toast.makeText(this, "Сначала выбери файлы или папку", Toast.LENGTH_SHORT).show()
            return
        }
        showPresetDialog(null)
    }

    /** 3. Удалить кнопку */
    private fun actionDelete() {
        val p = selected()
        if (p == null) {
            Toast.makeText(this, "Сначала выбери кнопку из списка", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить «${p.name}»?")
            .setMessage("Кнопка и её сохранённые файлы будут удалены из приложения. Уже поставленные файлы не трогаются.")
            .setPositiveButton("Удалить") { _, _ ->
                store.delete(p)
                selectedId = null
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** 4. Текст «когда ставить» */
    private fun actionEditNote() {
        val p = selected()
        if (p == null) {
            Toast.makeText(this, "Сначала выбери кнопку из списка", Toast.LENGTH_SHORT).show()
            return
        }
        showPresetDialog(p)
    }

    // ---------------------------------------------------------------- диалог кнопки

    private fun installedApps(): List<AppInfo> {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** existing == null → новая кнопка (полная форма); иначе правим только текст/автозапуск. */
    private fun showPresetDialog(existing: Preset?) {
        val full = existing == null
        val apps = installedApps()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val nameEt = EditText(this).apply { hint = "Название кнопки"; setText(existing?.name ?: "") }
        val noteEt = EditText(this).apply {
            hint = "Когда ставить этот файл (текст)"
            setText(existing?.note ?: "")
            minLines = 3
            gravity = Gravity.TOP
        }
        val hint = TextView(this).apply {
            textSize = 12f
            text = "Понимаю фразы: «в папку files/mods», «не заменять, если файл есть». " +
                "По умолчанию файл заменяется."
        }

        var destKind = existing?.destKind ?: "folder"
        var destTree = existing?.destTreeUri ?: ""
        val destLabel = TextView(this).apply {
            text = if (destTree.isEmpty()) "Папка не выбрана" else "Папка выбрана"
        }
        val pickDest = Button(this).apply {
            text = "Выбрать папку назначения"; isAllCaps = false
            setOnClickListener {
                onDestPicked = { u ->
                    destTree = u.toString()
                    destLabel.text = "Папка выбрана: " + (DocumentFile.fromTreeUri(this@MainActivity, u)?.name ?: "")
                }
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DEST)
            }
        }
        val destApp = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, apps) }
        val subEt = EditText(this).apply {
            this.hint = "Подпапка (например files)"
            setText(existing?.destSub ?: if (full) "files" else "")
        }
        existing?.destPackage?.takeIf { it.isNotEmpty() }?.let { pkg ->
            val idx = apps.indexOfFirst { it.pkg == pkg }
            if (idx >= 0) destApp.setSelection(idx)
        }

        val rg = RadioGroup(this)
        val rbFolder = RadioButton(this).apply { text = "Папка на телефоне"; id = 101 }
        val rbData = RadioButton(this).apply { text = "Android/data выбранного приложения"; id = 102 }
        val rbObb = RadioButton(this).apply { text = "Android/obb выбранного приложения"; id = 103 }
        rg.addView(rbFolder); rg.addView(rbData); rg.addView(rbObb)

        fun applyKind() {
            pickDest.visibility = if (destKind == "folder") View.VISIBLE else View.GONE
            destLabel.visibility = if (destKind == "folder") View.VISIBLE else View.GONE
            destApp.visibility = if (destKind == "folder") View.GONE else View.VISIBLE
        }
        when (destKind) {
            "appdata" -> rbData.isChecked = true
            "appobb" -> rbObb.isChecked = true
            else -> rbFolder.isChecked = true
        }
        rg.setOnCheckedChangeListener { _, id ->
            destKind = when (id) { 102 -> "appdata"; 103 -> "appobb"; else -> "folder" }
            applyKind()
        }

        val triggerApps = listOf(AppInfo("— не выбрано —", "")) + apps
        val trigger = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, triggerApps) }
        existing?.triggerPackage?.takeIf { it.isNotEmpty() }?.let { pkg ->
            val idx = triggerApps.indexOfFirst { it.pkg == pkg }
            if (idx >= 0) trigger.setSelection(idx)
        }
        val autoCb = CheckBox(this).apply {
            text = "Ставить автоматически, когда это приложение запускается"
            isChecked = existing?.auto ?: false
        }

        if (full) {
            form.addView(nameEt)
            form.addView(label("Куда ставить", 14f, true))
            form.addView(rg)
            form.addView(pickDest)
            form.addView(destLabel)
            form.addView(destApp)
            form.addView(subEt)
            applyKind()
        }
        form.addView(label("Когда ставить", 14f, true))
        form.addView(noteEt)
        form.addView(hint)
        form.addView(label("Приложение-триггер", 14f, true))
        form.addView(trigger)
        form.addView(autoCb)

        AlertDialog.Builder(this)
            .setTitle(if (full) "Новая кнопка" else "Когда ставить: ${existing!!.name}")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Сохранить") { _, _ ->
                val trigPkg = (trigger.selectedItem as? AppInfo)?.pkg ?: ""
                val p = existing ?: Preset(name = nameEt.text.toString().trim().ifEmpty { "Без названия" })
                p.note = noteEt.text.toString().trim()
                p.triggerPackage = trigPkg
                p.auto = autoCb.isChecked && trigPkg.isNotEmpty()
                if (full) {
                    p.destKind = destKind
                    p.destTreeUri = destTree
                    p.destPackage = (destApp.selectedItem as? AppInfo)?.pkg ?: ""
                    p.destSub = subEt.text.toString().trim().trim('/')
                    if (destKind == "folder" && destTree.isEmpty()) {
                        Toast.makeText(this, "Выбери папку назначения", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    FileOps.copyDir(store.pendingDir(), store.dirFor(p.id))
                    FileOps.clear(store.pendingDir())
                }
                store.upsert(p)
                selectedId = p.id
                refresh()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------------------------------------------------------------- настройки / разрешения

    private fun isAutoOn() = getSharedPreferences("app", MODE_PRIVATE).getBoolean("auto_on", false)

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun toggleAuto() {
        val sp = getSharedPreferences("app", MODE_PRIVATE)
        if (isAutoOn()) {
            stopService(Intent(this, WatchService::class.java))
            sp.edit().putBoolean("auto_on", false).apply()
            refresh()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
        if (!hasUsageAccess()) {
            Toast.makeText(this, "Включи FileDropper в списке и вернись назад", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        startForegroundService(Intent(this, WatchService::class.java))
        sp.edit().putBoolean("auto_on", true).apply()
        refresh()
    }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Доступ уже есть", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 10
            )
        }
    }

    private fun grantAndroidData() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata")
            )
        }
        startActivityForResult(i, REQ_GRANT)
    }
}
