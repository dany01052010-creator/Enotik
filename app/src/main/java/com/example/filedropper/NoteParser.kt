package com.example.filedropper

/**
 * Мгновенно читает текст "когда ставить" и достаёт из него настройки.
 * Это быстрый локальный разбор по ключевым словам (без интернета), а не нейросеть.
 *
 * Понимает, например:
 *   "при запуске игры заменить файл, в папку files/mods"
 *   "не заменять, если файл уже есть"
 *   "put into folder data/config, overwrite"
 */
object NoteParser {

    data class Opts(val overwrite: Boolean, val subPath: String?)

    private val keepWords = listOf(
        "не заменя", "не перезапис", "не трогать", "пропусти", "если файла нет", "если нет файла",
        "do not overwrite", "don't overwrite", "skip existing", "only if missing", "keep existing"
    )

    private val folderRegex = Regex(
        """(?:в папку|в папке|в директорию|into folder|to folder|in folder|into|to folder)\s+["«'“]?([\p{L}\p{N}_\-./ ]+?)["»'”]?\s*(?:[,.;\n]|$)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(note: String): Opts {
        val low = note.lowercase()
        val keep = keepWords.any { low.contains(it) }
        val sub = folderRegex.find(note)?.groupValues?.get(1)?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        return Opts(overwrite = !keep, subPath = sub)
    }
}
