package app.yoru

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppModel(application: Application) : AndroidViewModel(application) {
    val engine = Engine(application)
    var user by mutableStateOf(engine.user)
        private set

    var sources by mutableStateOf<List<Source>>(emptyList())
        private set

    var editorial by mutableStateOf(engine.editorial(user.mode == "manga"))
        private set

    var screen by mutableStateOf("home")
    var busy by mutableStateOf<String?>(null)
        private set

    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var source by mutableStateOf<Source?>(null)
        private set

    var results by mutableStateOf<List<Title>>(emptyList())
        private set

    var query by mutableStateOf("")
    var page by mutableIntStateOf(1)
        private set

    var hasMore by mutableStateOf(false)
        private set

    var detail by mutableStateOf<Detail?>(null)
    var reading by mutableStateOf<Reading?>(null)
    var streams by mutableStateOf<List<Stream>>(emptyList())
        private set

    var playback by mutableStateOf<Pair<Title, Episode>?>(null)
        private set

    var sourcePicker by mutableStateOf<Title?>(null)
    var available by mutableStateOf<List<Extension>>(emptyList())
        private set

    var pending by mutableStateOf<PendingInstall?>(null)
        private set

    var candidateRepo by mutableStateOf<Repo?>(null)
    var loadErrors by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        engine.initialize()
        task("Loading your sources") { refreshSources() }
    }

    private fun task(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } catch (e: LinkageError) {
                error = "Extension API mismatch: ${e.message}"
            } finally {
                busy = null
                user = engine.user
            }
        }
    }

    private suspend fun refreshSources() {
        sources = withContext(Dispatchers.IO) { engine.installedSources() }
        loadErrors = engine.loadErrors
    }

    private fun store(next: UserState) {
        engine.save(next)
        user = next
    }

    fun mode(manga: Boolean) {
        store(user.copy(mode = if (manga) "manga" else "anime"))
        editorial = engine.editorial(manga)
        detail = null
        source = null
        if (screen != "library") screen = "home"
    }

    fun theme(name: String) {
        store(user.copy(theme = name))
    }

    fun accent(value: Long) {
        store(user.copy(accent = value))
    }

    fun saveTitle(media: Title) {
        val exists = user.library.any { it.key == media.key }
        store(
            user.copy(
                library =
                    if (exists) user.library.filterNot { it.key == media.key }
                    else user.library + media
            )
        )
        notice = if (exists) "Removed from library" else "Saved to your library"
    }

    fun open(media: Title) {
        if (media.source == null) {
            sourcePicker = media
            return
        }
        task("Opening ${media.title}") {
            detail = withContext(Dispatchers.IO) { engine.detail(media) }
        }
    }

    fun browse(item: Source, text: String = "", more: Boolean = false) {
        task("Searching ${item.name}") {
            val next = if (more) page + 1 else 1
            val data = withContext(Dispatchers.IO) { engine.browse(item, text, next) }
            source = item
            query = text
            page = next
            results = if (more) results + data.first else data.first
            hasMore = data.second
            screen = "browse"
            sourcePicker = null
            detail = null
        }
    }

    fun sync(repo: Repo) {
        task("Refreshing ${repo.name}") {
            val (normalized, packages) = withContext(Dispatchers.IO) { engine.loadRepo(repo) }
            store(user.copy(repos = user.repos.map { if (it.url == repo.url) normalized else it }))
            available = packages
            screen = "extensions"
            notice = "${packages.size} extensions found"
        }
    }

    fun inspectRepo(url: String, type: String) {
        task("Reading repository") {
            val (repo, _) =
                withContext(Dispatchers.IO) {
                    engine.loadRepo(Repo("Repository", Engine.https(url.trim()), type))
                }
            require(user.repos.none { it.url == repo.url }) { "Repository is already added" }
            candidateRepo = repo
        }
    }

    fun addRepo() {
        val repo = candidateRepo ?: return
        store(user.copy(repos = user.repos + repo))
        candidateRepo = null
        sync(repo)
    }

    fun removeRepo(repo: Repo) {
        store(user.copy(repos = user.repos.filterNot { it.url == repo.url }))
        available = available.filterNot { it.repo == repo.url }
    }

    fun prepare(entry: Extension) {
        task("Verifying ${entry.name}") {
            pending = withContext(Dispatchers.IO) { engine.prepare(entry) }
        }
    }

    fun discard() {
        pending?.let(engine::cancel)
        pending = null
    }

    fun install() {
        val candidate = pending ?: return
        pending = null
        task("Installing ${candidate.entry.name}") {
            withContext(Dispatchers.IO) { engine.install(candidate) }
            user = engine.user
            refreshSources()
            notice = "${candidate.entry.name} is ready"
        }
    }

    fun remove(record: Installed) {
        task("Removing ${record.extension.name}") {
            withContext(Dispatchers.IO) { engine.remove(record) }
            refreshSources()
            notice = "Extension removed"
        }
    }

    fun reload() {
        task("Reloading sources") { refreshSources() }
    }

    fun playOrRead(media: Title, episode: Episode) {
        task(if (media.source?.manga == true) "Loading pages" else "Resolving streams") {
            if (media.source?.manga == true) {
                val pages = withContext(Dispatchers.IO) { engine.pages(media, episode) }
                val start =
                    user.history
                        .firstOrNull { it.key == "${media.key}:${episode.url}" }
                        ?.position
                        ?.toInt() ?: 0
                reading = Reading(media, episode, pages, start.coerceIn(0, pages.lastIndex))
            } else {
                streams = withContext(Dispatchers.IO) { engine.streams(media, episode) }
                playback = media to episode
            }
        }
    }

    fun dismissStreams() {
        playback = null
        streams = emptyList()
    }

    fun startStream(context: Context, stream: Stream) {
        val pair = playback ?: return
        val progress =
            user.history.firstOrNull { it.key == "${pair.first.key}:${pair.second.url}" }?.position
                ?: 0
        record(pair.first, pair.second, progress, false)
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra("stream", Gson().toJson(stream))
                .putExtra("title", pair.first.title + " · " + pair.second.label(false))
                .putExtra("progressKey", "${pair.first.key}:${pair.second.url}")
                .putExtra("position", progress)
        )
        dismissStreams()
    }

    fun record(media: Title, episode: Episode, position: Long, reader: Boolean) {
        val item = Progress(media, episode, position, reader)
        store(
            user.copy(
                history = (listOf(item) + user.history.filterNot { it.key == item.key }).take(150)
            )
        )
    }

    fun reloadPlaybackProgress() {
        val prefs =
            getApplication<Application>()
                .getSharedPreferences("player_progress", Context.MODE_PRIVATE)
        val updated =
            user.history.map { h ->
                if (h.reader) h else h.copy(position = prefs.getLong(h.key, h.position))
            }
        if (updated != user.history) store(user.copy(history = updated))
    }

    fun export() = engine.export()

    fun restore(text: String) {
        task("Restoring your library") {
            withContext(Dispatchers.IO) { engine.restore(text) }
            user = engine.user
            editorial = engine.editorial(user.mode == "manga")
            notice = "Library and settings restored"
        }
    }
}
