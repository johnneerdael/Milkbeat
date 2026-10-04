package io.github.aedev.flow.data.folders

import org.dcache.oncrpc4j.rpc.IoStrategy
import org.dcache.oncrpc4j.rpc.OncRpcClient
import org.dcache.oncrpc4j.rpc.OncRpcException
import org.dcache.oncrpc4j.rpc.RpcAuth
import org.dcache.oncrpc4j.rpc.RpcCall
import org.dcache.oncrpc4j.rpc.RpcTransport
import org.dcache.oncrpc4j.xdr.XdrAble
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One TCP connection to an ONC RPC server, owning the oncrpc4j client and its Grizzly threads. */
internal class NfsRpcConnection private constructor(
    private val client: OncRpcClient,
    private val transport: RpcTransport,
) : Closeable {
    val isOpen: Boolean get() = transport.isOpen

    fun call(
        program: Int,
        version: Int,
        procedure: Int,
        auth: RpcAuth,
        args: XdrAble,
        result: XdrAble,
        timeoutMs: Long = NFS_TIMEOUT_MS,
    ) {
        try {
            RpcCall(program, version, auth, transport).call(procedure, args, result, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            throw SocketTimeoutException("NFS server did not answer within $timeoutMs ms").apply { initCause(error) }
        } catch (error: InterruptedIOException) {
            throw interruption(error)
        } catch (error: OncRpcException) {
            throw rpcFailure(error)
        }
    }

    override fun close() {
        client.close()
    }

    companion object {
        fun connect(
            host: String,
            port: Int,
            timeoutMs: Long = NFS_TIMEOUT_MS,
        ): NfsRpcConnection {
            val address = InetSocketAddress(host.removeSurrounding("[", "]"), port)
            if (address.isUnresolved) throw UnknownHostException("Unknown NFS host")
            // Replies are decoded on the selector thread (SAME_THREAD), so no worker pool is started; oncrpc4j
            // still enforces two selector runners.
            val client =
                OncRpcClient
                    .newBuilder()
                    .withTCP()
                    .withIoStrategy(IoStrategy.SAME_THREAD)
                    .withSelectorThreadPoolSize(1)
                    .withServiceName("milkbeat-nfs")
                    .withConnectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build(address)
            try {
                return NfsRpcConnection(client, client.connect())
            } catch (error: IOException) {
                client.close()
                throw interruption(error)
            } catch (error: RuntimeException) {
                client.close()
                throw error
            }
        }

        private fun interruption(error: IOException): Exception {
            if (error is SocketTimeoutException || generateSequence<Throwable>(error) { it.cause }.none { it is InterruptedException }) {
                return error
            }
            Thread.currentThread().interrupt()
            return InterruptedException("NFS request interrupted").apply { initCause(error) }
        }
    }
}

internal const val NFS_TIMEOUT_MS = 15_000L
