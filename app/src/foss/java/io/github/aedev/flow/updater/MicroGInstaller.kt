package io.github.aedev.flow.updater

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Foss builds never install other apps; the listener installs MicroG-RE-TV themselves. */
@Singleton
class MicroGInstaller
    @Inject
    constructor() {
        val failed: StateFlow<Boolean> = MutableStateFlow(false)

        val isAvailable: Boolean = false

        fun canInstall(): Boolean = false

        fun installPermissionIntent(): Intent = Intent()

        suspend fun install(): Boolean = false
    }
