---
type: prd
status: draft
tags: [prd, agent]
---

# Tile Isolation — stop a hung agent fn from freezing the pod

## TL;DR + recommendation

- A DeepSeek-backed agent wrote a non-terminating tile fn; it ran on the single
  JS thread and froze the entire pod (heartbeat, HTTP, SSE, every other agent)
  until a manual restart. A synchronous CPU loop CANNOT be interrupted in-thread:
  the existing eval timeout is `Promise.race` against `js/setTimeout`, and a
  blocked event loop never fires the timer (`src/seon/eval.cljs:115-125`,
  self-documented at `eval.cljs:829-831`). The tile render path has no timeout at
  all (`src/seon/render.cljs:166-182`).
- **RECOMMENDATION: a two-layer fix, shipped in that order.** Layer 1 (ship
  first) runs each tile fn under SCI with a wall-clock `:interrupt-fn` deadline.
  The load-bearing primitive — `:interrupt-fn` fires at the top of every
  interpreted recur iteration, so it aborts `(loop [] (recur))` in-process with an
  un-catchable, un-forgeable interrupt and no worker — is CLJS-proven
  (`reference-code/sci/src/sci/impl/fns.cljc:46-51`; CLJS-branch test with a
  COUNT-based limiter `reference-code/sci/test/sci/interrupt_fn_test.cljc:82-89`).
  The specific WALL-CLOCK `(js/Date.now)` deadline variant is structurally
  identical and doc-shown on the JVM
  (`reference-code/sci/doc/interrupt.md:26-34`), but the spike must confirm
  `js/Date.now` reads correctly inside the blocked SCI loop on Node (Open
  Question). Layer 2 (the hard backstop)
  adds a warm `worker_thread` pool that `terminate()`s any unit SCI cannot stop —
  a runaway host call or CLJS regex ReDoS, which SCI's own docs say only a
  killable process can bound (`reference-code/sci/doc/interrupt.md:84-94`).
- The single decisive reason SCI goes first: it kills the EXACT freeze that
  reproduced (a sync interpreted loop) with the smallest change and without the
  worker's blocker — a live datahike db value is NOT structured-cloneable, so it
  cannot cross a worker boundary intact (`reference-code/datahike/src/datahike/db.cljc:302`;
  Node worker_threads structured-clone limits, doc-sourced). SCI runs in-process
  and passes the db value by reference.
- This deliberately diverges from the only tile-lockup research on disk
  (`docs/prds/agent-runtime/research/tile-lockup-safety-2026-06-21.md`), which
  recommends an external watchdog + crash-marker + boot recovery and explicitly
  rejects worker isolation (§2, §4.5). That recovery design is reactive: every
  bad tile costs a ~30s full-pod outage for ALL agents and the human UI, and it
  never isolates the bad unit. This PRD instead isolates the bad fn in-process
  (Layer 1) with a killable backstop (Layer 2). The SCI-vs-watchdog-vs-worker
  trade-off is NOT yet captured in a research file — see Open Questions (the SCI
  investigation must be written to disk per the project's "research is a file"
  rule before this design is treated as research-settled).

## Problem statement

The pod is a single Node thread shared by the agent loop, the cljs.js eval
substrate, the HTTP+SSE server, and the heartbeat. A tile fn is wired by the
agent into `:seon.render.live-canvas/content` on its DB entity and invoked
synchronously on the shared thread.

- The synchronous call site: `html-render` resolves the wired symbol via
  `eval/lookup-value` and calls `(f input-map)` directly — no `await`
  (`src/seon/render.cljs:166-182`, `:175`). `render-agent-tile`
  (`src/seon/render.cljs:376-435`) wraps it in a try/catch, but try/catch only
  catches throws, never hangs.
- This fn fires on multiple synchronous entry points: the agent's context
  section every turn (`src/seon/ctx/live_tile.cljs:51`) and three inspector
  surfaces (`src/seon/web/inspector.cljs:193`, `:710`, `:871`) — the ctx site
  plus the three inspector sites = four synchronous call sites total (matching the
  appendix count). (The per-tx SSE
  broadcast re-render is described in the source as future work — `serve.cljs`
  notes the broadcast shell is "unfinished" — so "fires on every tx" is
  forward-looking, not current; the verified synchronous sites above are
  sufficient to freeze the pod.)
- Why try/catch + the async timeout cannot help: the eval timeout is
  `Promise.race` of the eval promise against `js/setTimeout`
  (`src/seon/eval.cljs:115-125`, used at `:821`). A tight CPU loop blocks the one
  event loop, so the `setTimeout` callback never runs and the race never
  resolves. The code documents this itself: "the underlying eval keeps running —
  JS has no preemptive cancellation" (`eval.cljs:115-125`) and "JS has no
  preemption — Phase 2 worker_thread or Phase 3 wasmtime needed for hard
  cancellation" (`eval.cljs:829-831`).
- Blast radius when the one thread blocks: the heartbeat `setInterval`
  (`src/seon/client.cljs:189-192`) stops, the HTTP/SSE server goes silent
  (`src/seon/web/serve.cljs`), every other agent freezes (shared thread +
  compile-state), and the only recovery today is `bin/seon restart pod` (~30s).

## The decision

**Ship SCI-bounded tile rendering first (Layer 1); add a worker kill-switch as
the hard backstop (Layer 2).** This is not "survey both" — it is a phased
commitment with SCI as the primary mechanism for the tile path and the worker
reserved for the residual class SCI provably cannot cover.

Why SCI beats the worker for the tile render specifically:

- It kills the proven freeze case in-process. The vendored SCI tests assert
  `(loop [] (recur))` and `(dotimes [_ 1000000] nil)` both throw under the CLJS
  branch — but via a COUNT-based limiter (`limit-interrupt!`, a counter atom at
  `reference-code/sci/test/sci/interrupt_fn_test.cljc:16-23`), NOT a wall-clock
  deadline (`:82-89`). What this proves verbatim in CLJS: the `:interrupt-fn`
  fires per recur iteration and aborts the loop. The mechanism: `loop` desugars
  to an interpreted `(fn* …)`
  (`reference-code/sci/src/sci/impl/analyzer.cljc:813-821`); `while`/`dotimes`
  expand to loop/recur (`reference-code/sci/src/sci/impl/namespaces.cljc:409-414`);
  and the generated fn body calls `:interrupt-fn` at the top of every host
  recur iteration (`reference-code/sci/src/sci/impl/fns.cljc:46-51`, `:70-75`,
  `:167-172`). Because ANY `:interrupt-fn` fires per loop entry, the wall-clock
  `(js/Date.now)` deadline variant we ship WILL trip and throw — but that exact
  variant is doc-shown only on the JVM (`System/currentTimeMillis`,
  `reference-code/sci/doc/interrupt.md:26-34`); the spike must confirm
  `js/Date.now` reads correctly inside the blocked loop on Node (Open Question).
  Do not let `:82-89` carry the wall-clock claim.
- The interrupt is un-catchable and un-forgeable by sandboxed agent code. It
  carries a private `(js/Object.)` identity marker
  (`reference-code/sci/src/sci/impl/utils.cljc:43-51`,
  `reference-code/sci/src/sci/interrupt.cljc:32-42`), is rethrown ahead of any
  user `catch` (`reference-code/sci/src/sci/impl/evaluator.cljc:81-82`), survives
  a throwing `finally` (`:148-166`), and a hostile `(try … (catch :default _ …))`
  cannot swallow or forge it (CLJS-branch tests:
  `reference-code/sci/test/sci/interrupt_fn_test.cljc:36-47`, `:64-71`).
- It sidesteps the worker's decisive blocker — the db value cannot cross a worker
  boundary. The tile contract is built on querying the live store synchronously
  (the core `welcome` fn calls `db/installed-schema`, `db/query`, `js/Date.`
  straight-line — `src/seon/render/live_tile.cljs` welcome body). A live datahike
  db value is a `defrecord-updatable DB`
  (`reference-code/datahike/src/datahike/db.cljc:302`) whose index fields are
  `BTSet`/`Branch`/`Leaf`/`Datom` deftypes carrying function-valued fields (the
  BTSet `comparator`, the `CachedStorage` `cost-center-fn` atom-wrapped
  `(fn [_] nil)` — `reference-code/datahike/src/datahike/index/persistent_set.cljc:324`,
  `:384`). Node structured clone throws on functions and strips class prototypes
  (doc-sourced, Node worker_threads), so the db value cannot cross intact. SCI
  runs in-process and passes it by reference.
- It is the smaller change. Agent fns today are compiled to JS on `globalThis`
  and resolved by `lookup-value` (`src/seon/eval.cljs:288-322`); the worker path
  forces `render-agent-tile` and ALL its synchronous callers to become async (a
  postMessage round-trip), rippling through the render path and ctx composer.

Why the worker is still needed as Layer 2 — the honest gap SCI cannot close:

- `:interrupt-fn` fires only on interpreted code. A single runaway host call
  bypasses it entirely — the docs show `(.pow (biginteger 10) 100000000)` hangs
  even WITH an interrupt-fn (`reference-code/sci/doc/interrupt.md:52`, `:84-94`).
- CLJS regex ReDoS is unguardable in-thread: the regex interrupt overrides are
  `#?(:clj …)` only, and SCI's own comment states "CLJS regex is the JS engine:
  no char-access hook … JS is single-threaded so a running match blocks the
  event loop and can't be interrupted in-thread"
  (`reference-code/sci/src/sci/interrupt.cljc:106-117`, `:119`).
- SCI's docs are explicit: "For hard guarantees it is best to run untrusted code
  in a separate process that can be killed"
  (`reference-code/sci/doc/interrupt.md:84-85`). Only `worker.terminate()`
  provides that: "Stop all JavaScript execution in the worker thread as soon as
  possible" (doc-sourced, Node worker_threads). Note: this terminate-kills-a-sync-
  loop claim is doc-sourced, NOT yet live-reproduced — see Open Questions.

## Detailed design

### Layer 1 — SCI-bounded tile render (primary, ship first)

> **STATUS: IMPLEMENTED + live-verified (2026-06-21).** Ships in `src/seon/render/sci.cljs`;
> `seon.render/render-agent-tile` routes AGENT-authored tile symbols through
> `seon.render.sci/invoke-bounded` behind env flag `SEON_TILE_SCI` (default on). Dev (`:none`)
> build compiles clean with `org.babashka/sci 0.13.53` on the `:cljs` classpath; the pod boots
> clean. Live proofs + a reproduce-it recipe are in [[sci-interrupt-validation-2026-06-21]]
> ("Test the feature"). Three refinements the implementation added beyond this section's original
> sketch, all to satisfy the standing requirement *"SCI may fail but must never crash the pod, and
> must never break a working tile"*:
>
> 1. **Pure safety net, never a correctness gate.** Only the wall-clock INTERRUPT triggers
>    recovery. ANY other SCI outcome (no stored source, an env-reconstruction gap, even a genuine
>    runtime throw) returns a `:seon.render.sci/fallthrough` marker → `render-agent-tile` renders
>    the tile on the proven COMPILED `html-render` path. `invoke-bounded` is outer-guarded so it
>    never throws. So bounding can only ever CATCH HANGS — it cannot turn a working tile into an
>    error or crash the pod.
> 2. **Lexical-environment reconstruction (so aliased tiles actually resolve).** The agent fn body
>    must be INTERPRETED, so its `:seon.fn/source` is eval'd into a fresh per-render SCI ctx. Real
>    tiles use ns-aliases (`db/query`), so the ctx rebuilds the env from the DB: `:as` aliases +
>    `:refer`s parsed from the ns's stored `:seon.ns/source`, and each required `seon.*`/agent ns
>    exposed as host vars by ENUMERATING its members from the `:seon.fn` index (code-as-data — the
>    core is indexed) and resolving each via `eval/lookup-value`. `(in-ns agent-ns)` +
>    `:ns-aliases` make both fully-qualified and aliased + own-ns-helper refs resolve. This is what
>    lets "write a new fn and wire it the same turn" and "wire an existing fn symbol" keep working;
>    a still-incomplete env just falls back to compiled (one-time warn).
> 3. **Calm human card + active agent notification on any problem.** The human NEVER sees a scary
>    error or a blank: a hang shows the `welcome` card (after reset), a throw shows a calm
>    `error-response` "Updating this panel" card, a not-yet-resolvable symbol shows the `pending`
>    card. The AGENT is told the truth out-of-band — a hang posts a deduped force'd message + resets
>    the tile to welcome; a throw posts a deduped force'd message and KEEPS the tile (so a fix takes
>    effect). The `:seon.render/ai` twin still carries the failure into the awareness section.

Scope: ONLY the tile render call goes through SCI. The existing cljs.js agent
REPL/corpus is untouched. This SCI-first / worker-backstop hybrid is justified
from the source citations below (not from a research file — no research file
recommends an SCI hybrid; see Open Questions for the missing research artifact).

Lifecycle:

- Add `org.babashka/sci` at the vendored `v0.13.53` (the version where
  `:interrupt-fn` exists) to the `:cljs` alias in `deps.edn`. The current
  `deps.edn:94` pin is `0.8.43`, a JVM-only dep that PREDATES `:interrupt-fn` and
  is NOT on the runtime path today (`src/seon/client.cljs:1189-1197` records the
  absence — that comment is the malli `:malli/schema`-parse incident, "the pod
  doesn't bundle [sci]", not a statement about eval, but it does establish sci is
  absent from the bundle). This is a measured bundle-size hit — see Open
  Questions.
- Build ONE process-shared SCI context at boot (alongside `ensure-bootstrap!` in
  `src/seon/repl.cljs`), configured with:
  - `:interrupt-fn` = a wall-clock deadline closure `(fn [] (when (> (js/Date.now)
    deadline) (sci.interrupt/interrupt!)))`, where `deadline` is reset per render
    to `(+ (js/Date.now) budget-ms)` (budget default 250ms — see Test plan).
  - `:classes {'js js/globalThis :allow :all}` to expose JS interop
    (`reference-code/sci/doc/async-await.md:88-93`).
  - `:namespaces` exposing exactly the core fns the tile contract permits —
    `seon.db/query`, `seon.db/pull`, `seon.db/installed-schema`, the hiccup
    helpers, `seon.render.chat/*` — as host vars
    (`reference-code/sci/doc/interrupt.md:67-82` shows the `:namespaces` pattern).
  - merge `sci.interrupt/clojure-core` overrides for the lazy-seq hazard class
    (`(doall (range))`, `(reduce + (range))` — proven interruptible in CLJS at
    `reference-code/sci/test/sci/interrupt_fn_test.cljc:116-134`).

Data boundary (in-process — no serialization needed): because SCI runs on the
same thread, the tile fn receives the live db value by reference, exactly as
today. There is NO data-boundary problem for Layer 1. This is the recommended
mechanism's central advantage over the worker. The "boundary" that exists is the
interop surface (which core vars the SCI ctx can see), not a serialization wire.

Integration point — `render-agent-tile` / `html-render`:

- The tile fn must be EVALUATED INTO the SCI ctx and invoked as an SCI var.
  Merely looking it up from the cljs.js corpus on `globalThis` gives zero
  protection — `:interrupt-fn` does nothing to a compiled JS fn
  (`reference-code/sci/src/sci/impl/fns.cljc` fires only on interpreted bodies).
- `html-render` (`src/seon/render.cljs:166-182`) is the single touch point: when
  the slot resolves to an agent tile symbol, instead of
  `(eval/lookup-value slot)` → `(f input-map)`, route through a new
  `seon.render.sci/invoke-bounded` that (a) resets the deadline, (b) evaluates
  the agent's tile fn source into the SCI ctx (or resolves it if already
  interned), (c) calls it with `input-map`, and (d) on `interrupt-ex?` returns
  the fallback (below). The core `welcome` and core section fns stay on the
  compiled path — only AGENT-authored tile fns get the SCI wrapper.

Terminate/interrupt-on-timeout: the SCI `:interrupt-fn` throws the un-catchable
interrupt when the wall-clock deadline trips; `invoke-bounded` catches
`interrupt-ex?` (the ONE exception type it is allowed to catch) and converts it
to the fallback. No worker, no thread, no setTimeout.

Fallback-to-welcome + agent warning:

- On interrupt (or any tile throw), render the known-good core
  `seon.render.live-canvas/welcome` instead — already the wired-content resolution
  floor (`src/seon/render.cljs:400-401`,
  `src/seon/render/live_tile.cljs` wired-content / welcome).
- Surface the failure to the agent via the existing legible `error-response`
  twin in `src/seon/render/live_tile.cljs` (the error-response pattern), naming
  the EXACT defect ("your tile fn ran past the Nms render budget — likely an
  unbounded loop; tiles must be rendered database queries, not computation") per
  the project's "specific, actionable feedback" rule. This is derived at render
  time (a function of the failure), NOT a stored attr — consistent with the
  reactive-context principle.

### Layer 2 — worker kill-switch (hard backstop, ship after Layer 1 proves out)

Scope: covers the residual class SCI cannot bound (runaway host calls, CLJS
ReDoS). Lower priority — only build if a host-call/ReDoS hang actually
reproduces after Layer 1.

Lifecycle:

- A dedicated small worker build target (a new `:node-script` `:main` that boots
  ONLY `ensure-bootstrap!` + a job loop, NOT the web server) — the existing
  `:replica-peer` build (`shadow-cljs.edn:138-156`) is the proven precedent for a
  Node-side datahike reader off the main thread.
- A WARM POOL of N pre-booted workers. Per-render spawn is a non-starter: a cold
  worker must re-init the full bootstrap corpus — `load-all-analysis-caches!`
  reads every `*.transit.json` under `<bootstrap>/ana/` plus the compiled JS
  (`src/seon/eval.cljs:157-176`). The exact corpus byte-size is UNVERIFIED — the
  Layer-2 spike must `du -h` the bootstrap dir and the out bundle and record the
  command + figures here. Claim a warm worker, postMessage the request, `await`
  with a wall-clock deadline; on deadline call `worker.terminate()` and spin a
  replacement into the pool (a terminated worker is dead and must be respawned).

Data boundary (the worker's hard problem — this is why it is Layer 2, not
Layer 1): the live db value CANNOT cross the boundary (proven REFUTED below). The
proposed mechanism: **the worker opens its OWN non-streaming lock-free datahike
`:file` reader on the same store** and is handed only
`{:seon.agent/id <string> :basis-t <int>}` per render.

- This is the pod's OWN read mechanism: `deref-conn` in non-streaming mode does a
  synchronous `(k/get store branch nil {:sync? true})` + `stored->db`
  (`reference-code/datahike/src/datahike/connector.cljc:69-78`), backed by genuine
  `fs.*Sync` ops in konserve's node filestore. `cluster-config`
  (`src/seon/store/wire.cljs:88-110`) with `:lock-blob? false` makes reads
  lock-free; the worker is a SECOND lock-free reader on the same immutable,
  content-addressed store. PROVEN as a topology (the `:replica-peer` build is
  shipped and green) — but PROVEN-with-a-caveat: the precedent is pod-vs-JVM, not
  pod-vs-pod-worker on the same dir. A two-reader smoke test for
  `:file-lock-acquisition-error` is required before committing Layer 2 (Open
  Question).
- The worker mounts NO writer and never calls `transact!` (tile fns are
  read-only by contract). Inputs (`id` string, `basis-t` int) and outputs (plain
  hiccup) are structured-clone-safe. The worker uses `(d/as-of @conn basis-t)` to
  match the main thread's exact turn snapshot.

Terminate-on-timeout: main thread `await`s the worker reply against a wall-clock
deadline; on deadline, `worker.terminate()` (doc-sourced: stops all JS execution
in the worker "as soon as possible"), fall the tile back to `welcome`, surface
`error-response`, respawn the worker.

PREREQUISITE SPIKE — gates Layer 2 entirely: `worker.terminate()` killing a
SYNCHRONOUS, non-yielding loop (e.g. `(while true)` compiled to a tight
machine-code loop with no allocation/back-edge interrupt point) is the
adversarial case. V8 can terminate JS at interrupt points, but a tight loop with
no back-edge check is exactly where that may not fire. This claim is DOC-SOURCED
ONLY, NOT live-reproduced (the verifier verdict is "unverifiable pending a live
repro" — see Open Questions). If the spike shows `terminate()` cannot kill a
non-yielding sync loop, **Layer 2 has no working primitive and falls back to the
external watchdog + crash-marker + boot recovery from the research file**
(`docs/prds/agent-runtime/research/tile-lockup-safety-2026-06-21.md`). Do not
build the worker pool until this spike passes.

Integration: Layer 2 makes `render-agent-tile` async at the agent-tile branch
only. The core/welcome path stays synchronous. This async ripple is the bulk of
Layer 2's cost and is why it is deferred behind Layer 1.

## Scope — phased rollout

- **Phase 1 (tile render, SCI):** bound ONLY agent-authored tile fns via SCI.
  Smallest blast radius, kills the reproduced freeze, no async ripple, no data
  boundary. This is the demo-critical fix.
- **Phase 2 (general eval, SCI):** extend the SCI-bounded path to the agent eval
  surface (`seon.eval/eval`) for interpreted loops. `eval.cljs` is ALREADY async,
  so this is a smaller retrofit than the tile was. Still does not cover host-call
  hangs.
- **Phase 3 (worker backstop):** add the warm worker pool + read-replica boundary
  ONLY for the residual host-call/ReDoS class, IF it reproduces. Reuses the
  `:replica-peer` topology.

Do NOT attempt all eval + worker in one change. Each phase is independently
shippable and reversible.

## Risks + mitigations

- **SCI gives no hard guarantee against host-call / ReDoS hangs (BLOCKER for
  "any hang").** Source-confirmed (`reference-code/sci/doc/interrupt.md:84-94`,
  `reference-code/sci/src/sci/interrupt.cljc:106-117`). Mitigation: Layer 2
  worker is the hard backstop; Phase 1's claim is scoped to interpreted
  loops/recursion (the reproduced case), not arbitrary host code. Do not market
  Phase 1 as covering everything.
- **SCI v0.13.53 is a one-day-old release (MAJOR).** The submodule is at
  `v0.13.53`, but its CHANGELOG dates it 2026-06-20 — the day before this PRD.
  `sci.interrupt/interrupt!` (the un-catchable interrupt this design depends on)
  is brand-new in that release; `:interrupt-fn` itself landed in 0.13.52. None of
  the `:interrupt-fn` + `sci.interrupt/clojure-core` overrides have a production
  CLJS-`:advanced` track record. Note the CHANGELOG also confirms the
  interrupt-aware regex overrides are JVM-only, reinforcing the CLJS-ReDoS gap
  (item below). Mitigation: migration step 1 must boot-smoke AND `:advanced`-
  compile the pod with sci added — the malli/sci interaction at
  `src/seon/client.cljs:1189-1197` (the `sci-not-available` incident) is exactly
  the advanced-compile hazard that bit before.
- **SCI interpreter overhead + bundle-size delta UNMEASURED (MAJOR).** No
  published CLJS benchmark exists in the vendored tree; the interrupt-fn + core
  overrides add per-fn-entry cost (`reference-code/sci/doc/interrupt.md:50`,
  `:54`). Mitigation: measure both against the live render path before commit
  (spike, below). If overhead blows the render budget, the worker becomes
  primary.
- **Interop wiring + agent-authored-ns case is the real implementation cost.** A
  tile fn that calls reconstituted AGENT namespaces (not just core) needs those
  nses mirrored into the SCI ctx. Clean for core-only tile fns; hard-edged
  otherwise. Mitigation: Phase 1 supports core-only tile fns first; flag
  agent-ns-calling tiles as a follow-up.
- **Worker terminate() teardown / UDS-leak semantics UNVERIFIED.** Whether a
  terminated worker leaks file handles or its konserve reader is unresearched.
  Mitigation: the read-only worker holds NO durable write handle; confirm clean
  teardown in the Layer 2 spike.
- **Two lock-free `:file` readers on the same machine/store (Layer 2).** Argued
  correct from `:lock-blob? false` (`src/seon/store/wire.cljs:96-99`) but the
  proven precedent is pod-vs-JVM. Mitigation: two-reader smoke test before Layer
  2.

## Empirical spike results (2026-06-21)

A standalone, isolated spike (its own `deps.edn` + `shadow-cljs.edn` under
`tmp/sci-spike/`, never touching the shared build, the `:client` build, or the
live pod) ran SCI `0.13.53` under `node` (`v24.2.0`) as a `:node-script`. The
spike was independently rebuilt and re-run by one of two adversarial reviewers
(clean compile, 100 files, 0 warnings; output reproduced within timing jitter)
and verified line-by-line against the vendored SCI source by the other. Both
reviewers found the spike TRUSTWORTHY for its decision-gating claims. What it
PROVED, with the raw outcomes:

- **Interrupt aborts a TRULY synchronous loop in-process — PROVEN.**
  `(sci/eval-string "(loop [] (recur))" {:interrupt-fn <wall-clock deadline>})`
  threw the SCI interrupt at **251ms measured** against a **250ms budget**, and
  the script CONTINUED (the process did not hang). The synchronicity is
  falsified-against, not assumed: a `setTimeout(0)` macrotask AND a
  `Promise.resolve().then` microtask were both armed BEFORE the eval and read
  AFTER — both stayed `false`, proving the loop never yielded the event loop.
  The `:interrupt-fn` is a plain wall-clock closure
  `(fn [] (when (> (js/Date.now) deadline) (interrupt/interrupt!)))` — so
  `js/Date.now` does read correctly inside the blocked SCI loop on Node, in the
  same synchronous call stack as `eval-string`, with no `await`/`setTimeout`/
  core.async anywhere. Bonus measured outcomes: `(dotimes [_ 1e9] nil)` is also
  caught (255ms) because it desugars to interpreted loop/recur; deep NON-tail
  recursion `(f 1e9)` is caught by the JS stack (`Maximum call stack size
  exceeded`) at 12ms — i.e. the deadline bounds unbounded ITERATION, the JS
  stack bounds unbounded non-tail DEPTH.
- **The interrupt is un-catchable by hostile sandbox code — PROVEN.** Both
  `(try (loop [] (recur)) (catch :default _ :swallowed))` and the variant with a
  throwing `(finally (throw (ex-info "finally-boom" {})))` returned
  `:swallowed? false` and propagated the interrupt past the sandbox catch at
  ~251-253ms — the interrupt wins over both a `:default` catch and a throwing
  finally. Matches `evaluator.cljc` (rethrow ahead of user catch; finally cannot
  mask) and the un-forgeable `(js/Object.)` marker exactly.
- **Per-render overhead fits comfortably under the tile budget — PROVEN.** SCI
  context creation is a one-time **median 0.039ms** (p99 0.168ms). Per-render
  eval reusing one ctx: **median 206µs, p99 703µs** (n=2000). Fresh-ctx-per-call:
  median 0.23ms, p99 0.80ms (n=1000). Native compiled baseline is ~10µs, so SCI
  is ~20x slower in relative terms — but the ABSOLUTE warm median ~0.2ms is
  **~1200x under a 250ms tile budget**. Both reviewers judged the measurement
  honest-to-conservative: ctx-creation is reported separately AND folded into a
  cold-path variant, the warm path is the realistic reused-ctx route, and the
  timed tile body does MORE work than a bare expression (so overhead is
  over-stated, not under-stated).
- **SCI cannot bound native/host CPU loops or native regex — PROVEN (the
  residual class is real).** With an `:interrupt-fn` that records whether it ever
  fired: a compiled-CLJS host busy-loop (exposed to the sandbox via
  `:namespaces`, invoked from interpreted code but spinning in a native
  loop/recur that never re-enters the interpreter) ran the full **1502ms** doing
  ~44M iterations with the **interrupt-fn never called** (`false`); a native
  `re-find #"^(a+)+$"` ReDoS ran for **32903ms (~33s)** with the interrupt-fn
  likewise never firing. This is direct confirmation of the SCI source note
  (`interrupt.cljc:106-117`): the interrupt-aware regex/char-access overrides are
  `#?(:clj …)`-only, so on CLJS a native match blocks the single JS thread
  uninterruptibly in-thread. The residual class that SCI CANNOT bound is exactly:
  **code that calls into native/host functions or native regex.**

What the spike did NOT establish (do not over-read the results):

- **`:advanced` optimizations were NOT exercised.** The spike ran under `:none`.
  Both reviewers rate divergence low-risk (the gen-fn interrupt check is ordinary
  CLJS and the `identical?` marker is compilation-robust) but flag it as
  empirically unverified.
- **The spike's own exception CLASSIFIER is loose, not the mechanism.** It labels
  the `(loop [] (recur))` case as an `ex-info` carrying the
  `:sci.impl/interrupt` marker, but labels the `(dotimes …)` and Test-2 cases as
  a generic `js/Error: Interrupted` — because `rethrow-with-location-of-node`
  re-wraps the propagated interrupt into a `:sci/error` ex-info whose TOP-LEVEL
  ex-data lacks the marker. Both reviewers call this a COSMETIC labeling artifact,
  NOT a soundness defect: all cases fired at the ~250ms budget (not at natural
  loop completion), the process continued, and un-catchability is independently
  proven by Test 2. The report's "ex-info w/ marker" phrasing is therefore
  slightly over-stated for the dotimes/Test-2 lines.
- **The perf numbers are a viability spike, not a load test.** A single
  representative tile body timed with `performance.now` warmup — adequate to
  decide viability, not a real pod-render workload under concurrency.
- **`worker.terminate()` on a non-yielding sync loop was NOT tested.** This spike
  covered Layer 1 (SCI) only; the Layer-2 terminate-kills-sync-loop claim remains
  doc-sourced and un-reproduced.

## Verdict: is Layer 2 (worker) needed?

**For the tile freeze the user actually cares about — a synchronous interpreted
`(loop [] (recur))` written by an agent — Layer 1 (SCI + a wall-clock
`:interrupt-fn`) is SUFFICIENT, source-grounded and spike-proven.** The exact
reproduced freeze class is defeated in-process: the loop aborts at the deadline
(251ms vs 250ms budget) with an un-catchable, un-forgeable interrupt, the event
loop is never yielded (falsification probe), and per-render overhead (~0.2ms) is
~1200x under budget. No worker, no thread, no `setTimeout` is required for this
class. SCI+wallclock IS good enough for it.

**But Layer 2 is NOT made unnecessary — it covers a DIFFERENT, empirically-real
residual class that Layer 1 provably cannot bound: code that calls into
native/host functions or native regex.** The spike measured this directly: a
host busy-loop ran 1.5s and a native ReDoS ran ~33s, in BOTH cases with the
`:interrupt-fn` never firing. On CLJS these block the single JS thread
uninterruptibly in-thread (the interrupt-aware overrides are JVM-only). For that
class, only a kill-able process/worker boundary can recover the pod.

Decision the user can make from this evidence:

- **Ship Layer 1 now; it closes the freeze that reproduced.** Yes, SCI+wallclock
  is good enough for the tile freeze the user cares about.
- **Deferring or skipping Layer 2 is a defensible, BOUNDED bet — with named
  residual risk.** If Layer 2 is skipped, the pod remains freezable by an agent
  tile fn that (a) calls a native/host function in a CPU loop that never
  re-enters the SCI interpreter, or (b) runs a catastrophic-backtracking native
  regex. Both still freeze the single thread and still require a full
  `bin/seon restart pod` (~30s, the current stopgap). Whether that residual is
  acceptable depends on how much the tile contract exposes host calls / regex to
  agent code: a tile contract that admits only interpreted DB-query + hiccup work
  (the stated intent — "tiles must be rendered database queries, not
  computation") leaves Layer 1 covering essentially the whole realistic surface,
  and Layer 2 becomes a backstop you build only IF a host-call/ReDoS hang
  actually reproduces in practice (the Phase-3 trigger already written into
  Scope). A tile contract that hands agents broad host interop keeps the residual
  live and argues for building Layer 2 sooner.

Caveat on the verdict: Layer 1's proof is under `:none` optimizations; the
`:advanced`-compile boot-smoke (Migration step 1) must still pass before Layer 1
ships, and the Layer-2 `worker.terminate()`-kills-a-sync-loop primitive remains
un-reproduced — so "Layer 2 is available as a backstop" is itself still gated on
its own spike (below), not yet proven.

## Open questions (require a spike before committing)

- **Write the missing research artifact.** The SCI-vs-watchdog-vs-worker
  investigation that justifies this PRD's SCI-first decision is NOT on disk — the
  only tile-lockup research file
  (`docs/prds/agent-runtime/research/tile-lockup-safety-2026-06-21.md`)
  recommends the watchdog + crash-marker recovery and rejects worker isolation,
  with no mention of SCI `:interrupt-fn`. Per the project's "research is a file"
  rule, the SCI investigation (with the interrupt-fn test findings + the datahike
  non-cloneable evidence) must be written to a research file that THIS PRD then
  cites, OR this PRD must stand on its source citations alone (as it now does).
  Until that file exists, treat the SCI decision as source-grounded-but-not-
  research-settled. (BLOCKER for "research-settled" status — not for the
  engineering, which is independently source-grounded.)
- **RESOLVED (2026-06-21 spike).** `js/Date.now` reads correctly inside the
  blocked SCI loop on Node. The exact wall-clock `:interrupt-fn`
  `(fn [] (when (> (js/Date.now) deadline) (interrupt/interrupt!)))` tripped and
  aborted `(loop [] (recur))` at 251ms vs a 250ms budget, in the same
  synchronous call stack as `eval-string`, with the event loop never yielded
  (falsification probe both `false`). The Layer-1 wall-clock design is no longer
  speculative. (Caveat: proven under `:none` optimizations only — `:advanced`
  still pending, see below.)
- **RESOLVED (2026-06-21 spike) for per-render overhead.** Warm per-call eval is
  median 206µs / p99 703µs (n=2000); ctx creation is a one-time ~0.039ms;
  fresh-ctx-per-call is median 0.23ms. The absolute warm median ~0.2ms is
  ~1200x under a 250ms tile budget, so SCI overhead does NOT block SCI being
  primary for the tile path. STILL OPEN within this question: the v0.13.53
  bundle-size delta was NOT measured (the spike was a standalone `:node-script`,
  not the pod bundle), and the perf number is a single-tile viability spike, not
  a load test against the real render path under concurrency. Measure the bundle
  delta + run the comparison on the live render path before commit.
- Live-reproduce `worker.terminate()` killing a `while(true){}` (non-yielding,
  tight machine-code) worker loop. The terminate-kills-sync-loop claim is
  doc-sourced only, NOT reproduced (verifier verdict: "unverifiable" pending a
  live repro). Gates Layer 2 ENTIRELY — if it fails, Layer 2 falls back to the
  watchdog recovery from the research file.
- `du -h` the bootstrap corpus + out bundle to record the real cold-worker
  re-init cost (the PRD's prior "2.1MB/11MB/1.3MB" figures were unsourced and
  have been removed). Informs the warm-pool sizing in Layer 2.
- Two-reader smoke test: pod + a pod-worker both opening lock-free `:file`
  readers on `data/clusters/default/store` — confirm no
  `:file-lock-acquisition-error`. Gates Layer 2's data boundary.
- Confirm the SCI interop surface for the agent-authored-ns case: can a tile fn
  that calls a reconstituted agent ns be evaluated into the SCI ctx without
  mirroring the whole corpus? (Scopes Phase 1's coverage.)
- **STILL OPEN — `:advanced`-compile the SCI interrupt path in the pod bundle.**
  The 2026-06-21 spike ran under `:none` optimizations; both adversarial
  reviewers rate `:advanced` divergence low-risk (the gen-fn interrupt check is
  ordinary CLJS and the `identical?` marker is compilation-robust) but flag it as
  empirically unverified. This is the same advanced-compile hazard that bit at
  `src/seon/client.cljs:1189-1197` (the malli/sci incident), so it must be
  boot-smoked AND `:advanced`-compiled before Layer 1 ships (Migration step 1).

## Migration plan + test plan

### Migration (each phase independently shippable + reversible)

1. Add `org.babashka/sci v0.13.53` to the `:cljs` alias; verify the pod still
   boots AND that an `:advanced` compile succeeds (the malli/sci interaction at
   `src/seon/client.cljs:1189-1197` is the known advanced-compile hazard). The
   bundle must build clean under advanced optimizations, not just dev. Reversible:
   revert the dep.
2. Build the shared SCI ctx + `invoke-bounded` behind a flag (default ON for
   agent tiles only). Reversible: flag off → falls back to the current
   compiled-fn call.
3. Wire `html-render`'s agent-tile branch to `invoke-bounded`; keep core/welcome
   on the compiled path.
4. (Phase 2) extend to `seon.eval/eval` for interpreted forms.
5. (Phase 3) add the worker pool + read-replica boundary behind its own flag,
   only if a host-call/ReDoS hang reproduces.

### Test plan

- **Deliberate-hang test (the load-bearing test).** Wire a tile whose content is
  `(loop [] (recur))`, set it on an agent entity, trigger a render
  (`render-agent-tile`), and assert: (a) the call returns within `budget-ms +
  slack`; (b) the result is the `welcome` fallback; (c) a short-cadence liveness
  canary fired — schedule a `js/setTimeout` ~50ms canary BEFORE triggering the
  hang and assert it ran after `invoke-bounded` returns (proving the event loop
  was never blocked). Do NOT assert on the heartbeat `setInterval`
  (`src/seon/client.cljs:189-192`) — it ticks at 60000ms (60s) and cannot fire
  inside a sub-second test window, so a heartbeat assertion is vacuous. (d) the
  HTTP/SSE surface still answers (issue a concurrent request and assert it
  resolves); (e) the agent's context section shows the legible error-response.
  Add a `(dotimes [_ 1e9] nil)` variant
  (`reference-code/sci/test/sci/interrupt_fn_test.cljc:82-89` proves the
  count-based abort; the wall-clock variant is the Open Question).
- **Un-catchable test.** Wire a tile that wraps the loop in
  `(try (loop [] (recur)) (catch :default _ :swallowed))` and assert it STILL
  falls back to welcome (the interrupt is not swallowed —
  `reference-code/sci/test/sci/interrupt_fn_test.cljc:36-47`).
- **Lazy-seq hazard test.** Wire `(doall (range))` / `(reduce + (range))` and
  assert interrupt fires (requires the `sci.interrupt/clojure-core` overrides —
  `reference-code/sci/test/sci/interrupt_fn_test.cljc:116-134`).
- **Host-call escape test (NEGATIVE, documents the gap).** Use a concrete CLJS
  host hang, not a JVM-only one: a catastrophic-backtracking regex such as
  `(re-matches #"^(.*a){20}$" (apply str (conj (vec (repeat 28 \a)) \!)))` (the
  payload the JVM regex-redos-test uses at
  `reference-code/sci/test/sci/interrupt_fn_test.cljc:135-145`). On CLJS this runs
  in the JS regex engine and is UNGUARDABLE in-thread — the interrupt-aware regex
  overrides are `#?(:clj …)` only (`reference-code/sci/src/sci/interrupt.cljc:106-117`,
  confirmed JVM-only by the v0.13.53 CHANGELOG). Assert that under Layer 1 it does
  NOT self-recover (proves the gap is real and motivates Layer 2); under Layer 2,
  assert `terminate()` recovers it (gated on the terminate-kills-sync-loop spike).
- **Perf/latency budget.** The 250ms default is PROVISIONAL and must be tied to a
  measured baseline, not asserted in a vacuum. First measure the CURRENT compiled
  `welcome`-class render (a few queries + hiccup) p99 over the live render path —
  call it `baseline`. Then set `budget = max(250ms, 4 × baseline)`. Then measure
  p50/p99 of the SCI-interpreted `invoke-bounded` on the same render. If the
  SCI-interpreted welcome render exceeds `budget`, SCI CANNOT be primary (per the
  MAJOR risk above) and the worker becomes primary. Assert the SCI path does not
  regress per-turn prompt assembly latency (`render-prompt` is on the turn hot
  path). Both `baseline` and the SCI overhead are UNMEASURED today — this is a
  spike, not a shipped number (Open Question).
- **Full `.cljs` suite** via `bin/test-cljs` once at the phase checkpoint (the
  batch checkpoint per CLAUDE.md test cadence), not per sub-step.

## Rejected alternatives

- **External watchdog + full pod restart (the current stopgap).** Reactive: every
  bad tile = a ~30s full-pod outage for ALL agents and the human UI. It does not
  isolate the bad unit, it kills the whole world. Superseded by this PRD.
- **Node `vm` module `timeout`.** Cannot hard-cancel: any code entering a
  Promise/async escapes the timeout (the same async-escape class as the existing
  `Promise.race` failure), and the pod render path is sync-on-the-main-thread,
  the exact case vm cannot bound. (The precise vm-doc wording was flagged
  "unverifiable / not fetched this run" by the verifiers — the directional
  conclusion holds because Seon's eval IS a Promise and vm does not abort a
  main-thread sync loop, but cite this as doc-sourced, not source-proven.)
- **SharedArrayBuffer + Atomics cooperative interrupt.** A flag only helps if the
  running code POLLS it; a non-yielding `(loop [] (recur))` never polls, so it
  never sees the flag. Useful only for signaling a cooperative worker, not for
  breaking a hang. Confirmed from first principles + SCI's own CLJS note
  (`reference-code/sci/src/sci/interrupt.cljc:106-117`).
- **structuredClone / postMessage the db value into a worker (REFUTED).** A
  datahike db value contains function-valued fields (BTSet `comparator`,
  `CachedStorage` `cost-center-fn` — `reference-code/datahike/src/datahike/index/persistent_set.cljc:324`,
  `:384`) so structured clone throws `DataCloneError`; and every deftype/defrecord
  loses its prototype (becomes an unusable plain object), so `d/q` against it
  fails. Doc-sourced (Node worker_threads structured-clone limits) + source.
- **nippy freeze/thaw the db value (REFUTED for the pod).** Nippy is JVM-only —
  zero `.cljs` usages; the pod wire codec is Transit/CBOR
  (`shadow-cljs.edn:107-108`). Nothing serializes a whole db value at any boundary
  today; the wire moves tx-data, not databases (`src/seon/store/wire.cljs`). A
  flushed BTSet serializes konserve ADDRESSES, not inline datoms — a "frozen db"
  is just a root pointer into konserve, which reduces to the read-replica path
  anyway.
- **Adopt SCI as the SOLE eval substrate (replace cljs.js).** Agent code today is
  compiled to JS on `globalThis` and resolved by `lookup-value`
  (`src/seon/eval.cljs:288-322`); SCI is a separate interpreter with a distinct
  var/namespace model. Replacing the whole substrate is a foundational rewrite of
  `seon.eval`/`repl`/the bootstrap compile-state — against the "fix in place /
  don't be a dumbass" rule. The hybrid (SCI for the tile render only) is the
  correct scope.
- **Constrain tiles to a declarative (data-only) form, no agent code in render.**
  Eliminates the hang class entirely and aligns with "tile updates should be
  rendered database queries" (`src/seon/render/live_tile.cljs`), but removes the
  expressive power the tile contract sells (an agent defining its own render fn).
  A product change, not a runtime fix. Listed as the ultimate fallback if both
  SCI and worker are rejected.

## Appendix — load-bearing citations

Seon source (the constraints a design must fit):

- `src/seon/render.cljs:166-182` — `html-render` calls `(f input-map)`
  synchronously; the tile call site.
- `src/seon/render.cljs:376-435` — `render-agent-tile`; try/catch catches throws,
  not hangs; `:400-401` welcome is the wired-content resolution floor.
- `src/seon/eval.cljs:115-125` — `race-timeout` (`Promise.race` vs `setTimeout`),
  "JS has no preemptive cancellation"; used at `:821`; `:74` `!timeout-ms`.
- `src/seon/eval.cljs:829-831` — self-documented: worker_thread/wasmtime needed
  for hard cancellation.
- `src/seon/eval.cljs:288-322` — `lookup-value` walks `globalThis`; agent fns are
  compiled, not interpreted.
- `src/seon/eval.cljs:157-176`, `:239` — `load-all-analysis-caches!` /
  `init-bootstrap!`; the worker cold-start cost.
- `src/seon/ctx/live_tile.cljs:51`, `src/seon/web/inspector.cljs:193`/`:710`/`:871`
  — the four synchronous tile call sites.
- `src/seon/client.cljs:189-192` — heartbeat `setInterval` (60000ms; freeze
  symptom — too slow to assert in a sub-second test); `:1189-1197` — the
  `sci-not-available` malli-schema-parse incident, "the pod doesn't bundle [sci]"
  (records sci's absence; about schema parsing, not eval).
- `src/seon/store/wire.cljs:88-110`, `:96-99` — `cluster-config`, `:lock-blob?
  false` lock-free reads; the read-replica boundary.
- `deps.edn:94` — `org.babashka/sci 0.8.43` (JVM-only, pre-`:interrupt-fn`).
- `shadow-cljs.edn:58-77` `:client` (`:node-script`); `:138-156` `:replica-peer`
  (the off-thread datahike reader precedent); `:107-108` CBOR wire codec.

SCI (the interrupt mechanism), all CLJS-confirmed:

- `reference-code/sci/src/sci/impl/fns.cljc:46-51`, `:70-75`, `:167-172` —
  `:interrupt-fn` fires at the top of every host recur iteration.
- `reference-code/sci/src/sci/impl/analyzer.cljc:813-821` — `loop` → interpreted
  `(fn* …)`.
- `reference-code/sci/src/sci/impl/namespaces.cljc:409-414` — `while` →
  loop/recur.
- `reference-code/sci/src/sci/impl/utils.cljc:43-51`,
  `reference-code/sci/src/sci/interrupt.cljc:32-42` — `(js/Object.)` interrupt
  marker; un-forgeable.
- `reference-code/sci/src/sci/impl/evaluator.cljc:81-82`, `:148-166` — interrupt
  rethrown ahead of user catch; finally cannot mask.
- `reference-code/sci/test/sci/interrupt_fn_test.cljc:16-23` (`limit-interrupt!`,
  the COUNT-based limiter the CLJS tests use), `:82-89` (loop/dotimes abort via
  that count limiter — NOT a wall-clock deadline), `:36-47` (uncatchable),
  `:64-71` (forge-resistant), `:116-134` (lazy-seq overrides), `:135-145`
  (regex-redos, JVM-only) — the count-based loop/uncatchable/forge/lazy-seq tests
  are CLJS-branch; the regex-redos test is `#?(:clj …)` only.
- `reference-code/sci/doc/interrupt.md:6-8`, `:26-34` (JVM time-limit example
  using `System/currentTimeMillis` — the wall-clock pattern, but not a CLJS test),
  `:52`, `:84-94` (host-call escape, "run untrusted code in a separate process
  that can be killed"); `reference-code/sci/src/sci/interrupt.cljc:106-117`,
  `:119` (CLJS regex unguardable); `reference-code/sci/CHANGELOG.md` 0.13.53
  (2026-06-20) — `sci.interrupt/interrupt!` brand-new, interrupt-aware regex
  JVM-only.

datahike (why the db value can't cross a worker boundary):

- `reference-code/datahike/src/datahike/db.cljc:302` — `defrecord-updatable DB`.
- `reference-code/datahike/src/datahike/index/persistent_set.cljc:324`, `:384` —
  `CachedStorage` defrecord, `cost-center-fn` = `(atom (fn [_] nil))`.
- `reference-code/datahike/src/datahike/connector.cljc:69-78` — non-streaming
  `deref-conn` synchronous `k/get` + `stored->db` (the read-replica mechanism).
- BTSet/Branch/Leaf/Datom deftypes (persistent-sorted-set + `datahike/datom.cljc`)
  — function-valued `comparator`, no plain-data form.

Node (doc-sourced, worker_threads): `worker.terminate()` "Stop all JavaScript
execution in the worker thread as soon as possible", returns `Promise<exitCode>`;
structured clone "an error is thrown if the object cannot be cloned (e.g. because
it contains functions)" and "instances of JavaScript classes will be cloned as
plain JavaScript objects" (prototypes not preserved). MARK these as doc-sourced;
the terminate-kills-a-sync-loop behavior is NOT yet live-reproduced (Open
Question).
