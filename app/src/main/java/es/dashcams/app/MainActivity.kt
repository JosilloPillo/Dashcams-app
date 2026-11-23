package es.dashcams.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import es.dashcams.app.databinding.ActivityMainBinding
import es.dashcams.app.databinding.SheetSettingsBinding
import java.util.Locale

class MainActivity : AppCompatActivity(),
    DashcamController.Listener, VoiceCommand.Listener, RecordingService.Controls {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: ClipStorage
    private lateinit var dashcam: DashcamController
    private lateinit var voice: VoiceCommand
    private lateinit var location: LocationTracker

    /** La cámara va ligada a esto y no a la pantalla, para poder seguir grabando en segundo plano. */
    private val cameraLifecycle = CameraLifecycle()

    private val ui = Handler(Looper.getMainLooper())
    private val spanish = Locale.forLanguageTag("es-ES")
    private val prefs by lazy { getSharedPreferences("ajustes", MODE_PRIVATE) }

    /** El usuario estaba grabando cuando la app pasó a segundo plano (con el segundo plano apagado). */
    private var resumeOnStart = false
    /** Paramos nosotros porque el móvil estaba demasiado caliente. */
    private var pausedForHeat = false
    /** El usuario ha pulsado Parar: no volvemos a grabar solos (por ejemplo al cambiar de cámara). */
    private var userStopped = false
    private var cameraStarted = false
    private var serviceRunning = false

    // Ajustes (se guardan en el móvil)
    private var beepOn = true
    private var fitPreview = false
    private var voiceOn = false
    private var keepInBackground = true
    /** Se ha abierto otra pantalla de la propia app (Clips): no hay que parar de grabar. */
    private var inAppScreen = false
    /** Al salir, Android no dejó seguir grabando en segundo plano: se avisa al volver. */
    private var backgroundFailed = false
    private var bubbleOn = false

    private var batteryTemp = -1f
    private var batteryLevel = -1
    private var charging = false

    /** Hoja de Ajustes abierta (para refrescarla si cambian permisos o la descarga de la voz). */
    private var settings: SheetSettingsBinding? = null

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                // Como alarma: suena aunque el móvil esté en silencio o con el volumen de sistema a cero,
                // que es como suelen ir en el coche. Antes no se oía en muchos móviles.
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private var beepId = 0

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraPermission()) {
                startCameraOnce()
            } else {
                binding.statusText.setText(R.string.need_camera)
            }
            if ((dashcam.soundOn || voiceOn) && !hasAudioPermission()) {
                Toast.makeText(
                    this,
                    "Sin permiso de micrófono no hay sonido ni control por voz. Puedes darlo en Ajustes > Aplicaciones.",
                    Toast.LENGTH_LONG
                ).show()
            } else if (dashcam.soundOn && dashcam.isLoopRunning) {
                Toast.makeText(this, "El sonido se grabará desde el siguiente minuto", Toast.LENGTH_SHORT).show()
            }
            if (wantsLocation() && !location.hasPermission()) {
                Toast.makeText(
                    this,
                    "Sin permiso de ubicación el vídeo no lleva velocidad ni coordenadas.",
                    Toast.LENGTH_LONG
                ).show()
            }
            settings?.let { refreshSettings(it) }
            syncVoice()
            syncLocation()
        }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            batteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) / 10f
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryLevel = if (level >= 0 && scale > 0) level * 100 / scale else -1
            charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            checkHeat()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            binding.previewView.display?.let { dashcam.updateRotation(it.rotation) }
        }
    }

    /** Cada segundo: pantalla, voz, GPS y servicio de segundo plano al día. Sigue en segundo plano. */
    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            syncVoice()
            syncLocation()
            syncBackgroundService()
            ui.postDelayed(this, 1_000L)
        }
    }

    private val resetSaveButton = Runnable { binding.btnSave.setText(R.string.save) }

    private val hideSavedChip = Runnable {
        binding.savedChip.animate()
            .alpha(0f)
            .translationY(-12f * resources.displayMetrics.density)
            .setDuration(250)
            .withEndAction { binding.savedChip.visibility = View.GONE }
            .start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        keepControlsAwayFromNotch()

        storage = ClipStorage(this)
        dashcam = DashcamController(this, cameraLifecycle, storage, this)
        voice = VoiceCommand(this, this)
        location = LocationTracker(this)

        dashcam.soundOn = prefs.getBoolean(KEY_SOUND, true) // sonido activado por defecto
        dashcam.setInitialCamera(savedCameraMode())
        beepOn = prefs.getBoolean(KEY_BEEP, true)
        fitPreview = prefs.getBoolean(KEY_FIT, false)
        voiceOn = prefs.getBoolean(KEY_VOICE, false)
        voice.phrase = VoiceCommand.Phrase.fromName(prefs.getString(KEY_PHRASE, null))
        keepInBackground = prefs.getBoolean(KEY_BACKGROUND, true)
        bubbleOn = prefs.getBoolean(KEY_BUBBLE, false) // apagado de serie: al activarlo se piden los permisos
        Telemetry.showSpeed = prefs.getBoolean(KEY_OVERLAY_SPEED, true)
        Telemetry.showCoords = prefs.getBoolean(KEY_OVERLAY_COORDS, true)
        applyPreviewScale()
        beepId = soundPool.load(this, R.raw.guardado, 1)

        // Marca y entrada de los elementos como en los vídeos: kicker bajando, botones con rebote
        binding.brandText.text = Brand.wordmark()
        Brand.enter(binding.kicker, delayMs = 50, fromDp = -12f, durationMs = 400)
        Brand.pop(binding.btnSave, delayMs = 250)

        binding.btnSave.setOnClickListener { saveEvent() }
        binding.btnStartStop.setOnClickListener { toggleRecording() }
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnClips.setOnClickListener {
            // Ir a Clips no para la grabación: sigue siendo la app.
            inAppScreen = true
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.btnDark.setOnClickListener { setDarkMode(true) }
        binding.darkOverlay.setOnClickListener { setDarkMode(false) }

        // Temperatura y reloj interno: funcionan también con la app en segundo plano
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ui.post(tick)
        RecordingService.controls = this

        val missing = buildList {
            if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
            if ((dashcam.soundOn || voiceOn) && !hasAudioPermission()) add(Manifest.permission.RECORD_AUDIO)
            if (wantsLocation() && !location.hasPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // Android 13+: sin este permiso no sale la notificación con «Guardar clip»
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (hasCameraPermission()) startCameraOnce()
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    private fun startCameraOnce() {
        if (cameraStarted || !hasCameraPermission()) return
        cameraStarted = true
        cameraLifecycle.resume()
        dashcam.startCamera()
    }

    override fun onStart() {
        super.onStart()
        inAppScreen = false
        FloatingButton.hide(this)
        if (backgroundFailed) {
            backgroundFailed = false
            Toast.makeText(
                this,
                "Android no ha dejado seguir grabando con la app fuera" +
                    (RecordingService.lastError?.let { ": $it" } ?: ". Mira Ajustes > Segundo plano."),
                Toast.LENGTH_LONG
            ).show()
        }
        settings?.let { refreshSettings(it) } // p. ej. al volver de dar el permiso del botón flotante
        if (cameraStarted) cameraLifecycle.resume()
        dashcam.attachPreview(binding.previewView)
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, ui)

        if (resumeOnStart && dashcam.isCameraReady && !pausedForHeat) {
            resumeOnStart = false
            dashcam.startLoop()
        }
        syncBackgroundService()
    }

    override fun onStop() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        dashcam.detachPreview()

        val inBackgroundOk = keepInBackground && sessionActive() && RecordingService.isForeground
        if (inAppScreen && sessionActive()) {
            // Se ha abierto Clips: la app sigue a la vista, se sigue grabando.
        } else if (inBackgroundOk) {
            // Sigue grabando con la app en segundo plano (hay notificación fija y, si se quiere, botón flotante).
            if (bubbleOn) FloatingButton.show(this)
        } else {
            if (keepInBackground && sessionActive()) backgroundFailed = true
            // Sin segundo plano: paramos y, al volver, seguimos grabando solos.
            if (dashcam.isLoopRunning) resumeOnStart = true
            dashcam.stopLoop()
            voice.stop()
            location.stop()
            cameraLifecycle.pause()
            stopBackgroundService()
        }
        super.onStop()
    }

    override fun onDestroy() {
        // Salir de la app (botón Atrás o cerrarla): se para todo.
        ui.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
        }
        if (RecordingService.controls === this) RecordingService.controls = null
        FloatingButton.hide(this)
        dashcam.release()
        voice.release()
        location.stop()
        cameraLifecycle.destroy()
        stopBackgroundService()
        soundPool.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Hay una sesión de grabación en marcha (grabando o en pausa por calor). */
    private fun sessionActive(): Boolean = dashcam.isLoopRunning || pausedForHeat

    // ---- Servicio de segundo plano ----

    private fun syncBackgroundService() {
        val wanted = keepInBackground && cameraLifecycle.isActive && sessionActive()
        if (wanted && !serviceRunning) {
            // Android solo deja arrancarlo con la app a la vista
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                try {
                    RecordingService.start(this)
                    serviceRunning = true
                } catch (e: Exception) {
                    serviceRunning = false
                }
            }
        } else if (!wanted && serviceRunning) {
            stopBackgroundService()
        }
    }

    private fun stopBackgroundService() {
        if (!serviceRunning) return
        RecordingService.stop(this)
        serviceRunning = false
    }

    /** Botón "Guardar clip" de la notificación. */
    override fun onSaveRequested() {
        saveEvent()
    }

    // ---- DashcamController.Listener ----

    override fun onCameraReady() {
        // Como una dashcam de verdad: en cuanto la cámara está lista, empieza a grabar.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || serviceRunning) {
            if (!pausedForHeat && !userStopped) dashcam.startLoop()
        } else if (!userStopped) {
            resumeOnStart = true
        }
        // El servicio de segundo plano se arranca ya, con la app a la vista (luego Android no deja)
        syncBackgroundService()
        settings?.let { refreshSettings(it) }
        updateStatus()
    }

    override fun onDashcamError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateStatus()
    }

    override fun onDashcamInfo(message: String) {
        Toast.makeText(this, message, if (message.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ---- VoiceCommand.Listener ----

    override fun onVoiceCommand() {
        saveEvent()
    }

    override fun onVoiceState(listening: Boolean, message: String?) {
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        settings?.let { refreshSettings(it) }
        updateStatus()
    }

    /** La voz solo escucha mientras se graba, con permiso de micrófono y si está activada. */
    private fun syncVoice() {
        if (!::voice.isInitialized) return
        val shouldListen = voiceOn && voice.isModelReady && hasAudioPermission() &&
            dashcam.isLoopRunning && cameraLifecycle.isActive
        if (shouldListen) voice.start() else if (voice.isListening) voice.stop()
    }

    // ---- GPS ----

    private fun wantsLocation(): Boolean = Telemetry.showSpeed || Telemetry.showCoords

    /** El GPS solo funciona si hace falta para el vídeo y la cámara está abierta. */
    private fun syncLocation() {
        if (!::location.isInitialized) return
        val wanted = wantsLocation() && location.hasPermission() && cameraLifecycle.isActive && cameraStarted
        if (wanted) location.start() else location.stop()
    }

    // ---- Botón GUARDAR, teclas de volumen y mandos Bluetooth ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Las teclas de volumen y los mandos Bluetooth de selfie (que envían "subir volumen")
        // guardan el clip: así no hace falta tocar la pantalla.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        ) {
            if (event.repeatCount == 0) saveEvent()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun saveEvent() {
        if (dashcam.saveEvent()) {
            if (beepOn && beepId != 0) soundPool.play(beepId, 1f, 1f, 1, 0, 1f)

            binding.btnSave.setText(R.string.saved)
            Brand.bump(binding.btnSave)
            ui.removeCallbacks(resetSaveButton)
            ui.postDelayed(resetSaveButton, 2_500L)

            // Chip blanco con check verde que entra con rebote y se va solo
            ui.removeCallbacks(hideSavedChip)
            binding.savedChip.animate().cancel()
            binding.savedChip.translationY = 0f
            binding.savedChip.visibility = View.VISIBLE
            Brand.pop(binding.savedChip, fromRightDp = 40f)
            ui.postDelayed(hideSavedChip, 2_200L)
        } else {
            Toast.makeText(this, R.string.not_recording, Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    private fun toggleRecording() {
        if (dashcam.isLoopRunning) {
            pausedForHeat = false
            userStopped = true
            dashcam.stopLoop()
        } else if (batteryTemp >= HEAT_STOP_C) {
            Toast.makeText(this, "El móvil está demasiado caliente para grabar", Toast.LENGTH_LONG).show()
        } else {
            pausedForHeat = false
            userStopped = false
            dashcam.startLoop()
        }
        syncVoice()
        syncBackgroundService()
        updateStatus()
    }

    // ---- Ajustes ----

    private fun openSettings() {
        val sheet = BottomSheetDialog(this)
        val s = SheetSettingsBinding.inflate(layoutInflater)
        sheet.setContentView(s.root)
        settings = s
        sheet.setOnDismissListener { settings = null }

        s.title.text = Brand.highlight("Ajustes", "Ajustes")
        refreshSettings(s)
        buildIconRow(s)

        s.switchSound.setOnCheckedChangeListener { _, on ->
            if (on == (dashcam.soundOn && hasAudioPermission())) return@setOnCheckedChangeListener
            dashcam.soundOn = on
            prefs.edit().putBoolean(KEY_SOUND, on).apply()
            if (on && !hasAudioPermission()) {
                requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            refreshSettings(s)
        }
        s.switchBeep.setOnCheckedChangeListener { _, on ->
            beepOn = on
            prefs.edit().putBoolean(KEY_BEEP, on).apply()
            if (on && beepId != 0) soundPool.play(beepId, 1f, 1f, 1, 0, 1f) // para que se oiga cómo suena
        }
        s.switchFit.setOnCheckedChangeListener { _, on ->
            fitPreview = on
            prefs.edit().putBoolean(KEY_FIT, on).apply()
            applyPreviewScale()
        }

        // Datos en el vídeo (la fecha y la hora no se pueden quitar)
        s.switchSpeed.setOnCheckedChangeListener { _, on ->
            Telemetry.showSpeed = on
            prefs.edit().putBoolean(KEY_OVERLAY_SPEED, on).apply()
            askLocationIfNeeded()
            refreshSettings(s)
        }
        s.switchCoords.setOnCheckedChangeListener { _, on ->
            Telemetry.showCoords = on
            prefs.edit().putBoolean(KEY_OVERLAY_COORDS, on).apply()
            askLocationIfNeeded()
            refreshSettings(s)
        }

        // Segundo plano
        s.switchBackground.setOnCheckedChangeListener { _, on ->
            keepInBackground = on
            prefs.edit().putBoolean(KEY_BACKGROUND, on).apply()
            syncBackgroundService()
            refreshSettings(s)
        }
        s.btnBattery.setOnClickListener { openBatterySettings() }
        s.switchBubble.setOnCheckedChangeListener { _, on ->
            bubbleOn = on
            prefs.edit().putBoolean(KEY_BUBBLE, on).apply()
            if (on && !FloatingButton.canShow(this)) openOverlaySettings()
            refreshSettings(s)
        }
        s.bubbleNote.setOnClickListener {
            if (bubbleOn && !FloatingButton.canShow(this)) openOverlaySettings()
        }

        s.cameraGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioInside -> DashcamController.CameraMode.INSIDE
                R.id.radioBoth -> DashcamController.CameraMode.BOTH
                else -> DashcamController.CameraMode.ROAD
            }
            if (mode == dashcam.cameraMode) return@setOnCheckedChangeListener
            val filmsInside = mode != DashcamController.CameraMode.ROAD
            if (filmsInside && !prefs.getBoolean(KEY_INSIDE_WARNED, false)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.inside_title)
                    .setMessage(R.string.inside_text)
                    .setNegativeButton(R.string.cancel) { _, _ -> checkCameraRadio(s) }
                    .setPositiveButton(R.string.inside_ok) { _, _ ->
                        prefs.edit().putBoolean(KEY_INSIDE_WARNED, true).apply()
                        switchCamera(mode)
                    }
                    .setOnCancelListener { checkCameraRadio(s) }
                    .show()
            } else {
                switchCamera(mode)
            }
        }
        s.switchVoice.setOnCheckedChangeListener { _, on ->
            if (on == voiceOn) return@setOnCheckedChangeListener
            setVoice(on)
        }
        s.phraseGroup.setOnCheckedChangeListener { _, checkedId ->
            val chosen = when (checkedId) {
                R.id.phraseRed -> VoiceCommand.Phrase.CODIGO_ROJO
                R.id.phraseAlert -> VoiceCommand.Phrase.ALERTA
                else -> VoiceCommand.Phrase.EMERGENCIA
            }
            if (chosen == voice.phrase) return@setOnCheckedChangeListener
            voice.phrase = chosen // si estaba escuchando, se reinicia con la nueva frase
            prefs.edit().putString(KEY_PHRASE, chosen.name).apply()
            refreshSettings(s)
            syncVoice()
        }

        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    /** Pone los interruptores y textos de la hoja según el estado actual. */
    private fun refreshSettings(s: SheetSettingsBinding) {
        val micOk = hasAudioPermission()
        s.switchSound.isChecked = dashcam.soundOn && micOk
        s.soundNote.text = when {
            !micOk -> "Falta el permiso de micrófono. Activa el interruptor para pedirlo."
            dashcam.soundOn -> "Se graba el sonido del interior del coche."
            else -> "Los vídeos se graban sin sonido."
        }
        s.switchBeep.isChecked = beepOn
        s.switchFit.isChecked = fitPreview

        // Datos en el vídeo
        s.switchSpeed.isChecked = Telemetry.showSpeed
        s.switchCoords.isChecked = Telemetry.showCoords
        s.overlayNote.text = if (dashcam.isCameraReady && !dashcam.overlayActive) {
            "Este móvil no permite poner datos dentro del vídeo, así que no saldrán ni la fecha ni lo demás."
        } else {
            "La fecha, la hora y el escudo con dashcams.es salen siempre. La velocidad y las coordenadas puedes quitarlas."
        }
        s.locationNote.text = when {
            !wantsLocation() -> "El GPS está apagado: no se usa la ubicación."
            !location.hasPermission() -> "Falta el permiso de ubicación: activa velocidad o coordenadas para pedirlo."
            !Telemetry.hasFix -> "Buscando señal GPS… (al aire libre tarda menos de un minuto)."
            else -> "GPS funcionando. La velocidad es orientativa. La ubicación solo va al vídeo, no sale del móvil."
        }

        // Segundo plano
        s.switchBackground.isChecked = keepInBackground
        val power = getSystemService(PowerManager::class.java)
        val unrestricted = power?.isIgnoringBatteryOptimizations(packageName) == true
        s.backgroundNote.text = when {
            !keepInBackground -> "Al salir de la app se para la grabación y sigue al volver."
            unrestricted -> "Si sales de la app sigue grabando (verás una notificación con «Guardar clip»). Al pulsar Atrás se para."
            else -> "Si sales de la app sigue grabando, con una notificación con «Guardar clip». Algunos móviles (Xiaomi, Huawei, Oppo…) cierran las apps para ahorrar batería: pulsa el botón de abajo y quita la restricción a Dashcams España."
        }
        RecordingService.lastError?.let { err ->
            if (keepInBackground) s.backgroundNote.append("\nÚltimo fallo al salir de la app: $err")
        }
        s.switchBubble.isChecked = bubbleOn
        s.switchBubble.isEnabled = keepInBackground
        s.bubbleNote.text = when {
            !keepInBackground -> "Necesita que la grabación siga en segundo plano."
            !bubbleOn -> "Al salir de la app no aparecerá el botón flotante."
            !FloatingButton.canShow(this) -> "Falta el permiso «Mostrar sobre otras apps». Toca aquí para darlo."
            else -> "Al salir de la app aparece el escudo flotante: tócalo para guardar un clip, mantenlo pulsado para volver a la app y arrástralo para moverlo."
        }
        s.btnBattery.isVisible = !unrestricted

        s.radioBoth.isEnabled = dashcam.supportsBothCameras || dashcam.cameraMode == DashcamController.CameraMode.BOTH
        s.radioBoth.alpha = if (s.radioBoth.isEnabled) 1f else 0.4f
        checkCameraRadio(s)
        s.bothNote.text = when {
            dashcam.bothActive ->
                "Grabando las dos a la vez en dos vídeos separados: uno de la carretera (F) y otro del interior (I), a 720p. Gasta más batería, calienta más el móvil y el bucle guarda la mitad de minutos."
            dashcam.cameraMode == DashcamController.CameraMode.BOTH ->
                "Has elegido las dos a la vez, pero el móvil no ha podido abrirlas juntas: se está grabando solo la carretera." +
                    (dashcam.bothProblem?.let { "\nMotivo: $it" } ?: "")
            dashcam.supportsBothCameras ->
                "Tu móvil puede usar las dos cámaras a la vez: graba la carretera y el interior en dos vídeos separados."
            else -> "Tu móvil no deja a otras apps abrir las dos cámaras a la vez (aunque su propia app de cámara sí pueda): se graba una u otra."
        }

        s.switchVoice.isChecked = voiceOn
        s.phraseGroup.check(
            when (voice.phrase) {
                VoiceCommand.Phrase.CODIGO_ROJO -> R.id.phraseRed
                VoiceCommand.Phrase.ALERTA -> R.id.phraseAlert
                VoiceCommand.Phrase.EMERGENCIA -> R.id.phraseEmergency
            }
        )
        s.voiceProgress.isVisible = voice.isDownloading
        s.voiceNote.text = when {
            voice.isDownloading -> "Descargando el reconocedor de voz…"
            !voiceOn -> "Funciona sin internet. Al activarlo se descarga una vez el reconocedor de español (39 MB). Lo que dices no sale del móvil."
            !micOk -> "Falta el permiso de micrófono."
            voice.failed -> "El reconocedor no ha podido arrancar en este móvil. Prueba a apagar «Grabar sonido»: algunos móviles no dejan usar el micrófono para las dos cosas a la vez."
            !voice.isModelReady -> "Falta descargar el reconocedor de voz."
            voice.isListening -> "Escuchando. Di ${voice.phrase.label}, sola y con una pausa, y se guarda el clip."
            else -> "Se activa solo mientras se graba."
        }
    }

    private fun askLocationIfNeeded() {
        if (wantsLocation() && !location.hasPermission()) {
            requestPermissions.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        syncLocation()
    }

    /** Abre la lista de optimización de batería del móvil; si no existe, la guía de dontkillmyapp.com. */
    /** Pantalla de Android para dar el permiso "Mostrar sobre otras apps". */
    private fun openOverlaySettings() {
        Toast.makeText(this, "Activa «Mostrar sobre otras apps» para Dashcams España", Toast.LENGTH_LONG).show()
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (ignored: Exception) {
            }
        }
    }

    private fun openBatterySettings() {
        Toast.makeText(
            this,
            "Busca «Dashcams España» y elige «Sin restricciones» o «No optimizar».",
            Toast.LENGTH_LONG
        ).show()
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/")))
            } catch (ignored: Exception) {
            }
        }
    }

    private fun setVoice(on: Boolean) {
        voiceOn = on
        prefs.edit().putBoolean(KEY_VOICE, on).apply()
        if (!on) {
            voice.stop()
            settings?.let { refreshSettings(it) }
            updateStatus()
            return
        }
        voice.resetFailure()
        if (!hasAudioPermission()) requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        if (!voice.isModelReady) {
            voice.downloadModel(
                onProgress = { p ->
                    settings?.let {
                        it.voiceProgress.isVisible = true
                        it.voiceProgress.setProgressCompat(p, true)
                        it.voiceNote.text = "Descargando el reconocedor de voz (39 MB): $p %"
                    }
                },
                onDone = { ok, error ->
                    if (!ok) {
                        voiceOn = false
                        prefs.edit().putBoolean(KEY_VOICE, false).apply()
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                    settings?.let { refreshSettings(it) }
                    syncVoice()
                }
            )
        }
        settings?.let { refreshSettings(it) }
        syncVoice()
    }

    private fun switchCamera(mode: DashcamController.CameraMode) {
        prefs.edit()
            .putString(KEY_CAMERA_MODE, mode.name)
            .putBoolean(KEY_INSIDE, mode == DashcamController.CameraMode.INSIDE)
            .apply()
        voice.stop()
        dashcam.switchCamera(mode)
        if (mode != DashcamController.CameraMode.BOTH) {
            Toast.makeText(
                this,
                if (mode == DashcamController.CameraMode.INSIDE) "Grabando el interior del coche" else "Grabando la carretera",
                Toast.LENGTH_SHORT
            ).show()
        }
        updateStatus()
    }

    /** Lo elegido en Ajustes. Las versiones anteriores solo guardaban si era el interior. */
    private fun savedCameraMode(): DashcamController.CameraMode {
        val saved = prefs.getString(KEY_CAMERA_MODE, null)
        if (saved != null) return DashcamController.CameraMode.fromName(saved)
        return if (prefs.getBoolean(KEY_INSIDE, false)) DashcamController.CameraMode.INSIDE
        else DashcamController.CameraMode.ROAD
    }

    /** Fila de banderas en Ajustes para elegir el icono de la app. */
    private fun buildIconRow(s: SheetSettingsBinding) {
        val row = s.iconRow
        row.removeAllViews()
        val dp = resources.displayMetrics.density
        val selected = AppIcons.current(this)
        for (icon in AppIcons.all) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                minimumWidth = (84 * dp).toInt()
                setBackgroundResource(
                    if (icon.id == selected.id) R.drawable.bg_icon_option_selected else R.drawable.bg_icon_option
                )
                contentDescription = icon.name
                isClickable = true
                isFocusable = true
            }
            item.addView(ImageView(this).apply {
                setImageResource(icon.shield)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (52 * dp).toInt()))
            item.addView(TextView(this).apply {
                text = icon.name
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.public_sans_regular)
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, (4 * dp).toInt(), 0, 0)
            }, LinearLayout.LayoutParams((76 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            item.setOnClickListener { askIconChange(s, icon) }
            row.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() })
        }
    }

    private fun askIconChange(s: SheetSettingsBinding, icon: AppIcons.Icon) {
        if (icon.id == AppIcons.current(this).id) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.icon_change_title)
            .setMessage(
                "El icono pasará a ser el de ${icon.name}. Puede tardar unos segundos en cambiar y, " +
                    "en algunos móviles, la app se cierra un momento o hay que volver a poner el icono " +
                    "en la pantalla de inicio."
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.icon_change_ok) { _, _ ->
                try {
                    AppIcons.apply(this, icon)
                    buildIconRow(s)
                    Toast.makeText(this, "Icono cambiado: ${icon.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "No se pudo cambiar el icono", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun checkCameraRadio(s: SheetSettingsBinding) {
        when (dashcam.cameraMode) {
            DashcamController.CameraMode.ROAD -> s.radioRoad.isChecked = true
            DashcamController.CameraMode.INSIDE -> s.radioInside.isChecked = true
            DashcamController.CameraMode.BOTH -> s.radioBoth.isChecked = true
        }
    }

    private fun applyPreviewScale() {
        binding.previewView.scaleType =
            if (fitPreview) PreviewView.ScaleType.FIT_CENTER else PreviewView.ScaleType.FILL_CENTER
    }

    // ---- Temperatura ----

    private fun checkHeat() {
        if (batteryTemp >= HEAT_STOP_C && dashcam.isLoopRunning) {
            pausedForHeat = true
            dashcam.stopLoop()
            Toast.makeText(
                this,
                String.format(spanish, "Móvil a %.0f °C: paro la grabación para protegerlo", batteryTemp),
                Toast.LENGTH_LONG
            ).show()
        } else if (pausedForHeat && batteryTemp in 0f..HEAT_RESUME_C) {
            pausedForHeat = false
            if (dashcam.isCameraReady && !userStopped && cameraLifecycle.isActive) dashcam.startLoop()
            Toast.makeText(this, "Temperatura correcta, sigo grabando", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    // ---- Pantalla ----

    private fun updateStatus() {
        if (!::dashcam.isInitialized) return
        val recording = dashcam.isLoopRunning
        val blinkOn = (System.currentTimeMillis() / 500L) % 2L == 0L
        binding.recDot.visibility = if (recording && blinkOn) View.VISIBLE else View.INVISIBLE

        val savedCount = storage.savedClips().size
        val freeGb = storage.freeBytes() / (1024f * 1024f * 1024f)

        // Kicker corto, como en los vídeos: 2-4 palabras
        binding.statusText.text = when {
            pausedForHeat -> "Pausa por calor"
            recording -> {
                val s = dashcam.segmentElapsedMs / 1000L
                val extra = when {
                    freeGb < LOW_SPACE_GB -> "poco espacio"
                    savedCount == 1 -> "1 clip"
                    else -> "$savedCount clips"
                }
                val flags = buildString {
                    if (dashcam.bothActive) append(" · 2 cámaras")
                    else if (dashcam.useFrontCamera) append(" · interior")
                    if (!dashcam.soundOn || !hasAudioPermission()) append(" · sin sonido")
                    if (::voice.isInitialized && voice.isListening) append(" · voz")
                }
                String.format(spanish, "REC %02d:%02d · %s%s", s / 60, s % 60, extra, flags)
            }
            !dashcam.isCameraReady && !hasCameraPermission() -> "Falta permiso de cámara"
            !dashcam.isCameraReady -> getString(R.string.status_starting)
            else -> "Parado · $savedCount clips"
        }

        if (batteryTemp > 0f) {
            binding.tempText.text = String.format(
                spanish,
                "%.0f °C · %d %%%s",
                batteryTemp, batteryLevel, if (charging) " ⚡" else ""
            )
            binding.tempText.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        batteryTemp >= HEAT_STOP_C -> R.color.rojo_rec
                        batteryTemp >= HEAT_WARN_C -> R.color.amarillo
                        else -> android.R.color.white
                    }
                )
            )
        }

        binding.btnStartStop.setText(if (recording) R.string.stop else R.string.record)
        binding.darkText.text =
            if (recording) "● Grabando · toca la pantalla para volver" else "Parado · toca la pantalla para volver"
    }

    /** Tapa la pantalla de negro y baja el brillo al mínimo: el móvil se calienta menos. */
    private fun setDarkMode(on: Boolean) {
        binding.darkOverlay.isVisible = on
        val params = window.attributes
        params.screenBrightness =
            if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
        updateStatus()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun keepControlsAwayFromNotch() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.controls) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(
                left = cutout.left,
                top = cutout.top,
                right = cutout.right,
                bottom = cutout.bottom
            )
            insets
        }
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val KEY_SOUND = "sonido"
        private const val KEY_BEEP = "pitido"
        private const val KEY_FIT = "encuadre_completo"
        private const val KEY_INSIDE = "camara_interior"
        private const val KEY_CAMERA_MODE = "modo_camara"
        private const val KEY_INSIDE_WARNED = "aviso_interior_visto"
        private const val KEY_VOICE = "voz"
        private const val KEY_PHRASE = "frase_voz"
        private const val KEY_BACKGROUND = "segundo_plano"
        private const val KEY_BUBBLE = "boton_flotante"
        private const val KEY_OVERLAY_SPEED = "datos_velocidad"
        private const val KEY_OVERLAY_COORDS = "datos_coordenadas"

        /** Por debajo de esto el kicker avisa de que queda poco espacio. */
        private const val LOW_SPACE_GB = 2f

        /** A partir de aquí avisamos en ámbar. */
        const val HEAT_WARN_C = 45f
        /** A partir de aquí paramos de grabar para no dañar la batería. */
        const val HEAT_STOP_C = 50f
        /** Por debajo de esto volvemos a grabar solos. */
        const val HEAT_RESUME_C = 42f
    }
}
