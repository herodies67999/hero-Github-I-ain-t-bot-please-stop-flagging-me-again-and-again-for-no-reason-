package app.yoru

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

const val KEIYOUSHI = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
const val KEIYOUSHI_SIGNER = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

data class Repo(val name: String, val url: String, val type: String, val signer: String = "")

data class Extension(
    val id: String,
    val name: String,
    val version: String,
    val code: Long,
    val url: String,
    val icon: String = "",
    val languages: String = "all",
    val type: String = "mihon",
    val repo: String = "",
    val signer: String = "",
    val adult: Boolean = false,
) {
    val key
        get() = "$type:$repo:$id"
}

data class Installed(
    val extension: Extension,
    val filename: String,
    val sha256: String,
    val signer: String,
)

data class Source(
    val id: String,
    val name: String,
    val lang: String,
    val type: String,
    val packageId: String,
    val icon: String = "",
) {
    val manga
        get() = type == "mihon"

    val key
        get() = "$type:$id"
}

data class Title(
    val title: String,
    val url: String,
    val cover: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val source: Source? = null,
    val banner: String = cover,
    val score: String = "",
    val editorialId: Int = 0,
) {
    val key
        get() = "${source?.key?:"editorial"}:$url"
}

data class Episode(val name: String, val url: String, val raw: Map<String, Any?> = emptyMap())

data class Detail(val media: Title, val episodes: List<Episode>)

data class PageImage(val url: String, val headers: Map<String, String> = emptyMap())

data class Subtitle(val file: String, val label: String)

data class Stream(
    val url: String,
    val label: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<Subtitle> = emptyList(),
    val hls: Boolean = false,
)

data class Progress(
    val media: Title,
    val episode: Episode,
    val position: Long,
    val reader: Boolean,
    val updated: Long = System.currentTimeMillis(),
) {
    val key
        get() = "${media.key}:${episode.url}"
}

data class UserState(
    val library: List<Title> = emptyList(),
    val history: List<Progress> = emptyList(),
    val repos: List<Repo> = listOf(Repo("Keiyoushi", KEIYOUSHI, "mihon", KEIYOUSHI_SIGNER)),
    val installed: List<Installed> = emptyList(),
    val mode: String = "anime",
    val theme: String = "dark",
    val accent: Long = 0xFFC2A5FF,
)

data class Reading(
    val media: Title,
    val episode: Episode,
    val pages: List<PageImage>,
    val start: Int,
)

data class PendingInstall(
    val entry: Extension,
    val tempPath: String,
    val digest: String,
    val signer: String,
)

@Serializable
data class StoreIndex(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badge: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: Contact? = null,
    @ProtoNumber(101) val extensionList: StoreList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
)

@Serializable
data class Contact(
    @ProtoNumber(1) val website: String = "",
    @ProtoNumber(2) val discord: String? = null,
)

@Serializable
data class StoreList(@ProtoNumber(1) val extensions: List<StoreExtension> = emptyList())

@Serializable
data class StoreExtension(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val packageName: String = "",
    @ProtoNumber(3) val resources: StoreResources = StoreResources(),
    @ProtoNumber(4) val extensionLib: String = "",
    @ProtoNumber(5) val versionCode: Long = 0,
    @ProtoNumber(6) val versionName: String = "",
    @ProtoNumber(7) val contentWarning: Int = 0,
    @ProtoNumber(8) val sources: List<StoreSource> = emptyList(),
)

@Serializable
data class StoreResources(
    @ProtoNumber(1) val apkUrl: String = "",
    @ProtoNumber(2) val iconUrl: String = "",
)

@Serializable
data class StoreSource(
    @ProtoNumber(1) val id: Long = 0,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val language: String = "",
    @ProtoNumber(4) val homeUrl: String = "",
    @ProtoNumber(5) val mirrors: List<String> = emptyList(),
    @ProtoNumber(7) val message: String? = null,
)
