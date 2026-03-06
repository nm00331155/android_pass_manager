package com.securevault.app.ui.screen.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.app.data.backup.BackupManager
import com.securevault.app.data.backup.ImportSource
import com.securevault.app.data.backup.ImportStrategy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * バックアップ画面の UI 状態。
 */
sealed interface BackupUiState {
    /** 待機状態。 */
    data object Idle : BackupUiState

    /** 処理実行中。 */
    data object InProgress : BackupUiState

    /**
     * 処理成功状態。
     *
     * @property count 処理件数
     * @property isExport エクスポートの場合 true
     */
    data class Success(val count: Int, val isExport: Boolean) : BackupUiState

    /**
     * 処理失敗状態。
     *
     * @property message エラーメッセージ
     */
    data class Error(val message: String) : BackupUiState
}

/**
 * バックアップ画面の ViewModel。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)

    /** バックアップ画面の UI 状態。 */
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /**
     * 暗号化バックアップをエクスポートする。
     */
    fun exportEncrypted(uri: Uri, password: String) {
        viewModelScope.launch {
            _uiState.value = BackupUiState.InProgress
            runCatching {
                backupManager.exportEncrypted(uri, password)
            }.onSuccess { count ->
                _uiState.value = BackupUiState.Success(count = count, isExport = true)
            }.onFailure { throwable ->
                _uiState.value = BackupUiState.Error(throwable.message ?: "不明なエラー")
            }
        }
    }

    /**
     * CSV 形式でエクスポートする。
     */
    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = BackupUiState.InProgress
            runCatching {
                backupManager.exportCsv(uri)
            }.onSuccess { count ->
                _uiState.value = BackupUiState.Success(count = count, isExport = true)
            }.onFailure { throwable ->
                _uiState.value = BackupUiState.Error(throwable.message ?: "不明なエラー")
            }
        }
    }

    /**
     * 暗号化バックアップをインポートする。
     */
    fun importEncrypted(uri: Uri, password: String, strategy: ImportStrategy) {
        viewModelScope.launch {
            _uiState.value = BackupUiState.InProgress
            runCatching {
                backupManager.importEncrypted(uri, password, strategy)
            }.onSuccess { count ->
                _uiState.value = BackupUiState.Success(count = count, isExport = false)
            }.onFailure { throwable ->
                _uiState.value = BackupUiState.Error(throwable.message ?: "不明なエラー")
            }
        }
    }

    /**
     * CSV をインポートする。
     */
    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = BackupUiState.InProgress
            runCatching {
                backupManager.importCsv(uri)
            }.onSuccess { count ->
                _uiState.value = BackupUiState.Success(count = count, isExport = false)
            }.onFailure { throwable ->
                _uiState.value = BackupUiState.Error(throwable.message ?: "不明なエラー")
            }
        }
    }

    /**
     * 他サービスの CSV をインポートする。
     */
    fun importFromService(uri: Uri, source: ImportSource, strategy: ImportStrategy) {
        viewModelScope.launch {
            _uiState.value = BackupUiState.InProgress
            runCatching {
                backupManager.importFromService(uri, source, strategy)
            }.onSuccess { count ->
                _uiState.value = BackupUiState.Success(count = count, isExport = false)
            }.onFailure { throwable ->
                _uiState.value = BackupUiState.Error(throwable.message ?: "不明なエラー")
            }
        }
    }

    /**
     * UI 状態を待機状態へ戻す。
     */
    fun resetState() {
        _uiState.value = BackupUiState.Idle
    }
}
