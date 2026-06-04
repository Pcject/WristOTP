package app.wristotp.core.importer

import app.wristotp.core.model.ImportFailure
import app.wristotp.core.model.OtpType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupImporterTest {
    private val importer = BackupImporter()

    @Test
    fun importsUriTextAndSkipsBadLines() {
        val text = """
            otpauth://totp/Example%3Aalice?secret=GEZDGNBVGY3TQOJQ&issuer=Example
            not-a-uri
            otpauth://hotp/Example%3Abob?secret=GEZDGNBVGY3TQOJQ&issuer=Example&counter=5
        """.trimIndent()

        val imported = importer.import("backup.txt", text.toByteArray())

        assertEquals(2, imported.authenticators.size)
        assertEquals(1, imported.skipped.size)
        assertEquals(OtpType.HOTP, imported.authenticators.last().type)
    }

    @Test
    fun importsHtmlCodeNodes() {
        val html = """
            <html><body>
              <code>otpauth://totp/Example%3Aalice?secret=GEZDGNBVGY3TQOJQ&amp;issuer=Example</code>
            </body></html>
        """.trimIndent()

        val imported = importer.import("backup.html", html.toByteArray())

        assertEquals(1, imported.authenticators.size)
        assertEquals("Example", imported.authenticators.first().issuer)
    }

    @Test
    fun importsHtmlHrefAndVisibleOtpUris() {
        val html = """
            <html><body>
              <a href="otpauth://totp/Example%3Aalice?secret=GEZDGNBVGY3TQOJQ&amp;issuer=Example">Alice</a>
              <p>otpauth://hotp/Example%3Abob?secret=GEZDGNBVGY3TQOJQ&amp;issuer=Example&amp;counter=7</p>
            </body></html>
        """.trimIndent()

        val imported = importer.import("backup.html", html.toByteArray())

        assertEquals(2, imported.authenticators.size)
        assertEquals(OtpType.HOTP, imported.authenticators.last().type)
        assertEquals(7, imported.authenticators.last().counter)
    }

    @Test
    fun importsStratumJsonWithCategoryAndCustomIcon() {
        val json = """
            {
              "Authenticators": [
                {
                  "Type": 2,
                  "Icon": "@icon1",
                  "Issuer": "Example",
                  "Username": "alice",
                  "Secret": "GEZDGNBVGY3TQOJQ",
                  "Algorithm": 0,
                  "Digits": 6,
                  "Period": 30,
                  "Counter": 0
                },
                {
                  "Type": 2,
                  "Issuer": "Bad",
                  "Username": "digits",
                  "Secret": "GEZDGNBVGY3TQOJQ",
                  "Digits": 9
                }
              ],
              "Categories": [
                { "Id": "cat1", "Name": "Work", "Ranking": 2 }
              ],
              "AuthenticatorCategories": [
                { "CategoryId": "cat1", "AuthenticatorSecret": "gez dgnbv-gy3tqojq====" }
              ],
              "CustomIcons": [
                { "Id": "icon1", "Data": "iVBORw0KGgo=" },
                { "Id": "../outside", "Data": "iVBORw0KGgo=" }
              ]
            }
        """.trimIndent()

        val imported = importer.import("backup.stratum", json.toByteArray())

        assertEquals(1, imported.authenticators.size)
        assertEquals(listOf("cat1"), imported.authenticators.first().categoryIds)
        assertEquals("icon1", imported.authenticators.first().iconId)
        assertEquals(1, imported.icons.size)
        assertEquals(1, imported.skipped.size)
    }

    @Test
    fun encryptedBackupWithoutPasswordRequestsPassword() {
        val data = "AUTHENTICATORPRO".toByteArray() + ByteArray(64)

        assertFailsWith<ImportFailure.PasswordRequired> {
            importer.import("backup.stratum", data)
        }
    }

    @Test
    fun parsesIssuerAndUsernameFromUri() {
        val auth = OtpAuthUriParser.parse("otpauth://totp/Issuer%3Auser%40example.com?secret=GEZDGNBVGY3TQOJQ&issuer=Issuer&period=60&digits=8")

        assertEquals("Issuer", auth.issuer)
        assertEquals("user@example.com", auth.username)
        assertEquals(60, auth.period)
        assertEquals(8, auth.digits)
        assertNotNull(auth.id)
        assertTrue(auth.secret.isNotBlank())
    }
}
