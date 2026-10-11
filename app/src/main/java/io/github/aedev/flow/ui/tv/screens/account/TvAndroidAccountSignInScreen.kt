package io.github.aedev.flow.ui.tv.screens.account

import android.accounts.AccountManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.screens.account.AndroidAccountSignInState
import io.github.aedev.flow.ui.screens.account.AndroidAccountSignInViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.delay
import nl.neerdael.milkbeat.plugin.AccountIdentity

private const val SIGNED_IN_DISMISS_MS = 1_500L

@Composable
fun TvAndroidAccountSignInScreen(
    onNavigateBack: () -> Unit,
    viewModel: AndroidAccountSignInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chooser =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onAccountChosen(result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME))
        }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.onPromptClosed() }
    LaunchedEffect(state) {
        when (val current = state) {
            AndroidAccountSignInState.Starting -> {
                viewModel.start()
            }

            is AndroidAccountSignInState.ChooseAccount -> {
                viewModel.onLaunched()
                chooser.launch(current.intent)
            }

            is AndroidAccountSignInState.Confirm -> {
                viewModel.onLaunched()
                prompt.launch(current.intent)
            }

            is AndroidAccountSignInState.SignedIn -> {
                delay(SIGNED_IN_DISMISS_MS)
                onNavigateBack()
            }

            else -> {
                Unit
            }
        }
    }
    TvScreenScaffold(title = stringResource(R.string.tv_microg_sign_in_title)) {
        TvAndroidAccountSignInPanel(state, viewModel::retry, onNavigateBack)
    }
}

@Composable
private fun TvAndroidAccountSignInPanel(
    state: AndroidAccountSignInState,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = LocalTvDimens.current.overscanHorizontal),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when (state) {
            AndroidAccountSignInState.Starting, is AndroidAccountSignInState.ChooseAccount, is AndroidAccountSignInState.Confirm -> {
                TvLoadingState(modifier = Modifier.weight(1f))
            }

            AndroidAccountSignInState.Waiting -> {
                TvMessageState(title = stringResource(R.string.tv_microg_sign_in_waiting), modifier = Modifier.weight(1f))
            }

            AndroidAccountSignInState.MicroGMissing -> {
                TvMessageState(title = stringResource(R.string.tv_microg_sign_in_missing), modifier = Modifier.weight(1f))
            }

            AndroidAccountSignInState.NoAccountChosen -> {
                TvMessageState(title = stringResource(R.string.tv_microg_sign_in_no_account), modifier = Modifier.weight(1f))
            }

            is AndroidAccountSignInState.Denied -> {
                TvMessageState(
                    title = stringResource(R.string.tv_microg_sign_in_denied, identityLabel(state.identity)),
                    modifier = Modifier.weight(1f),
                )
            }

            is AndroidAccountSignInState.Failed -> {
                TvMessageState(title = state.message ?: stringResource(R.string.tv_microg_sign_in_failed), modifier = Modifier.weight(1f))
            }

            is AndroidAccountSignInState.SignedIn -> {
                TvMessageState(
                    modifier = Modifier.weight(1f),
                    title =
                        state.accountName?.let { stringResource(R.string.tv_account_signed_in_as, it) }
                            ?: stringResource(R.string.tv_account_signed_in),
                )
            }
        }
        val retryable =
            state == AndroidAccountSignInState.NoAccountChosen ||
                state is AndroidAccountSignInState.Denied ||
                state is AndroidAccountSignInState.Failed
        if (retryable) {
            TvButton(text = stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.tvInitialFocus(state))
        }
        if (state !is AndroidAccountSignInState.SignedIn) {
            TvButton(
                text = stringResource(R.string.cancel),
                onClick = onCancel,
                modifier = if (retryable) Modifier else Modifier.tvInitialFocus(state),
            )
        }
    }
}

@Composable
private fun identityLabel(identity: AccountIdentity): String =
    when (identity) {
        AccountIdentity.YOUTUBE -> stringResource(R.string.tv_microg_identity_youtube)
        AccountIdentity.YOUTUBE_MUSIC -> stringResource(R.string.tv_microg_identity_youtube_music)
    }
