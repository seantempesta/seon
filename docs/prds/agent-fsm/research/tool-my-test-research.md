---
type: research
status: draft
tags: [research, agent]
---

# Tool research — `my.test`: a REPL test loop + dual FAIL_TO_PASS/PASS_TO_PASS check

DESIGN ONLY. This note recommends the implementation strategy and the
agent-facing API for the `my.test` toolkit verb. It edits no source.

## TL;DR

- **Recommendation: `thin-wrap-existing-seon`.** A lean editable `my.test`
  facade (`check` + `check-edit`) over the already-built, well-shaped
  protected engine `seon.test.runner`. This is exactly the catalog's Q2
  decision; this note grounds it and pins the in/out shapes.
- **There is no library to wrap.** In a SELF-HOSTED CLJS-on-Node pod (no JVM,
  no build step at runtime), every "ClojureScript test runner" (kaocha-cljs,
  cljs-test-runner, shadow `:node-test`, eftest) is a JVM-driven, build-time
  tool — none runs in-process in self-host CLJS. The only runtime test
  framework available is **`cljs.test` itself**, and `seon.test.runner` already
  captures its `report` multimethod as DATA via a per-call `:reporter` slot
  (the correct, and only viable, approach). `wrap-lib` is off the table.
- **kaocha is API-design inspiration, not a dependency** (it's JVM-only). Its
  data result shape (`:kaocha.result/{count,pass,fail,error,pending}` +
  per-testable `:kaocha.testable/{id,type}` + events) is the precedent for the
  namespaced summary. Crucially kaocha ships `totals->clojure-test-summary` —
  it NAMESPACES its summary and translates to/from cljs.test's bare
  `{:test :pass :fail :error}` at the boundary. That is exactly the
  smell-fix `my.test` should perform: namespace the public summary, translate
  cljs.test's bare counters at the facade.
- **`check-edit` is a small build-fresh orchestration** over the same engine:
  two `run-vars` calls (must-now-pass + must-still-pass) and the SWE-bench
  predicate (`resolved? = all f2p pass AND all p2p pass`). It is a POST-EDIT
  predicate, not a before/after differ — the agent's redefinition is already
  live, so the set labels ARE the spec. This matches the gym philosophy
  (assert the post-condition mechanically, never on output strings).
- **Net: HYBRID, leaning thin-wrap** — `check` is a pure thin wrap; `check-edit`
  is ~15 lines of new orchestration on the same floor. No new engine, no npm dep.

## What already exists (the floor)

`src/seon/test/runner.cljs` (`seon.test.runner`, ~9.5k tok, the protected
engine). It is genuinely good and does the hard parts:

- **cljs.test capture as DATA, not stdout.** A per-call `(empty-env ::capture)`
  with per-event `defmethod`s appending to a fn-local volatile builder; no
  mutation of the global `cljs.test/report` root. Returns `{::events ::summary}`.
- **Async-correct.** `run-vars` is `^:async`; it awaits both `(async done …)`
  IAsyncTest CPS bodies AND `^:async` Promise-returning test bodies before
  emitting the summary (`drive-test-fn!`).
- **Fixtures.** Replicates the cljs.test `:once`/`:each` before/after walk
  (it can't route through `cljs.test/test-vars` — self-host can't synthesize
  real `Var` instances, so it uses a synthetic `#js {:sym sym}` stand-in).
- **Two authoring forms resolved** (`resolve-test-fn`): `(deftest foo …)`
  (thunk under `cljs$lang$var` → meta `:test`) AND `(defn f {:test (fn [] …)} …)`
  (thunk on `cljs$lang$test`). It also UNWRAPS malli instrumentation
  (`malli$instrument$original`) before reading the thunk — without this, a
  `:malli/schema`-bearing example fn runs its instrumented impl at arity 0 and
  throws `:malli.core/invalid-arity`.
- **Selectors + entrypoints:** `run!` (`::vars` xor `::ns`), `run-ns!`,
  `vars-in-ns`, `run-and-record!`, `last-result`, `tests-referring-to`
  (substring scan of `:seon.test/source` — the v0 heuristic the
  auto-test-on-fn-redef path in `eval.cljs` ~L2657 already uses).
- **Three-tier storage already correct:** full event sequence stashed on
  `globalThis` keyed by a `run-id`; the DB carries only the minimal projection
  (`:seon.test/last-{passed,failed}-at`, `…/last-failure-summary`,
  `…/last-run-id`). The agent fetches the blob via `last-result`/`fetch-run`.

**The gap** (verbatim from `toolkit-catalog.md` §my.test, Q2) is purely the
agent-facing surface: a one-call `check` returning the lean three-key value,
and a `check-edit` dual-set scorer. The engine is a floor (too heavy to render
in context every turn); `my.test` is the lean editable wrapper the agent sees.

## Options compared

| Option | Verdict | Why |
|---|---|---|
| **wrap-lib** (an npm/CLJS test lib) | **NO** | No CLJS-on-Node self-host lib returns Clojure-test-as-data at runtime. kaocha/kaocha-cljs/cljs-test-runner/shadow `:node-test`/eftest are JVM-driven build-time tools. cljs.test is the only runtime framework, and the runner already wraps it. A subprocess `bin/test-cljs` shells a *fresh* `:node-test` JVM (~160s) — wrong tool for an in-loop verify, and CLAUDE.md forbids overlapping `run-tests` in the live pod. |
| **build-fresh** (new runner) | **NO** | The "don't be a dumbass" rule: `seon.test.runner` already solves capture-as-data, async, fixtures, dual-authoring-form resolution, malli-unwrap, and the stash/projection split. Re-deriving any of that is pure regression risk. |
| **thin-wrap-existing-seon** | **YES (core)** | `check` is a lean projection of `run!`/`run-vars`'s `{::events ::summary}` into `{pass? summary failures}`. Editable `my.*` wrapper over the `:core-seed` engine — the two-tier model. |
| **hybrid** | **YES (net)** | `check` = thin-wrap; `check-edit` = ~15 lines of NEW orchestration (two `run-vars` calls + the SWE-bench predicate) over the SAME engine. New code, no new engine. |

### kaocha as the design oracle (vendored, JVM-only)

From `reference-code/kaocha/src/kaocha/result.clj` — its `totals` produces a
summary keyed `:kaocha.result/{count,pass,fail,error,pending,time}`, with
per-testable result maps under `:kaocha.result/tests` carrying
`:kaocha.testable/{id,type,desc,meta}` and a `:kaocha.testable/events` vector
of cljs.test-style report maps. `totals->clojure-test-summary` translates that
namespaced summary back to cljs.test's bare `{:test :pass :fail :error}`. Two
lessons stolen directly:

1. **Namespace the public summary; translate cljs.test's bare counters at the
   boundary.** This resolves the catalog's flagged smell (the runner's
   registered `::summary` is bare `{:test :pass :fail :error}`) WITHOUT making
   the engine lie about cljs.test's contract — the engine stays faithful to
   cljs.test's report shape; the `my.test` facade owns the namespacing
   translation. That translation is precisely the value a thin wrapper adds.
2. **The result is a value, not a side effect** — `{summary + per-test +
   events}` as data is the agent-readable contract. `my.test` keeps the lean
   three-key subset (`pass? / summary / failures`) and points at the run-id
   stash for the full events when the agent wants to drill in.

## Recommended API — map-in / map-out, errors-as-values

Shared shapes (the catalog already reserves these; this is the smell-fix landing
in the same patch). The summary keys mirror kaocha's namespaced form, fed by
translating cljs.test's bare counters at the facade:

```clojure
(schema/register! :seon.test/pass? :boolean)
(schema/register! :seon.test/summary
  [:map [:seon.test/tests :int] [:seon.test/pass :int]
        [:seon.test/fail  :int] [:seon.test/error :int]])
(schema/register! :seon.test/failures
  [:vector [:map [:seon.test/var :symbol] [:seon.test/message :string]]])
(schema/register! :seon.test/run-id :string)   ; pointer to the globalThis event stash

```

### `check` — the verify half of define → eval → verify

```clojure
;; IN  (one selector key): {:my.test/sym  'my.x/add}    ; a fn or deftest symbol
;;                    OR    {:my.test/ns   'my.x-test}   ; every test var in a ns
;; OUT (the value IS the answer — specialized, not the generic ok? envelope):
;;   {:seon.test/pass?    <bool>                          ; fail=0 AND error=0
;;    :seon.test/summary  {:seon.test/tests N :seon.test/pass N
;;                         :seon.test/fail N :seon.test/error N}
;;    :seon.test/failures [{:seon.test/var 'my.x/add-test
;;                          :seon.test/message "expected 5, actual 4"} …]
;;    :seon.test/run-id   "Ab3kZ…"}                       ; → full events via the stash
;; OUT (can't-run, never throws — errors are values):
;;   {:seon.test/pass? false
;;    :seon.error/message "no tests found for my.x/add"
;;    :seon.error/data {:seon.error/kind :user-input}}
(defn ^:async check [m] …)

```

Resolution order inside `check` (all already supported by the engine):

1. `:my.test/ns` → `vars-in-ns` → `run-vars`.
2. `:my.test/sym` that resolves to a `:test` thunk (colocated
   `{:test (fn [] …)}` OR a `deftest`) → `run-vars {::vars [sym]}` directly.
3. `:my.test/sym` (a plain fn, no colocated test) → fall back to
   `tests-referring-to sym` (the substring-scan heuristic), run those. Empty →
   the can't-run envelope above, pointing the agent at writing a `:test`.

### `check-edit` — the dual set (SWE-bench's scoring, stolen)

```clojure
;; IN:  {:my.test/fail-to-pass ['my.x/bug-repro-test …]    ; MUST now pass
;;       :my.test/pass-to-pass ['my.x/happy-1 'my.x/happy-2 …]} ; MUST still pass
;; OUT:
;;   {:seon.test/resolved?      <bool>          ; ALL f2p pass AND ALL p2p pass
;;    :seon.test/fixed          ['my.x/bug-repro-test]      ; f2p now passing
;;    :seon.test/still-failing  []                          ; f2p still red
;;    :seon.test/regressed      []                          ; p2p that broke
;;    :seon.test/fail-summary   {:seon.test/tests … …}      ; over the f2p set
;;    :seon.test/pass-summary   {:seon.test/tests … …}      ; over the p2p set
;;    :seon.test/run-id         "…"}                        ; full events stash
(defn ^:async check-edit [m] …)

```

Implementation = two `run-vars` calls + the predicate
`(and (zero? f2p-fail+error) (zero? p2p-fail+error))`. POST-EDIT only: the
agent's `(defn …)` is already live, so labeling which set is fail-to-pass IS
the specification — no before-snapshot needed (matches SWE-bench, which runs
both sets only AFTER the patch is applied; survey lines 113-118). The
`still-failing` / `regressed` vectors are symbols → thread straight back into
`check` for per-var drill-down.

### Optional third verb — `details` (thin over the stash)

```clojure
;; IN:  {:seon.test/run-id "Ab3kZ…"}
;; OUT: {:seon.test/events [<full ::test-event maps>]}   ; via fetch-run
(defn details [m] …)

```

Keeps `check`'s value lean (just the failure projection) while giving a
one-call path to the full captured event sequence. Together, `check`'s
`:seon.test/run-id` and `details` ARE the stash-pointer pattern the catalog
mandates: a pointer in the cheap value, the blob fetched on demand.

## Composability — outputs thread into inputs (PATH/REF/ITEMS/RESULT)

- **RESULT flavor:** `my.test` is one of the catalog's two SPECIALIZED result
  shapes (`{pass? summary failures}`) — the value IS the answer, so `pass?`
  is the discriminator on the happy path. The can't-run path still returns the
  shared `:seon.error/*` map (errors-are-values), so the agent distinguishes
  "ran and failed" from "couldn't find a test."
- **REF threading:** `:seon.test/failures` items carry `:seon.test/var` (a
  symbol). The threadable DB address of that test is the lookup-ref
  `[:seon.test/sym (str var)]` (the engine's identity attr is a STRING). So a
  failure feeds `(db/pull {:seon.db/ref [:seon.test/sym (str var)]})` to read
  `:seon.test/source`, OR `(check {:my.test/sym var})` to re-run just it, OR
  `(my.code/forget! var)` if the test itself is wrong. One documented rekey
  (symbol → string identity) — flagged so the wrapper does the `(str var)`
  itself if we want zero-rekey threading.
- **ITEMS:** `failures`, `fixed`, `regressed`, `still-failing` are vectors of
  self-describing maps/symbols, each a valid next input. Counts stay scalars in
  `summary` (aggregates, not items — per the catalog rule).
- **The loop chain:** `define (defn …) → eval → (check {:my.test/sym …})`;
  on red, `:seon.test/failures` → pull source / fix / re-check; on an edit to
  fix a bug, `(check-edit {:my.test/fail-to-pass […] :my.test/pass-to-pass […]})`
  → `resolved?` gates "done."

## Gotchas

1. **Self-host has no real `Var`s.** You CANNOT route through
   `cljs.test/run-tests` / `test-vars` — the engine uses a synthetic
   `#js {:sym sym}` testing-var and replicates the fixture walk. Any instinct to
   "just call cljs.test" is wrong; go through `run-vars`. (This is the deepest
   reason `wrap-lib` is impossible.)
2. **NEVER overlap `cljs.test/run-tests` in the live pod** (CLAUDE.md — it
   wedges the shared async continuation). `run-vars` is safe because it uses a
   per-call env + `:reporter` slot, NOT the global report root. `my.test` MUST
   call `run-vars`, never `run-tests` and never shell `bin/test-cljs`.
3. **Malli-instrumentation unwrap is load-bearing.** A `:test`-bearing fn that
   also carries `:malli/schema` is wrapped; the thunk lives on the ORIGINAL
   (`malli$instrument$original`). The engine already handles this — don't
   reimplement test resolution in the facade.
4. **`tests-referring-to` is a substring heuristic.** `check` of a plain fn
   with no colocated `:test` may over-match (a fn named `foo` matches comments
   / unrelated symbols sharing the suffix). Document it; prefer colocated
   `{:test …}` or an explicit `:my.test/ns`.
5. **The bare-keyword summary smell.** cljs.test's counters ARE bare
   (`{:test :pass :fail :error}`); the runner's registered `::summary` mirrors
   them. Fix at the FACADE: translate to `:seon.test/*` (kaocha's exact
   pattern), and don't let bare keys leak into the agent-facing value.
   Whether to also rename the engine's internal `::summary` is optional — it's
   cljs.test-interop-faithful as-is.
6. **Events live in the globalThis stash, not the DB.** `:seon.test/run-id`
   does NOT resolve via `db/pull`; full events come from `fetch-run`/`details`.
   `check`'s `failures` is a small projection of the captured `:fail`/`:error`
   events.
7. **`check-edit` is a post-condition, not a differ.** No before/after
   snapshot; live redefinition can't be cheaply un-applied. The set labels are
   the spec. (A bitemporal `as-of` "before" variant is possible but unjustified
   — note as a non-goal.)
8. **`^:async`.** `check`/`check-edit` await `run-vars`; the agent must `await`
   them (native CLJS await — the pod is core.async-free).
9. **Render rule (separate owner — flag, don't build here).** The per-namespace
   `:seon.test` block renders into context ONLY for the agent's CURRENT ns
   (`seon.ctx.namespaces` / `render-namespace`), coordinated with the GI-1
   double-render fix. Not a `my.test` API concern, but the verify-loop UX
   depends on it.

## Sources

- `src/seon/test/runner.cljs` — the existing engine (read in full): capture
  env, `resolve-test-fn` (dual authoring form + malli unwrap), `run-vars`
  (async + fixtures), stash/projection split, `last-result`,
  `tests-referring-to`.
- `src/seon/eval.cljs` ~L1260 (`deftest-def?` analyzer marker), ~L2657
  (auto-test-on-fn-redef calls `run!` with `:trigger :on-fn-redef`).
- `docs/prds/agent-fsm/toolkit-catalog.md` §`my.test` (Q2), §"four shared
  shapes" (PATH/REF/ITEMS/RESULT), the two-tier floor/owned model.
- `docs/prds/agent-fsm/research/agentic-benchmarks-survey-2026-06-26.md`
  lines 113-129 (SWE-bench FAIL_TO_PASS/PASS_TO_PASS predicate + "steal the
  dual-set" recommendation) and §D line 646-649 (test-runner-with-structured-
  pass/fail recurs across SWE-bench/Aider/Commit0/SWE-Lancer/Terminal-Bench).
- `reference-code/kaocha/src/kaocha/result.clj` — `:kaocha.result/{count,pass,
  fail,error,pending,time}`, `:kaocha.testable/{id,type,desc,meta}`,
  `totals` + `totals->clojure-test-summary` (the namespace-the-summary,
  translate-bare-cljs.test-counters pattern). JVM-only — design oracle, not a
  dependency.
- `reference-code/clojurescript/src/main/cljs/cljs/test.cljc` — the `report`
  multimethod, async `IAsyncTest`, fixtures contract the engine wraps.
- `src/my/kb.cljs` — the live precedent that `my.*` editable wrappers over a
  `seon.*` floor already ship.
- (Gemini/`agy` web lookup attempted for kaocha-cljs/SWE-bench corroboration but
  was non-responsive this session; all claims above are grounded in vendored
  source + the in-repo benchmark survey instead.)
