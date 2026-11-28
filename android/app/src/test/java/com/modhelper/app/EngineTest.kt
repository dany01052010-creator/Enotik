package com.modhelper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val rules = """
        1. Общие правила чата
        1.1 Запрещён мат и нецензурная лексика — мут 30 минут
        1.2 Оскорбление игроков — мут 2 часа
        2. Реклама и спам
        2.1 Реклама сторонних серверов и ссылок — бан 7 дней
        2.2 Спам и флуд в чате — мут 15 минут
        2.3 Использование КАПСА — предупреждение
        3.1 Запрещено слово «лагер» — мут 10 минут
    """.trimIndent()

    private val chat = """
        [12:30] Vasya_228: ты тупой идиот
        Pety: зайдите на сервер mysuper.ru лучший сервер
        Kolya: ПРИВЕТ ВСЕМ КАК ДЕЛА ОЧЕНЬ
        Anya: привет всем, хорошо что хулиганы ушли
        Zed: ну ты и лагер
        Bob: б л я д ь
        Spam1: купи
        Spam1: купи
        Spam1: купи
    """.trimIndent()

    private fun find(res: Engine.Result, nick: String) = res.found.filter { it.nick == nick }

    @Test
    fun parsesRules() {
        val r = Engine.parseRules(rules)
        val s = r.joinToString(" | ") { it.number + "=" + it.punishment }
        assertEquals("rules: $s", 8, r.size)
        assertEquals("rules: $s", "мут 30 минут", r.first { it.number == "1.1" }.punishment)
        assertEquals("rules: $s", "бан 7 дней", r.first { it.number == "2.1" }.punishment)
        assertEquals("rules: $s", "предупреждение", r.first { it.number == "2.3" }.punishment)
        assertEquals("rules: $s", listOf("лагер"), r.first { it.number == "3.1" }.quoted)
    }

    @Test
    fun findsViolations() {
        val res = Engine.analyze(rules, chat, "")
        val rep = res.report
        val v = find(res, "Vasya_228")
        assertEquals("Vasya: $rep", 1, v.size)
        assertEquals("Vasya: $rep", "1.2", v[0].rule)
        assertEquals("Vasya: $rep", "мут 2 часа", v[0].punishment)

        val p = find(res, "Pety")
        assertTrue("Pety: $rep", p.isNotEmpty() && p[0].rule == "2.1" && p[0].punishment == "бан 7 дней")

        val k = find(res, "Kolya")
        assertTrue("Kolya: $rep", k.isNotEmpty() && k[0].rule == "2.3")

        assertEquals("Anya: $rep", 0, find(res, "Anya").size)

        val z = find(res, "Zed")
        assertTrue("Zed: $rep", z.isNotEmpty() && z[0].rule == "3.1" && z[0].punishment == "мут 10 минут")

        val b = find(res, "Bob")
        assertTrue("Bob: $rep", b.isNotEmpty() && b[0].rule == "1.1" && b[0].punishment == "мут 30 минут")

        val s = find(res, "Spam1")
        assertTrue("Spam1: $rep", s.isNotEmpty() && s[0].rule == "2.2")
    }

    @Test
    fun forcedNickAndFreeText() {
        val res = Engine.analyze(rules, "он материл всех: пошёл нахуй", "Ivan_Petrov")
        assertTrue(res.report, res.found.isNotEmpty() && res.found[0].nick == "Ivan_Petrov" && res.found[0].rule == "1.1")
        val res2 = Engine.analyze("", "Vasya_228: ты идиот", "")
        assertTrue(res2.report, res2.found.size == 1 && res2.found[0].rule == "" && res2.found[0].nick == "Vasya_228")
    }

    @Test
    fun parsesPlayerIds() {
        val res = Engine.analyze(rules, "Vasya[123]: ты идиот\n[45] Pety: хуй\nKolya (7): привет", "")
        val rep = res.report
        val v = find(res, "Vasya")
        assertTrue("Vasya: $rep", v.isNotEmpty() && v[0].pid == "123" && v[0].rule == "1.2")
        val p = find(res, "Pety")
        assertTrue("Pety: $rep", p.isNotEmpty() && p[0].pid == "45" && p[0].rule == "1.1")
        assertEquals("Kolya: $rep", 0, find(res, "Kolya").size)
        val r2 = Engine.analyze(rules, "он матерился: хуй", "Ivan_Petrov 777")
        assertTrue(r2.report, r2.found.isNotEmpty() && r2.found[0].nick == "Ivan_Petrov" && r2.found[0].pid == "777")
        // как пишет речь: ник и ид говорящего склеены в тег
        val r3 = Engine.analyze(rules, "Vasya_228[15]: ты тупой идиот", "")
        assertTrue(r3.report, r3.found.size == 1 && r3.found[0].nick == "Vasya_228" && r3.found[0].pid == "15" && r3.found[0].punishment == "мут 2 часа")
    }
}
