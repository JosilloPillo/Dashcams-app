package es.dashcams.app

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Informes de fallos (Firebase Crashlytics). Solo se envían si el usuario lo acepta en la
 * bienvenida o en Ajustes. Se manda lo técnico: modelo del móvil, versión de Android, versión
 * de la app y el error. Nunca vídeos, ubicación, coordenadas ni velocidad.
 *
 * Además de los cierres de la app, se mandan los fallos que la app recoge sin cerrarse
 * (por ejemplo, que la cámara doble no arranque en un modelo concreto).
 */
object Reports {
    private const val PREFS = "ajustes"
    private const val KEY = "informes_fallos"

    /** Política de privacidad, con el apartado de la app: qué se envía y para qué. */
    const val PRIVACY_URL = "https://dashcams.es/privacidad#app"

    @Volatile
    private var on = false

    private fun crashlytics(): FirebaseCrashlytics? = try {
        FirebaseCrashlytics.getInstance()
    } catch (e: Exception) {
        null // Firebase no está montado: la app funciona igual, sin informes
    }

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Al abrir la app: deja Crashlytics como dijo el usuario. */
    fun init(context: Context) {
        on = isOn(context)
        val c = crashlytics() ?: return
        c.setCrashlyticsCollectionEnabled(on)
        // Lo que se guardó sin permiso (p. ej. un cierre antes de decidir) se queda en el móvil:
        // se manda si luego lo acepta y se borra si dice que no.
        if (on) c.sendUnsentReports()
    }

    fun set(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        on = value
        val c = crashlytics() ?: return
        c.setCrashlyticsCollectionEnabled(value)
        if (value) c.sendUnsentReports() else c.deleteUnsentReports()
    }

    /** Dato extra que acompaña a los informes (modo de cámara, etc.). */
    fun key(name: String, value: String) {
        if (!on) return
        crashlytics()?.setCustomKey(name, value)
    }

    /** Un fallo que la app ha recogido sin cerrarse. */
    fun error(where: String, e: Throwable) {
        if (!on) return
        crashlytics()?.recordException(Problem("$where: ${e.message ?: e.javaClass.simpleName}", e))
    }

    /** Un problema sin excepción detrás (p. ej. un código de error de la grabación). */
    fun problem(where: String, message: String) {
        if (!on) return
        crashlytics()?.recordException(Problem("$where: $message", null))
    }

    /** Manda ya lo pendiente (los errores sin cierre se mandan normalmente al volver a abrir la app). */
    fun flush() {
        if (!on) return
        crashlytics()?.sendUnsentReports()
    }

    /** Para que en el panel salgan con un nombre propio y no todos como «Exception». */
    class Problem(message: String, cause: Throwable?) : Exception(message, cause)
}
