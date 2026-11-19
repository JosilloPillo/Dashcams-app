package es.dashcams.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import es.dashcams.app.databinding.ActivityGalleryBinding
import es.dashcams.app.databinding.ItemClipBinding
import es.dashcams.app.databinding.ItemGalleryHeaderBinding
import java.io.File

/**
 * Pantalla Clips, con dos pestañas:
 *  - Eventos: lo guardado con GUARDAR, la voz o un mando (no se borra nunca solo).
 *  - Últimos minutos: la grabación normal en bucle, que se va borrando. Desde aquí se puede guardar un tramo.
 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var storage: ClipStorage
    private val adapter = ClipAdapter(
        onPlay = { play(it) },
        onShare = { share(it) },
        onDelete = { confirmDelete(it) },
        onKeep = { keep(it) },
        onTab = { loop -> showLoop = loop; refresh() },
    )
    private var showLoop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = ClipStorage(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        // El tramo que se está grabando ahora mismo no se enseña (aún no está terminado)
        val recordingNow = DashcamController.activeFile
        val list = if (showLoop) storage.loopSegments().filter { it != recordingNow }.reversed() else storage.savedClips()
        adapter.submit(list, showLoop)
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun play(file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(file), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_LONG).show()
        }
    }

    /** Compartir: a la web de Dashcams España directamente o con cualquier otra app. */
    private fun share(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_title)
            .setItems(arrayOf(getString(R.string.share_web), getString(R.string.share_other))) { _, which ->
                if (which == 0) {
                    startActivity(
                        Intent(this, SendClipActivity::class.java)
                            .putExtra(SendClipActivity.EXTRA_FILE, file.absolutePath)
                    )
                } else {
                    shareWithOtherApp(file)
                }
            }
            .show()
    }

    private fun shareWithOtherApp(file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uriFor(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(ClipNames.title(file) + "\n" + ClipNames.subtitle(file))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                file.delete()
                refresh()
            }
            .show()
    }

    /** Pasa un tramo de "Últimos minutos" a "Eventos" para que no se borre. */
    private fun keep(file: File) {
        val stamp = ClipNames.stampOf(file) ?: ClipStorage.stamp(file.lastModified())
        if (storage.protect(file, stamp, 2) != null) {
            Toast.makeText(this, "Guardado en Eventos", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No se ha podido guardar", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }
}

/**
 * Lista de la pantalla Clips. La posición 0 es la cabecera (kicker, título con palabra en degradado,
 * pestañas y carriles); el resto, una tarjeta blanca por clip.
 */
class ClipAdapter(
    private val onPlay: (File) -> Unit,
    private val onShare: (File) -> Unit,
    private val onDelete: (File) -> Unit,
    private val onKeep: (File) -> Unit,
    private val onTab: (Boolean) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<File> = emptyList()
    private var loop = false
    private val animated = mutableSetOf<String>()

    class HeaderHolder(val binding: ItemGalleryHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ClipHolder(val binding: ItemClipBinding) : RecyclerView.ViewHolder(binding.root)

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<File>, showingLoop: Boolean) {
        items = list
        loop = showingLoop
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_HEADER else TYPE_CLIP

    override fun getItemCount(): Int = items.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemGalleryHeaderBinding.inflate(inflater, parent, false))
        } else {
            ClipHolder(ItemClipBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderHolder) bindHeader(holder.binding) else bindClip((holder as ClipHolder).binding, position)
    }

    private fun bindHeader(b: ItemGalleryHeaderBinding) {
        val ctx = b.root.context
        if (loop) {
            b.kickerText.setText(R.string.tab_loop)
            b.title.text = Brand.highlight("Últimos minutos", "minutos")
            b.subtitle.setText(R.string.loop_subtitle)
            b.emptyText.setText(R.string.loop_empty)
        } else {
            b.kickerText.setText(R.string.gallery_kicker)
            b.title.text = Brand.highlight("Clips guardados", "guardados")
            b.subtitle.setText(R.string.gallery_subtitle)
            b.emptyText.setText(R.string.gallery_empty)
        }
        b.emptyText.isVisible = items.isEmpty()

        // Pestaña elegida: pastilla blanca con texto azul marino; la otra, translúcida
        val navy = ContextCompat.getColor(ctx, R.color.azul_marino)
        val white = ContextCompat.getColor(ctx, android.R.color.white)
        b.tabEvents.setBackgroundResource(if (!loop) R.drawable.bg_tab_selected else R.drawable.bg_tab)
        b.tabEvents.setTextColor(if (!loop) navy else white)
        b.tabLoop.setBackgroundResource(if (loop) R.drawable.bg_tab_selected else R.drawable.bg_tab)
        b.tabLoop.setTextColor(if (loop) navy else white)
        b.tabEvents.setOnClickListener { if (loop) onTab(false) }
        b.tabLoop.setOnClickListener { if (!loop) onTab(true) }

        if (animated.add("header")) {
            Brand.enter(b.kicker, delayMs = 50, fromDp = -12f, durationMs = 400)
            Brand.enter(b.title, delayMs = 150, fromDp = 20f)
        }
    }

    private fun bindClip(b: ItemClipBinding, position: Int) {
        val ctx = b.root.context
        val file = items[position - 1]
        b.title.text = ClipNames.title(file)
        b.btnPlay.setOnClickListener { onPlay(file) }
        b.btnShare.setOnClickListener { onShare(file) }

        if (loop) {
            // Tramo de la grabación normal: se puede guardar como evento
            b.partCircle.text = "↺"
            b.subtitle.text = ClipNames.loopSubtitle(file)
            b.btnDelete.setText(R.string.keep)
            b.btnDelete.setTextColor(ContextCompat.getColor(ctx, R.color.verde_ok))
            b.btnDelete.setOnClickListener { onKeep(file) }
        } else {
            b.partCircle.text = ClipNames.part(file)
            b.subtitle.text = ClipNames.subtitle(file)
            b.btnDelete.setText(R.string.delete)
            b.btnDelete.setTextColor(ContextCompat.getColor(ctx, R.color.rojo_marca))
            b.btnDelete.setOnClickListener { onDelete(file) }
        }

        // Las tarjetas entran con rebote desde la derecha, una detrás de otra (solo la primera vez)
        if (animated.add(file.name)) {
            Brand.pop(b.root, delayMs = 150L + 60L * minOf(position - 1, 6), fromRightDp = 60f)
        } else {
            b.root.animate().cancel()
            b.root.alpha = 1f
            b.root.scaleX = 1f
            b.root.scaleY = 1f
            b.root.translationX = 0f
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CLIP = 1
    }
}

/** Convierte "evento_20261118_210412_parte2.mp4" y "tramo_20261118_210412.mp4" en textos legibles. */
object ClipNames {
    private val pattern = Regex("""evento_(\d{8})_(\d{6})_parte(\d)\.mp4""")
    private val anyStamp = Regex("""(?:evento|tramo)_(\d{8})_(\d{6}).*\.mp4""")

    fun title(file: File): String {
        val m = anyStamp.matchEntire(file.name) ?: return file.name
        val d = m.groupValues[1]
        val t = m.groupValues[2]
        return "${d.substring(6, 8)}/${d.substring(4, 6)}/${d.substring(0, 4)} · " +
            "${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4, 6)}"
    }

    /** "20261118_210412" a partir del nombre del fichero. */
    fun stampOf(file: File): String? =
        anyStamp.matchEntire(file.name)?.let { "${it.groupValues[1]}_${it.groupValues[2]}" }

    /** "1", "2" o "3" para el círculo de la tarjeta. */
    fun part(file: File): String = pattern.matchEntire(file.name)?.groupValues?.get(3) ?: "•"

    /** Todas las partes guardadas del mismo evento que [file] (parte 1, 2, 3), en orden. */
    fun eventParts(file: File, saved: List<File>): List<File> {
        val m = pattern.matchEntire(file.name) ?: return if (file.exists()) listOf(file) else emptyList()
        val prefix = "evento_${m.groupValues[1]}_${m.groupValues[2]}_parte"
        return saved.filter { it.name.startsWith(prefix) }.sortedBy { it.name }
    }

    fun subtitle(file: File): String {
        val part = when (pattern.matchEntire(file.name)?.groupValues?.get(3)) {
            "1" -> "Minuto anterior al aviso"
            "2" -> "Momento del aviso"
            "3" -> "Minuto posterior al aviso"
            else -> "Clip"
        }
        val mb = file.length() / (1024 * 1024)
        return "$part · $mb MB"
    }

    fun loopSubtitle(file: File): String {
        val mb = file.length() / (1024 * 1024)
        return "Grabación normal · se borrará sola · $mb MB"
    }
}
