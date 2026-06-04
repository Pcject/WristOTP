package app.wristotp.core.importer

import app.wristotp.core.http.UploadHttpServer
import app.wristotp.core.http.UploadRequest
import app.wristotp.core.http.UploadResponse
import app.wristotp.core.model.Authenticator
import app.wristotp.core.model.ImportFailure
import app.wristotp.core.model.ImportedBackup
import app.wristotp.core.model.OtpType
import app.wristotp.core.storage.FileDataStore
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.File
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class EncryptedHttpImportPipelineTest {
    private val importer = BackupImporter()

    @Test
    fun strongEncryptedUploadRejectsWrongPasswordThenImportsWithCorrectPassword() {
        runEncryptedUploadPipeline(
            fileBytes = encryptStrong(backupJson("StrongImported"), "passphrase"),
            expectedIssuer = "StrongImported",
        )
    }

    @Test
    fun legacyEncryptedUploadRejectsWrongPasswordThenImportsWithCorrectPassword() {
        runEncryptedUploadPipeline(
            fileBytes = encryptLegacy(backupJson("LegacyImported"), "passphrase"),
            expectedIssuer = "LegacyImported",
        )
    }

    private fun runEncryptedUploadPipeline(fileBytes: ByteArray, expectedIssuer: String) {
        val dir = tempDir()
        val store = FileDataStore(dir)
        store.replaceImportedData(ImportedBackup(authenticators = listOf(oldAuthenticator())))

        val server = UploadHttpServer(port = 0) { request ->
            handleUpload(request, store)
        }

        try {
            server.start()
            waitUntilStarted(server)

            val wrong = postMultipart(server, fileBytes, password = "wrong")
            assertContains(wrong, "400 Bad Request")
            assertContains(wrong, "Password error")
            assertEquals("Existing", FileDataStore(dir).load().authenticators.single().issuer)

            val correct = postMultipart(server, fileBytes, password = "passphrase")
            assertContains(correct, "200 OK")
            assertContains(correct, "Import successful")
            assertEquals(expectedIssuer, FileDataStore(dir).load().authenticators.single().issuer)
        } finally {
            server.close()
        }
    }

    private fun handleUpload(request: UploadRequest, store: FileDataStore): UploadResponse {
        return try {
            val imported = importer.import(request.fileName, request.data, request.password.ifBlank { null })
            store.replaceImportedData(imported)
            UploadResponse(success = true, title = "Import successful", message = "Import successful")
        } catch (_: ImportFailure.PasswordError) {
            UploadResponse(success = false, title = "Password error", message = "Password error")
        } catch (e: Exception) {
            UploadResponse(success = false, title = "Import failed", message = e.message ?: "Import failed")
        }
    }

    private fun postMultipart(server: UploadHttpServer, fileBytes: ByteArray, password: String): String {
        val boundary = "wristotp-encrypted-boundary"
        val body = multipartBody(boundary, fileBytes, password)
        return Socket("127.0.0.1", server.boundPort).use { socket ->
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
    }

    private fun multipartBody(boundary: String, fileBytes: ByteArray, password: String): ByteArray {
        val prefix = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"password\"\r\n" +
                "\r\n" +
                "$password\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"backup.stratum\"\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n"
            ).toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        return prefix + fileBytes + suffix
    }

    private fun waitUntilStarted(server: UploadHttpServer) {
        repeat(50) {
            if (server.boundPort > 0) return
            Thread.sleep(20)
        }
        error("server did not start")
    }

    private fun backupJson(issuer: String): ByteArray = """
        {
          "Authenticators": [
            {
              "Type": 2,
              "Issuer": "$issuer",
              "Username": "alice",
              "Secret": "GEZDGNBVGY3TQOJQ",
              "Algorithm": 0,
              "Digits": 6,
              "Period": 30,
              "Counter": 0
            }
          ]
        }
    """.trimIndent().toByteArray()

    private fun oldAuthenticator(): Authenticator = Authenticator(
        id = "old",
        issuer = "Existing",
        label = "Existing:old",
        username = "old",
        secret = "GEZDGNBVGY3TQOJQ",
        type = OtpType.TOTP,
    )

    private fun encryptStrong(plain: ByteArray, password: String): ByteArray {
        val header = "AUTHENTICATORPRO".toByteArray(StandardCharsets.UTF_8)
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 31).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveArgon2id(password, salt), "AES"), GCMParameterSpec(128, iv))
        return header + salt + iv + cipher.doFinal(plain)
    }

    private fun encryptLegacy(plain: ByteArray, password: String): ByteArray {
        val header = "AuthenticatorPro".toByteArray(StandardCharsets.UTF_8)
        val salt = ByteArray(20) { (it + 11).toByte() }
        val iv = ByteArray(16) { (it + 71).toByte() }
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 64_000, 256))
            .encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return header + salt + iv + cipher.doFinal(plain)
    }

    private fun deriveArgon2id(password: String, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(4)
            .withIterations(3)
            .withMemoryAsKB(65_536)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val key = ByteArray(32)
        generator.generateBytes(password.toByteArray(StandardCharsets.UTF_8), key)
        return key
    }

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("wristotp-pipeline-test").toFile()
}
