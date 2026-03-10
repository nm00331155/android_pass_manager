package com.securevault.app.service.credential

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class PasskeyWebAuthnHelperTest {

    @Test
    fun `parseCreateRequestDisplay tolerates missing rp id`() {
        val requestJson = """
            {
              "challenge": "Y2hhbGxlbmdlLTEyMw",
              "rp": {
                "name": "amazon.co.jp"
              },
              "user": {
                "id": "dXNlci0xMjM",
                "displayName": "Example User"
              }
            }
        """.trimIndent()

        val result = PasskeyWebAuthnHelper.parseCreateRequestDisplay(requestJson)

        assertEquals("amazon.co.jp", result.rpLabel)
        assertEquals("Example User", result.userName)
    }

    @Test
    fun `parseCreateRequest falls back to origin domain when rp id is missing`() {
        val requestJson = """
            {
              "challenge": "Y2hhbGxlbmdlLTEyMw",
              "rp": {
                "name": "Amazon"
              },
              "user": {
                "id": "dXNlci0xMjM",
                "name": "user@example.com",
                "displayName": "Example User"
              }
            }
        """.trimIndent()

        val result = PasskeyWebAuthnHelper.parseCreateRequest(
            requestJson = requestJson,
            origin = "https://www.amazon.co.jp"
        )

        assertEquals("www.amazon.co.jp", result.rpId)
        assertEquals("user@example.com", result.userName)
    }

    @Test
    fun `createRegistrationResponse builds passkey and registration json`() {
        val requestJson = """
            {
              "challenge": "Y2hhbGxlbmdlLTEyMw",
              "rp": {
                "id": "example.com",
                "name": "Example"
              },
              "user": {
                "id": "dXNlci0xMjM",
                "name": "user@example.com",
                "displayName": "Example User"
              }
            }
        """.trimIndent()

        val result = PasskeyWebAuthnHelper.createRegistrationResponse(
            requestJson = requestJson,
            origin = "https://example.com",
            clientDataHash = null,
            packageName = null
        )

        val responseJson = JSONObject(result.registrationResponseJson)
        val responseBody = responseJson.getJSONObject("response")

        assertEquals("public-key", responseJson.getString("type"))
        assertEquals(result.passkeyData.credentialId, responseJson.getString("id"))
        assertEquals("example.com", result.passkeyData.rpId)
        assertEquals("https://example.com", result.passkeyData.origin)
        assertEquals("Example User", result.passkeyData.userDisplayName)
        val clientDataJson = JSONObject(
          String(
            PasskeyWebAuthnHelper.decodeBase64Url(responseBody.getString("clientDataJSON")),
            StandardCharsets.UTF_8
          )
        )
        assertEquals("webauthn.create", clientDataJson.getString("type"))
        assertEquals("Y2hhbGxlbmdlLTEyMw", clientDataJson.getString("challenge"))
        assertEquals("https://example.com", clientDataJson.getString("origin"))
        assertFalse(responseBody.getString("clientDataJSON").isBlank())
        assertFalse(responseBody.getString("attestationObject").isBlank())
        assertEquals("internal", responseBody.getJSONArray("transports").getString(0))
    }

    @Test
    fun `createAuthenticationResponse increments sign count and returns assertion json`() {
        val createRequestJson = """
            {
              "challenge": "Y2hhbGxlbmdlLTEyMw",
              "rp": {
                "id": "example.com",
                "name": "Example"
              },
              "user": {
                "id": "dXNlci0xMjM",
                "name": "user@example.com",
                "displayName": "Example User"
              }
            }
        """.trimIndent()
        val registration = PasskeyWebAuthnHelper.createRegistrationResponse(
            requestJson = createRequestJson,
            origin = "https://example.com",
            clientDataHash = null,
            packageName = null
        )
        val getRequestJson = """
            {
              "challenge": "bmV3LWNoYWxsZW5nZQ",
              "rpId": "example.com",
              "allowCredentials": [
                {
                  "type": "public-key",
                  "id": "${registration.passkeyData.credentialId}"
                }
              ]
            }
        """.trimIndent()

        val result = PasskeyWebAuthnHelper.createAuthenticationResponse(
            passkeyData = registration.passkeyData,
            requestJson = getRequestJson,
            origin = "https://example.com",
            clientDataHash = null,
            packageName = null
        )

        val responseJson = JSONObject(result.authenticationResponseJson)
        val responseBody = responseJson.getJSONObject("response")

        assertEquals(1L, result.updatedSignCount)
        assertEquals(registration.passkeyData.credentialId, responseJson.getString("id"))
        assertEquals("public-key", responseJson.getString("type"))
        assertEquals(registration.passkeyData.userHandle, responseBody.getString("userHandle"))
        val clientDataJson = JSONObject(
            String(
                PasskeyWebAuthnHelper.decodeBase64Url(responseBody.getString("clientDataJSON")),
                StandardCharsets.UTF_8
            )
        )
        assertEquals("webauthn.get", clientDataJson.getString("type"))
        assertEquals("bmV3LWNoYWxsZW5nZQ", clientDataJson.getString("challenge"))
        assertEquals("https://example.com", clientDataJson.getString("origin"))
        assertNotNull(responseBody.getString("authenticatorData"))
        assertNotNull(responseBody.getString("signature"))
        assertTrue(responseBody.getString("signature").isNotBlank())
    }

    @Test
    fun `createRegistrationResponse omits clientDataJson when clientDataHash is provided`() {
        val requestJson = """
            {
              "challenge": "Y2hhbGxlbmdlLTEyMw",
              "rp": {
                "id": "example.com",
                "name": "Example"
              },
              "user": {
                "id": "dXNlci0xMjM",
                "name": "user@example.com",
                "displayName": "Example User"
              }
            }
        """.trimIndent()

        val result = PasskeyWebAuthnHelper.createRegistrationResponse(
            requestJson = requestJson,
            origin = "https://example.com",
            clientDataHash = byteArrayOf(1, 2, 3),
            packageName = null
        )

        val responseBody = JSONObject(result.registrationResponseJson).getJSONObject("response")

        assertFalse(responseBody.has("clientDataJSON"))
        assertFalse(responseBody.getString("attestationObject").isBlank())
    }

    @Test
    fun `createAuthenticationResponse omits clientDataJson when clientDataHash is provided`() {
        val createRequestJson = """
            {
              "challenge": "Y2hhbGxlbmdlLTEyMw",
              "rp": {
                "id": "example.com",
                "name": "Example"
              },
              "user": {
                "id": "dXNlci0xMjM",
                "name": "user@example.com",
                "displayName": "Example User"
              }
            }
        """.trimIndent()
        val registration = PasskeyWebAuthnHelper.createRegistrationResponse(
            requestJson = createRequestJson,
            origin = "https://example.com",
            clientDataHash = null,
            packageName = null
        )
        val getRequestJson = """
            {
              "challenge": "bmV3LWNoYWxsZW5nZQ",
              "rpId": "example.com",
              "allowCredentials": [
                {
                  "type": "public-key",
                  "id": "${registration.passkeyData.credentialId}"
                }
              ]
            }
        """.trimIndent()

        val result = PasskeyWebAuthnHelper.createAuthenticationResponse(
            passkeyData = registration.passkeyData,
            requestJson = getRequestJson,
            origin = "https://example.com",
            clientDataHash = byteArrayOf(9, 8, 7),
            packageName = null
        )

        val responseBody = JSONObject(result.authenticationResponseJson).getJSONObject("response")

        assertFalse(responseBody.has("clientDataJSON"))
        assertEquals(registration.passkeyData.userHandle, responseBody.getString("userHandle"))
        assertTrue(responseBody.getString("signature").isNotBlank())
    }
}