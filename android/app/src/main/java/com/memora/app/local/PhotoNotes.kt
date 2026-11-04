package com.memora.app.local

/**
 * What the user wrote about a photo. Kept apart from everything the phone or laptop derived, so re-indexing or a
 * new download can never overwrite it.
 */
data class PhotoUserFields(
    val caption: String = "",
    val notes: String = "",
    val tags: List<String> = emptyList(),
    val eventTitle: String = "",
    val eventDate: String = "",
    val locationLabel: String = "",
    val favorite: Boolean = false,
) {
    val isBlank get() = caption.isBlank() && notes.isBlank() && tags.isEmpty() && eventTitle.isBlank() && eventDate.isBlank() && locationLabel.isBlank()
}

/** Machine-made text. [ocrText] is what the phone read from the pixels; the laptop fields come from the laptop's vision model. */
data class PhotoDerived(
    val ocrText: String = "",
    val laptopCaption: String = "",
    val laptopSummary: String = "",
)

data class PhotoNote(val fileId: Long, val user: PhotoUserFields = PhotoUserFields(), val derived: PhotoDerived = PhotoDerived(), val updatedAt: Long = 0)

object PhotoText {
    private const val MAX_FIELD = 2000
    private const val MAX_TAGS = 20

    fun cleanTags(raw: List<String>): List<String> =
        raw.map { it.trim().trim('#').lowercase().take(40) }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS)

    fun parseTags(text: String): List<String> = cleanTags(text.split(',', ';', '\n'))

    fun clean(user: PhotoUserFields) = user.copy(
        caption = user.caption.trim().take(300), notes = user.notes.trim().take(MAX_FIELD), tags = cleanTags(user.tags),
        eventTitle = user.eventTitle.trim().take(200), eventDate = user.eventDate.trim().take(10),
        locationLabel = user.locationLabel.trim().take(200),
    )

    /** True for a real calendar date written as YYYY-MM-DD (or empty). */
    fun validDate(s: String): Boolean =
        s.isEmpty() || runCatching { java.time.LocalDate.parse(s) }.isSuccess

    /**
     * The text the offline index searches for a photo. The user's own words are labelled as theirs, the laptop's
     * description as the laptop's, and the OCR text is kept as read. Nothing here is invented.
     */
    fun compose(note: PhotoNote?, ocr: String?): String = buildString {
        val u = note?.user
        val d = note?.derived
        fun line(label: String, value: String?) { if (!value.isNullOrBlank()) append(label).append(": ").append(value.trim()).append("\n\n") }
        line("Caption (written by you)", u?.caption)
        line("Notes (written by you)", u?.notes)
        line("Tags (written by you)", u?.tags?.joinToString(", "))
        line("Event (written by you)", u?.eventTitle)
        line("Event date (written by you)", u?.eventDate)
        line("Place (written by you)", u?.locationLabel)
        line("Description (from the laptop's AI)", d?.laptopCaption)
        line("Summary (from the laptop's AI)", d?.laptopSummary?.takeIf { it != d.laptopCaption })
        val text = (ocr ?: d?.ocrText).orEmpty().trim()
        if (text.isNotEmpty()) append("Text in the photo: ").append(text)
    }.trim()
}
