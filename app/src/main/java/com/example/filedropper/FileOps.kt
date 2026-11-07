package com.example.filedropper

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.ZipInputStream

object FileOps {

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        while (f.exists()) {
            val base = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }

    /**
     * Копирует выбранный файл во временную папку.
     * Если это ZIP — открывает его и берёт только самую первую папку внутри.
     * Если в ZIP нет папок — распаковывает все файлы.
     */
    fun copyUri(ctx: Context, uri: Uri, dir: File): File? {
        val name = displayName(ctx, uri).replace('/', '_')
        val isZip = name.endsWith(".zip", ignoreCase = true) ||
            ctx.contentResolver.getType(uri) == "application/zip"
        if (isZip) return extractFirstFolder(ctx, uri, dir)

        val target = uniqueFile(dir, name)
        ctx.contentResolver.openInputStream(uri)?.use { i ->
            target.outputStream().use { o -> i.copyTo(o) }
        } ?: return null
        return target
    }

    private fun cleanZipName(raw: String) = raw.replace('\\', '/').trimStart('/')

    private fun extractFirstFolder(ctx: Context, uri: Uri, dir: File): File? {
        // проход 1: найти первую папку
        var found: String? = null
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = cleanZipName(e.name)
                    if (n.startsWith("__MACOSX")) continue
                    val i = n.indexOf('/')
                    if (i > 0) {
                        found = n.substring(0, i)
                        break
                    }
                }
            }
        } ?: return null
        val folder = found

        val outRoot = if (folder != null) uniqueFile(dir, folder) else dir
        outRoot.mkdirs()
        val canonRoot = outRoot.canonicalPath + File.separator

        // проход 2: распаковать
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = cleanZipName(e.name)
                    if (n.startsWith("__MACOSX")) continue
                    val rel = if (folder != null) {
                        if (!n.startsWith("$folder/")) continue
                        n.removePrefix("$folder/")
                    } else n
                    if (rel.isEmpty()) continue
                    val f = File(outRoot, rel)
                    // защита от выхода за пределы папки
                    if (!(f.canonicalPath + (if (e.isDirectory) File.separator else "")).startsWith(canonRoot)) continue
                    if (e.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { o -> z.copyTo(o) }
                    }
                }
            }
        }
        return outRoot
    }

    fun copyTree(ctx: Context, treeUri: Uri, dir: File) {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return
        copyDoc(ctx, root, uniqueFile(dir, (root.name ?: "folder").replace('/', '_')))
    }

    private fun copyDoc(ctx: Context, doc: DocumentFile, target: File) {
        if (doc.isDirectory) {
            target.mkdirs()
            doc.listFiles().forEach { copyDoc(ctx, it, File(target, (it.name ?: "file").replace('/', '_'))) }
        } else {
            ctx.contentResolver.openInputStream(doc.uri)?.use { i ->
                target.outputStream().use { o -> i.copyTo(o) }
            }
        }
    }

    fun clear(dir: File) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun copyDir(from: File, to: File) {
        to.mkdirs()
        from.listFiles()?.forEach { it.copyRecursively(File(to, it.name), overwrite = true) }
    }

    fun countFiles(f: File): Int =
        if (f.isDirectory) (f.listFiles()?.sumOf { countFiles(it) } ?: 0) else 1
}
