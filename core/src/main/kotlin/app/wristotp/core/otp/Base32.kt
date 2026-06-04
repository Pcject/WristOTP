package app.wristotp.core.otp

import java.io.ByteArrayOutputStream

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun normalize(secret: String): String =
        secret.trim()
            .uppercase()
            .replace(" ", "")
            .replace("-", "")
            .trimEnd('=')

    fun decode(secret: String): ByteArray {
        val normalized = normalize(secret)
        require(normalized.isNotEmpty()) { "Secret cannot be empty" }

        var buffer = 0
        var bitsLeft = 0
        val out = ByteArrayOutputStream()

        for (char in normalized) {
            val value = ALPHABET.indexOf(char)
            require(value >= 0) { "Invalid base32 character: $char" }

            buffer = (buffer shl 5) or value
            bitsLeft += 5

            if (bitsLeft >= 8) {
                out.write((buffer shr (bitsLeft - 8)) and 0xff)
                bitsLeft -= 8
            }
        }

        val bytes = out.toByteArray()
        require(bytes.isNotEmpty()) { "Secret decoded to empty bytes" }
        return bytes
    }
}
