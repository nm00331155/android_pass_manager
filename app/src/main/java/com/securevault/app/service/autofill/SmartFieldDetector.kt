package com.securevault.app.service.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects username, password, and OTP targets from an Autofill assist structure.
 */
@Singleton
class SmartFieldDetector @Inject constructor() {

    /**
     * Detects candidate fields for username, password, and OTP.
     */
    fun detect(structure: AssistStructure): DetectedFields {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var otpId: AutofillId? = null
        var webDomain: String? = null
        val allAutofillIds = linkedSetOf<AutofillId>()

        fun traverse(node: AssistStructure.ViewNode) {
            node.autofillId?.let { allAutofillIds.add(it) }

            if (webDomain.isNullOrBlank()) {
                val domain = node.webDomain?.toString()?.trim()
                if (!domain.isNullOrEmpty()) {
                    webDomain = domain
                }
            }

            val hints = node.autofillHints
                ?.map { it.lowercase(Locale.ROOT) }
                .orEmpty()
            val idEntry = node.idEntry?.lowercase(Locale.ROOT).orEmpty()
            val hintText = node.hint?.lowercase(Locale.ROOT).orEmpty()
            val nodeText = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val htmlTokens = readHtmlTokens(node)

            if (usernameId == null && isUsernameField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                usernameId = node.autofillId
            }

            if (passwordId == null && isPasswordField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                passwordId = node.autofillId
            }

            if (otpId == null && isOtpField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                otpId = node.autofillId
            }

            for (index in 0 until node.childCount) {
                traverse(node.getChildAt(index))
            }
        }

        for (windowIndex in 0 until structure.windowNodeCount) {
            traverse(structure.getWindowNodeAt(windowIndex).rootViewNode)
        }

        return DetectedFields(
            usernameId = usernameId,
            passwordId = passwordId,
            otpId = otpId,
            webDomain = webDomain,
            allAutofillIds = allAutofillIds.toList()
        )
    }

    private fun isUsernameField(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): Boolean {
        val allTokens = buildList {
            addAll(hints)
            add(idEntry)
            add(hintText)
            add(nodeText)
            addAll(htmlTokens)
        }

        val officialHint = hints.any {
            it == View.AUTOFILL_HINT_USERNAME.lowercase(Locale.ROOT) ||
                it == View.AUTOFILL_HINT_EMAIL_ADDRESS.lowercase(Locale.ROOT) ||
                it.contains("email") ||
                it.contains("login")
        }

        if (officialHint) {
            return true
        }

        val keywordMatched = allTokens.any { token ->
            USERNAME_KEYWORDS.any { keyword -> token.contains(keyword) }
        }
        if (keywordMatched) {
            return true
        }

        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    private fun isPasswordField(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): Boolean {
        val allTokens = buildList {
            addAll(hints)
            add(idEntry)
            add(hintText)
            add(nodeText)
            addAll(htmlTokens)
        }

        val officialHint = hints.any {
            it == View.AUTOFILL_HINT_PASSWORD.lowercase(Locale.ROOT) ||
                it.contains("password") ||
                it.contains("pass")
        }

        if (officialHint) {
            return true
        }

        val keywordMatched = allTokens.any { token ->
            PASSWORD_KEYWORDS.any { keyword -> token.contains(keyword) }
        }
        if (keywordMatched) {
            return true
        }

        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun isOtpField(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): Boolean {
        val allTokens = buildList {
            addAll(hints)
            add(idEntry)
            add(hintText)
            add(nodeText)
            addAll(htmlTokens)
        }

        val hintMatched = hints.any {
            OTP_HINT_TOKENS.any { token -> it.contains(token) }
        }

        val keywordMatched = allTokens.any { token ->
            OTP_KEYWORDS.any { keyword -> token.contains(keyword) }
        }

        if (hintMatched || keywordMatched) {
            return true
        }

        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val maxLength = node.maxTextLength
        return inputClass == InputType.TYPE_CLASS_NUMBER && maxLength in OTP_MIN_LENGTH..OTP_MAX_LENGTH
    }

    private fun readHtmlTokens(node: AssistStructure.ViewNode): List<String> {
        val htmlInfo = node.htmlInfo ?: return emptyList()
        val tokens = mutableListOf<String>()

        htmlInfo.tag?.let { tokens += it.lowercase(Locale.ROOT) }

        htmlInfo.attributes?.forEach { pair ->
            pair.first?.let { tokens += it.lowercase(Locale.ROOT) }
            pair.second?.let { tokens += it.lowercase(Locale.ROOT) }
        }

        return tokens
    }

    /**
     * Result of the smart field detection pass.
     */
    data class DetectedFields(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val otpId: AutofillId?,
        val webDomain: String?,
        val allAutofillIds: List<AutofillId>
    )

    private companion object {
        const val OTP_MIN_LENGTH = 4
        const val OTP_MAX_LENGTH = 8

        val USERNAME_KEYWORDS = listOf(
            "user",
            "username",
            "login",
            "mail",
            "email",
            "account",
            "userid"
        )

        val PASSWORD_KEYWORDS = listOf(
            "pass",
            "password",
            "pwd",
            "pin",
            "secret"
        )

        val OTP_HINT_TOKENS = listOf("smsotpcode", "otp", "verification", "code")

        val OTP_KEYWORDS = listOf(
            "otp",
            "code",
            "token",
            "verify",
            "verification"
        )
    }
}
