package com.securevault.app.ui.screen.addedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.util.PasswordStrength
import com.securevault.app.util.PasswordStrengthChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 追加/編集画面のフォーム状態を管理する ViewModel。
 */
@HiltViewModel
class AddEditViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val credentialId: Long = savedStateHandle[ARG_CREDENTIAL_ID] ?: NO_CREDENTIAL_ID
    val isEditMode: Boolean = credentialId >= 0L && credentialId != NO_CREDENTIAL_ID

    private val _serviceName = MutableStateFlow("")
    val serviceName: StateFlow<String> = _serviceName.asStateFlow()

    private val _serviceUrl = MutableStateFlow("")
    val serviceUrl: StateFlow<String> = _serviceUrl.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes.asStateFlow()

    private val _category = MutableStateFlow(DEFAULT_CATEGORY)
    val category: StateFlow<String> = _category.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    private val _passwordVisible = MutableStateFlow(false)
    val passwordVisible: StateFlow<Boolean> = _passwordVisible.asStateFlow()

    private val _passwordStrength = MutableStateFlow(PasswordStrength.VERY_WEAK)
    val passwordStrength: StateFlow<PasswordStrength> = _passwordStrength.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _saveCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveCompleted = _saveCompleted.asSharedFlow()

    val isFormValid: StateFlow<Boolean> = combine(_serviceName, _password) { name, pass ->
        name.isNotBlank() && pass.isNotBlank()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    private var createdAtMillis: Long = System.currentTimeMillis()

    init {
        if (isEditMode) {
            loadExistingCredential()
        }
        observeGeneratedPassword()
    }

    /**
     * サービス名を更新する。
     */
    fun updateServiceName(value: String) {
        _serviceName.value = value
    }

    /**
     * URLを更新する。
     */
    fun updateServiceUrl(value: String) {
        _serviceUrl.value = value
    }

    /**
     * ユーザー名を更新する。
     */
    fun updateUsername(value: String) {
        _username.value = value
    }

    /**
     * パスワードを更新する。
     */
    fun updatePassword(value: String) {
        _password.value = value
        _passwordStrength.value = PasswordStrengthChecker.check(value)
    }

    /**
     * メモを更新する。
     */
    fun updateNotes(value: String) {
        _notes.value = value
    }

    /**
     * カテゴリを更新する。
     */
    fun updateCategory(value: String) {
        _category.value = value
    }

    /**
     * お気に入りを更新する。
     */
    fun updateFavorite(value: Boolean) {
        _isFavorite.value = value
    }

    /**
     * パスワード表示/非表示を切り替える。
     */
    fun togglePasswordVisibility() {
        _passwordVisible.value = !_passwordVisible.value
    }

    /**
     * 入力内容を保存する。
     */
    fun save() {
        if (!isFormValid.value || _isSaving.value) {
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            _errorMessage.value = null

            val now = System.currentTimeMillis()
            val payload = Credential(
                id = if (isEditMode) credentialId else 0L,
                serviceName = _serviceName.value.trim(),
                serviceUrl = _serviceUrl.value.trim().ifBlank { null },
                username = _username.value.trim(),
                password = _password.value,
                notes = _notes.value.ifBlank { null },
                category = _category.value,
                createdAt = createdAtMillis,
                updatedAt = now,
                isFavorite = _isFavorite.value
            )

            runCatching {
                credentialRepository.save(payload)
            }.onSuccess {
                _saveCompleted.emit(Unit)
            }.onFailure { throwable ->
                _errorMessage.value = throwable.message ?: "保存に失敗しました。"
            }

            _isSaving.value = false
        }
    }

    /**
     * エラー表示をクリアする。
     */
    fun clearError() {
        _errorMessage.value = null
    }

    private fun loadExistingCredential() {
        viewModelScope.launch {
            credentialRepository.getById(credentialId)
                .filterNotNull()
                .firstOrNull()
                ?.let { credential ->
                    _serviceName.value = credential.serviceName
                    _serviceUrl.value = credential.serviceUrl.orEmpty()
                    _username.value = credential.username
                    _password.value = credential.password
                    _notes.value = credential.notes.orEmpty()
                    _category.value = credential.category
                    _isFavorite.value = credential.isFavorite
                    _passwordStrength.value = PasswordStrengthChecker.check(credential.password)
                    createdAtMillis = credential.createdAt
                }
        }
    }

    private fun observeGeneratedPassword() {
        viewModelScope.launch {
            savedStateHandle.getStateFlow(GENERATED_PASSWORD_KEY, "")
                .collect { generatedPassword ->
                    if (generatedPassword.isNotBlank()) {
                        updatePassword(generatedPassword)
                        savedStateHandle[GENERATED_PASSWORD_KEY] = ""
                    }
                }
        }
    }

    private companion object {
        const val ARG_CREDENTIAL_ID = "credentialId"
        const val GENERATED_PASSWORD_KEY = "generated_password"
        const val NO_CREDENTIAL_ID = -1L
        const val DEFAULT_CATEGORY = "other"
    }
}
