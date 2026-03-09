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
import com.securevault.app.data.repository.model.Credential
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
    val cardholderNameCopiedLabel = stringResource(R.string.cardholder_name_copied)
    val cardNumberCopiedLabel = stringResource(R.string.card_number_copied)
    val cardSecurityCodeCopiedLabel = stringResource(R.string.card_security_code_copied)
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
                    credential?.takeUnless { it.isPasskey }?.let { current ->
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
                actionLabel = currentCredential.serviceUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.open_in_browser) },
                onAction = {
                    val url = currentCredential.serviceUrl.orEmpty()
                    if (url.isBlank()) {
                        return@DetailRow
                    }
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    runCatching { context.startActivity(intent) }
                }
            )

            if (!currentCredential.isCard) {
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
            }

            if (currentCredential.isCard) {
                val cardData = currentCredential.cardData
                cardData?.cardholderName?.takeIf { it.isNotBlank() }?.let { cardholderName ->
                    DetailRow(
                        title = stringResource(R.string.field_cardholder_name),
                        value = cardholderName,
                        actionLabel = stringResource(R.string.copy_action),
                        onAction = {
                            viewModel.copyToClipboard(
                                context = context,
                                text = cardholderName,
                                label = cardholderNameCopiedLabel
                            )
                        }
                    )
                }

                cardData?.let {
                    DetailRow(
                        title = stringResource(R.string.field_card_number_required),
                        value = if (passwordVisible) {
                            it.normalizedCardNumber
                        } else {
                            it.maskedCardNumber
                        },
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
                                text = it.normalizedCardNumber,
                                label = cardNumberCopiedLabel
                            )
                        }
                    )

                    it.formattedExpiration?.let { expiration ->
                        DetailRow(
                            title = stringResource(R.string.field_card_expiration),
                            value = expiration
                        )
                    }

                    it.securityCode?.takeIf { securityCode -> securityCode.isNotBlank() }?.let { securityCode ->
                        DetailRow(
                            title = stringResource(R.string.field_card_security_code),
                            value = if (passwordVisible) {
                                securityCode
                            } else {
                                "*".repeat(max(3, securityCode.length))
                            },
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
                                    text = securityCode,
                                    label = cardSecurityCodeCopiedLabel
                                )
                            }
                        )
                    }
                }
            }

            DetailRow(
                title = stringResource(R.string.field_credential_type),
                value = credentialTypeLabel(currentCredential)
            )

            if (currentCredential.hasPassword) {
                val password = currentCredential.password.orEmpty()
                val maskedPassword = "*".repeat(max(8, password.length))
                DetailRow(
                    title = stringResource(R.string.field_password_required),
                    value = if (passwordVisible) password else maskedPassword,
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
                            text = password,
                            label = passwordCopiedLabel
                        )
                    }
                )
            }

            currentCredential.passkeyData?.let { passkeyData ->
                DetailRow(
                    title = stringResource(R.string.field_passkey_rp_id),
                    value = passkeyData.rpId
                )
                passkeyData.origin?.takeIf { it.isNotBlank() }?.let { origin ->
                    DetailRow(
                        title = stringResource(R.string.field_url),
                        value = origin
                    )
                }
            }

            if (!currentCredential.notes.isNullOrBlank()) {
                DetailRow(
                    title = stringResource(R.string.field_notes),
                    value = currentCredential.notes
                )
            }

            Divider()

            DetailRow(
                title = stringResource(R.string.field_category),
                value = categoryLabel(currentCredential.category)
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

@Composable
private fun credentialTypeLabel(credential: Credential): String = when {
    credential.isCard -> stringResource(R.string.credential_type_card)
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
