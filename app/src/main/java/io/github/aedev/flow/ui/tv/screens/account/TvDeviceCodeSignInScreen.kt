package io.github.aedev.flow.ui.tv.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.QrCodeImage
import io.github.aedev.flow.ui.screens.account.DeviceCodeSignInState
import io.github.aedev.flow.ui.screens.account.DeviceCodeSignInViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.delay

@Composable
fun TvDeviceCodeSignInScreen(
    onNavigateBack: () -> Unit,
    viewModel: DeviceCodeSignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var isResumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        isResumed = true
        viewModel.setVisible(true)
        onPauseOrDispose {
            isResumed = false
            viewModel.setVisible(false)
        }
    }
    LaunchedEffect(state) {
        if (state is DeviceCodeSignInState.SignedIn) {
            delay(1500)
            onNavigateBack()
        }
    }
    TvScreenScaffold(title = stringResource(R.string.tv_account_sign_in_title)) {
        TvDeviceCodeSignInPanel(state, viewModel::retry, onNavigateBack, isResumed)
    }
}

@Composable
internal fun TvDeviceCodeSignInPanel(
    state: DeviceCodeSignInState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    isResumed: Boolean,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = LocalTvDimens.current.overscanHorizontal),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when (state) {
            DeviceCodeSignInState.Starting, DeviceCodeSignInState.Completing -> {
                if (isResumed) {
                    TvLoadingState(modifier = Modifier.weight(1f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            is DeviceCodeSignInState.Ready -> {
                val challenge = state.challenge
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        QrCodeImage(
                            text = challenge.verificationUriComplete ?: challenge.verificationUri,
                            contentDescription = stringResource(R.string.tv_device_code_qr_description),
                            modifier = Modifier.padding(12.dp).size(240.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.tv_device_code_instructions), style = MaterialTheme.typography.titleLarge)
                        Text(
                            challenge.verificationUri,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(challenge.userCode, style = MaterialTheme.typography.displayMedium)
                        challenge.message?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(stringResource(R.string.tv_device_code_waiting), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            DeviceCodeSignInState.Expired -> {
                TvMessageState(title = stringResource(R.string.tv_device_code_expired), modifier = Modifier.weight(1f))
            }

            DeviceCodeSignInState.Denied -> {
                TvMessageState(title = stringResource(R.string.tv_device_code_denied), modifier = Modifier.weight(1f))
            }

            is DeviceCodeSignInState.Failed -> {
                TvMessageState(title = state.message ?: stringResource(R.string.tv_device_code_failed), modifier = Modifier.weight(1f))
            }

            is DeviceCodeSignInState.SignedIn -> {
                TvMessageState(
                    modifier = Modifier.weight(1f),
                    title =
                        state.accountName?.let { stringResource(R.string.tv_account_signed_in_as, it) }
                            ?: stringResource(R.string.tv_account_signed_in),
                )
            }
        }
        if (state is DeviceCodeSignInState.Failed || state == DeviceCodeSignInState.Expired || state == DeviceCodeSignInState.Denied) {
            TvButton(text = stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.tvInitialFocus(state))
        }
        if (state !is DeviceCodeSignInState.SignedIn) {
            TvButton(
                text = stringResource(R.string.cancel),
                onClick = onCancel,
                modifier =
                    if (state is DeviceCodeSignInState.Ready ||
                        state == DeviceCodeSignInState.Starting
                    ) {
                        Modifier.tvInitialFocus(state)
                    } else {
                        Modifier
                    },
            )
        }
    }
}
