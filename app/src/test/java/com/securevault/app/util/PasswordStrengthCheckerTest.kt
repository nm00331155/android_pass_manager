package com.securevault.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PasswordStrengthChecker] のユニットテスト。
 */
class PasswordStrengthCheckerTest {

    /**
     * 空文字列が VERY_WEAK と判定されることを検証する。
     */
    @Test
    fun `empty password returns VERY_WEAK`() {
        val result = PasswordStrengthChecker.check("")
        assertEquals(PasswordStrength.VERY_WEAK, result)
    }

    /**
     * 短い数字のみパスワードが VERY_WEAK と判定されることを検証する。
     */
    @Test
    fun `short numeric password returns VERY_WEAK`() {
        val result = PasswordStrengthChecker.check("1234")
        assertEquals(PasswordStrength.VERY_WEAK, result)
    }

    /**
     * ブラックリスト単語を含むパスワードが VERY_WEAK と判定されることを検証する。
     */
    @Test
    fun `blacklisted word password returns VERY_WEAK`() {
        val result = PasswordStrengthChecker.check("password")
        assertEquals(PasswordStrength.VERY_WEAK, result)
    }

    /**
     * 大文字・小文字・数字を含むパスワードが MEDIUM と判定されることを検証する。
     */
    @Test
    fun `mixed case with number returns MEDIUM`() {
        val result = PasswordStrengthChecker.check("Secure123")
        assertEquals(PasswordStrength.MEDIUM, result)
    }

    /**
     * 複雑なパスワードが STRONG 以上であることを検証する。
     */
    @Test
    fun `complex password returns STRONG or VERY_STRONG`() {
        val result = PasswordStrengthChecker.check("P@ssw0rd!2024")
        assertTrue(
            "Expected STRONG or VERY_STRONG but was $result",
            result == PasswordStrength.STRONG || result == PasswordStrength.VERY_STRONG
        )
    }

    /**
     * 十分に長いランダム文字列が VERY_STRONG と判定されることを検証する。
     */
    @Test
    fun `long random password returns VERY_STRONG`() {
        val result = PasswordStrengthChecker.check("aB3#xY9!kL2@mN5&pQ8*")
        assertEquals(PasswordStrength.VERY_STRONG, result)
    }

    /**
     * 数字のみの長いパスワードが STRONG 未満であることを検証する。
     */
    @Test
    fun `long numeric only password is not STRONG`() {
        val result = PasswordStrengthChecker.check("12345678901234567890")
        assertTrue(
            "Expected not STRONG for numeric-only password but was $result",
            result == PasswordStrength.WEAK ||
                result == PasswordStrength.MEDIUM ||
                result == PasswordStrength.VERY_WEAK
        )
    }
}
