package com.securevault.app.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.app.BuildConfig
import com.securevault.app.R
import com.securevault.app.ui.component.ConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val autoLockTimeout by viewModel.autoLockTimeoutSeconds.collectAsStateWithLifecycle()
    val clipboardTimeout by viewModel.clipboardClearTimeoutSeconds.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteFinalConfirm by rememberSaveable { mutableStateOf(false) }

    val autoLockOptions = listOf(
        0 to stringResource(R.string.timeout_immediate),
        30 to stringResource(R.string.timeout_30_seconds),
        60 to stringResource(R.string.timeout_1_minute),
        300 to stringResource(R.string.timeout_5_minutes),
        -1 to stringResource(R.string.timeout_disabled)
    )

    val clipboardOptions = listOf(
        15 to stringResource(R.string.timeout_15_seconds),
        30 to stringResource(R.string.timeout_30_seconds),
        60 to stringResource(R.string.timeout_1_minute),
        -1 to stringResource(R.string.timeout_disabled)
    )

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(text = stringResource(R.string.back_label))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_security),
                style = MaterialTheme.typography.titleMedium
            )

            Text(text = stringResource(R.string.settings_auto_lock))
            TimeoutOptionRow(
                currentValue = autoLockTimeout,
                options = autoLockOptions,
                onSelect = viewModel::updateAutoLockTimeoutSeconds
            )

            Text(text = stringResource(R.string.settings_clipboard_clear))
            TimeoutOptionRow(
                currentValue = clipboardTimeout,
                options = clipboardOptions,
                onSelect = viewModel::updateClipboardClearTimeoutSeconds
            )

            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.open_autofill_settings))
            }

            Text(
                text = stringResource(R.string.settings_data),
                style = MaterialTheme.typography.titleMedium
            )
            Button(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.settings_delete_all),
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                text = stringResource(R.string.settings_app_info),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(
                    R.string.settings_version,
                    BuildConfig.VERSION_NAME
                )
            )
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_all_title),
            message = stringResource(R.string.confirm_delete_all_message),
            isDestructive = true,
            onConfirm = {
                showDeleteConfirm = false
                showDeleteFinalConfirm = true
            },
            onDismiss = { showDeleteConfirm = false }
        )
    }

    if (showDeleteFinalConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_all_title),
            message = stringResource(R.string.confirm_delete_all_final),
            isDestructive = true,
            onConfirm = {
                showDeleteFinalConfirm = false
                viewModel.deleteAllData()
            },
            onDismiss = { showDeleteFinalConfirm = false }
        )
    }
}

@Composable
private fun TimeoutOptionRow(
    currentValue: Int,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { (value, label) ->
            FilterChip(
                selected = currentValue == value,
                onClick = { onSelect(value) },
                label = { Text(text = label) }
            )
        }
    }
}
