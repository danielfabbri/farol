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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExploreScreen(state: UiState, onOpenArticle: (String) -> Unit, onOpenDay: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val feed = state.feed
    val subs = feed?.subcategories.orEmpty()
    val articles = feed?.articles.orEmpty()
        .filter { selected == null || selected in it.tags }
        .sortedWith(compareByDescending<app.farol.data.model.Article> { it.relevanceScore ?: -1 }.thenByDescending { it.publishedOrCollected })

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), modifier = Modifier.fillMaxSize()) {
        item { ScreenTitle("Explorar", "Notícias das últimas 48 horas, por tema.") }
        if (feed == null) {
            item { EmptyState("Sem dados ainda", "Atualize a edição na tela Hoje.") }
            return@LazyColumn
        }
        item {
            FlowRow(
                Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(selected = selected == null, onClick = { selected = null }, label = { Text("Tudo") })
                feed.filters.forEach { f ->
                    FilterChip(
                        selected = selected == f.id,
                        onClick = { selected = if (selected == f.id) null else f.id },
                        label = { Text(f.label) },
                    )
                }
            }
        }
        item { SectionHeader(if (selected == null) "Todas as notícias" else feed.filters.firstOrNull { it.id == selected }?.label ?: "", "${articles.size}") }
        if (articles.isEmpty()) {
            item { EmptyState("Nada neste tema agora", "Nenhuma notícia recente corresponde a este filtro.") }
        }
        items(articles, key = { it.id }) { a ->
            CompactArticleRow(a, a.subcategory?.let { subs[it] }, a.id in state.readIds) { onOpenArticle(a.id) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item { SectionHeader("Arquivo histórico", null) }
        item {
            Text(
                "Edições anteriores ficam separadas dos destaques. Cada dia é baixado só quando você o abre.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(feed.archiveDays.reversed(), key = { "d-$it" }) { day ->
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
                val list = archive.articles.sortedByDescending { it.relevanceScore ?: -1 }
                if (list.isEmpty()) item { EmptyState("Dia sem notícias", "Nenhuma notícia registrada nesta data.") }
                items(list, key = { it.id }) { a ->
                    CompactArticleRow(a, a.subcategory?.let { subs[it] }, a.id in state.readIds) { onOpenArticle(a.id) }
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
            CompactArticleRow(a, a.subcategory?.let { subs[it] }, read = false) { onOpenArticle(a.id) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
