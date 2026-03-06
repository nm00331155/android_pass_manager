package com.securevault.app.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
