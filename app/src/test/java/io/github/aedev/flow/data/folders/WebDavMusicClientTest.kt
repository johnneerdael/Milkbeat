package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WebDavMusicClientTest {
    private lateinit var dav: DavFileServer

    @Before fun setUp() {
        dav = DavFileServer().start()
    }

    @After fun tearDown() = dav.shutdown()

    private fun root(path: String = "/dav/Music") = dav.server.url(path).toString()

    private fun entries(
        body: String,
        path: String = "",
        base: String = "/dav/Music",
    ): List<MusicFolderEntry> {
        dav.propfindBodies[
            if (path.isEmpty()) {
                "$base/"
            } else {
                "$base/${path.split(
                    '/',
                ).joinToString("/") {
                    java.net.URLEncoder
                        .encode(it, "UTF-8")
                        .replace("+", "%20")
                }}/"
            },
        ] =
            body
        return davClient().list(davFolder(root(base)), MusicFolderSecrets(), path)
    }

    @Test fun apacheModDavSkipsSelfAndFiltersNonMusic() {
        val body =
            multistatus(
                davResponse("/dav/Music/", true),
                davResponse("/dav/Music/Albums/", true),
                davResponse("/dav/Music/track%2001.mp3", false, 1234, "audio/mpeg"),
                davResponse("/dav/Music/cover.jpg", false, 99, "image/jpeg"),
                davResponse("/dav/Music/notes.txt", false, 5, "text/plain"),
                extraNamespaces = "xmlns:ns0=\"DAV:\"",
            )
        val result = entries(body)
        assertThat(result.map { it.name }).containsExactly("Albums", "track 01.mp3")
        val track = result.single { !it.isDirectory }
        assertThat(track.location).isEqualTo("track 01.mp3")
        assertThat(track.size).isEqualTo(1234)
        assertThat(track.modified).isEqualTo(1_767_261_600_000)
        assertThat(result.single { it.isDirectory }.location).isEqualTo("Albums")
    }

    @Test fun nextcloudLowercasePrefixOwnCloudExtrasAndEncodedPaths() {
        val extras = "<oc:id>0001</oc:id><oc:permissions>RGDNVCK</oc:permissions><d:getetag>&quot;abc&quot;</d:getetag>"
        val missing = "<d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>"
        val body =
            multistatus(
                davResponse("/remote.php/dav/files/john/Music/", true, prefix = "d", extraProps = extras),
                davResponse("/remote.php/dav/files/john/Music/Caf%C3%A9%20%2312/", true, prefix = "d", extraProps = extras),
                davResponse(
                    "/remote.php/dav/files/john/Music/100%25%20%23%20track.flac",
                    false,
                    7,
                    "audio/flac",
                    prefix = "d",
                    extraProps = extras,
                ).replace("</d:response>", "$missing</d:response>"),
                prefix = "d",
                extraNamespaces = "xmlns:s=\"http://sabredav.org/ns\" xmlns:oc=\"http://owncloud.org/ns\"",
            )
        val result = entries(body, base = "/remote.php/dav/files/john/Music")
        assertThat(result.map { it.name }).containsExactly("Café #12", "100% # track.flac")
        assertThat(result.single { !it.isDirectory }.size).isEqualTo(7)
    }

    @Test fun rcloneStyleAndAbsoluteUrlHrefs() {
        val origin =
            dav.server
                .url("/")
                .toString()
                .removeSuffix("/")
        val body =
            multistatus(
                davResponse("$origin/dav/Music/", true),
                davResponse("$origin/dav/Music/%E6%97%A5%E6%9C%AC/", true),
                davResponse("$origin/dav/Music/%E6%97%A5%E6%9C%AC.opus", false, 3, null),
            )
        assertThat(entries(body).map { it.name }).containsExactly("日本", "日本.opus")
    }

    @Test fun rawUnencodedHrefsAreAccepted() {
        val body =
            multistatus(
                davResponse("/dav/Music/", true),
                davResponse("/dav/Music/a b é.mp3", false, 3, "audio/mpeg"),
            )
        assertThat(entries(body).map { it.name }).containsExactly("a b é.mp3")
    }

    @Test fun nonDirectChildrenAndEscapingHrefsAreIgnored() {
        val body =
            multistatus(
                davResponse("/dav/Music/", true),
                davResponse("/dav/Music/Sub/deep.mp3", false, 1, "audio/mpeg"),
                davResponse("/dav/Other/x.mp3", false, 1, "audio/mpeg"),
                davResponse("/dav/Music/../Secret/x.mp3", false, 1, "audio/mpeg"),
                davResponse("/dav/Music/a%2Fb.mp3", false, 1, "audio/mpeg"),
                davResponse("/dav/x.mp3", false, 1, "audio/mpeg"),
                davResponse("/dav/Music/ok.mp3", false, 1, "audio/mpeg"),
            )
        assertThat(entries(body).map { it.name }).containsExactly("ok.mp3")
    }

    @Test fun nonSuccessPropstatAndResponseStatusAreIgnored() {
        val body =
            multistatus(
                davResponse("/dav/Music/", true),
                davResponse("/dav/Music/missing.mp3", false, 1, "audio/mpeg", status = "HTTP/1.1 404 Not Found"),
                davResponse("/dav/Music/ok.mp3", false, 1, "audio/mpeg", status = "HTTP/1.1 200 OK"),
            )
        assertThat(entries(body).map { it.name }).containsExactly("ok.mp3")
    }

    @Test fun contentTypeAloneMakesAFileMusic() {
        val body =
            multistatus(
                davResponse("/dav/Music/", true),
                davResponse("/dav/Music/stream", false, 1, "audio/mpeg; charset=binary"),
            )
        assertThat(entries(body).map { it.name }).containsExactly("stream")
    }

    @Test fun nestedListingUsesFolderRelativeLocations() {
        val body =
            multistatus(
                davResponse("/dav/Music/Albums/Abbey%20Road/", true),
                davResponse("/dav/Music/Albums/Abbey%20Road/01.mp3", false, 1, "audio/mpeg"),
            )
        val result = entries(body, "Albums/Abbey Road")
        assertThat(result.single().location).isEqualTo("Albums/Abbey Road/01.mp3")
        val propfind = dav.requests.single { it.method == "PROPFIND" }
        assertThat(propfind.headers["Depth"]).isEqualTo("1")
        assertThat(propfind.url.encodedPath).isEqualTo("/dav/Music/Albums/Abbey%20Road/")
        assertThat(propfind.body?.utf8()).contains("getcontentlength")
    }

    @Test fun listRequiresMultistatus() {
        dav.propfindBodies["/dav/Music/"] = "<html/>"
        dav.propfindStatus = 200
        assertThrows(IOException::class.java) { davClient().list(davFolder(root()), MusicFolderSecrets(), "") }
    }

    @Test fun malformedXmlIsAnIOException() {
        dav.propfindBodies["/dav/Music/"] = "<D:multistatus xmlns:D=\"DAV:\"><D:response>"
        assertThrows(IOException::class.java) { davClient().list(davFolder(root()), MusicFolderSecrets(), "") }
    }

    @Test fun pathTraversalIsRejectedBeforeAnyRequest() {
        val folder = davFolder(root())
        listOf("../x", "a/../../x", "a/./b", "//x").forEach {
            assertThrows(IllegalArgumentException::class.java) { davClient().list(folder, MusicFolderSecrets(), it) }
            assertThrows(IllegalArgumentException::class.java) { davClient().open(folder, MusicFolderSecrets(), it) }
            assertThrows(IllegalArgumentException::class.java) { davClient().stream(folder, MusicFolderSecrets(), it) }
        }
        assertThat(dav.requests).isEmpty()
    }

    @Test fun testRequiresACollectionAndReturnsSourceUnchanged() {
        val folder = davFolder(root())
        dav.propfindBodies["/dav/Music/"] = multistatus(davResponse("/dav/Music/", true))
        assertThat(davClient().test(folder, MusicFolderSecrets())).isSameInstanceAs(folder)
        assertThat(dav.requests.single().headers["Depth"]).isEqualTo("0")
        dav.propfindBodies["/dav/Music/"] = multistatus(davResponse("/dav/Music/", false, 4))
        assertThrows(IOException::class.java) { davClient().test(folder, MusicFolderSecrets()) }
    }

    @Test fun testMapsAuthFailuresToIOException() {
        dav.server.close()
        val rejecting = MockWebServer()
        rejecting.enqueue(
            MockResponse
                .Builder()
                .code(401)
                .addHeader("WWW-Authenticate", "Basic realm=\"x\"")
                .build(),
        )
        rejecting.enqueue(MockResponse.Builder().code(403).build())
        rejecting.start()
        val folder = davFolder(rejecting.url("/dav").toString(), guest = false, username = "me")
        repeat(2) { assertThrows(IOException::class.java) { davClient().test(folder, MusicFolderSecrets("pw")) } }
        rejecting.close()
    }

    @Test fun anonymousSendsNoAuthorizationAndBasicIsPreemptive() {
        dav.propfindBodies["/dav/Music/"] = multistatus(davResponse("/dav/Music/", true))
        davClient().test(davFolder(root()), MusicFolderSecrets("ignored"))
        assertThat(dav.requests.last().headers["Authorization"]).isNull()
        davClient().test(davFolder(root(), guest = false, username = "jöhn"), MusicFolderSecrets("pä:ss"))
        val expected = Credentials.basic("jöhn", "pä:ss", Charsets.UTF_8)
        assertThat(dav.requests.last().headers["Authorization"]).isEqualTo(expected)
        assertThat(dav.requests).hasSize(2)
    }

    @Test fun streamBuildsEncodedUrlAndHeaders() {
        val folder = davFolder(root("/dav/My Music"), guest = false, username = "u")
        val stream = davClient().stream(folder, MusicFolderSecrets("p"), "Alb#um/100% é.mp3")
        assertThat(stream.url).isEqualTo(dav.server.url("/dav/My%20Music/Alb%23um/100%25%20%C3%A9.mp3").toString())
        assertThat(stream.headers).containsExactly("Authorization", Credentials.basic("u", "p", Charsets.UTF_8))
        assertThat(davClient().stream(davFolder(root()), MusicFolderSecrets(), "a.mp3").headers).isEmpty()
    }

    @Test fun authorizationIsDroppedOnCrossHostRedirect() {
        val target = DavFileServer().start()
        target.files["/a.mp3"] = ByteArray(4)
        dav.server.dispatcher =
            object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest) =
                    MockResponse
                        .Builder()
                        .code(302)
                        .addHeader("Location", target.server.url("/a.mp3").toString())
                        .build()
            }
        val client = davClient()
        val stream = client.stream(davFolder(root(), guest = false, username = "u"), MusicFolderSecrets("p"), "a.mp3")
        val request =
            okhttp3.Request
                .Builder()
                .url(stream.url)
                .apply { stream.headers.forEach { (k, v) -> header(k, v) } }
                .build()
        client.httpClient
            .newCall(request)
            .execute()
            .use { assertThat(it.code).isEqualTo(200) }
        assertThat(target.requests.single().headers["Authorization"]).isNull()
        target.shutdown()
    }
}
