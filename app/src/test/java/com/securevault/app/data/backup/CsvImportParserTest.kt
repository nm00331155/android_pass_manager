package com.securevault.app.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            https://accounts.google.com/signin,user1,pass1,,https://accounts.google.com,{uuid},1700000000000,1700001000000,1700001000000
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.FIREFOX)

        assertEquals(1, result.size)
        assertEquals("accounts.google.com", result[0].serviceName)
        assertEquals("https://accounts.google.com/signin", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertNull(result[0].notes)
    }

    @Test
    fun `parse 1Password CSV maps expected fields`() {
        val csv = """
            Title,Website,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes
            GitHub,https://github.com,user1,pass1,,0,0,,メモ
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.ONE_PASSWORD)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
        assertEquals("https://github.com", result[0].serviceUrl)
        assertEquals("user1", result[0].username)
        assertEquals("pass1", result[0].password)
        assertEquals("メモ", result[0].notes)
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
            NAME,URL,USERNAME,PASSWORD,NOTE
            GitHub,https://github.com,user1,pass1,メモ
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.BRAVE)

        assertEquals(1, result.size)
        assertEquals("GitHub", result[0].serviceName)
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

            GitHub,https://github.com,user1,pass1,メモ1

            GitLab,https://gitlab.com,user2,pass2,メモ2
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.BRAVE)

        assertEquals(2, result.size)
    }

    @Test
    fun `rows with blank password are skipped`() {
        val csv = """
            name,url,username,password,note
            GitHub,https://github.com,user1,,メモ1
            GitLab,https://gitlab.com,user2,pass2,メモ2
        """.trimIndent()

        val result = parser.parse(csv, ImportSource.BRAVE)

        assertEquals(1, result.size)
        assertEquals("GitLab", result[0].serviceName)
        assertEquals("user2", result[0].username)
    }
}
