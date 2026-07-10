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
```

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
   cap was sufficient in all live turns; consider 8192 for headroom.
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
cost-competitive default. (Note: per-agent routing can select the
provider, but `:seon.ai/base-url`/`:seon.ai/api-key-env` live on the
GLOBAL row — routing one agent to Meta while others stay on DeepSeek
works only because `:deepseek` ignores the row's base-url; two
different `:openai-compat` gateways at once is not supported today.)

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
