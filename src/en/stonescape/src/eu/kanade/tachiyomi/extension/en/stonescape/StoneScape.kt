package eu.kanade.tachiyomi.extension.en.stonescape

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class StoneScape :
    HttpSource(),
    ConfigurableSource {

    override val name = "StoneScape"
    override val baseUrl = "https://stonescape.xyz"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "$baseUrl/api"
    private val json: Json by injectLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // Popular
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("sort", "views")
            .addQueryParameter("contentType", "manhwa")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<SeriesResponse>(json)
        val mangas = result.data.map { it.toSManga(baseUrl) }
        val hasNextPage = (result.pagination?.current ?: 1) < (result.pagination?.total ?: 1)
        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("sort", "latest")
            .addQueryParameter("contentType", "manhwa")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is ContentTypeFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("contentType", filter.toUriPart())
                    }
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("status", filter.toUriPart())
                    }
                }
                is GenreFilter -> {
                    val genres = filter.state
                        .filter { it.state }
                        .joinToString(",") { it.slug }

                    if (genres.isNotEmpty()) {
                        url.addQueryParameter("genres", genres)
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Details
    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")
        return GET("$apiUrl/series/by-slug/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val series = response.parseAs<SeriesDto>(json)
        return series.toSManga(baseUrl).apply {
            description = series.description
            status = when (series.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            author = series.author
            artist = series.artist
            genre = series.genres?.joinToString(", ") { it.replaceFirstChar { char -> char.uppercase() } }
            initialized = true
        }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    // Chapters
    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")
        // Bypasses the ID requirement, querying directly via slug
        return GET("$apiUrl/series/by-slug/$slug/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val result = response.parseAs<ChapterListResponse>(json)
        val seriesSlug = response.request.url.pathSegments.let { it[it.size - 2] }
        return result.chapters.map { it.toSChapter(seriesSlug) }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url.substringBefore("#")}"

    // Pages
    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfter("#")
        return GET("$apiUrl/chapters/$chapterId/pages", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val result = response.parseAs<ChapterDetailsDto>(json)
        return result.allPages.mapIndexed { index, page ->
            Page(index, "", baseUrl + page.url)
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    // Filters
    override fun getFilterList() = getFilters()

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {}
}
