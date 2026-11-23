package app.farol.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.farol.FarolApplication
import app.farol.data.FarolRepository
import app.farol.data.SyncResult
import app.farol.data.local.SavedArticle
import app.farol.data.model.Article
import app.farol.data.model.Event
import app.farol.data.model.FarolJson
import app.farol.data.model.LatestFeed
import app.farol.data.model.TrendsDoc
import app.farol.data.prefs.ThemeMode
import app.farol.data.prefs.UserPreferences
import app.farol.data.remote.DispatchResult
import app.farol.data.remote.GitHubActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val loaded: Boolean = false,
    val feed: LatestFeed? = null,
    val trends: TrendsDoc? = null,
    val saved: List<SavedArticle> = emptyList(),
    val savedArticles: Map<String, Article> = emptyMap(),
    val readIds: Set<String> = emptySet(),
    val syncing: Boolean = false,
    val lastResult: SyncResult? = null,
    val lastSync: Long = 0L,
    val theme: ThemeMode = ThemeMode.DARK,
    val highlightLimit: Int = 7,
    val otherLimit: Int = 15,
    val demoMode: Boolean = false,
    val hasToken: Boolean = false,
    val scan: ScanState = ScanState.Idle,
) {
    val articlesById: Map<String, Article> by lazy { feed?.articles.orEmpty().associateBy { it.id } }
    val eventsById: Map<String, Event> by lazy { feed?.events.orEmpty().associateBy { it.id } }
    val savedIds: Set<String> get() = savedArticles.keys
}

/** Varredura sob demanda: dispara a coleta no GitHub Actions e acompanha até a nova edição chegar. */
sealed interface ScanState {
    data object Idle : ScanState
    data object Starting : ScanState
    data class Running(val step: String, val startedAt: Long) : ScanState
    data class Done(val message: String) : ScanState
    data class Failed(val message: String) : ScanState
}

sealed interface ArchiveState {
    data object Loading : ArchiveState
    data class Loaded(val articles: List<Article>) : ArchiveState
    data class Error(val message: String) : ArchiveState
}

private data class DataPart(
    val feed: LatestFeed?, val trends: TrendsDoc?, val saved: List<SavedArticle>,
    val savedArticles: Map<String, Article>, val read: Set<String>,
)

private data class PrefsPart(val theme: ThemeMode, val hl: Int, val other: Int, val demo: Boolean, val lastSync: Long)

private data class SyncPart(val syncing: Boolean = false, val result: SyncResult? = null)

class MainViewModel(
    private val repo: FarolRepository,
    private val prefs: UserPreferences,
    private val github: GitHubActions,
) : ViewModel() {

    private val sync = MutableStateFlow(SyncPart())
    private val scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    private val syncAndScan = combine(sync, scanState, prefs.githubToken) { s, sc, t -> Triple(s, sc, t != null) }
    private val _archive = MutableStateFlow<Map<String, ArchiveState>>(emptyMap())
    val archive: StateFlow<Map<String, ArchiveState>> = _archive.asStateFlow()

    private val data = combine(repo.latest, repo.trends, repo.saved, repo.readIds) { feed, trends, saved, read ->
        val decoded = saved.mapNotNull { s ->
            runCatching { FarolJson.decodeFromString(Article.serializer(), s.json) }.getOrNull()?.let { s.id to it }
        }.toMap()
        DataPart(feed, trends, saved, decoded, read)
    }

    private val prefsFlow = combine(prefs.theme, prefs.highlightLimit, prefs.otherLimit, prefs.demoMode, prefs.lastSync) { t, h, o, d, l ->
        PrefsPart(t, h, o, d, l)
    }

    val state: StateFlow<UiState> = combine(data, prefsFlow, syncAndScan) { d, p, (s, scan, hasToken) ->
        UiState(
            loaded = true, feed = d.feed, trends = d.trends, saved = d.saved, savedArticles = d.savedArticles,
            readIds = d.read, syncing = s.syncing, lastResult = s.result, lastSync = p.lastSync,
            theme = p.theme, highlightLimit = p.hl, otherLimit = p.other, demoMode = p.demo,
            hasToken = hasToken, scan = scan,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    /**
     * "Varrer agora": pede ao GitHub Actions uma coleta imediata e acompanha a execução.
     * Leva de 2 a 6 minutos (coleta + IA). Ao terminar, baixa a nova edição.
     */
    fun scanNow() {
        val token = prefs.githubToken.value ?: run {
            scanState.value = ScanState.Failed("Configure o token do GitHub em Ajustes para varrer as fontes.")
            return
        }
        val current = scanState.value
        if (current is ScanState.Starting || current is ScanState.Running) return
        viewModelScope.launch {
            scanState.value = ScanState.Starting
            val since = Instant.now()
            when (val r = github.dispatch(token)) {
                is DispatchResult.Error -> { scanState.value = ScanState.Failed("Não foi possível iniciar: ${r.message}."); return@launch }
                DispatchResult.Ok -> Unit
            }
            val started = System.currentTimeMillis()
            scanState.value = ScanState.Running("Na fila do GitHub…", started)
            val deadline = started + 15 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(10_000)
                val run = github.latestManualRun(token, since) ?: continue
                when (run.status) {
                    "queued", "waiting", "pending", "requested" -> scanState.value = ScanState.Running("Na fila do GitHub…", started)
                    "in_progress" -> scanState.value = ScanState.Running("Coletando fontes e analisando com IA…", started)
                    "completed" -> {
                        if (run.conclusion == "success" || run.conclusion == "failure") {
                            // "failure" pode ser só uma fonte fora do ar: os dados coletados são publicados mesmo assim.
                            scanState.value = ScanState.Running("Baixando a nova edição…", started)
                            delay(5_000)
                            val res = repo.refresh(force = true)
                            scanState.value = when (res) {
                                SyncResult.Updated -> ScanState.Done("Varredura concluída. Nova edição carregada.")
                                SyncResult.NotModified -> ScanState.Done("Varredura concluída. Nenhuma novidade desde a última edição.")
                                else -> ScanState.Done("Varredura concluída. Puxe a tela para atualizar.")
                            }
                            sync.value = SyncPart(syncing = false, result = res)
                        } else {
                            scanState.value = ScanState.Failed("A varredura foi interrompida (${run.conclusion}).")
                        }
                        return@launch
                    }
                }
            }
            scanState.value = ScanState.Failed("A varredura está demorando mais que o normal. Tente atualizar em alguns minutos.")
        }
    }

    fun dismissScanMessage() {
        if (scanState.value is ScanState.Done || scanState.value is ScanState.Failed) scanState.value = ScanState.Idle
    }

    fun setGithubToken(token: String?) = prefs.setGithubToken(token)

    init {
        refresh(force = false) // atualização automática ao abrir o app
    }

    fun refresh(force: Boolean) {
        if (sync.value.syncing) return
        viewModelScope.launch {
            sync.update { it.copy(syncing = true) }
            val result = repo.refresh(force)
            sync.value = SyncPart(syncing = false, result = result)
        }
    }

    fun findArticle(id: String): Article? {
        val s = state.value
        s.articlesById[id]?.let { return it }
        s.savedArticles[id]?.let { return it }
        _archive.value.values.forEach { st ->
            if (st is ArchiveState.Loaded) st.articles.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    fun loadArchive(day: String) {
        if (_archive.value[day] is ArchiveState.Loaded) return
        _archive.update { it + (day to ArchiveState.Loading) }
        viewModelScope.launch {
            val result = repo.archiveDay(day)
            _archive.update {
                it + (day to result.fold(
                    onSuccess = { list -> ArchiveState.Loaded(list) },
                    onFailure = { ArchiveState.Error("Não foi possível carregar este dia. Verifique a conexão.") },
                ))
            }
        }
    }

    fun toggleSave(article: Article) = viewModelScope.launch { repo.toggleSave(article) }
    fun markRead(id: String) = viewModelScope.launch { repo.markRead(id) }
    fun setTheme(mode: ThemeMode) = prefs.setTheme(mode)
    fun setHighlightLimit(n: Int) = prefs.setHighlightLimit(n)
    fun setOtherLimit(n: Int) = prefs.setOtherLimit(n)

    fun setDemoMode(on: Boolean) {
        prefs.setDemoMode(on)
        _archive.value = emptyMap()
        if (!on) refresh(force = false)
    }

    fun clearCache() = viewModelScope.launch {
        repo.clearCache()
        _archive.value = emptyMap()
        sync.value = SyncPart()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as FarolApplication
                MainViewModel(app.container.repository, app.container.prefs, app.container.gitHubActions)
            }
        }
    }
}
