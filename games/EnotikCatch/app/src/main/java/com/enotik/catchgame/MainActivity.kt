package com.enotik.catchgame

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity(), GameView.Host {

    private var screen = "menu"
    private var game: GameView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Store.init(this)

        // Если прошлый запуск упал — показываем причину, чтобы её можно было скопировать и прислать.
        val crash = getSharedPreferences("crash", MODE_PRIVATE)
        val old = crash.getString("err", null)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            crash.edit().putString("err", Log.getStackTraceString(e)).commit()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
        if (old != null) {
            showError(old)
            return
        }
        try {
            showMenu()
            if (Store.name.isBlank()) askName()
        } catch (e: Throwable) {
            showError(Log.getStackTraceString(e))
        }
    }

    // ---------------------------------------------------------------- экраны

    /** Контейнер по центру с ограниченной шириной (удобно и на телефоне, и на планшете). */
    private fun page(scroll: Boolean = true, build: (LinearLayout) -> Unit) {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        build(inner)
        val width = minOf(dp(560), resources.displayMetrics.widthPixels - dp(32))
        val holder = FrameHolder(this)
        holder.addView(inner, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        val sv = ScrollView(this).apply {
            background = screenBackground()
            isFillViewport = true
            addView(holder, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(sv)
    }

    private class FrameHolder(ctx: Activity) : LinearLayout(ctx) {
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ctx.dp(36), 0, ctx.dp(24))
        }
    }

    private fun showMenu() {
        screen = "menu"
        game = null
        page { box ->
            box.addView(label("🦝", 64f).apply { gravity = Gravity.CENTER })
            box.addView(label("Енот-бегун", 34f, true).apply { gravity = Gravity.CENTER })
            val who = Store.name.ifBlank { "Енот" }
            box.addView(label("Привет, $who!  🪙 ${Store.coins}   🏆 рекорд ${Store.best}", 16f,
                color = Color.parseColor("#FFE9A8")).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(18))
            })
            box.addView(bigButton("▶  Играть", "#2E9E5B") { startGame() })
            box.addView(bigButton("📋  Задания", "#3D6FD8") { showMissions() })
            box.addView(bigButton("🏆  Таблица лидеров", "#D8A23D") { showLeaders() })
            box.addView(bigButton("👤  Сменить имя", "#6B5B95") { askName() })
            val names = arrayOf("Низкая", "Средняя", "Высокая (шейдеры)")
            box.addView(bigButton("Графика: ${names[Store.quality]}", "#555B6E") {
                Store.quality = (Store.quality + 1) % 3
                showMenu()
            })
            box.addView(label(
                "Свайп влево/вправо — менять полосу, свайп вверх или тап — прыжок. Ешь еду, пока шкала голода не опустела!",
                13f, color = Color.argb(200, 255, 255, 255)
            ).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
        }
    }

    private fun showMissions() {
        screen = "missions"
        page { box ->
            box.addView(label("📋 Задания", 28f, true).apply { setPadding(0, 0, 0, dp(8)) })
            box.addView(label("Выполняй задания и получай монеты. После выполнения задание становится сложнее.", 13f,
                color = Color.argb(210, 255, 255, 255)))
            for (d in Missions.defs) {
                val t = Missions.target(d)
                val pr = Missions.progress(d).coerceAtMost(t)
                val c = card()
                c.addView(label("${d.icon}  ${d.title(t)}", 17f, true))
                c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = t
                    progress = pr
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(14)
                    ).apply { setMargins(0, dp(8), 0, dp(4)) }
                })
                c.addView(label("$pr / $t     награда: ${Missions.reward(d)} 🪙   (уровень ${Missions.tier(d) + 1})", 13f,
                    color = Color.argb(220, 255, 255, 255)))
                box.addView(c)
            }
            box.addView(bigButton("←  В меню", "#6B5B95") { showMenu() })
        }
    }

    private fun showLeaders() {
        screen = "leaders"
        page { box ->
            box.addView(label("🏆 Таблица лидеров", 28f, true).apply { setPadding(0, 0, 0, dp(4)) })
            box.addView(label("Лучшие забеги на этом устройстве", 13f, color = Color.argb(210, 255, 255, 255)))
            val list = Store.leaders()
            if (list.isEmpty()) {
                box.addView(card().apply { addView(label("Пока пусто. Сыграй первый забег!", 16f)) })
            }
            val medals = arrayOf("🥇", "🥈", "🥉")
            list.forEachIndexed { i, e ->
                val c = card()
                val place = if (i < 3) medals[i] else "${i + 1}."
                c.addView(label("$place  ${e.name}", 18f, true))
                c.addView(label("${e.score} очков   •   ${e.meters} м   •   ${e.date}", 13f,
                    color = Color.argb(220, 255, 255, 255)))
                box.addView(c)
            }
            box.addView(bigButton("←  В меню", "#6B5B95") { showMenu() })
        }
    }

    private fun askName() {
        val et = EditText(this).apply {
            hint = "Твоё имя"
            setText(Store.name)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Как тебя зовут?")
            .setMessage("Имя попадёт в таблицу лидеров.")
            .setView(et)
            .setPositiveButton("Сохранить") { _, _ ->
                Store.name = et.text.toString().trim().take(14)
                if (screen == "menu") showMenu()
            }
            .setNegativeButton("Пропустить", null)
            .show()
    }

    private fun startGame() {
        screen = "game"
        val g = GameView(this, this)
        game = g
        setContentView(g)
    }

    // ---------------------------------------------------------------- GameView.Host

    override fun onGameFinished(score: Int, meters: Int) {
        Store.addScore(score, meters)
    }

    override fun exitToMenu() = showMenu()
    override fun replay() = startGame()

    override fun onPause() {
        super.onPause()
        game?.pause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "menu" -> super.onBackPressed()
            "game" -> {
                val g = game
                if (g != null && g.isPlaying()) g.pause() else showMenu()
            }
            else -> showMenu()
        }
    }

    // ---------------------------------------------------------------- ошибки

    private fun showError(err: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        box.addView(TextView(this).apply {
            text = "Игра упала. Задержи палец на тексте ниже, скопируй его и пришли разработчику:"
            textSize = 16f
        })
        box.addView(Button(this).apply {
            text = "Очистить и запустить игру"
            setOnClickListener {
                getSharedPreferences("crash", MODE_PRIVATE).edit().remove("err").commit()
                recreate()
            }
        })
        box.addView(ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = err
                textSize = 12f
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(box)
    }
}
