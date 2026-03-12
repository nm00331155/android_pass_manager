package com.securevault.app.service.otp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.util.Log
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.securevault.app.R
import com.securevault.app.data.store.SecuritySettingsPreferences
import com.securevault.app.data.store.securitySettingsDataStore
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SMS OTP 取得を行い、Autofill 用 Dataset を返却するアクティビティ。
 */
@AndroidEntryPoint
class SmsOtpActivity : ComponentActivity() {

    @Inject
    lateinit var otpManager: OtpManager

    @Inject
    @ApplicationContext
    lateinit var appContext: Context

    private var otpAutofillId: AutofillId? = null
    private var clipboardMonitoringStarted = false
    private var pendingClipboardResumeCheck = false
    private var clipboardTextBeforeMailLaunch: String? = null
    private var resultDispatched = false
    private var smsResolutionRequested = false
    private var otpWaitJob: Job? = null
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var openMailButton: Button
    private lateinit var cancelButton: Button

    /**
     * OTP 取得を開始し、結果を Autofill に返す。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        otpAutofillId = getAutofillIdExtra(EXTRA_OTP_AUTOFILL_ID)
        if (otpAutofillId == null) {
            finishCanceled()
            return
        }

        setContentView(R.layout.activity_sms_otp_waiting)
        setFinishOnTouchOutside(false)
        titleView = findViewById(R.id.title)
        messageView = findViewById(R.id.message)
        progressView = findViewById(R.id.progress)
        openMailButton = findViewById(R.id.actionOpenMail)
        cancelButton = findViewById(R.id.actionCancel)
        renderState(
            title = getString(R.string.otp_waiting_for_code),
            message = getString(R.string.otp_waiting_message),
            showProgress = true
        )
        openMailButton.setOnClickListener {
            if (!clipboardMonitoringStarted) {
                otpManager.startClipboardMonitoring()
                clipboardMonitoringStarted = true
            }

            val emailIntent = buildEmailAppIntent()
            if (emailIntent == null) {
                Toast.makeText(this, R.string.otp_open_email_app_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            clipboardTextBeforeMailLaunch = otpManager.getCurrentClipboardText()
            pendingClipboardResumeCheck = true
            try {
                startActivity(emailIntent)
            } catch (_: ActivityNotFoundException) {
                pendingClipboardResumeCheck = false
                clipboardTextBeforeMailLaunch = null
                Toast.makeText(this, R.string.otp_open_email_app_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        cancelButton.setOnClickListener {
            finishCanceled()
        }

        startOtpCollection()
    }

    private fun startOtpCollection() {
        otpWaitJob?.cancel()
        otpWaitJob = lifecycleScope.launch {
            val settings = appContext.securitySettingsDataStore.data.first()
            val smsEnabled = settings[SecuritySettingsPreferences.OTP_SMS_ENABLED_KEY]
                ?: SecuritySettingsPreferences.DEFAULT_OTP_SMS_ENABLED
            val notificationEnabled = settings[SecuritySettingsPreferences.OTP_NOTIFICATION_ENABLED_KEY]
                ?: SecuritySettingsPreferences.DEFAULT_OTP_NOTIFICATION_ENABLED
            val clipboardEnabled = settings[SecuritySettingsPreferences.OTP_CLIPBOARD_ENABLED_KEY]
                ?: SecuritySettingsPreferences.DEFAULT_OTP_CLIPBOARD_ENABLED

            val allowedSources = buildSet {
                if (smsEnabled) add(OtpSource.SMS)
                if (notificationEnabled) add(OtpSource.NOTIFICATION)
                if (clipboardEnabled) add(OtpSource.CLIPBOARD)
            }

            val smsStatus = if (smsEnabled) {
                otpManager.startSmsListening()
            } else {
                null
            }
            Log.i(TAG, "OTP collection started smsEnabled=$smsEnabled notificationEnabled=$notificationEnabled clipboardEnabled=$clipboardEnabled smsStatus=$smsStatus")

            val startOutcome = OtpListeningPolicy.resolve(
                smsEnabled = smsEnabled,
                notificationEnabled = notificationEnabled,
                clipboardEnabled = clipboardEnabled,
                smsStatus = smsStatus
            )
            Log.i(TAG, "OTP start outcome=$startOutcome")

            if (startOutcome == OtpStartOutcome.SMS_RESOLUTION_REQUIRED) {
                if (!smsResolutionRequested && launchSmsResolutionIfNeeded()) {
                    smsResolutionRequested = true
                    renderState(
                        title = getString(R.string.otp_waiting_for_code),
                        message = getString(R.string.otp_sms_permission_denied_message),
                        showProgress = false
                    )
                    return@launch
                }
            }

            when (startOutcome) {
                OtpStartOutcome.NO_SOURCE_ENABLED,
                OtpStartOutcome.SMS_RESOLUTION_REQUIRED,
                OtpStartOutcome.SMS_PERMISSION_DENIED,
                OtpStartOutcome.SMS_UNAVAILABLE -> {
                    renderState(
                        title = getString(R.string.otp_waiting_for_code),
                        message = getString(R.string.otp_manual_assist_message),
                        showProgress = false
                    )
                }

                OtpStartOutcome.WAIT_FOR_CODE -> Unit
            }

            if (clipboardEnabled) {
                otpManager.startClipboardMonitoring()
                clipboardMonitoringStarted = true
            }

            if (allowedSources.isNotEmpty()) {
                otpManager.getRecentOtp(
                    allowedSources = allowedSources,
                    maxAgeMs = RECENT_OTP_MAX_AGE_MS
                )?.let { recentEvent ->
                    showDetectedOtpAndReturn(recentEvent.code)
                    return@launch
                }
            }

            if (startOutcome != OtpStartOutcome.WAIT_FOR_CODE) {
                return@launch
            }

            val event = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                otpManager.otpEvents
                    .filter { it.source in allowedSources }
                    .first()
            }
            Log.i(TAG, "OTP wait completed event=${event?.source}")

            if (event == null) {
                showTerminalMessageAndClose(
                    title = getString(R.string.otp_timeout_title),
                    message = getString(R.string.otp_timeout_message)
                )
                return@launch
            }

            showDetectedOtpAndReturn(event.code)
        }
    }

    override fun onResume() {
        super.onResume()

        if (!pendingClipboardResumeCheck || resultDispatched) {
            return
        }

        if (!clipboardMonitoringStarted) {
            return
        }

        pendingClipboardResumeCheck = false
        val clipboardTextAfterReturn = otpManager.getCurrentClipboardText()
        if (clipboardTextAfterReturn == clipboardTextBeforeMailLaunch) {
            clipboardTextBeforeMailLaunch = clipboardTextAfterReturn
            return
        }

        clipboardTextBeforeMailLaunch = clipboardTextAfterReturn
        otpManager.detectCurrentClipboardOtp()?.let { clipboardOtp ->
            lifecycleScope.launch {
                showDetectedOtpAndReturn(clipboardOtp.code)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE_SMS_RESOLUTION || resultDispatched) {
            return
        }

        Log.i(TAG, "SMS resolution result=$resultCode")
        startOtpCollection()
    }

    /**
     * 受信レシーバーを確実に解除する。
     */
    override fun onDestroy() {
        otpWaitJob?.cancel()
        if (clipboardMonitoringStarted) {
            otpManager.stopClipboardMonitoring()
            clipboardMonitoringStarted = false
        }
        super.onDestroy()
    }

    private fun returnOtpResult(code: String) {
        if (resultDispatched) {
            return
        }
        resultDispatched = true

        val targetId = otpAutofillId ?: return finishCanceled()

        val dataset = Dataset.Builder(
            RemoteViews(packageName, R.layout.autofill_otp_item).apply {
                setTextViewText(R.id.label, getString(R.string.otp_detected, code))
            }
        ).apply {
            setValue(targetId, AutofillValue.forText(code))
        }.build()

        val resultIntent = Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
        }

        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private suspend fun showDetectedOtpAndReturn(code: String) {
        renderState(
            title = getString(R.string.otp_detected_title),
            message = getString(R.string.otp_detected, code),
            showProgress = false
        )
        Log.i(TAG, "OTP detected source result code=$code")
        delay(RESULT_MESSAGE_VISIBLE_MS)
        returnOtpResult(code)
    }

    private fun renderState(
        title: CharSequence,
        message: CharSequence,
        showProgress: Boolean
    ) {
        titleView.text = title
        messageView.text = message
        progressView.visibility = if (showProgress) View.VISIBLE else View.GONE
    }

    private suspend fun showTerminalMessageAndClose(
        title: CharSequence,
        message: CharSequence
    ) {
        renderState(
            title = title,
            message = message,
            showProgress = false
        )
        delay(ERROR_MESSAGE_VISIBLE_MS)
        finishCanceled()
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
        if (resultDispatched) {
            return
        }
        Log.i(TAG, "OTP flow canceled")
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun launchSmsResolutionIfNeeded(): Boolean {
        val resolution = otpManager.consumePendingSmsResolution() ?: return false
        return try {
            resolution.startResolutionForResult(this, REQUEST_CODE_SMS_RESOLUTION)
            true
        } catch (exception: IntentSender.SendIntentException) {
            Log.w(TAG, "Failed to launch SMS resolution", exception)
            false
        }
    }

    private fun buildEmailAppIntent(): Intent? {
        val emailAppIntents = linkedMapOf<String, Intent>()
        addEmailAppIntents(
            emailAppIntents,
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_EMAIL)
            }
        )
        addEmailAppIntents(
            emailAppIntents,
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        )

        val chooserTargets = emailAppIntents.values.toList()
        if (chooserTargets.isEmpty()) {
            return null
        }

        return Intent.createChooser(
            chooserTargets.first(),
            getString(R.string.otp_open_email_app)
        ).apply {
            if (chooserTargets.size > 1) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, chooserTargets.drop(1).toTypedArray())
            }
        }
    }

    private fun addEmailAppIntents(
        emailAppIntents: MutableMap<String, Intent>,
        baseIntent: Intent
    ) {
        packageManager.queryIntentActivities(baseIntent, 0).forEach { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName ?: return@forEach
            val className = resolveInfo.activityInfo?.name ?: return@forEach
            if (emailAppIntents.containsKey(packageName)) {
                return@forEach
            }

            emailAppIntents[packageName] = Intent(baseIntent).apply {
                setClassName(packageName, className)
            }
        }
    }

    companion object {
        private const val TAG = "SmsOtpActivity"
        private const val REQUEST_CODE_SMS_RESOLUTION = 2001
        private const val LISTEN_TIMEOUT_MS = 90_000L
        private const val RECENT_OTP_MAX_AGE_MS = 5 * 60_000L
        private const val RESULT_MESSAGE_VISIBLE_MS = 450L
        private const val ERROR_MESSAGE_VISIBLE_MS = 1_200L

        const val EXTRA_OTP_AUTOFILL_ID = "otpAutofillId"
    }
}
