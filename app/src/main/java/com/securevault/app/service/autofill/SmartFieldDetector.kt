package com.securevault.app.service.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import com.securevault.app.util.AppLogger
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects username, password, and OTP targets from an Autofill assist structure.
 */
@Singleton
class SmartFieldDetector @Inject constructor(
    private val logger: AppLogger
) {

    /**
     * Detects candidate fields for username, password, and OTP.
     */
    fun detect(
        structure: AssistStructure,
        focusedId: AutofillId? = null
    ): DetectedFields {
        var usernameId: AutofillId? = null
        var passwordId: AutofillId? = null
        var otpId: AutofillId? = null
        var webDomain: String? = null
        var focusedNodeInfo: FocusedNodeInfo? = null
        val pageTitles = linkedSetOf<String>()
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

            logger.d(
                TAG,
                "detect node: hints=$hints, id=$idEntry, hint=${node.hint.orEmpty()}, inputType=${node.inputType}"
            )

            if (focusedNodeInfo == null && node.autofillId == focusedId) {
                focusedNodeInfo = createFocusedNodeInfo(
                    node = node,
                    hints = hints,
                    idEntry = idEntry,
                    hintText = hintText,
                    nodeText = nodeText,
                    htmlTokens = htmlTokens
                )
                logger.d(
                    TAG,
                    "detect: focused node id=$focusedId, supportsTrigger=${focusedNodeInfo?.supportsAutofillTrigger}, textLike=${focusedNodeInfo?.isTextInputLike}, hints=$hints, htmlTokens=$htmlTokens"
                )
            }

            if (usernameId == null && isUsernameField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                usernameId = node.autofillId
                logger.d(
                    TAG,
                    "detect: FOUND username field id=${node.autofillId}, idEntry=$idEntry, hint=${node.hint.orEmpty()}"
                )
            }

            if (passwordId == null && isPasswordField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                passwordId = node.autofillId
                logger.d(
                    TAG,
                    "detect: FOUND password field id=${node.autofillId}, idEntry=$idEntry, hint=${node.hint.orEmpty()}"
                )
            }

            if (otpId == null && isOtpField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                otpId = node.autofillId
                logger.d(
                    TAG,
                    "detect: FOUND OTP field id=${node.autofillId}, idEntry=$idEntry, hint=${node.hint.orEmpty()}"
                )
            }

            for (index in 0 until node.childCount) {
                traverse(node.getChildAt(index))
            }
        }

        for (windowIndex in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(windowIndex)
            windowNode.title?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { pageTitles += it }
            traverse(windowNode.rootViewNode)
        }

        logger.d(
            TAG,
            "detect result: focused=$focusedId, username=$usernameId, password=$passwordId, otp=$otpId, domain=$webDomain, pageTitles=${pageTitles.joinToString(limit = 2)}, totalIds=${allAutofillIds.size}"
        )

        return DetectedFields(
            focusedId = focusedId,
            focusedNodeInfo = focusedNodeInfo,
            usernameId = usernameId,
            passwordId = passwordId,
            otpId = otpId,
            webDomain = webDomain,
            pageTitle = pageTitles.firstOrNull(),
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
        val textInputLike = isTextInputLike(node, hints, htmlTokens)
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

        if (!textInputLike) {
            return false
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
        val textInputLike = isTextInputLike(node, hints, htmlTokens)
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

        if (!textInputLike) {
            return false
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
        val textInputLike = isTextInputLike(node, hints, htmlTokens)
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

        if (!textInputLike) {
            return false
        }

        if (hintMatched || keywordMatched) {
            return true
        }

        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val maxLength = node.maxTextLength
        return inputClass == InputType.TYPE_CLASS_NUMBER && maxLength in OTP_MIN_LENGTH..OTP_MAX_LENGTH
    }

    private fun isTextInputLike(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        htmlTokens: List<String>
    ): Boolean {
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        if (inputClass == InputType.TYPE_CLASS_TEXT || inputClass == InputType.TYPE_CLASS_NUMBER) {
            return true
        }

        if (hints.isNotEmpty()) {
            return true
        }

        val htmlJoined = htmlTokens.joinToString(separator = " ")
        val nodeJoined = buildString {
            append(node.idEntry?.lowercase(Locale.ROOT).orEmpty())
            append(' ')
            append(node.hint?.toString()?.lowercase(Locale.ROOT).orEmpty())
            append(' ')
            append(node.text?.toString()?.lowercase(Locale.ROOT).orEmpty())
        }
        if (
            htmlJoined.contains("radio") ||
            htmlJoined.contains("checkbox") ||
            htmlJoined.contains("button") ||
            htmlJoined.contains("submit") ||
            htmlJoined.contains("select") ||
            nodeJoined.contains("radio") ||
            nodeJoined.contains("checkbox") ||
            nodeJoined.contains("button") ||
            nodeJoined.contains("submit") ||
            nodeJoined.contains("toggle")
        ) {
            return false
        }

        return htmlJoined.contains("input") ||
            htmlJoined.contains("textarea") ||
            htmlJoined.contains("text") ||
            htmlJoined.contains("email") ||
            htmlJoined.contains("password") ||
            htmlJoined.contains("username") ||
            htmlJoined.contains("tel") ||
            htmlJoined.contains("number") ||
            htmlJoined.contains("search")
    }

    private fun createFocusedNodeInfo(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): FocusedNodeInfo {
        val textInputLike = isTextInputLike(node, hints, htmlTokens)
        val usernameCandidate = isUsernameField(node, hints, idEntry, hintText, nodeText, htmlTokens)
        val passwordCandidate = isPasswordField(node, hints, idEntry, hintText, nodeText, htmlTokens)
        val otpCandidate = isOtpField(node, hints, idEntry, hintText, nodeText, htmlTokens)

        return FocusedNodeInfo(
            inputType = node.inputType,
            hints = hints,
            idEntry = idEntry,
            hintText = hintText,
            htmlTokens = htmlTokens,
            isTextInputLike = textInputLike,
            supportsAutofillTrigger = textInputLike || usernameCandidate || passwordCandidate || otpCandidate
        )
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
        val focusedId: AutofillId?,
        val focusedNodeInfo: FocusedNodeInfo?,
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val otpId: AutofillId?,
        val webDomain: String?,
        val pageTitle: String?,
        val allAutofillIds: List<AutofillId>
    )

    data class FocusedNodeInfo(
        val inputType: Int,
        val hints: List<String>,
        val idEntry: String,
        val hintText: String,
        val htmlTokens: List<String>,
        val isTextInputLike: Boolean,
        val supportsAutofillTrigger: Boolean
    )

    private companion object {
        const val TAG = "SmartFieldDetector"
        const val OTP_MIN_LENGTH = 4
        const val OTP_MAX_LENGTH = 8

        val USERNAME_KEYWORDS = listOf(
            "user",
            "username",
            "login",
            "mail",
            "email",
            "account",
            "userid",
            "loginid",
            "memberid",
            "customerid",
            "ユーザー",
            "ユーザー名",
            "ログイン",
            "ログインid",
            "メール",
            "メールアドレス",
            "アカウント",
            "会員番号",
            "会員id"
        )

        val PASSWORD_KEYWORDS = listOf(
            "pass",
            "password",
            "pwd",
            "pin",
            "secret",
            "パスワード",
            "暗証番号",
            "パスコード",
            "認証情報"
        )

        val OTP_HINT_TOKENS = listOf(
            "smsotpcode",
            "otp",
            "verification",
            "code",
            "認証コード",
            "確認コード",
            "ワンタイム"
        )

        val OTP_KEYWORDS = listOf(
            "otp",
            "code",
            "token",
            "verify",
            "verification",
            "認証コード",
            "確認コード",
            "ワンタイム",
            "二段階",
            "確認番号"
        )
    }
}
