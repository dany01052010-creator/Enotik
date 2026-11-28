package com.modhelper.app

/** Один пункт правил, разобранный из текста. */
data class Rule(
    val number: String,
    val title: String,
    val text: String,
    val punishment: String,
    val words: List<String>,
    val groups: Set<String>
)

data class Suggestion(val rule: Rule, val score: Double, val matched: List<String>)

/**
 * Свой «движок» без интернета и без ИИ:
 *  1) разбирает текст правил на пункты и достаёт срок наказания;
 *  2) по описанию нарушения (твой текст + текст со скриншота + речь) подбирает пункты:
 *     совпадение слов (редкие слова весят больше), группы синонимов (мат = оскорбления, чит = хак...) и номер пункта;
 *  3) из распознанного текста вытаскивает возможные ники.
 * Без android-классов, поэтому проверяется обычными тестами.
 */
object RuleBook {
    private class Group(val id: String, val label: String, val exact: Set<String>, val prefixes: List<String>)

    private val GROUPS = listOf(
        Group(
            "insult", "оскорбления",
            setOf("мат", "мата", "матом", "маты", "мате", "хам", "тупой", "дебил", "идиот"),
            listOf("оскорб", "нецензур", "обзыв", "матер", "матюк", "хамств", "хамит", "токсич", "унижа", "унизи", "руган", "ругает", "ругал")
        ),
        Group(
            "cheat", "читы",
            setOf("аим", "вх", "wh", "esp", "бот", "боты"),
            listOf("чит", "хак", "cheat", "hack", "aimbot", "wallhack", "макрос", "макро", "автокликер", "speedhack", "спидхак")
        ),
        Group(
            "spam", "спам и флуд",
            setOf("оффтоп", "флуд", "капс", "caps"),
            listOf("спам", "флуд", "flood", "spam", "дублир")
        ),
        Group(
            "ads", "реклама",
            setOf("ип", "ip", "пиар", "дискорд", "discord", "телеграм", "telegram", "промокод"),
            listOf("реклам", "пиар", "ссылк", "invite")
        ),
        Group(
            "bug", "баги",
            setOf("баг", "баги", "дюп", "dupe", "глитч", "glitch"),
            listOf("багоюз", "эксплойт", "exploit", "уязвим")
        ),
        Group(
            "fraud", "обман",
            setOf("скам", "scam", "развод", "фишинг"),
            listOf("обман", "кидал", "кидок", "мошенн", "украл", "кража", "воровс", "воруе")
        ),
        Group(
            "threat", "угрозы",
            setOf("ддос", "ddos", "doxx", "деанон", "шантаж"),
            listOf("угроз", "угрож", "убью", "запуг")
        ),
        Group(
            "grief", "помехи игрокам",
            setOf("дм", "dm", "тк", "rdm", "vdm", "kos", "мешает", "гриф", "grief"),
            listOf("убийств", "убива", "тимкилл", "teamkill", "мешат", "помех", "пакост")
        ),
        Group(
            "hate", "дискриминация",
            emptySet(),
            listOf("расизм", "расист", "национал", "нацист", "дискримин", "гомофоб", "ксенофоб")
        ),
        Group(
            "nick", "ник",
            setOf("ник", "ника", "нике", "нику", "никнейм", "никнейма", "nick"),
            listOf("никнейм", "nick")
        ),
        Group(
            "afk", "афк",
            setOf("афк", "afk"),
            listOf("бездейств")
        ),
        Group(
            "fake", "выдача себя за администрацию",
            setOf("стафф", "staff"),
            listOf("админ", "модер", "притворя", "выдава", "представля")
        ),
        Group(
            "trade", "продажа и обмен",
            setOf("trade", "донат"),
            listOf("прода", "купл", "обмен", "торгов")
        ),
        Group(
            "voice", "голосовой чат",
            setOf("ор", "войс", "voice", "крик", "шум", "музыка", "саундпад", "soundpad"),
            listOf("микрофон", "голос", "орать", "музык")
        )
    )

    private val STOP = setOf(
        "запрещено", "запрещается", "запрещен", "запрещена", "запрещены", "запрещается", "нельзя", "разрешено",
        "игрок", "игрока", "игроки", "игроков", "игроку", "игроком", "правила", "правило", "пункт", "нарушение", "нарушения",
        "наказание", "наказания", "мут", "мута", "бан", "бана", "варн", "кик", "минут", "минуты", "минута", "мин", "час", "часа",
        "часов", "день", "дня", "дней", "сутки", "суток", "раз", "при", "для", "или", "если", "это", "что", "как", "так", "все",
        "всех", "его", "она", "они", "быть", "может", "можно", "без", "над", "под", "про", "этот", "также", "только", "после",
        "перед", "между", "чтобы", "когда", "где", "который", "которые", "другой", "других", "другие", "любой", "любые",
        "навсегда", "повтор", "повторе", "повторное", "перманентный", "перманентно", "общие", "тоже", "было", "были", "есть",
        "уже", "еще", "ещё", "нет", "они", "наш", "ваш", "тот", "той", "эти", "этих", "своего", "свои", "свой", "себя"
    )

    private val WORD = Regex("[A-Za-zА-Яа-яЁё]+")
    private val NUMBER = Regex("\\d{1,3}(?:\\.\\d{1,3}){0,3}")
    private val RULE_LINE = Regex(
        "^\\s*(?:п(?:ункт)?\\.?\\s*)?(\\d{1,3}(?:\\.\\d{1,3}){0,3})\\s*[.):\\-–—]?\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val UNITS = listOf("мин", "час", "сут", "дн", "ден", "нед", "мес", "сек", "раз", "год", "руб", "%", "шт")
    private val PUN_STEMS = listOf(
        "перманент", "мут", "бан", "варн", "кик", "блокиров", "предупрежд", "jail", "тюрьм", "деморган",
        "warn", "mute", "ban", "kick", "штраф", "отстранен", "дисквалиф"
    )
    private val DURATION = Regex("\\d+\\s*(?:мин|час|дн|ден|сут|нед|мес)[а-яё]*", RegexOption.IGNORE_CASE)
    private val CHAT_PREFIX = Regex(
        "^(?:\\[\\d{1,2}:\\d{2}(?::\\d{2})?\\]\\s*)?\\[?([A-Za-zА-Яа-яЁё0-9_.\\-]{3,20})\\]?\\s*[:>»]"
    )
    private val NICK_TOKEN = Regex("[A-Za-z0-9_]{3,20}")

    fun norm(w: String): String = w.lowercase().replace('ё', 'е')

    private fun groupsOf(w: String): List<String> =
        GROUPS.filter { g -> w in g.exact || g.prefixes.any { p -> w.startsWith(p) } }.map { it.id }

    private fun labelOf(id: String): String? = GROUPS.firstOrNull { it.id == id }?.label

    fun tokens(text: String): List<String> =
        WORD.findAll(text).map { norm(it.value) }
            .filter { (it.length >= 3 || groupsOf(it).isNotEmpty()) && it !in STOP }
            .distinct().toList()

    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val m = minOf(a.length, b.length)
        if (m < 4) return false
        var cp = 0
        while (cp < m && a[cp] == b[cp]) cp++
        return cp >= minOf(5, m - 1)
    }

    // ------------------------------------------------------------ разбор правил
    private fun isContinuation(rest: String): Boolean {
        val r = norm(rest.trim())
        return UNITS.any { r.startsWith(it) }
    }

    fun extractPunishment(text: String): String {
        val low = text.lowercase()
        var best = -1
        for (stem in PUN_STEMS) {
            var from = 0
            while (true) {
                val i = low.indexOf(stem, from)
                if (i < 0) break
                val okStart = i == 0 || !low[i - 1].isLetter()
                if (okStart) {
                    if (best < 0 || i < best) best = i
                    break
                }
                from = i + 1
            }
        }
        if (best >= 0) return cutPunishment(text, best)
        val m = DURATION.find(text)
        if (m != null) return cutPunishment(text, m.range.first)
        return ""
    }

    private fun cutPunishment(text: String, start: Int): String {
        var end = minOf(text.length, start + 45)
        for (ch in charArrayOf('.', ';', '\n')) {
            val k = text.indexOf(ch, start)
            if (k in 0 until end) end = k
        }
        return text.substring(start, end).trim().trimEnd(',', ':', '-', '—', '–', ' ')
    }

    private fun makeTitle(firstLine: String): String {
        var t = firstLine.trim()
        val cut = Regex("\\s[—–-]\\s|[:;.]").find(t)
        if (cut != null && cut.range.first >= 3) t = t.substring(0, cut.range.first)
        t = t.trim().trimEnd(',', ' ')
        if (t.length > 70) t = t.take(70).trimEnd() + "…"
        return t
    }

    private fun build(number: String, text: String): Rule {
        val words = tokens(text)
        val groups = HashSet<String>()
        for (w in words) groups.addAll(groupsOf(w))
        return Rule(number, makeTitle(text.lines().first()), text, extractPunishment(text), words, groups)
    }

    fun parse(text: String): List<Rule> {
        val raw = ArrayList<Pair<String, StringBuilder>>()
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val m = RULE_LINE.find(l)
            if (m != null && !isContinuation(m.groupValues[2])) {
                raw.add(m.groupValues[1] to StringBuilder(m.groupValues[2].trim()))
            } else if (raw.isNotEmpty()) {
                raw[raw.size - 1].second.append('\n').append(l)
            }
        }
        if (raw.isEmpty()) {
            // правила без нумерации: каждая строка — отдельный пункт
            val out = ArrayList<Rule>()
            for (line in text.lines()) {
                val l = line.trim().trimStart('-', '•', '*', '·', '–', '—', ' ')
                if (l.length >= 4) out.add(build("", l))
            }
            return out
        }
        return raw.map { build(it.first, it.second.toString()) }
    }

    // ------------------------------------------------------------ подбор пункта
    fun suggest(rules: List<Rule>, evidence: String, limit: Int = 3): List<Suggestion> {
        if (rules.isEmpty()) return emptyList()
        val ew = tokens(evidence)
        val eg = HashSet<String>()
        for (w in ew) eg.addAll(groupsOf(w))
        val dfCache = HashMap<String, Int>()
        fun df(w: String): Int = dfCache.getOrPut(w) { maxOf(1, rules.count { r -> r.words.any { same(it, w) } }) }
        val gdf = HashMap<String, Int>()
        for (g in eg) gdf[g] = maxOf(1, rules.count { g in it.groups })
        val nums = NUMBER.findAll(evidence).toList()
        val out = ArrayList<Suggestion>()
        for (r in rules) {
            var score = 0.0
            val matched = ArrayList<String>()
            for (rw in r.words) {
                if (ew.any { same(it, rw) }) {
                    score += 2.0 / df(rw)
                    matched.add(rw)
                }
            }
            for (g in r.groups) {
                if (g in eg) {
                    score += 3.0 / (gdf[g] ?: 1)
                    val lb = labelOf(g)
                    if (lb != null && lb !in matched) matched.add(lb)
                }
            }
            if (r.number.isNotEmpty()) {
                for (m in nums) {
                    if (m.value != r.number) continue
                    val before = evidence.substring(maxOf(0, m.range.first - 8), m.range.first).lowercase().trimEnd()
                    val prefixed = before.endsWith("п.") || before.endsWith("п") || before.endsWith("пункт") ||
                        before.endsWith("пункту") || before.endsWith("пп.")
                    if (r.number.contains('.') || prefixed) {
                        score += 8.0
                        matched.add("п. " + r.number)
                        break
                    }
                }
            }
            if (score > 0) out.add(Suggestion(r, score, matched.take(5)))
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    // ------------------------------------------------------------ ники из распознанного текста
    fun nickCandidates(ocr: String, limit: Int = 8): List<String> {
        val scores = LinkedHashMap<String, Int>()
        val shown = HashMap<String, String>()
        fun add(n: String, s: Int) {
            val k = n.lowercase()
            if (!shown.containsKey(k)) shown[k] = n
            scores[k] = (scores[k] ?: 0) + s
        }
        for (line in ocr.lines()) {
            val m = CHAT_PREFIX.find(line.trim())
            if (m != null) add(m.groupValues[1], 5)
        }
        for (m in NICK_TOKEN.findAll(ocr)) {
            val t = m.value
            if (!t.any { it.isLetter() }) continue
            var s = 1
            if (t.any { it.isDigit() }) s += 1
            if (t.contains('_')) s += 1
            if (t.drop(1).any { it.isUpperCase() }) s += 1
            add(t, s)
        }
        return scores.entries.sortedByDescending { it.value }.take(limit).map { shown[it.key] ?: it.key }
    }
}
