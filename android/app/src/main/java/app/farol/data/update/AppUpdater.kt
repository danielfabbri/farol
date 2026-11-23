package app.farol.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.farol.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Descrição da versão publicada em releases/version.json (gerada por scripts/release_app.py). */
@Serializable
data class ReleaseInfo(
    @SerialName("version_code") val versionCode: Int,
    @SerialName("version_name") val versionName: String,
    val apk: String,
    val sha256: String,
    val size: Long = 0,
    val notes: String = "",
    @SerialName("published_at") val publishedAt: String = "",
)

/**
 * Atualização do próprio app, sem loja: baixa o APK publicado no repositório (HTTPS),
 * confere o SHA-256 e entrega ao instalador do Android, que pede a confirmação do usuário.
 */
class AppUpdater(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Retorna a versão nova disponível, ou null se o app já está atualizado. */
    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val url = "${BuildConfig.DATA_BASE_URL}releases/version.json?t=${System.currentTimeMillis() / 1000}"
        val req = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val info = json.decodeFromString(ReleaseInfo.serializer(), resp.body?.string().orEmpty())
            if (info.versionCode > BuildConfig.VERSION_CODE) info else null
        }
    }

    suspend fun download(info: ReleaseInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "farol-${info.versionName}.apk")
        val url = "${BuildConfig.DATA_BASE_URL}${info.apk}?v=${info.versionCode}"
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("download vazio")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(info.sha256, ignoreCase = true)) {
            target.delete()
            throw IOException("arquivo corrompido (verificação SHA-256 falhou)")
        }
        target
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
