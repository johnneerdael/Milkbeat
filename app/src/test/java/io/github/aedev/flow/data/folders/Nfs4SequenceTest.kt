package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import org.dcache.nfs.v4.CompoundBuilder
import org.dcache.nfs.v4.xdr.COMPOUND4args
import org.dcache.nfs.v4.xdr.nfs_opnum4
import org.dcache.nfs.v4.xdr.sessionid4
import org.dcache.oncrpc4j.xdr.Xdr
import org.junit.Test

class Nfs4SequenceTest {
    private fun wireSequenceIds(lastSequence: Int): Pair<Int, Int> {
        val args = sequencedCompound(sessionid4(ByteArray(16)), lastSequence, 1, CompoundBuilder().withPutrootfh())
        val bytes =
            Xdr(Xdr.INITIAL_XDR_SIZE).use { xdr ->
                xdr.beginEncoding()
                args.xdrEncode(xdr)
                xdr.endEncoding()
                xdr.bytes
            }
        val decoded =
            Xdr(bytes).use { xdr ->
                xdr.beginDecoding()
                COMPOUND4args(xdr)
            }
        assertThat(decoded.argarray.map { it.argop }).containsExactly(nfs_opnum4.OP_SEQUENCE, nfs_opnum4.OP_PUTROOTFH).inOrder()
        return decoded.argarray[0]
            .opsequence.sa_sequenceid.value to decoded.minorversion.value
    }

    @Test fun aFreshSlotSendsSequenceIdOne() {
        assertThat(wireSequenceIds(lastSequence = 0)).isEqualTo(1 to 1)
    }

    @Test fun eachAcceptedRequestAdvancesTheWireIdByOne() {
        assertThat(wireSequenceIds(lastSequence = 41).first).isEqualTo(42)
    }
}
