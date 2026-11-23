package app.farol

import android.app.Application
import android.content.Context
import app.farol.data.FarolRepository
import app.farol.data.local.FarolDatabase
import app.farol.data.prefs.UserPreferences
import app.farol.data.remote.FarolApi
import app.farol.sync.SyncWorker
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

/** Injeção de dependências manual: poucas peças, sem frameworks extras. */
class AppContainer(context: Context) {
    val prefs = UserPreferences(context)
    private val db = FarolDatabase.create(context)
    private val httpCache = Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024)
    private val okHttp = OkHttpClient.Builder()
        .cache(httpCache)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val api: FarolApi = Retrofit.Builder()
        .baseUrl(BuildConfig.DATA_BASE_URL)
        .client(okHttp)
        .build()
        .create(FarolApi::class.java)
    val repository = FarolRepository(api, db, prefs, context.assets, httpCache)
}

class FarolApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncWorker.schedule(this)
    }
}
