package home.iptv.core.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import home.iptv.core.model.Source
import home.iptv.core.model.newId
import home.iptv.sync.ImportException
import kotlinx.coroutines.*
import okhttp3.*
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun parseHeaders(value: String): Map<String, String> = value.lineSequence().filter(String::isNotBlank).associate { line ->
    val split = line.indexOf(':')
    require(split > 0) { "Заголовки задаются строками «Имя: значение»" }
    val key = line.substring(0, split).trim()
    val content = line.substring(split + 1).trim()
    require(key.matches(Regex("[A-Za-z0-9-]+")) && content.none { it == '\r' || it == '\n' || it.code < 32 }) { "Некорректный HTTP-заголовок" }
    key to content
}

class LimitedInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
    private var count = 0L
    private fun record(size: Int): Int { if (size > 0) { count += size; if (count > maximum) throw ImportException("Источник превышает допустимый размер") }; return size }
    override fun read(): Int = super.read().also { if (it >= 0) record(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = record(`in`.read(buffer, offset, length))
}
fun decodedStream(input: InputStream, limit: Long): InputStream {
    val buffered = input.buffered()
    buffered.mark(2)
    val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
    buffered.reset()
    // OkHttp transparently handles Content-Encoding. Sniff the remaining bytes, never the suffix.
    return LimitedInputStream(if (gzip) GZIPInputStream(buffered) else buffered, limit)
}

data class Download(val input: InputStream?, val etag: String? = null, val modified: String? = null, val baseUrl: String, val closeAction: () -> Unit = {}) : Closeable {
    override fun close() { try { input?.close() } finally { closeAction() } }
}

class SourceLoader(private val context: Context) {
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES).followRedirects(true).build()
    suspend fun open(source: Source, force: Boolean = false): Download {
        val uri = Uri.parse(source.location)
        val max = if (source.kind == Source.EPG) 256L * 1024 * 1024 else 32L * 1024 * 1024
        return if (uri.scheme in listOf("http", "https")) {
            val builder = Request.Builder().url(source.location)
            parseHeaders(source.headers).forEach { (name, value) -> builder.header(name, value) }
            if (!force) { source.etag?.let { builder.header("If-None-Match", it) }; source.lastModified?.let { builder.header("If-Modified-Since", it) } }
            val call = client.newCall(builder.build())
            val response = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            if (response.code == 304) { response.close(); Download(null, source.etag, source.lastModified, source.location) }
            else {
                if (!response.isSuccessful) { val code = response.code; response.close(); throw ImportException("Сервер вернул HTTP $code") }
                val body = response.body
                // Cancellation closes an in-progress read, not only the initial request.
                val watcher = CoroutineScope(currentCoroutineContext()).launch { try { awaitCancellation() } finally { call.cancel() } }
                try {
                    Download(decodedStream(body.byteStream(), max), response.header("ETag"), response.header("Last-Modified"), response.request.url.toString()) { response.close(); watcher.cancel() }
                } catch (e: Exception) { response.close(); watcher.cancel(); throw e }
            }
        } else {
            val input = when (uri.scheme) {
                "content" -> context.contentResolver.openInputStream(uri)
                "file" -> FileInputStream(File(requireNotNull(uri.path)))
                else -> throw ImportException("Нужен HTTP(S)-адрес или файл из системного выбора")
            } ?: throw ImportException("Нет доступа к файлу. Выберите его снова")
            try { Download(decodedStream(input, max), baseUrl = source.location) } catch (e: Exception) { input.close(); throw e }
        }
    }
    suspend fun retain(uri: Uri): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri.toString() to false
        } catch (_: SecurityException) {
            val folder = File(context.filesDir, "sources").apply { mkdirs() }
            val copy = File(folder, newId())
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { LimitedInputStream(input, 256L * 1024 * 1024).copyTo(it) } }
                    ?: throw ImportException("Не удалось прочитать файл")
                Uri.fromFile(copy).toString() to true
            } catch (e: Exception) { copy.delete(); throw e }
        }
    }
}
