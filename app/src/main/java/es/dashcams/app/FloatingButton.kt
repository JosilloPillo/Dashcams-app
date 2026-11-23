package es.dashcams.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Botón flotante con el escudo que aparece encima de otras apps mientras se graba en segundo plano.
 *  - Tocar: guarda un clip de evento.
 *  - Mantener pulsado: vuelve a la app.
 *  - Arrastrar: lo mueves donde no moleste (se recuerda la posición).
 * Necesita el permiso "Mostrar sobre otras apps".
 */
object FloatingButton {

    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    val isShowing: Boolean get() = view != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (view != null || !canShow(context)) return
        val app = context.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val dp = app.resources.displayMetrics.density
        val size = (64 * dp).toInt()
        val prefs = app.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE612132A.toInt())
            setStroke((3 * dp).toInt(), ContextCompat.getColor(app, R.color.rojo_rec))
        }
        val root = FrameLayout(app).apply {
            background = ring
            elevation = 8 * dp
            contentDescription = "Guardar clip"
        }
        val icon = ImageView(app).apply {
            setImageResource(AppIcons.current(app).shield)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val pad = (12 * dp).toInt()
        root.addView(icon, FrameLayout.LayoutParams(size - 2 * pad, size - 2 * pad, Gravity.CENTER))

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, (16 * dp).toInt())
            y = prefs.getInt(KEY_Y, (160 * dp).toInt())
        }

        val slop = ViewConfiguration.get(app).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            openApp(app)
        }

        root.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(v, params)
                        } catch (ignored: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else if (!longPressed && e.actionMasked == MotionEvent.ACTION_UP) {
                        save(app, v, ring)
                    }
                }
            }
            true
        }

        try {
            wm.addView(root, params)
            view = root
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide(context: Context) {
        val v = view ?: return
        view = null
        handler.removeCallbacksAndMessages(null)
        try {
            context.applicationContext.getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (ignored: Exception) {
        }
    }

    private fun save(context: Context, v: View, ring: GradientDrawable) {
        val controls = RecordingService.controls
        if (controls == null) {
            openApp(context)
            return
        }
        controls.onSaveRequested()
        // Destello verde para confirmar, además del pitido
        val dp = context.resources.displayMetrics.density
        ring.setStroke((4 * dp).toInt(), ContextCompat.getColor(context, R.color.verde_ok))
        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
        handler.postDelayed({
            ring.setStroke((3 * dp).toInt(), ContextCompat.getColor(context, R.color.rojo_rec))
        }, 1500)
        Toast.makeText(context, "Clip guardado", Toast.LENGTH_SHORT).show()
    }

    private fun openApp(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private const val KEY_X = "boton_flotante_x"
    private const val KEY_Y = "boton_flotante_y"
    private const val LONG_PRESS_MS = 600L
}
