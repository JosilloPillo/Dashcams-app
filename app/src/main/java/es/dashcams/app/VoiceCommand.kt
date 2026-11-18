package es.dashcams.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * Control por voz SIN internet: el reconocimiento se hace dentro del móvil con Vosk.
 *
 * El modelo de español (unos 39 MB) no va dentro de la app: se descarga una sola vez cuando
 * el usuario activa la opción. Lo que se dice no sale nunca del móvil.
 *
 * La frase se elige en Ajustes (ver [Phrase]) y solo cuenta si se dice sola, con una pausa antes y después.
 */
class VoiceCommand(context: Context, private val listener: Listener) {

    interface Listener {
        fun onVoiceCommand()
        fun onVoiceState(listening: Boolean, message: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val baseDir = File(context.filesDir, "voz")
    private val modelDir = File(baseDir, MODEL_NAME)
    private val readyMark = File(baseDir, "listo")

    @Volatile private var model: Model? = null
    private var service: SpeechService? = null
    private var wanted = false
    private var loading = false
    private var lastTrigger = 0L
    /** Tras un fallo no se reintenta solo; hay que volver a activar la voz en Ajustes. */
    var failed = false
        private set

    var isListening = false
        private set

    var isDownloading = false
        private set

    val isModelReady: Boolean get() = readyMark.exists() && modelDir.isDirectory

    init {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
        } catch (e: Throwable) {
            // si la librería no carga, start() avisará
        }
    }

    /** Descarga y descomprime el modelo de español. [onProgress] recibe 0-100. */
    fun downloadModel(onProgress: (Int) -> Unit, onDone: (ok: Boolean, error: String?) -> Unit) {
        if (isDownloading) return
        isDownloading = true
        thread(name = "descarga-voz") {
            val zip = File(baseDir, "modelo.zip")
            try {
                baseDir.mkdirs()
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(zip).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPercent = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 90 / total).toInt()
                                if (p != lastPercent) {
                                    lastPercent = p
                                    main.post { onProgress(p) }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()

                main.post { onProgress(92) }
                unzip(zip, baseDir)
                zip.delete()
                if (!File(modelDir, "conf").isDirectory) throw IllegalStateException("modelo incompleto")
                readyMark.writeText("ok")
                main.post {
                    isDownloading = false
                    onProgress(100)
                    onDone(true, null)
                }
            } catch (e: Exception) {
                zip.delete()
                main.post {
                    isDownloading = false
                    onDone(false, "No se ha podido descargar el reconocedor de voz. Revisa la conexión e inténtalo otra vez.")
                }
            }
        }
    }

    /** Vuelve a permitir intentarlo tras un fallo (al reactivar la voz en Ajustes). */
    fun resetFailure() {
        failed = false
    }

    /** Empieza a escuchar (si el modelo está descargado). Se puede llamar varias veces. */
    fun start() {
        wanted = true
        if (service != null || loading || failed || !isModelReady) return
        loading = true
        thread(name = "carga-voz") {
            try {
                val m = model ?: Model(modelDir.absolutePath).also { model = it }
                val recognizer = Recognizer(m, SAMPLE_RATE, grammar())
                main.post {
                    loading = false
                    if (!wanted || service != null) {
                        recognizer.close()
                        return@post
                    }
                    try {
                        val s = SpeechService(recognizer, SAMPLE_RATE)
                        s.startListening(recognitionListener)
                        service = s
                        isListening = true
                        listener.onVoiceState(true, null)
                    } catch (e: Exception) {
                        recognizer.close()
                        failed = true
                        listener.onVoiceState(false, "No se ha podido usar el micrófono para la voz")
                    }
                }
            } catch (e: Throwable) {
                main.post {
                    loading = false
                    wanted = false
                    failed = true
                    listener.onVoiceState(false, "No se ha podido cargar el reconocedor de voz")
                }
            }
        }
    }

    fun stop() {
        wanted = false
        service?.let {
            try {
                it.stop()
                it.shutdown()
            } catch (e: Exception) {
            }
        }
        service = null
        if (isListening) {
            isListening = false
            listener.onVoiceState(false, null)
        }
    }

    /** Libera el modelo de memoria (al cerrar la app). */
    fun release() {
        stop()
        try {
            model?.close()
        } catch (e: Exception) {
        }
        model = null
    }

    private val recognitionListener = object : RecognitionListener {
        // Los resultados parciales se ignoran: solo cuenta la frase completa, dicha sola y con pausa.
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) = check(hypothesis)
        override fun onFinalResult(hypothesis: String?) = check(hypothesis)
        override fun onError(exception: Exception?) {
            service = null
            isListening = false
            failed = true
            listener.onVoiceState(false, "El reconocedor de voz se ha parado")
        }
        override fun onTimeout() {}
    }

    /**
     * Solo salta si lo reconocido es EXACTAMENTE la frase elegida, sin nada más.
     * Todo lo que no es la frase sale como "[unk]": si aparece, es que iba dentro de una conversación.
     */
    private fun check(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = try {
            JSONObject(hypothesis).optString("text")
        } catch (e: Exception) {
            return
        }
        val heard = normalize(text).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (heard.isEmpty() || heard.contains("[unk]")) return
        if (heard.joinToString(" ") != normalize(phrase.text)) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < DEBOUNCE_MS) return
        lastTrigger = now
        listener.onVoiceCommand()
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun unzip(zip: File, target: File) {
        val root = target.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(target, entry.name)
                if (!out.canonicalPath.startsWith(root)) throw SecurityException("zip no válido")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        private const val MODEL_NAME = "vosk-model-small-es-0.42"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        private const val SAMPLE_RATE = 16000f
        private const val DEBOUNCE_MS = 4_000L
    }

    /** Frases que se pueden elegir en Ajustes. Poco habituales a propósito. */
    enum class Phrase(val text: String, val label: String) {
        EMERGENCIA("grabación de emergencia", "«Grabación de emergencia»"),
        CODIGO_ROJO("código rojo", "«Código rojo»"),
        ALERTA("alerta alerta", "«Alerta, alerta»");

        companion object {
            fun fromName(name: String?): Phrase = values().firstOrNull { it.name == name } ?: EMERGENCIA
        }
    }

    /** Frase elegida. Si se cambia mientras escucha, se vuelve a arrancar el reconocedor con la nueva. */
    var phrase: Phrase = Phrase.EMERGENCIA
        set(value) {
            if (field == value) return
            field = value
            if (service != null) stop()
        }

    /** Solo la frase elegida (con y sin tildes) y "[unk]" para todo lo demás. */
    private fun grammar(): String {
        val variants = linkedSetOf(phrase.text, normalize(phrase.text))
        val array = JSONArray()
        variants.forEach { array.put(it) }
        array.put("[unk]")
        return array.toString()
    }
}
