package com.securevault.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.securevault.app.util.PasswordStrength

/**
 * パスワード強度をバーで表示するコンポーネント。
 */
@Composable
fun PasswordStrengthBar(strength: PasswordStrength) {
    val indicatorColor = when (strength) {
        PasswordStrength.VERY_WEAK -> MaterialTheme.colorScheme.error
        PasswordStrength.WEAK -> Color(0xFFFF9800)
        PasswordStrength.MEDIUM -> Color(0xFFFFEB3B)
        PasswordStrength.STRONG -> Color(0xFF8BC34A)
        PasswordStrength.VERY_STRONG -> Color(0xFF4CAF50)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { strength.score / 100f },
            color = indicatorColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text = strength.label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
