package app.farol.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import app.farol.data.prefs.VoiceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Voz em português disponível no aparelho, com um nome legível. */
data class VoiceOption(val name: String, val label: String)

/**
 * Leitura em voz alta com o motor de voz do próprio aparelho (TextToSpeech do Android):
 * gratuito, sem serviço externo. Até dois "repórteres", cada um com voz, velocidade e tom
 * configuráveis em Ajustes → Vozes. Uma instância só para o app inteiro.
 *
 * O Android não informa o gênero das vozes; ele é deduzido pelo nome. Na escolha automática,
 * o Repórter 1 usa uma voz masculina e o Repórter 2 uma feminina, quando dá para saber.
 */
class NewsReader(context: Context, private val settings: () -> VoiceSettings) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    private var voices: List<Voice> = emptyList()
    private var maleVoice: Voice? = null
    private var femaleVoice: Voice? = null
    private var configured = false

    private val _available = MutableStateFlow(true)
    /** Falso quando o aparelho não tem motor de voz em português. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _speakingKey = MutableStateFlow<String?>(null)
    /** Identificador do que está sendo lido agora (id da notícia), ou nulo. */
    val speakingKey: StateFlow<String?> = _speakingKey.asStateFlow()

    private var pendingId: String? = null
    private var pending: ((Boolean) -> Unit)? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finish(utteranceId, true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId, false)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId, false)
        })
    }

    /** Conclui a fala em andamento; ignora avisos atrasados de falas anteriores. */
    @Synchronized
    private fun finish(utteranceId: String?, ok: Boolean) {
        if (utteranceId != null && utteranceId != pendingId) return
        val cb = pending
        pending = null
        pendingId = null
        cb?.invoke(ok)
    }

    private fun configure(): Boolean {
        if (configured) return true
        val lang = tts.setLanguage(Locale("pt", "BR"))
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            _available.value = false
            return false
        }
        voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == "pt" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            // Português do Brasil e vozes do aparelho (sem internet) primeiro.
            .sortedWith(compareBy({ it.locale.country != "BR" }, { it.isNetworkConnectionRequired }, { it.name }))
        val male = voices.firstOrNull { genderOf(it) == Gender.MALE }
        val female = voices.firstOrNull { genderOf(it) == Gender.FEMALE }
        val distinct = voices.distinctBy { baseName(it) }
        maleVoice = male ?: distinct.firstOrNull { it != female }
        femaleVoice = female ?: distinct.firstOrNull { it != maleVoice } ?: maleVoice
        configured = true
        return true
    }

    /** Vozes em português instaladas no aparelho (para a tela de Ajustes). */
    suspend fun voiceOptions(): List<VoiceOption> {
        if (!ready.await() || !configure()) return emptyList()
        val counters = mutableMapOf<String, Int>()
        return voices.map { v ->
            val kind = when (genderOf(v)) {
                Gender.MALE -> "Masculina"
                Gender.FEMALE -> "Feminina"
                Gender.UNKNOWN -> "Voz"
            }
            val n = (counters[kind] ?: 0) + 1
            counters[kind] = n
            val region = if (v.locale.country == "BR") "" else " (${v.locale.country.ifBlank { "PT" }})"
            val net = if (v.isNetworkConnectionRequired) " · internet" else ""
            VoiceOption(v.name, "$kind $n$region$net")
        }
    }

    /**
     * Fala o texto com a voz do repórter [reporter] (0 = Repórter 1, 1 = Repórter 2) e retorna quando
     * terminar (true) ou for interrompido (false). [key] identifica o que está sendo lido.
     */
    suspend fun speak(text: String, reporter: Int = 0, key: String? = null): Boolean {
        if (!ready.await() || !configure()) {
            _available.value = false
            return false
        }
        val s = settings()
        val idx = if (s.reporters == 2) reporter.coerceIn(0, 1) else 0
        val cfg = s.reporter(idx)
        val voice = resolve(cfg.voiceName, idx)
        voice?.let { runCatching { tts.voice = it } }
        // Os dois repórteres caíram na mesma voz com o mesmo tom: o tom faz a diferença.
        val other = s.reporter(1 - idx)
        val sameVoice = s.reporters == 2 && voice != null && resolve(other.voiceName, 1 - idx) == voice
        val pitch = if (sameVoice && cfg.pitch == other.pitch) (if (idx == 0) cfg.pitch * 0.85f else cfg.pitch * 1.15f) else cfg.pitch
        tts.setPitch(pitch)
        tts.setSpeechRate(cfg.rate)
        _speakingKey.value = key
        return try {
            suspendCancellableCoroutine { cont ->
                // Encerra a fala anterior (se houver) antes de começar esta.
                finish(null, false)
                val id = UUID.randomUUID().toString()
                synchronized(this) {
                    pendingId = id
                    pending = { ok -> if (cont.isActive) cont.resume(ok) }
                }
                val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (result != TextToSpeech.SUCCESS) finish(id, false)
                cont.invokeOnCancellation { finish(id, false); tts.stop() }
            }
        } finally {
            if (_speakingKey.value == key) _speakingKey.value = null
        }
    }

    private fun resolve(name: String?, reporter: Int): Voice? =
        name?.let { n -> voices.firstOrNull { it.name == n } } ?: if (reporter == 0) maleVoice else femaleVoice

    fun stop() {
        finish(null, false)
        tts.stop()
        _speakingKey.value = null
    }

    private enum class Gender { MALE, FEMALE, UNKNOWN }

    private fun baseName(v: Voice) = v.name.substringBefore("-network").substringBefore("-local")

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

/** Texto lido para uma notícia: título e resumo (ou trecho). */
fun spokenText(title: String, body: String?): String =
    listOfNotNull(title.trim().trimEnd('.'), body?.trim()?.takeIf { it.isNotEmpty() }).joinToString(". ")
