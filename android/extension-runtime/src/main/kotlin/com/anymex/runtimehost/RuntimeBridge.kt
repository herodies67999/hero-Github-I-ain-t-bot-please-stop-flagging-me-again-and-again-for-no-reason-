package com.anymex.runtimehost

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.util.Log
import com.anymex.runtimehost.aniyomi.AnimeSourceMethods
import com.anymex.runtimehost.aniyomi.AniyomiExtensionManager
import com.anymex.runtimehost.aniyomi.AniyomiSourceMethods
import com.anymex.runtimehost.aniyomi.MangaSourceMethods
import com.anymex.runtimehost.network.MangaImageProxy
import java.lang.ref.WeakReference
import java.net.URLEncoder

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import eu.kanade.tachiyomi.network.normalizeUrl
import okhttp3.HttpUrl.Companion.toHttpUrl


@Suppress("unused")
object RuntimeBridge {

    private const val TAG = "RuntimeBridge"
    private var extensionManager: AniyomiExtensionManager? = null
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aniyomiPathRegistry = mutableMapOf<String, String>() 
    private val aniyomiIsAnimeRegistry = mutableMapOf<String, Boolean>() 
    private val activeRequests = mutableMapOf<String, Job>() 

    private var lastKnownActivity: WeakReference<Any>? = null 

    data class ProviderMetadata(
        val id: String,
        val name: String,
        val mainUrl: String,
        val lang: String,
        val sourcePlugin: String?,
        val iconUrl: String?,
        val internalName: String?,
        val hasSettings: Boolean = false
    )

    @JvmOverloads
    fun initialize(context: Context, settingsMap: Map<String, Any?>? = null) {
        if (initialized) return

        Log.i(TAG, "Initializing Runtime Host - Version: 1.0.6")

        try {
            Injekt.addSingletonFactory<android.app.Application> {
                context.applicationContext as android.app.Application
            }
            eu.kanade.tachiyomi.network.NetworkHelper.initUserAgent(context.applicationContext)
            Injekt.addSingletonFactory { NetworkHelper(context.applicationContext) }
            Injekt.addSingletonFactory { Injekt.get<NetworkHelper>().client }
            Injekt.addSingletonFactory {
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            }


            try {
                okhttp3.OkHttp.initialize(context.applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to initialize OkHttp platform context manually", e)
            }

            HostContext.context = context.applicationContext
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(a: Activity) { if (a is androidx.appcompat.app.AppCompatActivity) lastKnownActivity = WeakReference(a) }
                override fun onActivityDestroyed(a: Activity) { if (lastKnownActivity?.get() === a) lastKnownActivity = null }
                override fun onActivityCreated(a: Activity, b: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            })

            extensionManager = AniyomiExtensionManager(context.applicationContext)
            Injekt.addSingletonFactory<AniyomiExtensionManager> { extensionManager!! }

            val aniyomiExtensionsPath = settingsMap?.get("aniyomiExtensionsPath") as? String
            if (aniyomiExtensionsPath != null) {
                Log.i(TAG, "Proactive Loading: Starting Aniyomi extension scan at $aniyomiExtensionsPath")
                scope.launch {
                    getInstalledAnimeExtensions(context.applicationContext, aniyomiExtensionsPath)
                    getInstalledMangaExtensions(context.applicationContext)
                    Log.i(TAG, "Proactive Loading: Aniyomi background scan completed")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Init Step 2 Failed (Skipping Critical Setup): ${e.message}")
        }

        Log.i(TAG, "Runtime Host Initialized successfully")
        try {
            val cacheDir = File(context.cacheDir, "manga_pages_cache")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
            }
        } catch (_: Exception) {}
        initialized = true
    }

    fun cancelRequest(token: String): Boolean {
        val job = activeRequests[token] ?: return false
        Log.i(TAG, "Cancelling network request for token: $token")
        job.cancel()
        activeRequests.remove(token)
        return true
    }

    fun shutdown() {
        Log.i(TAG, "Shutting down Runtime Host...")
        try {
            initialized = false
            activeRequests.values.forEach { it.cancel() }
            activeRequests.clear()

            try {
                MangaImageProxy.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MangaImageProxy: ${e.message}")
            }

            aniyomiPathRegistry.clear()
            aniyomiIsAnimeRegistry.clear()
            Log.i(TAG, "Runtime Host shutdown complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown: ${e.message}")
        }
    }

    fun getInstalledAnimeExtensions(context: Context, path: String? = null): List<Map<String, Any?>> {
        val extensions = extensionManager(context).fetchInstalledAnimeExtensions(path)
        
        extensions.forEach { ext ->
            ext.sources.forEach { source ->
                aniyomiPathRegistry[source.id.toString()] = path ?: ""
                aniyomiIsAnimeRegistry[source.id.toString()] = true
            }
        }

        return extensions.flatMap { ext ->
            ext.sources.map { source ->
                val baseUrl = (source as? AnimeHttpSource)?.baseUrl.orEmpty()
                val supportsLatest = (source as? eu.kanade.tachiyomi.animesource.AnimeCatalogueSource)?.supportsLatest ?: false
                val supportsPopular = source is eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
                mapOf(
                    "id" to source.id.toString(),
                    "name" to source.name,
                    "baseUrl" to baseUrl,
                    "lang" to source.lang,
                    "isNsfw" to ext.isNsfw,
                    "iconUrl" to ext.iconUrl,
                    "version" to ext.versionName,
                    "pkgName" to ext.pkgName,
                    "itemType" to 1,
                    "hasUpdate" to ext.hasUpdate,
                    "isObsolete" to ext.isObsolete,
                    "isShared" to ext.isShared,
                    "isPrivate" to (!ext.isShared),
                    "supportsLatest" to supportsLatest,
                    "supportsPopular" to supportsPopular,
                )
            }
        }
    }

    fun invalidateSourceCache() { sourceMethodsCache.clear() }

    fun getInstalledMangaExtensions(context: Context, path: String? = null): List<Map<String, Any?>> {
        val extensions = extensionManager(context).fetchInstalledMangaExtensions(path)
        
        extensions.forEach { ext ->
            ext.sources.forEach { source ->
                aniyomiPathRegistry[source.id.toString()] = path ?: "" 
                aniyomiIsAnimeRegistry[source.id.toString()] = false
            }
        }

        return extensions.flatMap { ext ->
            ext.sources.map { source ->
                val baseUrl = (source as? HttpSource)?.baseUrl.orEmpty()
                val supportsLatest = (source as? eu.kanade.tachiyomi.source.CatalogueSource)?.supportsLatest ?: false
                val supportsPopular = source is eu.kanade.tachiyomi.source.CatalogueSource
                mapOf(
                    "id" to source.id.toString(),
                    "name" to source.name,
                    "baseUrl" to baseUrl,
                    "lang" to source.lang,
                    "isNsfw" to ext.isNsfw,
                    "iconUrl" to ext.iconUrl,
                    "version" to ext.versionName,
                    "pkgName" to ext.pkgName,
                    "itemType" to 0,
                    "hasUpdate" to ext.hasUpdate,
                    "isObsolete" to ext.isObsolete,
                    "isShared" to ext.isShared,
                    "isPrivate" to (!ext.isShared),
                    "supportsLatest" to supportsLatest,
                    "supportsPopular" to supportsPopular,
                )
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun aniyomiGetPopular(
        context: Context,
        sourceId: String,
        isAnime: Boolean,
        page: Int,
        parameters: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val token = parameters?.get("token") as? String
        val job = scope.async {
            val m = media(context, sourceId, isAnime)
            m.parameters = parameters
            val res = m.getPopular(page)
            mapOf("list" to res.animes.map { it.toMap() }, "hasNextPage" to res.hasNextPage)
        }
        if (token != null) activeRequests[token] = job
        return try {
            runBlocking { job.await() }
        } finally {
            if (token != null) activeRequests.remove(token)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun aniyomiGetLatestUpdates(
        context: Context,
        sourceId: String,
        isAnime: Boolean,
        page: Int,
        parameters: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val token = parameters?.get("token") as? String
        val job = scope.async {
            val m = media(context, sourceId, isAnime)
            m.parameters = parameters
            val res = m.getLatestUpdates(page)
            mapOf("list" to res.animes.map { it.toMap() }, "hasNextPage" to res.hasNextPage)
        }
        if (token != null) activeRequests[token] = job
        return try {
            runBlocking { job.await() }
        } finally {
            if (token != null) activeRequests.remove(token)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun aniyomiSearch(
        context: Context,
        sourceId: String,
        isAnime: Boolean,
        query: String,
        page: Int,
        filters: List<Any?>? = null,
        parameters: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val token = parameters?.get("token") as? String
        val job = scope.async {
            val m = media(context, sourceId, isAnime)
            val combinedParams = parameters?.toMutableMap() ?: mutableMapOf()
            if (filters != null) {
                combinedParams["filters"] = filters
            }
            m.parameters = combinedParams
            val res = m.getSearchResults(query, page)
            mapOf("list" to res.animes.map { it.toMap() }, "hasNextPage" to res.hasNextPage)
        }
        if (token != null) activeRequests[token] = job
        return try {
            runBlocking { job.await() }
        } finally {
            if (token != null) activeRequests.remove(token)
        }
    }

    @JvmStatic
    fun aniyomiGetFilterList(
        context: Context,
        sourceId: String,
        isAnime: Boolean
    ): List<Map<String, Any?>> {
        val m = media(context, sourceId, isAnime)
        return if (isAnime) {
            val source = (m as? com.anymex.runtimehost.aniyomi.AnimeSourceMethods)?.source ?: return emptyList()
            val filterList = try {
                source.getFilterList()
            } catch (e: Throwable) {
                Log.e("RuntimeBridge", "Error in getFilterList (Anime) for source $sourceId: ${e.message}", e)
                return emptyList()
            }
            filterList.list.map { serializeAnimeFilter(it) }
        } else {
            val source = (m as? com.anymex.runtimehost.aniyomi.MangaSourceMethods)?.source ?: return emptyList()
            val filterList = try {
                source.getFilterList()
            } catch (e: Throwable) {
                Log.e("RuntimeBridge", "Error in getFilterList (Manga) for source $sourceId: ${e.message}", e)
                return emptyList()
            }
            filterList.list.map { serializeMangaFilter(it) }
        }
    }

    private fun serializeAnimeFilter(filter: eu.kanade.tachiyomi.animesource.model.AnimeFilter<*>): Map<String, Any?> {
        val type = when (filter) {
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Header -> "Header"
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Separator -> "Separator"
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.CheckBox -> "CheckBox"
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.TriState -> "TriState"
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Select<*> -> "Select"
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Group<*> -> "Group"
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Sort -> "Sort"
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Text -> "Text"
            else -> "Unknown"
        }

        val state: Any? = when (filter) {
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Group<*> -> {
                filter.state.map { serializeAnimeFilter(it as eu.kanade.tachiyomi.animesource.model.AnimeFilter<*>) }
            }
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Sort -> {
                mapOf("index" to filter.state?.index, "ascending" to filter.state?.ascending)
            }
            else -> filter.state
        }

        val values: List<String>? = when (filter) {
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Select<*> -> {
                filter.values.map { it.toString() }
            }
            is eu.kanade.tachiyomi.animesource.model.AnimeFilter.Sort -> {
                filter.values.toList()
            }
            else -> null
        }

        return mapOf(
            "name" to filter.name,
            "type" to type,
            "state" to state,
            "values" to values
        )
    }

    private fun serializeMangaFilter(filter: eu.kanade.tachiyomi.source.model.Filter<*>): Map<String, Any?> {
        val type = when (filter) {
            is eu.kanade.tachiyomi.source.model.Filter.Header -> "Header"
            is eu.kanade.tachiyomi.source.model.Filter.Separator -> "Separator"
            is eu.kanade.tachiyomi.source.model.Filter.CheckBox -> "CheckBox"
            is eu.kanade.tachiyomi.source.model.Filter.TriState -> "TriState"
            is eu.kanade.tachiyomi.source.model.Filter.Select<*> -> "Select"
            is eu.kanade.tachiyomi.source.model.Filter.Group<*> -> "Group"
            is eu.kanade.tachiyomi.source.model.Filter.Sort -> "Sort"
            is eu.kanade.tachiyomi.source.model.Filter.Text -> "Text"
            else -> "Unknown"
        }

        val state: Any? = when (filter) {
            is eu.kanade.tachiyomi.source.model.Filter.Group<*> -> {
                filter.state.map { serializeMangaFilter(it as eu.kanade.tachiyomi.source.model.Filter<*>) }
            }
            is eu.kanade.tachiyomi.source.model.Filter.Sort -> {
                mapOf("index" to filter.state?.index, "ascending" to filter.state?.ascending)
            }
            else -> filter.state
        }

        val values: List<String>? = when (filter) {
            is eu.kanade.tachiyomi.source.model.Filter.Select<*> -> {
                filter.values.map { it.toString() }
            }
            is eu.kanade.tachiyomi.source.model.Filter.Sort -> {
                filter.values.toList()
            }
            else -> null
        }

        return mapOf(
            "name" to filter.name,
            "type" to type,
            "state" to state,
            "values" to values
        )
    }

    @JvmStatic
    @JvmOverloads
    fun aniyomiGetDetail(
        context: Context,
        sourceId: String,
        isAnime: Boolean,
        mediaMap: Map<String, Any?>,
        parameters: Map<String, Any?>? = null,
    ): Map<String, Any?> {
        val token = parameters?.get("token") as? String
        val job = scope.async {
            val anime = SAnime.create().apply {
                title = mediaMap["title"] as? String ?: ""
                url = mediaMap["url"] as? String ?: ""
                thumbnail_url = (mediaMap["thumbnail_url"] as? String)?.takeIf { it.isNotBlank() }?.normalizeUrl()
                description = mediaMap["description"] as? String
                artist = mediaMap["artist"] as? String
                author = mediaMap["author"] as? String
                genre = mediaMap["genre"] as? String
            }
            val m = media(context, sourceId, isAnime)
            m.parameters = parameters
            
            val details = m.getDetails(anime)
            val eps = if (isAnime) m.getEpisodeList(anime) else m.getChapterList(anime)

            mapOf(
                "title" to details.title,
                "url" to anime.url,
                "cover" to (details.thumbnail_url ?: anime.thumbnail_url),
                "artist" to details.artist,
                "author" to details.author,
                "description" to details.description,
                "genre" to details.getGenres(),
                "status" to details.status,
                "episodes" to eps.map {
                    val parsed = parseEpisodeInfoFromName(it.name)
                    val seasonNum = parsed.second
                    val epNum = if (it.episode_number != -1f) {
                        it.episode_number
                    } else if (parsed.first >= 0f) {
                        parsed.first
                    } else if (it.name.contains("oneshot", ignoreCase = true)) {
                        1f
                    } else {
                        -1f
                    }
                    
                    mapOf(
                        "name" to it.name,
                        "url" to it.url,
                        "date_upload" to it.date_upload,
                        "episode_number" to epNum,
                        "season" to seasonNum,
                        "scanlator" to it.scanlator,
                        "description" to it.summary,
                        "memo" to it.summary
                    )
                }
            )
        }
        if (token != null) activeRequests[token] = job
        return try {
            runBlocking { job.await() }
        } finally {
            if (token != null) activeRequests.remove(token)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun aniyomiGetVideoList(
        context: Context,
        sourceId: String,
        isAnime: Boolean,
        episodeMap: Map<String, Any?>,
        parameters: Map<String, Any?>? = null,
    ): List<Map<String, Any?>> {
        val token = parameters?.get("token") as? String
        val job = scope.async {
            val ep = SEpisode.create().apply {
                name = episodeMap["name"] as? String ?: ""
                url = episodeMap["url"] as? String ?: ""
                episode_number = (episodeMap["episode_number"] as? Number)?.toFloat() ?: 0f
                scanlator = episodeMap["scanlator"] as? String
            }
            val m = media(context, sourceId, isAnime)
            m.parameters = parameters
            m.getVideoList(ep).map { video ->
                val http = m.getHttpSource() as? AnimeHttpSource
                if (video.videoUrl.isBlank() || video.videoUrl == "null") {
                    requireNotNull(http) { "Source returned an unresolved video" }
                    video.videoUrl = http.getVideoUrl(video)
                }
                videoToMap(video.copy(headers = video.headers ?: http?.headers))
            }
        }
        if (token != null) activeRequests[token] = job
        return try {
            runBlocking { job.await() }
        } finally {
            if (token != null) activeRequests.remove(token)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun aniyomiStopHttpServer(
        context: Context,
        sourceId: String,
        isAnime: Boolean,
    ): Boolean {
        if (!isAnime) return false
        return try {
            val m = media(context, sourceId, isAnime)
            val httpSource = m.getHttpSource() as? AnimeHttpSource ?: return false
            val server = httpSource.server ?: return false
            if (server.isRunning()) {
                server.stop()
                Log.d("RuntimeBridge", "Successfully stopped HTTP server for source: $sourceId")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("RuntimeBridge", "Failed to stop HTTP server for source: $sourceId", e)
            false
        }
    }

    @JvmStatic
    @JvmOverloads
    fun aniyomiGetImageBytes(
        context: Context,
        sourceId: String,
        isAnime: Boolean,
        url: String,
    ): ByteArray? {
        return try {
            val m = media(context, sourceId, isAnime)
            val httpSource = m.getHttpSource()
            val client = when (httpSource) {
                is HttpSource -> httpSource.client
                is AnimeHttpSource -> httpSource.client
                else -> Injekt.get<NetworkHelper>().client
            }
            val headers = when (httpSource) {
                is HttpSource -> httpSource.headers
                is AnimeHttpSource -> httpSource.headers
                else -> okhttp3.Headers.Builder().build()
            }
            val request = okhttp3.Request.Builder()
                .url(url)
                .headers(headers)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else {
                Log.e("RuntimeBridge", "Failed to download image: HTTP ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e("RuntimeBridge", "Failed to get image bytes for url $url", e)
            null
        }
    }

    @JvmStatic
    @JvmOverloads
    fun aniyomiGetPageList(
        context: Context,
        sourceId: String,
        isAnime: Boolean,
        chapterMap: Map<String, Any?>,
        parameters: Map<String, Any?>? = null,
    ): List<Map<String, Any?>> {
        val token = parameters?.get("token") as? String
        val job = scope.async {
            val chapter = SChapter.create().apply {
                name = chapterMap["name"] as? String ?: ""
                url = chapterMap["url"] as? String ?: ""
                val rawMemo = (chapterMap["memo"] as? String)
                    ?: (chapterMap["description"] as? String)
                    ?: (chapterMap["extra"] as? String)
                if (!rawMemo.isNullOrBlank()) {
                    try {
                        memo = Json.parseToJsonElement(rawMemo) as? JsonObject
                    } catch (_: Exception) {}
                }
            }
            val m = media(context, sourceId, isAnime)
            m.parameters = parameters
            
            val pages = m.getPageList(chapter)
            val httpSource = m.getHttpSource() as? HttpSource

            val sourceName = httpSource?.name?.lowercase() ?: ""
            val sourceClassName = httpSource?.javaClass?.name?.lowercase() ?: ""
            val isBypassed = sourceName.contains("mangadex") ||
                    sourceClassName.contains("mangadex") ||
                    sourceId.equals("mangadex", ignoreCase = true)
            val isWhitelisted = sourceName.contains("mangafire") ||
                    sourceClassName.contains("mangafire") ||
                    sourceId.equals("mangafire", ignoreCase = true)

            val overridesClient = try {
                val networkHelper = uy.kohesive.injekt.Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>()
                httpSource?.client !== networkHelper.client
            } catch (e: Exception) {
                false
            }

            val hasCustomGetImage = if (httpSource != null) {
                try {
                    val method = httpSource.javaClass.getMethod("getImage", eu.kanade.tachiyomi.source.model.Page::class.java, kotlin.coroutines.Continuation::class.java)
                    method.declaringClass != HttpSource::class.java
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }

            val useProxy = pages.any { it.imageUrl.isNullOrBlank() } || (!isBypassed && (isWhitelisted || hasCustomGetImage || overridesClient))
            Log.i("RuntimeBridge", "Source '${sourceId}': isBypassed=$isBypassed, isWhitelisted=$isWhitelisted, hasCustomGetImage=$hasCustomGetImage, overridesClient=$overridesClient -> useProxy=$useProxy")
            val proxyPort = if (useProxy) MangaImageProxy.start() else 0

            pages.map { page ->
                val imageUrl = try {
                    if (httpSource != null) {
                        httpSource.imageRequest(page).url.toString()
                    } else {
                        page.imageUrl ?: ""
                    }
                } catch (e: Exception) {
                    Log.e("RuntimeBridge", "Error getting imageRequest URL: ${e.message}", e)
                    page.imageUrl ?: ""
                }
                
                if (useProxy && proxyPort > 0) {
                    val proxyUrl = if (imageUrl.isNotEmpty() || page.url.isNotEmpty()) {
                        "http://127.0.0.1:$proxyPort/image?token=${MangaImageProxy.accessToken}&sourceId=${java.net.URLEncoder.encode(sourceId, "UTF-8")}&imageUrl=${java.net.URLEncoder.encode(imageUrl, "UTF-8")}&pageUrl=${java.net.URLEncoder.encode(page.url ?: "", "UTF-8")}&pageNumber=${page.index}"
                    } else {
                        imageUrl
                    }
                    mapOf("url" to proxyUrl, "headers" to emptyMap<String, String>())
                } else {
                    val headersMap = try {
                        if (httpSource != null) {
                            val reqHeaders = httpSource.imageRequest(page).headers
                            val map = mutableMapOf<String, String>()
                            for (i in 0 until reqHeaders.size) {
                                map[reqHeaders.name(i)] = reqHeaders.value(i)
                            }
                            map
                        } else {
                            emptyMap()
                        }
                    } catch (e: Exception) {
                        Log.e("RuntimeBridge", "Error getting imageRequest headers: ${e.message}", e)
                        emptyMap()
                    }
                    mapOf("url" to imageUrl, "headers" to headersMap)
                }
            }
        }
        if (token != null) activeRequests[token] = job
        return try {
            runBlocking { job.await() }
        } finally {
            if (token != null) activeRequests.remove(token)
        }
    }

    private data class PrefHandlers(
        val pref: androidx.preference.Preference,
        val click: androidx.preference.Preference.OnPreferenceClickListener?,
        val change: androidx.preference.Preference.OnPreferenceChangeListener?
    )

    private val sourcePreferences = mutableMapOf<String, MutableMap<String, PrefHandlers>>()

    @android.annotation.SuppressLint("RestrictedApi")
    fun aniyomiGetPreference(
        context: Context,
        sourceId: String,
        isAnime: Boolean,
    ): List<Map<String, Any?>> = runBlocking {
        sourcePreferences.remove(sourceId)
        
        val prefManager = androidx.preference.PreferenceManager(context)
        prefManager.sharedPreferencesName = "source_$sourceId"
        
        val screen: androidx.preference.PreferenceScreen = try {
            prefManager.createPreferenceScreen(context)
        } catch (t: Throwable) {
            android.util.Log.w("RuntimeBridge", "createPreferenceScreen failed ($t), using reflection fallback")
            try {
                val constructor = androidx.preference.PreferenceScreen::class.java.getDeclaredConstructor(
                    Context::class.java,
                    android.util.AttributeSet::class.java
                )
                constructor.isAccessible = true
                val ps = constructor.newInstance(context, null)
                try {
                    val onAttached = androidx.preference.Preference::class.java.getDeclaredMethod(
                        "onAttachedToHierarchy",
                        androidx.preference.PreferenceManager::class.java
                    )
                    onAttached.isAccessible = true
                    onAttached.invoke(ps, prefManager)
                } catch (_: Throwable) {}
                ps
            } catch (t2: Throwable) {
                android.util.Log.e("RuntimeBridge", "Failed to construct PreferenceScreen: $t2", t2)
                return@runBlocking emptyList<Map<String, Any?>>()
            }
        }
        
        try {
            media(context, sourceId, isAnime).setupPreferenceScreen(screen)
        } catch (e: com.anymex.runtimehost.aniyomi.NoPreferenceScreenException) {
            return@runBlocking emptyList<Map<String, Any?>>()
        } catch (t: Throwable) {
            android.util.Log.w("RuntimeBridge", "setupPreferenceScreen failed for source $sourceId: $t")
            if (screen.preferenceCount == 0) {
                return@runBlocking emptyList<Map<String, Any?>>()
            }
        }

        val list = mutableListOf<Map<String, Any?>>()
        val store = sourcePreferences.getOrPut(sourceId) { mutableMapOf() }

        fun walk(group: androidx.preference.PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val p = group.getPreference(i)
                store[p.key] = PrefHandlers(p, p.onPreferenceClickListener, p.onPreferenceChangeListener)

                val map = mutableMapOf(
                    "key" to p.key,
                    "title" to p.title?.toString(),
                    "summary" to p.summary?.toString(),
                    "enabled" to p.isEnabled,
                    "type" to when (p) {
                        is androidx.preference.ListPreference -> "list"
                        is androidx.preference.MultiSelectListPreference -> "multi_select"
                        is androidx.preference.SwitchPreferenceCompat -> "switch"
                        is androidx.preference.EditTextPreference -> "text"
                        is androidx.preference.CheckBoxPreference -> "checkbox"
                        else -> "other"
                    },
                    "value" to when (p) {
                        is androidx.preference.ListPreference -> p.value
                        is androidx.preference.MultiSelectListPreference -> p.values.toList()
                        is androidx.preference.SwitchPreferenceCompat -> p.isChecked
                        is androidx.preference.EditTextPreference -> p.text
                        is androidx.preference.CheckBoxPreference -> p.isChecked
                        else -> null
                    }
                )

                if (p is androidx.preference.ListPreference) {
                    map["entries"] = p.entries?.map { it.toString() }
                    map["entryValues"] = p.entryValues?.map { it.toString() }
                } else if (p is androidx.preference.MultiSelectListPreference) {
                    map["entries"] = p.entries?.map { it.toString() }
                    map["entryValues"] = p.entryValues?.map { it.toString() }
                }

                list += map
                if (p is androidx.preference.PreferenceCategory) walk(p)
            }
        }

        walk(screen)
        list
    }

    fun aniyomiSavePreference(
        context: Context,
        sourceId: String,
        key: String,
        action: String?,
        value: Any?,
    ): Boolean = runBlocking(Dispatchers.Main) {
        val isAnime = aniyomiIsAnimeRegistry[sourceId] ?: true
        val handler = sourcePreferences[sourceId]?.get(key) ?: return@runBlocking false
        val pref = handler.pref
        
        val actualAction = action ?: "change"

        val actualValue = if (pref is androidx.preference.MultiSelectListPreference && value is List<*>) {
            value.filterIsInstance<String>().toSet()
        } else {
            value
        }

        if (actualAction == "click") {
            handler.click?.onPreferenceClick(pref)
        } else {
            if (handler.change?.onPreferenceChange(pref, actualValue) == false) return@runBlocking false
        }

        val prefs = context.getSharedPreferences("source_$sourceId", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        when (pref) {
            is androidx.preference.SwitchPreferenceCompat -> {
                val b = value as Boolean
                pref.isChecked = b
                editor.putBoolean(key, b)
            }
            is androidx.preference.ListPreference -> {
                val s = value as String
                pref.value = s
                editor.putString(key, s)
            }
            is androidx.preference.EditTextPreference -> {
                val s = value as String
                pref.text = s
                editor.putString(key, s)
            }
            is androidx.preference.MultiSelectListPreference -> {
                val newSet = when (value) {
                    is List<*> -> value.filterIsInstance<String>().toSet()
                    is Set<*> -> value.filterIsInstance<String>().toSet()
                    else -> emptySet()
                }
                pref.values = newSet.toMutableSet()
                editor.putStringSet(key, newSet)
            }
            is androidx.preference.CheckBoxPreference -> {
                val b = value as Boolean
                pref.isChecked = b
                editor.putBoolean(key, b)
            }
        }
        editor.apply()

        true
    }

    fun resolveAppCompatActivity(context: Context): androidx.appcompat.app.AppCompatActivity? {
        var current: Context? = context
        while (current != null) {
            if (current is androidx.appcompat.app.AppCompatActivity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return lastKnownActivity?.get() as? androidx.appcompat.app.AppCompatActivity
    }

    private fun extensionManager(context: Context): AniyomiExtensionManager {
        if (extensionManager == null) initialize(context)
        return extensionManager!!
    }

    private val sourceMethodsCache = java.util.concurrent.ConcurrentHashMap<String, AniyomiSourceMethods>()

    private fun media(context: Context, sourceId: String, isAnime: Boolean): AniyomiSourceMethods {
        val key = "$sourceId-$isAnime"
        var methods = sourceMethodsCache[key]
        if (methods == null) {
            methods = if (isAnime) AnimeSourceMethods(sourceId) else MangaSourceMethods(sourceId)
            sourceMethodsCache[key] = methods
        }
        return methods
    }

    private fun okhttp3.Headers?.toMap(): Map<String, String> = this?.names()?.associateWith { name -> get(name).orEmpty() }.orEmpty()

    private fun SAnime.toMap(): Map<String, Any?> = mapOf(
        "url" to url,
        "title" to title,
        "artist" to artist,
        "author" to author,
        "description" to description,
        "genre" to getGenres(),
        "status" to status,
        "thumbnail_url" to thumbnail_url,
        "background_url" to background_url,
        "update_strategy" to update_strategy.name,
        "fetch_type" to fetch_type.name,
        "season_number" to season_number,
        "initialized" to initialized
    )

    private fun videoToMap(it: Video): Map<String, Any?> = mapOf(
        "title" to it.videoTitle,
        "url" to it.videoUrl,
        "quality" to (it.videoTitle.takeIf { t -> t.isNotBlank() } ?: it.resolution?.let { "${it}p" } ?: "Default"),
        "resolution" to it.resolution,
        "bitrate" to it.bitrate,
        "headers" to it.headers?.toMap(),
        "preferred" to it.preferred,
        "subtitles" to it.subtitleTracks.map { t -> mapOf("file" to t.url, "label" to t.lang) },
        "audios" to it.audioTracks.map { t -> mapOf("file" to t.url, "label" to t.lang) },
        "timestamps" to it.timestamps.map { ts ->
            mapOf(
                "start" to ts.start,
                "end" to ts.end,
                "name" to ts.name,
                "type" to ts.type.name
            )
        },
        "mpvArgs" to it.mpvArgs.toMap(),
        "ffmpegStreamArgs" to it.ffmpegStreamArgs.toMap(),
        "ffmpegVideoArgs" to it.ffmpegVideoArgs.toMap(),
        "internalData" to it.internalData,
        "initialized" to it.initialized
    )

    private fun parseEpisodeInfoFromName(name: String): Pair<Float, Int?> {
        val seasonRegex = Regex("""(?i)\b(?:s|season)\s*(\d+)""")
        val seasonMatch = seasonRegex.find(name)
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()

        val cleanName = if (seasonMatch != null) name.replace(seasonMatch.value, "") else name

        val prefixRegex = Regex("""(?i)(?:chapter|ch\.|ch|ep\.|ep|episode|e)\s*(\d+(\.\d+)?)""")
        val prefixMatch = prefixRegex.find(cleanName)
        if (prefixMatch != null) {
            val epVal = prefixMatch.groupValues[1].toFloatOrNull() ?: -1f
            return Pair(epVal, season)
        }

        val fallbackRegex = Regex("""(\d+(\.\d+)?)""")
        val epVal = fallbackRegex.find(cleanName)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        return Pair(epVal, season)
    }
}
