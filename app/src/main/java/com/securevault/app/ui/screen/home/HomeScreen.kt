package com.securevault.app.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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

    var pendingDelete by remember { mutableStateOf<Credential?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onGeneratorClick) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = stringResource(R.string.generator_title)
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.open_settings)
                        )
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
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                placeholder = { Text(text = stringResource(R.string.search_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = stringResource(R.string.search_action)
                            )
                        }
                    }
                }
            )

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
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.home_empty_message),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                                            searchQuery = searchQuery,
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

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 0.dp)
    ) {
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
    searchQuery: String,
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
            HighlightedText(
                text = credential.serviceName,
                highlight = searchQuery,
                style = MaterialTheme.typography.titleSmall
            )
            HighlightedText(
                text = credential.username,
                highlight = searchQuery,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = listOf(
                    categoryLabel(credential.category),
                    credentialTypeLabel(credential)
                ).joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(text = if (credential.isFavorite) "*" else "-")
    }
}

@Composable
private fun HighlightedText(
    text: String,
    highlight: String,
    style: TextStyle,
    color: Color = Color.Unspecified
) {
    val normalizedHighlight = highlight.trim()
    if (normalizedHighlight.isBlank()) {
        Text(text = text, style = style, color = color)
        return
    }

    val lowerText = text.lowercase()
    val lowerHighlight = normalizedHighlight.lowercase()
    val annotated = buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val index = lowerText.indexOf(lowerHighlight, startIndex = start)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(SpanStyle(background = Color.Yellow)) {
                append(text.substring(index, index + lowerHighlight.length))
            }
            start = index + lowerHighlight.length
        }
    }

    Text(text = annotated, style = style, color = color)
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

@Composable
private fun credentialTypeLabel(credential: Credential): String = when {
    credential.isPasskey -> stringResource(R.string.credential_type_passkey)
    credential.hasPassword -> stringResource(R.string.credential_type_password)
    else -> stringResource(R.string.credential_type_id_only)
}

@Composable
private fun categoryLabel(category: String): String = when (category) {
    "login" -> stringResource(R.string.category_login)
    "finance" -> stringResource(R.string.category_finance)
    "social" -> stringResource(R.string.category_social)
    else -> stringResource(R.string.category_other)
}
