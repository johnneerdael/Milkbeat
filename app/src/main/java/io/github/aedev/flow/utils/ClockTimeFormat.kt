package io.github.aedev.flow.utils

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [epochMs] as a wall-clock time of day ("14:05" or "2:05 PM"), following the device's 24-hour setting. */
fun formatClockTime(
    context: Context,
    epochMs: Long,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"
    return DateTimeFormatter
        .ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        .withZone(zone)
        .format(Instant.ofEpochMilli(epochMs))
}
