package app.farol.data

import app.farol.data.model.Article
import app.farol.data.model.Event
import java.util.Locale

/**
 * Gostos do leitor aprendidos com o gesto de arrastar (direita = gostei, esquerda = não gostei).
 * O que conta é o TEMA da notícia: as palavras-chave que a IA separa ao resumir (ex.: "nvidia",
 * "reforma tributária", "spacex"). Cada reação soma ou subtrai peso dessas palavras; notícias com
 * palavras de que você gosta ganham destaque. A seção (Política, Economia…) não entra na conta.
 * Tudo fica só no aparelho.
 */
object Affinity {
    const val KEYWORD = "kw:"

    /** Palavras-chave do tema e quanto cada uma pesa numa reação. */
    fun features(event: Event, lead: Article?): Map<String, Float> {
        // Palavras da IA; sem elas (notícia ainda não resumida), nomes e tags servem de tema.
        val words = (lead?.keywords.orEmpty() + event.keywords).map(::norm).filter { it.isNotBlank() }.distinct()
            .ifEmpty {
                (lead?.entities.orEmpty() + lead?.technologies.orEmpty() + event.entities + event.technologies + event.tags)
                    .map(::norm).filter { it.isNotBlank() }.distinct()
            }
            .take(8)
        if (words.isEmpty()) return emptyMap()
        // Notícias com muitas palavras não pesam mais que as com poucas.
        val w = 1.5f / kotlin.math.sqrt(words.size.toFloat())
        return words.associate { "$KEYWORD$it" to w }
    }

    /** Afinidade de -1 (não gosta) a 1 (gosta); 0 sem histórico ou tema desconhecido. */
    fun score(weights: Map<String, Float>, event: Event, lead: Article?): Float {
        if (weights.isEmpty()) return 0f
        val raw = features(event, lead).entries.sumOf { (k, w) -> ((weights[k] ?: 0f) * w).toDouble() }.toFloat()
        // Satura suavemente: 2–3 reações sobre o mesmo tema já pesam bastante.
        return (raw / (2.0f + kotlin.math.abs(raw))).coerceIn(-1f, 1f)
    }

    /** Palavras-chave com mais peso (positivo ou negativo), para mostrar em Ajustes. */
    fun top(weights: Map<String, Float>, liked: Boolean, n: Int = 8): List<String> =
        weights.entries.filter { it.key.startsWith(KEYWORD) && if (liked) it.value > 0.4f else it.value < -0.4f }
            .sortedBy { if (liked) -it.value else it.value }
            .take(n).map { it.key.removePrefix(KEYWORD) }

    /** Chave guardada para uma palavra digitada ou exibida ("NVIDIA " → "kw:nvidia"). */
    fun key(word: String): String = KEYWORD + norm(word.removePrefix(KEYWORD))

    /** Todas as palavras guardadas, das mais fortes para as mais fracas. */
    fun all(weights: Map<String, Float>): List<Pair<String, Float>> =
        weights.entries.filter { it.key.startsWith(KEYWORD) }
            .sortedByDescending { kotlin.math.abs(it.value) }
            .map { it.key.removePrefix(KEYWORD) to it.value }

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}
