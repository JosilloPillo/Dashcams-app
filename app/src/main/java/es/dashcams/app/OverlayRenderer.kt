package es.dashcams.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.camera.effects.Frame
import androidx.core.content.res.ResourcesCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dibuja encima de cada fotograma del vídeo, con el estilo de la guía:
 *  - abajo a la izquierda, una pastilla azul marino translúcida con la fecha y hora (siempre),
 *    la velocidad y las coordenadas (opcionales);
 *  - abajo a la derecha, el escudo y «dashcams.es» con «dashcams» en degradado amarillo (opcional).
 *
 * Se llama desde el hilo de la cámara, unas 30 veces por segundo.
 */
class OverlayRenderer(context: Context) {

    private val semibold: Typeface =
        ResourcesCompat.getFont(context, R.font.public_sans_semibold) ?: Typeface.DEFAULT_BOLD
    private val barlow: Typeface =
        ResourcesCompat.getFont(context, R.font.barlow_condensed_bold) ?: Typeface.DEFAULT_BOLD
    private val escudo: Bitmap? = try {
        BitmapFactory.decodeResource(context.resources, R.drawable.escudo)
    } catch (e: Exception) {
        null
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = semibold
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xA612132A.toInt() // azul marino profundo al 65 %
    }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = barlow
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.US)
    private var lastSecond = -1L
    private var timeText = ""

    fun draw(frame: Frame): Boolean {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // El fotograma llega en la orientación del sensor: giramos el lienzo para dibujar "derecho".
        val bw = frame.size.width.toFloat()
        val bh = frame.size.height.toFloat()
        canvas.save()
        val (w, h) = when (frame.rotationDegrees) {
            90 -> {
                canvas.translate(0f, bh)
                canvas.rotate(-90f)
                bh to bw
            }
            180 -> {
                canvas.translate(bw, bh)
                canvas.rotate(-180f)
                bw to bh
            }
            270 -> {
                canvas.translate(bw, 0f)
                canvas.rotate(-270f)
                bh to bw
            }
            else -> bw to bh
        }
        drawUpright(canvas, w, h)
        canvas.restore()
        return true
    }

    private fun drawUpright(canvas: Canvas, w: Float, h: Float) {
        val s = minOf(w, h) / 1080f   // todo está pensado para un vídeo de 1080 px de alto
        val margin = 36f * s
        val pad = 22f * s

        // Fecha y hora: se formatean una vez por segundo
        val now = System.currentTimeMillis()
        val second = now / 1000L
        if (second != lastSecond) {
            lastSecond = second
            timeText = dateFormat.format(Date(now))
        }

        val lines = ArrayList<String>(2)
        lines += if (Telemetry.showSpeed) timeText + "   " + Telemetry.speedText() else timeText
        if (Telemetry.showCoords) lines += Telemetry.coordsText()

        textPaint.textSize = 34f * s
        textPaint.setShadowLayer(3f * s, 0f, 1.5f * s, 0x80000000.toInt())
        val lineHeight = textPaint.textSize * 1.32f
        var textWidth = 0f
        for (line in lines) textWidth = maxOf(textWidth, textPaint.measureText(line))

        val boxW = textWidth + pad * 2
        val boxH = lineHeight * lines.size + pad
        val left = margin
        val bottom = h - margin
        val top = bottom - boxH
        rect.set(left, top, left + boxW, bottom)
        canvas.drawRoundRect(rect, 18f * s, 18f * s, pillPaint)

        var baseline = top + pad / 2 - textPaint.ascent() + (lineHeight - textPaint.textSize) / 2
        for (line in lines) {
            canvas.drawText(line, left + pad, baseline, textPaint)
            baseline += lineHeight
        }

        if (Telemetry.showBrand) drawBrand(canvas, w, h, s, margin)
    }

    /** Escudo + «dashcams.es» abajo a la derecha. */
    private fun drawBrand(canvas: Canvas, w: Float, h: Float, s: Float, margin: Float) {
        brandPaint.textSize = 54f * s
        brandPaint.setShadowLayer(5f * s, 0f, 2f * s, 0x99000000.toInt())
        val part1 = "dashcams"
        val part2 = ".es"
        val w1 = brandPaint.measureText(part1)
        val w2 = brandPaint.measureText(part2)

        val iconH = 72f * s
        val iconW = escudo?.let { iconH * it.width / it.height } ?: 0f
        val gap = if (escudo != null) 12f * s else 0f
        val total = iconW + gap + w1 + w2

        val x0 = w - margin - total
        val iconTop = h - margin - iconH
        escudo?.let {
            rect.set(x0, iconTop, x0 + iconW, iconTop + iconH)
            canvas.drawBitmap(it, null, rect, bitmapPaint)
        }

        val textX = x0 + iconW + gap
        val baseline = iconTop + iconH / 2 - (brandPaint.ascent() + brandPaint.descent()) / 2

        // «dashcams» con el degradado amarillo
        brandPaint.shader = LinearGradient(
            textX, baseline + brandPaint.ascent(), textX + w1, baseline,
            intArrayOf(0xFFFFD84A.toInt(), 0xFFFBB80C.toInt(), 0xFFF68B1F.toInt()),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawText(part1, textX, baseline, brandPaint)
        // «.es» en blanco
        brandPaint.shader = null
        canvas.drawText(part2, textX + w1, baseline, brandPaint)
    }
}
