package com.modhelper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBookTest {
    private val sample = """
1. Общие правила
1.1 Запрещены оскорбления игроков — мут 30 минут
1.2 Запрещено использование читов и хаков. Наказание: бан 7 дней
2 Реклама сторонних серверов — бан навсегда
Повтор — перманентный бан
""".trimIndent()

    @Test
    fun parsesNumbersAndPunishments() {
        val rules = RuleBook.parse(sample)
        assertEquals(listOf("1", "1.1", "1.2", "2"), rules.map { it.number })
        assertEquals("мут 30 минут", rules[1].punishment)
        assertEquals("бан 7 дней", rules[2].punishment)
        assertEquals("бан навсегда", rules[3].punishment)
        assertEquals("Запрещены оскорбления игроков", rules[1].title)
    }

    @Test
    fun parsesUnnumberedRules() {
        val rules = RuleBook.parse("- Мат запрещён: мут 30 минут\n- Спам: мут 10 минут")
        assertEquals(2, rules.size)
        assertEquals("", rules[0].number)
        assertEquals("мут 30 минут", rules[0].punishment)
    }

    @Test
    fun durationLineIsNotARule() {
        val rules = RuleBin("1.1 Оскорбления\n30 минут мута")
        assertEquals(1, rules.size)
    }

    private fun RuleBin(t: String) = RuleBook.parse(t)

    @Test
    fun extractsPunishment() {
        assertEquals("Бан на 7 дней", RuleBook.extractPunishment("Бан на 7 дней"))
        assertEquals("", RuleBook.extractPunishment("Просто текст без срока"))
    }

    @Test
    fun suggestsByMeaning() {
        val rules = RuleBook.parse(sample)
        assertEquals("1.1", RuleBook.suggest(rules, "игрок оскорблял и матерился в чате").first().rule.number)
        assertEquals("1.2", RuleBook.suggest(rules, "использовал читы").first().rule.number)
        assertEquals("2", RuleBook.suggest(rules, "реклама сервера в чате").first().rule.number)
    }

    @Test
    fun suggestsByRuleNumber() {
        val rules = RuleBook.parse(sample)
        val s = RuleBook.suggest(rules, "нарушил п. 1.2")
        assertEquals("1.2", s.first().rule.number)
        assertTrue(s.first().matched.contains("п. 1.2"))
    }

    @Test
    fun noMatchGivesEmptyList() {
        val rules = RuleBook.parse(sample)
        assertTrue(RuleBook.suggest(rules, "погода сегодня хорошая").isEmpty())
    }

    @Test
    fun findsNicks() {
        val nicks = RuleBook.nickCandidates("[12:30] Vasya_228: ты тупой\nPetya: привет")
        assertEquals("Vasya_228", nicks.first())
        assertTrue(nicks.contains("Petya"))
    }
}
