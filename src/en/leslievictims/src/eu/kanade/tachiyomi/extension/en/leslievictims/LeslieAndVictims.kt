package eu.kanade.tachiyomi.extension.en.leslievictims

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class LeslieAndVictims : HttpSource() {

    override val name = "Leslie&Victims"

    override val baseUrl = "https://leslie-victims.pages.dev"

    override val lang = "en"

    override val supportsLatest = false

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/library", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val entries = response.parseAs<List<LibraryEntry>>()
        val mangas = entries.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Appending query as fragment to avoid intercepting and mutating the standard Request logic
        return GET("$baseUrl/api/library#$query", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.fragment ?: ""
        val entries = response.parseAs<List<LibraryEntry>>()

        val filtered = entries.filter { it.title.contains(query, ignoreCase = true) }
        val mangas = filtered.map { it.toSManga(baseUrl) }

        return MangasPage(mangas, false)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val seriesId = (baseUrl + manga.url).toHttpUrl().queryParameter("series")
            ?: throw Exception("Invalid manga URL")
        return GET("$baseUrl/api/library#$seriesId", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val seriesId = response.request.url.fragment!!
        val entries = response.parseAs<List<LibraryEntry>>()

        val entry = entries.find { it.getId() == seriesId }
            ?: throw Exception("Series not found")

        return entry.toSManga(baseUrl)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val seriesId = (baseUrl + manga.url).toHttpUrl().queryParameter("series")
            ?: throw Exception("Invalid manga URL")
        return GET("$baseUrl/api/library#$seriesId", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val seriesId = response.request.url.fragment!!
        val entries = response.parseAs<List<LibraryEntry>>()

        val entry = entries.find { it.getId() == seriesId }
            ?: throw Exception("Series not found")

        return entry.getChapters(baseUrl)
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = (baseUrl + chapter.url).toHttpUrl()
        val seriesId = url.queryParameter("series")!!
        val chId = url.queryParameter("ch")!!
        return GET("$baseUrl/api/library#$seriesId|$chId", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val fragment = response.request.url.fragment!!
        val (seriesId, chId) = fragment.split("|")

        val entries = response.parseAs<List<LibraryEntry>>()
        val entry = entries.find { it.getId() == seriesId }
            ?: throw Exception("Series not found")

        val chapterRoot = entry.getChapterRoot(chId)
        val pages = mutableListOf<Page>()

        if (chapterRoot != null) {
            val rootUrl = chapterRoot.url

            when (chapterRoot.mode) {
                "list" -> {
                    val list = chapterRoot.data.jsonArray.map { it.jsonPrimitive.content }
                    list.forEachIndexed { i, file ->
                        pages.add(Page(i, imageUrl = "$rootUrl/$file"))
                    }
                }
                "count" -> {
                    val count = chapterRoot.data.jsonPrimitive.content.toInt()
                    for (i in 1..count) {
                        val paddedNum = i.toString().padStart(2, '0')
                        pages.add(Page(i - 1, imageUrl = "$rootUrl/$paddedNum.webp"))
                    }
                }
            }
        } else {
            // Brute force logic: probe sequentially until receiving an HTML fallback instead of an image
            val baseImgUrl = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("content")
                .addPathSegment(seriesId)
                .addPathSegment(chId)
                .build()

            var pageNum = 1
            while (pageNum <= 150) { // Safety limit against infinite loops
                val paddedNum = pageNum.toString().padStart(2, '0')
                val imgUrl = baseImgUrl.newBuilder()
                    .addPathSegment("$paddedNum.webp")
                    .build()
                    .toString()

                val checkReq = Request.Builder()
                    .url(imgUrl)
                    .head()
                    .headers(headers)
                    .build()

                val checkRes = client.newCall(checkReq).execute()
                val contentType = checkRes.header("Content-Type") ?: ""
                checkRes.close()

                if (checkRes.isSuccessful && contentType.startsWith("image")) {
                    pages.add(Page(pageNum - 1, imageUrl = imgUrl))
                    pageNum++
                } else {
                    // Reached the SPA text/html fallback page (or a legitimate 404 block)
                    break
                }
            }
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url
}
