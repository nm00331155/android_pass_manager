package com.securevault.app.ui.screen.generator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.security.SecureRandom

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordGeneratorScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current

    var length by remember { mutableStateOf(16f) }
    var useLower by remember { mutableStateOf(true) }
    var useUpper by remember { mutableStateOf(true) }
    var useDigits by remember { mutableStateOf(true) }
    var useSymbols by remember { mutableStateOf(true) }
    var password by remember {
        mutableStateOf(generatePassword(length = length.toInt(), true, true, true, true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "パスワード生成") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(text = "戻る")
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
            Text(text = password)
            Text(text = "長さ: ${length.toInt()}")
            Slider(
                value = length,
                onValueChange = { length = it },
                valueRange = 8f..64f,
                modifier = Modifier.fillMaxWidth()
            )

            ToggleRow(label = "小文字", checked = useLower) { useLower = it }
            ToggleRow(label = "大文字", checked = useUpper) { useUpper = it }
            ToggleRow(label = "数字", checked = useDigits) { useDigits = it }
            ToggleRow(label = "記号", checked = useSymbols) { useSymbols = it }

            Button(
                onClick = {
                    password = generatePassword(
                        length = length.toInt(),
                        useLower = useLower,
                        useUpper = useUpper,
                        useDigits = useDigits,
                        useSymbols = useSymbols
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "再生成")
            }

            Button(
                onClick = {
                    val clipboardManager =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboardManager.setPrimaryClip(
                        ClipData.newPlainText("generated_password", password)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "コピー")
            }

            Button(onClick = onNavigateBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = "ホームへ戻る")
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun generatePassword(
    length: Int,
    useLower: Boolean,
    useUpper: Boolean,
    useDigits: Boolean,
    useSymbols: Boolean
): String {
    val lower = "abcdefghijklmnopqrstuvwxyz"
    val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    val digits = "0123456789"
    val symbols = "!@#$%^&*()-_=+[]{}:;,.?"

    val pool = buildString {
        if (useLower) append(lower)
        if (useUpper) append(upper)
        if (useDigits) append(digits)
        if (useSymbols) append(symbols)
    }.ifBlank { lower + upper + digits }

    val random = SecureRandom()
    return (1..length)
        .map { pool[random.nextInt(pool.length)] }
        .joinToString(separator = "")
}
