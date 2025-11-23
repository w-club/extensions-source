package eu.kanade.tachiyomi.extension.zh.manhuarensuwasuwa

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit.MINUTES
import javax.crypto.Cipher
import kotlin.random.Random
import kotlin.random.nextUBytes

class Manhuaren : HttpSource(), ConfigurableSource {
    override val lang = "zh"
    override val supportsLatest = true
    override val name = "Êº´Áîª‰∫?
    override val baseUrl = "http://mangaapi.manhuaren.com"

    private val pageSize = 20
    private val baseHttpUrl = baseUrl.toHttpUrl()
    private val preferences: SharedPreferences = getPreferences()

    private val gsnSalt = "4e0a48e1c0b54041bce9c8f0e036124d"
    private val encodedPublicKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmFCg289dTws27v8GtqIffkP4zgFR+MYIuUIeVO5AGiBV0rfpRh5gg7i8RrT12E9j6XwKoe3xJz1khDnPc65P5f7CJcNJ9A8bj7Al5K4jYGxz+4Q+n0YzSllXPit/Vz/iW5jFdlP6CTIgUVwvIoGEL2sS4cqqqSpCDKHSeiXh9CtMsktc6YyrSN+8mQbBvoSSew18r/vC07iQiaYkClcs7jIPq9tuilL//2uR9kWn5jsp8zHKVjmXuLtHDhM9lObZGCVJwdlN2KDKTh276u/pzQ1s5u8z/ARtK26N8e5w8mNlGcHcHfwyhjfEQurvrnkqYH37+12U3jGk5YNHGyOPcwIDAQAB"
    private val imei: String by lazy { generateIMEI() }
    private val lastUsedTime: String by lazy { generateLastUsedTime() }

    companion object {
        const val USER_ID_PREF = "userId"
        const val TOKEN_PREF = "token"
    }

    override val client: OkHttpClient = network.cloudflareClient
        .newBuilder()
        .apply { interceptors().removeAll { it.javaClass.simpleName == "BrotliInterceptor" } }
        .addInterceptor(ErrorResponseInterceptor(baseUrl, preferences))
        .build()

    private fun randomString(length: Int, pool: String): String {
        return (1..length)
            .map { Random.nextInt(0, pool.length).let { pool[it] } }
            .joinToString("")
    }

    private fun randomNumber(length: Int): String {
        return randomString(length, "0123456789")
    }

    private fun addLuhnCheckDigit(str: String): String {
        var sum = 0
        str.toCharArray().forEachIndexed { i, it ->
            var v = Character.getNumericValue(it)
            sum += if (i % 2 == 0) {
                v
            } else {
                v *= 2
                if (v < 10) {
                    v
                } else {
                    v - 9
                }
            }
        }
        var checkDigit = sum % 10
        if (checkDigit != 0) {
            checkDigit = 10 - checkDigit
        }

        return "$str$checkDigit"
    }

    private fun generateIMEI(): String {
        return addLuhnCheckDigit(randomNumber(14))
    }

    @Serializable
    data class TokenResult(
        val parameter: String,
        val scheme: String,
    )

    @Serializable
    data class TokenResponse(
        val initDeviceKey: String,
        val nickName: String,
        val tokenResult: TokenResult,
        val userId: Long,
        val userName: String,
    )

    @Serializable
    data class GetAnonyUserBody(
        val response: TokenResponse,
    )

    private fun fetchToken(): String {
        var token = preferences.getString(TOKEN_PREF, "")!!
        var userId = preferences.getString(USER_ID_PREF, "")!!
        if (token.isEmpty() || userId.isEmpty()) {
            val res = client.newCall(getAnonyUser()).execute()
            val tokenResponse = Json.decodeFromString<GetAnonyUserBody>(res.body.string()).response
            val tokenResult = tokenResponse.tokenResult

            token = "${tokenResult.scheme} ${tokenResult.parameter}"
            userId = tokenResponse.userId.toString()

            preferences.edit().apply {
                putString(TOKEN_PREF, token)
                putString(USER_ID_PREF, userId)
            }.apply()
        }

        return token
    }

    private fun generateLastUsedTime(): String {
        return ((Date().time / 1000) * 1000).toString()
    }

    private fun encrypt(message: String): String {
        val decodedKey = encodedPublicKey.decodeBase64()?.toByteArray() ?: throw Exception("Invalid Key")
        val x509EncodedKeySpec = X509EncodedKeySpec(decodedKey)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(x509EncodedKeySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)

        return cipher.doFinal(message.toByteArray()).toByteString().base64()
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun getAnonyUser(): Request {
        val url = baseHttpUrl.newBuilder()
            .addPathSegments("v1/user/createAnonyUser2")
            .build()

        val androidId = Random.nextUBytes(8)
            .joinToString("") { it.toString(16).padStart(2, '0') }
            .replaceFirst("^0+".toRegex(), "")
            .uppercase()

        val keysMap = ArrayList<HashMap<String, Any?>>().apply {
            add(
                HashMap<String, Any?>().apply {
                    put("key", encrypt(imei))
                    put("keyType", "0")
                },
            )
            add(
                HashMap<String, Any?>().apply {
                    put("key", encrypt(androidId))
                    put("keyType", "2")
                },
            )
            add(
                HashMap<String, Any?>().apply {
                    put("key", encrypt(UUID.randomUUID().toString()))
                    put("keyType", "-1")
                },
            )
        }
        val bodyMap = HashMap<String, Any?>().apply {
            put("keys", keysMap)
        }

        return myPost(
            url,
            JSONObject(bodyMap).toString()
                .replaceFirst("^/+".toRegex(), "")
                .toRequestBody("application/json".toMediaTypeOrNull()),
        )
    }

    private fun addGsnHash(request: Request): Request {
        val isPost = request.method == "POST"

        val params = request.url.queryParameterNames.toMutableSet()
        val bodyBuffer = Buffer()
        if (isPost) {
            params.add("body")
            request.body?.writeTo(bodyBuffer)
        }

        var str = gsnSalt + request.method
        params.toSortedSet().forEach {
            if (it != "gsn") {
                val value = if (isPost && it == "body") bodyBuffer.readUtf8() else request.url.queryParameter(it)
                str += "$it${urlEncode(value)}"
            }
        }
        str += gsnSalt

        val gsn = hashString("MD5", str)
        val newUrl = request.url.newBuilder()
            .addQueryParameter("gsn", gsn)
            .build()

        return request.newBuilder()
            .url(newUrl)
            .build()
    }

    private fun myRequest(url: HttpUrl, method: String, body: RequestBody?): Request {
        val now = SimpleDateFormat("yyyy-MM-dd+HH:mm:ss", Locale.US).format(Date())
        val userId = preferences.getString(USER_ID_PREF, "-1")!!
        val newUrl = url.newBuilder()
            .setQueryParameter("gsm", "md5")
            .setQueryParameter("gft", "json")
            .setQueryParameter("gak", "android_manhuaren2")
            .setQueryParameter("gat", "")
            .setQueryParameter("gui", userId)
            .setQueryParameter("gts", now)
            .setQueryParameter("gut", "0")
            .setQueryParameter("gem", "1")
            .setQueryParameter("gaui", userId)
            .setQueryParameter("gln", "")
            .setQueryParameter("gcy", "US")
            .setQueryParameter("gle", "zh")
            .setQueryParameter("gcl", "dm5")
            .setQueryParameter("gos", "1")
            .setQueryParameter("gov", "33_13")
            .setQueryParameter("gav", "7.0.1")
            .setQueryParameter("gdi", imei)
            .setQueryParameter("gfcl", "dm5")
            .setQueryParameter("gfut", lastUsedTime)
            .setQueryParameter("glut", lastUsedTime)
            .setQueryParameter("gpt", "com.mhr.mangamini")
            .setQueryParameter("gciso", "us")
            .setQueryParameter("glot", "")
            .setQueryParameter("glat", "")
            .setQueryParameter("gflot", "")
            .setQueryParameter("gflat", "")
            .setQueryParameter("glbsaut", "0")
            .setQueryParameter("gac", "")
            .setQueryParameter("gcut", "GMT+8")
            .setQueryParameter("gfcc", "")
            .setQueryParameter("gflg", "")
            .setQueryParameter("glcn", "")
            .setQueryParameter("glcc", "")
            .setQueryParameter("gflcc", "")
            .build()

        return addGsnHash(
            Request.Builder()
                .method(method, body)
                .url(newUrl)
                .headers(headers)
                .build(),
        )
    }

    private fun myPost(url: HttpUrl, body: RequestBody?): Request {
        return myRequest(url, "POST", body).newBuilder()
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .build()
    }

    private fun myGet(url: HttpUrl): Request {
        val authorization = fetchToken()
        return myRequest(url, "GET", null).newBuilder()
            .addHeader("Authorization", authorization)
            .cacheControl(CacheControl.Builder().maxAge(10, MINUTES).build())
            .build()
    }

    override fun headersBuilder(): Headers.Builder {
        val yqciMap = HashMap<String, Any?>().apply {
            put("at", -1)
            put("av", "7.0.1")
            put("ciso", "us")
            put("cl", "dm5")
            put("cy", "US")
            put("di", imei)
            put("dm", "Pixel 6")
            put("fcl", "dm5")
            put("ft", "mhr")
            put("fut", lastUsedTime)
            put("installation", "dm5")
            put("le", "zh")
            put("ln", "")
            put("lut", lastUsedTime)
            put("nt", 3)
            put("os", 1)
            put("ov", "33_13")
            put("pt", "com.mhr.mangamini")
            put("rn", "1080x1920")
            put("st", 0)
        }
        val yqppMap = HashMap<String, Any?>().apply {
            put("ciso", "us")
            put("laut", "0")
            put("lot", "")
            put("lat", "")
            put("cut", "GMT+8")
            put("fcc", "")
            put("flg", "")
            put("lcc", "")
            put("lcn", "")
            put("flcc", "")
            put("flot", "")
            put("flat", "")
            put("ac", "")
        }

        val userId = preferences.getString(USER_ID_PREF, "-1")!!
        return Headers.Builder().apply {
            add("X-Yq-Yqci", JSONObject(yqciMap).toString())
            add("X-Yq-Key", userId)
            add("yq_is_anonymous", "1")
            add("x-request-id", UUID.randomUUID().toString())
            add("X-Yq-Yqpp", JSONObject(yqppMap).toString())
            add("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 13; Pixel 6 Build/TQ3A.230901.001)")
        }
    }

    private fun hashString(type: String, input: String): String {
        val hexChars = "0123456789abcdef"
        val bytes = MessageDigest
            .getInstance(type)
            .digest(input.toByteArray())
        val result = StringBuilder(bytes.size * 2)

        bytes.forEach {
            val i = it.toInt()
            result.append(hexChars[i shr 4 and 0x0f])
            result.append(hexChars[i and 0x0f])
        }

        return result.toString()
    }

    private fun urlEncode(str: String?): String {
        return URLEncoder.encode(str ?: "", "UTF-8")
            .replace("+", "%20")
            .replace("%7E", "~")
            .replace("*", "%2A")
    }

    private fun mangasFromJSONArray(arr: JSONArray): MangasPage {
        val ret = ArrayList<SManga>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.getInt("mangaId")
            ret.add(
                SManga.create().apply {
                    title = obj.getString("mangaName")
                    thumbnail_url = obj.getString("mangaCoverimageUrl")
                    author = obj.optString("mangaAuthor")
                    status = when (obj.getInt("mangaIsOver")) {
                        1 -> SManga.COMPLETED
                        0 -> SManga.ONGOING
                        else -> SManga.UNKNOWN
                    }
                    url = "/v1/manga/getDetail?mangaId=$id"
                },
            )
        }
        return MangasPage(ret, arr.length() != 0)
    }

    private fun mangasPageParse(response: Response): MangasPage {
        val res = response.body.string()
        val arr = JSONObject(res).getJSONObject("response").getJSONArray("mangas")
        return mangasFromJSONArray(arr)
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = baseHttpUrl.newBuilder()
            .addQueryParameter("subCategoryType", "0")
            .addQueryParameter("subCategoryId", "0")
            .addQueryParameter("start", (pageSize * (page - 1)).toString())
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("sort", "0")
            .addPathSegments("v2/manga/getCategoryMangas")
            .build()
        return myGet(url)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = baseHttpUrl.newBuilder()
            .addQueryParameter("subCategoryType", "0")
            .addQueryParameter("subCategoryId", "0")
            .addQueryParameter("start", (pageSize * (page - 1)).toString())
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("sort", "1")
            .addPathSegments("v2/manga/getCategoryMangas")
            .build()
        return myGet(url)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return mangasPageParse(response)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return mangasPageParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = baseHttpUrl.newBuilder()
            .addQueryParameter("start", (pageSize * (page - 1)).toString())
            .addQueryParameter("limit", pageSize.toString())
        if (query != "") {
            url = url.addQueryParameter("keywords", query)
                .addPathSegments("v1/search/getSearchManga")
        } else {
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        url = url.setQueryParameter("sort", filter.getId())
                    }
                    is CategoryFilter -> {
                        url = url.setQueryParameter("subCategoryId", filter.getId())
                            .setQueryParameter("subCategoryType", filter.getType())
                    }
                    else -> {}
                }
            }
            url = url.addPathSegments("v2/manga/getCategoryMangas")
        }
        return myGet(url.build())
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val res = response.body.string()
        val obj = JSONObject(res).getJSONObject("response")
        return mangasFromJSONArray(
            obj.getJSONArray(
                if (obj.has("result")) "result" else "mangas",
            ),
        )
    }

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val res = response.body.string()
        val obj = JSONObject(res).getJSONObject("response")
        title = obj.getString("mangaName")
        thumbnail_url = ""
        obj.optString("mangaCoverimageUrl").let {
            if (it != "") { thumbnail_url = it }
        }
        if (thumbnail_url == "" || thumbnail_url == "http://mhfm5.tel.cdndm5.com/tag/category/nopic.jpg") {
            obj.optString("mangaPicimageUrl").let {
                if (it != "") { thumbnail_url = it }
            }
        }
        if (thumbnail_url == "") {
            obj.optString("shareIcon").let {
                if (it != "") { thumbnail_url = it }
            }
        }

        val arr = obj.getJSONArray("mangaAuthors")
        val tmparr = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            tmparr.add(arr.getString(i))
        }
        author = tmparr.joinToString(", ")

        genre = obj.getString("mangaTheme").replace(" ", ", ")

        status = when (obj.getInt("mangaIsOver")) {
            1 -> SManga.COMPLETED
            0 -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        description = obj.getString("mangaIntro")
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return myGet((baseUrl + manga.url).toHttpUrl())
    }

    override fun chapterListRequest(manga: SManga) = mangaDetailsRequest(manga)

    private fun getChapterName(type: String, name: String, title: String): String {
        return (if (type == "mangaEpisode") "[?™Â?] " else "") + name + (if (title == "") "" else ": $title")
    }

    private fun chaptersFromJSONArray(type: String, arr: JSONArray): List<SChapter> {
        val ret = ArrayList<SChapter>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            ret.add(
                SChapter.create().apply {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    name = if (obj.getInt("isMustPay") == 1) { "(?? " } else { "" } + getChapterName(type, obj.getString("sectionName"), obj.getString("sectionTitle"))
                    date_upload = dateFormat.parse(obj.getString("releaseTime"))?.time ?: 0L
                    chapter_number = obj.getInt("sectionSort").toFloat()
                    url = "/v1/manga/getRead?mangaSectionId=${obj.getInt("sectionId")}"
                },
            )
        }
        return ret
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val res = response.body.string()
        val obj = JSONObject(res).getJSONObject("response")
        val ret = ArrayList<SChapter>()
        listOf("mangaEpisode", "mangaWords", "mangaRolls").forEach {
            if (obj.has(it)) {
                ret.addAll(chaptersFromJSONArray(it, obj.getJSONArray(it)))
            }
        }
        return ret
    }

    override fun pageListParse(response: Response): List<Page> {
        val res = response.body.string()
        val obj = JSONObject(res).getJSONObject("response")
        val ret = ArrayList<Page>()
        val host = obj.getJSONArray("hostList").getString(0)
        val arr = obj.getJSONArray("mangaSectionImages")
        val query = obj.getString("query")
        for (i in 0 until arr.length()) {
            ret.add(Page(i, "$host${arr.getString(i)}$query", "$host${arr.getString(i)}$query"))
        }
        return ret
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = (baseUrl + chapter.url).toHttpUrl().newBuilder()
            .addQueryParameter("netType", "4")
            .addQueryParameter("loadreal", "1")
            .addQueryParameter("imageQuality", "2")
            .build()
        return myGet(url)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override fun imageRequest(page: Page): Request {
        val newHeaders = headersBuilder()
            .set("Referer", "http://www.dm5.com/dm5api/")
            .build()

        return GET(page.imageUrl!!, newHeaders)
    }

    override fun getFilterList() = FilterList(
        SortFilter(
            "?∂ÊÄ?,
            arrayOf(
                Pair("?≠Èó®", "0"),
                Pair("?¥Êñ∞", "1"),
                Pair("?∞‰?", "2"),
                Pair("ÂÆåÁ?", "3"),
            ),
        ),
        CategoryFilter(
            "?ÜÁ±ª",
            arrayOf(
                Category("?®ÈÉ®", "0", "0"),
                Category("?≠Ë?", "0", "31"),
                Category("?ãÁà±", "0", "26"),
                Category("?°Âõ≠", "0", "1"),
                Category("?æÂ?", "0", "3"),
                Category("?ΩÁ?", "0", "27"),
                Category("‰º™Â?", "0", "5"),
                Category("?íÈô©", "0", "2"),
                Category("?åÂú∫", "0", "6"),
                Category("?éÂÆ´", "0", "8"),
                Category("Ê≤ªÊ?", "0", "9"),
                Category("ÁßëÂπª", "0", "25"),
                Category("?±Â?", "0", "10"),
                Category("?üÊ¥ª", "0", "11"),
                Category("?ò‰?", "0", "12"),
                Category("?¨Á?", "0", "17"),
                Category("?®Á?", "0", "33"),
                Category("?ûÁ?", "0", "37"),
                Category("Â•áÂπª", "0", "14"),
                Category("È≠îÊ?", "0", "15"),
                Category("?êÊÄ?, "0", "29"),
                Category("Á•ûÈ¨º", "0", "20"),
                Category("?åÁ≥ª", "0", "21"),
                Category("?ÜÂè≤", "0", "4"),
                Category("ÁæéÈ?", "0", "7"),
                Category("?å‰∫∫", "0", "30"),
                Category("ËøêÂä®", "0", "34"),
                Category("ÁªÖÂ£´", "0", "36"),
                Category("?∫Áî≤", "0", "40"),
                Category("?êÂà∂Á∫?, "0", "61"),
                Category("Â∞ëÂπ¥??, "1", "1"),
                Category("Â∞ëÂ•≥??, "1", "2"),
                Category("?íÂπ¥??, "1", "3"),
                Category("Ê∏ØÂè∞", "2", "35"),
                Category("?•Èü©", "2", "36"),
                Category("Â§ßÈ?", "2", "37"),
                Category("Ê¨ßÁ?", "2", "52"),
            ),
        ),
    )

    private data class Category(val name: String, val type: String, val id: String)

    private class SortFilter(
        name: String,
        val vals: Array<Pair<String, String>>,
        state: Int = 0,
    ) : Filter.Select<String>(
        name,
        vals.map { it.first }.toTypedArray(),
        state,
    ) {
        fun getId() = vals[state].second
    }

    private class CategoryFilter(
        name: String,
        val vals: Array<Category>,
        state: Int = 0,
    ) : Filter.Select<String>(
        name,
        vals.map { it.name }.toTypedArray(),
        state,
    ) {
        fun getId() = vals[state].id
        fun getType() = vals[state].type
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = USER_ID_PREF
            title = "?®Êà∑ID"
            val userId = preferences.getString(USER_ID_PREF, "")!!
            summary = userId.ifEmpty { "?†Áî®?∑IDÔºåÁÇπ?ªËÆæÁΩ? }
            setOnPreferenceChangeListener { _, newValue ->
                summary = (newValue as String).ifEmpty { "?†Áî®?∑IDÔºåÁÇπ?ªËÆæÁΩ? }
                true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = TOKEN_PREF
            title = "‰ª§Á?(Token)"
            val token = preferences.getString(TOKEN_PREF, "")!!
            summary = if (token.isEmpty()) "?†‰ª§?åÔ??πÂáªËÆæÁΩÆ" else "?πÂáª?•Á?"
            setOnPreferenceChangeListener { _, newValue ->
                summary = if ((newValue as String).isEmpty()) "?†‰ª§?åÔ??πÂáªËÆæÁΩÆ" else "?πÂáª?•Á?"
                true
            }
        }.let(screen::addPreference)
    }
}
