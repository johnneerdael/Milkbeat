package io.github.aedev.flow.data.library.catalog

import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef

/** The lists a home shelf's "Show all" opens, each optionally narrowed to the home's genre. */
internal enum class LocalSection { RECENT, ARTISTS, RELEASES, LABELS }

/** What a page of the local library is about, as carried in an [EntityRef]'s provider id. */
internal sealed interface LocalRef {
    val entity: EntityRef

    data class Artist(
        val name: String,
    ) : LocalRef {
        override val entity get() = EntityRef(EntityKind.ARTIST, "$PREFIX$ARTIST$name")
    }

    data class Release(
        val key: String,
    ) : LocalRef {
        override val entity get() = EntityRef(EntityKind.ALBUM, "$PREFIX$RELEASE$key")
    }

    data class Label(
        val name: String,
    ) : LocalRef {
        override val entity get() = EntityRef(EntityKind.MIX, "$PREFIX$LABEL$name")
    }

    data class Year(
        val year: Int,
    ) : LocalRef {
        override val entity get() = EntityRef(EntityKind.MIX, "$PREFIX$YEAR$year")
    }

    data class Playlist(
        val id: String,
    ) : LocalRef {
        override val entity get() = EntityRef(EntityKind.PLAYLIST, "$PREFIX$PLAYLIST$id")
    }

    data class All(
        val section: LocalSection,
        val genre: String?,
    ) : LocalRef {
        override val entity get() = EntityRef(EntityKind.MIX, "$PREFIX$ALL${section.name}" + (genre?.let { ":$it" } ?: ""))
    }

    companion object {
        private const val PREFIX = "local:"
        private const val ARTIST = "artist:"
        private const val RELEASE = "release:"
        private const val LABEL = "label:"
        private const val YEAR = "year:"
        private const val PLAYLIST = "playlist:"
        private const val ALL = "all:"

        fun parse(entity: EntityRef): LocalRef? {
            val id = entity.providerId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
            return when {
                id.startsWith(ARTIST) -> {
                    Artist(id.removePrefix(ARTIST))
                }

                id.startsWith(RELEASE) -> {
                    Release(id.removePrefix(RELEASE))
                }

                id.startsWith(LABEL) -> {
                    Label(id.removePrefix(LABEL))
                }

                id.startsWith(YEAR) -> {
                    id.removePrefix(YEAR).toIntOrNull()?.let(::Year)
                }

                id.startsWith(PLAYLIST) -> {
                    Playlist(id.removePrefix(PLAYLIST))
                }

                id.startsWith(ALL) -> {
                    val rest = id.removePrefix(ALL)
                    val section = LocalSection.entries.firstOrNull { it.name == rest.substringBefore(':') } ?: return null
                    All(section, rest.substringAfter(':', "").ifEmpty { null })
                }

                else -> {
                    null
                }
            }
        }
    }
}
