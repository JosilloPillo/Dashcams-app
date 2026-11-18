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
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val clips = storage.savedClips()
        adapter.submit(clips)
        binding.emptyText.isVisible = clips.isEmpty()
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

    private fun share(file: File) {
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

class ClipAdapter(
    private val onPlay: (File) -> Unit,
    private val onShare: (File) -> Unit,
    private val onDelete: (File) -> Unit,
) : RecyclerView.Adapter<ClipAdapter.Holder>() {

    private var items: List<File> = emptyList()

    class Holder(val binding: ItemClipBinding) : RecyclerView.ViewHolder(binding.root)

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<File>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemClipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = items[position]
        holder.binding.title.text = ClipNames.title(file)
        holder.binding.subtitle.text = ClipNames.subtitle(file)
        holder.binding.btnPlay.setOnClickListener { onPlay(file) }
        holder.binding.btnShare.setOnClickListener { onShare(file) }
        holder.binding.btnDelete.setOnClickListener { onDelete(file) }
    }
}

/** Convierte "evento_20261118_210412_parte2.mp4" en textos legibles. */
object ClipNames {
    private val pattern = Regex("""evento_(\d{8})_(\d{6})_parte(\d)\.mp4""")

    fun title(file: File): String {
        val m = pattern.matchEntire(file.name) ?: return file.name
        val d = m.groupValues[1]
        val t = m.groupValues[2]
        return "Evento ${d.substring(6, 8)}/${d.substring(4, 6)}/${d.substring(0, 4)} · " +
            "${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4, 6)}"
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
