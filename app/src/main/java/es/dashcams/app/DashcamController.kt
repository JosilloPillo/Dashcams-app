package es.dashcams.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Cámara + grabación en bucle.
 *
 * Graba tramos de [SEGMENT_MS]. Al acabar uno empieza el siguiente y se borran los más viejos.
 * Al pulsar GUARDAR se protegen tres tramos: el anterior, el actual y el siguiente
 * (así siempre hay al menos un minuto antes y otro después del momento del aviso).
 */
class DashcamController(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val storage: ClipStorage,
    private val listener: Listener,
) {

    interface Listener {
        fun onCameraReady()
        fun onDashcamError(message: String)
        fun onDashcamInfo(message: String)
    }

    private val executor = ContextCompat.getMainExecutor(activity)
    private val handler = Handler(Looper.getMainLooper())

    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null

    private var recording: Recording? = null
    private var currentFile: File? = null
    private var currentStart = 0L
    private var lastFinished: File? = null
    private var loopWanted = false
    private var failures = 0

    /** Tramos que hay que mover a "guardados" cuando terminen de escribirse: fichero -> (evento, parte). */
    private val pendingProtection = mutableMapOf<File, Pair<String, Int>>()
    /** Si no es null, el próximo tramo que empiece se guardará como parte 3 de este evento. */
    private var nextSegmentEvent: String? = null

    /**
     * Sonido activado o no. Si hay permiso de micrófono, todos los tramos llevan pista de audio
     * y esto solo la silencia o la reactiva: el cambio se nota al momento, sin cortar la grabación.
     */
    var soundOn: Boolean = true
        set(value) {
            field = value
            recording?.mute(!value)
        }

    val isCameraReady: Boolean get() = videoCapture != null
    val isLoopRunning: Boolean get() = loopWanted
    val segmentElapsedMs: Long
        get() = if (recording != null && currentStart > 0) System.currentTimeMillis() - currentStart else 0L

    fun startCamera() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                val provider = future.get()

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
                val vc = VideoCapture.withOutput(recorder)

                val pv = Preview.Builder().build()
                pv.setSurfaceProvider(previewView.surfaceProvider)

                val rotation = previewView.display?.rotation ?: Surface.ROTATION_90
                vc.setTargetRotation(rotation)
                pv.setTargetRotation(rotation)

                // Primero intentamos la cámara trasera más angular; si da problemas, la normal.
                val widest = widestBackCamera(provider)
                val candidates = listOfNotNull(widest?.first, CameraSelector.DEFAULT_BACK_CAMERA)
                var camera: Camera? = null
                var lastError: Exception? = null
                for (selector in candidates) {
                    try {
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(activity, selector, pv, vc)
                        if (selector !== CameraSelector.DEFAULT_BACK_CAMERA && widest?.second == true) {
                            listener.onDashcamInfo("Usando la cámara gran angular")
                        }
                        break
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                val bound = camera ?: throw (lastError ?: IllegalStateException("sin cámara trasera"))

                applyWidestZoom(bound)

                videoCapture = vc
                preview = pv
                listener.onCameraReady()
            } catch (e: Exception) {
                listener.onDashcamError("No se pudo abrir la cámara: ${e.message}")
            }
        }, executor)
    }

    /**
     * Busca la cámara trasera con más campo de visión (sensor más ancho respecto a la focal).
     * Devuelve su selector y si es distinta de la cámara trasera principal.
     * Ojo: algunos móviles no dejan usar su gran angular a apps que no sean la de cámara de serie.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun widestBackCamera(provider: ProcessCameraProvider): Pair<CameraSelector, Boolean>? {
        var bestId: String? = null
        var bestRatio = 0f
        var firstBackId: String? = null
        for (info in provider.availableCameraInfos) {
            try {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                if (firstBackId == null) firstBackId = c2.cameraId
                val sensor = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: continue
                if (focal <= 0f) continue
                val ratio = maxOf(sensor.width, sensor.height) / focal
                if (ratio > bestRatio) {
                    bestRatio = ratio
                    bestId = c2.cameraId
                }
            } catch (e: Exception) {
                // Cámara rara que no informa bien: la saltamos.
            }
        }
        val id = bestId ?: return null
        val selector = CameraSelector.Builder()
            .addCameraFilter { infos ->
                infos.filter { info ->
                    try {
                        Camera2CameraInfo.from(info).cameraId == id
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            .build()
        return selector to (id != firstBackId)
    }

    /** En móviles que juntan varias lentes en una sola cámara, el gran angular se consigue con zoom < 1x. */
    private fun applyWidestZoom(camera: Camera) {
        var applied = false
        camera.cameraInfo.zoomState.observe(activity) { state ->
            if (!applied && state != null && state.minZoomRatio < 0.99f) {
                applied = true
                camera.cameraControl.setZoomRatio(state.minZoomRatio)
                listener.onDashcamInfo(
                    String.format(java.util.Locale.forLanguageTag("es-ES"), "Gran angular activado (zoom %.1fx)", state.minZoomRatio)
                )
            }
        }
    }

    /** El móvil se ha girado 180º (soporte del revés): los próximos tramos salen derechos. */
    fun updateRotation(rotation: Int) {
        videoCapture?.setTargetRotation(rotation)
        preview?.setTargetRotation(rotation)
    }

    fun startLoop() {
        if (loopWanted) return
        loopWanted = true
        failures = 0
        trim()
        // Si aún se está cerrando el tramo anterior, el siguiente arrancará al terminar de cerrarse.
        if (recording == null) startSegment()
    }

    fun stopLoop() {
        loopWanted = false
        nextSegmentEvent = null
        handler.removeCallbacksAndMessages(null)
        recording?.stop()
    }

    /** Devuelve false si no se está grabando. */
    fun saveEvent(): Boolean {
        val current = currentFile
        if (!loopWanted || current == null) return false
        val stamp = ClipStorage.stamp(System.currentTimeMillis())

        // Parte 1: el minuto anterior (ya está cerrado, se mueve ahora mismo).
        lastFinished?.let { previous ->
            if (storage.protect(previous, stamp, 1) != null) lastFinished = null
        }
        // Parte 2: el tramo que se está grabando ahora (se mueve al cerrarse).
        if (current !in pendingProtection) pendingProtection[current] = stamp to 2
        // Parte 3: el siguiente tramo.
        nextSegmentEvent = stamp
        return true
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // se comprueba con hasAudioPermission()
    private fun startSegment() {
        val vc = videoCapture ?: return
        val file = storage.newSegmentFile()
        val options = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(SEGMENT_MS)
            .build()

        nextSegmentEvent?.let { pendingProtection[file] = it to 3 }
        nextSegmentEvent = null

        try {
            var pending = vc.output.prepareRecording(activity, options)
            // Con permiso de micrófono el tramo lleva audio; si el usuario lo ha apagado, va silenciado.
            val withAudio = hasAudioPermission()
            if (withAudio) pending = pending.withAudioEnabled()

            val rec = pending.start(executor) { event ->
                if (event is VideoRecordEvent.Start) {
                    currentStart = System.currentTimeMillis()
                } else if (event is VideoRecordEvent.Finalize) {
                    onFinalized(file, event)
                }
            }
            if (withAudio && !soundOn) rec.mute(true)
            recording = rec
            currentFile = file
            currentStart = System.currentTimeMillis()
        } catch (e: Exception) {
            pendingProtection.remove(file)
            loopWanted = false
            listener.onDashcamError("No se pudo empezar a grabar: ${e.message}")
        }
    }

    private fun onFinalized(file: File, event: VideoRecordEvent.Finalize) {
        if (currentFile == file) {
            currentFile = null
            recording = null
            currentStart = 0L
        }

        val error = event.error
        val ok = error == VideoRecordEvent.Finalize.ERROR_NONE ||
            error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED

        val hasData = file.exists() && file.length() > 0
        if (!hasData) file.delete()

        val pending = pendingProtection.remove(file)
        if (hasData) {
            if (pending != null) {
                storage.protect(file, pending.first, pending.second)
                if (lastFinished == file) lastFinished = null
            } else {
                lastFinished = file
            }
        }

        trim()

        if (!loopWanted) return

        if (ok) {
            failures = 0
            startSegment()
        } else {
            failures++
            if (failures >= MAX_FAILURES) {
                loopWanted = false
                listener.onDashcamError(
                    "La grabación ha fallado varias veces seguidas (código $error). Cierra y vuelve a abrir la app."
                )
            } else {
                handler.postDelayed({ if (loopWanted && recording == null) startSegment() }, RETRY_DELAY_MS)
            }
        }
    }

    private fun trim() {
        val keep = setOfNotNull(currentFile, lastFinished) + pendingProtection.keys
        storage.trimLoop(MAX_LOOP_BYTES, MIN_FREE_BYTES, keep)
    }

    companion object {
        const val SEGMENT_MS = 60_000L
        /** Espacio máximo para el bucle: ~3 GB (unos 30 minutos en Full HD). */
        const val MAX_LOOP_BYTES = 3L * 1024 * 1024 * 1024
        /** Siempre dejamos al menos 500 MB libres en el móvil. */
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
        private const val MAX_FAILURES = 3
        private const val RETRY_DELAY_MS = 1_500L
    }
}
