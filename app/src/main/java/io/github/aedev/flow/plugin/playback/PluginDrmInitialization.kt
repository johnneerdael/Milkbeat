package io.github.aedev.flow.plugin.playback

import java.io.IOException

/**
 * Applies a licensed profile's public track identifiers to an initialization range: the narrow
 * byte-for-byte port of the reference adapter. The range must be a plain `moov` init segment with
 * exactly two `enca` entries whose `sinf/schi/tenc` boxes carry the default shared key; anything
 * else refuses, so a changed layout never corrupts media bytes.
 */
internal object PluginDrmInitialization {
    private data class Atom(
        val type: String,
        val start: Int,
        val end: Int,
    )

    fun apply(
        bytes: ByteArray,
        keyIds: List<ByteArray>,
    ) {
        checkFormat(bytes.size in 8..262_144 && keyIds.size == 2 && keyIds.all { it.size == 16 } && !keyIds[0].contentEquals(keyIds[1]))
        val root = atoms(bytes, 0, bytes.size)
        checkFormat(root.none { it.type == "moof" || it.type == "mdat" })
        val track = child(bytes, requireAtom(root, "moov"), "trak")
        val media = child(bytes, track, "mdia")
        val handler = child(bytes, media, "hdlr")
        checkFormat(handler.end - handler.start >= 12 && bytes.hasTag(handler.start + 8, "soun"))
        val table = child(bytes, child(bytes, child(bytes, media, "minf"), "stbl"), "stsd")
        checkFormat(table.end - table.start >= 8 && bytes.uint(table.start + 4) == 2L)
        val descriptions = atoms(bytes, table.start + 8, table.end)
        checkFormat(descriptions.size == 2)

        val positions =
            descriptions.map { entry ->
                checkFormat(
                    entry.type == "enca" && entry.end - entry.start >= 28 &&
                        bytes[entry.start + 8] == 0.toByte() && bytes[entry.start + 9] == 0.toByte(),
                )
                val protection = requireAtom(atoms(bytes, entry.start + 28, entry.end), "sinf")
                val format = child(bytes, protection, "frma")
                val scheme = child(bytes, protection, "schm")
                checkFormat(
                    format.end - format.start == 4 && bytes.hasTag(format.start, "ec-3") &&
                        scheme.end - scheme.start >= 8 && bytes.hasTag(scheme.start + 4, "cbcs"),
                )
                val trackEncryption = child(bytes, child(bytes, protection, "schi"), "tenc")
                val start = trackEncryption.start
                checkFormat(trackEncryption.end - start >= 24 && bytes[start].toInt() in 0..1 && bytes[start + 6] == 1.toByte())
                val ivSize = bytes[start + 7].toInt()
                checkFormat(
                    ivSize == 16 || (ivSize == 0 && trackEncryption.end - start >= 41 && bytes[start + 24] == 16.toByte()),
                )
                start + 8
            }
        val current = positions.map { bytes.copyOfRange(it, it + 16) }
        val mapped = current.indices.all { current[it].contentEquals(keyIds[it]) }
        checkFormat(mapped || current[0].contentEquals(current[1]))
        if (!mapped) keyIds.forEachIndexed { index, id -> id.copyInto(bytes, positions[index]) }
    }

    private fun child(
        bytes: ByteArray,
        parent: Atom,
        name: String,
    ) = requireAtom(atoms(bytes, parent.start, parent.end), name)

    private fun requireAtom(
        atoms: List<Atom>,
        name: String,
    ) = atoms.singleOrNull { it.type == name } ?: throw IOException("Unexpected plugin audio initialization layout")

    private fun atoms(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): List<Atom> =
        buildList {
            var cursor = start
            while (cursor < end) {
                checkFormat(end - cursor >= 8)
                val size = bytes.uint(cursor)
                val type = bytes.decodeToString(cursor + 4, cursor + 8)
                val header = if (size == 1L) 16 else 8
                val length =
                    when (size) {
                        0L -> {
                            (end - cursor).toLong()
                        }

                        1L -> {
                            checkFormat(end - cursor >= 16 && bytes.uint(cursor + 8) == 0L)
                            bytes.uint(cursor + 12)
                        }

                        else -> {
                            size
                        }
                    }
                checkFormat(length in header.toLong()..(end - cursor).toLong())
                val next = cursor + length.toInt()
                add(Atom(type, cursor + header, next))
                cursor = next
            }
        }

    private fun ByteArray.uint(offset: Int): Long =
        (0..3).fold(0L) { number, byte ->
            (number shl 8) or
                (this[offset + byte].toLong() and 255)
        }

    private fun ByteArray.hasTag(
        offset: Int,
        tag: String,
    ): Boolean = decodeToString(offset, offset + 4) == tag

    private fun checkFormat(ok: Boolean) {
        if (!ok) throw IOException("Unexpected plugin audio initialization layout")
    }
}
