package es.dashcams.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Si la app se cierra por un fallo, guarda qué pasó en un fichero para enseñarlo al volver a abrirla
 * (y poder copiarlo y mandarlo). Los cierres dentro del propio sistema de cámara no pasan por aquí:
 * para esos está la protección del modo doble en DashcamController.
 */
object CrashLog {
    private const val FILE = "ultimo_fallo.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    /** Lo que se guardó del último cierre, o null. Lo borra al leerlo. */
    fun take(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        val text = try { f.readText() } catch (e: Exception) { null }
        f.delete()
        return text
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("Versión ").append(BuildConfig.VERSION_NAME)
                    append(" · Android ").append(android.os.Build.VERSION.RELEASE)
                    append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                    append("\nHilo: ").append(t.name).append('\n')
                    append(sw.toString().take(6000))
                }
                File(context.filesDir, FILE).writeText(text)
            } catch (ignored: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }
}
