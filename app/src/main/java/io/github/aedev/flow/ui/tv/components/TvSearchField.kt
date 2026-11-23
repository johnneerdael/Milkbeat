package io.github.aedev.flow.ui.tv.components

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import kotlinx.coroutines.flow.drop

/**
 * The TV search box: a standard text field typed with the system keyboard, so every character and the
 * keyboard's own suggestions are there, with a voice button beside it when the device offers voice
 * input. Landing on the field with the D-pad does not pop the keyboard over the page; center opens it,
 * and the arrows leave the field wherever its cursor cannot move.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onVoice: (() -> Unit)? = null,
    placeholder: String = stringResource(R.string.tv_search_prompt),
    leadingIcon: ImageVector = Icons.Outlined.Search,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Search,
    secure: Boolean = false,
    label: String? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val state = remember { TextFieldState(initialText = query) }
    val currentOnQueryChange by rememberUpdatedState(onQueryChange)
    val fieldFocus = remember { FocusRequester() }
    var passwordVisible by remember(secure) { mutableStateOf(false) }

    // The keyboard may only open on focus while the user asked to type: center re-focuses the field
    // with it allowed, and it is taken back once the keyboard has closed again.
    var typing by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    var keyboardSeen by remember { mutableStateOf(false) }
    LaunchedEffect(typing) {
        if (!typing) return@LaunchedEffect
        keyboardSeen = false
        focusManager.clearFocus()
        withFrameNanos {}
        fieldFocus.requestFocus()
    }
    LaunchedEffect(imeVisible, typing) {
        if (imeVisible) {
            keyboardSeen = true
        } else if (typing && keyboardSeen) {
            typing = false
        }
    }

    // Only a query changed from outside (a picked suggestion, a voice result) is written back into the
    // field; echoing the field's own edits back would overwrite letters typed since.
    var lastReported by remember { mutableStateOf(query) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collect {
                lastReported = it
                currentOnQueryChange(it)
            }
    }
    LaunchedEffect(query) {
        if (query != lastReported && query != state.text.toString()) {
            lastReported = query
            state.setTextAndPlaceCursorAtEnd(query)
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fieldModifier =
            Modifier
                .weight(1f)
                .focusRequester(fieldFocus)
                .onPreviewKeyEvent { event ->
                    val keyCode = event.nativeKeyEvent.keyCode
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        if (event.type == KeyEventType.KeyUp) typing = true
                        return@onPreviewKeyEvent true
                    }
                    val selection = state.selection
                    val leave =
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                            KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                            KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left.takeIf { selection.start == 0 }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right.takeIf { selection.end == state.text.length }
                            else -> null
                        } ?: return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(leave)
                    true
                }
        if (secure) {
            OutlinedSecureTextField(
                state = state,
                modifier = fieldModifier,
                placeholder = { Text(placeholder) },
                label =
                    if (label == null) {
                        null
                    } else {
                        { Text(label) }
                    },
                leadingIcon = { Icon(leadingIcon, contentDescription = null) },
                textObfuscationMode = if (passwordVisible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = imeAction,
                        showKeyboardOnFocus = typing,
                    ),
                onKeyboardAction = {
                    keyboard?.hide()
                    typing = false
                    onSearch()
                    // The keyboard had the D-pad until now; land in the page so the next press scrolls
                    // results (and, at the end of them, reaches the now-playing strip).
                    focusManager.moveFocus(FocusDirection.Down)
                },
            )
        } else {
            OutlinedTextField(
                state = state,
                modifier = fieldModifier,
                placeholder = { Text(placeholder) },
                label =
                    if (label == null) {
                        null
                    } else {
                        { Text(label) }
                    },
                leadingIcon = { Icon(leadingIcon, contentDescription = null) },
                lineLimits = TextFieldLineLimits.SingleLine,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, showKeyboardOnFocus = typing),
                onKeyboardAction = {
                    keyboard?.hide()
                    typing = false
                    onSearch()
                    // The keyboard had the D-pad until now; land in the page so the next press scrolls
                    // results (and, at the end of them, reaches the now-playing strip).
                    focusManager.moveFocus(FocusDirection.Down)
                },
            )
        }
        if (secure) {
            TvIconButton(
                icon = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = stringResource(if (passwordVisible) R.string.hide_password else R.string.show_password),
                onClick = { passwordVisible = !passwordVisible },
            )
        }
        if (onVoice != null) {
            TvIconButton(
                icon = Icons.Outlined.Mic,
                contentDescription = stringResource(R.string.voice_search_cd),
                onClick = onVoice,
            )
        }
    }
}
