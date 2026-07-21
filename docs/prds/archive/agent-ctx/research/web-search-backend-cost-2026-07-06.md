---
type: research
status: active
tags: [research, agent]
---

# Web-search backend + true cost of Google web results (`seon.agent.web/search`)

Researched 2026-07-06 against live docs (links preserved), following the owner's
question after the **T4 O5 defect**: the shipped `:gemini-grounding` backend
returns no usable URLs ~2/3 of the time, and we've decided we do NOT need
Gemini/LLM-grounded ANSWERS — we need reliable raw `{title, url, snippet}` SERP
rows an agent picks from, then fetches via `seon.agent.web/fetch`. Read the
backend seam in `src/seon/agent/web.cljs` (+ `web/internal.cljs`): the verb is
already backend-agnostic — `::search-backend [:enum :gemini-grounding :serper
:none]`, row shape `{::url ::title ::snippet ::rank}`, backend chosen in
`config/system.edn`, key read live from env. Today only `:gemini-grounding`
ships; `:serper` is a declared-but-unwired branch that returns a legible refusal.

## TL;DR

- **GCP has NO clean, cheap "give me Google web results as JSON" product.** The
  honest GCP-native verdict, verified today:
  - **Custom Search JSON API is CLOSED to new customers** (a new GCP project
    can NOT enable it) and deprecated for existing users **2027-01-01**. Ruled
    out — we can't even turn it on.
  - **Vertex AI Search** searches YOUR OWN indexed data (or up to ~50 named
    domains), NOT the open web. Not a SERP API.
  - **Grounding (Google Search Grounding $35/1k, Web Grounding for Enterprise
    $45/1k, + model tokens)** is the LLM-answer path we're rejecting — it
    returns citation redirect URIs, not raw SERP rows, and is the exact source
    of the T4 O5 defect.
  - Google's own migration answer for real open-web results is **"contact us
    about our full web search solution"** — sales-gated, no public pricing.
- **Third-party wins, decisively. Recommendation: wire `:serper`
  (serper.dev).** Real Google SERP JSON in the exact row shape the verb already
  expects, **2,500 free queries/month**, then **~$0.30–$1.00 / 1,000 queries**
  ($50/mo = 50k). 15–45× cheaper than grounding, no LLM, no hallucination
  surface, canonical re-fetchable URLs. The verb's `:serper` branch just needs
  wiring + a `SERPER_API_KEY` env read (mirroring `int/gemini-key`).
- Brave Search API is the strongest independent-index fallback (own index,
  explicitly AI-licensed, ~$3–5/1k) if we ever want to avoid a Google-SERP
  reseller.

## Cost table (verified 2026-07-06)

| Backend | $/1,000 queries | Free tier | Real Google results? | GCP-native? | Verdict |
|---|---|---|---|---|---|
| **Serper** (serper.dev) | **$0.30–$1.00** ($50/mo=50k) | **2,500/mo**, no card | **Yes** — real Google SERP JSON (`organic:[{title,link,snippet,position}]`) | No | **RECOMMENDED** |
| Brave Search API | ~$3–5 ($0.003–0.005/q, metered) | $5/mo credit (~1k q), card req'd | Own index (not Google), AI-licensed | No | Best independent-index fallback |
| SerpApi | ~$15 dev ($75/mo=5k) → ~$5 at volume | 100/mo (250 first mo) | Yes (Google + 80 engines) | No | Pricey; only if multi-engine needed |
| Tavily | ~$5–8 (agent plans) | 1,000/mo | Aggregated; agent-shaped | No | Dup's our own fetch+readability |
| Exa | ~$10/search (neural, $0.001/result) | 1,000/mo | Neural/semantic, not keyword SERP | No | Different animal (semantic) |
| **Gemini/Vertex Google Search Grounding** | **$35/1k prompts** + model tokens; 5k/mo (G3) or 1,500/day (G2.5) free | yes | LLM ANSWER + citation redirect URIs, NOT raw SERP | **Yes** | **Shipped backend — the defect; rejected** |
| Vertex Web Grounding for Enterprise | **$45/1k prompts** + model tokens | — | Same grounding shape (not raw SERP) | Yes | Costlier grounding; same wrong shape |
| Vertex AI Search | per-query/per-node (Agent Builder) | — | Over YOUR data / ≤50 domains, NOT open web | Yes | Wrong product (site search) |
| **Google Custom Search JSON API** | $5/1k | 100/day, 10k/day cap | Yes (classic CSE) | Yes | **CLOSED to new users; dead 2027-01-01** |

Real Google SERP as clean JSON, cheapest reliable: **Serper**. GCP-native honest
answer: **there is no such GCP product for the open web** — the one that existed
(CSE) is closed, its successor (Vertex AI Search) is site-search, and open-web
grounding only comes bundled with an LLM answer at 35–45×/1k the price.

## Sources

- Custom Search JSON API — closed to new customers, $5/1k, 100/day, dead
  2027-01-01, migration = Vertex AI Search / "contact us for full web search":
  <https://developers.google.com/custom-search/v1/overview>
- Vertex/Agent Platform grounding pricing ($35/1k Search Grounding, $45/1k Web
  Grounding for Enterprise): <https://cloud.google.com/vertex-ai/generative-ai/pricing>
- Vertex AI Search (site/data search, JSON, ≤50 domains):
  <https://cloud.google.com/use-cases/site-search> ·
  <https://docs.cloud.google.com/generative-ai-app-builder/docs/preview-search-results>
- Serper pricing (2,500 free/mo, $0.30–$1.00/1k, real Google):
  <https://serper.dev/> · comparison: <https://apiserpent.com/blog/serp-api-pricing-comparison>
- Brave / SerpApi / Tavily / Exa comparison:
  <https://scrapfly.io/blog/posts/google-serp-api-and-alternatives> ·
  <https://brightdata.com/blog/web-data/best-serp-apis>

## Recommendation + wiring

**Switch the default `:seon.agent.web/search-backend` to `:serper`** and wire the
existing `:serper` branch in `seon.agent.web/search` (today it returns the
"not wired yet" refusal at `web.cljs:503`). No schema changes: Serper's
`organic[]` maps 1:1 onto `{::url (link) ::title ::snippet ::rank (position-1)}`,
and grounding-only fields (`::answer`, `::queries`) simply stay absent for a raw
backend. Keep `:gemini-grounding` selectable in config for the rare
"synthesize-what-changed" query, but it is no longer the reliable default.

**Auth/env (never hardcode):** add a `SERPER_API_KEY` read to `web/internal.cljs`
alongside `gemini-key` (live `platform/env-val`, same as `SEON_WEB`), plumbed via
`bin/seon`'s pod env. `grants` already reports `::search-backend :none` when the
key is absent — that contract carries over unchanged. Request is one POST to
`https://google.serper.dev/search` with `X-API-KEY` header + `{"q": query, "num":
n}` — reuses the pod's existing `fetch` transport, zero new npm deps, same
`SEON_WEB` grant and errors-as-values envelope as `fetch`.
