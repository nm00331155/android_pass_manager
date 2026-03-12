package com.securevault.app.service.otp

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.api.phone.SmsCodeAutofillClient
import com.google.android.gms.auth.api.phone.SmsCodeRetriever
import com.google.android.gms.auth.api.phone.SmsRetrieverStatusCodes
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * SMS Code Autofill API を用いて SMS から OTP を取得するマネージャー。
 */
@Singleton
class SmsOtpManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val client: SmsCodeAutofillClient = SmsCodeRetriever.getAutofillClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var pendingResolutionException: ResolvableApiException? = null

    private val _otpResult = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val otpResult: SharedFlow<String> = _otpResult.asSharedFlow()

    init {
        scope.launch {
            receiverOtpFlow.collect { code ->
                _otpResult.emit(code)
            }
        }
    }

    /**
     * SMS OTP 監視を開始する。
     */
    suspend fun startListening(): SmsOtpStatus {
        pendingResolutionException = null

        if (!isAvailablePlatform()) {
            return SmsOtpStatus.UNAVAILABLE
        }

        val ongoingRequest = runCatching {
            client.hasOngoingSmsRequest(context.packageName).awaitResult()
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to check ongoing SMS request", throwable)
            return SmsOtpStatus.UNAVAILABLE
        }

        if (ongoingRequest) {
            return SmsOtpStatus.ALREADY_IN_PROGRESS
        }

        val permission = runCatching {
            checkPermission()
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to check SMS autofill permission", throwable)
            return SmsOtpStatus.UNAVAILABLE
        }

        if (permission == SmsCodeAutofillClient.PermissionState.DENIED) {
            return SmsOtpStatus.PERMISSION_DENIED
        }

        return runCatching {
            client.startSmsCodeRetriever().awaitResult()
            Log.i(TAG, "SMS code retriever started")
            SmsOtpStatus.LISTENING
        }.getOrElse { throwable ->
            val statusCode = extractStatusCode(throwable)
            if (
                throwable is ResolvableApiException &&
                statusCode == CommonStatusCodes.RESOLUTION_REQUIRED
            ) {
                pendingResolutionException = throwable
                Log.i(TAG, "SMS code retriever requires foreground resolution")
                SmsOtpStatus.RESOLUTION_REQUIRED
            } else if (
                statusCode == SmsRetrieverStatusCodes.API_NOT_AVAILABLE ||
                statusCode == SmsRetrieverStatusCodes.PLATFORM_NOT_SUPPORTED
            ) {
                SmsOtpStatus.UNAVAILABLE
            } else {
                Log.w(TAG, "Failed to start SMS code retriever", throwable)
                SmsOtpStatus.UNAVAILABLE
            }
        }
    }

    fun consumePendingResolution(): ResolvableApiException? {
        val exception = pendingResolutionException
        pendingResolutionException = null
        return exception
    }

    /**
     * 権限状態を確認する。
     */
    suspend fun checkPermission(): @SmsCodeAutofillClient.PermissionState Int {
        return client.checkPermissionState().awaitResult()
    }

    /**
     * 受信 SMS からOTPコードを抽出する正規表現。
     */
    fun extractOtp(message: String): String? {
        val pattern = Regex("\\b(\\d{4,8})\\b")
        return pattern.find(message)?.groupValues?.get(1)
    }

    private fun isAvailablePlatform(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }

        return GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private fun extractStatusCode(throwable: Throwable): Int? {
        val apiException = throwable as? com.google.android.gms.common.api.ApiException
        return apiException?.statusCode
    }

    private suspend fun <T> Task<T>.awaitResult(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
            addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
            addOnCanceledListener {
                if (continuation.isActive) {
                    continuation.cancel(CancellationException("Task was cancelled"))
                }
            }
        }
    }

    companion object {
        private const val TAG = "SmsOtpManager"

        private val receiverOtpFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)

        /**
         * SmsOtpReceiver から OTP 受信結果を転送する。
         */
        fun publishOtpFromReceiver(code: String) {
            receiverOtpFlow.tryEmit(code)
        }
    }
}

/**
 * SMS OTP 監視開始結果。
 */
enum class SmsOtpStatus {
    LISTENING,
    ALREADY_IN_PROGRESS,
    RESOLUTION_REQUIRED,
    PERMISSION_DENIED,
    UNAVAILABLE
}
