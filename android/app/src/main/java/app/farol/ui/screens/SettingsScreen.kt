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

@Composable
fun SettingsScreen(
    state: UiState,
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
    onResetTastes: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmResetTastes by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
    ) {
        ScreenTitle("Configurações", null)

        Section("Atualizar app")
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

        Section("Sincronização")
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

        Section("Varredura sob demanda")
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

        Section("Tema")
        Choice(
            options = listOf(ThemeMode.DARK to "Escuro", ThemeMode.LIGHT to "Claro", ThemeMode.SYSTEM to "Sistema"),
            selected = state.theme,
            onSelect = onTheme,
        )

        VoiceSection(state.voice, reader, onVoiceSettings)

        Section("Seus gostos")
        Text(
            if (state.reactions == 0) "Arraste um card para a direita (gostei) ou para a esquerda (não gostei). " +
                "O FAROL guarda as palavras-chave do tema da notícia e passa a destacar os temas de que você gosta."
            else "${state.reactions} ${if (state.reactions == 1) "reação registrada" else "reações registradas"}. " +
                "Direita = gostei, esquerda = não gostei: o FAROL guarda as palavras-chave do tema de cada notícia, " +
                "destaca os temas de que você gosta e afasta os outros.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val liked = app.farol.data.Affinity.top(state.affinity, liked = true)
        val disliked = app.farol.data.Affinity.top(state.affinity, liked = false)
        if (liked.isNotEmpty()) Hint("👍 Mais destaque: ${liked.joinToString(", ")}")
        if (disliked.isNotEmpty()) Hint("👎 Menos destaque: ${disliked.joinToString(", ")}")
        if (state.reactions > 0) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmResetTastes = true }) { Text("Zerar gostos") }
        }

        Section("Limite de notícias")
        Text("Destaques por edição", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Choice(listOf(5 to "5", 7 to "7", 10 to "10"), state.highlightLimit, onHighlightLimit)
        Spacer(Modifier.height(16.dp))
        Text("Outras notícias da edição", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Choice(listOf(0 to "Nenhuma", 10 to "10", 15 to "15", 30 to "30"), state.otherLimit, onOtherLimit)
        Hint("O FAROL não tem feed infinito: cada edição termina. Limites menores, leitura mais focada.")

        Section("Dados")
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

        Section("Sobre")
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
            title = { Text("Zerar gostos?") },
            text = { Text("O feed volta a ordenar só pela relevância e pelo horário, sem considerar suas reações.") },
            confirmButton = { TextButton(onClick = { confirmResetTastes = false; onResetTastes() }) { Text("Zerar") } },
            dismissButton = { TextButton(onClick = { confirmResetTastes = false }) { Text("Cancelar") } },
        )
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

    Section("Vozes")
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
