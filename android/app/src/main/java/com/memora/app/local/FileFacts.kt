package com.memora.app.local

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * When a photo was taken, exactly as the camera wrote it. EXIF stores a wall-clock time with no zone, so [date] and
 * [time] are never converted: converting them to the phone's zone could move the photo to another calendar day.
 * [offset] (like "+05:30") is shown only when the file itself records it.
 */
data class CaptureTime(val date: LocalDate, val time: String?, val offset: String?)

object ExifDates {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val OFFSET = Regex("^[+-](0\\d|1\\d|2[0-3]):[0-5]\\d$")

    /** Parses EXIF "yyyy:MM:dd HH:mm:ss". Returns null for blank, all-zero or impossible values; never guesses. */
    fun parse(dateTime: String?, offset: String? = null): CaptureTime? {
        val raw = dateTime?.trim().orEmpty()
        if (raw.isEmpty() || raw.startsWith("0000")) return null
        val parsed = try { LocalDateTime.parse(raw, FORMAT) } catch (_: Exception) { return null }
        val tz = offset?.trim()?.takeIf { OFFSET.matches(it) }
        return CaptureTime(parsed.toLocalDate(), parsed.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)), tz)
    }

    /** Locale-aware long date for a calendar date, with no time-zone conversion. */
    fun formatDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

    fun formatInstant(millis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        java.time.Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))

    fun formatCoordinates(lat: Double, lon: Double): String? {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null // "null island": a camera that had no fix, not a place
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "W"
        return String.format(Locale.US, "%.4f° %s, %.4f° %s", Math.abs(lat), ns, Math.abs(lon), ew)
    }
}

/** What a photo's own metadata says. Every field is null when the file doesn't record it. */
data class PhotoMetadata(
    val capture: CaptureTime? = null,
    val width: Int? = null,
    val height: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val coordinates: String? get() = if (latitude != null && longitude != null) ExifDates.formatCoordinates(latitude, longitude) else null
}

/**
 * Facts read literally from text Memora extracted (OCR or document text). Nothing is inferred: each result is text that
 * appears in the source, so it can be shown next to the passage it came from.
 */
object TextFacts {
    data class Pair(val label: String, val value: String)

    private val KEY_VALUE = Regex("^\\s*([\\p{L}][\\p{L}\\p{N} /#.()'-]{1,31}?)\\s*[:：]\\s*(\\S.{0,119})$")
    private val DATE = Regex(
        "\\b(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}|" +
            "\\d{1,2}(?:st|nd|rd|th)?\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?,?\\s+\\d{4}|" +
            "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4})\\b",
        RegexOption.IGNORE_CASE,
    )
    private val AMOUNT = Regex("(?:₹|\\$|€|£|Rs\\.?|INR|USD|EUR|GBP)\\s?\\d[\\d,]*(?:\\.\\d{1,2})?", RegexOption.IGNORE_CASE)

    /** "Label: value" lines, such as "Warranty period: 2 years". Returns at most [limit], in order of appearance. */
    fun keyValues(text: String, limit: Int = 8): List<Pair> {
        val seen = HashSet<String>()
        return text.lineSequence().mapNotNull { line ->
            KEY_VALUE.matchEntire(line)?.let { m -> Pair(m.groupValues[1].trim(), m.groupValues[2].trim()) }
        }.filter { seen.add(it.label.lowercase()) }.take(limit).toList()
    }

    /** Dates exactly as written in the text. The format is not interpreted, because 03/04/2024 means different days in different countries. */
    fun datesMentioned(text: String, limit: Int = 5): List<String> =
        DATE.findAll(text).map { it.value.trim() }.distinct().take(limit).toList()

    fun amountsMentioned(text: String, limit: Int = 5): List<String> =
        AMOUNT.findAll(text).map { it.value.trim() }.distinct().take(limit).toList()
}
