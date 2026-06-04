package app.wristotp.core.otp

import app.wristotp.core.model.Authenticator
import app.wristotp.core.model.OtpAlgorithm
import app.wristotp.core.model.OtpType
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object OtpGenerator {
    fun code(authenticator: Authenticator, unixSeconds: Long = System.currentTimeMillis() / 1000): String {
        require(authenticator.type == OtpType.TOTP) { "Only TOTP codes are generated for display" }
        return totp(
            secret = authenticator.secret,
            unixSeconds = unixSeconds,
            period = authenticator.period,
            algorithm = authenticator.algorithm,
            digits = authenticator.digits,
        )
    }

    fun remainingSeconds(authenticator: Authenticator, unixSeconds: Long = System.currentTimeMillis() / 1000): Int {
        if (authenticator.type != OtpType.TOTP) return 0
        val period = authenticator.period.coerceAtLeast(1)
        val remaining = period - (unixSeconds % period).toInt()
        return if (remaining == 0) period else remaining
    }

    fun totp(
        secret: String,
        unixSeconds: Long,
        period: Int = 30,
        algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
        digits: Int = 6,
    ): String {
        require(period > 0) { "Period must be positive" }
        return hotp(secret, unixSeconds / period, algorithm, digits)
    }

    fun hotp(
        secret: String,
        counter: Long,
        algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
        digits: Int = 6,
    ): String {
        require(digits == 6 || digits == 8) { "Digits must be 6 or 8" }
        val secretBytes = Base32.decode(secret)
        val counterBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array()
        val mac = Mac.getInstance(algorithm.macName)
        mac.init(SecretKeySpec(secretBytes, algorithm.macName))
        val hash = mac.doFinal(counterBytes)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val modulo = 10.0.pow(digits).toInt()
        return (binary % modulo).toString().padStart(digits, '0')
    }

    private val OtpAlgorithm.macName: String
        get() = when (this) {
            OtpAlgorithm.SHA1 -> "HmacSHA1"
            OtpAlgorithm.SHA256 -> "HmacSHA256"
            OtpAlgorithm.SHA512 -> "HmacSHA512"
        }
}
