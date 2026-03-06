package com.securevault.app.ui.navigation

import androidx.lifecycle.ViewModel
import com.securevault.app.util.AutoLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * ナビゲーション全体で利用するロック状態を公開する ViewModel。
 */
@HiltViewModel
class NavGuardViewModel @Inject constructor(
    autoLockManager: AutoLockManager
) : ViewModel() {
    val isLocked: StateFlow<Boolean> = autoLockManager.lockedState
}
