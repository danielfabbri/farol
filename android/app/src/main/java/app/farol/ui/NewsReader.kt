package app.farol.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Leitura em voz alta com o motor de voz do próprio aparelho (TextToSpeech do Android):
 * gratuito, sem serviço externo. Alterna uma voz masculina e uma feminina.
 *
 * O Android não informa o gênero das vozes; ele é deduzido pelo nome. Quando não dá para
 * saber (ou só existe uma voz em português), a diferença é feita pelo tom (grave / agudo).
 */
class NewsReader(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    private var maleVoice: Voice? = null
    private var femaleVoice: Voice? = null
    private var gendersKnown = false
    private var configured = false

    private val _available = MutableStateFlow(true)
    /** Falso quando o aparelho não tem motor de voz em português. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var pending: ((Boolean) -> Unit)? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(false)
        })
    }

    private fun finish(ok: Boolean) {
        val cb = pending
        pending = null
        cb?.invoke(ok)
    }

    private fun configure(): Boolean {
        if (configured) return true
        val ptBr = Locale("pt", "BR")
        val lang = tts.setLanguage(ptBr)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            _available.value = false
            return false
        }
        val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == "pt" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            // Português do Brasil e vozes do aparelho (sem internet) primeiro.
            .sortedWith(compareBy({ it.locale.country != "BR" }, { it.isNetworkConnectionRequired }, { it.name }))
        val male = voices.firstOrNull { genderOf(it) == Gender.MALE }
        val female = voices.firstOrNull { genderOf(it) == Gender.FEMALE }
        if (male != null && female != null) {
            maleVoice = male; femaleVoice = female; gendersKnown = true
        } else {
            // Gênero desconhecido: duas vozes diferentes, se houver, e o tom faz a diferença.
            val distinct = voices.distinctBy { it.name.substringBefore("-network").substringBefore("-local") }
            maleVoice = male ?: distinct.firstOrNull { it != female }
            femaleVoice = female ?: distinct.firstOrNull { it != maleVoice } ?: maleVoice
        }
        configured = true
        return true
    }

    /** Fala o texto e retorna quando terminar (true) ou for interrompido (false). */
    suspend fun speak(text: String, male: Boolean): Boolean {
        if (!ready.await() || !configure()) {
            _available.value = false
            return false
        }
        val voice = if (male) maleVoice else femaleVoice
        voice?.let { runCatching { tts.voice = it } }
        tts.setPitch(if (gendersKnown) 1.0f else if (male) 0.8f else 1.2f)
        tts.setSpeechRate(1.0f)
        return suspendCancellableCoroutine { cont ->
            pending = { ok -> if (cont.isActive) cont.resume(ok) }
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
            if (result != TextToSpeech.SUCCESS) finish(false)
            cont.invokeOnCancellation { pending = null; tts.stop() }
        }
    }

    fun stop() {
        pending = null
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private enum class Gender { MALE, FEMALE, UNKNOWN }

    private fun genderOf(v: Voice): Gender {
        val n = v.name.lowercase(Locale.ROOT)
        return when {
            "female" in n || "feminin" in n || "mulher" in n -> Gender.FEMALE
            "male" in n || "masculin" in n || "homem" in n -> Gender.MALE
            // Vozes do Google em português do Brasil (pt-br-x-XXX-local/network).
            listOf("-ptd-", "-ptb-").any { it in "$n-" } -> Gender.MALE
            listOf("-afs-", "-pte-", "-pta-").any { it in "$n-" } -> Gender.FEMALE
            else -> Gender.UNKNOWN
        }
    }
}
