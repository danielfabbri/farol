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
import androidx.compose.material3.OutlinedTextField
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
) {
    var confirmClear by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
    ) {
        ScreenTitle("Configurações", null)

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
            "O botão \"Varrer agora\" pede ao GitHub Actions uma coleta imediata em todas as fontes, com análise da IA. " +
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
        Hint("Cada varredura usa a mesma cota gratuita das edições automáticas; o próprio sistema limita as chamadas à IA por dia.")

        Section("Tema")
        Choice(
            options = listOf(ThemeMode.DARK to "Escuro", ThemeMode.LIGHT to "Claro", ThemeMode.SYSTEM to "Sistema"),
            selected = state.theme,
            onSelect = onTheme,
        )

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
            "Notícias coletadas de feeds RSS públicos e resumidas por IA (Gemini). Resumos podem conter erros: " +
                "abra sempre a publicação original. Escores e potenciais são avaliações editoriais da IA, não fatos.",
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
