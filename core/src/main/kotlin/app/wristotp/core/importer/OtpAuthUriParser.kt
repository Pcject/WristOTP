package app.wristotp.core.importer

import app.wristotp.core.model.Authenticator
import app.wristotp.core.model.ImportSkipped
import app.wristotp.core.model.OtpAlgorithm
import app.wristotp.core.model.OtpType
import app.wristotp.core.otp.Base32
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object OtpAuthUriParser {
    fun parse(uriText: String): Authenticator {
        val trimmed = uriText.trim()
        val uri = URI(trimmed)
        require(uri.scheme.equals("otpauth", ignoreCase = true)) { "Unsupported URI scheme" }

        val type = when (uri.host?.lowercase()) {
            "totp" -> OtpType.TOTP
            "hotp" -> OtpType.HOTP
            else -> throw IllegalArgumentException("Unsupported OTP type")
        }

        val params = parseQuery(uri.rawQuery.orEmpty())
        val label = decode(uri.rawPath.orEmpty().removePrefix("/")).trim()
        require(label.isNotEmpty()) { "Label is required" }

        val issuerParam = params["issuer"]?.trim().orEmpty()
        val labelParts = label.split(":", limit = 2)
        val issuer: String
        val username: String?

        if (labelParts.size == 2) {
            issuer = labelParts[0].ifBlank { labelParts[1] }.trim()
            username = labelParts[1].trim().ifBlank { null }
        } else if (issuerParam.isNotBlank()) {
            issuer = issuerParam
            username = label.ifBlank { null }
        } else {
            issuer = label
            username = null
        }

        val secret = Base32.normalize(params["secret"] ?: throw IllegalArgumentException("Secret is required"))
        Base32.decode(secret)

        val algorithm = when (params["algorithm"]?.uppercase()) {
            null, "", "SHA1" -> OtpAlgorithm.SHA1
            "SHA256" -> OtpAlgorithm.SHA256
            "SHA512" -> OtpAlgorithm.SHA512
            else -> throw IllegalArgumentException("Unsupported algorithm")
        }

        val digits = params["digits"]?.toIntOrNull() ?: 6
        require(digits == 6 || digits == 8) { "Only 6 and 8 digit OTP codes are supported" }

        val period = params["period"]?.toIntOrNull() ?: 30
        require(period > 0) { "Period must be positive" }

        val counter = params["counter"]?.toLongOrNull() ?: 0
        require(counter >= 0) { "Counter cannot be negative" }

        val stableId = sha1("$type|$issuer|$username|$secret|$counter")
        return Authenticator(
            id = stableId,
            issuer = issuer.take(64),
            label = label.take(96),
            username = username?.take(96),
            secret = secret,
            type = type,
            algorithm = algorithm,
            digits = digits,
            period = period,
            counter = counter,
        )
    }

    fun parseLines(lines: Iterable<String>): Pair<List<Authenticator>, List<ImportSkipped>> {
        val authenticators = mutableListOf<Authenticator>()
        val skipped = mutableListOf<ImportSkipped>()

        for (line in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (!line.startsWith("otpauth://", ignoreCase = true)) {
                skipped += ImportSkipped(line.take(120), "Not an otpauth URI")
                continue
            }

            try {
                authenticators += parse(line)
            } catch (e: Exception) {
                skipped += ImportSkipped(line.take(120), e.message ?: "Invalid URI")
            }
        }

        return authenticators.distinctBy { it.id } to skipped
    }

    private fun parseQuery(rawQuery: String): Map<String, String> =
        rawQuery.split("&")
            .filter { it.isNotBlank() }
            .associate { part ->
                val pieces = part.split("=", limit = 2)
                decode(pieces[0]) to decode(pieces.getOrElse(1) { "" })
            }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
