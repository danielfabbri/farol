package app.farol.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.farol.data.model.Article
import app.farol.data.model.Event
import app.farol.ui.components.EmptyState
import app.farol.ui.components.Eyebrow
import app.farol.ui.components.NewsImage
import androidx.compose.foundation.layout.aspectRatio
import app.farol.ui.components.ImportanceIndicator
import app.farol.ui.components.NoticeCard
import app.farol.ui.components.Pill
import app.farol.util.Fmt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArticleScreen(
    article: Article?,
    event: Event?,
    subcategoryLabel: String?,
    saved: Boolean,
    onBack: () -> Unit,
    onToggleSave: (Article) -> Unit,
    onMarkRead: (String) -> Unit,
) {
    val uri = LocalUriHandler.current
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0.dp),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar") }
                },
                actions = {
                    if (article != null) {
                        IconButton(onClick = { onToggleSave(article) }) {
                            Icon(
                                if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = if (saved) "Remover dos salvos" else "Salvar",
                                tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (article == null) {
            EmptyState("Notícia indisponível", "Ela pode ter saído da edição atual. Notícias salvas continuam disponíveis em Salvos.", Modifier.padding(padding))
            return@Scaffold
        }
        LaunchedEffect(article.id) { onMarkRead(article.id) }

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 40.dp),
        ) {
            article.imageUrl?.let {
                NewsImage(it, article.category, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)))
                Text(
                    "Imagem: ${article.sourceName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                )
            }
            subcategoryLabel?.let { Eyebrow(it) }
            Spacer(Modifier.height(10.dp))
            Text(article.displayTitle, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            if (article.titlePt != null && article.titlePt != article.originalTitle) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Original: ${article.originalTitle}",
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${article.sourceName}  ·  ${Fmt.sourceType(article.sourceType)}\nPublicado em ${Fmt.date(article.publishedOrCollected)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (article.relevanceScore != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ImportanceIndicator(article.relevanceScore)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Relevância editorial ${article.relevanceScore}/100",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Um parágrafo explicando a matéria; sem ele, o trecho publicado pela fonte.
            when {
                !article.summary.isNullOrBlank() -> Block("Resumo", null) { Body(article.summary) }
                !article.excerptPt.isNullOrBlank() -> Block("Trecho da fonte", "Traduzido automaticamente") { Body(article.excerptPt) }
                article.excerpt.isNotBlank() && article.language == "pt" -> Block("Trecho da fonte", null) { Body(article.excerpt) }
            }

            val chips = (article.entities + article.technologies).distinct()
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { Pill(it) }
                }
            }

            val sources = event?.sources?.takeIf { it.isNotEmpty() }
            Block("Fontes originais", if ((event?.sourceCount ?: 1) > 1) "${event?.sourceCount} veículos cobriram este acontecimento" else null) {
                if (sources == null) {
                    SourceRow(article.sourceName, article.originalTitle, article.publishedAt) { uri.openUri(article.sourceUrl) }
                } else {
                    sources.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SourceRow(s.sourceName, s.title, s.publishedAt) { uri.openUri(s.sourceUrl) }
                    }
                }
                article.discussionUrl?.let { url ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SourceRow("Discussão", "Comentários no agregador", null) { uri.openUri(url) }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(onClick = { uri.openUri(article.sourceUrl) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Abrir publicação original")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (!article.summary.isNullOrBlank()) {
                    "Resumo gerado por IA (Gemini) a partir do título e do trecho publicados pela fonte. Pode conter erros: confira a publicação original."
                } else {
                    "Texto do feed da fonte. Confira sempre a publicação original."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Block(title: String, caption: String?, content: @Composable () -> Unit) {
    Spacer(Modifier.height(26.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    if (caption != null) {
        Spacer(Modifier.height(2.dp))
        Eyebrow(caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(10.dp))
    content()
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Bullets(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Row {
                Text("—", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(item, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceRow(name: String, title: String, publishedAt: String?, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                if (publishedAt != null) {
                    Text(Fmt.dateTime(publishedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Abrir", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}
