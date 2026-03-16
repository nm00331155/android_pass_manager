package com.securevault.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerAccessTest {

    @Test
    fun `contains notification listener matches flattened component`() {
        val result = containsNotificationListenerComponent(
            enabledListeners = "com.example/.Other:com.securevault.app/com.securevault.app.service.otp.OtpNotificationListener",
            flattenedComponentName = "com.securevault.app/com.securevault.app.service.otp.OtpNotificationListener",
            shortFlattenedComponentName = "com.securevault.app/.service.otp.OtpNotificationListener"
        )

        assertTrue(result)
    }

    @Test
    fun `contains notification listener matches short flattened component`() {
        val result = containsNotificationListenerComponent(
            enabledListeners = "com.example/.Other:com.securevault.app/.service.otp.OtpNotificationListener",
            flattenedComponentName = "com.securevault.app/com.securevault.app.service.otp.OtpNotificationListener",
            shortFlattenedComponentName = "com.securevault.app/.service.otp.OtpNotificationListener"
        )

        assertTrue(result)
    }

    @Test
    fun `contains notification listener returns false when component missing`() {
        val result = containsNotificationListenerComponent(
            enabledListeners = "com.example/.Other:com.other/.Listener",
            flattenedComponentName = "com.securevault.app/com.securevault.app.service.otp.OtpNotificationListener",
            shortFlattenedComponentName = "com.securevault.app/.service.otp.OtpNotificationListener"
        )

        assertFalse(result)
    }
}