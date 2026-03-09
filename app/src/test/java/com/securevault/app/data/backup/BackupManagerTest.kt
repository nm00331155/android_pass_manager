package com.securevault.app.data.backup

import com.securevault.app.data.repository.model.CardData
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.data.repository.model.CredentialType
import com.securevault.app.data.repository.model.PasskeyData
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackupCredential] 変換と JSON 往復のユニットテスト。
 */
class BackupManagerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * [Credential] -> [BackupCredential] 変換の保持性を検証する。
     */
    @Test
    fun `Credential to BackupCredential conversion preserves all fields`() {
        val credential = Credential(
            id = 42,
            serviceName = "GitHub",
            serviceUrl = "https://github.com",
            packageName = "com.github.android",
            username = "testuser",
            password = "secretPass123",
            notes = "メモです",
            category = "development",
            isFavorite = true,
            createdAt = 1700000000000L,
            updatedAt = 1700001000000L
        )

        val backup = credential.toBackup()
        val serializedBackup = json.encodeToString(backup)

        assertEquals("GitHub", backup.serviceName)
        assertEquals("https://github.com", backup.serviceUrl)
        assertEquals("testuser", backup.username)
        assertEquals("secretPass123", backup.password)
        assertEquals("メモです", backup.notes)
        assertEquals("development", backup.category)
        assertTrue(backup.isFavorite)
        assertEquals(1700000000000L, backup.createdAt)
        assertEquals(1700001000000L, backup.updatedAt)
        assertEquals(CredentialType.PASSWORD.name, backup.credentialType)
        assertFalse(serializedBackup.contains("\"id\""))
        assertFalse(serializedBackup.contains("packageName"))
    }

    /**
     * [BackupCredential] -> [Credential] 変換で id が 0 になることを検証する。
     */
    @Test
    fun `BackupCredential to Credential conversion sets id to 0`() {
        val backup = BackupCredential(
            serviceName = "Twitter",
            serviceUrl = "https://twitter.com",
            username = "user1",
            password = "pass1",
            notes = null,
            category = "social",
            isFavorite = false,
            createdAt = 1600000000000L,
            updatedAt = 1600001000000L
        )

        val credential = backup.toCredential()

        assertEquals(0, credential.id)
        assertEquals("Twitter", credential.serviceName)
        assertEquals("https://twitter.com", credential.serviceUrl)
        assertEquals("user1", credential.username)
        assertEquals("pass1", credential.password)
        assertNull(credential.notes)
        assertEquals("social", credential.category)
        assertFalse(credential.isFavorite)
        assertEquals(1600000000000L, credential.createdAt)
        assertEquals(1600001000000L, credential.updatedAt)
        assertEquals(CredentialType.PASSWORD, credential.credentialType)
        assertNull(credential.packageName)
    }

    /**
     * 変換往復で id 以外が保持されることを検証する。
     */
    @Test
    fun `roundtrip conversion preserves data except id`() {
        val original = Credential(
            id = 99,
            serviceName = "Amazon",
            serviceUrl = "https://amazon.co.jp",
            packageName = "jp.co.amazon.shopping",
            username = "buyer",
            password = "sh0pp1ng!",
            notes = "プライム会員",
            category = "shopping",
            isFavorite = true,
            createdAt = 1650000000000L,
            updatedAt = 1650001000000L
        )

        val roundTripped = original.toBackup().toCredential()

        assertEquals(0, roundTripped.id)
        assertEquals(original.serviceName, roundTripped.serviceName)
        assertEquals(original.serviceUrl, roundTripped.serviceUrl)
        assertEquals(original.username, roundTripped.username)
        assertEquals(original.password, roundTripped.password)
        assertEquals(original.notes, roundTripped.notes)
        assertEquals(original.category, roundTripped.category)
        assertEquals(original.isFavorite, roundTripped.isFavorite)
        assertEquals(original.createdAt, roundTripped.createdAt)
        assertEquals(original.updatedAt, roundTripped.updatedAt)
        assertEquals(CredentialType.PASSWORD, roundTripped.credentialType)
        assertNull(roundTripped.packageName)
    }

    @Test
    fun `ID only backup roundtrip preserves null password`() {
        val original = Credential(
            serviceName = "Example",
            serviceUrl = "https://example.com",
            username = "user@example.com",
            password = null,
            notes = "OTP login",
            category = "other",
            credentialType = CredentialType.ID_ONLY
        )

        val roundTripped = original.toBackup().toCredential()

        assertNull(roundTripped.password)
        assertEquals(CredentialType.ID_ONLY, roundTripped.credentialType)
    }

    @Test
    fun `passkey backup roundtrip preserves passkey data`() {
        val passkeyData = PasskeyData(
            credentialId = "credential-id",
            publicKey = "public-key",
            privateKey = "private-key",
            userHandle = "user-handle",
            rpId = "example.com",
            origin = "https://example.com",
            signCount = 3,
            userDisplayName = "Example User"
        )
        val original = Credential(
            serviceName = "Example",
            serviceUrl = "example.com",
            username = "user@example.com",
            passkeyData = passkeyData,
            credentialType = CredentialType.PASSKEY
        )

        val backup = original.toBackup()
        val restored = backup.toCredential()

        assertEquals(CredentialType.PASSKEY.name, backup.credentialType)
        assertEquals(passkeyData, restored.passkeyData)
        assertEquals(CredentialType.PASSKEY, restored.credentialType)
        assertNull(restored.password)
    }

    @Test
    fun `card backup roundtrip preserves card data`() {
        val cardData = CardData(
            cardholderName = "TARO YAMADA",
            cardNumber = "4111111111111111",
            expirationMonth = 12,
            expirationYear = 2028,
            securityCode = "123"
        )
        val original = Credential(
            serviceName = "Visa",
            serviceUrl = "https://cards.example.com",
            username = "1111",
            notes = "Business card",
            category = "finance",
            cardData = cardData,
            credentialType = CredentialType.CARD
        )

        val backup = original.toBackup()
        val restored = backup.toCredential()

        assertEquals(CredentialType.CARD.name, backup.credentialType)
        assertEquals(cardData, restored.cardData)
        assertEquals(CredentialType.CARD, restored.credentialType)
        assertNull(restored.password)
    }

    /**
     * BackupCredential リストの JSON 往復を検証する。
     */
    @Test
    fun `JSON serialization and deserialization roundtrip succeeds`() {
        val backupList = listOf(
            BackupCredential(
                serviceName = "Google",
                serviceUrl = "https://google.com",
                username = "user@gmail.com",
                password = "g00gleP@ss",
                notes = "メインアカウント",
                category = "email",
                isFavorite = true,
                createdAt = 1700000000000L,
                updatedAt = 1700001000000L
            ),
            BackupCredential(
                serviceName = "Slack",
                serviceUrl = "https://slack.com",
                username = "worker",
                password = "sl@ckP@ss",
                notes = null,
                category = "work",
                isFavorite = false,
                createdAt = 1700002000000L,
                updatedAt = 1700003000000L
            )
        )

        val jsonString = json.encodeToString(backupList)
        val deserialized: List<BackupCredential> = json.decodeFromString(jsonString)

        assertEquals(backupList.size, deserialized.size)
        for (i in backupList.indices) {
            assertEquals(backupList[i], deserialized[i])
        }
    }

    /**
     * デフォルト値を持つデータの JSON 往復を検証する。
     */
    @Test
    fun `BackupCredential with defaults serializes correctly`() {
        val backup = BackupCredential(
            serviceName = "Test",
            username = "user",
            password = "pass"
        )

        val jsonString = json.encodeToString(backup)
        val deserialized: BackupCredential = json.decodeFromString(jsonString)

        assertEquals("Test", deserialized.serviceName)
        assertNull(deserialized.serviceUrl)
        assertEquals("user", deserialized.username)
        assertEquals("pass", deserialized.password)
        assertNull(deserialized.notes)
        assertEquals("other", deserialized.category)
        assertFalse(deserialized.isFavorite)
        assertEquals(0L, deserialized.createdAt)
        assertEquals(0L, deserialized.updatedAt)
    }

    /**
     * 未知フィールドを含む JSON でも復元できることを検証する。
     */
    @Test
    fun `deserialization ignores unknown fields`() {
        val jsonString = """
            {
                "serviceName": "Test",
                "username": "user",
                "password": "pass",
                "unknownField": "value",
                "anotherUnknown": 123
            }
        """.trimIndent()

        val deserialized: BackupCredential = json.decodeFromString(jsonString)

        assertEquals("Test", deserialized.serviceName)
        assertEquals("user", deserialized.username)
        assertEquals("pass", deserialized.password)
    }

    /**
     * 特殊文字を含む値でも JSON 往復できることを検証する。
     */
    @Test
    fun `JSON roundtrip preserves special characters`() {
        val original = BackupCredential(
            serviceName = "Svc with \"quotes\" & <angles>",
            serviceUrl = "https://svc.example.com/path?x=1&y=2",
            username = "user@email.com",
            password = "p@ss\$w0rd!#%",
            notes = "メモ（日本語）"
        )

        val jsonString = json.encodeToString(original)
        val restored: BackupCredential = json.decodeFromString(jsonString)

        assertEquals(original, restored)
    }
}
