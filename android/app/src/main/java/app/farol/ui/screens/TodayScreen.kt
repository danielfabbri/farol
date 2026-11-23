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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Science
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.farol.data.SyncResult
import app.farol.data.model.Article
import app.farol.data.model.Event
import app.farol.data.model.LatestFeed
import app.farol.ui.ScanState
import app.farol.ui.UiState
import app.farol.ui.components.CompactArticleRow
import app.farol.ui.components.EmptyState
import app.farol.ui.components.EndCard
import app.farol.ui.components.HighlightCard
import app.farol.ui.components.NoticeCard
import app.farol.ui.components.NoticeTone
import app.farol.ui.components.SectionHeader
import app.farol.util.Fmt
import kotlinx.coroutines.delay

/** Artigo que representa o evento: o primeiro analisado pela IA, senão o primeiro disponível. */
fun leadArticle(event: Event, articles: Map<String, Article>): Article? {
    val items = event.articleIds.mapNotNull { articles[it] }
    return items.firstOrNull { it.aiProcessed } ?: items.firstOrNull { it.titlePt != null } ?: items.firstOrNull()
}

private fun Event.matches(topic: String?, articles: Map<String, Article>): Boolean =
    topic == null || topic in tags || articleIds.any { id -> articles[id]?.tags?.contains(topic) == true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onDismissScan: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArticle: (String) -> Unit,
    onOpenExplore: () -> Unit,
) {
    val feed = state.feed
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var askToken by remember { mutableStateOf(false) }

    PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
            item {
                TodayHeader(feed, state.scan, onScan = { if (state.hasToken) onScan() else askToken = true })
            }
            if (feed != null && feed.filters.isNotEmpty()) {
                item { TopicBar(feed, topic) { topic = it } }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    ScanBanner(state.scan, onDismissScan)
                    SyncNotices(state)
                }
            }

            if (feed == null) {
                item {
                    if (!state.loaded || state.syncing) {
                        Box(Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        EmptyState(
                            "Nenhuma edição disponível",
                            "Conecte-se à internet e puxe para atualizar. As edições são publicadas às 05h30, 11h30, 17h30 e 20h30.",
                            Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
                return@LazyColumn
            }

            val arts = state.articlesById
            val isRead = { e: Event -> e.articleIds.any { it in state.readIds } }

            if (topic != null) {
                // Tema escolhido: tudo do tema na edição, por importância — ainda com fim.
                val label = feed.filters.firstOrNull { it.id == topic }?.label ?: ""
                val events = feed.events.filter { it.matches(topic, arts) }.sortedByDescending { it.score }
                item { SectionHeader(label, "${events.size} ${if (events.size == 1) "acontecimento" else "acontecimentos"}", Modifier.padding(horizontal = 20.dp)) }
                if (events.isEmpty()) {
                    item { EmptyState("Nada sobre $label nesta edição", "Experimente outro tema ou volte na próxima edição.", Modifier.padding(horizontal = 20.dp)) }
                }
                itemsIndexed(events, key = { _, e -> "t-" + e.id }) { i, event ->
                    val lead = leadArticle(event, arts) ?: return@itemsIndexed
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        if (i < 3) {
                            HighlightCard(i + 1, event, lead, lead.subcategory?.let { feed.subcategories[it] }, isRead(event)) { onOpenArticle(lead.id) }
                        } else {
                            Column {
                                CompactArticleRow(lead, lead.subcategory?.let { feed.subcategories[it] }, isRead(event), (event.sourceCount - 1).coerceAtLeast(0)) { onOpenArticle(lead.id) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                item {
                    EndCard(
                        "Fim de $label",
                        "Isso é tudo sobre este tema na edição atual. A próxima sai ${Fmt.nextEdition()}.",
                        Modifier.padding(horizontal = 20.dp),
                        action = { TextButton(onClick = { topic = null }) { Text("Ver todos os temas") } },
                    )
                }
                return@LazyColumn
            }

            val highlights = feed.highlights.mapNotNull { state.eventsById[it] }.take(state.highlightLimit)
            val shownIds = highlights.map { it.id }.toSet()
            val readCount = highlights.count(isRead)
            val others = feed.events.filter { it.id !in shownIds }.sortedByDescending { it.score }.take(state.otherLimit)

            item { SectionHeader("Destaques", "$readCount de ${highlights.size} lidos", Modifier.padding(horizontal = 20.dp)) }
            itemsIndexed(highlights, key = { _, e -> e.id }) { i, event ->
                val lead = leadArticle(event, arts)
                Box(Modifier.padding(horizontal = 20.dp)) {
                    HighlightCard(
                        rank = i + 1,
                        event = event,
                        lead = lead,
                        subcategoryLabel = (lead?.subcategory ?: event.subcategory)?.let { feed.subcategories[it] },
                        read = isRead(event),
                        onClick = { lead?.let { onOpenArticle(it.id) } },
                    )
                }
            }

            if (others.isNotEmpty()) {
                item { SectionHeader("Também nesta edição", "${others.size}", Modifier.padding(horizontal = 20.dp)) }
                items(others, key = { "o-" + it.id }) { event ->
                    val lead = leadArticle(event, arts) ?: return@items
                    Column(Modifier.padding(horizontal = 20.dp)) {
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
            }

            item {
                val allRead = highlights.isNotEmpty() && readCount == highlights.size
                EndCard(
                    title = if (allRead) "Você está em dia" else "Fim da edição",
                    body = (if (allRead) "Você leu todos os destaques desta edição. " else "Não há mais nada nesta edição. ") +
                        "A próxima sai ${Fmt.nextEdition()}.",
                    modifier = Modifier.padding(horizontal = 20.dp),
                    action = { TextButton(onClick = onOpenExplore) { Text("Ver arquivo histórico") } },
                )
            }
        }
    }

    if (askToken) {
        AlertDialog(
            onDismissRequest = { askToken = false },
            title = { Text("Varrer as fontes agora") },
            text = {
                Text(
                    "A varredura roda no GitHub Actions (gratuito) e leva de 2 a 6 minutos. " +
                        "Para o app poder iniciá-la, cadastre uma vez um token do GitHub em Ajustes → Varredura sob demanda."
                )
            },
            confirmButton = { TextButton(onClick = { askToken = false; onOpenSettings() }) { Text("Abrir Ajustes") } },
            dismissButton = { TextButton(onClick = { askToken = false }) { Text("Agora não") } },
        )
    }
}

@Composable
private fun TodayHeader(feed: LatestFeed?, scan: ScanState, onScan: () -> Unit) {
    val scanning = scan is ScanState.Starting || scan is ScanState.Running
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "FAROL",
                style = TextStyle(fontSize = 13.sp, letterSpacing = 5.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onScan, enabled = !scanning, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Varrendo…" else "Varrer agora")
            }
        }
        Spacer(Modifier.height(8.dp))
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

/** Categorias no topo: rolagem horizontal com os temas definidos no backend. */
@Composable
private fun TopicBar(feed: LatestFeed, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 16.dp),
    ) {
        item {
            FilterChip(
                selected = selected == null, onClick = { onSelect(null) }, label = { Text("Tudo") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
            )
        }
        items(feed.filters, key = { it.id }) { f ->
            FilterChip(
                selected = selected == f.id,
                onClick = { onSelect(if (selected == f.id) null else f.id) },
                label = { Text(f.label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
            )
        }
    }
}

@Composable
private fun ScanBanner(scan: ScanState, onDismiss: () -> Unit) {
    when (scan) {
        ScanState.Idle -> Unit
        ScanState.Starting -> ScanProgress("Pedindo a varredura ao GitHub…", null)
        is ScanState.Running -> ScanProgress(scan.step, scan.startedAt)
        is ScanState.Done -> {
            NoticeCard(scan.message)
            LaunchedEffect(scan) { delay(8_000); onDismiss() }
        }
        is ScanState.Failed -> {
            NoticeCard(scan.message, NoticeTone.Warning)
            LaunchedEffect(scan) { delay(12_000); onDismiss() }
        }
    }
}

@Composable
private fun ScanProgress(step: String, startedAt: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(step, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                if (startedAt != null) {
                    val s = ((now - startedAt) / 1000).coerceAtLeast(0)
                    Text("%d:%02d".format(s / 60, s % 60), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                "Costuma levar de 2 a 6 minutos. Pode sair do app: a nova edição aparece quando você voltar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncNotices(state: UiState) {
    val feed = state.feed
    Column {
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
                if (r.reason == "HTTP 404") "Ainda não há edição publicada no GitHub. Toque em \"Varrer agora\" ou aguarde a próxima edição automática."
                else "Não foi possível atualizar: ${r.reason}. Exibindo a última edição salva.",
                NoticeTone.Warning,
            )
            else -> Unit
        }
        val ai = feed?.aiStatus
        if (ai != null && ai.state !in setOf("ok", "idle", "") && ai.message.isNotBlank()) {
            NoticeCard(ai.message)
        }
    }
}
