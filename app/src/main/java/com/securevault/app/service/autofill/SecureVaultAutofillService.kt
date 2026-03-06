package com.securevault.app.service.autofill

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.securevault.app.R
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.service.otp.SmsOtpActivity
import com.securevault.app.service.otp.SmsOtpManager
import com.securevault.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Autofill service implementation that resolves credentials from local storage.
 */
@AndroidEntryPoint
class SecureVaultAutofillService : AutofillService() {

    @Inject
    lateinit var credentialRepository: CredentialRepository

    @Inject
    lateinit var smartFieldDetector: SmartFieldDetector

    @Inject
    lateinit var smsOtpManager: SmsOtpManager

    @Inject
    lateinit var logger: AppLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called when Android binds to this AutofillService.
     */
    override fun onConnected() {
        createNotificationChannel()
        logger.i(TAG, "Autofill service connected")
    }

    /**
     * Called when Android unbinds this AutofillService.
     */
    override fun onDisconnected() {
        logger.i(TAG, "Autofill service disconnected")
    }

    /**
     * Cancels service-scoped coroutines.
     */
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Provides autofill datasets for detected login fields.
     */
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }

        val targetPackageName = structure.activityComponent?.packageName.orEmpty()
        if (targetPackageName == APP_PACKAGE_NAME || targetPackageName == packageName) {
            callback.onSuccess(null)
            return
        }

        val targets = smartFieldDetector.detect(structure)

        if (targets.usernameId == null && targets.passwordId == null && targets.otpId == null) {
            callback.onSuccess(null)
            return
        }

        logger.d(
            TAG,
            "onFillRequest: pkg=$targetPackageName, username=${targets.usernameId}, password=${targets.passwordId}, otp=${targets.otpId}"
        )

        val job = serviceScope.launch {
            if (targets.otpId != null) {
                runCatching {
                    val status = smsOtpManager.startListening()
                    logger.d(TAG, "SmsOtpManager start status=$status")
                }.onFailure { throwable ->
                    logger.w(TAG, "Failed to start SMS OTP listener", throwable)
                }
            }

            val credentials = if (targets.usernameId != null || targets.passwordId != null) {
                runCatching {
                    resolveCredentials(targetPackageName, targets.webDomain)
                }.getOrElse { throwable ->
                    logger.w(TAG, "Failed to resolve autofill credentials", throwable)
                    emptyList()
                }
            } else {
                emptyList()
            }

            val response = buildFillResponse(targets, credentials)
            withContext(Dispatchers.Main) {
                callback.onSuccess(response)
            }
        }

        cancellationSignal.setOnCancelListener {
            job.cancel()
        }
    }

    /**
     * Saves newly entered credentials when the host app requests save.
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess()
            return
        }

        serviceScope.launch {
            runCatching {
                val savedData = extractSavedValues(structure)
                if (savedData == null || savedData.password.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        callback.onSuccess()
                    }
                    return@launch
                }

                val serviceName = inferServiceName(savedData.packageName, savedData.webDomain)
                credentialRepository.save(
                    Credential(
                        serviceName = serviceName,
                        serviceUrl = savedData.webDomain,
                        packageName = savedData.packageName.takeIf { it != UNKNOWN_PACKAGE },
                        username = savedData.username.orEmpty(),
                        password = savedData.password,
                        category = DEFAULT_SAVE_CATEGORY
                    )
                )
                showSaveNotification(serviceName)

                withContext(Dispatchers.Main) {
                    callback.onSuccess()
                }
            }.onFailure { throwable ->
                logger.w(TAG, "Failed to save autofill credential", throwable)
                withContext(Dispatchers.Main) {
                    callback.onFailure(SAVE_FAILURE_MESSAGE)
                }
            }
        }
    }

    private suspend fun resolveCredentials(packageName: String, webDomain: String?): List<Credential> {
        val fromPackage = if (packageName.isNotBlank()) {
            credentialRepository.findByPackageName(packageName)
        } else {
            emptyList()
        }
        val fromUrl = if (!webDomain.isNullOrBlank()) {
            credentialRepository.findByUrl(webDomain)
        } else {
            emptyList()
        }
        logger.d(TAG, "resolveCredentials: fromPkg=${fromPackage.size}, fromUrl=${fromUrl.size}")

        if (fromPackage.isNotEmpty()) {
            return fromPackage.take(MAX_DATASET_ITEMS)
        }

        if (fromUrl.isNotEmpty()) {
            return fromUrl.take(MAX_DATASET_ITEMS)
        }

        return credentialRepository.getAll().first().take(MAX_DATASET_ITEMS)
    }

    private fun buildFillResponse(
        targets: SmartFieldDetector.DetectedFields,
        credentials: List<Credential>
    ): FillResponse? {
        val saveInfo = createSaveInfo(targets.usernameId, targets.passwordId)
        if (credentials.isEmpty() && saveInfo == null) {
            return null
        }

        var hasDataset = false
        val responseBuilder = FillResponse.Builder()
        saveInfo?.let { responseBuilder.setSaveInfo(it) }

        credentials.forEach { credential ->
            val presentation = createPresentation(credential)
            val datasetBuilder = Dataset.Builder(presentation)
            var hasValue = false

            targets.usernameId?.let { usernameId ->
                datasetBuilder.setValue(usernameId, AutofillValue.forText(""))
                hasValue = true
            }

            targets.passwordId?.let { passwordId ->
                datasetBuilder.setValue(passwordId, AutofillValue.forText(""))
                hasValue = true
            }

            if (hasValue) {
                val authenticationIntent = createAuthenticationPendingIntent(
                    credentialId = credential.id,
                    usernameId = targets.usernameId,
                    passwordId = targets.passwordId
                )
                datasetBuilder.setAuthentication(authenticationIntent.intentSender)
                responseBuilder.addDataset(datasetBuilder.build())
                hasDataset = true
            }
        }

        targets.otpId?.let { otpId ->
            val otpDatasetBuilder = Dataset.Builder(createOtpPresentation())
            otpDatasetBuilder.setValue(otpId, AutofillValue.forText(""))

            val otpAuthIntent = createOtpAuthenticationPendingIntent(otpId)
            otpDatasetBuilder.setAuthentication(otpAuthIntent.intentSender)

            responseBuilder.addDataset(otpDatasetBuilder.build())
            hasDataset = true
        }

        return if (hasDataset || saveInfo != null) responseBuilder.build() else null
    }

    private fun createPresentation(credential: Credential): RemoteViews {
        return RemoteViews(packageName, R.layout.autofill_suggestion_item).apply {
            setTextViewText(R.id.service_name, credential.serviceName)
            setTextViewText(R.id.username, credential.username)
        }
    }

    private fun createOtpPresentation(): RemoteViews {
        return RemoteViews(packageName, R.layout.autofill_otp_item).apply {
            setTextViewText(R.id.label, getString(R.string.otp_sms_suggestion))
        }
    }

    private fun createAuthenticationPendingIntent(
        credentialId: Long,
        usernameId: AutofillId?,
        passwordId: AutofillId?
    ): PendingIntent {
        val intent = Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillAuthActivity.EXTRA_CREDENTIAL_ID, credentialId)
            usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_AUTOFILL_ID, it) }
            passwordId?.let { putExtra(AutofillAuthActivity.EXTRA_PASSWORD_AUTOFILL_ID, it) }
        }

        return PendingIntent.getActivity(
            this,
            credentialId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createOtpAuthenticationPendingIntent(otpId: AutofillId): PendingIntent {
        val intent = Intent(this, SmsOtpActivity::class.java).apply {
            putExtra(SmsOtpActivity.EXTRA_OTP_AUTOFILL_ID, otpId)
        }

        return PendingIntent.getActivity(
            this,
            OTP_REQUEST_CODE_BASE + abs(otpId.hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createSaveInfo(usernameId: AutofillId?, passwordId: AutofillId?): SaveInfo? {
        val requiredIds = listOfNotNull(usernameId, passwordId)
        if (requiredIds.isEmpty()) {
            return null
        }

        var saveDataType = 0
        if (usernameId != null) {
            saveDataType = saveDataType or SaveInfo.SAVE_DATA_TYPE_USERNAME
        }
        if (passwordId != null) {
            saveDataType = saveDataType or SaveInfo.SAVE_DATA_TYPE_PASSWORD
        }

        return SaveInfo.Builder(saveDataType, requiredIds.toTypedArray())
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            .build()
    }

    private fun extractSavedValues(structure: AssistStructure): SavedCredentialData? {
        val detectedFields = smartFieldDetector.detect(structure)
        var username: String? = null
        var password: String? = null
        var packageName = structure.activityComponent?.packageName.orEmpty()
        var webDomain = detectedFields.webDomain

        fun traverse(node: AssistStructure.ViewNode) {
            if (packageName.isBlank()) {
                val packageFromNode = node.idPackage?.trim().orEmpty()
                if (packageFromNode.isNotBlank()) {
                    packageName = packageFromNode
                }
            }

            if (webDomain.isNullOrBlank()) {
                val domain = node.webDomain?.toString()?.trim()
                if (!domain.isNullOrEmpty()) {
                    webDomain = domain
                }
            }

            val textValue = node.autofillValue
                ?.takeIf { it.isText }
                ?.textValue
                ?.toString()
                ?.trim()

            if (!textValue.isNullOrEmpty()) {
                val nodeAutofillId = node.autofillId
                if (username.isNullOrBlank()) {
                    val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.usernameId
                    if (byDetectedId || isLikelyUsernameField(node)) {
                        username = textValue
                    }
                }

                if (password.isNullOrBlank()) {
                    val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.passwordId
                    if (byDetectedId || isLikelyPasswordField(node)) {
                        password = textValue
                    }
                }
            }

            for (index in 0 until node.childCount) {
                traverse(node.getChildAt(index))
            }
        }

        for (windowIndex in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(windowIndex).rootViewNode
            traverse(root)
        }

        if (username.isNullOrBlank() && password.isNullOrBlank()) {
            return null
        }

        return SavedCredentialData(
            username = username,
            password = password,
            packageName = packageName.ifBlank { UNKNOWN_PACKAGE },
            webDomain = webDomain
        )
    }

    private fun isLikelyUsernameField(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints?.joinToString(separator = " ") { it.lowercase() }.orEmpty()
        val idEntry = node.idEntry?.lowercase().orEmpty()
        val hintText = node.hint?.lowercase().orEmpty()
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION

        return hints.contains("user") ||
            hints.contains("email") ||
            hints.contains("login") ||
            idEntry.contains("user") ||
            idEntry.contains("mail") ||
            hintText.contains("user") ||
            hintText.contains("mail") ||
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    }

    private fun isLikelyPasswordField(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints?.joinToString(separator = " ") { it.lowercase() }.orEmpty()
        val idEntry = node.idEntry?.lowercase().orEmpty()
        val hintText = node.hint?.lowercase().orEmpty()
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION

        return hints.contains("password") ||
            hints.contains("pass") ||
            idEntry.contains("password") ||
            idEntry.contains("pass") ||
            hintText.contains("password") ||
            hintText.contains("pass") ||
            hintText.contains("暗証") ||
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun inferServiceName(packageName: String, webDomain: String?): String {
        return when {
            !webDomain.isNullOrBlank() -> webDomain
            packageName.contains('.') -> packageName.substringAfterLast('.')
            packageName.isNotBlank() -> packageName
            else -> DEFAULT_SERVICE_NAME
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(AUTOFILL_SAVE_CHANNEL_ID) != null) {
            return
        }

        val channel = NotificationChannel(
            AUTOFILL_SAVE_CHANNEL_ID,
            getString(R.string.autofill_save_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.autofill_save_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun showSaveNotification(serviceName: String) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            logger.d(TAG, "Skip save notification: POST_NOTIFICATIONS not granted")
            return
        }

        val notification = NotificationCompat.Builder(this, AUTOFILL_SAVE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.autofill_save_notification_title))
            .setContentText(getString(R.string.autofill_save_notification_message, serviceName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(
                SAVE_NOTIFICATION_BASE_ID + abs(serviceName.hashCode()),
                notification
            )
        }.onFailure { throwable ->
            logger.w(TAG, "Failed to show save notification", throwable)
        }
    }

    private data class SavedCredentialData(
        val username: String?,
        val password: String?,
        val packageName: String,
        val webDomain: String?
    )

    private companion object {
        const val TAG = "SecureVaultAutofill"
        const val APP_PACKAGE_NAME = "com.securevault.app"
        const val MAX_DATASET_ITEMS = 8
        const val UNKNOWN_PACKAGE = "unknown"
        const val DEFAULT_SERVICE_NAME = "Saved Login"
        const val DEFAULT_SAVE_CATEGORY = "login"
        const val SAVE_FAILURE_MESSAGE = "保存に失敗しました"
        const val AUTOFILL_SAVE_CHANNEL_ID = "autofill_save"
        const val SAVE_NOTIFICATION_BASE_ID = 12000
        const val OTP_REQUEST_CODE_BASE = 20000
    }
}
