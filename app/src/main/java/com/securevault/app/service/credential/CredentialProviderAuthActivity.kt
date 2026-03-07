package com.securevault.app.service.credential

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.securevault.app.R
import com.securevault.app.biometric.BiometricAuthManager
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
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

        val credentialId = intent.getLongExtra(EXTRA_CREDENTIAL_ID, -1L)
        logger.d(TAG, "onCreate: credentialId=$credentialId")

        if (credentialId == -1L) {
            finishCanceled()
            return
        }

        biometricAuthManager.authenticate(
            activity = this,
            title = getString(R.string.autofill_auth_title),
            subtitle = getString(R.string.autofill_auth_subtitle),
            onSuccess = {
                logger.d(TAG, "Biometric auth succeeded")
                returnCredential(credentialId)
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

    private fun returnCredential(credentialId: Long) {
        lifecycleScope.launch {
            val credential = runCatching {
                credentialRepository.getById(credentialId).first()
            }.getOrNull()

            if (credential == null) {
                logger.w(TAG, "Credential not found id=$credentialId")
                finishCanceled()
                return@launch
            }

            logger.d(
                TAG,
                "Returning credential: service=${credential.serviceName}, user=${credential.username}"
            )

            val passwordCredential = PasswordCredential(
                credential.username,
                credential.password
            )
            val response = GetCredentialResponse(passwordCredential)

            val result = Intent()
            PendingIntentHandler.setGetCredentialResponse(result, response)
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    private fun finishCanceled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        biometricAuthManager.resetState()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CredProviderAuth"
        const val EXTRA_CREDENTIAL_ID = "credentialId"
    }
}
