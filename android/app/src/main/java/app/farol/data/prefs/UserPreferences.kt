package app.farol.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Preferências simples (SharedPreferences) expostas como StateFlow. */
class UserPreferences(context: Context) {
    private val sp = context.getSharedPreferences("farol_prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(sp.getString(KEY_THEME, ThemeMode.DARK.name)!!) }.getOrDefault(ThemeMode.DARK)
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _highlightLimit = MutableStateFlow(sp.getInt(KEY_HIGHLIGHTS, 7))
    val highlightLimit: StateFlow<Int> = _highlightLimit.asStateFlow()

    private val _otherLimit = MutableStateFlow(sp.getInt(KEY_OTHERS, 30))
    val otherLimit: StateFlow<Int> = _otherLimit.asStateFlow()

    private val _demoMode = MutableStateFlow(sp.getBoolean(KEY_DEMO, false))
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    /** Token pessoal do GitHub (opcional), usado só pelo botão "Varrer agora". Fica apenas neste aparelho. */
    private val _githubToken = MutableStateFlow(sp.getString(KEY_TOKEN, null))
    val githubToken: StateFlow<String?> = _githubToken.asStateFlow()

    /** Filtro de leitura (Hoje e Explorar): Todas, Não lidas ou Lidas. */
    private val _readFilter = MutableStateFlow(
        runCatching { ReadFilter.valueOf(sp.getString(KEY_READ_FILTER, ReadFilter.ALL.name)!!) }.getOrDefault(ReadFilter.ALL)
    )
    val readFilter: StateFlow<ReadFilter> = _readFilter.asStateFlow()

    /** Acontecimentos dispensados com o gesto de arrastar (mais recentes por último; guardamos os últimos 2000). */
    private val _dismissed = MutableStateFlow(
        sp.getString(KEY_DISMISSED, "").orEmpty().split(',').filter { it.isNotBlank() }
    )
    val dismissed: StateFlow<List<String>> = _dismissed.asStateFlow()

    private val _lastSync = MutableStateFlow(sp.getLong(KEY_LAST_SYNC, 0L))
    val lastSync: StateFlow<Long> = _lastSync.asStateFlow()

    fun setTheme(mode: ThemeMode) { sp.edit().putString(KEY_THEME, mode.name).apply(); _theme.value = mode }
    fun setHighlightLimit(n: Int) { sp.edit().putInt(KEY_HIGHLIGHTS, n).apply(); _highlightLimit.value = n }
    fun setOtherLimit(n: Int) { sp.edit().putInt(KEY_OTHERS, n).apply(); _otherLimit.value = n }
    fun setDemoMode(on: Boolean) { sp.edit().putBoolean(KEY_DEMO, on).apply(); _demoMode.value = on }
    fun setGithubToken(token: String?) {
        val clean = token?.trim()?.takeIf { it.isNotEmpty() }
        sp.edit().apply { if (clean == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, clean) }.apply()
        _githubToken.value = clean
    }
    fun setReadFilter(f: ReadFilter) { sp.edit().putString(KEY_READ_FILTER, f.name).apply(); _readFilter.value = f }
    fun dismiss(eventId: String) {
        val list = (_dismissed.value - eventId + eventId).takeLast(2000)
        sp.edit().putString(KEY_DISMISSED, list.joinToString(",")).apply()
        _dismissed.value = list
    }
    /** Gostos aprendidos com o gesto de arrastar: traço da notícia (seção, tema, fonte…) → peso. */
    // Só palavras-chave de tema ("kw:"); pesos antigos por seção/fonte (versão 0.13.0) são descartados.
    private val _affinity = MutableStateFlow(decodeAffinity(sp.getString(KEY_AFFINITY, null)).filterKeys { it.startsWith("kw:") })
    val affinity: StateFlow<Map<String, Float>> = _affinity.asStateFlow()

    private val _reactions = MutableStateFlow(sp.getInt(KEY_REACTIONS, 0))
    val reactions: StateFlow<Int> = _reactions.asStateFlow()

    /** Reação dada a cada acontecimento (1 = gostei, -1 = não gostei), para a tela da notícia mostrar a mãozinha marcada. */
    private val _eventReactions = MutableStateFlow(decodeAffinity(sp.getString(KEY_EVENT_REACTIONS, null)).mapValues { it.value.toInt() })
    val eventReactions: StateFlow<Map<String, Int>> = _eventReactions.asStateFlow()

    /**
     * Gostei (1), não gostei (-1) ou sem opinião (0) para um acontecimento. Trocar de opinião desfaz a
     * anterior; os pesos antigos perdem um pouco de força a cada reação nova, para o feed acompanhar
     * mudanças de interesse.
     */
    fun setReaction(eventId: String, features: Map<String, Float>, value: Int) {
        val old = _eventReactions.value[eventId] ?: 0
        if (old == value) return
        val delta = (value - old).toFloat()
        val base = if (old == 0) _affinity.value.mapValues { it.value * 0.98f } else _affinity.value
        val next = base.toMutableMap()
        features.forEach { (k, w) -> next[k] = ((next[k] ?: 0f) + delta * w).coerceIn(-MAX_WEIGHT, MAX_WEIGHT) }
        val count = (_reactions.value + when { old == 0 -> 1; value == 0 -> -1; else -> 0 }).coerceAtLeast(0)
        val reactionsMap = (_eventReactions.value - eventId).let { if (value == 0) it else it + (eventId to value) }
            .entries.toList().takeLast(2000).associate { it.key to it.value }
        saveAffinity(next, count, reactionsMap)
    }

    /** Edição manual em Ajustes → Seus gostos: [weight] nulo apaga a palavra. */
    fun setTaste(key: String, weight: Float?) {
        val next = _affinity.value.toMutableMap()
        if (weight == null) next.remove(key) else next[key] = weight.coerceIn(-MAX_WEIGHT, MAX_WEIGHT)
        saveAffinity(next, _reactions.value, _eventReactions.value)
    }

    private fun saveAffinity(weights: Map<String, Float>, count: Int, reactionsMap: Map<String, Int>) {
        // Guarda no máximo MAX_TASTES palavras: ficam as de peso mais forte (gosto ou não gosto).
        val cleaned = weights.filterValues { kotlin.math.abs(it) >= 0.05f }
            .entries.sortedByDescending { kotlin.math.abs(it.value) }.take(MAX_TASTES).associate { it.key to it.value }
        sp.edit()
            .putString(KEY_AFFINITY, encodeAffinity(cleaned))
            .putInt(KEY_REACTIONS, count)
            .putString(KEY_EVENT_REACTIONS, encodeAffinity(reactionsMap.mapValues { it.value.toFloat() }))
            .apply()
        _affinity.value = cleaned
        _reactions.value = count
        _eventReactions.value = reactionsMap
    }

    fun resetAffinity() {
        sp.edit().remove(KEY_AFFINITY).remove(KEY_REACTIONS).remove(KEY_EVENT_REACTIONS).apply()
        _affinity.value = emptyMap()
        _reactions.value = 0
        _eventReactions.value = emptyMap()
    }

    private val _voice = MutableStateFlow(
        VoiceSettings(
            reporters = sp.getInt(KEY_REPORTERS, 2).coerceIn(1, 2),
            r1 = ReporterVoice(sp.getString(KEY_R1_VOICE, null), sp.getFloat(KEY_R1_RATE, 1f), sp.getFloat(KEY_R1_PITCH, 1f)),
            r2 = ReporterVoice(sp.getString(KEY_R2_VOICE, null), sp.getFloat(KEY_R2_RATE, 1f), sp.getFloat(KEY_R2_PITCH, 1f)),
        )
    )
    val voice: StateFlow<VoiceSettings> = _voice.asStateFlow()

    fun setVoiceSettings(v: VoiceSettings) {
        sp.edit()
            .putInt(KEY_REPORTERS, v.reporters)
            .apply { if (v.r1.voiceName == null) remove(KEY_R1_VOICE) else putString(KEY_R1_VOICE, v.r1.voiceName) }
            .putFloat(KEY_R1_RATE, v.r1.rate).putFloat(KEY_R1_PITCH, v.r1.pitch)
            .apply { if (v.r2.voiceName == null) remove(KEY_R2_VOICE) else putString(KEY_R2_VOICE, v.r2.voiceName) }
            .putFloat(KEY_R2_RATE, v.r2.rate).putFloat(KEY_R2_PITCH, v.r2.pitch)
            .apply()
        _voice.value = v
    }

    /** Volta a mostrar no feed um acontecimento dispensado (marcado de novo como não lido). */
    fun undismiss(eventId: String) {
        if (eventId !in _dismissed.value) return
        val list = _dismissed.value - eventId
        sp.edit().putString(KEY_DISMISSED, list.joinToString(",")).apply()
        _dismissed.value = list
    }

    fun setLastSync(ts: Long) { sp.edit().putLong(KEY_LAST_SYNC, ts).apply(); _lastSync.value = ts }

    companion object {
        /** Quantas palavras de gosto o app guarda, no máximo. */
        const val MAX_TASTES = 100

        private const val KEY_THEME = "theme"
        private const val KEY_HIGHLIGHTS = "highlight_limit"
        private const val KEY_OTHERS = "other_limit"
        private const val KEY_DEMO = "demo_mode"
        private const val KEY_READ_FILTER = "read_filter"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_DISMISSED = "dismissed_events"
        private const val KEY_TOKEN = "github_token"
        private const val KEY_AFFINITY = "affinity"
        private const val KEY_REACTIONS = "reactions"
        private const val KEY_EVENT_REACTIONS = "event_reactions"
        private const val MAX_WEIGHT = 8f
        private const val KEY_REPORTERS = "voice_reporters"
        private const val KEY_R1_VOICE = "voice_r1_name"
        private const val KEY_R1_RATE = "voice_r1_rate"
        private const val KEY_R1_PITCH = "voice_r1_pitch"
        private const val KEY_R2_VOICE = "voice_r2_name"
        private const val KEY_R2_RATE = "voice_r2_rate"
        private const val KEY_R2_PITCH = "voice_r2_pitch"

        // Formato simples "chave<TAB>peso" por linha (as chaves não têm tabulação nem quebra de linha).
        private fun encodeAffinity(m: Map<String, Float>): String = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
        private fun decodeAffinity(s: String?): Map<String, Float> = s.orEmpty().lineSequence().mapNotNull { line ->
            val i = line.lastIndexOf('\t')
            if (i <= 0) null else line.substring(i + 1).toFloatOrNull()?.let { line.substring(0, i) to it }
        }.toMap()
    }
}

/** Voz de um repórter. [voiceName] nulo = escolha automática. */
data class ReporterVoice(val voiceName: String? = null, val rate: Float = 1f, val pitch: Float = 1f)

/** Leitura em voz alta: 1 repórter só, ou 2 alternando notícia a notícia. */
data class VoiceSettings(val reporters: Int = 2, val r1: ReporterVoice = ReporterVoice(), val r2: ReporterVoice = ReporterVoice()) {
    fun reporter(index: Int): ReporterVoice = if (index == 1 && reporters == 2) r2 else r1
}

enum class ReadFilter(val label: String) {
    ALL("Todas"), UNREAD("Não lidas"), READ("Lidas");

    fun accepts(isRead: Boolean): Boolean = when (this) {
        ALL -> true
        UNREAD -> !isRead
        READ -> isRead
    }
}
