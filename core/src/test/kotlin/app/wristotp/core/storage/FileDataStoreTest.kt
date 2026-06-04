package app.wristotp.core.storage

import app.wristotp.core.model.Authenticator
import app.wristotp.core.model.CustomIcon
import app.wristotp.core.model.ImportedBackup
import app.wristotp.core.model.ImportedIcon
import app.wristotp.core.model.OtpType
import app.wristotp.core.model.Preferences
import app.wristotp.core.model.SortMode
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileDataStoreTest {
    @Test
    fun emptyDirectoryLoadsEmptyStateWithoutError() {
        val store = FileDataStore(tempDir())

        val state = store.load()

        assertTrue(state.authenticators.isEmpty())
        assertTrue(state.categories.isEmpty())
        assertNull(state.dataError)
    }

    @Test
    fun replaceImportedDataPersistsAuthenticators() {
        val dir = tempDir()
        val store = FileDataStore(dir)
        val auth = Authenticator(
            id = "auth1",
            issuer = "Example",
            label = "Example:alice",
            username = "alice",
            secret = "GEZDGNBVGY3TQOJQ",
            type = OtpType.TOTP,
        )

        store.replaceImportedData(ImportedBackup(authenticators = listOf(auth)))

        val state = FileDataStore(dir).load()
        assertEquals(1, state.authenticators.size)
        assertEquals("Example", state.authenticators.first().issuer)
    }

    @Test
    fun replaceImportedDataPreservesPreferencesAndClearsOldIcons() {
        val dir = tempDir()
        val store = FileDataStore(dir)
        val authWithIcon = auth("auth1").copy(iconId = "icon1")
        store.savePreferences(Preferences(sortMode = SortMode.ISSUER_ASC))
        store.replaceImportedData(
            ImportedBackup(
                authenticators = listOf(authWithIcon),
                icons = listOf(ImportedIcon(CustomIcon("icon1", "image/png", "icon1"), byteArrayOf(1, 2, 3))),
            ),
        )
        assertTrue(File(dir, "icons/icon1").exists())

        store.replaceImportedData(ImportedBackup(authenticators = listOf(auth("auth2"))))

        val state = FileDataStore(dir).load()
        assertEquals(SortMode.ISSUER_ASC, state.preferences.sortMode)
        assertEquals("auth2", state.authenticators.single().id)
        assertFalse(File(dir, "icons/icon1").exists())
    }

    @Test
    fun saveAuthenticatorsPersistsCustomOrder() {
        val dir = tempDir()
        val store = FileDataStore(dir)
        store.replaceImportedData(ImportedBackup(authenticators = listOf(auth("one"), auth("two"), auth("three"))))

        store.saveAuthenticators(listOf(auth("three"), auth("one"), auth("two")))

        val state = FileDataStore(dir).load()
        assertEquals(listOf("three", "one", "two"), state.authenticators.map { it.id })
    }

    @Test
    fun failedReplaceRollsBackOldData() {
        val dir = tempDir()
        FileDataStore(dir).replaceImportedData(ImportedBackup(authenticators = listOf(auth("old"))))
        val failingStore = FileDataStore(dir, beforeInstall = { throw IOException("boom") })

        assertFailsWith<IOException> {
            failingStore.replaceImportedData(ImportedBackup(authenticators = listOf(auth("new"))))
        }

        val state = FileDataStore(dir).load()
        assertEquals("old", state.authenticators.single().id)
    }

    @Test
    fun corruptedJsonDoesNotBlockLoad() {
        val dir = tempDir()
        File(dir, "authenticators.json").writeText("{not-json")
        val store = FileDataStore(dir)

        val state = store.load()

        assertTrue(state.authenticators.isEmpty())
        assertNotNull(state.dataError)
    }

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("wristotp-test").toFile()

    private fun auth(id: String): Authenticator = Authenticator(
        id = id,
        issuer = "Example",
        label = "Example:alice",
        username = "alice",
        secret = "GEZDGNBVGY3TQOJQ",
        type = OtpType.TOTP,
    )
}
