package es.dashcams.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Servicio en primer plano: mientras está activo, Android deja que la app siga usando la cámara,
 * el micrófono y el GPS aunque salgas de ella. Muestra una notificación fija con un botón
 * "Guardar clip". No graba nada por sí mismo: la grabación la lleva la pantalla principal.
 */
class RecordingService : Service() {

    /** Lo que la notificación puede pedir a la app. */
    interface Controls {
        fun onSaveRequested()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SAVE) {
            controls?.onSaveRequested()
            return START_NOT_STICKY
        }
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Grabación", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aviso de que Dashcams España está grabando"
                    setShowBadge(false)
                }
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val save = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_SAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Dashcams España está grabando")
            .setContentText("Toca para volver a la app · «Guardar clip» guarda lo que acaba de pasar")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Guardar clip", save)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        // Solo se declara lo que tiene permiso; si no, Android 14 no deja arrancar el servicio.
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            isForeground = true
            lastError = null
        } catch (e: Exception) {
            isForeground = false
            lastError = e.message ?: e.javaClass.simpleName
            stopSelf()
        }
    }

    override fun onDestroy() {
        isForeground = false
        FloatingButton.hide(this)
        super.onDestroy()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "grabacion"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_SAVE = "es.dashcams.app.GUARDAR"

        /** La pantalla principal se apunta aquí para recibir el "Guardar clip" de la notificación. */
        @Volatile
        var controls: Controls? = null

        /** true cuando Android ha aceptado el servicio: solo entonces se puede grabar con la app fuera. */
        @Volatile
        var isForeground = false
            private set

        /** Por qué Android no ha dejado arrancar el servicio la última vez (para enseñarlo en Ajustes). */
        @Volatile
        var lastError: String? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}

/**
 * "Ciclo de vida" propio para la cámara: la cámara queda abierta mientras esto esté activo,
 * aunque la pantalla de la app no se vea (así puede seguir grabando en segundo plano).
 */
class CameraLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    val isActive: Boolean get() = registry.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun resume() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
