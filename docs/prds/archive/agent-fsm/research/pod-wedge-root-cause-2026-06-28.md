---
type: research
status: draft
tags: [research, agent, flow]
---

# Pod wedge root-cause — the unbounded test-runner await

## TL;DR

The pod does NOT wedge on a bare never-resolving Promise from an agent form —
that path is already wall-clock-bounded (`seon.eval/eval` +
`maybe-await-value` race the form's value against a timer and record a
`:pending` placeholder). The pod wedges on the **auto-test-run**: after a
successful eval that (re)defines a `deftest`/tested fn, `eval-batch!` awaits
`seon.test.runner/run!` **with no timeout** (`src/seon/eval.cljs:2683`), and
`run-vars` → `drive-test-fn!` (`src/seon/test/runner.cljs:443`, `:334`) has no
per-test bound. A test body that never settles (a `^:async` body awaiting a
never-resolving Promise, or an `(async done …)` test that never calls `done`)
parks `run-vars` forever → parks the turn → parks that agent's `run-loop!`
forever. The agent's run never closes (stays `:open`); the agent is
permanently "running" and processes no further messages — the observed
"wedge." A second, related hazard: `run-vars` holds `cljs.test/*current-env*`
via `binding` **across `await`** boundaries; because CLJS `binding` mutates the
var's GLOBAL root, two overlapping `run-vars` (overlapping `cljs.test`)
corrupt each other's env/counters — the "overlapping cljs.test wedges the
shared async continuation" note in `CLAUDE.md`.

Fix (implemented, in `seon.test.runner`): bound every test/fixture drive with
a wall-clock timer (`SEON_TEST_TIMEOUT_MS`, default 15000). On overrun the
runner emits a timeout `:error` event and moves on, so `run-vars` ALWAYS
completes — the turn/run-loop is never parked, and the `binding` finally
ALWAYS restores `*current-env*` (shrinking the overlap-corruption window to
near-zero). Same no-preemption caveat as every seon timeout: the body keeps
running in the background; only the awaiter is freed.

## Grounding — read, not guessed

### What is "the shared async continuation"?

Two distinct shared resources, two distinct failure modes:

1. **The agent's `run-loop!` continuation** (`src/seon/agent/loop.cljs:178`).
   `run-loop!` is one `^:async` `loop`/`recur` per run; each iteration does
   `(await (turn/run-turn! …))`. `run-turn!` → `ask-and-eval-reply!`
   (`src/seon/agent/turn.cljs:277`) → `(await (seval/eval-batch! …))`. If
   anything inside `eval-batch!` awaits a Promise that never resolves, the
   whole chain back up to `run-loop!` is parked on that one pending await.
   This is "the agent loop's continuation parked forever on a pending await."

2. **`cljs.test/*current-env*`** — a process-global `^:dynamic` var
   (`reference-code/clojurescript/src/main/cljs/cljs/test.cljs:269`), mutated
   by `set!` in `update-current-env!`/`set-env!`/`clear-env!`
   (`:274`/`:278`/`:281`). `seon.test.runner/run-vars` runs the whole batch
   inside `(binding [t/*current-env* env] … (await …))`
   (`src/seon/test/runner.cljs:489`). In self-host CLJS, `binding` = save root
   → `set!` new root → try body → finally `set!` old root. Across an `await`
   the GLOBAL root stays mutated for the entire suspension. Two overlapping
   `run-vars` therefore clobber each other's env, counters, and the inner
   `finally` restores a stale value over a still-active run.

### Why a timeout does NOT prevent the wedge

`seon.eval/eval` (`src/seon/eval.cljs:926`) and `maybe-await-value` (`:1238`)
both wrap the awaited value in `race-timeout` (`:175`). So:

- A bare `(js/Promise. (fn [_ _]))` agent form → `raw-eval` resolves instantly
  with the Promise as `:value` → `maybe-await-value` races it, the timer wins
  → `{:ok false :pending <promise>}` → recorded as a `:seon.eval/pending`
  placeholder (`eval.cljs:2499`/`:2511`). **No wedge.** Confirmed by reading
  the path end-to-end (`eval-form-entry!`, `eval.cljs:2419`).

The timeout lives at the FORM-VALUE boundary only. The `(deftest …)` form's
value is just the var (resolves instantly), so the form-value timeout never
engages. The hang happens LATER, in the auto-test-run that `eval-batch!`
awaits AFTER recording the eval:

```
;; src/seon/eval.cljs:2674-2691  (auto-test-run, NO race-timeout)
(when-not outer-test-run?
  (let [targets (collect-auto-test-targets compile-state defs-before)]
    (when (seq targets)
      (try
        (await
          (db/with-tx-context {::db/origin :test-run}
            (fn ^:async run-auto-tests! []
              (await (test-runner/run!                 ; <-- UNBOUNDED await
                       {:seon.test.runner/vars (vec targets) …})))))
        (catch :default e …)))))
```

`test-runner/run!` → `run-vars` → `(await (drive-test-fn! sym fn))`
(`runner.cljs:510`). `drive-test-fn!` (`:334`) returns a Promise that resolves
only when the test body settles; for a never-resolving body it never resolves
→ `run-vars` parks → the auto-test-run await parks → the turn parks → the
run-loop parks. **No timer anywhere on this path.**

Distinguish the two residual kinds the brief asked about:

- **Sync infinite loop** (`(loop [] (recur))`, `(dotimes [_ 1e18] …)`): blocks
  the Node event loop entirely; even the `race-timeout` timer can't fire. NOT
  fixable in single-threaded Node — needs `worker_threads` (Phase 2) /
  `wasmtime` (Phase 3). Acknowledged at `eval.cljs:67-77`. Out of scope.
- **Parked-on-pending-await** (never-resolving Promise / `(async done…)` that
  never calls done): the event loop keeps spinning; only the agent's
  continuation is parked. THIS is the reported wedge and is fixable. Fixed
  here.

### Why overlapping evals / cljs.test wedge

`cljs.js/eval-str` compilation is synchronous, so two agent EVALS cannot
truly interleave during compile (single-threaded Node) — the compile-state is
not corrupted by ordinary overlapping evals. The "overlapping" hazard is
specifically **overlapping `run-vars`**: because `run-vars` `binding`s the
process-global `*current-env*` across its awaits (`runner.cljs:489`), a second
`run-vars` that starts while the first is suspended on a slow/never-settling
test corrupts the shared env. Once the PRIMARY bug (a parked run-vars) is
removed by bounding, no run stays parked, so overlap windows collapse to the
brief, bounded duration of a real test — the practical wedge disappears.

## The fix

`src/seon/test/runner.cljs` — bound every awaited test/fixture body:

- `env-test-timeout-ms` reads `SEON_TEST_TIMEOUT_MS` (default 15000) — generous
  vs. real async probes (25–50 ms) but a hard ceiling on a hang.
- `with-test-timeout` races a drive thunk against a timer; the returned
  Promise ALWAYS resolves. On overrun it fires `on-timeout` (a `do-report`
  `:error` event naming the sym) then resolves. Clears the timer on settle so
  fast tests leak no timer.
- `run-vars` wraps the `drive-test-fn!` and the `run-fixture-fn!` calls in
  `with-test-timeout`.

Consequences:

- `run-vars` ALWAYS completes → the auto-test-run await ALWAYS returns → the
  turn/run-loop is never parked. The agent's run closes normally; the pod
  keeps serving. (Mechanism 1 fixed at the source.)
- The `binding` finally ALWAYS runs → `*current-env*` is always restored
  promptly → overlap-corruption window collapses. (Mechanism 2 mitigated.)
- An agent's EXPLICIT `(seon.test.runner/run! …)` form is also covered: it
  returns a Promise that flows through `maybe-await-value` (already bounded)
  AND each inner test is now bounded too.

No preemption: the hung body keeps running in the background — identical to
every other seon timeout. The agent sees a clean `:error` test event ("timed
out after Nms"), not a frozen pod.

## What this does NOT fix (flagged residuals)

1. **Sync infinite loop** blocks the event loop — needs worker_threads /
   wasmtime. Pre-existing, acknowledged at `eval.cljs:67`.
2. **`binding`-over-`await` of `*current-env*`** is a latent footgun even
   bounded: two genuinely-overlapping finite test runs can still miscount.
   Proper fix = thread `env` explicitly through the reporter dispatch instead
   of `binding` a process-global across awaits (the `report` defmethods read
   `t/get-current-env`). Larger refactor; deferred.
3. **Unbounded `(await (llm-fn …))`** in `call-llm!` (`turn.cljs:304`) and
   unbounded DB awaits over the wire socket are the SAME parked-continuation
   class but on non-eval paths — out of this task's eval scope. Worth a
   follow-up: a turn-level watchdog so a hung LLM/DB call fails the turn
   loudly rather than parking the run-loop.

## Live proof

A unit test (`test/seon/test/runner_timeout_test.cljs` driving
`seon.test.runner-timeout-probes`) sets `SEON_TEST_TIMEOUT_MS` low, drives a
never-resolving `^:async` probe and an `(async done…)`-never-called probe
through `run-vars`, and asserts: (a) the run RESOLVES (does not hang) within
the bound, (b) the result carries a timeout `:error` event, and (c) two
overlapping runs (one hanging, one fast) both settle without mutual wedge.

Live-proven in the running pod (bounded, scratch — never wedged the live
default pod): `seon.test.runner/with-test-timeout` against a never-resolving
`(js/Promise. (fn [_ _]))` resolved to nil at exactly the 150ms bound with the
on-timeout callback fired. Full suite: **673 tests / 3083 assertions / 1
failure / 0 errors** — the new timeout tests pass; the lone failure is a
pre-existing render-lane drift (below), not in scope.

---

# Companion finding — fragile agent-home-ns refers (RC1 + RC2)

A second "eval/bootstrap robustness" defect, root-caused in the same pass:
`setup-agent-ns!` wired the agent home ns with two HACKS (a bare-`(ns)` prime +
a `(fn? complete)` probe) to work around a `:refer` that "fails." Grounded in a
scratch compile-state built from the real `init-bootstrap!`.

## RC2 — the `:refer` raises `:cljs/analysis-error`, not a "benign warning"

`setup-agent-ns!`'s docstring claimed the `:refer [wait complete …]` against
host-bundled `seon.agent.lifecycle` produces a "benign `:undeclared-var`
warning." FALSE. Live-proven: the `(ns <home> (:require … :refer […]))` form
returns `{:ok false :error {:seon.error/message "Could not parse ns form …"}}`
— a HARD parse abort. Cause (grounded): `cljs.analyzer/missing-use?`
(`reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc:2881`) treats
a refer'd sym as missing iff `(get-in cenv [::namespaces lib :defs sym])` is
absent; `check-uses` (`:2933`) then `throw`s `:undeclared-ns-form`. The toolkit
nses live on globalThis (`:client` bundle) but are NOT `:bootstrap` analyzer
entries, so a fresh compile-state has no `:defs` for them → the refer can't
parse. The prime + probe existed solely to survive that abort.

THE FIX (implemented, live-proven): `seed-toolkit-refers!` (new, in
`seon.eval`) declares each refer-toolkit ns's LIVE fn members (read via
`ns-fn-members` — code-as-data, no drift) as analyzer `:defs` in the
compile-state; called from `init-bootstrap!` so EVERY fresh/rebuilt state
carries it. With it, the single refer form parses `:ok true`, the clean emit
materializes the home ns's runtime object (so a later `(defn …)` works — no
prime), and `wait`/`complete`/`message/user`/`db/query`/`schema/register!`/`todo/add!`
all resolve. `setup-agent-ns!` is now ONE refer form that TRUSTS `:ok` (throws
loudly on failure); prime + probe deleted; docstring corrected. Verified with
the REAL shipped `init-bootstrap!` + `setup-agent-ns!`:
`{:setup-threw? false :verbs-resolve [true true true true true true]}`, and the
full agent-boot path is exercised green by the gym scenarios in the suite.

## RC1 — version-rotation drops the home-ns wiring (NOT fixed by RC2; flagged)

"Edits break agents" has a SECOND, independent cause that seeding toolkit defs
does NOT fix. On any `seon.eval` hot-edit, `init-version` rotates
(`eval.cljs` `(def init-version (gensym …))`), so the next
`seon.repl/ensure-bootstrap!` discards the warm compile-state and rebuilds a
FRESH one. `seon.client/rearm-wake-triggers!` (`client.cljs:1940`) then re-arms
every agent with that fresh state but **does NOT replay `setup-agent-ns!` or
`replay-program-graph!`** — so the agent's HOME ns (`my.agent.<id>`) loses its
`:refer`/alias wiring AND all agent-authored fns until the next full boot. The
agent's next `(wait …)` resolves against a bare home-ns entry → fails. This is
the user-visible "an eval.cljs edit breaks the live root agent."

RC2 does not address this: seeding TOOLKIT defs into the fresh state does not
re-wire a per-agent HOME ns (init-bootstrap! doesn't know the agent ids; the
home ns must be replayed per-agent).

PROPOSED FIX (NOT implemented — `seon.client` lane, needs live-reload
verification): `rearm-wake-triggers!` should, per agent, `await`
`seval/setup-agent-ns!` (now CLEAN thanks to RC2) and `replay-program-graph!`
before/with `install-wake-trigger!`, so a rebuild re-seeds the dropped per-agent
wiring. Trade-off to weigh: program-graph replay on every hot reload has a cost;
the alternative is decoupling the compile-state rebuild from `eval.cljs`'s
`init-version` (harder — the warning-dispatcher closure must stay in sync). This
is the remaining half of "edits break agents" and should be its own scoped unit.

# Flagged smells (documented per owner directive — not fixed)

- **`seon.render.value` test drift (render lane — DO NOT TOUCH):**
  `test/seon/render/value_test.cljs:153` asserts `(str/includes? out "chars⟩")`
  but the renderer now emits `⟨N tokens⟩`. The lone suite failure
  (`render-ai-long-string-reports-length`). A render-lane test/code drift; the
  UI/render owner should reconcile to `tokens` (or restore `chars`).
- **`seon.test.runner/last-run-id-from-db` uses `get-else`:** on the
  datahike-cljs track `get-else`'s default branch does not fire — rows missing
  `:seon.test/last-passed-at` OR `:seon.test/last-failed-at` are SILENTLY
  DROPPED, so a sym with only-passed or only-failed history is excluded from
  "latest run" resolution. Fix: query the two timestamps separately and
  max/merge in CLJS (same pattern `render.cljs` already uses to avoid
  `get-else`).
- **`run-ns!` `:or {record? true}` is NOT a smell (false-positive):** a
  reviewer flagged converting it to `(or record? true)`, but that would coerce
  an explicit `::record? false` to `true`. `:or` correctly applies only when
  the key is absent — left as-is.
- **Fixed in this pass (were `:cat`/`:any` convention violations):**
  `fetch-run` and `tests-referring-to` `:malli/schema` now use named-positional
  `:catn` (and `tests-referring-to` tightens `:any` → `[:or :string :symbol]`).
