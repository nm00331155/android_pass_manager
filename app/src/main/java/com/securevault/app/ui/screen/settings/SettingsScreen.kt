package com.securevault.app.ui.screen.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.securevault.app.BuildConfig
import com.securevault.app.R
import com.securevault.app.ui.component.ConfirmDialog
import com.securevault.app.util.isOtpNotificationListenerAccessGranted
import kotlinx.coroutines.launch

/**
 * 設定画面を表示し、セキュリティ設定と OTP 設定の変更を受け付ける。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToBackup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val autoLockTimeout by viewModel.autoLockTimeoutSeconds.collectAsStateWithLifecycle()
    val clipboardTimeout by viewModel.clipboardClearTimeoutSeconds.collectAsStateWithLifecycle()
    val otpSmsEnabled by viewModel.otpSmsEnabled.collectAsStateWithLifecycle()
    val otpNotificationEnabled by viewModel.otpNotificationEnabled.collectAsStateWithLifecycle()
    val otpClipboardEnabled by viewModel.otpClipboardEnabled.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationListenerAccessGranted by remember {
        mutableStateOf(isOtpNotificationListenerAccessGranted(context))
    }

    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteFinalConfirm by rememberSaveable { mutableStateOf(false) }
    var pendingNotificationListenerEnable by rememberSaveable { mutableStateOf(false) }

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

    LaunchedEffect(otpNotificationEnabled, notificationListenerAccessGranted) {
        if (otpNotificationEnabled && !notificationListenerAccessGranted) {
            viewModel.updateOtpNotificationEnabled(false)
        }
    }

    DisposableEffect(lifecycleOwner, otpNotificationEnabled, pendingNotificationListenerEnable) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) {
                return@LifecycleEventObserver
            }

            val hasNotificationAccess = isOtpNotificationListenerAccessGranted(context)
            notificationListenerAccessGranted = hasNotificationAccess
            if (pendingNotificationListenerEnable) {
                pendingNotificationListenerEnable = false
                if (hasNotificationAccess) {
                    viewModel.updateOtpNotificationEnabled(true)
                } else {
                    viewModel.updateOtpNotificationEnabled(false)
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.settings_notification_otp_enable_failed)
                        )
                    }
                }
            } else if (otpNotificationEnabled && !hasNotificationAccess) {
                viewModel.updateOtpNotificationEnabled(false)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_label)
                        )
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

            Text(
                text = stringResource(R.string.settings_otp),
                style = MaterialTheme.typography.titleMedium
            )
            SettingToggleRow(
                label = stringResource(R.string.settings_sms_otp),
                checked = otpSmsEnabled,
                onCheckedChange = viewModel::updateOtpSmsEnabled
            )
            SettingToggleRow(
                label = stringResource(R.string.settings_notification_otp),
                checked = otpNotificationEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        pendingNotificationListenerEnable = false
                        viewModel.updateOtpNotificationEnabled(false)
                    } else if (notificationListenerAccessGranted) {
                        viewModel.updateOtpNotificationEnabled(true)
                    } else {
                        pendingNotificationListenerEnable = true
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }.onFailure {
                            pendingNotificationListenerEnable = false
                            viewModel.updateOtpNotificationEnabled(false)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.settings_notification_otp_settings_unavailable)
                                )
                            }
                        }
                    }
                }
            )
            Text(
                text = if (notificationListenerAccessGranted) {
                    stringResource(R.string.settings_notification_otp_access_granted)
                } else {
                    stringResource(R.string.settings_notification_otp_access_missing)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!notificationListenerAccessGranted) {
                TextButton(
                    onClick = {
                        pendingNotificationListenerEnable = true
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }.onFailure {
                            pendingNotificationListenerEnable = false
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.settings_notification_otp_settings_unavailable)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.open_notification_listener_settings))
                }
            }
            SettingToggleRow(
                label = stringResource(R.string.settings_clipboard_otp),
                checked = otpClipboardEnabled,
                onCheckedChange = viewModel::updateOtpClipboardEnabled
            )

            Text(
                text = stringResource(R.string.settings_credentials),
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.settings_credential_provider_unavailable)
                                )
                            }
                        }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.open_autofill_settings))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                OutlinedButton(
                    onClick = {
                        if (!openCredentialProviderSettings(context)) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.settings_credential_provider_unavailable)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(R.string.open_credential_provider_settings))
                }
            }

            Text(
                text = stringResource(R.string.settings_credential_provider_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.settings_autofill_browser_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedButton(
                onClick = onNavigateToBackup,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_backup))
            }

            OutlinedButton(
                onClick = {
                    runCatching {
                        val logFile = viewModel.exportLog(context)
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            logFile
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                shareIntent,
                                context.getString(R.string.settings_export_log)
                            )
                        )
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.settings_log_exported)
                            )
                        }
                    }.onFailure { throwable ->
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                throwable.message ?: context.getString(R.string.backup_error, "unknown")
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_export_log))
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

@Composable
private fun SettingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.width(56.dp)
        )
    }
}

private fun openCredentialProviderSettings(context: android.content.Context): Boolean {
    val packageUri = Uri.parse("package:${context.packageName}")
    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            add(
                Intent(Settings.ACTION_CREDENTIAL_PROVIDER).apply {
                    data = packageUri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        add(
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        add(
            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                data = packageUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        add(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = packageUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    intents.forEach { intent ->
        if (intent.resolveActivity(context.packageManager) != null) {
            val launched = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) {
                return true
            }
        }
    }

    return false
}
