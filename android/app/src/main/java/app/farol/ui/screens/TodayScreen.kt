package app.farol.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.farol.data.SyncResult
import app.farol.data.model.Article
import app.farol.data.model.Event
import app.farol.data.model.LatestFeed
import app.farol.ui.UiState
import app.farol.ui.components.CompactArticleRow
import app.farol.ui.components.EmptyState
import app.farol.ui.components.EndCard
import app.farol.ui.components.HighlightCard
import app.farol.ui.components.NoticeCard
import app.farol.ui.components.NoticeTone
import app.farol.ui.components.SectionHeader
import app.farol.util.Fmt

/** Artigo que representa o evento: o primeiro analisado pela IA, senão o primeiro disponível. */
fun leadArticle(event: Event, articles: Map<String, Article>): Article? {
    val items = event.articleIds.mapNotNull { articles[it] }
    return items.firstOrNull { it.aiProcessed } ?: items.firstOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpenArticle: (String) -> Unit,
    onOpenExplore: () -> Unit,
) {
    val feed = state.feed
    PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
            item { TodayHeader(feed, state.syncing, onRefresh) }
            item { SyncNotices(state) }

            if (feed == null) {
                item {
                    if (!state.loaded || state.syncing) {
                        Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        EmptyState(
                            "Nenhuma edição disponível",
                            "Conecte-se à internet e puxe para atualizar. As edições são publicadas às 05h30, 11h30, 17h30 e 20h30.",
                        )
                    }
                }
                return@LazyColumn
            }

            val highlights = feed.highlights.mapNotNull { state.eventsById[it] }.take(state.highlightLimit)
            val shownIds = highlights.map { it.id }.toSet()
            val isRead = { e: Event -> e.articleIds.any { it in state.readIds } }
            val readCount = highlights.count(isRead)
            val others = feed.events.filter { it.id !in shownIds }.sortedByDescending { it.score }.take(state.otherLimit)

            item { SectionHeader("Destaques", "$readCount de ${highlights.size} lidos") }
            itemsIndexed(highlights, key = { _, e -> e.id }) { i, event ->
                val lead = leadArticle(event, state.articlesById)
                HighlightCard(
                    rank = i + 1,
                    event = event,
                    lead = lead,
                    subcategoryLabel = (lead?.subcategory ?: event.subcategory)?.let { feed.subcategories[it] },
                    read = isRead(event),
                    onClick = { lead?.let { onOpenArticle(it.id) } },
                )
            }

            if (others.isNotEmpty()) {
                item { SectionHeader("Também nesta edição", "${others.size}") }
                items(others, key = { "o-" + it.id }) { event ->
                    val lead = leadArticle(event, state.articlesById) ?: return@items
                    CompactArticleRow(
                        article = lead,
                        subcategoryLabel = lead.subcategory?.let { feed.subcategories[it] },
                        read = isRead(event),
                        extraSources = (event.sourceCount - 1).coerceAtLeast(0),
                        onClick = { onOpenArticle(lead.id) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            item {
                val allRead = highlights.isNotEmpty() && readCount == highlights.size
                EndCard(
                    title = if (allRead) "Você está em dia" else "Fim da edição",
                    body = (if (allRead) "Você leu todos os destaques desta edição. " else "Não há mais nada nesta edição. ") +
                        "A próxima sai ${Fmt.nextEdition()}.",
                    action = { TextButton(onClick = onOpenExplore) { Text("Explorar por tema ou arquivo") } },
                )
            }
        }
    }
}

@Composable
private fun TodayHeader(feed: LatestFeed?, syncing: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "FAROL",
                style = TextStyle(fontSize = 13.sp, letterSpacing = 5.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh, enabled = !syncing) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Atualizar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(feed?.edition?.label ?: "Hoje", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        if (feed != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Atualizada em ${Fmt.dateTime(feed.generatedAt)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            val e = feed.edition
            Text(
                "${e.articleCount} notícias  ·  ${e.aiProcessedCount} analisadas pela IA  ·  ${e.sourcesOk} fontes",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncNotices(state: UiState) {
    val feed = state.feed
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        if (state.demoMode || feed?.isSample == true) {
            NoticeCard(
                feed?.notice ?: "Modo demonstração: dados fictícios, sem valor informativo.",
                NoticeTone.Warning, Icons.Outlined.Science,
            )
        }
        when (val r = state.lastResult) {
            is SyncResult.Offline -> NoticeCard(
                "Sem conexão. Exibindo a última edição salva (sincronizada em ${Fmt.dateTime(state.lastSync)}).",
                NoticeTone.Warning, Icons.Outlined.CloudOff,
            )
            is SyncResult.Failed -> NoticeCard("Não foi possível atualizar: ${r.reason}. Exibindo a última edição salva.", NoticeTone.Warning)
            else -> Unit
        }
        val ai = feed?.aiStatus
        if (ai != null && ai.state !in setOf("ok", "idle", "") && ai.message.isNotBlank()) {
            NoticeCard(ai.message)
        }
    }
}
