package io.github.aedev.flow.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugLoggingPreferencesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `logging is off on a fresh install and opt-in survives preference recreation`() =
        runTest {
            val file = temporaryFolder.newFolder().resolve("debug.preferences_pb")
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val store = PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file })
            val preferences = DebugLoggingPreferences(store)
            try {
                assertThat(preferences.enabled.first()).isFalse()
                preferences.setEnabled(true)
                assertThat(preferences.enabled.first()).isTrue()
            } finally {
                firstScope.cancel()
            }
            firstScope.coroutineContext[kotlinx.coroutines.Job]?.join()
            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reopened = DebugLoggingPreferences(PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file }))
            try {
                assertThat(reopened.enabled.first()).isTrue()
                reopened.setEnabled(false)
                assertThat(reopened.enabled.first()).isFalse()
            } finally {
                secondScope.cancel()
            }
        }
}
