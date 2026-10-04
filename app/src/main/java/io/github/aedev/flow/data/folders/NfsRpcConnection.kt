package io.github.aedev.flow.data.folders

import org.dcache.oncrpc4j.rpc.IoStrategy
import org.dcache.oncrpc4j.rpc.OncRpcClient
import org.dcache.oncrpc4j.rpc.OncRpcException
import org.dcache.oncrpc4j.rpc.RpcAuth
import org.dcache.oncrpc4j.rpc.RpcAuthType
import org.dcache.oncrpc4j.rpc.RpcAuthVerifier
import org.dcache.oncrpc4j.rpc.RpcCall
import org.dcache.oncrpc4j.rpc.RpcTransport
import org.dcache.oncrpc4j.xdr.Xdr
import org.dcache.oncrpc4j.xdr.XdrAble
import org.dcache.oncrpc4j.xdr.XdrDecodingStream
import org.dcache.oncrpc4j.xdr.XdrEncodingStream
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.security.auth.Subject

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

/**
 * AUTH_SYS credentials. oncrpc4j's RpcAuthTypeUnix builds a JAAS Subject from com.sun.security.auth principals
 * in its constructor, and those classes do not exist on Android, so the credential body is encoded here with
 * oncrpc4j's own XDR stream instead.
 */
internal class NfsAuthSys(
    uid: Int,
    gid: Int,
) : RpcAuth {
    private val body: ByteArray =
        Xdr(Xdr.INITIAL_XDR_SIZE).use { xdr ->
            xdr.beginEncoding()
            xdr.xdrEncodeInt((System.currentTimeMillis() / 1_000).toInt())
            xdr.xdrEncodeString(MACHINE_NAME)
            xdr.xdrEncodeInt(uid)
            xdr.xdrEncodeInt(gid)
            xdr.xdrEncodeIntVector(intArrayOf(gid))
            xdr.endEncoding()
            xdr.bytes
        }
    private val verifier = RpcAuthVerifier(RpcAuthType.NONE, ByteArray(0))

    override fun type(): Int = RpcAuthType.UNIX

    override fun getVerifier(): RpcAuthVerifier = verifier

    override fun getSubject(): Subject = Subject()

    override fun xdrEncode(xdr: XdrEncodingStream) {
        xdr.xdrEncodeInt(RpcAuthType.UNIX)
        xdr.xdrEncodeDynamicOpaque(body)
        verifier.xdrEncode(xdr)
    }

    override fun xdrDecode(xdr: XdrDecodingStream): Unit = throw UnsupportedOperationException("Client credentials are never decoded")

    private companion object {
        const val MACHINE_NAME = "milkbeat"
    }
}

internal const val NFS_TIMEOUT_MS = 15_000L
