# Configuração

Passo a passo para colocar o FAROL em funcionamento. Nada aqui exige cartão de crédito.

## 1. Chave da Gemini API (gratuita)

1. Acesse <https://aistudio.google.com/apikey> e crie uma chave (ou use a que você já tem).
2. **Confirme que o projeto da chave está no plano gratuito.** No AI Studio, abra *Usage & Billing* (ou *Plano*) e verifique que o projeto aparece como **Free tier** e que **nenhuma conta de faturamento** está vinculada. Enquanto não houver faturamento vinculado, o Google não tem como cobrar. Essa é a garantia principal contra custos.
3. Nunca coloque a chave em arquivos versionados. Para testes locais, use o arquivo `.env` na raiz (já ignorado pelo Git):

   ```
   GEMINI_API_KEY=sua-chave
   ```

## 2. Cadastrar o segredo no GitHub

No repositório `danielfabbri/farol` no GitHub:

1. **Settings** (aba no topo do repositório) → no menu lateral, **Secrets and variables** → **Actions**.
2. Na aba **Secrets**, clique em **New repository secret**.
3. *Name*: `GEMINI_API_KEY` · *Secret*: cole a chave · **Add secret**.

### Variáveis opcionais

Na mesma tela, use a aba **Variables** → **New repository variable**. Todas têm valores padrão seguros e só devem ser criadas se você quiser mudar algo:

| Variável | Padrão | Para quê |
|---|---|---|
| `GEMINI_MODEL` | `gemini-flash-latest` | Modelo principal (um alias que acompanha o Flash atual) |
| `GEMINI_FALLBACK_MODELS` | `gemini-3.5-flash,gemini-3.5-flash-lite,gemini-flash-lite-latest` | Modelos Flash gratuitos usados só quando o principal está sobrecarregado (503) ou foi descontinuado (404) |
| `FAROL_MAX_AI_CALLS_PER_DAY` | `20` | Teto diário de chamadas (contado no fuso do Pacífico, como a cota do Google) |
| `FAROL_MAX_AI_CALLS_PER_RUN` | `5` | Teto por execução |

Para ver os modelos disponíveis para a sua chave:

```bash
curl -s -H "x-goog-api-key: $GEMINI_API_KEY" "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200" | grep '"name"'
```

## 3. Permissão de escrita do workflow

O workflow já declara `permissions: contents: write`. Se o passo **Commit dos dados** falhar com erro 403:

**Settings** → **Actions** → **General** → seção **Workflow permissions** → marque **Read and write permissions** → **Save**.

## 4. Primeira execução (manual)

1. Aba **Actions** do repositório. Se aparecer um aviso para habilitar workflows, clique em **I understand my workflows, go ahead and enable them**.
2. No menu lateral, selecione **FAROL — coleta e inteligência**.
3. Clique em **Run workflow** → branch `main` → **Run workflow**.
4. Em 2 a 6 minutos, o commit `data: edição …` aparece no repositório e `data/latest.json` passa a existir.
5. Depois disso, o agendamento roda sozinho às 05h30, 11h30, 17h30 e 20h30 (Brasília). O GitHub pode atrasar execuções agendadas alguns minutos.

Confira se os dados estão acessíveis publicamente (é a URL usada pelo app):
<https://raw.githubusercontent.com/danielfabbri/farol/main/data/latest.json>

## 5. Instalar o app no celular Android

### Opção A — Android Studio
1. **File → Open** → selecione a pasta `android/`.
2. Conecte o celular com a **Depuração USB** ativada (*Configurações → Sobre o telefone →* toque 7 vezes em *Número da versão*; depois *Opções do desenvolvedor → Depuração USB*).
3. Clique em **Run ▶**.

### Opção B — linha de comando
```bash
cd android
./gradlew assembleRelease        # Windows: gradlew.bat assembleRelease
```
O APK fica em `android/app/build/outputs/apk/release/app-release.apk` (cerca de 1,5 MB). Copie para o celular e abra. O Android vai pedir permissão para **instalar apps desconhecidos** no app usado para abrir o arquivo.

> O APK de release é assinado com a chave de debug da sua máquina, o que basta para uso pessoal fora da Play Store. Gere as próximas versões **na mesma máquina** para que a atualização instale por cima, sem perder os salvos.

O Gradle precisa de `android/local.properties` apontando para o SDK (o Android Studio cria esse arquivo automaticamente):
```
sdk.dir=C\:/Users/SEU_USUARIO/AppData/Local/Android/Sdk
```

### Botão "Varrer agora" (opcional)

O botão dispara o workflow do GitHub Actions na hora, sem esperar o próximo horário. Para o app ter permissão, crie **uma vez** um token pessoal de escopo mínimo:

1. Em <https://github.com/settings/personal-access-tokens/new> (também acessível pelo app, em **Ajustes → Varredura sob demanda → Criar token no GitHub**), preencha:
   - **Token name:** `FAROL`
   - **Expiration:** 1 ano
   - **Repository access:** *Only select repositories* → `farol`
   - **Permissions → Repository permissions → Actions:** *Read and write*. Nada além disso.
2. Clique em **Generate token**, copie o valor (`github_pat_…`) e cole em **Ajustes → Salvar token**.

O token fica guardado apenas no aparelho e só é enviado para `api.github.com`. Com escopo restrito, ele não consegue ler nem alterar código: apenas iniciar e consultar execuções do workflow deste repositório. Para revogá-lo: GitHub → Settings → Developer settings → Fine-grained tokens.

### Sem dados ainda?
Em **Ajustes → Modo demonstração**, o app usa dados **fictícios** embutidos (marcados como tal) para você conhecer a interface antes da primeira edição real.
