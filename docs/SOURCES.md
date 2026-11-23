# Fontes

As fontes ficam em [`backend/config/sources.json`](../backend/config/sources.json). Para adicionar, desativar (`"enabled": false`) ou ajustar uma fonte, basta editar o JSON; o código não muda.

**Todos os endereços foram verificados em 23/11/2026** com uma requisição real: HTTP 200 e itens presentes no feed. Nenhuma URL foi deduzida.

## Portais brasileiros (hard news)

| Seção | Fonte | Feed |
|---|---|---|
| Política | Agência Brasil — Política | `https://agenciabrasil.ebc.com.br/rss/politica/feed.xml` |
| Política | g1 — Política | `https://g1.globo.com/rss/g1/politica/` |
| Política | Folha — Poder | `https://feeds.folha.uol.com.br/poder/rss091.xml` |
| Política | Poder360 | `https://www.poder360.com.br/feed/` |
| Economia | Agência Brasil — Economia | `https://agenciabrasil.ebc.com.br/rss/economia/feed.xml` |
| Economia | g1 — Economia | `https://g1.globo.com/rss/g1/economia/` |
| Economia | Folha — Mercado | `https://feeds.folha.uol.com.br/mercado/rss091.xml` |
| Economia | InfoMoney | `https://www.infomoney.com.br/feed/` |
| Mundo | Agência Brasil — Internacional | `https://agenciabrasil.ebc.com.br/rss/internacional/feed.xml` |
| Mundo | g1 — Mundo | `https://g1.globo.com/rss/g1/mundo/` |
| Mundo | Folha — Mundo | `https://feeds.folha.uol.com.br/mundo/rss091.xml` |
| Mundo | BBC News Brasil | `https://feeds.bbci.co.uk/portuguese/rss.xml` |
| Brasil | Agência Brasil — Geral | `https://agenciabrasil.ebc.com.br/rss/geral/feed.xml` |
| Brasil | Folha — Cotidiano | `https://feeds.folha.uol.com.br/cotidiano/rss091.xml` |
| Tecnologia | Tecnoblog | `https://tecnoblog.net/feed/` |
| Política | Agência Câmara (oficial) | `https://www.camara.leg.br/noticias/rss/ultimas-noticias` |
| Política | Estadão — Política | `https://www.estadao.com.br/arc/outboundfeeds/feeds/rss/sections/politica/` |
| Economia | Money Times | `https://www.moneytimes.com.br/feed/` |
| Mundo | RFI Brasil | `https://www.rfi.fr/br/rss` |
| Mundo | DW Brasil | `https://rss.dw.com/rdf/rss-br-all` |
| Brasil | g1 — Educação | `https://g1.globo.com/rss/g1/educacao/` |
| Ciência e Saúde | Folha — Ciência / Equilíbrio e Saúde / Ambiente | `https://feeds.folha.uol.com.br/{ciencia,equilibrioesaude,ambiente}/rss091.xml` |
| Tecnologia | Olhar Digital, Canaltech | `https://olhardigital.com.br/feed/`, `https://canaltech.com.br/rss/` |
| Tecnologia | The Verge, Wired — IA (em inglês, traduzidos) | `https://www.theverge.com/rss/index.xml`, `https://www.wired.com/feed/tag/ai/latest/rss` |

### Feeds gerais classificados pela URL

Alguns portais só oferecem um feed com tudo misturado. Nesses, a seção vem do início do caminho da URL (`section_rules`); o que não casa com nenhuma regra (esporte, entretenimento, colunas sociais) é descartado.

| Fonte | Feed | Regras |
|---|---|---|
| CNN Brasil | `https://www.cnnbrasil.com.br/feed/` | `politica/`, `economia/`, `internacional/`→Mundo, `nacional/`→Brasil, `saude/`→Ciência e Saúde |
| Veja | `https://veja.abril.com.br/feed/` | `politica/`, `economia/`, `mundo/`, `brasil/`, `saude/`, `ciencia/` |
| CartaCapital | `https://www.cartacapital.com.br/feed/` | `politica/`, `economia/`, `mundo/`, `sociedade/`→Brasil |
| Exame | `https://exame.com/feed/` | `economia/`, `invest/`, `negocios/`, `mundo/`, `brasil/`, `tecnologia/`, `inteligencia-artificial/`, `ciencia/` |

### Imagens

O endereço da imagem de capa vem do próprio feed (`media:content`, `media:thumbnail`, `enclosure` ou a primeira `<img>` do trecho). O FAROL guarda só o endereço; a imagem continua hospedada pelo veículo e é exibida com crédito. Quando o feed não traz foto (Folha, DW, Agência Brasil...), o pipeline lê o início da página da própria notícia (até 300 KB) e usa a imagem de destaque que o veículo declara (`og:image`/`twitter:image`, a mesma do compartilhamento em redes). Logotipos são descartados e cada notícia é consultada uma única vez (`backend/collectors/image_enricher.py`, até 80 por execução). Sem nenhuma foto, o app mostra a ilustração da seção.

Conteúdo que não é notícia é excluído pelo título (`exclude_keywords` em `categories.json` e em cada fonte): loterias, horóscopo, cupons e prêmios patrocinados nas seções de hard news; ofertas e promoções em Tecnologia.

## Tecnologia e IA (internacionais)

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
| **CNN Brasil (feeds por editoria)** | `/politica/feed/`, `/economia/feed/` etc. retornam 404. | Usado o feed geral, classificado pela URL. |
| **Metrópoles** | Feed geral dominado por colunas e celebridades. | Não cadastrado. |
| **Nexo** | URLs indicam o formato (expresso, ensaio), não a editoria. | Não cadastrado. |
| **ONU News, Agência Senado** | Feeds sem itens. | Não cadastrados. |
| **VentureBeat — AI** | HTTP 429 para clientes automatizados. | Não cadastrado. |
| **g1 — Brasil e g1 — Tecnologia** | `/rss/g1/brasil/` e `/rss/g1/tecnologia/` respondem 200, mas sem itens. | Não cadastradas; a seção Brasil usa Agência Brasil e Folha. |
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
