# Operação, cotas e custos

## Custo: zero, se estas condições se mantiverem

| Serviço | Plano | Condição para continuar gratuito |
|---|---|---|
| GitHub Actions | Repositórios **públicos** usam runners padrão sem custo | Manter o repositório público (se ficar privado: 2.000 min/mês grátis; o FAROL usa ~500) |
| GitHub (repo + Raw) | Gratuito | — |
| Gemini API | **Free tier** do Google AI Studio | **Nenhuma conta de faturamento vinculada ao projeto da chave.** Sem faturamento, excedentes viram erro 429, nunca cobrança |
| Android | Instalação direta do APK | Sem Play Store (que cobraria taxa de cadastro) |

## Consumo estimado

### GitHub Actions
- Cada execução dura de 1 a 2 min sem IA e de 3 a 6 min com IA: instalação, testes (~2 s), coleta (~25 s), lotes Gemini (~30–60 s cada, mais o intervalo de 13 s entre chamadas).
- 4 execuções/dia ≈ 10–25 min/dia ≈ **300–750 min/mês**. Sem custo em repositório público.
- `timeout-minutes: 25` limita qualquer execução travada.

### Gemini
- Lotes de **6 notícias** por chamada; teto de **10 chamadas por execução** (divididas entre as seções) e **30 por dia** (configurável).
- **Resumos de seção:** até 6 chamadas por execução e 20 por dia, só quando os principais acontecimentos da seção mudam.
- Medido em 23/11/2026 num lote de 6 notícias: ~1.300 tokens de entrada, ~2.000 de saída e ~3.500 de "raciocínio" do modelo. Total ≈ 7 mil tokens por chamada, **≈ 140 mil tokens/dia** no teto.
- Capacidade: até ~120 notícias analisadas por dia. O volume típico de notícias novas fica entre 30 e 80.

### Limites do plano gratuito da Gemini
O Google muda as cotas com frequência. Os valores atuais por modelo estão em <https://ai.google.dev/gemini-api/docs/rate-limits> e no painel do AI Studio. O que **observamos** em 23/11/2026, ao configurar:

- `gemini-flash-latest` e vários modelos Flash responderam **503 "high demand"** por longos períodos. Prompts maiores eram recusados mais vezes que os pequenos, por isso os lotes são pequenos (6) e o contexto de eventos é limitado (15).
- `gemini-2.5-flash` responde **404 para usuários novos** (descontinuado).
- `gemini-3.5-flash` atingiu a **cota diária** depois de uma série de chamadas de diagnóstico. A cota diária gratuita pode ser pequena, na casa de dezenas de chamadas por modelo.

Por isso o cliente percorre uma lista de modelos Flash gratuitos e controla a cota **por modelo** (ver [ARCHITECTURE.md](ARCHITECTURE.md#ia-uma-chamada-por-lote)).

### Tudo em português, mesmo quando a IA falha
Cada notícia passa por até três camadas, nesta ordem:

1. **Análise completa** (Gemini Flash): título, resumo, pontos-chave, relevância etc., em português.
2. **Tradução leve** (Gemini *lite*, cota própria, ~25 notícias por chamada, até 12 chamadas/dia): título e trecho.
3. **Tradução de reserva, só títulos** ([MyMemory](https://mymemory.translated.net/doc/spec.php), gratuito, sem chave, ~4.500 caracteres/dia): usada quando todos os modelos Gemini falham. Recebe apenas as manchetes públicas.

Textos gerados com caracteres corrompidos (ex.: `c#00f3digo`, `Inteligêêode`) são corrigidos ou descartados, e a notícia volta para a fila. Nomes de produtos podem eventualmente sair traduzidos na camada 3; a análise completa corrige isso quando chega.

## Riscos e mitigação

| Risco | Efeito | Mitigação |
|---|---|---|
| Cota diária da Gemini esgotada | Notícias novas sem resumo | Coleta e edição continuam; o app avisa; pendentes (até 72 h) são processados nas próximas execuções |
| Sobrecarga (503) prolongada | Idem | Modelos alternativos; artigos não são penalizados |
| Mudança de nomes de modelos | 404 | Lista de alternativos + variável `GEMINI_MODEL` |
| Feed muda de endereço ou bloqueia | Fonte com status `error` no log | Execução marcada `partial`; ver `data/state/runs.json`; corrigir em `sources.json` |
| Schedules desativados após 60 dias sem atividade | Workflows agendados param | Os commits diários de dados contam como atividade. Se o GitHub desativar mesmo assim, reative em *Actions* |
| Crescimento do repositório | Clone mais lento | Retenção de 120 dias nos arquivos; JSON comprime bem no Git (~1–3 MB/mês de histórico) |
| Alguém habilitar faturamento no projeto Google | Cobranças possíveis | Não vincular faturamento; tetos de chamadas no código como segunda barreira |

## Resumos das seções escritos no VS Code (temporário)

Enquanto a Gemini gratuita estiver sobrecarregada, os resumos podem ser escritos pelo Claude no VS Code:

```bash
python -m backend.briefing_cli export > pedido.json      # material de cada seção
# o Claude lê pedido.json e escreve respostas.json seguindo as mesmas regras do prompt automático
python -m backend.briefing_cli import respostas.json --author "Claude (VS Code)"
git add data && git commit -m "data: resumos das seções" && git push
```

O app mostra a autoria ("Resumo por Claude (VS Code)"). Na próxima execução automática, o resumo é mantido se a seção não mudou; se mudou e a Gemini conseguir responder, ela o substitui.

## Publicar nova versão do app

1. Aumente `versionCode` e `versionName` em `android/app/build.gradle.kts`.
2. `python scripts/release_app.py --notes "o que mudou"` (gera e verifica o APK).
3. Commit e push de `releases/`. No celular: **Ajustes → Atualizar app → Verificar atualização**.

Gere sempre na mesma máquina (mesma chave de assinatura); com outra chave, o Android recusa a atualização e é preciso desinstalar.

## Monitoramento

- **Aba Actions**: cada execução mostra, por fonte, `ok`, `not_modified` ou `error`, além das chamadas de IA e do estado final.
- **`data/state/runs.json`**: últimas 60 execuções (status, fontes com falha, notícias novas, chamadas de IA, erros resumidos sem segredos).
- **`data/state/ai_usage.json`**: chamadas do dia e modelos esgotados.
- **No app**: *Ajustes* mostra a última sincronização; *Hoje* mostra avisos de IA indisponível, cota atingida ou modo offline.

## Operações comuns

| Tarefa | Como |
|---|---|
| Rodar agora | Actions → *FAROL — coleta e inteligência* → **Run workflow** (ignora a proteção de 30 min) |
| Rodar só a coleta | Run workflow → marcar *Executar somente a coleta, sem IA* |
| Pausar tudo | Actions → workflow → **⋯** → *Disable workflow* |
| Trocar modelo | Settings → Secrets and variables → Actions → Variables → `GEMINI_MODEL` |
| Reduzir uso da IA | Variáveis `FAROL_MAX_AI_CALLS_PER_DAY` / `FAROL_MAX_AI_CALLS_PER_RUN` |
| Desativar uma fonte | `"enabled": false` em `backend/config/sources.json` |
| Testar localmente sem cota | `python -m backend.main --dev` ou `--mock-ai` |

## Segurança

- A chave existe somente em GitHub Secrets (mascarada nos logs pelo próprio GitHub) e no `.env` local, que é ignorado pelo Git.
- Vai apenas no cabeçalho `x-goog-api-key`, nunca na URL. O `repr` do cliente e das configurações a omite, e o formatador de logs substitui qualquer ocorrência por `***`, inclusive em tracebacks.
- O app não tem chave alguma: lê arquivos públicos via HTTPS (tráfego em texto puro bloqueado por `network_security_config`).
- Os dados públicos contêm apenas notícias públicas, sem informações pessoais.
