package app.farol.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.farol.data.SyncResult
import app.farol.data.model.Article
import app.farol.data.model.Event
import app.farol.data.model.FilterInfo
import app.farol.data.model.LatestFeed
import app.farol.data.model.Section
import app.farol.ui.ScanState
import app.farol.ui.UiState
import app.farol.ui.UpdateState
import app.farol.ui.components.CardShape
import app.farol.ui.components.CompactArticleRow
import app.farol.ui.components.EmptyState
import app.farol.ui.components.EndCard
import app.farol.ui.components.Eyebrow
import app.farol.ui.components.HighlightCard
import app.farol.ui.components.NoticeCard
import app.farol.ui.components.NoticeTone
import app.farol.ui.components.SectionHeader
import app.farol.util.Fmt
import kotlinx.coroutines.delay

/**
 * Notícia que representa o acontecimento: a mais recente do grupo (preferindo uma com resumo).
 * O horário exibido no card e a ordenação usam esta mesma notícia.
 */
fun leadArticle(event: Event, articles: Map<String, Article>): Article? {
    val items = event.articleIds.mapNotNull { articles[it] }.sortedByDescending { it.timeMillis }
    val newest = items.firstOrNull() ?: return null
    // Uma com resumo, se for praticamente tão recente quanto a mais nova (até 2 h de diferença).
    return items.firstOrNull { !it.summary.isNullOrBlank() && newest.timeMillis - it.timeMillis <= 2 * 3_600_000L } ?: newest
}

/** Momento exibido no card do acontecimento (o da notícia principal). */
fun Event.shownMillis(articles: Map<String, Article>): Long =
    leadArticle(this, articles)?.timeMillis?.takeIf { it > 0 } ?: app.farol.data.model.isoMillis(lastSeen)

private fun Event.matches(topic: String?, articles: Map<String, Article>): Boolean =
    topic == null || topic in tags || articleIds.any { id -> articles[id]?.tags?.contains(topic) == true }

private val HPad = Modifier.padding(horizontal = 20.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpenArticle: (String) -> Unit,
    onOpenExplore: () -> Unit,
    onUnreadOnly: (Boolean) -> Unit = {},
) {
    val feed = state.feed
    var sectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    val sections = feed?.sections.orEmpty().sortedBy { it.order }
    val section = sections.firstOrNull { it.id == sectionId }

    PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
            item { TodayHeader(feed, section) }
            if (sections.isNotEmpty()) {
                item { SectionBar(sections, sectionId) { sectionId = it; topic = null } }
            }
            if (feed != null) {
                item {
                    Box(HPad.padding(top = 8.dp)) {
                        Chip("Só não lidas", state.unreadOnly, small = true) {
                            onUnreadOnly(!state.unreadOnly)
                        }
                    }
                }
            }
            item {
                Column(HPad) { SyncNotices(state) }
            }

            if (feed == null) {
                item {
                    if (!state.loaded || state.syncing) {
                        Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        EmptyState(
                            "Nenhuma edição disponível",
                            "Conecte-se à internet e puxe para atualizar.",
                            HPad,
                        )
                    }
                }
                return@LazyColumn
            }

            val arts = state.articlesById
            val isRead = { e: Event -> e.articleIds.any { it in state.readIds } }

            if (section != null) {
                // ---------------- Seção ----------------
                if (section.filters.isNotEmpty()) {
                    item { TopicChips(section.filters, topic) { topic = it } }
                }
                // Sempre da mais recente para a mais antiga.
                val ordered = feed.events
                    .filter { it.category == section.id && it.matches(topic, arts) && !(state.unreadOnly && isRead(it)) }
                    .sortedByDescending { it.shownMillis(arts) }
                val shown = ordered.take(state.highlightLimit + state.otherLimit)
                val label = topic?.let { t -> section.filters.firstOrNull { it.id == t }?.label } ?: section.label
                item { SectionHeader(label, "${shown.size} de ${ordered.size}", HPad) }
                if (shown.isEmpty()) {
                    item { EmptyState("Nada em $label nesta edição", "Volte na próxima edição.", HPad) }
                }
                itemsIndexed(shown, key = { _, e -> "s-" + e.id }) { i, event ->
                    val lead = leadArticle(event, arts) ?: return@itemsIndexed
                    val sub = state.labelFor(lead)
                    Box(HPad) {
                        if (i < 3) {
                            HighlightCard(i + 1, event, lead, sub, isRead(event)) { onOpenArticle(lead.id) }
                        } else {
                            Column {
                                CompactArticleRow(lead, sub, isRead(event), (event.sourceCount - 1).coerceAtLeast(0)) { onOpenArticle(lead.id) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                item {
                    EndCard(
                        "Fim de $label",
                        "Isso é tudo nesta edição.",
                        HPad,
                        action = { TextButton(onClick = { sectionId = null; topic = null }) { Text("Voltar à capa") } },
                    )
                }
                return@LazyColumn
            }

            // ---------------- Capa ----------------
            val allHighlights = feed.highlights.mapNotNull { state.eventsById[it] }.take(state.highlightLimit)
            val readCount = allHighlights.count(isRead)
            val highlights = allHighlights.filter { !(state.unreadOnly && isRead(it)) }.sortedByDescending { it.shownMillis(arts) }
            item { SectionHeader("Destaques", "$readCount de ${allHighlights.size} lidos", HPad) }
            if (highlights.isEmpty() && allHighlights.isNotEmpty()) {
                item { EmptyState("Nenhum destaque novo", "Você já leu todos os destaques desta edição.", HPad) }
            }
            itemsIndexed(highlights, key = { _, e -> e.id }) { i, event ->
                val lead = leadArticle(event, arts)
                Box(HPad) {
                    HighlightCard(
                        rank = i + 1,
                        event = event,
                        lead = lead,
                        subcategoryLabel = lead?.let { state.labelFor(it) } ?: state.sectionLabels[event.category],
                        read = isRead(event),
                        onClick = { lead?.let { onOpenArticle(it.id) } },
                    )
                }
            }

            if (sections.isNotEmpty()) {
                item { SectionHeader("Seções", null, HPad) }
                items(sections, key = { "n-" + it.id }) { sec ->
                    Row(
                        HPad.fillMaxWidth().clickable { sectionId = sec.id }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(sec.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("${sec.articleCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                val allRead = allHighlights.isNotEmpty() && readCount == allHighlights.size
                EndCard(
                    title = if (allRead) "Você está em dia" else "Fim da capa",
                    body = (if (allRead) "Você leu todos os destaques. " else "") +
                        "Abra uma seção acima para ver o resto.",
                    modifier = HPad,
                    action = { TextButton(onClick = onOpenExplore) { Text("Ver arquivo histórico") } },
                )
            }
        }
    }

}

@Composable
private fun TodayHeader(feed: LatestFeed?, section: Section?) {
    Column(HPad.fillMaxWidth().padding(top = 8.dp)) {
        Text("FAROL", style = TextStyle(fontSize = 13.sp, letterSpacing = 5.sp), color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            section?.label ?: feed?.edition?.label ?: "Hoje",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** Seções no topo: Capa, Brasil, Política, Economia, Mundo, Tecnologia. */
@Composable
private fun SectionBar(sections: List<Section>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp),
    ) {
        item { Chip("Capa", selected == null) { onSelect(null) } }
        items(sections, key = { it.id }) { s -> Chip(s.label, selected == s.id) { onSelect(s.id) } }
    }
}

/** Subtemas da seção (hoje só em Tecnologia: Microsoft, OpenAI, Agentes…). */
@Composable
private fun TopicChips(filters: List<FilterInfo>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        item { Chip("Todos os temas", selected == null, small = true) { onSelect(null) } }
        items(filters, key = { it.id }) { f -> Chip(f.label, selected == f.id, small = true) { onSelect(if (selected == f.id) null else f.id) } }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, small: Boolean = false, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
private fun SyncNotices(state: UiState) {
    val feed = state.feed
    Column {
        if (state.scan is ScanState.Starting || state.scan is ScanState.Running) {
            Text(
                "Buscando notícias novas…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
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
            is SyncResult.Failed -> NoticeCard(
                if (r.reason == "HTTP 404") "Ainda não há edição publicada. Aguarde a próxima edição automática."
                else "Não foi possível atualizar: ${r.reason}. Exibindo a última edição salva.",
                NoticeTone.Warning,
            )
            else -> Unit
        }
    }
}
