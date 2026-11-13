// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** One JSON configuration for the whole app: tolerant of fields we did not ask for. */
val RelaisJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

/** Envelope of every ReliefWeb API v2 response. */
@Serializable
data class ApiResponse<T>(
    val totalCount: Int = 0,
    val count: Int = 0,
    val data: List<ApiItem<T>> = emptyList(),
    val embedded: Embedded? = null,
)

@Serializable
data class ApiItem<T>(
    @Serializable(with = LenientLongSerializer::class) val id: Long,
    val score: Double? = null,
    val fields: T,
)

@Serializable
data class Embedded(val facets: Map<String, FacetResult> = emptyMap())

@Serializable
data class FacetResult(
    val data: List<FacetValue> = emptyList(),
    @Serializable(with = LenientLongSerializer::class) val missing: Long? = null,
    val type: String? = null,
)

@Serializable
data class FacetValue(
    @Serializable(with = LenientStringSerializer::class) val value: String,
    val count: Int = 0,
)

/** Fields of a report. Every field is optional: the API only returns what we request. */
@Serializable
data class ReportFields(
    @Serializable(with = LenientLongSerializer::class) val id: Long? = null,
    val title: String = "",
    @SerialName("url_alias") val urlAlias: String? = null,
    val url: String? = null,
    val origin: String? = null,
    val status: String? = null,
    val date: DateFields? = null,
    @SerialName("primary_country")
    @Serializable(with = CountryRefOrFirst::class)
    val primaryCountry: CountryRef? = null,
    @Serializable(with = CountryRefList::class) val country: List<CountryRef> = emptyList(),
    @Serializable(with = SourceRefList::class) val source: List<SourceRef> = emptyList(),
    @Serializable(with = NamedRefList::class) val format: List<NamedRef> = emptyList(),
    @Serializable(with = NamedRefList::class) val theme: List<NamedRef> = emptyList(),
    @Serializable(with = LanguageRefList::class) val language: List<LanguageRef> = emptyList(),
    @Serializable(with = DisasterRefList::class) val disaster: List<DisasterRef> = emptyList(),
    val headline: Headline? = null,
    @Serializable(with = FileRefList::class) val file: List<FileRef> = emptyList(),
    val image: ImageRef? = null,
    @SerialName("body-html") val bodyHtml: String? = null,
)

@Serializable
data class DateFields(
    val created: String? = null,
    val original: String? = null,
    val changed: String? = null,
)

@Serializable
data class CountryRef(
    /** 0 when the API sent an empty object. */
    @Serializable(with = LenientLongSerializer::class) val id: Long = 0,
    val name: String? = null,
    val shortname: String? = null,
    val iso3: String? = null,
    val primary: Boolean? = null,
) {
    val displayName: String get() = shortname ?: name ?: ""
}

@Serializable
data class SourceRef(
    @Serializable(with = LenientLongSerializer::class) val id: Long,
    val name: String? = null,
    val shortname: String? = null,
    val longname: String? = null,
    val homepage: String? = null,
) {
    val displayShort: String get() = shortname ?: name ?: ""
    val displayLong: String get() = longname ?: name ?: shortname ?: ""
}

@Serializable
data class NamedRef(
    @Serializable(with = LenientLongSerializer::class) val id: Long,
    val name: String? = null,
)

@Serializable
data class LanguageRef(
    @Serializable(with = LenientLongSerializer::class) val id: Long? = null,
    val name: String? = null,
    val code: String? = null,
)

@Serializable
data class DisasterRef(
    @Serializable(with = LenientLongSerializer::class) val id: Long,
    val name: String? = null,
    val glide: String? = null,
    val status: String? = null,
)

@Serializable
data class Headline(
    val title: String? = null,
    val summary: String? = null,
    val image: ImageRef? = null,
)

@Serializable
data class ImageRef(
    val url: String? = null,
    @SerialName("url-small") val urlSmall: String? = null,
    @SerialName("url-large") val urlLarge: String? = null,
    @SerialName("url-thumb") val urlThumb: String? = null,
    val caption: String? = null,
    val copyright: String? = null,
    @Serializable(with = LenientLongSerializer::class) val width: Long? = null,
    @Serializable(with = LenientLongSerializer::class) val height: Long? = null,
)

@Serializable
data class FileRef(
    @Serializable(with = LenientLongSerializer::class) val id: Long? = null,
    val url: String? = null,
    val filename: String? = null,
    @Serializable(with = LenientLongSerializer::class) val filesize: Long? = null,
    @Serializable(with = LenientLongSerializer::class) val pagecount: Long? = null,
    val mimetype: String? = null,
    val description: String? = null,
    val preview: FilePreview? = null,
)

@Serializable
data class FilePreview(
    val url: String? = null,
    @SerialName("url-small") val urlSmall: String? = null,
    @SerialName("url-large") val urlLarge: String? = null,
    @SerialName("url-thumb") val urlThumb: String? = null,
)

/** Fields of a country: the picker's row, and the profile for the overview (call A). */
@Serializable
data class CountryFields(
    @Serializable(with = LenientLongSerializer::class) val id: Long? = null,
    val name: String = "",
    val shortname: String? = null,
    val iso3: String? = null,
    val status: String? = null,
    @SerialName("url_alias") val urlAlias: String? = null,
    val profile: Profile? = null,
)

/** Fields of an organization (a ReliefWeb source, P13 §1). The app shows no logo. */
@Serializable
data class SourceFields(
    @Serializable(with = LenientLongSerializer::class) val id: Long? = null,
    val name: String = "",
    val shortname: String? = null,
    val longname: String? = null,
    @Serializable(with = NamedRefOrFirst::class) val type: NamedRef? = null,
    @Serializable(with = CountryRefOrFirst::class) val country: CountryRef? = null,
    val homepage: String? = null,
    val status: String? = null,
    @SerialName("description-html") val descriptionHtml: String? = null,
) {
    val displayShort: String get() = shortname?.takeIf { it.isNotBlank() } ?: name
}

/** Fields of a disaster ("crisis" in the app). */
@Serializable
data class DisasterFields(
    @Serializable(with = LenientLongSerializer::class) val id: Long? = null,
    val name: String = "",
    @SerialName("url_alias") val urlAlias: String? = null,
    val url: String? = null,
    /** alert, ongoing (or the older current), past. */
    val status: String? = null,
    val glide: String? = null,
    val date: DisasterDates? = null,
    @SerialName("primary_type") @Serializable(with = NamedRefOrFirst::class) val primaryType: NamedRef? = null,
    @Serializable(with = NamedRefList::class) val type: List<NamedRef> = emptyList(),
    @SerialName("primary_country")
    @Serializable(with = CountryRefOrFirst::class)
    val primaryCountry: CountryRef? = null,
    @Serializable(with = CountryRefList::class) val country: List<CountryRef> = emptyList(),
    @SerialName("description-html") val descriptionHtml: String? = null,
    val profile: Profile? = null,
) {
    val typeName: String? get() = primaryType?.name ?: type.firstOrNull()?.name
}

@Serializable
data class DisasterDates(
    val created: String? = null,
    val event: String? = null,
    val changed: String? = null,
)

/** The editors' profile of a country or a disaster: current appeals and plans, key content. */
@Serializable
data class Profile(
    @SerialName("appeals_response_plans") val appeals: ProfileSection? = null,
    @SerialName("key_content") val keyContent: ProfileSection? = null,
)

@Serializable
data class ProfileSection(
    val title: String? = null,
    @Serializable(with = ProfileLinkList::class) val active: List<ProfileLink> = emptyList(),
)

@Serializable
data class ProfileLink(
    val url: String? = null,
    val title: String? = null,
    val cover: String? = null,
)

// ---------------------------------------------------------------------------
// Tolerant serializers. The API sometimes returns numbers as strings, and some
// taxonomy fields can be a single object or a list. These keep parsing robust.
// ---------------------------------------------------------------------------

object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.relais.api.LenientLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = json.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("Expected a number, got $element")
        return primitive.longOrNull
            ?: primitive.doubleOrNull?.toLong()
            ?: primitive.content.trim().toLong()
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

object LenientStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.relais.api.LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = json.decodeJsonElement()) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** Accepts either one object or a list of objects. */
abstract class ObjectOrList<T : Any>(element: KSerializer<T>) :
    JsonTransformingSerializer<List<T>>(ListSerializer(element)) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonObject) JsonArray(listOf(element)) else element
}

object CountryRefList : ObjectOrList<CountryRef>(CountryRef.serializer())
object SourceRefList : ObjectOrList<SourceRef>(SourceRef.serializer())
object NamedRefList : ObjectOrList<NamedRef>(NamedRef.serializer())
object LanguageRefList : ObjectOrList<LanguageRef>(LanguageRef.serializer())
object DisasterRefList : ObjectOrList<DisasterRef>(DisasterRef.serializer())
object FileRefList : ObjectOrList<FileRef>(FileRef.serializer())
object ProfileLinkList : ObjectOrList<ProfileLink>(ProfileLink.serializer())

/** Accepts one object or a list; keeps the first element of a list. */
object NamedRefOrFirst : JsonTransformingSerializer<NamedRef>(NamedRef.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element.firstOrNull() ?: JsonObject(mapOf("id" to JsonPrimitive(0))) else element
}

/** Accepts one object or a list; keeps the first element of a list. */
object CountryRefOrFirst : JsonTransformingSerializer<CountryRef>(CountryRef.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) element.firstOrNull() ?: JsonObject(emptyMap()) else element
}
