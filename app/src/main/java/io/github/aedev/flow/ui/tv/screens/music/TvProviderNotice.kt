package io.github.aedev.flow.ui.tv.screens.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.screens.music.ProviderNotice
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/** A music tab's line about its plugin: signed out of a MicroG sign-in, or retired in favour of another. */
@Composable
internal fun TvProviderNotice(
    notice: ProviderNotice,
    onSignIn: (pluginId: String, methodId: String, downloadMicroG: Boolean) -> Unit,
    onAddReplacement: (code: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = LocalTvDimens.current.overscanHorizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                when (notice) {
                    is ProviderNotice.SignedOut -> {
                        stringResource(R.string.tv_music_signed_out_microg, notice.name)
                    }

                    is ProviderNotice.Replaced -> {
                        if (notice.replacementInstalled) {
                            stringResource(R.string.tv_plugins_replaced_installed, notice.name)
                        } else {
                            stringResource(R.string.tv_plugins_replaced, notice.name, notice.code)
                        }
                    }
                },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        when (notice) {
            is ProviderNotice.SignedOut -> {
                TvButton(text = stringResource(R.string.tv_music_sign_in), onClick = { onSignIn(notice.pluginId, notice.methodId, false) })
                if (notice.canDownloadMicroG) {
                    TvButton(
                        text = stringResource(R.string.tv_microg_download_action),
                        onClick = { onSignIn(notice.pluginId, notice.methodId, true) },
                    )
                }
            }

            is ProviderNotice.Replaced -> {
                if (!notice.replacementInstalled) {
                    TvButton(text = stringResource(R.string.tv_plugins_add_youtube), onClick = { onAddReplacement(notice.code) })
                }
            }
        }
    }
}
