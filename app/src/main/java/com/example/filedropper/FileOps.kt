package com.example.filedropper

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File

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

    fun copyUri(ctx: Context, uri: Uri, dir: File): File? {
        val target = uniqueFile(dir, displayName(ctx, uri).replace('/', '_'))
        ctx.contentResolver.openInputStream(uri)?.use { i ->
            target.outputStream().use { o -> i.copyTo(o) }
        } ?: return null
        return target
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
