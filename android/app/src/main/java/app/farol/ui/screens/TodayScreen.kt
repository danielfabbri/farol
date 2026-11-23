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
import app.farol.data.model.SectionBrief
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

/** Artigo que representa o evento: o primeiro analisado pela IA, senão o traduzido, senão o primeiro. */
fun leadArticle(event: Event, articles: Map<String, Article>): Article? {
    val items = event.articleIds.mapNotNull { articles[it] }
    return items.firstOrNull { it.aiProcessed } ?: items.firstOrNull { it.titlePt != null } ?: items.firstOrNull()
}

private fun Event.matches(topic: String?, articles: Map<String, Article>): Boolean =
    topic == null || topic in tags || articleIds.any { id -> articles[id]?.tags?.contains(topic) == true }

private val HPad = Modifier.padding(horizontal = 20.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: UiState,
    update: UpdateState,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onDismissScan: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenArticle: (String) -> Unit,
    onOpenExplore: () -> Unit,
) {
    val feed = state.feed
    var sectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    var askToken by remember { mutableStateOf(false) }
    val sections = feed?.sections.orEmpty().sortedBy { it.order }
    val section = sections.firstOrNull { it.id == sectionId }

    PullToRefreshBox(isRefreshing = state.syncing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
            item { TodayHeader(feed, section, state.scan, onScan = { if (state.hasToken) onScan() else askToken = true }) }
            if (sections.isNotEmpty()) {
                item { SectionBar(sections, sectionId) { sectionId = it; topic = null } }
            }
            item {
                Column(HPad) {
                    if (update is UpdateState.Available) {
                        Box(Modifier.clickable(onClick = onOpenSettings)) {
                            NoticeCard("Nova versão ${update.info.versionName} do app disponível. Toque para atualizar em Ajustes.", icon = Icons.Outlined.SystemUpdate)
                        }
                    }
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
                section.brief?.let { brief ->
                    item { BriefCard(section, brief, expanded = true, state = state, onOpenArticle = onOpenArticle) }
                }
                if (section.filters.isNotEmpty()) {
                    item { TopicChips(section.filters, topic) { topic = it } }
                }
                val ordered = run {
                    val inSection = feed.events.filter { it.category == section.id && it.matches(topic, arts) }
                    val hl = section.highlights.mapNotNull { id -> inSection.firstOrNull { it.id == id } }
                    hl + inSection.filter { it !in hl }.sortedByDescending { it.score }
                }
                val shown = ordered.take(state.highlightLimit + state.otherLimit)
                val label = topic?.let { t -> section.filters.firstOrNull { it.id == t }?.label } ?: section.label
                item { SectionHeader(label, "${shown.size} de ${ordered.size}", HPad) }
                if (shown.isEmpty()) {
                    item { EmptyState("Nada em $label nesta edição", "Volte na próxima edição ou toque em \"Varrer agora\".", HPad) }
                }
                itemsIndexed(shown, key = { _, e -> "s-" + e.id }) { i, event ->
                    val lead = leadArticle(event, arts) ?: return@itemsIndexed
                    val sub = lead.subcategory?.let { feed.subcategories[it] }
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
                        "Isso é tudo nesta edição. A próxima sai ${Fmt.nextEdition()}.",
                        HPad,
                        action = { TextButton(onClick = { sectionId = null; topic = null }) { Text("Voltar à capa") } },
                    )
                }
                return@LazyColumn
            }

            // ---------------- Capa ----------------
            val highlights = feed.highlights.mapNotNull { state.eventsById[it] }.take(state.highlightLimit)
            val readCount = highlights.count(isRead)
            item { SectionHeader("Destaques", "$readCount de ${highlights.size} lidos", HPad) }
            itemsIndexed(highlights, key = { _, e -> e.id }) { i, event ->
                val lead = leadArticle(event, arts)
                val secLabel = sections.firstOrNull { it.id == event.category }?.label
                Box(HPad) {
                    HighlightCard(
                        rank = i + 1,
                        event = event,
                        lead = lead,
                        subcategoryLabel = secLabel ?: (lead?.subcategory ?: event.subcategory)?.let { feed.subcategories[it] },
                        read = isRead(event),
                        onClick = { lead?.let { onOpenArticle(it.id) } },
                    )
                }
            }

            val withBriefs = sections.filter { it.brief != null }
            if (withBriefs.isNotEmpty()) {
                item { SectionHeader("Resumo das seções", "${withBriefs.size}", HPad) }
                items(withBriefs, key = { "b-" + it.id }) { sec ->
                    BriefCard(sec, sec.brief!!, expanded = false, state = state, onOpenArticle = onOpenArticle) {
                        sectionId = sec.id
                    }
                }
            } else if (sections.isNotEmpty()) {
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
                val allRead = highlights.isNotEmpty() && readCount == highlights.size
                EndCard(
                    title = if (allRead) "Você está em dia" else "Fim da capa",
                    body = (if (allRead) "Você leu todos os destaques. " else "") +
                        "Abra uma seção acima para ver o resto. A próxima edição sai ${Fmt.nextEdition()}.",
                    modifier = HPad,
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

/** Resumo da seção: tópicos ligados às notícias de origem. Na capa aparece resumido e leva à seção. */
@Composable
private fun BriefCard(
    section: Section,
    brief: SectionBrief,
    expanded: Boolean,
    state: UiState,
    onOpenArticle: (String) -> Unit,
    onOpenSection: (() -> Unit)? = null,
) {
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (expanded) 0.45f else 0.3f),
        modifier = HPad.fillMaxWidth().padding(top = if (expanded) 16.dp else 0.dp, bottom = 12.dp)
            .then(if (onOpenSection != null) Modifier.clickable(onClick = onOpenSection) else Modifier),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(if (expanded) "Resumo da seção" else section.label, modifier = Modifier.weight(1f))
                if (!expanded) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(brief.headline, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                brief.overview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                brief.points.forEach { p ->
                    val target = p.eventIds.firstNotNullOfOrNull { id -> state.eventsById[id]?.let { leadArticle(it, state.articlesById) } }
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = target != null) { target?.let { onOpenArticle(it.id) } }.padding(vertical = 6.dp),
                    ) {
                        Text("—", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(10.dp))
                        Text(p.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        if (target != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                brief.watch?.let {
                    Spacer(Modifier.height(8.dp))
                    Eyebrow("O que acompanhar", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Resumo por ${brief.author}${brief.model?.let { " ($it)" } ?: ""} · ${Fmt.dateTime(brief.generatedAt)}" +
                    (if (brief.stale) " · a seção mudou desde então" else "") +
                    (if (expanded) ". Toque num tópico para ver a notícia de origem." else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TodayHeader(feed: LatestFeed?, section: Section?, scan: ScanState, onScan: () -> Unit) {
    val scanning = scan is ScanState.Starting || scan is ScanState.Running
    Column(HPad.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "FAROL",
                style = TextStyle(fontSize = 13.sp, letterSpacing = 5.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onScan, enabled = !scanning, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Varrendo…" else "Varrer agora")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(section?.label ?: feed?.edition?.label ?: "Hoje", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        if (feed != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                (if (section != null) "${feed.edition.label} · " else "") + "Atualizada em ${Fmt.dateTime(feed.generatedAt)}",
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
