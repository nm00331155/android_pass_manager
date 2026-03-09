package com.securevault.app.data.backup

import com.securevault.app.data.repository.model.CredentialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [CsvImportParser] のユニットテスト。
 */
class CsvImportParserTest {

    private val parser = CsvImportParser()

    @Test
    fun `parse Brave CSV maps expected fields`() {
        val csv = """
            name,url,username,password,note
            GitHub,https://github.com,user1,pass1,メモ
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.BRAVE)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("メモ", result[0].notes)
    }

    @Test
    fun `parse Bitwarden CSV maps expected fields`() {
        val csv = """
            folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp
            ,1,login,GitHub,メモ,,,https://github.com,user1,pass1,
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.BITWARDEN)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("メモ", result[0].notes)
    }

    @Test
    fun `parse LastPass CSV maps expected fields`() {
        val csv = """
            url,username,password,totp,extra,name,grouping,fav
            https://github.com,user1,pass1,,メモ,GitHub,,0
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.LASTPASS)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("メモ", result[0].notes)
    }

    @Test
    fun `parse Firefox CSV extracts domain as service name`() {
        val csv = """
            url,username,password,httpRealm,formActionOrigin,guid,timeCreated,timeLastUsed,timePasswordChanged
            https://github.com/login,user1,pass1,,https://github.com,{uuid},1700000000000,1700001000000,1700001000000
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.FIREFOX)

        assertEquals(1, result.size)
        assertEquals("github.com", result[0].serviceName)
        assertEquals("https://github.com/login", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertNull(result[0].notes)
    }

    @Test
    fun `parse 1Password CSV maps expected fields`() {
        val csv = """
            Title,Website,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes
            GitHub,https://github.com,user1,pass1,,0,0,,Important
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.ONE_PASSWORD)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("Important", result[0].notes)
    }

    @Test
    fun `parse Apple passwords CSV maps expected fields`() {
        val csv = """
            Title,URL,Username,Password,Notes,OTPAuth
            iCloud,https://icloud.com,user1,pass1,メモ,
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.APPLE_PASSWORDS)

        assertEquals(1, result.size)
        assertEquals("iCloud", result[0].serviceName)
        assertEquals("https://icloud.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("メモ", result[0].notes)
    }

    @Test
    fun `parse KeePass CSV maps expected fields`() {
        val csv = """
            Title,Username,Password,URL,Notes
            GitHub,user1,pass1,https://github.com,メモ
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.KEEPASS)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("メモ", result[0].notes)
    }

    @Test
    fun `parse KeePass CSV supports User Name header variation`() {
        val csv = """
            Group,Title,User Name,Password,URL,Notes
            General,GitHub,user1,pass1,https://github.com,メモ
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.KEEPASS)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("メモ", result[0].notes)
    }

    @Test
    fun `parse Dashlane CSV maps expected fields`() {
        val csv = """
            title,url,username,password,note,otpSecret,category
            GitHub,https://github.com,user1,pass1,メモ,,Work
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.DASHLANE)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("メモ", result[0].notes)
    }

    @Test
    fun `header matching is case insensitive`() {
        val csv = """
            NAME,URL,Username,PASSWORD,Note
            GitHub,https://github.com,user1,pass1,test
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.BRAVE)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("test", result[0].notes)
    }

    @Test
    fun `quoted header with BOM is parsed correctly`() {
        val csv = "\uFEFF\"name\",\"url\",\"username\",\"password\",\"note\"\nGitHub,https://github.com,user1,pass1,memo"

        val result = parser.parse(csv, ImportSource.CHROME)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("memo", result[0].notes)
    }

    @Test
    fun `missing required column throws IllegalArgumentException`() {
        val csv = """
            name,url,username,note
            GitHub,https://github.com,user1,メモ
        """.trimIndent()

        try {
            parser.parse(csv, ImportSource.BRAVE)
            fail("Expected IllegalArgumentException")
        } catch (exception: IllegalArgumentException) {
            assertEquals(CsvImportParser.INVALID_FORMAT_ERROR, exception.message)
        }
    }

    @Test
    fun `quoted field containing comma is parsed correctly`() {
        val csv = """
            name,url,username,password,note
            """ +
            "\"GitHub, Inc.\",https://github.com,user1,pass1,\"メモ,カンマ\""

        val result = parser.parse(csv, ImportSource.BRAVE)

        assertEquals(1, result.size)
        assertEquals("GitHub, Inc.", result[0].serviceName)
        assertEquals("メモ,カンマ", result[0].notes)
    }

    @Test
    fun `empty lines are skipped`() {
        val csv = """
            name,url,username,password,note

            GitHub,https://github.com,user1,pass1,memo

            Twitter,https://twitter.com,user2,pass2,
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.BRAVE)

        assertEquals(2, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("Twitter", result[1].serviceName)
    }

    @Test
    fun `rows with blank password are skipped`() {
        val csv = """
            name,url,username,password,note
            GitHub,https://github.com,user1,,memo
            Twitter,https://twitter.com,user2,pass2,
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.BRAVE)

        assertEquals(1, result.size)
        assertEquals("Twitter", result[0].serviceName)
        assertEquals("user2", result[0].username)
    }

    @Test
    fun `parse SecureVault CSV restores category and credential type`() {
        val csv = """
            serviceName,serviceUrl,username,password,notes,category,credentialType
            GitHub,https://github.com,user1,pass1,メモ,login,PASSWORD
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.SECUREVAULT)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("メモ", result[0].notes)
        assertEquals("login", result[0].category)
        assertEquals(CredentialType.PASSWORD.name, result[0].credentialType)
    }

    @Test
    fun `parse SecureVault CSV keeps ID only rows without password`() {
        val csv = """
            serviceName,serviceUrl,username,password,notes,category,credentialType
            Example,https://example.com,user@example.com,,OTP only,other,ID_ONLY
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.SECUREVAULT)

        assertEquals(1, result.size)
        assertEquals("Example", result[0].serviceName)
        assertEquals("user@example.com", result[0].username)
        assertNull(result[0].password)
        assertEquals(CredentialType.ID_ONLY.name, result[0].credentialType)
    }

    @Test
    fun `parse SecureVault CSV restores card rows`() {
        val csv = """
            serviceName,serviceUrl,username,password,notes,category,credentialType,cardholderName,cardNumber,expirationMonth,expirationYear,securityCode
            Visa,https://cards.example.com,1111,,Business card,finance,CARD,TARO YAMADA,4111111111111111,12,2028,123
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.SECUREVAULT)

        assertEquals(1, result.size)
        assertEquals("Visa", result[0].serviceName)
        assertEquals(CredentialType.CARD.name, result[0].credentialType)
        assertNull(result[0].password)
        assertEquals("1111", result[0].username)
        assertEquals("TARO YAMADA", result[0].cardData?.cardholderName)
        assertEquals("4111111111111111", result[0].cardData?.cardNumber)
        assertEquals(12, result[0].cardData?.expirationMonth)
        assertEquals(2028, result[0].cardData?.expirationYear)
        assertEquals("123", result[0].cardData?.securityCode)
    }

    @Test
    fun `parse SecureVault CSV skips passkey rows`() {
        val csv = """
            serviceName,serviceUrl,username,password,notes,category,credentialType
            Example,https://example.com,user@example.com,,passkey,other,PASSKEY
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.SECUREVAULT)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse Chrome CSV with standard headers`() {
        val csv = "name,url,username,password,note\nGitHub,https://github.com,user1,pass123,my note"

        val result = parser.parse(csv, ImportSource.CHROME)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass123", result[0].password)
        assertEquals("my note", result[0].notes)
    }

    @Test
    fun `parse Chrome CSV multiple rows`() {
        val csv = """name,url,username,password,note
GitHub,https://github.com,user1,pass1,
Google,https://google.com,user2,pass2,memo
Amazon,https://amazon.co.jp,user3,pass3,
""".trimIndent()

        val result = parser.parse(csv, ImportSource.CHROME)

        assertEquals(3, result.size)
    }

    @Test
    fun `parse CSV with UTF-8 BOM`() {
        val csv = "\uFEFFname,url,username,password,note\nTest,https://test.com,u,p,n"

        val result = parser.parse(csv, ImportSource.CHROME)

        assertEquals(1, result.size)
        assertEquals("Test", result[0].serviceName)
    }

    @Test
    fun `parse CSV with escaped quotes`() {
        val csv = "name,url,username,password,note\n\"He said \"\"hello\"\"\",https://a.com,u,p,"

        val result = parser.parse(csv, ImportSource.CHROME)

        assertEquals(1, result.size)
        assertEquals("He said \"hello\"", result[0].serviceName)
    }

    @Test
    fun `return empty list for empty CSV`() {
        val result = parser.parse("", ImportSource.CHROME)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `return empty list for header only CSV`() {
        val result = parser.parse("name,url,username,password,note\n", ImportSource.CHROME)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse Edge CSV same format as Chrome`() {
        val csv = "name,url,username,password,note\nEdgeSvc,https://edge.com,euser,epass,enote"

        val result = parser.parse(csv, ImportSource.EDGE)

        assertEquals(1, result.size)
        assertEquals("EdgeSvc", result[0].serviceName)
    }

    @Test
    fun `parse with mixed case headers`() {
        val csv = "Name,Url,UserName,Password,Note\nSvc,https://a.com,u,p,n"

        val result = parser.parse(csv, ImportSource.CHROME)

        assertEquals(1, result.size)
        assertEquals("Svc", result[0].serviceName)
    }

    @Test
    fun `parse 100 rows`() {
        val header = "name,url,username,password,note"
        val rows = (1..100).joinToString("\n") { index ->
            "svc$index,https://svc$index.com,user$index,pass$index,note$index"
        }
        val csv = "$header\n$rows"

        val result = parser.parse(csv, ImportSource.CHROME)

        assertEquals(100, result.size)
    }

    @Test
    fun `fallback to URL host when name is empty`() {
        val csv = "name,url,username,password,note\n,https://example.com/login,u,p,"

        val result = parser.parse(csv, ImportSource.CHROME)

        assertEquals(1, result.size)
        assertEquals("example.com", result[0].serviceName)
    }

    @Test
    fun `fallback to unknown when both name and url are empty`() {
        val csv = "name,url,username,password,note\n,,u,p,"

        val result = parser.parse(csv, ImportSource.CHROME)

        assertEquals(1, result.size)
        assertEquals("unknown", result[0].serviceName)
    }

    @Test
    fun `parseCsvLine handles simple line`() {
        val fields = parser.parseCsvLine("a,b,c")
        assertEquals(listOf("a", "b", "c"), fields)
    }

    @Test
    fun `parseCsvLine handles quoted fields`() {
        val fields = parser.parseCsvLine("\"hello, world\",b,\"c\"\"d\"")

        assertEquals("hello, world", fields[0])
        assertEquals("b", fields[1])
        assertEquals("c\"d", fields[2])
    }

    @Test
    fun `parseCsvLine handles empty fields`() {
        val fields = parser.parseCsvLine(",,")

        assertEquals(3, fields.size)
        assertTrue(fields.all { it.isEmpty() })
    }
}
