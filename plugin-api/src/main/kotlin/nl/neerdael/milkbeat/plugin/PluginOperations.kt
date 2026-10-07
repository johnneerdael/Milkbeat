package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import nl.neerdael.milkbeat.catalog.CommentsPage
import nl.neerdael.milkbeat.catalog.CommentsRequest
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.LiveChatBatch
import nl.neerdael.milkbeat.catalog.LiveChatRequest
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.PageRequest
import nl.neerdael.milkbeat.catalog.PersonalCollectionsPage
import nl.neerdael.milkbeat.catalog.PersonalCollectionsRequest
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.RadioRequest
import nl.neerdael.milkbeat.catalog.SearchRequest
import nl.neerdael.milkbeat.catalog.SuggestRequest
import nl.neerdael.milkbeat.catalog.Suggestions
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.catalog.TracksRequest

/** A call the host makes into a plugin: `role.name`, with the JSON shapes it sends and expects back. */
class PluginOperation<Request, Response>(
    val path: String,
    val request: KSerializer<Request>,
    val response: KSerializer<Response>,
)

@Serializable
data class SettingOptionsRequest(
    val key: String,
)

/** Every operation of plugin API v1. The SDK's TypeScript plugin interface is generated from this list. */
object PluginOperations {
    val warmUp = PluginOperation("lifecycle.warmUp", Unit.serializer(), Unit.serializer())

    /** The listener changed one of the plugin's settings; region- or language-bound caches are stale. */
    val settingsChanged = PluginOperation("lifecycle.settingsChanged", Unit.serializer(), Unit.serializer())

    val home = PluginOperation("metadata.home", HomeRequest.serializer(), MetadataPage.serializer())
    val search = PluginOperation("metadata.search", SearchRequest.serializer(), MetadataPage.serializer())
    val suggest = PluginOperation("metadata.suggest", SuggestRequest.serializer(), Suggestions.serializer())
    val entity = PluginOperation("metadata.entity", PageRequest.serializer(), MetadataPage.serializer())
    val tracks = PluginOperation("metadata.tracks", TracksRequest.serializer(), TrackList.serializer())
    val library = PluginOperation("metadata.library", LibraryRequest.serializer(), MetadataPage.serializer())
    val personalCollections =
        PluginOperation("metadata.personalCollections", PersonalCollectionsRequest.serializer(), PersonalCollectionsPage.serializer())
    val importPrivatePlaylist =
        PluginOperation(
            "metadata.importPrivatePlaylist",
            PrivatePlaylistImportRequest.serializer(),
            PrivatePlaylistImportResult.serializer(),
        )
    val radio = PluginOperation("metadata.radio", RadioRequest.serializer(), TrackList.serializer())

    val resolveAudio = PluginOperation("audio.resolve", ResolveAudioRequest.serializer(), AudioStream.serializer())
    val matchAudio = PluginOperation("audio.match", MatchAudioRequest.serializer(), AudioMatches.serializer())
    val matchAudioBatch = PluginOperation("audio.matchBatch", MatchAudioBatchRequest.serializer(), AudioMatchesBatch.serializer())
    val audioRadio = PluginOperation("audio.radio", RadioRequest.serializer(), TrackList.serializer())
    val reportListen = PluginOperation("audio.reportPlayback", ReportPlaybackRequest.serializer(), Unit.serializer())

    val videoSearch = PluginOperation("video.search", SearchRequest.serializer(), MetadataPage.serializer())
    val videoSuggest = PluginOperation("video.suggest", SuggestRequest.serializer(), Suggestions.serializer())
    val videoEntity = PluginOperation("video.entity", PageRequest.serializer(), MetadataPage.serializer())
    val videoTracks = PluginOperation("video.tracks", TracksRequest.serializer(), TrackList.serializer())
    val related = PluginOperation("video.related", PageRequest.serializer(), MetadataPage.serializer())
    val resolveVideo = PluginOperation("video.resolve", ResolveVideoRequest.serializer(), VideoPlayback.serializer())
    val comments = PluginOperation("video.comments", CommentsRequest.serializer(), CommentsPage.serializer())
    val liveChat = PluginOperation("video.liveChat", LiveChatRequest.serializer(), LiveChatBatch.serializer())
    val reportView = PluginOperation("video.reportPlayback", ReportPlaybackRequest.serializer(), Unit.serializer())

    val beginSignIn = PluginOperation("signIn.begin", DeviceCodeBeginRequest.serializer(), DeviceCodeChallenge.serializer())
    val pollSignIn = PluginOperation("signIn.poll", DeviceCodeSession.serializer(), DeviceCodePollResult.serializer())
    val cancelSignIn = PluginOperation("signIn.cancel", DeviceCodeSession.serializer(), Unit.serializer())

    val completeSignIn = PluginOperation("signIn.complete", WebLoginResult.serializer(), ProviderAccount.serializer())
    val account = PluginOperation("signIn.account", Unit.serializer(), ProviderAccount.serializer())
    val signOut = PluginOperation("signIn.signOut", Unit.serializer(), Unit.serializer())

    val settingOptions =
        PluginOperation("settings.options", SettingOptionsRequest.serializer(), ListSerializer(SettingOption.serializer()))

    val all: List<PluginOperation<*, *>> =
        listOf(
            warmUp,
            settingsChanged,
            home,
            search,
            suggest,
            entity,
            tracks,
            library,
            personalCollections,
            importPrivatePlaylist,
            radio,
            resolveAudio,
            matchAudio,
            matchAudioBatch,
            audioRadio,
            reportListen,
            videoSearch,
            videoSuggest,
            videoEntity,
            videoTracks,
            related,
            resolveVideo,
            comments,
            liveChat,
            reportView,
            beginSignIn,
            pollSignIn,
            cancelSignIn,
            completeSignIn,
            account,
            signOut,
            settingOptions,
        )
}
