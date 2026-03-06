package com.securevault.app.service.autofill

import android.app.Activity
import android.os.Bundle

class AutofillAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Phase 4 で BiometricPrompt 認証を実装する。
        setResult(RESULT_CANCELED)
        finish()
    }
}
