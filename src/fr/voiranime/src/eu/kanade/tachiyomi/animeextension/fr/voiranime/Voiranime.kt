package eu.kanade.tachiyomi.animeextension.fr.voiranime

import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.universalextractor.UniversalExtractor
import aniyomi.lib.vidmolyextractor.VidMolyExtractor
import aniyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Voiranime : AnimeHttpLegacySource() {

    override val name = "Voiranime"

    override val baseUrl = "https://voir-anime.to"

    override val lang = "fr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Sans Referer : certains CDN (ex. celui derrière VidMoly) répondent 500 si le
    // Referer du site hôte est envoyé en plus de celui que l'extracteur définit lui-même.
    private val extractorHeaders by lazy { headers.newBuilder().removeAll("Referer").build() }

    private val vidMolyExtractor by lazy { VidMolyExtractor(client, extractorHeaders) }
    private val voeExtractor by lazy { VoeExtractor(client, extractorHeaders) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ============================== Popular ================================
    // Page "Recherche AV" triée par popularité (m_orderby=trending).

    override fun popularAnimeRequest(page: Int): Request = GET(searchListingUrl(page, "trending"), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseSearchListing(response)

    // =============================== Latest =================================
    // Page d'accueil ("EN COURS") : liste les animes ayant reçu un épisode récemment,
    // triée par date de dernière mise à jour. Pagination confirmée en `/page/{n}/`
    // (identique à la page de recherche, testée directement sur le site).

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select("div.page-item-detail").map(::latestAnimeFromElement)
        val hasNextPage = document.selectFirst(nextPageSelector) != null

        return AnimesPage(animes, hasNextPage)
    }

    private fun latestAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleLink = element.selectFirst(".item-summary .post-title a")!!
        title = titleLink.text()
        setUrlWithoutDomain(titleLink.attr("href"))
        thumbnail_url = element.selectFirst(".item-thumb img")?.absUrl("src")?.takeIf(String::isNotBlank)
    }

    // =============================== Search ==================================
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

    // =============================== Details ==================================
    // Champs regroupés dans des blocs répétés `.post-content_item` (label en `h5`,
    // valeur en `.summary-content`). Studios/Author/Artist ne sont pas toujours
    // présents (confirmé en comparant plusieurs animes), donc accès sûrs uniquement.

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = document.selectFirst(".post-title h1")!!.text()
            thumbnail_url = document.selectFirst(".summary_image img")?.absUrl("src")?.takeIf(String::isNotBlank)
            description = document.select(".description-summary .summary__content p")
                .map { it.text().trim() }
                .filter(String::isNotEmpty)
                .joinToString("\n\n")
                .takeIf(String::isNotEmpty)
            genre = document.metaItem("Genre(s)")?.select("a")?.joinToString { it.text() }
            author = document.metaText("Author")
            artist = document.metaText("Artist")
            status = parseStatus(document.metaText("Status"))
            initialized = true
        }
    }

    private fun parseStatus(status: String?): Int {
        val value = status?.lowercase() ?: return SAnime.UNKNOWN
        return when {
            value.contains("cours") -> SAnime.ONGOING
            value.contains("termin") -> SAnime.COMPLETED
            value.contains("annul") -> SAnime.CANCELLED
            value.contains("pause") -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
    }

    // =============================== Episodes ==================================
    // La page anime n'embarque pas forcément la liste complète des épisodes pour
    // les séries à très grand nombre d'épisodes (comportement Madara classique :
    // la liste HTML statique n'est qu'un aperçu, le JS du site charge le reste via
    // cet endpoint ajax). On utilise donc systématiquement l'endpoint ajax, qui
    // renvoie le même motif de fragment `li.wp-manga-chapter` que la page statique.
    //
    // Motif du site : "{préfixe} - {nom épisode} - {numéro}". On découpe nous-mêmes
    // (première/dernière partie) plutôt que de garder le texte brut, pour un
    // affichage cohérent qu'importe la comparaison interne de l'app avec le titre
    // affiché de l'anime. Les entrées qui ne suivent pas ce motif (films, épisodes
    // uniques) gardent leur texte brut tel quel plutôt qu'un numéro artificiel.
    // La liste est déjà triée du plus récent au plus ancien sur le site, conforme
    // à l'exigence de tri décroissant.

    override fun episodeListRequest(anime: SAnime): Request {
        val ajaxHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        // On normalise le trailing slash de anime.url plutôt que de le supposer présent :
        // une simple concaténation aurait cassé l'URL (ou produit un chemin invalide)
        // si un lien de titre venait un jour sans "/" final.
        val animePath = anime.url.removeSuffix("/")
        return POST("$baseUrl$animePath/ajax/chapters/", ajaxHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        return document.select(episodeListSelector).map(::episodeFromElement)
    }

    private fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = element.selectFirst("a")!!
        setUrlWithoutDomain(link.absUrl("href"))

        val rawText = link.text().trim()
        val parts = rawText.split(" - ")

        // Préfixe "Épisode" uniquement quand le motif série est détecté (nom +
        // numéro extraits) : les films / entrées uniques passent par le repli
        // ci-dessous et gardent leur texte brut, sans numérotation artificielle.
        name = if (parts.size >= 3) {
            "Épisode " + parts.subList(1, parts.size - 1).joinToString(" - ")
        } else {
            rawText
        }
        episode_number = parts.lastOrNull()?.trim()?.toFloatOrNull() ?: -1f

        date_upload = element.selectFirst(".chapter-release-date")?.text()?.let(::parseEpisodeDate) ?: 0L
    }

    // Pas d'ancrage sur `.listing-chapters_wrap` : ce conteneur fait partie du
    // gabarit de la page anime complète, absent du fragment retourné par l'ajax.
    private val episodeListSelector = "li.wp-manga-chapter"

    private val episodeDateFormat by lazy { SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH) }

    private fun parseEpisodeDate(dateStr: String): Long = if (dateStr.contains("ago", ignoreCase = true)) {
        parseRelativeDate(dateStr)
    } else {
        episodeDateFormat.tryParse(dateStr)
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

    // =============================== Videos ==================================
    // Tous les lecteurs (et leur balise <iframe> déjà résolue) sont embarqués dans
    // une variable JS `thisChapterSources` (JSON valide : Map<nom du lecteur, HTML
    // de l'iframe>) directement dans la page épisode. Pas d'appel ajax nécessaire ici.
    //
    // Dispatch par lecteur plutôt qu'un unique appel générique WebView, pour la
    // fiabilité et la vitesse (voir historique des extracteurs dédiés ci-dessous) :
    // - myTV : marque blanche VidMoly (domaine observé rotatif, ex. voembed.net) ;
    //   routé sur le LABEL "myTV", pas sur le domaine, pour rester valide si celui-ci
    //   change.
    // - VOE / Stape : domaines officiels stables (voe.sx, streamtape.com), routage
    //   par domaine classique.
    // - MOON (gn1r5n.org) : nécessite un clic manuel avant tout chargement (challenge
    //   anti-bot + lecteur JW Player), incompatible avec le sniffing passif de
    //   `UniversalExtractor`. Exclu explicitement pour éviter une attente inutile.
    // - FHD1 (my.mail.ru) : très rarement présent/fonctionnel sur ce site (souvent
    //   une source 404 côté site). Exclu pour la même raison que MOON.
    // - Tout autre lecteur inconnu retombe sur `UniversalExtractor` en dernier
    //   recours (WebView + sniffing réseau), au prix d'un délai potentiel plus élevé.

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = baseUrl + episode.url
        val document = client.newCall(GET(episodeUrl, headers)).awaitSuccess().useAsJsoup()

        val sources = document.selectFirst("script:containsData(thisChapterSources)")
            ?.data()
            ?.substringAfter("thisChapterSources = ")
            ?.substringBefore(";")
            ?.let { runCatching { it.parseAs<Map<String, String>>() }.getOrNull() }

        if (sources.isNullOrEmpty()) return emptyList()

        return sources.entries.parallelCatchingFlatMap { (serverName, iframeHtml) ->
            val iframe = iframeSrcRegex.find(iframeHtml)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMap emptyList()

            val label = serverName.removePrefix("LECTEUR").trim()
            val host = iframe.toHttpUrlOrNull()?.host.orEmpty()

            when {
                label.equals("myTV", ignoreCase = true) || host.contains("vidmoly", ignoreCase = true) ->
                    vidMolyExtractor.videosFromUrl(iframe, prefix = "$label - ")

                host.contains("voe.sx", ignoreCase = true) ->
                    voeExtractor.videosFromUrl(iframe, prefix = "$label - ")

                host.contains("streamtape", ignoreCase = true) ->
                    streamTapeExtractor.videosFromUrl(iframe, "$label - Streamtape")

                label.equals("MOON", ignoreCase = true) || host.contains("mail.ru", ignoreCase = true) ->
                    emptyList()

                else -> universalExtractor.videosFromUrl(iframe, extractorHeaders, prefix = "$label - ")
            }
        }
    }

    private val iframeSrcRegex = Regex("""<iframe[^>]*\ssrc=["']([^"']+)["']""")

    // =============================== Utilities ==================================

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
        val document = response.asJsoup()

        val animes = document.select(searchListingSelector).map(::searchAnimeFromElement)
        val hasNextPage = document.selectFirst(nextPageSelector) != null

        return AnimesPage(animes, hasNextPage)
    }

    private val searchListingSelector = "div.row.c-tabs-item__content"

    private val nextPageSelector = ".wp-pagenavi .nextpostslink"

    private fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleLink = element.selectFirst(".tab-summary .post-title a")!!
        title = titleLink.text()
        setUrlWithoutDomain(titleLink.attr("href"))
        thumbnail_url = element.selectFirst(".tab-thumb img")?.absUrl("src")?.takeIf(String::isNotBlank)
    }

    // Cherche un bloc `.post-content_item` par son libellé (`h5`) et retourne son
    // conteneur `.summary-content`. Les libellés sont en anglais sur le site
    // ("Status", "Genre(s)"...) même si les valeurs sont en français.
    private fun Element.metaItem(label: String): Element? = select(".post-content_item").firstOrNull {
        it.selectFirst(".summary-heading")?.text()?.trim().equals(label, ignoreCase = true)
    }?.selectFirst(".summary-content")

    private fun Element.metaText(label: String): String? = metaItem(label)?.text()?.trim()?.takeIf(String::isNotEmpty)
}
