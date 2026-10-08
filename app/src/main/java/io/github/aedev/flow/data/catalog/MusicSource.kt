package io.github.aedev.flow.data.catalog

import androidx.annotation.DrawableRes
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import nl.neerdael.milkbeat.plugin.MetadataSurface

/** Where one music tab's catalog comes from: a metadata plugin, or the local library. */
sealed interface MusicSource {
    val key: String

    /** The id catalog pages carry as their `provider` argument. */
    val providerId: String

    data class Plugin(
        val id: String,
    ) : MusicSource {
        override val key: String get() = "$PLUGIN_PREFIX$id"
        override val providerId: String get() = id
    }

    data object Local : MusicSource {
        override val key: String = "local"
        override val providerId: String = LocalCatalogProvider.ID
    }

    companion object {
        private const val PLUGIN_PREFIX = "plugin:"

        fun fromKey(key: String?): MusicSource? =
            when {
                key == null -> null
                key == Local.key -> Local
                key.startsWith(PLUGIN_PREFIX) && key.length > PLUGIN_PREFIX.length -> Plugin(key.removePrefix(PLUGIN_PREFIX))
                else -> null
            }
    }
}

/** One music tab of the rail; [label] is null for the local library, whose name is a string resource. */
data class MusicTab(
    val source: MusicSource,
    val label: String?,
    @param:DrawableRes val iconRes: Int?,
    val expired: Boolean = false,
    val signedIn: Boolean = false,
    /** Shown before its account is known (YouTube Music works signed out); its home waits for the answer. */
    val accountPending: Boolean = false,
    val surfaces: Set<MetadataSurface> = emptySet(),
    /** Changes with the account and the installation answering for this tab; what it showed is stale then. */
    val identity: String = "",
)

/** The music tabs, and whether every account they depend on has answered yet. */
data class MusicTabs(
    val tabs: List<MusicTab> = emptyList(),
    val settled: Boolean = false,
)
