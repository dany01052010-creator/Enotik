package com.modhelper.app

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Ai {
    class AiError(msg: String) : Exception(msg)

    class Msg(val role: String, val text: String, val images: List<Bitmap> = emptyList())

    data class Found(val nick: String, val rule: String, val reason: String, val punishment: String)

    private const val API_URL = "https://api.anthropic.com/v1/messages"

    private const val BASE_PROMPT = """Ты — помощник модератора онлайн-игры. Отвечай по-русски, коротко и по делу.

Твоя задача: по скриншотам, тексту жалобы и (если есть) распознанной речи найти игроков-нарушителей, определить пункт правил, который нарушен, и предложить наказание СТРОГО по таблице наказаний из правил ниже.

Важные принципы:
- Ник читай внимательно, с точным регистром и символами. Если ник не виден или неразборчив — пиши "?" и попроси уточнить.
- Называй только те нарушения, которые реально видны или слышны в материалах. Ничего не придумывай.
- Если подходящего пункта в правилах нет — так и скажи, не выдумывай пункт.
- Срок наказания бери из правил. Если срок зависит от повторности — укажи минимальный и напиши об этом в причине.
- Окончательное решение принимает модератор-человек; ты только подсказываешь.

Формат ответа: 1–4 коротких предложения для модератора. Если нашёл нарушения, в конце добавь блок JSON строго такого вида (и только его, без комментариев внутри):

```json
{"violations": [{"nick": "Ник", "rule": "3.2", "reason": "кратко: что сделал", "punishment": "мут 30 минут"}]}
```

Если нарушений нет или данных не хватает — JSON-блок не добавляй."""

    private fun systemPrompt(): String {
        val rules = Hub.s("rules").trim()
        val tpl = Hub.s("template").trim()
        val sb = StringBuilder(BASE_PROMPT)
        sb.append("\n\nПРАВИЛА ИГРЫ И НАКАЗАНИЯ:\n")
        sb.append(
            if (rules.isEmpty()) "(правила ещё не добавлены — скажи модератору, что их нужно вставить во вкладке «Правила»)"
            else rules
        )
        if (tpl.isNotEmpty()) {
            sb.append("\n\nКАК МОДЕРАТОР ОФОРМЛЯЕТ ЖАЛОБЫ (образец, следуй ему в поле reason):\n").append(tpl)
        }
        return sb.toString()
    }

    private fun encode(src: Bitmap, maxSide: Int = 1568): String {
        val s = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height).toFloat())
        val b = if (s < 1f) {
            Bitmap.createScaledBitmap(src, maxOf(1, (src.width * s).toInt()), maxOf(1, (src.height * s).toInt()), true)
        } else src
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun textBlock(t: String): JSONObject = JSONObject().put("type", "text").put("text", t)

    private fun imageBlock(b: Bitmap): JSONObject = JSONObject().put("type", "image").put(
        "source",
        JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", encode(b))
    )

    private fun toMessages(history: List<Msg>, transcript: String): JSONArray {
        val hist = if (history.size > 30) history.subList(history.size - 30, history.size) else history
        val withImgs = hist.indices.filter { hist[it].images.isNotEmpty() }
        val keep = withImgs.takeLast(4).toSet()
        val roles = ArrayList<String>()
        val blocks = ArrayList<JSONArray>()
        for ((i, m) in hist.withIndex()) {
            val arr = JSONArray()
            if (m.role == "user" && i in keep) {
                for ((n, img) in m.images.withIndex()) {
                    if (m.images.size > 1) arr.put(textBlock("Скриншот " + (n + 1) + ":"))
                    arr.put(imageBlock(img))
                }
            }
            var text = m.text
            if (m.images.isNotEmpty() && i !in keep) {
                text += "\n[к сообщению были приложены скриншоты, их уже нет в контексте]"
            }
            if (text.isNotBlank()) arr.put(textBlock(text))
            if (arr.length() == 0) continue
            if (roles.isNotEmpty() && roles[roles.size - 1] == m.role) {
                val prev = blocks[blocks.size - 1]
                for (k in 0 until arr.length()) prev.put(arr.get(k))
            } else {
                roles.add(m.role)
                blocks.add(arr)
            }
        }
        while (roles.isNotEmpty() && roles[0] != "user") {
            roles.removeAt(0)
            blocks.removeAt(0)
        }
        if (transcript.isNotBlank() && roles.isNotEmpty() && roles[roles.size - 1] == "user") {
            blocks[blocks.size - 1].put(
                textBlock(
                    "Недавняя речь из голосового чата (распознана автоматически, может содержать ошибки, " +
                        "кто именно говорит — неизвестно):\n" + transcript
                )
            )
        }
        val out = JSONArray()
        for (i in roles.indices) out.put(JSONObject().put("role", roles[i]).put("content", blocks[i]))
        return out
    }

    private fun errorText(code: Int, body: String): String {
        var msg = ""
        try {
            msg = JSONObject(body).optJSONObject("error")?.optString("message") ?: ""
        } catch (e: Exception) {
            msg = body.take(200)
        }
        return when {
            code == 401 || code == 403 -> "API-ключ не подошёл (ошибка $code). Проверь ключ во вкладке «Настройки»."
            code == 429 -> "Слишком много запросов или закончился лимит. Подожди немного. $msg"
            code >= 500 -> "Сервер Claude сейчас перегружен ($code). Попробуй ещё раз."
            else -> "Ошибка API $code: $msg"
        }
    }

    private fun ask(system: String, messages: JSONArray, maxTokens: Int): String {
        val key = Hub.s("api_key").trim()
        if (key.isEmpty()) throw AiError("Не указан API-ключ. Открой вкладку «Настройки» и вставь ключ Anthropic.")
        val model = Hub.s("model", "claude-sonnet-5-5").ifBlank { "claude-sonnet-5-5" }
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", messages)
        if (system.isNotEmpty()) body.put("system", system)
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code != 200) throw AiError(errorText(code, txt))
            val content = JSONObject(txt).optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                if (b.optString("type") == "text") sb.append(b.optString("text"))
            }
            sb.toString().trim()
        } catch (e: IOException) {
            throw AiError("Нет связи с сервером Claude: " + e.message)
        } catch (e: JSONException) {
            throw AiError("Сервер вернул непонятный ответ.")
        } finally {
            conn.disconnect()
        }
    }

    fun chat(history: List<Msg>, transcript: String): String =
        ask(systemPrompt(), toMessages(history, transcript), 1500)

    fun transcribeRules(img: Bitmap): String {
        val content = JSONArray().put(imageBlock(img)).put(
            textBlock(
                "Перепиши весь текст со скриншота дословно, сохраняя нумерацию пунктов и " +
                    "сроки наказаний. Без комментариев, только текст."
            )
        )
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        return ask("Ты точно переписываешь текст со скриншотов.", msgs, 4000)
    }

    fun ping(): String {
        val msgs = JSONArray().put(
            JSONObject().put("role", "user").put("content", JSONArray().put(textBlock("Ответь одним словом: ок")))
        )
        return ask("", msgs, 20)
    }

    // ---------- разбор ответа ----------
    private val FENCE = Regex("```(?:json)?\\s*(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val BARE = Regex("\\{\\s*\"violations\"\\s*:")

    /** Первый объект {...} в строке, начиная с позиции from: (текст, индекс после него). */
    private fun firstObject(s: String, from: Int = 0): Pair<String, Int>? {
        val start = s.indexOf('{', from)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) {
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == '"') {
                    inStr = false
                }
            } else {
                if (c == '"') {
                    inStr = true
                } else if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) return Pair(s.substring(start, i + 1), i + 1)
                }
            }
        }
        return null
    }

    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key).trim()

    fun extractViolations(text: String): List<Found> {
        val cands = ArrayList<String>()
        for (m in FENCE.findAll(text)) cands.add(m.groupValues[1])
        if (cands.isEmpty()) {
            val m = BARE.find(text)
            if (m != null) cands.add(text.substring(m.range.first))
        }
        val out = ArrayList<Found>()
        for (c in cands) {
            val objText = firstObject(c)?.first ?: continue
            val arr: JSONArray = try {
                JSONObject(objText).optJSONArray("violations")
            } catch (e: Exception) {
                null
            } ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val f = Found(str(o, "nick"), str(o, "rule"), str(o, "reason"), str(o, "punishment"))
                if (f.nick.isNotEmpty() || f.reason.isNotEmpty()) out.add(f)
            }
        }
        return out
    }

    /** Убирает JSON-блок из ответа, чтобы не показывать его в чате. */
    fun stripJson(text: String): String {
        var t = FENCE.replace(text) { m -> if (m.groupValues[1].contains("\"violations\"")) "" else m.value }
        val b = BARE.find(t)
        if (b != null) {
            val r = firstObject(t, b.range.first)
            if (r != null) t = t.substring(0, b.range.first) + t.substring(r.second)
        }
        return t.trim()
    }
}
