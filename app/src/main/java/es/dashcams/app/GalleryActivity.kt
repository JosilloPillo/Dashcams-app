package es.dashcams.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import es.dashcams.app.databinding.ActivityGalleryBinding
import es.dashcams.app.databinding.ItemClipBinding
import es.dashcams.app.databinding.ItemGalleryHeaderBinding
import java.io.File

/** Lista de clips guardados: ver, compartir (WhatsApp, correo, SwissTransfer...) o borrar. */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var storage: ClipStorage
    private val adapter = ClipAdapter(
        onPlay = { play(it) },
        onShare = { share(it) },
        onDelete = { confirmDelete(it) },
    )

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
        adapter.submit(storage.savedClips())
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
}

/**
 * Lista de la pantalla Clips. La posición 0 es la cabecera (kicker, título con palabra en degradado,
 * carriles); el resto, una tarjeta blanca por clip.
 */
class ClipAdapter(
    private val onPlay: (File) -> Unit,
    private val onShare: (File) -> Unit,
    private val onDelete: (File) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<File> = emptyList()
    private val animated = mutableSetOf<String>()

    class HeaderHolder(val binding: ItemGalleryHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    class ClipHolder(val binding: ItemClipBinding) : RecyclerView.ViewHolder(binding.root)

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<File>) {
        items = list
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
        if (holder is HeaderHolder) {
            val b = holder.binding
            b.title.text = Brand.highlight("Clips guardados", "guardados")
            b.emptyText.isVisible = items.isEmpty()
            if (animated.add("header")) {
                Brand.enter(b.kicker, delayMs = 50, fromDp = -12f, durationMs = 400)
                Brand.enter(b.title, delayMs = 150, fromDp = 20f)
            }
            return
        }
        val b = (holder as ClipHolder).binding
        val file = items[position - 1]
        b.partCircle.text = ClipNames.part(file)
        b.title.text = ClipNames.title(file)
        b.subtitle.text = ClipNames.subtitle(file)
        b.btnPlay.setOnClickListener { onPlay(file) }
        b.btnShare.setOnClickListener { onShare(file) }
        b.btnDelete.setOnClickListener { onDelete(file) }
        // Las tarjetas entran con rebote desde la derecha, una detrás de otra (solo la primera vez)
        if (animated.add(file.name)) {
            Brand.pop(b.root, delayMs = 200L + 70L * minOf(position - 1, 6), fromRightDp = 60f)
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

/** Convierte "evento_20261118_210412_parte2.mp4" en textos legibles. */
object ClipNames {
    private val pattern = Regex("""evento_(\d{8})_(\d{6})_parte(\d)\.mp4""")

    fun title(file: File): String {
        val m = pattern.matchEntire(file.name) ?: return file.name
        val d = m.groupValues[1]
        val t = m.groupValues[2]
        return "${d.substring(6, 8)}/${d.substring(4, 6)}/${d.substring(0, 4)} · " +
            "${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4, 6)}"
    }

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
}
