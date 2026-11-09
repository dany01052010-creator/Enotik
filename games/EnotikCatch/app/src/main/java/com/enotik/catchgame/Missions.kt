package com.enotik.catchgame

enum class MKind { RUN_TOTAL, COINS, FOOD, JUMPS, SINGLE_RUN }

class MissionDef(
    val id: String, val kind: MKind, val icon: String,
    private val pattern: String, val base: Int, val reward: Int
) {
    fun title(target: Int) = pattern.replace("%d", target.toString())
}

/** Задания: выполнил — получил монеты, следующее задание тяжелее. */
object Missions {
    val defs = listOf(
        MissionDef("run", MKind.RUN_TOTAL, "🏃", "Пробеги всего %d м", 500, 30),
        MissionDef("coins", MKind.COINS, "🪙", "Собери %d монет", 20, 20),
        MissionDef("food", MKind.FOOD, "🐟", "Съешь %d вкусняшек", 10, 25),
        MissionDef("jump", MKind.JUMPS, "🦘", "Перепрыгни %d препятствий", 8, 25),
        MissionDef("single", MKind.SINGLE_RUN, "⚡", "Пробеги %d м за один забег", 200, 40)
    )

    fun tier(d: MissionDef) = Store.getInt("m_${d.id}_tier")
    fun progress(d: MissionDef) = Store.getInt("m_${d.id}_prog")
    fun target(d: MissionDef) = d.base * (tier(d) + 1)
    fun reward(d: MissionDef) = d.reward * (tier(d) + 1)

    /** Возвращает тексты выполненных заданий. */
    fun add(kind: MKind, amount: Int, absolute: Boolean = false): List<String> {
        val done = ArrayList<String>()
        for (d in defs) {
            if (d.kind != kind) continue
            var prog = if (absolute) maxOf(progress(d), amount) else progress(d) + amount
            val t = target(d)
            if (prog >= t) {
                val r = reward(d)
                done.add("${d.icon} ${d.title(t)}  +$r монет")
                Store.coins = Store.coins + r
                Store.putInt("m_${d.id}_tier", tier(d) + 1)
                prog = 0
            }
            Store.putInt("m_${d.id}_prog", prog)
        }
        return done
    }
}
