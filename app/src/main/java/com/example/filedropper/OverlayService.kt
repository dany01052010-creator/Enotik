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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * Плавающая кнопка поверх всех приложений (остаётся, даже если выйти из Enotik sborki).
 * Тап — открывает крупное меню, перетаскивание — двигает кнопку.
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
            x = dp(12)
            y = dp(240)
        }

        rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(68))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            minimumWidth = dp(300)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F2202020"))
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
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
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

    private fun chip(
        text: String,
        bg: String = "#37474F",
        weight: Float = 0f,
        onClick: (TextView) -> Unit
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(bg))
        }
        layoutParams = if (weight > 0f)
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        else LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        setOnClickListener { onClick(this) }
    }

    private fun rebuildPanel() {
        panel.removeAllViews()
        val pending = store.pendingDir().listFiles()?.size ?: 0
        panel.addView(TextView(this).apply {
            text = if (pending == 0) "Файлы не выбраны" else "Выбрано: $pending"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), 0, 0, dp(6))
        })

        panel.addView(chip("📂  Выбрать файл / ZIP", "#1565C0") { openApp("pick_files") })
        panel.addView(chip("📁  Выбрать папку", "#1565C0") { openApp("pick_folder") })
        panel.addView(chip("⚡  Поставить сразу — выбрать куда", "#E65100") {
            if (pending == 0) toast("Сначала выбери файл, ZIP или папку")
            else openApp("quick")
        })

        val presets = store.all()
        if (presets.isNotEmpty()) {
            panel.addView(TextView(this).apply {
                text = "Мои кнопки"; textSize = 15f; setTextColor(Color.LTGRAY)
                setPadding(dp(4), dp(8), 0, dp(2))
            })
        }
        presets.forEach { p ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(chip("▶  ${p.name}", "#2E7D32", 1f) { place(p) })
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
            }.also { it.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) } })
            panel.addView(row)
        }

        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        foot.addView(chip("✚ Новая", "#37474F", 1f) {
            if (pending == 0) toast("Сначала выбери файл, ZIP или папку") else openApp("create")
        })
        foot.addView(chip("Открыть", "#37474F", 1f) { openApp(null) })
        foot.addView(chip("✕", "#616161", 1f) { panel.visibility = View.GONE })
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

    private fun openApp(action: String?) {
        panel.visibility = View.GONE
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) i.putExtra("action", action)
        startActivity(i)
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
