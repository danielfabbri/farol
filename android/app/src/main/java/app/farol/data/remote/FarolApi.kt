package app.farol.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Arquivos JSON públicos servidos pelo GitHub Raw.
 * O cache HTTP do OkHttp envia If-None-Match (ETag) automaticamente; com "no-cache"
 * a resposta é revalidada no servidor e, se nada mudou, volta 304 sem baixar o corpo.
 */
interface FarolApi {
    /** [bust] diferente a cada atualização forçada contorna o cache da CDN do GitHub (~5 min). */
    @GET("data/latest.json")
    suspend fun latest(
        @Header("Cache-Control") cacheControl: String? = null,
        @Query("t") bust: Long? = null,
    ): Response<ResponseBody>

    @GET("data/trends/latest.json")
    suspend fun trends(
        @Header("Cache-Control") cacheControl: String? = null,
        @Query("t") bust: Long? = null,
    ): Response<ResponseBody>

    @GET("data/articles/{day}.json")
    suspend fun archiveDay(@Path("day") day: String): Response<ResponseBody>
}
