package com.example.filedropper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import rikka.shizuku.Shizuku

@Suppress("DEPRECATION")
class MainActivity : Activity() {

    private val REQ_FILES = 1
    private val REQ_DIR = 2
    private val REQ_DEST = 3
    private val REQ_GRANT = 4
    private val REQ_NOTIF = 5
    private val REQ_SHIZUKU = 6

    private lateinit var store: Store
    private lateinit var selectionText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var autoBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var shizukuBtn: Button
    private lateinit var mtBtn: Button

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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        root.addView(label("Enotik sborki", 26f, true))

        selectionText = label("", 14f)
        root.addView(selectionText)
        root.addView(row(
            btn("Выбрать файл / ZIP", 1f) { pickFiles() },
            btn("Выбрать папку", 1f) { pickFolder() }
        ))
        root.addView(label("ZIP открою сам и возьму самую первую папку внутри.", 12f))

        root.addView(label("Действия", 16f, true))
        root.addView(row(
            btn("▶ Поставить файл", 1f) { actionPlace() },
            btn("✚ Записать кнопку", 1f) { actionCreate() }
        ))
        root.addView(row(
            btn("🗑 Удалить кнопку", 1f) { actionDelete() },
            btn("✎ Когда ставить", 1f) { actionEditNote() }
        ))
        root.addView(btn("⚡ Поставить сразу — выбрать куда") { actionQuick() })

        root.addView(label("Мои кнопки (нажми, чтобы выбрать)", 16f, true))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        root.addView(label("Настройки", 16f, true))
        overlayBtn = btn("") { toggleOverlay() }
        root.addView(overlayBtn)
        mtBtn = btn("") { toggleMt() }
        root.addView(mtBtn)
        shizukuBtn = btn("") { shizukuAction() }
        root.addView(shizukuBtn)
        autoBtn = btn("") { toggleAuto() }
        root.addView(autoBtn)
        root.addView(btn("Доступ ко всем файлам") { requestAllFiles() })
        root.addView(btn("Доступ к Android/data (если система даёт)") { grantAndroidData() })

        statusText = label("", 13f)
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })

        refresh()
        handleIncoming(intent)
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
        val names = store.pendingDir().listFiles()?.map { it.name }.orEmpty()
        selectionText.text = if (names.isEmpty()) "Файлы не выбраны. Выбери здесь или через «Поделиться» в любом приложении."
        else "Выбрано (${names.size}): " + names.take(5).joinToString(", ") + if (names.size > 5) "…" else ""

        listBox.removeAllViews()
        val presets = store.all()
        if (selectedId != null && presets.none { it.id == selectedId }) selectedId = null
        if (presets.isEmpty()) {
            listBox.addView(label("Пока нет кнопок. Выбери файл и нажми «Записать кнопку»."))
        }
        presets.forEach { p -> listBox.addView(card(p)) }

        autoBtn.text = if (isAutoOn()) "Автоставить при запуске приложения: ВКЛ" else "Автоставить при запуске приложения: ВЫКЛ"
        overlayBtn.text = if (isOverlayOn()) "Плавающая кнопка на экране: ВКЛ" else "Плавающая кнопка на экране: ВЫКЛ"
        shizukuBtn.text = "Shizuku: " + shizukuState()
        mtBtn.text = if (mtPackage() == null) "MT Manager не найден — выбор через систему"
        else "Выбирать через MT Manager: " + (if (useMt()) "ВКЛ" else "ВЫКЛ")
    }

    private fun destSummary(p: Preset): String = when (p.destKind) {
        "appdata" -> "Android/data/${p.destPackage}" + (if (p.destSub.isNotEmpty()) "/${p.destSub}" else "")
        "appobb" -> "Android/obb/${p.destPackage}" + (if (p.destSub.isNotEmpty()) "/${p.destSub}" else "")
        "path" -> p.destPath + (if (p.destSub.isNotEmpty()) "/${p.destSub}" else "")
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
        val files = FileOps.countFiles(store.dirFor(p.id))
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
        launchPicker(i, REQ_FILES)
    }

    private val mtPackages = listOf("bin.mt.plus", "bin.mt.plus.canary")

    private fun mtPackage(): String? = mtPackages.firstOrNull {
        try {
            packageManager.getPackageInfo(it, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun useMt() = getSharedPreferences("app", MODE_PRIVATE).getBoolean("use_mt", true)

    private fun toggleMt() {
        getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("use_mt", !useMt()).apply()
        refresh()
    }

    /** Адрес корня MT Manager в системном выборе (если MT Manager даёт свой провайдер файлов). */
    private fun mtInitialUri(mt: String): Uri? = try {
        val prov = packageManager.queryIntentContentProviders(Intent("android.content.action.DOCUMENTS_PROVIDER"), 0)
            .firstOrNull { it.providerInfo.packageName == mt }
        val auth = prov?.providerInfo?.authority?.substringBefore(';')
        if (auth == null) null else {
            contentResolver.query(
                DocumentsContract.buildRootsUri(auth),
                arrayOf(DocumentsContract.Root.COLUMN_DOCUMENT_ID), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) DocumentsContract.buildDocumentUri(auth, c.getString(0)) else null
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Открывает выбор файла/папки сначала в MT Manager, если он есть; иначе — в системе. */
    private fun launchPicker(base: Intent, req: Int) {
        val mt = if (useMt()) mtPackage() else null
        if (mt != null) {
            val tries = mutableListOf(Intent(base).setPackage(mt))
            if (base.action == Intent.ACTION_OPEN_DOCUMENT) {
                tries.add(Intent(base).setAction(Intent.ACTION_GET_CONTENT).setPackage(mt))
            }
            for (t in tries) {
                if (t.resolveActivity(packageManager) != null) {
                    try {
                        startActivityForResult(t, req)
                        return
                    } catch (_: Exception) {
                    }
                }
            }
            if (base.action == Intent.ACTION_OPEN_DOCUMENT_TREE || base.action == Intent.ACTION_OPEN_DOCUMENT) {
                mtInitialUri(mt)?.let { base.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        }
        startActivityForResult(base, req)
    }

    private fun pickFolder() {
        launchPicker(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DIR)
    }

    private fun handleIncoming(i: Intent?) {
        if (i == null) return
        val act = i.getStringExtra("action")
        if (act != null) {
            i.removeExtra("action")
            when (act) {
                "pick_files" -> pickFiles()
                "pick_folder" -> pickFolder()
                "quick" -> actionQuick()
                "create" -> actionCreate()
            }
            return
        }
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            else -> return
        }
        if (uris.isNotEmpty()) {
            importUris(uris, null)
            toast("Файлы получены. Нажми «Поставить сразу» или выбери кнопку")
        }
        i.action = null
    }

    private fun importUris(uris: List<Uri>, tree: Uri?) {
        status("Копирую выбранное (ZIP распаковываю)…")
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
                if (it.scheme == "content") {
                    try {
                        contentResolver.takePersistableUriPermission(
                            it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }
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

    // ---------------------------------------------------------------- действия

    private fun selected(): Preset? = store.all().firstOrNull { it.id == selectedId }

    private fun runPlace(p: Preset, src: java.io.File) {
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

    /** 1. Поставить файл */
    private fun actionPlace() {
        val p = selected()
        if (p == null) {
            toast("Сначала выбери кнопку из списка")
            return
        }
        val pending = store.pendingDir()
        val src = if (!pending.listFiles().isNullOrEmpty()) pending else store.dirFor(p.id)
        runPlace(p, src)
    }

    /** 2. Записать (создать) кнопку */
    private fun actionCreate() {
        if (store.pendingDir().listFiles().isNullOrEmpty()) {
            toast("Сначала выбери файлы, ZIP или папку")
            return
        }
        showPresetDialog(null, quick = false)
    }

    /** 3. Удалить кнопку */
    private fun actionDelete() {
        val p = selected()
        if (p == null) {
            toast("Сначала выбери кнопку из списка")
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
            toast("Сначала выбери кнопку из списка")
            return
        }
        showPresetDialog(p, quick = false)
    }

    /** Поставить выбранное сразу, выбрав место (в том числе другим проводником) */
    private fun actionQuick() {
        if (store.pendingDir().listFiles().isNullOrEmpty()) {
            toast("Сначала выбери файлы, ZIP или папку")
            return
        }
        showPresetDialog(null, quick = true)
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

    /** Свой обзор папок: видит Android/data и Android/obb через Shizuku (или root). */
    private fun browseFolders(start: String, onPick: (String) -> Unit) {
        val path = start.trimEnd('/').ifEmpty { "/" }
        Thread {
            val dirs = Placer.listDirs(path)
            runOnUiThread {
                val names = dirs ?: emptyList()
                val items = mutableListOf("✔ Выбрать эту папку", "⬆ Наверх")
                items.addAll(names.map { "📁 $it" })
                val title = path + if (dirs == null) "\n(нет доступа — включи Shizuku)" else if (names.isEmpty()) "\n(подпапок нет)" else ""
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setItems(items.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> onPick(path)
                            1 -> browseFolders(path.substringBeforeLast('/').ifEmpty { "/" }, onPick)
                            else -> browseFolders("$path/${names[which - 2]}", onPick)
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }.start()
    }

    private fun pickWithOtherManager() {
        if (useMt() && mtPackage() != null) {
            launchPicker(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DEST)
            return
        }
        val oi = Intent("org.openintents.action.PICK_DIRECTORY").apply {
            data = Uri.parse("file:///sdcard")
            putExtra("org.openintents.extra.TITLE", "Куда поставить")
        }
        val base = if (oi.resolveActivity(packageManager) != null) oi else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            startActivityForResult(Intent.createChooser(base, "Выбери проводник"), REQ_DEST)
        } catch (e: Exception) {
            toast("Не нашёл проводник с выбором папки. Впиши путь вручную.")
        }
    }

    /**
     * existing == null && !quick → новая кнопка; existing != null → правка текста/автозапуска;
     * quick → только выбор места и сразу поставить.
     */
    private fun showPresetDialog(existing: Preset?, quick: Boolean) {
        val isNew = existing == null && !quick
        val showDest = isNew || quick
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
            text = "Понимаю фразы: «в папку files/mods», «не заменять, если файл есть». По умолчанию файл заменяется."
        }

        var destKind = when (existing?.destKind) {
            "appdata", "appobb" -> existing.destKind
            else -> "folder"
        }
        var destTree = existing?.destTreeUri ?: ""
        val destLabel = TextView(this).apply { text = if (destTree.isEmpty()) "Папка не выбрана" else "Папка выбрана" }
        val pathEt = EditText(this).apply {
            this.hint = "или впиши путь, например /storage/emulated/0/Download"
            setText(if (existing?.destKind == "path") existing.destPath else "")
        }
        val pickDest = Button(this).apply {
            text = "Выбрать папку (системный выбор)"; isAllCaps = false
            setOnClickListener {
                onDestPicked = { u ->
                    destTree = u.toString()
                    pathEt.setText("")
                    destLabel.text = "Папка выбрана: " + (DocumentFile.fromTreeUri(this@MainActivity, u)?.name ?: "")
                }
                launchPicker(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DEST)
            }
        }
        val pickOther = Button(this).apply {
            text = "Выбрать в MT Manager / другом проводнике"; isAllCaps = false
            setOnClickListener {
                onDestPicked = { u ->
                    if (u.scheme == "file") {
                        destTree = ""
                        pathEt.setText(u.path ?: "")
                        destLabel.text = "Путь выбран"
                    } else {
                        destTree = u.toString()
                        pathEt.setText("")
                        destLabel.text = "Папка выбрана: " + (DocumentFile.fromTreeUri(this@MainActivity, u)?.name ?: "")
                    }
                }
                pickWithOtherManager()
            }
        }
        val pickBrowse = Button(this).apply {
            text = "📂 Обзор папок (видит Android/data)"; isAllCaps = false
            setOnClickListener {
                if (!Placer.shizukuReady()) toast("Для Android/data включи Shizuku (кнопка в настройках)")
                browseFolders(Environment.getExternalStorageDirectory().path) { picked ->
                    destTree = ""
                    pathEt.setText(picked)
                    destLabel.text = "Путь выбран"
                }
            }
        }
        val destApp = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, apps)
        }
        val subEt = EditText(this).apply {
            this.hint = "Подпапка (например files)"
            setText(existing?.destSub ?: if (isNew) "files" else "")
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
            val f = destKind == "folder"
            pickDest.visibility = if (f) View.VISIBLE else View.GONE
            pickOther.visibility = if (f) View.VISIBLE else View.GONE
            pickBrowse.visibility = if (f) View.VISIBLE else View.GONE
            destLabel.visibility = if (f) View.VISIBLE else View.GONE
            pathEt.visibility = if (f) View.VISIBLE else View.GONE
            destApp.visibility = if (f) View.GONE else View.VISIBLE
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
        val trigger = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, triggerApps)
        }
        existing?.triggerPackage?.takeIf { it.isNotEmpty() }?.let { pkg ->
            val idx = triggerApps.indexOfFirst { it.pkg == pkg }
            if (idx >= 0) trigger.setSelection(idx)
        }
        val autoCb = CheckBox(this).apply {
            text = "Ставить автоматически, когда это приложение запускается"
            isChecked = existing?.auto ?: false
        }

        if (isNew) form.addView(nameEt)
        if (showDest) {
            form.addView(label("Куда ставить", 14f, true))
            form.addView(rg)
            form.addView(pickBrowse)
            form.addView(pickDest)
            form.addView(pickOther)
            form.addView(destLabel)
            form.addView(pathEt)
            form.addView(destApp)
            form.addView(subEt)
            applyKind()
        }
        if (!quick) {
            form.addView(label("Когда ставить", 14f, true))
            form.addView(noteEt)
            form.addView(hint)
            form.addView(label("Приложение-триггер", 14f, true))
            form.addView(trigger)
            form.addView(autoCb)
        }

        fun applyDest(p: Preset): Boolean {
            p.destKind = destKind
            p.destPackage = (destApp.selectedItem as? AppInfo)?.pkg ?: ""
            p.destSub = subEt.text.toString().trim().trim('/')
            if (destKind == "folder") {
                val typed = pathEt.text.toString().trim()
                if (typed.isNotEmpty()) {
                    p.destKind = "path"; p.destPath = typed; p.destTreeUri = ""
                } else if (destTree.isNotEmpty()) {
                    p.destTreeUri = destTree; p.destPath = ""
                } else {
                    toast("Выбери папку назначения")
                    return false
                }
            } else if (p.destPackage.isEmpty()) {
                toast("Выбери приложение")
                return false
            }
            return true
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(
                when {
                    quick -> "Поставить сразу"
                    isNew -> "Новая кнопка"
                    else -> "Когда ставить: ${existing!!.name}"
                }
            )
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(if (quick) "Поставить" else "Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (quick) {
                val tmp = Preset(name = "Быстро")
                if (!applyDest(tmp)) return@setOnClickListener
                dlg.dismiss()
                runPlace(tmp, store.pendingDir())
                return@setOnClickListener
            }
            val trigPkg = (trigger.selectedItem as? AppInfo)?.pkg ?: ""
            val p = existing ?: Preset(name = nameEt.text.toString().trim().ifEmpty { "Без названия" })
            p.note = noteEt.text.toString().trim()
            p.triggerPackage = trigPkg
            p.auto = autoCb.isChecked && trigPkg.isNotEmpty()
            if (isNew) {
                if (!applyDest(p)) return@setOnClickListener
                FileOps.copyDir(store.pendingDir(), store.dirFor(p.id))
                FileOps.clear(store.pendingDir())
            }
            store.upsert(p)
            selectedId = p.id
            dlg.dismiss()
            refresh()
        }
    }

    // ---------------------------------------------------------------- настройки / разрешения

    private fun isAutoOn() = getSharedPreferences("app", MODE_PRIVATE).getBoolean("auto_on", false)
    private fun isOverlayOn() = getSharedPreferences("app", MODE_PRIVATE).getBoolean("overlay_on", false)

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = ops.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    private fun toggleOverlay() {
        val sp = getSharedPreferences("app", MODE_PRIVATE)
        if (isOverlayOn()) {
            stopService(Intent(this, OverlayService::class.java))
            sp.edit().putBoolean("overlay_on", false).apply()
            refresh()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Разреши «Поверх других окон» для Enotik sborki и вернись назад")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        askNotifPermission()
        startForegroundService(Intent(this, OverlayService::class.java))
        sp.edit().putBoolean("overlay_on", true).apply()
        refresh()
    }

    private fun toggleAuto() {
        val sp = getSharedPreferences("app", MODE_PRIVATE)
        if (isAutoOn()) {
            stopService(Intent(this, WatchService::class.java))
            sp.edit().putBoolean("auto_on", false).apply()
            refresh()
            return
        }
        askNotifPermission()
        if (!hasUsageAccess()) {
            toast("Включи Enotik sborki в списке и вернись назад")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        startForegroundService(Intent(this, WatchService::class.java))
        sp.edit().putBoolean("auto_on", true).apply()
        refresh()
    }

    private fun shizukuState(): String = try {
        when {
            !Shizuku.pingBinder() -> "не запущен (нажми — открою Shizuku)"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "готов ✔"
            else -> "нужно разрешение (нажми)"
        }
    } catch (_: Throwable) {
        "недоступен"
    }

    private fun shizukuAction() {
        try {
            if (!Shizuku.pingBinder()) {
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (i != null) startActivity(i) else toast("Установи приложение Shizuku и запусти его")
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_SHIZUKU)
            } else {
                toast("Shizuku уже готов")
            }
        } catch (_: Throwable) {
            toast("Shizuku недоступен")
        }
        refresh()
    }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                toast("Доступ уже есть")
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
