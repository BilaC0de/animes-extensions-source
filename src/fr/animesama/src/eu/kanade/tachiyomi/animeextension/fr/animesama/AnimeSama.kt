package eu.kanade.tachiyomi.animeextension.fr.animesama

import android.content.SharedPreferences
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.sendvidextractor.SendvidExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.uqloadextractor.UqloadExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.vkextractor.VkExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class AnimeSama :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Anime-Sama"

    override val lang = "fr"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private var SharedPreferences.customDomain by preferences.delegate(PREF_URL_KEY, PREF_URL_DEFAULT)

    override var baseUrl by LazyMutable { preferences.customDomain.ifBlank { PREF_URL_DEFAULT }.sanitizeDomain() }

    // Caps simultaneous requests to the source's own site, so a listing never bursts dozens of pages.
    private val siteRequestLimiter = Semaphore(MAX_CONCURRENT_SITE_REQUESTS)

    override val client: OkHttpClient = super.client.newBuilder()
        .followRedirects(false)
        .addInterceptor { chain ->
            if (chain.request().url.host != baseUrl.toHttpUrlOrNull()?.host) {
                chain.proceed(chain.request())
            } else {
                siteRequestLimiter.acquire()
                try {
                    chain.proceed(chain.request())
                } finally {
                    siteRequestLimiter.release()
                }
            }
        }
        .addInterceptor { chain ->
            var request = chain.request()
            var response = chain.proceed(request)
            var redirectCount = 0

            while (response.isRedirect && redirectCount < MAX_REDIRECTS) {
                val newUrl = response.header("Location") ?: break
                val newUrlHttp = request.url.resolve(newUrl) ?: break
                val redirectedDomain = newUrlHttp.run { "$scheme://$host" }
                // This client is shared with the video extractors, and hosts like uqload.is
                // permanently redirect to another domain. Only a redirect on the source's own
                // domain is a domain migration; anything else must not touch baseUrl/headers.
                val isSourceDomain = request.url.host == baseUrl.toHttpUrlOrNull()?.host
                if (isSourceDomain && redirectedDomain != baseUrl) {
                    updateDomain(redirectedDomain)
                }
                response.close()
                request = request.newBuilder()
                    .url(newUrlHttp)
                    .apply {
                        if (isSourceDomain) {
                            header("Origin", redirectedDomain)
                            header("Referer", "$redirectedDomain/")
                        }
                    }
                    .build()
                response = chain.proceed(request)
                redirectCount++
            }
            if (redirectCount >= MAX_REDIRECTS) {
                response.close()
                throw java.io.IOException("Too many redirects: $MAX_REDIRECTS")
            }
            response
        }.build()

    // ============================== Popular ===============================
    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val page = response.request.url.fragment?.toIntOrNull() ?: 0
        val chunks = doc.select("#containerPepites > div.as-carte:has(.as-badge-anime) a.as-carte-lien").chunked(5)
        val seasons = chunks.getOrNull(page - 1)?.parallelCatchingFlatMapBlocking {
            fetchAnimeSeasons("$baseUrl${it.attr("href")}", "")
        }?.distinctBy { it.url }.orEmpty()
        return AnimesPage(seasons, page < chunks.size)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/#$page", headers)

    // =============================== Latest ===============================
    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = response.useAsJsoup()
        val seasons = animes.select("#containerAjoutsAnimes > div.as-carte a.as-carte-lien")
            .mapNotNull { it.absUrl("href").toHttpUrlOrNull()?.pathSegments?.getOrNull(1) }
            .distinct()
            .parallelCatchingFlatMapBlocking { slug -> fetchAnimeSeasons("$baseUrl/catalogue/$slug/", "") }
            .distinctBy { it.url }
        return AnimesPage(seasons, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    // =============================== Search ===============================
    override fun getFilterList() = Filters.FILTER_LIST

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val queryUrl = query.toHttpUrlOrNull()
        if (queryUrl != null) {
            val host = queryUrl.host
            if (host != baseUrl.toHttpUrl().host && host !in DEEPLINK_HOSTS) {
                throw Exception("Unsupported url")
            }
            val id = queryUrl.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() }
                ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "$PREFIX_SEARCH$id", filters)
        } else if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            val animeUrl = if (id.startsWith("/")) "$baseUrl$id" else "$baseUrl/$id"
            return AnimesPage(fetchAnimeSeasons(animeUrl, ""), false)
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // The catalogue is populated asynchronously by catalogue.js. Request its JSON fragment
        // directly instead of parsing the initial /catalogue/ HTML page.
        val url = "$baseUrl/catalogue/fragment.php".toHttpUrl().newBuilder()
        val params = Filters.getSearchFilters(filters)

        params.types.forEach { url.addQueryParameter("type[]", it) }
        params.language.forEach { url.addQueryParameter("langue[]", it) }
        params.genres.forEach { url.addQueryParameter("genre[]", it) }

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }
        url.addQueryParameter("tri", "titre")
        url.addQueryParameter("page", page.toString())

        val requestHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .build()

        return GET(url.build(), requestHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val fragment = response.parseAs<CatalogueFragment>()
        val document = Jsoup.parseBodyFragment(fragment.liste, baseUrl)

        val anime = document
            .select("div.as-carte a.as-carte-lien")
            .mapNotNull { it.absUrl("href").ifEmpty { null } }
            .distinct()
            .parallelCatchingFlatMapBlocking { fetchAnimeSeasons(it, "") }
            .distinctBy { it.url }

        val hasNextPage = Jsoup.parseBodyFragment(fragment.pagination, baseUrl)
            .selectFirst("a[rel=next]") != null

        return AnimesPage(anime, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val segments = anime.url.trim('/').split("/")
        val animeUrl = "$baseUrl/${segments.take(2).joinToString("/")}/"
        val season = segments.getOrNull(2) ?: ""

        // Listing results remain uninitialized so the app fetches current details
        // when the anime page is opened. Only the details flow marks it initialized.
        val seasons = fetchAnimeSeasons(animeUrl, season, initialized = true)
        return seasons.firstOrNull { it.url == anime.url } ?: seasons.firstOrNull() ?: anime
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = anime.url.removeSuffix("/")
        val movie = url.substringAfter("#", "").toIntOrNull()
        val cleanUrl = url.substringBefore("#")
        val currentFolder = cleanUrl.substringAfterLast("/")
        val isVoiceFolder = VOICES_VALUES.contains(currentFolder)
        val parentUrl = if (isVoiceFolder) {
            "$baseUrl${cleanUrl.substringBeforeLast("/")}"
        } else {
            "$baseUrl$cleanUrl"
        }

        val paths = (listOf(currentFolder) + VOICES_VALUES).distinct()
        val results = paths.parallelMapBlocking { runCatching { fetchPlayers("$parentUrl/$it") } }
        val players = results.map { it.getOrDefault(emptyList()) }
        if (players.all { it.isEmpty() }) {
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
        val episodes = playersToEpisodes(players, paths)
        if (movie == null) return episodes.reversed()

        // Older cached film entries can contain an index that was created from
        // the number of players rather than the number of actual film entries.
        return episodes.getOrNull(movie)?.let(::listOf)
            ?: episodes.singleOrNull()?.let(::listOf)
            ?: emptyList()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    // A blocked or dead host must fail fast instead of keeping its hoster loading.
    private val extractorClient by lazy {
        client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).build()
    }

    private val vkExtractor by lazy { VkExtractor(extractorClient, headers) }
    private val voeExtractor by lazy { VoeExtractor(extractorClient, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(extractorClient, headers) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(extractorClient, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(extractorClient, headers) }
    private val uqloadExtractor by lazy { UqloadExtractor(extractorClient) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(extractorClient) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // UniversalExtractor drives a WebView on the main looper, so its calls must not overlap.
    private val universalMutex = Mutex()

    private enum class PlayerHost(val label: String) {
        VK("VK"),
        SENDVID("Sendvid"),
        VIDMOLY("VidMoly"),
        VOE("VOE"),
        VIDHIDE("VidHide"),
        UQLOAD("Uqload"),
        YOURUPLOAD("YourUpload"),
        DIRECT("Direct"),
    }

    private fun String.playerHost(): PlayerHost? = when {
        contains("vk.", true) || contains("vkvideo", true) -> PlayerHost.VK
        contains("sendvid.com", true) -> PlayerHost.SENDVID
        contains("vidmoly", true) || contains("ansembed", true) -> PlayerHost.VIDMOLY
        VOE_DOMAINS.any { contains(it, true) } -> PlayerHost.VOE
        VIDHIDE_DOMAINS.any { contains(it, true) } -> PlayerHost.VIDHIDE
        contains("uqload", true) -> PlayerHost.UQLOAD
        contains("yourupload", true) -> PlayerHost.YOURUPLOAD
        contains(".mp4", true) -> PlayerHost.DIRECT
        else -> null
    }

    private suspend fun extractVideos(host: PlayerHost, playerUrl: String, prefix: String): List<Video> = when (host) {
        PlayerHost.VK -> vkExtractor.videosFromUrl(playerUrl, prefix)
        PlayerHost.SENDVID -> sendvidExtractor.videosFromUrl(playerUrl, prefix)
        PlayerHost.VIDMOLY -> vidmolyExtractor.videosFromUrl(playerUrl, prefix.trim())
        PlayerHost.VOE -> voeExtractor.videosFromUrl(playerUrl, prefix)
        // VidHide pages ship the same stream twice (cdn master.m3u8 and a proxied /stream/ one),
        // which shows up as duplicated entries with an identical label. Restricted to this host:
        // other players can return distinct files under one label.
        PlayerHost.VIDHIDE ->
            vidHideExtractor
                .videosFromUrl(playerUrl, videoNameGen = { quality -> "${prefix.trim()} VidHide - $quality" })
                .distinctBy { it.videoTitle }
        PlayerHost.UQLOAD -> uqloadExtractor.videosFromUrl(playerUrl, prefix)
        PlayerHost.YOURUPLOAD -> yourUploadExtractor.videoFromUrl(playerUrl, headers, prefix = prefix)
        PlayerHost.DIRECT -> listOf(Video(videoUrl = playerUrl, videoTitle = "$prefix Direct", headers = headers))
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // One hoster per player link: a slow or blocked host only affects its own hoster.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val playerUrls = episode.url.parseAs<List<List<String>>>()
        val voiceNames = episode.scanlator?.split(", ").orEmpty()
        val seenUrls = mutableSetOf<String>()
        val nameCounts = mutableMapOf<String, Int>()

        // Filter empty voice groups first to keep voice index aligned with scanlator
        // (playersToEpisodes stores outer=voice, scanlator skips empty voices)
        return playerUrls
            .filter { it.isNotEmpty() }
            .flatMapIndexed { i, urls ->
                val prefix = "(${voiceNames.getOrElse(i) { "" }}) ".takeUnless { it.trim() == "()" }.orEmpty()
                urls.filter { it.isNotEmpty() }.map { it to prefix }
            }
            .filter { (url, _) -> DEAD_HOSTS.none { url.contains(it, ignoreCase = true) } && seenUrls.add(url) }
            .map { (url, prefix) ->
                val label = url.playerHost()?.label ?: url.toHttpUrlOrNull()?.host ?: "Player"
                val baseName = "$prefix$label"
                val count = (nameCounts[baseName] ?: 0) + 1
                nameCounts[baseName] = count
                Hoster(
                    hosterUrl = url,
                    hosterName = if (count > 1) "$baseName $count" else baseName,
                    internalData = prefix,
                )
            }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val playerUrl = hoster.hosterUrl
        val prefix = hoster.internalData

        val host = playerUrl.playerHost()
        if (host != null) {
            val videos = try {
                extractVideos(host, playerUrl, prefix)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return emptyList()
            }
            if (videos.isNotEmpty()) return videos.distinctVideos()
        }

        // Unknown host, or a known one that returned nothing: WebView fallback
        return universalMutex.withLock {
            try {
                universalExtractor.videosFromUrl(playerUrl, headers, prefix = prefix.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }.distinctVideos()
    }

    private fun List<Video>.distinctVideos() = distinctBy { it.videoTitle to it.videoUrl.substringBefore("?") }

    // ============================ Utils =============================
    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        return sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(voices, true) }
                .thenByDescending { it.hosterName.contains(player, true) },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val voices = preferences.getString(PREF_VOICES_KEY, PREF_VOICES_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val player = preferences.getString(PREF_PLAYER_KEY, PREF_PLAYER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.videoTitle.contains(voices, true) },
                { it.videoTitle.contains(player, true) },
                { it.videoTitle.contains(quality) },
            ),
        ).reversed()
    }

    private suspend fun fetchAnimeSeasons(
        animeUrl: String,
        season: String,
        initialized: Boolean = false,
    ): List<SAnime> {
        val response = client.newCall(GET(animeUrl, headers)).awaitSuccess()
        return fetchAnimeSeasons(response, season, initialized)
    }

    private suspend fun fetchAnimeSeasons(
        response: Response,
        season: String,
        initialized: Boolean,
    ): List<SAnime> {
        val animeDoc = response.useAsJsoup()
        val animeUrl = response.request.url.toString().removeSuffix("/")
        val animeName = animeDoc.selectFirst("h1")?.text() ?: ""

        val statusText = animeDoc.infoValue("Statut").orEmpty()
        val animeStatus = when {
            statusText.contains("En cours", true) -> SAnime.ONGOING
            statusText.contains("Termin", true) || statusText.contains("Fini", true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        val thumbnailUrl = animeDoc.selectFirst(".oe-affiche img")?.attr("abs:src")?.ifEmpty { null }
            ?: animeDoc.selectFirst("meta[property=og:image]")?.attr("abs:content")
            ?: animeDoc.selectFirst("meta[itemprop=image]")?.attr("abs:content")

        val scripts = animeDoc.select("script").joinToString("\n") { it.data() }
        val uncommented = COMMENT_REGEX.replace(scripts, "")
        val animes = SEASON_REGEX.findAll(uncommented).withIndex().asIterable().parallelCatchingFlatMapBlocking { (animeIndex, seasonMatch) ->
            val (seasonName, seasonStem) = seasonMatch.destructured

            val stemSeason = seasonStem.substringBefore("/")
            if (season.isNotEmpty() && stemSeason != season) {
                return@parallelCatchingFlatMapBlocking emptyList()
            }

            if (seasonStem.contains("film", true)) {
                val moviesUrl = "$animeUrl/$seasonStem"
                // The page is fetched once: its players only verify that the page has playable
                // sources (they are not the number of films), its HTML provides the film titles.
                val page = runCatching { fetchPlayerPage(moviesUrl) }.getOrNull()
                    ?.takeIf { it.players.isNotEmpty() }
                    ?: return@parallelCatchingFlatMapBlocking emptyList()
                val matches = MOVIE_NAME_REGEX.findAll(page.html).toList()
                val movieCount = matches.size.coerceAtLeast(1)
                List(movieCount) { i ->
                    val movieTitle = matches.getOrNull(i)?.groupValues?.getOrNull(1)
                        ?.takeIf { it.isNotBlank() }
                    val title = when {
                        movieTitle != null -> "$animeName $movieTitle"
                        movieCount == 1 && animeIndex == 0 -> animeName
                        movieCount == 1 -> "$animeName Film"
                        else -> "$animeName Film ${i + 1}"
                    }
                    Pair(title, "$moviesUrl#$i")
                }
            } else {
                val displaySeason = if (stemSeason.startsWith("saison")) {
                    "Saison " + stemSeason.substringAfter("saison").substringBefore("/")
                } else {
                    seasonName.substringBefore(" (")
                }
                listOf(Pair("$animeName $displaySeason", "$animeUrl/$seasonStem"))
            }
        }

        val altTitles = animeDoc.selectFirst("#titreAlter")?.text().orEmpty()
        val details = listOf("Format", "Studio", "Auteur", "Diffusion", "Durée")
            .mapNotNull { label -> animeDoc.infoValue(label)?.let { "$label : $it" } }
        val descriptionText = buildString {
            append(animeDoc.selectFirst("#synopsisText")?.text().orEmpty())
            if (altTitles.isNotEmpty()) append("\n\nTitres alternatifs : $altTitles")
            if (details.isNotEmpty()) append("\n\n${details.joinToString(" · ")}")
        }
        val authorText = animeDoc.infoValue("Auteur")
        val artistText = animeDoc.infoValue("Studio")
        val genresText = animeDoc.select(".genre-pill").joinToString { g -> g.text() }

        return animes.map { (animeTitle, animePath) ->
            SAnime.create().apply {
                title = animeTitle
                thumbnail_url = thumbnailUrl
                description = descriptionText
                genre = genresText
                author = authorText
                artist = artistText
                setUrlWithoutDomain(animePath.removeSuffix("/"))
                status = animeStatus
                this.initialized = initialized
            }
        }
    }

    private fun playersToEpisodes(list: List<List<List<String>>>, voiceNames: List<String>): List<SEpisode> {
        val episodeCount = list.maxOfOrNull { voice ->
            voice.maxOfOrNull { player -> player.size } ?: 0
        } ?: 0

        return List(episodeCount) { epIdx ->
            val episodeVoices = list.map { voicePlayers ->
                voicePlayers.mapNotNull { it.getOrNull(epIdx) }
            }
            SEpisode.create().apply {
                name = "Episode ${epIdx + 1}"
                url = episodeVoices.toJsonString()
                episode_number = (epIdx + 1).toFloat()
                scanlator = episodeVoices.mapIndexedNotNull { i, players ->
                    if (players.isNotEmpty()) voiceNames[i] else null
                }.joinToString().uppercase()
            }
        }
    }

    private class PlayerPage(val html: String, val players: List<List<String>>)

    private suspend fun fetchPlayers(url: String): List<List<String>> = fetchPlayerPage(url).players

    private suspend fun fetchPlayerPage(url: String): PlayerPage {
        val pageUrl = "${url.removeSuffix("/")}/"
        val pageResponse = client.newCall(GET(pageUrl, headers)).awaitSuccess()
        val finalUrl = pageResponse.request.url.toString()
        val html = pageResponse.bodyString()
        val scriptUrl = Jsoup.parse(html, finalUrl)
            .selectFirst("script[src*='episodes.js']")?.absUrl("src")
            ?.takeIf { it.isNotEmpty() }
            ?: throw Exception("episodes.js introuvable sur $pageUrl")

        val scriptHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .set("Accept", "*/*")
            .set("Sec-Fetch-Dest", "script")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-origin")
            .build()
        val script = client.newCall(GET(scriptUrl, scriptHeaders)).await().use {
            if (!it.isSuccessful) {
                throw Exception("episodes.js : HTTP ${it.code} - ${it.body.string().toPlainText().take(150)}")
            }
            it.body.string()
        }
        return PlayerPage(html, parsePlayers(script))
    }

    private fun parsePlayers(script: String): List<List<String>> {
        val cleaned = LINE_COMMENT_REGEX.replace(COMMENT_REGEX.replace(script, ""), "")
        val players = PLAYERS_REGEX.findAll(cleaned)
            .sortedBy { it.groupValues[1].toInt() }
            .map { match -> QUOTED_REGEX.findAll(match.groupValues[2]).map { it.groupValues[1] }.toList() }
            .toList()
        if (players.isNotEmpty()) return players

        return QuickJs.create().use { qjs ->
            qjs.evaluate(script)
            val res = qjs.evaluate("JSON.stringify(Object.keys(this).filter(k => /^eps[0-9]+$/.test(k)).sort((a, b) => parseInt(a.slice(3)) - parseInt(b.slice(3))).map(k => this[k]))")
            (res as String).parseAs<List<List<String>>>()
        }
    }

    private fun Document.infoValue(label: String): String? = selectFirst("dl.oe-infos dt:containsOwn($label) + dd")
        ?.text()
        ?.takeIf { it.isNotEmpty() }

    private fun String.toPlainText() = replace(HTML_TAG_REGEX, " ").replace(WHITESPACE_REGEX, " ").trim()

    private fun String.sanitizeDomain() = trim().removeSuffix("/").ifBlank { PREF_URL_DEFAULT }

    private fun updateDomain(domain: String) {
        val newDomain = domain.sanitizeDomain()
        if (URLUtil.isValidUrl(newDomain)) {
            preferences.customDomain = newDomain
            baseUrl = newDomain
        }
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_URL_KEY,
            title = PREF_URL_TITLE,
            default = PREF_URL_DEFAULT,
            summary = PREF_URL_SUMMARY,
            onChange = { _, newValue ->
                val newDomain = newValue.sanitizeDomain()
                if (URLUtil.isValidUrl(newDomain)) {
                    updateDomain(newDomain)
                    true
                } else {
                    Toast.makeText(screen.context, "URL invalide. Exemple: $PREF_URL_DEFAULT", Toast.LENGTH_LONG).show()
                    false
                }
            },
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = arrayOf("1080p", "720p", "480p", "360p"),
            entryValues = arrayOf("1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
        )
        screen.addListPreference(
            key = PREF_VOICES_KEY,
            title = "Préférence des voix",
            entries = VOICES,
            entryValues = VOICES_VALUES,
            default = PREF_VOICES_DEFAULT,
        )
        screen.addListPreference(
            key = PREF_PLAYER_KEY,
            title = "Lecteur par défaut",
            entries = PLAYERS,
            entryValues = PLAYERS_VALUES,
            default = PREF_PLAYER_DEFAULT,
        )
    }

    private fun PreferenceScreen.addListPreference(
        key: String,
        title: String,
        entries: Array<String>,
        entryValues: Array<String>,
        default: String,
    ) {
        ListPreference(context).apply {
            this.key = key
            this.title = title
            this.entries = entries
            this.entryValues = entryValues
            setDefaultValue(default)
            summary = "%s"
        }.also(::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val MAX_REDIRECTS = 5

        private const val MAX_CONCURRENT_SITE_REQUESTS = 4

        private const val PREF_URL_KEY = "base_url_pref"
        private const val PREF_URL_TITLE = "URL de base"

        // Domain info at: https://anime-sama.pw
        private const val PREF_URL_DEFAULT = "https://anime-sama.to"
        private const val PREF_URL_SUMMARY = "Pour changer le domaine de l'extension. Voir https://anime-sama.pw"

        // Must stay in sync with the deeplink hosts declared in build.gradle.kts
        private val DEEPLINK_HOSTS = setOf("anime-sama.to", "anime-sama.org", "anime-sama.si", "anime-sama.tv")

        private val COMMENT_REGEX = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT_REGEX = Regex("""^\s*//.*$""", RegexOption.MULTILINE)
        private val PLAYERS_REGEX = Regex("""\beps(\d+)\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
        private val QUOTED_REGEX = Regex("""['"]([^'"]*)['"]""")
        private val SEASON_REGEX = Regex("^\\s*panneauAnime\\(\"(.*)\", \"(.*)\"\\)", RegexOption.MULTILINE)
        private val MOVIE_NAME_REGEX = Regex("^\\s*newSPF\\(\"(.*)\"\\);", RegexOption.MULTILINE)
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
        private val WHITESPACE_REGEX = Regex("\\s+")

        private val voicesMap = mapOf(
            "Préférer VOSTFR" to "vostfr",
            "Préférer VF" to "vf",
            "Préférer VF1" to "vf1",
            "Préférer VF2" to "vf2",
            "Préférer VA" to "va",
            "Préférer VAR" to "var",
            "Préférer VCN" to "vcn",
            "Préférer VJ" to "vj",
            "Préférer VKR" to "vkr",
            "Préférer VQC" to "vqc",
        )
        private val VOICES = voicesMap.keys.toTypedArray()
        private val VOICES_VALUES = voicesMap.values.toTypedArray()

        private val playersMap = mapOf(
            "Sendvid" to "sendvid",
            "VK" to "vk",
            "VOE" to "voe",
            "VidMoly" to "vidmoly",
            "VidHide" to "vidhide",
            "Uqload" to "uqload",
            "YourUpload" to "yourupload",
            "Direct" to "direct",
        )
        private val PLAYERS = playersMap.keys.toTypedArray()
        private val PLAYERS_VALUES = playersMap.values.toTypedArray()

        private val VIDHIDE_DOMAINS = listOf("smoothpre", "movearnpre", "minochinos", "morencius", "earnvids")

        private val VOE_DOMAINS = listOf(
            "voe.sx",
            "teresapoliticallearn",
            "megankiddifference",
            "jeremyparticipantanything",
            "jamesbornmain",
            "claireguessthousand",
            "derricknationtop",
        )

        private val DEAD_HOSTS = listOf("sibnet.ru", "oneupload", "embed4me", "player4me", "4meplayer", "myvi")

        private const val PREF_VOICES_KEY = "voices_preference"
        private const val PREF_VOICES_DEFAULT = "vostfr"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_PLAYER_KEY = "player_preference"
        private const val PREF_PLAYER_DEFAULT = "sendvid"
    }
}

@Serializable
private class CatalogueFragment(
    val liste: String,
    val pagination: String,
)
