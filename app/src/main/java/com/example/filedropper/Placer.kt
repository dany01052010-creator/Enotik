package com.example.filedropper

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ставит файлы из папки src по назначению кнопки.
 * Каждый файл после записи проверяется по размеру.
 */
object Placer {

    data class Result(val ok: Boolean, val message: String)

    private class Stat {
        var ok = 0
        var skipped = 0
        val errors = mutableListOf<String>()
    }

    fun place(ctx: Context, src: File, p: Preset): Result {
        val opts = NoteParser.parse(p.note)
        val sub = (opts.subPath ?: p.destSub).trim().trim('/')
        val items = src.listFiles()?.sortedBy { it.name }.orEmpty()
        if (items.isEmpty()) return Result(false, "Нет файлов: сначала выбери файл или папку")
        return try {
            if (p.destKind == "folder") toTree(ctx, items, p, sub, opts.overwrite)
            else toApp(ctx, items, p, sub, opts.overwrite)
        } catch (e: Exception) {
            Result(false, "Ошибка: ${e.message}")
        }
    }

    private fun finish(st: Stat, how: String): Result {
        if (st.errors.isNotEmpty()) {
            return Result(false, "Не всё поставилось ($how). Ошибки: " + st.errors.take(3).joinToString("; "))
        }
        val skip = if (st.skipped > 0) ", пропущено уже существующих: ${st.skipped}" else ""
        return Result(true, "Готово ($how): файлов поставлено ${st.ok}$skip")
    }

    // ---------- назначение: обычная папка (SAF) ----------

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
        val dest = File(Environment.getExternalStorageDirectory(), rel)

        tryDirect(items, dest, overwrite)?.let { return it }
        trySu(items, dest, overwrite)?.let { return it }
        trySaf(ctx, items, rel, overwrite)?.let { return it }

        val why = if (Build.VERSION.SDK_INT >= 33)
            "Android 13+ не даёт обычным приложениям писать в Android/data и Android/obb."
        else if (Build.VERSION.SDK_INT >= 30)
            "Android 11–12 закрывает Android/data. Нажми «Доступ к Android/data» и выбери эту папку, либо нужен root."
        else
            "Дай приложению разрешение на файлы (кнопка «Доступ ко всем файлам»)."
        return Result(
            false,
            "Не получилось записать в $rel. $why Можно выбрать назначение «Папка на телефоне»."
        )
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
            val root = DocumentFile.fromTreeUri(ctx, uri) ?: continue
            val rest = rel.removePrefix(prefix).split('/').filter { it.isNotEmpty() }
            val dir = ensureDirs(root, rest) ?: continue
            if (!dir.canWrite()) continue
            val st = Stat()
            items.forEach { copyToDoc(ctx, it, dir, overwrite, st) }
            return finish(st, "через доступ к папке")
        }
        return null
    }
}
