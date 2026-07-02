---
type: research
status: active
tags: [research, agent, web, flow]
---

# Seon ↔ DiffusionGemma interface — adapter + gym design

> The design + ordered build plan for wiring DiffusionGemma into Seon as a
> first-class LLM provider, and for driving the diffusion capability ladder
> through the existing agent-gym. DESIGN + grounded plan only — nothing here is
> wired into the live pod (7890) and the GPU worker is NOT called from this work.
> Every claim cites a real file:line. Companion to
> [[serving-optimization-survey-2026-06-28]] (the two-endpoint motivation) and
> [[../CLAUDE]] (current worker state).

## TL;DR

- **One Seon provider, `:diffusiongemma`, with TWO backends** behind
  `SEON_DG_BACKEND=vllm|control`, exactly as the serving survey recommends
  ([[serving-optimization-survey-2026-06-28]] §3-4).
  - **`vllm`** (fast demo, no per-step control) reuses the EXISTING
    `:openai-compat` path verbatim — `seon.ai.openai-compat/complete` +
    `agent-adapter` (`src/seon/ai/openai_compat.cljs:322,415`). vLLM serves
    `/v1/chat/completions`; the adapter's ns doc already names "vLLM/SGLang"
    as the `:openai-compat` target (`openai_compat.cljs:6-14`). **No new
    request code** — it is a config row (`SEON_AI_BASE_URL` → the vLLM `/v1`
    root) plus a one-line dispatch case.
  - **`control`** (full per-step control, slower) is a NEW adapter ns,
    `seon.ai.diffusiongemma`, that speaks the RunPod async-job JSON the
    worker's `gpu_worker.py` expects (modes `generate|clamp_smoke|infill|
    introspect|probe`) over `https://api.runpod.ai/v2/{EP}/run` + `/status/{id}`.
- **It slots into the existing `SEON_AI_PROVIDER` dispatch** by adding
  `:diffusiongemma` to the provider enum (`seon.ai.cljs:160`) and a case to BOTH
  selection points: `seon.client/current-llm-fn` (`client.cljs:1897-1917`) and
  the gym's `paid-llm-fn` (`driver.cljs:~1160`). The `vllm` backend's case is
  literally "fall through to the openai adapter"; the `control` backend's case
  builds the new adapter.
- **Transport-retry is FREE if the control adapter returns the standard
  errors-as-values envelope.** The sole LLM retry authority is
  `seon.agent.turn/call-llm!` (`turn.cljs:330-357`) → `seon.retry/with-retry!`
  (`retry.cljs:167`); it retries any resp whose `:seon.ai/error` carries
  `:seon.ai/transport?`, HTTP 429, or 5xx (`turn.cljs:302-317`). A RunPod
  cold-start (66s, [[../CLAUDE]]) surfaces as a transport-flagged fetch throw
  or a 503 — both already retryable — so the adapter just maps RunPod failures
  onto `:seon.ai/error` (`ai.cljs:102-110`) and inherits backoff with zero new
  retry code.
- **The gym already drives any provider.** Diffusion experiments are new
  scenario EDN + a small set of NEW predicate kinds (`:infill-beats-ar`,
  `:clamp-held`, an eval-renoise predicate reusing the parser `:span/:error-kind`)
  added to `driver.cljs`'s `eval-predicate` (`driver.cljs:701-775`); the
  scenario→predicate→scorecard machinery, the paid-tier guard, and the LLM-retry
  it inherits all stay.
- **Reachability is a non-issue for OUTBOUND.** The pod's loopback-UDS-only
  constraint is the DB-WRITE path to wire-server; LLM calls are ordinary
  outbound HTTPS via `js/fetch`/the openai SDK (that is how DeepSeek/Anthropic
  already work today). The control adapter is one more outbound HTTPS call to
  `api.runpod.ai`. `RUNPOD_API_KEY` is read from `process.env` at call time and
  NEVER stored (same discipline as `DEEPSEEK_API_KEY`, `openai_compat.cljs:157-166`).

---

## 1. The worker's real wire shape (what the CLJS client must speak)

Grounded in `tmp/flash-diffgemma/gpu_worker.py` + `client.py`.

### 1a. Transport: RunPod async job, submit + poll (NOT one round-trip)

`client.py:5-39` is the canonical driver. The control adapter must replicate it:

- **Submit:** `POST https://api.runpod.ai/v2/{EP}/run` with body
  `{"input": <payload>}`, header `Authorization: Bearer ${RUNPOD_API_KEY}`,
  `Content-Type: application/json` (`client.py:7-12,24`). Returns `{"id": <jid>,
  "status": "IN_QUEUE"}`.
- **Poll:** `GET https://api.runpod.ai/v2/{EP}/status/{jid}` until `status` is
  `COMPLETED` (read `.output`), `FAILED`, or `CANCELLED` (`client.py:27-38`).
  `client.py` polls every 15s up to ~100 times — the cold-start (provision A100 +
  load 50GB, 66s; [[../CLAUDE]]) lives inside this poll loop, NOT the HTTP
  timeout. **This is the critical divergence from the OpenAI path** (single
  request/response): the adapter is submit-then-poll with a LONG total budget.
- The endpoint id is `DIFFGEMMA_EP` (e.g. `kzonsp5b18hpq5`), env-supplied
  (`client.py:3`).

### 1b. Request payload (the worker's `**payload` kwargs, `gpu_worker.py:148`)

`mode` selects the branch (`gpu_worker.py:151`); default `generate`.

| mode | required fields | optional tuning |
|---|---|---|
| `probe` | — | (cheap: imports+config, no model load, `gpu_worker.py:168-178`) |
| `introspect` | — | (reflect live model, `:184-258`) |
| `generate` | `prompt` | `max_new_tokens` (dflt 256), `trace` (`"canvas"`/`"entropy"`), + denoise knobs |
| `clamp_smoke` | — | `clamp_text` ({pos→string}) or `clamps` ({pos→token-id}), `prompt`, `seed_canvas`, `trace` (`:264-329`) |
| `infill` | `prefix` | `suffix`, `max_hole_tokens` (dflt 16), `expect_contains`, `prompt` (`:335-412`) |

Shared denoise knobs (folded by `_gen_overrides`, `gpu_worker.py:45-70`):
`max_denoising_steps` (int), `entropy_bound` (float, the commit-rate dial),
`t_min`/`t_max` (float), `stability_threshold` (int) + `confidence_threshold`
(float, pass BOTH to early-stop).

### 1c. Response (`output`) fields the adapter reads

- **Always:** `mode`, `transformers`, `torch`, `cuda`, `gpu`, `vram_gb`
  (`gpu_worker.py:157-165`).
- **`generate`:** `text`, `prompt_tokens`, `completion_tokens`, `gen_s`,
  `tok_per_s`, `tokens_per_forward`, `attn_impl` (`sdpa`|`eager`), `load_s`;
  with `trace` also a streamer `summary()` incl. `denoise_steps` /
  `committed_per_step` (`gpu_worker.py:417,434-445`).
- **`clamp_smoke`:** `all_held` (the decisive bool), `positions`
  ({pos→{forced_id,forced_tok,got_id,got_tok,held}}), `completion_text`,
  `clamps`, `gen_s`, `tokens_per_forward` (`gpu_worker.py:312-325`).
- **`infill`:** `prefix_held`, `suffix_held`, `middle_text`, `assembled`,
  `expect_contains`, `expect_met`, `hole_positions`, token counts
  (`gpu_worker.py:391-405`).
- **Errors are in-band**, per-mode keys: `gen_error` / `clamp_smoke_error` /
  `infill_error` + `trace_err` (`gpu_worker.py:326-328,409-411,446-448`). The
  worker does NOT raise to the HTTP layer for a generation failure — it returns
  a COMPLETED job whose output carries `*_error`. The adapter must inspect these
  and map them to `:seon.ai/error` (a processing error — NOT transport-flagged,
  so it is not retried; matches the openai adapter's non-retryable-parse stance).

---

## 2. The `:diffusiongemma` provider adapter

### 2a. Provider + backend selection

DiffusionGemma is ONE provider with a backend selector — NOT two providers
(keeps the survey's "same weights, same prompt format, two transports" framing,
[[serving-optimization-survey-2026-06-28]] §3).

```clojure
;; seon.ai.cljs — extend the enum (currently :160)
(schema/register! ::provider [:enum :deepseek :anthropic :openai-compat :diffusiongemma])

;; NEW backend selector — env SEON_DG_BACKEND, DB-ownable like ::provider.
;; control = the transformers worker (per-step seam); vllm = OpenAI-compatible
;; serving endpoint (speed, no seam).
(schema/register! ::dg-backend [:enum :vllm :control])
;; default :control (the research path this PRD targets); vllm is the demo flip.
```

`::dg-backend` reads via the SAME reactive-context pattern as `::provider`
(`ai.cljs:354-365`): DB-owned row attr, seeded once from `SEON_DG_BACKEND`,
runtime-tunable by transact. Add it to `env-var-specs` (`ai.cljs:261-270`) and
`config-attrs` (`ai.cljs:224-226`) so it rides the existing seed-once machinery
unchanged. Endpoint id + key envs (`SEON_DG_ENDPOINT`, `SEON_DG_API_KEY_ENV`
defaulting to `RUNPOD_API_KEY`) follow the same env-string reads
(`config.cljs:173`).

### 2b. The `vllm` backend = the EXISTING openai-compat path, zero new request code

vLLM serves OpenAI-compatible `/v1/chat/completions`
([[serving-optimization-survey-2026-06-28]] §2, the `vllm/vllm-openai:gemma`
image). The `:openai-compat` adapter already targets exactly this — its ns doc
says "any OpenAI-compatible gateway (vLLM/SGLang…)" (`openai_compat.cljs:6-14`),
`sdk-base-url` reconciles the `/v1` root (`openai_compat.cljs:141-155`), and key
resolution reads `SEON_AI_API_KEY` (`openai_compat.cljs:157-166`).

So `SEON_DG_BACKEND=vllm` is **configuration, not code**: point
`SEON_AI_BASE_URL` at the vLLM endpoint's `/v1` root and the dispatch falls
through to `openai/agent-adapter` (`openai_compat.cljs:415`). The only code is
the dispatch case (§2d) routing `:diffusiongemma`+`:vllm` to the openai adapter.
(Per-call diffusion knobs that vLLM accepts — e.g. `entropy_bound` via
`--hf-overrides` at serve time — are a SERVE flag, not a per-request field, so
nothing rides `:seon.ai/extra-body` for vllm.)

### 2c. The `control` backend = NEW `seon.ai.diffusiongemma` ns

Mirrors `seon.ai.openai-compat`'s public surface so the agent loop is
backend-agnostic: a `complete` (^:async, map-in/map-out, errors-as-values) and
an `agent-adapter` returning `(fn [ctx-string]) → Promise<{:text … :seon.ai/raw …}>`
(the shape `run-turn-once!` expects, `openai_compat.cljs:404-429`).

Request/response Malli schemas (the skeleton — these ARE the contract the next
implementer registers; see §6 for why this is doc-embedded, not a compiled file):

```clojure
(ns seon.ai.diffusiongemma
  "DiffusionGemma CONTROL backend — the transformers RunPod worker that
   keeps the per-step LogitsProcessor/accept_canvas seam (clamp/infill/
   eval-renoise). RunPod async job: submit /run, poll /status/{id}. The
   :vllm backend is NOT here — it reuses seon.ai.openai-compat unchanged.
   Errors-are-values: a cold-start / 5xx maps to :seon.ai/error with
   :seon.ai/transport? so seon.agent.turn/call-llm! retries it."
  (:require [seon.ai :as ai] [seon.config :as config]
            [seon.schema :as schema] [seon.error :as error]))

;; --- worker modes + knobs (the gpu_worker.py payload vocabulary) -----------
(schema/register! ::mode [:enum :generate :clamp-smoke :infill :introspect :probe])
(schema/register! ::prompt :string)
(schema/register! ::max-new-tokens :int)
(schema/register! ::trace [:enum :canvas :entropy])
;; denoise tuning — _gen_overrides (gpu_worker.py:45-70). entropy_bound = the
;; commit-rate dial; all optional, absent = the worker's gen-config defaults.
(schema/register! ::entropy-bound :double)
(schema/register! ::max-denoising-steps :int)
(schema/register! ::t-min :double)
(schema/register! ::t-max :double)
(schema/register! ::stability-threshold :int)
(schema/register! ::confidence-threshold :double)
;; clamp_smoke / infill inputs (third-party-shaped maps → :map boundary).
(schema/register! ::clamp-text [:map-of :string :string])  ; {canvas-pos → token-string}
(schema/register! ::prefix :string)
(schema/register! ::suffix :string)
(schema/register! ::max-hole-tokens :int)
(schema/register! ::expect-contains :string)

;; --- the generic control request: a mode + the knobs it uses ---------------
(schema/register! ::request
  [:map
   [::mode ::mode]
   [::prompt        {:optional true} ::prompt]
   [::max-new-tokens {:optional true} ::max-new-tokens]
   [::trace         {:optional true} ::trace]
   [::entropy-bound {:optional true} ::entropy-bound]
   [::max-denoising-steps {:optional true} ::max-denoising-steps]
   [::t-min {:optional true} ::t-min] [::t-max {:optional true} ::t-max]
   [::stability-threshold {:optional true} ::stability-threshold]
   [::confidence-threshold {:optional true} ::confidence-threshold]
   [::clamp-text {:optional true} ::clamp-text]
   [::prefix {:optional true} ::prefix] [::suffix {:optional true} ::suffix]
   [::max-hole-tokens {:optional true} ::max-hole-tokens]
   [::expect-contains {:optional true} ::expect-contains]])

;; --- response: the worker `output` map (third-party shape) + our envelope --
;; The worker's JSON is Google/RunPod's shape, not seon's — :map boundary,
;; like :seon.ai/provider-fields (ai.cljs:91-94). We surface a normalized
;; :seon.ai/text (from output.text / completion_text / middle_text per mode)
;; for the agent-loop path, the RAW worker output for experiments, and the
;; errors-as-values envelope.
(schema/register! ::worker-output :map)
(schema/register! ::response
  [:map
   [:seon.ai/text :string]                         ; "" on non-generate modes
   [::worker-output {:optional true} ::worker-output]
   [:seon.ai/error {:optional true} :seon.ai/error]])
```

`complete` flow:

1. Resolve `endpoint` (`SEON_DG_ENDPOINT`) + key (`SEON_DG_API_KEY_ENV` → that
   env var, default `RUNPOD_API_KEY`) at CALL TIME — never stored
   (`openai_compat.cljs:157-166` is the template). Missing either → a
   `config-error`-style envelope (NOT transport-flagged), exactly like
   `openai_compat.cljs:265-272`.
2. Build the worker payload (`::request` → the snake_case JSON the worker reads)
   — string-keyed JSON via `clj->js`, mode kebab→snake (`:clamp-smoke` →
   `"clamp_smoke"`).
3. **Submit** `js/fetch` `POST {EP}/run` `{"input": payload}`; **poll**
   `GET {EP}/status/{jid}` until terminal. Wrap fetch throws (DNS/refused/reset =
   the cold-start transient) in `:seon.ai/transport? true` (`ai.cljs:69-77`); a
   RunPod 5xx/429 → `:seon.ai/status` + optional `:seon.ai/retry-after-ms`
   (`ai.cljs:83`). A `FAILED`/`CANCELLED` job or an in-band `*_error`
   (`gpu_worker.py:326,409,446`) → a plain `:seon.ai/msg` (processing error, NOT
   retried).
4. Normalize: `:seon.ai/text` from `output.text` (generate) /
   `output.completion_text` (clamp_smoke) / `output.middle_text` (infill);
   keep the whole `output` under `::worker-output` for the gym to assert on.

`agent-adapter` wraps `complete` for the turn loop, defaulting `::mode :generate`
+ the agent's prompt as `::prompt` (the `complete+wrap` shape,
`openai_compat.cljs:404-429`).

### 2d. Dispatch — the two selection points

```clojure
;; seon.client/current-llm-fn (client.cljs:1897-1917) — ADD a case:
(case (ai/provider)
  :anthropic      …
  :diffusiongemma (case (ai/dg-backend)
                    :control (diffusiongemma/agent-adapter)
                    (openai/agent-adapter))        ; :vllm → openai-compat path
  ;; :openai-compat + :deepseek fall through to the openai adapter (unchanged)
  (openai/agent-adapter))
```

The SAME case is mirrored in the gym's `paid-llm-fn` (`driver.cljs:~1160`, which
already `case`-dispatches `:anthropic` vs the openai default) — keeping the gym's
"same selection point as the live pod" invariant (`driver.cljs:1155-1158`).

### 2e. Transport-retry wrapping (free, via the standard envelope)

Nothing new. `call-llm!` (`turn.cljs:330-357`) is the SOLE retry authority; it
feeds `llm-fn` to `seon.retry/with-retry!` (`retry.cljs:167`) under
`llm-retryable?` (`turn.cljs:302-317`: transport? OR 429 OR 5xx) with
exponential backoff (base 500ms ×2, jittered, 20s per-wait clamp, 60s total cap,
`SEON_AI_MAX_RETRIES` retries — `turn.cljs:319-328`). Because the control adapter
emits the standard `:seon.ai/error` envelope, a cold-start fetch throw
(`transport?`) or a RunPod 503 is retried automatically; a `Retry-After` is
honored via the `:seon.retry/override` hook (`turn.cljs:347-350`).

**One caveat to verify when building:** the 60s total-backoff cap
(`turn.cljs:299`) is tuned for fast providers. A RunPod COLD start is ~66s INSIDE
one poll loop, not across retries — so the long wait belongs in the adapter's
poll budget (a generous `js/fetch`-loop timeout), NOT in the retry strategy. The
retry layer should only re-fire on a genuinely failed submit/poll, not babysit a
warming worker. Flag: consider a diffusion-specific total cap if cold starts
prove to need cross-retry patience.

---

## 3. The gym harness for diffusion experiments

The gym (`test/seon/gym/driver.cljs`) is scenario EDN → mechanical predicates +
optional LLM-judge → a `(scenario × git-sha)` scorecard (`driver.cljs:1-71`). It
already drives the ACTIVE provider's adapter through `run-loop!` on the paid tier
(`driver.cljs:40-49,1153-1164`) and inherits the LLM-retry above. Diffusion
experiments are NEW scenarios + a few NEW predicate kinds; the machinery is
unchanged.

### 3a. Two shapes of diffusion gym run

1. **Agent-behavior runs (existing shape).** Set `SEON_AI_PROVIDER=diffusiongemma`
   (+ `SEON_DG_BACKEND`), run the existing scenarios. This measures whether a
   DiffusionGemma-driven agent passes the same correctness ladder as a DeepSeek
   agent — zero new scenario code, the provider swap is the whole experiment.
   The `usage-logging` wrapper (`driver.cljs:1139-1149`) prints per-call spend;
   judge stays pinned to DeepSeek (`driver.cljs:933-955`) so the grader never
   inherits the model-under-test.
2. **Capability-ladder runs (NEW predicate kinds).** These do NOT drive the
   agentic loop — they call the control adapter DIRECTLY with `clamp_smoke` /
   `infill` / `generate` and assert on the worker `output`. This is a new, small
   driver path (`run-experiment!`) reusing the scorecard schema; the scenario
   carries the worker payload + the new predicates below.

### 3b. New predicate kinds (extend `:seon.gym.predicate/kind`, `driver.cljs:178-181`)

Added to `eval-predicate`'s `case` (`driver.cljs:715-769`). Each returns
`[pass? actual]` and tags a rubric axis (reuse `:terminates`/`:replies-honestly`
or add a `:refines-with-control` axis to `:seon.gym.axis/name`, `driver.cljs:128`).

- **`:clamp-held`** — assert the control worker's `clamp_smoke` output
  `all_held` is true (`gpu_worker.py:314`). PASS iff every clamped position held
  its forced id. This is the T0 gate: the LogitsProcessor seam works at all.
- **`:infill-beats-ar`** (the T2 first KILL gate, [[../CLAUDE]] plan §5) —
  run the SAME hole twice: once `mode=infill` (prefix+suffix clamped,
  bidirectional middle, `gpu_worker.py:335-412`) and once an AR baseline that
  sees ONLY the prefix (suffix-blind). PASS iff infill's `expect_met`
  (`gpu_worker.py:400`) is true AND the AR baseline's is false — i.e. seeing the
  suffix actually changed the answer. A both-pass or both-fail is RED (the
  diffusion advantage is unproven), the same discrimination logic the judge
  calibration already uses (`driver.cljs:970-1003`).
- **`:eval-renoise-converges`** — drive `generate` (noisy diffusion gen), run the
  output through Seon's parser-as-oracle, and assert that the parser's
  `:span`/`:error-kind` signal ([[../CLAUDE]] settled §, the measured 92.7%
  detect oracle) points at the actual defect span. PASS iff the parser flags an
  error AND its `:span` overlaps the injected/known-bad region — i.e. the
  re-noise DIAL has a real target. (The renoise loop itself is a later worker
  capability; this predicate proves the CONTROL SIGNAL is usable first.) Reuses
  the existing parser; the predicate just compares `:span`/`:error-kind` to the
  scenario's reference.

These are mechanical (datalog-free) — they read the worker `output` the control
adapter returned, the same way `:domain-attrs` reads the post-run store
(`driver.cljs:762-766`). A predicate that throws scores RED with the error as
`actual` (`driver.cljs:770-771`) — a blind referee never silently passes.

### 3c. Reuse, not rebuild

- The paid guard (`:seon.gym/allow-paid? true` + key present,
  `driver.cljs:1404-1442`) gates control-worker spend exactly as it gates LLM
  spend — a diffusion run costs A100 time.
- The scorecard (`:seon.gym/scorecard`, `driver.cljs:357-377`) records
  `(scenario × git-sha × run-id)` so an entropy_bound sweep shows as a MOVED
  number, not an anecdote — the same quantification the context work uses.
- `tokens_per_forward` / `tok_per_s` / `denoise_steps` from the worker output
  land in the scorecard `actual` strings as evidence (TOKENS per the hard
  size-reporting rule — the worker already reports token-units, never chars).

---

## 4. Reachability — pod (loopback) → RunPod (HTTPS)

- **The UDS-only rule is the INBOUND + DB-write constraint, not outbound.** The
  pod's loopback-UDS is (a) the inspector HTTP on 127.0.0.1:7890 and (b) the
  DB-write forward to wire-server. LLM calls are ordinary OUTBOUND HTTPS — the
  openai/anthropic SDKs already `js/fetch` `api.deepseek.com` / `api.anthropic.com`
  today. The control adapter is one more outbound HTTPS target,
  `api.runpod.ai/v2/{EP}/{run,status}` — no new network surface, no UDS
  involvement.
- **Call path:** `seon.agent.turn/call-llm!` → `diffusiongemma/agent-adapter`'s
  fn → `complete` → `js/fetch POST https://api.runpod.ai/v2/{EP}/run` → poll
  `GET …/status/{jid}` → normalized resp → back up the turn loop. Identical
  control flow to the openai path except submit+poll replaces one round-trip.
- **Keys (env, never committed):** `RUNPOD_API_KEY` read from `process.env` at
  call time (default name; overridable via `SEON_DG_API_KEY_ENV` →
  `seon.config/env-string`, `config.cljs:173`). The endpoint id `SEON_DG_ENDPOINT`
  is non-secret config (DB-ownable). Mirrors `openai_compat.cljs:157-166`'s
  "key value is never transacted" discipline and the `.env` the worker already
  uses ([[../CLAUDE]] "How to run it"). NOTHING about RunPod credentials lands
  in the DB or git.
- **Co-location note (future):** [[../CLAUDE]] keeps the custom image to run
  parse/eval/retrieve ON the worker (kill the internet round-trip for the live
  feedback loop). That changes WHERE the control loop runs, not this adapter's
  shape — the pod still submits a job; the per-step seam just executes locally to
  the GPU. Out of scope here; flagged so the adapter's mode vocabulary stays
  forward-compatible (new modes = new `::mode` enum values).

---

## 5. Ordered implementation plan

**Load-bearing first, deferred last. Each step is independently verifiable.**

1. **Provider plumbing (no worker calls).** Add `:diffusiongemma` to `::provider`
   (`ai.cljs:160`), add `::dg-backend` + its env-var-spec + config-attr
   (`ai.cljs:224-270`), add a `dg-backend` reader mirroring `provider`
   (`ai.cljs:354-365`). Verify in the REPL: `(ai/provider)` /`(ai/dg-backend)`
   read env + row. **Live-proof:** transact a backend switch, confirm it
   persists. *No GPU, no risk.*
2. **`vllm` backend wiring.** Add the dispatch cases to `current-llm-fn`
   (`client.cljs:1907`) and `paid-llm-fn` (`driver.cljs:1160`): `:diffusiongemma`
   + `:vllm` → `openai/agent-adapter`. Point `SEON_AI_BASE_URL` at a vLLM `/v1`
   root. **This is the whole vllm backend** — verify with the existing
   openai_compat tests + one live call against any OpenAI-compatible endpoint.
3. **`control` adapter (`seon.ai.diffusiongemma`).** Build `complete` (submit +
   poll + envelope mapping) + `agent-adapter` per §2c. Unit-test the
   payload-build (kebab→snake, knob passthrough) and the response-normalize
   (each mode's text field, the in-band `*_error` → envelope, the
   transport-flag mapping) with an INJECTED fetch seam (the `*fetch*` dynamic-var
   pattern, `openai_compat.cljs:302-320`) — zero GPU spend. **Falsify:** feed a
   simulated 503 and a simulated `gen_error` output; assert one is
   transport-retried and the other is not.
4. **Control dispatch + retry proof.** Wire `:control` into both selection points
   (§2d). Prove the retry inheritance with the injected fetch: a transport throw
   on submit re-fires under `with-retry!`; a `FAILED` job does not. (No new retry
   code — this just confirms the envelope mapping is right, `turn.cljs:302-317`.)
5. **Gym capability predicates.** Add `:clamp-held`, `:infill-beats-ar`,
   `:eval-renoise-converges` to `:seon.gym.predicate/kind` + `eval-predicate`
   (§3b), plus a `run-experiment!` driver path that calls the control adapter
   directly and scores against the worker `output`. Author the T0/T2 scenario
   EDN. Unit-test predicates against CAPTURED worker-output fixtures (real JSON
   from the owner's live runs) — no live GPU in CI.
6. **Live wiring (owner-gated).** Only after 1-5 are green offline: point at the
   live RunPod endpoint, run the T0 `clamp_smoke` gym scenario, then the T2
   `infill` kill-gate, under `:seon.gym/allow-paid? true`. **The owner drives the
   GPU** — this work hands over a tested-offline adapter + scenarios, not a live
   integration.

**Deferred (out of scope, flagged for forward-compat):**

- The eval-RENOISE loop itself (worker re-noising from the parser span) — the
  worker primitive ([[../CLAUDE]] plan §5 #2) and a `mode=renoise`. The gym
  predicate proves the SIGNAL first.
- Retrieval-denoising + live-feedback (Route A) modes — new `::mode` values when
  the worker grows them.
- The Seon co-location image (§4) — moves the control loop onto the GPU; adapter
  shape survives.
- A diffusion-specific retry total-cap if cold starts need cross-retry patience
  (§2e caveat).

---

## 6. On the skeleton (why doc-embedded, not a compiled file)

The §2c/§2d code blocks ARE the skeleton — registered schemas, the dispatch
cases, the `complete`/`agent-adapter` surface. They are deliberately NOT written
as a live `src/seon/ai/diffusiongemma.cljs` file: the running `cljs-watch`
compiles every `.cljs` under the source paths into the SHARED pod build, so a
new (necessarily incomplete) ns risks breaking the build for every agent on this
tree — exactly the "do NOT touch the live pod" boundary this task carries. The
next implementer lifts these blocks into the file as STEP 1/3 of §5, where they
can verify each against the live REPL. This keeps the deliverable a design with a
copy-paste-ready skeleton AND zero risk to the shared build.

## Entry points

- [[serving-optimization-survey-2026-06-28]] — the two-endpoint motivation
  (vLLM speed vs transformers control).
- [[../CLAUDE]] — live worker state, deploy/run, the capability ladder.
- `src/seon/ai/openai_compat.cljs` — the adapter the `vllm` backend reuses + the
  template the `control` adapter mirrors.
- `src/seon/agent/turn.cljs:289-357` — the retry authority the control backend
  inherits.
- `test/seon/gym/driver.cljs` — the gym the diffusion predicates extend.
- `tmp/flash-diffgemma/{gpu_worker,client}.py` — the worker wire shape (gitignored).
</content>
</invoke>
