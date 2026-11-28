package com.modhelper.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    val BG = Color.parseColor("#1B1C21")
    val PANEL = Color.parseColor("#24262D")
    val PANEL2 = Color.parseColor("#2F323B")
    val FG = Color.parseColor("#E9E9EE")
    val MUTED = Color.parseColor("#8D919C")
    val ACCENT = Color.parseColor("#5B9DFF")
    val WARN = Color.parseColor("#FFB454")
    val DANGER = Color.parseColor("#EF5B5B")
    val OK = Color.parseColor("#5FD38D")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radius: Int): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = dp(radius).toFloat()
    return d
}

fun linp(w: Int, h: Int, weight: Float = 0f, top: Int = 0): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.topMargin = top
    return p
}

fun Context.vbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.VERTICAL
    return l
}

fun Context.hbox(): LinearLayout {
    val l = LinearLayout(this)
    l.orientation = LinearLayout.HORIZONTAL
    l.gravity = Gravity.CENTER_VERTICAL
    return l
}

fun Context.label(text: String, size: Float = 14f, color: Int = C.FG, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = text
    t.textSize = size
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
    return t
}

fun Context.button(text: String, accent: Boolean = false, onClick: () -> Unit): Button {
    val b = Button(this)
    b.text = text
    b.isAllCaps = false
    b.textSize = 13f
    b.setTextColor(Color.WHITE)
    b.background = rounded(if (accent) C.ACCENT else C.PANEL2, 10)
    b.setPadding(dp(12), dp(6), dp(12), dp(6))
    b.minHeight = 0
    b.minimumHeight = 0
    b.minWidth = 0
    b.minimumWidth = 0
    b.setOnClickListener { onClick() }
    return b
}

fun Context.edit(hint: String, value: String = "", multiline: Boolean = false, password: Boolean = false): EditText {
    val e = EditText(this)
    e.setText(value)
    e.hint = hint
    e.setTextColor(C.FG)
    e.setHintTextColor(C.MUTED)
    e.background = rounded(C.PANEL, 8)
    e.setPadding(dp(10), dp(8), dp(10), dp(8))
    e.textSize = 14f
    if (multiline) {
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        e.gravity = Gravity.TOP
        e.minLines = 3
    } else if (password) {
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    } else {
        e.inputType = InputType.TYPE_CLASS_TEXT
    }
    return e
}

fun ruleLabel(rule: String): String {
    val r = rule.trim()
    if (r.isEmpty()) return ""
    val l = r.lowercase()
    return if (l.startsWith("п") || l.startsWith("p") || l.startsWith("ст")) r else "п. $r"
}
