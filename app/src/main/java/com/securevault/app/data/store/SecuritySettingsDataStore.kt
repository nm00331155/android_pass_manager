package com.securevault.app.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/**
 * セキュリティ関連設定で共通利用する Preferences DataStore。
 *
 * 同名 DataStore を複数箇所で宣言すると実行時クラッシュの原因になるため、
 * この定義を全体で共有する。
 */
val Context.securitySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "securevault_security_settings"
)

/**
 * セキュリティ関連設定で共有するキーと既定値。
 */
object SecuritySettingsPreferences {
    const val DEFAULT_OTP_SMS_ENABLED: Boolean = true
    const val DEFAULT_OTP_NOTIFICATION_ENABLED: Boolean = false
    const val DEFAULT_OTP_CLIPBOARD_ENABLED: Boolean = true

    val OTP_SMS_ENABLED_KEY = booleanPreferencesKey("otp_sms_enabled")
    val OTP_NOTIFICATION_ENABLED_KEY = booleanPreferencesKey("otp_notification_enabled")
    val OTP_CLIPBOARD_ENABLED_KEY = booleanPreferencesKey("otp_clipboard_enabled")
}
