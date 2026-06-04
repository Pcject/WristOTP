package app.wristotp.core.crypto

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

object StratumBackupCrypto {
    private val strongHeader = "AUTHENTICATORPRO".toByteArray(StandardCharsets.UTF_8)
    private val legacyHeader = "AuthenticatorPro".toByteArray(StandardCharsets.UTF_8)

    fun decrypt(data: ByteArray, password: String?): ByteArray {
        return when {
            data.startsWith(strongHeader) -> decryptStrong(data, password)
            data.startsWith(legacyHeader) -> decryptLegacy(data, password)
            else -> data
        }
    }

    fun looksEncrypted(data: ByteArray): Boolean = data.startsWith(strongHeader) || data.startsWith(legacyHeader)

    private fun decryptStrong(data: ByteArray, password: String?): ByteArray {
        if (password.isNullOrEmpty()) throw ImportFailure.PasswordRequired()
        val offset = strongHeader.size
        require(data.size > offset + 16 + 12 + 16) { "Strong backup is truncated" }
        val salt = data.copyOfRange(offset, offset + 16)
        val iv = data.copyOfRange(offset + 16, offset + 16 + 12)
        val cipherText = data.copyOfRange(offset + 16 + 12, data.size)
        val key = deriveArgon2id(password, salt)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            throw ImportFailure.PasswordError(e)
        }
    }

    private fun decryptLegacy(data: ByteArray, password: String?): ByteArray {
        if (password.isNullOrEmpty()) throw ImportFailure.PasswordRequired()
        val offset = legacyHeader.size
        require(data.size > offset + 20 + 16) { "Legacy backup is truncated" }
        val salt = data.copyOfRange(offset, offset + 20)
        val iv = data.copyOfRange(offset + 20, offset + 20 + 16)
        val cipherText = data.copyOfRange(offset + 20 + 16, data.size)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 64_000, 256))
            .encoded

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            throw ImportFailure.PasswordError(e)
        }
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

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
