package com.securevault.app.ui.screen.auth

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.securevault.app.R
import com.securevault.app.biometric.AuthUiState
import com.securevault.app.util.findFragmentActivity

@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    var shouldReveal by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val authAvailability = remember(context) { viewModel.getAuthAvailability(context) }
    val authAvailable = authAvailability.isAvailable

    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val appName = stringResource(R.string.app_name)
    val authSubtitle = stringResource(R.string.auth_subtitle)
    val authUnavailableMessage = stringResource(R.string.auth_unavailable)
    val authFallbackMessage = stringResource(R.string.auth_fallback)
    val authActivityMissingMessage = stringResource(R.string.auth_activity_not_found)
    val authRetryMessage = stringResource(R.string.auth_retry)
    val authButtonText = if (authState is AuthUiState.Authenticating) {
        stringResource(R.string.auth_button_progress)
    } else {
        stringResource(R.string.auth_button)
    }

    val alpha by animateFloatAsState(
        targetValue = if (shouldReveal) 1f else 0f,
        label = "authReveal"
    )

    LaunchedEffect(Unit) {
        shouldReveal = true
    }

    Scaffold {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(horizontal = 24.dp)
                .alpha(alpha),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = stringResource(R.string.auth_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = stringResource(R.string.auth_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
            )

            Button(
                onClick = {
                    val fragmentActivity = activity
                    if (fragmentActivity == null) {
                        viewModel.setError(authActivityMissingMessage)
                        return@Button
                    }
                    viewModel.authenticate(
                        activity = fragmentActivity,
                        title = appName,
                        subtitle = authSubtitle,
                        onAuthenticated = onAuthenticated
                    )
                },
                modifier = Modifier.padding(top = 24.dp),
                enabled = authAvailable && authState !is AuthUiState.Authenticating
            ) {
                Text(text = authButtonText)
            }

            if (!authAvailable) {
                Text(
                    text = authAvailability.message ?: authUnavailableMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            if (authState is AuthUiState.DeviceCredentialFallback) {
                Text(
                    text = authFallbackMessage,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
                TextButton(
                    onClick = { viewModel.resetForRetry() },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(text = authRetryMessage)
                }
            }
        }
    }
}
