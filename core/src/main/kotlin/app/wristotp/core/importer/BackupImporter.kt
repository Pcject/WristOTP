package app.wristotp.core.importer

import app.wristotp.core.crypto.StratumBackupCrypto
import app.wristotp.core.model.Authenticator
import app.wristotp.core.model.Category
import app.wristotp.core.model.CustomIcon
import app.wristotp.core.model.ImportFailure
import app.wristotp.core.model.ImportSkipped
import app.wristotp.core.model.ImportedBackup
import app.wristotp.core.model.ImportedIcon
import app.wristotp.core.model.OtpAlgorithm
import app.wristotp.core.model.OtpType
import app.wristotp.core.otp.Base32
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.util.Base64

class BackupImporter(
    private val json: Json = Json {
        ignoreUnknownKeys = true
    },
) {
    fun import(fileName: String, data: ByteArray, password: String? = null): ImportedBackup {
        if (data.size > MAX_BYTES) throw ImportFailure.UnsupportedFormat("File is larger than 20 MB")
        val lowerName = fileName.lowercase()

        return when {
            lowerName.endsWith(".txt") -> importUriText(data)
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") -> importHtml(data)
            else -> importStratumOrFallback(data, password)
        }
    }

    private fun importUriText(data: ByteArray): ImportedBackup {
        val text = data.toString(Charsets.UTF_8)
        val (authenticators, skipped) = OtpAuthUriParser.parseLines(text.lineSequence().asIterable())
        if (authenticators.isEmpty()) throw ImportFailure.UnsupportedFormat("No supported OTP URIs found")
        return ImportedBackup(authenticators = authenticators, skipped = skipped)
    }

    private fun importHtml(data: ByteArray): ImportedBackup {
        val document = Jsoup.parse(data.toString(Charsets.UTF_8))
        val lines = extractHtmlOtpUris(document)
        if (lines.isEmpty()) throw ImportFailure.UnsupportedFormat("No OTP URIs found in HTML")
        val (authenticators, skipped) = OtpAuthUriParser.parseLines(lines)
        if (authenticators.isEmpty()) throw ImportFailure.UnsupportedFormat("No supported OTP URIs found")
        return ImportedBackup(authenticators = authenticators, skipped = skipped)
    }

    private fun extractHtmlOtpUris(document: Document): List<String> {
        val candidates = mutableListOf<String>()
        candidates += document.select("code").map { it.text() }
        candidates += document.select("a[href]").map { it.attr("href") }
        candidates += document.text()
        return candidates
            .flatMap { text -> OTP_URI_REGEX.findAll(text).map { it.value }.toList() }
            .distinct()
    }

    private fun importStratumOrFallback(data: ByteArray, password: String?): ImportedBackup {
        val decrypted = try {
            StratumBackupCrypto.decrypt(data, password)
        } catch (e: ImportFailure) {
            throw e
        } catch (e: Exception) {
            throw ImportFailure.ParseError(e.message ?: "Could not decrypt backup", e)
        }

        return try {
            importStratumJson(decrypted)
        } catch (e: ImportFailure) {
            if (StratumBackupCrypto.looksEncrypted(data)) throw e
            importUriText(data)
        } catch (e: Exception) {
            if (StratumBackupCrypto.looksEncrypted(data)) {
                throw ImportFailure.ParseError(e.message ?: "Could not parse Stratum backup", e)
            }
            importUriText(data)
        }
    }

    private fun importStratumJson(data: ByteArray): ImportedBackup {
        val backup = try {
            json.decodeFromString(StratumBackup.serializer(), data.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ImportFailure.ParseError("Could not parse Stratum JSON", e)
        }

        val rawAuthenticators = backup.authenticators.orEmpty()
        if (rawAuthenticators.isEmpty()) throw ImportFailure.UnsupportedFormat("No authenticators in Stratum backup")

        val customIcons = backup.customIcons.orEmpty().mapNotNull { raw ->
            val id = safeId(raw.id) ?: return@mapNotNull null
            val decoded = runCatching { Base64.getMimeDecoder().decode(raw.data.orEmpty()) }.getOrNull()
                ?: return@mapNotNull null
            ImportedIcon(
                icon = CustomIcon(id = id, mimeType = detectMimeType(decoded), fileName = id),
                data = decoded,
            )
        }
        val customIconIds = customIcons.map { it.icon.id }.toSet()

        val categories = backup.categories.orEmpty()
            .mapNotNull { raw ->
                val id = raw.id ?: return@mapNotNull null
                val name = raw.name?.trim()?.take(64)?.ifBlank { null } ?: return@mapNotNull null
                Category(id = id, name = name, order = raw.ranking ?: 0)
            }
            .sortedBy { it.order }

        val categoryIds = categories.map { it.id }.toSet()
        val categoryMap = backup.authenticatorCategories.orEmpty()
            .mapNotNull { binding ->
                val secret = binding.authenticatorSecret?.let { Base32.normalize(it) } ?: return@mapNotNull null
                val categoryId = binding.categoryId?.takeIf { it in categoryIds } ?: return@mapNotNull null
                secret to categoryId
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, bindings) -> bindings.distinct() }

        val skipped = mutableListOf<ImportSkipped>()
        val authenticators = rawAuthenticators.mapNotNull { raw ->
            try {
                raw.toAuthenticator(customIconIds, categoryMap)
            } catch (e: Exception) {
                skipped += ImportSkipped(raw.issuer ?: raw.username ?: raw.secret.orEmpty().take(16), e.message ?: "Invalid authenticator")
                null
            }
        }.distinctBy { it.id }

        if (authenticators.isEmpty()) {
            throw ImportFailure.UnsupportedFormat("No supported TOTP/HOTP entries found")
        }

        val usedIconIds = authenticators.mapNotNull { it.iconId }.toSet()
        return ImportedBackup(
            authenticators = authenticators,
            categories = categories,
            icons = customIcons.filter { it.icon.id in usedIconIds },
            skipped = skipped,
        )
    }

    private fun StratumAuthenticator.toAuthenticator(
        customIconIds: Set<String>,
        categoryMap: Map<String, List<String>>,
    ): Authenticator {
        val otpType = when (type) {
            1 -> OtpType.HOTP
            2 -> OtpType.TOTP
            else -> throw IllegalArgumentException("Unsupported authenticator type")
        }
        val secret = Base32.normalize(secret ?: throw IllegalArgumentException("Secret is required"))
        Base32.decode(secret)
        val resolvedIssuer = issuer?.trim()?.take(64)?.ifBlank { null }
            ?: username?.trim()?.take(64)?.ifBlank { null }
            ?: "Authenticator"
        val resolvedUsername = username?.trim()?.take(96)?.ifBlank { null }
        val resolvedLabel = if (resolvedUsername != null) "$resolvedIssuer:$resolvedUsername" else resolvedIssuer
        val iconId = icon?.takeIf { it.startsWith("@") }?.removePrefix("@")?.let(::safeId)?.takeIf { it in customIconIds }
        val algorithm = when (algorithm ?: 0) {
            0 -> OtpAlgorithm.SHA1
            1 -> OtpAlgorithm.SHA256
            2 -> OtpAlgorithm.SHA512
            else -> throw IllegalArgumentException("Unsupported algorithm")
        }
        val resolvedDigits = digits ?: 6
        require(resolvedDigits == 6 || resolvedDigits == 8) { "Only 6 and 8 digit OTP codes are supported" }
        val resolvedPeriod = period ?: 30
        require(resolvedPeriod > 0) { "Period must be positive" }

        return Authenticator(
            id = sha1("$otpType|$resolvedIssuer|$resolvedUsername|$secret|${counter ?: 0}"),
            issuer = resolvedIssuer,
            label = resolvedLabel,
            username = resolvedUsername,
            secret = secret,
            type = otpType,
            algorithm = algorithm,
            digits = resolvedDigits,
            period = resolvedPeriod,
            counter = counter ?: 0,
            iconId = iconId,
            categoryIds = categoryMap[secret].orEmpty(),
        )
    }

    private fun detectMimeType(data: ByteArray): String = when {
        data.size >= 8 &&
            data[0] == 0x89.toByte() &&
            data[1] == 'P'.code.toByte() &&
            data[2] == 'N'.code.toByte() &&
            data[3] == 'G'.code.toByte() -> "image/png"

        data.size >= 3 &&
            data[0] == 0xff.toByte() &&
            data[1] == 0xd8.toByte() &&
            data[2] == 0xff.toByte() -> "image/jpeg"

        else -> "application/octet-stream"
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun safeId(value: String?): String? {
        val trimmed = value?.trim()?.take(64) ?: return null
        return trimmed.takeIf { SAFE_ID_REGEX.matches(it) }
    }

    companion object {
        const val MAX_BYTES: Int = 20 * 1024 * 1024
        private val OTP_URI_REGEX = Regex("""otpauth://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        private val SAFE_ID_REGEX = Regex("""[A-Za-z0-9._-]{1,64}""")
    }
}

@Serializable
private data class StratumBackup(
    @SerialName("Authenticators") val authenticators: List<StratumAuthenticator>? = null,
    @SerialName("Categories") val categories: List<StratumCategory>? = null,
    @SerialName("AuthenticatorCategories") val authenticatorCategories: List<StratumAuthenticatorCategory>? = null,
    @SerialName("CustomIcons") val customIcons: List<StratumCustomIcon>? = null,
)

@Serializable
private data class StratumAuthenticator(
    @SerialName("Type") val type: Int? = null,
    @SerialName("Icon") val icon: String? = null,
    @SerialName("Issuer") val issuer: String? = null,
    @SerialName("Username") val username: String? = null,
    @SerialName("Secret") val secret: String? = null,
    @SerialName("Algorithm") val algorithm: Int? = null,
    @SerialName("Digits") val digits: Int? = null,
    @SerialName("Period") val period: Int? = null,
    @SerialName("Counter") val counter: Long? = null,
)

@Serializable
private data class StratumCategory(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Ranking") val ranking: Int? = null,
)

@Serializable
private data class StratumAuthenticatorCategory(
    @SerialName("CategoryId") val categoryId: String? = null,
    @SerialName("AuthenticatorSecret") val authenticatorSecret: String? = null,
)

@Serializable
private data class StratumCustomIcon(
    @SerialName("Id") val id: String? = null,
    @SerialName("Data") val data: String? = null,
)
