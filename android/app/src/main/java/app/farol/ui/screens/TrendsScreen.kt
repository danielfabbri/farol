package app.farol.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.farol.data.model.Trend
import app.farol.ui.UiState
import app.farol.ui.components.CardShape
import app.farol.ui.components.EmptyState
import app.farol.ui.components.Eyebrow
import app.farol.ui.components.NoticeCard
import app.farol.ui.components.Pill
import app.farol.util.Fmt
import java.time.LocalDate

@Composable
fun TrendsScreen(state: UiState, onOpenArticle: (String) -> Unit) {
    val doc = state.trends
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
        item { ScreenTitle("Tendências", "Assuntos que várias fontes vêm citando nos últimos ${doc?.windowDays ?: 7} dias.") }
        item {
            NoticeCard(
                doc?.disclaimer?.takeIf { it.isNotBlank() }
                    ?: "Sinais baseados em contagem de menções entre fontes. Não são previsões."
            )
            Spacer(Modifier.height(16.dp))
        }
        if (doc == null || doc.trends.isEmpty()) {
            item {
                EmptyState(
                    "Ainda sem tendências",
                    "Uma tendência aparece quando um assunto é citado por pelo menos duas fontes em três ou mais notícias analisadas.",
                )
            }
            return@LazyColumn
        }
        items(doc.trends, key = { it.key }) { t -> TrendCard(t, state, onOpenArticle) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendCard(t: Trend, state: UiState, onOpenArticle: (String) -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(Fmt.trendKind(t.kind), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Pill(Fmt.trendStatus(t.status), emphasized = t.status != "steady")
            }
            Spacer(Modifier.height(8.dp))
            Text(t.label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "${t.sourceCount} fontes  ·  ${t.articleCount} notícias  ·  ${t.eventCount} acontecimentos",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Primeira menção ${Fmt.shortDate(t.firstSeen)}  ·  mais recente ${Fmt.relative(t.lastSeen)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            DailyBars(t.dailyCounts, days = 7)
            if (t.relatedEntities.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    t.relatedEntities.forEach { Pill(it) }
                }
            }
            val related = t.eventIds.mapNotNull { state.eventsById[it] }.take(3)
            if (related.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Eyebrow("Acontecimentos relacionados", color = MaterialTheme.colorScheme.onSurfaceVariant)
                related.forEach { e ->
                    val lead = leadArticle(e, state.articlesById)
                    Text(
                        lead?.displayTitle ?: e.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = lead != null) { lead?.let { onOpenArticle(it.id) } }.padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Evolução diária de menções (barras simples). */
@Composable
private fun DailyBars(counts: Map<String, Int>, days: Int) {
    val end = counts.keys.maxOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    val series = (days - 1 downTo 0).map { counts[end.minusDays(it.toLong()).toString()] ?: 0 }
    val max = (series.maxOrNull() ?: 0).coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val gap = 6.dp.toPx()
        val w = (size.width - gap * (days - 1)) / days
        series.forEachIndexed { i, v ->
            val h = if (v == 0) 3.dp.toPx() else (size.height * v / max).coerceAtLeast(4.dp.toPx())
            drawRoundRect(
                color = if (v == 0) empty else bar,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}
