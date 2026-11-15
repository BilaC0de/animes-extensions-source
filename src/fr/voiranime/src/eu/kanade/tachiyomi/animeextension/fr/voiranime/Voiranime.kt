package eu.kanade.tachiyomi.animeextension.fr.voiranime

import aniyomi.lib.filemoonextractor.FilemoonExtractor
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import aniyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.annotation.Source
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.post
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Source
abstract class Voiranime : AnimeHttpSource() {

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder().add("Referer", "$baseUrl/")

    // Sans Referer : certains CDN (ex. celui derrière VidMoly) répondent 500 si le
    // Referer du site hôte est envoyé en plus de celui que l'extracteur définit lui-même.
    private val extractorHeaders by lazy { headers.newBuilder().removeAll("Referer").build() }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client, extractorHeaders) }
    private val voeExtractor by lazy { VoeExtractor(client, extractorHeaders) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // UniversalExtractor pilote une WebView : ses appels ne doivent pas se chevaucher
    // (l'app peut résoudre plusieurs hosters en parallèle).
    private val universalMutex = Mutex()

    // ============================== Popular ===============================
    // Page "Recherche AV" triée par popularité (m_orderby=trending).
    override fun popularAnimeRequest(page: Int): Request = GET(searchListingUrl(page, "trending"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseSearchListing(response)

    // =============================== Latest ===============================
    // Page d'accueil ("EN COURS") : animes ayant reçu un épisode récemment.
    // Pagination en `/page/{n}/`.
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("div.page-item-detail").mapNotNull(::latestAnimeFromElement)
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun latestAnimeFromElement(element: Element): SAnime? {
        val titleLink = element.selectFirst(".item-summary .post-title a") ?: return null
        return SAnime.create().apply {
            title = titleLink.text()
            setUrlWithoutDomain(titleLink.attr("abs:href"))
            thumbnail_url = element.selectFirst(".item-thumb img").imageUrl()
        }
    }

    // =============================== Search ===============================
    // Page "Recherche AV" (formulaire simple + formulaire avancé, voir Filters.kt).
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = filters.ifEmpty { Filters.FILTER_LIST }
        val pagePath = if (page > 1) "page/$page/" else ""

        val url = "$baseUrl/$pagePath".toHttpUrl().newBuilder().apply {
            addQueryParameter("s", query)
            addQueryParameter("post_type", "wp-manga")

            filterList.firstInstanceOrNull<Filters.SortFilter>()
                ?.toUriPart()?.takeIf(String::isNotEmpty)
                ?.let { addQueryParameter("m_orderby", it) }

            filterList.firstInstanceOrNull<Filters.GenreFilter>()
                ?.toUriParts()?.forEach { addQueryParameter("genre[]", it) }

            filterList.firstInstanceOrNull<Filters.GenreConditionFilter>()
                ?.toUriPart()?.takeIf(String::isNotEmpty)
                ?.let { addQueryParameter("op", it) }

            filterList.firstInstanceOrNull<Filters.AuthorFilter>()
                ?.state?.takeIf(String::isNotBlank)
                ?.let { addQueryParameter("author", it) }

            filterList.firstInstanceOrNull<Filters.ArtistFilter>()
                ?.state?.takeIf(String::isNotBlank)
                ?.let { addQueryParameter("artist", it) }

            filterList.firstInstanceOrNull<Filters.YearFilter>()
                ?.state?.takeIf(String::isNotBlank)
                ?.let { addQueryParameter("release", it) }

            filterList.firstInstanceOrNull<Filters.AdultFilter>()
                ?.toUriPart()?.takeIf(String::isNotEmpty)
                ?.let { addQueryParameter("adult", it) }

            filterList.firstInstanceOrNull<Filters.StatusFilter>()
                ?.toUriParts()?.forEach { addQueryParameter("status[]", it) }

            filterList.firstInstanceOrNull<Filters.TypeFilter>()
                ?.toUriPart()?.takeIf(String::isNotEmpty)
                ?.let { addQueryParameter("type", it) }

            filterList.firstInstanceOrNull<Filters.LanguageFilter>()
                ?.toUriPart()?.takeIf(String::isNotEmpty)
                ?.let { addQueryParameter("language", it) }
        }.build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseSearchListing(response)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        // Deeplink : l'app envoie l'URL complète ouverte comme requête.
        if (query.startsWith("http")) {
            val url = query.toHttpUrlOrNull()
            if (url != null && url.host.contains(baseUrl.toHttpUrl().host)) {
                val anime = SAnime.create().apply {
                    this.url = "/" + url.pathSegments.joinToString("/")
                }
                return AnimesPage(listOf(getAnimeDetails(anime)), false)
            }
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun getFilterList(): AnimeFilterList = Filters.FILTER_LIST

    // =========================== Anime Details ============================
    // Champs regroupés dans des blocs `.post-content_item` (label en `.summary-heading`,
    // valeur en `.summary-content`). Studios/Author/Artist ne sont pas toujours présents :
    // accès sûrs uniquement.
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = client.get("$baseUrl${anime.url}", headers).useAsJsoup()
        return detailsFromDocument(document, anime.url)
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    private fun detailsFromDocument(document: Document, animeUrl: String): SAnime = SAnime.create().apply {
        url = animeUrl
        // ownText() : évite d'inclure les badges (HOT, NEW…) que Madara met dans le <h1>.
        title = document.selectFirst(".post-title h1")?.ownText()?.trim()?.takeIf(String::isNotEmpty)
            ?: document.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = document.selectFirst(".summary_image img").imageUrl()
        description = document.select(".description-summary .summary__content p")
            .map { it.text().trim() }
            .filter(String::isNotEmpty)
            .joinToString("\n\n")
            .takeIf(String::isNotEmpty)
            ?: document.selectFirst(".description-summary .summary__content, .manga-excerpt")?.text()
        genre = document.select(".genres-content a").joinToString { it.text() }.ifBlank { null }
            ?: document.metaItem("Genre(s)")?.select("a")?.joinToString { it.text() }
        author = document.metaText("Author") ?: document.metaText("Studios")
        artist = document.metaText("Artist")
        status = parseStatus(document.metaText("Status"))
        initialized = true
    }

    private fun parseStatus(status: String?): Int {
        val value = status?.lowercase() ?: return SAnime.UNKNOWN
        return when {
            value.contains("cours") -> SAnime.ONGOING
            value.contains("termin") || value.contains("compl") -> SAnime.COMPLETED
            value.contains("annul") -> SAnime.CANCELLED
            value.contains("pause") -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    // La liste HTML statique n'est qu'un aperçu pour les séries très longues : on utilise
    // systématiquement l'endpoint ajax Madara (header X-Requested-With obligatoire), qui renvoie
    // des `li.wp-manga-chapter`. La liste est déjà triée du plus récent au plus ancien.
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        val chaptersUrl = anime.url.resolvePageUrl()
            ?.newBuilder()
            ?.addPathSegments("ajax/chapters/")
            ?.build()
            ?: return emptyList()
        val document = client.post(chaptersUrl.toString(), ajaxHeaders).useAsJsoup()
        return document.select("li.wp-manga-chapter").map(::episodeFromElement)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()

    // Motif du site : "{préfixe} - {nom épisode} - {numéro}". Les entrées qui ne suivent pas ce
    // motif (films, épisodes uniques) gardent leur texte brut.
    private fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = element.selectFirst("a")!!
        val href = link.absUrl("href")
        setUrlWithoutDomain(href)

        val rawText = link.text().trim()
        val parts = rawText.split(" - ")
        val baseName = if (parts.size >= 3) {
            "Épisode " + parts.subList(1, parts.size - 1).joinToString(" - ")
        } else {
            rawText
        }

        // Le site peut lister la VF et la VOSTFR d'un même épisode : le slug les distingue.
        val slug = href.trimEnd('/').substringAfterLast('/')
        val subType = when {
            slug.contains("vostfr", ignoreCase = true) || VOSTFR_REGEX.containsMatchIn(rawText) -> "VOSTFR"
            VF_SLUG_REGEX.containsMatchIn(slug) || VF_TEXT_REGEX.containsMatchIn(rawText) -> "VF"
            else -> null
        }
        name = if (subType != null && !baseName.contains(subType, ignoreCase = true)) {
            "$baseName $subType"
        } else {
            baseName
        }

        // Le slug porte le numéro de façon fiable (ex. ".../one-piece-0001-vostfr/"), contrairement
        // au texte affiché qui peut contenir des chiffres du titre. Le site complète avec des zéros.
        episode_number = EP_NUM_REGEX.find(slug)?.groupValues?.get(1)
            ?.trimStart('0')?.ifEmpty { "0" }
            ?.replace(',', '.')
            ?.toFloatOrNull()
            ?: parts.lastOrNull()?.trim()?.toFloatOrNull()
            ?: -1f

        date_upload = element.selectFirst(".chapter-release-date")?.text()?.let(::parseEpisodeDate) ?: 0L
    }

    private val episodeDateFormatEn by lazy { SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH) }
    private val episodeDateFormatFr by lazy { SimpleDateFormat("d MMMM yyyy", Locale.FRENCH) }

    private fun parseEpisodeDate(dateStr: String): Long = if (dateStr.contains("ago", ignoreCase = true)) {
        parseRelativeDate(dateStr)
    } else {
        episodeDateFormatEn.tryParse(dateStr).takeIf { it != 0L } ?: episodeDateFormatFr.tryParse(dateStr)
    }

    private fun parseRelativeDate(date: String): Long {
        val parts = date.substringBefore(" ago").removeSuffix("s").split(" ")
        val amount = parts.getOrNull(0)?.toIntOrNull() ?: return 0L
        val field = when (parts.getOrNull(1)) {
            "year" -> Calendar.YEAR
            "month" -> Calendar.MONTH
            "week" -> Calendar.WEEK_OF_YEAR
            "day" -> Calendar.DAY_OF_MONTH
            "hour" -> Calendar.HOUR_OF_DAY
            "minute" -> Calendar.MINUTE
            "second" -> Calendar.SECOND
            else -> return 0L
        }
        return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
    }

    // ============================== Hosters ===============================
    // Deux sources possibles pour la liste des lecteurs selon la version de la page épisode :
    // 1. le sélecteur `select.host-select` (chaque option pointe vers une page de redirection
    //    qui contient l'iframe, résolue seulement au moment de lancer le hoster) ;
    // 2. à défaut, la variable JS `thisChapterSources` (Map<nom du lecteur, HTML de l'iframe>),
    //    qui embarque directement l'URL de l'iframe.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val pageUrl = episode.url.resolvePageUrl()?.toString() ?: return emptyList()
        val document = client.get(pageUrl, headers).useAsJsoup()

        val fromSelect = document.select("select.host-select option").mapNotNull { option ->
            val redirect = option.attr("data-redirect").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = option.attr("value").cleanHosterName()
                .ifBlank { option.text().cleanHosterName() }
            Hoster(hosterName = name, internalData = "$REDIRECT_PREFIX$redirect")
        }
        if (fromSelect.isNotEmpty()) return fromSelect.distinctBy { it.internalData }

        val sources = document.selectFirst("script:containsData(thisChapterSources)")
            ?.data()
            ?.substringAfter("thisChapterSources = ")
            ?.substringBefore(";")
            ?.let { runCatching { it.parseAs<Map<String, String>>() }.getOrNull() }
            .orEmpty()

        return sources.mapNotNull { (serverName, iframeHtml) ->
            val iframe = IFRAME_SRC_REGEX.find(iframeHtml)?.groupValues?.get(1) ?: return@mapNotNull null
            Hoster(hosterName = serverName.cleanHosterName(), internalData = "$IFRAME_PREFIX$iframe")
        }.distinctBy { it.internalData }
    }

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    // ========================= Video Extraction ===========================
    // Dispatch par lecteur plutôt qu'un appel générique WebView, pour la fiabilité et la vitesse :
    // - myTV : marque blanche VidMoly (domaine rotatif) ; routé sur le LABEL, pas sur le domaine.
    // - VOE / Stape / SB / YU : extracteurs dédiés.
    // - MOON (Filemoon) : peut exiger un clic manuel (challenge anti-bot) ; en cas d'échec,
    //   seul ce hoster est vide.
    // - FHD1 (my.mail.ru) : très rarement fonctionnel, exclu.
    // - Tout autre lecteur retombe sur UniversalExtractor (WebView + sniffing réseau).
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = hoster.internalData
        val label = hoster.hosterName

        val iframe: String
        val referer: String
        if (data.startsWith(IFRAME_PREFIX)) {
            iframe = data.removePrefix(IFRAME_PREFIX)
            referer = "$baseUrl/"
        } else {
            val pageUrl = data.removePrefix(REDIRECT_PREFIX).resolvePageUrl()?.toString() ?: return emptyList()
            iframe = client.get(pageUrl, headers).useAsJsoup()
                .selectFirst("iframe")?.attr("abs:src")?.takeIf { it.isNotEmpty() }
                ?: return emptyList()
            referer = pageUrl
        }
        val host = iframe.toHttpUrlOrNull()?.host.orEmpty()

        return when {
            label.contains("myTV", true) || host.contains("vidmoly", true) ->
                vidMolyExtractor.videosFromUrl(iframe, prefix = "$label - ")

            label.contains("VOE", true) || host.contains("voe.sx", true) ->
                voeExtractor.videosFromUrl(iframe, prefix = "$label - ")

            label.contains("Stape", true) || label.contains("StreamTape", true) || host.contains("streamtape", true) ->
                streamTapeExtractor.videosFromUrl(iframe, "$label - Streamtape")

            label.contains("MOON", true) ->
                try {
                    filemoonExtractor.videosFromUrl(iframe, prefix = "$label - Filemoon - ", referer = referer)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }

            host.contains("mail.ru", true) -> emptyList()

            label.contains("SB", true) ->
                vidHideExtractor.videosFromUrl(iframe, videoNameGen = { quality -> "$label - VidHide - $quality" })

            label.contains("YU", true) || host.contains("yourupload", true) ->
                yourUploadExtractor.videoFromUrl(iframe, extractorHeaders, prefix = "$label - ")

            else -> universalMutex.withLock {
                universalExtractor.videosFromUrl(iframe, extractorHeaders, prefix = "$label - ")
            }
        }
    }

    // =============================== Utils ================================
    private fun String.cleanHosterName() = removePrefix("LECTEUR").trim()

    // N'accepte que les liens du domaine du site (relatifs ou absolus).
    private fun String.resolvePageUrl(): HttpUrl? {
        val base = baseUrl.toHttpUrl()
        return base.resolve(this)?.takeIf { it.host == base.host }
    }

    private fun searchListingUrl(page: Int, orderBy: String): String {
        val pagePath = if (page > 1) "page/$page/" else ""
        return "$baseUrl/$pagePath".toHttpUrl().newBuilder()
            .addQueryParameter("s", "")
            .addQueryParameter("post_type", "wp-manga")
            .addQueryParameter("m_orderby", orderBy)
            .build()
            .toString()
    }

    private fun parseSearchListing(response: Response): AnimesPage {
        val document = response.useAsJsoup()
        val animes = document.select("div.c-tabs-item__content").mapNotNull(::searchAnimeFromElement)
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        return AnimesPage(animes, hasNextPage)
    }

    private fun searchAnimeFromElement(element: Element): SAnime? {
        val link = element.selectFirst(".tab-summary .post-title a")
            ?: element.selectFirst("a[href*=/anime/]")
            ?: element.selectFirst("a")
            ?: return null
        val img = element.selectFirst(".tab-thumb img") ?: element.selectFirst("img")
        return SAnime.create().apply {
            setUrlWithoutDomain(link.attr("abs:href"))
            title = link.text().ifBlank { link.attr("title") }
                .ifBlank { img?.attr("alt").orEmpty() }
                .trim()
            thumbnail_url = img.imageUrl()
        }
    }

    // Les images peuvent être chargées en lazy-loading : l'URL réelle est alors dans `data-src`.
    private fun Element?.imageUrl(): String? = this
        ?.let { img -> img.absUrl("data-src").ifEmpty { img.absUrl("src") } }
        ?.takeIf(String::isNotBlank)

    // Cherche un bloc `.post-content_item` par son libellé (`.summary-heading`) et retourne son
    // `.summary-content`. Les libellés sont en anglais sur le site ("Status", "Genre(s)"…).
    private fun Element.metaItem(label: String): Element? = select(".post-content_item").firstOrNull {
        it.selectFirst(".summary-heading")?.text()?.trim().equals(label, ignoreCase = true)
    }?.selectFirst(".summary-content")

    private fun Element.metaText(label: String): String? = metaItem(label)?.text()?.trim()?.takeIf(String::isNotEmpty)

    companion object {
        // Sans filtre, la pagination est celle de wp-pagenavi (`a.nextpostslink`). Avec des filtres
        // (genre, etc.), le site affiche la navigation Madara par défaut : le lien « Older Posts »
        // (`.nav-previous`) pointe vers la page SUIVANTE, et `.nav-next` vers la précédente.
        private const val NEXT_PAGE_SELECTOR = "a.nextpostslink, .paging-navigation .nav-previous a"

        private const val REDIRECT_PREFIX = "redirect|"
        private const val IFRAME_PREFIX = "iframe|"

        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]*\ssrc=["']([^"']+)["']""")

        private val EP_NUM_REGEX = Regex("""-(\d+(?:[.,]\d+)?)-(?:vostfr|vf)""", RegexOption.IGNORE_CASE)
        private val VOSTFR_REGEX = Regex("""\bVOSTFR\b""", RegexOption.IGNORE_CASE)
        private val VF_SLUG_REGEX = Regex("""(^|-)vf($|-)""", RegexOption.IGNORE_CASE)
        private val VF_TEXT_REGEX = Regex("""\bVF\b""")
    }
}
