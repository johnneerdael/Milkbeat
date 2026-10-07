package io.github.aedev.flow.ui.tv.screens.account

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.ui.components.shared.FlowWebViewStream
import io.github.aedev.flow.ui.components.shared.QrCodeImage
import io.github.aedev.flow.ui.screens.account.AccountSignInState
import io.github.aedev.flow.ui.screens.account.AccountSignInViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.delay
import nl.neerdael.milkbeat.plugin.DeviceCodeMethod

private const val SIGNED_IN_DISMISS_MS = 1_500L

@Composable
fun TvAccountSignInScreen(
    onNavigateBack: () -> Unit,
    viewModel: AccountSignInViewModel = hiltViewModel(),
) {
    if (viewModel.signInMethod is DeviceCodeMethod) {
        TvDeviceCodeSignInScreen(onNavigateBack = onNavigateBack)
        return
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val window = LocalActivity.current?.window
    val supported = remember { loginProfileSupported() }
    val method = viewModel.method
    val controller =
        remember(supported, method) {
            if (supported &&
                method != null
            ) {
                LoginWebViewController(context, viewModel::onPageTitle, method, viewModel::onCaptured, window)
            } else {
                null
            }
        }
    DisposableEffect(controller) {
        viewModel.frameProvider(controller?.let { { it.frame() } })
        onDispose {
            viewModel.frameProvider(null)
            controller?.destroy()
        }
    }
    LaunchedEffect(state) { if (state == AccountSignInState.Starting) viewModel.start(supported) }
    LaunchedEffect(controller) {
        val login = controller ?: return@LaunchedEffect
        viewModel.inputs.collect { input ->
            when (input) {
                is PhoneInput.Text -> login.typeText(input.value, input.field)
                is PhoneInput.Key -> login.pressKey(input.key)
                is PhoneInput.Click -> login.clickAction(input.index)
                is PhoneInput.Pointer -> login.pointer(input)
                is PhoneInput.Frame -> Unit
            }
        }
    }
    LaunchedEffect(state) {
        if (state is AccountSignInState.SignedIn) {
            delay(SIGNED_IN_DISMISS_MS)
            onNavigateBack()
        }
    }

    TvScreenScaffold(title = stringResource(R.string.tv_account_sign_in_title)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = LocalTvDimens.current.overscanHorizontal),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1.4f).fillMaxHeight(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                if (controller != null) {
                    val dimensions = LocalTvDimens.current
                    FlowWebViewStream(controller.stream, DpSize(dimensions.signInViewportWidth, dimensions.signInViewportHeight))
                }
            }
            TvAccountSignInPanel(
                state = state,
                onRetry = {
                    controller?.restart()
                    viewModel.retry()
                },
                onCancel = onNavigateBack,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TvAccountSignInPanel(
    state: AccountSignInState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (state) {
            AccountSignInState.Starting -> {
                TvLoadingState()
            }

            is AccountSignInState.Ready -> {
                Text(
                    text = stringResource(R.string.tv_account_sign_in_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.large) {
                    QrCodeImage(
                        text = state.phoneUrl,
                        contentDescription = stringResource(R.string.tv_account_sign_in_qr_description),
                        modifier = Modifier.padding(12.dp).size(200.dp),
                    )
                }
                Text(
                    text = state.phoneUrl.substringBefore('#'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TvButton(text = stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.tvInitialFocus(state.phoneUrl))
            }

            AccountSignInState.NoNetwork -> {
                TvMessageState(title = stringResource(R.string.tv_account_sign_in_no_network))
                TvButton(text = stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.tvInitialFocus(state))
            }

            AccountSignInState.Unsupported -> {
                TvMessageState(title = stringResource(R.string.tv_account_sign_in_unsupported))
                TvButton(text = stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.tvInitialFocus(state))
            }

            AccountSignInState.TimedOut -> {
                TvMessageState(title = stringResource(R.string.tv_account_sign_in_timed_out))
                TvButton(text = stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.tvInitialFocus(state))
            }

            is AccountSignInState.Failed -> {
                TvMessageState(title = state.message)
                TvButton(text = stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.tvInitialFocus(state))
            }

            is AccountSignInState.SignedIn -> {
                TvMessageState(
                    title =
                        state.accountName?.let { stringResource(R.string.tv_account_signed_in_as, it) }
                            ?: stringResource(R.string.tv_account_signed_in),
                )
            }
        }
    }
}
