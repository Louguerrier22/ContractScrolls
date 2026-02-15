package me.l1cked.contractscrolls.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeUtil {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")

    fun format(epochMillis: Long, zoneId: ZoneId): String {
        return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))
    }

    fun formatRemainingMillis(millis: Long): String {
        if (millis <= 0L) {
            return "expired"
        }

        var remaining = millis / 1000L
        val days = remaining / 86_400L
        remaining %= 86_400L
        val hours = remaining / 3_600L
        remaining %= 3_600L
        val minutes = remaining / 60L

        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h ")
            append("${minutes}m")
        }.trim()
    }
}
