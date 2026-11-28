package com.modhelper.app

/**
 * Свой «мозг» приложения — работает полностью на телефоне, без интернета и без ключей.
 *
 * Схема:
 *  1. Правила разбираются на пункты: номер, текст, срок наказания (мут 30 минут и т.п.),
 *     слова в «кавычках» считаются запрещёнными словами этого пункта.
 *  2. Текст (со скриншота, из чата, из речи) режется на сообщения, у каждого ищется ник.
 *  3. Каждое сообщение проверяется по словарю признаков (мат, оскорбления, реклама, угрозы,
 *     спам, капс и т.д.) и по запрещённым словам из правил.
 *  4. Для найденной категории подбирается самый подходящий пункт ваших правил, из него
 *     берётся срок наказания.
 */
object Engine {
    data class Found(val nick: String, val rule: String, val reason: String, val punishment: String, val pid: String = "")

    class Rule(
        val number: String,
        val text: String,
        val heading: String,
        val punishment: String,
        val quoted: List<String>
    ) {
        val words: List<String> = Engine.words("$heading $text")
        val norm: String = words.joinToString(" ")
    }

    class Msg(val nick: String, val text: String, val pid: String = "")

    class Result(val found: List<Found>, val report: String, val checked: Int)

    private class Cat(
        val id: String,
        val title: String,
        val hints: List<String>,
        val prefixes: List<String> = emptyList(),
        val exact: Set<String> = emptySet(),
        val contains: List<String> = emptyList(),
        val phrases: List<String> = emptyList(),
        val fallback: String? = null
    )

    // ------------------------------------------------------------------ нормализация
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val CYR = Regex("[а-я]")
    private val REPEAT = Regex("(.)\\1{2,}")
    private val LAT2CYR = mapOf(
        'a' to 'а', 'e' to 'е', 'o' to 'о', 'p' to 'р', 'c' to 'с', 'x' to 'х',
        'y' to 'у', 'k' to 'к', 'm' to 'м', 't' to 'т', 'b' to 'в', 'h' to 'н'
    )

    fun words(s: String): List<String> {
        val raw = WORD.findAll(s.lowercase().replace('ё', 'е')).map { it.value }.toList()
        val fixed = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            // «х у й» — склеиваем одиночные буквы подряд
            if (raw[i].length == 1 && raw[i][0].isLetter()) {
                var j = i
                val sb = StringBuilder()
                while (j < raw.size && raw[j].length == 1 && raw[j][0].isLetter()) {
                    sb.append(raw[j])
                    j++
                }
                if (j - i >= 3) {
                    fixed.add(sb.toString())
                    i = j
                    continue
                }
            }
            fixed.add(raw[i])
            i++
        }
        return fixed.map { fixWord(it) }
    }

    private fun fixWord(w: String): String {
        var x = w
        if (CYR.containsMatchIn(x)) x = x.map { LAT2CYR[it] ?: it }.joinToString("")
        return REPEAT.replace(x, "\$1")
    }

    // ------------------------------------------------------------------ словарь признаков
    private val CATS: List<Cat> = listOf(
        Cat(
            "mat", "Нецензурная лексика",
            hints = listOf(
                "мат=", "маты=", "матом", "нецензур", "ругательств", "бранн", "брань",
                "сквернослов", "грубая лексик", "обсценн"
            ),
            contains = listOf(
                "хуй", "хуя", "хуе", "пизд", "пезд", "ебан", "ебат", "ебал", "ебуч", "ебну", "еблан", "ебаш",
                "бляд", "блят", "долбоеб", "ублюд", "выбляд", "залуп", "мудак", "мудил", "гандон", "гондон",
                "шлюх", "шалав", "пидор", "пидар", "дроч", "fuck", "shit", "bitch", "asshole", "cunt"
            ),
            prefixes = listOf("сука", "суки", "сучк", "сучар", "сукин", "говн", "дерьм", "жоп", "нахер", "похер"),
            exact = setOf("бля", "блять", "хер", "хера", "пох", "нах", "хуй", "fck", "сука", "педик", "педики", "педиков"),
            fallback = "insult"
        ),
        Cat(
            "insult", "Оскорбление",
            hints = listOf(
                "оскорбл", "унижен", "токсичн", "хамств", "грубост", "неуважен", "неуважит", "провокац",
                "буллинг", "травл"
            ),
            prefixes = listOf(
                "идиот", "дебил", "тупой", "тупая", "тупое", "тупые", "тупиц", "тупорыл", "кретин", "урод",
                "придур", "мраз", "сволоч", "ничтож", "чмошн", "даун", "дурак", "дура", "дурил", "гнид", "гнус",
                "быдл", "дегенер", "отброс", "убог", "шизо", "ушлеп", "лошар", "лузер", "криворук", "рукожоп",
                "сосун", "сосал", "тварь", "твари", "отстой", "имбецил", "олигофрен", "неудачник", "слабак",
                "idiot", "moron", "stupid", "retard"
            ),
            exact = setOf("лох", "лохи", "лохов", "чмо", "скот", "скотина", "псих"),
            phrases = listOf("мать твою", "твою мать", "мамку", "мамаш", "мама твоя", "маму твою", "мамы твоей")
        ),
        Cat(
            "discr", "Дискриминация, разжигание вражды",
            hints = listOf(
                "национал", "расов", "расизм", "дискриминац", "ксенофоб", "разжиган", "нацизм", "экстремиз",
                "фашизм", "религиоз", "межнац"
            ),
            prefixes = listOf(
                "нигг", "негр", "чурк", "чурб", "хохол", "хохл", "москал", "кацап", "узкогл", "пиндос",
                "нацист", "фашист", "гитлер", "холокост", "нацизм", "расизм"
            ),
            exact = setOf("хач", "хачи", "жид", "жиды", "жидов", "жидок", "нигер", "нигеры", "нигера", "нигеров"),
            fallback = "insult"
        ),
        Cat(
            "threat", "Угрозы",
            hints = listOf("угроз", "деанон", "ддос", "ddos", "шантаж", "запугив", "сватинг", "вымогат", "личн"),
            phrases = listOf(
                "убью", "убьем", "убьют", "убить тебя", "прибью", "замочу", "зарежу", "сломаю тебе", "найду тебя",
                "найду и", "знаю где ты живеш", "твой адрес", "твой ip", "твой айпи", "изнасил", "сдохни", "сдохн",
                "взломаю", "ддос", "ddos", "дидос", "деанон", "выслежу", "подожгу"
            ),
            fallback = "insult"
        ),
        Cat(
            "ads", "Реклама",
            hints = listOf("реклам", "сторонн", "ссылк", "пиар", "раскрутк", "привлечен", "приглаш"),
            phrases = listOf(
                "заходи на", "заходите на", "заходи ко мне", "заходите ко мне", "приходи на", "приходите на",
                "лучший сервер", "топ сервер", "мой сервер", "наш сервер", "мой канал", "наш канал", "подпишись",
                "подписывайтесь", "подпишитесь", "подписывайся", "мой стрим", "мой дискорд", "наш дискорд",
                "мой дс", "мой тг", "наш тг", "канал в тг"
            )
        ),
        Cat(
            "spam", "Спам / флуд",
            hints = listOf("спам", "флуд", "засорен", "однотипн", "бессмысленн")
        ),
        Cat(
            "caps", "Капс (крик заглавными)",
            hints = listOf("капс", "caps", "заглавн", "верхн регистр", "большими букв"),
            fallback = "spam"
        ),
        Cat(
            "beg", "Попрошайничество",
            hints = listOf("попрошай", "клянч", "выпрашив", "просьб", "просить"),
            phrases = listOf(
                "дай админ", "дайте админ", "дай модер", "выдай админ", "выдайте админ", "дай вип", "дайте вип",
                "дай донат", "дайте донат", "дай деньги", "дайте деньги", "подкиньте", "подкинь денег", "кто даст"
            )
        ),
        Cat(
            "trade", "Продажа / покупка аккаунтов и ценностей",
            hints = listOf("продаж", "торговл", "аккаунт", "обмен", "реальн", "купл"),
            phrases = listOf(
                "продам акк", "продаю акк", "куплю акк", "покупаю акк", "продам аккаунт", "продаю аккаунт",
                "куплю аккаунт", "продам донат", "продаю донат", "дешевый донат", "за реальные деньги"
            )
        ),
        Cat(
            "cheat", "Читы / запрещённое ПО",
            hints = listOf("чит", "хак", "софт", "сторонн програм", "модификац", "макрос", "автокликер"),
            prefixes = listOf("аимбот", "валлхак", "спидхак", "триггербот"),
            contains = listOf("aimbot", "wallhack", "speedhack", "cheat"),
            phrases = listOf(
                "я с чит", "у меня чит", "я на чит", "использую чит", "продам чит", "продаю чит", "куплю чит",
                "купить чит", "скачай чит", "скачать чит", "чит на", "читы на", "купи чит"
            )
        ),
        Cat(
            "bug", "Использование багов",
            hints = listOf("баг", "дюп", "эксплойт", "уязвим", "глюк", "недочет"),
            prefixes = listOf("дюп"),
            phrases = listOf("использую баг", "юзаю баг", "дюпаю", "дюпнул")
        ),
        Cat(
            "imp", "Выдача себя за администрацию",
            hints = listOf("выдав", "администр", "модератор", "представл"),
            phrases = listOf(
                "я админ", "я модер", "я администратор", "я модератор", "я владелец", "я создатель сервера"
            )
        ),
        Cat(
            "polit", "Политика / религия",
            hints = listOf("политик", "религ", "политическ"),
            prefixes = listOf("путин", "навальн", "зеленск", "спецоперац", "украин", "политик"),
            exact = setOf("сво", "лнр", "днр")
        ),
        Cat(
            "porn", "Контент 18+",
            hints = listOf("18=", "порн", "эрот", "сексуал", "непристойн", "интим", "пошл"),
            prefixes = listOf("порн", "хентай", "эротик", "сиськ", "секс")
        )
    )

    private val CAT_BY_ID: Map<String, Cat> = CATS.associateBy { it.id }

    // ------------------------------------------------------------------ разбор правил
    private val NUM = Regex(
        """^\s*(?:п(?:ункт)?\.?\s*|§\s*)?(\d{1,3}(?:\.\d{1,3}){1,3}|\d{1,3}(?=[.)]))[.):\s\-–—]*(.*)""",
        RegexOption.IGNORE_CASE
    )
    private val SEP = Regex("""\s[—–-]\s|:\s|→|->|=>|\|""")
    private val VERB = Regex(
        "(?U)\\b(мут(?:а|у|ы|ом|е)?|мьют\\w*|бан(?:ов|ы|а|у|е|ом)?|кик(?:а|у|ом|нут\\w*)?|" +
            "варн(?:а|у|ы|ом|ов)?|warn\\w*|mute|ban|kick|jail|тюрьм\\w*|предупрежден\\w*|блокиров\\w*|" +
            "отстранен\\w*|штраф\\w*|чс|выговор\\w*|деморт\\w*|снят\\w* с должност\\w*)\\b"
    )
    private val TIME = Regex(
        "(?U)(\\d+\\s*(?:мин\\w*|час\\w*|ч\\b|дн\\w*|д\\b|сут\\w*|нед\\w*|мес\\w*)|навсегда|перманент\\w*|бессрочн\\w*)"
    )
    private val QUOTED = Regex("«([^»]{2,30})»|\"([^\"]{2,30})\"")

    private fun extractPunishment(text: String): String {
        val lower = text.lowercase()
        var start = -1
        for (sep in SEP.findAll(lower)) {
            val from = sep.range.last + 1
            if (from >= lower.length) continue
            val m = VERB.find(lower.substring(from))
            if (m != null) {
                start = from + m.range.first
                break
            }
        }
        if (start < 0) {
            val m = VERB.find(lower)
            if (m != null) start = m.range.first
        }
        if (start < 0) {
            val all = TIME.findAll(lower).toList()
            if (all.isNotEmpty()) start = all.last().range.first
        }
        if (start < 0) return ""
        var p = text.substring(start)
        val cut = Regex("""; |\.\s""").find(p)
        if (cut != null) p = p.substring(0, cut.range.first)
        p = p.trim().trim('.', ',', ';', ':', ' ')
        return if (p.length > 60) p.substring(0, 60).trim() else p
    }

    fun parseRules(text: String): List<Rule> {
        class Raw(val number: String, val sb: StringBuilder)

        val raws = ArrayList<Raw>()
        var anyNumbered = false
        for (line in text.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val m = NUM.find(l)
            if (m != null && m.groupValues[2].isNotBlank()) {
                anyNumbered = true
                raws.add(Raw(m.groupValues[1].trimEnd('.'), StringBuilder(m.groupValues[2].trim())))
            } else if (anyNumbered && raws.isNotEmpty()) {
                raws.last().sb.append(' ').append(l)
            } else {
                raws.add(Raw("", StringBuilder(l)))
            }
        }
        val out = ArrayList<Rule>()
        var headNum = ""
        var headText = ""
        for (r in raws) {
            val body = r.sb.toString()
            val pun = extractPunishment(body)
            var heading = ""
            if (r.number.isNotEmpty() && !r.number.contains('.')) {
                headNum = r.number
                headText = body
            } else if (r.number.isNotEmpty() && headNum.isNotEmpty() && r.number.startsWith("$headNum.")) {
                heading = headText
            }
            val quoted = ArrayList<String>()
            for (q in QUOTED.findAll(body)) {
                val v = (if (q.groupValues[1].isNotEmpty()) q.groupValues[1] else q.groupValues[2]).trim()
                if (v.isNotEmpty()) quoted.add(v)
            }
            out.add(Rule(r.number, body, heading, pun, quoted))
        }
        return out
    }

    // ------------------------------------------------------------------ разбор сообщений
    private val TS = Regex("""^\s*[\[(]?\d{1,2}[:.]\d{2}(?::\d{2})?[\])]?\s*[-–]?\s*""")
    private val LEADID = Regex("""^\s*[\[(#](\d{1,6})[\])]\s*""")
    private const val IDPART = """(?:\s*[\[(#]\s*(\d{1,10})\s*[\])]?)?"""
    private val COLON = Regex("""^(?:[\[(][^\])]{1,20}[\])]\s*)*<?([\p{L}\p{N}_.\-]{2,24})>?""" + IDPART + """\s*[:»>]+\s*(.+)""")
    private val BRACKET = Regex("""^[\[<]([\p{L}\p{N}_.\-]{2,24})[\]>]""" + IDPART + """\s+(.+)""")
    private val NICKLINE = Regex("""(?iu)^\s*(?:ник|нарушитель|игрок|nick)\s*[:=\-]\s*([\p{L}\p{N}_.\-]{2,24})(?:[\s,;]+(?:id|ид|айди)\s*[:=#]?\s*(\d{1,10}))?\s*$""")
    private val IDLINE = Regex("""(?iu)^\s*(?:id|ид|айди)\s*[:=#\-]?\s*(\d{1,10})\s*$""")
    private val NOTE_NICK = Regex("""(?iu)^([\p{L}\p{N}_.\-]{2,24})(?:[\s,;]+(?:(?:id|ид|айди)\s*[:=#]?\s*)?(\d{1,10}))?$""")
    private val NOT_NICK = setOf(
        "нарушение", "причина", "правило", "пункт", "жалоба", "ник", "игрок", "чат", "итого", "примечание",
        "наказание", "срок", "время", "скриншот", "вывод", "важно", "внимание", "ответ", "вопрос", "http", "https",
        "system", "система", "сервер", "server", "info", "warning"
    )
    private val LATIN_TOKEN = Regex("[A-Za-z0-9_.\\-]{3,24}")
    private val ENGLISH_STOP = setOf("the", "and", "you", "for", "not", "was", "chat", "game", "ban", "mute", "kick")

    private fun guessNick(input: String): String? {
        for (m in LATIN_TOKEN.findAll(input)) {
            val t = m.value.trim('.', '-')
            if (t.length < 3 || t.lowercase() in ENGLISH_STOP || t.lowercase() in NOT_NICK) continue
            if (!t.any { it.isLetter() }) continue
            val hasDigit = t.any { it.isDigit() }
            val hasUnderscore = t.contains('_')
            val midUpper = t.drop(1).any { it.isUpperCase() }
            val capital = t[0].isUpperCase() && t.length >= 4
            if (hasDigit || hasUnderscore || midUpper || capital) return t
        }
        return null
    }

    fun parseMessages(input: String, forcedNick: String, forcedPid: String = ""): List<Msg> {
        val out = ArrayList<Msg>()
        var current = forcedNick
        var currentPid = forcedPid
        var structured = 0
        for (raw in input.lines()) {
            var line = raw.trim()
            if (line.length < 2) continue
            line = TS.replace(line, "").trim()
            if (line.isEmpty()) continue
            val ni = NICKLINE.find(line)
            if (ni != null) {
                current = ni.groupValues[1]
                if (ni.groupValues[2].isNotEmpty()) currentPid = ni.groupValues[2]
                continue
            }
            val il = IDLINE.find(line)
            if (il != null) {
                currentPid = il.groupValues[1]
                continue
            }
            var lead = ""
            val lm = LEADID.find(line)
            if (lm != null) {
                lead = lm.groupValues[1]
                line = line.substring(lm.range.last + 1).trim()
                if (line.isEmpty()) continue
            }
            var nick: String? = null
            var pid = ""
            var text = ""
            val m = COLON.find(line)
            if (m != null && m.groupValues[1].lowercase() !in NOT_NICK) {
                nick = m.groupValues[1]
                pid = m.groupValues[2]
                text = m.groupValues[3]
            } else {
                val b = BRACKET.find(line)
                if (b != null && b.groupValues[1].lowercase() !in NOT_NICK) {
                    nick = b.groupValues[1]
                    pid = b.groupValues[2]
                    text = b.groupValues[3]
                }
            }
            if (nick != null) {
                structured++
                out.add(Msg(nick, text.trim(), pid.ifEmpty { lead }))
            } else {
                out.add(Msg(current.ifEmpty { "?" }, line, currentPid.ifEmpty { lead }))
            }
        }
        if (structured == 0 && current.isEmpty()) {
            val g = guessNick(input)
            if (g != null) return out.map { Msg(g, it.text, it.pid) }
        }
        return out
    }

    // ------------------------------------------------------------------ поиск нарушений
    private val URL = Regex(
        "(?iU)(https?://\\S+|www\\.\\S+|discord\\.gg\\S*|t\\.me\\S*|" +
            "\\b[\\p{L}\\p{N}-]{2,}\\.(?:ru|com|net|org|gg|tv|su|рф|me|io|xyz|ly|cc|ua|by|kz|us|pro|fun|club|online|site)\\b)"
    )
    private val IP = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{2,5})?\\b")
    private val CHAR_FLOOD = Regex("(.)\\1{7,}")

    private class Hit(val pid: String, val nick: String, val cat: Cat?, val title: String, val rule: Rule?, val snippet: String)

    private fun matchCat(cat: Cat, ws: List<String>, nText: String): String? {
        for (w in ws) {
            if (cat.exact.contains(w)) return w
            for (c in cat.contains) if (w.contains(c)) return w
            for (p in cat.prefixes) if (w.startsWith(p)) return w
        }
        for (ph in cat.phrases) if (nText.contains(ph)) return ph
        return null
    }

    private fun score(rule: Rule, hints: List<String>): Int {
        var s = 0
        for (h in hints) {
            val exact = h.endsWith("=")
            val key = if (exact) h.dropLast(1) else h
            if (key.contains(' ')) {
                if (rule.norm.contains(key)) s++
            } else if (rule.words.any { if (exact) it == key else it.startsWith(key) }) {
                s++
            }
        }
        return s
    }

    private fun pickRule(rules: List<Rule>, cat: Cat): Rule? {
        var best: Rule? = null
        var bs = 0
        for (r in rules) {
            val sc = score(r, cat.hints)
            if (sc > bs) {
                best = r
                bs = sc
            } else if (sc == bs && sc > 0 && best != null) {
                val b = best
                if (b.punishment.isEmpty() && r.punishment.isNotEmpty()) best = r
            }
        }
        if (best == null && cat.fallback != null) {
            val fb = CAT_BY_ID[cat.fallback]
            if (fb != null) return pickRule(rules, fb)
        }
        return best
    }

    private fun snippet(s: String): String {
        val t = s.trim().replace(Regex("\\s+"), " ")
        return if (t.length > 70) t.substring(0, 70) + "…" else t
    }

    private fun detect(m: Msg, rules: List<Rule>): List<Hit> {
        val hits = ArrayList<Hit>()
        if (m.text.isBlank()) return hits
        val ws = words(m.text)
        val nText = ws.joinToString(" ")
        val sn = snippet(m.text)
        for (cat in CATS) {
            if (cat.id == "spam" || cat.id == "caps" || cat.id == "ads") continue
            if (matchCat(cat, ws, nText) != null) hits.add(Hit(m.pid, m.nick, cat, cat.title, null, sn))
        }
        // реклама: слова + ссылки + адреса
        val ads = CAT_BY_ID.getValue("ads")
        if (matchCat(ads, ws, nText) != null || URL.containsMatchIn(m.text) || IP.containsMatchIn(m.text)) {
            hits.add(Hit(m.pid, m.nick, ads, ads.title, null, sn))
        }
        // капс
        val letters = m.text.count { it.isLetter() }
        val upper = m.text.count { it.isUpperCase() }
        if (letters >= 10 && upper * 100 / letters >= 80) {
            val c = CAT_BY_ID.getValue("caps")
            hits.add(Hit(m.pid, m.nick, c, c.title, null, sn))
        }
        // флуд символами и словами
        var flood = CHAR_FLOOD.containsMatchIn(m.text)
        if (!flood) {
            var run = 1
            for (i in 1 until ws.size) {
                run = if (ws[i] == ws[i - 1]) run + 1 else 1
                if (run >= 4) {
                    flood = true
                    break
                }
            }
        }
        if (flood) {
            val c = CAT_BY_ID.getValue("spam")
            hits.add(Hit(m.pid, m.nick, c, c.title, null, sn))
        }
        // запрещённые слова из самих правил
        for (r in rules) {
            for (q in r.quoted) {
                val qw = words(q)
                if (qw.isEmpty()) continue
                val found = if (qw.size == 1) ws.any { it.startsWith(qw[0]) } else nText.contains(qw.joinToString(" "))
                if (found) hits.add(Hit(m.pid, m.nick, null, "Запрещённое слово «$q»", r, sn))
            }
        }
        return hits
    }

    // ------------------------------------------------------------------ главный вход
    fun analyze(rulesText: String, body: String, note: String): Result {
        val rules = parseRules(rulesText)
        var text = body
        var forced = ""
        var forcedPid = ""
        val n = note.trim()
        if (n.isNotEmpty()) {
            val idOnly = IDLINE.find(n)
            val nm = NOTE_NICK.find(n)
            if (idOnly != null) {
                forcedPid = idOnly.groupValues[1]
            } else if (nm != null) {
                forced = nm.groupValues[1]
                forcedPid = nm.groupValues[2]
            } else {
                text = "$n\n$body"
            }
        }
        val msgs = parseMessages(text, forced, forcedPid)
        if (msgs.isEmpty()) {
            return Result(emptyList(), "Не нашёл текста для проверки. Пришли скриншот чата или напиши сообщения.", 0)
        }
        val hits = ArrayList<Hit>()
        for (m in msgs) hits.addAll(detect(m, rules))
        // повторы одного и того же сообщения
        val counts = LinkedHashMap<String, Int>()
        val firstMsg = HashMap<String, Msg>()
        for (m in msgs) {
            val key = m.nick.lowercase() + "|" + words(m.text).joinToString(" ")
            if (words(m.text).isEmpty()) continue
            counts[key] = (counts[key] ?: 0) + 1
            if (!firstMsg.containsKey(key)) firstMsg[key] = m
        }
        for ((key, c) in counts) {
            if (c >= 3) {
                val m = firstMsg.getValue(key)
                val sp = CAT_BY_ID.getValue("spam")
                hits.add(Hit(m.pid, m.nick, sp, sp.title + " (одно сообщение ×" + c + ")", null, snippet(m.text)))
            }
        }

        // собираем: одна запись на (ник, категория/пункт)
        val order = ArrayList<String>()
        val grouped = HashMap<String, MutableList<Hit>>()
        for (h in hits) {
            val key = h.nick.lowercase() + "|" + (h.cat?.id ?: ("rule" + h.rule?.number + h.title))
            if (!grouped.containsKey(key)) {
                grouped[key] = ArrayList()
                order.add(key)
            }
            grouped.getValue(key).add(h)
        }
        val found = ArrayList<Found>()
        val noRule = ArrayList<String>()
        for (key in order) {
            val list = grouped.getValue(key)
            val h = list[0]
            val rule = h.rule ?: (if (h.cat != null) pickRule(rules, h.cat) else null)
            var reason = h.title + ": «" + h.snippet + "»"
            if (list.size > 1) reason += " (и ещё " + (list.size - 1) + ")"
            if (rule == null) noRule.add(h.nick)
            val pid = list.firstOrNull { it.pid.isNotEmpty() }?.pid ?: ""
            found.add(Found(h.nick, rule?.number ?: "", reason, rule?.punishment ?: "", pid))
        }

        val sb = StringBuilder()
        sb.append("Проверено сообщений: ").append(msgs.size).append(".\n")
        if (found.isEmpty()) {
            sb.append("Нарушений не нашёл. Я ищу по словам и признакам (мат, оскорбления, реклама, угрозы, спам, капс, ")
            sb.append("запрещённые слова из твоих правил), поэтому смысл и шутки не понимаю — глянь экран сам.")
        } else {
            sb.append("Нашёл нарушений: ").append(found.size).append(".\n")
            for (f in found) {
                sb.append("• ").append(f.nick)
                if (f.pid.isNotEmpty()) sb.append(" (ID ").append(f.pid).append(")")
                if (f.rule.isNotEmpty()) sb.append(" — ").append(ruleLabel(f.rule))
                sb.append(": ").append(f.reason)
                if (f.punishment.isNotEmpty()) sb.append(" → ").append(f.punishment)
                sb.append('\n')
            }
            if (rules.isEmpty()) {
                sb.append("Правила не добавлены, поэтому пункты и сроки не подставил — добавь их во вкладке «Правила».")
            } else if (noRule.isNotEmpty()) {
                sb.append("Для некоторых нарушений не нашёл подходящий пункт в правилах — выбери вручную в таблице.")
            }
            if (found.any { it.nick == "?" }) {
                sb.append("\nНик не определился (знак «?») — исправь в таблице или укажи ник в пояснении.")
            }
        }
        return Result(found, sb.toString().trim(), msgs.size)
    }
}
