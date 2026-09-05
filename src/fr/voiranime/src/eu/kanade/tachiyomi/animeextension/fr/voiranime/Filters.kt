package eu.kanade.tachiyomi.animeextension.fr.voiranime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class Checkbox(name: String, val value: String) : AnimeFilter.CheckBox(name, false)

    open class CheckBoxGroupFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(displayName, vals.map { Checkbox(it.first, it.second) }) {
        fun toUriParts(): List<String> = state.filterIsInstance<Checkbox>().filter { it.state }.map { it.value }
    }

    class SortFilter : UriPartFilter("Trier par", SORT_OPTIONS)
    class GenreFilter : CheckBoxGroupFilter("Genres", GENRES)
    class GenreConditionFilter : UriPartFilter("Condition des genres", GENRE_CONDITIONS)
    class AuthorFilter : AnimeFilter.Text("Auteur")
    class ArtistFilter : AnimeFilter.Text("Artiste")
    class YearFilter : AnimeFilter.Text("Année de sortie")
    class AdultFilter : UriPartFilter("Contenu adulte", ADULT_OPTIONS)
    class StatusFilter : CheckBoxGroupFilter("Statut", STATUS_OPTIONS)
    class TypeFilter : UriPartFilter("Format", TYPE_OPTIONS)
    class LanguageFilter : UriPartFilter("Langue", LANGUAGE_OPTIONS)

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("NOTE : le texte de recherche est combiné avec les filtres"),
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        GenreConditionFilter(),
        AnimeFilter.Separator(),
        StatusFilter(),
        TypeFilter(),
        LanguageFilter(),
        AdultFilter(),
        AnimeFilter.Separator(),
        AuthorFilter(),
        ArtistFilter(),
        YearFilter(),
    )

    // Options de tri de la page "Recherche AV" (onglets .c-nav-tabs).
    private val SORT_OPTIONS = arrayOf(
        Pair("Pertinence", ""),
        Pair("Date", "latest"),
        Pair("A-Z", "alphabet"),
        Pair("Note", "rating"),
        Pair("Populaire", "trending"),
        Pair("Plus vues", "views"),
        Pair("Nouveautés", "new-manga"),
    )

    private val GENRE_CONDITIONS = arrayOf(
        Pair("Au moins un genre (OR)", ""),
        Pair("Tous les genres (AND)", "1"),
    )

    private val ADULT_OPTIONS = arrayOf(
        Pair("Tous", ""),
        Pair("Sans contenu adulte", "0"),
        Pair("Contenu adulte uniquement", "1"),
    )

    private val STATUS_OPTIONS = arrayOf(
        Pair("Terminé", "end"),
        Pair("En cours", "on-going"),
        Pair("Annulé", "canceled"),
        Pair("En pause", "on-hold"),
    )

    private val TYPE_OPTIONS = arrayOf(
        Pair("Tous", ""),
        Pair("TV", "TV"),
        Pair("Film", "MOVIE"),
        Pair("TV Short", "TV SHORT"),
        Pair("OVA", "OVA"),
        Pair("ONA", "ONA"),
        Pair("Spécial", "SPECIAL"),
    )

    private val LANGUAGE_OPTIONS = arrayOf(
        Pair("Toutes", ""),
        Pair("VF", "vf"),
        Pair("VOSTFR", "vostfr"),
    )

    // Slugs du formulaire de recherche avancée. Les libellés de tous les genres sauf
    // "Cartoon" et "R+" sont confirmés via le menu de navigation du site (voir-anime.to) ;
    // "Cartoon" et "R+" (uniquement présents dans le formulaire avancé) sont déduits du slug.
    private val GENRES = arrayOf(
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Cartoon", "cartoon"),
        Pair("Chinese", "chinese"),
        Pair("Comedy", "comedy"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Fantasy", "fantasy"),
        Pair("Horror", "horror"),
        Pair("Mahou Shoujo", "mahou-shoujo"),
        Pair("Mecha", "mecha"),
        Pair("Music", "music"),
        Pair("Mystery", "mystery"),
        Pair("Psychological", "psychological"),
        Pair("R+", "r"),
        Pair("Romance", "romance"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Sports", "sports"),
        Pair("Supernatural", "supernatural"),
        Pair("Thriller", "thriller"),
    )
}
