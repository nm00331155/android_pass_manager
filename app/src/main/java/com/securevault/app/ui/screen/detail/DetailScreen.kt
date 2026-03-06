package com.securevault.app.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    credentialId: Long,
    onNavigateBack: () -> Unit,
    onEditClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "詳細") },
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
            Text(text = "対象ID: $credentialId")
            Text(text = "詳細画面は次フェーズで暗号化データと接続します。")

            Button(onClick = onEditClick, modifier = Modifier.fillMaxWidth()) {
                Text(text = "編集")
            }
            Button(onClick = onNavigateBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = "ホームへ戻る")
            }
        }
    }
}
