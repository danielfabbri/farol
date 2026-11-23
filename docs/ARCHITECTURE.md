# Arquitetura

## Visão geral

```
                    ┌──────────────────── GitHub Actions (cron 4x/dia + manual) ─────────────────────┐
                    │                                                                                  │
 sources.json ──►  collectors/        storage/            ai/                       ai/               │
                   rss_collector  ──► deduplication ──► event_clusterer ──► news_processor ──► intelligence_analyzer
                   (ETag, backoff)    (URL, título)      (título similar)   (Gemini em lote)   (ranking, edição, tendências)
                    │                                                                                  │
                    └──────────────────────► storage/json_repository ──► data/*.json ──► git commit ──┘
                                                                                │
                                                     raw.githubusercontent.com (HTTPS público)
                                                                                │
                                         App Android: Retrofit/OkHttp (cache + ETag) ──► Room ──► Compose
```

## Pipeline (`backend/pipeline.py`)

1. **Proteção contra execução duplicada.** Se a última execução bem-sucedida terminou há menos de 30 min, a execução agendada é ignorada (a manual sempre roda). O workflow ainda usa `concurrency` para nunca rodar duas instâncias em paralelo.
2. **Coleta** (`collectors/`). Cada feed é baixado com requisição condicional (ETag/Last-Modified) e até 3 tentativas com backoff exponencial. Os textos são normalizados (HTML removido, entidades decodificadas, espaços colapsados, trecho truncado). Itens com mais de 4 dias são descartados, e feeds genéricos passam por filtro de palavras-chave. Uma fonte com falha nunca derruba as demais.
3. **Deduplicação** (`storage/deduplication.py`). O id do artigo é o SHA-1 da URL normalizada (sem `utm_*`, `www`, fragmento ou barra final; versões do arXiv unificadas). Um índice de itens vistos (30 dias) também barra a mesma fonte republicando o mesmo título com outra URL.
4. **Eventos** (`ai/event_clusterer.py`). Notícias de **fontes diferentes** com títulos semelhantes (≥ 3 termos em comum e similaridade ≥ 0,6, janela de 72 h) viram um único evento. Depois, a IA pode indicar `related_event_id` para unir coberturas que dizem o mesmo com palavras diferentes; só artigos isolados são movidos. Nenhuma fonte é descartada: o evento lista todas.
5. **IA** (`ai/news_processor.py` + `ai/gemini_client.py`). Veja abaixo.
6. **Edição** (`ai/intelligence_analyzer.py`). Escore do evento = relevância da IA (ou prioridade da fonte, sem IA) + bônus de cobertura por múltiplas fontes − decaimento por idade. Saem **7 destaques** (configurável) e o restante da janela de 48 h.
7. **Publicação.** `latest.json` é validado contra o modelo Pydantic `LatestFeed` antes de ser gravado. A gravação é atômica (arquivo temporário + `os.replace`).
8. **Tendências.** Contagem determinística de entidades e tecnologias citadas por ≥ 2 fontes em ≥ 3 notícias nos últimos 7 dias, com status emergente, em alta ou recorrente. São sinais, não previsões.
9. **Retenção e estado.**

## IA: uma chamada por lote

A especificação previa módulos separados para classificador, resumidor, agrupador e analisador. **Decisão:** um único prompt por lote de 6 notícias devolve, para cada uma, classificação, resumo, pontos-chave, relevância, entidades, oportunidades e relação com eventos recentes. Quatro chamadas por notícia esgotariam a cota gratuita em poucas horas; em lote, ~20 chamadas por dia processam ~120 notícias.

Garantias:

- **Schema JSON** enviado como `responseJsonSchema` e **validação Pydantic** (`models/analysis.py`): escores limitados a 0–100, listas truncadas, subcategoria verificada contra `categories.json`. Itens inválidos contam como tentativa; após 3, o artigo deixa de ser reenviado.
- **Separação editorial no prompt**: `summary`/`key_points` = fatos reportados; `why_it_matters` = interpretação; `potential_impacts` = hipóteses em linguagem condicional; `uncertainties` = o que não foi possível confirmar. A IA recebe somente título, fonte, data e trecho do feed, com instrução explícita de não acrescentar fatos, números ou citações.
- **Falhas tratadas por tipo**:

| Situação | Comportamento |
|---|---|
| 503 (sobrecarga) | 3 tentativas com backoff (5 s, 10 s); depois, o próximo modelo Flash gratuito. Se todos estiverem sobrecarregados, a IA para nesta execução **sem penalizar** os artigos |
| 404 (modelo descontinuado) | Próximo modelo da lista |
| 429 por minuto | Espera o `retryDelay` (≤ 65 s); se persistir, para nesta execução |
| 429 diário | O modelo fica marcado como esgotado até a cota reiniciar (meia-noite do Pacífico); os demais seguem. Com todos esgotados, nenhuma nova chamada no dia |
| 401/403 | A IA é desativada nesta execução; a coleta continua |

- **Sem fallback pago.** Todos os modelos alternativos são Flash do plano gratuito, e não existe código para outro provedor.

## Dados (`data/`)

| Arquivo | Conteúdo | Retenção |
|---|---|---|
| `latest.json` | Edição atual: destaques, eventos e artigos das últimas 48 h, filtros, estado da IA | sobrescrito a cada execução |
| `articles/AAAA-MM-DD.json` | Todos os artigos do dia (por data de publicação, UTC) | 120 dias |
| `editions/AAAA-MM-DD/HHMM.json` | Instantâneo de cada edição (destaques + artigos) | 21 dias |
| `trends/latest.json`, `trends/AAAA-MM-DD.json` | Tendências | 120 dias |
| `state/*.json` | Cache HTTP, itens vistos, uso da cota, histórico das últimas 60 execuções | contínuo |

O histórico completo continua no Git mesmo após a retenção. O `latest.json` é gravado compacto (~40–100 KB) e os demais com indentação mínima, para diffs legíveis.

## App Android (`android/`)

| Camada | Implementação |
|---|---|
| Rede | Retrofit + OkHttp com cache em disco de 10 MB; o OkHttp envia `If-None-Match` sozinho. "Atualizar" usa `Cache-Control: no-cache` (revalida; 304 se nada mudou) |
| Cache local | Room: documentos JSON (`latest`, `trends`, dias do arquivo), notícias salvas (cópia completa) e notícias lidas (14 dias) |
| Sincronização | Ao abrir o app, manual (botão ou puxar a lista) e periódica via WorkManager (~6 h, com rede) |
| UI | Jetpack Compose + Material 3: Hoje, Explorar (filtros + arquivo por dia), Tendências, Salvos e Ajustes |
| Contrato | `data/model/Models.kt` espelha `LatestFeed`; campos desconhecidos são ignorados (`ignoreUnknownKeys`), então o backend pode evoluir sem quebrar o app |

**Sem consumo infinito:** a edição tem número fixo de destaques, um contador "x de y lidos" e um cartão de **fim da edição** com o horário da próxima. Não há rolagem infinita, autoplay, notificações ou métricas de tempo de tela. O arquivo histórico fica separado e só é baixado quando você abre um dia.

## Seções e resumos de seção

`categories.json` define as **seções** (Brasil, Política, Economia, Mundo, Tecnologia), cada uma com subcategorias, filtros de exclusão e um `ai_context` (o critério editorial que a IA recebe: impacto público em hard news, relevância profissional em Tecnologia). Os **temas** (`filters`) pertencem a uma seção — hoje, Microsoft, OpenAI, Agentes etc. ficam dentro de Tecnologia.

A cada execução, para cada seção:
- **Destaques da seção** (5) e, na **capa**, o melhor de cada seção primeiro e depois os demais por importância (8).
- No máximo 25 notícias por seção no `latest.json`, para nenhuma seção engolir as outras.
- O orçamento da IA é dividido entre as seções que têm pendências.
- **Resumo da seção** (`section_briefer.py`): uma chamada com os 10 principais acontecimentos → manchete, abertura, 3–6 tópicos (cada um ligado aos acontecimentos de origem; tópico sem origem é descartado) e "o que acompanhar". Só é refeito quando o conjunto dos principais acontecimentos muda (assinatura); se a IA falhar, o anterior continua visível marcado como "a seção mudou desde então"; resumos com mais de 36 h deixam de ser exibidos.
- **Modo manual** (`backend/briefing_cli.py`): exporta exatamente o mesmo material e importa resumos escritos fora do pipeline — por exemplo, pelo Claude no VS Code enquanto a cota gratuita da Gemini estiver instável —, com a mesma validação e autoria identificada no app.

Agrupamento entre portais: além de títulos quase iguais, notícias com 4+ termos distintivos em comum em 36 h viram o mesmo acontecimento (g1 e Folha redigem manchetes diferentes para o mesmo fato).

## Atualização do app

`scripts/release_app.py` gera o APK de release, copia para `releases/farol.apk` e escreve `releases/version.json` (versão, tamanho, SHA-256, notas). O app verifica esse arquivo ao abrir e em **Ajustes → Atualizar app**; baixa o APK, confere o SHA-256 e entrega ao instalador do Android (`FileProvider` + permissão `REQUEST_INSTALL_PACKAGES`, que o Android pede para o usuário confirmar). Como o APK é assinado com a mesma chave da instalação anterior, a atualização mantém salvos e ajustes.

## Novas categorias

1. Adicione a categoria em `categories.json` (palavras-chave e subcategorias) e, se quiser, novos filtros.
2. Cadastre fontes com `"category": "<nova>"` em `sources.json`.

O pipeline processa cada categoria com o seu próprio prompt, e o app lê subcategorias e filtros do `latest.json`.
