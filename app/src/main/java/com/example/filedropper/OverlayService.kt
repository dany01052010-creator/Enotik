package com.example.filedropper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Маленькая плавающая кнопка «E» поверх всех приложений.
 * Остаётся на экране, даже если выйти из Enotik sborki.
 * Тап — открывает мини-меню, перетаскивание — двигает кнопку.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var rootView: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var store: Store
    private val ui = Handler(Looper.getMainLooper())
    private var armedDelete: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        store = Store(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("overlay", "Плавающая кнопка", NotificationManager.IMPORTANCE_LOW))
        startForeground(
            2,
            Notification.Builder(this, "overlay")
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("Enotik sborki: плавающая кнопка включена")
                .setOngoing(true)
                .build()
        )

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(220)
        }

        rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = TextView(this).apply {
            text = "E"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E61E88E5"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E6202020"))
            }
        }
        rootView.addView(bubble)
        rootView.addView(panel)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        bubble.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = ev.rawX; touchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    wm.updateViewLayout(rootView, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }
        wm.addView(rootView, lp)
    }

    private fun togglePanel() {
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
        } else {
            rebuildPanel()
            panel.visibility = View.VISIBLE
        }
    }

    private fun chip(text: String, bg: String = "#37474F", onClick: (TextView) -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(bg))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
            setOnClickListener { onClick(this) }
        }

    private fun rebuildPanel() {
        panel.removeAllViews()
        val pending = store.pendingDir().listFiles()?.size ?: 0
        panel.addView(TextView(this).apply {
            text = if (pending == 0) "Файлы не выбраны" else "Выбрано: $pending"
            textSize = 12f
            setTextColor(Color.LTGRAY)
        })
        val presets = store.all()
        if (presets.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "Кнопок нет"; textSize = 12f; setTextColor(Color.LTGRAY)
            })
        }
        presets.forEach { p ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(chip("▶ ${p.name}", "#2E7D32") { place(p) })
            row.addView(chip(if (armedDelete == p.id) "❓" else "🗑", "#B71C1C") { v ->
                if (armedDelete == p.id) {
                    armedDelete = null
                    store.delete(p)
                    rebuildPanel()
                } else {
                    armedDelete = p.id
                    v.text = "❓"
                    ui.postDelayed({ if (armedDelete == p.id) { armedDelete = null; rebuildPanel() } }, 3000)
                }
            })
            panel.addView(row)
        }
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        foot.addView(chip("✚ Новая") { openApp(create = true) })
        foot.addView(chip("Открыть") { openApp(create = false) })
        foot.addView(chip("✕", "#616161") { panel.visibility = View.GONE })
        panel.addView(foot)
    }

    private fun place(p: Preset) {
        val pending = store.pendingDir()
        val src = if (!pending.listFiles().isNullOrEmpty()) pending else store.dirFor(p.id)
        toast("Ставлю «${p.name}»…")
        Thread {
            val r = Placer.place(this, src, p)
            ui.post { toast((if (r.ok) "✔ " else "✘ ") + r.message, long = true) }
        }.start()
    }

    private fun openApp(create: Boolean) {
        panel.visibility = View.GONE
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("create", create)
        )
    }

    private fun toast(s: String, long: Boolean = false) {
        Toast.makeText(this, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        try {
            wm.removeView(rootView)
        } catch (_: Exception) {
        }
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
