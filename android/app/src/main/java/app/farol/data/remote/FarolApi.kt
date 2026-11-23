package app.farol.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Arquivos JSON públicos servidos pelo GitHub Raw.
 * O cache HTTP do OkHttp envia If-None-Match (ETag) automaticamente; com "no-cache"
 * a resposta é revalidada no servidor e, se nada mudou, volta 304 sem baixar o corpo.
 */
interface FarolApi {
    /**
     * Atenção: a CDN do GitHub Raw guarda o arquivo por ~5 min e ignora parâmetros de URL.
     * Para pegar uma edição recém-publicada sem esperar, use [latestAt] com o SHA do commit.
     */
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

    /** Edição de um commit específico (URL imutável: nunca vem desatualizada do cache). */
    @GET
    suspend fun latestAt(@Url url: String): Response<ResponseBody>

    @GET("data/articles/{day}.json")
    suspend fun archiveDay(@Path("day") day: String): Response<ResponseBody>
}
