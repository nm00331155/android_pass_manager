package com.securevault.app.ui.screen.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.app.R
import com.securevault.app.ui.component.ConfirmDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onNavigateBack: () -> Unit,
    onEditClick: (Long) -> Unit,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val usernameCopiedLabel = stringResource(R.string.username_copied)
    val passwordCopiedLabel = stringResource(R.string.password_copied)
    val credential by viewModel.credential.collectAsStateWithLifecycle()
    val passwordVisible by viewModel.passwordVisible.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.Message -> snackbarHostState.showSnackbar(event.text)
                DetailEvent.NavigateBack -> onNavigateBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = credential?.serviceName ?: stringResource(R.string.detail_title)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(text = stringResource(R.string.back_label))
                    }
                },
                actions = {
                    credential?.let { current ->
                        TextButton(onClick = { onEditClick(current.id) }) {
                            Text(text = stringResource(R.string.edit_action))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        val currentCredential = credential
        if (currentCredential == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DetailRow(
                title = stringResource(R.string.field_url),
                value = currentCredential.serviceUrl.orEmpty(),
                actionLabel = stringResource(R.string.open_in_browser),
                onAction = {
                    val url = currentCredential.serviceUrl.orEmpty()
                    if (url.isBlank()) {
                        return@DetailRow
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    runCatching { context.startActivity(intent) }
                }
            )

            DetailRow(
                title = stringResource(R.string.field_username),
                value = currentCredential.username,
                actionLabel = stringResource(R.string.copy_action),
                onAction = {
                    viewModel.copyToClipboard(
                        context = context,
                        text = currentCredential.username,
                        label = usernameCopiedLabel
                    )
                }
            )

            val maskedPassword = "*".repeat(max(8, currentCredential.password.length))
            DetailRow(
                title = stringResource(R.string.field_password_required),
                value = if (passwordVisible) currentCredential.password else maskedPassword,
                actionLabel = if (passwordVisible) {
                    stringResource(R.string.hide_password)
                } else {
                    stringResource(R.string.show_password)
                },
                secondaryActionLabel = stringResource(R.string.copy_action),
                onAction = viewModel::togglePasswordVisibility,
                onSecondaryAction = {
                    viewModel.copyToClipboard(
                        context = context,
                        text = currentCredential.password,
                        label = passwordCopiedLabel
                    )
                }
            )

            if (!currentCredential.notes.isNullOrBlank()) {
                DetailRow(
                    title = stringResource(R.string.field_notes),
                    value = currentCredential.notes
                )
            }

            Divider()

            DetailRow(
                title = stringResource(R.string.field_category),
                value = currentCredential.category
            )
            DetailRow(
                title = stringResource(R.string.created_at),
                value = formatDate(currentCredential.createdAt)
            )
            DetailRow(
                title = stringResource(R.string.updated_at),
                value = formatDate(currentCredential.updatedAt)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.delete_action),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            message = stringResource(R.string.confirm_delete_message),
            isDestructive = true,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteCredential()
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }
}

@Composable
private fun DetailRow(
    title: String,
    value: String,
    actionLabel: String? = null,
    secondaryActionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (value.isBlank()) "-" else value,
            style = MaterialTheme.typography.bodyLarge
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAction) {
                    Text(text = actionLabel)
                }
                if (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null) {
                    TextButton(onClick = onSecondaryAction) {
                        Text(text = secondaryActionLabel)
                    }
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val format = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    return format.format(Date(timestamp))
}
