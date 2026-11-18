package es.dashcams.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.textfield.TextInputEditText
import es.dashcams.app.databinding.ActivitySendBinding
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Formulario para enviar un evento guardado a dashcams.es.
 * Mismos campos y condiciones que el formulario de la web.
 */
class SendClipActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendBinding
    private val prefs by lazy { getSharedPreferences("ajustes", MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val spanish = Locale.forLanguageTag("es-ES")
    private val parts = mutableListOf<Pair<CheckBox, File>>()
    private var sending = false

    private val blockBackWhileSending = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Toast.makeText(this@SendClipActivity, "Espera a que termine el envío", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySendBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, blockBackWhileSending)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val chosen = intent.getStringExtra(EXTRA_FILE)?.let { File(it) }
        val eventFiles = if (chosen == null) emptyList() else ClipNames.eventParts(chosen, ClipStorage(this).savedClips())
        if (eventFiles.isEmpty()) {
            Toast.makeText(this, "No se encuentra el clip", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        binding.toolbar.subtitle = ClipNames.title(eventFiles.first())

        for (file in eventFiles) {
            val check = CheckBox(this).apply {
                text = ClipNames.subtitle(file)
                isChecked = true
                setOnCheckedChangeListener { _, _ -> updateSize() }
            }
            binding.partsBox.addView(check)
            parts += check to file
        }
        updateSize()

        // Lo que se rellena siempre igual se recuerda en el móvil (no se manda a ningún sitio hasta enviar).
        binding.inputCorreo.setText(prefs.getString(KEY_CORREO, ""))
        binding.inputAparecer.setText(prefs.getString(KEY_APARECER, ""))

        binding.btnCondiciones.setOnClickListener { openWeb("https://dashcams.es/condiciones-clips") }
        binding.btnPrivacidad.setOnClickListener { openWeb("https://dashcams.es/privacidad") }
        binding.btnSend.setOnClickListener { send() }
    }

    private fun selectedFiles(): List<File> = parts.filter { it.first.isChecked }.map { it.second }

    private fun updateSize() {
        val bytes = selectedFiles().sumOf { it.length() }
        binding.sizeText.text = String.format(spanish, "Total: %.0f MB", bytes / (1024f * 1024f))
    }

    private fun value(input: TextInputEditText): String = input.text?.toString()?.trim().orEmpty()

    private fun send() {
        if (sending) return
        val files = selectedFiles()
        val correo = value(binding.inputCorreo)
        binding.layoutCorreo.error = null

        val problem = when {
            files.isEmpty() -> "Elige al menos un vídeo."
            !Patterns.EMAIL_ADDRESS.matcher(correo).matches() -> {
                binding.layoutCorreo.error = "Revisa el correo"
                "Pon un correo válido para que podamos avisarte si lo publicamos."
            }
            !(binding.checkAutor.isChecked && binding.checkCesion.isChecked &&
                binding.checkEdad.isChecked && binding.checkPrivacidad.isChecked) ->
                "Tienes que marcar las cuatro casillas para enviar."
            else -> null
        }
        if (problem != null) {
            binding.statusText.text = problem
            return
        }

        prefs.edit()
            .putString(KEY_CORREO, correo)
            .putString(KEY_APARECER, value(binding.inputAparecer))
            .apply()

        val form = JSONObject()
            .put("correo", correo)
            .put("aparecer", value(binding.inputAparecer))
            .put("lugar", value(binding.inputLugar))
            .put("carretera", value(binding.inputCarretera))
            .put("descripcion", value(binding.inputDescripcion))
            .put("autor", true)
            .put("cesion", true)
            .put("edad", true)
            .put("privacidad", true)

        setSending(true)
        binding.statusText.text = "Preparando la subida…"

        thread(name = "subida-clip") {
            try {
                ClipUploader().send(files, form) { sent, total, index, count ->
                    main.post { showProgress(sent, total, index, count) }
                }
                main.post { onSent(files.size) }
            } catch (e: Exception) {
                val message = e.message ?: "No se ha podido enviar. Inténtalo de nuevo."
                main.post { onFailed(message) }
            }
        }
    }

    private fun showProgress(sent: Long, total: Long, index: Int, count: Int) {
        val percent = if (total > 0) (sent * 100 / total).toInt().coerceIn(0, 99) else 0
        binding.progress.setProgressCompat(percent, true)
        binding.statusText.text = String.format(
            spanish,
            "Subiendo vídeo %d de %d: %d %% (%.0f de %.0f MB). No cierres la app.",
            index + 1, count, percent, sent / 1048576f, total / 1048576f
        )
    }

    private fun onSent(count: Int) {
        setSending(false)
        binding.progress.setProgressCompat(100, true)
        val who = value(binding.inputAparecer)
        val thanks = if (who.isNotEmpty() && !who.equals("anónimo", true) && !who.equals("anonimo", true)) ", $who" else ""
        binding.statusText.text =
            "¡Clip enviado! Gracias$thanks. Hemos recibido ${if (count > 1) "$count vídeos" else "tu vídeo"}. " +
                "Si lo publicamos, te avisaremos por correo."
        binding.btnSend.text = "Enviado"
        binding.btnSend.isEnabled = false
    }

    private fun onFailed(message: String) {
        setSending(false)
        binding.progress.isVisible = false
        binding.statusText.text = message
        binding.btnSend.text = "Volver a intentarlo"
    }

    private fun setSending(on: Boolean) {
        sending = on
        blockBackWhileSending.isEnabled = on
        binding.btnSend.isEnabled = !on
        binding.progress.isVisible = true
        if (on) {
            binding.progress.setProgressCompat(0, false)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        listOf(
            binding.inputCorreo, binding.inputAparecer, binding.inputLugar,
            binding.inputCarretera, binding.inputDescripcion
        ).forEach { it.isEnabled = !on }
        parts.forEach { it.first.isEnabled = !on }
    }

    private fun openWeb(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_FILE = "archivo"
        private const val KEY_CORREO = "correo"
        private const val KEY_APARECER = "aparecer"
    }
}
