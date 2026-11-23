package es.dashcams.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs

/**
 * Datos que se graban encima del vídeo y qué se muestra.
 * La fecha y la hora salen siempre; lo demás se puede desactivar en Ajustes.
 * Se lee desde el hilo que dibuja el vídeo, por eso todo es @Volatile.
 */
object Telemetry {
    @Volatile var showCoords = true
    @Volatile var showSpeed = true
    /** El escudo y «dashcams.es» salen siempre: la app es gratis y así la gente puede encontrarla. */
    const val showBrand = true

    @Volatile private var lat = 0.0
    @Volatile private var lon = 0.0
    @Volatile private var speedKmh = -1f
    @Volatile private var fixTime = 0L

    fun update(location: Location) {
        lat = location.latitude
        lon = location.longitude
        speedKmh = if (location.hasSpeed()) location.speed * 3.6f else -1f
        fixTime = SystemClock.elapsedRealtime()
    }

    /** Un dato de GPS de hace más de 10 segundos ya no vale. */
    private fun fresh(): Boolean = fixTime > 0 && SystemClock.elapsedRealtime() - fixTime < 10_000

    val hasFix: Boolean get() = fresh()

    fun speedText(): String =
        if (fresh() && speedKmh >= 0f) String.format(Locale.US, "%.0f km/h", speedKmh) else "-- km/h"

    fun coordsText(): String =
        if (fresh()) {
            String.format(
                Locale.US, "%.5f %s  %.5f %s",
                abs(lat), if (lat >= 0) "N" else "S",
                abs(lon), if (lon >= 0) "E" else "O"
            )
        } else {
            "GPS: buscando señal…"
        }
}

/**
 * GPS del propio móvil (sin servicios de Google). Solo se usa si están activadas las coordenadas
 * o la velocidad, y los datos solo van al vídeo: no salen del móvil.
 */
class LocationTracker(context: Context) : LocationListener {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    var isRunning = false
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // se comprueba con hasPermission()
    fun start() {
        if (isRunning || !hasPermission() || manager == null) return
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            isRunning = true
        } catch (e: Exception) {
            isRunning = false
        }
    }

    fun stop() {
        if (!isRunning) return
        try {
            manager?.removeUpdates(this)
        } catch (e: Exception) {
        }
        isRunning = false
    }

    override fun onLocationChanged(location: Location) {
        Telemetry.update(location)
    }

    // En Android 8-10 estos métodos no tienen implementación por defecto: hay que ponerlos.
    @Deprecated("Solo para Android antiguos")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
