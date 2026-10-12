package io.github.aedev.flow.plugin.host

import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugins whose MicroG bindings a sign-out or removal failed to delete, kept apart from the store that failed so the
 * revocation outlives the run. Calls block.
 */
internal interface RevocationTombstones {
    fun contains(pluginId: String): Boolean

    fun add(pluginId: String)

    fun remove(pluginId: String)
}

/** One empty file per plugin; creating it needs no room the failed store lacked beyond a directory entry. */
internal class FileRevocationTombstones(
    private val directory: File,
) : RevocationTombstones {
    private fun file(pluginId: String) = File(directory, URLEncoder.encode(pluginId, "UTF-8"))

    override fun contains(pluginId: String): Boolean = file(pluginId).exists()

    override fun add(pluginId: String) {
        directory.mkdirs()
        file(pluginId).createNewFile()
    }

    override fun remove(pluginId: String) {
        file(pluginId).delete()
    }
}

internal class MemoryRevocationTombstones : RevocationTombstones {
    private val plugins = ConcurrentHashMap.newKeySet<String>()

    override fun contains(pluginId: String): Boolean = pluginId in plugins

    override fun add(pluginId: String) {
        plugins += pluginId
    }

    override fun remove(pluginId: String) {
        plugins -= pluginId
    }
}
