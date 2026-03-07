package com.securevault.app.service.credential

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class KeyPassCredentialProviderService : CredentialProviderService() {

    @Inject
    lateinit var credentialRepository: CredentialRepository

    @Inject
    lateinit var logger: AppLogger

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        logger.d(TAG, "onBeginCreateCredentialRequest: type=${request::class.simpleName}")

        when (request) {
            is BeginCreatePasswordCredentialRequest -> {
                val createEntries = listOf(
                    CreateEntry(
                        ACCOUNT_ID,
                        createPendingIntent(ACTION_CREATE_PASSWORD)
                    )
                )
                callback.onResult(BeginCreateCredentialResponse(createEntries))
            }

            else -> {
                logger.d(TAG, "onBeginCreateCredentialRequest: unsupported type")
                callback.onError(CreateCredentialUnknownException())
            }
        }
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        val callingPackage = request.callingAppInfo?.packageName.orEmpty()
        logger.d(TAG, "onBeginGetCredentialRequest: callingPackage=$callingPackage")

        try {
            val credentialEntries = mutableListOf<PasswordCredentialEntry>()

            for (option in request.beginGetCredentialOptions) {
                when (option) {
                    is BeginGetPasswordOption -> {
                        logger.d(TAG, "onBeginGetCredentialRequest: BeginGetPasswordOption")
                        val credentials = runBlocking {
                            val byPackage = credentialRepository.findByPackageName(callingPackage)
                            if (byPackage.isNotEmpty()) byPackage else credentialRepository.getAll().first()
                        }

                        logger.d(TAG, "onBeginGetCredentialRequest: found ${credentials.size} credentials")

                        credentials.take(MAX_ENTRIES).forEach { credential ->
                            credentialEntries.add(
                                PasswordCredentialEntry.Builder(
                                    applicationContext,
                                    credential.username,
                                    createGetPendingIntent(credential.id),
                                    option
                                )
                                    .setDisplayName(credential.serviceName)
                                    .build()
                            )
                            logger.d(
                                TAG,
                                "onBeginGetCredentialRequest: added entry id=${credential.id}, user=${credential.username}, service=${credential.serviceName}"
                            )
                        }
                    }

                    else -> {
                        logger.d(
                            TAG,
                            "onBeginGetCredentialRequest: unsupported option ${option::class.simpleName}"
                        )
                    }
                }
            }

            val response = BeginGetCredentialResponse(credentialEntries)
            logger.d(TAG, "onBeginGetCredentialRequest: returning ${credentialEntries.size} entries")
            callback.onResult(response)
        } catch (e: Exception) {
            logger.e(TAG, "onBeginGetCredentialRequest: error", e)
            callback.onError(GetCredentialUnknownException())
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

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getActivity(
            applicationContext,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createGetPendingIntent(credentialId: Long): PendingIntent {
        val intent = Intent(applicationContext, CredentialProviderAuthActivity::class.java).apply {
            putExtra(CredentialProviderAuthActivity.EXTRA_CREDENTIAL_ID, credentialId)
        }
        return PendingIntent.getActivity(
            applicationContext,
            credentialId.toInt(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val TAG = "KeyPassCredProvider"
        private const val ACCOUNT_ID = "keypass_default"
        private const val ACTION_CREATE_PASSWORD = "com.securevault.app.CREATE_PASSWORD"
        private const val MAX_ENTRIES = 8
    }
}
