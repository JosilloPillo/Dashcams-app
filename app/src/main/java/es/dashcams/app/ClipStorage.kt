package es.dashcams.app

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dónde viven los vídeos. Todo se queda en el móvil, en la carpeta privada de la app:
 *  - bucle/      tramos de 1 minuto que se van borrando solos (los más viejos primero)
 *  - guardados/  clips que el usuario ha guardado; el bucle nunca los toca
 */
class ClipStorage(context: Context) {

    private val root: File =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies")

    val loopDir: File = File(root, "bucle").apply { mkdirs() }
    val savedDir: File = File(root, "guardados").apply { mkdirs() }

    fun newSegmentFile(): File {
        val base = "tramo_" + stamp(System.currentTimeMillis())
        var file = File(loopDir, "$base.mp4")
        var n = 1
        while (file.exists()) {
            file = File(loopDir, "${base}_$n.mp4")
            n++
        }
        return file
    }

    fun loopSegments(): List<File> =
        loopDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Clips guardados: el evento más reciente primero y, dentro de cada evento, parte 1, 2, 3. */
    fun savedClips(): List<File> =
        savedDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedWith(
                compareByDescending<File> { it.name.substringBeforeLast("_parte") }
                    .thenBy { it.name }
            )
            ?: emptyList()

    /** Mueve un tramo terminado a "guardados" para que el bucle no lo borre. */
    fun protect(segment: File, eventStamp: String, part: Int): File? {
        if (!segment.exists() || segment.length() == 0L) return null
        val target = File(savedDir, "evento_${eventStamp}_parte$part.mp4")
        if (segment.renameTo(target)) return target
        return try {
            segment.copyTo(target, overwrite = true)
            segment.delete()
            target
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Borra los tramos más viejos del bucle mientras ocupen más de [maxBytes]
     * o quede poco espacio libre. Nunca borra los ficheros de [keep].
     */
    fun trimLoop(maxBytes: Long, minFreeBytes: Long, keep: Set<File>) {
        val segments = loopSegments()
        var total = segments.sumOf { it.length() }
        for (f in segments) {
            if (total <= maxBytes && freeBytes() >= minFreeBytes) break
            if (f in keep) continue
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    fun freeBytes(): Long = try {
        StatFs(root.path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE
    }

    companion object {
        fun stamp(millis: Long): String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(millis))
    }
}
