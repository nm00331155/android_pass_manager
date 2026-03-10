package com.securevault.app.service.credential

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPasswordOption
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.securevault.app.R
import com.securevault.app.biometric.BiometricAuthManager
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CredentialProviderAuthActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var credentialRepository: CredentialRepository

    @Inject
    lateinit var logger: AppLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logger.d(
            TAG,
            "onCreate: credentialId=${intent.getLongExtra(EXTRA_CREDENTIAL_ID, -1L)}, flow=${intent.getStringExtra(EXTRA_CREATE_FLOW_TYPE)}"
        )

        biometricAuthManager.authenticate(
            activity = this,
            title = getString(R.string.autofill_auth_title),
            subtitle = getString(R.string.autofill_auth_subtitle),
            onSuccess = {
                logger.d(TAG, "Biometric auth succeeded")
                handleCredentialFlow()
            },
            onError = {
                logger.w(TAG, "Biometric auth failed")
                finishCanceled()
            },
            onFallback = {
                logger.d(TAG, "Biometric fallback")
            }
        )
    }

    private fun handleCredentialFlow() {
        lifecycleScope.launch {
            val createRequest = runCatching {
                PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
            }.getOrNull()
            if (createRequest != null) {
                handleCreateRequest(createRequest)
                return@launch
            }

            val getRequest = runCatching {
                PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
            }.getOrNull()
            if (getRequest != null) {
                handleGetRequest(getRequest)
                return@launch
            }

            logger.w(TAG, "Unable to retrieve provider request from pending intent")
            finishCanceled()
        }
    }

    private suspend fun handleGetRequest(request: ProviderGetCredentialRequest) {
        val credentialId = intent.getLongExtra(EXTRA_CREDENTIAL_ID, -1L)
        if (credentialId == -1L) {
            finishWithGetException(GetCredentialUnknownException())
            return
        }

        val credential = runCatching {
            credentialRepository.getById(credentialId).first()
        }.getOrNull()

        if (credential == null) {
            logger.w(TAG, "Credential not found id=$credentialId")
            finishWithGetException(GetCredentialUnknownException())
            return
        }

        val selectedOption = when {
            credential.isPasskey -> request.credentialOptions.firstOrNull { it is GetPublicKeyCredentialOption }
            credential.hasPassword -> request.credentialOptions.firstOrNull { it is GetPasswordOption }
            else -> request.credentialOptions.firstOrNull()
        }

        when (val option = selectedOption) {
            is GetPasswordOption -> returnPasswordCredential(credential)
            is GetPublicKeyCredentialOption -> returnPasskeyCredential(
                credential = credential,
                option = option,
                callingAppInfo = request.callingAppInfo
            )

            else -> finishWithGetException(GetCredentialUnknownException())
        }
    }

    private suspend fun handleCreateRequest(request: ProviderCreateCredentialRequest) {
        when (val callingRequest = request.callingRequest) {
            is CreatePasswordRequest -> createPasswordCredential(
                request = callingRequest,
                callingAppInfo = request.callingAppInfo
            )

            is CreatePublicKeyCredentialRequest -> createPasskeyCredential(
                request = callingRequest,
                callingAppInfo = request.callingAppInfo
            )

            else -> finishWithCreateException(CreateCredentialUnknownException())
        }
    }

    private fun returnPasswordCredential(credential: Credential) {
        val password = credential.password
        if (password.isNullOrBlank()) {
            finishWithGetException(GetCredentialUnknownException())
            return
        }

        logger.d(
            TAG,
            "Returning password credential: service=${credential.serviceName}, user=${credential.username}"
        )

        val response = GetCredentialResponse(PasswordCredential(credential.username, password))
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(result, response)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private suspend fun returnPasskeyCredential(
        credential: Credential,
        option: GetPublicKeyCredentialOption,
        callingAppInfo: CallingAppInfo
    ) {
        val passkeyData = credential.passkeyData
        if (passkeyData == null) {
            finishWithGetException(GetCredentialUnknownException())
            return
        }

        val origin = resolveCallingOrigin(callingAppInfo)
        if (origin.isNullOrBlank()) {
            logger.w(TAG, "Unable to resolve origin for passkey get package=${callingAppInfo.packageName}")
            finishWithGetException(GetCredentialUnknownException())
            return
        }

        val authResult = runCatching {
            PasskeyWebAuthnHelper.createAuthenticationResponse(
                passkeyData = passkeyData,
                requestJson = option.requestJson,
                origin = origin,
                clientDataHash = option.clientDataHash,
                packageName = callingAppInfo.packageName
            )
        }.onFailure { throwable ->
            logger.e(TAG, "Failed to create passkey get response", throwable)
        }.getOrNull()
        if (authResult == null) {
            finishWithGetException(GetCredentialUnknownException())
            return
        }

        runCatching {
            credentialRepository.save(
                credential.copy(
                    updatedAt = System.currentTimeMillis(),
                    passkeyData = passkeyData.copy(signCount = authResult.updatedSignCount)
                )
            )
        }.onFailure { throwable ->
            logger.w(TAG, "Failed to persist passkey signCount update", throwable)
        }

        val response = GetCredentialResponse(
            PublicKeyCredential(authResult.authenticationResponseJson)
        )
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(result, response)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private suspend fun createPasswordCredential(
        request: CreatePasswordRequest,
        callingAppInfo: CallingAppInfo
    ) {
        val descriptor = resolveServiceDescriptor(callingAppInfo)
        runCatching {
            credentialRepository.save(
                Credential(
                    serviceName = descriptor.serviceName,
                    serviceUrl = descriptor.serviceUrl,
                    packageName = descriptor.packageName,
                    username = request.id,
                    password = request.password,
                    category = DEFAULT_CATEGORY
                )
            )
        }.onFailure { throwable ->
            logger.e(TAG, "Failed to save password credential", throwable)
            finishWithCreateException(CreateCredentialUnknownException())
            return
        }

        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, CreatePasswordResponse())
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private suspend fun createPasskeyCredential(
        request: CreatePublicKeyCredentialRequest,
        callingAppInfo: CallingAppInfo
    ) {
        val origin = resolveCallingOrigin(callingAppInfo)
        if (origin.isNullOrBlank()) {
            logger.w(TAG, "Unable to resolve origin for passkey create package=${callingAppInfo.packageName}")
            finishWithCreateException(CreateCredentialUnknownException())
            return
        }

        val requestInfo = runCatching {
            PasskeyWebAuthnHelper.parseCreateRequest(request.requestJson, origin)
        }.onFailure { throwable ->
            logger.e(TAG, "Failed to parse passkey create request", throwable)
        }.getOrNull()
        if (requestInfo == null) {
            finishWithCreateException(CreateCredentialUnknownException())
            return
        }

        logger.d(
            TAG,
            "Creating passkey credential: package=${callingAppInfo.packageName}, origin=$origin, rpId=${requestInfo.rpId}, user=${requestInfo.userName}, clientDataHashPresent=${request.clientDataHash != null}"
        )

        val registrationResult = runCatching {
            PasskeyWebAuthnHelper.createRegistrationResponse(
                requestJson = request.requestJson,
                origin = origin,
                clientDataHash = request.clientDataHash,
                packageName = callingAppInfo.packageName
            )
        }.onFailure { throwable ->
            logger.e(TAG, "Failed to create passkey registration response", throwable)
        }.getOrNull()
        if (registrationResult == null) {
            finishWithCreateException(CreateCredentialUnknownException())
            return
        }

        val isBrowserFlow = callingAppInfo.packageName in PrivilegedBrowserAllowlist.browserPackages
        runCatching {
            credentialRepository.save(
                Credential(
                    serviceName = requestInfo.rpName ?: requestInfo.rpId,
                    serviceUrl = requestInfo.rpId,
                    packageName = callingAppInfo.packageName.takeIf { !isBrowserFlow },
                    username = requestInfo.userName,
                    category = DEFAULT_CATEGORY,
                    passkeyData = registrationResult.passkeyData
                )
            )
        }.onFailure { throwable ->
            logger.e(TAG, "Failed to save passkey credential", throwable)
            finishWithCreateException(CreateCredentialUnknownException())
            return
        }

        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(
            result,
            CreatePublicKeyCredentialResponse(registrationResult.registrationResponseJson)
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun resolveServiceDescriptor(callingAppInfo: CallingAppInfo): ServiceDescriptor {
        val origin = resolveCallingOrigin(callingAppInfo)
        val domain = extractDomain(origin)
        return if (!domain.isNullOrBlank()) {
            ServiceDescriptor(
                serviceName = domain,
                serviceUrl = domain,
                packageName = null
            )
        } else {
            ServiceDescriptor(
                serviceName = resolveApplicationLabel(callingAppInfo.packageName)
                    ?: callingAppInfo.packageName.substringAfterLast('.'),
                serviceUrl = null,
                packageName = callingAppInfo.packageName
            )
        }
    }

    private fun resolveCallingOrigin(callingAppInfo: CallingAppInfo): String? {
        val browserOrigin = runCatching {
            callingAppInfo.getOrigin(PrivilegedBrowserAllowlist.json)
        }.onFailure { throwable ->
            logger.w(TAG, "Browser origin lookup failed for ${callingAppInfo.packageName}", throwable)
        }.getOrNull()
        if (!browserOrigin.isNullOrBlank()) {
            return browserOrigin
        }

        if (callingAppInfo.packageName in PrivilegedBrowserAllowlist.browserPackages) {
            return null
        }

        return computeAndroidFacetOrigin(callingAppInfo)
    }

    private fun computeAndroidFacetOrigin(callingAppInfo: CallingAppInfo): String? {
        val signatureBytes = resolveCallingSignatureBytes(callingAppInfo) ?: return null

        val certificateDigest = MessageDigest.getInstance(HASH_ALGORITHM)
            .digest(signatureBytes)
        return ANDROID_ORIGIN_PREFIX + PasskeyWebAuthnHelper.encodeBase64Url(certificateDigest)
    }

    private fun resolveCallingSignatureBytes(callingAppInfo: CallingAppInfo): ByteArray? {
        val signingInfoCompat = runCatching {
            callingAppInfo.signingInfoCompat
        }.onFailure { throwable ->
            logger.w(TAG, "Failed to read calling app signing info for ${callingAppInfo.packageName}", throwable)
        }.getOrNull() ?: return null

        val signature = signingInfoCompat.apkContentsSigners.firstOrNull()
            ?: signingInfoCompat.signingCertificateHistory.firstOrNull()

        if (signature == null) {
            logger.w(TAG, "No signing certificates available for ${callingAppInfo.packageName}")
            return null
        }

        return signature.toByteArray()
    }

    private fun resolveApplicationLabel(packageName: String): String? {
        return runCatching {
            val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrNull()
    }

    private fun extractDomain(origin: String?): String? {
        val normalized = origin
            ?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?.substringAfter("://")
            ?.substringBefore('/')
            ?.substringBefore(':')
        return normalized?.ifBlank { null }
    }

    private fun finishWithGetException(exception: GetCredentialUnknownException) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(result, exception)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishWithCreateException(exception: CreateCredentialUnknownException) {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(result, exception)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishCanceled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        biometricAuthManager.resetState()
        super.onDestroy()
    }

    private data class ServiceDescriptor(
        val serviceName: String,
        val serviceUrl: String?,
        val packageName: String?
    )

    companion object {
        private const val TAG = "CredProviderAuth"
        const val EXTRA_CREDENTIAL_ID = "credentialId"
        const val EXTRA_CREATE_FLOW_TYPE = "createFlowType"

        const val FLOW_GET = "get"
        const val FLOW_CREATE_PASSWORD = "create_password"
        const val FLOW_CREATE_PASSKEY = "create_passkey"

        private const val DEFAULT_CATEGORY = "login"
        private const val HASH_ALGORITHM = "SHA-256"
        private const val ANDROID_ORIGIN_PREFIX = "android:apk-key-hash:"
    }
}
