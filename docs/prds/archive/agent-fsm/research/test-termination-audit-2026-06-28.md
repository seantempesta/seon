---
type: research
status: draft
tags: [research, agent]
---

# Test-termination audit — can any test never-terminate?

## TOP-LINE

**140 test files audited (68 `.cljs` + 3 `.cljc` + 69 `.clj`; ~1252 `deftest`
vars). ZERO tests can never-terminate by construction in the healthy path.
M (never-terminating) = 0.**

- The only never-resolving-by-construction promise in the whole test tree
  (`runner_timeout_probes.cljs:26`) is **double-gated** — it is a no-op in the
  `bin/test-cljs` suite run, and when armed it only ever flows through the
  now-bounded `run-vars`. Gating CONFIRMED (below).
- All live/agent/LLM drives are bounded (env-gated OR scripted-stub OR
  turn-limit/deadline bounded). All sync loops are deadline/tries bounded. All
  lazy seqs are `take`/sampler bounded. No test holds a dynvar `binding` across
  an `await`.
- **3 `.cljs` files carry a LOW-severity STRUCTURAL gap** (not a by-construction
  hang): a terminal `(.then done)` whose `done` is not guaranteed if a promise
  in the chain *rejects*. They do NOT hang in the green suite (empirically
  ~673 tests complete in ~200s) — they would only fail to terminate if the
  production DB/fs setup itself regressed to *rejecting*. Flagged with the exact
  fix; not bulk-edited (26 call sites across 3 files = task-worthy, not
  "trivial+obvious", and the safe idiom already exists in a sibling file).

**Fixed in this pass: 0** (no by-construction hang found to fix; the structural
gap is flagged as a focused follow-up, see "Flagged" below).

## Scan coverage + method

Static grep + read over `test/seon/**`. The pod-wedge class is CLJS-only
(`^:async`/`await`/`(async done …)`/never-resolving `js/Promise`), so the
async analysis is concentrated on the 71 `.cljs`/`.cljc` namespaces; the 69
`.clj` JVM namespaces were swept for sync infinite loops + untimed blocking
takes only.

Patterns searched (the five the brief names):

1. `^:async` body awaiting a never-resolving promise / `js/fetch` / live
   resource — grep `js/Promise.` (minus `.resolve/.reject/.all/.race`),
   `js/fetch`, `XMLHttpRequest`, `setInterval`/`setTimeout`.
2. `(async done …)` where `done` is not guaranteed on every path — per-file
   profile of `(async` vs `.then`/`.catch`/`.finally`/`(done)` counts, then
   read every outlier (low `.catch`/`done` against high `async`).
3. Sync infinite loops — `(loop [] … recur)`, `while true`, unbounded
   `dotimes`/`iterate`/`cycle`/`range`/`repeatedly`.
4. `binding` of `cljs.test/*current-env*` (or any dynvar) across `await`.
5. Live-pod / real-agent / real-LLM drives without a bound.

## Gating CONFIRMED — `bin/test-cljs` cannot hang on the probes

shadow's `:node-test` runner registers EVERY ns that has `deftest` vars and is
on the classpath (the `-test$` `:ns-regexp` controls *compilation*, not
*discovery*) — so the `*-probes` nses run DIRECTLY in `bin/test-cljs`. Each is
gated so the direct (unarmed) run is a no-op:

| Probe ns | Never-settle construct | Gate → direct-run behavior |
|---|---|---|
| `runner_timeout_probes.cljs` | `js/Promise.` that never resolves (`:26`); `(async done)` that never calls `done` (`:29`) | `@armed?` false by default → returns `(is true)` / calls `done`. Armed ONLY by `runner_timeout_test`, which drives them through `run-vars` under `SEON_TEST_TIMEOUT_MS=150` and disarms + `done`s in `.finally`. **Safe.** |
| `runner_probes.cljs` | none (intentional *failure*, not hang) | `@armed?` gates a `(is = mismatch)` *assertion*, not a hang; `probe-async-test` calls `done` inside a 25ms `setTimeout`. **Safe.** |
| `async_fixture_probes.cljs` | fixtures return Promises | only when `@armed?`; resolve via 20–50ms `setTimeout`; unarmed → `nil` (no-op). Probe bodies are sync `is`. **Safe.** |
| `fixture_support_probes.cljs` | `probe-c-async` `(async done)` | `done` inside a 10ms `setTimeout`, unconditional. **Safe.** |

Empirical corroboration (root-cause doc + prior runs): `bin/test-cljs`
completes ~673 tests in ~200s WITH `runner_timeout_test` armed and passing — so
the bounded run-vars converts the armed never-settle bodies into timeout
`:error` events instead of a hang. Gating is live-proven.

## Verdict table — every async/await/loop/done/blocking site of note

| Site | Construct | Verdict |
|---|---|---|
| `test/runner_timeout_probes.cljs:22,29` | never-resolving promise / done-never-called | SAFE — armed-gated + only driven through bounded `run-vars` |
| `test/runner_probes.cljs:37,45` | armed fail / `setTimeout` done | SAFE — bounded, unconditional done |
| `test/async_fixture_probes.cljs:26-51` | armed async fixtures | SAFE — `setTimeout`-resolved, armed-gated |
| `test/fixture_support_probes.cljs:34` | `(async done)` | SAFE — `setTimeout(10)` → `done` |
| `test/runner_timeout_test.cljs` | drives probes | SAFE — 150ms bound; `done` in `.finally` every path |
| `gym/paid_test.cljs` (8 paid + calib deftests) | live LLM drive; `setInterval` keepalive (`:118`) | SAFE — whole tests gated on `SEON_GYM_PAID` (no-op in `bin/test-cljs`); `done` via `call-once` on every `.then`/`.catch`; keepalive armed only under the gate, killed by `process.exit` at suite end |
| `gym/driver_test.cljs` (23 deftests) | real `run-loop!` drive | SAFE — `:scripted-replay` (no live LLM); `run-loop!` bounded by turn-limit/deadline; every deftest pairs `.then …done` + `.catch …done` |
| `gym/driver.cljs:1155` `rejecting-llm` | `js/Promise` | SAFE — rejects after 100ms `setTimeout` |
| `gym/driver.cljs:1178,1212` drive loops | `loop`/`run-loop!` | SAFE — `drive-stub-turns!` bounded by script seq; `drive-loop!` bounded by run turn-limit/deadline |
| `agent_loop_test.cljs:121` `wait-until` | recursive poll | SAFE — base case `(<= max-ms 0) → false`; step via `setTimeout` |
| `agent_loop_test.cljs:133` | `js/Promise` | SAFE — resolved by `setTimeout` |
| `store/wire_test.cljs:81,100,138` | `(async done)` + `setTimeout` | SAFE — `done` via `.finally` (`:81`) or inside 25ms `setTimeout` (`:104,:144`) |
| `eval/promise_ergonomics_test.cljs:87,125` | `js/Promise` in eval-strings | SAFE — resolved by 150/500ms `setTimeout`; the eval path is itself `race-timeout`-bounded |
| `db/transact_precondition_test.cljs:40,72` | counting-`done` `(when (zero? …) (done))` | SAFE — `pending` = exact call count; `envelope-error` fires its cb once via `.then`-or-`.catch` |
| `db_test.cljs` (29 async) | terminal helper `:93-99` | SAFE — gold-standard `.catch (is false) → .then (done)` guarantees `done` |
| `boot/preconditions_test.cljs` | `(async done)` | SAFE — gold-standard `.catch → .then done`; docstring documents fixing a prior sync busy-wait |
| `eval/require_test.cljs:34` | `(iterate :seon.error/cause …)` | SAFE — `(take-while some?)` bounds the cause chain |
| `render/value_test.cljs:69` | `(map … (range))` infinite | SAFE — deliberately probes the bounded sampler (`v/sample {:max-items 8}`); asserts `<= 50` realized |
| `server/test_util.clj:37`, `boot_test.clj:56`, `protocol_integration_test.clj:51` | `(loop [] … recur)` polls | SAFE — every one deadline-bounded (`System/currentTimeMillis` vs a timeout), throws/returns on overrun |
| `server/test_util.clj:72`, `tx_feed_replay_test.clj:138` | drain loops | SAFE — `tries` cap (20 / 80) |
| `dev/verify_test.clj:100` | `(deref (resolve …))` | SAFE — derefs a *var*, not a future/promise; never blocks |
| ai adapter tests (`anthropic_test`, `openai_compat_test`, `ai_test`) | `(async done)` | SAFE — stubbed transport (no `js/fetch` anywhere in `test/`); `done` paired with `.catch`/`.finally` |
| **`db/envelope_test.cljs`** (~12 deftests) | terminal `(.then done)` | **RISK (low)** — see below |
| **`agent/search_test.cljs`** (~10 deftests) | terminal `(.then done)` | **RISK (low)** — see below |
| **`db/pull_guard_test.cljs`** (4 deftests) | terminal `(.then done)` | **RISK (low)** — see below |

`binding` of `*current-env*` across `await`: **0 occurrences in `test/`** (the
only such site is `src/seon/test/runner.cljs:556`, the runner lane — already
mitigated by `with-test-timeout` and flagged as a deferred refactor in the
root-cause doc; out of this audit's edit scope).

## The at-risk list (file:line + why + fix) — LOW severity, structural

All three share one shape: the deftest chains assertions and ends in a bare
`(.then done)`. A bare `(.then done)` only registers `done` as the *fulfilled*
handler — if ANY promise earlier in the chain **rejects**, the rejection
propagates PAST `(.then done)` (rejections skip fulfilled-only handlers) and
`done` is never called → the `(async done)` test hangs.

Why they do NOT hang in the green suite: the *tested* promise is guarded
(`never-reject!` / `resolves!` wrap it), and `cljs.test/is` swallows throws
inside assertion bodies. The only UNGUARDED reject path is the **DB/fs setup**
(`fresh-conn` = `d/create-database` + `d/connect`; `seeded-conn` adds a seed
`transact!`; search's `setup!`), which is deterministic on a `:memory` store
and does not reject in a healthy system. So today they terminate; they would
hang only if the production setup regressed to rejecting — i.e. this is
NON-ROBUST failure handling, not a by-construction never-terminate.

- `test/seon/db/envelope_test.cljs` — every deftest terminal `(.then done)`
  (e.g. `:99, :133, :220, :247, :298, :342`). Tested transact wrapped in
  `never-reject!` (`:68`); `fresh-conn` (`:58`) unguarded.
- `test/seon/agent/search_test.cljs` — terminal `(.then done)` (e.g.
  `:99, :116, :134, :146, :164, :181, :203, :221, :234, :251`). `grep` wrapped
  in `resolves!` (`:63`); the assertion `.then` (contains a sync
  `fs/read-file`, `:95`) and `setup!` unguarded.
- `test/seon/db/pull_guard_test.cljs` — terminal `(.then done)` (`:102, :134,
  :163, :180`). **No `.catch` anywhere** in the file; `seeded-conn` (`:52`,
  create + connect + seed `transact!`) fully unguarded.

FIX (mechanical; the gold-standard already lives in sibling `db_test.cljs:93-99`
and `boot/preconditions_test.cljs`): replace each terminal `(.then done)` with
the guaranteeing pair —

```clojure
;; before
(.then done)
;; after
(.catch (fn [e] (is false (str "test chain rejected — " e))))
(.then (fn [_] (done)))
```

This calls `done` exactly once on EVERY path (fulfill → `.catch` skipped →
`.then` `done`; reject → `.catch` reports + resolves → `.then` `done`) AND
turns a silent hang into a clean, named failure. 26 call sites across 3 files.

WHY FLAGGED, NOT FIXED HERE: low severity (does not hang in the green path,
suite-proven), 26 sites is past "trivial+obvious", and bulk-editing test bodies
in an audit pass + a mandatory ~200s suite re-run trades against the
"full-suite-once / token-economy" directive for near-zero risk reduction. Best
as a focused follow-up unit (`TaskCreate`: "Make `(async done)` terminals
unconditional in envelope_test / search_test / pull_guard_test — convert bare
`(.then done)` to `.catch→.then(done)`").

## What I fixed vs flagged

- **Fixed: nothing.** No by-construction never-terminate exists in `test/` to
  fix; the gated probes are correct as-is.
- **Flagged: the 3-file `(.then done)` structural gap** above (low severity,
  exact fix + file:lines provided).
- No suite re-run performed: zero edits were made, so the documented baseline
  (~673 tests, ~200s, 1 known pre-existing `render/value_test` tokens/chars
  drift unrelated to termination) stands unchanged.

## Out-of-lane residuals (already known; not re-fixed here)

- `src/seon/test/runner.cljs:556` — `binding [t/*current-env* …]` held across
  `await` (the overlap-corruption footgun). Mitigated by `with-test-timeout`,
  proper fix (thread `env` explicitly) deferred per the root-cause doc. Runner
  lane — not this task's edit scope.
- Sync infinite loop in agent-eval'd code blocks the single Node event loop
  even past every timer — needs `worker_threads`/`wasmtime` (acknowledged
  `eval.cljs:67`). No such loop exists in any *test*; this is an eval-path
  residual only.
</content>
</invoke>
