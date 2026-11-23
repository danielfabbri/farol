# Fontes

As fontes ficam em [`backend/config/sources.json`](../backend/config/sources.json). Para adicionar, desativar (`"enabled": false`) ou ajustar uma fonte, basta editar o JSON; o código não muda.

**Todos os endereços foram verificados em 23/11/2026** com uma requisição real: HTTP 200 e itens presentes no feed. Nenhuma URL foi deduzida.

| # | Fonte | Feed | Tipo | Observações |
|---|---|---|---|---|
| 1 | Microsoft Source — AI | `https://news.microsoft.com/source/topics/ai/feed/` | primária | Substitui o antigo Microsoft AI Blog (ver abaixo) |
| 1b | The Official Microsoft Blog | `https://blogs.microsoft.com/feed/` | primária | Filtrado por palavras-chave de IA |
| 2 | Microsoft for Developers | `https://devblogs.microsoft.com/feed/` | primária | Filtrado por palavras-chave |
| 3 | Microsoft Research | `https://www.microsoft.com/en-us/research/feed/` | pesquisa | Filtrado por palavras-chave |
| 4 | OpenAI News | `https://openai.com/news/rss.xml` | primária | Feed com o arquivo completo (1.400+ itens); limitado aos últimos 4 dias |
| 5 | Google — AI (The Keyword) | `https://blog.google/technology/ai/rss/` | primária | |
| 5b | Google Research Blog | `https://research.google/blog/rss/` | pesquisa | |
| 6 | Google DeepMind | `https://deepmind.google/blog/rss.xml` | primária | |
| 8 | NVIDIA Blog | `https://blogs.nvidia.com/feed/` | primária | |
| 9 | Hugging Face Blog | `https://huggingface.co/blog/feed.xml` | primária | Feed com o arquivo completo; limitado aos recentes |
| 10 | MIT Technology Review — AI | `https://www.technologyreview.com/topic/artificial-intelligence/feed` | imprensa | Recusa (403) clientes sem User-Agent no padrão `Mozilla/5.0 (compatible; …)` |
| 11 | Ars Technica — AI | `https://arstechnica.com/ai/feed/` | imprensa | |
| 11b | Ars Technica — Biz & IT | `https://feeds.arstechnica.com/arstechnica/technology-lab` | imprensa | Filtrado por palavras-chave |
| 12 | TechCrunch — AI | `https://techcrunch.com/category/artificial-intelligence/feed/` | imprensa | |
| 13 | Hacker News | `https://news.ycombinator.com/rss` | agregador | Feed oficial da primeira página, filtrado por palavras-chave; o link aponta para a publicação original e o debate fica em `discussion_url` |
| 14 | arXiv — cs.AI | `https://rss.arxiv.org/rss/cs.AI` | pesquisa | ~300 itens/dia; prioridade mínima e no máximo 3 por execução |
| 15 | arXiv — cs.LG | `https://rss.arxiv.org/rss/cs.LG` | pesquisa | Idem |

## Indisponíveis

| Fonte | Situação | Decisão |
|---|---|---|
| **Anthropic News** (`anthropic.com/news`) | Não há feed RSS/Atom oficial. `rss.xml`, `news/rss.xml`, `feed.xml` e `news/feed` retornam 404, e a página não declara feed. | **Sem scraping.** A cobertura sobre Anthropic/Claude vem de veículos que a reportam (TechCrunch, Ars, MIT TR, Hacker News). Se a Anthropic publicar um feed, basta adicioná-lo ao `sources.json`. |
| **Microsoft AI Blog** (`blogs.microsoft.com/ai/feed/`) | Retorna **HTTP 410 (Gone)**: o blog foi desativado. | Substituído pelo feed oficial do Microsoft Source, tópico AI. |

## Campos de uma fonte

| Campo | Significado |
|---|---|
| `id` | Identificador único (minúsculas e hífens) |
| `url` | Endereço do feed, **somente HTTPS** |
| `category` | Categoria de `categories.json` (hoje `technology_ai`) |
| `source_type` | `primary` (fonte original), `press`, `research` ou `aggregator` |
| `priority` | 1–5: ordem de envio à IA e peso no ranking quando não há análise |
| `max_items_per_run` | Teto de itens novos por execução (protege a cota da IA) |
| `require_keywords` | Para feeds genéricos: só aceita itens com palavras-chave da categoria |

## Boas práticas de coleta

- Requisições condicionais (ETag / Last-Modified): feeds que não mudaram respondem 304, sem baixar nada.
- Uma requisição por feed a cada execução (4 por dia), com User-Agent identificado como leitor RSS.
- Nada além do que o próprio feed publica: título, link, data e o trecho fornecido (truncado em 600 caracteres).
