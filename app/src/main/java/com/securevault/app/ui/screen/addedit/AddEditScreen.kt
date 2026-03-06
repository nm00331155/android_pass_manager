package com.securevault.app.ui.screen.addedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.app.R
import com.securevault.app.ui.component.PasswordStrengthBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditScreen(
    onNavigateBack: () -> Unit,
    onGeneratorClick: () -> Unit,
    viewModel: AddEditViewModel = hiltViewModel()
) {
    val isEditMode = viewModel.isEditMode
    val serviceName by viewModel.serviceName.collectAsStateWithLifecycle()
    val serviceUrl by viewModel.serviceUrl.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val passwordVisible by viewModel.passwordVisible.collectAsStateWithLifecycle()
    val passwordStrength by viewModel.passwordStrength.collectAsStateWithLifecycle()
    val isFormValid by viewModel.isFormValid.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var categoryExpanded by remember { mutableStateOf(false) }

    val categoryOptions = listOf(
        "login" to stringResource(R.string.category_login),
        "finance" to stringResource(R.string.category_finance),
        "social" to stringResource(R.string.category_social),
        "other" to stringResource(R.string.category_other)
    )

    LaunchedEffect(Unit) {
        viewModel.saveCompleted.collect {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isEditMode) {
                            stringResource(R.string.add_edit_title_edit)
                        } else {
                            stringResource(R.string.add_edit_title_add)
                        }
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(text = stringResource(R.string.back_label))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = serviceName,
                onValueChange = viewModel::updateServiceName,
                label = { Text(text = stringResource(R.string.field_service_name_required)) },
                modifier = Modifier.fillMaxWidth(),
                isError = serviceName.isBlank() && errorMessage != null
            )

            OutlinedTextField(
                value = serviceUrl,
                onValueChange = viewModel::updateServiceUrl,
                label = { Text(text = stringResource(R.string.field_url)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = username,
                onValueChange = viewModel::updateUsername,
                label = { Text(text = stringResource(R.string.field_username)) },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = viewModel::updatePassword,
                label = { Text(text = stringResource(R.string.field_password_required)) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = viewModel::togglePasswordVisibility) {
                        Text(
                            text = if (passwordVisible) {
                                stringResource(R.string.hide_password)
                            } else {
                                stringResource(R.string.show_password)
                            }
                        )
                    }
                },
                supportingText = {
                    PasswordStrengthBar(strength = passwordStrength)
                }
            )

            TextButton(onClick = onGeneratorClick) {
                Text(text = stringResource(R.string.generate_password_action))
            }

            OutlinedTextField(
                value = notes,
                onValueChange = viewModel::updateNotes,
                label = { Text(text = stringResource(R.string.field_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = !categoryExpanded }
            ) {
                OutlinedTextField(
                    value = categoryOptions.firstOrNull { it.first == category }?.second
                        ?: stringResource(R.string.category_other),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(text = stringResource(R.string.field_category)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    categoryOptions.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(text = label) },
                            onClick = {
                                viewModel.updateCategory(value)
                                categoryExpanded = false
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.favorite_only_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = isFavorite,
                    onCheckedChange = viewModel::updateFavorite
                )
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = viewModel::clearError) {
                    Text(text = stringResource(R.string.auth_retry))
                }
            }

            Button(
                onClick = viewModel::save,
                enabled = isFormValid && !isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isSaving) {
                        stringResource(R.string.save_in_progress)
                    } else {
                        stringResource(R.string.save_action)
                    }
                )
            }
        }
    }
}
