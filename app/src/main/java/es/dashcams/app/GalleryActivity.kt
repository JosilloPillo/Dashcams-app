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
import es.dashcams.app.databinding.ItemEventGroupBinding
import es.dashcams.app.databinding.ItemGalleryHeaderBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

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
        val recordingNow = DashcamController.activeFiles
        val list = if (showLoop) storage.loopSegments().filter { it !in recordingNow }.reversed() else storage.savedClips()
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

    /** Filas de la lista: un [File] por clip y, en Eventos, un [String] con la cabecera de cada evento. */
    private var items: List<Any> = emptyList()
    private var loop = false
    private val animated = mutableSetOf<String>()

    class HeaderHolder(val binding: ItemGalleryHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ClipHolder(val binding: ItemClipBinding) : RecyclerView.ViewHolder(binding.root)
    class GroupHolder(val binding: ItemEventGroupBinding) : RecyclerView.ViewHolder(binding.root)

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<File>, showingLoop: Boolean) {
        items = if (showingLoop) list else ClipNames.eventGroups(list).flatMap { listOf(ClipNames.groupTitle(it)) + it }
        loop = showingLoop
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when {
        position == 0 -> TYPE_HEADER
        items[position - 1] is String -> TYPE_GROUP
        else -> TYPE_CLIP
    }

    override fun getItemCount(): Int = items.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemGalleryHeaderBinding.inflate(inflater, parent, false))
            TYPE_GROUP -> GroupHolder(ItemEventGroupBinding.inflate(inflater, parent, false))
            else -> ClipHolder(ItemClipBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> bindHeader(holder.binding)
            is GroupHolder -> holder.binding.groupTitle.text = items[position - 1] as String
            is ClipHolder -> bindClip(holder.binding, position)
        }
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
        val file = items[position - 1] as File
        b.title.text = ClipNames.title(file)
        b.btnPlay.setOnClickListener { onPlay(file) }
        b.btnShare.setOnClickListener { onShare(file) }

        if (loop) {
            // Tramo de la grabación normal: se puede guardar como evento
            b.partCircle.text = ClipNames.cameraBadge(file)
            b.subtitle.text = ClipNames.loopSubtitle(file)
            b.btnDelete.setText(R.string.keep)
            b.btnDelete.setTextColor(ContextCompat.getColor(ctx, R.color.verde_ok))
            b.btnDelete.setOnClickListener { onKeep(file) }
        } else {
            b.partCircle.text = ClipNames.cameraBadge(file)
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
        private const val TYPE_GROUP = 2
    }
}

/**
 * Lee los nombres de los vídeos y los convierte en textos legibles.
 * Nombre actual:   dashcams.es_2026-11-24_22-07-15_0008_N_F.mp4 (E en vez de N si es un evento)
 * Nombres antiguos (se siguen entendiendo): dashcams.es_23-11-2026_13-15-00_F.mp4,
 *   dashcams.es_evento_23-11-2026_13-15-00_parte1_F.mp4, tramo_20261118_210412.mp4, evento_20261118_210412_parte2.mp4
 */
object ClipNames {
    private val isoStamp = Regex("""(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})""")
    private val dmyStamp = Regex("""(\d{2})-(\d{2})-(\d{4})_(\d{2})-(\d{2})-(\d{2})""")
    private val oldStamp = Regex("""(?:evento|tramo)_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})""")
    private val partRe = Regex("""_parte(\d)""")
    private val seqRe = Regex("""_(\d{4})_([NE])_""")
    private val camPattern = Regex("""_(F|I|FI)\.mp4$""")

    /** Clips guardados a menos de esto unos de otros cuentan como el mismo evento. */
    private const val SAME_EVENT_MS = 75_000L

    /** [día, mes, año, hora, minuto, segundo] del nombre, o null si no se entiende. */
    private fun fields(file: File): List<String>? {
        isoStamp.find(file.name)?.let { m ->
            val g = m.groupValues
            return listOf(g[3], g[2], g[1], g[4], g[5], g[6])
        }
        dmyStamp.find(file.name)?.let { return it.groupValues.drop(1) }
        oldStamp.find(file.name)?.let { m ->
            val g = m.groupValues
            return listOf(g[3], g[2], g[1], g[4], g[5], g[6])
        }
        return null
    }

    fun title(file: File): String {
        val f = fields(file) ?: return file.name
        return "${f[0]}/${f[1]}/${f[2]} · ${f[3]}:${f[4]}:${f[5]}"
    }

    /** "2026-11-24_22-07-15" a partir del nombre del fichero. */
    fun stampOf(file: File): String? =
        fields(file)?.let { "${it[2]}-${it[1]}-${it[0]}_${it[3]}-${it[4]}-${it[5]}" }

    /** Momento del clip (o del evento, en los nombres antiguos) para ordenar. */
    fun timeOf(file: File): Long {
        val stamp = stampOf(file) ?: return file.lastModified()
        return try {
            SimpleDateFormat(ClipStorage.STAMP_FORMAT, Locale.US).parse(stamp)?.time ?: file.lastModified()
        } catch (e: Exception) {
            file.lastModified()
        }
    }

    /** Número de grabación (0008), o null en nombres antiguos. */
    fun seqOf(file: File): String? = seqRe.find(file.name)?.groupValues?.get(1)

    fun isEvent(file: File): Boolean =
        file.name.contains("evento_") || seqRe.find(file.name)?.groupValues?.get(2) == "E"

    /** 1, 2 o 3 en los eventos de versiones antiguas; null en el resto. */
    fun part(file: File): Int? =
        if (file.name.contains("evento_")) partRe.find(file.name)?.groupValues?.get(1)?.toIntOrNull() else null

    /**
     * Agrupa los clips guardados en eventos: los que se grabaron seguidos (o a la vez, carretera e
     * interior) van juntos. [saved] tiene que venir ordenado del más reciente al más viejo.
     */
    fun eventGroups(saved: List<File>): List<List<File>> {
        val groups = mutableListOf<MutableList<File>>()
        var last = Long.MIN_VALUE
        for (f in saved) {
            val t = timeOf(f)
            if (groups.isEmpty() || last - t > SAME_EVENT_MS) groups.add(mutableListOf())
            groups.last().add(f)
            last = t
        }
        // Dentro de cada evento, en orden de tiempo y la carretera antes que el interior
        return groups.map { g ->
            g.sortedWith(compareBy<File> { timeOf(it) }.thenBy { part(it) ?: 0 }.thenBy { cameraBadge(it) })
        }
    }

    /** Todos los clips del mismo evento que [file], en orden. */
    fun eventParts(file: File, saved: List<File>): List<File> {
        if (!isEvent(file)) return if (file.exists()) listOf(file) else emptyList()
        return eventGroups(saved).firstOrNull { file in it } ?: listOf(file)
    }

    /** "F" (carretera), "I" (interior), "FI" (vídeo combinado de la 0.8) o "" en clips de versiones antiguas. */
    fun camera(file: File): String = camPattern.find(file.name)?.groupValues?.get(1) ?: ""

    /** Letra del círculo de la tarjeta. Los clips antiguos no la llevan: se asume la carretera. */
    fun cameraBadge(file: File): String = camera(file).ifEmpty { "F" }

    private fun cameraText(file: File): String = when (cameraBadge(file)) {
        "I" -> "Interior"
        "FI" -> "Carretera + interior"
        else -> "Carretera"
    }

    fun subtitle(file: File): String {
        val part = when (part(file)) {
            1 -> " · minuto anterior al aviso"
            2 -> " · minuto del aviso"
            3 -> " · minuto siguiente al aviso"
            else -> seqOf(file)?.let { " · nº $it" } ?: ""
        }
        val mb = file.length() / (1024 * 1024)
        return "${cameraText(file)}$part · $mb MB"
    }

    /** Cabecera de un evento en la lista: "24/11/2026 · 22:07 · 2 vídeos". */
    fun groupTitle(group: List<File>): String {
        val f = fields(group.first())
        val n = group.size
        val videos = if (n == 1) "1 vídeo" else "$n vídeos"
        return if (f == null) videos else "${f[0]}/${f[1]}/${f[2]} · ${f[3]}:${f[4]} · $videos"
    }

    fun loopSubtitle(file: File): String {
        val mb = file.length() / (1024 * 1024)
        val seq = seqOf(file)?.let { " · nº $it" } ?: ""
        return "${cameraText(file)}$seq · se borrará sola · $mb MB"
    }
}
