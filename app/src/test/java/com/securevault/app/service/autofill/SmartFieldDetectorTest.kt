package com.securevault.app.service.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.util.Pair as AndroidPair
import android.view.View
import android.view.ViewStructure
import android.view.autofill.AutofillId
import com.securevault.app.util.AppLogger
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartFieldDetectorTest {

    private val logger = mockk<AppLogger>(relaxed = true)
    private val detector = SmartFieldDetector(logger)

    @Test
    fun `generic id text on non input node is not detected as username`() {
        val focusedId = autofillId("focused-generic")
        val structure = structureOf(
            node(
                autofillId = focusedId,
                idEntry = "customer_id_label",
                text = "Customer ID",
                inputType = 0,
                htmlAttributes = listOf("role" to "heading")
            )
        )

        val detected = detector.detect(structure, focusedId)

        assertNull(detected.usernameId)
        assertFalse(detected.focusedNodeInfo?.supportsAutofillTrigger ?: true)
    }

    @Test
    fun `radio input does not become autofill trigger`() {
        val focusedId = autofillId("focused-radio")
        val structure = structureOf(
            node(
                autofillId = focusedId,
                idEntry = "apply_radio",
                inputType = 0,
                htmlTag = "input",
                htmlAttributes = listOf("type" to "radio", "name" to "application_type")
            )
        )

        val detected = detector.detect(structure, focusedId)

        assertFalse(detected.focusedNodeInfo?.isTextInputLike ?: true)
        assertFalse(detected.focusedNodeInfo?.supportsAutofillTrigger ?: true)
    }

    @Test
    fun `login id text input remains detectable and triggerable`() {
        val focusedId = autofillId("focused-login")
        val passwordId = autofillId("password")
        val structure = structureOf(
            node(
                autofillId = focusedId,
                idEntry = "login_id",
                hint = "Login ID",
                inputType = InputType.TYPE_CLASS_TEXT,
                htmlTag = "input",
                htmlAttributes = listOf("type" to "text", "autocomplete" to "username")
            ),
            node(
                autofillId = passwordId,
                idEntry = "password",
                hint = "Password",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                htmlTag = "input",
                htmlAttributes = listOf("type" to "password")
            )
        )

        val detected = detector.detect(structure, focusedId)

        assertEquals(focusedId, detected.usernameId)
        assertEquals(passwordId, detected.passwordId)
        assertTrue(detected.focusedNodeInfo?.isTextInputLike == true)
        assertTrue(detected.focusedNodeInfo?.supportsAutofillTrigger == true)
    }

    @Test
    fun `member id numeric field is login not otp`() {
        val focusedId = autofillId("member-id")
        val structure = structureOf(
            node(
                autofillId = focusedId,
                idEntry = "member_id",
                hint = "会員番号",
                inputType = InputType.TYPE_CLASS_NUMBER,
                htmlTag = "input",
                htmlAttributes = listOf("type" to "number"),
                maxTextLength = 6
            )
        )

        val detected = detector.detect(structure, focusedId)

        assertEquals(focusedId, detected.usernameId)
        assertNull(detected.otpId)
        assertEquals(SmartFieldDetector.CredentialKind.LOGIN, detected.focusedNodeInfo?.credentialKind)
    }

    @Test
    fun `verification code field remains detectable as otp`() {
        val focusedId = autofillId("otp")
        val structure = structureOf(
            node(
                autofillId = focusedId,
                idEntry = "verification_code",
                hint = "認証コード",
                inputType = InputType.TYPE_CLASS_NUMBER,
                htmlTag = "input",
                htmlAttributes = listOf("type" to "text", "autocomplete" to "one-time-code"),
                maxTextLength = 6
            )
        )

        val detected = detector.detect(structure, focusedId)

        assertEquals(focusedId, detected.otpId)
        assertNull(detected.usernameId)
        assertEquals(SmartFieldDetector.CredentialKind.OTP, detected.focusedNodeInfo?.credentialKind)
    }

    @Test
    fun `credit card fields are detected as card autofill targets`() {
        val cardNumberId = autofillId("card-number")
        val expiryId = autofillId("card-exp")
        val cvcId = autofillId("card-cvc")
        val structure = structureOf(
            node(
                autofillId = cardNumberId,
                idEntry = "card_number",
                hint = "Card number",
                inputType = InputType.TYPE_CLASS_NUMBER,
                htmlTag = "input",
                htmlAttributes = listOf("autocomplete" to "cc-number"),
                autofillHints = arrayOf(View.AUTOFILL_HINT_CREDIT_CARD_NUMBER)
            ),
            node(
                autofillId = expiryId,
                idEntry = "cc_exp",
                hint = "MM/YY",
                inputType = InputType.TYPE_CLASS_NUMBER,
                htmlTag = "input",
                htmlAttributes = listOf("autocomplete" to "cc-exp"),
                autofillHints = arrayOf(View.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE)
            ),
            node(
                autofillId = cvcId,
                idEntry = "security_code",
                hint = "CVC",
                inputType = InputType.TYPE_CLASS_NUMBER,
                htmlTag = "input",
                htmlAttributes = listOf("autocomplete" to "cc-csc"),
                autofillHints = arrayOf(View.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE)
            )
        )

        val detected = detector.detect(structure, cardNumberId)

        assertEquals(cardNumberId, detected.cardNumberId)
        assertEquals(expiryId, detected.cardExpirationDateId)
        assertEquals(cvcId, detected.cardSecurityCodeId)
        assertEquals(SmartFieldDetector.CredentialKind.CARD, detected.focusedNodeInfo?.credentialKind)
        assertTrue(detected.focusedNodeInfo?.supportsAutofillTrigger == true)
    }

    private fun structureOf(vararg rootNodes: AssistStructure.ViewNode): AssistStructure {
        val structure = mockk<AssistStructure>()
        val windows = rootNodes.map { rootNode ->
            mockk<AssistStructure.WindowNode>().also { windowNode ->
                every { windowNode.title } returns null
                every { windowNode.rootViewNode } returns rootNode
            }
        }

        every { structure.windowNodeCount } returns windows.size
        windows.forEachIndexed { index, windowNode ->
            every { structure.getWindowNodeAt(index) } returns windowNode
        }

        return structure
    }

    private fun node(
        autofillId: AutofillId? = null,
        idEntry: String? = null,
        hint: String? = null,
        text: String? = null,
        inputType: Int = 0,
        htmlTag: String? = null,
        htmlAttributes: List<Pair<String, String>> = emptyList(),
        autofillHints: Array<String>? = null,
        children: List<AssistStructure.ViewNode> = emptyList(),
        maxTextLength: Int = -1
    ): AssistStructure.ViewNode {
        val node = mockk<AssistStructure.ViewNode>()
        every { node.autofillId } returns autofillId
        every { node.webDomain } returns null
        every { node.autofillHints } returns autofillHints
        every { node.idEntry } returns idEntry
        every { node.hint } returns hint
        every { node.text } returns text
        every { node.inputType } returns inputType
        every { node.maxTextLength } returns maxTextLength
        every { node.childCount } returns children.size
        children.forEachIndexed { index, child ->
            every { node.getChildAt(index) } returns child
        }

        if (htmlTag == null && htmlAttributes.isEmpty()) {
            every { node.htmlInfo } returns null
        } else {
            val htmlInfo = mockk<ViewStructure.HtmlInfo>()
            every { htmlInfo.tag } returns (htmlTag ?: "")
            every { htmlInfo.attributes } returns htmlAttributes.map { AndroidPair(it.first, it.second) }
            every { node.htmlInfo } returns htmlInfo
        }

        return node
    }

    private fun autofillId(label: String): AutofillId {
        val id = mockk<AutofillId>(relaxed = true)
        every { id.toString() } returns label
        every { id.hashCode() } returns label.hashCode()
        every { id.equals(any()) } answers { firstArg<Any?>() === id }
        return id
    }
}