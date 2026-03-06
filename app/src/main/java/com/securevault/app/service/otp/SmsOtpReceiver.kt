package com.securevault.app.service.otp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.google.android.gms.auth.api.phone.SmsCodeRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status

/**
 * SmsCodeRetriever の Broadcast を受信し OTP を抽出して共有するレシーバー。
 */
class SmsOtpReceiver : BroadcastReceiver() {

    /**
     * SMS_CODE_RETRIEVED_ACTION を受信した際に OTP を抽出して通知する。
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsCodeRetriever.SMS_CODE_RETRIEVED_ACTION) {
            return
        }

        val extras = intent.extras ?: return
        val status = extras.get(SmsCodeRetriever.EXTRA_STATUS) as? Status ?: return

        if (status.statusCode != CommonStatusCodes.SUCCESS) {
            Log.d(TAG, "SMS retriever status=${status.statusCode}")
            return
        }

        val smsCode = extras.getString(SmsCodeRetriever.EXTRA_SMS_CODE)
        val smsCodeLine = extras.getString(SmsCodeRetriever.EXTRA_SMS_CODE_LINE)
        val candidate = smsCode ?: smsCodeLine
        if (candidate.isNullOrBlank()) {
            return
        }

        val otp = extractOtp(candidate) ?: return
        SmsOtpManager.publishOtpFromReceiver(otp)
    }

    private fun extractOtp(text: String): String? {
        val pattern = Regex("\\b(\\d{4,8})\\b")
        return pattern.find(text)?.groupValues?.getOrNull(1)
    }

    private companion object {
        const val TAG = "SmsOtpReceiver"
    }
}
