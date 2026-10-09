package io.github.aedev.flow.plugin.host

import android.os.Build
import android.util.Log
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.KeystoreSecretBox
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import nl.neerdael.milkbeat.plugin.AssetText
import nl.neerdael.milkbeat.plugin.HashAlgorithm
import nl.neerdael.milkbeat.plugin.HashResult
import nl.neerdael.milkbeat.plugin.HostEnvironment
import nl.neerdael.milkbeat.plugin.HostOperation
import nl.neerdael.milkbeat.plugin.HostOperations
import nl.neerdael.milkbeat.plugin.LogLevel
import nl.neerdael.milkbeat.plugin.PLUGIN_API_VERSION
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.RandomBytesResult
import nl.neerdael.milkbeat.plugin.StorageEntry
import nl.neerdael.milkbeat.plugin.StoredValue
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val MAX_SLEEP_MS = 60_000L
private const val SECRETS_QUOTA = 256L * 1024
private const val SETTINGS_QUOTA = 64L * 1024
private const val MAX_LOG_CHARS = 2_000

/** What every host call answers: its result, or why it failed. The SDK reads the same shape. */
@Serializable
internal data class CallEnvelope(
    val result: JsonElement? = null,
    val error: PluginError? = null,
)

/** A host call that failed for a reason the plugin should see as [code]. */
internal class HostCallException(
    val code: PluginErrorCode,
    message: String,
) : Exception(message)

/**
 * The host functions of one plugin that do not need its script context: HTTP, storage, secrets,
 * hashing, assets, environment, logging, settings and sleep. Each answers in a [CallEnvelope], and
 * everything is scoped to the plugin and to what the listener granted it.
 */
internal class PluginHostApi(
    private val plugin: InstalledPlugin,
    private val directory: File,
    dataDirectory: File,
    client: OkHttpClient,
    private val appLocale: () -> Locale,
    private val webLogin: WebLoginRefresher? = null,
) {
    private val http = PluginHttp(client, plugin.grantedNetwork)
    private val storage = PluginStore(File(dataDirectory, "storage.json"), plugin.manifest.permissions.storage)
    private val secrets =
        PluginStore(File(dataDirectory, "secrets.json"), SECRETS_QUOTA, seal = KeystoreSecretBox::seal, open = KeystoreSecretBox::open)
    val settings = PluginStore(File(dataDirectory, "settings.json"), SETTINGS_QUOTA)
    private val logTag = "Plugin/${plugin.id.substringAfterLast('.')}".take(23)

    private val handlers: Map<String, suspend (String) -> String> =
        mapOf(
            handler(HostOperations.fetch) { http.fetch(it) },
            handler(HostOperations.storageGet) { StoredValue(storage.get(it.key)) },
            handler(HostOperations.storageSet) { storage.set(it.key, it.value) },
            handler(HostOperations.storageDelete) { storage.delete(it.key) },
            handler(HostOperations.secretGet) { StoredValue(secrets.get(it.key)) },
            handler(HostOperations.secretSet) { secrets.set(it.key, it.value) },
            handler(HostOperations.secretDelete) { secrets.delete(it.key) },
            handler(HostOperations.hash) { request ->
                val algorithm =
                    when (request.algorithm) {
                        HashAlgorithm.SHA1 -> "SHA-1"
                        HashAlgorithm.SHA256 -> "SHA-256"
                        HashAlgorithm.MD5 -> "MD5"
                    }
                HashResult(MessageDigest.getInstance(algorithm).digest(request.text.toByteArray()).joinToString("") { "%02x".format(it) })
            },
            handler(HostOperations.randomBytes) { request ->
                pluginRandomBytes(request.length)
            },
            handler(HostOperations.hmac) { request ->
                val algorithm =
                    when (request.algorithm) {
                        HashAlgorithm.SHA1 -> "HmacSHA1"
                        HashAlgorithm.SHA256 -> "HmacSHA256"
                        HashAlgorithm.MD5 -> "HmacMD5"
                    }
                val key = request.keyHex.hexBytes() ?: throw HostCallException(PluginErrorCode.INTERNAL, "The HMAC key is not hex")
                val message =
                    request.messageHex.hexBytes() ?: throw HostCallException(PluginErrorCode.INTERNAL, "The HMAC message is not hex")
                val mac = Mac.getInstance(algorithm).apply { init(SecretKeySpec(key, algorithm)) }
                HashResult(mac.doFinal(message).toHexString())
            },
            handler(HostOperations.assetRead) { AssetText(asset(it.path).readText()) },
            handler(HostOperations.environment) { environment() },
            handler(HostOperations.log) { request ->
                val priority =
                    when (request.level) {
                        LogLevel.DEBUG -> Log.DEBUG
                        LogLevel.INFO -> Log.INFO
                        LogLevel.WARN -> Log.WARN
                        LogLevel.ERROR -> Log.ERROR
                    }
                Log.println(priority, logTag, request.message.take(MAX_LOG_CHARS))
            },
            handler(HostOperations.settings) { settings.entries().map { (key, value) -> StorageEntry(key, value) } },
            handler(HostOperations.sleep) { delay(it.ms.coerceIn(0, MAX_SLEEP_MS)) },
            handler(HostOperations.signInRefresh) { request ->
                webLogin?.refresh(plugin, request) ?: throw HostCallException(PluginErrorCode.UNSUPPORTED, "Sign-in refresh is unavailable")
            },
        )

    /** Runs host function [path] with [requestJson] and answers with its envelope; never throws. */
    suspend fun call(
        path: String,
        requestJson: String,
    ): String {
        val handler = handlers[path] ?: return failure(PluginErrorCode.UNSUPPORTED, "No host function $path")
        return envelope { handler(requestJson) }
    }

    suspend fun envelope(block: suspend () -> String): String =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostCallException) {
            failure(e.code, e.message.orEmpty())
        } catch (e: PluginHttpException) {
            failure(PluginErrorCode.UNSUPPORTED, e.message.orEmpty())
        } catch (e: PluginQuotaException) {
            failure(PluginErrorCode.UNSUPPORTED, e.message.orEmpty())
        } catch (e: IOException) {
            failure(PluginErrorCode.NETWORK, e.message ?: e.javaClass.simpleName)
        } catch (e: SerializationException) {
            failure(PluginErrorCode.INTERNAL, "Bad request: ${e.message}")
        } catch (e: IllegalArgumentException) {
            failure(PluginErrorCode.INTERNAL, e.message.orEmpty())
        }

    fun asset(path: String): File {
        val file = File(directory, path).canonicalFile
        if (!file.path.startsWith(directory.canonicalPath + File.separator) || !file.isFile) {
            throw HostCallException(PluginErrorCode.NOT_FOUND, "No asset $path")
        }
        return file
    }

    private fun environment(): HostEnvironment {
        val locale = appLocale()
        return HostEnvironment(
            apiVersion = PLUGIN_API_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            locale = locale.toLanguageTag(),
            region = locale.country.ifEmpty { "US" },
            deviceClass = "tv",
            pluginVersion = plugin.manifest.version,
            osVersion = Build.VERSION.RELEASE,
            deviceModel = Build.MODEL,
        )
    }

    private fun <Request, Response> handler(
        operation: HostOperation<Request, Response>,
        run: suspend (Request) -> Response,
    ): Pair<String, suspend (String) -> String> =
        operation.path to { requestJson ->
            val request = PluginJson.decodeFromString(operation.request, requestJson)
            success(operation.response, run(request))
        }

    companion object {
        fun <T> success(
            serializer: KSerializer<T>,
            value: T,
        ): String =
            PluginJson.encodeToString(
                CallEnvelope.serializer(),
                CallEnvelope(result = PluginJson.encodeToJsonElement(serializer, value)),
            )

        fun failure(
            code: PluginErrorCode,
            message: String,
        ): String = PluginJson.encodeToString(CallEnvelope.serializer(), CallEnvelope(error = PluginError(code, message)))
    }
}

private fun String.hexBytes(): ByteArray? = runCatching { hexToByteArray() }.getOrNull()

internal fun pluginRandomBytes(length: Int): RandomBytesResult {
    require(length in 1..256) { "Random byte length must be between 1 and 256" }
    return RandomBytesResult(ByteArray(length).also(SecureRandom()::nextBytes).toHexString())
}
