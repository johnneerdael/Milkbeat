package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import org.dcache.oncrpc4j.rpc.RpcAuth
import org.dcache.oncrpc4j.rpc.RpcAuthTypeUnix
import org.dcache.oncrpc4j.xdr.Xdr
import org.junit.Test

class NfsAuthSysTest {
    private fun encode(auth: RpcAuth): ByteArray =
        Xdr(Xdr.INITIAL_XDR_SIZE).use { xdr ->
            xdr.beginEncoding()
            auth.xdrEncode(xdr)
            xdr.endEncoding()
            xdr.bytes
        }

    @Test fun credentialsAreWireIdenticalToRpcAuthTypeUnix() {
        for ((uid, gid) in listOf(0 to 0, 1026 to 100, 65534 to 65534)) {
            val stamp = 1_759_571_234
            assertThat(encode(NfsAuthSys(uid, gid, stamp)))
                .isEqualTo(encode(RpcAuthTypeUnix(uid, gid, intArrayOf(gid), stamp, NfsAuthSys.MACHINE_NAME)))
        }
    }
}
