---
type: research
status: active
tags: [research, agent]
---

# Web-fetch capability fn — design options + recommendation (2026-07-02)

> Purpose: the design pass `tool-designs-eval-2026-07-02.md` flagged web-fetch
> as the largest missing inspect.ai-suite tool with NO written design. This doc
> grounds the design in (a) the vendored prior art (`reference-code/openclaw`,
> `reference-code/inspect-ai`, `reference-code/hermes-agent`, the browser
> benchmarks), (b) the current node-ecosystem extraction libraries, and (c) the
> house template (`seon.agent.fs` / `seon.agent.search` + `toolkit.md`'s four
> shared shapes). It answers the six open design questions with a
> recommendation each and proposes the `:seon.web-fetch/*` schemas. It is a
> design doc — nothing here is built.

## TL;DR — the recommended design

- **Floor `seon.agent.web` (+ `.internal`), wrapper `my.web`, one `^:async`
  verb `fetch`.** Attrs are `:seon.web-fetch/*` (the `:seon.shell/*`-style
  short attr namespace precedent from `toolkit.md`).
- **Fetch layer: built-in `fetch` (undici — the pod runs Node 24.2, nothing to
  install).** AbortController timeout (default 30s), streamed body with a
  byte cap (default 2 MB, openclaw's number), redirects followed with a cap,
  content-type dispatch (html → extract; json/text/markdown → passthrough;
  binary → legible refusal). Zero new deps for the transport.
- **Extraction: `@mozilla/readability` + `linkedom`, with a ported regex
  HTML→markdown converter (no turndown).** This is the exact, working,
  vendored-and-readable openclaw pipeline
  (`reference-code/openclaw/src/agents/tools/web-fetch-utils.ts`) — two small
  MIT deps, proven against pathological HTML (size + nesting-depth guards,
  regex fallback). `defuddle` is the watch-list alternative (owner option).
  JS-rendered pages are an HONEST limitation: fetch-only returns the server's
  HTML; a thin SPA shell yields thin content and the response says so. The
  browser tier (a11y-tree / screenshots, per inspect-ai + WebVoyager +
  BrowserGym) is a separate, later tool — already deferred by the survey.
- **Output discipline (the hard requirement): blob + projection + capped
  preview.** The full extracted markdown goes to `my.blob`
  (content-addressed); the DB gets a small fetch-projection entity (url,
  final-url, status, title, fetched-at, total TOKENS, blob hash); the
  immediate result carries a preview capped at ~2k tokens with honest totals
  (`:seon.web-fetch/total-tokens`, `truncated?`) and the blob hash for paged
  reads. Search-within-page = grep over the blob (the planned
  `my.search`-over-blobs surface) — no new verb. All sizes are TOKENS via
  `seon.ai.tokens/estimate`, never chars.
- **Gate: `SEON_WEB`, default-deny** (the `SEON_FS_*`/`SEON_SHELL` posture),
  allow-all-when-granted PLUS an always-on private-range block (localhost,
  RFC-1918, link-local/metadata, ULA) as the SSRF soft boundary; an optional
  domain allowlist rides the same `grants`/`configure!`/host-lock mechanism
  as `seon.agent.fs`.
- **Caching: refetch policy is data.** Optional `:seon.web-fetch/max-age-ms`;
  a young-enough prior fetch entity for the same URL is returned from the DB
  (a derived read, no cache subsystem). Default 0 (always refetch). The blob
  layer dedupes identical bodies for free.
- **inspect.ai: no wire-shape to match.** inspect bundles NO plain fetch tool
  (only `web_search` and the a11y-tree `web_browser`); our `/solve` bridge
  exposes seon FUNCTIONS, not inspect tool schemas, so the envelope needs no
  adaptation. The honest gap for GAIA-class evals is the browser tier, not
  the envelope.

## Q1 — Fetch layer: built-in `fetch` (undici) vs a dep

**Recommendation: built-in `fetch`. Zero deps.** The pod runs Node v24.2.0
(verified); global `fetch` is undici-backed and stable since Node 18. No
vendored repo needed more: openclaw's `web_fetch` (the closest prior art,
`reference-code/openclaw/src/agents/tools/web-fetch.ts`) is plain `fetch`
plus policy; inspect-ai's only inline fetch (the Google search provider's
`page_if_relevant`) is a plain httpx GET.

Mechanics, each with its prior-art anchor:

- **Timeout** — `AbortController` + `setTimeout`, default 30,000 ms (the
  `:seon.shell/timeout-ms` default). Timeout is an ok?-false value, never a
  rejection (`^:async` fns always RESOLVE — house rule).
- **Max-bytes cap** — stream the body (`response.body` reader) and stop at a
  cap rather than `await response.text()` on an unbounded body. Default
  2,000,000 bytes = openclaw's `DEFAULT_FETCH_MAX_RESPONSE_BYTES`; over-cap
  sets `truncated?` and continues with what was read (openclaw's `warning:
  "Response body truncated after N bytes"` pattern, made a first-class flag).
- **Redirects** — `redirect: "follow"` with a manual cap of 5 hops
  (openclaw caps at 3; undici's default 20 is too generous). Each hop's
  destination re-passes the SSRF check (Q4) — the redirect is the classic
  bypass. The response reports `:seon.web-fetch/final-url` so the agent sees
  where it landed (openclaw's `finalUrl`).
- **Content-type dispatch** —
  - `text/html` → extraction (Q2);
  - `text/markdown` → passthrough, `extractor :markdown-passthrough` — and
    send `Accept: text/markdown, text/html;q=0.9, */*;q=0.8` so servers that
    speak "markdown for agents" (openclaw's `cf-markdown` path) skip
    extraction entirely;
  - `application/json` → pretty-printed passthrough, `extractor :json`;
  - `text/plain` and other `text/*` → passthrough, `extractor :text`;
  - everything else (images, PDFs, octet-stream) → **legible refusal**:
    `ok? false` with the content-type named and a hint that binary lands in
    a later blob-tier verb. Refuse early via the response header — don't
    download 2 MB of PNG first.
- **UA policy** — an honest, constant UA (`seon-agent/<version>`), set in
  `internal`. **No robots.txt in v1**: this is a user-directed single-URL
  read (the `curl` / Claude-Code-WebFetch class), not a crawler; none of the
  vendored fetch tools (openclaw, hermes, inspect's Google provider) consult
  robots. Flagged as an owner decision below.

Rejected: `axios`/`got`/`node-fetch` (nothing over undici for this shape;
new dep for zero capability), raw `http`/`https` modules (re-implements
redirects/streaming that `fetch` already owns).

## Q2 — Extraction: raw text vs readability vs markdown

**Recommendation: `@mozilla/readability` + `linkedom`, markdown via a ported
regex converter; `extractor` provenance on every response; honest fallback
chain.** Two small MIT deps.

Why this pair and not the alternatives:

- **It is the proven, vendored combination.** openclaw's `web_fetch` uses
  exactly `@mozilla/readability` ^0.6 + `linkedom` (NOT jsdom) — see its
  `package.json` and `web-fetch-utils.ts`: `parseHTML` from linkedom,
  `baseURI` set for relative links, `new Readability(document,
  {charThreshold: 0}).parse()`, then a **regex** HTML→markdown pass
  (`<a>`→`[label](href)`, headings→`#`, `<li>`→`- `, strip
  script/style/noscript, normalize whitespace). No turndown, no cheerio —
  in fact **no vendored repo uses turndown or cheerio at all** (manifest
  grep across `reference-code/`), and turndown is no longer actively
  maintained ([npm-compare — turndown et al.](https://npm-compare.com/markdown-it,marked,node-html-markdown,showdown,turndown)).
  The whole pipeline is readable in `reference-code/openclaw/` when building.
- **Pathological-HTML guards come with it.** openclaw guards Readability with
  a 1 MB HTML size cap and a nesting-depth heuristic (~3,000), falling back
  to the regex tag-stripper — adopt both (the pod is single-threaded; a DOM
  blowup blanks every agent).
- **Readability is aging but stable** — mozilla/readability is
  low-maintenance ([mozilla/readability issues](https://github.com/mozilla/readability/issues),
  [jocmp — comparing Defuddle and Postlight Parser](https://jocmp.com/2025/07/12/full-content-extractors-comparing-defuddle/))
  yet it is the algorithm everything else is measured against, and our usage
  (one parse call behind guards) has no API-churn exposure.

Alternatives considered:

- **`defuddle`** ([kepano/defuddle](https://github.com/kepano/defuddle)) —
  active (Obsidian-backed, v0.19.x June 2026), `defuddle/node` entry,
  markdown output + rich metadata (title/author/published/site/word-count)
  built in. The real contender; two cautions: it needs a DOM impl anyway
  (JSDOM is heavy; its linkedom behavior has reported rough edges —
  [jocmp comparison](https://jocmp.com/2025/07/12/full-content-extractors-comparing-defuddle/)),
  and it is younger/faster-moving than the readability algorithm. Listed as
  an owner option — it would replace BOTH readability and the md converter.
- **`mdream`** ([harlan-zw/mdream](https://github.com/harlan-zw/mdream)) —
  zero-dep, streaming, token-lean HTML→markdown with an LLM `minimal`
  preset — but **conversion only, no main-content extraction** (nav/footer
  boilerplate survives). Could replace the regex converter behind
  readability later; not sufficient alone.
- **`@postlight/parser`** — effectively unmaintained since the Postlight
  acquisition ([jocmp comparison](https://jocmp.com/2025/07/12/full-content-extractors-comparing-defuddle/)). Rejected.
- **Raw text only (zero deps)** — the openclaw regex fallback as the ONLY
  mode. Cheapest, but on real pages the boilerplate typically multiplies
  tokens several-fold versus extracted markdown
  ([Web2MD — markdown vs HTML for LLMs](https://web2md.org/blog/markdown-vs-html-for-llm));
  it survives as the fallback tier, not the default.

**Failure mode, stated honestly: fetch-only cannot do JS-rendered pages.**
The verb returns whatever HTML the server sends; an SPA shell extracts to
near-nothing. The response should say so — when extraction yields under a
small token floor on a 200 response, add a hint ("page appears
script-rendered; content requires a browser"). The browser tier is a
SEPARATE later tool: every vendored browser system is a stateful session
with a fundamentally different page representation — inspect-ai's
`web_browser` returns `main content` + an **accessibility tree** with
bracketed element ids
(`reference-code/inspect-ai/src/inspect_ai/tool/_tools/_web_browser/_web_browser.py`),
WebVoyager uses set-of-mark screenshots (`reference-code/webvoyager/prompts.py`),
BrowserGym exposes screenshot+AXTree+DOM observations
(`reference-code/browsergym/`). None of that shape-shares with a stateless
`fetch(url)` — bolting it on later is a new tool, not a v2 flag.

Fallback chain (each step honest via `:seon.web-fetch/extractor`):
readability parse → (empty/guarded) → regex strip (`:raw`) → the response
never pretends a fallback was an extraction.

## Q3 — Output discipline: blob + projection + capped preview

**Recommendation (the owner's hard requirement, made concrete): full content
→ `my.blob`; metadata datoms as the fetch projection; a ~2k-token preview in
the immediate result with honest totals; paged/grep follow-ups go through
the blob.**

The three-tier storage rule decides this without new mechanism: DB datoms =
small indexed projections; blobs = persistent full content. A fetched page
is exactly the "scraped document" case `toolkit.md` §`my.blob` and
`observability.md` already name. Concretely:

- **The blob** — the full extracted markdown (not the raw HTML; raw HTML is
  re-derivable by refetching and is 5–10× the tokens). Content-addressed
  `put!` → hash; identical bodies dedupe for free.
- **The projection entity** (persisted datoms): url, final-url, status,
  title, content-type, extractor, fetched-at, **total-tokens** (via
  `seon.ai.tokens/estimate` — never chars), blob hash. This is what queries,
  renders, and the max-age cache (Q5) read. The preview is NOT stored —
  renders/derived views are never stored; it re-derives from the blob.
- **The immediate result** — the envelope (schemas in the proposed-schemas
  section): the projection fields + `:seon.web-fetch/preview` capped at
  `max-preview-tokens` (default 2,000 — deliberately far below openclaw's
  50k-char default; seon's context discipline is the binding constraint) +
  `:seon.web-fetch/preview-tokens` + `:seon.web-fetch/total-tokens` +
  `:seon.web-fetch/truncated?`. A partial read never looks complete — the
  same law as `read-file`'s `lines-returned`/`total-lines`.
- **Paged reads** — `my.blob`'s `text` verb (paged, honest totals — already
  in the `observability.md` blob design) pages the full content; web-fetch
  adds NO paging mechanism of its own.
- **Search-within-page** — compose, don't build: `observability.md` already
  plans blobs inside the `my.search` grep surface. Grep-the-blob is the
  search-within-page story; until that lands, the interim recipe is
  `blob/text` pages + the agent's own code. No `:seon.web-fetch/find` verb.
- **Links list** — the one extra worth carrying: extraction keeps `[label](href)`
  links inline in the markdown (grep-able in the blob), and the response
  carries a CAPPED `:seon.web-fetch/links` items vector (absolute href +
  label, default cap ~25) so "fetch → pick a link → fetch" threads without
  re-parsing. This is the convergent shape: Jina Reader appends a
  "Buttons & Links" section ([Jina Reader](https://jina.ai/reader/)),
  Firecrawl returns `{url, title, description}` link rows
  ([Firecrawl scrape](https://www.firecrawl.dev/scrape)), and inspect's
  search providers return `{title, url, content}` citations
  (`reference-code/inspect-ai/src/inspect_ai/tool/_tools/_web_search/_tavily.py`).

Prior-art calibration for the caps: openclaw returns up to 50k chars inline
(~12.5k tok — context-hostile by seon standards); hermes-agent compresses via
an LLM to a hard 5k-char output (`reference-code/hermes-agent/tools/web_tools.py`
— `MAX_OUTPUT_SIZE = 5000`, refuse over 2 MB, chunked summarize over 500k).
The blob + preview design gets hermes' small-context result without the LLM
summarization cost or lossiness, because the full text stays one `blob/text`
call away. (LLM-summarize-into-preview could be a later opt-in; not v1.)

**Dependency note:** this ordering confirms the build order in
`tool-designs-eval-2026-07-02.md` — blob lands BEFORE web-fetch so the
big-body landing zone exists on day one.

## Q4 — Gating: `SEON_WEB`, default-deny, private-range block

**Recommendation: `SEON_WEB` host grant, default-deny; when granted,
allow-all domains MINUS an always-on private-range block; optional domain
allowlist on the standard `grants`/`configure!`/lock surface.**

- **Default-deny** is the settled house posture (`SEON_FS_ROOT`,
  `SEON_SHELL`): with no grant, every call returns a legible
  `ok? false` envelope naming the missing grant. Same `grants` /
  `configure!` verbs, same `SEON_WEB_LOCK` host-lock semantics as
  `seon.agent.fs/locked?`.
- **Allow-all-when-granted, not allowlist-first.** An fs root is a natural
  scope; the web isn't — benchmark tasks (GAIA-class) go anywhere, and a
  domain allowlist as the DEFAULT would make every eval a config chore. The
  allowlist exists as an OPTIONAL grant field
  (`:seon.web-fetch/allowed-domains`, absent = all) for hosts that want it.
- **SSRF: block private ranges, always, un-configurable.** The pod sits on
  the user's machine next to the wire-server, the inspector (7890), and
  whatever else listens on localhost — an agent-emitted
  `http://127.0.0.1:7891/…` must land on a denial envelope. Block: literal
  loopback (`127/8`, `::1`, `localhost`), RFC-1918 (`10/8`, `172.16/12`,
  `192.168/16`), link-local + cloud metadata (`169.254/16`, notably
  `169.254.169.254`), and IPv6 ULA (`fc00::/7`) — checked on the resolved
  address of every hop, redirects included (openclaw ships the same guard:
  `fetchWithSsrFGuard` in `web-fetch.ts`; hermes has `url_safety.py`).
  Stated honestly in the docstring: this is the usual SOFT boundary against
  LLM accidents (DNS-rebinding-grade evasion is out of scope; process-level
  isolation is the real boundary, per the settled sandbox posture).
- **Scheme:** `http`/`https` only. `file:` is `seon.agent.fs`'s jurisdiction;
  everything else refuses legibly.

## Q5 — Caching: content-addressing + max-age-as-data

**Recommendation: no cache subsystem. The fetch-projection entities ARE the
cache; refetch policy is a request argument.**

- Every successful fetch transacts a projection entity (Q3). The blob layer
  dedupes identical content for free (same hash → one blob), so re-fetching
  an unchanged page costs datoms, not disk.
- **`:seon.web-fetch/max-age-ms` (optional; default 0 = always refetch):**
  when present, the verb first queries for the newest projection entity with
  the same url; if `fetched-at` is younger than max-age, it returns that
  projection (re-deriving the preview from the blob) with
  `:seon.web-fetch/cached? true`. This is the derive-don't-store principle
  applied to caching — a DB query at call time, no TTL store, no eviction,
  nothing to invalidate (openclaw carries a TTL cache + `cached` flag; ours
  falls out of the DB).
- Default 0 because a live agent usually wants the live page; benchmark
  harnesses and repeated-research loops opt in per call. HTTP-level
  conditional revalidation (ETag/If-Modified-Since) is a v2 refinement —
  note it, don't build it.

## Q6 — inspect.ai compatibility

**Recommendation: no envelope adaptation needed; record the honest capability
gap instead.**

- **inspect bundles NO plain fetch tool.** Its web surface is `web_search`
  (per-provider: a `ContentText` answer + `{title, url, cited_text}`
  citations — Tavily/Exa; or per-page `title + text` capped at 2,000 words —
  the Google CSE provider's BeautifulSoup fetch) and `web_browser` (the
  stateful a11y-tree session). Sources:
  `reference-code/inspect-ai/src/inspect_ai/tool/_tools/_web_search/` and
  `_web_browser/_web_browser.py`.
- **The evals pass TOOLS, not shapes, to the solver** — GAIA's default
  solver is `bash + python + web_browser()`
  (`reference-code/inspect-evals/src/inspect_evals/gaia/gaia.py`);
  browse_comp adds `web_search`. Our `/solve` bridge exposes seon FUNCTIONS
  as the tool surface (the settled fns-are-the-tool-surface decision in the
  bridge spike), so there is no inspect-side result schema our envelope must
  serialize into — the model reads the envelope map like any other verb
  result. It maps cleanly by construction.
- **The honest gap:** GAIA's reference solver assumes a browser. Fetch +
  shell + files covers the GAIA-level-1 territory the survey targeted;
  tasks that require in-page interaction stay out of reach until the
  deferred browser tier. Say that in the eval notes rather than bending the
  fetch design toward browser-shaped output.
- **Forward-compatible sibling:** inspect's search-provider shape
  (`{title, url, snippet}` rows) is exactly the `:seon.items/*` envelope a
  future `my.web/search` verb (API-key-backed) would return — the schemas
  below keep the `:seon.web-fetch/links` item shape compatible with that so
  search results and page links thread the same way.

## Proposed schemas — `:seon.web-fetch/*`

Floor `seon.agent.web` (+ `seon.agent.web.internal` for the fetch/SSRF/cap
plumbing), wrapper `my.web`. Attr namespace `:seon.web-fetch/*` follows the
`:seon.shell/*` short-attr precedent in `toolkit.md`. Sketch (house style —
register scalars, then named request/response maps; errors are values):

```clojure
;; ---- grant ----
(schema/register! :seon.web-fetch/allowed-domains [:vector :string]) ; absent/empty grant field = all (when SEON_WEB granted)
(schema/register! :seon.web-fetch/enabled?        :boolean)          ; SEON_WEB present
(schema/register! :seon.web-fetch/locked?         :boolean)          ; SEON_WEB_LOCK

;; ---- request ----
(schema/register! :seon.web-fetch/url                [:string {:min 1}])
(schema/register! :seon.web-fetch/timeout-ms         :int)  ; default 30000
(schema/register! :seon.web-fetch/max-bytes          :int)  ; default 2000000
(schema/register! :seon.web-fetch/max-preview-tokens :int)  ; default 2000
(schema/register! :seon.web-fetch/max-age-ms         :int)  ; default 0 = always refetch

(schema/register! :seon.web-fetch/fetch-request
  [:map
   [:seon.web-fetch/url                :seon.web-fetch/url]
   [:seon.web-fetch/timeout-ms         {:optional true} :seon.web-fetch/timeout-ms]
   [:seon.web-fetch/max-bytes          {:optional true} :seon.web-fetch/max-bytes]
   [:seon.web-fetch/max-preview-tokens {:optional true} :seon.web-fetch/max-preview-tokens]
   [:seon.web-fetch/max-age-ms         {:optional true} :seon.web-fetch/max-age-ms]])

;; ---- response / projection ----
(schema/register! :seon.web-fetch/ok?            :boolean)
(schema/register! :seon.web-fetch/final-url      :seon.web-fetch/url)
(schema/register! :seon.web-fetch/status         :int)
(schema/register! :seon.web-fetch/content-type   :string)
(schema/register! :seon.web-fetch/title          :string)
(schema/register! :seon.web-fetch/extractor      ; provenance — a value enum, never stored as a kind
  [:enum :readability :raw :json :text :markdown-passthrough])
(schema/register! :seon.web-fetch/preview        :string)   ; response-only, NEVER a datom
(schema/register! :seon.web-fetch/preview-tokens :int)
(schema/register! :seon.web-fetch/total-tokens   :int)      ; seon.ai.tokens/estimate — tokens, never chars
(schema/register! :seon.web-fetch/truncated?     :boolean)
(schema/register! :seon.web-fetch/blob-hash      :string)   ; -> my.blob get/text (align with the blob design's hash shape)
(schema/register! :seon.web-fetch/fetched-at     :inst)
(schema/register! :seon.web-fetch/cached?        :boolean)  ; served from a young-enough prior fetch
(schema/register! :seon.web-fetch/hint           :string)   ; e.g. "script-rendered page — needs the browser tier"

;; link items — shape-compatible with a future my.web/search result row
(schema/register! :seon.web-fetch/href  :seon.web-fetch/url)
(schema/register! :seon.web-fetch/label :string)
(schema/register! :seon.web-fetch/link
  [:map
   [:seon.web-fetch/href  :seon.web-fetch/href]
   [:seon.web-fetch/label {:optional true} :seon.web-fetch/label]])
(schema/register! :seon.web-fetch/links [:vector :seon.web-fetch/link]) ; capped (~25)

(schema/register! :seon.web-fetch/fetch-response
  [:or
   [:map
    [:seon.web-fetch/ok?            [:= true]]
    [:seon.web-fetch/url            :seon.web-fetch/url]
    [:seon.web-fetch/final-url      :seon.web-fetch/final-url]
    [:seon.web-fetch/status         :seon.web-fetch/status]
    [:seon.web-fetch/content-type   :seon.web-fetch/content-type]
    [:seon.web-fetch/title          {:optional true} :seon.web-fetch/title]
    [:seon.web-fetch/extractor      :seon.web-fetch/extractor]
    [:seon.web-fetch/preview        :seon.web-fetch/preview]
    [:seon.web-fetch/preview-tokens :seon.web-fetch/preview-tokens]
    [:seon.web-fetch/total-tokens   :seon.web-fetch/total-tokens]
    [:seon.web-fetch/truncated?     :seon.web-fetch/truncated?]
    [:seon.web-fetch/blob-hash      :seon.web-fetch/blob-hash]
    [:seon.web-fetch/fetched-at     :seon.web-fetch/fetched-at]
    [:seon.web-fetch/cached?        {:optional true} :seon.web-fetch/cached?]
    [:seon.web-fetch/links          {:optional true} :seon.web-fetch/links]
    [:seon.web-fetch/hint           {:optional true} :seon.web-fetch/hint]]
   [:map
    [:seon.web-fetch/ok? [:= false]]
    [:seon/error         :seon/error]]])   ; the shared error value (message + kind), new-floor convention
```

Persisted projection datoms (a subset of the success map — url, final-url,
status, title, content-type, extractor, total-tokens, blob-hash,
fetched-at). `:seon.web-fetch/url` is indexed but NOT unique-identity — one
entity per fetch event, history is the point; the max-age read takes the
newest. `preview`/`links`/`hint` are response-only derivations, never
stored (renders are never stored). A non-2xx status that still returned a
body is `ok? true` with the status visible — "the fetch RAN" mirrors
shell's `ok?`-=-RAN refinement; transport failures (timeout, DNS, SSRF
denial, no grant) are `ok? false`.

Verb surface: `fetch` (`^:async`), plus the standard `grants` / `configure!`
pair. Nothing else in v1 — no `find`, no paging (blob owns those), no
`search` (a separate keyed verb later).

## Open owner decisions

1. **robots.txt** — v1 skips it (user-directed single-URL reads; matches all
   vendored prior art). Confirm, or require a robots check despite the cost
   (an extra fetch per new domain).
2. **defuddle vs readability+regex** — the recommendation is
   readability+linkedom+ported-regex (vendored-proven, minimal surface);
   defuddle (+DOM impl) would swap in richer metadata (byline/published) and
   maintained extraction at the cost of a heavier, faster-moving dep. Cheap
   to swap later behind `internal` — but pick one before build.
3. **Preview default** — 2,000 tokens proposed. Tune against real drives
   (the flag-garbage-over-fake-optimization rule: read actual agent-facing
   output before locking it).
4. **Domain allowlist default for BENCHMARK runs** — allow-all-when-granted
   is proposed; decide whether inspect eval runs get a narrower host-locked
   grant per task family.
5. **Store raw HTML too?** — proposed NO (markdown blob only; refetch
   re-derives). If exact-archive provenance ever matters (citations,
   diffing), a second blob ref is a one-attr addition.
6. **Sequencing** — this design assumes `my.blob` exists (build order #3
   before #4 in `tool-designs-eval-2026-07-02.md`). If web-fetch must ship
   first, the interim is a degraded inline-only mode — not recommended.

## Sources

Vendored (read these when building):

- `reference-code/openclaw/src/agents/tools/web-fetch.ts` +
  `web-fetch-utils.ts` — the closest working prior art (caps, SSRF guard,
  readability+linkedom, regex markdown, extractor provenance).
- `reference-code/inspect-ai/src/inspect_ai/tool/_tools/_web_search/`
  (`_tavily.py`, `_exa.py`, `_google.py`) and
  `_web_browser/_web_browser.py` — inspect's two web tools + shapes.
- `reference-code/inspect-evals/src/inspect_evals/gaia/gaia.py` — which
  tools GAIA's solver actually passes.
- `reference-code/hermes-agent/tools/web_tools.py` — the LLM-compression
  tier + its caps (2 MB refuse / 500k chunk / 5k output).
- `reference-code/webvoyager/prompts.py`, `reference-code/browsergym/`,
  `reference-code/online-mind2web/` — why the browser tier is a separate
  tool (screenshots / AXTree / stateful sessions).
- `src/seon/agent/fs.cljs`, `src/seon/agent/search.cljs` — the house
  template being cloned.

Web references:

- [kepano/defuddle](https://github.com/kepano/defuddle) — node entry,
  markdown + metadata, active (v0.19.x, June 2026).
- [jocmp — Comparing Defuddle and Postlight Parser](https://jocmp.com/2025/07/12/full-content-extractors-comparing-defuddle/)
  — maintenance states; Postlight effectively ended; readability aging.
- [harlan-zw/mdream](https://github.com/harlan-zw/mdream) — zero-dep
  streaming HTML→markdown, `minimal` LLM preset; conversion-only.
- [mozilla/readability](https://github.com/mozilla/readability) — the
  extraction baseline.
- [npm-compare — turndown and friends](https://npm-compare.com/markdown-it,marked,node-html-markdown,showdown,turndown)
  — turndown maintenance state.
- [Jina Reader](https://jina.ai/reader/) and
  [Firecrawl scrape](https://www.firecrawl.dev/scrape) — the convergent
  fetch-tool output shape (clean markdown + title + links + caps).
- [Web2MD — markdown vs HTML for LLMs](https://web2md.org/blog/markdown-vs-html-for-llm)
  — token economics of extraction vs raw HTML.
