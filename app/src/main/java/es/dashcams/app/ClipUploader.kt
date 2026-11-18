package es.dashcams.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sube clips a dashcams.es usando el mismo sistema que el formulario de la web (clips.php):
 *  1. "iniciar": la web devuelve una URL firmada por cada trozo de 16 MB.
 *  2. Cada trozo va directo a Cloudflare R2 (no pasa por Hostinger).
 *  3. "completar": la web junta los trozos.
 *  4. "terminar": la web guarda los datos y avisa por correo, igual que desde la web.
 *
 * Solo se usa cuando el usuario pulsa "Enviar". La app no manda nada más a ningún sitio.
 * Hay que llamarlo desde un hilo en segundo plano.
 */
class ClipUploader(private val endpoint: String = ENDPOINT) {

    class UploadException(message: String) : Exception(message)

    fun interface ProgressListener {
        fun onProgress(sentBytes: Long, totalBytes: Long, fileIndex: Int, fileCount: Int)
    }

    /** [form] lleva los campos del formulario: correo, aparecer, lugar, carretera, descripcion y las casillas. */
    fun send(files: List<File>, form: JSONObject, listener: ProgressListener) {
        val total = files.sumOf { it.length() }
        var base = 0L
        var envio = ""
        var fe = ""
        val archivos = JSONArray()

        for ((index, file) in files.withIndex()) {
            val size = file.length()
            val start = api(
                JSONObject()
                    .put("accion", "iniciar")
                    .put("archivo", file.name)
                    .put("tam", size)
                    .put("num", index)
                    .put("envio", envio)
                    .put("fe", fe)
            )
            envio = start.getString("envio")
            fe = start.getString("fe")
            val clave = start.getString("clave")
            val id = start.getString("id")
            val ficha = start.getString("ficha")
            val partSize = start.getLong("parte")
            val urls = start.getJSONArray("urls")

            val partes = JSONArray()
            try {
                for (k in 0 until urls.length()) {
                    val offset = k * partSize
                    val length = minOf(partSize, size - offset)
                    val etag = uploadPart(urls.getString(k), file, offset, length) { sentInPart ->
                        listener.onProgress(base + offset + sentInPart, total, index, files.size)
                    }
                    partes.put(JSONObject().put("n", k + 1).put("etag", etag))
                }
            } catch (e: Exception) {
                try {
                    api(JSONObject().put("accion", "cancelar").put("clave", clave).put("id", id).put("ficha", ficha))
                } catch (ignored: Exception) {
                }
                throw e
            }

            api(
                JSONObject()
                    .put("accion", "completar")
                    .put("clave", clave)
                    .put("id", id)
                    .put("ficha", ficha)
                    .put("partes", partes)
            )
            base += size
            archivos.put(JSONObject().put("clave", clave).put("nombre", file.name))
        }

        val end = JSONObject(form.toString())
            .put("accion", "terminar")
            .put("envio", envio)
            .put("fe", fe)
            .put("archivos", archivos)
        api(end)
        listener.onProgress(total, total, files.size - 1, files.size)
    }

    private fun api(body: JSONObject): JSONObject {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw UploadException("La web no ha respondido bien (código $code). Inténtalo más tarde.")
            }
            if (!json.optBoolean("ok", false)) {
                throw UploadException(json.optString("error", "No se ha podido enviar."))
            }
            return json
        } catch (e: IOException) {
            throw UploadException("No hay conexión con dashcams.es. Revisa internet y vuelve a intentarlo.")
        } finally {
            conn.disconnect()
        }
    }

    /** Sube un trozo del vídeo a su URL firmada. Reintenta 3 veces si se corta. Devuelve el ETag. */
    private fun uploadPart(
        url: String,
        file: File,
        offset: Long,
        length: Long,
        onSent: (Long) -> Unit,
    ): String {
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection)
                try {
                    conn.requestMethod = "PUT"
                    conn.doOutput = true
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 120_000
                    conn.setFixedLengthStreamingMode(length)
                    conn.setRequestProperty("Content-Type", "application/octet-stream")

                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(offset)
                        val buffer = ByteArray(64 * 1024)
                        var remaining = length
                        var sent = 0L
                        conn.outputStream.use { out ->
                            while (remaining > 0) {
                                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                remaining -= n
                                sent += n
                                onSent(sent)
                            }
                        }
                    }

                    val code = conn.responseCode
                    val etag = conn.getHeaderField("ETag")
                    if (code in 200..299 && !etag.isNullOrBlank()) return etag
                    throw IOException("HTTP $code")
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                onSent(0)
                if (attempt < MAX_ATTEMPTS) Thread.sleep(1_500L * attempt)
            }
        }
        throw UploadException("Se ha cortado la subida. Revisa tu conexión y vuelve a intentarlo.")
    }

    companion object {
        const val ENDPOINT = "https://dashcams.es/clips.php"
        private const val MAX_ATTEMPTS = 4
    }
}
