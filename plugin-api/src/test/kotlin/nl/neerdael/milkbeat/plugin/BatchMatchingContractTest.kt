package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.ExperimentalSerializationApi
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class BatchMatchingContractTest {
    @Test
    fun `batch matching is a published plugin operation`() {
        assertThat(PluginOperations.all.map { it.path }).contains("audio.matchBatch")
    }

    @Test
    fun `single matching carries an optional fallback strategy`() {
        assertThat(MatchAudioRequest.serializer().descriptor.elementNames()).contains("strategy")
    }

    @Test
    fun `candidate results distinguish transient failures from misses`() {
        assertThat(AudioMatches.serializer().descriptor.elementNames()).contains("error")
    }

    @Test
    fun `import results expose write and confirmation progress`() {
        assertThat(PrivatePlaylistImportResult.serializer().descriptor.elementNames()).contains("progress")
    }

    @Test
    fun `manifest advertises optional batch matching`() {
        assertThat(AudioRole.serializer().descriptor.elementNames()).contains("batchMatching")
    }

    private fun kotlinx.serialization.descriptors.SerialDescriptor.elementNames(): List<String> =
        (0 until elementsCount).map(::getElementName)
}
