---
type: reference
status: active
tags: [reference, agent, schema, flow]
---

# Grounding — read the real source before you build

> Every load-bearing claim in [[architecture]] and [[roadmap]] maps to a concrete
> `reference-code/…:LINE` (or `tmp/flash-diffgemma/…:LINE`, or `src/seon/…:LINE`)
> cite. The ground truth for the model is the vendored submodule
> `reference-code/transformers/` pinned to **v5.11.0** (HEAD `e7b5b964e6`) — the
> EXACT version the worker runs. Where the source is silent, this says so rather
> than inferring. Read your area's rows BEFORE you build it; guessing diffusion or
> library semantics produces confident, wrong code.

## Per-area read-first map

| Building… | Read FIRST | Why it grounds the work |
|---|---|---|
| **clamp / infill / span-renoise** | `tmp/flash-diffgemma/diffgemma_common.py:35-78` (Clamp), `:184-222` (offset_map/span); `transformers .../generation_diffusion_gemma.py:1034` | the per-step apply site + the clamp/offset primitives |
| **stop-at-step-K / eval-renoise** | `generation_diffusion_gemma.py:466,1207` (stopping ABC), `:751,786` (loops), `:311` (temp ramp) | the external-stop pattern + the temperature-compression trap |
| **the quality gate (parse/eval)** | `src/seon/repl/internal.cljc:561-677` (`parse-forms`, `:span`/`:error-kind`/`:source`); `src/seon/eval.cljs` | the syntactic + semantic oracle the gate runs |
| **the validation ladder (T1 lint / phase gate)** | `src/seon/diffusion/grammar.cljc:16` (`malformed-def?`), `:36` (`phase-grammars`), `:57` (`phase-violation?`); `src/seon/diffusion/oracle.cljs:122-173` (`refine` folds them); `bin/oracle-server:74-116` (`refine-parse` — bb loads the SAME ns) | one definition, pod + bb, no drift |
| **refine_loop / validation-as-early-stop** | `tmp/flash-diffgemma/gpu_worker.py:1145-1321` (the gate: parse → eval (`eval_gate`, dflt on) → behavioral `[{call,expect}]`; per-iteration `oracle_ms`) | the loop stops on PROOF, not confidence |
| **the FP8/fast-MoE Hopper gate (speed)** | `reference-code/transformers/.../integrations/moe.py:287-288` (`_can_use_grouped_mm` bf16-only under compile), `deepgemm.py:19-24` (FP8 experts require SM90+), `sonicmoe.py:62-68` (raises below SM90) | why there is no kernel lever on the A100 |
| **forced-spec / missing-spec detector** | `src/seon/agent.cljs:210,215`; `src/seon/eval.cljs:1357` | `:seon.fn/spec` present + no `:seon.fn/schema-error` = the detector |
| **the mode schema / malli shapes** | `src/seon/db/internal.cljs:147-360` (the malli→datahike bridge); `src/seon/schema.cljc:193` | what each registered attr bridges to; `register!` ≠ bridge |
| **the `:diffusiongemma` provider** | `src/seon/ai/openai_compat.cljs:322,415`; `src/seon/ai.cljs:160`; `src/seon/agent/turn.cljs:330-357`; `src/seon/retry.cljs:167` | the two-backend dispatch + the free transport-retry |
| **the gym predicates** | `test/seon/gym/driver.cljs:701-775` (`eval-predicate`), `:1585` (hermetic `:memory` boot) | scenario→predicate→scorecard; a throwing predicate scores RED |
| **deploy stability / keep-warm** | `reference-code/flash/.../serverless.py:1294,571`; `reference-code/runpod-python/.../rp_job.py:150` | `imageName` is structural; FlashBoot is a platform-side toggle only |

## transformers v5.11.0 — the generation seams

File: `reference-code/transformers/src/transformers/models/diffusion_gemma/generation_diffusion_gemma.py`.
Full read-through: [[research/transformers-diffusion-source-grounding-2026-06-28]].

- **Per-step control hook — `:1034`** ✓. `processed_logits = logits_processor(input_ids,
  raw_logits, cur_step=cur_step)` runs inside `_denoising_step`, EVERY step, BEFORE
  the temperature schedule (`_prepare_logits_processor:1162` only APPENDS the temp
  processor after any user list, `:1170-1181`). A near-one-hot survives division by a
  positive temperature → our `ClampLogitsProcessor` holds. This is the whole
  between-step-control thesis.
- **Commit is emergent, not a mask — `:388,400,444`** ✓. `EntropyBoundSampler`:
  `accept_code-buffer` (`:400`) keeps the lowest-entropy positions up to `entropy_bound`
  (`:431-442`); `renoise_code-buffer` (`:444`) re-randomizes the rest via
  `initialize_code-buffer` = `torch.randint(0, vocab_size)` (`:388`) — **random vocab
  ids, no mask token**. `mask_token_id=4` is vestigial (the only `masked_*` calls are
  `masked_scatter` for image embeds, `modeling_diffusion_gemma.py:1094`). HIGHER
  `entropy_bound` → more accepted/forward → higher `tokens_per_forward`.
- **Two nested loops — `:713,751,786`** ✓. Outer block-AR over code-buffers
  (`max_new_code-buffers = ceil(max_new_tokens / code_buffer_length)`, `:638`); inner denoise
  `for cur_step in reversed(range(1, max_denoising_steps+1))` — N..1, the cap counts
  DOWN. The outer loop appends `argmax_code-buffer` (the draft), `:786`.
- **`max_denoising_steps` is a CAP, not a checkpoint — `:311`** ✓ (the trap).
  `temperature = t_min + (t_max-t_min)*(cur_step/max_denoising_steps)`
  (`LinearTemperatureScheduleLogitsProcessor.__call__:311`). Shrinking the cap
  COMPRESSES the ramp → a different generation regime. To peek at the natural
  intermediate state, keep N and stop EXTERNALLY.
- **Custom early-stop — `:466,1207`** ✓ (the supported override).
  `DiffusionGemmaAdaptiveStopping` is an ABC (`:466`); subclass it and inject via
  `_prepare_diffusion_stopping_criteria` (`:1207`); it gets `(argmax_code-buffer,
  processed_logits)` at the per-step update site (`:1059`). Runs on the default
  non-compiled DynamicCache path (`is_compiling=False`, `:692`) so a Python
  parse/eval doesn't break `torch.compile` (`:1258-1263`) — but it is therefore
  **mutually exclusive with `cache_implementation="static"` + compile** (`:692-696`):
  compiled-fast OR custom-stop, choose per experiment.
- **Seed + resume — `:979-983,826,635-636`** ✓. `decoder_input_ids` AND
  `self_conditioning_logits` are both seedable start state; `past_key_values` is
  returned and accepted back — the outer-loop-of-K-step-`generate()` pattern reuses
  PUBLIC seams only, no fork.
- **The step metric — `:829`** ✓. `out.tokens_per_forward` = non-pad tokens ÷
  decoder forward passes. The actual denoise-step count is NOT returned directly;
  recover it as `num_valid_tokens / tokens_per_forward` or `len(streamer.steps)`.
- **The streamer — `:773-779`** ✓. `put_draft(value=argmax_code-buffer.cpu())` unless
  `_takes_logits` (then the FULL `(1,code-buffer,vocab)` logits copy — GB/step, the OOM
  risk). Default to the CHEAP argmax trace; gate entropy hard.
- **Newer transformers buys nothing** ✓. The streamer/logits/sampler/seed seam is
  byte-identical v5.11.0..`main`; upgrading adds only convenience + a cache-API
  rename risk. STAY on 5.11.0 (matches the checkpoint's `generation_config.json`).

## The worker primitives — `tmp/flash-diffgemma/`

- **`ClampLogitsProcessor` (`diffgemma_common.py:35-78`)** — forces clamped
  positions to a near-one-hot every step; documented to run before the temp schedule
  (`:52-59`). PROVEN by `clamp_smoke`.
- **`build_offset_map` / `span_to_positions` (`:184-222`)** — char-span ↔ code-buffer
  token positions, the bridge from the parser's `:span` to the renoise dial.
- **`TraceStreamer` (`:81`)** — mirrors the reference `TextDiffusionStreamer`
  contract exactly (`put`/`put_draft`/`_takes_logits`/`end`); the `n_stable` path
  works WITHOUT logits.
- **`StepCountStopping` / `good_clamp_for_renoise` (`gpu_worker.py`,
  `eval-renoise-worker-build`)** — the eval-renoise round-trip; py_compile-clean,
  pure halves unit-checked off-GPU, the torch half UNVERIFIED until a GPU run.
- **`worker_sha`** — `sha256(gpu_worker.py + diffgemma_common.py)[:12]` computed
  inside the container; `verify_fresh.py` refuses a measurement until it matches
  local. The deploy-stability guard.

## The parser/eval oracle — `src/seon/`

Full measurement: [[research/parser-as-generation-oracle-2026-06-28]].

- **`parse-forms` (`src/seon/repl/internal.cljc:561-677`)** — returns each top-level
  form as a `:kind :form` entry with its `:form` sexpr; a `:read` entry carries
  `:error-kind` + `:span [start end]` + `:source`. The syntactic tier: **92.7%** of
  injected corruptions detected, no model call. The SAFE classes
  (`:eof`/`:unmatched-delimiter`) are **100%** mechanically recovered by `seon.repair`
  with zero model round-trip; only the FLAG class (`:invalid-token`) and eval failures
  re-noise.
- **`seon.eval/eval` (the SCI cage)** — never throws, returns `{:ok true :value v}` |
  `{:ok false :error …}`. The semantic tier: **62.5%** of masked-divergent
  corruptions caught reference-free (a hard error), **91.5%** with a comparator; the
  ~8.5% residual is dead-data mutation (off the live path) — the factual/retrieval
  tier's job. Combined parser+eval = **93.5%** of meaning-altering corruptions.
- **HONEST LIMIT (load-bearing for [[roadmap]]'s kill-gate):** these numbers measured
  DETECTING corruptions of known-good code. They do NOT certify a from-scratch spec
  is faithful — a vacuous `[:map]` parses, evals, AND instruments. The
  strong-model A/Bs (gemini-flash, DeepSeek-on-acme) were NULL: the collar's value is
  on NOISY generation, not capable AR output.

## The validation ladder — shared predicates, worker gate

- **`seon.diffusion.grammar.cljc`** — the dependency-free T1/phase predicates:
  `malformed-def?` (`:16` — `def` is valid only name+init / name+"doc"+init, so
  `(def mean [v] body)` is unambiguously a defn typo; `(def xs [1 2 3])` stays a
  clamp), `phase-grammars` (`:36`) + `phase-violation?` (`:57` — name-based head
  match, so `register!` / `seon.schema/register!` / the bare sym all count).
  Loaded by BOTH `seon.diffusion.oracle` (`oracle.cljs:115-116` "No copy here;
  drift is impossible") AND babashka (`bin/oracle-server:47`).
- **bb `op:"refine"`** (`bin/oracle-server:74-116,144`) — `refine-parse` returns
  the CHEAP tiers of the unified control set (clamps + renoise spans from parse +
  structural + phase) in one warm ~0.05 ms call; injections/eval stay pod/node-side.
- **The worker gate** (`gpu_worker.py:1145-1321`, `mode:"refine_loop"`) — the
  ladder as termination: `_validate` runs parse (T0/T1/phase via the bb pipe) →
  eval (`eval_gate`, default on) → behavioral (`[{call,expect}]`); "validated" =
  parse-clean AND runs AND behavioral-clean — the docstring's own words: "the
  model's probability is irrelevant once we have PROOF it executes." Proven
  offline by `eval_gate_earlystop_proof.py` (6 cases, real bb+node oracles).
- **Live-test correction (2026-07-02):** the ratio literal `9/5` is NOT a CLJS
  eval error — the real node eval tier returns 1.8, ok:true (bb parse accepts it
  too). def-vs-defn IS real (eval: "Too many arguments to def"; T1 catches it
  structurally, cheaper). Don't cite the ratio as an oracle catch.
- **Oracle liveness is load-bearing:** E1's behavioral zeros were produced by a
  DEAD eval bundle, not the model — proven in
  [[research/e1-behavioral-zero-audit-2026-07-02]] (dead-tier simulation
  reproduces the arm means to 3 decimals). A tier's verdict counts only after
  its golden-sample liveness gate passes (`assert_oracle_live`).

## The program-graph spec detector — `src/seon/`

- **`:seon.fn/spec` (`agent.cljs:210`) + `:seon.fn/schema-error` (`agent.cljs:215`);
  `eval.cljs:1357`** — the reconstitutable predicate is exactly "has a `:seon.fn/spec`
  AND no `:seon.fn/schema-error`". So "unspecced" = a `:seon.fn/sym` whose entity is
  MISSING `:seon.fn/spec` — a one-line query, the `missing-spec-target` trigger that
  forces `:defn-with-specs` with zero model cooperation.
- **detect-and-tee + identity-upsert** (`docs/seon/concepts/code-as-data-runtime.md`)
  — every successful eval becomes `:seon.ns`/`:seon.fn`/`:seon.schema` entities; a
  redefinition REPLACES in place. The publish gate (`specced?` AND `last-passed-at >
  last-failed-at`, `code-as-data-runtime.md:84-92`) IS the convergence bar — reused,
  not reinvented.

## The malli → datahike bridge — `src/seon/db/internal.cljs:147-360`

The mode schema's attrs must be bridge-storable. `:seon.db/ref`→`:db.type/ref`;
`{:seon.db/identity true}`→`:db.unique/identity` (349); `{:seon.db/component true}`→
`:db/isComponent` (350); `:symbol`→`:db.type/symbol` (193, so `:seon.dg.mode/context-fns`
/ `:seon.dg.mode/vocab` store native symbols); `[:vector :keyword]`→cardinality-many.
⚠ **`schema/register!` (`schema.cljc:193`) ≠ bridge** — `register!` is in-memory malli
only; the bridge runs lazily at transact time. So in-memory-only value shapes (a
`:map`) are fine to register and never hit the bridge; only attrs you `transact!`
must be bridge-storable.

## The Seon interface — provider + gym

Full design: [[research/seon-diffusion-interface-design-2026-06-28]].

- **`:openai-compat` reuse (`openai_compat.cljs:322,415`, ns doc `:6-14`)** — vLLM
  serves `/v1/chat/completions`; the `vllm` backend is a config row
  (`SEON_AI_BASE_URL` → the vLLM `/v1` root) + a one-line dispatch case, NO new
  request code. Provider enum at `ai.cljs:160`; selection points
  `client/current-llm-fn` (`client.cljs:1897-1917`) + gym `paid-llm-fn`.
- **Free transport-retry (`turn.cljs:330-357` → `retry.cljs:167`)** —
  `seon.agent.turn/call-llm!` is the sole retry authority; it retries any
  `:seon.ai/error` flagged `:seon.ai/transport?` / 429 / 5xx (`turn.cljs:302-317`). A
  RunPod cold-start surfaces as a transport-flagged throw or 503 — already retryable.
  The control adapter just maps RunPod failures onto `:seon.ai/error` (`ai.cljs:102-110`).
- **The gym is hermetic + consumer-shaped (`driver.cljs:1585,701-775`)** — every run
  boots fresh agents on a scratch `:memory` conn; a diffusion experiment is new
  scenario EDN + new predicate kinds in `eval-predicate`; a throwing predicate scores
  RED (`:770`). A consumer drives it via `SEON_CONFIG` + `SEON_EXTRA_SRC`, no
  `src/seon` edits ([[research/gym-third-party-adoption-2026-06-28]]).

## The Flash deploy / FlashBoot source — `reference-code/flash`, `runpod-python`

Full read-throughs: [[research/flash-deployment-stability-2026-06-28]],
[[research/flash-warm-reuse-2026-06-28]], [[research/runpod-flash-grounding-2026-06-28]].

- **A code-only change does NOT recycle a warm worker** ✓. Flash injects a
  source-hash env (`_FLASH_SOURCE_FINGERPRINT`) so the deploy isn't a no-op, but an
  env change is a *rolling* change, NOT a version increment — only a version
  increment recreates workers. With `workers=(0,1), idle_timeout=600` a probe resets
  the idle clock so the warm worker never drains. This is the `worker_sha`-missing
  symptom.
- **`FLASH_GPU_IMAGE` is the force-fresh that PRESERVES the endpoint id** ✓.
  `imageName` IS a structural field (`serverless.py:1294`) → server-side version
  increment → worker recreation, id preserved. `flash undeploy --all && flash deploy`
  also works but CHANGES the endpoint id (`delete_endpoint(self.id)`).
- **`dependencies=[...]` is a BUILD-TIME `pip install --target`** ✓
  (`build.py:340-393`) — baked into the uploaded tarball, NOT a worker install; torch
  is force-stripped (`SIZE_PROHIBITIVE_PACKAGES`), so torch comes ONLY from the base
  image. (The base's stock torch 2.9.1 WORKS — the custom image is kept only for the
  Seon co-location latency play.)
- **FlashBoot is a platform-side toggle only** ✓. Both repos only toggle it
  (`flashBootType: FLASHBOOT`, `serverless.py:571`, `endpoints.py:26`) and treat an
  HTTP 400 on job-acquire as the "enabled" signal (`rp_job.py:150`); there is ZERO
  snapshot/restore code in `reference-code/`. Realistic large-model FlashBoot win is
  ~10-15s and decays with idle gaps — keep-warm is the dependable lever, FlashBoot a
  bonus on dense traffic.

## Idioms to internalize

- **Lower onto PUBLIC seams, never fork `generate()`** — `decoder_input_ids` +
  `ClampLogitsProcessor` + `past_key_values` + an external stopping criterion express
  every control loop without owning the decode code (maintenance death).
- **Stop external, keep N** — never shrink `max_denoising_steps` to "checkpoint".
- **Cheap argmax trace first** — entropy (`_takes_logits=True`) copies the full
  logits GB/step; prove `put_draft` fires on the tiny argmax code-buffer first.
- **Never trust wall-clock** — reconcile `len(streamer.steps)` with
  `out.tokens_per_forward`; "fast + empty" = a not-attached streamer or a swallowed
  exception, not the model.
- **Modes are rows, not functions** — one `run-mode!` engine; a new stage is a
  `:seon.dg.mode` row. A second hardcoded mode fn is the `foo-v2` anti-pattern.
- **The prompt is f(DB)** — dynamic-context section-fns are pure reactive queries,
  re-run every generation; no stored prompt, self-healing.
- **Predicates are mechanical + fail-RED** — a gym predicate reads the worker output
  or the post-run program graph; a throw scores RED, never a silent pass.
