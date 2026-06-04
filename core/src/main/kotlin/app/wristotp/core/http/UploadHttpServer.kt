package app.wristotp.core.http

import app.wristotp.core.importer.BackupImporter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UploadHttpServer(
    private val port: Int = 8765,
    private val text: UploadPageText = UploadPageText(),
    private val onUpload: (UploadRequest) -> UploadResponse,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var actualPort: Int = port

    val boundPort: Int
        get() = actualPort

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(port)
        serverSocket = socket
        actualPort = socket.localPort
        executor.execute {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    executor.execute { handle(client) }
                } catch (_: Exception) {
                    if (running.get()) stop()
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
    }

    override fun close() {
        stop()
        executor.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val requestBytes = try {
                readRequest(input)
            } catch (_: RequestTooLarge) {
                write(client, 413, messagePage(text.importFailed, text.fileTooLarge))
                return
            }
            val split = requestBytes.indexOf(HEADER_SEPARATOR)
            if (split < 0) {
                write(client, 400, messagePage(text.badRequest, text.badRequest))
                return
            }

            val headerText = requestBytes.copyOfRange(0, split).toString(StandardCharsets.ISO_8859_1)
            val body = requestBytes.copyOfRange(split + HEADER_SEPARATOR.size, requestBytes.size)
            val lines = headerText.split("\r\n")
            val start = lines.firstOrNull().orEmpty().split(" ")
            val method = start.getOrNull(0).orEmpty().uppercase(Locale.US)
            val path = start.getOrNull(1).orEmpty()
            val headers = lines.drop(1).mapNotNull {
                val index = it.indexOf(':')
                if (index < 0) null else it.substring(0, index).trim().lowercase(Locale.US) to it.substring(index + 1).trim()
            }.toMap()

            when {
                method == "GET" && path == "/" -> write(client, 200, uploadPage())
                method == "POST" && path == "/upload" -> handleUpload(client, headers, body)
                else -> write(client, 404, messagePage(text.notFound, text.notFound))
            }
        }
    }

    private fun handleUpload(socket: Socket, headers: Map<String, String>, body: ByteArray) {
        if (body.size > BackupImporter.MAX_BYTES + 1024 * 128) {
            write(socket, 413, messagePage(text.importFailed, text.fileTooLarge))
            return
        }

        val contentType = headers["content-type"].orEmpty()
        val boundary = contentType.substringAfter("boundary=", missingDelimiterValue = "").trim('"')
        if (boundary.isBlank()) {
            write(socket, 400, messagePage(text.importFailed, text.missingMultipartBoundary))
            return
        }

        val parts = parseMultipart(body, boundary)
        val filePart = parts.firstOrNull { it.name == "file" && it.data.isNotEmpty() }
        if (filePart == null) {
            write(socket, 400, messagePage(text.importFailed, text.noFileUploaded))
            return
        }

        val password = parts.firstOrNull { it.name == "password" }?.data?.toString(StandardCharsets.UTF_8).orEmpty()
        val response = onUpload(
            UploadRequest(
                fileName = filePart.fileName.ifBlank { "backup" },
                data = filePart.data,
                password = password,
            ),
        )
        val status = if (response.success) 200 else 400
        write(socket, status, messagePage(response.title, response.message))
    }

    private fun readRequest(input: java.io.InputStream): ByteArray {
        val header = ByteArrayOutputStream()
        val last = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            header.write(b)
            last.write(b)
            val recent = last.toByteArray().takeLast(HEADER_SEPARATOR.size).toByteArray()
            if (recent.contentEquals(HEADER_SEPARATOR)) break
        }

        val headerBytes = header.toByteArray()
        val headerText = headerBytes.toString(StandardCharsets.ISO_8859_1)
        val contentLength = headerText.lineSequence()
            .firstOrNull { it.lowercase(Locale.US).startsWith("content-length:") }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull() ?: 0
        if (contentLength > BackupImporter.MAX_BYTES + 1024 * 128) throw RequestTooLarge()

        val body = readExactly(input, contentLength)
        return headerBytes + body
    }

    private fun readExactly(input: java.io.InputStream, contentLength: Int): ByteArray {
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == contentLength) body else body.copyOf(offset)
    }

    private fun parseMultipart(body: ByteArray, boundary: String): List<FormPart> {
        val marker = "--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
        val parts = mutableListOf<FormPart>()
        var pos = body.indexOf(marker)

        while (pos >= 0) {
            var start = pos + marker.size
            if (start + 1 < body.size && body[start] == '-'.code.toByte() && body[start + 1] == '-'.code.toByte()) break
            if (start + 1 < body.size && body[start] == '\r'.code.toByte() && body[start + 1] == '\n'.code.toByte()) start += 2
            val headerEnd = body.indexOf(HEADER_SEPARATOR, start)
            if (headerEnd < 0) break
            val next = body.indexOf(marker, headerEnd + HEADER_SEPARATOR.size)
            if (next < 0) break

            val headerText = body.copyOfRange(start, headerEnd).toString(StandardCharsets.ISO_8859_1)
            val dataEnd = if (next >= 2 && body[next - 2] == '\r'.code.toByte() && body[next - 1] == '\n'.code.toByte()) next - 2 else next
            val data = body.copyOfRange(headerEnd + HEADER_SEPARATOR.size, dataEnd)
            val disposition = headerText.lineSequence().firstOrNull { it.lowercase(Locale.US).startsWith("content-disposition:") }.orEmpty()
            val name = disposition.attribute("name")
            val fileName = disposition.attribute("filename")
            if (name.isNotBlank()) parts += FormPart(name = name, fileName = fileName, data = data)
            pos = next
        }

        return parts
    }

    private fun write(socket: Socket, status: Int, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        socket.getOutputStream().write(
            "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(StandardCharsets.UTF_8),
        )
        socket.getOutputStream().write(bytes)
    }

    private fun uploadPage(): String = page(
        text.uploadBackupFile,
        """
        <form action="/upload" method="post" enctype="multipart/form-data">
          <label>${text.backupFile.escapeHtml()}</label>
          <input name="file" type="file" required>
          <label>${text.backupPassword.escapeHtml()}</label>
          <input name="password" type="password" autocomplete="current-password">
          <button type="submit">${text.upload.escapeHtml()}</button>
        </form>
        """.trimIndent(),
    )

    private fun messagePage(title: String, message: String): String =
        page(title, "<p>${message.escapeHtml()}</p>")

    private fun page(title: String, body: String): String =
        """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${title.escapeHtml()}</title>
          <style>
            body { background:#050505; color:#f5f5f5; font-family:system-ui,sans-serif; margin:0; padding:24px; }
            main { max-width:520px; margin:0 auto; }
            h1 { font-size:24px; margin:0 0 18px; }
            form { display:grid; gap:14px; }
            label { color:#bcbcbc; font-size:14px; }
            input, button { box-sizing:border-box; width:100%; font:inherit; padding:12px; border-radius:8px; }
            input { background:#111; color:#fff; border:1px solid #333; }
            button { border:0; background:#2CB1EC; color:#00131c; font-weight:700; }
            p { line-height:1.4; }
          </style>
        </head>
        <body><main><h1>${title.escapeHtml()}</h1>$body</main></body>
        </html>
        """.trimIndent()

    private fun String.attribute(name: String): String {
        val regex = Regex("""$name="([^"]*)"""")
        return regex.find(this)?.groupValues?.getOrNull(1)?.let {
            URLDecoder.decode(it, StandardCharsets.UTF_8.name())
        }.orEmpty()
    }

    private fun String.escapeHtml(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun ByteArray.indexOf(needle: ByteArray, startIndex: Int = 0): Int {
        outer@ for (i in startIndex..(size - needle.size)) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    companion object {
        private val HEADER_SEPARATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }
}

data class UploadPageText(
    val uploadBackupFile: String = "Upload backup file",
    val backupFile: String = "Backup file",
    val backupPassword: String = "Backup password",
    val upload: String = "Upload",
    val importFailed: String = "Import failed",
    val fileTooLarge: String = "File is larger than 20 MB",
    val noFileUploaded: String = "No file uploaded",
    val missingMultipartBoundary: String = "Missing multipart boundary",
    val badRequest: String = "Bad request",
    val notFound: String = "Not found",
)

private class RequestTooLarge : Exception()

data class UploadRequest(
    val fileName: String,
    val data: ByteArray,
    val password: String,
)

data class UploadResponse(
    val success: Boolean,
    val title: String,
    val message: String,
)

private data class FormPart(
    val name: String,
    val fileName: String,
    val data: ByteArray,
)
