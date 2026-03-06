package com.securevault.app.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.app.R
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.ui.component.ConfirmDialog
import com.securevault.app.ui.component.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onGeneratorClick: () -> Unit,
    onDetailClick: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val credentials by viewModel.filteredCredentials.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val showFavoritesOnly by viewModel.showFavoritesOnly.collectAsStateWithLifecycle()
    val credentialCount by viewModel.credentialCount.collectAsStateWithLifecycle()

    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Credential?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "SecureVault") },
                actions = {
                    TextButton(onClick = { searchExpanded = !searchExpanded }) {
                        Text(text = stringResource(R.string.search_action))
                    }
                    TextButton(onClick = onGeneratorClick) {
                        Text(text = stringResource(R.string.generator_title))
                    }
                    TextButton(onClick = onSettingsClick) {
                        Text(text = stringResource(R.string.open_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Text(text = "+")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (searchExpanded) {
                DockedSearchBar(
                    query = searchQuery,
                    onQueryChange = viewModel::updateSearchQuery,
                    onSearch = {},
                    active = searchExpanded,
                    onActiveChange = { searchExpanded = it },
                    placeholder = { Text(text = stringResource(R.string.search_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                ) {}
            }

            CategoryFilterRow(
                selectedCategory = selectedCategory,
                onSelectedCategoryChange = viewModel::updateCategoryFilter,
                showFavoritesOnly = showFavoritesOnly,
                onToggleFavoritesOnly = viewModel::toggleFavoritesOnly
            )

            val displayCount = if (credentials.isNotEmpty()) credentials.size else credentialCount
            Text(
                text = stringResource(R.string.password_count, displayCount),
                style = MaterialTheme.typography.bodyMedium
            )

            when (val state = uiState) {
                HomeUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                HomeUiState.Empty -> {
                    EmptyState(
                        message = stringResource(R.string.home_empty_message),
                        actionLabel = stringResource(R.string.go_add),
                        onAction = onAddClick
                    )
                }

                is HomeUiState.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = state.message,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = viewModel::clearError) {
                            Text(text = stringResource(R.string.auth_retry))
                        }
                    }
                }

                HomeUiState.Success -> {
                    if (credentials.isEmpty()) {
                        EmptyState(
                            message = stringResource(R.string.home_filter_empty_message),
                            actionLabel = stringResource(R.string.filter_reset),
                            onAction = {
                                viewModel.updateSearchQuery("")
                                viewModel.updateCategoryFilter(null)
                                if (showFavoritesOnly) {
                                    viewModel.toggleFavoritesOnly()
                                }
                            }
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = credentials,
                                key = { credential -> credential.id }
                            ) { credential ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { dismissValue ->
                                        when (dismissValue) {
                                            SwipeToDismissBoxValue.StartToEnd -> {
                                                viewModel.toggleFavorite(credential.id)
                                                false
                                            }

                                            SwipeToDismissBoxValue.EndToStart -> {
                                                pendingDelete = credential
                                                false
                                            }

                                            SwipeToDismissBoxValue.Settled -> false
                                        }
                                    }
                                )

                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = {
                                        DismissBackground(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(84.dp)
                                        )
                                    },
                                    content = {
                                        CredentialListItem(
                                            credential = credential,
                                            onClick = { onDetailClick(credential.id) }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            message = stringResource(R.string.confirm_delete_message),
            isDestructive = true,
            onConfirm = {
                viewModel.deleteCredential(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun CategoryFilterRow(
    selectedCategory: String?,
    onSelectedCategoryChange: (String?) -> Unit,
    showFavoritesOnly: Boolean,
    onToggleFavoritesOnly: () -> Unit
) {
    val categories = listOf(
        null to R.string.category_all,
        "login" to R.string.category_login,
        "finance" to R.string.category_finance,
        "social" to R.string.category_social,
        "other" to R.string.category_other
    )

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(categories) { (value, labelRes) ->
            FilterChip(
                selected = selectedCategory == value,
                onClick = { onSelectedCategoryChange(value) },
                label = { Text(text = stringResource(labelRes)) }
            )
        }

        item {
            FilterChip(
                selected = showFavoritesOnly,
                onClick = onToggleFavoritesOnly,
                label = { Text(text = stringResource(R.string.favorite_only_label)) }
            )
        }
    }
}

@Composable
private fun CredentialListItem(
    credential: Credential,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = credential.serviceName,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = credential.username,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = credential.category,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(text = if (credential.isFavorite) "*" else "-")
    }
}

@Composable
private fun DismissBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = stringResource(R.string.home_swipe_hint))
    }
}
