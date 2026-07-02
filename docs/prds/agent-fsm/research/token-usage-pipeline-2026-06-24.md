---
type: research
status: active
tags: [research, agent, web]
---

# Token-usage pipeline: is total + cached captured accurately end-to-end?

## TL;DR — verdict

**YES for both providers, with one caveat.** The persisted
`:seon.agent.turn/llm-usage` is the provider's `usage` object captured
**verbatim** (`pr-str` of `(:usage body)`), so every field the provider
returns — including the cache fields — is present and EXACT. The render
spec's assumption ("ALREADY persisted, do not re-instrument adapters") is
**correct**. No adapter or turn change is required to read total + cached
tokens accurately.

| Provider | Total input | Cached | Captured today? |
|----------|-------------|--------|-----------------|
| DeepSeek (live default) | `prompt_tokens` | `prompt_cache_hit_tokens` | YES — verbatim |
| Anthropic | `input_tokens` + cache fields | `cache_read_input_tokens` | YES — verbatim |

**Caveat (the only one):** there is **NO normalization layer**. The usage
map is stored exactly as the provider returned it, with provider-specific,
DIFFERENTLY-NAMED keys. The `usage-section` (and the inspector viz) MUST
carry a per-provider field map. See "Per-provider cache-field map" below.

**One subtlety on "total":** DeepSeek's `prompt_tokens` already INCLUDES
the cached tokens (cache hit + miss sum to prompt_tokens). Anthropic's
`input_tokens` does NOT include cached tokens — `cache_read_input_tokens`
and `cache_creation_input_tokens` are reported SEPARATELY and must be
ADDED to `input_tokens` for the true total. This asymmetry is the single
most important thing the viz math must get right (detailed below).

---

## 1. Per-adapter usage capture (verbatim, no normalization)

### DeepSeek / OpenAI-compat — `src/seon/ai/openai_compat.cljs`

`parse-completion` (lines 224-258) sets `:seon.ai/usage` to the raw
`usage` sub-map of the completion, untouched:

```clojure
;; line 256
(cond-> {:seon.ai/text                        (or msg "")
         :seon.ai.openai-compat/finish-reason (:finish_reason choice)
         :seon.ai/usage                       (:usage body)}   ; <-- verbatim
  ...)
```

`body` is `(js->clj completion :keywordize-keys true)` (line 235), so
`(:usage body)` is the full keywordized usage object. The request asks for
it explicitly via `:stream_options {:include_usage true}`
(`request-params`, line 200), so usage is present on the final assembled
chunk. **Nothing is dropped** — `prompt_cache_hit_tokens` /
`prompt_cache_miss_tokens` ride through because the whole map is taken as-is.

### Anthropic — `src/seon/ai/anthropic.cljs`

`parse-completion` (lines 208-242) sets `:seon.ai/usage` to the raw
`usage` sub-map in BOTH the normal and the refusal branch:

```clojure
;; line 235 (normal) and line 228 (refusal)
:seon.ai/usage (:usage body)   ; <-- verbatim
```

Same pattern: `body` is the keywordized Message, `(:usage body)` is the
full usage object including `cache_read_input_tokens` /
`cache_creation_input_tokens`. **Nothing is dropped.**

### The shared schema is a passthrough

`src/seon/ai.cljs:59`:

```clojure
(schema/register! ::usage :map)
```

`:seon.ai/usage` is a bare `:map` — no field constraints, no whitelist. So
the schema does not strip provider keys; whatever the provider returns is
valid. (This is the standard "third-party boundary, value is the lib's
return" exception — fine here.)

---

## 2. The persisted `llm-usage` shape

### Where it is built + persisted

`src/seon/agent/turn.cljs`, `ask-and-eval!` (lines 377-403):

```clojure
;; lines 388-390
raw     (:seon.ai/raw resp)
usage   (:seon.ai/usage raw)
pfields (:seon.ai/provider-fields raw)
...
;; line 402 — folded into the close-tx result map
(seq usage) (assoc :seon.agent.turn/llm-usage (pr-str usage))
```

`close-turn!` (lines 288-318) then `select-keys`-es
`:seon.agent.turn/llm-usage` out of that result and transacts it onto the
turn entity (line 300-304).

### The exact shape on the datom

The schema (`turn.cljs:81`):

```clojure
(schema/register! :seon.agent.turn/llm-usage :string)
```

It is a **STRING** — specifically `(pr-str usage)`, the EDN serialization
of the verbatim provider usage map. The docstring (turn.cljs:79-82)
explains why: `:map` is unbridgeable (a `:map` close-tx fails the schema
bridge), so usage + provider-fields are EDN-stringified. ABSENT on a
stub-LLM turn (no `:seon.ai/raw`).

**To read it back:** pull `:seon.agent.turn/llm-usage` off the most-recent
turn and `cljs.reader/read-string` it → you get the keywordized provider
usage map exactly as the provider sent it. (`:seon.agent.turn/llm-meta`,
also EDN, holds the unrecognized top-level provider fields — NOT usage.)

There is currently **no reader** of `:seon.agent.turn/llm-usage` anywhere
in `src/` — it is write-only today. The `usage-section` will be its first
consumer, which matches the spec (P7).

---

## 3. Per-provider cache-field map (no normalization — viz needs this)

Because the map is verbatim, the field names differ by provider. The
`usage-section` and inspector must branch on provider (or on key
presence). Field paths inside the parsed `llm-usage` map:

### DeepSeek (`:deepseek` — the LIVE default)

| Meaning | Field | Notes |
|---------|-------|-------|
| Total input tokens | `:prompt_tokens` | INCLUDES cached (= hit + miss) |
| Cached (hit) | `:prompt_cache_hit_tokens` | tokens served from cache |
| Uncached (miss) | `:prompt_cache_miss_tokens` | `hit + miss == prompt_tokens` |
| Output tokens | `:completion_tokens` | |
| Grand total | `:total_tokens` | prompt + completion |

True total input = `:prompt_tokens` (already complete).
Cached fraction = `prompt_cache_hit_tokens / prompt_tokens`.

### Anthropic (`claude-*`)

| Meaning | Field | Notes |
|---------|-------|-------|
| Uncached input | `:input_tokens` | does NOT include cached tokens |
| Cache READ (hit) | `:cache_read_input_tokens` | served from an existing cache |
| Cache WRITE (creation) | `:cache_creation_input_tokens` | written to cache this turn (1.25x billed) |
| Output tokens | `:output_tokens` | |

True total input =
`input_tokens + cache_read_input_tokens + cache_creation_input_tokens`.
Cached fraction = `cache_read_input_tokens / (that total)`.
There is no single `total_tokens` field — the viz must sum.

### The asymmetry the viz math MUST handle

- **DeepSeek:** `prompt_tokens` is the total; cached is a SUBSET of it.
  Do NOT add the cache fields to prompt_tokens (double-counts).
- **Anthropic:** `input_tokens` is the UNCACHED remainder only; cached
  fields are DISJOINT and must be ADDED for the total.

Recommended approach for `seon.ctx.usage`: a small per-provider extractor
returning a normalized `{:total-input N :cached N :output N}` triple
DERIVED at render time from the EDN map — do not store a normalized form
(reactive-context). Detect provider from `(seon.ai/provider)` or, more
robustly for old turns, from key presence (`:prompt_tokens` ⇒ openai-shape,
`:input_tokens` ⇒ anthropic-shape).

---

## 4. Gaps + minimal fixes

**None required for accuracy.** Total and cached are both captured EXACTLY
for both providers today, because capture is verbatim. The spec's "do not
re-instrument adapters" holds.

The ONLY work the viz needs is at the READ side (new code, not a fix to
existing code):

1. `src/seon/ctx/usage.cljs` (new, per spec P7) — parse the EDN
   `:seon.agent.turn/llm-usage` off the latest turn and extract
   total/cached via the per-provider field map above.
2. The inspector's live-cache line — same extractor, reading the same
   turn datom.

No `turn.cljs` change. No adapter change. (If a future viz wanted a
single uniform stored shape, the right move would still be a DERIVED
extractor, not a new stored attr — but it is not needed.)

---

## 5. Cache-line position (where the breakpoint falls)

### How the breakpoint is determined today

The breakpoint position is set by the **in-band `stable-boundary` marker**
the composer joins into the assembled ctx string:

- `src/seon/ctx.cljs:1457-1474` — `stable-boundary` is a literal comment
  line; `stable-boundary-delim` wraps it in `\n\n…\n\n`. The composer
  emits it BETWEEN the stable prefix (sections through `:namespaces`,
  priorities 10-20) and the volatile tail (priority ≥30). Per
  `core-default-ctx` (1491-1521), the prefix = `:system` + `:namespaces`;
  everything from `:your-entity` (35) down is volatile.
- `src/seon/ctx.cljs:1476-1490` — `split-context` recovers the two halves
  by `.indexOf` of the delimiter: `stable-text` = everything before,
  `volatile-text` = everything after. A ctx without the marker → all
  volatile (degrades gracefully).
- `src/seon/ai/anthropic.cljs:138-163` — `request-params` calls
  `split-context` (line 139), then places `cache_control {:type
  "ephemeral"}` on TWO system blocks: block 1 = soul/system prompt
  (always), block 2 = `stable-text` (only when `split?`, i.e. both halves
  non-blank — line 142, 160). The volatile tail rides in `:messages`
  uncached (line 163). So the cache breakpoint is at the END of the stable
  prefix = the boundary marker's position.

For DeepSeek/OpenAI the breakpoint is IMPLICIT (provider auto-caches the
longest byte-stable prefix); the boundary marker is not consumed by
`openai_compat.cljs` at all — it just rides as a comment inside the ctx
string. So the line position is only an EXPLICIT control for Anthropic;
for DeepSeek it is observational (the provider decides, you read
`prompt_cache_hit_tokens` to see how much hit).

### Is it derivable for the viz? YES

The breakpoint's position is fully derivable from the assembled context:

- **Section index:** the breakpoint sits immediately after the last
  section with `:seon.ctx/priority < 30` (i.e. after `:namespaces`). The
  viz already iterates sections to draw the per-section bars, so it can
  mark the bar boundary between `:namespaces` and `:your-entity`.
- **Token offset:** `(seon.ai.tokens/estimate stable-text)` where
  `stable-text` = `(:seon.render/stable-text (seon.ctx/split-context
  assembled))`. That is the ESTIMATED token offset where the stable prefix
  ends — exactly the cache-line position for the bar graph.
- **Live confirmation of how much actually cached:** read
  `cache_read_input_tokens` (Anthropic) / `prompt_cache_hit_tokens`
  (DeepSeek) off the last turn's `llm-usage` — the EXACT cached count,
  which the viz overlays as the "live cache line" against the estimated
  prefix boundary.

So the viz can draw BOTH: (a) the structural breakpoint (where the stable
prefix ends, estimated, from `split-context`), and (b) the actual cached
extent (exact, from provider usage). Note they may differ — the provider's
cache MINIMUM (4096 tokens on Opus 4.x) means a short prefix shows a
breakpoint line but zero actual cache hit. That divergence is itself
useful to surface.

---

## Follow-up — live usage samples (DO NOT run during the concurrent restart)

To confirm the REAL on-the-wire field names against a live call, the
orchestrator should later eval (against the pod, once it is back up):

DeepSeek (live default) — drive one turn, then read the last turn's usage:

```clojure
;; in the pod REPL, after at least one real turn has run
(let [aid "<agent-id>"
      sess (seon.ctx/current-session aid)
      last-turn (last (:seon.agent.session/turns
                        (seon.db/entity {:seon.db/ref [:seon.agent.session/id
                                                       (:seon.agent.session/id sess)]})))]
  (cljs.reader/read-string (:seon.agent.turn/llm-usage last-turn)))
;; expect e.g. {:prompt_tokens N :completion_tokens N :total_tokens N
;;              :prompt_cache_hit_tokens N :prompt_cache_miss_tokens N ...}
```

Anthropic — temporarily set the config row provider to anthropic (or run a
direct adapter call) and inspect:

```clojure
(-> (seon.ai.anthropic/complete {:seon.ai/ctx "say hi"})
    :seon.ai/usage)
;; expect e.g. {:input_tokens N :output_tokens N
;;              :cache_read_input_tokens N :cache_creation_input_tokens N ...}
```

These confirm the exact key names and the include/exclude semantics
documented above (DeepSeek prompt_tokens includes cache; Anthropic
input_tokens excludes it).
