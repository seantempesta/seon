---
type: research
status: active
tags: [research, agent]
---

# Web-search verb (`seon.agent.web/search`) — backend research + design

Researched 2026-07-06 against live docs (links preserved below), plus a read of
`src/seon/agent/web.cljs` (the fetch verb's conventions), `src/seon/embed.clj` /
`docs/prds/embeddings/vertex-usage-reference-2026-06-25.md` (the Vertex auth
pattern), `bin/seon` (env plumbing), and `package.json` (SDK inventory).

## TL;DR

- **Wire Gemini "Grounding with Google Search" first, over plain REST
  (`generateContent` + `{"google_search": {}}`), authenticated with the
  `GEMINI_API_KEY` already in the owner's shell env.** Zero new npm deps (the
  pod's `fetch` transport already exists in `seon.agent.web.internal`), zero
  new accounts, and a real free tier: **5,000 grounded prompts/month free on
  Gemini 3 models, then $14/1k search queries**.
- The honest limitation: grounding returns a **synthesized answer + cited
  sources**, not raw SERP rows. Sources come back as `groundingChunks`
  (`web.uri` + `web.title`) where the `uri` is a
  `vertexaisearch.cloud.google.com/grounding-api-redirect/…` redirect (it does
  resolve to the real page when fetched, so it composes with
  `seon.agent.web/fetch`, but it's opaque and time-limited). Per-source
  "snippets" can be derived from `groundingSupports` text segments.
- If/when the owner wants **raw `{title, url, snippet}` SERP rows** (better
  for "pick a URL, then fetch it"), **Serper** is the clear raw-results
  backend: real Google results, 2,500 free queries/month, then ~$0.30–$1.00/1k
  — 15–45× cheaper than grounding at scale. It needs a new key, so it's the
  documented second backend, not the first wire.
- **Google Programmable Search JSON API is ruled out**: closed to new users and
  fully deprecated 2027-01-01.
- **No GEMINI_API_KEY conflict**: the "keep GEMINI_API_KEY unset" rule scopes to
  the **wire-server JVM's env** (so the Java GenAI SDK can't fall back from
  Vertex to the consumer endpoint) — and today `seon.embed` *itself* still
  builds its client from `GEMINI_API_KEY` (`embed.clj:610–618`), so the rule is
  prospective there anyway. The pod is a separate process calling raw REST; it
  can read `GEMINI_API_KEY` safely. When embed migrates to Vertex ADC,
  `bin/seon` unsets the var **for the wire-server spawn only**.
- Design below: one `^:async` verb, `::search-request`/`::search-response`
  Malli schemas with namespaced keys, the same errors-as-values envelope and
  `SEON_WEB` grant as `fetch`, results as capped `{title, url, snippet}` rows
  with honest token totals, backend chosen in `config/system.edn` (the ONE
  manifest), keys via env only.

## 1. Gemini API — Grounding with Google Search (verified 2026-07-06)

### SDK / wire shape

The current docs show two API surfaces; both work on the consumer
("Gemini Developer") API:

- **Interactions API** (newer): `tools: [{ type: "google_search" }]`, response
  as steps (`google_search_call` with the executed `queries`,
  `google_search_result` with `search_suggestions` HTML, and a `model_output`
  step whose text carries `annotations` of type `url_citation` with `url`,
  `title`, `start_index`, `end_index`).
- **`generateContent`** (the shape our JVM `seon.ai.gemini` already uses, and
  what the pod should call over REST): tool is `{"google_search": {}}`.

Official JS SDK is **`@google/genai`** (npm) — **NOT currently in
`package.json`** (the pod has only `@anthropic-ai/sdk` and `openai`). We do
not need it: the call is one POST, and the pod already owns an HTTP transport.

Docs JS example (from [Grounding with Google Search](https://ai.google.dev/gemini-api/docs/google-search)):

```javascript
const groundingTool = { googleSearch: {} };
const response = await ai.models.generateContent({
  model: "gemini-3.5-flash",
  contents: "Who won the euro 2024?",
  config: { tools: [groundingTool] },
});
```

Raw REST equivalent (what the verb would actually do):

```bash
curl -s -X POST \
  "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent" \
  -H "x-goog-api-key: $GEMINI_API_KEY" -H "Content-Type: application/json" \
  -d '{"contents":[{"parts":[{"text":"<query>"}]}],
       "tools":[{"google_search":{}}]}'
```

### What a grounded response returns

`candidates[0].groundingMetadata` carries four things
([generateContent grounding doc](https://ai.google.dev/gemini-api/docs/generate-content/google-search)):

- `webSearchQueries` — the search queries the model actually executed
  (array of strings; each one is a billable unit on Gemini 3).
- `searchEntryPoint.renderedContent` — HTML/CSS for the required "Search
  Suggestions" chip (ToS display requirement for end-user-facing apps).
- `groundingChunks` — the sources: `[{"web": {"uri": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/…", "title": "site.com"}}, …]`.
  The `uri` is a **redirect URL**, not the source URL; `title` is typically
  just the domain. (Vertex docs have historically stated these redirect URIs
  remain accessible for **30 days** — treat them as ephemeral; the redirect
  DOES resolve when fetched, so `seon.agent.web/fetch` on one works.)
- `groundingSupports` — links answer-text `segment`s (with byte offsets) to
  `groundingChunkIndices`, enabling inline citations. This is the only
  per-source "snippet-like" text available.

**You cannot get raw search results (real URLs + independent snippets) out of
grounding** — only the grounded LLM answer plus the citation apparatus above.
That is the fundamental shape difference vs a SERP API.

### Pricing / quotas (verified against the live pricing pages)

From the [Gemini API pricing page](https://ai.google.dev/gemini-api/docs/pricing):

- **Gemini 3.x models** (3.5 Flash, 3.1 Flash-Lite, 3.1 Pro Preview, 3 Flash
  Preview, …): **5,000 grounded prompts/month free (shared across Gemini 3),
  then $14 / 1,000 search queries**. Billing is **per executed search query**
  — one prompt can trigger several queries (`webSearchQueries` shows how many).
- **Gemini 2.5 models**: **1,500 requests/day free** (shared quota), then
  **$35 / 1,000 grounded prompts** (per PROMPT, regardless of query count).
- Plus normal token costs: gemini-3.5-flash $1.50/$9.00 per 1M in/out;
  **gemini-3.1-flash-lite $0.25/$1.50 per 1M in/out** (the cheap pick for a
  search verb — it's a retrieval call, not a reasoning call; the pricing page
  lists Flash-Lite under Gemini-3 grounding billing, so it supports the tool).

From the [Vertex AI pricing page](https://cloud.google.com/vertex-ai/generative-ai/pricing):

- Vertex has the **same tool** at the **same Gemini-3 price** ($14/1k queries,
  5,000/month free aggregated across Gemini 3; Gemini 2.5 = $35/1k prompts,
  free 10,000/day for 2.5 Pro, 1,500/day combined Flash/Flash-Lite). Grounding
  billing on Gemini 3 started 2026-01-05. Notably Vertex states customers
  **"may decide not to display Search Suggestions … at standard pricing"** —
  the display requirement is relaxed there; the consumer-API ToS still asks
  end-user-facing apps to render `searchEntryPoint`. For an internal
  agent-facing verb (no end-user search UI), we record the metadata and note
  the obligation if results are ever shown verbatim in a product UI.
- Grounding-provided input tokens are not charged (Vertex note).

Availability: all current models on BOTH the consumer API and Vertex support
the tool (3.5 Flash, 3.1 variants, 3 Flash/Pro, 2.5 Pro/Flash/Flash-Lite,
2.0 Flash).

Rate limits: the free tiers above are the practical caps (5,000/month Gemini 3;
1,500 RPD Gemini 2.5); >1M grounded prompts/day requires an account team.

## 2. Direct raw-results search APIs (alternatives)

| API | Free tier | Paid | Result shape | Notes for agent use |
|---|---|---|---|---|
| **Serper** ([serper.dev](https://serper.dev)) | **2,500 queries/month** | $5/20k → ~$0.30–$1.00/1k | Raw Google SERP JSON: `organic: [{title, link, snippet, position}]` + answerBox/knowledgeGraph | Cheapest real-Google results; exactly the `{title,url,snippet}` row shape the verb wants |
| **Brave Search API** ([brave.com/search/api](https://brave.com/search/api/)) | Free tier KILLED Feb 2026 → $5/month credit (~1,000 queries), attribution required to keep it | ~$3–$5/1k ($0.003–$0.005/query, metered, no spend cap) | Own index, `web.results[{title, url, description}]` | Independent index, explicitly licensed for AI/LLM use ("Data for AI"); credit card required |
| **Tavily** ([tavily.com](https://tavily.com)) | 1,000 credits/month | PAYG $8/1k; plans $5–$7.50/1k (Bootstrap $100/mo = 15k) | Agent-ready: ranked, filtered, optional extracted content + synthesized answer | Purpose-built for agents but 10–25× Serper's price; its content extraction duplicates our own fetch+readability verb |
| **Exa** ([exa.ai](https://exa.ai)) | 1,000 free/month | $0.001/result (≈$0.01/search at 10 results); +$0.001/page for contents | Neural/semantic search; embeddings-based | Different animal (semantic retrieval, "find pages LIKE this"); complements rather than replaces keyword search |
| **Google Programmable Search JSON API** | 100/day free | $5/1k, hard cap 10k/day | Classic CSE JSON | **CLOSED to new users; fully deprecated 2027-01-01** — do not build on it |

### Raw results vs grounded answer — which fits the verb?

For an agent whose next move is "pick a URL and `fetch` it into a blob", **raw
SERP rows are the better primitive**: real canonical URLs (cacheable,
re-fetchable, joinable with prior fetch projections), independent snippets for
relevance triage, no LLM in the loop (faster, cheaper, no hallucination
surface), and no display-ToS strings attached. Grounding is better when the
question itself needs synthesis ("what changed in X since May?") — the answer
text is genuinely useful context, and the citations still give fetchable (if
redirect-shaped, expiring) URIs.

Conclusion: the verb's RESPONSE SHAPE should be backend-agnostic rows +
an optional `::answer`, so grounding (ships first, key already present) and
Serper (better rows, needs a new key) are config choices behind one schema.
`web.cljs` already anticipated this: its `::link` schema comment says
"shape-compatible with a future search-result row".

## 3. Recommended design — `seon.agent.web/search`

Lives in the existing `seon.agent.web` ns (same grant, same error envelope,
same ns-local keys) with plumbing in `seon.agent.web.internal` — NOT a new
namespace (one mechanism: `fs.cljs` template via `web.cljs`).

### Schemas (namespaced, shared shapes referenced)

```clojure
;; request
(schema/register! ::query        [:string {:min 1}])
(schema/register! ::max-results                              ; default 10
  [:int {:min 1 :max 20}])                                   ; hard cap — safety constraint
(schema/register! ::search-request
  [:map
   [::query       ::query]
   [::max-results {:optional true} ::max-results]
   [::timeout-ms  {:optional true} ::timeout-ms]])           ; reuse fetch's dial

;; result rows — ::url / ::title reuse fetch's registered shapes
(schema/register! ::snippet :string)
(schema/register! ::rank    [:int {:min 0}])   ; 0-based row position
(schema/register! ::result
  [:map
   [::url     ::url]
   [::title   {:optional true} ::title]
   [::snippet {:optional true} ::snippet]
   [::rank    ::rank]])
(schema/register! ::results [:vector ::result])

;; provenance + the grounded-answer arm
(schema/register! ::backend        [:enum :gemini-grounding :serper])
(schema/register! ::answer         :string)             ; grounded answer text (gemini only)
(schema/register! ::answer-tokens  [:int {:min 0}])     ; tokens/estimate — never chars
(schema/register! ::queries        [:vector ::query])   ; webSearchQueries executed (shared shape)
(schema/register! ::result-count   [:int {:min 0}])     ; honest pre-cap total

(schema/register! ::search-response
  [:or
   [:map
    [::ok?           [:= true]]
    [::query         ::query]
    [::backend       ::backend]
    [::results       ::results]
    [::result-count  ::result-count]          ; honest total, pre-cap
    [::answer        {:optional true} ::answer]
    [::answer-tokens {:optional true} ::answer-tokens]
    [::queries       {:optional true} ::queries]
    [::hint          {:optional true} ::hint]]
   ;; COULD-NOT-SEARCH — the shared :seon.error/* shape, matching fetch.
   [:map
    [::ok?               [:= false]]
    [::query             ::query]
    [:seon.error/message :string]
    [:seon.error/data    {:optional true} :map]]])
```

`^:async search`, resolves — never rejects — mirroring `fetch`'s docstring
contract. `ok? false` covers: `SEON_WEB` ungranted (reuse `int/ungranted`),
no backend key in env, HTTP/timeout failure, quota-exceeded (surface the
provider's message verbatim in `:seon.error/message`).

### Backend mapping

- **`:gemini-grounding`** (first wire): POST `generateContent` on
  `gemini-3.1-flash-lite` with `{"google_search": {}}` via the existing
  `internal` transport. Map `groundingChunks[i].web` → `{::url uri ::title
  title ::rank i}`; derive `::snippet` from the `groundingSupports` segments
  citing chunk `i` (join their `segment.text`, token-capped); `::answer` =
  candidate text; `::queries` = `webSearchQueries`. Add a standing `::hint`
  that the URLs are Google redirect URIs (fetchable now, expire ~30 days;
  `fetch`'s `::final-url` recovers the canonical URL).
- **`:serper`**: POST to `https://google.serper.dev/search` with
  `X-API-KEY`; `organic[i]` → `{::url link ::title title ::snippet snippet
  ::rank position}`. No `::answer`.

### Gating + config + env

- **Grant**: the existing `SEON_WEB` default-deny gate governs search too —
  search IS web access; do not mint a second gate. `grants` grows a
  `::search-backend` key (or `:none` when no key is present) so the agent can
  see live what search it has.
- **Config** (the ONE manifest, `config/system.edn` under `:seon.config/web`,
  which today holds `{:seon.agent.web/policy :open}`): add
  `:seon.agent.web/search-backend :gemini-grounding` (+ optional
  `:seon.agent.web/search-model "gemini-3.1-flash-lite"`). Backend choice is
  host-owned config, not env, per the config rule.
- **Keys via env only, never hardcoded**: `GEMINI_API_KEY` (already ambient in
  the owner's shell; `bin/seon`'s `.env` sourcing + shell inheritance deliver
  it to the pod) and later `SERPER_API_KEY`. Note `.env` / `.env.example`
  document them; `bin/seon print-env` is the verification verb for gates.
- **The GEMINI_API_KEY / Vertex "conflict"** — scoped, not real:
  - The CLAUDE.md rule "unset GEMINI_API_KEY" exists so the **Java GenAI SDK
    in the wire-server** can't silently fall back from Vertex to the consumer
    endpoint when `GOOGLE_GENAI_USE_VERTEXAI=true`.
  - Today `seon.embed` (embed.clj:610–618) still constructs its client FROM
    `GEMINI_API_KEY` — the Vertex-ADC wiring in
    `docs/prds/embeddings/vertex-usage-reference-2026-06-25.md` is the target,
    not the shipped state. So there is currently no conflict at all.
  - When embed migrates to Vertex ADC, the fix is process-scoped: `bin/seon`
    unsets `GEMINI_API_KEY` in the **wire-server spawn only** (it already
    conditions env per process — see the `SEON_EMBED` presence-gate block).
    The pod keeps the var for search; the pod calls raw REST (no SDK, no
    fallback ambiguity).
  - Vertex-authed grounding (same tool, ADC) is a viable LATER backend if the
    owner wants governed/no-consumer-key operation — but it would drag OAuth
    (`google-auth-library`) into the Node pod for zero functional gain today.

### Composition + output discipline

- Rows are small; they return inline (no blob) — capped at `::max-results`
  (default 10) with the honest pre-cap `::result-count`. The grounded
  `::answer` is token-capped with `::answer-tokens` (via
  `seon.ai.tokens/estimate`); if an answer ever exceeds the cap it goes to
  `my.blob` like fetch's preview discipline (unlikely at flash-lite lengths).
- The intended agent loop: `search` → pick `::url` → `seon.agent.web/fetch`
  (full page → blob) → `my.blob/text` / `seon.agent.search/grep` to page and
  search it. Search adds NO fetch/extract mechanism of its own.
- Best-effort projection datom (query, backend, result-count, executed
  queries, fetched-at) mirroring fetch's projection, so grep-graph and the
  forensic tooling can see what was searched.

### Verification plan (live probes)

```bash
# 1. Key present in the pod's inherited env (no value printed)
bin/seon print-env; printenv GEMINI_API_KEY >/dev/null && echo "gemini key: present"

# 2. Raw grounding probe — inspect groundingMetadata shape + count webSearchQueries
curl -s -X POST \
  "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent" \
  -H "x-goog-api-key: $GEMINI_API_KEY" -H "Content-Type: application/json" \
  -d '{"contents":[{"parts":[{"text":"current stable Clojure version"}]}],
       "tools":[{"google_search":{}}]}' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin)["candidates"][0]; \
      m=d.get("groundingMetadata",{}); \
      print("queries:", m.get("webSearchQueries")); \
      print("chunks:", [(c["web"]["title"], c["web"]["uri"][:60]) for c in m.get("groundingChunks",[])])'

# 3. Redirect URIs actually resolve (composes with fetch)
curl -sIL "<one groundingChunks uri>" | grep -i "^location\|^HTTP"

# 4. After implementation — the verb end-to-end in the live pod:
#    (await (seon.agent.web/search {:seon.agent.web/query "..."}))
#    then fetch a returned ::url and confirm ::final-url is the canonical page.
#    Falsify: unset SEON_WEB (SEON_WEB=0) → ok? false ungranted envelope;
#    blank GEMINI_API_KEY → ok? false "no search backend key" envelope.
```

## 4. Existing-code findings (read 2026-07-06)

- `src/seon/agent/web.cljs` — the conventions to copy exactly: ns-local
  namespaced keys, `[:or ok-map error-map]` response with the shared
  `:seon.error/*` arm, `int/granted?`/`int/ungranted` gating, honest
  caps/totals in tokens, best-effort projection tx, `^:async` +
  never-reject. Its `::link` row was explicitly designed "shape-compatible
  with a future search-result row".
- `src/seon/ai/gemini.clj` (paused JVM track) — a working `search` fn with
  `:tools [{:google_search {}}]` and `::grounding-metadata`
  (`webSearchQueries` + `groundingChunks`) already exists; it validates the
  wire shape but is NOT the pod's lane. The pod verb is REST-from-Node.
- `src/seon/embed.clj` — wire-server embeddings currently key on
  `GEMINI_API_KEY` (client built at first use; feature master-switch is
  `SEON_EMBED` presence); the Vertex-ADC/global-endpoint config is the
  documented target (vertex-usage-reference-2026-06-25.md).
- `package.json` — no Google SDK present (`@anthropic-ai/sdk`, `openai`,
  readability/linkedom for fetch). Recommendation adds **no** new dependency;
  if the SDK is ever wanted it is `@google/genai` (npm).
- `bin/seon` — sources `.env` (`set -a`), exports `SEON_WEB` default-granted
  (`${SEON_WEB:-1}`) for the dev cluster, and `print-env` is the gate
  inspection verb; per-process env conditioning already exists (the
  `SEON_EMBED` presence-gate), which is where a wire-server-only
  `unset GEMINI_API_KEY` lands post-Vertex-migration.

## Sources

- [Grounding with Google Search — Gemini API docs](https://ai.google.dev/gemini-api/docs/google-search)
- [generateContent grounding variant](https://ai.google.dev/gemini-api/docs/generate-content/google-search)
- [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing) — Gemini 3: 5,000 grounded prompts/mo free, then $14/1k queries; Gemini 2.5: 1,500 RPD free, then $35/1k prompts
- [Vertex AI generative pricing](https://cloud.google.com/vertex-ai/generative-ai/pricing) — same tool/pricing on Vertex; Search-Suggestions display optional at standard pricing; grounded input tokens uncharged
- [Brave Search API pricing](https://api-dashboard.search.brave.com/documentation/pricing) + [free-tier removal coverage](https://www.implicator.ai/brave-drops-free-search-api-tier-puts-all-developers-on-metered-billing/) — $5/mo credit (~1k queries), attribution-conditional
- [Search API pricing comparisons (Tavily/Exa/Serper, 2026)](https://www.buildmvpfast.com/api-costs/ai-search) · [Firecrawl survey](https://www.firecrawl.dev/blog/best-web-search-apis) — Serper 2,500 free/mo, $0.30–$1/1k; Tavily $8/1k PAYG; Exa $0.001/result
- [Custom Search JSON API overview](https://developers.google.com/custom-search/v1/overview) + [deprecation guide](https://blog.expertrec.com/google-custom-search-json-api-simplified/) — closed to new users, deprecated 2027-01-01
- Local: `src/seon/agent/web.cljs`, `src/seon/agent/web/internal.cljs`, `src/seon/ai/gemini.clj`, `src/seon/embed.clj`, `docs/prds/embeddings/vertex-usage-reference-2026-06-25.md`, `bin/seon`, `config/system.edn`, `package.json`
