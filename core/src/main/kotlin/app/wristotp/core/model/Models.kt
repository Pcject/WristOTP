package app.wristotp.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class OtpType {
    TOTP,
    HOTP
}

@Serializable
enum class OtpAlgorithm {
    SHA1,
    SHA256,
    SHA512
}

@Serializable
data class Authenticator(
    val id: String,
    val issuer: String,
    val label: String,
    val username: String? = null,
    val secret: String,
    val type: OtpType = OtpType.TOTP,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    val counter: Long = 0,
    val iconId: String? = null,
    val categoryIds: List<String> = emptyList(),
)

@Serializable
data class Category(
    val id: String,
    val name: String,
    val order: Int = 0,
)

@Serializable
data class CustomIcon(
    val id: String,
    val mimeType: String,
    val fileName: String,
)

@Serializable
data class Preferences(
    val sortMode: SortMode = SortMode.CUSTOM,
    val showUsername: Boolean = true,
    val groupingMode: GroupingMode = GroupingMode.NONE,
)

@Serializable
enum class SortMode {
    CUSTOM,
    ISSUER_ASC,
    ISSUER_DESC,
}

@Serializable
enum class GroupingMode {
    NONE,
    CATEGORY,
}

data class ImportedIcon(
    val icon: CustomIcon,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImportedIcon) return false
        return icon == other.icon && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * icon.hashCode() + data.contentHashCode()
}

data class ImportedBackup(
    val authenticators: List<Authenticator>,
    val categories: List<Category> = emptyList(),
    val icons: List<ImportedIcon> = emptyList(),
    val skipped: List<ImportSkipped> = emptyList(),
)

data class ImportSkipped(
    val description: String,
    val reason: String,
)

sealed class ImportFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class PasswordRequired : ImportFailure("Password required")
    class PasswordError(cause: Throwable? = null) : ImportFailure("Password error", cause)
    class UnsupportedFormat(message: String = "Unsupported file format", cause: Throwable? = null) :
        ImportFailure(message, cause)

    class ParseError(message: String, cause: Throwable? = null) : ImportFailure(message, cause)
}

data class AppState(
    val authenticators: List<Authenticator> = emptyList(),
    val categories: List<Category> = emptyList(),
    val preferences: Preferences = Preferences(),
    val dataError: String? = null,
)
