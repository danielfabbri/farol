package app.farol.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Contrato de dados publicado pelo backend em data/latest.json (schema_version 1). */
@Serializable
data class LatestFeed(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("is_sample") val isSample: Boolean = false,
    val edition: EditionInfo,
    val category: CategoryInfo = CategoryInfo(),
    val subcategories: Map<String, String> = emptyMap(),
    val filters: List<FilterInfo> = emptyList(),
    val highlights: List<String> = emptyList(),
    val events: List<Event> = emptyList(),
    val articles: List<Article> = emptyList(),
    @SerialName("archive_days") val archiveDays: List<String> = emptyList(),
    @SerialName("ai_status") val aiStatus: AiStatus = AiStatus(),
    val notice: String? = null,
    val sections: List<Section> = emptyList(),
)

@Serializable
data class Section(
    val id: String,
    val label: String,
    val order: Int = 0,
    val highlights: List<String> = emptyList(),
    val filters: List<FilterInfo> = emptyList(),
    @SerialName("article_count") val articleCount: Int = 0,
    val brief: SectionBrief? = null,
)

/** Resumo da seção inteira; cada tópico aponta para os acontecimentos de origem. */
@Serializable
data class SectionBrief(
    val headline: String,
    val overview: String,
    val points: List<BriefPoint> = emptyList(),
    val watch: String? = null,
    @SerialName("generated_at") val generatedAt: String,
    val model: String? = null,
    val author: String = "IA",
    val stale: Boolean = false,
)

@Serializable
data class BriefPoint(
    val text: String,
    @SerialName("event_ids") val eventIds: List<String> = emptyList(),
)

@Serializable
data class EditionInfo(
    val id: String,
    val label: String,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("highlight_count") val highlightCount: Int = 0,
    @SerialName("article_count") val articleCount: Int = 0,
    @SerialName("ai_processed_count") val aiProcessedCount: Int = 0,
    @SerialName("sources_ok") val sourcesOk: Int = 0,
    @SerialName("sources_failed") val sourcesFailed: Int = 0,
    @SerialName("new_articles") val newArticles: Int = 0,
)

@Serializable
data class CategoryInfo(val id: String = "", val label: String = "")

@Serializable
data class FilterInfo(val id: String, val label: String)

@Serializable
data class AiStatus(
    val state: String = "",
    val model: String? = null,
    @SerialName("processed_this_run") val processedThisRun: Int = 0,
    val pending: Int = 0,
    val message: String = "",
)

@Serializable
data class EventSource(
    @SerialName("article_id") val articleId: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_name") val sourceName: String,
    @SerialName("source_url") val sourceUrl: String,
    val title: String,
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
data class Event(
    val id: String,
    val title: String,
    val summary: String? = null,
    val category: String = "",
    val subcategory: String? = null,
    @SerialName("first_seen") val firstSeen: String,
    @SerialName("last_seen") val lastSeen: String,
    @SerialName("article_ids") val articleIds: List<String> = emptyList(),
    val sources: List<EventSource> = emptyList(),
    @SerialName("source_count") val sourceCount: Int = 1,
    val entities: List<String> = emptyList(),
    val technologies: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("relevance_score") val relevanceScore: Int? = null,
    val score: Double = 0.0,
    @SerialName("ai_processed") val aiProcessed: Boolean = false,
)

@Serializable
data class Article(
    val id: String,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("original_title") val originalTitle: String,
    @SerialName("title_pt") val titlePt: String? = null,
    @SerialName("source_id") val sourceId: String = "",
    @SerialName("source_name") val sourceName: String,
    @SerialName("source_type") val sourceType: String = "press",
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("discussion_url") val discussionUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("collected_at") val collectedAt: String,
    val language: String = "en",
    val excerpt: String = "",
    @SerialName("excerpt_pt") val excerptPt: String? = null,
    val category: String = "",
    val subcategory: String? = null,
    val summary: String? = null,
    @SerialName("why_it_matters") val whyItMatters: String? = null,
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    @SerialName("potential_impacts") val potentialImpacts: List<String> = emptyList(),
    val uncertainties: List<String> = emptyList(),
    val entities: List<String> = emptyList(),
    val technologies: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("relevance_score") val relevanceScore: Int? = null,
    @SerialName("novelty_score") val noveltyScore: Int? = null,
    @SerialName("business_potential") val businessPotential: String? = null,
    @SerialName("career_potential") val careerPotential: String? = null,
    @SerialName("study_potential") val studyPotential: String? = null,
    @SerialName("off_topic") val offTopic: Boolean = false,
    @SerialName("ai_processed") val aiProcessed: Boolean = false,
    @SerialName("ai_model") val aiModel: String? = null,
    @SerialName("is_sample") val isSample: Boolean = false,
) {
    val displayTitle: String get() = titlePt?.takeIf { it.isNotBlank() } ?: originalTitle
    val publishedOrCollected: String get() = publishedAt ?: collectedAt
    /** Texto curto para listas: resumo da IA, senão trecho traduzido, senão trecho original. */
    val preview: String? get() = summary ?: excerptPt ?: excerpt.takeIf { it.isNotBlank() }
    val isTranslatedOnly: Boolean get() = !aiProcessed && titlePt != null
}

@Serializable
data class ArchiveDay(val date: String, val count: Int = 0, val articles: List<Article> = emptyList())

@Serializable
data class TrendsDoc(
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("is_sample") val isSample: Boolean = false,
    @SerialName("window_days") val windowDays: Int = 7,
    val disclaimer: String = "",
    val trends: List<Trend> = emptyList(),
)

@Serializable
data class Trend(
    val key: String,
    val label: String,
    val kind: String,
    @SerialName("first_seen") val firstSeen: String,
    @SerialName("last_seen") val lastSeen: String,
    @SerialName("article_count") val articleCount: Int,
    @SerialName("source_count") val sourceCount: Int,
    @SerialName("event_count") val eventCount: Int = 0,
    @SerialName("daily_counts") val dailyCounts: Map<String, Int> = emptyMap(),
    @SerialName("related_entities") val relatedEntities: List<String> = emptyList(),
    @SerialName("event_ids") val eventIds: List<String> = emptyList(),
    val status: String = "steady",
)

val FarolJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
