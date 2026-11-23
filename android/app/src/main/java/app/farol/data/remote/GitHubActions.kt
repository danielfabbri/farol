package app.farol.data.remote

import app.farol.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

@Serializable
data class WorkflowRun(
    val id: Long,
    val status: String,               // queued, in_progress, completed
    val conclusion: String? = null,   // success, failure, cancelled...
    @SerialName("created_at") val createdAt: String,
    @SerialName("html_url") val htmlUrl: String = "",
)

@Serializable
private data class RunsResponse(@SerialName("workflow_runs") val runs: List<WorkflowRun> = emptyList())

sealed interface DispatchResult {
    data object Ok : DispatchResult
    data class Error(val message: String) : DispatchResult
}

/**
 * Dispara a coleta no GitHub Actions ("Varrer agora") usando um token pessoal do próprio usuário.
 * O token fica apenas neste aparelho; o app nunca o envia a outro lugar além de api.github.com.
 */
class GitHubActions(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/actions/workflows/${BuildConfig.WORKFLOW_FILE}"

    private fun Request.Builder.auth(token: String) = this
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    suspend fun dispatch(token: String): DispatchResult = withContext(Dispatchers.IO) {
        val body = """{"ref":"main"}""".toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/dispatches").auth(token).post(body).build()
        try {
            http.newCall(req).execute().use { resp ->
                when (resp.code) {
                    204 -> DispatchResult.Ok
                    401 -> DispatchResult.Error("token inválido ou expirado")
                    403 -> DispatchResult.Error("o token não tem permissão de Actions (leitura e escrita) neste repositório")
                    404 -> DispatchResult.Error("repositório ou workflow não encontrado para este token")
                    422 -> DispatchResult.Error("o workflow não aceita execução manual")
                    else -> DispatchResult.Error("GitHub respondeu HTTP ${resp.code}")
                }
            }
        } catch (e: IOException) {
            DispatchResult.Error("sem conexão com a internet")
        }
    }

    /** Execução manual mais recente criada a partir de [since] (ou null se ainda não apareceu). */
    suspend fun latestManualRun(token: String, since: Instant): WorkflowRun? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/runs?event=workflow_dispatch&per_page=5").auth(token).get().build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val runs = json.decodeFromString(RunsResponse.serializer(), resp.body?.string().orEmpty()).runs
                runs.firstOrNull { runCatching { Instant.parse(it.createdAt) }.getOrNull()?.isAfter(since.minusSeconds(30)) == true }
            }
        } catch (e: Exception) {
            null
        }
    }
}
