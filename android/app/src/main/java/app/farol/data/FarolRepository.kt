package app.farol.data

import android.content.res.AssetManager
import app.farol.data.local.CachedDocument
import app.farol.data.local.FarolDatabase
import app.farol.data.local.ReadArticle
import app.farol.data.local.SavedArticle
import app.farol.data.model.ArchiveDay
import app.farol.data.model.Article
import app.farol.data.model.FarolJson
import app.farol.data.model.LatestFeed
import app.farol.data.model.TrendsDoc
import app.farol.data.prefs.UserPreferences
import app.farol.data.remote.FarolApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Cache
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

sealed interface SyncResult {
    data object Updated : SyncResult
    data object NotModified : SyncResult
    data object Offline : SyncResult
    data object Demo : SyncResult
    data class Failed(val reason: String) : SyncResult
}

@OptIn(ExperimentalCoroutinesApi::class)
class FarolRepository(
    private val api: FarolApi,
    private val db: FarolDatabase,
    private val prefs: UserPreferences,
    private val assets: AssetManager,
    private val httpCache: Cache?,
) {
    private val mutex = Mutex()

    val latest: Flow<LatestFeed?> = prefs.demoMode.flatMapLatest { demo ->
        if (demo) flow { emit(readAsset(ASSET_LATEST)?.let { decodeOrNull<LatestFeed>(it) }) }
        else db.cacheDao().observe(KEY_LATEST).map { doc -> doc?.json?.let { decodeOrNull<LatestFeed>(it) } }
    }.flowOn(Dispatchers.Default)

    val trends: Flow<TrendsDoc?> = prefs.demoMode.flatMapLatest { demo ->
        if (demo) flow { emit(readAsset(ASSET_TRENDS)?.let { decodeOrNull<TrendsDoc>(it) }) }
        else db.cacheDao().observe(KEY_TRENDS).map { doc -> doc?.json?.let { decodeOrNull<TrendsDoc>(it) } }
    }.flowOn(Dispatchers.Default)

    val saved: Flow<List<SavedArticle>> = db.savedDao().observeAll()
    val readIds: Flow<Set<String>> = db.readDao().observeIds().map { it.toSet() }

    /** Baixa latest.json (e tendências). Usa ETag via cache do OkHttp; "force" revalida no servidor. */
    suspend fun refresh(force: Boolean, commitSha: String? = null): SyncResult = withContext(Dispatchers.IO) {
        if (prefs.demoMode.value) return@withContext SyncResult.Demo
        mutex.withLock {
            try {
                val resp = if (commitSha != null) {
                    api.latestAt("https://raw.githubusercontent.com/${app.farol.BuildConfig.GITHUB_REPO}/$commitSha/data/latest.json")
                } else {
                    api.latest(if (force) "no-cache" else null, if (force) System.currentTimeMillis() / 1000 else null)
                }
                if (!resp.isSuccessful) return@withLock SyncResult.Failed("HTTP ${resp.code()}")
                val body = resp.body()?.string() ?: return@withLock SyncResult.Failed("resposta vazia")
                FarolJson.decodeFromString<LatestFeed>(body) // valida antes de substituir o cache
                val network = resp.raw().networkResponse
                val notModified = network == null || network.code == 304
                val now = System.currentTimeMillis()
                val previous = db.cacheDao().get(KEY_LATEST)
                if (previous?.json != body) db.cacheDao().put(CachedDocument(KEY_LATEST, body, now))
                prefs.setLastSync(now)
                runCatching { refreshTrends(force) }
                db.readDao().prune(now - TimeUnit.DAYS.toMillis(14))
                if (notModified || previous?.json == body) SyncResult.NotModified else SyncResult.Updated
            } catch (e: IOException) {
                SyncResult.Offline
            } catch (e: SerializationException) {
                SyncResult.Failed("dados em formato inesperado")
            } catch (e: IllegalArgumentException) {
                SyncResult.Failed("dados em formato inesperado")
            }
        }
    }

    private suspend fun refreshTrends(force: Boolean) {
        val resp = api.trends(if (force) "no-cache" else null, if (force) System.currentTimeMillis() / 1000 else null)
        val body = resp.body()?.string() ?: return
        if (!resp.isSuccessful) return
        FarolJson.decodeFromString<TrendsDoc>(body)
        if (db.cacheDao().get(KEY_TRENDS)?.json != body) {
            db.cacheDao().put(CachedDocument(KEY_TRENDS, body, System.currentTimeMillis()))
        }
    }

    /** Arquivo histórico: baixado somente quando o usuário abre o dia. Dias passados ficam em cache. */
    suspend fun archiveDay(day: String): Result<List<Article>> = withContext(Dispatchers.IO) {
        if (prefs.demoMode.value) {
            val feed = readAsset(ASSET_LATEST)?.let { decodeOrNull<LatestFeed>(it) }
            return@withContext Result.success(feed?.articles?.filter { it.publishedOrCollected.startsWith(day) }.orEmpty())
        }
        val key = "day:$day"
        val cached = db.cacheDao().get(key)
        val isRecent = runCatching { LocalDate.parse(day) >= LocalDate.now(ZoneOffset.UTC).minusDays(1) }.getOrDefault(true)
        if (cached != null && !isRecent) {
            decodeOrNull<ArchiveDay>(cached.json)?.let { return@withContext Result.success(it.articles.visible()) }
        }
        try {
            val resp = api.archiveDay(day)
            val body = resp.body()?.string()
            if (!resp.isSuccessful || body == null) error("HTTP ${resp.code()}")
            val doc = FarolJson.decodeFromString<ArchiveDay>(body)
            db.cacheDao().put(CachedDocument(key, body, System.currentTimeMillis()))
            Result.success(doc.articles.visible())
        } catch (e: Exception) {
            cached?.let { decodeOrNull<ArchiveDay>(it.json) }?.let { Result.success(it.articles.visible()) }
                ?: Result.failure(e)
        }
    }

    suspend fun toggleSave(article: Article) = withContext(Dispatchers.IO) {
        val dao = db.savedDao()
        if (dao.get(article.id) != null) dao.delete(article.id)
        else dao.put(
            SavedArticle(
                id = article.id, title = article.displayTitle, sourceName = article.sourceName,
                savedAt = System.currentTimeMillis(), json = FarolJson.encodeToString(Article.serializer(), article),
            )
        )
    }

    suspend fun markRead(id: String) = withContext(Dispatchers.IO) {
        db.readDao().markRead(ReadArticle(id, System.currentTimeMillis()))
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        db.cacheDao().clear()
        db.readDao().clear()
        runCatching { httpCache?.evictAll() }
        prefs.setLastSync(0L)
    }

    private fun List<Article>.visible() = filterNot { it.offTopic }

    private fun readAsset(name: String): String? =
        runCatching { assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

    private inline fun <reified T> decodeOrNull(json: String): T? =
        runCatching { FarolJson.decodeFromString<T>(json) }.getOrNull()

    companion object {
        const val KEY_LATEST = "latest"
        const val KEY_TRENDS = "trends"
        const val ASSET_LATEST = "sample_latest.json"
        const val ASSET_TRENDS = "sample_trends.json"
    }
}
