package io.github.aedev.flow.ui.tv.focus

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged

/** Keep the end of a growing list reachable while its last row remains focused. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.tvEndFocus(
    isLast: Boolean,
    target: FocusRequester?,
): Modifier {
    if (target == null) return this
    var hasFocus by remember { mutableStateOf(false) }
    var enteredAtEnd by remember { mutableStateOf(false) }
    return onFocusChanged {
        if (it.hasFocus && !hasFocus) enteredAtEnd = isLast
        hasFocus = it.hasFocus
    }.focusProperties {
        exit = { direction ->
            if (direction == FocusDirection.Down && enteredAtEnd) target ?: FocusRequester.Default else FocusRequester.Default
        }
    }.focusGroup()
}
