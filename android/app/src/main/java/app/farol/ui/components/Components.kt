package app.farol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.farol.data.model.Article
import app.farol.data.model.Event
import app.farol.util.Fmt

val CardShape = RoundedCornerShape(20.dp)

@Composable
fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)
}

@Composable
fun SectionHeader(title: String, trailing: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Relevância editorial atribuída pela IA (0–100), em 5 barras. Não é um fato objetivo. */
@Composable
fun ImportanceIndicator(score: Int?, modifier: Modifier = Modifier) {
    val filled = if (score == null) 0 else ((score + 10) / 20).coerceIn(1, 5)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        repeat(5) { i ->
            Box(
                Modifier
                    .width(4.dp)
                    .height((6 + i * 2.5f).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i < filled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
            )
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val bg = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun ReadDot(read: Boolean) {
    if (read) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = "Lida", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    } else {
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
fun HighlightCard(
    rank: Int,
    event: Event,
    lead: Article?,
    subcategoryLabel: String?,
    read: Boolean,
    onClick: () -> Unit,
) {
    val title = lead?.displayTitle ?: event.title
    val summary = lead?.preview ?: event.summary
    Surface(
        onClick = onClick,
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column {
        NewsImage(lead?.imageUrl, event.category, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%02d".format(rank),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Eyebrow(subcategoryLabel.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                ImportanceIndicator(lead?.relevanceScore ?: event.relevanceScore)
                Spacer(Modifier.width(10.dp))
                ReadDot(read)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            SourceLine(
                primary = lead?.sourceName ?: event.sources.firstOrNull()?.sourceName.orEmpty(),
                extraSources = (event.sourceCount - 1).coerceAtLeast(0),
                time = Fmt.relative(lead?.publishedOrCollected ?: event.lastSeen),
                aiProcessed = lead?.aiProcessed ?: event.aiProcessed,
            )
        }
        }
    }
}

/**
 * Imagem publicada pelo próprio veículo (carregada do endereço original; nada é copiado).
 * Sem imagem — ou se ela não carregar — mostra a ilustração da seção, para nenhum card ficar vazio.
 */
@Composable
fun NewsImage(url: String?, category: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    if (url == null) {
        SectionArt(category, modifier, compact)
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) },
        error = { SectionArt(category, Modifier.fillMaxSize(), compact) },
    )
}

fun sectionIcon(category: String): ImageVector = when (category) {
    "brasil" -> Icons.Outlined.Flag
    "politica" -> Icons.Outlined.AccountBalance
    "economia" -> Icons.Outlined.Paid
    "mundo" -> Icons.Outlined.Public
    "ciencia_saude" -> Icons.Outlined.Science
    "technology_ai" -> Icons.Outlined.Memory
    "robotica" -> Icons.Outlined.SmartToy
    "espaco" -> Icons.Outlined.RocketLaunch
    else -> Icons.Outlined.Newspaper
}

/** Ilustração da seção: degradê com o ícone (usada quando a notícia não tem foto). */
@Composable
fun SectionArt(category: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.background(Brush.linearGradient(listOf(c.primaryContainer, c.surfaceContainerHighest))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            sectionIcon(category),
            contentDescription = null,
            tint = c.primary.copy(alpha = 0.75f),
            modifier = Modifier.size(if (compact) 28.dp else 48.dp),
        )
    }
}

@Composable
fun SourceLine(primary: String, extraSources: Int, time: String, aiProcessed: Boolean) {
    val parts = buildList {
        add(primary)
        if (extraSources > 0) add("+$extraSources ${if (extraSources == 1) "fonte" else "fontes"}")
        if (time.isNotBlank()) add(time)
    }
    Text(
        parts.joinToString("  ·  "),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun CompactArticleRow(article: Article, subcategoryLabel: String?, read: Boolean, extraSources: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            if (subcategoryLabel != null) {
                Eyebrow(subcategoryLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                article.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                color = if (read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            SourceLine(article.sourceName, extraSources, Fmt.relative(article.publishedOrCollected), article.aiProcessed)
        }
        Spacer(Modifier.width(12.dp))
        NewsImage(article.imageUrl, article.category, Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)), compact = true)
    }
}

enum class NoticeTone { Info, Warning }

@Composable
fun NoticeCard(text: String, tone: NoticeTone = NoticeTone.Info, icon: ImageVector = Icons.Outlined.Info) {
    val color = if (tone == NoticeTone.Warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Marca explícita de fim: o app não tem feed infinito. */
@Composable
fun EndCard(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
            .clip(CardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
