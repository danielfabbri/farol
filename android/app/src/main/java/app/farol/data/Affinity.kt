package app.farol.data

import app.farol.data.model.Article
import app.farol.data.model.Event
import java.util.Locale

/**
 * Gostos do leitor aprendidos com o gesto de arrastar (direita = gostei, esquerda = não gostei).
 * Cada notícia vira um conjunto de traços — seção, tema, tags, empresas/pessoas, fonte — e cada
 * reação soma ou subtrai peso desses traços. Tudo fica só no aparelho.
 */
object Affinity {
    /** Traços da notícia e quanto cada um pesa numa reação. */
    fun features(event: Event, lead: Article?): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        fun put(key: String, w: Float) { if (key.isNotBlank()) out[key] = maxOf(out[key] ?: 0f, w) }
        val category = event.category.ifBlank { lead?.category.orEmpty() }
        if (category.isNotBlank()) put("cat:$category", 1f)
        (event.subcategory ?: lead?.subcategory)?.takeIf { it != "other" }?.let { put("sub:$it", 1f) }
        val tags = (event.tags + lead?.tags.orEmpty()).map(::norm).distinct()
        tags.forEach { put("tag:$it", 0.8f / kotlin.math.sqrt(tags.size.toFloat())) }
        val names = (event.entities + event.technologies + lead?.entities.orEmpty() + lead?.technologies.orEmpty())
            .map(::norm).distinct().take(8)
        names.forEach { put("ent:$it", 0.8f / kotlin.math.sqrt(names.size.toFloat())) }
        (lead?.sourceName ?: event.sources.firstOrNull()?.sourceName)?.let { put("src:${norm(it)}", 0.5f) }
        return out
    }

    /** Afinidade de -1 (não gosta) a 1 (gosta); 0 sem histórico. */
    fun score(weights: Map<String, Float>, event: Event, lead: Article?): Float {
        if (weights.isEmpty()) return 0f
        val raw = features(event, lead).entries.sumOf { (k, w) -> ((weights[k] ?: 0f) * w).toDouble() }.toFloat()
        // Satura suavemente: 3–4 reações no mesmo sentido já pesam bastante.
        return (raw / (2.5f + kotlin.math.abs(raw))).coerceIn(-1f, 1f)
    }

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT)
}
