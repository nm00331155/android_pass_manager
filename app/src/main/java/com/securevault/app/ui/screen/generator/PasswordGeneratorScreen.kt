package com.securevault.app.ui.screen.generator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.app.R
import com.securevault.app.ui.component.PasswordStrengthBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordGeneratorScreen(
    onNavigateBack: () -> Unit,
    onUsePassword: (String) -> Unit,
    viewModel: PasswordGeneratorViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    val length by viewModel.length.collectAsStateWithLifecycle()
    val useLowercase by viewModel.useLowercase.collectAsStateWithLifecycle()
    val useUppercase by viewModel.useUppercase.collectAsStateWithLifecycle()
    val useDigits by viewModel.useDigits.collectAsStateWithLifecycle()
    val useSymbols by viewModel.useSymbols.collectAsStateWithLifecycle()
    val excludeAmbiguous by viewModel.excludeAmbiguous.collectAsStateWithLifecycle()
    val generatedPassword by viewModel.generatedPassword.collectAsStateWithLifecycle()
    val strength by viewModel.strength.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.generator_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_label)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = generatedPassword,
                modifier = Modifier.fillMaxWidth(),
                fontFamily = FontFamily.Monospace
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::generate,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.regenerate_action))
                }
                Button(
                    onClick = {
                        val clipboardManager =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboardManager.setPrimaryClip(
                            ClipData.newPlainText("generated_password", generatedPassword)
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.copy_action))
                }
            }

            Text(text = stringResource(R.string.password_length_label, length))
            Slider(
                value = length.toFloat(),
                onValueChange = { value ->
                    viewModel.updateLength(value.toInt())
                },
                valueRange = 8f..64f,
                modifier = Modifier.fillMaxWidth()
            )

            GeneratorSwitchRow(
                label = stringResource(R.string.option_lowercase),
                checked = useLowercase,
                onCheckedChange = viewModel::updateUseLowercase
            )
            GeneratorSwitchRow(
                label = stringResource(R.string.option_uppercase),
                checked = useUppercase,
                onCheckedChange = viewModel::updateUseUppercase
            )
            GeneratorSwitchRow(
                label = stringResource(R.string.option_digits),
                checked = useDigits,
                onCheckedChange = viewModel::updateUseDigits
            )
            GeneratorSwitchRow(
                label = stringResource(R.string.option_symbols),
                checked = useSymbols,
                onCheckedChange = viewModel::updateUseSymbols
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = stringResource(R.string.exclude_ambiguous))
                Checkbox(
                    checked = excludeAmbiguous,
                    onCheckedChange = viewModel::updateExcludeAmbiguous
                )
            }

            PasswordStrengthBar(strength = strength)

            Button(
                onClick = { onUsePassword(generatedPassword) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.use_generated_password))
            }
        }
    }
}

@Composable
private fun GeneratorSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
