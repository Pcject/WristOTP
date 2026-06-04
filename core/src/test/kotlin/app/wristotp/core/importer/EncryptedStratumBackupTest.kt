package app.wristotp.core.importer

import app.wristotp.core.crypto.StratumBackupCrypto
import app.wristotp.core.model.ImportFailure
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EncryptedStratumBackupTest {
    private val importer = BackupImporter()
    private val backupJson = """
        {
          "Authenticators": [
            {
              "Type": 2,
              "Issuer": "Example",
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

    @Test
    fun importsStrongEncryptedStratumBackup() {
        val data = encryptStrong(backupJson, "passphrase")

        val imported = importer.import("backup.stratum", data, "passphrase")

        assertEquals(1, imported.authenticators.size)
        assertEquals("Example", imported.authenticators.first().issuer)
    }

    @Test
    fun rejectsStrongEncryptedBackupWithWrongPassword() {
        val data = encryptStrong(backupJson, "passphrase")

        assertFailsWith<ImportFailure.PasswordError> {
            importer.import("backup.stratum", data, "wrong")
        }
    }

    @Test
    fun importsLegacyEncryptedStratumBackup() {
        val data = encryptLegacy(backupJson, "passphrase")

        val imported = importer.import("backup.stratum", data, "passphrase")

        assertEquals(1, imported.authenticators.size)
        assertEquals("alice", imported.authenticators.first().username)
    }

    @Test
    fun rejectsLegacyEncryptedBackupWithWrongPassword() {
        val data = encryptLegacy(backupJson, "passphrase")

        assertFailsWith<ImportFailure.PasswordError> {
            importer.import("backup.stratum", data, "wrong")
        }
    }

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
}
