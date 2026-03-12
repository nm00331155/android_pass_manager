package com.securevault.app.service.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
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

/**
 * Biometric gate used by Autofill datasets before sensitive values are released.
 */
@AndroidEntryPoint
class AutofillAuthActivity : FragmentActivity() {

    @Inject
    lateinit var biometricAuthManager: BiometricAuthManager

    @Inject
    lateinit var credentialRepository: CredentialRepository

    @Inject
    lateinit var logger: AppLogger

    /**
     * Starts biometric authentication and returns a resolved Dataset on success.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.d(TAG, "=== AutofillAuthActivity onCreate ===")

        val credentialId = intent.getLongExtra(EXTRA_CREDENTIAL_ID, INVALID_CREDENTIAL_ID)
        val usernameAutofillId = getAutofillIdExtra(EXTRA_USERNAME_AUTOFILL_ID)
        val passwordAutofillId = getAutofillIdExtra(EXTRA_PASSWORD_AUTOFILL_ID)
        val cardholderNameAutofillId = getAutofillIdExtra(EXTRA_CARDHOLDER_NAME_AUTOFILL_ID)
        val cardNumberAutofillId = getAutofillIdExtra(EXTRA_CARD_NUMBER_AUTOFILL_ID)
        val cardExpirationDateAutofillId = getAutofillIdExtra(EXTRA_CARD_EXPIRATION_DATE_AUTOFILL_ID)
        val cardExpirationMonthAutofillId = getAutofillIdExtra(EXTRA_CARD_EXPIRATION_MONTH_AUTOFILL_ID)
        val cardExpirationYearAutofillId = getAutofillIdExtra(EXTRA_CARD_EXPIRATION_YEAR_AUTOFILL_ID)
        val cardSecurityCodeAutofillId = getAutofillIdExtra(EXTRA_CARD_SECURITY_CODE_AUTOFILL_ID)
        val focusedAutofillId = getAutofillIdExtra(EXTRA_FOCUSED_AUTOFILL_ID)
        val focusedFillHint = intent.getStringExtra(EXTRA_FOCUSED_FILL_HINT)
        val targetPackageName = intent.getStringExtra(EXTRA_TARGET_PACKAGE_NAME).orEmpty()
        val targetWebDomain = intent.getStringExtra(EXTRA_TARGET_WEB_DOMAIN)
        val authResultMode = intent.getStringExtra(EXTRA_AUTH_RESULT_MODE) ?: AUTH_RESULT_DATASET

        logger.d(
            TAG,
            "AutofillAuthActivity args: credentialId=$credentialId, usernameAutofillId=$usernameAutofillId, passwordAutofillId=$passwordAutofillId, cardholderNameAutofillId=$cardholderNameAutofillId, cardNumberAutofillId=$cardNumberAutofillId, cardExpirationDateAutofillId=$cardExpirationDateAutofillId, cardExpirationMonthAutofillId=$cardExpirationMonthAutofillId, cardExpirationYearAutofillId=$cardExpirationYearAutofillId, cardSecurityCodeAutofillId=$cardSecurityCodeAutofillId, focusedAutofillId=$focusedAutofillId, focusedFillHint=$focusedFillHint, targetPackage=$targetPackageName, targetWebDomain=$targetWebDomain, authResultMode=$authResultMode"
        )

        if (
            credentialId == INVALID_CREDENTIAL_ID ||
            !hasAnyFillTarget(
                usernameAutofillId = usernameAutofillId,
                passwordAutofillId = passwordAutofillId,
                cardholderNameAutofillId = cardholderNameAutofillId,
                cardNumberAutofillId = cardNumberAutofillId,
                cardExpirationDateAutofillId = cardExpirationDateAutofillId,
                cardExpirationMonthAutofillId = cardExpirationMonthAutofillId,
                cardExpirationYearAutofillId = cardExpirationYearAutofillId,
                cardSecurityCodeAutofillId = cardSecurityCodeAutofillId,
                focusedAutofillId = focusedAutofillId
            )
        ) {
            logger.w(TAG, "Invalid intent extras for autofill auth")
            finishCanceled()
            return
        }

        biometricAuthManager.authenticate(
            activity = this,
            title = getString(R.string.autofill_auth_title),
            subtitle = getString(R.string.autofill_auth_subtitle),
            onSuccess = {
                logger.d(TAG, "Biometric auth SUCCESS")
                completeWithCredential(
                    credentialId = credentialId,
                    usernameAutofillId = usernameAutofillId,
                    passwordAutofillId = passwordAutofillId,
                    cardholderNameAutofillId = cardholderNameAutofillId,
                    cardNumberAutofillId = cardNumberAutofillId,
                    cardExpirationDateAutofillId = cardExpirationDateAutofillId,
                    cardExpirationMonthAutofillId = cardExpirationMonthAutofillId,
                    cardExpirationYearAutofillId = cardExpirationYearAutofillId,
                    cardSecurityCodeAutofillId = cardSecurityCodeAutofillId,
                    focusedAutofillId = focusedAutofillId,
                    focusedFillHint = focusedFillHint,
                    targetPackageName = targetPackageName,
                    targetWebDomain = targetWebDomain,
                    authResultMode = authResultMode
                )
            },
            onError = { errorMessage ->
                logger.w(TAG, "Biometric auth ERROR: $errorMessage")
                finishCanceled()
            },
            onFallback = {
                logger.d(TAG, "Biometric unavailable, falling back to device credential")
            }
        )
    }

    private fun completeWithCredential(
        credentialId: Long,
        usernameAutofillId: AutofillId?,
        passwordAutofillId: AutofillId?,
        cardholderNameAutofillId: AutofillId?,
        cardNumberAutofillId: AutofillId?,
        cardExpirationDateAutofillId: AutofillId?,
        cardExpirationMonthAutofillId: AutofillId?,
        cardExpirationYearAutofillId: AutofillId?,
        cardSecurityCodeAutofillId: AutofillId?,
        focusedAutofillId: AutofillId?,
        focusedFillHint: String?,
        targetPackageName: String,
        targetWebDomain: String?,
        authResultMode: String
    ) {
        lifecycleScope.launch {
            val credential = runCatching {
                credentialRepository.getById(credentialId).first()
            }.onFailure { throwable ->
                logger.e(TAG, "Failed to load credential id=$credentialId", throwable)
            }.getOrNull()

            if (credential == null) {
                logger.w(TAG, "Credential not found for id=$credentialId")
                finishCanceled()
                return@launch
            }

            maybeLearnTargetAssociation(
                credential = credential,
                targetPackageName = targetPackageName,
                targetWebDomain = targetWebDomain
            )

            logger.d(
                TAG,
                "Loaded credential: service=${credential.serviceName}, user=${credential.username}, hasPassword=${credential.hasPassword}, isPasskey=${credential.isPasskey}, isCard=${credential.isCard}"
            )

            val usernameTargetIds = buildList {
                usernameAutofillId?.let(::add)
                if (focusedFillHint == FOCUSED_FILL_HINT_USERNAME) {
                    focusedAutofillId?.takeIf { it != usernameAutofillId }?.let(::add)
                }
            }
            val passwordTargetIds = buildList {
                passwordAutofillId?.let(::add)
                if (focusedFillHint == FOCUSED_FILL_HINT_PASSWORD) {
                    focusedAutofillId?.takeIf { it != passwordAutofillId }?.let(::add)
                }
            }

            val datasetBuilder = if (authResultMode == AUTH_RESULT_FILL_RESPONSE) {
                Dataset.Builder()
            } else {
                Dataset.Builder(
                    RemoteViews(packageName, R.layout.autofill_suggestion_item).apply {
                        setTextViewText(R.id.service_name, credential.serviceName)
                        setTextViewText(R.id.username, formatCredentialPresentationSubtitle(credential))
                    }
                )
            }

            val dataset = datasetBuilder.apply {
                if (credential.username.isNotBlank()) {
                    usernameTargetIds.forEach { autofillId ->
                        setValue(autofillId, AutofillValue.forText(credential.username))
                        logger.d(TAG, "Set username for autofillId=$autofillId")
                    }
                }
                credential.password?.takeIf { it.isNotBlank() }?.let { password ->
                    passwordTargetIds.forEach { autofillId ->
                        setValue(autofillId, AutofillValue.forText(password))
                        logger.d(TAG, "Set password for autofillId=$autofillId")
                    }
                }

                credential.cardData?.let { cardData ->
                    cardholderNameAutofillId?.let { autofillId ->
                        cardData.cardholderName?.takeIf { it.isNotBlank() }?.let { cardholderName ->
                            setValue(autofillId, AutofillValue.forText(cardholderName))
                            logger.d(TAG, "Set cardholder name for autofillId=$autofillId")
                        }
                    }
                    cardNumberAutofillId?.let { autofillId ->
                        setValue(autofillId, AutofillValue.forText(cardData.normalizedCardNumber))
                        logger.d(TAG, "Set card number for autofillId=$autofillId")
                    }
                    cardExpirationDateAutofillId?.let { autofillId ->
                        cardData.shortExpiration?.let { expiration ->
                            setValue(autofillId, AutofillValue.forText(expiration))
                            logger.d(TAG, "Set card expiration date for autofillId=$autofillId")
                        }
                    }
                    cardExpirationMonthAutofillId?.let { autofillId ->
                        cardData.expirationMonth?.let { month ->
                            setValue(autofillId, AutofillValue.forText("%02d".format(month)))
                            logger.d(TAG, "Set card expiration month for autofillId=$autofillId")
                        }
                    }
                    cardExpirationYearAutofillId?.let { autofillId ->
                        cardData.expirationYear?.let { year ->
                            setValue(autofillId, AutofillValue.forText(year.toString()))
                            logger.d(TAG, "Set card expiration year for autofillId=$autofillId")
                        }
                    }
                    cardSecurityCodeAutofillId?.let { autofillId ->
                        cardData.securityCode?.takeIf { it.isNotBlank() }?.let { securityCode ->
                            setValue(autofillId, AutofillValue.forText(securityCode))
                            logger.d(TAG, "Set card security code for autofillId=$autofillId")
                        }
                    }
                }
            }.build()

            val authResult = if (authResultMode == AUTH_RESULT_FILL_RESPONSE) {
                FillResponse.Builder()
                    .addDataset(dataset)
                    .build()
            } else {
                dataset
            }

            val resultIntent = Intent().apply {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, authResult)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            logger.d(TAG, "=== AutofillAuthActivity returning RESULT_OK mode=$authResultMode ===")
            finish()
        }
    }

    private fun formatCredentialPresentationSubtitle(
        credential: com.securevault.app.data.repository.model.Credential
    ): String {
        return when {
            credential.isCard -> credential.listSubtitle
            credential.credentialType == com.securevault.app.data.repository.model.CredentialType.ID_ONLY -> {
                getString(R.string.autofill_id_only_subtitle, credential.username)
            }
            credential.hasPassword && credential.username.isBlank() -> {
                getString(R.string.autofill_password_only_subtitle)
            }
            else -> credential.listSubtitle
        }
    }

    private suspend fun maybeLearnTargetAssociation(
        credential: com.securevault.app.data.repository.model.Credential,
        targetPackageName: String,
        targetWebDomain: String?
    ) {
        val normalizedPackage = targetPackageName.trim()
        val normalizedWebDomain = normalizeWebDomain(targetWebDomain)
        val packageToSave = normalizedPackage.takeIf {
            it.isNotBlank() && it !in CHROMIUM_BROWSER_PACKAGES && it != packageName
        }
        val shouldUpdatePackage = credential.packageName.isNullOrBlank() && !packageToSave.isNullOrBlank()
        val shouldUpdateDomain = credential.serviceUrl.isNullOrBlank() && !normalizedWebDomain.isNullOrBlank()

        if (!shouldUpdatePackage && !shouldUpdateDomain) {
            return
        }

        val updatedCredential = credential.copy(
            packageName = credential.packageName ?: packageToSave,
            serviceUrl = credential.serviceUrl ?: normalizedWebDomain
        )
        runCatching {
            credentialRepository.save(updatedCredential)
        }.onSuccess {
            logger.d(
                TAG,
                "Updated credential association id=${credential.id}, package=${updatedCredential.packageName}, domain=${updatedCredential.serviceUrl}"
            )
        }.onFailure { throwable ->
            logger.w(TAG, "Failed to update credential association id=${credential.id}", throwable)
        }
    }

    private fun normalizeWebDomain(webDomain: String?): String? {
        val trimmed = webDomain?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        val withoutScheme = trimmed.substringAfter("://", trimmed)
        return withoutScheme.substringBefore('/').substringBefore(':').ifBlank { null }
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
        logger.d(TAG, "Returning RESULT_CANCELED")
        finish()
    }

    private fun hasAnyFillTarget(
        usernameAutofillId: AutofillId?,
        passwordAutofillId: AutofillId?,
        cardholderNameAutofillId: AutofillId?,
        cardNumberAutofillId: AutofillId?,
        cardExpirationDateAutofillId: AutofillId?,
        cardExpirationMonthAutofillId: AutofillId?,
        cardExpirationYearAutofillId: AutofillId?,
        cardSecurityCodeAutofillId: AutofillId?,
        focusedAutofillId: AutofillId?
    ): Boolean {
        return usernameAutofillId != null ||
            passwordAutofillId != null ||
            cardholderNameAutofillId != null ||
            cardNumberAutofillId != null ||
            cardExpirationDateAutofillId != null ||
            cardExpirationMonthAutofillId != null ||
            cardExpirationYearAutofillId != null ||
            cardSecurityCodeAutofillId != null ||
            focusedAutofillId != null
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
        const val EXTRA_CARDHOLDER_NAME_AUTOFILL_ID = "cardholderNameAutofillId"
        const val EXTRA_CARD_NUMBER_AUTOFILL_ID = "cardNumberAutofillId"
        const val EXTRA_CARD_EXPIRATION_DATE_AUTOFILL_ID = "cardExpirationDateAutofillId"
        const val EXTRA_CARD_EXPIRATION_MONTH_AUTOFILL_ID = "cardExpirationMonthAutofillId"
        const val EXTRA_CARD_EXPIRATION_YEAR_AUTOFILL_ID = "cardExpirationYearAutofillId"
        const val EXTRA_CARD_SECURITY_CODE_AUTOFILL_ID = "cardSecurityCodeAutofillId"
        const val EXTRA_FOCUSED_AUTOFILL_ID = "focusedAutofillId"
        const val EXTRA_FOCUSED_FILL_HINT = "focusedFillHint"
        const val EXTRA_TARGET_PACKAGE_NAME = "targetPackageName"
        const val EXTRA_TARGET_WEB_DOMAIN = "targetWebDomain"
        const val EXTRA_AUTH_RESULT_MODE = "authResultMode"

        const val AUTH_RESULT_DATASET = "dataset"
        const val AUTH_RESULT_FILL_RESPONSE = "fillResponse"
        const val FOCUSED_FILL_HINT_USERNAME = "username"
        const val FOCUSED_FILL_HINT_PASSWORD = "password"

        val CHROMIUM_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.vivaldi.browser"
        )
    }
}
