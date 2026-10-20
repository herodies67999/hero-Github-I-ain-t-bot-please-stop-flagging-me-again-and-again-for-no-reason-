package app.yoru

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import com.anymex.runtimehost.RuntimeBridge
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.PluginManager
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okhttp3.Request

/** Owns the trusted package store. All calls are serialized by AppModel on an IO dispatcher. */
class Engine(private val context: Context) {
    private val gson = Gson()
    private val prefs = context.getSharedPreferences("yoru_native_v2", Context.MODE_PRIVATE)
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    var user: UserState =
        runCatching {
                gson.fromJson(prefs.getString("state", null), UserState::class.java)?.also {
                    validateState(it)
                }
            }
            .getOrNull() ?: UserState()
        private set

    private val mangaDir = File(context.filesDir, "exts_manga").apply { mkdirs() }
    private val pluginDir = File(context.filesDir, "yoru_plugins").apply { mkdirs() }
    var loadErrors: List<String> = emptyList()
        private set

    fun save(state: UserState) {
        check(prefs.edit().putString("state", gson.toJson(state)).commit()) {
            "Could not save app data"
        }
        user = state
    }

    fun initialize() {
        RuntimeBridge.initialize(context)
    }

    fun installedSources(): List<Source> {
        RuntimeBridge.invalidateSourceCache()
        val errors = mutableListOf<String>()
        val trusted = user.installed.map { file(it).absolutePath }.toSet()
        // Never execute stray or modified packages copied into the store.
        mangaDir
            .listFiles()
            ?.filter { it.extension == "apk" && it.absolutePath !in trusted }
            ?.forEach { it.renameTo(File(it.parentFile, it.name + ".quarantined")) }
        for (record in user.installed) {
            val f = file(record)
            if (!f.exists() || sha(f.readBytes()) != record.sha256) {
                errors.add("${record.extension.name}: package missing or changed")
                if (f.exists()) f.renameTo(File(f.parentFile, f.name + ".quarantined"))
            }
        }
        val manga =
            runCatching { RuntimeBridge.getInstalledMangaExtensions(context) }
                .getOrElse {
                    errors.add("Manga runtime: ${it.message}")
                    emptyList()
                }
                .map {
                    Source(
                        it.s("id"),
                        it.s("name"),
                        it.s("lang"),
                        "mihon",
                        it.s("pkgName"),
                        it.s("iconUrl"),
                    )
                }
        for (record in user.installed.filter { it.extension.type == "cloudstream" }) {
            val f = file(record)
            if (f.exists() && !PluginManager.plugins.containsKey(f.absolutePath)) {
                val ok =
                    runCatching { RuntimeBridge.csLoadPlugin(context, f.absolutePath) }
                        .getOrDefault(false)
                if (!ok) errors.add("${record.extension.name}: plugin load failed")
            }
        }
        val cloud =
            RuntimeBridge.csGetRegisteredProviders().map {
                Source(
                    it.s("id"),
                    it.s("name"),
                    it.s("language"),
                    "cloudstream",
                    user.installed
                        .firstOrNull { r -> file(r).absolutePath == it.s("plugin") }
                        ?.extension
                        ?.id ?: it.s("internalName"),
                    it.s("iconUrl"),
                )
            }
        for (record in user.installed.filter { it.extension.type == "mihon" }) if (
            manga.none { it.packageId == record.extension.id }
        )
            errors.add("${record.extension.name}: no compatible sources loaded")
        loadErrors = errors.distinct()
        return manga + cloud
    }

    private fun file(record: Installed) =
        File(if (record.extension.type == "mihon") mangaDir else pluginDir, record.filename)

    fun loadRepo(repo: Repo): Pair<Repo, List<Extension>> {
        val bytes = download(repo.url, 20 * 1024 * 1024)
        if (repo.type == "mihon") {
            val index = ProtoBuf.decodeFromByteArray(StoreIndex.serializer(), unpack(bytes))
            require(index.name.isNotBlank()) { "Invalid Mihon repository" }
            require(repo.signer.isBlank() || repo.signer.equals(index.signingKey, true)) {
                "Repository signing identity changed. Remove and explicitly re-add only after verifying the new identity."
            }
            val list =
                index.extensionList
                    ?: index.extensionListUrl?.let {
                        ProtoBuf.decodeFromByteArray(
                            StoreList.serializer(),
                            unpack(download(resolve(repo.url, it), 20 * 1024 * 1024)),
                        )
                    }
                    ?: StoreList()
            val normalized = repo.copy(name = index.name, signer = index.signingKey)
            return normalized to
                list.extensions.map {
                    Extension(
                        it.packageName,
                        it.name,
                        it.versionName,
                        it.versionCode,
                        https(it.resources.apkUrl),
                        it.resources.iconUrl,
                        it.sources.map { it.language }.distinct().joinToString(", "),
                        "mihon",
                        repo.url,
                        index.signingKey,
                        it.contentWarning == 3,
                    )
                }
        }
        val root = JsonParser.parseString(bytes.decodeToString()).asJsonObject
        val lists =
            root.getAsJsonArray("pluginLists")
                ?: error("Expected a Cloudstream repository JSON with pluginLists")
        val items =
            lists.take(20).flatMap { list ->
                JsonParser.parseString(
                        download(resolve(repo.url, list.asString), 20 * 1024 * 1024)
                            .decodeToString()
                    )
                    .asJsonArray
                    .map { x ->
                        val o = x.asJsonObject
                        fun text(k: String): String {
                            return o.get(k)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                        }
                        Extension(
                            text("internalName").ifBlank { text("name") },
                            text("name"),
                            text("version"),
                            text("version").toLongOrNull() ?: 0,
                            https(text("url")),
                            text("iconUrl"),
                            text("language").ifBlank { "all" },
                            "cloudstream",
                            repo.url,
                        )
                    }
            }
        return repo.copy(name = root.get("name")?.asString ?: repo.name) to
            items.distinctBy { it.key }
    }

    fun prepare(entry: Extension): PendingInstall {
        val existing = user.installed.firstOrNull { it.extension.key == entry.key }
        require(existing == null || entry.code >= existing.extension.code) {
            "Downgrades are not allowed"
        }
        val bytes = download(entry.url, 80 * 1024 * 1024)
        val staging = File.createTempFile("package-", ".download", context.cacheDir)
        staging.writeBytes(bytes)
        try {
            val digest = sha(bytes)
            var signer = ""
            if (entry.type == "mihon") {
                val verified = ApkVerifier.Builder(staging).build().verify()
                require(verified.isVerified) { "APK signature verification failed" }
                require(verified.signerCertificates.size == 1) { "Expected one extension signer" }
                signer = sha(verified.signerCertificates.single().encoded)
                require(entry.signer.isBlank() || signer.equals(entry.signer, true)) {
                    "APK signer does not match the trusted repository"
                }
                require(existing == null || existing.signer == signer) { "Update signer changed" }
                val info =
                    context.packageManager.getPackageArchiveInfo(
                        staging.absolutePath,
                        PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS,
                    ) ?: error("Not an Android extension APK")
                require(info.packageName == entry.id) { "Package ID mismatch" }
                val code =
                    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
                    else @Suppress("DEPRECATION") info.versionCode.toLong()
                require(code == entry.code) { "Version differs from repository index" }
                require(info.reqFeatures?.any { it.name == "tachiyomi.extension" } == true) {
                    "Not a Mihon manga extension"
                }
            } else {
                ZipFile(staging).use { zip ->
                    require(zip.getEntry("classes.dex") != null) {
                        "Plugin has no Android bytecode"
                    }
                    val manifest =
                        zip.getEntry("manifest.json") ?: error("Missing Cloudstream manifest")
                    require(manifest.size in 1..1_000_000) { "Invalid plugin manifest size" }
                    val obj =
                        JsonParser.parseString(
                                zip.getInputStream(manifest).bufferedReader().readText()
                            )
                            .asJsonObject
                    require(obj.get("pluginClassName")?.asString?.isNotBlank() == true) {
                        "Missing plugin entry point"
                    }
                    require(obj.get("version")?.asLong == entry.code) {
                        "Plugin version differs from repository index"
                    }
                }
            }
            return PendingInstall(entry, staging.absolutePath, digest, signer)
        } catch (e: Throwable) {
            staging.delete()
            throw e
        }
    }

    fun cancel(pending: PendingInstall) {
        File(pending.tempPath).delete()
    }

    fun install(pending: PendingInstall) {
        val temp = File(pending.tempPath)
        require(temp.isFile && sha(temp.readBytes()) == pending.digest) {
            "Prepared package changed"
        }
        val entry = pending.entry
        val previous = user
        val existing = user.installed.firstOrNull { it.extension.key == entry.key }
        val name = sha(entry.key.toByteArray()) + if (entry.type == "mihon") ".apk" else ".cs3"
        val record = Installed(entry, name, pending.digest, pending.signer)
        val target = file(record)
        val backup = File(target.parentFile, "$name.backup")
        unload(existing)
        backup.delete()
        if (target.exists()) check(target.renameTo(backup)) { "Cannot stage existing extension" }
        try {
            check(temp.setReadOnly())
            check(temp.renameTo(target)) { "Could not commit package" }
            save(
                user.copy(
                    installed = user.installed.filterNot { it.extension.key == entry.key } + record
                )
            )
            val sources = installedSources()
            if (entry.type == "mihon")
                require(sources.any { it.packageId == entry.id }) {
                    "Extension could not load against this host API"
                }
            else
                require(PluginManager.plugins.containsKey(target.absolutePath)) {
                    "Cloudstream plugin failed to initialize"
                }
            backup.delete()
        } catch (e: Throwable) {
            unload(record)
            target.delete()
            if (backup.exists()) backup.renameTo(target)
            save(previous)
            installedSources()
            throw e
        }
    }

    private fun unload(record: Installed?) {
        if (record == null) return
        if (record.extension.type == "cloudstream") {
            RuntimeBridge.csGetRegisteredProviders()
                .filter { it.s("plugin") == file(record).absolutePath }
                .forEach { RuntimeBridge.csUnloadPlugin(it.s("id")) }
            PluginManager.unloadPlugin(file(record).absolutePath)
        }
        RuntimeBridge.invalidateSourceCache()
    }

    fun remove(record: Installed) {
        unload(record)
        check(!file(record).exists() || file(record).delete()) { "Cannot remove package" }
        save(
            user.copy(
                installed = user.installed.filterNot { it.extension.key == record.extension.key }
            )
        )
        installedSources()
    }

    fun browse(source: Source, query: String, page: Int): Pair<List<Title>, Boolean> {
        val result =
            if (source.manga) {
                if (query.isBlank())
                    RuntimeBridge.aniyomiGetPopular(context, source.id, false, page)
                else RuntimeBridge.aniyomiSearch(context, source.id, false, query, page)
            } else {
                val provider =
                    APIHolder.apis.firstOrNull { it.name == source.id }
                        ?: error("Provider is no longer loaded")
                if (query.isBlank() && provider.hasMainPage)
                    kotlinx.coroutines.runBlocking {
                        val response =
                            provider.getMainPage(
                                page,
                                com.lagradost.cloudstream3.MainPageRequest(
                                    provider.mainPage.first().name,
                                    provider.mainPage.first().data,
                                    provider.mainPage.first().horizontalImages,
                                ),
                            )
                        mapOf(
                            "list" to
                                response
                                    ?.items
                                    ?.flatMap { it.list }
                                    ?.map {
                                        mapOf(
                                            "title" to it.name,
                                            "url" to it.url,
                                            "cover" to it.posterUrl,
                                        )
                                    },
                            "hasNextPage" to (response?.hasNext ?: false),
                        )
                    }
                else RuntimeBridge.csSearch(context, query, source.id, page)
            }
        val list =
            result.maps("list").map {
                Title(
                    it.s("title").ifBlank { it.s("name") },
                    it.s("url"),
                    it.s("thumbnail_url").ifBlank { it.s("cover") }.ifBlank { it.s("posterUrl") },
                    source = source,
                )
            }
        return list to (result["hasNextPage"] == true)
    }

    fun detail(title: Title): Detail {
        val source = title.source ?: error("Choose an installed source first")
        val result =
            if (source.manga)
                RuntimeBridge.aniyomiGetDetail(
                    context,
                    source.id,
                    false,
                    mapOf(
                        "title" to title.title,
                        "url" to title.url,
                        "thumbnail_url" to title.cover,
                    ),
                )
            else RuntimeBridge.csGetDetail(context, source.id, title.url)
        result["error"]?.let { error(it.toString()) }
        val media =
            title.copy(
                title = result.s("title").ifBlank { title.title },
                cover = result.s("cover").ifBlank { title.cover },
                description = result.s("description"),
                genres = (result["genre"] as? List<*>)?.map { it.toString() } ?: emptyList(),
            )
        return Detail(media, result.maps("episodes").map { Episode(it.s("name"), it.s("url"), it) })
    }

    fun pages(media: Title, episode: Episode): List<PageImage> {
        val source = media.source ?: error("Missing source")
        return RuntimeBridge.aniyomiGetPageList(
                context,
                source.id,
                false,
                episode.raw + mapOf("name" to episode.name, "url" to episode.url),
            )
            .map { PageImage(it.s("url"), it.stringMap("headers")) }
            .also { require(it.isNotEmpty()) { "No pages returned" } }
    }

    fun streams(media: Title, episode: Episode): List<Stream> {
        val source = media.source ?: error("Missing source")
        return RuntimeBridge.csGetVideoList(context, source.id, episode.url)
            .map {
                Stream(
                    it.s("url"),
                    it.s("title").ifBlank { it.s("quality") },
                    it.stringMap("headers"),
                    it.maps("subtitles").map { s -> Subtitle(s.s("file"), s.s("label")) },
                    it["isM3u8"] == true,
                )
            }
            .filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
            .also { require(it.isNotEmpty()) { "The provider returned no playable links" } }
    }

    fun editorial(manga: Boolean): List<Title> {
        val root =
            JsonParser.parseString(
                    context.assets.open("editorial.json").bufferedReader().readText()
                )
                .asJsonObject
        return root.getAsJsonArray(if (manga) "manga" else "anime").map {
            val o = it.asJsonObject
            val id = o["id"].asInt
            Title(
                o["title"].asString,
                "editorial:$id",
                "file:///android_asset/art/$id.jpg",
                o["description"].asString.replace(Regex("<[^>]+>"), ""),
                o["genres"].asJsonArray.map { it.asString },
                banner =
                    "file:///android_asset/art/" + o["banner"].asString.substringAfterLast('/'),
                score = o["score"].asString,
                editorialId = id,
            )
        }
    }

    fun export(): String = gson.toJson(user.copy(installed = emptyList()))

    fun restore(text: String) {
        require(text.length < 4_000_000)
        val tree = JsonParser.parseString(text)
        require(tree.isJsonObject && listOf("library", "history", "repos", "mode", "theme", "accent").all { tree.asJsonObject.has(it) && !tree.asJsonObject[it].isJsonNull }) { "Incomplete backup" }
        val restored = gson.fromJson(tree, UserState::class.java) ?: error("Invalid backup")
        validateState(restored)
        restored.repos.forEach {
            https(it.url)
            require(it.type in listOf("mihon", "cloudstream"))
        }
        save(restored.copy(installed = user.installed))
    }

    private fun validateState(state: UserState) {
        require(
            state.mode in listOf("anime", "manga") &&
                state.theme in listOf("dark", "light", "oled", "custom")
        ) {
            "Invalid backup appearance"
        }
        require(
            state.library != null &&
                state.history != null &&
                state.repos != null &&
                state.installed != null
        ) {
            "Incomplete backup"
        }
        fun source(s: Source) {
            require(
                s.id.isNotBlank() &&
                    s.name.isNotBlank() &&
                    s.lang.isNotBlank() &&
                    s.packageId.isNotBlank()
            )
            require(s.type in listOf("mihon", "cloudstream"))
        }
        fun title(t: Title) {
            require(
                t.title.isNotBlank() &&
                    t.url.isNotBlank() &&
                    t.cover != null &&
                    t.banner != null &&
                    t.description != null &&
                    t.score != null
            )
            t.genres.forEach { require(it != null) }
            t.source?.let { source(it) }
        }
        state.library.forEach { title(it) }
        state.history.forEach {
            title(it.media)
            require(
                it.episode.name.isNotBlank() &&
                    it.episode.url.isNotBlank() &&
                    it.episode.raw != null &&
                    it.position >= 0
            )
        }
        state.repos.forEach {
            require(it.name.isNotBlank() && it.signer != null)
            https(it.url)
            require(it.type in listOf("mihon", "cloudstream"))
        }
        state.installed.forEach {
            require(
                it.filename.matches(Regex("[A-Za-z0-9_.-]+")) &&
                    it.sha256.matches(Regex("[a-f0-9]{64}"))
            )
            require(it.extension.id.isNotBlank())
        }
    }

    private fun download(url: String, limit: Int): ByteArray {
        val safe = https(url)
        val normalized =
            safe.replace(
                "https://github.com/keiyoushi/extensions/raw/repo/",
                "https://raw.githubusercontent.com/keiyoushi/extensions/repo/",
            )
        return client
            .newCall(
                Request.Builder().url(normalized).header("User-Agent", "Yoru/0.2 Android").build()
            )
            .execute()
            .use { r ->
                require(r.isSuccessful) { "HTTP ${r.code}: ${URI(url).host}" }
                require(r.request.url.isHttps) { "Repository redirected to insecure HTTP" }
                r.body.byteStream().use { bounded(it, limit) }
            }
    }

    private fun unpack(bytes: ByteArray) =
        if (bytes.size >= 2 && bytes[0] == 31.toByte() && bytes[1] == 139.toByte())
            GZIPInputStream(bytes.inputStream()).use { bounded(it, 20 * 1024 * 1024) }
        else bytes

    companion object {
        fun https(url: String): String {
            val u = URI(url)
            require(u.scheme == "https" && !u.host.isNullOrBlank() && u.userInfo == null) {
                "Enter a valid HTTPS URL"
            }
            return u.toASCIIString()
        }

        fun sha(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }

        fun bounded(input: InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= limit) { "Download exceeds size limit" }
                output.write(buffer, 0, n)
            }
            return output.toByteArray()
        }

        private fun resolve(base: String, path: String) = https(URI(base).resolve(path).toString())
    }
}

fun Map<String, Any?>.s(key: String) = this[key]?.toString().orEmpty()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.maps(key: String) = this[key] as? List<Map<String, Any?>> ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.stringMap(key: String) =
    (this[key] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }
        ?: emptyMap()
