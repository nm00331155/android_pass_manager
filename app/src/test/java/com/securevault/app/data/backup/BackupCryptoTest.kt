package com.securevault.app.data.backup

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackupCrypto] のユニットテスト。
 */
class BackupCryptoTest {

    /**
     * 正しいパスワードで暗号化・復号が往復できることを検証する。
     */
    @Test
    fun `encrypt and decrypt roundtrip succeeds with correct password`() {
        val password = "testPassword123!"
        val salt = BackupCrypto.generateSalt()
        val key = BackupCrypto.deriveKey(password, salt)

        val plainText = "Hello, SecureVault! これはテストデータです。"
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)

        val (cipherBytes, iv) = BackupCrypto.encrypt(plainBytes, key)
        val decryptedBytes = BackupCrypto.decrypt(cipherBytes, iv, key)

        assertArrayEquals(plainBytes, decryptedBytes)
        assertTrue(String(decryptedBytes, Charsets.UTF_8) == plainText)
    }

    /**
     * 異なるパスワードでは復号に失敗することを検証する。
     */
    @Test(expected = AEADBadTagException::class)
    fun `decrypt fails with wrong password`() {
        val correctPassword = "correctPassword"
        val wrongPassword = "wrongPassword"
        val salt = BackupCrypto.generateSalt()

        val correctKey = BackupCrypto.deriveKey(correctPassword, salt)
        val wrongKey = BackupCrypto.deriveKey(wrongPassword, salt)

        val plainBytes = "Secret data".toByteArray(Charsets.UTF_8)
        val (cipherBytes, iv) = BackupCrypto.encrypt(plainBytes, correctKey)

        BackupCrypto.decrypt(cipherBytes, iv, wrongKey)
    }

    /**
     * 生成されるソルトが十分な長さで、複数回で異なることを検証する。
     */
    @Test
    fun `generateSalt produces different salts each time`() {
        val salt1 = BackupCrypto.generateSalt()
        val salt2 = BackupCrypto.generateSalt()
        val salt3 = BackupCrypto.generateSalt()

        assertTrue(salt1.size == 32)
        assertTrue(salt2.size == 32)
        assertTrue(salt3.size == 32)
        assertFalse(salt1.contentEquals(salt2))
        assertFalse(salt2.contentEquals(salt3))
        assertFalse(salt1.contentEquals(salt3))
    }

    /**
     * 空データでも暗号化・復号が可能であることを検証する。
     */
    @Test
    fun `encrypt and decrypt empty data succeeds`() {
        val password = "password"
        val salt = BackupCrypto.generateSalt()
        val key = BackupCrypto.deriveKey(password, salt)

        val plainBytes = ByteArray(0)
        val (cipherBytes, iv) = BackupCrypto.encrypt(plainBytes, key)
        val decryptedBytes = BackupCrypto.decrypt(cipherBytes, iv, key)

        assertArrayEquals(plainBytes, decryptedBytes)
    }

    /**
     * 大きなデータでも暗号化・復号が可能であることを検証する。
     */
    @Test
    fun `encrypt and decrypt large data succeeds`() {
        val password = "strongP@ssw0rd!"
        val salt = BackupCrypto.generateSalt()
        val key = BackupCrypto.deriveKey(password, salt)

        val plainBytes = ByteArray(1_000_000) { (it % 256).toByte() }
        val (cipherBytes, iv) = BackupCrypto.encrypt(plainBytes, key)
        val decryptedBytes = BackupCrypto.decrypt(cipherBytes, iv, key)

        assertArrayEquals(plainBytes, decryptedBytes)
    }

    /**
     * 同一パスワードとソルトから同一キーが導出されることを検証する。
     */
    @Test
    fun `deriveKey produces same key for same password and salt`() {
        val password = "deterministic"
        val salt = BackupCrypto.generateSalt()

        val key1 = BackupCrypto.deriveKey(password, salt)
        val key2 = BackupCrypto.deriveKey(password, salt)

        assertArrayEquals(key1.encoded, key2.encoded)
    }

    /**
     * 異なるソルトから異なる鍵が導出されることを検証する。
     */
    @Test
    fun `different salts produce different keys`() {
        val key1 = BackupCrypto.deriveKey("same-password", BackupCrypto.generateSalt())
        val key2 = BackupCrypto.deriveKey("same-password", BackupCrypto.generateSalt())

        assertFalse(key1.encoded.contentEquals(key2.encoded))
    }

    /**
     * 同じ平文でも毎回異なるIVで暗号化されることを検証する。
     */
    @Test
    fun `encryption uses unique IV each time`() {
        val key = BackupCrypto.deriveKey("pwd", BackupCrypto.generateSalt())
        val plainBytes = "same data".toByteArray(Charsets.UTF_8)

        val (cipher1, iv1) = BackupCrypto.encrypt(plainBytes, key)
        val (cipher2, iv2) = BackupCrypto.encrypt(plainBytes, key)

        assertFalse(iv1.contentEquals(iv2))
        assertFalse(cipher1.contentEquals(cipher2))
    }
}
