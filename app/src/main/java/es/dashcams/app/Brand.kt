package es.dashcams.app

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.roundToInt

/**
 * Piezas de la guía de estilo de Dashcams España:
 *  - la palabra clave de cada título con el degradado amarillo,
 *  - la marca «dashcams.es» («dashcams» en degradado, «.es» en blanco),
 *  - las tres curvas de movimiento: e() (entrada), pop() (rebote) y eio() (desplazamientos).
 */
object Brand {

    /** linear-gradient(120deg, #ffd84a 0%, #fbb80c 48%, #f68b1f 100%) */
    private val GRADIENT = intArrayOf(0xFFFFD84A.toInt(), 0xFFFBB80C.toInt(), 0xFFF68B1F.toInt())
    private val STOPS = floatArrayOf(0f, 0.48f, 1f)

    /** Devuelve [text] con [keyword] pintada con el degradado amarillo. */
    fun highlight(text: String, keyword: String): CharSequence {
        val i = text.indexOf(keyword)
        if (i < 0 || keyword.isEmpty()) return text
        return SpannableString(text).apply {
            setSpan(GradientTextSpan(GRADIENT, STOPS), i, i + keyword.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun wordmark(): CharSequence = highlight("dashcams.es", "dashcams")

    /** Entrada de texto o tarjeta: aparece desplazándose [fromDp] (positivo = desde abajo). Curva e(). */
    fun enter(view: View, delayMs: Long = 0, fromDp: Float = 24f, durationMs: Long = 500) {
        val d = view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationY = fromDp * d
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(durationMs)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    /** Rebote para el logo, botones, opciones y avisos. Curva pop(). */
    fun pop(view: View, delayMs: Long = 0, fromRightDp: Float = 0f) {
        val d = view.resources.displayMetrics.density
        view.alpha = 0f
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        view.translationX = fromRightDp * d
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .setStartDelay(delayMs)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()
    }

    /** Pequeño rebote al pulsar algo importante (por ejemplo GUARDAR). */
    fun bump(view: View) {
        view.animate().cancel()
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(0)
            .setDuration(380)
            .setInterpolator(OvershootInterpolator(3f))
            .start()
    }
}

/** Pinta un trozo de texto con un degradado (para la palabra clave de los títulos). */
class GradientTextSpan(
    private val colors: IntArray,
    private val positions: FloatArray,
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        if (fm != null) {
            val p = paint.fontMetricsInt
            fm.ascent = p.ascent
            fm.descent = p.descent
            fm.top = p.top
            fm.bottom = p.bottom
        }
        return paint.measureText(text, start, end).roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val width = paint.measureText(text, start, end)
        val previous = paint.shader
        // 120º aproximados: casi horizontal, bajando un poco hacia la derecha
        paint.shader = LinearGradient(
            x, top.toFloat(), x + width, bottom.toFloat(),
            colors, positions, Shader.TileMode.CLAMP
        )
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
        paint.shader = previous
    }
}
