package com.securevault.app.service.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.securevault.app.R
import com.securevault.app.biometric.BiometricAuthManager
import com.securevault.app.data.repository.CredentialRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Biometric gate used by Autofill datasets before sensitive values are released.
 */
@AndroidEntryPoint
class AutofillAuthActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var credentialRepository: CredentialRepository

    /**
     * Starts biometric authentication and returns a resolved Dataset on success.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val credentialId = intent.getLongExtra(EXTRA_CREDENTIAL_ID, INVALID_CREDENTIAL_ID)
        val usernameAutofillId = getAutofillIdExtra(EXTRA_USERNAME_AUTOFILL_ID)
        val passwordAutofillId = getAutofillIdExtra(EXTRA_PASSWORD_AUTOFILL_ID)

        if (credentialId == INVALID_CREDENTIAL_ID || (usernameAutofillId == null && passwordAutofillId == null)) {
            finishCanceled()
            return
        }

        biometricAuthManager.authenticate(
            activity = this,
            title = getString(R.string.autofill_auth_title),
            subtitle = getString(R.string.autofill_auth_subtitle),
            onSuccess = {
                completeWithCredential(
                    credentialId = credentialId,
                    usernameAutofillId = usernameAutofillId,
                    passwordAutofillId = passwordAutofillId
                )
            },
            onError = {
                finishCanceled()
            },
            onFallback = {
                Log.d(TAG, "Biometric unavailable, falling back to device credential")
            }
        )
    }

    private fun completeWithCredential(
        credentialId: Long,
        usernameAutofillId: AutofillId?,
        passwordAutofillId: AutofillId?
    ) {
        lifecycleScope.launch {
            val credential = runCatching {
                credentialRepository.getById(credentialId).first()
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to load credential id=$credentialId", throwable)
            }.getOrNull()

            if (credential == null) {
                finishCanceled()
                return@launch
            }

            val dataset = Dataset.Builder(
                RemoteViews(packageName, R.layout.autofill_suggestion_item).apply {
                    setTextViewText(R.id.service_name, credential.serviceName)
                    setTextViewText(R.id.username, credential.username)
                }
            ).apply {
                usernameAutofillId?.let { autofillId ->
                    setValue(autofillId, AutofillValue.forText(credential.username))
                }
                passwordAutofillId?.let { autofillId ->
                    setValue(autofillId, AutofillValue.forText(credential.password))
                }
            }.build()

            val resultIntent = Intent().apply {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun getAutofillIdExtra(key: String): AutofillId? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, AutofillId::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(key)
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
        private const val TAG = "AutofillAuthActivity"
        private const val INVALID_CREDENTIAL_ID = -1L

        const val EXTRA_CREDENTIAL_ID = "credentialId"
        const val EXTRA_USERNAME_AUTOFILL_ID = "usernameAutofillId"
        const val EXTRA_PASSWORD_AUTOFILL_ID = "passwordAutofillId"
    }
}
