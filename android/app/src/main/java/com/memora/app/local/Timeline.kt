package com.memora.app.local

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Which moment a timeline date describes. Only these two are known to Memora; it never invents an "event date". */
enum class DateKind(val label: String) {
    /** Written inside the photo by the camera. */
    CAPTURED("Captured"),
    /** When this phone stored the file. Says nothing about when the photo or document was made. */
    ADDED("Added to Memora"),
}

data class TimelineEntry(val file: LocalFile, val date: LocalDate, val kind: DateKind) {
    val isPhoto: Boolean get() = file.mime.startsWith("image/") || file.ext in DefaultExtractor.IMAGE_EXTS
}

enum class Granularity(val label: String) { DAY("Day"), MONTH("Month"), YEAR("Year") }

enum class KindFilter(val label: String) { ALL("All"), PHOTOS("Photos"), DOCUMENTS("Documents") }

data class TimelineGroup(val label: String, val entries: List<TimelineEntry>)

object Timeline {
    /**
     * A file's date is its capture date when the photo carries one; otherwise the day it was added, labelled as such.
     * The capture date is used exactly as stored (no zone conversion); the added instant is shown in the phone's own zone.
     */
    fun entry(file: LocalFile, capture: CaptureTime?, zone: ZoneId = ZoneId.systemDefault()): TimelineEntry =
        if (capture != null) TimelineEntry(file, capture.date, DateKind.CAPTURED)
        else TimelineEntry(file, Instant.ofEpochMilli(file.importedAt).atZone(zone).toLocalDate(), DateKind.ADDED)

    fun filter(entries: List<TimelineEntry>, kind: KindFilter, query: String): List<TimelineEntry> {
        val q = query.trim().lowercase()
        return entries.filter { e ->
            val kindOk = when (kind) {
                KindFilter.ALL -> true
                KindFilter.PHOTOS -> e.isPhoto
                KindFilter.DOCUMENTS -> !e.isPhoto
            }
            kindOk && (q.isEmpty() || e.file.name.lowercase().contains(q) || e.file.ext == q)
        }
    }

    fun group(
        entries: List<TimelineEntry>, granularity: Granularity, newestFirst: Boolean = true, locale: Locale = Locale.getDefault(),
    ): List<TimelineGroup> {
        val sorted = if (newestFirst) entries.sortedWith(compareByDescending<TimelineEntry> { it.date }.thenByDescending { it.file.id })
        else entries.sortedWith(compareBy<TimelineEntry> { it.date }.thenBy { it.file.id })
        val fmt = when (granularity) {
            Granularity.DAY -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
            Granularity.MONTH -> DateTimeFormatter.ofPattern("LLLL yyyy", locale)
            Granularity.YEAR -> DateTimeFormatter.ofPattern("yyyy", locale)
        }
        val key: (LocalDate) -> LocalDate = when (granularity) {
            Granularity.DAY -> { d -> d }
            Granularity.MONTH -> { d -> d.withDayOfMonth(1) }
            Granularity.YEAR -> { d -> d.withDayOfYear(1) }
        }
        return sorted.groupBy { key(it.date) }.map { (d, items) -> TimelineGroup(d.format(fmt), items) }
    }
}
