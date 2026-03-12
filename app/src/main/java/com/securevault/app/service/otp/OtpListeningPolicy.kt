package com.securevault.app.service.otp

internal enum class OtpStartOutcome {
    WAIT_FOR_CODE,
    NO_SOURCE_ENABLED,
    SMS_RESOLUTION_REQUIRED,
    SMS_PERMISSION_DENIED,
    SMS_UNAVAILABLE
}

internal object OtpListeningPolicy {
    fun resolve(
        smsEnabled: Boolean,
        notificationEnabled: Boolean,
        clipboardEnabled: Boolean,
        smsStatus: SmsOtpStatus?
    ): OtpStartOutcome {
        if (!smsEnabled && !notificationEnabled && !clipboardEnabled) {
            return OtpStartOutcome.NO_SOURCE_ENABLED
        }

        if (!smsEnabled) {
            return OtpStartOutcome.WAIT_FOR_CODE
        }

        val hasFallbackSource = notificationEnabled || clipboardEnabled
        return when (smsStatus) {
            SmsOtpStatus.LISTENING,
            SmsOtpStatus.ALREADY_IN_PROGRESS -> OtpStartOutcome.WAIT_FOR_CODE

            SmsOtpStatus.RESOLUTION_REQUIRED -> OtpStartOutcome.SMS_RESOLUTION_REQUIRED

            SmsOtpStatus.PERMISSION_DENIED -> {
                if (hasFallbackSource) OtpStartOutcome.WAIT_FOR_CODE else OtpStartOutcome.SMS_PERMISSION_DENIED
            }

            SmsOtpStatus.UNAVAILABLE,
            null -> {
                if (hasFallbackSource) OtpStartOutcome.WAIT_FOR_CODE else OtpStartOutcome.SMS_UNAVAILABLE
            }
        }
    }
}