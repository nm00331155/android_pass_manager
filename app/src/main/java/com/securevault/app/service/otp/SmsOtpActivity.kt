package com.securevault.app.service.otp

import android.app.Activity
import android.os.Bundle

class SmsOtpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Phase 5 で SmsCodeAutofillClient によるOTP取得を実装する。
        setResult(RESULT_CANCELED)
        finish()
    }
}
