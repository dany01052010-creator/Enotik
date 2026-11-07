package com.example.filedropper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Одна "кнопка": что ставить (файлы лежат в store/<id>), куда ставить,
 * и текст "когда ставить" (его читает NoteParser).
 */
data class Preset(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var note: String = "",
    /** "folder" | "appdata" | "appobb" */
    var destKind: String = "folder",
    var destTreeUri: String = "",
    var destPackage: String = "",
    var destSub: String = "",
    var triggerPackage: String = "",
    var auto: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("note", note)
        put("destKind", destKind); put("destTreeUri", destTreeUri)
        put("destPackage", destPackage); put("destSub", destSub)
        put("triggerPackage", triggerPackage); put("auto", auto)
    }

    companion object {
        fun fromJson(o: JSONObject) = Preset(
            id = o.getString("id"),
            name = o.optString("name"),
            note = o.optString("note"),
            destKind = o.optString("destKind", "folder"),
            destTreeUri = o.optString("destTreeUri"),
            destPackage = o.optString("destPackage"),
            destSub = o.optString("destSub"),
            triggerPackage = o.optString("triggerPackage"),
            auto = o.optBoolean("auto", false)
        )
    }
}

class Store(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("presets", Context.MODE_PRIVATE)

    fun all(): MutableList<Preset> {
        val out = mutableListOf<Preset>()
        try {
            val arr = JSONArray(sp.getString("list", "[]"))
            for (i in 0 until arr.length()) out.add(Preset.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
        return out
    }

    fun save(list: List<Preset>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("list", arr.toString()).apply()
    }

    fun upsert(p: Preset) {
        val l = all()
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) l[i] = p else l.add(p)
        save(l)
    }

    fun delete(p: Preset) {
        File(ctx.filesDir, "store/${p.id}").deleteRecursively()
        save(all().filter { it.id != p.id })
    }

    fun dirFor(id: String): File = File(ctx.filesDir, "store/$id").apply { mkdirs() }

    /** Выбранные (но ещё не записанные) файлы. */
    fun pendingDir(): File = File(ctx.filesDir, "pending").apply { mkdirs() }
}
