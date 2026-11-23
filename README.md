# FAROL — Central Pessoal de Inteligência

O FAROL coleta notícias de tecnologia e inteligência artificial em fontes confiáveis, resume tudo em português com IA e entrega **edições curtas e com fim**, quatro vezes por dia, em um app Android. O objetivo é informar sem prender: não há feed infinito, autoplay ou recomendação por tempo de tela.

```
GitHub Actions (4x/dia) ──► coleta RSS ──► deduplicação + eventos ──► Gemini (plano gratuito)
        │                                                                     │
        └──────────── commit em data/*.json ◄──── edição, ranking, tendências ┘
                                   │
                   GitHub Raw (HTTPS, público) ──► App Android (cache Room, ETag)
```

Tudo roda em serviços gratuitos. Não há servidor próprio, banco pago nem computador ligado.

## Estrutura

| Pasta | Conteúdo |
|---|---|
| `backend/` | Pipeline Python: coletores RSS, cliente Gemini, deduplicação, eventos, persistência JSON, testes |
| `backend/config/` | `sources.json` (fontes verificadas) e `categories.json` (categorias, subcategorias e filtros) |
| `data/` | Dados publicados (gerados pelo GitHub Actions): `latest.json`, artigos por dia, edições, tendências |
| `android/` | App Kotlin + Jetpack Compose + Material 3 + Room + Retrofit + WorkManager |
| `.github/workflows/` | Execução agendada (`intelligence.yml`) |
| `docs/` | [Arquitetura](docs/ARCHITECTURE.md), [Fontes](docs/SOURCES.md), [Configuração](docs/SETUP.md), [Operação e custos](docs/OPERATIONS.md) |

## Início rápido

```bash
pip install -r backend/requirements-dev.txt
python -m pytest backend/tests            # testes offline, nunca consomem cota
python -m backend.main --dev              # dados FICTÍCIOS em data-dev/ (fixtures + IA simulada)
python -m backend.main --no-ai --force    # feeds reais, sem IA
python -m backend.main --mock-ai --force  # feeds reais, IA simulada
```

Para colocar em produção (segredo no GitHub, primeira execução e instalação no celular), siga o [docs/SETUP.md](docs/SETUP.md).

## Princípios

- **Somente planos gratuitos.** Sem faturamento e sem fallback para APIs pagas. Se a cota da IA acaba, a coleta continua e as notícias aparecem sem resumo.
- **Sem segredos no repositório.** `GEMINI_API_KEY` vive apenas nos Secrets do GitHub (e num `.env` local ignorado pelo Git).
- **A fonte original vem sempre junto.** O app guarda metadados, link e resumo próprio, nunca o texto integral.
- **Fatos, interpretação e hipóteses aparecem separados.** Escores da IA são avaliações editoriais, não fatos.
