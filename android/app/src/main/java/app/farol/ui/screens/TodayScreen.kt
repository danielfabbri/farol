package app.farol.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import app.farol.ui.NewsReader
import app.farol.ui.spokenText
import app.farol.data.Affinity
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Done
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
import app.farol.ui.components.ReadFilterButton
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

/** Quanto o gosto desloca uma notícia na ordem cronológica: até 6 horas para cima ou para baixo. */
private const val TASTE_SHIFT_MS = 6 * 3_600_000.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onOpenArticle: (String) -> Unit,
    onOpenExplore: () -> Unit,
    onReadFilter: (app.farol.data.prefs.ReadFilter) -> Unit = {},
    reader: NewsReader,
    onDismiss: (eventId: String, articleId: String, liked: Boolean?) -> Unit = { _, _, _ -> },
) {
    val feed = state.feed
    var sectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var topic by rememberSaveable { mutableStateOf<String?>(null) }
    val sections = feed?.sections.orEmpty().sortedBy { it.order }
    val section = sections.firstOrNull { it.id == sectionId }

    val arts = state.articlesById
    val isRead = { e: Event -> e.articleIds.any { it in state.readIds } }
    val readOnly = state.readFilter == app.farol.data.prefs.ReadFilter.READ
    // Arrastada para o lado = lida e fora do feed (só volta no filtro "Lidas").
    val visible = { e: Event -> state.readFilter.accepts(isRead(e)) && (readOnly || e.id !in state.dismissed) }

    // Gostos aprendidos com o gesto de arrastar: de -1 (não gosta) a 1 (gosta).
    val taste = { e: Event -> Affinity.score(state.affinity, e, leadArticle(e, arts)) }

    // Seção: da mais recente para a mais antiga; o que você gosta sobe (até ~6 h) e o que não gosta desce.
    val sectionOrdered = if (feed != null && section != null) {
        feed.events
            .filter { it.category == section.id && it.matches(topic, arts) && visible(it) }
            .sortedByDescending { it.shownMillis(arts) + (taste(it) * TASTE_SHIFT_MS).toLong() }
    } else emptyList()
    val sectionShown = sectionOrdered.take(state.highlightLimit + state.otherLimit)

    // Capa. Os destaques da edição mudam pouco de uma edição para outra. Filtrar só eles esvaziava a capa
    // em "Não lidas"; então a capa é completada com os destaques das seções e, depois, com os
    // demais acontecimentos por importância, até preencher o limite. Seus gostos mudam quem entra na capa.
    val allHighlights = feed?.highlights.orEmpty().mapNotNull { state.eventsById[it] }.take(state.highlightLimit)
    val capa = if (feed != null && section == null) {
        val highlightIds = (feed.highlights + sections.flatMap { it.highlights }).toSet()
        feed.events
            .filter { leadArticle(it, arts) != null }
            .filter(visible)
            .sortedByDescending { (if (it.id in highlightIds) 100.0 else 0.0) + it.score + taste(it) * 60.0 }
            .take(state.highlightLimit)
            .sortedByDescending { it.shownMillis(arts) + (taste(it) * TASTE_SHIFT_MS).toLong() }
    } else emptyList()

    // ---------------- Leitura automática ----------------
    // Lê título e resumo da primeira notícia da lista (voz masculina e feminina alternadas); ao terminar,
    // o card é jogado para a esquerda e vira "lida", e a próxima assume o topo.
    val queue = (if (section != null) sectionShown else capa).mapNotNull { e -> leadArticle(e, arts)?.let { e to it } }
    val ttsAvailable by reader.available.collectAsState()
    val speakingKey by reader.speakingKey.collectAsState()
    var autoRead by remember { mutableStateOf(false) }
    var swipingId by remember { mutableStateOf<String?>(null) }
    var turn by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val stopReading = { autoRead = false; reader.stop() }
    val openArticle = { id: String -> stopReading(); onOpenArticle(id) }
    // Ícone de som na foto: lê só aquela notícia (Repórter 1). Tocar de novo para.
    val speakOne = { event: Event, lead: Article ->
        val wasThis = speakingKey == lead.id
        stopReading()
        if (!wasThis) scope.launch { reader.speak(spokenText(lead.displayTitle, lead.preview ?: event.summary), 0, lead.id) }
        Unit
    }
    DisposableEffect(Unit) { onDispose { reader.stop() } }
    // Atualização automática não aparece no feed; só a puxada feita por você mostra o indicador.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.syncing) { if (!state.syncing) pulled = false }
    // Posição do primeiro card na lista (cabeçalho, seções, avisos, cabeçalho da lista).
    val firstCardIndex = 3 + (if (sections.isNotEmpty()) 1 else 0) +
        (if (section != null && section.filters.isNotEmpty()) 1 else 0)
    val nextId = queue.firstOrNull()?.first?.id
    LaunchedEffect(autoRead, nextId) {
        if (!autoRead) return@LaunchedEffect
        val (event, lead) = queue.firstOrNull() ?: run { autoRead = false; return@LaunchedEffect }
        runCatching { listState.animateScrollToItem(firstCardIndex) }
        val ok = reader.speak(spokenText(lead.displayTitle, lead.preview ?: event.summary), turn % 2, lead.id)
        if (!ok) { autoRead = false; return@LaunchedEffect }
        turn++
        swipingId = event.id // o card anima para a esquerda e é marcado como lido; a fila anda.
    }

    PullToRefreshBox(
        isRefreshing = pulled && state.syncing,
        onRefresh = { pulled = true; onRefresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
            item {
                TodayHeader(feed, section) {
                    if (feed != null) {
                        ReadAloudButton(
                            playing = autoRead,
                            enabled = autoRead || (!readOnly && queue.isNotEmpty() && ttsAvailable),
                        ) {
                            if (autoRead) stopReading() else { turn = 0; swipingId = null; autoRead = true }
                        }
                        ReadFilterButton(state.readFilter) { stopReading(); onReadFilter(it) }
                    }
                }
            }
            if (sections.isNotEmpty()) {
                item { SectionBar(sections, sectionId) { stopReading(); sectionId = it; topic = null } }
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

            if (section != null) {
                // ---------------- Seção ----------------
                if (section.filters.isNotEmpty()) {
                    item { TopicChips(section.filters, topic) { stopReading(); topic = it } }
                }
                val ordered = sectionOrdered
                val shown = sectionShown
                val label = topic?.let { t -> section.filters.firstOrNull { it.id == t }?.label } ?: section.label
                item { SectionHeader(label, "${shown.size} de ${ordered.size}", HPad) }
                if (shown.isEmpty()) {
                    item { EmptyState("Nada em $label nesta edição", "Volte na próxima edição.", HPad) }
                }
                itemsIndexed(shown, key = { _, e -> "s-" + e.id }) { i, event ->
                    val lead = leadArticle(event, arts) ?: return@itemsIndexed
                    val sub = state.labelFor(lead)
                    val speaking = speakingKey == lead.id
                    val onSpeak: (() -> Unit)? = if (ttsAvailable) ({ speakOne(event, lead) }) else null
                    SwipeToRead(
                        !readOnly, { liked -> onDismiss(event.id, lead.id, liked) }, Modifier.animateItem().then(HPad),
                        speaking = speaking, autoSwipe = swipingId == event.id,
                    ) {
                        if (i < 3) {
                            HighlightCard(i + 1, event, lead, sub, isRead(event), speaking = speaking, onSpeak = onSpeak) { openArticle(lead.id) }
                        } else {
                            Column {
                                CompactArticleRow(
                                    lead, sub, isRead(event), (event.sourceCount - 1).coerceAtLeast(0),
                                    speaking = speaking, onSpeak = onSpeak,
                                ) { openArticle(lead.id) }
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
            val readCount = allHighlights.count(isRead)
            val highlights = capa
            item { SectionHeader("Destaques", "$readCount de ${allHighlights.size} lidos", HPad) }
            if (highlights.isEmpty() && allHighlights.isNotEmpty()) {
                item {
                    EmptyState(
                        if (state.readFilter == app.farol.data.prefs.ReadFilter.READ) "Nenhum destaque lido" else "Nenhum destaque novo",
                        if (state.readFilter == app.farol.data.prefs.ReadFilter.READ) "Você ainda não abriu nenhum destaque desta edição."
                        else "Você já leu todos os destaques desta edição.",
                        HPad,
                    )
                }
            }
            itemsIndexed(highlights, key = { _, e -> e.id }) { i, event ->
                val lead = leadArticle(event, arts) ?: return@itemsIndexed
                val speaking = speakingKey == lead.id
                SwipeToRead(
                    !readOnly, { liked -> onDismiss(event.id, lead.id, liked) }, Modifier.animateItem().then(HPad),
                    speaking = speaking, autoSwipe = swipingId == event.id,
                ) {
                    HighlightCard(
                        rank = i + 1,
                        event = event,
                        lead = lead,
                        subcategoryLabel = state.labelFor(lead) ?: state.sectionLabels[event.category],
                        read = isRead(event),
                        speaking = speaking,
                        onSpeak = if (ttsAvailable) ({ speakOne(event, lead) }) else null,
                        onClick = { openArticle(lead.id) },
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

/**
 * Arrastar o card marca a notícia como lida; ela sai e a de baixo sobe.
 * Para a direita = gostei, para a esquerda = não gostei: isso ensina o feed o que destacar.
 * [onRead] recebe nulo quando o card sai sozinho (leitura automática), sem opinião.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRead(
    enabled: Boolean,
    onRead: (liked: Boolean?) -> Unit,
    modifier: Modifier = Modifier,
    speaking: Boolean = false,
    autoSwipe: Boolean = false,
    content: @Composable () -> Unit,
) {
    val current by androidx.compose.runtime.rememberUpdatedState(onRead)
    var automatic by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != androidx.compose.material3.SwipeToDismissBoxValue.Settled && !automatic && !done) {
                done = true // uma reação só por card
                current(value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd)
            }
            true
        },
        positionalThreshold = { it * 0.35f },
    )
    // Leitura automática: ao terminar de ler, o card sai para a esquerda (sem contar como "não gostei").
    LaunchedEffect(autoSwipe) {
        if (autoSwipe) {
            automatic = true
            swipe.dismiss(androidx.compose.material3.SwipeToDismissBoxValue.EndToStart)
            current(null)
        }
    }
    androidx.compose.material3.SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        gesturesEnabled = enabled,
        backgroundContent = {
            val dir = swipe.dismissDirection
            if (dir == androidx.compose.material3.SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val liked = dir == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val (icon, label, color) = when {
                automatic -> Triple(Icons.Outlined.Done, "Lida", MaterialTheme.colorScheme.primary)
                liked -> Triple(Icons.Outlined.ThumbUp, "Gostei", MaterialTheme.colorScheme.primary)
                else -> Triple(Icons.Outlined.ThumbDown, "Não gostei", MaterialTheme.colorScheme.tertiary)
            }
            Row(
                Modifier.fillMaxSize().padding(bottom = 12.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (liked) Arrangement.Start else Arrangement.End,
            ) {
                Icon(icon, null, tint = color)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            }
        },
    ) {
        // Fundo opaco: o aviso só aparece no espaço que o card deixa ao ser arrastado.
        Box(Modifier.background(MaterialTheme.colorScheme.background)) {
            content()
            // Contorno no card que está sendo lido em voz alta.
            if (speaking) {
                Box(
                    Modifier.matchParentSize().padding(bottom = 12.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CardShape)
                )
            }
        }
    }
}

/** Liga e desliga a leitura automática em voz alta. */
@Composable
private fun ReadAloudButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            if (playing) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = if (playing) "Parar leitura" else "Ler em voz alta",
            tint = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodayHeader(feed: LatestFeed?, section: Section?, trailing: @Composable () -> Unit = {}) {
    Column(HPad.fillMaxWidth().padding(top = 8.dp)) {
        Text("FAROL", style = TextStyle(fontSize = 13.sp, letterSpacing = 5.sp), color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                section?.label ?: feed?.edition?.label ?: "Hoje",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            trailing()
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
private fun SyncNotices(state: UiState) {
    val feed = state.feed
    Column {
        // A busca por notícias novas acontece em silêncio; o andamento aparece só em Ajustes.
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
