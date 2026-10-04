package io.github.aedev.flow.plugin

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal class PlaylistMirrorHttpFixture(
    val sourcePositions: List<Int> = (0 until 100).map { if (it == 99) 0 else it },
) : Interceptor {
    val searches = CopyOnWriteArrayList<String>()
    val searchParams = CopyOnWriteArrayList<String>()
    val edits = CopyOnWriteArrayList<List<JSONObject>>()
    val playlistIds = CopyOnWriteArrayList<String>()
    val sourceReads = AtomicInteger()
    val activeSearches = AtomicInteger()
    val maximumSearches = AtomicInteger()
    val createCount = AtomicInteger()
    val createdWithSearches = AtomicInteger()
    val unexpected = CopyOnWriteArrayList<String>()
    var firstRound: CountDownLatch? = null
    var creationObserved: CountDownLatch? = null
    var searchRelease: CountDownLatch? = null
    var confirmationStarted: CountDownLatch? = null
    var confirmationRelease: CountDownLatch? = null
    var failSearch: Int? = null
    var primaryMissIndex: Int? = null
    private var description = ""

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { value -> Buffer().also(value::writeTo).readUtf8() }
        val data = body?.takeIf { it.startsWith("{") }?.let(::JSONObject) ?: JSONObject()
        val endpoint = request.url.encodedPath.removePrefix("/youtubei/v1/")
        var status = 200
        val result: String =
            when {
                request.url.host == "raw.githubusercontent.com" -> {
                    status = 503
                    "{}"
                }

                request.url.host == "www.youtube.com" &&
                    (endpoint == "/iframe_api" || endpoint.startsWith("/api/jnn/v1/")) -> {
                    status = 503
                    "{}"
                }

                endpoint == "/api/server-time" -> {
                    obj("serverTime" to 1_800_000_000).toString()
                }

                endpoint == "/api/token" -> {
                    obj(
                        "accessToken" to "synthetic-test-token",
                        "accessTokenExpirationTimestampMs" to System.currentTimeMillis() + 3_600_000,
                        "isAnonymous" to false,
                    ).toString()
                }

                request.url.host == "api-partner.spotify.com" -> {
                    spotify(data).toString()
                }

                endpoint == "/sw.js_data" -> {
                    ")]}'\n[[null,null,[\"CgtGSVhUVVJFMA%3D%3D\"]]]"
                }

                endpoint == "account/account_menu" -> {
                    obj(
                        "actions" to
                            listOf(
                                obj(
                                    "openPopupAction" to
                                        obj(
                                            "popup" to
                                                obj(
                                                    "multiPageMenuRenderer" to
                                                        obj(
                                                            "header" to
                                                                obj(
                                                                    "activeAccountHeaderRenderer" to
                                                                        obj(
                                                                            "accountName" to text("Fixture listener"),
                                                                        ),
                                                                ),
                                                        ),
                                                ),
                                        ),
                                ),
                            ),
                    ).toString()
                }

                endpoint == "search" -> {
                    check(request.header("authorization") == null) { "Matching must stay anonymous" }
                    val query = data.getString("query")
                    val index = Regex("Signal (\\d+)", RegexOption.IGNORE_CASE).find(query)!!.groupValues[1].toInt()
                    searches += query
                    searchParams += data.getString("params")
                    val active = activeSearches.incrementAndGet()
                    maximumSearches.updateAndGet { maxOf(it, active) }
                    try {
                        firstRound?.countDown()
                        firstRound?.let { check(it.await(15, TimeUnit.SECONDS)) { "The 16 searches and ENSURE did not overlap" } }
                        creationObserved?.let { check(it.await(15, TimeUnit.SECONDS)) { "Creation overlap was not recorded" } }
                        searchRelease?.let { check(it.await(15, TimeUnit.SECONDS)) { "Search release timed out" } }
                        if (failSearch == index) {
                            status = 503
                            "{}"
                        } else {
                            search(index, primaryMissIndex == index && query.startsWith("$ARTIST ")).toString()
                        }
                    } finally {
                        activeSearches.decrementAndGet()
                    }
                }

                endpoint == "playlist/create" -> {
                    check(data.getString("privacyStatus") == "PRIVATE")
                    description = data.getString("description")
                    createCount.incrementAndGet()
                    firstRound?.countDown()
                    firstRound?.let { check(it.await(15, TimeUnit.SECONDS)) { "Creation did not overlap all 16 searches" } }
                    createdWithSearches.set(activeSearches.get())
                    creationObserved?.countDown()
                    obj("playlistId" to PLAYLIST_ID).toString()
                }

                endpoint == "browse/edit_playlist" -> {
                    val actions = data.getJSONArray("actions").objects()
                    edits += actions
                    for (action in actions) {
                        when (action.getString("action")) {
                            "ACTION_ADD_VIDEO" -> {
                                check(action.getString("dedupeOption") == "DEDUPE_OPTION_SKIP")
                                playlistIds += action.getString("addedVideoId")
                            }

                            "ACTION_REMOVE_VIDEO" -> {
                                playlistIds.remove(action.getString("removedVideoId"))
                            }
                        }
                    }
                    obj("status" to "STATUS_SUCCEEDED").toString()
                }

                endpoint == "browse" && data.optString("browseId") == "VL$PLAYLIST_ID" -> {
                    if (playlistIds.isNotEmpty()) {
                        confirmationStarted?.countDown()
                        confirmationRelease?.let { check(it.await(15, TimeUnit.SECONDS)) { "Confirmation release timed out" } }
                    }
                    snapshot().toString()
                }

                endpoint == "browse" && data.optString("browseId") == "FEmusic_liked_playlists" -> {
                    "{}"
                }

                else -> {
                    unexpected += "${request.method} ${request.url.host}${request.url.encodedPath}"
                    throw IOException("No synthetic route for ${unexpected.last()}")
                }
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("Fixture")
            .body(result.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun spotify(request: JSONObject): JSONObject =
        when (request.getString("operationName")) {
            "profileAttributes" -> {
                obj("data" to obj("me" to obj("profile" to obj("uri" to "spotify:user:fixture", "name" to "Fixture"))))
            }

            "fetchPlaylist" -> {
                sourceReads.incrementAndGet()
                val variables = request.getJSONObject("variables")
                check(variables.getInt("limit") == 100) { "Spotify playlist reads must request 100 entries" }
                val offset = variables.getInt("offset")
                val positions = sourcePositions.drop(offset).take(variables.getInt("limit"))
                obj(
                    "data" to
                        obj(
                            "playlistV2" to
                                obj(
                                    "name" to "Fixture playlist",
                                    "revisionId" to "fixture-revision",
                                    "content" to
                                        obj(
                                            "totalCount" to sourcePositions.size,
                                            "items" to
                                                positions.map { index ->
                                                    obj(
                                                        "itemV2" to
                                                            obj(
                                                                "data" to
                                                                    obj(
                                                                        "uri" to "spotify:track:${index.toString().padStart(22, '0')}",
                                                                        "name" to "Signal $index",
                                                                        "artists" to
                                                                            obj("items" to listOf(obj("profile" to obj("name" to ARTIST)))),
                                                                        "trackDuration" to obj("totalMilliseconds" to 180_000),
                                                                    ),
                                                            ),
                                                    )
                                                },
                                        ),
                                ),
                        ),
                )
            }

            else -> {
                throw IOException("Unexpected Spotify operation ${request.getString("operationName")}")
            }
        }

    private fun search(
        index: Int,
        empty: Boolean = false,
    ): JSONObject {
        val rows = if (empty) emptyList() else listOf(trackRow(index))
        val shelf = obj("musicShelfRenderer" to obj("title" to text("Songs"), "contents" to rows))
        val section = obj("sectionListRenderer" to obj("contents" to listOf(shelf)))
        val tab = obj("tabRenderer" to obj("content" to section))
        return obj("contents" to obj("tabbedSearchResultsRenderer" to obj("tabs" to listOf(tab))))
    }

    private fun trackRow(index: Int): JSONObject {
        val title = obj("musicResponsiveListItemFlexColumnRenderer" to obj("text" to text("Signal $index")))
        val runs = listOf(obj("text" to ARTIST), obj("text" to " • "), obj("text" to "3:00"))
        val metadata = obj("musicResponsiveListItemFlexColumnRenderer" to obj("text" to obj("runs" to runs)))
        val row =
            obj(
                "playlistItemData" to obj("videoId" to videoId(index)),
                "flexColumns" to listOf(title, metadata),
            )
        return obj("musicResponsiveListItemRenderer" to row)
    }

    private fun snapshot(): JSONObject {
        val header =
            obj(
                "musicEditablePlaylistDetailHeaderRenderer" to
                    obj(
                        "editHeader" to obj("musicPlaylistEditHeaderRenderer" to obj("privacy" to "PRIVATE")),
                        "header" to
                            obj(
                                "musicResponsiveHeaderRenderer" to
                                    obj(
                                        "title" to text("Fixture playlist"),
                                        "description" to text(description),
                                    ),
                            ),
                    ),
            )
        val section = obj("sectionListRenderer" to obj("contents" to listOf(header)))
        val tab = obj("tabRenderer" to obj("content" to section))
        val rows =
            playlistIds.mapIndexed { index, id ->
                obj(
                    "musicResponsiveListItemRenderer" to
                        obj(
                            "playlistItemData" to obj("videoId" to id, "playlistSetVideoId" to "set$index"),
                        ),
                )
            }
        val shelf = obj("musicPlaylistShelfRenderer" to obj("contents" to rows))
        val secondary = obj("sectionListRenderer" to obj("contents" to listOf(shelf)))
        return obj(
            "contents" to
                obj(
                    "twoColumnBrowseResultsRenderer" to
                        obj(
                            "tabs" to listOf(tab),
                            "secondaryContents" to secondary,
                        ),
                ),
        )
    }

    companion object {
        const val PLAYLIST_ID = "PLfixture"
        const val ARTIST = "Fixture Orchestra"

        fun videoId(index: Int): String = "v${index.toString().padStart(10, '0')}"

        private fun text(value: String): JSONObject = obj("runs" to listOf(obj("text" to value)))

        private fun obj(vararg pairs: Pair<String, Any>): JSONObject =
            JSONObject().apply { pairs.forEach { (key, value) -> put(key, if (value is List<*>) JSONArray(value) else value) } }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    }
}
