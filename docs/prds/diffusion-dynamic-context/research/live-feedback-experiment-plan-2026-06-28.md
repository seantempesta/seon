---
type: research
status: draft
tags: [research, agent, web]
---

# Live-feedback experiment plan — Capability #4 (live human feedback into the denoiser)

> Ready-to-run plan + worker-mode design for the most novel, most fork capability:
> a human (or an oracle) watches the 256-token canvas evolve LIVE over Seon's SSE
> feed, and BETWEEN denoise steps injects feedback — **accept** a region (lock it
> forever), **clamp** a region (hold it for the next steps), or **re-noise** a
> region (send it back to MASK to be re-decided) — which feeds the next
> `accept_canvas` step. Companion to [[index]] (the 4 capabilities + the
> `accept_canvas` seam), and a DIRECT reuse of
> [[eval-renoise-experiment-plan-2026-06-28]] (L — the **stateless-worker
> round-trip**, the **clamp/re-mask** primitives, the **char-span → canvas-position
> `offset_map`**) and
> [[retrieval-denoising-experiment-plan-2026-06-28]] (#3 — **Seon drives the loop**
> because the pod is loopback-only, the worker is a stateless denoiser). This plan
> does **not** re-derive L's V1–V3 or #3's round-trip — it adds the **human-in-the-
> loop SSE surface** and resolves the **one genuinely hard architectural tension**:
> Flash serverless is request/response, and "inject feedback between steps within
> one `generate()`" fights that model.

## TL;DR

- **The win:** a diffusion canvas is **revisable in place AND mid-flight**. Because
  commits are NOT frozen (the Transformer Lab paper measured ~7.5 re-mask
  events/gen) and `accept_canvas` is the per-step commit hook, a human can steer
  the canvas WHILE it denoises — accept the part that's right so it stops churning,
  re-noise the part that's wrong so it re-decides under the now-locked surroundings.
  AR has no such seam: a token, once emitted left-to-right, is past — you cannot
  hand a human the half-written tail to approve before the model continues.
- **The three feedback primitives are L's clamp/re-mask, plus one sticky flag:**
  - **accept(span)** — clamp the span's positions to their committed ids AND mark
    them **sticky-accepted**: they are never re-noised again, for the rest of the
    run. (= L's clamp + a persistent "done" set Seon carries across round-trips.)
  - **clamp(span)** — hold the span at its current ids for the NEXT steps only
    (L's clamp, non-sticky — a later feedback can still re-noise it).
  - **renoise(span)** — set the span's positions back to MASK and let the denoiser
    re-decide them, clamping everything else (L's re-mask + clamp, verbatim).
  The human's **char/visual span → canvas token positions** mapping is **P's
  `offset_map`** (`diffgemma_common.span_to_positions`), unchanged.
- **The Seon SSE side is REAL, shipping infra — grounded here exactly.** The pod
  already streams `view = f(db)` over a long-lived gzip-morph datastar feed
  (`seon.web.datastar`): every datahike commit re-renders the bound view and morphs
  `#world` on every open stream; `db/listen!` IS the refresh signal. Human input
  bars (chat, new-agent, time-travel) already POST form-mode to seeded
  `:seon.route/*` handlers **outside the morph**, parsed by
  `seon.web.serve/read-body` + `parse-urlencoded` behind the `same-origin?` gate.
  **Capability #4 needs NO new streaming mechanism** — it writes each denoise
  step's canvas as datoms (the feed morphs a new canvas tile), and takes feedback
  through one more outside-the-morph POST surface. Part 1 below is concrete against
  these exact fns.
- **The hard architectural tension (the highest-value output of this doc):** Flash
  serverless is **request/response** — a worker runs `generate()` to completion then
  returns. "Inject feedback BETWEEN steps within one `generate()`" needs a feedback
  channel INTO a running job, which **RunPod serverless does not provide** (its jobs
  are one-shot; you can stream OUTPUT out via a generator handler, but there is no
  documented way to push INPUT into a job already running). So:
  - **Route A — per-step (per-segment) round-trip (RECOMMENDED, deployable today).**
    Seon drives the denoise loop one `accept_canvas` step (or a short run of steps)
    at a time. Each request = advance the canvas; the worker returns the canvas +
    `offset_map` + entropy; Seon writes it as a datom (the SSE feed morphs it to the
    human); the human's accept/clamp/re-noise feedback comes back over a POST; Seon
    maps spans → positions and sends the next step's mask. **This reuses L/#3's
    Seon-drives + stateless-worker shape verbatim, at per-step granularity.** The
    cost is many round-trips; the analysis (below) shows the **human's reaction time
    dominates**, so the round-trip count is set by how often the human intervenes,
    not by 48 blind steps.
  - **Route B — streaming worker (the input half is the blocker).** The worker
    streams per-step canvases OUT (feasible — RunPod serverless supports a
    **generator handler + `/stream` poll**; the `accept_canvas` override yields each
    step's canvas) AND accepts feedback IN mid-`generate()` (the blocker — **no
    serverless input-into-a-running-job mechanism is known to exist**). So Route B's
    output-streaming half can make the **observation** cheaper (no 48 separate
    round-trips just to watch), but every actual feedback **injection** is still a
    job boundary (stop the stream, start a new round-trip). **The "any input into a
    running job?" question is THE key confirm-on-deploy unknown (X1).**
- **Recommendation: Route A is the deployable architecture.** Optionally fold in
  Route B's *output streaming* (X2 — confirmed-supported in classic RunPod
  serverless; confirm it holds for a Flash `@Endpoint`) as an **observation
  optimization** — the human watches the canvas evolve through a streamed run, and
  each time they intervene, Seon ends that run and issues a Route-A round-trip with
  the feedback mask. Mid-job *input* (true Route B) stays gated on X1, which is
  almost certainly a hard "no" for serverless.

## Why this is the most novel / most fork capability

Capabilities #1–#3 keep the human (or an oracle) OUTSIDE the generation: #1 clamps
typed tokens once; #2 evals AFTER a canvas lands; #3 retrieves AFTER a symbol
commits. #4 puts a human **inside the denoise loop**, reacting to the canvas as it
forms. That is only possible because diffusion exposes a per-step commit decision
(`accept_canvas`) over a whole revisable canvas — there is no autoregressive analog
(you cannot pause an L→R decode, show the human the not-yet-written suffix, and let
them lock the good prefix while re-rolling the bad middle). The "buzzsaw" thesis at
its sharpest: a human eye + the model's bidirectional re-decision, collared tight.

## Part 1 — the Seon SSE side (GROUNDED — real, shipping infra)

This half is **concrete** because the streaming + input machinery already ships in
the pod. The capability adds a canvas RENDER (a `view = f(db)` section), a few
datoms, and one POST handler — no new streaming mechanism.

### What already exists (file:line-grounded)

1. **The live feed — `seon.web.datastar`.** `view = f(db)`: ONE render fn produces
   the whole view; datastar's idiomorph diffs client-side, so re-pushing the whole
   element morphs only what changed. The stream is long-lived + gzip-compressed
   (`open-feed!`, `src/seon/web/datastar.cljs:423`); **every datahike commit**
   re-renders each open connection's bound `view-fn` and writes a
   `datastar-patch-elements` event (`broadcast!` / `push-conn!`, `:155`/`:143`).
   The refresh signal is **`db/listen!`** — `on-tx` → `schedule-broadcast!`
   (`:187`/`:174`), coalesced by a 50 ms trailing timer (a tx burst → one morph).
   **This is exactly what "stream the canvas evolving live" needs**: write each
   denoise step as datoms and the canvas tile morphs on every open feed, free.

2. **The per-agent world is the natural host.** `/agent/{id}` renders
   `world/world-layout` (`open-agent-feed!`, `:543`), a `view = f(db)` of that
   agent's canvas/tiles. A diffusion run belongs to an agent (or to `root`), so the
   canvas tile is one more tile in that layout — a new **section function**, the
   reactive-context default (CLAUDE.md: "new ways to surface data are new section
   functions, not new mechanisms").

3. **Human input bars live OUTSIDE the morph.** `chat-form-html`
   (`:275`) is a static `<form>` SIBLING of `<main id="world">` so the
   whole-`#world` morph never clobbers its focus/value; `data-on:submit` runs
   `@post('/chat?agent=<id>', {contentType:'form'})`, which posts the form fields
   `application/x-www-form-urlencoded`. `new-agent-bar-html` (`:374`) does an inline
   `fetch` POST and reads the response. **The feedback surface copies this pattern
   exactly** — an outside-the-morph control that POSTs a span + an action.

4. **POST plumbing — `seon.web.serve`.** `read-body` (`:209`) +
   `parse-urlencoded` (`:223`) parse a form POST into a map; `handle-chat!`
   (`:420`) is the worked example (query-param `agent` wins; writes into the agent's
   log via `message!`; 204 closes the datastar POST stream cleanly). All
   state-changing POSTs pass the **`same-origin?`** gate (`:565`) — the loopback UI
   carries Sec-Fetch / Origin headers it checks. A feedback POST reuses all of this.

5. **Routes are seeded `:seon.route/*` datoms** resolved late by symbol
   (`db->routes`; the handler registry is `seon.web.serve`'s map at `:603`,
   `:seon.web.router/chat → handle-chat!` etc.). A new `:seon.route/diffusion-
   feedback` row pointing at a `handle-feedback!` symbol is the same shape — **no
   Core change, overridable downstream** (the acme override-proof discipline).

6. **Time-travel is already `view = f(db-as-of t)`** (`time-travel-bar-html`,
   `:313`; `open-agent-feed!`'s `?t=` branch, `:543`). So **replaying a finished
   diffusion run step-by-step is free** — the canvas steps are datoms with a tx
   order; scrubbing the existing slider to a past tx re-renders the canvas at that
   step. The human can watch the denoise unfold AND scrub back through it with zero
   new UI mechanism.

### The canvas render + the feedback datoms (PROPOSED — not added to src/ by prep)

Reactive-context, derive-by-default: the canvas tile is a pure `f(db)` over
canvas-step datoms; the human's selection + action is the only stored input.

```clojure
;; PROPOSED schemas — grounded against the datastar feed + serve POST path; NOT
;; wired into src/ by this prep (added when Capability #4 is built).

;; --- a denoise step Seon records each round-trip (the thing the feed morphs) ---
(schema/register! :seon.diffusion.canvas/run        [:string {:seon.db/identity true}])
(schema/register! :seon.diffusion.canvas/step-index :int)
(schema/register! :seon.diffusion.canvas/tokens     [:vector :int])    ; ≤256 canvas ids
(schema/register! :seon.diffusion.canvas/text       :string)          ; joint-decoded canvas
(schema/register! :seon.diffusion.canvas/offset-map [:vector :any])    ; P's [pos cs ce] table
(schema/register! :seon.diffusion.canvas/entropy    [:vector :double]) ; per-position (J's read)
(schema/register! :seon.diffusion.canvas/masked     [:vector :int])    ; positions still MASK
(schema/register! :seon.diffusion.canvas/accepted   [:vector :int])    ; sticky-accepted positions
(schema/register! :seon.diffusion.canvas/done       :boolean)          ; denoiser converged

;; --- one human feedback event (the ONLY stored input; everything else derived) ---
(schema/register! :seon.diffusion.feedback/run    :seon.db/ref)        ; → the run entity
(schema/register! :seon.diffusion.feedback/action [:enum :accept :clamp :renoise])
(schema/register! :seon.diffusion.feedback/span   [:tuple :int :int])  ; [char-start char-end] in the canvas text
(schema/register! :seon.diffusion.feedback/at     :inst)
```

```clojure
;; PROPOSED render — a `view = f(db)` canvas tile (a new SECTION fn, the
;; reactive-context default). Reads the LATEST step for the run; renders each
;; canvas position colored by state so the human watches it evolve + can select a
;; span. NOT wired into src/ by this prep.
(defn canvas-tile
  "Render the latest denoise step of `run` as a span-selectable 256-cell canvas.
   Pure of external state (reads only `db`); never throws (the morph engine is
   crash-proof). Each cell carries its char range (from offset-map) so a
   client-side drag → [char-start char-end] → the feedback POST."
  [db run]
  (let [step (latest-step db run)]              ; one db/pull of the highest step-index
    [:div {:id (str "canvas-" run) :class "diffusion-canvas font-mono text-xs"}
     ;; … per-position cells, class by state: accepted (locked/green),
     ;;    committed (cream), clamped (amber), masked (dim) …
     ]))
```

Notes that keep this honest, not hand-wavy:

- **The human's region select is a UI affordance over the rendered canvas** (a
  click-drag across cells, or accept/clamp/re-noise buttons that act on the current
  selection). The selection resolves to a **char span `[start end]`** in the
  canvas `text` — exactly the unit P's `offset_map` consumes. The visual
  representation is a UI detail (the owner refines it, same as the time-travel
  slider note); the **data contract is a char span + an action**, which is all the
  loop needs.
- **The feedback POST is `chat-form-html`'s twin.** Outside the morph, form-mode
  `@post('/diffusion/feedback?run=<run>', {contentType:'form'})` with fields
  `action`, `start`, `end`; parsed by `parse-urlencoded`; gated by `same-origin?`;
  204 on accept. `handle-feedback!` transacts ONE
  `:seon.diffusion.feedback/*` entity. That's the whole input path — no new
  transport.
- **Nothing about the feedback is "stored state that must be cleared."** The next
  step's mask is DERIVED from the feedback datoms for the run at loop time (a
  `db/query`), the canvas tile is derived from the step datoms — both vanish when
  the run ends. This is the reactive-context rule applied: derive the mask, don't
  keep a mutable "pending feedback" registry.

## Part 2 — the GPU side + the architectural tension (the hard part)

### The per-step worker primitive (`step` mode) — L's clamp/re-mask, generalized

The worker stays a **stateless denoiser** (matches Flash scale-to-zero; #3/L's
choice). Seon holds the canvas + the accumulated `accepted` set between calls and
passes them back. The new primitive is **one `accept_canvas` step (or a short run
of K steps) per request**:

```
  step {canvas_tokens, accept_positions, clamp_positions, renoise_positions, max_steps}
     → renoise_positions := MASK
       clamp accept_positions ∪ clamp_positions to their ids every step
       advance ≤ max_steps denoise steps (max_steps=1 → strict per-step; larger → segment)
     ← {text, canvas_tokens, offset_map, entropy, masked, done}
```

- `accept_positions` / `clamp_positions` / `renoise_positions` are the **same
  `move_indices` / clamp mechanism L grounded in bd3lms** (`q_xt`
  `torch.where(move_indices, mask_index, x)`, ~L519; the `copy_flag` clamp in
  `_ddpm_caching_update`, ~L592). **accept vs clamp is purely a Seon-side
  distinction** (sticky vs one-shot) — the worker treats both as "hold these ids";
  Seon keeps `accepted` in the union it sends every call, so accepted positions are
  clamped on EVERY future step without the worker holding cross-request state.
- `max_steps=1` gives strict per-step round-trips (the human can intervene between
  every commit); `max_steps=K` runs a short autonomous segment then returns (fewer
  round-trips, the human intervenes between segments). **The segment size is the
  Route-A latency dial** (below).
- This is **L's `renoise` mode plus an accept set and a step budget** — the GPU
  code is L's verbatim; only the request fields grow. It shares L's V1 (re-seed a
  partially-good canvas), V2 (offset_map fidelity), and U3/U4 (accept_canvas
  contract, mask id) — **do not re-derive; introspect resolves them once.**

### The control seam (shared with #1/#2/#3)

`EntropyBoundSampler.accept_canvas(current_canvas, denoiser_canvas, logits,
cur_step)`, logits `[1, 256, 262144]`, open `transformers` 5.11.0, class
`DiffusionGemmaForBlockDiffusion`, model_type `diffusion_gemma`, no
`trust_remote_code`. For #4 the override (a) re-masks `renoise_positions`, (b)
clamps `accept_positions ∪ clamp_positions` to their incoming ids every step, and
(c) reads per-position entropy (J's read) so the canvas tile can show the human
WHERE the model is unsure. Same one surface, fourth use.

### THE architectural tension — Route A vs Route B (honest)

**Flash serverless is request/response.** A worker handler is invoked with an
input, runs to a return, and the result is fetched via `/status` (or yielded via
`/stream`). "Inject feedback BETWEEN steps within one `generate()`" needs a
**feedback channel INTO a job that is already running** — and that is the crux.

| | Route A — per-step round-trip (RECOMMENDED) | Route B — streaming worker |
|---|---|---|
| **Shape** | Seon drives; each request = 1 step (or K-step segment); worker returns the canvas; Seon morphs it + collects feedback + sends the next step | One `generate()` streams per-step canvases out; feedback is pushed IN mid-job |
| **Output streaming** | N HTTP responses (one per round-trip) | RunPod **generator handler + `/stream` poll** — `accept_canvas` override yields each step's canvas. **Feasible** (classic serverless supports it; confirm for Flash `@Endpoint` = **X2**) |
| **Feedback INPUT** | Trivial — it's the NEXT request's body. The whole point of Seon-drives. | **The blocker.** No known serverless mechanism pushes input into a running job (**X1**). Without it, "feedback between steps in one generate" is impossible. |
| **Reuse** | **L/#3 verbatim** — stateless worker, Seon-drives, offset_map, clamp/re-mask | New generator-handler path; new (nonexistent) input channel |
| **Cost** | Many round-trips; **but human reaction time dominates** (analysis below) | One job's GPU time; but can't take feedback without ending the job anyway |
| **Reachability** | Works — Seon→worker over HTTPS `/run` (the proven path); worker never calls back (pod is loopback `127.0.0.1:7890`) | The output stream is Seon→worker poll (fine); the INPUT channel is the impossible part |

**Why Route B's *input* half is (almost certainly) impossible on serverless, and
why that's the honest blocker.** RunPod serverless jobs are submitted to a queue,
run on a worker, and return a result; the documented surfaces are `/run`,
`/runsync`, `/status`, `/cancel`, and `/stream` (for generator OUTPUT). **There is
no documented "send a message to an in-flight job" API.** To take feedback you must
either (a) end the job and start a new one (= Route A), or (b) build a side channel
(the worker long-polls some external queue mid-`generate()` — which needs a
publicly reachable queue AND the worker holding GPU state across an interactive
human pause, fighting scale-to-zero). Both collapse back to "the round-trip is the
real boundary." **So Route A is not a compromise — it is the shape the platform
actually supports.**

**The latency analysis for Route A — why many round-trips is fine.** The naïve
fear is "≤48 steps × round-trip latency." But:

- **A human does not react to all 48 steps.** They watch the canvas form and
  intervene OCCASIONALLY (accept the signature once it's right; re-noise the body
  once). The number of *feedback* round-trips is set by **human interventions
  (a handful), not step count.**
- **Between interventions, run a SEGMENT, not a single step.** `max_steps=K`
  (e.g. 8) advances 8 denoise steps per request and streams the result; the human
  watches K-step jumps. Strict `max_steps=1` is available when the human wants to
  step frame-by-frame, but the default is segment-paced. This bounds round-trips to
  **≈ ⌈48/K⌉ observation jumps + a few feedback injections.**
- **On a WARM worker, a round-trip is HTTP RTT + a few forward passes** — the
  expensive part is the **cold start** (~50 GB load from the NetworkVolume), which
  `idle_timeout=600` amortizes across the whole interactive session (do the run
  inside one warm window, exactly like first-light §2). **X3** measures the warm
  per-segment latency to confirm it's interactive.
- **Human reaction time (seconds) ≫ a warm round-trip**, so even strict per-step is
  not human-perceptibly slow during an actual intervention; segment-paced
  observation hides the rest.

**The recommended deployable design = Route A, optionally with Route B's OUTPUT
streaming for cheaper observation.** Drive the loop from Seon (Route A); within a
segment, if X2 confirms Flash supports a generator handler, the worker can
`/stream` the intermediate steps so the human watches sub-segment evolution without
a round-trip per step — but **every feedback INJECTION is still a Route-A job
boundary** (end the stream, issue a new `step` request with the feedback mask),
because mid-job input (X1) is unavailable. This is the honest best of both: stream
to OBSERVE, round-trip to STEER.

### The full loop (mirrors L/#3, with the human in the collar)

```
            ┌──────────────────── Seon pod (datastar feed + SCI/oracles) ──────────────────────┐
            │                                                                                   │
  (1) generate_canvas {prompt}                                                                  │
   ─────────────────────────────►  GPU worker (A100, DiffusionGemma)                            │
            │                         denoise; record per-position entropy                      │
            │                       ◄─── {text, canvas_tokens, offset_map, entropy, masked}      │
            │                                                                                    │
            │  (2) Seon writes the step as :seon.diffusion.canvas/* datoms                       │
            │      → db/listen! → datastar broadcast → #world morph → HUMAN sees the canvas      │
            │                                                                                    │
            │  (3) human selects a span + an action (accept / clamp / renoise):                  │
            │      POST /diffusion/feedback?run=<run>  (form-mode, same-origin gated)            │
            │      → handle-feedback! transacts ONE :seon.diffusion.feedback/* entity            │
            │                                                                                    │
            │  (4) Seon's loop DERIVES the next mask from the feedback datoms:                   │
            │      accepted ∪= accept spans;  clamp = clamp spans;  renoise = renoise spans      │
            │      span → positions via P's offset_map (span_to_positions)                       │
            │                                                                                    │
  (5) step {canvas_tokens, accept_positions, clamp_positions, renoise_positions, max_steps}      │
   ─────────────────────────────►  GPU worker                                                   │
            │                         re-mask renoise; clamp accept∪clamp; advance ≤K steps      │
            │                       ◄─── {text, canvas_tokens, offset_map, entropy, masked, done} │
            │                                                                                    │
            │  (6) goto (2) until `done` AND no pending feedback (or a step cap)                 │
            └─────────────────────────────────────────────────────────────────────────────────┘
```

- **Transport is JSON over HTTPS** to RunPod `/run` + poll `/status` (or `/stream`
  for observation if X2), identical to the proven `gpu_worker.py` and to L/#3. **No
  tensors cross the wire** — `canvas_tokens` (≤256 ints), `offset_map`, `entropy`
  (256 floats), and the position lists are all tiny.
- **The human's browser never talks to RunPod.** Canvas steps flow worker → Seon
  (HTTP) → datom → Seon `/feed` SSE → browser; feedback flows browser → Seon POST →
  datom → Seon's loop → worker. The pod's loopback binding is never exposed; this is
  the same reachability reasoning #3 used to choose Seon-drives.
- **Reuse, not re-derivation:** the `offset_map` + clamp/re-mask are **L's**; the
  entropy read is **J's**; the datastar feed + form POST are **shipping pod infra**.
  The only genuinely new pieces are the `step` request fields (an accept set + a
  step budget) and the human SSE surface — both thin.

## Worker-mode design (`gpu_worker_feedback.py`)

Standalone `@Endpoint` `diffgemma-feedback` (same A100 / NetworkVolume / env as
`gpu_worker.py`; the proven generate path + J/L/#3's stubs untouched). Imports the
canonical `diffgemma_common.{resolve_mask_id, build_offset_map, span_to_positions}`
(C3/C4 resolved). Modes:

| mode | input | output | purpose |
|---|---|---|---|
| `env` | — | import/health info | cheap liveness |
| `introspect` | — | reuses the unified U1–U4 + V1–V3; adds the #4-specific X-probes (see below) | first-deploy oracle |
| `generate_canvas` | `{prompt, max_new_tokens}` | `{text, canvas_tokens, offset_map, entropy, masked}` | round-trip leg 1 (STUB on generate until U1–U3 confirmed) |
| `step` | `{canvas_tokens, accept_positions, clamp_positions, renoise_positions, max_steps}` | `{text, canvas_tokens, offset_map, entropy, masked, done}` | the per-step/per-segment primitive (STUB until V1 + U3 confirmed) |
| `stream` (optional, gated on X2) | `{canvas_tokens, …, max_steps}` | a generator yielding `{step_index, canvas_tokens, offset_map, entropy}` per step | Route-B OUTPUT streaming for cheaper observation |

`generate_canvas`/`step` build the layout + the `accept_canvas` override + the
entropy read and report `feedback_status: STUB` **without** issuing a guessed
generate — wired to the real sampler only after introspect resolves the seam, same
discipline as J/L/#3. The **offset_map + span→positions math ships LIVE** (pure
functions over the real tokenizer at introspect time).

## New unknowns — MUST CONFIRM ON FIRST DEPLOY

J resolves U1–U4; L resolves V1–V3; #3 resolves W1–W3. **#4 inherits all of those
verbatim** (the `step` primitive IS L's `renoise` + an accept set; it does NOT add
a GPU mechanism beyond L). The genuinely new unknowns are **platform**, not model —
and **X1 is the load-bearing one this whole doc is built around:**

- **X1 — input into a running job (THE key unknown).** Does RunPod/Flash serverless
  expose ANY mechanism to push input into a job that is mid-`generate()` (a control
  message, a re-readable input, a side queue the handler can poll)? **Expected: NO**
  — serverless jobs are one-shot (`/run`→`/status`/`/stream`/`/cancel`). If NO (the
  expected answer), **Route B's feedback-input half is impossible and Route A is the
  only architecture** — which is already the recommendation, so a "no" here
  *confirms* the plan rather than blocking it. Resolved by reading the RunPod
  serverless API surface + the vendored Flash SDK (`reference-code/runpod-python`,
  `runpod-flash`) — **answerable from the SDK source TODAY, no GPU needed.**
- **X2 — generator-handler OUTPUT streaming on a Flash `@Endpoint`.** Classic RunPod
  serverless supports a generator handler + `/stream` poll for incremental output.
  Does a Flash `@Endpoint` (the decorator path `gpu_worker.py` uses) support the
  same `yield`-based streaming, and what is the per-yield latency/granularity? This
  decides whether Route B's *observation* optimization is available. **Also
  answerable from the vendored SDK source** — confirm before relying on it; Route A
  works without it (N round-trips, segment-paced).
- **X3 — warm per-segment round-trip latency.** On a WARM A100 worker
  (`idle_timeout=600`), measure the wall-clock for one `step {max_steps=K}`
  round-trip (HTTP RTT EU-RO-1 + K forward passes). Confirms Route A is interactive
  (target: a few hundred ms to low seconds per segment, ≪ human reaction time). A
  GPU measurement, run in the first warm window alongside the unified introspect.

**Reused, not re-derived (do NOT re-probe):** U3 (`accept_canvas` return-vs-mutate),
U4 (mask id — `diffgemma_common.resolve_mask_id`, C4), V1 (re-seed a partially-good
canvas — the `step` primitive's seed), V2 (offset_map fidelity), V3 (in-place
re-denoise convergence with most positions clamped — **exactly** #4's accept/clamp
case, so V3's self-test already validates #4's clamp). The introspect that resolves
these is the unified one in [[first-light-runbook-2026-06-28]] §2.

## The concrete first test — a human steering one canvas mid-generation

The canonical interactive case: a human watches `(defn mean …)` denoise, **accepts**
the signature once it's right (locks it), and **re-noises** the body (watches it
re-decide under the locked signature).

```clojure
;; leg 1 (generate_canvas) — an early, partly-wrong canvas the human watches form:
(defn mean [xs] (/ (sum xs) (len xs)))     ; signature good; body uses non-idiomatic sum/len
```

1. **Seon writes the step → the canvas tile morphs** on the human's `/agent/{id}`
   feed (real infra: `db/listen!` → datastar broadcast). The human SEES the canvas,
   with per-position entropy shading (J's read) marking `sum`/`len` as the uncertain
   region.
2. **Human accepts the signature.** Drag-select `(defn mean [xs] (/ ` → click
   **accept** → POST `/diffusion/feedback?run=<run>` `{action:accept,
   start:S1,end:E1}`. Seon adds those positions to the sticky `accepted` set.
3. **Human re-noises the body.** Drag-select `(sum xs) (len xs)` → click
   **renoise** → POST `{action:renoise, start:S2,end:E2}`. Seon maps the span →
   canvas positions via `offset_map`.
4. **Next `step` request:** `accept_positions` = the signature, `renoise_positions`
   = the body, `clamp` = everything else. The worker re-masks the body, clamps the
   accepted signature (which can NEVER re-noise now), and re-denoises. Bidirectional
   attention re-decides the body seeing the LOCKED `(defn mean [xs] (/ …))`.
5. **Expected in-place result** (the body re-commits idiomatically under the locked
   signature):

```clojure
(defn mean [xs] (/ (reduce + xs) (count xs)))   ; body re-decided; signature byte-identical (clamp held)
```

6. **Seon writes the new step → the feed morphs → the human sees the fix.** Eval in
   the SCI cage (free, the #2 oracle) → clean: `(mean [1 2 3 4]) ;=> 5/2`. Loop
   terminates (no pending feedback, `done`).
7. **The decisive contrast:** the accepted signature is **byte-identical** across
   the round-trip (proves accept = a real lock the model could not override), and
   the body changed ONLY in the re-noised span (proves clamp held everywhere else).
   Run the same canvas with NO accept (re-noise the whole thing) to show the human's
   lock is what preserved the good signature — the delta IS the capability's value.

### Why this beats AR

An AR model emitting `(defn mean [xs] (/ (sum xs) ` cannot be handed to a human to
"approve the signature, re-roll the body" — the body is *not yet written* and the
signature is already *past*. Diffusion's whole-canvas + per-step `accept_canvas`
seam is what lets a human lock the right part and re-decide the wrong part WHILE the
model still has both in view. There is no autoregressive analog.

## Run order on first deploy

1. **Resolve X1 + X2 from the vendored SDK source — NO GPU, do it first.** Read the
   RunPod serverless API surface (`reference-code/runpod-python`) + the Flash
   `@Endpoint` path (`reference-code/runpod-flash`): confirm there is **no input
   channel into a running job** (X1 — locks Route A as the architecture) and whether
   a Flash `@Endpoint` supports a **generator handler + `/stream`** (X2 — whether the
   observation optimization is available). This is the highest-leverage step and
   needs no A100.
2. **Prove Part 1 (the SSE side) live — NO GPU.** Stub a fake run: transact a
   sequence of `:seon.diffusion.canvas/*` step datoms by hand and confirm the canvas
   tile morphs on an open `/agent/{id}` feed (verify the `/feed` **server-side** with
   the node gunzip client — the browser agent 503s long-lived SSE, per the
   browser-automation skill). POST a `/diffusion/feedback` form and confirm
   `handle-feedback!` transacts the feedback datom and the next derived mask is
   correct. **This whole half is verifiable today** — the feed + POST path are
   shipping infra.
3. In the first warm A100 window, run the **unified `introspect`**
   ([[first-light-runbook-2026-06-28]] §2) — it already resolves U1–U4 + V1–V3 that
   `step` reuses; confirm V3 (clamp self-test) carries over to #4's accept/clamp.
   Measure **X3** (warm per-segment round-trip latency) here.
4. Wire `generate_canvas` + `step` to the confirmed seam (V1 re-seed, U3 contract,
   U4 mask id) at the marked CONFIRM-ON-DEPLOY spots. If X2 confirmed, additionally
   wire the optional `stream` mode for observation.
5. **Run the first test** (above): `generate_canvas` → Seon morphs the canvas →
   human accepts the signature + re-noises the body → `step` → confirm the signature
   is byte-identical and the body re-decided. Eval → clean.
6. **Decision gate:** if accept does NOT hold a region byte-identical across the
   round-trip (the clamp leaks) OR re-noise does not re-decide a region under the
   locked surroundings, the in-loop-steering premise is broken at the mechanism
   level — reassess (this is V3 failing, caught earlier). If both hold, Capability
   #4 — live human steering of a denoiser — is proven end-to-end, and the diffusion
   prep suite is complete.

## Honesty / limits

- **Part 1 (the Seon SSE side) is real, shipping infra.** The gzip-morph
  `view = f(db)` feed, the `db/listen!` refresh signal, the outside-the-morph
  form-mode POST pattern, `read-body`/`parse-urlencoded`, the `same-origin?` gate,
  the seeded `:seon.route/*` rows, and time-travel-as-`f(db-as-of t)` ALL exist and
  run in production today (`seon.web.datastar`, `seon.web.serve`). The ONLY new Seon
  code is a `canvas-tile` render (a section fn), a `handle-feedback!` POST handler
  (a `handle-chat!` twin), the proposed datoms, and the loop glue — all grounded
  against exact existing fns above. **This half is verifiable now, with no GPU**
  (run order step 2).
- **X1 is the genuine unknown, and the honest answer is "Flash serverless almost
  certainly does not support feedback-into-a-running-job."** This doc does NOT
  invent a streaming-feedback API — it states the platform constraint plainly and
  recommends Route A precisely because Route A is what request/response serverless
  supports. A "no" on X1 confirms the recommendation; it is not a blocker. (If a
  later, non-serverless deploy — a dedicated A100 pod with a persistent process —
  becomes available, true Route B mid-job input becomes possible; that is out of
  scope here and explicitly not assumed.)
- **The GPU mechanism adds nothing beyond L.** `step` IS L's `renoise` (re-mask +
  clamp) with an accept set + a step budget; accept vs clamp is a Seon-side sticky
  distinction the stateless worker never sees. So #4 carries **zero new model
  unknowns** — it reuses U/V wholesale; the new unknowns (X1–X3) are all platform /
  latency, and X1/X2 are answerable from the vendored SDK source without a GPU.
- **The region-select UI is an affordance the owner refines** (like the time-travel
  slider — a minimal raw selection now; human-readable cells, drag handles, a diff
  later). The load-bearing contract is **char span + action**; the visual is a
  detail. No DiffusionGemma output has been produced yet (blocked on the custom
  torch image — see [[index]]).
- **The case is small, single-form, single human intervention** — chosen because it
  is exactly where "lock the good part, re-roll the bad part mid-flight" is
  unambiguously a thing AR cannot do. It is a clean proxy for live human steering,
  not a general benchmark.
- This plan is to EXECUTE fast once the GPU is live, not a result.

## X1/X2 resolved from SDK source (no GPU)

Read-the-source verdict, grounded in the vendored SDKs (`reference-code/runpod-python`,
`reference-code/flash`). X1 and X2 are both **fully answerable from the client/worker
SDK source** — they are not opaque server-side platform behaviors. The job lifecycle
is defined IN the SDK: the client wraps a fixed set of HTTP routes, and the worker's
own FastAPI app registers exactly those routes. There is nothing hidden behind them.

### X1 — input into a running job: UNSUPPORTED (confirms Route A)

The complete inbound-to-a-job surface in BOTH SDKs is `/run` (or `/runsync`). Once a
job is submitted, every remaining operation is read-only (`/status`, `/stream`) or
terminal (`/cancel`). There is **no `/update`, no `/send`, no re-readable input, no
message-into-a-running-job route anywhere in the SDK.**

- **runpod-python client** (`reference-code/runpod-python/runpod/endpoint/runner.py`):
  the entire `Job` + `Endpoint` API is `run` → POST `/run` (`:207`-`:224`), `run_sync`
  → POST `/runsync` (`:226`-`:250`), `status` → GET `/status/{id}` (`:130`-`:135` via
  `_fetch_job` `:119`-`:128`), `stream` → GET `/stream/{id}` (output only, `:156`-`:169`),
  `cancel` → POST `/cancel/{id}` (`:170`-`:181`), plus `health`/`purge_queue`
  (`:252`-`:275`). **No method posts data INTO an existing `job_id`** — `/cancel` is the
  only POST that targets a running job, and it only kills it.
- **runpod-python worker** (`reference-code/runpod-python/runpod/serverless/modules/rp_fastapi.py:236`-`:271`):
  the worker's FastAPI app registers ONLY `/run`, `/runsync`, `/stream/{job_id}`,
  `/status/{job_id}` (+ a docs redirect + an optional `/{id}/realtime`). The route table
  IS the inbound surface; there is no input route keyed by a live `job_id`.
- **`/realtime` is not an exception** (`rp_fastapi.py:290`-`:303`): `_realtime` takes a
  `Job`, runs `run_job(handler, …)` **to completion**, and returns the result. It is a
  low-latency *one-shot* (submit→run→return), not a channel into an in-flight
  `generate()`.
- **Flash QB `@Endpoint`** (`reference-code/flash/src/runpod_flash/endpoint.py`): the
  queue-based path `gpu_worker.py` uses exposes `run` → `/run` (`:865`-`:885`), `runsync`
  → `/runsync` (`:887`-`:897`), `cancel` → `/cancel/{id}` (`:899`-`:908`); the returned
  `EndpointJob` offers only `status`/`cancel`/`wait` (`:113`-`:128`). **No update /
  send-to-job surface** — Flash is strictly narrower than runpod-python here (it does not
  even wrap `/stream`; see X2).

**Verdict X1: UNSUPPORTED.** Serverless jobs are one-shot; the only inbound is a fresh
`/run`. This **confirms Route A (per-step round-trip via repeated `/run`) is the correct
architecture, not a compromise** — it is the only shape the platform's job lifecycle
permits. Every feedback injection MUST be a new `/run`; "feedback between steps within
one `generate()`" is impossible on the queue-based serverless path.

**The one honest caveat (does not change the verdict for the gpu_worker path):** Flash's
**load-balanced** mode (`LoadBalancerSlsResource`, `reference-code/flash/src/runpod_flash/core/resources/load_balancer_sls_resource.py:34`-`:38`)
advertises "REST APIs, **WebSocket servers**, Real-time streaming, Custom HTTP protocols"
— i.e. a direct always-on HTTP server, NOT the queue model. A WebSocket in LB mode is the
only SDK-visible path to a true bidirectional mid-job channel — but it abandons
scale-to-zero (`workersMin ≥ 1`, request-count scaling, `:54`-`:62`) and requires the
worker to hold GPU state across the interactive human pause. That is exactly the
"dedicated persistent process" deploy the doc already scopes OUT of serverless Route B.
So: on the queue-based `@Endpoint` path (the one `gpu_worker.py` and this whole plan
use), X1 is a hard **no**.

### X2 — generator-handler OUTPUT streaming: SUPPORTED (observation optimization is available)

The serverless worker fully supports a generator handler whose `yield`s are streamed out
and polled via `/stream/{job_id}`:

- **Detection** (`reference-code/runpod-python/runpod/serverless/modules/rp_handler.py:7`-`:9`):
  `is_generator` returns true for `inspect.isgeneratorfunction` OR
  `inspect.isasyncgenfunction` — a sync `yield` handler or an `async def … yield` handler
  both qualify.
- **Streaming pump** (`reference-code/runpod-python/runpod/serverless/modules/rp_job.py:307`-`:336`):
  `run_job_generator` iterates the handler and emits each partial as `{"output": …}` —
  one streamed chunk per `yield`.
- **The `/stream` route requires it** (`rp_fastapi.py:352`-`:372`): `_sim_stream` runs the
  generator and, if the handler is NOT a generator, returns `"Stream not supported, handler
  must be a generator."` So `/stream/{job_id}` ⇔ generator handler.
- **Client poll** (`runner.py:156`-`:169`): `Job.stream()` GET-polls `/stream/{id}` every
  1 s and yields each chunk's `output` until a terminal status — pull-based output streaming,
  ~1 s poll granularity.

**Flash caveat for X2:** Flash's QB `@Endpoint` client does **not** expose a `.stream()`
helper (only `run`/`runsync`/`status`/`cancel`/`wait`, `endpoint.py:113`-`:128`, `:865`-`:908`).
So to use generator streaming with the `gpu_worker.py` QB endpoint you either (a) poll
`/stream/{job_id}` via `runpod.Endpoint(id).run(...).stream()` from runpod-python directly
(the worker emits it regardless of which client reads), or (b) use Flash LB mode's native
real-time streaming. The output-streaming *capability* exists at the worker/platform level;
only Flash's QB *client wrapper* omits the convenience method.

**Verdict X2: SUPPORTED as an observation optimization layered on Route A.** A
`accept_canvas`-override handler can `yield` each denoise step's canvas; the human watches
sub-segment evolution over `/stream` without a round-trip per step. But — per X1 — every
actual feedback **injection** still ends that stream and issues a new `/run`. Stream to
OBSERVE, round-trip to STEER. Route A works fully without it (N segment-paced round-trips);
X2 just makes the watching cheaper.

### The deployable live-feedback architecture is

**Route A — Seon drives a stateless QB `@Endpoint` denoiser one `accept_canvas` segment per
`/run`, feedback injected as the next `/run`'s body — optionally streaming intermediate
canvases OUT via a generator handler + `/stream` for cheaper observation, but never taking
feedback IN mid-job (the SDK exposes no such route).** This is now grounded in the SDK
source, not "almost certainly": X1 is a confirmed hard no on the queue path (only `/run`
is inbound; `/status`/`/stream` read, `/cancel` kills), and X2 is a confirmed yes for output
streaming. No live GPU probe is needed to settle either — both are decided by the SDK's
fixed route table. (X3, warm per-segment latency, remains a genuine GPU measurement.)
