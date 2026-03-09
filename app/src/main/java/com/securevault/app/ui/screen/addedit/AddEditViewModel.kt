package com.securevault.app.ui.screen.addedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.CardData
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.data.repository.model.CredentialType
import com.securevault.app.data.repository.model.PasskeyData
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

    private val _credentialType = MutableStateFlow(CredentialType.PASSWORD)
    val credentialType: StateFlow<CredentialType> = _credentialType.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _cardholderName = MutableStateFlow("")
    val cardholderName: StateFlow<String> = _cardholderName.asStateFlow()

    private val _cardNumber = MutableStateFlow("")
    val cardNumber: StateFlow<String> = _cardNumber.asStateFlow()

    private val _cardExpirationMonth = MutableStateFlow("")
    val cardExpirationMonth: StateFlow<String> = _cardExpirationMonth.asStateFlow()

    private val _cardExpirationYear = MutableStateFlow("")
    val cardExpirationYear: StateFlow<String> = _cardExpirationYear.asStateFlow()

    private val _cardSecurityCode = MutableStateFlow("")
    val cardSecurityCode: StateFlow<String> = _cardSecurityCode.asStateFlow()

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

    val isFormValid: StateFlow<Boolean> = combine(
        _credentialType,
        _serviceName,
        _username,
        _password,
        _cardNumber
    ) { selectedType, name, username, password, cardNumber ->
        when (selectedType) {
            CredentialType.PASSWORD -> {
                name.isNotBlank() && username.isNotBlank() && password.isNotBlank()
            }

            CredentialType.ID_ONLY -> {
                name.isNotBlank() && username.isNotBlank()
            }

            CredentialType.CARD -> {
                name.isNotBlank() && cardNumber.filter(Char::isDigit).length in CARD_NUMBER_LENGTH_RANGE
            }

            CredentialType.PASSKEY -> false
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    private var createdAtMillis: Long = System.currentTimeMillis()
    private var existingPasskeyData: PasskeyData? = null

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
     * 種類を更新する。
     */
    fun updateCredentialType(value: CredentialType) {
        _credentialType.value = value
        if (value == CredentialType.CARD) {
            _category.value = FINANCE_CATEGORY
        }
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
     * カード名義を更新する。
     */
    fun updateCardholderName(value: String) {
        _cardholderName.value = value
    }

    /**
     * カード番号を更新する。
     */
    fun updateCardNumber(value: String) {
        _cardNumber.value = value
    }

    /**
     * カード有効期限（月）を更新する。
     */
    fun updateCardExpirationMonth(value: String) {
        _cardExpirationMonth.value = value
    }

    /**
     * カード有効期限（年）を更新する。
     */
    fun updateCardExpirationYear(value: String) {
        _cardExpirationYear.value = value
    }

    /**
     * セキュリティコードを更新する。
     */
    fun updateCardSecurityCode(value: String) {
        _cardSecurityCode.value = value
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
            val selectedType = when {
                existingPasskeyData != null -> CredentialType.PASSKEY
                else -> _credentialType.value
            }
            val normalizedPassword = _password.value.takeIf { it.isNotBlank() }
            val normalizedCardNumber = _cardNumber.value.filter(Char::isDigit)
            val expirationMonth = parseCardExpirationMonth(_cardExpirationMonth.value)
            val expirationYear = parseCardExpirationYear(_cardExpirationYear.value)

            if (selectedType == CredentialType.CARD) {
                if (normalizedCardNumber.length !in CARD_NUMBER_LENGTH_RANGE) {
                    _errorMessage.value = "カード番号は12〜19桁で入力してください。"
                    _isSaving.value = false
                    return@launch
                }

                val hasExpirationInput =
                    _cardExpirationMonth.value.isNotBlank() || _cardExpirationYear.value.isNotBlank()
                if (hasExpirationInput && (expirationMonth == null || expirationYear == null)) {
                    _errorMessage.value = "有効期限は月と年を正しく入力してください。"
                    _isSaving.value = false
                    return@launch
                }
            }

            val cardData = if (selectedType == CredentialType.CARD) {
                CardData(
                    cardholderName = _cardholderName.value.trim().ifBlank { null },
                    cardNumber = normalizedCardNumber,
                    expirationMonth = expirationMonth,
                    expirationYear = expirationYear,
                    securityCode = _cardSecurityCode.value.trim().ifBlank { null }
                )
            } else {
                null
            }

            val resolvedUsername = if (selectedType == CredentialType.CARD) {
                cardData?.cardholderName
                    ?: normalizedCardNumber.takeLast(CARD_LABEL_DIGITS).ifBlank {
                        _serviceName.value.trim()
                    }
            } else {
                _username.value.trim()
            }
            val payload = Credential(
                id = if (isEditMode) credentialId else 0L,
                serviceName = _serviceName.value.trim(),
                serviceUrl = _serviceUrl.value.trim().ifBlank { null },
                username = resolvedUsername,
                password = if (selectedType == CredentialType.PASSWORD) normalizedPassword else null,
                notes = _notes.value.ifBlank { null },
                category = if (selectedType == CredentialType.CARD) FINANCE_CATEGORY else _category.value,
                createdAt = createdAtMillis,
                updatedAt = now,
                isFavorite = _isFavorite.value,
                passkeyData = existingPasskeyData,
                cardData = cardData,
                credentialType = selectedType
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
                    _credentialType.value = credential.credentialType
                    _username.value = credential.username
                    _password.value = credential.password.orEmpty()
                    _cardholderName.value = credential.cardData?.cardholderName.orEmpty()
                    _cardNumber.value = credential.cardData?.normalizedCardNumber.orEmpty()
                    _cardExpirationMonth.value = credential.cardData?.expirationMonth?.toString().orEmpty()
                    _cardExpirationYear.value = credential.cardData?.expirationYear?.toString().orEmpty()
                    _cardSecurityCode.value = credential.cardData?.securityCode.orEmpty()
                    _notes.value = credential.notes.orEmpty()
                    _category.value = credential.category
                    _isFavorite.value = credential.isFavorite
                    _passwordStrength.value = PasswordStrengthChecker.check(credential.password.orEmpty())
                    existingPasskeyData = credential.passkeyData
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

    private fun parseCardExpirationMonth(value: String): Int? {
        val month = value.filter(Char::isDigit).toIntOrNull() ?: return null
        return month.takeIf { it in 1..12 }
    }

    private fun parseCardExpirationYear(value: String): Int? {
        val digits = value.filter(Char::isDigit)
        return when (digits.length) {
            0 -> null
            2 -> 2000 + digits.toInt()
            4 -> digits.toIntOrNull()
            else -> null
        }
    }

    private companion object {
        const val ARG_CREDENTIAL_ID = "credentialId"
        const val GENERATED_PASSWORD_KEY = "generated_password"
        const val NO_CREDENTIAL_ID = -1L
        const val DEFAULT_CATEGORY = "other"
        const val FINANCE_CATEGORY = "finance"
        const val CARD_LABEL_DIGITS = 4
        val CARD_NUMBER_LENGTH_RANGE = 12..19
    }
}
