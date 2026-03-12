package com.securevault.app.service.otp

import org.junit.Assert.assertEquals
import org.junit.Test

class OtpListeningPolicyTest {

    @Test
    fun `resolve returns no source enabled when nothing is available`() {
        val result = OtpListeningPolicy.resolve(
            smsEnabled = false,
            notificationEnabled = false,
            clipboardEnabled = false,
            smsStatus = null
        )

        assertEquals(OtpStartOutcome.NO_SOURCE_ENABLED, result)
    }

    @Test
    fun `resolve returns wait when sms listening started`() {
        val result = OtpListeningPolicy.resolve(
            smsEnabled = true,
            notificationEnabled = false,
            clipboardEnabled = false,
            smsStatus = SmsOtpStatus.LISTENING
        )

        assertEquals(OtpStartOutcome.WAIT_FOR_CODE, result)
    }

    @Test
    fun `resolve returns permission denied when sms is required but unavailable`() {
        val result = OtpListeningPolicy.resolve(
            smsEnabled = true,
            notificationEnabled = false,
            clipboardEnabled = false,
            smsStatus = SmsOtpStatus.PERMISSION_DENIED
        )

        assertEquals(OtpStartOutcome.SMS_PERMISSION_DENIED, result)
    }

    @Test
    fun `resolve returns resolution required when sms needs foreground consent`() {
        val result = OtpListeningPolicy.resolve(
            smsEnabled = true,
            notificationEnabled = true,
            clipboardEnabled = true,
            smsStatus = SmsOtpStatus.RESOLUTION_REQUIRED
        )

        assertEquals(OtpStartOutcome.SMS_RESOLUTION_REQUIRED, result)
    }

    @Test
    fun `resolve keeps waiting when notification can cover sms failure`() {
        val result = OtpListeningPolicy.resolve(
            smsEnabled = true,
            notificationEnabled = true,
            clipboardEnabled = false,
            smsStatus = SmsOtpStatus.UNAVAILABLE
        )

        assertEquals(OtpStartOutcome.WAIT_FOR_CODE, result)
    }
}