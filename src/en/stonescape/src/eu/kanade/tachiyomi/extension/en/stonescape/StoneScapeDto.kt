package eu.kanade.tachiyomi.extension.en.stonescape

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
data class SeriesResponse(
    val data: List<SeriesDto>,
    val pagination: PaginationDto? = null,
)

@Serializable
data class PaginationDto(
    val page: Int? = null,
    val totalPages: Int? = null,
) {
    val current: Int get() = page ?: 1
    val total: Int get() = totalPages ?: 1
}

@Serializable
data class SeriesDto(
    val seriesId: String,
    val title: String,
    val slug: String,
    val coverUrl: String? = null,
    val description: String? = null,
    @SerialName("publicationStatus") val status: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<String>? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = "/series/$slug"
        title = this@SeriesDto.title
        thumbnail_url = this@SeriesDto.coverUrl?.let { baseUrl + it }
    }
}

@Serializable
data class ChapterListResponse(
    val chapters: List<ChapterDto>,
)

@Serializable
data class ChapterDto(
    val chapterId: String,
    val chapterNumber: String,
    val title: String? = null,
    val createdAt: String? = null,
) {
    fun toSChapter(seriesSlug: String) = SChapter.create().apply {
        val formattedNumber = chapterNumber.removeSuffix(".00")
        url = "/series/$seriesSlug/ch-$formattedNumber#$chapterId"
        name = "Chapter $formattedNumber" + (if (!title.isNullOrBlank()) " - $title" else "")
        date_upload = parseDate(createdAt)
        chapter_number = formattedNumber.toFloatOrNull() ?: -1f
    }
}

@Serializable
data class ChapterDetailsDto(
    val pages: List<PageDto> = emptyList(),
    val images: List<PageDto> = emptyList(),
) {
    val allPages: List<PageDto> get() = pages.ifEmpty { images }
}

@Serializable
data class PageDto(
    val url: String,
)

private fun parseDate(dateStr: String?): Long {
    if (dateStr == null) return 0L
    return try {
        // Strip fractional seconds to prevent precision mismatch crashing the SimpleDateFormat
        val cleanDate = dateStr.replace(" ", "T").substringBefore(".").substringBefore("+")
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        dateFormat.parse(cleanDate)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}
