package es.dashcams.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
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
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
 * Al pulsar GUARDAR se protege el minuto que se está grabando. Si el aviso cae en sus primeros
 * 20 s, también el anterior; si cae en los últimos 20 s, también el siguiente.
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
    /** Efecto del vídeo del interior cuando se graban las dos cámaras. */
    private var overlayEffect2: OverlayEffect? = null

    /** Grabación del interior en paralelo (solo en el modo de dos cámaras). */
    private var insideTrack: InsideTrack? = null

    /** true si este móvil acepta dibujar los datos dentro del vídeo. */
    var overlayActive = false
        private set

    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var previewView: PreviewView? = null
    /** Ventanita con la imagen del interior (solo con las dos cámaras). */
    private var insidePreviewView: PreviewView? = null
    private var insidePreview: Preview? = null
    private var targetRotation = Surface.ROTATION_90

    private var recording: Recording? = null
    private var currentFile: File? = null
    private var currentStart = 0L
    private var lastFinished: File? = null
    private var loopWanted = false
    private var failures = 0

    /** Tramos que hay que mover a "guardados" cuando terminen de escribirse: fichero -> (evento, parte). */
    private val pendingProtection = mutableMapOf<File, Pair<String, Int>>()
    /** Si no es null, el próximo tramo que empiece se guardará como «minuto siguiente» de este evento. */
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
        /** Las dos a la vez, en dos vídeos separados: carretera (_F) e interior (_I). */
        BOTH;

        companion object {
            fun fromName(name: String?): CameraMode = values().firstOrNull { it.name == name } ?: ROAD
        }
    }

    /** Lo que ha elegido el usuario. */
    var cameraMode: CameraMode = CameraMode.ROAD
        private set

    /** Por qué no se han podido abrir las dos cámaras la última vez (para enseñarlo en Ajustes). */
    var bothProblem: String? = null
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
    fun attachPreview(view: PreviewView, insideView: PreviewView? = null) {
        previewView = view
        insidePreviewView = insideView
        view.display?.rotation?.let { targetRotation = it }
        preview?.setSurfaceProvider(view.surfaceProvider)
        insideView?.let { insidePreview?.setSurfaceProvider(it.surfaceProvider) }
    }

    /** Quita la imagen de la pantalla (la app pasa a segundo plano); la grabación sigue. */
    fun detachPreview() {
        previewView = null
        insidePreviewView = null
        preview?.setSurfaceProvider(null)
        insidePreview?.setSurfaceProvider(null)
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

                previewView?.display?.rotation?.let { targetRotation = it }
                var (pv, vc) = makeUseCases(hdOnly = false, inside = useFrontCamera)

                closeEffects()
                insideTrack?.stop()
                insideTrack = null
                insidePreview?.setSurfaceProvider(null)
                insidePreview = null

                val effectTargets = listOf(
                    CameraEffect.VIDEO_CAPTURE,
                    CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                    NO_EFFECT
                )

                var camera: Camera? = null
                var lastError: Exception? = null
                bothActive = false
                bothProblem = null

                // Modo "las dos a la vez": dos vídeos separados, uno de la carretera (_F) y otro del interior (_I).
                if (cameraMode == CameraMode.BOTH) {
                    val pair = if (supportsBothCameras) concurrentPair(provider) else null
                    if (pair == null) {
                        bothProblem = if (supportsBothCameras) {
                            "el móvil no ofrece la cámara trasera y la frontal juntas"
                        } else {
                            "el móvil no permite abrir dos cámaras a la vez (Android no se lo deja a otras apps)"
                        }
                    } else {
                        // Con dos cámaras Android solo garantiza hasta 720p: pedir más hace que falle.
                        for (withData in listOf(true, false)) {
                            var roadEffect: OverlayEffect? = null
                            var insideEffect: OverlayEffect? = null
                            try {
                                provider.unbindAll()
                                val (roadPv, roadVc) = makeUseCases(hdOnly = true, inside = false)
                                // Con dos cámaras, el interior también en 16:9 a 720p: es el tamaño que
                                // Android garantiza para dos cámaras (el 4:3 puede no estar admitido).
                                // Cada cámara necesita su propia vista previa además del vídeo: sin ella,
                                // CameraX intenta meterlo todo en la cámara de la carretera y falla.
                                val (insidePv, insideVc) = makeUseCases(
                                    hdOnly = true, inside = false, view = insidePreviewView
                                )
                                val roadGroup = UseCaseGroup.Builder().addUseCase(roadPv).addUseCase(roadVc)
                                val insideGroup = UseCaseGroup.Builder().addUseCase(insidePv).addUseCase(insideVc)
                                if (withData) {
                                    roadEffect = newOverlayEffect(CameraEffect.VIDEO_CAPTURE)
                                    insideEffect = newOverlayEffect(CameraEffect.VIDEO_CAPTURE)
                                    roadGroup.addEffect(roadEffect)
                                    insideGroup.addEffect(insideEffect)
                                }
                                val concurrent = provider.bindToLifecycle(
                                    listOf(
                                        ConcurrentCamera.SingleCameraConfig(pair.first, roadGroup.build(), owner),
                                        ConcurrentCamera.SingleCameraConfig(pair.second, insideGroup.build(), owner)
                                    )
                                )
                                camera = concurrent.cameras.firstOrNull() ?: throw IllegalStateException("sin cámaras")
                                pv = roadPv
                                vc = roadVc
                                overlayEffect = roadEffect
                                overlayEffect2 = insideEffect
                                overlayActive = roadEffect != null
                                insideTrack = InsideTrack(insideVc)
                                insidePreview = insidePv
                                bothActive = true
                                listener.onDashcamInfo("Grabando carretera e interior a la vez, en dos vídeos")
                                break
                            } catch (e: Exception) {
                                lastError = e
                                bothProblem = (pairIds?.let { "[cámaras $it] " } ?: "") +
                                    (e.message ?: e.javaClass.simpleName)
                                for (effect in listOf(roadEffect, insideEffect)) {
                                    try {
                                        effect?.close()
                                    } catch (ignored: Exception) {
                                    }
                                }
                            }
                        }
                    }
                    if (camera == null) {
                        listener.onDashcamInfo(
                            "No se han podido abrir las dos cámaras a la vez: grabando solo la carretera" +
                                (bothProblem?.let { ". Motivo: $it" } ?: "")
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

    /**
     * Vista previa y grabadora. La carretera va en 16:9; el interior en 4:3, que es la forma del
     * sensor de la cámara frontal (en 16:9 se pierde arriba y abajo). Con [hdOnly], como mucho 720p.
     */
    private fun makeUseCases(
        hdOnly: Boolean,
        inside: Boolean,
        view: PreviewView? = previewView
    ): Pair<Preview, VideoCapture<Recorder>> {
        val vc = makeCapture(hdOnly, inside)
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (inside) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )
        if (hdOnly) {
            selector.setResolutionStrategy(
                ResolutionStrategy(
                    if (inside) Size(960, 720) else Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
            )
        }
        val pv = Preview.Builder().setResolutionSelector(selector.build()).build()
        view?.let { pv.setSurfaceProvider(it.surfaceProvider) }
        pv.setTargetRotation(targetRotation)
        return pv to vc
    }

    private fun makeCapture(hdOnly: Boolean, inside: Boolean): VideoCapture<Recorder> {
        val quality = if (hdOnly) {
            QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
        } else {
            QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(quality)
            .setAspectRatio(if (inside) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9)
            .build()
        return VideoCapture.withOutput(recorder).also { it.setTargetRotation(targetRotation) }
    }

    private fun closeEffects() {
        for (effect in listOf(overlayEffect, overlayEffect2)) {
            try {
                effect?.close()
            } catch (e: Exception) {
            }
        }
        overlayEffect = null
        overlayEffect2 = null
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
        insideTrack = null
        videoCapture = null
        preview = null
        currentCamera = null
        startCamera()
    }

    /** Cierra la cámara y libera el hilo de los datos (al cerrar la app del todo). */
    fun release() {
        stopLoop()
        closeEffects()
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
            // Se usan los selectores que da el propio móvil para cada cámara de la combinación.
            // Con un filtro hecho a mano, CameraX acababa mandando las dos grabaciones a la cámara 0.
            val back = combo.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            val front = combo.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            if (back != null && front != null) {
                pairIds = "${idOf(back)} y ${idOf(front)}"
                return back.cameraSelector to front.cameraSelector
            }
        }
        return null
    }

    /** Ids de las cámaras elegidas para el modo doble (para el motivo del fallo). */
    private var pairIds: String? = null

    @OptIn(ExperimentalCamera2Interop::class)
    private fun idOf(info: CameraInfo): String = try {
        Camera2CameraInfo.from(info).cameraId
    } catch (e: Exception) {
        "?"
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
        insideTrack?.start()
    }

    fun stopLoop() {
        loopWanted = false
        nextSegmentEvent = null
        handler.removeCallbacksAndMessages(null)
        recording?.stop()
        insideTrack?.stop()
    }

    /** Devuelve false si no se está grabando. */
    fun saveEvent(): Boolean {
        val current = currentFile
        if (!loopWanted || current == null) return false
        val now = System.currentTimeMillis()
        val stamp = ClipStorage.stamp(now)
        val elapsed = if (currentStart > 0) now - currentStart else SEGMENT_MS / 2

        // Siempre: el minuto que se está grabando ahora (se mueve al cerrarse).
        if (current !in pendingProtection) pendingProtection[current] = stamp to 2
        // En los primeros 20 s del minuto, lo que pasó está sobre todo en el anterior: también ese.
        if (elapsed < EDGE_MS) {
            lastFinished?.let { previous ->
                if (storage.protect(previous, stamp, 1) != null) lastFinished = null
            }
        }
        // En los últimos 20 s, lo que viene después cae en el siguiente: también ese.
        if (elapsed > SEGMENT_MS - EDGE_MS) nextSegmentEvent = stamp
        // Y lo mismo con el vídeo del interior, si se están grabando las dos cámaras.
        insideTrack?.save(stamp)
        return true
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // se comprueba con hasAudioPermission()
    private fun startSegment() {
        val vc = videoCapture ?: return
        // F = carretera (cámara trasera del móvil), I = interior (frontal).
        // Con las dos cámaras, este es el de la carretera; el del interior lo lleva InsideTrack.
        val camTag = if (useFrontCamera) "I" else "F"
        val file = storage.newSegmentFile(camTag)
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
        val keep = setOfNotNull(currentFile, lastFinished) + pendingProtection.keys +
            (insideTrack?.keepFiles ?: emptySet())
        storage.trimLoop(MAX_LOOP_BYTES, MIN_FREE_BYTES, keep)
    }

    companion object {
        const val SEGMENT_MS = 60_000L
        /** Si se guarda en los primeros o últimos 20 s de un minuto, se guarda también el de al lado. */
        private const val EDGE_MS = 20_000L
        /** Espacio máximo para el bucle: ~3 GB (unos 30 minutos en Full HD). */
        const val MAX_LOOP_BYTES = 3L * 1024 * 1024 * 1024
        /** Siempre dejamos al menos 500 MB libres en el móvil. */
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
        private const val MAX_FAILURES = 3
        private const val RETRY_DELAY_MS = 1_500L
        private const val NO_EFFECT = 0

        /** Tramo que se está grabando ahora mismo (la pantalla Clips no lo enseña). */
        @Volatile
        var activeFile: File? = null
            private set

        /** Tramo del interior que se está grabando ahora (modo de dos cámaras). */
        @Volatile
        var activeInsideFile: File? = null
            private set

        /** Todos los tramos que se están escribiendo ahora mismo. */
        val activeFiles: Set<File> get() = setOfNotNull(activeFile, activeInsideFile)
    }

    /**
     * Vídeo del interior cuando se graban las dos cámaras: va en su propio fichero (_I) y con su
     * propio bucle de tramos de 1 minuto. Sin audio: el micrófono ya lo usa el vídeo de la carretera.
     */
    private inner class InsideTrack(private val vc: VideoCapture<Recorder>) {
        private var recording: Recording? = null
        private var currentFile: File? = null
        private var lastFinished: File? = null
        private var nextEvent: String? = null
        private var running = false
        private var failures = 0
        private var startedAt = 0L

        val keepFiles: Set<File> get() = setOfNotNull(currentFile, lastFinished)

        fun start() {
            if (running) return
            running = true
            failures = 0
            if (recording == null) startSegment()
        }

        fun stop() {
            running = false
            nextEvent = null
            recording?.stop()
        }

        fun save(stamp: String) {
            val current = currentFile ?: return
            val elapsed = if (startedAt > 0) System.currentTimeMillis() - startedAt else SEGMENT_MS / 2
            if (current !in pendingProtection) pendingProtection[current] = stamp to 2
            if (elapsed < EDGE_MS) {
                lastFinished?.let { previous ->
                    if (storage.protect(previous, stamp, 1) != null) lastFinished = null
                }
            }
            if (elapsed > SEGMENT_MS - EDGE_MS) nextEvent = stamp
        }

        private fun startSegment() {
            val file = storage.newSegmentFile("I")
            val options = FileOutputOptions.Builder(file).setDurationLimitMillis(SEGMENT_MS).build()
            nextEvent?.let { pendingProtection[file] = it to 3 }
            nextEvent = null
            try {
                recording = vc.output.prepareRecording(context, options).start(executor) { event ->
                    if (event is VideoRecordEvent.Finalize) onFinalized(file, event)
                }
                currentFile = file
                startedAt = System.currentTimeMillis()
                activeInsideFile = file
            } catch (e: Exception) {
                pendingProtection.remove(file)
                running = false
                listener.onDashcamInfo("No se ha podido grabar el interior: ${e.message}")
            }
        }

        private fun onFinalized(file: File, event: VideoRecordEvent.Finalize) {
            if (currentFile == file) {
                currentFile = null
                recording = null
            }
            if (activeInsideFile == file) activeInsideFile = null

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

            if (!running || insideTrack !== this) return
            if (ok) {
                failures = 0
                startSegment()
            } else {
                failures++
                if (failures >= MAX_FAILURES) {
                    running = false
                    listener.onDashcamInfo("El vídeo del interior ha dejado de grabarse (código $error)")
                } else {
                    handler.postDelayed({ if (running && recording == null) startSegment() }, RETRY_DELAY_MS)
                }
            }
        }
    }
}
