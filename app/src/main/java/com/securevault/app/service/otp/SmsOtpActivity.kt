package com.securevault.app.service.otp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.Toast
import com.google.android.gms.auth.api.phone.SmsCodeAutofillClient
import com.google.android.gms.auth.api.phone.SmsCodeRetriever
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.common.api.Status
import com.securevault.app.R

/**
 * SMS OTP 取得を行い、Autofill 用 Dataset を返却するアクティビティ。
 */
class SmsOtpActivity : Activity() {

    private val smsClient: SmsCodeAutofillClient by lazy {
        SmsCodeRetriever.getAutofillClient(this)
    }

    private var otpAutofillId: AutofillId? = null
    private var receiverRegistered = false

    private val smsCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != SmsCodeRetriever.SMS_CODE_RETRIEVED_ACTION) {
                return
            }

            val extras = intent.extras ?: return
            val status = extras.get(SmsCodeRetriever.EXTRA_STATUS) as? Status ?: return

            when (status.statusCode) {
                CommonStatusCodes.SUCCESS -> {
                    val rawCode = extras.getString(SmsCodeRetriever.EXTRA_SMS_CODE)
                        ?: extras.getString(SmsCodeRetriever.EXTRA_SMS_CODE_LINE)
                        ?: return finishCanceled()

                    val otp = extractOtp(rawCode) ?: return finishCanceled()
                    showMessage(getString(R.string.otp_detected, otp))
                    returnOtpResult(otp)
                }

                CommonStatusCodes.TIMEOUT -> {
                    Log.d(TAG, "SMS code retrieval timed out")
                    finishCanceled()
                }

                else -> {
                    Log.d(TAG, "SMS code retrieval failed with status=${status.statusCode}")
                    finishCanceled()
                }
            }
        }
    }

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

        registerSmsReceiver()
        showMessage(getString(R.string.otp_sms_suggestion))
        startSmsCodeRetriever()
    }

    /**
     * 同意ダイアログの結果を受け取り、許可時に再度 OTP 取得を開始する。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE_SMS_CONSENT) {
            return
        }

        if (resultCode == RESULT_OK) {
            startSmsCodeRetriever()
        } else {
            finishCanceled()
        }
    }

    /**
     * 受信レシーバーを確実に解除する。
     */
    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(smsCodeReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun startSmsCodeRetriever() {
        smsClient.startSmsCodeRetriever()
            .addOnSuccessListener {
                Log.d(TAG, "SmsCodeRetriever started")
            }
            .addOnFailureListener { throwable ->
                val resolvable = throwable as? ResolvableApiException
                if (resolvable != null) {
                    runCatching {
                        resolvable.startResolutionForResult(this, REQUEST_CODE_SMS_CONSENT)
                    }.onFailure {
                        Log.w(TAG, "Failed to show SMS consent dialog", it)
                        finishCanceled()
                    }
                    return@addOnFailureListener
                }

                Log.w(TAG, "Failed to start SmsCodeRetriever", throwable)
                finishCanceled()
            }
    }

    private fun registerSmsReceiver() {
        if (receiverRegistered) {
            return
        }

        val filter = IntentFilter(SmsCodeRetriever.SMS_CODE_RETRIEVED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                smsCodeReceiver,
                filter,
                SmsRetriever.SEND_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(smsCodeReceiver, filter, SmsRetriever.SEND_PERMISSION, null)
        }
        receiverRegistered = true
    }

    private fun returnOtpResult(code: String) {
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

    private fun extractOtp(text: String): String? {
        val pattern = Regex("\\b(\\d{4,8})\\b")
        return pattern.find(text)?.groupValues?.getOrNull(1)
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val TAG = "SmsOtpActivity"
        private const val REQUEST_CODE_SMS_CONSENT = 1001

        const val EXTRA_OTP_AUTOFILL_ID = "otpAutofillId"
    }
}
