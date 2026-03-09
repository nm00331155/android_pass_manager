package com.securevault.app.service.credential

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasskeyWebAuthnHelperTest {

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
        assertNotNull(responseBody.getString("authenticatorData"))
        assertNotNull(responseBody.getString("signature"))
        assertTrue(responseBody.getString("signature").isNotBlank())
    }
}