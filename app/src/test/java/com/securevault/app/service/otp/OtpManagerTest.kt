package com.securevault.app.service.otp

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OtpManagerTest {

    private val smsOtpManager = mockk<SmsOtpManager>()
    private val clipboardOtpDetector = mockk<ClipboardOtpDetector>()
    private val smsFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val clipboardFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    @Before
    fun setUp() {
        every { smsOtpManager.otpResult } returns smsFlow
        every { clipboardOtpDetector.detectedOtp } returns clipboardFlow
    }

    @Test
    fun `getRecentOtp returns latest event within age window`() {
        val manager = OtpManager(smsOtpManager, clipboardOtpDetector)
        manager.onOtpDetected("123456", OtpSource.SMS)

        val event = manager.latestOtpEvent.value
        val result = manager.getRecentOtp(
            allowedSources = setOf(OtpSource.SMS),
            maxAgeMs = 1_000L,
            now = (event?.timestamp ?: 0L) + 500L
        )

        assertEquals("123456", result?.code)
        assertEquals(OtpSource.SMS, result?.source)
    }

    @Test
    fun `getRecentOtp returns null when source is not allowed`() {
        val manager = OtpManager(smsOtpManager, clipboardOtpDetector)
        manager.onOtpDetected("654321", OtpSource.CLIPBOARD)

        val event = manager.latestOtpEvent.value
        val result = manager.getRecentOtp(
            allowedSources = setOf(OtpSource.SMS),
            maxAgeMs = 1_000L,
            now = (event?.timestamp ?: 0L) + 10L
        )

        assertNull(result)
    }

    @Test
    fun `getRecentOtp returns null when event is older than max age`() {
        val manager = OtpManager(smsOtpManager, clipboardOtpDetector)
        manager.onOtpDetected("987654", OtpSource.NOTIFICATION)

        val event = manager.latestOtpEvent.value
        val result = manager.getRecentOtp(
            allowedSources = setOf(OtpSource.NOTIFICATION),
            maxAgeMs = 100L,
            now = (event?.timestamp ?: 0L) + 101L
        )

        assertNull(result)
    }
}