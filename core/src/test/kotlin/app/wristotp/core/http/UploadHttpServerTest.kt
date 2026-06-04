package app.wristotp.core.http

import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadHttpServerTest {
    @Test
    fun receivesMultipartUpload() {
        var received: UploadRequest? = null
        val server = UploadHttpServer(port = 0) { request ->
            received = request
            UploadResponse(success = true, title = "Import successful", message = "ok")
        }

        try {
            server.start()
            waitUntilStarted(server)

            val boundary = "wristotp-test-boundary"
            val fileBytes = "otpauth://totp/Example?secret=GEZDGNBVGY3TQOJQ".toByteArray()
            val body = multipartBody(boundary, fileBytes)
            val response = Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.getOutputStream().write(
                    (
                        "POST /upload HTTP/1.1\r\n" +
                            "Host: 127.0.0.1\r\n" +
                            "Content-Type: multipart/form-data; boundary=$boundary\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "\r\n"
                        ).toByteArray(),
                )
                socket.getOutputStream().write(body)
                socket.getOutputStream().flush()
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }

            assertContains(response, "200 OK")
            assertEquals("backup.txt", received?.fileName)
            assertEquals("secret", received?.password)
            assertContentEquals(fileBytes, received?.data)
        } finally {
            server.close()
        }
    }

    @Test
    fun servesLocalizedUploadPage() {
        val server = UploadHttpServer(
            port = 0,
            text = UploadPageText(uploadBackupFile = "上传备份文件", backupFile = "备份文件", backupPassword = "备份密码", upload = "上传"),
        ) {
            UploadResponse(success = true, title = "ok", message = "ok")
        }

        try {
            server.start()
            waitUntilStarted(server)

            val response = request(server, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

            assertContains(response, "上传备份文件")
            assertContains(response, "备份密码")
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsOversizedUploadBeforeCallback() {
        var received: UploadRequest? = null
        val server = UploadHttpServer(port = 0) { request ->
            received = request
            UploadResponse(success = true, title = "ok", message = "ok")
        }

        try {
            server.start()
            waitUntilStarted(server)

            val response = request(
                server,
                "POST /upload HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Type: multipart/form-data; boundary=x\r\n" +
                    "Content-Length: ${20 * 1024 * 1024 + 1024 * 128 + 1}\r\n" +
                    "\r\n",
            )

            assertContains(response, "413 Payload Too Large")
            assertNull(received)
        } finally {
            server.close()
        }
    }

    private fun waitUntilStarted(server: UploadHttpServer) {
        repeat(50) {
            if (server.boundPort > 0) return
            Thread.sleep(20)
        }
        error("server did not start")
    }

    private fun request(server: UploadHttpServer, rawRequest: String): String =
        Socket("127.0.0.1", server.boundPort).use { socket ->
            socket.getOutputStream().write(rawRequest.toByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun multipartBody(boundary: String, fileBytes: ByteArray): ByteArray {
        val prefix = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"password\"\r\n" +
                "\r\n" +
                "secret\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"backup.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n"
            ).toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        return prefix + fileBytes + suffix
    }
}
