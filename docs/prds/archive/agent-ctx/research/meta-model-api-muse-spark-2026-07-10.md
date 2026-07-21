---
type: research
status: active
tags: [research, agent]
---

# Meta Model API (Muse Spark 1.1) — provider evaluation, 2026-07-10

## TL;DR

**No adapter needed.** Meta Superintelligence Labs' Muse Spark 1.1
(released 2026-07-09) is served over the new **Meta Model API**, which is
OpenAI-compatible — the existing `:openai-compat` provider in
`seon.ai.openai-compat` drives it with config only. Verified live
end-to-end on the acme harness: agent runs completed through the full
stack (env-named key → config row → adapter → streamed completion →
turn/usage datoms). One real bug surfaced and was fixed in place:
`parse-completion` assoc'd a present-nil `:seon.ai/usage` when the
provider omitted the usage chunk (a DeepSeek-ism; Meta's preview omits it
intermittently), tripping `:malli.core/invalid-output`. **The default
provider is unchanged (DeepSeek).**

Headline numbers: decode is ~900–1,060 tok/s (≈20× deepseek-v4-pro),
but hidden reasoning cannot be turned off — only dialed. **Run Muse
with `reasoning_effort "minimal"`** (via `SEON_AI_EXTRA_BODY`, below):
that cuts time-to-first-token from 16–18 s to 3.9 s and total
wall-clock to 7.7 s, beating deepseek-v4-flash end-to-end. At the
default (≈high) effort Muse is SLOWER end-to-end than both DeepSeeks
on short/medium outputs despite the decode speed.

## The config (documented, not switched on)

Env for a pod that should run on Meta (seed-once: the row must be
unconfigured, else transact the same attrs onto the `:seon.ai/config`
row):

```bash
SEON_AI_PROVIDER=openai-compat
SEON_AI_BASE_URL=https://api.meta.ai/v1
SEON_AI_MODEL=muse-spark-1.1
SEON_AI_API_KEY_ENV=META_MODEL_API_KEY   # the key itself lives ONLY in env
META_MODEL_API_KEY=<key>                  # never committed, never in the DB
# RECOMMENDED — thinking has no off-switch; minimal is the fastest dial
# (TTFT 16–18s → 3.9s, wall 7.7s, reasoning ~2k → ~400 tokens; see the
# measured table below). Raise to "low"/"medium" only for tasks that
# measurably benefit; leave the default (≈high) for forensic/debug use.
SEON_AI_THINKING=minimal
```

`SEON_AI_THINKING=<effort>` is the front door (row-visible, per-agent
overridable via `:seon.ai/agent-thinking`): since the 2026-07-10
adapter fix, `:openai-compat` sends an effort string as the STANDARD
`reasoning_effort` param and never the vendor `:thinking` field.
`SEON_AI_EXTRA_BODY='{:reasoning_effort "minimal"}'` remains the
equivalent data-only door (and the only door for true vendor fields).

- Auth: `Authorization: Bearer <key>`. Key format `LLM_…`. The account
  ships with $20 free credits.
- `GET /v1/models` → `muse-spark-1.1` (the only model).
- Context window 1,048,576 tokens; input: text/image/video/PDF.
- Streaming works, honors `:stream_options {:include_usage true}`
  (usage on the final chunk — but see the gotcha below).
- Public preview, US-based developers.

## Verified live (2026-07-10, acme harness)

- `GET /models` and `POST /chat/completions` 200 with the provided key
  (curl), streaming and non-streaming.
- Through the REAL adapter: acme pod (7980) config row flipped to Meta,
  pod restarted with the key env, driven via `POST /agents/run`.
  Three runs, 27 turns total, zero `:error` closes after the fix; one
  run fully `:completed` with the correct answer.
- Usage datoms persisted per turn
  (`{:completion_tokens 1474 :prompt_tokens 41296 …}`).
- **Prompt caching is real and excellent**: 41,669 of 41,673 prompt
  tokens cached on successive turns — the stable-prefix context
  assembly hits Meta's cache nearly perfectly.
- Acme row + env restored to its snapshot afterwards (byte-identical).

## Gotchas

1. **Reasoning tokens bill as output and count against `max_tokens`.**
   Muse Spark 1.1 always reasons (hidden — no `reasoning_content` in
   the response; only `completion_tokens_details.reasoning_tokens` in
   usage). Observed ~200–1,500 reasoning tokens per turn. A small
   `max_tokens` (e.g. 50) is consumed entirely by reasoning →
   `finish_reason "length"`, `content null`. The shipped 4096 output
   cap was sufficient in all live turns; consider 8192 for headroom at
   the default effort. At `reasoning_effort "minimal"` (the recommended
   dial, above) reasoning drops to ~300–650 tokens and 4096 is ample.
2. **The usage chunk is intermittently omitted** (preview flakiness).
   This killed a live turn until `seon.ai.openai-compat/parse-completion`
   was fixed to omit `:seon.ai/usage` when absent (optional-is-absent —
   the schema and the sole consumer `turn.cljs` were already
   `(seq usage)`-guarded).
3. **`:no-forms` closes**: in 2 of 3 driven runs Muse drifted into prose
   instead of Clojure forms and the run closed `:no-forms` (graceful).
   Model-behavior observation under the acme context, not an
   integration failure; worth a proper src-inspect-ai eval before any
   serious use.
4. Do NOT confuse this with the old **Llama API**
   (`api.llama.com`) — that public preview retired 2026-07-06.

## Speed (measured 2026-07-10, same prompt ≈400-word essay, 2 stream + 1 buffered run each)

| | TTFT | decode tok/s (gen window) | wall tok/s | wall-clock to full answer |
|---|---|---|---|---|
| muse-spark-1.1 | **16–18 s** (hidden reasoning first) | **~900–1060** | 118–161 | 18.5–20.5 s |
| deepseek-v4-pro (thinking off) | 1.3 s | ~43 | ~39 | 12.7–15.3 s |
| deepseek-v4-flash (thinking off) | 1.0 s | ~68 | ~60 | 7.4–9.9 s |

Muse's raw decode is ~20× DeepSeek-pro (likely speculative/parallel
decode infra), but it spends ~1,800–2,600 hidden reasoning tokens
(~15–18 s) before the first visible token, so on short/medium answers
its END-TO-END latency is comparable to pro and slower than flash. The
longer the visible output, the more Muse's decode speed wins. Numbers
from `scratchpad/llm-bench.mjs` (this session); re-run to refresh.

### Thinking cannot be turned OFF — but `reasoning_effort` dials it down

Probed live (2026-07-10): DeepSeek-style `thinking {:type "disabled"}`
→ HTTP 400 `unknown parameter`; `reasoning_effort "none"` → HTTP 400
`does not support "none" with this model`. Accepted levels:
`"minimal"` / `"low"` / `"medium"` (default appears ≈high). Measured on
the same essay prompt:

| reasoning_effort | TTFT | reasoning tokens | wall-clock |
|---|---|---|---|
| (default) | 16–18 s | ~1,800–2,600 | 18.5–20.5 s |
| medium | 5.4 s | 639 | 9.7 s |
| low | 4.4 s | 319 | 8.7 s |
| **minimal** | **3.9 s** | **414** | **7.7 s** |

At `minimal`, Muse beats deepseek-v4-flash on wall-clock (7.7 s vs
7.4–9.9 s) at ~2× flash's decode rate. Config: `SEON_AI_THINKING=minimal`
(see the recipe above).

### The adapter smell — researched and FIXED (2026-07-10)

The old `:openai-compat` behavior sent the DeepSeek-vendor
`:thinking {:type "enabled"}` field whenever thinking was truthy —
alongside `:reasoning_effort` for a string effort. Researched across
the ecosystem:

| Surface | vendor `thinking` | standard `reasoning_effort` | unknown params |
|---|---|---|---|
| OpenAI spec (openai-node `shared.ts`) | not in spec | ✅ `none…xhigh` | — |
| DeepSeek direct | ✅ their field | ✅ accepted (their docs) | tolerant |
| Meta Model API | ❌ HTTP 400 (verified live) | ✅ `minimal/low/medium` (`none` refused) | strict 400 |
| vLLM OpenAI server | ❌ (uses `chat_template_kwargs`) | ✅ native, auto-enables thinking | strict |
| OpenRouter | forwarded/ignored | ✅ (shorthand for their `reasoning.effort`) | tolerant |

Verdict: `reasoning_effort` IS the OpenAI-standard knob; `thinking` is
DeepSeek-vendor. Sending `:thinking` on `:openai-compat` broke exactly
the strict gateways the mode targets (Meta, vLLM). **Fix (shipped):**
`:openai-compat` now sends ONLY `:reasoning_effort` for a string
effort and NOTHING for `"true"` (no standard wire form — reasoning
models reason by default; vendor fields go through `:extra-body`).
The `:deepseek` provider is UNCHANGED (always sends the explicit
toggle — correct, their API defaults thinking ON). The shipped
DEFAULTS were already right (`"false"` → compat sends nothing); only
the truthy path was wrong. Pinned by
`compat-thinking-sends-only-standard-reasoning-effort` in
`test/seon/ai/openai_compat_test.cljs`.

Side observation (2026-07-10): the OpenRouter account acme's config
points at is OUT OF CREDITS — live OpenRouter probes returned HTTP 402,
so acme drives against its default row will fail until topped up.

## Usage metadata (both wire modes verified)

Both providers return caching + reasoning info in `usage`, in BOTH
streaming (`stream_options {:include_usage true}`, final chunk) and
buffered completions — verified live against both:

- Meta: `prompt_tokens_details.cached_tokens`,
  `completion_tokens_details.reasoning_tokens`.
- DeepSeek: `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`
  (plus `prompt_tokens_details.cached_tokens`).
- **Dollar cost is NOT returned by either** — compute it as
  usage × published rates (the OpenRouter gateway CAN return cost;
  direct DeepSeek/Meta do not).

The adapter persists the whole usage map per turn
(`:seon.agent.turn/llm-usage`), so cache-hit and reasoning counts are
queryable per turn. Caveat: in repl-mode `:stream` the adapter aborts
at the first complete form, losing the provider's final usage chunk —
usage is then client-ESTIMATED and flagged `:seon.ai/estimated? true`
(by design); provider-reported usage is guaranteed only in `:batch`.

## Pricing vs DeepSeek (2026-07-10)

| | Input /M (miss) | Input /M (cache hit) | Output /M |
|---|---|---|---|
| Muse Spark 1.1 | $1.25 | (discounted; rate not published on the pricing page we could fetch) | $4.25 (reasoning billed here too) |
| deepseek-v4-flash | $0.14 | $0.0028 | $0.28 |
| deepseek-v4-pro | $0.435 | $0.003625 | $0.87 |

Muse Spark 1.1 is roughly **3–9× DeepSeek on input and 5–15× on
output**, and its always-on hidden reasoning adds ~200–1,500 billed
output tokens per turn that DeepSeek (thinking disabled) doesn't incur.
It is however ~¼ of Anthropic/OpenAI frontier rates, with a 1M context
window and full multimodality. Positioning: a mid-price
reasoning/agentic option — interesting for forensic/debug agents via
per-agent provider routing (`:seon.ai/agent-provider`), not a
cost-competitive default.

**Superseded implementation constraint:** at the time of this measurement,
`base-url` and `api-key-env` lived only on the global row, so two different
OpenAI-compatible gateways could not coexist. Per-agent transport mirrors now
persist both fields on each agent; current configuration and routing truth
lives in `docs/seon/reference/llm-adapters.md`.

## Sources

- [Meta: Introducing Muse Spark 1.1](https://ai.meta.com/blog/introducing-muse-spark-meta-model-api/)
- [Meta Model API docs — models](https://ai.developer.meta.com/docs/getting-started/models)
- [MarkTechPost release coverage](https://www.marktechpost.com/2026/07/09/meta-superintelligence-labs-releases-muse-spark-1-1/)
- [AI Weekly pricing alert ($1.25/$4.25)](https://aiweekly.co/alerts/meta-prices-muse-spark-11-api-at-125425-per-m-tokens)
- [DeepSeek pricing](https://api-docs.deepseek.com/quick_start/pricing)
- [Llama API retirement / OpenAI compatibility](https://llama.developer.meta.com/docs/features/compatibility/)
- Live verification transcript: this session's acme drives (usage datoms
  in `data/clusters/acme/store`, agents `gPY-2607101544`,
  `BWI-2607101547`, `mTX-2607101547`).
