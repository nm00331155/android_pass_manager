package com.securevault.app.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.securevault.app.service.otp.OtpNotificationListener

fun isOtpNotificationListenerAccessGranted(context: Context): Boolean {
    val componentName = ComponentName(context, OtpNotificationListener::class.java)
    val enabledListeners = Settings.Secure.getString(
        context.contentResolver,
        ENABLED_NOTIFICATION_LISTENERS_KEY
    ).orEmpty()

    if (
        containsNotificationListenerComponent(
            enabledListeners = enabledListeners,
            flattenedComponentName = componentName.flattenToString(),
            shortFlattenedComponentName = componentName.flattenToShortString()
        )
    ) {
        return true
    }

    return NotificationManagerCompat.getEnabledListenerPackages(context)
        .contains(context.packageName)
}

internal fun containsNotificationListenerComponent(
    enabledListeners: String,
    flattenedComponentName: String,
    shortFlattenedComponentName: String
): Boolean {
    if (enabledListeners.isBlank()) {
        return false
    }

    return enabledListeners.split(':').any { entry ->
        entry == flattenedComponentName || entry == shortFlattenedComponentName
    }
}

private const val ENABLED_NOTIFICATION_LISTENERS_KEY = "enabled_notification_listeners"