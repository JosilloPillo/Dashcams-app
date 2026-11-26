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

        /** El móvil se va a apagar o han quitado la app de recientes: hay que cerrar bien el vídeo. */
        fun onShutdownRequested()

        /** Han tocado «Parar grabación» en el aviso de que parece que has aparcado. */
        fun onStopRequested()
    }

    private val shutdownReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            controls?.onShutdownRequested()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val filter = android.content.IntentFilter(Intent.ACTION_SHUTDOWN)
            androidx.core.content.ContextCompat.registerReceiver(
                this, shutdownReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        } catch (ignored: Exception) {
        }
    }

    /** Han quitado la app de la lista de recientes con la grabación en segundo plano. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        controls?.onShutdownRequested()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SAVE) {
            controls?.onSaveRequested()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_KEEP) {
            cancelParkedAsk(this)
            if (intent.action == ACTION_STOP) controls?.onStopRequested()
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
            Reports.error("Segundo plano", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(shutdownReceiver)
        } catch (ignored: Exception) {
        }
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
        private const val ACTION_STOP = "es.dashcams.app.PARAR"
        private const val ACTION_KEEP = "es.dashcams.app.SEGUIR"
        private const val ASK_CHANNEL_ID = "avisos"
        private const val ASK_NOTIFICATION_ID = 8

        /**
         * Aviso con sonido cuando parece que has aparcado y se sigue grabando:
         * «Parar grabación» o «Seguir grabando».
         */
        fun askParked(context: Context, title: String, text: String) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(ASK_CHANNEL_ID, "Avisos", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Aviso de que se sigue grabando cuando parece que has aparcado"
                    }
                )
            }
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stop = PendingIntent.getService(
                context, 3,
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val keep = PendingIntent.getService(
                context, 4,
                Intent(context, RecordingService::class.java).setAction(ACTION_KEEP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(context, ASK_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_rec)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Parar grabación", stop)
                .addAction(0, "Seguir grabando", keep)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try {
                manager.notify(ASK_NOTIFICATION_ID, notification)
            } catch (ignored: SecurityException) {
            }
        }

        fun cancelParkedAsk(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(ASK_NOTIFICATION_ID)
        }

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
