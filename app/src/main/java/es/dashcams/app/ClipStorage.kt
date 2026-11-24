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
 *  Nombres (al estilo de Vantrue): dashcams.es_2026-11-24_22-07-15_0008_N_F.mp4
 *    fecha y hora de inicio · número de grabación (el mismo en F e I del mismo minuto) ·
 *    N normal / E evento · F carretera / I interior
 *  - guardados/  clips que el usuario ha guardado; el bucle nunca los toca
 */
class ClipStorage(context: Context) {

    private val root: File =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "Movies")

    val loopDir: File = File(root, "bucle").apply { mkdirs() }
    val savedDir: File = File(root, "guardados").apply { mkdirs() }

    private val prefs = context.applicationContext.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    /** Siguiente número de grabación: 0001 … 9999 y vuelta a empezar. */
    @Synchronized
    fun nextSeq(): Int {
        val n = prefs.getInt(KEY_SEQ, 0) % 9999 + 1
        prefs.edit().putInt(KEY_SEQ, n).apply()
        return n
    }

    /** Fichero para el siguiente tramo: "dashcams.es_2026-11-24_22-07-15_0008_N_F.mp4". */
    fun newSegmentFile(camTag: String, seq: Int): File {
        val base = PREFIX + stamp(System.currentTimeMillis())
        val tail = "_%04d_N_%s.mp4".format(Locale.US, seq, camTag)
        var file = File(loopDir, base + tail)
        var n = 1
        while (file.exists()) {
            file = File(loopDir, "$base-$n$tail")
            n++
        }
        return file
    }

    /** Tramos del bucle, del más viejo al más nuevo. */
    fun loopSegments(): List<File> =
        loopDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedWith(compareBy<File> { ClipNames.timeOf(it) }.thenBy { it.name })
            ?: emptyList()

    /** Clips guardados por orden de tiempo, lo más reciente primero (carretera antes que interior). */
    fun savedClips(): List<File> =
        savedDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?.sortedWith(
                compareByDescending<File> { ClipNames.timeOf(it) }
                    .thenBy { ClipNames.part(it) ?: 0 }
                    .thenBy { ClipNames.cameraBadge(it) }
            )
            ?: emptyList()

    /** Mueve un tramo terminado a "guardados" para que el bucle no lo borre. */
    fun protect(segment: File, eventStamp: String, part: Int): File? {
        if (!segment.exists() || segment.length() == 0L) return null
        val target = if (segment.name.contains("_N_")) {
            // Nombre actual: se queda igual, solo cambia la N (normal) por la E (evento)
            File(savedDir, segment.name.replace("_N_", "_E_"))
        } else {
            // Tramos de versiones antiguas
            val cam = ClipNames.camera(segment)
            val suffix = if (cam.isEmpty()) "" else "_$cam"
            File(savedDir, "${PREFIX}evento_${eventStamp}_parte$part$suffix.mp4")
        }
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
        /** Así empiezan todos los vídeos, para que quien los vea sepa de dónde salen. */
        const val PREFIX = "dashcams.es_"

        /**
         * Fecha y hora para los nombres: "2026-11-24_22-07-15" (año-mes-día_hora-minuto-segundo).
         * El año primero hace que en el ordenador o la galería salgan ordenados.
         */
        const val STAMP_FORMAT = "yyyy-MM-dd_HH-mm-ss"
        private const val KEY_SEQ = "numero_grabacion"

        fun stamp(millis: Long): String =
            SimpleDateFormat(STAMP_FORMAT, Locale.US).format(Date(millis))
    }
}
