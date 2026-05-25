---
type: research
status: completed
tags: [research, testing, agent]
---

# CLJS testing infrastructure — Phase 1 handoff (2026-05-25)

## What shipped

- `src/seon/test/runner.cljs` — `vars-in-ns`, `run!`, `run-ns!`, `last-result`, helper `resolve-selector`, `last-run-id-from-db`. `run-vars` and `record-run!` are already `^:async`; `drive-test-fn!` awaits both Promise/A+ thenables and cljs.test `IAsyncTest` CPS continuations. `::selector` schema added; `::run-request` enforces exactly one of `::vars` or `::ns` for Phase 1 (future selectors gated by their phases).
- `test/seon/db_test.cljs`, `test/seon/render_test.cljs` — moved from `src/` via `git mv`; history preserved.
- `test/seon/test/runner_test.cljs` — 7 self-tests, 26 assertions covering `vars-in-ns`, both `run!` selectors, `run-ns!` recording, `last-result` round-trip, and the async-await contract. Each test fails with the data that drove the decision so a regression's fail event tells you what broke. Probes live in a separate ns to avoid self-recursion.
- `test/seon/test/runner_probes.cljs` — 3 synthetic deftest vars (passing, failing, async-via-setTimeout) so the self-tests can drive the runner against known-shape inputs without recursing.
- `src/seon/dev/test_preload.cljs` — requires test nses so they're reachable from the live `:client` pod. Wired into `shadow-cljs.edn` `:client :devtools :preloads` (zero release-bundle cost).
- `shadow-cljs.edn` — `:client` now lists `seon.dev.test-preload` as a devtools preload.
- `deps.edn` — `"test"` added to the `:cljs` alias `:extra-paths` so shadow's JVM classpath can resolve `seon.test.runner-test` et al (without this, shadow's classpath cache returns "namespace not available" even though the .cljs file exists).

### Picked-up fixes that weren't on the original list

- `record-assertion!` now uses `some?` checks for `:message`, `:file`, `:line` (cljs.test passes literal nils that violated the `::test-event` `:string`/`:int` schemas — caught immediately by `stash-run!`'s Malli instrumentation).
- The Phase 1 plan said "use `cljs.analyzer.api/ns-interns`" for `vars-in-ns`. Probe 1 already documented this strips `:test` meta under self-host. The shipped impl walks the munged ns object on `globalThis`, finds props with `cljs$lang$test` marker, and reads the FQ sym from the runtime var's meta. Comments cite the probe.

## Acceptance evidence

All commands run via `mcp__seon_cljs__eval` against pid 19360 (pod restarted with the new preload).

```clojure
;; Acceptance #1 — run-ns! against the real db-test ns
(r/run-ns! {:seon.test.runner/ns 'seon.db-test
            :seon.test.runner/record? true})
;; → {:summary {:test 26 :pass 164 :fail 10 :error 51}
;;    :run-id "dacuvIaERe"
;;    :recorded? true
;;    :selected-count 26
;;    :event-count 278
;;    :recorded-count 15}

;; Acceptance #2 — last-result round-trip
(r/last-result {})
;; → {:run-id "dacuvIaERe"
;;    :summary {:type :summary, :test 26, :pass 164, :fail 10, :error 51}}
;; (full event blob also hydrated from the globalThis stash)

;; Acceptance #3 — the runner self-test suite
(r/run-ns! {:seon.test.runner/ns 'seon.test.runner-test
            :seon.test.runner/record? true})
;; → {:summary {:type :summary, :test 7, :pass 26, :fail 0, :error 0}
;;    :run-id "OQFjjYu1ON"
;;    :recorded? true
;;    :recorded-syms-count 7
;;    :failures []}

;; Acceptance #4 — single-var run! through the self-test
(r/run! {:seon.test.runner/vars
         '[seon.test.runner-test/vars-in-ns-discovers-probe-tests]})
;; → {:type :summary, :test 1, :pass 3, :fail 0, :error 0}
```

The 10 fails + 51 errors in `seon.db-test` are NOT runner bugs — they're the current state of the db-test code itself (pre-existing). The runner faithfully surfaces them via the captured-as-data pipeline.

## Deviations from the plan

1. **`vars-in-ns` impl path.** Plan said analyzer; probe results said runtime-meta walk. Shipped runtime-meta walk with a doc comment citing the probe. Outcome identical for the caller.
2. **`record-assertion!` nil-key bug.** Plan didn't mention it because no acceptance check exercised `record?` against a passing test until now. The Malli instrumentation on `stash-run!` caught it immediately — exactly the kind of bug §4.K wants. Fix landed in the same patch.
3. **Probes split into `seon.test.runner-probes`.** Plan put probes in the self-test ns. That causes infinite recursion when `run!-with-ns-selector` re-selects its enclosing ns. Split is documented inline.
4. **No `seon.test.suite` yet.** That's Phase 3 work. The interim mechanism is `seon.dev.test-preload`, which serves the same role for the live `:client` pod (require test nses so they're reachable) but is opt-in per-build via the `:devtools :preloads` slot, not driven by `:seon.fn/test? true` discovery.
5. **Async assertion-count limitation surfaced honestly.** CLJS dynamic bindings are lost across the JS event-loop boundary; `(is true)` inside a `js/setTimeout` reports to whatever `t/*current-env*` is when the timer fires, NOT the env that started the test. The async self-test asserts on what the runner contractually owns (did it AWAIT the body?) via a side-effect atom, not on the inner pass count. Documented in the test's docstring as a known limitation.

## Smallest concrete Phase 2 starting point

Per §6 Phase 2 sequencing, step 7:

> **Add `:seon.fn/test?` and `:seon.fn/test-targets` schema attrs in `src/seon/agent.cljs:251+`.**

That's a 4-line Malli schema-registration plus the corresponding Datahike valueType update in `seon.client/agent-bootstrap-schema`. No code that consumes the attrs yet — that's step 8. But it unblocks the analyzer-tee extension (step 8) and the unified `:seon.fn/test? true` discovery (step 11 BFS handler), which is the load-bearing piece of the reactive spine.

Pair it with the §4.N Probe 4 follow-ups (probe doc already wrote them up):

- Add `malli.instrument` to `shadow-cljs.edn :bootstrap :entries` so agent-evaled code can `(require '[malli.instrument])` cleanly.
- When step 9 lands, use `m/-register-function-schema!` 6-arity (not the non-existent `mi/-register!`); pass `:report seon.error.instrument/report-fn` to `mi/instrument!`.

## Files touched

- `src/seon/test/runner.cljs` (edit — public surface + nil-key fix)
- `src/seon/dev/test_preload.cljs` (new)
- `test/seon/test/runner_test.cljs` (new)
- `test/seon/test/runner_probes.cljs` (new)
- `test/seon/db_test.cljs` (moved from `src/seon/`)
- `test/seon/render_test.cljs` (moved from `src/seon/`)
- `shadow-cljs.edn` (preload wired into `:client`)
- `deps.edn` (`"test"` on `:cljs` extra-paths)
- `docs/prds/agent-runtime/research/cljs-testing-infrastructure-2026-05-25.md` (Phase 1 marked COMPLETED with acceptance data)
