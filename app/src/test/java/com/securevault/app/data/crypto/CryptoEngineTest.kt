package com.securevault.app.data.crypto

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CryptoEngineTest {

    @Test
    fun encryptDecrypt_roundTrip_restoresOriginalText() {
        val keyGenerator = KeyGenerator.getInstance("AES").apply { init(256) }
        val secretKey = keyGenerator.generateKey()

        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val plainText = "securevault-test-plain-text"
        val encrypted = CryptoEngine.encryptStatic(plainText, encryptCipher)

        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(128, encrypted.iv)
        )

        val decrypted = CryptoEngine.decryptStatic(encrypted, decryptCipher)

        assertEquals(plainText, decrypted)

        val encoded = CryptoEngine.toBase64(encrypted.cipherText)
        val decoded = CryptoEngine.fromBase64(encoded)
        assertArrayEquals(encrypted.cipherText, decoded)
    }
}
