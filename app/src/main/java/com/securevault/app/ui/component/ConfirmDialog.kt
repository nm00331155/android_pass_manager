package com.securevault.app.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun DialogConfirmButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false
) {
    val contentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = contentColor,
            disabledContentColor = contentColor.copy(alpha = 0.38f)
        )
    ) {
        Text(text = text)
    }
}

@Composable
fun DialogDismissButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val contentColor = MaterialTheme.colorScheme.onSurface

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor,
            disabledContentColor = contentColor.copy(alpha = 0.38f)
        )
    ) {
        Text(text = text)
    }
}

/**
 * 汎用の確認ダイアログ。
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "削除",
    dismissText: String = "キャンセル",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = surfaceColor,
        title = { Text(text = title) },
        text = { Text(text = message) },
        confirmButton = {
            DialogConfirmButton(
                text = confirmText,
                onClick = onConfirm,
                isDestructive = isDestructive
            )
        },
        dismissButton = {
            DialogDismissButton(
                text = dismissText,
                onClick = onDismiss
            )
        }
    )
}
