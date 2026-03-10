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
        var cardholderNameId: AutofillId? = null
        var cardNumberId: AutofillId? = null
        var cardExpirationDateId: AutofillId? = null
        var cardExpirationMonthId: AutofillId? = null
        var cardExpirationYearId: AutofillId? = null
        var cardSecurityCodeId: AutofillId? = null
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

            if (VERBOSE_NODE_LOGGING) {
                logger.d(
                    TAG,
                    "detect node: hints=$hints, id=$idEntry, hint=${node.hint.orEmpty()}, inputType=${node.inputType}"
                )
            }

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

            if (cardholderNameId == null && isCardholderNameField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                cardholderNameId = node.autofillId
                logger.d(
                    TAG,
                    "detect: FOUND cardholder field id=${node.autofillId}, idEntry=$idEntry, hint=${node.hint.orEmpty()}"
                )
            }

            if (cardNumberId == null && isCardNumberField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                cardNumberId = node.autofillId
                logger.d(
                    TAG,
                    "detect: FOUND card number field id=${node.autofillId}, idEntry=$idEntry, hint=${node.hint.orEmpty()}"
                )
            }

            if (cardExpirationMonthId == null && isCardExpirationMonthField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                cardExpirationMonthId = node.autofillId
                logger.d(
                    TAG,
                    "detect: FOUND card exp month field id=${node.autofillId}, idEntry=$idEntry, hint=${node.hint.orEmpty()}"
                )
            }

            if (cardExpirationYearId == null && isCardExpirationYearField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                cardExpirationYearId = node.autofillId
                logger.d(
                    TAG,
                    "detect: FOUND card exp year field id=${node.autofillId}, idEntry=$idEntry, hint=${node.hint.orEmpty()}"
                )
            }

            if (cardExpirationDateId == null && isCardExpirationDateField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                cardExpirationDateId = node.autofillId
                logger.d(
                    TAG,
                    "detect: FOUND card expiration field id=${node.autofillId}, idEntry=$idEntry, hint=${node.hint.orEmpty()}"
                )
            }

            if (cardSecurityCodeId == null && isCardSecurityCodeField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
                cardSecurityCodeId = node.autofillId
                logger.d(
                    TAG,
                    "detect: FOUND card security code field id=${node.autofillId}, idEntry=$idEntry, hint=${node.hint.orEmpty()}"
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
            "detect result: focused=$focusedId, username=$usernameId, password=$passwordId, cardholder=$cardholderNameId, cardNumber=$cardNumberId, expDate=$cardExpirationDateId, expMonth=$cardExpirationMonthId, expYear=$cardExpirationYearId, cvc=$cardSecurityCodeId, otp=$otpId, domain=$webDomain, pageTitles=${pageTitles.joinToString(limit = 2)}, totalIds=${allAutofillIds.size}"
        )

        return DetectedFields(
            focusedId = focusedId,
            focusedNodeInfo = focusedNodeInfo,
            usernameId = usernameId,
            passwordId = passwordId,
            cardholderNameId = cardholderNameId,
            cardNumberId = cardNumberId,
            cardExpirationDateId = cardExpirationDateId,
            cardExpirationMonthId = cardExpirationMonthId,
            cardExpirationYearId = cardExpirationYearId,
            cardSecurityCodeId = cardSecurityCodeId,
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
        if (isCardSecurityCodeField(node, hints, idEntry, hintText, nodeText, htmlTokens)) {
            return false
        }

        if (isUsernameField(node, hints, idEntry, hintText, nodeText, htmlTokens) ||
            isPasswordField(node, hints, idEntry, hintText, nodeText, htmlTokens)
        ) {
            return false
        }

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

        val loginIdentifierMatched = allTokens.any { token ->
            LOGIN_IDENTIFIER_EXCLUSION_KEYWORDS.any { keyword -> token.contains(keyword) }
        }

        if (!textInputLike) {
            return false
        }

        if (hintMatched || keywordMatched) {
            return true
        }

        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val maxLength = node.maxTextLength
        return !loginIdentifierMatched &&
            inputClass == InputType.TYPE_CLASS_NUMBER &&
            maxLength in OTP_MIN_LENGTH..OTP_MAX_LENGTH
    }

    private fun isCardholderNameField(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): Boolean {
        if (!isTextInputLike(node, hints, htmlTokens)) {
            return false
        }

        val allTokens = buildList {
            addAll(hints)
            add(idEntry)
            add(hintText)
            add(nodeText)
            addAll(htmlTokens)
        }

        return allTokens.any { token ->
            CARDHOLDER_NAME_KEYWORDS.any { keyword -> token.contains(keyword) }
        }
    }

    private fun isCardNumberField(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): Boolean {
        val officialHint = hints.any {
            it == View.AUTOFILL_HINT_CREDIT_CARD_NUMBER.lowercase(Locale.ROOT)
        }
        if (officialHint) {
            return true
        }

        if (!isTextInputLike(node, hints, htmlTokens)) {
            return false
        }

        val allTokens = buildList {
            addAll(hints)
            add(idEntry)
            add(hintText)
            add(nodeText)
            addAll(htmlTokens)
        }

        return allTokens.any { token ->
            CARD_NUMBER_KEYWORDS.any { keyword -> token.contains(keyword) }
        }
    }

    private fun isCardExpirationDateField(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): Boolean {
        if (isCardExpirationMonthField(node, hints, idEntry, hintText, nodeText, htmlTokens) ||
            isCardExpirationYearField(node, hints, idEntry, hintText, nodeText, htmlTokens)
        ) {
            return false
        }

        val officialHint = hints.any {
            it == View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE.lowercase(Locale.ROOT)
        }
        if (officialHint) {
            return true
        }

        if (!isTextInputLike(node, hints, htmlTokens)) {
            return false
        }

        val allTokens = buildList {
            addAll(hints)
            add(idEntry)
            add(hintText)
            add(nodeText)
            addAll(htmlTokens)
        }

        return allTokens.any { token ->
            CARD_EXPIRATION_KEYWORDS.any { keyword -> token.contains(keyword) }
        }
    }

    private fun isCardExpirationMonthField(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): Boolean {
        val officialHint = hints.any {
            it == View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_MONTH.lowercase(Locale.ROOT)
        }
        if (officialHint) {
            return true
        }

        if (!isTextInputLike(node, hints, htmlTokens)) {
            return false
        }

        val allTokens = buildList {
            addAll(hints)
            add(idEntry)
            add(hintText)
            add(nodeText)
            addAll(htmlTokens)
        }

        return allTokens.any { token ->
            CARD_EXPIRATION_MONTH_KEYWORDS.any { keyword -> token.contains(keyword) }
        }
    }

    private fun isCardExpirationYearField(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): Boolean {
        val officialHint = hints.any {
            it == View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_YEAR.lowercase(Locale.ROOT)
        }
        if (officialHint) {
            return true
        }

        if (!isTextInputLike(node, hints, htmlTokens)) {
            return false
        }

        val allTokens = buildList {
            addAll(hints)
            add(idEntry)
            add(hintText)
            add(nodeText)
            addAll(htmlTokens)
        }

        return allTokens.any { token ->
            CARD_EXPIRATION_YEAR_KEYWORDS.any { keyword -> token.contains(keyword) }
        }
    }

    private fun isCardSecurityCodeField(
        node: AssistStructure.ViewNode,
        hints: List<String>,
        idEntry: String,
        hintText: String,
        nodeText: String,
        htmlTokens: List<String>
    ): Boolean {
        val officialHint = hints.any {
            it == View.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE.lowercase(Locale.ROOT)
        }
        if (officialHint) {
            return true
        }

        if (!isTextInputLike(node, hints, htmlTokens)) {
            return false
        }

        val allTokens = buildList {
            addAll(hints)
            add(idEntry)
            add(hintText)
            add(nodeText)
            addAll(htmlTokens)
        }

        return allTokens.any { token ->
            CARD_SECURITY_CODE_KEYWORDS.any { keyword -> token.contains(keyword) }
        }
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
        val cardCandidate = isCardholderNameField(node, hints, idEntry, hintText, nodeText, htmlTokens) ||
            isCardNumberField(node, hints, idEntry, hintText, nodeText, htmlTokens) ||
            isCardExpirationDateField(node, hints, idEntry, hintText, nodeText, htmlTokens) ||
            isCardExpirationMonthField(node, hints, idEntry, hintText, nodeText, htmlTokens) ||
            isCardExpirationYearField(node, hints, idEntry, hintText, nodeText, htmlTokens) ||
            isCardSecurityCodeField(node, hints, idEntry, hintText, nodeText, htmlTokens)
        val otpCandidate = isOtpField(node, hints, idEntry, hintText, nodeText, htmlTokens)

        return FocusedNodeInfo(
            inputType = node.inputType,
            hints = hints,
            idEntry = idEntry,
            hintText = hintText,
            htmlTokens = htmlTokens,
            isTextInputLike = textInputLike,
            supportsAutofillTrigger = textInputLike || usernameCandidate || passwordCandidate || cardCandidate || otpCandidate,
            credentialKind = when {
                cardCandidate -> CredentialKind.CARD
                usernameCandidate || passwordCandidate -> CredentialKind.LOGIN
                otpCandidate -> CredentialKind.OTP
                else -> null
            }
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
        val cardholderNameId: AutofillId?,
        val cardNumberId: AutofillId?,
        val cardExpirationDateId: AutofillId?,
        val cardExpirationMonthId: AutofillId?,
        val cardExpirationYearId: AutofillId?,
        val cardSecurityCodeId: AutofillId?,
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
        val supportsAutofillTrigger: Boolean,
        val credentialKind: CredentialKind?
    )

    enum class CredentialKind {
        LOGIN,
        CARD,
        OTP
    }

    private companion object {
        const val TAG = "SmartFieldDetector"
        const val VERBOSE_NODE_LOGGING = false
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
            "one-time-code",
            "onetimecode",
            "verificationcode",
            "authcode",
            "otp",
            "verification",
            "認証コード",
            "確認コード",
            "ワンタイム"
        )

        val OTP_KEYWORDS = listOf(
            "otp",
            "one-time-code",
            "onetimecode",
            "token",
            "authcode",
            "verify",
            "verification",
            "verificationcode",
            "認証コード",
            "確認コード",
            "ワンタイム",
            "二段階",
            "確認番号"
        )

        val LOGIN_IDENTIFIER_EXCLUSION_KEYWORDS = listOf(
            "user",
            "username",
            "login",
            "mail",
            "email",
            "account",
            "member",
            "customer",
            "userid",
            "loginid",
            "memberid",
            "customerid",
            "ユーザー",
            "ログイン",
            "メール",
            "アカウント",
            "会員"
        )

        val CARDHOLDER_NAME_KEYWORDS = listOf(
            "cc-name",
            "cardholder",
            "cardholdername",
            "cardholder_name",
            "card holder",
            "nameoncard",
            "cardname",
            "holdername",
            "名義",
            "カード名義"
        )

        val CARD_NUMBER_KEYWORDS = listOf(
            "cc-number",
            "cardnumber",
            "card_number",
            "cardno",
            "card_no",
            "creditcard",
            "credit-card",
            "カード番号"
        )

        val CARD_EXPIRATION_KEYWORDS = listOf(
            "cc-exp",
            "expiry",
            "expiration",
            "validthru",
            "valid thru",
            "有効期限"
        )

        val CARD_EXPIRATION_MONTH_KEYWORDS = listOf(
            "cc-exp-month",
            "expmonth",
            "expirymonth",
            "expirationmonth"
        )

        val CARD_EXPIRATION_YEAR_KEYWORDS = listOf(
            "cc-exp-year",
            "expyear",
            "expiryyear",
            "expirationyear"
        )

        val CARD_SECURITY_CODE_KEYWORDS = listOf(
            "cvc",
            "cvv",
            "csc",
            "securitycode",
            "security code",
            "cardcode",
            "カードコード",
            "セキュリティコード"
        )
    }
}
