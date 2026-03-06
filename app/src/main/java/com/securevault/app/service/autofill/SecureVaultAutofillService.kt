package com.securevault.app.service.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log

class SecureVaultAutofillService : AutofillService() {

    override fun onConnected() {
        Log.i(TAG, "Autofill service connected")
    }

    override fun onDisconnected() {
        Log.i(TAG, "Autofill service disconnected")
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        val packageName = structure?.activityComponent?.packageName ?: "unknown"
        Log.d(TAG, "onFillRequest from package=$packageName")

        // Phase 4 で実データセット構築を実装する。
        callback.onSuccess(null)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        val packageName = structure?.activityComponent?.packageName ?: "unknown"
        Log.d(TAG, "onSaveRequest from package=$packageName")

        // Phase 4 で暗号化保存を実装する。
        callback.onSuccess()
    }

    private companion object {
        const val TAG = "SecureVaultAutofill"
    }
}
