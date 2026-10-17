package io.github.aedev.flow.ui.tv.music

import androidx.annotation.StringRes
import io.github.aedev.flow.R

/** The bundled mood table names its groups and options in English; these map their ids to localized strings, and an id the app does not know yet keeps the table's label. */
@StringRes
internal fun moodGroupLabel(id: String): Int? =
    when (id) {
        "energy" -> R.string.tv_music_mood_group_energy
        "brightness" -> R.string.tv_music_mood_group_brightness
        "colour" -> R.string.tv_music_mood_group_colour
        "palette" -> R.string.tv_music_mood_group_palette
        "main_colour" -> R.string.tv_music_mood_group_main_colour
        "movement" -> R.string.tv_music_mood_group_movement
        "style" -> R.string.tv_music_mood_group_style
        "comfort" -> R.string.tv_music_mood_group_comfort
        else -> null
    }

@StringRes
internal fun moodOptionLabel(id: String): Int? =
    when (id) {
        "chill" -> R.string.tv_music_mood_option_chill
        "normal" -> R.string.tv_music_mood_option_normal
        "intense" -> R.string.tv_music_mood_option_intense
        "dark" -> R.string.tv_music_mood_option_dark
        "balanced" -> R.string.tv_music_mood_option_balanced
        "bright" -> R.string.tv_music_mood_option_bright
        "monochrome" -> R.string.tv_music_mood_option_monochrome
        "pastel" -> R.string.tv_music_mood_option_pastel
        "vivid" -> R.string.tv_music_mood_option_vivid
        "warm" -> R.string.tv_music_mood_option_warm
        "cool" -> R.string.tv_music_mood_option_cool
        "red" -> R.string.tv_music_mood_option_red
        "orange" -> R.string.tv_music_mood_option_orange
        "yellow" -> R.string.tv_music_mood_option_yellow
        "green" -> R.string.tv_music_mood_option_green
        "cyan" -> R.string.tv_music_mood_option_cyan
        "blue" -> R.string.tv_music_mood_option_blue
        "purple" -> R.string.tv_music_mood_option_purple
        "pink" -> R.string.tv_music_mood_option_pink
        "main_red" -> R.string.tv_music_mood_option_main_red
        "main_orange" -> R.string.tv_music_mood_option_main_orange
        "main_yellow" -> R.string.tv_music_mood_option_main_yellow
        "main_green" -> R.string.tv_music_mood_option_main_green
        "main_cyan" -> R.string.tv_music_mood_option_main_cyan
        "main_blue" -> R.string.tv_music_mood_option_main_blue
        "main_purple" -> R.string.tv_music_mood_option_main_purple
        "main_pink" -> R.string.tv_music_mood_option_main_pink
        "still" -> R.string.tv_music_mood_option_still
        "drifting" -> R.string.tv_music_mood_option_drifting
        "fast" -> R.string.tv_music_mood_option_fast
        "kaleidoscope" -> R.string.tv_music_mood_option_kaleidoscope
        "tunnel" -> R.string.tv_music_mood_option_tunnel
        "liquid" -> R.string.tv_music_mood_option_liquid
        "geometric" -> R.string.tv_music_mood_option_geometric
        "particles" -> R.string.tv_music_mood_option_particles
        "fractal" -> R.string.tv_music_mood_option_fractal
        "no_strobe" -> R.string.tv_music_mood_option_no_strobe
        else -> null
    }
