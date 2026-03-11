package com.securevault.app.service.autofill

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.ComponentName
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
import com.securevault.app.data.repository.model.CardData
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.data.repository.model.CredentialType
import com.securevault.app.data.store.SecuritySettingsPreferences
import com.securevault.app.data.store.securitySettingsDataStore
import com.securevault.app.service.otp.OtpManager
import com.securevault.app.service.otp.OtpSource
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
    lateinit var otpManager: OtpManager

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

        val inlineRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request.inlineSuggestionsRequest
        } else {
            null
        }

        cancellationSignal.setOnCancelListener {
            logger.d(TAG, "onFillRequest: cancellation requested for package=$targetPackageName")
        }

        serviceScope.launch {
            try {
                val focusedId = request.fillContexts.lastOrNull()?.focusedId
                val targets = smartFieldDetector.detect(structure, focusedId)
                val contentType = determineContentType(targets)
                val currentFieldValues = extractTargetFieldValues(structure, targets)

                // Chromium 137+ のブラウザ設定案内を初回のみ表示する。
                showBrowserSettingsGuideIfNeeded(targetPackageName)
                logger.d(
                    TAG,
                    "onFillRequest: detected focusedId=${targets.focusedId}, focusedSupportsTrigger=${targets.focusedNodeInfo?.supportsAutofillTrigger}, contentType=$contentType, usernameId=${targets.usernameId}, passwordId=${targets.passwordId}, cardholderNameId=${targets.cardholderNameId}, cardNumberId=${targets.cardNumberId}, cardExpirationDateId=${targets.cardExpirationDateId}, cardExpirationMonthId=${targets.cardExpirationMonthId}, cardExpirationYearId=${targets.cardExpirationYearId}, cardSecurityCodeId=${targets.cardSecurityCodeId}, otpId=${targets.otpId}, webDomain=${targets.webDomain}, pageTitle=${targets.pageTitle}"
                )

                val shouldOfferOtp = shouldOfferOtpDataset(targets)
                val allowedOtpSources = if (shouldOfferOtp) {
                    loadAllowedOtpSources()
                } else {
                    emptySet()
                }
                val shouldOfferOtpNow = shouldOfferOtp && allowedOtpSources.isNotEmpty()

                if (contentType == null && !shouldOfferOtpNow) {
                    logger.d(TAG, "onFillRequest: no fields detected")
                    deliverFillResponse(callback, cancellationSignal, null)
                    return@launch
                }

                if (contentType != null && shouldSuppressAutofillForFilledFields(contentType, targets, currentFieldValues)) {
                    logger.d(
                        TAG,
                        "onFillRequest: skipping because ${describeFilledFieldSuppression(contentType, targets, currentFieldValues)}"
                    )
                    deliverFillResponse(callback, cancellationSignal, null)
                    return@launch
                }

                if (shouldOfferOtpNow && OtpSource.SMS in allowedOtpSources) {
                    runCatching {
                        smsOtpManager.startListening()
                    }.onFailure { throwable ->
                        logger.w(TAG, "Failed to start SMS OTP listener", throwable)
                    }
                }

                val targetAppLabel = resolveTargetApplicationLabel(
                    activityComponent = structure.activityComponent,
                    packageName = targetPackageName
                )
                val credentials = if (contentType != null) {
                    runCatching {
                        filterAutofillableCredentials(
                            resolveCredentials(targetPackageName, targets.webDomain, targetAppLabel),
                            contentType
                        ).ifEmpty {
                            if (!targets.pageTitle.isNullOrBlank()) {
                                filterAutofillableCredentials(
                                    resolveCredentials(
                                        targetPackageName,
                                        targets.webDomain,
                                        targets.pageTitle
                                    ),
                                    contentType
                                )
                            } else {
                                emptyList()
                            }
                        }
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

                val response = buildFillResponse(
                    targetPackageName = targetPackageName,
                    targets = targets,
                    credentials = credentials,
                    inlineRequest = inlineRequest,
                    contentType = contentType,
                    allowedOtpSources = allowedOtpSources
                )
                logger.d(TAG, "onFillRequest: responseBuilt=${response != null}")

                deliverFillResponse(callback, cancellationSignal, response)
                logger.d(TAG, "=== onFillRequest END (success) ===")
            } catch (throwable: Throwable) {
                logger.e(TAG, "onFillRequest: exception", throwable)
                deliverFillResponse(callback, cancellationSignal, null)
            }
        }
    }

    private fun deliverFillResponse(
        callback: FillCallback,
        cancellationSignal: CancellationSignal,
        response: FillResponse?
    ) {
        if (cancellationSignal.isCanceled) {
            logger.d(TAG, "deliverFillResponse: skipped because request was canceled")
            return
        }
        callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        logger.d(TAG, "=== onSaveRequest START ===")

        val structures = request.fillContexts.mapNotNull { it.structure }
        if (structures.isEmpty()) {
            logger.w(TAG, "onSaveRequest: structures are empty")
            callback.onSuccess()
            return
        }

        logger.d(TAG, "onSaveRequest: fillContextCount=${structures.size}")

        val structure = structures.last()
        val targetPackageName = structure.activityComponent?.packageName.orEmpty()
        if (targetPackageName in EXCLUDED_PACKAGES || targetPackageName == packageName) {
            logger.d(TAG, "onSaveRequest: skipping excluded package=$targetPackageName")
            callback.onSuccess()
            return
        }

        serviceScope.launch {
            runCatching {
                val savedData = extractSavedValues(structures)
                logger.d(
                    TAG,
                    "onSaveRequest: extracted type=${savedData?.credentialType}, username=${savedData?.username}, hasPassword=${!savedData?.password.isNullOrBlank()}, hasCard=${savedData?.cardData != null}, package=${savedData?.packageName}, domain=${savedData?.webDomain}"
                )
                if (
                    savedData == null ||
                    (savedData.credentialType == CredentialType.CARD && savedData.cardData == null) ||
                    (savedData.credentialType != CredentialType.CARD && savedData.username.isNullOrBlank())
                ) {
                    logger.d(TAG, "onSaveRequest: no usable credential data")
                    withContext(Dispatchers.Main) {
                        callback.onSuccess()
                    }
                    return@launch
                }

                val credentialToSave = buildCredentialForSave(savedData)
                logger.d(
                    TAG,
                    "onSaveRequest: saving service=${credentialToSave.serviceName}, package=${credentialToSave.packageName}, updateId=${credentialToSave.id}"
                )
                credentialRepository.save(credentialToSave)
                showSaveNotification(credentialToSave.serviceName)
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
        inlineRequest: InlineSuggestionsRequest?,
        contentType: AutofillContentType?,
        allowedOtpSources: Set<OtpSource>
    ): FillResponse? {
        logger.d(
            TAG,
            "buildFillResponse: inlineRequest=${inlineRequest != null}, specsCount=${inlineRequest?.inlinePresentationSpecs?.size ?: 0}, maxSuggestionCount=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) inlineRequest?.maxSuggestionCount else null}"
        )

        val rawSaveInfo = createSaveInfo(targets, contentType)
        val saveInfo = if (
            rawSaveInfo != null &&
            contentType == AutofillContentType.LOGIN &&
            credentials.isNotEmpty() &&
            isCompatProxyFocusedId(targets)
        ) {
            logger.d(
                TAG,
                "buildFillResponse: suppressing saveInfo for compat proxy focusedId=${targets.focusedId} while datasets exist to avoid Chromium session dismissal"
            )
            null
        } else {
            rawSaveInfo
        }
        val shouldOfferOtp = shouldOfferOtpDataset(targets) && allowedOtpSources.isNotEmpty()
        if (credentials.isEmpty() && saveInfo == null && !shouldOfferOtp) {
            logger.d(TAG, "buildFillResponse: no dataset and no saveInfo")
            return null
        }

        var hasDataset = false
        val responseBuilder = FillResponse.Builder()
        val detectedTargetIds = detectedTargetIds(targets)
        val dialogTriggerIds = (listOfNotNull(targets.focusedId) + detectedTargetIds)
            .distinct()

        val focusedMatchesDetectedField = targets.focusedId == null || targets.focusedId in detectedTargetIds
        val allowFocusedTrigger = shouldAllowFocusedTrigger(targets)

        if (!focusedMatchesDetectedField && !allowFocusedTrigger) {
            logger.d(
                TAG,
                "buildFillResponse: skipping because focusedId=${targets.focusedId} is not a credential-like input"
            )
            return null
        }

        if (targets.focusedId != null && targets.focusedId !in detectedTargetIds) {
            logger.d(
                TAG,
                "buildFillResponse: focusedId=${targets.focusedId} differs from detected fill ids, adding it as UI trigger only"
            )
        }

        if (shouldUseResponseAuthentication(targetPackageName, credentials, targets, contentType, shouldOfferOtp)) {
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
                    targets = targets,
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
                        targets = targets,
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
                val credentialTargetIds = when (contentType) {
                    AutofillContentType.CARD -> cardTargetIds(targets)
                    AutofillContentType.LOGIN -> listOfNotNull(targets.usernameId, targets.passwordId)
                    null -> emptyList()
                }

                hasValue = maybeAddFocusedTriggerValue(
                    datasetBuilder = datasetBuilder,
                    focusedId = targets.focusedId,
                    existingIds = credentialTargetIds,
                    presentation = lockedPresentation,
                    allowFocusedTrigger = allowFocusedTrigger
                ) || hasValue

                when (contentType) {
                    AutofillContentType.CARD -> {
                        listOfNotNull(
                            targets.cardholderNameId,
                            targets.cardNumberId,
                            targets.cardExpirationDateId,
                            targets.cardExpirationMonthId,
                            targets.cardExpirationYearId,
                            targets.cardSecurityCodeId
                        ).forEach { id ->
                            datasetBuilder.setValue(id, null, lockedPresentation)
                            hasValue = true
                            logger.d(TAG, "buildFillResponse: prepared auth-gated card value for id=$id")
                        }
                    }

                    AutofillContentType.LOGIN -> {
                        targets.usernameId?.let { id ->
                            datasetBuilder.setValue(id, null, lockedPresentation)
                            hasValue = true
                            logger.d(TAG, "buildFillResponse: prepared auth-gated username for id=$id")
                        }

                        if (credential.hasPassword) {
                            targets.passwordId?.let { id ->
                                datasetBuilder.setValue(id, null, lockedPresentation)
                                hasValue = true
                                logger.d(TAG, "buildFillResponse: prepared auth-gated password for id=$id")
                            }
                        }
                    }

                    null -> Unit
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

        targets.otpId?.takeIf { shouldOfferOtp }?.let { otpId ->
            try {
                val otpPresentation = createOtpPresentation()
                val otpDialogPresentation = createOtpDialogPresentation()
                val recentOtp = otpManager.getRecentOtp(
                    allowedSources = allowedOtpSources,
                    maxAgeMs = RECENT_OTP_MAX_AGE_MS
                )
                val otpDatasetBuilder = createDatasetBuilder(
                    menuPresentation = otpPresentation,
                    dialogPresentation = otpDialogPresentation,
                    inlinePresentation = null
                )
                maybeAddFocusedTriggerValue(
                    datasetBuilder = otpDatasetBuilder,
                    focusedId = targets.focusedId,
                    existingIds = listOfNotNull(otpId),
                    presentation = otpPresentation,
                    allowFocusedTrigger = allowFocusedTrigger
                )

                if (recentOtp != null) {
                    otpDatasetBuilder.setValue(
                        otpId,
                        AutofillValue.forText(recentOtp.code),
                        otpPresentation
                    )
                    logger.d(TAG, "buildFillResponse: added direct otp dataset source=${recentOtp.source}")
                } else {
                    val otpPendingIntent = createOtpAuthenticationPendingIntent(otpId)
                    otpDatasetBuilder.setAuthentication(otpPendingIntent.intentSender)
                    otpDatasetBuilder.setValue(
                        otpId,
                        null,
                        otpPresentation
                    )
                    logger.d(TAG, "buildFillResponse: added deferred otp dataset (auth gate enabled)")
                }

                responseBuilder.addDataset(otpDatasetBuilder.build())
                hasDataset = true
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
        targetPackageName: String,
        credentials: List<Credential>,
        targets: SmartFieldDetector.DetectedFields,
        contentType: AutofillContentType?,
        shouldOfferOtp: Boolean
    ): Boolean {
        val credential = credentials.singleOrNull() ?: return false
        if (contentType != AutofillContentType.LOGIN) {
            return false
        }
        if (!credential.hasPassword) {
            return false
        }
        if (targets.webDomain.isNullOrBlank()) {
            logger.d(
                TAG,
                "shouldUseResponseAuthentication: disabled for native app / no web domain package=$targetPackageName"
            )
            return false
        }
        if (targetPackageName in CHROMIUM_BROWSER_PACKAGES) {
            logger.d(
                TAG,
                "shouldUseResponseAuthentication: disabled for chromium browser package=$targetPackageName"
            )
            return false
        }

        val focusedMatchesDetectedField = targets.focusedId == null ||
            targets.focusedId == targets.usernameId ||
            targets.focusedId == targets.passwordId

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldOfferOtp &&
            focusedMatchesDetectedField &&
            (targets.usernameId != null || targets.passwordId != null)
    }

    private fun shouldOfferOtpDataset(targets: SmartFieldDetector.DetectedFields): Boolean {
        val otpId = targets.otpId ?: return false
        val focusedId = targets.focusedId ?: return true
        if (focusedId == otpId) {
            return true
        }

        if (targets.focusedNodeInfo?.credentialKind == SmartFieldDetector.CredentialKind.OTP) {
            return true
        }

        logger.d(
            TAG,
            "buildFillResponse: suppressing otp dataset for focusedId=$focusedId because otpId=$otpId is not focused"
        )
        return false
    }

    private suspend fun loadAllowedOtpSources(): Set<OtpSource> {
        val settings = applicationContext.securitySettingsDataStore.data.first()
        return buildSet {
            if (
                settings[SecuritySettingsPreferences.OTP_SMS_ENABLED_KEY]
                    ?: SecuritySettingsPreferences.DEFAULT_OTP_SMS_ENABLED
            ) {
                add(OtpSource.SMS)
            }
            if (
                settings[SecuritySettingsPreferences.OTP_NOTIFICATION_ENABLED_KEY]
                    ?: SecuritySettingsPreferences.DEFAULT_OTP_NOTIFICATION_ENABLED
            ) {
                add(OtpSource.NOTIFICATION)
            }
            if (
                settings[SecuritySettingsPreferences.OTP_CLIPBOARD_ENABLED_KEY]
                    ?: SecuritySettingsPreferences.DEFAULT_OTP_CLIPBOARD_ENABLED
            ) {
                add(OtpSource.CLIPBOARD)
            }
        }
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
            targets.passwordId?.takeIf { credential.hasPassword },
            targets.focusedId?.takeIf { resolveFocusedFillHint(targets) != null }
        )
            .distinct()
        if (targetIds.isEmpty()) {
            return null
        }

        return runCatching {
            val authPendingIntent = createAuthenticationPendingIntent(
                credentialId = credential.id,
                targets = targets,
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
                    targets = targets,
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
        targets: SmartFieldDetector.DetectedFields,
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
                targets.usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_AUTOFILL_ID, it) }
                targets.passwordId?.let { putExtra(AutofillAuthActivity.EXTRA_PASSWORD_AUTOFILL_ID, it) }
                targets.cardholderNameId?.let { putExtra(AutofillAuthActivity.EXTRA_CARDHOLDER_NAME_AUTOFILL_ID, it) }
                targets.cardNumberId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_NUMBER_AUTOFILL_ID, it) }
                targets.cardExpirationDateId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_EXPIRATION_DATE_AUTOFILL_ID, it) }
                targets.cardExpirationMonthId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_EXPIRATION_MONTH_AUTOFILL_ID, it) }
                targets.cardExpirationYearId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_EXPIRATION_YEAR_AUTOFILL_ID, it) }
                targets.cardSecurityCodeId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_SECURITY_CODE_AUTOFILL_ID, it) }
                putExtra(AutofillAuthActivity.EXTRA_TARGET_PACKAGE_NAME, targetPackageName)
                targetWebDomain?.let { putExtra(AutofillAuthActivity.EXTRA_TARGET_WEB_DOMAIN, it) }
                putExtra(AutofillAuthActivity.EXTRA_AUTH_RESULT_MODE, authResultMode)
                putFocusedFillExtras(this, targets)
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                credential.id.toInt(),
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val slice = InlineSuggestionUi.newContentBuilder(pendingIntent)
                .setTitle(credential.serviceName)
                .setSubtitle(credential.listSubtitle)
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
            setTextViewText(R.id.username, credential.listSubtitle)
        }
    }

    private fun createDialogPresentation(credential: Credential, locked: Boolean): RemoteViews {
        return RemoteViews(packageName, R.layout.autofill_suggestion_item).apply {
            val prefix = if (locked) LOCKED_LABEL_PREFIX else ""
            setTextViewText(R.id.service_name, "$prefix${credential.serviceName}")
            setTextViewText(R.id.username, credential.listSubtitle)
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

    private fun isCompatProxyFocusedId(targets: SmartFieldDetector.DetectedFields): Boolean {
        val focusedId = targets.focusedId ?: return false
        return focusedId !in targets.allAutofillIds
    }

    private fun shouldAllowFocusedTrigger(targets: SmartFieldDetector.DetectedFields): Boolean {
        val focusedId = targets.focusedId ?: return false
        if (
            focusedId == targets.usernameId ||
            focusedId == targets.passwordId ||
            focusedId == targets.otpId ||
            focusedId in cardTargetIds(targets)
        ) {
            return true
        }

        targets.focusedNodeInfo?.let { focusedNodeInfo ->
            return focusedNodeInfo.supportsAutofillTrigger
        }

        val hasCredentialTargets =
            targets.usernameId != null || targets.passwordId != null || hasCardTargets(targets)
        if (isCompatProxyFocusedId(targets) && hasCredentialTargets) {
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

    private fun putFocusedFillExtras(intent: Intent, targets: SmartFieldDetector.DetectedFields) {
        val focusedId = targets.focusedId ?: return
        val focusedFillHint = resolveFocusedFillHint(targets) ?: return
        intent.putExtra(AutofillAuthActivity.EXTRA_FOCUSED_AUTOFILL_ID, focusedId)
        intent.putExtra(AutofillAuthActivity.EXTRA_FOCUSED_FILL_HINT, focusedFillHint)
    }

    private fun resolveFocusedFillHint(targets: SmartFieldDetector.DetectedFields): String? {
        val focusedId = targets.focusedId ?: return null
        if (focusedId == targets.passwordId) {
            return AutofillAuthActivity.FOCUSED_FILL_HINT_PASSWORD
        }
        if (focusedId == targets.usernameId) {
            return AutofillAuthActivity.FOCUSED_FILL_HINT_USERNAME
        }

        val focusedNodeInfo = targets.focusedNodeInfo ?: return null
        if (focusedNodeInfo.credentialKind != SmartFieldDetector.CredentialKind.LOGIN) {
            return null
        }

        return if (isFocusedPasswordLike(focusedNodeInfo)) {
            AutofillAuthActivity.FOCUSED_FILL_HINT_PASSWORD
        } else {
            AutofillAuthActivity.FOCUSED_FILL_HINT_USERNAME
        }
    }

    private fun isFocusedPasswordLike(focusedNodeInfo: SmartFieldDetector.FocusedNodeInfo): Boolean {
        val variation = focusedNodeInfo.inputType and InputType.TYPE_MASK_VARIATION
        if (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return true
        }

        val rawTokens = buildList {
            addAll(focusedNodeInfo.hints)
            add(focusedNodeInfo.idEntry)
            add(focusedNodeInfo.hintText)
            addAll(focusedNodeInfo.htmlTokens)
        }
        return rawTokens.any { token ->
            FOCUSED_PASSWORD_HINT_TOKENS.any { keyword -> token.contains(keyword) }
        }
    }

    private fun createAuthenticationPendingIntent(
        credentialId: Long,
        targets: SmartFieldDetector.DetectedFields,
        targetPackageName: String,
        targetWebDomain: String?,
        authResultMode: String
    ): PendingIntent {
        val intent = Intent(this, AutofillAuthActivity::class.java).apply {
            putExtra(AutofillAuthActivity.EXTRA_CREDENTIAL_ID, credentialId)
            targets.usernameId?.let { putExtra(AutofillAuthActivity.EXTRA_USERNAME_AUTOFILL_ID, it) }
            targets.passwordId?.let { putExtra(AutofillAuthActivity.EXTRA_PASSWORD_AUTOFILL_ID, it) }
            targets.cardholderNameId?.let { putExtra(AutofillAuthActivity.EXTRA_CARDHOLDER_NAME_AUTOFILL_ID, it) }
            targets.cardNumberId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_NUMBER_AUTOFILL_ID, it) }
            targets.cardExpirationDateId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_EXPIRATION_DATE_AUTOFILL_ID, it) }
            targets.cardExpirationMonthId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_EXPIRATION_MONTH_AUTOFILL_ID, it) }
            targets.cardExpirationYearId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_EXPIRATION_YEAR_AUTOFILL_ID, it) }
            targets.cardSecurityCodeId?.let { putExtra(AutofillAuthActivity.EXTRA_CARD_SECURITY_CODE_AUTOFILL_ID, it) }
            putExtra(AutofillAuthActivity.EXTRA_TARGET_PACKAGE_NAME, targetPackageName)
            targetWebDomain?.let { putExtra(AutofillAuthActivity.EXTRA_TARGET_WEB_DOMAIN, it) }
            putExtra(AutofillAuthActivity.EXTRA_AUTH_RESULT_MODE, authResultMode)
            putFocusedFillExtras(this, targets)
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

    private fun createSaveInfo(
        targets: SmartFieldDetector.DetectedFields,
        contentType: AutofillContentType?
    ): SaveInfo? {
        val requiredIds = when (contentType) {
            AutofillContentType.CARD -> listOfNotNull(targets.cardNumberId).distinct()
            AutofillContentType.LOGIN -> listOfNotNull(targets.usernameId ?: targets.passwordId).distinct()
            null -> emptyList()
        }
        val optionalIds = when (contentType) {
            AutofillContentType.CARD -> listOfNotNull(
                targets.cardholderNameId,
                targets.cardExpirationDateId,
                targets.cardExpirationMonthId,
                targets.cardExpirationYearId,
                targets.cardSecurityCodeId
            ).filterNot { it in requiredIds }

            AutofillContentType.LOGIN -> listOfNotNull(targets.usernameId, targets.passwordId)
                .distinct()
                .filterNot { it in requiredIds }

            null -> emptyList()
        }
        if (requiredIds.isEmpty()) {
            logger.d(
                TAG,
                "createSaveInfo: no required ids for contentType=$contentType, usernameId=${targets.usernameId}, passwordId=${targets.passwordId}, cardNumberId=${targets.cardNumberId}"
            )
            return null
        }

        if (contentType == AutofillContentType.LOGIN && targets.usernameId == null && targets.passwordId != null) {
            logger.d(
                TAG,
                "createSaveInfo: using passwordId=${targets.passwordId} as required save field because usernameId is missing"
            )
        }

        if (shouldSkipSaveInfoForFocusedId(targets, contentType, requiredIds, optionalIds)) {
            return null
        }

        val saveDataType = when (contentType) {
            AutofillContentType.CARD -> SaveInfo.SAVE_DATA_TYPE_CREDIT_CARD
            AutofillContentType.LOGIN -> {
                var loginSaveDataType = SaveInfo.SAVE_DATA_TYPE_USERNAME
                if (targets.passwordId != null) {
                    loginSaveDataType = loginSaveDataType or SaveInfo.SAVE_DATA_TYPE_PASSWORD
                }
                loginSaveDataType
            }

            null -> return null
        }

        logger.d(
            TAG,
            "createSaveInfo: building contentType=$contentType, focusedId=${targets.focusedId}, requiredIds=${requiredIds.size}, optionalIds=${optionalIds.size}"
        )

        return SaveInfo.Builder(saveDataType, requiredIds.toTypedArray())
            .apply {
                if (optionalIds.isNotEmpty()) {
                    setOptionalIds(optionalIds.toTypedArray())
                }
            }
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
            .build()
    }

    private fun shouldSkipSaveInfoForFocusedId(
        targets: SmartFieldDetector.DetectedFields,
        contentType: AutofillContentType?,
        requiredIds: List<AutofillId>,
        optionalIds: List<AutofillId>
    ): Boolean {
        val focusedId = targets.focusedId ?: return false
        if (focusedId in requiredIds || focusedId in optionalIds) {
            return false
        }

        if (isCompatProxyFocusedId(targets)) {
            logger.d(
                TAG,
                "createSaveInfo: allowing compat proxy focusedId=$focusedId outside save ids for contentType=$contentType"
            )
            return false
        }

        val focusedCredentialKind = targets.focusedNodeInfo?.credentialKind
        val canReuseFocusedField = when (contentType) {
            AutofillContentType.LOGIN -> {
                focusedCredentialKind == SmartFieldDetector.CredentialKind.LOGIN ||
                    focusedCredentialKind == SmartFieldDetector.CredentialKind.OTP
            }

            AutofillContentType.CARD -> focusedCredentialKind == SmartFieldDetector.CredentialKind.CARD
            null -> false
        }
        if (canReuseFocusedField) {
            logger.d(
                TAG,
                "createSaveInfo: allowing focusedId=$focusedId outside save ids because focused credentialKind=$focusedCredentialKind for contentType=$contentType"
            )
            return false
        }

        logger.d(
            TAG,
            "createSaveInfo: skipping because focusedId=$focusedId is outside save ids for contentType=$contentType, focusedCredentialKind=$focusedCredentialKind"
        )
        return true
    }

    private fun extractSavedValues(structures: List<AssistStructure>): SavedCredentialData? {
        if (structures.isEmpty()) {
            return null
        }

        var detectedContentType: AutofillContentType? = null
        var username: String? = null
        var password: String? = null
        var cardholderName: String? = null
        var cardNumber: String? = null
        var cardExpirationDate: String? = null
        var cardExpirationMonth: String? = null
        var cardExpirationYear: String? = null
        var cardSecurityCode: String? = null
        var capturedPackageName = ""
        var webDomain: String? = null
        var activityComponent: ComponentName? = null
        val observedPackageNames = mutableListOf<String>()

        structures.forEachIndexed { contextIndex, structure ->
            val detectedFields = smartFieldDetector.detect(structure)
            val contentType = determineContentType(detectedFields)
            detectedContentType = when {
                detectedContentType == AutofillContentType.CARD || contentType == AutofillContentType.CARD -> AutofillContentType.CARD
                detectedContentType == AutofillContentType.LOGIN || contentType == AutofillContentType.LOGIN -> AutofillContentType.LOGIN
                else -> detectedContentType ?: contentType
            }

            if (activityComponent == null) {
                activityComponent = structure.activityComponent
            }
            structure.activityComponent?.packageName?.trim()?.takeIf { it.isNotBlank() }?.let {
                observedPackageNames += it
            }
            if (capturedPackageName.isBlank()) {
                capturedPackageName = structure.activityComponent?.packageName.orEmpty()
            }
            if (webDomain.isNullOrBlank()) {
                webDomain = detectedFields.webDomain
            }

            fun traverse(node: AssistStructure.ViewNode) {
                val packageFromNode = node.idPackage?.trim().orEmpty()
                if (packageFromNode.isNotBlank()) {
                    observedPackageNames += packageFromNode
                }
                if (capturedPackageName.isBlank() && packageFromNode.isNotBlank()) {
                    capturedPackageName = packageFromNode
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
                            logger.d(TAG, "extractSavedValues: captured username from context=$contextIndex")
                        }
                    }

                    if (password.isNullOrBlank() && !rawTextValue.isNullOrBlank()) {
                        val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.passwordId
                        if (byDetectedId || isLikelyPasswordField(node)) {
                            password = rawTextValue
                            logger.d(TAG, "extractSavedValues: captured password from context=$contextIndex")
                        }
                    }

                    if (cardholderName.isNullOrBlank()) {
                        val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.cardholderNameId
                        if (byDetectedId || isLikelyCardholderNameField(node)) {
                            cardholderName = trimmedTextValue
                            logger.d(TAG, "extractSavedValues: captured cardholderName from context=$contextIndex")
                        }
                    }

                    if (cardNumber.isNullOrBlank()) {
                        val normalizedCardNumber = rawTextValue.orEmpty().filter(Char::isDigit)
                        val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.cardNumberId
                        if (normalizedCardNumber.isNotBlank() && (byDetectedId || isLikelyCardNumberField(node))) {
                            cardNumber = normalizedCardNumber
                            logger.d(TAG, "extractSavedValues: captured cardNumber from context=$contextIndex")
                        }
                    }

                    if (cardExpirationDate.isNullOrBlank()) {
                        val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.cardExpirationDateId
                        if (byDetectedId || isLikelyCardExpirationDateField(node)) {
                            cardExpirationDate = trimmedTextValue
                            logger.d(TAG, "extractSavedValues: captured cardExpirationDate from context=$contextIndex")
                        }
                    }

                    if (cardExpirationMonth.isNullOrBlank()) {
                        val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.cardExpirationMonthId
                        if (byDetectedId || isLikelyCardExpirationMonthField(node)) {
                            cardExpirationMonth = trimmedTextValue
                            logger.d(TAG, "extractSavedValues: captured cardExpirationMonth from context=$contextIndex")
                        }
                    }

                    if (cardExpirationYear.isNullOrBlank()) {
                        val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.cardExpirationYearId
                        if (byDetectedId || isLikelyCardExpirationYearField(node)) {
                            cardExpirationYear = trimmedTextValue
                            logger.d(TAG, "extractSavedValues: captured cardExpirationYear from context=$contextIndex")
                        }
                    }

                    if (cardSecurityCode.isNullOrBlank()) {
                        val byDetectedId = nodeAutofillId != null && nodeAutofillId == detectedFields.cardSecurityCodeId
                        if (byDetectedId || isLikelyCardSecurityCodeField(node)) {
                            cardSecurityCode = trimmedTextValue
                            logger.d(TAG, "extractSavedValues: captured cardSecurityCode from context=$contextIndex")
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
        }

        val resolvedPackageName = NativeAppMetadataResolver.chooseBestPackageName(
            activityPackageName = activityComponent?.packageName ?: capturedPackageName,
            observedPackages = observedPackageNames,
            ownPackageName = packageName
        ).orEmpty().ifBlank { UNKNOWN_PACKAGE }
        val resolvedContentType = when {
            detectedContentType != null -> detectedContentType
            !cardNumber.isNullOrBlank() -> AutofillContentType.CARD
            !username.isNullOrBlank() || !password.isNullOrBlank() -> AutofillContentType.LOGIN
            else -> null
        }

        return when (resolvedContentType) {
            AutofillContentType.CARD -> {
                val normalizedCardNumber = cardNumber.orEmpty().filter(Char::isDigit)
                if (normalizedCardNumber.isBlank()) {
                    logger.d(TAG, "extractSavedValues: no card number found")
                    null
                } else {
                    val (expirationMonth, expirationYear) = parseCardExpiration(
                        rawExpirationDate = cardExpirationDate,
                        rawMonth = cardExpirationMonth,
                        rawYear = cardExpirationYear
                    )
                    SavedCredentialData(
                        credentialType = CredentialType.CARD,
                        username = cardholderName?.takeIf { it.isNotBlank() }
                            ?: normalizedCardNumber.takeLast(CARD_LABEL_DIGITS).ifBlank { null },
                        password = null,
                        cardData = CardData(
                            cardholderName = cardholderName?.takeIf { it.isNotBlank() },
                            cardNumber = normalizedCardNumber,
                            expirationMonth = expirationMonth,
                            expirationYear = expirationYear,
                            securityCode = cardSecurityCode?.takeIf { it.isNotBlank() }
                        ),
                        packageName = resolvedPackageName,
                        webDomain = normalizeWebDomain(webDomain),
                        activityComponent = activityComponent
                    )
                }
            }

            AutofillContentType.LOGIN -> {
                if (username.isNullOrBlank() && password.isNullOrBlank()) {
                    logger.d(TAG, "extractSavedValues: no username/password found")
                    null
                } else {
                    SavedCredentialData(
                        credentialType = if (password.isNullOrBlank()) {
                            CredentialType.ID_ONLY
                        } else {
                            CredentialType.PASSWORD
                        },
                        username = username,
                        password = password,
                        cardData = null,
                        packageName = resolvedPackageName,
                        webDomain = normalizeWebDomain(webDomain),
                        activityComponent = activityComponent
                    )
                }
            }

            null -> null
        }
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

    private fun isLikelyCardholderNameField(node: AssistStructure.ViewNode): Boolean {
        val joined = buildFieldTokenText(node)
        return joined.contains("cc-name") ||
            joined.contains("cardholder") ||
            joined.contains("nameoncard") ||
            joined.contains("名義")
    }

    private fun isLikelyCardNumberField(node: AssistStructure.ViewNode): Boolean {
        val joined = buildFieldTokenText(node)
        return joined.contains("creditcard") ||
            joined.contains("cardnumber") ||
            joined.contains("cc-number") ||
            joined.contains("カード番号")
    }

    private fun isLikelyCardExpirationDateField(node: AssistStructure.ViewNode): Boolean {
        val joined = buildFieldTokenText(node)
        return joined.contains("cc-exp") ||
            joined.contains("expiry") ||
            joined.contains("expiration") ||
            joined.contains("有効期限")
    }

    private fun isLikelyCardExpirationMonthField(node: AssistStructure.ViewNode): Boolean {
        val joined = buildFieldTokenText(node)
        return joined.contains("cc-exp-month") ||
            joined.contains("expmonth") ||
            joined.contains("expirationmonth")
    }

    private fun isLikelyCardExpirationYearField(node: AssistStructure.ViewNode): Boolean {
        val joined = buildFieldTokenText(node)
        return joined.contains("cc-exp-year") ||
            joined.contains("expyear") ||
            joined.contains("expirationyear")
    }

    private fun isLikelyCardSecurityCodeField(node: AssistStructure.ViewNode): Boolean {
        val joined = buildFieldTokenText(node)
        return joined.contains("cvc") ||
            joined.contains("cvv") ||
            joined.contains("securitycode") ||
            joined.contains("セキュリティコード")
    }

    private fun buildFieldTokenText(node: AssistStructure.ViewNode): String {
        return buildString {
            append(node.autofillHints?.joinToString(separator = " ") { it.lowercase() }.orEmpty())
            append(' ')
            append(node.idEntry?.lowercase().orEmpty())
            append(' ')
            append(node.hint?.lowercase().orEmpty())
        }
    }

    private fun detectedTargetIds(targets: SmartFieldDetector.DetectedFields): List<AutofillId> {
        return (listOfNotNull(
            targets.usernameId,
            targets.passwordId,
            targets.cardholderNameId,
            targets.cardNumberId,
            targets.cardExpirationDateId,
            targets.cardExpirationMonthId,
            targets.cardExpirationYearId,
            targets.cardSecurityCodeId,
            targets.otpId
        )).distinct()
    }

    private fun cardTargetIds(targets: SmartFieldDetector.DetectedFields): List<AutofillId> {
        return listOfNotNull(
            targets.cardholderNameId,
            targets.cardNumberId,
            targets.cardExpirationDateId,
            targets.cardExpirationMonthId,
            targets.cardExpirationYearId,
            targets.cardSecurityCodeId
        ).distinct()
    }

    private fun hasCardTargets(targets: SmartFieldDetector.DetectedFields): Boolean {
        return cardTargetIds(targets).isNotEmpty()
    }

    private fun determineContentType(targets: SmartFieldDetector.DetectedFields): AutofillContentType? {
        val hasLoginTargets = targets.usernameId != null || targets.passwordId != null
        val hasCardTargets = hasCardTargets(targets)
        val focusedId = targets.focusedId

        return when {
            hasCardTargets && !hasLoginTargets -> AutofillContentType.CARD
            hasLoginTargets && !hasCardTargets -> AutofillContentType.LOGIN
            hasCardTargets && hasLoginTargets -> when {
                focusedId != null && focusedId in cardTargetIds(targets) -> AutofillContentType.CARD
                focusedId != null && focusedId in listOfNotNull(targets.usernameId, targets.passwordId) -> AutofillContentType.LOGIN
                targets.focusedNodeInfo?.credentialKind == SmartFieldDetector.CredentialKind.CARD -> AutofillContentType.CARD
                targets.focusedNodeInfo?.credentialKind == SmartFieldDetector.CredentialKind.LOGIN -> AutofillContentType.LOGIN
                else -> AutofillContentType.LOGIN
            }

            else -> null
        }
    }

    private fun extractTargetFieldValues(
        structure: AssistStructure,
        targets: SmartFieldDetector.DetectedFields
    ): FieldValueSnapshot {
        val trackedIds = detectedTargetIds(targets).toSet()
        if (trackedIds.isEmpty()) {
            return FieldValueSnapshot(emptyMap())
        }

        val valuesById = mutableMapOf<AutofillId, String>()

        fun traverse(node: AssistStructure.ViewNode) {
            val autofillId = node.autofillId
            if (autofillId != null && autofillId in trackedIds && autofillId !in valuesById) {
                val rawValue = node.autofillValue
                    ?.takeIf { it.isText }
                    ?.textValue
                    ?.toString()
                    ?: node.text?.toString()
                rawValue
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { valuesById[autofillId] = it }
            }

            for (index in 0 until node.childCount) {
                traverse(node.getChildAt(index))
            }
        }

        for (windowIndex in 0 until structure.windowNodeCount) {
            traverse(structure.getWindowNodeAt(windowIndex).rootViewNode)
        }

        return FieldValueSnapshot(valuesById)
    }

    private fun shouldSuppressAutofillForFilledFields(
        contentType: AutofillContentType,
        targets: SmartFieldDetector.DetectedFields,
        currentFieldValues: FieldValueSnapshot
    ): Boolean {
        return when (contentType) {
            AutofillContentType.LOGIN -> {
                currentFieldValues.hasTextOrMissing(targets.usernameId) &&
                    currentFieldValues.hasTextOrMissing(targets.passwordId)
            }

            AutofillContentType.CARD -> {
                val targetIds = cardTargetIds(targets)
                targetIds.isNotEmpty() && targetIds.all(currentFieldValues::hasText)
            }
        }
    }

    private fun describeFilledFieldSuppression(
        contentType: AutofillContentType,
        targets: SmartFieldDetector.DetectedFields,
        currentFieldValues: FieldValueSnapshot
    ): String {
        return when (contentType) {
            AutofillContentType.LOGIN -> {
                val usernameFilled = targets.usernameId?.let(currentFieldValues::hasText) == true
                val passwordFilled = targets.passwordId?.let(currentFieldValues::hasText) == true
                "login fields already satisfied usernameFilled=$usernameFilled passwordFilled=$passwordFilled usernameMissing=${targets.usernameId == null} passwordMissing=${targets.passwordId == null}"
            }

            AutofillContentType.CARD -> {
                val targetIds = cardTargetIds(targets)
                val filledCount = targetIds.count(currentFieldValues::hasText)
                "card fields already satisfied filledTargets=$filledCount/${targetIds.size}"
            }
        }
    }

    private fun parseCardExpiration(
        rawExpirationDate: String?,
        rawMonth: String?,
        rawYear: String?
    ): Pair<Int?, Int?> {
        val monthFromField = parseCardMonth(rawMonth)
        val yearFromField = parseCardYear(rawYear)
        if (monthFromField != null || yearFromField != null) {
            return monthFromField to yearFromField
        }

        val normalized = rawExpirationDate.orEmpty().filter(Char::isDigit)
        return when (normalized.length) {
            4 -> parseCardMonth(normalized.take(2)) to parseCardYear(normalized.takeLast(2))
            6 -> parseCardMonth(normalized.take(2)) to parseCardYear(normalized.takeLast(4))
            else -> null to null
        }
    }

    private fun parseCardMonth(rawValue: String?): Int? {
        val month = rawValue.orEmpty().filter(Char::isDigit).toIntOrNull() ?: return null
        return month.takeIf { it in 1..12 }
    }

    private fun parseCardYear(rawValue: String?): Int? {
        val digits = rawValue.orEmpty().filter(Char::isDigit)
        return when (digits.length) {
            0 -> null
            2 -> 2000 + digits.toInt()
            4 -> digits.toIntOrNull()
            else -> null
        }
    }

    private suspend fun buildCredentialForSave(savedData: SavedCredentialData): Credential {
        val normalizedWebDomain = normalizeWebDomain(savedData.webDomain)
        val resolvedPackageName = savedData.packageName.takeUnless {
            it == UNKNOWN_PACKAGE || NativeAppMetadataResolver.isGenericPackageName(it, packageName)
        }
        val resolvedAppLabel = resolveTargetApplicationLabel(
            activityComponent = savedData.activityComponent,
            packageName = resolvedPackageName
        )
        val resolvedServiceName = inferServiceName(
            packageName = resolvedPackageName ?: savedData.packageName,
            webDomain = normalizedWebDomain,
            appLabel = resolvedAppLabel
        )
        val existingCredential = findExistingCredentialForSave(
            savedData = savedData,
            resolvedPackageName = resolvedPackageName,
            normalizedWebDomain = normalizedWebDomain,
            resolvedServiceName = resolvedServiceName
        )

        return mergeSavedCredential(
            existingCredential = existingCredential,
            savedData = savedData,
            resolvedPackageName = resolvedPackageName,
            normalizedWebDomain = normalizedWebDomain,
            resolvedServiceName = resolvedServiceName
        )
    }

    private suspend fun findExistingCredentialForSave(
        savedData: SavedCredentialData,
        resolvedPackageName: String?,
        normalizedWebDomain: String?,
        resolvedServiceName: String
    ): Credential? {
        if (savedData.credentialType == CredentialType.CARD) {
            return null
        }

        val normalizedUsername = normalizeCredentialIdentifier(savedData.username) ?: return null
        val candidates = credentialRepository.getAll().first()
            .asSequence()
            .filter { candidate -> !candidate.isPasskey && !candidate.isCard }
            .map { candidate ->
                candidate to scoreExistingCredentialForSave(
                    candidate = candidate,
                    normalizedUsername = normalizedUsername,
                    resolvedPackageName = resolvedPackageName,
                    normalizedWebDomain = normalizedWebDomain,
                    resolvedServiceName = resolvedServiceName
                )
            }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<Credential, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .toList()

        val bestMatch = candidates.firstOrNull() ?: return null
        return when {
            bestMatch.second >= STRONG_SAVE_MATCH_SCORE -> bestMatch.first
            bestMatch.second >= WEAK_SAVE_MATCH_SCORE && candidates.size == 1 -> bestMatch.first
            else -> null
        }
    }

    private fun scoreExistingCredentialForSave(
        candidate: Credential,
        normalizedUsername: String,
        resolvedPackageName: String?,
        normalizedWebDomain: String?,
        resolvedServiceName: String
    ): Int {
        if (normalizeCredentialIdentifier(candidate.username) != normalizedUsername) {
            return 0
        }

        var score = 0
        if (!resolvedPackageName.isNullOrBlank() && candidate.packageName == resolvedPackageName) {
            score += 120
        }

        val candidateDomain = normalizeWebDomain(candidate.serviceUrl)
        if (!normalizedWebDomain.isNullOrBlank() && candidateDomain == normalizedWebDomain) {
            score += 90
        } else if (!normalizedWebDomain.isNullOrBlank()) {
            val targetMainDomain = extractMainDomain(normalizedWebDomain)
            val candidateMainDomain = candidateDomain?.let(::extractMainDomain)
            if (!targetMainDomain.isNullOrBlank() && targetMainDomain == candidateMainDomain) {
                score += 60
            }
        }

        if (
            NativeAppMetadataResolver.normalizeLookupLabel(candidate.serviceName) ==
                NativeAppMetadataResolver.normalizeLookupLabel(resolvedServiceName)
        ) {
            score += 50
        }

        if (
            NativeAppMetadataResolver.isGenericPackageName(candidate.packageName, packageName) ||
            NativeAppMetadataResolver.isWeakServiceName(candidate.serviceName, candidate.packageName)
        ) {
            score += WEAK_ASSOCIATION_RECOVERY_SCORE
        }

        return score
    }

    private fun mergeSavedCredential(
        existingCredential: Credential?,
        savedData: SavedCredentialData,
        resolvedPackageName: String?,
        normalizedWebDomain: String?,
        resolvedServiceName: String
    ): Credential {
        val savedPassword = savedData.password?.takeIf { it.isNotBlank() }
        if (existingCredential == null) {
            return Credential(
                serviceName = resolvedServiceName,
                serviceUrl = normalizedWebDomain,
                packageName = resolvedPackageName,
                username = savedData.username.orEmpty(),
                password = savedPassword,
                category = if (savedData.credentialType == CredentialType.CARD) {
                    DEFAULT_CARD_CATEGORY
                } else {
                    DEFAULT_SAVE_CATEGORY
                },
                cardData = savedData.cardData,
                credentialType = if (!savedPassword.isNullOrBlank()) {
                    CredentialType.PASSWORD
                } else {
                    savedData.credentialType
                }
            )
        }

        val packageToPersist = when {
            NativeAppMetadataResolver.shouldReplacePackageName(
                currentPackageName = existingCredential.packageName,
                candidatePackageName = resolvedPackageName,
                ownPackageName = packageName
            ) -> resolvedPackageName
            else -> existingCredential.packageName ?: resolvedPackageName
        }
        val serviceNameToPersist = when {
            NativeAppMetadataResolver.shouldReplaceServiceName(
                currentServiceName = existingCredential.serviceName,
                candidateServiceName = resolvedServiceName,
                currentPackageName = existingCredential.packageName ?: packageToPersist
            ) -> resolvedServiceName
            else -> existingCredential.serviceName
        }
        val passwordToPersist = savedPassword ?: existingCredential.password
        val cardDataToPersist = savedData.cardData ?: existingCredential.cardData

        return existingCredential.copy(
            serviceName = serviceNameToPersist,
            serviceUrl = normalizedWebDomain ?: existingCredential.serviceUrl,
            packageName = packageToPersist,
            username = savedData.username?.takeIf { it.isNotBlank() } ?: existingCredential.username,
            password = passwordToPersist,
            cardData = cardDataToPersist,
            credentialType = when {
                cardDataToPersist != null -> CredentialType.CARD
                !passwordToPersist.isNullOrBlank() -> CredentialType.PASSWORD
                else -> CredentialType.ID_ONLY
            }
        )
    }

    private fun inferServiceName(packageName: String, webDomain: String?, appLabel: String?): String {
        return NativeAppMetadataResolver.chooseServiceName(
            webDomain = webDomain,
            appLabel = appLabel,
            packageName = packageName,
            defaultServiceName = DEFAULT_SERVICE_NAME
        )
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

    private fun resolveTargetApplicationLabel(
        activityComponent: ComponentName?,
        packageName: String?
    ): String? {
        val activityLabel = activityComponent?.let(::resolveActivityLabel)
            ?.takeIf { !NativeAppMetadataResolver.isWeakServiceName(it, packageName) }
        return activityLabel ?: packageName?.let(::resolveApplicationLabel)
    }

    private fun resolveActivityLabel(activityComponent: ComponentName): String? {
        return runCatching {
            val activityInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getActivityInfo(
                    activityComponent,
                    PackageManager.ComponentInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getActivityInfo(activityComponent, 0)
            }
            activityInfo.loadLabel(packageManager)?.toString()?.trim()
        }.onFailure { throwable ->
            logger.w(TAG, "resolveActivityLabel: failed component=$activityComponent", throwable)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun normalizeCredentialIdentifier(value: String?): String? {
        return value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }
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
        contentType: AutofillContentType
    ): List<Credential> {
        return credentials.filter { credential ->
            when (contentType) {
                AutofillContentType.CARD -> credential.isCard
                AutofillContentType.LOGIN -> {
                    when {
                        credential.isPasskey || credential.isCard -> false
                        !credential.hasPassword && credential.credentialType != CredentialType.ID_ONLY -> false
                        else -> true
                    }
                }
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
        val credentialType: CredentialType,
        val username: String?,
        val password: String?,
        val cardData: CardData?,
        val packageName: String,
        val webDomain: String?,
        val activityComponent: ComponentName?
    )

    private data class FieldValueSnapshot(
        val valuesById: Map<AutofillId, String>
    ) {
        fun hasTextOrMissing(autofillId: AutofillId?): Boolean {
            return autofillId == null || !valuesById[autofillId].isNullOrBlank()
        }

        fun hasText(autofillId: AutofillId): Boolean {
            return !valuesById[autofillId].isNullOrBlank()
        }
    }

    private enum class AutofillContentType {
        LOGIN,
        CARD
    }

    private companion object {
        const val TAG = "SecureVaultAutofill"
        const val APP_PACKAGE_NAME = "com.securevault.app"
        const val MAX_DATASET_ITEMS = 8
        const val UNKNOWN_PACKAGE = "unknown"
        const val DEFAULT_SERVICE_NAME = "Saved Login"
        const val DEFAULT_SAVE_CATEGORY = "login"
        const val DEFAULT_CARD_CATEGORY = "finance"
        const val SAVE_FAILURE_MESSAGE = "保存に失敗しました"
        const val AUTOFILL_SAVE_CHANNEL_ID = "autofill_save"
        const val SAVE_NOTIFICATION_BASE_ID = 12000
        const val OTP_REQUEST_CODE_BASE = 20000
        const val PLACEHOLDER_PASSWORD = "••••••••"
        const val LOCKED_LABEL_PREFIX = "\uD83D\uDD12 "
        const val BROWSER_GUIDE_PREFS = "browser_guide_prefs"
        const val BROWSER_GUIDE_NOTIFICATION_ID = 13000
        const val CARD_LABEL_DIGITS = 4
        const val RECENT_OTP_MAX_AGE_MS = 5 * 60_000L
        const val STRONG_SAVE_MATCH_SCORE = 50
        const val WEAK_SAVE_MATCH_SCORE = 25
        const val WEAK_ASSOCIATION_RECOVERY_SCORE = 25

        val FOCUSED_PASSWORD_HINT_TOKENS = listOf(
            "pass",
            "password",
            "pwd",
            "pin",
            "secret",
            "パスワード",
            "暗証番号",
            "パスコード"
        )

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
