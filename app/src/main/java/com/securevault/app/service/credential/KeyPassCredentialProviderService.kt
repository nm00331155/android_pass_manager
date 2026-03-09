package com.securevault.app.service.credential

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialOption
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import com.securevault.app.R
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class KeyPassCredentialProviderService : CredentialProviderService() {

    @Inject
    lateinit var credentialRepository: CredentialRepository

    @Inject
    lateinit var logger: AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        logger.d(TAG, "onBeginCreateCredentialRequest: type=${request::class.simpleName}")

        cancellationSignal.setOnCancelListener {
            logger.d(TAG, "onBeginCreateCredentialRequest: canceled")
        }

        serviceScope.launch {
            val allCredentials = runCatching { credentialRepository.getAll().first() }
                .onFailure { throwable ->
                    logger.e(TAG, "onBeginCreateCredentialRequest: failed to load credential counts", throwable)
                }
                .getOrDefault(emptyList())
            val passwordCount = allCredentials.count { it.hasPassword }
            val passkeyCount = allCredentials.count { it.isPasskey }
            val totalCount = allCredentials.size

            val createEntries = when (request) {
                is BeginCreatePasswordCredentialRequest -> {
                    listOf(
                        createEntry(
                            accountName = getString(R.string.app_name),
                            description = getString(R.string.credential_type_password),
                            flowType = CredentialProviderAuthActivity.FLOW_CREATE_PASSWORD,
                            requestCode = FLOW_CREATE_PASSWORD_REQUEST_CODE,
                            passwordCount = passwordCount,
                            publicKeyCount = passkeyCount,
                            totalCount = totalCount
                        )
                    )
                }

                is BeginCreatePublicKeyCredentialRequest -> {
                    val requestInfo = runCatching {
                        PasskeyWebAuthnHelper.parseCreateRequest(request.requestJson)
                    }.onFailure { throwable ->
                        logger.w(TAG, "onBeginCreateCredentialRequest: failed to parse passkey request", throwable)
                    }.getOrNull()

                    if (requestInfo == null) {
                        emptyList()
                    } else {
                        listOf(
                            createEntry(
                                accountName = requestInfo.userName,
                                description = requestInfo.rpName ?: requestInfo.rpId,
                                flowType = CredentialProviderAuthActivity.FLOW_CREATE_PASSKEY,
                                requestCode = FLOW_CREATE_PASSKEY_REQUEST_CODE,
                                passwordCount = passwordCount,
                                publicKeyCount = passkeyCount,
                                totalCount = totalCount
                            )
                        )
                    }
                }

                else -> emptyList()
            }

            if (cancellationSignal.isCanceled) {
                logger.d(TAG, "onBeginCreateCredentialRequest: skip result because request was canceled")
                return@launch
            }

            if (createEntries.isEmpty()) {
                logger.d(TAG, "onBeginCreateCredentialRequest: no create entries available")
                callback.onError(CreateCredentialUnknownException())
                return@launch
            }

            callback.onResult(BeginCreateCredentialResponse(createEntries, null))
        }
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        val callingPackage = request.callingAppInfo?.packageName.orEmpty()
        logger.d(TAG, "onBeginGetCredentialRequest: callingPackage=$callingPackage")

        cancellationSignal.setOnCancelListener {
            logger.d(TAG, "onBeginGetCredentialRequest: canceled for package=$callingPackage")
        }

        serviceScope.launch {
            try {
                val credentialEntries = mutableListOf<CredentialEntry>()

                for (option in request.beginGetCredentialOptions) {
                    when (option) {
                        is BeginGetPasswordOption -> {
                            credentialEntries += buildPasswordEntries(option, callingPackage)
                        }

                        is BeginGetPublicKeyCredentialOption -> {
                            credentialEntries += buildPasskeyEntries(option)
                        }

                        else -> {
                            logger.d(
                                TAG,
                                "onBeginGetCredentialRequest: unsupported option ${option::class.simpleName}"
                            )
                        }
                    }
                }

                if (cancellationSignal.isCanceled) {
                    logger.d(TAG, "onBeginGetCredentialRequest: skip result because request was canceled")
                    return@launch
                }

                val response = BeginGetCredentialResponse(
                    credentialEntries,
                    emptyList(),
                    emptyList(),
                    null
                )
                logger.d(TAG, "onBeginGetCredentialRequest: returning ${credentialEntries.size} entries")
                callback.onResult(response)
            } catch (e: Exception) {
                logger.e(TAG, "onBeginGetCredentialRequest: error", e)
                if (!cancellationSignal.isCanceled) {
                    callback.onError(GetCredentialUnknownException())
                }
            }
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        logger.d(TAG, "onClearCredentialStateRequest")
        callback.onResult(null)
    }

    private fun createEntry(
        accountName: String,
        description: String,
        flowType: String,
        requestCode: Int,
        passwordCount: Int,
        publicKeyCount: Int,
        totalCount: Int
    ): CreateEntry {
        return CreateEntry(
            accountName,
            createCreatePendingIntent(flowType, requestCode),
            description,
            Instant.now(),
            Icon.createWithResource(applicationContext, R.mipmap.ic_launcher),
            passwordCount,
            publicKeyCount,
            totalCount,
            false
        )
    }

    private suspend fun buildPasswordEntries(
        option: BeginGetPasswordOption,
        callingPackage: String
    ): List<PasswordCredentialEntry> {
        logger.d(TAG, "onBeginGetCredentialRequest: BeginGetPasswordOption")
        val credentials = selectPasswordCredentials(callingPackage)
        logger.d(TAG, "onBeginGetCredentialRequest: found ${credentials.size} password credentials")

        return credentials.take(MAX_ENTRIES).mapNotNull { credential ->
            if (credential.username.isBlank()) {
                logger.w(
                    TAG,
                    "onBeginGetCredentialRequest: SKIP password credential id=${credential.id} service=${credential.serviceName} (empty username)"
                )
                return@mapNotNull null
            }

            runCatching {
                PasswordCredentialEntry.Builder(
                    applicationContext,
                    credential.username,
                    createGetPendingIntent(credential.id),
                    option
                )
                    .setDisplayName(credential.serviceName)
                    .build()
            }.onFailure { throwable ->
                logger.w(
                    TAG,
                    "onBeginGetCredentialRequest: failed to build password entry id=${credential.id}",
                    throwable
                )
            }.getOrNull()
        }
    }

    private suspend fun buildPasskeyEntries(
        option: BeginGetPublicKeyCredentialOption
    ): List<PublicKeyCredentialEntry> {
        logger.d(TAG, "onBeginGetCredentialRequest: BeginGetPublicKeyCredentialOption")
        val requestInfo = runCatching {
            PasskeyWebAuthnHelper.parseGetRequest(option.requestJson)
        }.onFailure { throwable ->
            logger.w(TAG, "onBeginGetCredentialRequest: failed to parse passkey option", throwable)
        }.getOrNull() ?: return emptyList()

        val credentials = credentialRepository.getAll().first()
            .asSequence()
            .filter { credential ->
                credential.isPasskey && credential.passkeyData?.rpId.equals(requestInfo.rpId, ignoreCase = true)
            }
            .filter { credential ->
                requestInfo.allowedCredentialIds.isEmpty() ||
                    requestInfo.allowedCredentialIds.contains(credential.passkeyData?.credentialId)
            }
            .sortedByDescending { it.updatedAt }
            .take(MAX_ENTRIES)
            .toList()
        logger.d(TAG, "onBeginGetCredentialRequest: found ${credentials.size} passkeys")

        return credentials.mapNotNull { credential ->
            val passkeyData = credential.passkeyData ?: return@mapNotNull null
            runCatching {
                PublicKeyCredentialEntry(
                    applicationContext,
                    credential.username,
                    createGetPendingIntent(credential.id),
                    option,
                    passkeyData.userDisplayName ?: credential.serviceName,
                    Instant.ofEpochMilli(credential.updatedAt),
                    Icon.createWithResource(applicationContext, R.mipmap.ic_launcher),
                    false,
                    false
                )
            }.onFailure { throwable ->
                logger.w(
                    TAG,
                    "onBeginGetCredentialRequest: failed to build passkey entry id=${credential.id}",
                    throwable
                )
            }.getOrNull()
        }
    }

    private suspend fun selectPasswordCredentials(callingPackage: String): List<Credential> {
        val byPackage = credentialRepository.findByPackageName(callingPackage)
            .filter { it.hasPassword }
        if (byPackage.isNotEmpty()) {
            return byPackage
        }

        return credentialRepository.getAll().first()
            .filter { it.hasPassword }
            .sortedByDescending { it.updatedAt }
    }

    private fun createCreatePendingIntent(flowType: String, requestCode: Int): PendingIntent {
        val intent = Intent(applicationContext, CredentialProviderAuthActivity::class.java).apply {
            putExtra(CredentialProviderAuthActivity.EXTRA_CREATE_FLOW_TYPE, flowType)
        }
        return PendingIntent.getActivity(
            applicationContext,
            requestCode,
            intent,
            pendingIntentFlags()
        )
    }

    private fun createGetPendingIntent(credentialId: Long): PendingIntent {
        val intent = Intent(applicationContext, CredentialProviderAuthActivity::class.java).apply {
            putExtra(CredentialProviderAuthActivity.EXTRA_CREDENTIAL_ID, credentialId)
            putExtra(CredentialProviderAuthActivity.EXTRA_CREATE_FLOW_TYPE, CredentialProviderAuthActivity.FLOW_GET)
        }
        return PendingIntent.getActivity(
            applicationContext,
            credentialId.toInt(),
            intent,
            pendingIntentFlags()
        )
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    companion object {
        private const val TAG = "KeyPassCredProvider"
        private const val MAX_ENTRIES = 8
        private const val FLOW_CREATE_PASSWORD_REQUEST_CODE = 31_000
        private const val FLOW_CREATE_PASSKEY_REQUEST_CODE = 31_001
    }
}
