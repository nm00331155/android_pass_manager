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
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.securevault.app.R
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.service.otp.SmsOtpActivity
import com.securevault.app.service.otp.SmsOtpManager
import com.securevault.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    override fun onConnected() {
        createNotificationChannel()
        logger.i(TAG, "Autofill service connected")
    }

    override fun onDisconnected() {
        logger.i(TAG, "Autofill service disconnected")
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        logger.d(TAG, "=== onFillRequest START ===")
        logger.d(TAG, "onFillRequest: cancellationSignalCanceled=${cancellationSignal.isCanceled}")

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

        val focusedId = request.fillContexts.lastOrNull()?.focusedId
        val targets = smartFieldDetector.detect(structure, focusedId)
        // Chromium 137+ のブラウザ設定案内を初回のみ表示する。
        showBrowserSettingsGuideIfNeeded(targetPackageName)
        logger.d(
            TAG,
            "onFillRequest: detected focusedId=${targets.focusedId}, focusedSupportsTrigger=${targets.focusedNodeInfo?.supportsAutofillTrigger}, usernameId=${targets.usernameId}, passwordId=${targets.passwordId}, otpId=${targets.otpId}, webDomain=${targets.webDomain}, pageTitle=${targets.pageTitle}"
        )

        if (targets.usernameId == null && targets.passwordId == null && targets.otpId == null) {
            logger.d(TAG, "onFillRequest: no fields detected")
            callback.onSuccess(null)
            return
        }

        val inlineRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request.inlineSuggestionsRequest
        } else {
            null
        }

        try {
            if (targets.otpId != null) {
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        smsOtpManager.startListening()
                    }
                }.onFailure { throwable ->
                    logger.w(TAG, "Failed to start SMS OTP listener", throwable)
                }
            }

            val credentials = if (targets.usernameId != null || targets.passwordId != null) {
                runBlocking(Dispatchers.IO) {
                    runCatching {
                        filterAutofillableCredentials(
                            resolveCredentials(targetPackageName, targets.webDomain),
                            targets
                        ).ifEmpty {
                                if (!targets.pageTitle.isNullOrBlank()) {
                                    filterAutofillableCredentials(
                                        resolveCredentials(
                                            targetPackageName,
                                            targets.webDomain,
                                            targets.pageTitle
                                        ),
                                        targets
                                    )
                                } else {
                                    emptyList()
                                }
                            }
                    }.getOrElse { throwable ->
                        logger.e(TAG, "Failed to resolve autofill credentials", throwable)
                        emptyList()
                    }
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

            val response = buildFillResponse(targetPackageName, targets, credentials, inlineRequest)
            logger.d(TAG, "onFillRequest: responseBuilt=${response != null}")

            callback.onSuccess(response)
            logger.d(TAG, "=== onFillRequest END (success) ===")
        } catch (throwable: Throwable) {
            logger.e(TAG, "onFillRequest: exception", throwable)
            callback.onSuccess(null)
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
                if (savedData == null || savedData.username.isNullOrBlank()) {
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
                        username = savedData.username,
                        password = savedData.password?.takeIf { it.isNotBlank() },
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

    private suspend fun resolveCredentials(
        packageName: String,
        webDomain: String?,
        fallbackLabel: String? = null
    ): List<Credential> {
        val normalizedWebDomain = normalizeWebDomain(webDomain)
        val resolvedAppLabel = fallbackLabel ?: resolveApplicationLabel(packageName)
        val appSearchTokens = buildAppSearchTokens(packageName, resolvedAppLabel)
        val appLabel = appSearchTokens.firstOrNull() ?: resolvedAppLabel
        logger.d(
            TAG,
            "resolveCredentials: package=$packageName, webDomain=$normalizedWebDomain, appLabel=$appLabel, appSearchTokens=$appSearchTokens"
        )

        if (packageName in CHROMIUM_BROWSER_PACKAGES && normalizedWebDomain.isNullOrBlank() && appLabel.isNullOrBlank()) {
            logger.d(TAG, "resolveCredentials: browser request without domain or page title, skipping suggestions")
            return emptyList()
        }

        // 1. パッケージ名で完全一致検索
        if (packageName.isNotBlank()) {
            val fromPackage = credentialRepository.findByPackageName(packageName)
            logger.d(TAG, "resolveCredentials: fromPackage=${fromPackage.size}")
            if (fromPackage.isNotEmpty()) {
                return fromPackage.take(MAX_DATASET_ITEMS)
            }
        }

        // 2. webDomain があれば URL 部分一致で検索
        if (!normalizedWebDomain.isNullOrBlank()) {
            val fromUrl = credentialRepository.findByUrl(normalizedWebDomain)
            logger.d(TAG, "resolveCredentials: fromUrl=${fromUrl.size} domain=$normalizedWebDomain")
            if (fromUrl.isNotEmpty()) {
                return fromUrl.take(MAX_DATASET_ITEMS)
            }

            // 3. serviceName / serviceUrl 横断でドメイン部分一致検索
            val fromDomain = credentialRepository.findByDomain(normalizedWebDomain)
            logger.d(TAG, "resolveCredentials: fromDomain=${fromDomain.size} domain=$normalizedWebDomain")
            if (fromDomain.isNotEmpty()) {
                return fromDomain.take(MAX_DATASET_ITEMS)
            }

            // 4. メインドメインで検索
            val mainDomain = extractMainDomain(normalizedWebDomain)
            if (!mainDomain.isNullOrBlank() && mainDomain != normalizedWebDomain.lowercase()) {
                val fromMainDomain = credentialRepository.findByDomain(mainDomain)
                logger.d(
                    TAG,
                    "resolveCredentials: fromMainDomain=${fromMainDomain.size} mainDomain=$mainDomain"
                )
                if (fromMainDomain.isNotEmpty()) {
                    return fromMainDomain.take(MAX_DATASET_ITEMS)
                }
            }
        }

        if (normalizedWebDomain.isNullOrBlank()) {
            appSearchTokens.forEach { token ->
                val fromAppToken = credentialRepository.findByDomain(token)
                logger.d(TAG, "resolveCredentials: fromAppToken=${fromAppToken.size} token=$token")
                if (fromAppToken.isNotEmpty()) {
                    return fromAppToken.take(MAX_DATASET_ITEMS)
                }
            }
        }

        val allCredentials = credentialRepository.getAll().first()
        logger.d(TAG, "resolveCredentials: fallback source size=${allCredentials.size}")
        val ranked = AutofillCredentialMatcher.rank(
            credentials = allCredentials,
            packageName = packageName,
            webDomain = normalizedWebDomain,
            appLabel = appLabel,
            maxItems = MAX_DATASET_ITEMS
        )
        logger.d(
            TAG,
            "resolveCredentials: fallback ranked=${ranked.size}, services=${ranked.joinToString(limit = 4) { it.serviceName }}"
        )
        if (ranked.isNotEmpty()) {
            return ranked
        }

        logger.d(TAG, "resolveCredentials: no match found")
        return emptyList()
    }

    /**
     * Chromium ベースブラウザからの初回リクエスト時に、
     * ブラウザ側の設定案内通知を表示する。
     */
    private fun showBrowserSettingsGuideIfNeeded(targetPackageName: String) {
        if (targetPackageName !in CHROMIUM_BROWSER_PACKAGES) {
            return
        }

        val prefs = getSharedPreferences(BROWSER_GUIDE_PREFS, MODE_PRIVATE)
        val key = "guide_shown_$targetPackageName"
        if (prefs.getBoolean(key, false)) {
            return
        }
        prefs.edit().putBoolean(key, true).apply()

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(this, AUTOFILL_SAVE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ブラウザの自動入力設定")
            .setContentText("ブラウザ設定 -> 自動入力サービス -> 別のサービスを使用 を有効にしてください")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Chrome/Brave/Edge のバージョン137以降では、ブラウザ設定で別のサービスを使用して自動入力を有効にする必要があります。\n\nブラウザ -> 設定 -> 自動入力サービス -> 別のサービスを使用して自動入力 を選択してください。"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(
                BROWSER_GUIDE_NOTIFICATION_ID + abs(targetPackageName.hashCode()),
                notification
            )
        }.onFailure { throwable ->
            logger.w(TAG, "Failed to show browser guide notification", throwable)
        }
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
        targetPackageName: String,
        targets: SmartFieldDetector.DetectedFields,
        credentials: List<Credential>,
        inlineRequest: InlineSuggestionsRequest?
    ): FillResponse? {
        logger.d(
            TAG,
            "buildFillResponse: inlineRequest=${inlineRequest != null}, specsCount=${inlineRequest?.inlinePresentationSpecs?.size ?: 0}, maxSuggestionCount=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) inlineRequest?.maxSuggestionCount else null}"
        )

        val saveInfo = createSaveInfo(targets)
        if (credentials.isEmpty() && saveInfo == null && targets.otpId == null) {
            logger.d(TAG, "buildFillResponse: no dataset and no saveInfo")
            return null
        }

        var hasDataset = false
        val responseBuilder = FillResponse.Builder()
        val dialogTriggerIds = listOfNotNull(
            targets.focusedId,
            targets.usernameId,
            targets.passwordId,
            targets.otpId
        )
            .distinct()

        val focusedMatchesDetectedField = targets.focusedId == null ||
            targets.focusedId == targets.usernameId ||
            targets.focusedId == targets.passwordId ||
            targets.focusedId == targets.otpId
        val allowFocusedTrigger = shouldAllowFocusedTrigger(targets)

        if (!focusedMatchesDetectedField && !allowFocusedTrigger) {
            logger.d(
                TAG,
                "buildFillResponse: skipping because focusedId=${targets.focusedId} is not a credential-like input"
            )
            return null
        }

        if (
            targets.focusedId != null &&
            targets.focusedId != targets.usernameId &&
            targets.focusedId != targets.passwordId &&
            targets.focusedId != targets.otpId
        ) {
            logger.d(
                TAG,
                "buildFillResponse: focusedId=${targets.focusedId} differs from detected fill ids, adding it as UI trigger only"
            )
        }

        if (shouldUseResponseAuthentication(credentials, targets)) {
            val responseAuthResult = buildResponseAuthenticationFillResponse(
                targetPackageName = targetPackageName,
                targets = targets,
                credential = credentials.first(),
                inlineRequest = inlineRequest,
                saveInfo = saveInfo
            )
            if (responseAuthResult != null) {
                logger.d(TAG, "buildFillResponse: using response-level authentication")
                return responseAuthResult
            }
        }

        credentials.forEachIndexed { index, credential ->
            try {
                val authPendingIntent = createAuthenticationPendingIntent(
                    credentialId = credential.id,
                    usernameId = targets.usernameId,
                    passwordId = targets.passwordId,
                    targetPackageName = targetPackageName,
                    targetWebDomain = targets.webDomain,
                    authResultMode = AutofillAuthActivity.AUTH_RESULT_DATASET
                )
                val lockedPresentation = createPresentation(credential, locked = true)
                val dialogPresentation = createDialogPresentation(credential, locked = true)

                val inlinePresentation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlineRequest != null) {
                    createInlinePresentation(
                        credential = credential,
                        inlineRequest = inlineRequest,
                        index = index,
                        usernameId = targets.usernameId,
                        passwordId = targets.passwordId,
                        targetPackageName = targetPackageName,
                        targetWebDomain = targets.webDomain,
                        authResultMode = AutofillAuthActivity.AUTH_RESULT_DATASET
                    )
                } else {
                    null
                }

                val datasetBuilder = createDatasetBuilder(
                    menuPresentation = lockedPresentation,
                    dialogPresentation = dialogPresentation,
                    inlinePresentation = inlinePresentation
                ).setAuthentication(authPendingIntent.intentSender)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlineRequest != null) {
                    logger.d(
                        TAG,
                        "buildFillResponse: inlinePresentation set=${inlinePresentation != null} credentialId=${credential.id}"
                    )
                } else {
                    logger.d(
                        TAG,
                        "buildFillResponse: inline skipped (api=${Build.VERSION.SDK_INT}, hasInlineRequest=${inlineRequest != null})"
                    )
                }

                var hasValue = false

                hasValue = maybeAddFocusedTriggerValue(
                    datasetBuilder = datasetBuilder,
                    focusedId = targets.focusedId,
                    existingIds = listOfNotNull(targets.usernameId, targets.passwordId),
                    presentation = lockedPresentation,
                    allowFocusedTrigger = allowFocusedTrigger
                ) || hasValue

                targets.usernameId?.let { id ->
                    datasetBuilder.setValue(
                        id,
                        null,
                        lockedPresentation
                    )
                    hasValue = true
                    logger.d(TAG, "buildFillResponse: prepared auth-gated username for id=$id")
                }

                if (credential.hasPassword) {
                    targets.passwordId?.let { id ->
                        datasetBuilder.setValue(
                            id,
                            null,
                            lockedPresentation
                        )
                        hasValue = true
                        logger.d(TAG, "buildFillResponse: prepared auth-gated password for id=$id")
                    }
                }

                if (hasValue) {
                    responseBuilder.addDataset(datasetBuilder.build())
                    hasDataset = true
                    logger.d(
                        TAG,
                        "buildFillResponse: added dataset id=${credential.id} (auth gate enabled)"
                    )
                }
            } catch (e: Exception) {
                logger.e(TAG, "buildFillResponse: EXCEPTION building dataset", e)
            }
        }

        targets.otpId?.let { otpId ->
            try {
                val otpPresentation = createOtpPresentation()
                val otpDialogPresentation = createOtpDialogPresentation()
                val otpPendingIntent = createOtpAuthenticationPendingIntent(otpId)
                val otpDatasetBuilder = createDatasetBuilder(
                    menuPresentation = otpPresentation,
                    dialogPresentation = otpDialogPresentation,
                    inlinePresentation = null
                ).setAuthentication(otpPendingIntent.intentSender)
                maybeAddFocusedTriggerValue(
                    datasetBuilder = otpDatasetBuilder,
                    focusedId = targets.focusedId,
                    existingIds = listOfNotNull(otpId),
                    presentation = otpPresentation,
                    allowFocusedTrigger = allowFocusedTrigger
                )
                otpDatasetBuilder.setValue(
                    otpId,
                    null,
                    otpPresentation
                )
                responseBuilder.addDataset(otpDatasetBuilder.build())
                hasDataset = true
                logger.d(TAG, "buildFillResponse: added otp dataset (auth gate enabled)")
            } catch (e: Exception) {
                logger.e(TAG, "buildFillResponse: EXCEPTION building OTP dataset", e)
            }
        }

        if (hasDataset) {
            configureFillDialog(responseBuilder, dialogTriggerIds)
            saveInfo?.let { responseBuilder.setSaveInfo(it) }
        } else if (saveInfo != null) {
            responseBuilder.setSaveInfo(saveInfo)
        }

        val result = if (hasDataset || saveInfo != null) responseBuilder.build() else null
        logger.d(
            TAG,
            "buildFillResponse: final result=${if (result != null) "FillResponse" else "null"}, hasDataset=$hasDataset, hasSaveInfo=${saveInfo != null}"
        )
        return result
    }

    private fun shouldUseResponseAuthentication(
        credentials: List<Credential>,
        targets: SmartFieldDetector.DetectedFields
    ): Boolean {
        val focusedMatchesDetectedField = targets.focusedId == null ||
            targets.focusedId == targets.usernameId ||
            targets.focusedId == targets.passwordId

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            credentials.size == 1 &&
            targets.otpId == null &&
            focusedMatchesDetectedField &&
            (targets.usernameId != null || targets.passwordId != null)
    }

    private fun buildResponseAuthenticationFillResponse(
        targetPackageName: String,
        targets: SmartFieldDetector.DetectedFields,
        credential: Credential,
        inlineRequest: InlineSuggestionsRequest?,
        saveInfo: SaveInfo?
    ): FillResponse? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null
        }

        val targetIds = listOfNotNull(
            targets.usernameId,
            targets.passwordId?.takeIf { credential.hasPassword }
        )
            .distinct()
        if (targetIds.isEmpty()) {
            return null
        }

        return runCatching {
            val authPendingIntent = createAuthenticationPendingIntent(
                credentialId = credential.id,
                usernameId = targets.usernameId,
                passwordId = targets.passwordId,
                targetPackageName = targetPackageName,
                targetWebDomain = targets.webDomain,
                authResultMode = AutofillAuthActivity.AUTH_RESULT_FILL_RESPONSE
            )

            val menuPresentation = createPresentation(credential, locked = true)
            val dialogPresentation = createDialogPresentation(credential, locked = true)
            val inlinePresentation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlineRequest != null) {
                createInlinePresentation(
                    credential = credential,
                    inlineRequest = inlineRequest,
                    index = 0,
                    usernameId = targets.usernameId,
                    passwordId = targets.passwordId,
                    targetPackageName = targetPackageName,
                    targetWebDomain = targets.webDomain,
                    authResultMode = AutofillAuthActivity.AUTH_RESULT_FILL_RESPONSE
                )
            } else {
                null
            }

            val presentationsBuilder = Presentations.Builder()
                .setMenuPresentation(menuPresentation)
                .setDialogPresentation(dialogPresentation)

            if (inlinePresentation != null) {
                presentationsBuilder.setInlinePresentation(inlinePresentation)
            }

            FillResponse.Builder()
                .setAuthentication(
                    targetIds.toTypedArray(),
                    authPendingIntent.intentSender,
                    presentationsBuilder.build()
                )
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        setFillDialogTriggerIds(*targetIds.toTypedArray())
                        setShowFillDialogIcon(true)
                    }
                    saveInfo?.let { setSaveInfo(it) }
                }
                .build()
        }.onFailure { throwable ->
            logger.e(TAG, "buildFillResponse: failed to build response-level authentication", throwable)
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createInlinePresentation(
        credential: Credential,
        inlineRequest: InlineSuggestionsRequest,
        index: Int,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        targetPackageName: String,
        targetWebDomain: String?,
        authResultMode: String
    ): InlinePresentation? {
        val specs = inlineRequest.inlinePresentationSpecs
        if (specs.isEmpty()) {
            logger.d(TAG, "createInlinePresentation: no inline specs")
            return null
        }
        val spec = if (index < specs.size) specs[index] else specs.last()

        logger.d(
            TAG,
            "createInlinePresentation: index=$index, minSize=${spec.minSize}, maxSize=${spec.maxSize}, style=${spec.style}"
        )

        return runCatching {
            val intent = Intent(this, AutofillAuthActivity::class.java).apply {
                putExtra(AutofillAuthActivity.EXTRA_CREDENTIAL_ID, credential.id)
                usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_AUTOFILL_ID, it) }
                passwordId?.let { putExtra(AutofillAuthActivity.EXTRA_PASSWORD_AUTOFILL_ID, it) }
                putExtra(AutofillAuthActivity.EXTRA_TARGET_PACKAGE_NAME, targetPackageName)
                targetWebDomain?.let { putExtra(AutofillAuthActivity.EXTRA_TARGET_WEB_DOMAIN, it) }
                putExtra(AutofillAuthActivity.EXTRA_AUTH_RESULT_MODE, authResultMode)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                credential.id.toInt(),
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val slice = InlineSuggestionUi.newContentBuilder(pendingIntent)
                .setTitle(credential.serviceName)
                .setSubtitle(credential.username)
                .build()
                .slice

            InlinePresentation(slice, spec, false).also {
                logger.d(
                    TAG,
                    "createInlinePresentation: success credentialId=${credential.id}"
                )
            }
        }.onFailure { throwable ->
            logger.w(TAG, "createInlinePresentation: failed credentialId=${credential.id}", throwable)
        }.getOrNull()
    }

    private fun createPresentation(credential: Credential, locked: Boolean): RemoteViews {
        return RemoteViews(packageName, R.layout.autofill_suggestion_item).apply {
            val prefix = if (locked) LOCKED_LABEL_PREFIX else ""
            setTextViewText(R.id.service_name, "$prefix${credential.serviceName}")
            setTextViewText(R.id.username, credential.username)
        }
    }

    private fun createDialogPresentation(credential: Credential, locked: Boolean): RemoteViews {
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

    private fun createOtpDialogPresentation(): RemoteViews {
        return RemoteViews(packageName, R.layout.autofill_otp_item).apply {
            setTextViewText(R.id.label, getString(R.string.otp_sms_suggestion))
        }
    }

    private fun createDatasetBuilder(
        menuPresentation: RemoteViews,
        dialogPresentation: RemoteViews,
        inlinePresentation: InlinePresentation?
    ): Dataset.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val presentationsBuilder = Presentations.Builder()
                .setMenuPresentation(menuPresentation)
                .setDialogPresentation(dialogPresentation)

            if (inlinePresentation != null) {
                presentationsBuilder.setInlinePresentation(inlinePresentation)
            }

            return Dataset.Builder(presentationsBuilder.build())
        }

        return Dataset.Builder(menuPresentation).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && inlinePresentation != null) {
                setInlinePresentation(inlinePresentation)
            }
        }
    }

    private fun configureFillDialog(
        responseBuilder: FillResponse.Builder,
        dialogTriggerIds: List<AutofillId>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || dialogTriggerIds.isEmpty()) {
            return
        }

        responseBuilder
            .setDialogHeader(createFillDialogHeader())
            .setFillDialogTriggerIds(*dialogTriggerIds.toTypedArray())
            .setShowFillDialogIcon(true)

        logger.d(
            TAG,
            "buildFillResponse: fill dialog configured triggerIds=${dialogTriggerIds.size}"
        )
    }

    private fun maybeAddFocusedTriggerValue(
        datasetBuilder: Dataset.Builder,
        focusedId: AutofillId?,
        existingIds: List<AutofillId>,
        presentation: RemoteViews,
        allowFocusedTrigger: Boolean
    ): Boolean {
        if (!allowFocusedTrigger || focusedId == null || focusedId in existingIds) {
            return false
        }

        datasetBuilder.setValue(focusedId, null, presentation)
        logger.d(TAG, "buildFillResponse: added focused trigger id=$focusedId")
        return true
    }

    private fun shouldAllowFocusedTrigger(targets: SmartFieldDetector.DetectedFields): Boolean {
        val focusedId = targets.focusedId ?: return false
        if (focusedId == targets.usernameId || focusedId == targets.passwordId || focusedId == targets.otpId) {
            return true
        }

        targets.focusedNodeInfo?.let { focusedNodeInfo ->
            return focusedNodeInfo.supportsAutofillTrigger
        }

        val isCompatProxyId = focusedId !in targets.allAutofillIds
        val hasCredentialTargets = targets.usernameId != null || targets.passwordId != null
        if (isCompatProxyId && hasCredentialTargets) {
            logger.d(
                TAG,
                "buildFillResponse: allowing compat-mode proxy trigger focusedId=$focusedId because it is absent from AssistStructure nodes"
            )
            return true
        }

        return false
    }

    private fun createFillDialogHeader(): RemoteViews {
        return RemoteViews(packageName, R.layout.autofill_suggestion_item).apply {
            setTextViewText(R.id.service_name, getString(R.string.autofill_service_name))
            setTextViewText(R.id.username, getString(R.string.autofill_auth_subtitle))
        }
    }

    private fun createAuthenticationPendingIntent(
        credentialId: Long,
        usernameId: AutofillId?,
        passwordId: AutofillId?,
        targetPackageName: String,
        targetWebDomain: String?,
        authResultMode: String
    ): PendingIntent {
        val intent = Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillAuthActivity.EXTRA_CREDENTIAL_ID, credentialId)
            usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_AUTOFILL_ID, it) }
            passwordId?.let { putExtra(AutofillAuthActivity.EXTRA_PASSWORD_AUTOFILL_ID, it) }
            putExtra(AutofillAuthActivity.EXTRA_TARGET_PACKAGE_NAME, targetPackageName)
            targetWebDomain?.let { putExtra(AutofillAuthActivity.EXTRA_TARGET_WEB_DOMAIN, it) }
            putExtra(AutofillAuthActivity.EXTRA_AUTH_RESULT_MODE, authResultMode)
        }

        return PendingIntent.getActivity(
            this,
            credentialId.toInt(),
            intent,
            authenticationPendingIntentFlags()
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
            authenticationPendingIntentFlags()
        )
    }

    private fun authenticationPendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    private fun createSaveInfo(targets: SmartFieldDetector.DetectedFields): SaveInfo? {
        val requiredIds = listOfNotNull(targets.usernameId).distinct()
        val optionalIds = listOfNotNull(targets.passwordId)
            .filterNot { it in requiredIds }
        if (requiredIds.isEmpty()) {
            return null
        }

        val focusedId = targets.focusedId
        if (focusedId != null && focusedId !in requiredIds && focusedId !in optionalIds) {
            logger.d(
                TAG,
                "createSaveInfo: skipping because focusedId=$focusedId is not part of save ids"
            )
            return null
        }

        var saveDataType = SaveInfo.SAVE_DATA_TYPE_USERNAME
        if (targets.passwordId != null) {
            saveDataType = saveDataType or SaveInfo.SAVE_DATA_TYPE_PASSWORD
        }

        return SaveInfo.Builder(saveDataType, requiredIds.toTypedArray())
            .apply {
                if (optionalIds.isNotEmpty()) {
                    setOptionalIds(optionalIds.toTypedArray())
                }
            }
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

            val rawTextValue = node.autofillValue
                ?.takeIf { it.isText }
                ?.textValue
                ?.toString()
            val trimmedTextValue = rawTextValue?.trim()

            if (!trimmedTextValue.isNullOrEmpty()) {
                val nodeAutofillId = node.autofillId
                if (username.isNullOrBlank()) {
                    val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.usernameId
                    if (byDetectedId || isLikelyUsernameField(node)) {
                        username = trimmedTextValue
                        logger.d(TAG, "extractSavedValues: captured username")
                    }
                }

                if (password.isNullOrBlank() && !rawTextValue.isNullOrBlank()) {
                    val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.passwordId
                    if (byDetectedId || isLikelyPasswordField(node)) {
                        password = rawTextValue
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

    private fun normalizeWebDomain(webDomain: String?): String? {
        val normalized = webDomain
            ?.trim()
            ?.substringAfter("://", webDomain.trim())
            ?.substringBefore('/')
            ?.substringBefore(':')
            .orEmpty()
        return normalized.ifBlank { null }
    }

    private fun resolveApplicationLabel(packageName: String): String? {
        if (packageName.isBlank() || packageName in CHROMIUM_BROWSER_PACKAGES) {
            return null
        }

        return runCatching {
            val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.onFailure { throwable ->
            logger.w(TAG, "resolveApplicationLabel: failed package=$packageName", throwable)
        }.getOrNull()
    }

    private fun buildAppSearchTokens(packageName: String, appLabel: String?): List<String> {
        val tokens = linkedSetOf<String>()

        fun collect(raw: String?) {
            raw
                ?.lowercase(Locale.ROOT)
                ?.replace('/', ' ')
                ?.split(Regex("[^a-z0-9\u3040-\u30ff\u3400-\u9fff]+"))
                .orEmpty()
                .filter { token ->
                    token.length >= 3 && token !in APP_SEARCH_IGNORED_TOKENS
                }
                .forEach { token ->
                    tokens += token
                }
        }

        collect(appLabel)
        collect(packageName)

        packageName.split('.')
            .map { it.lowercase(Locale.ROOT) }
            .filter { token -> token.length >= 3 && token !in APP_SEARCH_IGNORED_TOKENS }
            .forEach { token ->
                tokens += token
            }

        return tokens.toList()
    }

    private fun filterAutofillableCredentials(
        credentials: List<Credential>,
        targets: SmartFieldDetector.DetectedFields
    ): List<Credential> {
        return credentials.filter { credential ->
            when {
                credential.isPasskey -> false
                targets.usernameId == null && !credential.hasPassword -> false
                else -> true
            }
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
        const val BROWSER_GUIDE_PREFS = "browser_guide_prefs"
        const val BROWSER_GUIDE_NOTIFICATION_ID = 13000

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

        val CHROMIUM_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.vivaldi.browser"
        )

        val APP_SEARCH_IGNORED_TOKENS = setOf(
            "com",
            "app",
            "android",
            "google",
            "apps",
            "browser",
            "mobile",
            "client",
            "mediaclient",
            "main",
            "activity",
            "login",
            "ui",
            "view",
            "fragment",
            "screen"
        )
    }
}
