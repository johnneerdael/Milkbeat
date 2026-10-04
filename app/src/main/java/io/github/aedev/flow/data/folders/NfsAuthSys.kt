package io.github.aedev.flow.data.folders

import org.dcache.oncrpc4j.rpc.RpcAuth
import org.dcache.oncrpc4j.rpc.RpcAuthType
import org.dcache.oncrpc4j.rpc.RpcAuthVerifier
import org.dcache.oncrpc4j.xdr.Xdr
import org.dcache.oncrpc4j.xdr.XdrDecodingStream
import org.dcache.oncrpc4j.xdr.XdrEncodingStream
import javax.security.auth.Subject

/** AUTH_SYS credentials; oncrpc4j's RpcAuthTypeUnix needs com.sun.security.auth principals that Android lacks. */
internal class NfsAuthSys(
    uid: Int,
    gid: Int,
    stamp: Int = (System.currentTimeMillis() / 1_000).toInt(),
) : RpcAuth {
    private val body: ByteArray =
        Xdr(Xdr.INITIAL_XDR_SIZE).use { xdr ->
            xdr.beginEncoding()
            xdr.xdrEncodeInt(stamp)
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

    companion object {
        const val MACHINE_NAME = "milkbeat"
    }
}
