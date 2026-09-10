package eu.kanade.tachiyomi.animeextension.all.anizone

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
class LivewireDto(
    val components: List<ComponentDto>,
) {
    @Serializable
    class ComponentDto(
        val snapshot: String,
        val effects: EffectsDto,
    ) {
        @Serializable
        class EffectsDto(
            val html: String,
            val dispatches: List<DispatchDto> = emptyList(),
        )
    }
}

@Serializable
class DispatchDto(
    val name: String,
    val params: DispatchParamsDto? = null,
)

@Serializable
class DispatchParamsDto(
    val items: JsonArray? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean? = null,
)

@Serializable
class LivewireCall(
    val path: String = "",
    val method: String,
    val params: List<JsonElement>,
)

@Serializable
class LivewirePayload(
    @SerialName("_token") val token: String,
    val components: List<LivewireComponentPayload>,
)

@Serializable
class LivewireComponentPayload(
    val snapshot: String,
    val updates: JsonObject,
    val calls: List<LivewireCall>,
)

// A single anime entry from the Anime Index/Home page's
// `x-data="{ items: JSON.parse('[...]') }"` (initial load) or from an
// "items-loaded" Livewire dispatch's "items" param (pagination).
@Serializable
class AnimeXData(
    val url: String,
    val cover: String,
    @SerialName("main_title") val mainTitle: String,
    @SerialName("title_list")
    @Serializable(with = TitleListSerializer::class)
    val titleList: Map<String, String> = emptyMap(),
)

// A single episode entry from an anime's detail page episode list, found in
// the page's `x-data="{ items: JSON.parse('[...]') }"` (initial load) or in
// an "items-loaded" Livewire dispatch's "items" param (loadPage pagination).
// "type" (e.g. "Regular Episode", "Special", "Opening/Ending") is a real field
// on the site's JSON payload for every episode item - confirmed via network
// capture - and is the only reliable signal for the specials/regulars split,
// since JSON-sourced episode names never contain season/special wording
// (they're always "Episode N").
@Serializable
class EpisodeXData(
    val slug: String,
    val url: String,
    @SerialName("title_list")
    @Serializable(with = TitleListSerializer::class)
    val titleList: Map<String, String> = emptyMap(),
    @SerialName("air_date")
    val airDate: String? = null,
    val type: String? = null,
)

@Serializable
class VidstackConfig(
    val src: String,
    val subtitles: List<VidstackSubtitle> = emptyList(),
)

@Serializable
class VidstackSubtitle(
    val title: String,
    val file: String,
)

// AniZone's site returns `title_list: []` (empty array) instead of `{}` (empty
// object) for anime/episodes with no translated titles - a common PHP
// json_encode quirk where an empty associative array serializes as `[]`. This
// custom deserializer accepts both shapes instead of failing to parse.
object TitleListSerializer : KSerializer<Map<String, String>> {
    override val descriptor = buildClassSerialDescriptor("TitleList")

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val input = decoder as? JsonDecoder ?: error("Only JSON supported")
        val element = input.decodeJsonElement()
        return when (element) {
            is JsonArray -> emptyMap() // PHP's empty-array-as-[] quirk
            is JsonObject -> element.mapValues { it.value.jsonPrimitive.content }
            else -> emptyMap()
        }
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        error("Serialization not needed")
    }
}
