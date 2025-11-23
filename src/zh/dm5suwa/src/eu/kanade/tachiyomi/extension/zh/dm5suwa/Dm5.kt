package eu.kanade.tachiyomi.extension.zh.dm5suwa

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class Dm5 : ParsedHttpSource(), ConfigurableSource {
    override val lang = "zh"
    override val supportsLatest = true
    override val name = "动漫屋 (Suwa)"

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(CommentsInterceptor)
        .build()

    private val preferences: SharedPreferences = getPreferences()

    override val baseUrl = preferences.getString(MIRROR_PREF, MIRROR_ENTRIES[0])!!

    // 修正策略：
    // 1. 只保留最關鍵的 isAdult=1
    // 2. 使用 Windows Chrome 的 User-Agent，讓 Cloudflare 比較不會擋
    override fun headersBuilder() = super.headersBuilder()
        .set(
            "Accept-Language",
            "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        )
        .set(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36",
        )
        .add("Cookie", "isAdult=1")
        .add("Referer", baseUrl)

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/manhua-list-p$page/", headers)

    override fun popularMangaNextPageSelector(): String = "div.page-pagination a:contains(>)"

    override fun popularMangaSelector(): String = "ul.mh-list > li > div.mh-item"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst("h2.title > a")!!.text()
        thumbnail_url = element.selectFirst("p.mh-cover")!!
            .attr("style")
            .substringAfter("url(")
            .substringBefore(")")
        url = element.selectFirst("h2.title > a")!!.attr("href")
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/manhua-list-s2-p$page/", headers)

    override fun latestUpdatesNextPageSelector(): String = popularMangaNextPageSelector()

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/search?title=$query&language=1&page=$page", headers)
    }

    override fun searchMangaNextPageSelector(): String = popularMangaNextPageSelector()

    override fun searchMangaSelector(): String = "ul.mh-list > li, div.banner_detail_form"

    override fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = element.selectFirst(".title > a")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
            ?: element.selectFirst("p.mh-cover")!!
                .attr("style")
                .substringAfter("url(")
                .substringBefore(")")
        url = element.selectFirst(".title > a")!!.attr("href")
    }

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.selectFirst("div.banner_detail_form p.title")!!.ownText()
        thumbnail_url = document.selectFirst("div.banner_detail_form img")!!.attr("abs:src")
        author = document.selectFirst("div.banner_detail_form p.subtitle > a")!!.text()
        artist = author
        genre = document.select("div.banner_detail_form p.tip a")
            .eachText()
            .joinToString(", ")
        val el = document.selectFirst("div.banner_detail_form p.content")!!
        description = el.ownText() + el.selectFirst("span")?.ownText().orEmpty()
        status = when (document.selectFirst("div.banner_detail_form p.tip > span > span")!!.text()) {
            "连载中" -> SManga.ONGOING
            "已完结" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        // 章節列表容器
        val container = document.selectFirst("div#chapterlistload")
            ?: throw Exception("无法读取章节列表（可能是 Cloudflare 阻挡或 IP 被封锁）")

        val chapters = container.select("li > a").map { element ->
            SChapter.create().apply {
                url = element.attr("href")
                name = buildString {
                    if (element.selectFirst("span.detail-lock, span.view-lock") != null) {
                        append("\uD83D\uDD12")
                    }
                    append(element.selectFirst("p.title")?.text() ?: element.text())
                }

                val dateStr = element.selectFirst("p.tip")?.text()
                if (dateStr != null) {
                    date_upload = dateFormat.parse(dateStr)?.time ?: 0L
                }
            }
        }

        // 依照設定排序
        if (preferences.getBoolean(SORT_CHAPTER_PREF, false)) {
            return chapters.sortedByDescending {
                it.url.drop(2).dropLast(1).toInt()
            }
        }

        // 有些作品章節原本就是正序，要反轉
        val orderText = document.selectFirst("div.detail-list-title a.order")!!.text()
        return if (orderText == "正序") {
            chapters.reversed()
        } else {
            chapters
        }
    }

    override fun chapterListSelector(): String = throw UnsupportedOperationException()

    override fun chapterFromElement(element: Element): SChapter =
        throw UnsupportedOperationException()

    override fun pageListParse(document: Document): List<Page> {
        val images = document.select("div#barChapter > img.load-src")
        val result: ArrayList<Page>

        // 檢查是否有關鍵腳本（決定是否成功載入）
        val scriptElement = document.selectFirst("script:containsData(DM5_MID)")
            ?: throw Exception("无法解析图片页面（脚本缺失）")

        val script = scriptElement.data()
        if (!script.contains("DM5_VIEWSIGN_DT")) {
            val msg = document.selectFirst("div.view-pay-form p.subtitle")?.text()
                ?: "需要付费或额外验证"
            throw Exception(msg)
        }

        val cid = script.substringAfter("var DM5_CID=").substringBefore(";")

        if (images.isNotEmpty()) {
            result = images.mapIndexed { index, element ->
                Page(index, "", element.attr("data-src"))
            } as ArrayList<Page>
        } else {
            val mid = script.substringAfter("var DM5_MID=").substringBefore(";")
            val dt = script.substringAfter("var DM5_VIEWSIGN_DT=\"").substringBefore("\";")
            val sign = script.substringAfter("var DM5_VIEWSIGN=\"").substringBefore("\";")
            val requestUrl = document.location()
            val imageCount = script.substringAfter("var DM5_IMAGE_COUNT=").substringBefore(";").toInt()

            result = (1..imageCount).map { pageIndex ->
                val url = requestUrl.toHttpUrl().newBuilder()
                    .addPathSegment("chapterfun.ashx")
                    .addQueryParameter("cid", cid)
                    .addQueryParameter("page", pageIndex.toString())
                    .addQueryParameter("key", "")
                    .addQueryParameter("language", "1")
                    .addQueryParameter("gtk", "6")
                    .addQueryParameter("_cid", cid)
                    .addQueryParameter("_mid", mid)
                    .addQueryParameter("_dt", dt)
                    .addQueryParameter("_sign", sign)
                    .build()
                Page(pageIndex, url.toString())
            } as ArrayList<Page>
        }

        if (preferences.getBoolean(CHAPTER_COMMENTS_PREF, false)) {
            val pageSize = script.substringAfter("var DM5_PAGEPCOUNT = ").substringBefore(";").toInt()
            val tid = script.substringAfter("var DM5_TIEBATOPICID='").substringBefore("'")

            for (i in 1..pageSize) {
                result.add(
                    Page(
                        result.size,
                        "",
                        "$baseUrl/m$cid/pagerdata.ashx?pageindex=$i&pagesize=$pageSize&tid=$tid&cid=$cid&t=9",
                    ),
                )
            }
        }

        return result
    }

    override fun imageUrlRequest(page: Page): Request {
        val referer = page.url.substringBefore("chapterfun.ashx")
        val header = headers.newBuilder()
            .add("Referer", referer)
            .build()
        return GET(page.url, header)
    }

    override fun imageUrlParse(response: Response): String {
        val script = Unpacker.unpack(response.body.string())
        val pix = script.substringAfter("var pix=\"").substringBefore("\"")
        val pvalue = script.substringAfter("var pvalue=[\"").substringBefore("\"")
        val query = script.substringAfter("pix+pvalue[i]+\"").substringBefore("\"")
        return pix + pvalue + query
    }

    override fun imageUrlParse(document: Document): String =
        throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl!!.toHttpUrl()
        val cid = url.queryParameter("cid")!!
        val headers = headers.newBuilder()
            .add("Referer", "$baseUrl/m$cid")
            .build()
        return GET(url, headers)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mirrorPreference = ListPreference(screen.context).apply {
            key = MIRROR_PREF
            title = "使用镜像网址"
            entries = MIRROR_ENTRIES
            entryValues = MIRROR_ENTRIES
            setDefaultValue(MIRROR_ENTRIES[0])
        }

        val chapterCommentsPreference = SwitchPreferenceCompat(screen.context).apply {
            key = CHAPTER_COMMENTS_PREF
            title = "章末吐槽页"
            summary = "修改后，已加载的章节需要清除章节缓存才能生效。"
            setDefaultValue(false)
        }

        val sortChapterPreference = SwitchPreferenceCompat(screen.context).apply {
            key = SORT_CHAPTER_PREF
            title = "依照上傳時間排序章節"
            setDefaultValue(false)
        }

        screen.addPreference(mirrorPreference)
        screen.addPreference(chapterCommentsPreference)
        screen.addPreference(sortChapterPreference)
    }

    companion object {
        private val MIRROR_ENTRIES
            get() = arrayOf(
                "https://www.dm5.cn",
                "https://www.dm5.com",
            )

        private const val MIRROR_PREF = "mirror"
        private const val CHAPTER_COMMENTS_PREF = "chapterComments"
        private const val SORT_CHAPTER_PREF = "sortChapter"

        private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
}
