package com.enotik.catchgame

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun screenBackground() = GradientDrawable(
    GradientDrawable.Orientation.TOP_BOTTOM,
    intArrayOf(Color.parseColor("#2B1B5A"), Color.parseColor("#7B2D5F"), Color.parseColor("#E4572E"))
)

fun Context.bigButton(label: String, color: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 20f
    setTypeface(null, Typeface.BOLD)
    setTextColor(Color.WHITE)
    gravity = Gravity.CENTER
    setPadding(dp(16), dp(16), dp(16), dp(16))
    background = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.parseColor(color))
        setStroke(dp(2), Color.argb(90, 255, 255, 255))
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(7), 0, dp(7)) }
    setOnClickListener { onClick() }
}

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(12), dp(16), dp(12))
    background = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(60, 0, 0, 0))
        setStroke(dp(1), Color.argb(70, 255, 255, 255))
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(6), 0, dp(6)) }
}

fun Context.label(text: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(null, Typeface.BOLD)
}
