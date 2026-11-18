package es.dashcams.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import es.dashcams.app.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity(), DashcamController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: ClipStorage
    private lateinit var dashcam: DashcamController

    private val ui = Handler(Looper.getMainLooper())
    private val spanish = Locale.forLanguageTag("es-ES")

    /** El usuario estaba grabando cuando la app pasó a segundo plano: al volver, seguimos. */
    private var resumeOnStart = false
    /** Paramos nosotros porque el móvil estaba demasiado caliente. */
    private var pausedForHeat = false

    private var batteryTemp = -1f
    private var batteryLevel = -1
    private var charging = false

    private val prefs by lazy { getSharedPreferences("ajustes", MODE_PRIVATE) }
    private var cameraStarted = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCameraPermission()) {
                startCameraOnce()
            } else {
                binding.statusText.setText(R.string.need_camera)
            }
            if (dashcam.soundOn && !hasAudioPermission()) {
                Toast.makeText(
                    this,
                    "Sin permiso de micrófono se graba sin sonido. Puedes darlo en Ajustes > Aplicaciones.",
                    Toast.LENGTH_LONG
                ).show()
            } else if (dashcam.soundOn && dashcam.isLoopRunning) {
                Toast.makeText(this, "El sonido se grabará desde el siguiente minuto", Toast.LENGTH_SHORT).show()
            }
            updateSoundButton()
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
            ui.postDelayed(this, 1_000L)
        }
    }

    private val resetSaveButton = Runnable { binding.btnSave.setText(R.string.save) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        keepControlsAwayFromNotch()

        storage = ClipStorage(this)
        dashcam = DashcamController(this, binding.previewView, storage, this)
        dashcam.soundOn = prefs.getBoolean(KEY_SOUND, true) // sonido activado por defecto
        updateSoundButton()

        binding.btnSave.setOnClickListener { saveEvent() }
        binding.btnStartStop.setOnClickListener { toggleRecording() }
        binding.btnSound.setOnClickListener { toggleSound() }
        binding.btnClips.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        binding.btnDark.setOnClickListener { setDarkMode(true) }
        binding.darkOverlay.setOnClickListener { setDarkMode(false) }

        val missing = buildList {
            if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
            if (dashcam.soundOn && !hasAudioPermission()) add(Manifest.permission.RECORD_AUDIO)
        }
        if (hasCameraPermission()) startCameraOnce()
        if (missing.isNotEmpty()) requestPermissions.launch(missing.toTypedArray())
    }

    private fun startCameraOnce() {
        if (cameraStarted || !hasCameraPermission()) return
        cameraStarted = true
        dashcam.startCamera()
    }

    // ---- Sonido ----

    private fun toggleSound() {
        if (!hasAudioPermission()) {
            // Quiere sonido pero no hay permiso: lo pedimos (si lo negó para siempre, el aviso le dice dónde darlo).
            dashcam.soundOn = true
            prefs.edit().putBoolean(KEY_SOUND, true).apply()
            requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        val on = !dashcam.soundOn
        dashcam.soundOn = on
        prefs.edit().putBoolean(KEY_SOUND, on).apply()
        Toast.makeText(
            this,
            if (on) "Sonido activado" else "Sonido desactivado: los vídeos se graban sin audio",
            Toast.LENGTH_SHORT
        ).show()
        updateSoundButton()
    }

    private fun updateSoundButton() {
        binding.btnSound.setText(
            when {
                !hasAudioPermission() -> R.string.sound_no_permission
                dashcam.soundOn -> R.string.sound_on
                else -> R.string.sound_off
            }
        )
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

        unregisterReceiver(batteryReceiver)
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        ui.removeCallbacks(tick)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---- DashcamController.Listener ----

    override fun onCameraReady() {
        // Como una dashcam de verdad: en cuanto la cámara está lista, empieza a grabar.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (!pausedForHeat) dashcam.startLoop()
        } else {
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
            binding.btnSave.setText(R.string.saved)
            ui.removeCallbacks(resetSaveButton)
            ui.postDelayed(resetSaveButton, 2_500L)
            Toast.makeText(
                this,
                "Guardado: el minuto anterior, el actual y el siguiente",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, R.string.not_recording, Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    private fun toggleRecording() {
        if (dashcam.isLoopRunning) {
            pausedForHeat = false
            dashcam.stopLoop()
        } else if (batteryTemp >= HEAT_STOP_C) {
            Toast.makeText(this, "El móvil está demasiado caliente para grabar", Toast.LENGTH_LONG).show()
        } else {
            pausedForHeat = false
            dashcam.startLoop()
        }
        updateStatus()
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
            if (dashcam.isCameraReady) dashcam.startLoop()
            Toast.makeText(this, "Temperatura correcta, sigo grabando", Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    // ---- Pantalla ----

    private fun updateStatus() {
        if (!::dashcam.isInitialized) return
        val recording = dashcam.isLoopRunning
        val blinkOn = (System.currentTimeMillis() / 500L) % 2L == 0L
        binding.recDot.visibility =
            if (recording && blinkOn) android.view.View.VISIBLE else android.view.View.INVISIBLE

        val savedCount = storage.savedClips().size
        val freeGb = storage.freeBytes() / (1024f * 1024f * 1024f)

        binding.statusText.text = when {
            pausedForHeat -> "En pausa por calor · se reanuda al enfriarse"
            recording -> {
                val s = dashcam.segmentElapsedMs / 1000L
                String.format(
                    spanish,
                    "REC %02d:%02d · %d guardados · %.1f GB libres",
                    s / 60, s % 60, savedCount, freeGb
                )
            }
            !dashcam.isCameraReady && !hasCameraPermission() -> getString(R.string.need_camera)
            !dashcam.isCameraReady -> getString(R.string.status_starting)
            else -> "Parado · $savedCount guardados"
        }

        if (batteryTemp > 0f) {
            binding.tempText.text = String.format(
                spanish,
                "%.0f °C · %d%%%s",
                batteryTemp, batteryLevel, if (charging) " · cargando" else ""
            )
            binding.tempText.setTextColor(
                ContextCompat.getColor(
                    this,
                    when {
                        batteryTemp >= HEAT_STOP_C -> R.color.rojo
                        batteryTemp >= HEAT_WARN_C -> R.color.ambar
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

        /** A partir de aquí avisamos en ámbar. */
        const val HEAT_WARN_C = 45f
        /** A partir de aquí paramos de grabar para no dañar la batería. */
        const val HEAT_STOP_C = 50f
        /** Por debajo de esto volvemos a grabar solos. */
        const val HEAT_RESUME_C = 42f
    }
}
