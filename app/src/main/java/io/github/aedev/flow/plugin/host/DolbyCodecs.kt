package io.github.aedev.flow.plugin.host

/**
 * Dolby codecs the host reports to providers, from the decoder MIME types the device advertises.
 * Atmos over E-AC-3 needs its own `audio/eac3-joc` decoder; a plain `audio/eac3` decoder plays Dolby
 * Digital Plus without the height channels, and claiming Atmos for it makes providers pick a tier the
 * TV cannot decode.
 */
internal fun dolbyCodecsFor(decoderTypes: Set<String>): List<String> =
    buildList {
        if ("audio/eac3" in decoderTypes || "audio/eac3-joc" in decoderTypes) add("eac3")
        if ("audio/eac3-joc" in decoderTypes) add("eac3-joc")
        if ("audio/ac4" in decoderTypes) add("ac4")
    }
