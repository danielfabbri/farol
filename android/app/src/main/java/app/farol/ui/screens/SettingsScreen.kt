package app.farol.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import app.farol.ui.UpdateState
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.farol.BuildConfig
import app.farol.data.SyncResult
import app.farol.data.prefs.ThemeMode
import app.farol.ui.UiState
import app.farol.util.Fmt
import app.farol.ui.NewsReader
import app.farol.ui.VoiceOption
import app.farol.data.prefs.ReporterVoice
import app.farol.data.prefs.VoiceSettings
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.ThumbsUpDown
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import app.farol.data.prefs.UserPreferences

/** Páginas de Ajustes, na ordem do menu (estilo Configurações do Android). */
enum class SettingsPage(val route: String, val title: String) {
    SCAN("scan", "Varrer agora"),
    SYNC("sync", "Sincronização"),
    THEME("theme", "Tema"),
    VOICES("voices", "Vozes"),
    TASTES("tastes", "Seus gostos"),
    LIMITS("limits", "Limite de notícias"),
    DATA("data", "Dados"),
    ABOUT("about", "Sobre"),
    UPDATE("update", "Verificar atualização");

    companion object { fun of(route: String?) = entries.firstOrNull { it.route == route } }
}

@Composable
fun SettingsScreen(
    state: UiState,
    page: SettingsPage?,
    onOpenPage: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onHighlightLimit: (Int) -> Unit,
    onOtherLimit: (Int) -> Unit,
    onDemoMode: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onSaveToken: (String?) -> Unit,
    onScan: () -> Unit,
    onDismissScan: () -> Unit,
    update: UpdateState,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    reader: NewsReader,
    onVoiceSettings: (VoiceSettings) -> Unit,
    onSetTaste: (String, Float?) -> Unit,
    onResetTastes: () -> Unit,
) {
    if (page == null) {
        SettingsMenu(state, update, onOpenPage)
        return
    }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmResetTastes by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        SubpageBar(page.title, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp),
        ) {
            when (page) {
                SettingsPage.SCAN -> {
                    Text(
                        "O botão \"Varrer agora\" pede ao GitHub Actions uma coleta imediata em todas as fontes. Com o token salvo, o app também faz isso sozinho ao ser aberto quando a edição tem mais de 1 hora. " +
                            "Para isso, o app precisa de um token pessoal do GitHub, que fica guardado só neste aparelho.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.hasToken) {
                        Text("✓ Token configurado", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onScan, enabled = state.scan !is app.farol.ui.ScanState.Running && state.scan !is app.farol.ui.ScanState.Starting) {
                                Text("Varrer agora")
                            }
                            OutlinedButton(onClick = { onSaveToken(null) }) { Text("Remover token") }
                        }
                        ScanStatus(state.scan, onDismissScan)
                    } else {
                        Text(
                            "Como criar (uma vez, leva 2 minutos):\n" +
                                "1. Toque em \"Criar token no GitHub\" e entre na sua conta.\n" +
                                "2. Token name: FAROL · Expiration: 1 ano.\n" +
                                "3. Repository access: Only select repositories → farol.\n" +
                                "4. Permissions → Repository permissions → Actions: Read and write.\n" +
                                "5. Generate token, copie e cole abaixo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { uri.openUri("https://github.com/settings/personal-access-tokens/new") }) {
                            Text("Criar token no GitHub")
                        }
                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("Token (github_pat_…)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onSaveToken(tokenInput); tokenInput = "" }, enabled = tokenInput.isNotBlank()) {
                            Text("Salvar token")
                        }
                    }
                    Hint("A IA (Gemini, plano gratuito) só traduz títulos e trechos de fontes estrangeiras; o próprio sistema limita as chamadas por dia.")

                }
                SettingsPage.SYNC -> {
                    Text("Última sincronização: ${Fmt.dateTime(state.lastSync)}", style = MaterialTheme.typography.bodyMedium)
                    state.feed?.let {
                        Text(
                            "Edição atual gerada em ${Fmt.dateTime(it.generatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val status = when (val r = state.lastResult) {
                        SyncResult.Updated -> "Nova edição recebida."
                        SyncResult.NotModified -> "Você já tem a edição mais recente."
                        SyncResult.Offline -> "Sem conexão com a internet."
                        SyncResult.Demo -> "Modo demonstração ativo: nada é baixado."
                        is SyncResult.Failed -> "Falha: ${r.reason}"
                        null -> null
                    }
                    if (status != null) {
                        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRefresh, enabled = !state.syncing) {
                        if (state.syncing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text("Atualizar agora")
                    }
                    Hint("O app também atualiza ao abrir e, quando o Android permite, em segundo plano a cada ~6 horas.")

                }
                SettingsPage.THEME -> Choice(
                    options = listOf(ThemeMode.DARK to "Escuro", ThemeMode.LIGHT to "Claro", ThemeMode.SYSTEM to "Sistema"),
                    selected = state.theme,
                    onSelect = onTheme,
                )
                SettingsPage.VOICES -> VoiceSection(state.voice, reader, onVoiceSettings)
                SettingsPage.TASTES -> TastesSection(state, onSetTaste, onClear = { confirmResetTastes = true })
                SettingsPage.LIMITS -> {
                    Text("Destaques por edição", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Choice(listOf(5 to "5", 7 to "7", 10 to "10"), state.highlightLimit, onHighlightLimit)
                    Spacer(Modifier.height(16.dp))
                    Text("Outras notícias da edição", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Choice(listOf(0 to "Nenhuma", 10 to "10", 15 to "15", 30 to "30"), state.otherLimit, onOtherLimit)
                    Hint("O FAROL não tem feed infinito: cada edição termina. Limites menores, leitura mais focada.")

                }
                SettingsPage.DATA -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Modo demonstração", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Usa dados fictícios embutidos no app, claramente identificados. Para testes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.demoMode, onCheckedChange = onDemoMode)
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { confirmClear = true }) { Text("Limpar cache") }
                    Hint("Apaga edições baixadas e o histórico de leitura. As notícias salvas são mantidas.")

                }
                SettingsPage.ABOUT -> {
                    Text(
                        "Notícias coletadas de feeds RSS públicos. Fontes estrangeiras são traduzidas automaticamente e a tradução pode conter erros: " +
                            "abra sempre a publicação original.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Versão ${BuildConfig.VERSION_NAME}  ·  dados: ${BuildConfig.DATA_BASE_URL.removePrefix("https://")}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingsPage.UPDATE -> {
                    Text("Versão instalada: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    when (update) {
                        UpdateState.Idle, UpdateState.UpToDate -> {
                            if (update == UpdateState.UpToDate) {
                                Text("Você já tem a versão mais recente.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(8.dp))
                            }
                            OutlinedButton(onClick = onCheckUpdate) { Text("Verificar atualização") }
                        }
                        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Verificando…", style = MaterialTheme.typography.bodyMedium)
                        }
                        is UpdateState.Available -> {
                            Text("Nova versão ${update.info.versionName} disponível", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            if (update.info.notes.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(update.info.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = onDownloadUpdate) { Text("Baixar e instalar") }
                        }
                        is UpdateState.Downloading -> {
                            Text("Baixando ${update.info.versionName}… ${(update.progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
                        }
                        is UpdateState.Ready -> {
                            Text("Versão ${update.info.versionName} baixada e verificada.", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = onInstallUpdate) { Text("Instalar agora") }
                            Hint(
                                "Na primeira vez, o Android pede para permitir que o FAROL instale apps: permita e volte aqui para tocar em " +
                                    "\"Instalar agora\" de novo. Seus salvos e ajustes são mantidos."
                            )
                        }
                        is UpdateState.Error -> {
                            Text(update.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onCheckUpdate) { Text("Tentar de novo") }
                        }
                    }

                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpar cache?") },
            text = { Text("As edições baixadas e o histórico de leitura serão apagados. Notícias salvas são mantidas.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClearCache() }) { Text("Limpar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
    if (confirmResetTastes) {
        AlertDialog(
            onDismissRequest = { confirmResetTastes = false },
            title = { Text("Limpar todos os gostos?") },
            text = { Text("Todas as palavras de gosto e não gosto serão apagadas. O feed volta a ordenar só pela relevância e pelo horário.") },
            confirmButton = { TextButton(onClick = { confirmResetTastes = false; onResetTastes() }) { Text("Limpar") } },
            dismissButton = { TextButton(onClick = { confirmResetTastes = false }) { Text("Cancelar") } },
        )
    }
}

/** Primeira tela de Ajustes: só a lista de áreas, cada uma com resumo e setinha. */
@Composable
private fun SettingsMenu(state: UiState, update: UpdateState, onOpen: (SettingsPage) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Box(Modifier.padding(horizontal = 20.dp)) { ScreenTitle("Configurações", null) }
        Spacer(Modifier.height(8.dp))
        SettingsPage.entries.forEach { p ->
            val (icon, summary) = when (p) {
                SettingsPage.SCAN -> Icons.Outlined.Radar to when (val sc = state.scan) {
                    app.farol.ui.ScanState.Starting, is app.farol.ui.ScanState.Running -> "Varredura em andamento…"
                    is app.farol.ui.ScanState.Done -> sc.message
                    is app.farol.ui.ScanState.Failed -> sc.message
                    app.farol.ui.ScanState.Idle -> if (state.hasToken) "Coleta imediata em todas as fontes" else "Configurar token do GitHub"
                }
                SettingsPage.SYNC -> Icons.Outlined.Sync to "Última: ${Fmt.dateTime(state.lastSync)}"
                SettingsPage.THEME -> Icons.Outlined.Palette to when (state.theme) {
                    ThemeMode.DARK -> "Escuro"
                    ThemeMode.LIGHT -> "Claro"
                    ThemeMode.SYSTEM -> "Sistema"
                }
                SettingsPage.VOICES -> Icons.Outlined.RecordVoiceOver to
                    (if (state.voice.reporters == 1) "1 repórter" else "2 repórteres") + " · velocidade e voz"
                SettingsPage.TASTES -> Icons.Outlined.ThumbsUpDown to app.farol.data.Affinity.all(state.affinity).size.let {
                    if (it == 0) "Nenhum gosto ainda" else "$it de ${UserPreferences.MAX_TASTES} palavras · toque para editar"
                }
                SettingsPage.LIMITS -> Icons.Outlined.FormatListNumbered to
                    "${state.highlightLimit} destaques · ${if (state.otherLimit == 0) "nenhuma outra" else "${state.otherLimit} outras"}"
                SettingsPage.DATA -> Icons.Outlined.Storage to (if (state.demoMode) "Modo demonstração ativo" else "Modo demonstração e cache")
                SettingsPage.ABOUT -> Icons.Outlined.Info to "Fontes, traduções e versão"
                SettingsPage.UPDATE -> Icons.Outlined.SystemUpdate to when (update) {
                    is UpdateState.Available -> "Nova versão ${update.info.versionName} disponível"
                    is UpdateState.Ready -> "Versão ${update.info.versionName} pronta para instalar"
                    is UpdateState.Downloading -> "Baixando…"
                    else -> "Versão instalada: ${BuildConfig.VERSION_NAME}"
                }
            }
            val highlight = p == SettingsPage.UPDATE && (update is UpdateState.Available || update is UpdateState.Ready)
            MenuRow(icon, p.title, summary, highlight) { onOpen(p) }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, summary: String, highlight: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SubpageBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Voltar") }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Ajustes → Seus gostos: até 100 palavras, editáveis (gosto ↔ não gosto, apagar, adicionar). */
@Composable
private fun TastesSection(state: UiState, onSet: (String, Float?) -> Unit, onClear: () -> Unit) {
    val all = app.farol.data.Affinity.all(state.affinity)
    var input by remember { mutableStateOf("") }
    Text(
        "Arraste um card para a direita (gostei) ou para a esquerda (não gostei), ou use as mãozinhas dentro da notícia. " +
            "O FAROL guarda as palavras-chave do tema e destaca as de que você gosta. Guarda até ${UserPreferences.MAX_TASTES} palavras; " +
            "quando passa disso, ficam as mais fortes.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Text("Adicionar", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it.replace("\n", "").replace("\t", "") },
        label = { Text("Palavras (separe por vírgula)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    val words = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { words.forEach { onSet(it, ADDED_WEIGHT) }; input = "" }, enabled = words.isNotEmpty()) {
            Icon(Icons.Outlined.ThumbUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Gosto")
        }
        OutlinedButton(onClick = { words.forEach { onSet(it, -ADDED_WEIGHT) }; input = "" }, enabled = words.isNotEmpty()) {
            Icon(Icons.Outlined.ThumbDown, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Não gosto")
        }
    }

    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${all.size} de ${UserPreferences.MAX_TASTES} palavras",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (all.isNotEmpty()) TextButton(onClick = onClear) { Text("Limpar todos") }
    }
    if (all.isEmpty()) {
        Hint("Nenhum gosto registrado ainda.")
        return
    }
    Hint("Toque na mãozinha para trocar entre gosto e não gosto. A barra mostra a força de cada palavra.")
    Spacer(Modifier.height(8.dp))
    all.forEach { (word, weight) ->
        TasteRow(
            word = word,
            weight = weight,
            onLike = { if (weight <= 0) onSet(word, kotlin.math.abs(weight).coerceAtLeast(ADDED_WEIGHT)) },
            onDislike = { if (weight >= 0) onSet(word, -kotlin.math.abs(weight).coerceAtLeast(ADDED_WEIGHT)) },
            onDelete = { onSet(word, null) },
        )
    }
}

private const val ADDED_WEIGHT = 3f

@Composable
private fun TasteRow(word: String, weight: Float, onLike: () -> Unit, onDislike: () -> Unit, onDelete: () -> Unit) {
    val liked = weight > 0
    val color = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(word, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (kotlin.math.abs(weight) / 8f).coerceIn(0.05f, 1f) },
                modifier = Modifier.fillMaxWidth(0.6f).height(3.dp),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
        }
        IconButton(onClick = onLike) {
            Icon(
                if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = "Gosto",
                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDislike) {
            Icon(
                if (!liked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                contentDescription = "Não gosto",
                tint = if (!liked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Close, contentDescription = "Apagar $word", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val RATES = listOf(0.8f to "0,8×", 1.0f to "1×", 1.2f to "1,2×", 1.5f to "1,5×", 1.8f to "1,8×")
private val PITCHES = listOf(0.8f to "Grave", 1.0f to "Normal", 1.2f to "Agudo")
private fun nearest(options: List<Pair<Float, String>>, v: Float) = options.minBy { kotlin.math.abs(it.first - v) }.first

/** Ajustes → Vozes: 1 ou 2 repórteres, cada um com voz, velocidade e tom. */
@Composable
private fun VoiceSection(voice: VoiceSettings, reader: NewsReader, onChange: (VoiceSettings) -> Unit) {
    var options by remember { mutableStateOf<List<VoiceOption>?>(null) }
    LaunchedEffect(Unit) { options = reader.voiceOptions() }
    val available by reader.available.collectAsState()
    val speakingKey by reader.speakingKey.collectAsState()
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { reader.stop() } }

    if (!available || options?.isEmpty() == true) {
        Text(
            "Este aparelho não tem voz em português instalada. Instale em Configurações do Android → Acessibilidade → Saída de texto para fala.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(10.dp))
    }
    Text("Quem lê as notícias", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    Choice(listOf(1 to "1 repórter", 2 to "2 repórteres"), voice.reporters) { onChange(voice.copy(reporters = it)) }
    Hint(
        if (voice.reporters == 2) "Na leitura automática, os dois se alternam a cada notícia. O ícone de som na foto usa o Repórter 1."
        else "Todas as leituras usam a voz do Repórter 1."
    )
    val count = voice.reporters
    for (idx in 0 until count) {
        val cfg = if (idx == 0) voice.r1 else voice.r2
        val update = { c: ReporterVoice -> onChange(if (idx == 0) voice.copy(r1 = c) else voice.copy(r2 = c)) }
        val key = "preview-$idx"
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Repórter ${idx + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(
                enabled = available,
                onClick = {
                    if (speakingKey == key) reader.stop()
                    else scope.launch {
                        reader.speak("Olá! Eu sou o Repórter ${idx + 1} do Farol. Estas são as notícias de agora.", idx, key)
                    }
                },
            ) { Text(if (speakingKey == key) "Parar" else "Ouvir") }
        }
        Spacer(Modifier.height(6.dp))
        VoicePicker(cfg.voiceName, options.orEmpty(), automaticLabel = if (idx == 0) "Automática (masculina)" else "Automática (feminina)") {
            update(cfg.copy(voiceName = it))
        }
        Spacer(Modifier.height(12.dp))
        Text("Velocidade", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Choice(RATES, nearest(RATES, cfg.rate)) { update(cfg.copy(rate = it)) }
        Spacer(Modifier.height(12.dp))
        Text("Tom", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Choice(PITCHES, nearest(PITCHES, cfg.pitch)) { update(cfg.copy(pitch = it)) }
    }
    Hint("As vozes são as do próprio aparelho (gratuitas). Para ter mais opções, instale vozes em português no app \"Serviços de voz do Google\".")
}

@Composable
private fun VoicePicker(selected: String?, options: List<VoiceOption>, automaticLabel: String, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.name == selected }?.label ?: automaticLabel
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Voz: $label", modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(automaticLabel) }, onClick = { onSelect(null); open = false })
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o.label) }, onClick = { onSelect(o.name); open = false })
            }
        }
    }
}

/** Andamento da varredura sob demanda (o botão fica só aqui, para não ser tocado sem querer). */
@Composable
private fun ScanStatus(scan: app.farol.ui.ScanState, onDismiss: () -> Unit) {
    val (text, busy) = when (scan) {
        app.farol.ui.ScanState.Idle -> return
        app.farol.ui.ScanState.Starting -> "Pedindo a varredura ao GitHub…" to true
        is app.farol.ui.ScanState.Running -> "${scan.step} (costuma levar de 2 a 4 minutos)" to true
        is app.farol.ui.ScanState.Done -> scan.message to false
        is app.farol.ui.ScanState.Failed -> scan.message to false
    }
    Spacer(Modifier.height(12.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    if (busy) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        TextButton(onClick = onDismiss) { Text("Ok") }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(28.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
            ) { Text(label) }
        }
    }
}
