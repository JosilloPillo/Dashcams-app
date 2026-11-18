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
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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

class MainActivity : AppCompatActivity(), DashcamController.Listener, VoiceCommand.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: ClipStorage
    private lateinit var dashcam: DashcamController
    private lateinit var voice: VoiceCommand

    private val ui = Handler(Looper.getMainLooper())
    private val spanish = Locale.forLanguageTag("es-ES")
    private val prefs by lazy { getSharedPreferences("ajustes", MODE_PRIVATE) }

    /** El usuario estaba grabando cuando la app pasó a segundo plano: al volver, seguimos. */
    private var resumeOnStart = false
    /** Paramos nosotros porque el móvil estaba demasiado caliente. */
    private var pausedForHeat = false
    /** El usuario ha pulsado Parar: no volvemos a grabar solos (por ejemplo al cambiar de cámara). */
    private var userStopped = false
    private var cameraStarted = false

    // Ajustes (se guardan en el móvil)
    private var beepOn = true
    private var fitPreview = false
    private var voiceOn = false

    private var batteryTemp = -1f
    private var batteryLevel = -1
    private var charging = false

    /** Hoja de Ajustes abierta (para refrescarla si cambian permisos o la descarga de la voz). */
    private var settings: SheetSettingsBinding? = null

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
            settings?.let { refreshSettings(it) }
            syncVoice()
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

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            syncVoice()
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
        dashcam = DashcamController(this, binding.previewView, storage, this)
        voice = VoiceCommand(this, this)

        dashcam.soundOn = prefs.getBoolean(KEY_SOUND, true) // sonido activado por defecto
        dashcam.setInitialCamera(prefs.getBoolean(KEY_INSIDE, false))
        beepOn = prefs.getBoolean(KEY_BEEP, true)
        fitPreview = prefs.getBoolean(KEY_FIT, false)
        voiceOn = prefs.getBoolean(KEY_VOICE, false)
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
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.btnDark.setOnClickListener { setDarkMode(true) }
        binding.darkOverlay.setOnClickListener { setDarkMode(false) }

        val missing = buildList {
            if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
            if ((dashcam.soundOn || voiceOn) && !hasAudioPermission()) add(Manifest.permission.RECORD_AUDIO)
        }
        if (hasCameraPermission()) startCameraOnce()
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    private fun startCameraOnce() {
        if (cameraStarted || !hasCameraPermission()) return
        cameraStarted = true
        dashcam.startCamera()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, ui)
        ui.post(tick)

        if (resumeOnStart && dashcam.isCameraReady && !pausedForHeat) {
            resumeOnStart = false
            dashcam.startLoop()
        }
    }

    override fun onStop() {
        // Android no deja usar la cámara con la app en segundo plano desde aquí:
        // paramos y, al volver, seguimos grabando solos.
        if (dashcam.isLoopRunning) resumeOnStart = true
        dashcam.stopLoop()
        voice.stop()

        unregisterReceiver(batteryReceiver)
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        ui.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        voice.release()
        soundPool.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---- DashcamController.Listener ----

    override fun onCameraReady() {
        // Como una dashcam de verdad: en cuanto la cámara está lista, empieza a grabar.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (!pausedForHeat && !userStopped) dashcam.startLoop()
        } else if (!userStopped) {
            resumeOnStart = true
        }
        updateStatus()
    }

    override fun onDashcamError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        updateStatus()
    }

    override fun onDashcamInfo(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
            dashcam.isLoopRunning && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (shouldListen) voice.start() else if (voice.isListening) voice.stop()
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
        s.cameraGroup.setOnCheckedChangeListener { _, checkedId ->
            val inside = checkedId == R.id.radioInside
            if (inside == dashcam.useFrontCamera) return@setOnCheckedChangeListener
            if (inside && !prefs.getBoolean(KEY_INSIDE_WARNED, false)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.inside_title)
                    .setMessage(R.string.inside_text)
                    .setNegativeButton(R.string.cancel) { _, _ -> s.radioRoad.isChecked = true }
                    .setPositiveButton(R.string.inside_ok) { _, _ ->
                        prefs.edit().putBoolean(KEY_INSIDE_WARNED, true).apply()
                        switchCamera(true)
                    }
                    .setOnCancelListener { s.radioRoad.isChecked = true }
                    .show()
            } else {
                switchCamera(inside)
            }
        }
        s.switchVoice.setOnCheckedChangeListener { _, on ->
            if (on == voiceOn) return@setOnCheckedChangeListener
            setVoice(on)
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
        if (dashcam.useFrontCamera) s.radioInside.isChecked = true else s.radioRoad.isChecked = true
        s.bothNote.text = if (dashcam.supportsBothCameras) {
            "Tu móvil puede usar las dos cámaras a la vez. Grabar carretera e interior juntas llegará en una próxima versión."
        } else {
            "Tu móvil no permite usar las dos cámaras a la vez: se graba una u otra."
        }

        s.switchVoice.isChecked = voiceOn
        s.voiceProgress.isVisible = voice.isDownloading
        s.voiceNote.text = when {
            voice.isDownloading -> "Descargando el reconocedor de voz…"
            !voiceOn -> "Funciona sin internet. Al activarlo se descarga una vez el reconocedor de español (39 MB). Lo que dices no sale del móvil."
            !micOk -> "Falta el permiso de micrófono."
            voice.failed -> "El reconocedor no ha podido arrancar en este móvil. Prueba a apagar «Grabar sonido»: algunos móviles no dejan usar el micrófono para las dos cosas a la vez."
            !voice.isModelReady -> "Falta descargar el reconocedor de voz."
            voice.isListening -> "Escuchando. Di «Cámara, guarda» y se guarda el clip."
            else -> "Se activa solo mientras se graba."
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

    private fun switchCamera(inside: Boolean) {
        prefs.edit().putBoolean(KEY_INSIDE, inside).apply()
        voice.stop()
        dashcam.switchCamera(inside)
        Toast.makeText(
            this,
            if (inside) "Grabando el interior del coche" else "Grabando la carretera",
            Toast.LENGTH_SHORT
        ).show()
        updateStatus()
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
            if (dashcam.isCameraReady && !userStopped) dashcam.startLoop()
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
                    if (dashcam.useFrontCamera) append(" · interior")
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
        private const val KEY_INSIDE_WARNED = "aviso_interior_visto"
        private const val KEY_VOICE = "voz"

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
