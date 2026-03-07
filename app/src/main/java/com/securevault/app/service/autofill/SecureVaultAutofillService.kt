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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自動入力データセットの解決と保存を行う AutofillService 実装。
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
    private var currentFillJob: Job? = null

    override fun onConnected() {
        createNotificationChannel()
        logger.i(TAG, "Autofill service connected")
    }

    override fun onDisconnected() {
        logger.i(TAG, "Autofill service disconnected")
    }

    override fun onDestroy() {
        currentFillJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        logger.d(TAG, "=== onFillRequest START ===")

        // 新しいリクエストが来たら前回ジョブをキャンセルする。
        currentFillJob?.cancel()

        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            logger.w(TAG, "onFillRequest: structure is null")
            callback.onSuccess(null)
            return
        }

        val targetPackageName = structure.activityComponent?.packageName.orEmpty()
        logger.d(TAG, "onFillRequest: targetPackage=$targetPackageName")

        if (targetPackageName in EXCLUDED_PACKAGES || targetPackageName == packageName) {
            logger.d(TAG, "onFillRequest: skipping excluded package=$targetPackageName")
            callback.onSuccess(null)
            return
        }

        val targets = smartFieldDetector.detect(structure)
        logger.d(
            TAG,
            "onFillRequest: detected usernameId=${targets.usernameId}, passwordId=${targets.passwordId}, otpId=${targets.otpId}, webDomain=${targets.webDomain}"
        )

        if (targets.usernameId == null && targets.passwordId == null && targets.otpId == null) {
            logger.d(TAG, "onFillRequest: no fields detected")
            callback.onSuccess(null)
            return
        }

        val job = serviceScope.launch {
            try {
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
                        logger.e(TAG, "Failed to resolve autofill credentials", throwable)
                        emptyList()
                    }
                } else {
                    emptyList()
                }

                logger.d(TAG, "onFillRequest: resolved ${credentials.size} credentials")
                credentials.forEachIndexed { index, credential ->
                    logger.d(
                        TAG,
                        "onFillRequest: credential[$index] id=${credential.id}, service=${credential.serviceName}, user=${credential.username}"
                    )
                }

                val response = buildFillResponse(targets, credentials)
                logger.d(TAG, "onFillRequest: responseBuilt=${response != null}")

                withContext(Dispatchers.Main) {
                    callback.onSuccess(response)
                }
                logger.d(TAG, "=== onFillRequest END (success) ===")
            } catch (throwable: Throwable) {
                logger.e(TAG, "onFillRequest: exception", throwable)
                withContext(Dispatchers.Main) {
                    callback.onSuccess(null)
                }
            }
        }

        currentFillJob = job

        cancellationSignal.setOnCancelListener {
            logger.d(TAG, "onFillRequest: cancelled")
            job.cancel()
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        logger.d(TAG, "=== onSaveRequest START ===")

        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            logger.w(TAG, "onSaveRequest: structure is null")
            callback.onSuccess()
            return
        }

        val targetPackageName = structure.activityComponent?.packageName.orEmpty()
        if (targetPackageName in EXCLUDED_PACKAGES || targetPackageName == packageName) {
            logger.d(TAG, "onSaveRequest: skipping excluded package=$targetPackageName")
            callback.onSuccess()
            return
        }

        serviceScope.launch {
            runCatching {
                val savedData = extractSavedValues(structure)
                logger.d(
                    TAG,
                    "onSaveRequest: extracted username=${savedData?.username}, hasPassword=${!savedData?.password.isNullOrBlank()}, package=${savedData?.packageName}, domain=${savedData?.webDomain}"
                )
                if (savedData == null || savedData.password.isNullOrBlank()) {
                    logger.d(TAG, "onSaveRequest: no usable credential data")
                    withContext(Dispatchers.Main) {
                        callback.onSuccess()
                    }
                    return@launch
                }

                val serviceName = inferServiceName(savedData.packageName, savedData.webDomain)
                logger.d(TAG, "onSaveRequest: saving service=$serviceName")
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
                logger.d(TAG, "=== onSaveRequest END (saved) ===")

                withContext(Dispatchers.Main) {
                    callback.onSuccess()
                }
            }.onFailure { throwable ->
                logger.e(TAG, "onSaveRequest: failed", throwable)
                withContext(Dispatchers.Main) {
                    callback.onFailure(SAVE_FAILURE_MESSAGE)
                }
            }
        }
    }

    private suspend fun resolveCredentials(packageName: String, webDomain: String?): List<Credential> {
        logger.d(TAG, "resolveCredentials: package=$packageName, webDomain=$webDomain")

        if (packageName.isNotBlank()) {
            val fromPackage = credentialRepository.findByPackageName(packageName)
            logger.d(TAG, "resolveCredentials: fromPackage=${fromPackage.size}")
            if (fromPackage.isNotEmpty()) {
                return fromPackage.take(MAX_DATASET_ITEMS)
            }
        }

        if (!webDomain.isNullOrBlank()) {
            val fromUrl = credentialRepository.findByUrl(webDomain)
            logger.d(TAG, "resolveCredentials: fromUrl=${fromUrl.size} domain=$webDomain")
            if (fromUrl.isNotEmpty()) {
                return fromUrl.take(MAX_DATASET_ITEMS)
            }

            val fromServiceSearch = credentialRepository.search(webDomain).first()
            logger.d(TAG, "resolveCredentials: fromServiceSearch=${fromServiceSearch.size}")
            if (fromServiceSearch.isNotEmpty()) {
                return fromServiceSearch.take(MAX_DATASET_ITEMS)
            }

            val mainDomain = extractMainDomain(webDomain)
            if (!mainDomain.isNullOrBlank() && mainDomain != webDomain.lowercase()) {
                val fromDomainSearch = credentialRepository.search(mainDomain).first()
                logger.d(
                    TAG,
                    "resolveCredentials: fromDomainSearch=${fromDomainSearch.size} mainDomain=$mainDomain"
                )
                if (fromDomainSearch.isNotEmpty()) {
                    return fromDomainSearch.take(MAX_DATASET_ITEMS)
                }
            }
        }

        logger.d(TAG, "resolveCredentials: no match found")
        return emptyList()
    }

    /**
     * ドメインからメイン識別子を抽出する。
     * 例: id.kuronekoyamato.co.jp -> kuronekoyamato
     */
    private fun extractMainDomain(domain: String): String? {
        val parts = domain.lowercase()
            .split(".")
            .filter { it.isNotBlank() }
        if (parts.size < 2) {
            return null
        }

        val twoLevelTlds = setOf(
            "co.jp",
            "or.jp",
            "ne.jp",
            "ac.jp",
            "go.jp",
            "co.uk",
            "org.uk",
            "com.au",
            "co.kr",
            "com.br",
            "com.cn"
        )
        val twoLevelSuffix = "${parts[parts.size - 2]}.${parts.last()}"
        val suffix = if (twoLevelSuffix in twoLevelTlds) twoLevelSuffix else parts.last()
        val suffixParts = suffix.split(".").size
        val mainIndex = parts.size - suffixParts - 1
        return parts.getOrNull(mainIndex)
    }

    private fun buildFillResponse(
        targets: SmartFieldDetector.DetectedFields,
        credentials: List<Credential>
    ): FillResponse? {
        val saveInfo = createSaveInfo(targets.usernameId, targets.passwordId)
        if (credentials.isEmpty() && saveInfo == null && targets.otpId == null) {
            logger.d(TAG, "buildFillResponse: no dataset and no saveInfo")
            return null
        }

        var hasDataset = false
        val responseBuilder = FillResponse.Builder()

        credentials.forEach { credential ->
            try {
                val presentation = createPresentation(credential, locked = false)

                val datasetBuilder = Dataset.Builder(presentation)
                var hasValue = false

                targets.usernameId?.let { id ->
                    datasetBuilder.setValue(id, AutofillValue.forText(credential.username))
                    hasValue = true
                    logger.d(TAG, "buildFillResponse: set username=${credential.username} for id=$id")
                }
                targets.passwordId?.let { id ->
                    datasetBuilder.setValue(id, AutofillValue.forText(credential.password))
                    hasValue = true
                    logger.d(TAG, "buildFillResponse: set password for id=$id")
                }

                if (hasValue) {
                    responseBuilder.addDataset(datasetBuilder.build())
                    hasDataset = true
                    logger.d(TAG, "buildFillResponse: added dataset id=${credential.id}")
                }
            } catch (e: Exception) {
                logger.e(TAG, "buildFillResponse: EXCEPTION building dataset", e)
            }
        }

        targets.otpId?.let { otpId ->
            try {
                val otpPresentation = createOtpPresentation()
                val otpIntent = createOtpAuthenticationPendingIntent(otpId)
                val otpDatasetBuilder = Dataset.Builder(otpPresentation)
                otpDatasetBuilder.setAuthentication(otpIntent.intentSender)
                otpDatasetBuilder.setValue(otpId, AutofillValue.forText(""))
                responseBuilder.addDataset(otpDatasetBuilder.build())
                hasDataset = true
                logger.d(TAG, "buildFillResponse: added otp dataset with auth")
            } catch (e: Exception) {
                logger.e(TAG, "buildFillResponse: EXCEPTION building OTP dataset", e)
            }
        }

        val result = if (hasDataset) {
            saveInfo?.let { responseBuilder.setSaveInfo(it) }
            responseBuilder.build()
        } else if (saveInfo != null) {
            responseBuilder.setSaveInfo(saveInfo)
            responseBuilder.build()
        } else {
            null
        }
        logger.d(
            TAG,
            "buildFillResponse: final result=${if (result != null) "FillResponse" else "null"}, hasDataset=$hasDataset, hasSaveInfo=${saveInfo != null}"
        )
        return result
    }

    private fun createPresentation(credential: Credential, locked: Boolean): RemoteViews {
        return RemoteViews(packageName, R.layout.autofill_suggestion_item).apply {
            val prefix = if (locked) LOCKED_LABEL_PREFIX else ""
            setTextViewText(R.id.service_name, "$prefix${credential.serviceName}")
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
                        logger.d(TAG, "extractSavedValues: captured username")
                    }
                }

                if (password.isNullOrBlank()) {
                    val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.passwordId
                    if (byDetectedId || isLikelyPasswordField(node)) {
                        password = textValue
                        logger.d(TAG, "extractSavedValues: captured password")
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
            logger.d(TAG, "extractSavedValues: no username/password found")
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
        const val PLACEHOLDER_PASSWORD = "••••••••"
        const val LOCKED_LABEL_PREFIX = "\uD83D\uDD12 "

        /** Autofill の対象から除外するパッケージ */
        val EXCLUDED_PACKAGES = setOf(
            APP_PACKAGE_NAME,
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "com.agilebits.onepassword",
            "com.lastpass.lpandroid",
            "keepass2android.keepass2android",
            "keepass2android.keepass2android_nonet",
            "com.kunzisoft.keepass.free",
            "com.kunzisoft.keepass.pro",
            "com.dashlane",
            "com.nordpass.android.app.password.manager",
            "com.enpass.android",
            "com.roboform.android",
            "org.nicbit.robofill",
            "com.android.settings"
        )
    }
}
