package es.dashcams.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.effects.OverlayEffect
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
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * Cámara + grabación en bucle.
 *
 * Graba tramos de [SEGMENT_MS]. Al acabar uno empieza el siguiente y se borran los más viejos.
 * Al pulsar GUARDAR se protegen tres tramos: el anterior, el actual y el siguiente
 * (así siempre hay al menos un minuto antes y otro después del momento del aviso).
 *
 * La cámara va ligada a [owner], no a la pantalla: así puede seguir grabando aunque la app
 * pase a segundo plano. La vista previa se pone y se quita con [attachPreview] / [detachPreview].
 */
class DashcamController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val storage: ClipStorage,
    private val listener: Listener,
) {

    interface Listener {
        fun onCameraReady()
        fun onDashcamError(message: String)
        fun onDashcamInfo(message: String)
    }

    private val executor = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())

    // Hilo y efecto que dibujan fecha, velocidad, coordenadas y marca dentro del vídeo
    private val overlayThread = HandlerThread("datos-en-video").apply { start() }
    private val overlayHandler = Handler(overlayThread.looper)
    private val renderer = OverlayRenderer(context)
    private var overlayEffect: OverlayEffect? = null

    /** true si este móvil acepta dibujar los datos dentro del vídeo. */
    var overlayActive = false
        private set

    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var previewView: PreviewView? = null
    private var targetRotation = Surface.ROTATION_90

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

    /** Qué graba la app. */
    enum class CameraMode {
        /** Cámara trasera del móvil: la carretera. */
        ROAD,
        /** Cámara frontal del móvil: el interior del coche. */
        INSIDE,
        /** Las dos a la vez en un solo vídeo: carretera de fondo e interior en una ventanita. */
        BOTH;

        companion object {
            fun fromName(name: String?): CameraMode = values().firstOrNull { it.name == name } ?: ROAD
        }
    }

    /** Lo que ha elegido el usuario. */
    var cameraMode: CameraMode = CameraMode.ROAD
        private set

    /** true si de verdad están grabando las dos cámaras (el móvil lo ha aceptado). */
    var bothActive: Boolean = false
        private set

    /** true = solo cámara frontal (interior). */
    val useFrontCamera: Boolean get() = cameraMode == CameraMode.INSIDE

    /** Si el móvil permite usar la cámara trasera y la frontal a la vez. */
    var supportsBothCameras: Boolean = false
        private set

    private var currentCamera: Camera? = null

    /** Para elegir la cámara antes de abrirla por primera vez (lo guardado en Ajustes). */
    fun setInitialCamera(mode: CameraMode) {
        if (videoCapture == null) cameraMode = mode
    }

    val isCameraReady: Boolean get() = videoCapture != null
    val isLoopRunning: Boolean get() = loopWanted
    val segmentElapsedMs: Long
        get() = if (recording != null && currentStart > 0) System.currentTimeMillis() - currentStart else 0L

    /** Muestra la imagen de la cámara en [view]. */
    fun attachPreview(view: PreviewView) {
        previewView = view
        view.display?.rotation?.let { targetRotation = it }
        preview?.setSurfaceProvider(view.surfaceProvider)
    }

    /** Quita la imagen de la pantalla (la app pasa a segundo plano); la grabación sigue. */
    fun detachPreview() {
        previewView = null
        preview?.setSurfaceProvider(null)
    }

    fun startCamera() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                supportsBothCameras = try {
                    provider.availableConcurrentCameraInfos.isNotEmpty()
                } catch (e: Exception) {
                    false
                }

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
                val vc = VideoCapture.withOutput(recorder)

                // La vista previa en 16:9, igual que el vídeo (por defecto Android la da en 4:3)
                val pv = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .build()
                    )
                    .build()
                previewView?.let { pv.setSurfaceProvider(it.surfaceProvider) }

                previewView?.display?.rotation?.let { targetRotation = it }
                vc.setTargetRotation(targetRotation)
                pv.setTargetRotation(targetRotation)

                overlayEffect?.close()
                overlayEffect = null

                val effectTargets = listOf(
                    CameraEffect.VIDEO_CAPTURE,
                    CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                    NO_EFFECT
                )

                var camera: Camera? = null
                var lastError: Exception? = null
                bothActive = false

                // Modo "las dos a la vez": la carretera ocupa todo el vídeo y el interior va en una ventanita.
                if (cameraMode == CameraMode.BOTH) {
                    val pair = if (supportsBothCameras) concurrentPair(provider) else null
                    if (pair != null) {
                        for (targets in effectTargets) {
                            var effect: OverlayEffect? = null
                            try {
                                provider.unbindAll()
                                val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc)
                                if (targets != NO_EFFECT) {
                                    effect = newOverlayEffect(targets)
                                    group.addEffect(effect)
                                }
                                val built = group.build()
                                val road = ConcurrentCamera.SingleCameraConfig(
                                    pair.first,
                                    built,
                                    CompositionSettings.Builder()
                                        .setOffset(0f, 0f)
                                        .setScale(1f, 1f)
                                        .build(),
                                    owner
                                )
                                val inside = ConcurrentCamera.SingleCameraConfig(
                                    pair.second,
                                    built,
                                    CompositionSettings.Builder()
                                        .setOffset(0f, INSIDE_WINDOW_Y)
                                        .setScale(INSIDE_WINDOW_SCALE, INSIDE_WINDOW_SCALE)
                                        .build(),
                                    owner
                                )
                                val concurrent = provider.bindToLifecycle(listOf(road, inside))
                                camera = concurrent.cameras.firstOrNull() ?: throw IllegalStateException("sin cámaras")
                                overlayEffect = effect
                                overlayActive = effect != null
                                bothActive = true
                                listener.onDashcamInfo("Grabando carretera e interior a la vez")
                                break
                            } catch (e: Exception) {
                                lastError = e
                                try {
                                    effect?.close()
                                } catch (ignored: Exception) {
                                }
                            }
                        }
                    }
                    if (camera == null) {
                        listener.onDashcamInfo(
                            if (pair == null) "Este móvil no deja usar las dos cámaras a la vez: grabando solo la carretera"
                            else "No se han podido abrir las dos cámaras a la vez: grabando solo la carretera"
                        )
                    }
                }

                // Una sola cámara: primero la más angular del lado elegido; si da problemas, la normal.
                // Y para cada una, primero con los datos dentro del vídeo; si el móvil no puede, sin ellos.
                if (camera == null) {
                    val facing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
                    val fallback = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    val widest = widestCamera(provider, facing)
                    val candidates = listOfNotNull(widest?.first, fallback)
                    search@ for (selector in candidates) {
                        for (targets in effectTargets) {
                            var effect: OverlayEffect? = null
                            try {
                                provider.unbindAll()
                                val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(vc)
                                if (targets != NO_EFFECT) {
                                    effect = newOverlayEffect(targets)
                                    group.addEffect(effect)
                                }
                                camera = provider.bindToLifecycle(owner, selector, group.build())
                                overlayEffect = effect
                                overlayActive = effect != null
                                if (selector !== fallback && widest?.second == true) {
                                    listener.onDashcamInfo("Usando la cámara gran angular")
                                }
                                break@search
                            } catch (e: Exception) {
                                lastError = e
                                try {
                                    effect?.close()
                                } catch (ignored: Exception) {
                                }
                            }
                        }
                    }
                }
                val bound = camera ?: throw (lastError ?: IllegalStateException("sin cámara"))
                currentCamera = bound
                if (!overlayActive) {
                    listener.onDashcamInfo("Este móvil no permite poner la fecha y los datos dentro del vídeo")
                }

                // Con las dos cámaras no tocamos el zoom: el móvil ya va justo de recursos.
                if (!bothActive) applyWidestZoom(bound)

                videoCapture = vc
                preview = pv
                listener.onCameraReady()
            } catch (e: Exception) {
                listener.onDashcamError("No se pudo abrir la cámara: ${e.message}")
            }
        }, executor)
    }

    private fun newOverlayEffect(targets: Int): OverlayEffect {
        val effect = OverlayEffect(targets, 0, overlayHandler) { error ->
            handler.post { listener.onDashcamInfo("Fallo al dibujar los datos en el vídeo: ${error.message}") }
        }
        effect.setOnDrawListener { frame -> renderer.draw(frame) }
        return effect
    }

    /**
     * Cambia entre la carretera (cámara trasera del móvil), el interior (frontal) o las dos a la vez.
     * Corta el tramo actual y vuelve a abrir la cámara.
     */
    fun switchCamera(mode: CameraMode) {
        if (mode == cameraMode) return
        cameraMode = mode
        stopLoop()
        videoCapture = null
        preview = null
        currentCamera = null
        startCamera()
    }

    /** Cierra la cámara y libera el hilo de los datos (al cerrar la app del todo). */
    fun release() {
        stopLoop()
        try {
            overlayEffect?.close()
        } catch (e: Exception) {
        }
        overlayEffect = null
        overlayThread.quitSafely()
    }

    /**
     * Busca la cámara de un lado ([lensFacing]) con más campo de visión (sensor más ancho respecto a la focal).
     * Devuelve su selector y si es distinta de la cámara principal de ese lado.
     * Ojo: algunos móviles no dejan usar su gran angular a apps que no sean la de cámara de serie.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun widestCamera(provider: ProcessCameraProvider, lensFacing: Int): Pair<CameraSelector, Boolean>? {
        var bestId: String? = null
        var bestRatio = 0f
        var firstId: String? = null
        for (info in provider.availableCameraInfos) {
            try {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                if (facing != lensFacing) continue
                if (firstId == null) firstId = c2.cameraId
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
        return selectorForId(id) to (id != firstId)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorForId(id: String): CameraSelector =
        CameraSelector.Builder()
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

    /**
     * Busca, entre las combinaciones que el móvil deja abrir a la vez, una con cámara trasera y frontal.
     * Devuelve (carretera, interior) o null si no hay ninguna.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun concurrentPair(provider: ProcessCameraProvider): Pair<CameraSelector, CameraSelector>? {
        val combos = try {
            provider.availableConcurrentCameraInfos
        } catch (e: Exception) {
            return null
        }
        for (combo in combos) {
            var backId: String? = null
            var frontId: String? = null
            for (info in combo) {
                try {
                    val c2 = Camera2CameraInfo.from(info)
                    when (c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> if (backId == null) backId = c2.cameraId
                        CameraCharacteristics.LENS_FACING_FRONT -> if (frontId == null) frontId = c2.cameraId
                    }
                } catch (e: Exception) {
                }
            }
            if (backId != null && frontId != null) return selectorForId(backId) to selectorForId(frontId)
        }
        return null
    }

    /** En móviles que juntan varias lentes en una sola cámara, el gran angular se consigue con zoom < 1x. */
    private fun applyWidestZoom(camera: Camera) {
        var applied = false
        camera.cameraInfo.zoomState.observe(owner) { state ->
            if (camera !== currentCamera) return@observe // es de una cámara que ya no usamos
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
        targetRotation = rotation
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
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
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
            var pending = vc.output.prepareRecording(context, options)
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
            activeFile = file
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
        if (activeFile == file) activeFile = null

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
        private const val NO_EFFECT = 0

        /** Ventanita del interior: 30 % del tamaño, centrada arriba (cielo), lejos de los datos de abajo. */
        private const val INSIDE_WINDOW_SCALE = 0.3f
        private const val INSIDE_WINDOW_Y = 0.65f

        /** Tramo que se está grabando ahora mismo (la pantalla Clips no lo enseña). */
        @Volatile
        var activeFile: File? = null
            private set
    }
}
