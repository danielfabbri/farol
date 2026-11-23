package app.farol.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.farol.ui.ArchiveState
import app.farol.ui.UiState
import app.farol.ui.components.CompactArticleRow
import app.farol.ui.components.EmptyState
import app.farol.ui.components.EndCard
import app.farol.ui.components.NoticeCard
import app.farol.ui.components.SectionHeader
import app.farol.util.Fmt

@Composable
fun ScreenTitle(title: String, subtitle: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fold(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

private fun app.farol.data.model.Article.matches(terms: List<String>): Boolean {
    if (terms.isEmpty()) return true
    val hay = fold(listOfNotNull(displayTitle, originalTitle, summary, excerptPt, excerpt, sourceName).joinToString(" ") + " " + entities.joinToString(" "))
    return terms.all { it in hay }
}

@Composable
fun ExploreScreen(state: UiState, onOpenArticle: (String) -> Unit, onOpenDay: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    val feed = state.feed
    val sections = feed?.sections.orEmpty().sortedBy { it.order }
    val terms = fold(query).split(" ").filter { it.length >= 2 }
    val base = feed?.articles.orEmpty().filter { (section == null || it.category == section) && it.matches(terms) }
    val sourceCounts = base.groupingBy { it.sourceName }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
    val activeSource = source?.takeIf { s -> sourceCounts.any { it.first == s } }
    val results = base.filter { activeSource == null || it.sourceName == activeSource }
        .sortedByDescending { it.timeMillis }

    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
        item { Box(Modifier.padding(horizontal = 20.dp)) { ScreenTitle("Explorar", "Notícias das últimas 48 horas.") } }
        if (feed == null) {
            item { EmptyState("Sem dados ainda", "Atualize a edição na tela Hoje.") }
            return@LazyColumn
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Buscar notícias, pessoas, empresas…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Limpar busca") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp),
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 14.dp),
            ) {
                item { FilterChip(selected = section == null, onClick = { section = null }, label = { Text("Todas as seções") }) }
                items(sections, key = { it.id }) { s ->
                    FilterChip(selected = section == s.id, onClick = { section = if (section == s.id) null else s.id }, label = { Text(s.label) })
                }
            }
        }
        if (sourceCounts.isNotEmpty()) {
            item {
                Text(
                    "FONTES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
                )
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = activeSource == null, onClick = { source = null }, label = { Text("Todas") }) }
                    items(sourceCounts, key = { it.first }) { (name, count) ->
                        FilterChip(
                            selected = activeSource == name,
                            onClick = { source = if (activeSource == name) null else name },
                            label = { Text("$name · $count", style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
        }
        item {
            val title = when {
                terms.isNotEmpty() -> "Resultados"
                activeSource != null -> activeSource
                section != null -> sections.firstOrNull { it.id == section }?.label ?: ""
                else -> "Todas as notícias"
            }
            SectionHeader(title, "${results.size}", Modifier.padding(horizontal = 20.dp))
        }
        if (results.isEmpty()) {
            item {
                EmptyState(
                    if (terms.isNotEmpty()) "Nada encontrado para \"$query\"" else "Nada aqui agora",
                    "A busca cobre as notícias das últimas 48 horas. Para dias anteriores, use o arquivo histórico abaixo.",
                    Modifier.padding(horizontal = 20.dp),
                )
            }
        }
        items(results, key = { it.id }) { a ->
            Column(Modifier.padding(horizontal = 20.dp)) {
                CompactArticleRow(a, state.labelFor(a), a.id in state.readIds) { onOpenArticle(a.id) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item { SectionHeader("Arquivo histórico", null, Modifier.padding(horizontal = 20.dp)) }
        item {
            Text(
                "Edições anteriores ficam separadas dos destaques. Cada dia é baixado só quando você o abre.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
        }
        items(feed.archiveDays.reversed(), key = { "d-$it" }) { day ->
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenDay(day) }.padding(vertical = 14.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Fmt.dayLabel(day), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveDayScreen(
    day: String,
    archive: ArchiveState?,
    state: UiState,
    onLoad: (String) -> Unit,
    onBack: () -> Unit,
    onOpenArticle: (String) -> Unit,
) {
    LaunchedEffect(day) { onLoad(day) }
    val subs = state.feed?.subcategories.orEmpty()
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0.dp),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(Fmt.dayLabel(day), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") } },
            )
        },
    ) { padding ->
        when (archive) {
            null, ArchiveState.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is ArchiveState.Error -> Column(Modifier.padding(padding).padding(20.dp)) { NoticeCard(archive.message) }
            is ArchiveState.Loaded -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                val list = archive.articles.sortedByDescending { it.timeMillis }
                if (list.isEmpty()) item { EmptyState("Dia sem notícias", "Nenhuma notícia registrada nesta data.") }
                items(list, key = { it.id }) { a ->
                    CompactArticleRow(a, state.labelFor(a), a.id in state.readIds) { onOpenArticle(a.id) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (list.isNotEmpty()) item { EndCard("Fim do dia", "${list.size} notícias registradas em ${Fmt.dayLabel(day).lowercase()}.") }
            }
        }
    }
}

@Composable
fun SavedScreen(state: UiState, onOpenArticle: (String) -> Unit) {
    val subs = state.feed?.subcategories.orEmpty()
    val items = state.saved.mapNotNull { s -> state.savedArticles[s.id] }
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
        item { ScreenTitle("Salvos", "Guardados neste aparelho. Continuam disponíveis mesmo sem internet.") }
        if (items.isEmpty()) {
            item { EmptyState("Nada salvo ainda", "Toque no marcador de uma notícia para guardá-la aqui.") }
        } else {
            item { Spacer(Modifier.height(16.dp)) }
        }
        items(items, key = { it.id }) { a ->
            CompactArticleRow(a, state.labelFor(a), read = false) { onOpenArticle(a.id) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
