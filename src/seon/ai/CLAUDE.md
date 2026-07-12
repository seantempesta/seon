# src/seon/ai — LLM providers (.cljs = pod-active; .clj = paused JVM track)

**Read before editing:** `docs/seon/architecture/agent-runtime.md` (where the
LLM call sits in the turn), `observability.md` (what of the request/response
must persist). The `claude-api` dev skill covers Anthropic specifics.

## The contract

- **Providers return values, never throw**: success or a `:seon.ai/error`
  (the `:seon/error` specialization carrying provider fields). The agent
  loop must survive every provider failure as data.
- **`seon.agent.turn/call-llm!` is the sole retry authority.** Providers do
  ONE attempt; backoff/strategy lives there (`seon.retry`, the ported
  `again` design). Never add a retry loop inside a provider.
- **Per-agent provider routing** (`:seon.ai/agent-provider` via the config
  manifest) selects the adapter per agent — this is how forensic/debug
  agents get a reasoning model with thinking ON while workers stay on the
  cheap default (DeepSeek via `openai_compat.cljs`). Extend routing through
  the manifest, not with per-callsite conditionals.
- **`::max-tokens` is the OUTPUT cap** — a context-window limit is a
  separate concern; don't conflate them.
- **`tokens.cljs` owns the one token estimator** (`chars/4`). Every size
  shown to a human or agent goes through it. If a real tokenizer ever
  lands, it goes behind this ns and nowhere else.
- Prompt-cache discipline is a provider-visible concern: the context is
  assembled stable-prefix-first (aged transcript clips byte-frozen) — a
  provider change that reorders or re-flows prompt parts silently destroys
  the cache hit rate; check `llm-usage` cached-token counts after changes.

**Model catalog** (recommended models + working configs per provider):
`docs/seon/reference/llm-adapters.md` §"Model catalog" — update it when a
provider ships/deprecates a model.

Adapters: `openai_compat.cljs` (DeepSeek et al — the pod default),
`anthropic.cljs`, `diffusiongemma.cljs` (the diffusion-worker provider).
Verified `:openai-compat` gateways: OpenRouter (acme), Meta Model API
(Muse Spark 1.1 — config recipe, measured speed, and the
`SEON_AI_THINKING=minimal` dial in
`docs/prds/agent-ctx/research/meta-model-api-muse-spark-2026-07-10.md`).
On `:openai-compat` a string thinking goes out as the STANDARD
`reasoning_effort` and the vendor `:thinking` field is NEVER sent
(strict gateways 400 unknown params); `:deepseek` keeps its explicit
toggle. Vendor-specific request fields ride `:extra-body` only.
Vendored SDK grounding: `reference-code/openai-node/`,
`reference-code/anthropic-sdk-typescript/`, `reference-code/js-genai/`.
