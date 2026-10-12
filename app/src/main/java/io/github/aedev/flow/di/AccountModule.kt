package io.github.aedev.flow.di

import android.content.Context
import android.text.TextUtils
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.signin.PhoneChannel
import io.github.aedev.flow.data.account.signin.PhoneFrame
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.data.account.signin.PhoneInputServer
import io.github.aedev.flow.data.account.signin.PhoneStatus
import io.github.aedev.flow.plugin.host.AndroidMicroGAccountGateway
import io.github.aedev.flow.plugin.host.MicroGAccountGateway
import io.github.aedev.flow.sync.transport.LanAddress
import io.github.aedev.flow.ui.screens.account.LanAddressProvider
import io.github.aedev.flow.ui.screens.account.PhoneServerHandle
import io.github.aedev.flow.ui.screens.account.PhoneServerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Module
@InstallIn(SingletonComponent::class)
object AccountModule {
    @Provides
    fun provideLanAddressProvider(): LanAddressProvider = LanAddressProvider { LanAddress.resolve() }

    @Provides
    fun provideMicroGAccountGateway(gateway: AndroidMicroGAccountGateway): MicroGAccountGateway = gateway

    @Provides
    fun providePhoneServerLauncher(
        @ApplicationContext context: Context,
    ): PhoneServerLauncher =
        object : PhoneServerLauncher {
            override suspend fun launch(
                channel: PhoneChannel,
                host: String,
                status: () -> PhoneStatus,
                onInput: suspend (PhoneInput) -> Unit,
                captureFrame: suspend () -> PhoneFrame?,
            ): PhoneServerHandle {
                val server =
                    PhoneInputServer(channel, { path ->
                        val bytes = context.assets.open(path).use { it.readBytes() }
                        if (path == PhoneInputServer.INDEX) {
                            bytes
                                .decodeToString()
                                .replace(
                                    "{{remote_view_label}}",
                                    TextUtils.htmlEncode(context.getString(R.string.tv_account_remote_view_label)),
                                ).replace(
                                    "{{remote_view_help}}",
                                    TextUtils.htmlEncode(context.getString(R.string.tv_account_remote_view_help)),
                                ).encodeToByteArray()
                        } else {
                            bytes
                        }
                    }, status, onInput, captureFrame)
                val boundPort = withContext(Dispatchers.IO) { server.start(host) }
                return object : PhoneServerHandle {
                    override val port = boundPort

                    override fun stop() = server.stop()
                }
            }
        }
}
