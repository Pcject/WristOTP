package app.wristotp.core.storage

import app.wristotp.core.model.AppState
import app.wristotp.core.model.Authenticator
import app.wristotp.core.model.Category
import app.wristotp.core.model.ImportedBackup
import app.wristotp.core.model.Preferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class FileDataStore(
    private val filesDir: File,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    },
    private val beforeInstall: (() -> Unit)? = null,
) {
    private val authenticatorsFile = File(filesDir, "authenticators.json")
    private val categoriesFile = File(filesDir, "categories.json")
    private val preferencesFile = File(filesDir, "preferences.json")
    private val iconsDir = File(filesDir, "icons")

    fun load(): AppState {
        filesDir.mkdirs()
        iconsDir.mkdirs()
        var error: String? = null
        val preferences = readJson(preferencesFile, Preferences.serializer(), Preferences()) {
            error = error ?: it
        }
        val authenticators = readJson(authenticatorsFile, ListSerializer(Authenticator.serializer()), emptyList()) {
            error = error ?: it
        }
        val categories = readJson(categoriesFile, ListSerializer(Category.serializer()), emptyList()) {
            error = error ?: it
        }

        return AppState(
            authenticators = authenticators,
            categories = categories,
            preferences = preferences,
            dataError = error,
        )
    }

    fun savePreferences(preferences: Preferences) {
        filesDir.mkdirs()
        atomicWrite(preferencesFile, json.encodeToString(Preferences.serializer(), preferences).toByteArray())
    }

    fun saveAuthenticators(authenticators: List<Authenticator>) {
        filesDir.mkdirs()
        atomicWrite(authenticatorsFile, json.encodeToString(ListSerializer(Authenticator.serializer()), authenticators).toByteArray())
    }

    fun replaceImportedData(imported: ImportedBackup) {
        filesDir.mkdirs()
        val transactionId = System.nanoTime()
        val stageDir = File(filesDir, "import-stage.$transactionId")
        val backupDir = File(filesDir, "import-backup.$transactionId")

        try {
            stageDir.mkdirs()
            val stagedIconsDir = File(stageDir, "icons")
            stagedIconsDir.mkdirs()

            File(stageDir, authenticatorsFile.name).writeBytes(
                json.encodeToString(ListSerializer(Authenticator.serializer()), imported.authenticators).toByteArray(),
            )
            File(stageDir, categoriesFile.name).writeBytes(
                json.encodeToString(ListSerializer(Category.serializer()), imported.categories).toByteArray(),
            )

            for (importedIcon in imported.icons) {
                File(stagedIconsDir, importedIcon.icon.fileName).writeBytes(importedIcon.data)
            }

            backupDir.mkdirs()
            moveIfExists(authenticatorsFile, File(backupDir, authenticatorsFile.name))
            moveIfExists(categoriesFile, File(backupDir, categoriesFile.name))
            moveIfExists(iconsDir, File(backupDir, iconsDir.name))

            try {
                beforeInstall?.invoke()
                Files.move(File(stageDir, authenticatorsFile.name).toPath(), authenticatorsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(File(stageDir, categoriesFile.name).toPath(), categoriesFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                Files.move(stagedIconsDir.toPath(), iconsDir.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                rollbackImport(backupDir)
                throw e
            }

            backupDir.deleteRecursively()
        } finally {
            if (stageDir.exists()) stageDir.deleteRecursively()
            if (backupDir.exists()) backupDir.deleteRecursively()
        }
    }

    fun iconFile(iconId: String): File = File(iconsDir, iconId)

    private fun <T> readJson(
        file: File,
        serializer: kotlinx.serialization.KSerializer<T>,
        defaultValue: T,
        onError: (String) -> Unit,
    ): T {
        if (!file.exists()) return defaultValue
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            onError("Could not read ${file.name}: ${e.message}")
            defaultValue
        }
    }

    private fun atomicWrite(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp.${System.nanoTime()}")
        temp.writeBytes(data)
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun moveIfExists(source: File, target: File) {
        if (!source.exists()) return
        target.parentFile?.mkdirs()
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun rollbackImport(backupDir: File) {
        if (authenticatorsFile.exists()) authenticatorsFile.delete()
        if (categoriesFile.exists()) categoriesFile.delete()
        if (iconsDir.exists()) iconsDir.deleteRecursively()

        moveIfExists(File(backupDir, authenticatorsFile.name), authenticatorsFile)
        moveIfExists(File(backupDir, categoriesFile.name), categoriesFile)
        moveIfExists(File(backupDir, iconsDir.name), iconsDir)
    }
}
