package com.enotik.catchgame

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("crash", MODE_PRIVATE)

        // Если прошлый запуск упал — показываем причину, чтобы её можно было скопировать и прислать.
        val old = prefs.getString("err", null)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            prefs.edit().putString("err", Log.getStackTraceString(e)).commit()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
        if (old != null) {
            showError(old)
            return
        }

        try {
            setContentView(GameView(this))
        } catch (e: Throwable) {
            showError(Log.getStackTraceString(e))
        }
    }

    private fun showError(err: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        box.addView(TextView(this).apply {
            text = "Игра упала. Задержи палец на тексте ниже, скопируй его и пришли разработчику:"
            textSize = 16f
        })
        val btn = Button(this).apply {
            text = "Очистить и запустить игру"
            setOnClickListener {
                getSharedPreferences("crash", MODE_PRIVATE).edit().remove("err").commit()
                recreate()
            }
        }
        box.addView(btn)
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
