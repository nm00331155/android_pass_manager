package com.securevault.app.ui.screen.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.app.R
import com.securevault.app.data.backup.CsvImportParser
import com.securevault.app.data.backup.ImportSource
import com.securevault.app.data.backup.ImportStrategy

private enum class ImportRequestType {
    ENCRYPTED,
    SERVICE
}

/**
 * バックアップ・復元画面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var showCsvWarningDialog by remember { mutableStateOf(false) }
    var showStrategyDialog by remember { mutableStateOf(false) }
    var showServiceSourceDialog by remember { mutableStateOf(false) }

    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var passwordMismatchError by remember { mutableStateOf(false) }

    var selectedStrategy by remember { mutableStateOf(ImportStrategy.SKIP_DUPLICATES) }
    var selectedImportSource by remember { mutableStateOf(ImportSource.BRAVE) }

    var pendingExportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingEncryptedImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingServiceImportUri by remember { mutableStateOf<Uri?>(null) }
    var importRequestType by remember { mutableStateOf(ImportRequestType.ENCRYPTED) }

    val exportSuccessFormat = stringResource(R.string.backup_export_success)
    val importSuccessFormat = stringResource(R.string.backup_import_success)
    val errorFormat = stringResource(R.string.backup_error)
    val invalidImportFormat = stringResource(R.string.backup_import_invalid_format)
    val blockerInteractionSource = remember { MutableInteractionSource() }

    val encryptedExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let {
            pendingExportUri = it
            showExportPasswordDialog = true
        }
    }

    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let { viewModel.exportCsv(it) }
    }

    val encryptedImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingEncryptedImportUri = it
            showImportPasswordDialog = true
        }
    }

    val csvImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCsv(it) }
    }

    val serviceImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingServiceImportUri = it
            importRequestType = ImportRequestType.SERVICE
            showStrategyDialog = true
        }
    }

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is BackupUiState.Success -> {
                val message = if (state.isExport) {
                    String.format(exportSuccessFormat, state.count)
                } else {
                    String.format(importSuccessFormat, state.count)
                }
                snackbarHostState.showSnackbar(message)
                viewModel.resetState()
            }

            is BackupUiState.Error -> {
                val detailMessage = if (state.message == CsvImportParser.INVALID_FORMAT_ERROR) {
                    invalidImportFormat
                } else {
                    state.message
                }
                snackbarHostState.showSnackbar(String.format(errorFormat, detailMessage))
                viewModel.resetState()
            }

            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(text = stringResource(R.string.back_label))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.backup_export_section),
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = { encryptedExportLauncher.launch("securevault_backup.securevault") },
                    enabled = uiState !is BackupUiState.InProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.backup_export_encrypted))
                }

                OutlinedButton(
                    onClick = { showCsvWarningDialog = true },
                    enabled = uiState !is BackupUiState.InProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.backup_export_csv))
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.backup_import_section),
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = { encryptedImportLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    enabled = uiState !is BackupUiState.InProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.backup_import_encrypted))
                }

                OutlinedButton(
                    onClick = {
                        csvImportLauncher.launch(
                            arrayOf("text/csv", "text/comma-separated-values", "*/*")
                        )
                    },
                    enabled = uiState !is BackupUiState.InProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.backup_import_csv))
                }

                OutlinedButton(
                    onClick = { showServiceSourceDialog = true },
                    enabled = uiState !is BackupUiState.InProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.backup_import_service))
                }
            }

            if (uiState is BackupUiState.InProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = blockerInteractionSource,
                            indication = null,
                            onClick = {}
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.backup_in_progress),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }

    if (showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showExportPasswordDialog = false
                exportPassword = ""
                exportPasswordConfirm = ""
                passwordMismatchError = false
            },
            title = { Text(stringResource(R.string.backup_password_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = {
                            exportPassword = it
                            passwordMismatchError = false
                        },
                        label = { Text(stringResource(R.string.backup_password_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = exportPasswordConfirm,
                        onValueChange = {
                            exportPasswordConfirm = it
                            passwordMismatchError = false
                        },
                        label = { Text(stringResource(R.string.backup_password_confirm_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = passwordMismatchError,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (passwordMismatchError) {
                        Text(
                            text = stringResource(R.string.backup_password_mismatch),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (exportPassword != exportPasswordConfirm) {
                            passwordMismatchError = true
                        } else if (exportPassword.isNotEmpty()) {
                            pendingExportUri?.let { uri ->
                                viewModel.exportEncrypted(uri, exportPassword)
                            }
                            showExportPasswordDialog = false
                            exportPassword = ""
                            exportPasswordConfirm = ""
                            passwordMismatchError = false
                        }
                    },
                    enabled = exportPassword.isNotEmpty()
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportPasswordDialog = false
                        exportPassword = ""
                        exportPasswordConfirm = ""
                        passwordMismatchError = false
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showImportPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportPasswordDialog = false
                importPassword = ""
            },
            title = { Text(stringResource(R.string.backup_password_title)) },
            text = {
                OutlinedTextField(
                    value = importPassword,
                    onValueChange = { importPassword = it },
                    label = { Text(stringResource(R.string.backup_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (importPassword.isNotEmpty()) {
                            importRequestType = ImportRequestType.ENCRYPTED
                            showImportPasswordDialog = false
                            showStrategyDialog = true
                        }
                    },
                    enabled = importPassword.isNotEmpty()
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportPasswordDialog = false
                        importPassword = ""
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showStrategyDialog) {
        AlertDialog(
            onDismissRequest = {
                showStrategyDialog = false
                importPassword = ""
            },
            title = { Text(stringResource(R.string.backup_strategy_title)) },
            text = {
                Column {
                    ImportStrategy.entries.forEach { strategy ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedStrategy == strategy,
                                onClick = { selectedStrategy = strategy }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (strategy) {
                                    ImportStrategy.SKIP_DUPLICATES -> stringResource(R.string.backup_strategy_skip)
                                    ImportStrategy.OVERWRITE -> stringResource(R.string.backup_strategy_overwrite)
                                    ImportStrategy.IMPORT_ALL -> stringResource(R.string.backup_strategy_all)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (importRequestType) {
                            ImportRequestType.ENCRYPTED -> {
                                pendingEncryptedImportUri?.let { uri ->
                                    viewModel.importEncrypted(uri, importPassword, selectedStrategy)
                                }
                            }

                            ImportRequestType.SERVICE -> {
                                pendingServiceImportUri?.let { uri ->
                                    viewModel.importFromService(
                                        uri = uri,
                                        source = selectedImportSource,
                                        strategy = selectedStrategy
                                    )
                                }
                            }
                        }
                        showStrategyDialog = false
                        importPassword = ""
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showStrategyDialog = false
                        importPassword = ""
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showServiceSourceDialog) {
        AlertDialog(
            onDismissRequest = { showServiceSourceDialog = false },
            title = { Text(stringResource(R.string.backup_import_service_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportSource.entries.forEach { source ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                RadioButton(
                                    selected = selectedImportSource == source,
                                    onClick = { selectedImportSource = source }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = source.displayName)
                            }
                            Text(
                                text = stringResource(R.string.backup_import_service_hint),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 40.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showServiceSourceDialog = false
                        serviceImportLauncher.launch(
                            arrayOf("text/csv", "text/comma-separated-values", "*/*")
                        )
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showServiceSourceDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (showCsvWarningDialog) {
        AlertDialog(
            onDismissRequest = { showCsvWarningDialog = false },
            title = { Text(stringResource(R.string.backup_export_csv)) },
            text = { Text(stringResource(R.string.backup_export_csv_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCsvWarningDialog = false
                        csvExportLauncher.launch("securevault_export.csv")
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCsvWarningDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
