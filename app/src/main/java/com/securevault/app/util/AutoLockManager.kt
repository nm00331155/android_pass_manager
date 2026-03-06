package com.securevault.app.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.securevault.app.data.store.securitySettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * アプリのフォアグラウンド/バックグラウンド遷移を監視し、タイムアウトに応じて自動ロックする。
 */
@Singleton
class AutoLockManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    private val _lockedState = MutableStateFlow(true)
    val lockedState: StateFlow<Boolean> = _lockedState.asStateFlow()

    private val _autoLockTimeoutSeconds = MutableStateFlow(DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS)
    val autoLockTimeoutSeconds: StateFlow<Int> = _autoLockTimeoutSeconds.asStateFlow()

    @Volatile
    private var backgroundedAtMillis: Long = -1L

    init {
        scope.launch {
            appContext.securitySettingsDataStore.data
                .map { preferences ->
                    preferences[AUTO_LOCK_TIMEOUT_SECONDS_KEY] ?: DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS
                }
                .distinctUntilChanged()
                .collect { timeout ->
                    _autoLockTimeoutSeconds.value = timeout
                }
        }
    }

    /**
     * プロセスライフサイクル監視を開始する。
     */
    fun start() {
        if (started.compareAndSet(false, true)) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAtMillis = System.currentTimeMillis()
        if (_autoLockTimeoutSeconds.value == AUTO_LOCK_TIMEOUT_IMMEDIATE_SECONDS) {
            lockNow()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val timeoutSeconds = _autoLockTimeoutSeconds.value
        val lastBackgroundMillis = backgroundedAtMillis
        backgroundedAtMillis = -1L

        if (timeoutSeconds == AUTO_LOCK_TIMEOUT_DISABLED_SECONDS) {
            return
        }
        if (timeoutSeconds == AUTO_LOCK_TIMEOUT_IMMEDIATE_SECONDS) {
            lockNow()
            return
        }
        if (lastBackgroundMillis <= 0L) {
            return
        }

        val elapsed = System.currentTimeMillis() - lastBackgroundMillis
        if (elapsed >= timeoutSeconds * 1000L) {
            lockNow()
        }
    }

    /**
     * ロック状態を解除する。
     */
    fun unlock() {
        _lockedState.value = false
    }

    /**
     * 直ちにロックする。
     */
    fun lockNow() {
        _lockedState.value = true
    }

    /**
     * 自動ロックのタイムアウト秒を保存する。
     * -1: 無効, 0: 即時, 1以上: 秒
     */
    suspend fun setAutoLockTimeoutSeconds(timeoutSeconds: Int) {
        val sanitized = timeoutSeconds.coerceAtLeast(AUTO_LOCK_TIMEOUT_DISABLED_SECONDS)
        appContext.securitySettingsDataStore.edit { settings ->
            settings[AUTO_LOCK_TIMEOUT_SECONDS_KEY] = sanitized
        }
    }

    companion object {
        const val AUTO_LOCK_TIMEOUT_DISABLED_SECONDS = -1
        const val AUTO_LOCK_TIMEOUT_IMMEDIATE_SECONDS = 0
        const val DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS = 60

        private val AUTO_LOCK_TIMEOUT_SECONDS_KEY =
            intPreferencesKey("auto_lock_timeout_seconds")
    }
}
