package com.example.filedropper

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ставит файлы из папки src по назначению кнопки.
 * Для закрытых папок (Android/data, Android/obb) пробует по очереди:
 * напрямую → Shizuku → root → доступ через системный выбор папки.
 */
object Placer {

    data class Result(val ok: Boolean, val message: String)

    private class Stat {
        var ok = 0
        var skipped = 0
        val errors = mutableListOf<String>()
    }

    private fun root(): String = Environment.getExternalStorageDirectory().path

    fun place(ctx: Context, src: File, p: Preset): Result {
        val opts = NoteParser.parse(p.note)
        val sub = (opts.subPath ?: p.destSub).trim().trim('/')
        val items = src.listFiles()?.sortedBy { it.name }.orEmpty()
        if (items.isEmpty()) return Result(false, "Нет файлов: сначала выбери файл, ZIP или папку")
        return try {
            when (p.destKind) {
                "folder" -> {
                    val r = toTree(ctx, items, p, sub, opts.overwrite)
                    if (r.ok) r else {
                        val base = treePath(p.destTreeUri)
                        val pr = if (base != null) privileged(ctx, items, withSub(base, sub), opts.overwrite) else null
                        pr ?: r
                    }
                }
                "path" -> {
                    if (p.destPath.isBlank()) Result(false, "Путь назначения не указан")
                    else {
                        val dest = withSub(p.destPath.trim(), sub)
                        privileged(ctx, items, dest, opts.overwrite) ?: Result(false, failMessage(dest.path))
                    }
                }
                else -> toApp(ctx, items, p, sub, opts.overwrite)
            }
        } catch (e: Exception) {
            Result(false, "Ошибка: ${e.message}")
        }
    }

    private fun withSub(base: String, sub: String): File = if (sub.isEmpty()) File(base) else File(base, sub)

    /** Путь на диске для выбранной через систему папки (только основная память и SD-карта). */
    fun treePath(uriStr: String): String? {
        return try {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(uriStr))
            val vol = id.substringBefore(':')
            val rest = id.substringAfter(':', "")
            val base = when {
                vol == "primary" -> root()
                Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(vol) -> "/storage/$vol"
                else -> return null
            }
            if (rest.isEmpty()) base else "$base/$rest"
        } catch (_: Exception) {
            null
        }
    }

    private fun finish(st: Stat, how: String): Result {
        if (st.errors.isNotEmpty()) {
            return Result(false, "Не всё поставилось ($how). Ошибки: " + st.errors.take(3).joinToString("; "))
        }
        val skip = if (st.skipped > 0) ", пропущено уже существующих: ${st.skipped}" else ""
        return Result(true, "Готово ($how): файлов поставлено ${st.ok}$skip")
    }

    private fun failMessage(path: String): String {
        val why = when {
            Build.VERSION.SDK_INT >= 33 ->
                "Android 13+ закрывает Android/data для обычных приложений — включи Shizuku (кнопка в приложении) или root."
            Build.VERSION.SDK_INT >= 30 ->
                "Android 11–12 закрывает Android/data — включи Shizuku, дай доступ к папке или root."
            else -> "Дай приложению доступ ко всем файлам."
        }
        return "Не получилось записать в $path. $why"
    }

    // ---------- назначение: папка, выбранная через систему/проводник (SAF) ----------

    private fun toTree(ctx: Context, items: List<File>, p: Preset, sub: String, overwrite: Boolean): Result {
        if (p.destTreeUri.isEmpty()) return Result(false, "Не выбрана папка назначения")
        val root = DocumentFile.fromTreeUri(ctx, Uri.parse(p.destTreeUri))
            ?: return Result(false, "Папка назначения недоступна")
        if (!root.canWrite()) return Result(false, "Нет доступа к папке — выбери её заново в настройках кнопки")
        val dir = ensureDirs(root, sub.split('/').filter { it.isNotEmpty() })
            ?: return Result(false, "Не могу создать подпапку «$sub»")
        val st = Stat()
        items.forEach { copyToDoc(ctx, it, dir, overwrite, st) }
        return finish(st, "в папку")
    }

    private fun ensureDirs(parent: DocumentFile, names: List<String>): DocumentFile? {
        var cur = parent
        for (n in names) {
            val ex = cur.findFile(n)
            cur = if (ex != null && ex.isDirectory) ex else (cur.createDirectory(n) ?: return null)
        }
        return cur
    }

    private fun copyToDoc(ctx: Context, f: File, dir: DocumentFile, overwrite: Boolean, st: Stat) {
        if (f.isDirectory) {
            val d = ensureDirs(dir, listOf(f.name))
            if (d == null) {
                st.errors.add("папка ${f.name}")
                return
            }
            f.listFiles()?.forEach { copyToDoc(ctx, it, d, overwrite, st) }
            return
        }
        val ex = dir.findFile(f.name)
        if (ex != null) {
            if (!overwrite) {
                st.skipped++
                return
            }
            ex.delete()
        }
        val target = dir.createFile("application/octet-stream", f.name)
        if (target == null) {
            st.errors.add("${f.name}: не создался")
            return
        }
        val out = ctx.contentResolver.openOutputStream(target.uri, "wt")
        if (out == null) {
            st.errors.add("${f.name}: нет записи")
            return
        }
        out.use { o -> f.inputStream().use { it.copyTo(o) } }
        if (target.length() != f.length()) st.errors.add("${f.name}: размер не совпал") else st.ok++
    }

    // ---------- назначение: Android/data или Android/obb выбранного приложения ----------

    private fun toApp(ctx: Context, items: List<File>, p: Preset, sub: String, overwrite: Boolean): Result {
        if (p.destPackage.isEmpty()) return Result(false, "Не выбрано приложение")
        val base = if (p.destKind == "appobb") "Android/obb" else "Android/data"
        val rel = "$base/${p.destPackage}" + if (sub.isNotEmpty()) "/$sub" else ""
        val dest = File(root(), rel)
        return privileged(ctx, items, dest, overwrite) ?: Result(false, failMessage(rel))
    }

    /** Прямая запись → Shizuku → root → SAF-доступ. Возвращает null, если ничего не сработало. */
    private fun privileged(ctx: Context, items: List<File>, dest: File, overwrite: Boolean): Result? {
        tryDirect(items, dest, overwrite)?.let { return it }
        tryShizuku(ctx, items, dest, overwrite)?.let { return it }
        trySu(items, dest, overwrite)?.let { return it }
        val r = root()
        if (dest.path.startsWith("$r/")) {
            trySaf(ctx, items, dest.path.removePrefix("$r/"), overwrite)?.let { return it }
        }
        return null
    }

    private fun tryDirect(items: List<File>, dest: File, overwrite: Boolean): Result? {
        return try {
            if (!dest.isDirectory && !dest.mkdirs()) return null
            if (!dest.canWrite()) return null
            val st = Stat()
            items.forEach { copyToFile(it, File(dest, it.name), overwrite, st) }
            finish(st, "напрямую")
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToFile(src: File, dst: File, overwrite: Boolean, st: Stat) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyToFile(it, File(dst, it.name), overwrite, st) }
            return
        }
        if (dst.exists() && !overwrite) {
            st.skipped++
            return
        }
        src.copyTo(dst, overwrite = true)
        if (dst.length() != src.length()) st.errors.add("${src.name}: размер не совпал") else st.ok++
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    // ---------- Shizuku ----------

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun shizukuShell(cmd: String): Process? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    } catch (_: Throwable) {
        null
    }

    private fun tryShizuku(ctx: Context, items: List<File>, dest: File, overwrite: Boolean): Result? {
        if (!shizukuReady()) return null
        return try {
            // Shizuku работает от имени shell и не видит внутреннюю память приложения,
            // поэтому сначала кладём файлы в общую папку приложения на карте памяти.
            val stage = File(ctx.getExternalFilesDir(null) ?: return null, "stage")
            stage.deleteRecursively()
            stage.mkdirs()
            items.forEach { it.copyRecursively(File(stage, it.name), overwrite = true) }
            val flag = if (overwrite) "-rf" else "-rn"
            val cmd = "mkdir -p ${q(dest.path)} && cp $flag ${q(stage.path + "/.")} ${q(dest.path + "/")}"
            val proc = shizukuShell(cmd) ?: return null
            val code = proc.waitFor()
            stage.deleteRecursively()
            if (code == 0) Result(true, "Готово (через Shizuku): файлов/папок ${items.size}") else null
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- root ----------

    private fun trySu(items: List<File>, dest: File, overwrite: Boolean): Result? {
        return try {
            val flag = if (overwrite) "-rf" else "-rn"
            val cmd = buildString {
                append("mkdir -p ${q(dest.path)}")
                items.forEach { append(" && cp $flag ${q(it.path)} ${q(dest.path + "/")}") }
            }
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            if (!proc.waitFor(20, TimeUnit.SECONDS)) {
                proc.destroy()
                return null
            }
            if (proc.exitValue() == 0) Result(true, "Готово (через root): файлов/папок ${items.size}") else null
        } catch (_: Exception) {
            null
        }
    }

    // ---------- сохранённый SAF-доступ к Android/data ----------

    private fun trySaf(ctx: Context, items: List<File>, rel: String, overwrite: Boolean): Result? {
        val grants = ctx.getSharedPreferences("grants", Context.MODE_PRIVATE)
            .getStringSet("uris", emptySet()) ?: emptySet()
        for (g in grants) {
            val uri = Uri.parse(g)
            val docId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) {
                continue
            }
            val prefix = docId.substringAfter(':', "")
            val matches = prefix.isEmpty() || rel == prefix || rel.startsWith("$prefix/")
            if (!matches) continue
            val rootDoc = DocumentFile.fromTreeUri(ctx, uri) ?: continue
            val rest = rel.removePrefix(prefix).split('/').filter { it.isNotEmpty() }
            val dir = ensureDirs(rootDoc, rest) ?: continue
            if (!dir.canWrite()) continue
            val st = Stat()
            items.forEach { copyToDoc(ctx, it, dir, overwrite, st) }
            return finish(st, "через доступ к папке")
        }
        return null
    }
}
