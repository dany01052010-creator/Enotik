package com.modhelper.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** Чтение текста со скриншота прямо на телефоне (Tesseract, русский + английский). Без интернета и ключей. */
object Ocr {
    private var api: TessBaseAPI? = null

    private fun ensureData(): String {
        val ctx = Hub.ctx
        val base = File(ctx.filesDir, "ocr")
        val dir = File(base, "tessdata")
        dir.mkdirs()
        for (n in listOf("rus.traineddata", "eng.traineddata")) {
            val f = File(dir, n)
            if (!f.exists() || f.length() < 100_000) {
                try {
                    ctx.assets.open("tessdata/$n").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                } catch (e: Exception) {
                    f.delete()
                    throw IllegalStateException("В приложении нет файла языка $n (" + e.message + ")")
                }
            }
        }
        return base.absolutePath
    }

    /** Делает картинку удобнее для распознавания: серая, текст тёмный на светлом. */
    private fun prepare(src: Bitmap): Bitmap {
        val maxSide = 3000f
        val s = minOf(1f, maxSide / maxOf(src.width, src.height).toFloat())
        val w = maxOf(2, (src.width * s).toInt())
        val h = maxOf(2, (src.height * s).toInt())
        // средняя яркость по сетке
        var sum = 0L
        var n = 0
        val stepX = maxOf(1, src.width / 40)
        val stepY = maxOf(1, src.height / 40)
        var y = 0
        while (y < src.height) {
            var x = 0
            while (x < src.width) {
                val p = src.getPixel(x, y)
                sum += (((p shr 16) and 255) * 3 + ((p shr 8) and 255) * 6 + (p and 255)) / 10
                n++
                x += stepX
            }
            y += stepY
        }
        val dark = n > 0 && sum / n < 120
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        if (dark) {
            cm.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, w, h), paint)
        return out
    }

    @Synchronized
    fun read(bmp: Bitmap): String {
        val path = ensureData()
        var t = api
        if (t == null) {
            val n = TessBaseAPI()
            if (!n.init(path, "rus+eng")) {
                n.recycle()
                throw IllegalStateException("не загрузились данные распознавания текста")
            }
            n.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api = n
            t = n
        }
        val prepared = prepare(bmp)
        t.setImage(prepared)
        val text = t.getUTF8Text() ?: ""
        t.clear()
        prepared.recycle()
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
