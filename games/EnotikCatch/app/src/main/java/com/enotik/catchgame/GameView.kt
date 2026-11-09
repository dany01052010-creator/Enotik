package com.enotik.catchgame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Енот-бегун: 3 полосы, перспектива (объекты приближаются), шкала голода.
 * Оптимизация: ни одного выделения памяти в кадре (все Paint/Path/RectF создаются один раз),
 * шейдеры создаются один раз, три уровня качества, пауза не грузит процессор.
 */
class GameView(ctx: Context, private val host: Host) : View(ctx) {

    interface Host {
        fun onGameFinished(score: Int, meters: Int)
        fun exitToMenu()
        fun replay()
    }

    private class Obj(val at: Float, val lane: Int, val type: Int) {
        var done = false
        var cleared = false
        var counted = false
    }

    private class Banner(val text: String, var life: Float)

    // ---------------------------------------------------------------- настройки

    private val q = Store.quality          // 0 низкое, 1 среднее, 2 высокое
    private val zFar = if (q == 0) 70f else 95f

    // ---------------------------------------------------------------- размеры

    private var w = 0f
    private var h = 0f
    private var cx = 0f
    private var horizonY = 0f
    private var groundY = 0f
    private var laneW = 0f
    private var unit = 0f

    // ---------------------------------------------------------------- состояние игры

    private var state = 0                  // 0 игра, 1 пауза, 2 конец
    private var time = 0f
    private var dist = 0f
    private var speed = 14f
    private var hunger = 100f
    private var coins = 0
    private var eaten = 0
    private var jumps = 0
    private var laneTarget = 0
    private var laneX = 0f
    private var jumpT = -1f
    private var invuln = 0f
    private var shake = 0f
    private var nextRowDist = 30f
    private var overTime = 0f
    private var pendingMeters = 0
    private var lastMeterInt = 0
    private var last = System.nanoTime()

    private val objs = ArrayList<Obj>()
    private val banners = ArrayList<Banner>()
    private val laneBuf = intArrayOf(-1, 0, 1)

    // ---------------------------------------------------------------- кисти (создаются один раз)

    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()
    private val rect = RectF()
    private val pauseRect = RectF()
    private val btnA = RectF()
    private val btnB = RectF()

    private val skyPaint = Paint()
    private var skyShader: SkyShader? = null
    private val vignette = Paint()
    private val haze = Paint()
    private val groundPaint = Paint()

    // единичные радиальные градиенты: рисуются через translate/scale, без выделения памяти
    private val glowGold = unitGlow(0xFFFFD54A.toInt())
    private val glowSun = unitGlow(0xFFFFE9A8.toInt())
    private val glowWhite = unitGlow(0xFFFFFFFF.toInt())
    private val shadowPaint = unitGlow(0xCC000000.toInt())

    private val front = intArrayOf(
        Color.parseColor("#5C4B8B"), Color.parseColor("#3E6F8E"), Color.parseColor("#8E4B6B"),
        Color.parseColor("#4B7F6B"), Color.parseColor("#7A5B3E"), Color.parseColor("#4F5A9E")
    )
    private val side = IntArray(front.size) { darken(front[it], 0.62f) }

    private val gray = Color.parseColor("#8E9AAF")
    private val grayLight = Color.parseColor("#B9C4D6")
    private val grayDark = Color.parseColor("#3A3F52")
    private val cream = Color.parseColor("#E9EEF5")

    private val foods = arrayOf("🐟", "🍎", "🍗")

    private fun unitGlow(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f, intArrayOf(color, color and 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun darken(c: Int, f: Float) =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    private fun lighten(c: Int, a: Int) =
        Color.rgb(min(255, Color.red(c) + a), min(255, Color.green(c) + a), min(255, Color.blue(c) + a))

    private fun rnd(n: Int): Float {
        var x = n * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return ((x and 0x7fffffff) % 1000) / 1000f
    }

    // ---------------------------------------------------------------- размеры экрана

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw.toFloat()
        h = nh.toFloat()
        cx = w / 2
        horizonY = h * 0.34f
        groundY = h * 0.85f
        laneW = min(w * 0.27f, (groundY - horizonY) * 0.75f)
        unit = min(w, h) * 0.05f

        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, horizonY,
            intArrayOf(Color.parseColor("#2B1B5A"), Color.parseColor("#E4572E"), Color.parseColor("#FFC857")),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        groundPaint.shader = LinearGradient(
            0f, horizonY, 0f, h,
            Color.parseColor("#2E1F4D"), Color.parseColor("#17323A"), Shader.TileMode.CLAMP
        )
        // дымка у горизонта
        haze.shader = LinearGradient(
            0f, horizonY, 0f, horizonY + (groundY - horizonY) * 0.55f,
            Color.parseColor("#F0FFB27A"), Color.parseColor("#00FFB27A"), Shader.TileMode.CLAMP
        )
        // виньетка
        vignette.shader = RadialGradient(
            cx, h / 2, max(w, h) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, 0xA0000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        skyShader = null
        if (q >= 2 && Build.VERSION.SDK_INT >= 33) {
            try {
                skyShader = SkyShader(w, horizonY)
            } catch (t: Throwable) {
                skyShader = null   // если шейдер не принялся — обычный градиент
            }
        }
        val pw = unit * 2.2f
        pauseRect.set(w - pw - unit * 0.6f, unit * 0.7f, w - unit * 0.6f, unit * 0.7f + pw)
    }

    // ---------------------------------------------------------------- управление

    private var sx = 0f
    private var sy = 0f
    private var consumed = false
    private var moved = false

    private fun jump() {
        if (jumpT < 0f) jumpT = 0f
    }

    private fun lift(): Float = if (jumpT < 0f) 0f else 4f * jumpT * (1f - jumpT)

    fun isPlaying() = state == 0

    fun pause() {
        if (state == 0) {
            state = 1
            invalidate()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = e.x; sy = e.y; consumed = false; moved = false
            }
            MotionEvent.ACTION_MOVE -> if (state == 0 && !consumed) {
                val dx = e.x - sx
                val dy = e.y - sy
                val thr = unit * 1.4f
                if (abs(dx) > thr && abs(dx) > abs(dy)) {
                    laneTarget = (laneTarget + if (dx > 0) 1 else -1).coerceIn(-1, 1)
                    consumed = true
                } else if (dy < -thr && abs(dy) > abs(dx)) {
                    jump()
                    consumed = true
                }
                if (abs(dx) > thr * 0.4f || abs(dy) > thr * 0.4f) moved = true
            }
            MotionEvent.ACTION_UP -> when (state) {
                0 -> if (pauseRect.contains(e.x, e.y)) pause() else if (!consumed && !moved) jump()
                1 -> if (btnA.contains(e.x, e.y)) {
                    state = 0
                    last = System.nanoTime()
                    invalidate()
                } else if (btnB.contains(e.x, e.y)) host.exitToMenu()
                2 -> if (overTime > 0.6f) {
                    if (btnA.contains(e.x, e.y)) host.replay()
                    else if (btnB.contains(e.x, e.y)) host.exitToMenu()
                }
            }
        }
        return true
    }

    // ---------------------------------------------------------------- игровая логика

    private fun score() = dist.toInt() + eaten * 8 + coins * 2

    private fun showDone(list: List<String>) {
        for (s in list) banners.add(Banner("✅ $s", 3.2f))
    }

    private fun flushMeters() {
        if (pendingMeters > 0) {
            showDone(Missions.add(MKind.RUN_TOTAL, pendingMeters))
            pendingMeters = 0
        }
        showDone(Missions.add(MKind.SINGLE_RUN, lastMeterInt, true))
    }

    private fun spawnRow(at: Float) {
        for (i in 2 downTo 1) {
            val j = Random.nextInt(i + 1)
            val t = laneBuf[i]; laneBuf[i] = laneBuf[j]; laneBuf[j] = t
        }
        val nObs = when {
            at < 70f -> if (Random.nextFloat() < 0.4f) 1 else 0
            at > 250f && Random.nextFloat() < 0.4f -> 2
            else -> if (Random.nextFloat() < 0.85f) 1 else 0
        }
        for (i in 0 until nObs) {
            objs.add(Obj(at, laneBuf[i], if (Random.nextFloat() < 0.5f) 2 else 3))
        }
        val free = laneBuf[nObs]
        val foodChance = if (hunger < 40f) 0.9f else if (hunger < 70f) 0.55f else 0.3f
        if (Random.nextFloat() < foodChance) {
            objs.add(Obj(at, free, 1))
        } else {
            objs.add(Obj(at, free, 0))
            objs.add(Obj(at + 2.5f, free, 0))
            objs.add(Obj(at + 5f, free, 0))
        }
        if (nObs < 2 && Random.nextFloat() < 0.35f) objs.add(Obj(at, laneBuf[nObs + 1], 0))
    }

    private fun hit() {
        if (invuln > 0f) return
        hunger -= 22f
        invuln = 1.4f
        shake = 0.35f
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (hunger <= 0f) finish()
    }

    private fun finish() {
        if (state == 2) return
        hunger = max(0f, hunger)
        state = 2
        overTime = 0f
        flushMeters()
        Store.coins = Store.coins + coins
        host.onGameFinished(score(), dist.toInt())
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun update(dt: Float) {
        time += dt
        speed = min(34f, 14f + dist * 0.012f)
        dist += (if (invuln > 1.0f) speed * 0.6f else speed) * dt
        hunger -= (3.4f + speed * 0.06f) * dt
        if (hunger <= 0f) {
            finish()
            return
        }
        laneX += (laneTarget - laneX) * min(1f, dt * 12f)
        if (jumpT >= 0f) {
            jumpT += dt / 0.78f
            if (jumpT >= 1f) jumpT = -1f
        }
        invuln = max(0f, invuln - dt)
        shake = max(0f, shake - dt)

        val mi = dist.toInt()
        if (mi > lastMeterInt) {
            pendingMeters += mi - lastMeterInt
            lastMeterInt = mi
            if (pendingMeters >= 25) flushMeters()
        }

        while (nextRowDist - dist < zFar) {
            spawnRow(nextRowDist)
            nextRowDist += speed * 0.55f + Random.nextFloat() * speed * 0.25f
        }

        val lf = lift()
        var i = objs.size - 1
        while (i >= 0) {
            val o = objs[i]
            val z = o.at - dist
            if (!o.done && z < 0.9f && z > -0.9f && abs(o.lane - laneX) < 0.55f) {
                when (o.type) {
                    0 -> {
                        o.done = true
                        coins++
                        showDone(Missions.add(MKind.COINS, 1))
                    }
                    1 -> {
                        o.done = true
                        eaten++
                        hunger = min(100f, hunger + 20f)
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        showDone(Missions.add(MKind.FOOD, 1))
                    }
                    2 -> if (lf >= 0.4f) o.cleared = true else {
                        o.done = true
                        hit()
                    }
                    else -> {
                        o.done = true
                        hit()
                    }
                }
            }
            if (z < -2f) {
                if (o.cleared && !o.counted) {
                    jumps++
                    showDone(Missions.add(MKind.JUMPS, 1))
                }
                objs.removeAt(i)
            }
            i--
        }

        var b = banners.size - 1
        while (b >= 0) {
            banners[b].life -= dt
            if (banners[b].life <= 0f) banners.removeAt(b)
            b--
        }
    }

    // ---------------------------------------------------------------- главный цикл

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        var dt = (now - last) / 1e9f
        last = now
        if (dt > 0.05f) dt = 0.05f
        if (w <= 0f) {
            postInvalidateOnAnimation()
            return
        }
        when (state) {
            0 -> update(dt)
            2 -> {
                overTime += dt
                time += dt
            }
        }
        drawScene(c)
        // на паузе кадры не перерисовываем — экономим батарею
        if (state != 1) postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- перспектива

    private fun persp(z: Float): Float = 1f / (1f + max(z, -4f) / 9f)
    private fun gy(p: Float): Float = horizonY + (groundY - horizonY) * p
    private fun lx(lane: Float, p: Float): Float = cx + lane * laneW * p

    private fun glow(c: Canvas, paint: Paint, x: Float, y: Float, rx: Float, ry: Float, alpha: Int) {
        c.save()
        c.translate(x, y)
        c.scale(rx, ry)
        paint.alpha = alpha
        c.drawCircle(0f, 0f, 1f, paint)
        c.restore()
    }

    // ---------------------------------------------------------------- рисование сцены

    private fun drawScene(c: Canvas) {
        c.save()
        if (shake > 0f) {
            c.translate((Random.nextFloat() - 0.5f) * unit * 1.8f * shake, (Random.nextFloat() - 0.5f) * unit * 1.8f * shake)
        }
        drawSky(c)
        drawGround(c)
        drawBuildings(c)
        if (q >= 1) c.drawRect(0f, horizonY, w, horizonY + (groundY - horizonY) * 0.55f, haze)

        // дальние объекты -> енот -> ближние объекты
        var i = objs.size - 1
        while (i >= 0) {
            val o = objs[i]
            if (o.at - dist > 0f) drawObj(c, o)
            i--
        }
        drawRaccoon(c)
        i = objs.size - 1
        while (i >= 0) {
            val o = objs[i]
            if (o.at - dist <= 0f) drawObj(c, o)
            i--
        }
        if (q >= 2 && speed > 22f && state == 0) drawSpeedLines(c)
        c.restore()

        if (q >= 1) c.drawRect(0f, 0f, w, h, vignette)
        drawHud(c)
        when (state) {
            1 -> drawPause(c)
            2 -> drawOver(c)
        }
    }

    private fun drawSky(c: Canvas) {
        val sk = skyShader
        if (sk != null) {
            sk.update(time)
            c.drawRect(0f, 0f, w, horizonY, sk.paint)
        } else {
            c.drawRect(0f, 0f, w, horizonY, skyPaint)
        }
        // солнце со свечением
        val sunY = horizonY - unit * 1.2f
        glow(c, glowSun, cx, sunY, unit * 9f, unit * 9f, 150)
        fp.color = Color.parseColor("#FFF3C4")
        c.drawCircle(cx, sunY, unit * 2.6f, fp)

        // горы (параллакс)
        val layers = if (q == 0) 1 else 2
        for (layer in 0 until layers) {
            path.reset()
            path.moveTo(0f, horizonY)
            val amp = unit * (3.4f - layer * 1.3f)
            var x = 0f
            while (x <= w + 24f) {
                val y = horizonY - amp * (0.5f + 0.5f * sin(x * 0.006f + layer * 2.1f + dist * 0.0004f * (layer + 1)))
                path.lineTo(x, y)
                x += 24f
            }
            path.lineTo(w, horizonY)
            path.close()
            fp.color = if (layer == 0) Color.parseColor("#3B2A6B") else Color.parseColor("#2A1F52")
            c.drawPath(path, fp)
        }
    }

    private fun quad(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float, color: Int) {
        path.reset()
        path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.lineTo(x4, y4)
        path.close()
        fp.color = color
        c.drawPath(path, fp)
    }

    private fun drawGround(c: Canvas) {
        c.drawRect(0f, horizonY, w, h, groundPaint)

        val half = 1.6f
        val pb = (h - horizonY) / (groundY - horizonY)
        quad(c, cx - 3f, horizonY, cx + 3f, horizonY, cx + laneW * half * pb, h, cx - laneW * half * pb, h,
            Color.parseColor("#3A3F52"))

        // полосы дороги и бордюры: двигаются навстречу — ощущение скорости
        val bandLen = 4f
        val shift = dist % bandLen
        val n = (zFar / bandLen).toInt() + 2
        for (k in n downTo -1) {
            val z0 = max(k * bandLen - shift, -3.5f)
            val z1 = k * bandLen - shift + bandLen
            if (z1 <= -3.5f) continue
            val p0 = persp(z0)
            val p1 = persp(z1)
            val idx = floor((dist + k * bandLen - shift) / bandLen).toInt()
            val even = idx and 1 == 0
            if (even) {
                quad(c, lx(-half, p0), gy(p0), lx(half, p0), gy(p0), lx(half, p1), gy(p1), lx(-half, p1), gy(p1),
                    Color.parseColor("#454B63"))
            }
            val curb = if (even) Color.parseColor("#E04B4B") else Color.WHITE
            quad(c, lx(half, p0), gy(p0), lx(half + 0.12f, p0), gy(p0), lx(half + 0.12f, p1), gy(p1), lx(half, p1), gy(p1), curb)
            quad(c, lx(-half - 0.12f, p0), gy(p0), lx(-half, p0), gy(p0), lx(-half, p1), gy(p1), lx(-half - 0.12f, p1), gy(p1), curb)
        }

        // разметка полос
        val dash = 8f
        val dshift = dist % dash
        val dn = (zFar / dash).toInt() + 1
        for (k in dn downTo -1) {
            val z0 = max(k * dash - dshift, -3.5f)
            val z1 = k * dash - dshift + 3.5f
            if (z1 <= -3.5f) continue
            val p0 = persp(z0)
            val p1 = persp(z1)
            val lw0 = 0.03f
            for (bx in 0..1) {
                val b = if (bx == 0) -0.5f else 0.5f
                quad(c, lx(b - lw0, p0), gy(p0), lx(b + lw0, p0), gy(p0), lx(b + lw0, p1), gy(p1), lx(b - lw0, p1), gy(p1),
                    Color.argb(190, 255, 255, 255))
            }
        }
    }

    private fun drawBuildings(c: Canvas) {
        val step = if (q == 0) 16f else 12f
        val depth = 8f
        val n0 = floor(dist / step).toInt()
        val cnt = (zFar / step).toInt() + 2
        for (i in cnt downTo 0) {
            val k = n0 + i
            val z = k * step - dist
            if (z < -3f || z > zFar) continue
            drawBuilding(c, k, -1, z, depth)
            drawBuilding(c, k, 1, z, depth)
        }
    }

    private fun drawBuilding(c: Canvas, k: Int, sd: Int, z: Float, depth: Float) {
        val r = rnd(k * 2 + (if (sd > 0) 1 else 0))
        val hh = laneW * (1.8f + 3.2f * r)
        val bwL = 1.2f + rnd(k + 99) * 1.0f
        val inner = 1.95f
        val p = persp(z)
        val p2 = persp(z + depth)
        val xi = cx + sd * laneW * inner * p
        val xo = cx + sd * laneW * (inner + bwL) * p
        val left = min(xi, xo)
        val right = max(xi, xo)
        if (right < 0f || left > w) return
        val top = gy(p) - hh * p
        val bot = gy(p)
        val xi2 = cx + sd * laneW * inner * p2
        val top2 = gy(p2) - hh * p2
        val bot2 = gy(p2)
        val ci = (k * 3 + (if (sd > 0) 1 else 0)) % front.size
        val ci2 = if (ci < 0) ci + front.size else ci

        // боковая грань (к дороге) — объём
        quad(c, xi, top, xi2, top2, xi2, bot2, xi, bot, side[ci2])
        // лицевая грань
        fp.color = front[ci2]
        rect.set(left, top, right, bot)
        c.drawRect(rect, fp)
        // верхняя кромка для объёма
        fp.color = lighten(front[ci2], 40)
        c.drawRect(left, top, right, top + max(2f, 5f * p), fp)

        // окна (только вблизи и не на низком качестве)
        if (q >= 1 && p > 0.14f) {
            val ww = right - left
            val wh = bot - top
            for (rr in 0 until 5) {
                for (cc in 0 until 3) {
                    if (rnd(k * 31 + rr * 7 + cc + sd * 13) < 0.45f) continue
                    val wx = left + (cc + 0.5f) / 3f * ww
                    val wy = top + (rr + 0.7f) / 5.6f * wh
                    fp.color = Color.argb(230, 255, 220, 120)
                    c.drawRect(wx - ww * 0.07f, wy - wh * 0.03f, wx + ww * 0.07f, wy + wh * 0.03f, fp)
                }
            }
        }
    }

    private fun drawBox(c: Canvas, x: Float, gyv: Float, bw: Float, bh: Float, color: Int) {
        val x1 = x - bw / 2
        val x2 = x + bw / 2
        val top = gyv - bh
        val f = 0.9f
        val bx1 = cx + (x1 - cx) * f
        val bx2 = cx + (x2 - cx) * f
        val btop = horizonY + (top - horizonY) * f
        val bgy = horizonY + (gyv - horizonY) * f
        // боковая грань, обращённая к центру
        if (x > cx + 1f) quad(c, x1, top, x1, gyv, bx1, bgy, bx1, btop, darken(color, 0.6f))
        else if (x < cx - 1f) quad(c, x2, top, x2, gyv, bx2, bgy, bx2, btop, darken(color, 0.6f))
        // верх
        quad(c, x1, top, x2, top, bx2, btop, bx1, btop, lighten(color, 45))
        // лицо
        fp.color = color
        c.drawRect(x1, top, x2, gyv, fp)
    }

    private fun drawObj(c: Canvas, o: Obj) {
        if (o.done && o.type <= 1) return
        val z = o.at - dist
        val p = persp(z)
        val x = lx(o.lane.toFloat(), p)
        val y = gy(p)
        if (y < horizonY) return
        // мягкая тень (шейдер)
        val sw = laneW * (if (o.type >= 2) 0.55f else 0.28f) * p
        if (q >= 1) glow(c, shadowPaint, x, y, sw, sw * 0.28f, 150)

        when (o.type) {
            0 -> {
                val r = laneW * 0.17f * p
                val cy = y - laneW * 0.35f * p - abs(sin(time * 3f + o.at)) * laneW * 0.05f * p
                if (q >= 2) glow(c, glowGold, x, cy, r * 2.6f, r * 2.6f, 120)
                val sxs = max(0.15f, abs(cos(time * 5f + o.at)))
                rect.set(x - r * sxs, cy - r, x + r * sxs, cy + r)
                fp.color = Color.parseColor("#E0A800")
                c.drawOval(rect, fp)
                rect.set(x - r * sxs * 0.72f, cy - r * 0.72f, x + r * sxs * 0.72f, cy + r * 0.72f)
                fp.color = Color.parseColor("#FFD54A")
                c.drawOval(rect, fp)
            }
            1 -> {
                val cy = y - laneW * 0.3f * p - abs(sin(time * 4f + o.at)) * laneW * 0.06f * p
                if (q >= 1) glow(c, glowWhite, x, cy, laneW * 0.4f * p, laneW * 0.4f * p, 90)
                tp.textSize = laneW * 0.55f * p
                tp.color = Color.WHITE
                c.drawText(foods[((o.at / 7f).toInt() and 0x7fffffff) % 3], x, cy + laneW * 0.18f * p, tp)
            }
            2 -> {
                // низкий барьер: красно-белый, через него прыгают
                val bw = laneW * 0.9f * p
                val bh = laneW * 0.34f * p
                drawBox(c, x, y, bw, bh, Color.parseColor("#E04B4B"))
                fp.color = Color.WHITE
                val stripes = 4
                for (s in 0 until stripes) {
                    val sx0 = x - bw / 2 + (s * 2 + 0.5f) * bw / (stripes * 2)
                    c.drawRect(sx0, y - bh, sx0 + bw / (stripes * 2), y, fp)
                }
            }
            else -> {
                // высокий контейнер: обходить по полосе
                val bw = laneW * 0.82f * p
                val bh = laneW * 1.0f * p
                drawBox(c, x, y, bw, bh, Color.parseColor("#3E7CB1"))
                fp.color = Color.argb(70, 0, 0, 0)
                for (s in 1..3) c.drawRect(x - bw / 2, y - bh + s * bh / 4f - 1f, x + bw / 2, y - bh + s * bh / 4f + 2f, fp)
            }
        }
    }

    private fun drawSpeedLines(c: Canvas) {
        fp.color = Color.argb(60, 255, 255, 255)
        fp.strokeWidth = max(1.5f, unit * 0.06f)
        for (k in 0 until 14) {
            val a = rnd(k * 17 + (time * 9f).toInt()) * 6.2831f
            val r0 = unit * 6f + rnd(k + 5) * unit * 4f
            val r1 = r0 + unit * (3f + rnd(k + 9) * 6f)
            c.drawLine(cx + cos(a) * r0 * 1.8f, horizonY + sin(a) * r0 * 0.7f + unit * 4f,
                cx + cos(a) * r1 * 1.8f, horizonY + sin(a) * r1 * 0.7f + unit * 4f, fp)
        }
        fp.strokeWidth = 0f
    }

    // ---------------------------------------------------------------- енот

    private fun drawRaccoon(c: Canvas) {
        val lf = lift()
        val x = lx(laneX, 1f)
        val gyv = groundY
        val s = laneW * 0.62f
        if (invuln > 0f && ((time * 12f).toInt() and 1) == 0) return  // мигает после удара

        val ph = time * 15f
        val bob = abs(sin(ph)) * s * 0.05f * (1f - lf)
        val air = lf * laneW * 0.9f
        val by = gyv - air - bob

        // тень
        glow(c, shadowPaint, x, gyv, s * 0.55f * (1f - lf * 0.35f), s * 0.16f * (1f - lf * 0.35f), 170)

        // лапы (бег)
        fp.color = grayDark
        for (sd in 0..1) {
            val sign = if (sd == 0) -1f else 1f
            val l = max(0f, sin(ph + (if (sd == 0) 0f else 3.14159f))) * s * 0.2f * (1f - lf)
            rect.set(x + sign * s * 0.2f - s * 0.12f, by - s * 0.14f - l, x + sign * s * 0.2f + s * 0.12f, by + s * 0.03f - l)
            c.drawOval(rect, fp)
        }

        // тело
        fp.color = gray
        rect.set(x - s * 0.42f, by - s * 0.85f, x + s * 0.42f, by - s * 0.1f)
        c.drawOval(rect, fp)
        // объёмный блик на теле (шейдер)
        glow(c, glowWhite, x - s * 0.12f, by - s * 0.6f, s * 0.3f, s * 0.38f, 70)

        // голова
        val hy = by - s * 1.0f
        fp.color = gray
        c.drawCircle(x, hy, s * 0.35f, fp)
        glow(c, glowWhite, x - s * 0.1f, hy - s * 0.1f, s * 0.26f, s * 0.26f, 80)
        // уши
        for (sd in 0..1) {
            val sign = if (sd == 0) -1f else 1f
            path.reset()
            path.moveTo(x + sign * s * 0.12f, hy - s * 0.28f)
            path.lineTo(x + sign * s * 0.34f, hy - s * 0.52f)
            path.lineTo(x + sign * s * 0.34f, hy - s * 0.08f)
            path.close()
            fp.color = gray
            c.drawPath(path, fp)
            path.reset()
            path.moveTo(x + sign * s * 0.17f, hy - s * 0.28f)
            path.lineTo(x + sign * s * 0.31f, hy - s * 0.44f)
            path.lineTo(x + sign * s * 0.31f, hy - s * 0.16f)
            path.close()
            fp.color = grayDark
            c.drawPath(path, fp)
        }
        // «маска» по бокам головы
        fp.color = grayDark
        rect.set(x - s * 0.36f, hy - s * 0.04f, x - s * 0.18f, hy + s * 0.16f); c.drawOval(rect, fp)
        rect.set(x + s * 0.18f, hy - s * 0.04f, x + s * 0.36f, hy + s * 0.16f); c.drawOval(rect, fp)

        // хвост в полоску — виден сзади
        c.save()
        c.rotate(sin(time * 7f) * 9f, x, by - s * 0.15f)
        rect.set(x - s * 0.2f, by - s * 0.78f, x + s * 0.2f, by - s * 0.05f)
        fp.color = cream
        c.drawOval(rect, fp)
        fp.color = grayDark
        for (b in 0 until 4) {
            val yy = by - s * (0.1f + b * 0.17f)
            rect.set(x - s * 0.19f, yy - s * 0.045f - s * 0.04f, x + s * 0.19f, yy)
            c.drawOval(rect, fp)
        }
        c.restore()
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(c: Canvas) {
        val u = unit
        val top = u * 0.8f
        // шкала голода
        val barX = u * 2.6f
        val barW = min(w * 0.40f, u * 12f)
        val barH = u * 1.0f
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = u * 1.5f
        tp.color = Color.WHITE
        c.drawText("🍖", u * 0.6f, top + barH * 1.05f, tp)
        fp.color = Color.argb(150, 0, 0, 0)
        rect.set(barX, top, barX + barW, top + barH)
        c.drawRoundRect(rect, barH / 2, barH / 2, fp)
        val ratio = (hunger / 100f).coerceIn(0f, 1f)
        val blink = ratio < 0.25f && ((time * 6f).toInt() and 1) == 0
        fp.color = when {
            blink -> Color.parseColor("#FF8A80")
            ratio > 0.5f -> Color.parseColor("#4CAF50")
            ratio > 0.25f -> Color.parseColor("#FFB300")
            else -> Color.parseColor("#F44336")
        }
        val inset = barH * 0.12f
        rect.set(barX + inset, top + inset, barX + inset + (barW - 2 * inset) * ratio, top + barH - inset)
        if (ratio > 0.01f) c.drawRoundRect(rect, barH / 2, barH / 2, fp)
        tp.textSize = u * 0.8f
        c.drawText("Голод", barX + u * 0.5f, top + barH * 0.75f, tp)

        // дистанция
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = u * 1.9f
        tp.color = Color.WHITE
        c.drawText("${dist.toInt()} м", cx, u * 3.4f, tp)
        tp.textSize = u * 0.8f
        tp.color = Color.parseColor("#FFE9A8")
        c.drawText("очки ${score()}", cx, u * 4.4f, tp)

        // монеты и пауза
        tp.textAlign = Paint.Align.RIGHT
        tp.textSize = u * 1.1f
        tp.color = Color.parseColor("#FFD54A")
        c.drawText("🪙 $coins", pauseRect.left - u * 0.5f, top + barH * 1.05f, tp)
        tp.textAlign = Paint.Align.CENTER

        fp.color = Color.argb(150, 0, 0, 0)
        c.drawRoundRect(pauseRect, u * 0.5f, u * 0.5f, fp)
        fp.color = Color.WHITE
        val pw = pauseRect.width()
        c.drawRect(pauseRect.left + pw * 0.3f, pauseRect.top + pw * 0.25f, pauseRect.left + pw * 0.44f, pauseRect.bottom - pw * 0.25f, fp)
        c.drawRect(pauseRect.left + pw * 0.56f, pauseRect.top + pw * 0.25f, pauseRect.left + pw * 0.70f, pauseRect.bottom - pw * 0.25f, fp)

        // баннеры выполненных заданий
        var yy = u * 6.2f
        for (i in banners.indices) {
            val bn = banners[i]
            tp.textSize = u * 0.9f
            val tw = min(w * 0.92f, tp.measureText(bn.text) + u * 2f)
            fp.color = Color.argb((200 * min(1f, bn.life)).toInt(), 30, 110, 60)
            rect.set(cx - tw / 2, yy - u * 1.1f, cx + tw / 2, yy + u * 0.5f)
            c.drawRoundRect(rect, u * 0.6f, u * 0.6f, fp)
            tp.color = Color.WHITE
            c.drawText(bn.text, cx, yy, tp)
            yy += u * 1.9f
        }

        if (state == 0 && time < 4f) {
            tp.textSize = u * 1.0f
            tp.color = Color.WHITE
            c.drawText("Свайп влево/вправо — полоса, вверх или тап — прыжок", cx, h * 0.62f, tp)
            c.drawText("Ешь еду, чтобы не умереть с голоду!", cx, h * 0.62f + u * 1.6f, tp)
        }
    }

    private fun button(c: Canvas, r: RectF, label: String, color: String) {
        fp.color = Color.parseColor(color)
        c.drawRoundRect(r, unit, unit, fp)
        tp.color = Color.WHITE
        tp.textSize = unit * 1.15f
        c.drawText(label, r.centerX(), r.centerY() + unit * 0.4f, tp)
    }

    private fun drawPause(c: Canvas) {
        fp.color = Color.argb(170, 0, 0, 0)
        c.drawRect(0f, 0f, w, h, fp)
        tp.color = Color.WHITE
        tp.textSize = unit * 2.6f
        c.drawText("Пауза", cx, h * 0.33f, tp)
        val bw = min(w * 0.7f, unit * 14f)
        btnA.set(cx - bw / 2, h * 0.42f, cx + bw / 2, h * 0.42f + unit * 3.2f)
        btnB.set(cx - bw / 2, h * 0.42f + unit * 4.2f, cx + bw / 2, h * 0.42f + unit * 7.4f)
        button(c, btnA, "Продолжить", "#2E9E5B")
        button(c, btnB, "В меню", "#6B5B95")
    }

    private fun drawOver(c: Canvas) {
        fp.color = Color.argb(180, 0, 0, 0)
        c.drawRect(0f, 0f, w, h, fp)
        tp.color = Color.WHITE
        tp.textSize = unit * 2.6f
        c.drawText(if (hunger <= 0f) "Енот проголодался!" else "Игра окончена", cx, h * 0.24f, tp)
        tp.textSize = unit * 1.5f
        c.drawText("${dist.toInt()} м   •   ${score()} очков", cx, h * 0.24f + unit * 3f, tp)
        tp.textSize = unit * 1.1f
        tp.color = Color.parseColor("#FFD54A")
        c.drawText("Монеты: +$coins   Съедено: $eaten   Прыжков: $jumps", cx, h * 0.24f + unit * 5f, tp)
        tp.color = Color.parseColor("#FFE9A8")
        c.drawText("Рекорд: ${Store.best}", cx, h * 0.24f + unit * 6.8f, tp)
        if (overTime > 0.6f) {
            val bw = min(w * 0.7f, unit * 14f)
            btnA.set(cx - bw / 2, h * 0.52f, cx + bw / 2, h * 0.52f + unit * 3.2f)
            btnB.set(cx - bw / 2, h * 0.52f + unit * 4.2f, cx + bw / 2, h * 0.52f + unit * 7.4f)
            button(c, btnA, "Ещё раз", "#2E9E5B")
            button(c, btnB, "В меню", "#6B5B95")
        }
    }
}
