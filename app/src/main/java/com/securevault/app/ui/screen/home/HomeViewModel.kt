package com.securevault.app.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.Credential
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ホーム画面の一覧・フィルタ状態を管理する ViewModel。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository
) : ViewModel() {

    private val _allCredentials = MutableStateFlow<List<Credential>>(emptyList())
    private val _isLoading = MutableStateFlow(true)
    private val _errorMessage = MutableStateFlow<String?>(null)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    val credentialCount: StateFlow<Int> = _allCredentials
        .map { credentials -> credentials.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val filteredCredentials: StateFlow<List<Credential>> = combine(
        _allCredentials,
        _searchQuery,
        _selectedCategory,
        _showFavoritesOnly
    ) { credentials, query, category, favoritesOnly ->
        credentials.filter { credential ->
            val matchedSearch = if (query.isBlank()) {
                true
            } else {
                val normalizedQuery = query.trim().lowercase()
                credential.serviceName.lowercase().contains(normalizedQuery) ||
                    credential.username.lowercase().contains(normalizedQuery)
            }

            val matchedCategory = category == null || credential.category == category
            val matchedFavorite = !favoritesOnly || credential.isFavorite

            matchedSearch && matchedCategory && matchedFavorite
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val uiState: StateFlow<HomeUiState> = combine(
        _isLoading,
        _errorMessage,
        _allCredentials
    ) { loading, error, all ->
        when {
            loading -> HomeUiState.Loading
            !error.isNullOrBlank() -> HomeUiState.Error(error)
            all.isEmpty() -> HomeUiState.Empty
            else -> HomeUiState.Success
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState.Loading
    )

    init {
        observeCredentials()
    }

    /**
     * 検索クエリを更新する。
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * カテゴリフィルタを更新する。
     */
    fun updateCategoryFilter(category: String?) {
        _selectedCategory.value = category
    }

    /**
     * お気に入りのみ表示するかを切り替える。
     */
    fun toggleFavoritesOnly() {
        _showFavoritesOnly.value = !_showFavoritesOnly.value
    }

    /**
     * 指定IDの認証情報を削除する。
     */
    fun deleteCredential(id: Long) {
        viewModelScope.launch {
            runCatching {
                credentialRepository.delete(id)
            }.onFailure { throwable ->
                _errorMessage.value = throwable.message ?: "削除に失敗しました。"
            }
        }
    }

    /**
     * 指定IDのお気に入り状態を反転する。
     */
    fun toggleFavorite(id: Long) {
        viewModelScope.launch {
            runCatching {
                credentialRepository.toggleFavorite(id)
            }.onFailure { throwable ->
                _errorMessage.value = throwable.message ?: "お気に入り更新に失敗しました。"
            }
        }
    }

    /**
     * エラーメッセージをクリアする。
     */
    fun clearError() {
        _errorMessage.value = null
    }

    private fun observeCredentials() {
        viewModelScope.launch {
            credentialRepository.getAll()
                .onStart {
                    _isLoading.value = true
                    _errorMessage.value = null
                }
                .catch { throwable ->
                    _errorMessage.value = throwable.message ?: "一覧の取得に失敗しました。"
                    _allCredentials.value = emptyList()
                    _isLoading.value = false
                }
                .collect { credentials ->
                    _allCredentials.value = credentials
                    _isLoading.value = false
                }
        }
    }
}

/**
 * ホーム画面の表示状態。
 */
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Success : HomeUiState
    data object Empty : HomeUiState
    data class Error(val message: String) : HomeUiState
}
