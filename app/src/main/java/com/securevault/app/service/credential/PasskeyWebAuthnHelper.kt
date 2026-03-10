package com.securevault.app.service.credential

import com.securevault.app.data.repository.model.PasskeyData
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import org.json.JSONArray
import org.json.JSONObject

object PasskeyWebAuthnHelper {

    data class CreateRequestDisplayInfo(
        val rpLabel: String,
        val userName: String
    )

    data class CreateRequestInfo(
        val rpId: String,
        val rpName: String?,
        val userName: String,
        val userDisplayName: String?,
        val userHandle: ByteArray,
        val challenge: String
    )

    data class GetRequestInfo(
        val rpId: String,
        val challenge: String,
        val allowedCredentialIds: Set<String>
    )

    data class RegistrationResult(
        val passkeyData: PasskeyData,
        val registrationResponseJson: String
    )

    data class AuthenticationResult(
        val authenticationResponseJson: String,
        val updatedSignCount: Long
    )

    fun parseCreateRequestDisplay(requestJson: String): CreateRequestDisplayInfo {
        val json = JSONObject(requestJson)
        val rpJson = json.getJSONObject("rp")
        val userJson = json.getJSONObject("user")

        return CreateRequestDisplayInfo(
            rpLabel = rpJson.optString("name").takeIf { it.isNotBlank() }
                ?: rpJson.optString("id").takeIf { it.isNotBlank() }
                ?: DEFAULT_PASSKEY_PROVIDER_LABEL,
            userName = userJson.optString("name").takeIf { it.isNotBlank() }
                ?: userJson.optString("displayName").takeIf { it.isNotBlank() }
                ?: DEFAULT_PASSKEY_ACCOUNT_LABEL
        )
    }

    fun parseCreateRequest(requestJson: String, origin: String? = null): CreateRequestInfo {
        val json = JSONObject(requestJson)
        val rpJson = json.getJSONObject("rp")
        val userJson = json.getJSONObject("user")

        val rpId = resolveRpId(rpJson, origin)
            ?: throw IllegalArgumentException("Missing rp.id")
        val challenge = json.optString("challenge").ifBlank {
            throw IllegalArgumentException("Missing challenge")
        }
        val userName = userJson.optString("name").ifBlank {
            throw IllegalArgumentException("Missing user.name")
        }
        val userDisplayName = userJson.optString("displayName").takeIf { it.isNotBlank() }
        val rawUserId = userJson.optString("id")
        val userHandle = when {
            rawUserId.isBlank() -> userName.toByteArray(StandardCharsets.UTF_8)
            else -> decodeBase64UrlOrNull(rawUserId)
                ?: rawUserId.toByteArray(StandardCharsets.UTF_8)
        }

        return CreateRequestInfo(
            rpId = rpId,
            rpName = rpJson.optString("name").takeIf { it.isNotBlank() },
            userName = userName,
            userDisplayName = userDisplayName,
            userHandle = userHandle,
            challenge = challenge
        )
    }

    private fun resolveRpId(rpJson: JSONObject, origin: String?): String? {
        val explicitRpId = rpJson.optString("id").takeIf { it.isNotBlank() }
        if (!explicitRpId.isNullOrBlank()) {
            return explicitRpId
        }

        extractDomainFromOrigin(origin)?.let { return it }

        val rpName = rpJson.optString("name").takeIf { it.isNotBlank() }
        if (!rpName.isNullOrBlank() && looksLikeRpId(rpName)) {
            return rpName.lowercase()
        }

        return null
    }

    private fun extractDomainFromOrigin(origin: String?): String? {
        return origin
            ?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.substringAfter("://")
            ?.substringBefore('/')
            ?.substringBefore(':')
            ?.lowercase()
            ?.ifBlank { null }
    }

    private fun looksLikeRpId(value: String): Boolean {
        return value.contains('.') && !value.contains(' ') && !value.startsWith("android:")
    }

    fun parseGetRequest(requestJson: String): GetRequestInfo {
        val json = JSONObject(requestJson)
        val allowedCredentialIds = buildSet {
            val allowCredentials = json.optJSONArray("allowCredentials") ?: JSONArray()
            for (index in 0 until allowCredentials.length()) {
                val credentialJson = allowCredentials.optJSONObject(index) ?: continue
                val credentialId = credentialJson.optString("id")
                if (credentialId.isNotBlank()) {
                    add(credentialId)
                }
            }
        }

        return GetRequestInfo(
            rpId = json.optString("rpId").ifBlank {
                throw IllegalArgumentException("Missing rpId")
            },
            challenge = json.optString("challenge").ifBlank {
                throw IllegalArgumentException("Missing challenge")
            },
            allowedCredentialIds = allowedCredentialIds
        )
    }

    fun createRegistrationResponse(
        requestJson: String,
        origin: String,
        clientDataHash: ByteArray?,
        packageName: String?
    ): RegistrationResult {
        val requestInfo = parseCreateRequest(requestJson, origin)
        val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM).apply {
            initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        }
        val keyPair = keyPairGenerator.generateKeyPair()
        val credentialIdBytes = ByteArray(CREDENTIAL_ID_LENGTH).also(SecureRandom()::nextBytes)
        val credentialId = encodeBase64Url(credentialIdBytes)
        val clientDataJsonBytes = buildClientDataJson(
            type = CLIENT_DATA_CREATE,
            challenge = requestInfo.challenge,
            origin = origin,
            packageName = packageName
        )
        val authData = buildRegistrationAuthenticatorData(
            rpId = requestInfo.rpId,
            credentialId = credentialIdBytes,
            publicKey = keyPair.public
        )
        val attestationObject = buildAttestationObject(authData)
        val registrationJson = JSONObject()
            .put("id", credentialId)
            .put("rawId", credentialId)
            .put("type", PUBLIC_KEY_TYPE)
            .put("authenticatorAttachment", AUTHENTICATOR_ATTACHMENT)
            .put("clientExtensionResults", JSONObject())
            .put(
                "response",
                JSONObject().apply {
                    if (clientDataHash == null) {
                        put("clientDataJSON", encodeBase64Url(clientDataJsonBytes))
                    }
                    put("attestationObject", encodeBase64Url(attestationObject))
                    put("transports", JSONArray().put(TRANSPORT_INTERNAL))
                }
            )
            .toString()

        val passkeyData = PasskeyData(
            credentialId = credentialId,
            publicKey = encodeBase64Url(keyPair.public.encoded),
            privateKey = encodeBase64Url(keyPair.private.encoded),
            userHandle = encodeBase64Url(requestInfo.userHandle),
            rpId = requestInfo.rpId,
            origin = origin,
            signCount = 0,
            userDisplayName = requestInfo.userDisplayName
        )

        return RegistrationResult(
            passkeyData = passkeyData,
            registrationResponseJson = registrationJson
        )
    }

    fun createAuthenticationResponse(
        passkeyData: PasskeyData,
        requestJson: String,
        origin: String,
        clientDataHash: ByteArray?,
        packageName: String?
    ): AuthenticationResult {
        val requestInfo = parseGetRequest(requestJson)
        require(passkeyData.rpId.equals(requestInfo.rpId, ignoreCase = true)) {
            "RP ID mismatch"
        }

        val privateKey = KeyFactory.getInstance(KEY_ALGORITHM).generatePrivate(
            PKCS8EncodedKeySpec(decodeBase64Url(passkeyData.privateKey))
        )
        val clientDataJsonBytes = buildClientDataJson(
            type = CLIENT_DATA_GET,
            challenge = requestInfo.challenge,
            origin = origin,
            packageName = packageName
        )
        val signedClientDataHash = clientDataHash ?: sha256(clientDataJsonBytes)
        val updatedSignCount = passkeyData.signCount + 1
        val authenticatorData = buildAssertionAuthenticatorData(
            rpId = passkeyData.rpId,
            signCount = updatedSignCount
        )
        val signature = sign(
            privateKey = privateKey,
            payload = authenticatorData + signedClientDataHash
        )
        val authenticationJson = JSONObject()
            .put("id", passkeyData.credentialId)
            .put("rawId", passkeyData.credentialId)
            .put("type", PUBLIC_KEY_TYPE)
            .put("authenticatorAttachment", AUTHENTICATOR_ATTACHMENT)
            .put("clientExtensionResults", JSONObject())
            .put(
                "response",
                JSONObject().apply {
                    if (clientDataHash == null) {
                        put("clientDataJSON", encodeBase64Url(clientDataJsonBytes))
                    }
                    put("authenticatorData", encodeBase64Url(authenticatorData))
                    put("signature", encodeBase64Url(signature))
                    put("userHandle", passkeyData.userHandle)
                }
            )
            .toString()

        return AuthenticationResult(
            authenticationResponseJson = authenticationJson,
            updatedSignCount = updatedSignCount
        )
    }

    fun encodeBase64Url(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun decodeBase64Url(value: String): ByteArray {
        val normalized = buildString {
            append(value)
            repeat((4 - value.length % 4) % 4) {
                append('=')
            }
        }
        return Base64.getUrlDecoder().decode(normalized)
    }

    private fun decodeBase64UrlOrNull(value: String): ByteArray? {
        return runCatching { decodeBase64Url(value) }.getOrNull()
    }

    private fun buildClientDataJson(
        type: String,
        challenge: String,
        origin: String,
        packageName: String?
    ): ByteArray {
        val json = JSONObject()
            .put("type", type)
            .put("challenge", challenge)
            .put("origin", origin)
            .put("crossOrigin", false)

        if (!packageName.isNullOrBlank() && origin.startsWith(ANDROID_ORIGIN_PREFIX)) {
            json.put("androidPackageName", packageName)
        }

        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun buildRegistrationAuthenticatorData(
        rpId: String,
        credentialId: ByteArray,
        publicKey: PublicKey
    ): ByteArray {
        val authData = ByteArrayOutputStream()
        authData.write(sha256(rpId.toByteArray(StandardCharsets.UTF_8)))
        authData.write(REGISTRATION_FLAGS)
        authData.write(signCountBytes(0))
        authData.write(ByteArray(AAGUID_LENGTH))
        authData.write(shortBytes(credentialId.size))
        authData.write(credentialId)
        authData.write(encodeCosePublicKey(publicKey))
        return authData.toByteArray()
    }

    private fun buildAssertionAuthenticatorData(rpId: String, signCount: Long): ByteArray {
        val authData = ByteArrayOutputStream()
        authData.write(sha256(rpId.toByteArray(StandardCharsets.UTF_8)))
        authData.write(ASSERTION_FLAGS)
        authData.write(signCountBytes(signCount))
        return authData.toByteArray()
    }

    private fun buildAttestationObject(authData: ByteArray): ByteArray {
        return cborMap(
            listOf(
                cborText("fmt") to cborText("none"),
                cborText("authData") to cborByteString(authData),
                cborText("attStmt") to cborMap(emptyList())
            )
        )
    }

    private fun encodeCosePublicKey(publicKey: PublicKey): ByteArray {
        val ecPublicKey = publicKey as ECPublicKey
        val x = ecPublicKey.w.affineX.toFixedLength(COORDINATE_LENGTH)
        val y = ecPublicKey.w.affineY.toFixedLength(COORDINATE_LENGTH)

        return cborMap(
            listOf(
                cborInt(1) to cborInt(2),
                cborInt(3) to cborInt(-7),
                cborInt(-1) to cborInt(1),
                cborInt(-2) to cborByteString(x),
                cborInt(-3) to cborByteString(y)
            )
        )
    }

    private fun sign(privateKey: PrivateKey, payload: ByteArray): ByteArray {
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance(HASH_ALGORITHM).digest(bytes)
    }

    private fun signCountBytes(signCount: Long): ByteArray {
        return ByteBuffer.allocate(Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(signCount.toInt())
            .array()
    }

    private fun shortBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(Short.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(value.toShort())
            .array()
    }

    private fun cborText(value: String): ByteArray {
        val textBytes = value.toByteArray(StandardCharsets.UTF_8)
        return cborHeader(MAJOR_TEXT, textBytes.size.toLong()) + textBytes
    }

    private fun cborByteString(value: ByteArray): ByteArray {
        return cborHeader(MAJOR_BYTE_STRING, value.size.toLong()) + value
    }

    private fun cborInt(value: Int): ByteArray {
        return if (value >= 0) {
            cborHeader(MAJOR_UNSIGNED, value.toLong())
        } else {
            cborHeader(MAJOR_NEGATIVE, (-1L - value))
        }
    }

    private fun cborMap(entries: List<Pair<ByteArray, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(cborHeader(MAJOR_MAP, entries.size.toLong()))
        entries.forEach { (key, value) ->
            output.write(key)
            output.write(value)
        }
        return output.toByteArray()
    }

    private fun cborHeader(majorType: Int, value: Long): ByteArray {
        require(value >= 0) { "CBOR header value must be >= 0" }
        return when {
            value < 24 -> byteArrayOf(((majorType shl 5) or value.toInt()).toByte())
            value <= 0xFF -> byteArrayOf(((majorType shl 5) or 24).toByte(), value.toByte())
            value <= 0xFFFF -> byteArrayOf(
                ((majorType shl 5) or 25).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )

            value <= 0xFFFF_FFFFL -> byteArrayOf(
                ((majorType shl 5) or 26).toByte(),
                ((value shr 24) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )

            else -> byteArrayOf(
                ((majorType shl 5) or 27).toByte(),
                ((value shr 56) and 0xFF).toByte(),
                ((value shr 48) and 0xFF).toByte(),
                ((value shr 40) and 0xFF).toByte(),
                ((value shr 32) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
        }
    }

    private fun BigInteger.toFixedLength(length: Int): ByteArray {
        val source = toByteArray()
        val normalized = when {
            source.size == length -> source
            source.size == length + 1 && source.first() == 0.toByte() -> source.copyOfRange(1, source.size)
            source.size < length -> ByteArray(length - source.size) + source
            else -> source.copyOfRange(source.size - length, source.size)
        }
        return normalized
    }

    private const val PUBLIC_KEY_TYPE = "public-key"
    private const val AUTHENTICATOR_ATTACHMENT = "platform"
    private const val TRANSPORT_INTERNAL = "internal"
    private const val CLIENT_DATA_CREATE = "webauthn.create"
    private const val CLIENT_DATA_GET = "webauthn.get"
    private const val KEY_ALGORITHM = "EC"
    private const val EC_CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val HASH_ALGORITHM = "SHA-256"
    private const val ANDROID_ORIGIN_PREFIX = "android:apk-key-hash:"
    private const val CREDENTIAL_ID_LENGTH = 32
    private const val AAGUID_LENGTH = 16
    private const val COORDINATE_LENGTH = 32
    private const val REGISTRATION_FLAGS = 0x45
    private const val ASSERTION_FLAGS = 0x05
    private const val MAJOR_UNSIGNED = 0
    private const val MAJOR_NEGATIVE = 1
    private const val MAJOR_BYTE_STRING = 2
    private const val MAJOR_TEXT = 3
    private const val MAJOR_MAP = 5
    private const val DEFAULT_PASSKEY_PROVIDER_LABEL = "Passkey"
    private const val DEFAULT_PASSKEY_ACCOUNT_LABEL = "account"
}