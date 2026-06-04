package app.wristotp.core.otp

import app.wristotp.core.model.OtpAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OtpGeneratorTest {
    @Test
    fun hotpMatchesRfc4226() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")

        expected.forEachIndexed { counter, code ->
            assertEquals(code, OtpGenerator.hotp(secret, counter.toLong()))
        }
    }

    @Test
    fun totpMatchesRfc6238Sha1() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

        assertEquals("94287082", OtpGenerator.totp(secret, 59, algorithm = OtpAlgorithm.SHA1, digits = 8))
        assertEquals("07081804", OtpGenerator.totp(secret, 1_111_111_109, algorithm = OtpAlgorithm.SHA1, digits = 8))
        assertEquals("89005924", OtpGenerator.totp(secret, 1_234_567_890, algorithm = OtpAlgorithm.SHA1, digits = 8))
    }

    @Test
    fun base32NormalizesCommonFormatting() {
        assertEquals(
            Base32.decode("abcd efgh-ij==").toList(),
            Base32.decode("ABCDEFGHIJ").toList(),
        )
    }

    @Test
    fun rejectsUnsupportedDigitCount() {
        assertFailsWith<IllegalArgumentException> {
            OtpGenerator.hotp("GEZDGNBVGY3TQOJQ", 0, digits = 7)
        }
    }
}
