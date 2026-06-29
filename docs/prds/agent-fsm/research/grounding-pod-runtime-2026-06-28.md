---
type: research
status: active
tags: [research, agent, flow]
---

# Grounding the pod runtime / eval / async engine — read the source, not training-memory

A GROUNDING pass for the Core lane on the POD RUNTIME / EVAL / ASYNC engine:
the long-running Node process that self-host compiles + evals agent-authored
Clojure via `cljs.js`, runs the agent loop, and is **core.async-FREE** (native
CLJS `^:async`/`await`). Every load-bearing claim cites a `file:line` actually
opened in `reference-code/` AND in `src/seon/`. The companion doc
[[cljs-async-await-2026-06-28]] already settled the `^:async`/`await` self-host
verdict + the `async-fn?` wedge; this doc does NOT re-derive that — it grounds
the SURROUNDING engine (eval pipeline, retry, turn, SCI tile bounding, boot/
replay) and reports the SMELLS found while reading, including one place the code
GUESSES CLJS async semantics and gets them wrong.

## TL;DR

- **`await` is ONLY emitted by the explicit `await` macro and the async-IIFE
  wrapper — never implicitly for a test/condition.** Verified against the
  compiler: the only two `await` emission sites are `core.cljc:975-977` (the
  macro) and `compiler.cljc:705` (`iife-open`, wrapping an async body used in
  expression context). The analyzer has NO pass that injects `await` into an
  `if`/`or`/`cond` condition. → **SMELL #1:** `eval.cljs:2544-2551` documents the
  opposite ("a Promise in an `if`/`or`/`cond` TEST position is AUTO-AWAITED")
  to justify `(some? pending-promise)`. The code is harmless (a Promise-or-nil
  value tests identically with or without `some?`), but the *rationale is wrong
  CLJS semantics* and will mislead the next agent.
- **The pod is one Node thread; the "timeout" preempts only ASYNC hangs.** Both
  the eval `race-timeout` (`eval.cljs:169-179`) and the SCI tile deadline
  (`render/sci.cljs`) exist because a sync CPU loop blocks the event loop AND
  the timer. They are two DIFFERENT mechanisms for two DIFFERENT surfaces (agent
  eval vs. agent-authored tile fn); neither is a security boundary.
- **SCI is the pod's INTERRUPT seam, not a sandbox.** `seon.render.sci` runs an
  agent tile fn under SCI purely to get an interrupt-able interpreted loop
  (`render/sci.cljs:48-63`). SCI's own SECURITY.md frames it as a sandbox, but
  seon does NOT lean on that — isolation comes from the process boundary + the
  wire capability surface (CLAUDE.md "settled"). Training-memory will reach for
  SCI-as-security; here it is SCI-as-`:interrupt-fn`.
- **Don't reinvent — port the design to native async.** `seon.retry` ports the
  JVM `again` lib's pure strategy-as-seq combinators verbatim and rewrites ONLY
  the executor as `^:async` + errors-as-values (`retry.cljs:19-35,137-191`),
  because `Thread/sleep` + exception-catching don't exist in the pod. `call-llm!`
  is the SOLE LLM retry authority (`turn.cljs:330-357`). This is the
  core.async-free analog of the JVM flow `request!`/retry backbone.
- **Two `set!`-based capture paths, ONE migrated.** Warnings moved to
  `AsyncLocalStorage` precisely because a process-global `set!` cross-wires
  concurrent fibers (`eval.cljs:240-292`). `*print-fn*` capture still uses the
  exact abandoned `set!` pattern across `await` boundaries
  (`eval.cljs:2526-2566`) — **SMELL #2**, the same hazard, acknowledged in-code
  but not yet fixed the same way.

## Per-source: what it actually does → the training-memory mistake → the seon idiom

### ClojureScript self-host (`reference-code/clojurescript/`)

**What the source actually does.** `await` is a macro that *refuses to expand*
outside an async env (`core.cljc:975-977`, asserts `(:async &env)`) and emits raw
`(js* "(await ~{})" expr)`. The `:async` env flag is set in `parse 'fn*` from the
fn NAME's metadata (`analyzer.cljc:2336-2341`). The compiler emits the JS `async`
keyword in exactly two spots — `emit-fn-method`/`emit-variadic-fn-method`
(`compiler.cljc:945-959, 975-1069`) and `iife-open` (`compiler.cljc:704-708`,
which wraps an async body used in expression context as
`(await (async function(){…})())`). **Grep-confirmed: those, plus the `await`
macro, are the ONLY `await` emission sites.** There is no implicit-await analysis
pass — `analyzer.cljc:191` lists `"await"` only as a reserved word to avoid as a
munge target. Self-host (`cljs/js.cljs`) requires the SAME analyzer + compiler +
core macros, so all of this is live in the pod's bootstrap compile-state
(settled in [[cljs-async-await-2026-06-28]]).

**The training-memory mistake.** "I'm in an `^:async` fn, so the runtime will
`await` Promises for me where it needs them" — e.g. in a condition, or a bare
top-level `(await x)`. WRONG on both counts: a top-level `(await x)` throws at
macroexpand (no async env), and a Promise in a condition is just a truthy object,
never awaited. The compiler inserts NOTHING; only the explicit `(await …)` macro
inside an `^:async` body produces an await.

**The correct seon idiom.** Agents NEVER write `await`; the eval pipeline
auto-awaits a returned Promise via `maybe-await-value` (`eval.cljs:1282-1331`) so
quick `^:async` verbs read as synchronous. A still-pending Promise is carried to
`result/<id>` and resolved by RE-REFERENCE (a later bare `result/<id>` read,
which `maybe-await-value` then awaits) — never a top-level `(await result/<id>)`.
seon-internal code that must await does so explicitly inside an `^:async` fn
(e.g. `race-timeout` at `eval.cljs:169-179`, `sleep!` at `retry.cljs:137-141`).

### SCI (`reference-code/sci/` → `src/seon/render/sci.cljs`)

**What the source actually does.** SCI is an interpreter that calls a
caller-supplied `:interrupt-fn` at the top of every interpreted `fn`/`loop`
entry. seon uses ONLY that hook: `deadline-interrupt-fn` (`render/sci.cljs:134-140`)
throws an un-forgeable `interrupt/interrupt!` once a wall-clock deadline passes,
aborting a runaway interpreted loop IN-PROCESS on the single Node thread —
something `try/catch` and the `Promise.race` eval timeout CANNOT do (a blocked
event loop never fires the timer; `render/sci.cljs:14-19`). The agent tile fn's
own body is re-eval'd INTO the SCI ctx so it is interpreted (and thus bounded);
exposed core/agent fns still run COMPILED and fast (`render/sci.cljs:48-51`).
Only agent-authored, non-`seon.*` symbols get the wrapper
(`agent-authored-sym?`, `render/sci.cljs:93-106`).

**The training-memory mistake.** "SCI is the sandbox / security boundary for
agent code" — and "wrap everything in SCI." Both wrong for seon: (1) SCI's
SECURITY.md frames it as a sandbox, but seon's settled position is that the eval
surface is NOT a security boundary (it catches LLM hallucinations; isolation is
the process + wire surface). (2) SCI bounds ONLY interpreted bodies — a native
host loop or CLJS regex hidden in an exposed COMPILED helper still freezes the
pod (`render/sci.cljs:53-60`, residual Layer-2 class). Reaching for SCI to "make
agent eval safe" is the wrong layer; the eval path is `cljs.js` self-host, and
SCI is a NARROW interrupt guard for the synchronous tile-render path only.

**The correct seon idiom.** Use SCI where a sync, un-cancellable interpreted loop
can freeze the one thread (tile renders) — gated by `SEON_TILE_SCI`
(`render/sci.cljs:86-91`), warmed once at module load (`render/sci.cljs:148-153`).
Everywhere else, the async `race-timeout` is the bound, and it is honest that it
only stops ASYNC hangs (`eval.cljs:62-72` caveat).

### core.async / flow (`reference-code/core.async/` — JVM track, PAUSED)

**What the source actually does (and why it's not in the pod).** The JVM backbone
routes cross-boundary calls through `core.async.flow` step-fns +
`request!`/reply (CLAUDE.md "Flow Topology"). Its primitives — channels, parked
`go` blocks, `<!`/`>!`, `Thread/sleep` backoff — are JVM/threaded.

**The training-memory mistake.** Reaching for `core.async` / `go` / `<!` to model
concurrency or backoff in the pod. The pod is **core.async-free**; importing it
is the anti-pattern. A JVM-only lib that blocks on `Thread/sleep` or catches
thrown `Exception`s cannot run here.

**The correct seon idiom — port the DESIGN, native-async.** `seon.retry`
(`retry.cljs:19-35`) is the worked example: the `again` lib's PURE combinators
(`multiplicative-strategy`/`randomize-strategy`/`clamp-delay`/`max-retries`/
`max-duration`, `retry.cljs:59-131`) are ported faithfully (strategy = a lazy seq
of delays), and ONLY the executor is rewritten as `^:async` + errors-as-values:
`sleep!` is `(js/Promise. (fn [resolve] (js/setTimeout resolve ms)))`
(`retry.cljs:137-141`) and `with-retry!` is an `await`-in-`loop/recur`
(`retry.cljs:167-191`). `call-llm!` is the single consumer
(`turn.cljs:330-357`) — the SOLE LLM retry authority (adapters ship
`maxRetries 0`), with `llm-retryable?` (`turn.cljs:302-317`) deciding transient
(transport / 429 / 5xx) vs. fix-this. Defensive grounding worth copying:
`:seon.retry/strategy` is specced `[:fn sequential?]` (`retry.cljs:43`) so
instrumentation never realizes an infinite `(iterate …)` builder.

## The eval pipeline — the contracts that bite

- **`seon.eval/eval` does NOT auto-await; only the BATCH path does.** `eval`
  (`eval.cljs:970-1025`) returns the raw `:value` (a Promise if the form called
  an `^:async` fn). Auto-await lives in `maybe-await-value`, called from
  `eval-form-entry!` (`eval.cljs:2532-2561`). Callers of bare `eval` (e.g.
  `replay-program-graph!`, `client.cljs:838-839`; `setup-agent-ns!`,
  `eval.cljs:1259`) get raw values — fine there because those forms return
  vars/maps, not Promises. Don't assume `eval` resolves Promises.
- **`:analyze-deps? false` is load-bearing.** The bootstrap bundle holds only
  `cljs.core`; any form touching `seon.db/*` MUST analyze with deps OFF, then the
  emitted JS resolves at runtime via munged globalThis paths
  (`eval.cljs:982-996`). The resulting `:undeclared-var` warnings are filtered by
  `truly-undeclared?` (`eval.cljs:510-580`) — a real grounding read for anyone
  touching warning handling.
- **Bare value-defs don't cross `eval-str` calls.** `(def x 42)` then `x` → nil;
  fns cross fine; use atoms for state (`eval.cljs:26-34`). This is THE bootstrap
  gotcha agents trip on, and why `result/<id>` is a globalThis-backed var, not a
  plain def (`eval.cljs:785-824`).
- **`guarded-load` re-links the malli registry after EVERY load**
  (`eval.cljs:672-743`) because a self-host `(require …)` can goog.globalEval a
  bundle's macro JS that re-runs `set-default-registry!` and stomps every
  seon-registered schema. Live incident, grounded — do not "simplify" the
  relink away.
- **`ensure-analyzer-ns!` primes a real `(ns …)`** before a `def` into a
  never-set-up ns (`eval.cljs:745-783`), because `:def-emits-var`'s `var-ast`
  returns nil for an unknown current-ns → `(ana/ast? sym)` assert. Cites
  `analyzer.cljc:592`. This is grounded, not a guess — keep the real-`(ns)` prime
  (a hand-rolled `::namespaces` map is insufficient, PROVEN).
- **errors-are-values, end to end.** `eval` never throws/rejects
  (`eval.cljs:1024-1025`); `record-eval!` never silently loses the eval row, with
  a two-stage LOUD recovery + `:seon.eval/record-error` stamp
  (`eval.cljs:2179-2368`). The agent-facing error STRING is curated
  (`render-error-string`, `eval.cljs:2052-2087`) — don't dump raw EDN/stacks.

## SMELLS I found (cite, defect, fix, confidence)

### SMELL #1 — `eval.cljs:2544-2551`: wrong CLJS async rationale for `(some? pending-promise)`

```clojure
;; `(some? pending-promise)`, NOT a bare `pending-promise` test:
;; in a CLJS `^:async` fn, a Promise in an `if`/`or`/`cond` TEST
;; position is AUTO-AWAITED (the compiler emits `await` for the
;; condition). A bare `pending-promise` test would block on the
;; handle and resolve it — exactly what we must NOT do. ...
pending?        (some? pending-promise)
```

- **What's wrong.** The compiler does NOT emit `await` for a condition. Grep of
  `compiler.cljc` shows the only `await` emission sites are the `await` macro
  (`core.cljc:975-977`) and `iife-open` (`compiler.cljc:705`); the analyzer has
  no implicit-await pass (`analyzer.cljc:191` lists `"await"` only as a reserved
  munge word). A bare `pending-promise` in `(if pending-promise …)` compiles to a
  plain truthiness check on the Promise object — it is NEVER awaited, never
  blocks. Moreover `pending-promise` is Promise-or-nil, and a Promise is never
  `false`, so `(some? pending-promise)` and a bare `pending-promise` are
  **behaviorally identical here** — the `some?` guards nothing.
- **The fix.** Keep the code (harmless either way) but correct the comment to the
  truth: "`pending-promise` is a Promise-or-nil; `some?` reads as an explicit
  presence test. Promises are not auto-awaited in CLJS — only the explicit
  `(await …)` macro inside an `^:async` body awaits." Leaving the false rationale
  in place is the real damage: it is exactly the "guess library semantics from
  training-memory" trap, and it now reads as authoritative in-tree.
- **Confidence: HIGH** that the rationale is wrong (grep-confirmed emission sites;
  consistent with [[cljs-async-await-2026-06-28]] §A). MEDIUM that there is zero
  behavioral difference — recommend a 30-second REPL check
  `(let [p (js/Promise.resolve 1)] [(if p :t :f) (boolean p)])` against the live
  pod before editing, per "falsify, don't confirm."

### SMELL #2 — `eval.cljs:2526-2566`: `*print-fn*` capture uses the `set!` pattern that warnings ABANDONED for ALS

`eval-form-entry!` captures `(fix …)` print output by `(set! *print-fn* cap)` /
`(set! *print-err-fn* cap)` (`eval.cljs:2529-2531`), then runs `(await (eval …))`
and `(await (maybe-await-value …))` (`eval.cljs:2532-2536`) BEFORE restoring
(`eval.cljs:2566`). The in-code comment admits the hazard
(`eval.cljs:2521-2525`): "prints from other interleaved async work during this
form's awaits land here too."

- **What's wrong.** This is the SAME process-global-`set!`-across-`await`
  cross-wiring that the warnings path was migrated OFF of, on purpose, into
  `AsyncLocalStorage` (`warnings-als`, `eval.cljs:240-292`, citing D13: Node ALS
  survives Promise/await boundaries, CLJS `binding` does not). The warning
  dispatcher reads each fiber's bucket via `.getStore`; the print capture has no
  such per-fiber isolation. So concurrent agents' prints bleed into each other's
  `:seon.eval/output` exactly as warnings used to bleed.
- **The fix.** Route `*print-fn*` capture through the same `AsyncLocalStorage`
  shape: install ONE root `*print-fn*`/`*print-err-fn*` that appends to
  `(.getStore print-als)`, and wrap the eval span in `(.run print-als <atom> …)`
  — mirroring `install-warning-dispatcher!` + the `raw-eval` `(.run warnings-als …)`
  scope. One mechanism, in place (don't add a parallel capture ns).
- **Confidence: HIGH** that it is the same hazard class (it is acknowledged
  in-code and structurally identical to the pre-ALS warning bug). MEDIUM on
  priority — it only bites multi-agent concurrency, which is why it was left;
  worth a tracked task rather than an inline fix, since the warnings-als design
  is the proven template.

### Lower-confidence observations (flag, not assert)

- **`eval.cljs` `maybe-await-value` is `^:async ^:private` with NO
  `:malli/schema`** (`eval.cljs:1282`). Private, so not a convention violation,
  but it is a load-bearing async boundary returning a 3-shape union
  (`{:ok true :value}` / `{:ok false :pending}` / `{:ok false :error}`); a
  registered response schema would make the `:pending` contract explicit for the
  caller. LOW confidence this needs changing (private fns intentionally skip
  schemas here).
- **Loose `:map` arg schemas on the turn pipeline.** `open-turn!`
  (`turn.cljs:204`), `ask-and-eval!` (`turn.cljs:366`), `run-turn!`
  (`turn.cljs:408`) spec their request as bare `:map` (+ `body-fn :any`). The
  keys ARE all namespaced inside, so this is not the "bare-keyword" violation,
  but a named request schema (`::run-turn-request`) would catch a mis-shaped turn
  input at the instrument boundary instead of mid-flight. LOW confidence — these
  are internal map-passing fns and the values include runtime fns/db handles;
  acceptable, noting it for the #42-adjacent schema-tightening pass.
- **`async-fn?` mis-detection (P0 wedge) is real but already documented** in
  `instrument.cljc:307-313` and [[cljs-async-await-2026-06-28]] §C — surfaced
  here only so the Core lane treats "instrument once per process"
  (`instrument.cljc:365-376`) as load-bearing, not incidental: re-instrumenting a
  malli wrapper (ctor name `"Function"`, not `"AsyncFunction"`) routes a
  still-async fn through the SYNC output validator → `:malli.core/invalid-output`
  on the Promise → wedge. NOT a new finding; a standing hazard to respect.

## Sources opened

- `reference-code/clojurescript/src/main/clojure/cljs/core.cljc:975-977` —
  `await` macro (asserts `(:async &env)`).
- `reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc:191` —
  `"await"` reserved munge word; `:2336-2341` — `:async` flag in `parse 'fn*`.
- `reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc:704-708`
  (`iife-open` — the only implicit `await`), `:945-959`, `:975-1069` — async
  `function` emission.
- `reference-code/sci/SECURITY.md` — SCI's own sandbox framing (NOT what seon
  relies on); `reference-code/sci/README.md` — interpreter model.
- `reference-code/core.async/doc/{flow,flow-guide,rationale}.md` — the JVM flow
  backbone the pod deliberately does NOT use.
- `src/seon/eval.cljs` — `race-timeout` `:169-179`; `budget`/`defer`
  `:90-152`; bootstrap `init-bootstrap!` `:298-341`; `warnings-als` + dispatcher
  `:240-292`; `truly-undeclared?` `:510-580`; `guarded-load` `:672-743`;
  `ensure-analyzer-ns!` `:745-783`; `raw-eval` `:876-968`; `eval` `:970-1025`;
  `maybe-await-value` `:1282-1331`; `home-ns-form`/`setup-agent-ns!`
  `:1204-1268`; `eval-form-entry!` (print capture + `pending?`) `:2463-2566`;
  `record-eval!` `:2179-2368`.
- `src/seon/retry.cljs` — full (combinators `:59-131`; `sleep!`/`with-retry!`
  `:137-191`).
- `src/seon/agent/turn.cljs` — `call-llm!` `:330-357`; `llm-retryable?`
  `:302-317`; `llm-retry-strategy` `:319-328`; `prefetch-and-render-prompt!`
  `:148-177`; `open-turn!`/`close-turn!` `:189-260`.
- `src/seon/client.cljs` — `current-llm-fn` `:1898-1931`; `rearm-wake-triggers!`
  `:1936-1996`; `replay-program-graph!` `:787-870`; `guarded-load` consumer /
  `core-ns-set` `:1032-1061`.
- `src/seon/render/sci.cljs` — interrupt model `:14-63`; `agent-authored-sym?`
  `:93-106`; `deadline-interrupt-fn` `:134-140`; warmup `:148-153`.

Verified during this pass: the `await`-emission grep over `compiler.cljc`
(only the macro + `iife-open`), grounding SMELL #1. The behavioral claim in
SMELL #1 (no difference between `some?` and bare test) should be REPL-falsified
before any edit — research only; no `src/` was changed.
