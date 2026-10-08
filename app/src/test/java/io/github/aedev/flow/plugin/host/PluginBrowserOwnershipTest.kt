package io.github.aedev.flow.plugin.host

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.plugin.BrowserEvaluateRequest
import nl.neerdael.milkbeat.plugin.BrowserOpenRequest
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PluginBrowserOwnershipTest {
    private class Page(
        override val id: String,
    ) : PluginBrowser.BrowserPage {
        var destroyed = 0

        override suspend fun load(
            baseUrl: String,
            html: String,
        ) {}

        override suspend fun evaluate(script: String): String = "session:$id"

        override fun destroy() {
            destroyed++
        }
    }

    @Test
    fun `same plugin warm and main retirement close only the originating generation`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val asset = File.createTempFile("browser-owner", ".html").apply { writeText("fixture") }
            val pages = mutableListOf<Page>()
            val factory: (Context, String?) -> PluginBrowser.BrowserPage = { _, _ -> Page("page-${pages.size}").also(pages::add) }
            val browser = PluginBrowser(mockk<Context>(), listOf("www.youtube.com"), { asset }, factory)
            val request = BrowserOpenRequest("fixture.html", "https://www.youtube.com")
            val main = PluginBrowser.Owner()
            val warm = PluginBrowser.Owner()
            try {
                val mainPage = browser.open(request, main)
                browser.open(request, warm)
                browser.closeOwner(warm)
                assertThat(pages[0].destroyed).isEqualTo(0)
                assertThat(pages[1].destroyed).isEqualTo(1)
                assertThat(browser.evaluate(BrowserEvaluateRequest(mainPage.id, "fixture"), main).value).isEqualTo("session:page-0")
                val newMain = PluginBrowser.Owner()
                val newPage = browser.open(request, newMain)
                browser.closeOwner(main)
                browser.closeOwner(main)
                assertThat(pages[0].destroyed).isEqualTo(1)
                assertThat(pages[2].destroyed).isEqualTo(0)
                assertThat(runCatching { browser.evaluate(BrowserEvaluateRequest(mainPage.id, "fixture"), newMain) }.isFailure).isTrue()
                assertThat(browser.evaluate(BrowserEvaluateRequest(newPage.id, "fixture"), newMain).value).isEqualTo("session:page-2")
                browser.closeAll()
                assertThat(pages[2].destroyed).isEqualTo(1)
                assertThat(runCatching { browser.open(request, PluginBrowser.Owner()) }.isFailure).isTrue()
            } finally {
                browser.closeAll()
                asset.delete()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `canceling a loading page destroys it even when the caller job is canceled`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val asset = File.createTempFile("browser-owner", ".html").apply { writeText("fixture") }
            val owner = PluginBrowser.Owner()
            val entered = CompletableDeferred<Unit>()
            var destroyed = 0
            val browser =
                PluginBrowser(mockk<Context>(), listOf("www.youtube.com"), { asset }) { _, _ ->
                    object : PluginBrowser.BrowserPage {
                        override val id = "pending"

                        override suspend fun load(
                            baseUrl: String,
                            html: String,
                        ) {
                            entered.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        }

                        override suspend fun evaluate(script: String): String = "unused"

                        override fun destroy() {
                            destroyed++
                        }
                    }
                }
            try {
                val open = async { browser.open(BrowserOpenRequest("fixture.html", "https://www.youtube.com"), owner) }
                entered.await()
                open.cancelAndJoin()
                assertThat(destroyed).isEqualTo(1)
                browser.closeOwner(owner)
                assertThat(destroyed).isEqualTo(1)
            } finally {
                browser.closeAll()
                asset.delete()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `owner retired while a page is created cannot register a new orphan`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val asset = File.createTempFile("browser-owner", ".html").apply { writeText("fixture") }
            val owner = PluginBrowser.Owner()
            val page = Page("late")
            val browser =
                PluginBrowser(mockk<Context>(), listOf("www.youtube.com"), { asset }) { _, _ ->
                    owner.retire()
                    page
                }
            try {
                val failed = runCatching { browser.open(BrowserOpenRequest("fixture.html", "https://www.youtube.com"), owner) }
                assertThat(failed.isFailure).isTrue()
                assertThat(page.destroyed).isEqualTo(1)
                browser.closeOwner(owner)
                assertThat(page.destroyed).isEqualTo(1)
            } finally {
                browser.closeAll()
                asset.delete()
                Dispatchers.resetMain()
            }
        }
}
