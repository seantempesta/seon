# src/seon/ai — pod LLM routing, providers, and token estimates

**Read before editing:** `docs/seon/architecture/agent-runtime.md` (where the
LLM call sits in the turn), `observability.md` (what of the request/response
  must persist). Use `data-oriented-clojure` and `clojurescript`; read the
  vendored provider SDK before changing an adapter.

## The contract

- **Providers return values, never throw**: success or a `:seon.ai/error`
  (the `:seon/error` specialization carrying provider fields). The agent
  loop must survive every provider failure as data.
- **`seon.agent.turn/call-llm!` is the sole retry authority.** Providers do
  ONE attempt; backoff/strategy lives there (`seon.retry`, the ported
  `again` design). Never add a retry loop inside a provider.
- **One fresh abort signal belongs to one provider attempt.**
  `seon.agent.turn/bounded-llm-attempt!` creates it and every dispatch adapter
  preserves it through the provider request. The attempt timeout aborts before
  it returns its timeout value. OpenAI first-form `.abort()` remains a distinct
  successful stream-consumer stop; DiffusionGemma also best-effort cancels a
  known remote job id. Never reuse an aborted signal across retries.
- **Per-agent provider routing** is a complete non-secret overlay on the agent
  entity: `:seon.ai/agent-provider`, `/agent-model`, `/agent-temperature`,
  `/agent-max-tokens`, `/agent-thinking`, `/agent-timeout-ms`, `/agent-base-url`,
  `/agent-api-key-env`, `/agent-dg-backend`, and `/agent-extra-body-edn`.
  Each absent or `:inherit` value falls through to the cluster row. Secrets
  remain in the named process environment variable. This is how independent
  agents use different providers and compatible gateways; never add
  call-site provider conditionals.
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

`dispatch.cljs` owns provider selection and planning dispatch.
`typeahead.cljs` owns the optional completion consumer; it does not create a
second LLM retry or transport path. Adapters are `openai_compat.cljs`
(DeepSeek et al—the pod default), `anthropic.cljs`, and
`diffusiongemma.cljs` (the optional diffusion-worker provider).
Verified `:openai-compat` gateways: OpenRouter (acme), Meta Model API
(Muse Spark 1.1 — config recipe, measured speed, and the
`SEON_AI_THINKING=minimal` dial in
`docs/prds/agent-ctx/research/meta-model-api-muse-spark-2026-07-10.md`), and
Moonshot AI (Kimi K3 — `https://api.moonshot.ai/v1`, `kimi-k3`, credential
name `MOONSHOT_API_KEY`; K3 only accepts max reasoning effort today).
On `:openai-compat` a string thinking goes out as the STANDARD
`reasoning_effort` and the vendor `:thinking` field is NEVER sent
(strict gateways 400 unknown params); `:deepseek` keeps its explicit
toggle. Vendor-specific request fields ride `:extra-body` only.
Vendored SDK grounding: `reference-code/openai-node/`,
`reference-code/anthropic-sdk-typescript/`, `reference-code/js-genai/`.
