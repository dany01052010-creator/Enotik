package com.enotik.catchgame

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Entry(val name: String, val score: Int, val meters: Int, val date: String)

/** Всё, что сохраняется на устройстве: имя, монеты, рекорд, прогресс заданий, таблица лидеров. */
object Store {
    private var sp: SharedPreferences? = null
    private fun p(): SharedPreferences = sp!!

    fun init(c: Context) {
        if (sp == null) sp = c.applicationContext.getSharedPreferences("runner", Context.MODE_PRIVATE)
    }

    fun getInt(k: String, d: Int = 0) = p().getInt(k, d)
    fun putInt(k: String, v: Int) = p().edit().putInt(k, v).apply()

    var name: String
        get() = p().getString("name", "") ?: ""
        set(v) = p().edit().putString("name", v).apply()

    var coins: Int
        get() = getInt("coins")
        set(v) = putInt("coins", v)

    /** Качество графики: 0 низкое, 1 среднее, 2 высокое (шейдеры). По умолчанию зависит от Android. */
    var quality: Int
        get() = getInt("quality", if (Build.VERSION.SDK_INT >= 33) 2 else 1).coerceIn(0, 2)
        set(v) = putInt("quality", v)

    val best: Int get() = leaders().maxOfOrNull { it.score } ?: 0

    fun leaders(): List<Entry> {
        val out = ArrayList<Entry>()
        try {
            val arr = JSONArray(p().getString("leaders", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.getString("n"), o.getInt("s"), o.getInt("m"), o.optString("d")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun addScore(score: Int, meters: Int) {
        if (score <= 0) return
        val list = ArrayList(leaders())
        val who = name.ifBlank { "Енот" }
        list.add(Entry(who, score, meters, SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date())))
        list.sortByDescending { it.score }
        val top = list.take(10)
        val arr = JSONArray()
        top.forEach {
            arr.put(JSONObject().put("n", it.name).put("s", it.score).put("m", it.meters).put("d", it.date))
        }
        p().edit().putString("leaders", arr.toString()).apply()
    }
}
