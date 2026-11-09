package com.enotik.catchgame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private class Item(var x: Float, var y: Float, val vy: Float, val kind: Int, var rot: Float, val spin: Float)
private class Popup(val x: Float, var y: Float, val text: String, val color: Int, var life: Float)

/** Енот-ловец: води пальцем, лови еду, обходи бомбы. */
class GameView(ctx: Context) : View(ctx) {

    // 0 рыба, 1 яблоко (+1), 2 звезда (+5), 3 бомба (минус жизнь)
    private val emoji = arrayOf("🐟", "🍎", "⭐", "💣")

    private val prefs = ctx.getSharedPreferences("catch", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)

    private var w = 0f
    private var h = 0f
    private var raccoonX = 0f
    private var targetX = 0f
    private var squash = 0f
    private var time = 0f
    private var overTime = 0f
    private var spawnTimer = 0.6f
    private var last = System.nanoTime()

    private var score = 0
    private var lives = 3
    private var combo = 0
    private var over = false
    private var started = false

    private val items = ArrayList<Item>()
    private val popups = ArrayList<Popup>()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bgPaint = Paint()
    private val path = Path()
    private val rect = RectF()

    private val gray = Color.parseColor("#8E9AAF")
    private val grayDark = Color.parseColor("#3A3F52")
    private val light = Color.parseColor("#E9EEF5")

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw.toFloat()
        h = nh.toFloat()
        raccoonX = w / 2
        targetX = raccoonX
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(Color.parseColor("#1B1F3B"), Color.parseColor("#44318D"), Color.parseColor("#E84A5F")),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun size() = min(w * 0.30f, h * 0.17f)

    private fun restart() {
        items.clear(); popups.clear()
        score = 0; lives = 3; combo = 0; over = false; started = true
        spawnTimer = 0.6f; overTime = 0f; time = 0f
        last = System.nanoTime()
    }

    private fun spawn() {
        val s = size()
        val r = s * 0.25f
        val bombChance = min(0.34f, 0.20f + score * 0.002f)
        val roll = Random.nextFloat()
        val kind = when {
            roll < bombChance -> 3
            roll < bombChance + 0.08f -> 2
            else -> Random.nextInt(2)
        }
        val base = h * 0.30f + score * h * 0.004f
        val vy = min(h * 0.95f, base) * (0.8f + Random.nextFloat() * 0.5f)
        items.add(
            Item(r + Random.nextFloat() * (w - 2 * r), -r, vy, kind, 0f, (Random.nextFloat() - 0.5f) * 180f)
        )
    }

    private fun update(dt: Float) {
        time += dt
        raccoonX += (targetX - raccoonX) * min(1f, dt * 18f)
        squash = maxOf(0f, squash - dt * 4f)

        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            spawn()
            spawnTimer = maxOf(0.32f, 0.95f - score * 0.008f) * (0.7f + Random.nextFloat() * 0.6f)
        }

        val s = size()
        val baseY = h - h * 0.07f
        val it = items.iterator()
        while (it.hasNext()) {
            val i = it.next()
            i.y += i.vy * dt
            i.rot += i.spin * dt
            val caught = i.y > baseY - s * 1.2f && i.y < baseY && abs(i.x - raccoonX) < s * 0.55f
            if (caught) {
                it.remove()
                squash = 1f
                if (i.kind == 3) {
                    lives--
                    combo = 0
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    popups.add(Popup(i.x, i.y, "БУМ!", Color.parseColor("#FF5252"), 0.8f))
                    if (lives <= 0) gameOver()
                } else {
                    combo++
                    val mult = 1 + combo / 5
                    val pts = (if (i.kind == 2) 5 else 1) * mult
                    score += pts
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    popups.add(Popup(i.x, i.y, "+$pts", Color.parseColor("#FFE066"), 0.7f))
                }
            } else if (i.y > h + s) {
                it.remove()
            }
        }

        val pi = popups.iterator()
        while (pi.hasNext()) {
            val q = pi.next()
            q.life -= dt
            q.y -= h * 0.12f * dt
            if (q.life <= 0f) pi.remove()
        }
    }

    private fun gameOver() {
        over = true
        overTime = 0f
        if (score > best) {
            best = score
            prefs.edit().putInt("best", best).apply()
        }
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        var dt = (now - last) / 1e9f
        last = now
        if (dt > 0.05f) dt = 0.05f

        if (w > 0f) {
            if (!started) { time += dt; raccoonX = w / 2 }
            else if (!over) update(dt) else overTime += dt
            drawScene(c)
        }
        postInvalidateOnAnimation()
    }

    private fun drawScene(c: Canvas) {
        c.drawRect(0f, 0f, w, h, bgPaint)

        // звёзды на фоне
        p.color = Color.argb(90, 255, 255, 255)
        for (k in 0 until 24) {
            val sx = (k * 97 % 100) / 100f * w
            val sy = (k * 53 % 70) / 100f * h
            c.drawCircle(sx, sy, 1.5f + (k % 3), p)
        }

        // предметы
        val s = size()
        text.textSize = s * 0.5f
        text.color = Color.WHITE
        for (i in items) {
            c.save()
            c.rotate(i.rot, i.x, i.y)
            c.drawText(emoji[i.kind], i.x, i.y + s * 0.18f, text)
            c.restore()
        }

        drawRaccoon(c, raccoonX, h - h * 0.07f, s)

        // всплывающие очки
        text.textSize = s * 0.32f
        for (q in popups) {
            text.color = q.color
            text.alpha = (255 * min(1f, q.life * 2f)).toInt()
            c.drawText(q.text, q.x, q.y, text)
        }
        text.alpha = 255

        drawHud(c)
        if (!started) drawTitle(c)
        else if (over) drawOver(c)
        else if (time < 3f) {
            text.color = Color.WHITE
            text.textSize = w * 0.05f
            c.drawText("Води пальцем, лови еду, обходи бомбы!", w / 2, h * 0.55f, text)
        }
    }

    private fun drawRaccoon(c: Canvas, cx: Float, baseY: Float, s: Float) {
        c.save()
        val sq = sin(squash * Math.PI.toFloat()) * 0.12f
        c.scale(1f + sq, 1f - sq, cx, baseY)

        // хвост
        c.save()
        c.rotate(sin(time * 6f) * 9f, cx + s * 0.4f, baseY - s * 0.3f)
        rect.set(cx + s * 0.25f, baseY - s * 0.62f, cx + s * 1.0f, baseY - s * 0.12f)
        p.color = light; c.drawOval(rect, p)
        p.color = grayDark
        for (k in 0 until 3) {
            val x0 = cx + s * (0.42f + k * 0.19f)
            rect.set(x0, baseY - s * 0.60f, x0 + s * 0.08f, baseY - s * 0.14f)
            c.drawOval(rect, p)
        }
        c.restore()

        // тело
        rect.set(cx - s * 0.5f, baseY - s * 0.78f, cx + s * 0.5f, baseY)
        p.color = gray; c.drawOval(rect, p)
        rect.set(cx - s * 0.3f, baseY - s * 0.6f, cx + s * 0.3f, baseY - s * 0.04f)
        p.color = light; c.drawOval(rect, p)

        // лапки
        p.color = grayDark
        rect.set(cx - s * 0.42f, baseY - s * 0.14f, cx - s * 0.12f, baseY + s * 0.02f); c.drawOval(rect, p)
        rect.set(cx + s * 0.12f, baseY - s * 0.14f, cx + s * 0.42f, baseY + s * 0.02f); c.drawOval(rect, p)

        // голова
        val hy = baseY - s * 0.9f
        p.color = gray
        c.drawCircle(cx, hy, s * 0.42f, p)

        // уши
        for (side in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(cx + side * s * 0.14f, hy - s * 0.36f)
            path.lineTo(cx + side * s * 0.42f, hy - s * 0.54f)
            path.lineTo(cx + side * s * 0.40f, hy - s * 0.12f)
            path.close()
            p.color = gray; c.drawPath(path, p)
            path.reset()
            path.moveTo(cx + side * s * 0.2f, hy - s * 0.36f)
            path.lineTo(cx + side * s * 0.38f, hy - s * 0.46f)
            path.lineTo(cx + side * s * 0.37f, hy - s * 0.2f)
            path.close()
            p.color = grayDark; c.drawPath(path, p)
        }

        // маска
        rect.set(cx - s * 0.40f, hy - s * 0.14f, cx + s * 0.40f, hy + s * 0.12f)
        p.color = grayDark; c.drawOval(rect, p)

        // глаза
        for (side in intArrayOf(-1, 1)) {
            p.color = Color.WHITE
            c.drawCircle(cx + side * s * 0.17f, hy - s * 0.02f, s * 0.075f, p)
            p.color = Color.BLACK
            val look = ((targetX - cx) / w).coerceIn(-1f, 1f) * s * 0.02f
            c.drawCircle(cx + side * s * 0.17f + look, hy - s * 0.02f, s * 0.04f, p)
        }

        // мордочка и нос
        rect.set(cx - s * 0.16f, hy + s * 0.08f, cx + s * 0.16f, hy + s * 0.30f)
        p.color = light; c.drawOval(rect, p)
        p.color = Color.BLACK
        c.drawCircle(cx, hy + s * 0.14f, s * 0.05f, p)
        c.restore()
    }

    private fun drawHud(c: Canvas) {
        text.color = Color.WHITE
        text.textSize = w * 0.13f
        c.drawText(score.toString(), w / 2, h * 0.09f, text)
        text.textSize = w * 0.04f
        c.drawText("Рекорд: $best", w / 2, h * 0.125f, text)

        text.textSize = w * 0.07f
        var hearts = ""
        for (k in 0 until lives) hearts += "❤️"
        text.textAlign = Paint.Align.LEFT
        c.drawText(hearts, w * 0.04f, h * 0.07f, text)
        text.textAlign = Paint.Align.CENTER

        if (combo >= 3) {
            text.color = Color.parseColor("#FFE066")
            text.textSize = w * 0.05f
            c.drawText("комбо $combo  x${1 + combo / 5}", w / 2, h * 0.165f, text)
        }
    }

    private fun drawTitle(c: Canvas) {
        p.color = Color.argb(120, 0, 0, 0)
        c.drawRect(0f, 0f, w, h, p)
        text.color = Color.WHITE
        text.textSize = min(w, h * 1.4f) * 0.12f
        c.drawText("Енот-ловец", w / 2, h * 0.30f, text)
        text.textSize = min(w, h * 1.4f) * 0.045f
        c.drawText("Лови еду \uD83D\uDC1F\uD83C\uDF4E\u2B50, обходи бомбы \uD83D\uDCA3", w / 2, h * 0.40f, text)
        c.drawText("Нажми на экран, чтобы начать", w / 2, h * 0.50f, text)
    }

    private fun drawOver(c: Canvas) {
        p.color = Color.argb(170, 0, 0, 0)
        c.drawRect(0f, 0f, w, h, p)
        text.color = Color.WHITE
        text.textSize = w * 0.11f
        c.drawText("Игра окончена", w / 2, h * 0.38f, text)
        text.textSize = w * 0.08f
        c.drawText("Очки: $score", w / 2, h * 0.47f, text)
        text.color = Color.parseColor("#FFE066")
        text.textSize = w * 0.055f
        c.drawText(if (score >= best && score > 0) "Новый рекорд!" else "Рекорд: $best", w / 2, h * 0.53f, text)
        if (overTime > 0.7f) {
            text.color = Color.WHITE
            text.textSize = w * 0.05f
            c.drawText("Нажми, чтобы сыграть снова", w / 2, h * 0.65f, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!started) restart()
                else if (over && overTime > 0.7f) restart()
                targetX = e.x
            }
            MotionEvent.ACTION_MOVE -> targetX = e.x
        }
        return true
    }
}
