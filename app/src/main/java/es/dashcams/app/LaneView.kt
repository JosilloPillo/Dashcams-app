package es.dashcams.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Los "carriles" de la guía de estilo: dos líneas discontinuas amarillas que se mueven
 * hacia la izquierda como una carretera (la de abajo más rápida que la de arriba).
 */
class LaneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFBB80C.toInt()
        style = Paint.Style.FILL
    }

    // Proporciones de la guía (raya 120 px, hueco 100 px en un vídeo de 1080 px de ancho)
    private val dash = 40f * density
    private val gap = 33f * density
    private val thickness = 5f * density
    private val speedBack = 160f * density   // px por segundo
    private val speedFront = 260f * density

    private var startTime = SystemClock.uptimeMillis()

    /** Si es false, los carriles se quedan quietos (para no gastar batería). */
    var running: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = if (running) (SystemClock.uptimeMillis() - startTime) / 1000f else 0f
        drawLane(canvas, height * 0.3f, t * speedBack, 110)
        drawLane(canvas, height * 0.75f, t * speedFront, 255)
        if (running && isShown) postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    private fun drawLane(canvas: Canvas, y: Float, offset: Float, alpha: Int) {
        paint.alpha = alpha
        val period = dash + gap
        var x = -(offset % period)
        val half = thickness / 2f
        while (x < width) {
            canvas.drawRoundRect(x, y - half, x + dash, y + half, half, half, paint)
            x += period
        }
    }
}
