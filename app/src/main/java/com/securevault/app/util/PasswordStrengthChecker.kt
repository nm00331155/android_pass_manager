package com.securevault.app.util

/**
 * パスワード強度の分類。
 */
enum class PasswordStrength(val label: String, val score: Int) {
    VERY_WEAK("非常に弱い", 0),
    WEAK("弱い", 25),
    MEDIUM("普通", 50),
    STRONG("強い", 75),
    VERY_STRONG("非常に強い", 100)
}

/**
 * パスワード文字列を評価して強度を返す。
 */
object PasswordStrengthChecker {

    private val blacklist = setOf(
        "password",
        "123456",
        "qwerty",
        "letmein",
        "admin",
        "iloveyou"
    )

    /**
     * 強度を評価する。
     */
    fun check(password: String): PasswordStrength {
        if (password.isBlank()) {
            return PasswordStrength.VERY_WEAK
        }

        val normalized = password.lowercase()
        if (blacklist.any { normalized.contains(it) }) {
            return PasswordStrength.VERY_WEAK
        }

        val lengthScore = when {
            password.length < 8 -> 0
            password.length <= 11 -> 1
            password.length <= 15 -> 2
            else -> 3
        }

        var charsetScore = 0
        if (password.any { it.isLowerCase() }) charsetScore += 1
        if (password.any { it.isUpperCase() }) charsetScore += 1
        if (password.any { it.isDigit() }) charsetScore += 1
        if (password.any { !it.isLetterOrDigit() }) charsetScore += 1

        val repetitionPenalty = if (TRIPLE_REPEAT_REGEX.containsMatchIn(password)) -1 else 0
        val total = lengthScore + charsetScore + repetitionPenalty

        return when {
            total <= 1 -> PasswordStrength.VERY_WEAK
            total <= 3 -> PasswordStrength.WEAK
            total <= 5 -> PasswordStrength.MEDIUM
            total == 6 -> PasswordStrength.STRONG
            else -> PasswordStrength.VERY_STRONG
        }
    }

    private val TRIPLE_REPEAT_REGEX = Regex("(.)\\1\\1")
}
