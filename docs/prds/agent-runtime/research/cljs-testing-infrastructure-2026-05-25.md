---
type: research
status: draft
tags: [research, testing, agent, prd]
---

# CLJS testing infrastructure — research + plan

## Revision log

- **2026-05-25 (revision):** killed `:seon.test/test` as a separate discovery entity — agent-defined tests are now plain `:seon.fn` rows with `:seon.fn/test?` true and `:seon.fn/test-targets` for explicit targeting; `:seon.test/*` retained only as the run-result projection. (§4.A, §4.G, §4.L, §5, §6, §7, §8.)
- **2026-05-25:** moved the agent-defined-fn instrumentation hook (`mi/-register!` + `mi/instrument!` in `build-tee-entities`) from Phase 5 → Phase 2; it's load-bearing for the Phase 2 reactive acceptance test, not a polish item. (§4.K, §6 renumbered.)
- **2026-05-25:** fixed TL;DR numbering — was `1,2,3,1,2,3,4,5`; now a clean `1–7`. (§1.)
- **2026-05-25:** reconciled the two divergent `::run-result` shapes; canonical definition now lives in §5 with `::run-id`, `::selected-vars`, `::recorded?`, `::recorded-syms`. §4.E/§4.H reference §5 instead of redefining. (§4.E, §4.H, §5.)
- **2026-05-25:** added §4.N "Pre-Phase-1 probes" with exact REPL forms for `:test` meta survival, `with-redefs` self-host, `use-fixtures :each` async ordering, and `mi/instrument!` on post-bootstrap-defined syms. Each lists expected output + fallback.
- **2026-05-25:** §4.L "transitive dependents" Datalog sketch marked **PENDING** — depends on `datahike-query-capabilities-2026-05-25.md` audit (currently running in parallel). Same for Phase 2 step.
- **2026-05-25:** §4.F CLI shim picked Babashka concretely with a 15-line bb-nrepl-client script; deleted the hand-wave `node -e "..."` stub.
- **2026-05-25:** added §4.O "Test lifecycle / GC" — when a test loses `:seon.fn/test?`, the warnings query filters it out via join (derived-not-stored, matches reactive-context principle); no retraction needed.

Plan for the V0 CLJS pod's testing surface. One shared API serves
humans (REPL), agents (eval-batch), and CI (CLI). Foundation already
exists in `src/seon/test/runner.cljs:1-383` (`run-vars` /
`stash-run!` / `record-run!` / `run-and-record!`); this document
extends — never replaces — that foundation.

Cross-references:

- [loop-testing-strategy-2026-05-25.md](loop-testing-strategy-2026-05-25.md) — the 5-layer strategy this plan slots beneath.
- [platform.md](../platform.md) Phase 2 — where the runner was sequenced.
- `src/seon/test/runner.cljs` — the shipped foundation.

> **Revised 2026-05-25 (follow-up):** the headline framing shifted
> from "test runner with a run-all entrypoint" to **"reactive test
> daemon, with `run-all!` as the before-victory escape hatch."** The
> auto-trigger handler (formerly Phase 6, §4.G) is now the spine. Two
> new sections — §4.K (Malli instrumentation during tests) and §4.L
> (Reactive testing) — and a new walkthrough §10 cover the changes.
> Phase ordering in §6 has been re-sequenced so the reactive loop
> works end-to-end as early as possible. The existing §4.A–J
> recommendations still hold; nothing got rewritten, just extended.

## 1. TL;DR

1. **Reactive by default; `run-all!` is the "before-victory" escape
   hatch.** A `seon.runtime` handler keyed on `:seon.fn/source` tx
   data computes affected tests via Datalog over the program graph
   and queues a `:run-tests` effect. Editor-save and agent eval-batch
   go through the same handler — no editor plugin, no separate
   filesystem watcher. (§4.L + §10.)
2. **Malli instrumentation stays on during tests.** The pod boots
   with `seon.instrument/install!` already wrapping every
   `:malli/schema`-bearing fn; the test runner does NOT toggle it.
   Tests get input/output/arity validation for free. Per-test
   override is via dynvar (`m/-no-checks` shim) only for "I'm
   intentionally calling this fn wrong to assert the error envelope"
   tests. (§4.K.)
3. **Keep `seon.test.runner` as the only entrypoint.** Extend it with
   four new map-in/map-out selectors (`run-ns!`, `run-all!`,
   `run-failed!`, `last-result`) that all funnel into the existing
   `run-vars` core. No `seon.test2`, no `seon.test.api` parallel ns.
4. **Unified discovery via `:seon.fn/test?`.** Both platform tests
   (`deftest` in `test/`) and agent-defined tests (`deftest` evaled
   at runtime) land in the program graph as `:seon.fn` rows; the
   analyzer-tee sets `:seon.fn/test?` true when the symbol carries
   `:test` meta. Agent-authored tests that target a specific symbol
   (vs. relying on the `foo` ↔ `foo-test` sibling convention) use
   `:seon.fn/test-targets [:vector :symbol]`. `:seon.test/*` is NOT
   a discovery entity — it's only the run-result projection
   (`:seon.test/last-passed-at`, `:seon.test/last-failed-at`, etc.).
   A hand-maintained `seon.test.suite` ns still `(:require …)`s every
   platform test ns so they load at boot.
5. **`test/` is the canonical location.** Move
   `src/seon/db_test.cljs` and `src/seon/render_test.cljs` to
   `test/seon/db_test.cljs` and `test/seon/render_test.cljs`. `test/`
   is already on the shadow source path (`shadow-cljs.edn:42-43`).
6. **Async via Promises, not `(async done …)`.** Add an `^:async`-aware
   driver path inside `run-vars` that, when a test fn returns a
   thenable, awaits it before emitting `:end-test-var`. cljs.test's
   `(async done …)` form keeps working (it's just a callback that
   resolves a Promise we wait on). `defspec` from test.check needs a
   small wrapper that wraps the property runner in a thenable.
7. **One CLI shim, one REPL fn.** `bin/seon test pod` (cold-start
   wrapper) and `(seon.test/run)` (REPL helper, JVM nREPL `:7888`-via-MCP
   or shadow nREPL `:7889`) both call `seon.test.runner/run-all!`. The
   human pattern mirrors `(user/run-tests 'ns)` on the JVM side; output
   is the data result rendered by `seon.test.render` (new) for the
   REPL, raw EDN over the wire.

## 2. Current state

### What works today

| Component | File / Line | Status |
|---|---|---|
| Capture-as-data core | `src/seon/test/runner.cljs:209-266` | shipped — `run-vars` |
| Stash to globalThis | `src/seon/test/runner.cljs:285-310` | shipped — `stash-run!`/`fetch-run` |
| DB projection | `src/seon/test/runner.cljs:335-369` | shipped — `record-run!` |
| Convenience composite | `src/seon/test/runner.cljs:371-383` | shipped — `run-and-record!` |
| `:seon.test/*` Datahike attrs | `src/seon/test/runner.cljs:82-86` | shipped (Malli only — datahike valueType lives in `seon.client/agent-bootstrap-schema`) |
| cljs.test in bootstrap | `shadow-cljs.edn:228, 238` | shipped — `cljs.test` + `cljs.test$macros` in `:entries`/`:macros` |
| `test/` on source path | `shadow-cljs.edn:42-43` | shipped |
| Pod with conn | `src/seon/db.cljs:1-80` — `*conn*` dynvar, namespaced API | shipped |
| Reference test (sync sweep) | `test/seon/boot/preconditions_test.cljs:1-83` | shipped |
| Reference test (async, `(async done …)`) | `src/seon/db_test.cljs:152-374` | shipped — but uses old core.async `go` + `<!` patterns |
| Reference test (sync pure) | `src/seon/render_test.cljs:1-133` | shipped |

### What is missing (gap inventory)

| Gap | Symptom | Where it should live |
|---|---|---|
| No ns-level entrypoint | Caller has to know every test var by FQN | `seon.test.runner/run-ns!` |
| No "all" entrypoint | Nothing iterates discovered tests | `seon.test.runner/run-all!` + `seon.test.suite` |
| No "failed-only" | Re-running narrows by hand | `seon.test.runner/run-failed!` reading `:seon.test/last-failed-at` |
| No human-friendly output | REPL caller sees a giant data map | `seon.test.render` (new) |
| Agent-defined tests can't be discovered | They live in eval-stash, not in any ns | `:seon.test/test` entity + dispatch |
| No CLI shim | `node out/client/main.js` boots the pod with no test mode | `bin/seon test pod` |
| Async tests don't compose with `run-vars` | `(async done …)` works but doesn't propagate; `^:async/await` doesn't either | `run-vars` Promise-aware driver |
| No auto-trigger from eval-batch | D4 in spec — when `(defn foo …)` lands and `foo-test` exists, nothing fires | `seon.runtime` handler + lookup table |
| Fixtures + `with-redefs` self-host coverage unknown | unverified for our cljs.test build | Investigation, then `seon.test.fixtures` if needed |
| `test/` is barely used | Only `test/seon/boot/preconditions_test.cljs` lives there | Move db_test + render_test |

### Concrete JVM analogue we're mirroring

- `src/seon/dev/test.clj:108` — `(user/run-tests 'ns)` wraps
  `clojure.test/run-tests`.
- `src/seon/dev/test.clj:321` — `test-affected` walks code graph.
- `src/seon/dev/test.clj:357` — `test-gen` runs Malli generative
  checks.

The CLJS surface aims for parity at the ns/symbol level. `test-affected`
is out-of-scope until a CLJS code graph exists; `test-gen` is in-scope
because test.check is already on deps (`/Users/sean/src/seon/deps.edn`)
and shadow-cljs ships it under CLJS.

## 3. Constraints

Load-bearing rules — every recommendation below honours these:

1. **One shared API.** Same fn called by human REPL, agent eval-batch,
   and CLI. No "internal" vs "external" entrypoint.
2. **Map-in / map-out, every key namespaced.** Already true in
   `run-vars`; preserve.
3. **Three-tier storage.** Full event sequence → globalThis stash.
   DB → only the surfaced projection (`:seon.test/last-passed-at`,
   `:seon.test/last-failed-at`, `:seon.test/last-failure-summary`,
   `:seon.test/last-run-id`). Source code of agent-defined tests →
   blob (in `:seon.test/source` Malli-typed string).
4. **No `_v2` parallel hierarchy.** Extend `seon.test.runner`. Don't
   create `seon.test.runner2`, `seon.test.api`, etc.
5. **No `:any`.** All event/result/request schemas must be concrete.
   `run-vars` already does this with `pr-str` on `:expected/:actual`.
6. **Schema-first.** Every new request/response is `register!`-ed
   before the fn is written.
7. **`*conn*` is the conn.** Tests never reach for `seon.client/!conn`.
   Fixture binds `db/*conn*` to a fresh memory conn; test code uses
   `db/transact!` exactly as production does.
8. **Pod has one DB.** Tests default to per-test in-memory conns
   (`:store {:backend :memory :id (random-uuid)}` — see
   `src/seon/db_test.cljs:61`). Shared-DB tests (loop layer 3) opt
   in explicitly.

## 4. Recommended architecture

### A. Discovery & registration — hybrid (A1 platform + A2 agent)

**Platform tests: A1 — hand-maintained discovery ns.**

New file `src/seon/test/suite.cljs`:

```clojure
(ns seon.test.suite
  "Platform test suite. Every CLJS test ns must be required here so
   its `deftest` vars land on globalThis under the munged path that
   `seon.test.runner/resolve-test-fn` walks. The list is the source
   of truth for `run-all!`.

   Add new test nses with a single (:require) line below — the
   `all-test-syms` collector picks them up via
   `cljs.analyzer.api/all-ns` at boot. (No build-time codegen, no
   shadow-cljs hot-reload race.)"
  (:require
    ;; Platform tests
    [seon.db-test]
    [seon.render-test]
    [seon.boot.preconditions-test]
    ;; future: seon.runtime.dispatcher-test, etc.
    ))

(defn ^:export all-test-syms
  "Walk the analyzer's :defs for every required ns and return the
   FQ symbols whose meta has :test (the cljs.test marker)."
  []
  (vec
    (for [ns-sym (cljs.analyzer.api/all-ns)
          :when (str/starts-with? (str ns-sym) "seon.")
          [name-sym info] (cljs.analyzer.api/ns-interns ns-sym)
          :when (:test (:meta info))]
      (symbol (str ns-sym) (str name-sym)))))
```

**Required by `seon.client/-main`** so the test nses get loaded into
the analyzer at pod boot. (Pure `(:require …)`; no perf cost — same
trick used in `shadow-cljs.edn:226-234` to pre-load `cljs.test`,
`malli.core`, etc.)

**Agent-defined tests: A2 — same `:seon.fn` shape, `:seon.fn/test?` flag.**

Agent-defined tests are not a separate entity kind. When an agent's
eval-batch lands a `(deftest foo-test …)`, the analyzer-tee path
(already in `seon.eval/build-tee-entities` — see
`src/seon/eval.cljs:648`) emits a normal `:seon.fn` row with
`:seon.fn/test? true`. Optional `:seon.fn/test-targets` lets the
agent name which production symbols the test exercises (overriding
the convention-driven `foo` ↔ `foo-test` sibling resolution):

```clojure
(schema/register! :seon.fn/test?         :boolean)
(schema/register! :seon.fn/test-targets  [:vector :symbol])
```

`:seon.fn/source` already holds the form text. `:seon.fn/created-at`
already exists. No new entity, no parallel registry.

Discovery for `run-all!` queries one shape:

```clojure
(into [] (map symbol)
  (db/query {::db/query '[:find [?sym ...]
                          :where
                          [?e :seon.fn/sym ?sym]
                          [?e :seon.fn/test? true]]}))
```

Platform tests (loaded by `seon.test.suite` requires) also land as
`:seon.fn` rows with `:seon.fn/test? true` once their containing ns
is analyzed — single source of truth.

**`:seon.test/*` is the projection.** The only `:seon.test/*` attrs
that exist are run-result rows written by `record-run!`:
`:seon.test/sym`, `:seon.test/last-passed-at`,
`:seon.test/last-failed-at`, `:seon.test/last-failure-summary`,
`:seon.test/last-run-id`, `:seon.test/trigger`. No
`:seon.test/source`, no `:seon.test/test`, no
`:seon.test/defined-at`, no `:seon.test/targets`.

**Rejected:** build-time codegen of the discovery ns (adds rebuild
latency; doesn't help agent-defined tests anyway). Also rejected:
a parallel `:seon.test/test` entity kind — `:seon.fn` already
carries everything needed.

### B. Test file location

**Rule: all CLJS tests live in `test/`.** Move:

- `src/seon/db_test.cljs` → `test/seon/db_test.cljs`
- `src/seon/render_test.cljs` → `test/seon/render_test.cljs`

`test/seon/boot/preconditions_test.cljs` is already correct. `test/`
is already on shadow's `:source-paths` (`shadow-cljs.edn:42-43`), and
the entries comment block (`shadow-cljs.edn:21-28`) explicitly
documents this layout.

**Agent-defined tests have no file.** They live as `:seon.fn` rows
with `:seon.fn/test? true`; their source text lives in
`:seon.fn/source`, the same field every other agent-defined fn uses.
On pod restart they re-emerge by replaying the program-graph entities
— literally the same mechanism that restores any `:seon.fn` def. No
test-specific code path.

**Rejected:** dual-location (some tests in `src/`, some in `test/`).
The split is muddy and the "don't be a dumbass" rule applies. Pick
one — `test/`.

### C. Datahike fixture model — `with-test-conn` macro

New file `src/seon/test/fixtures.cljs`:

```clojure
(ns seon.test.fixtures
  "Test fixtures for CLJS — formalizes the `fresh-conn` pattern
   that `seon.db-test` rolls inline. Every test that touches the DB
   should `use-fixtures` this ns's `:each` map.

   The fixture binds `seon.db/*conn*` to a fresh :memory conn for
   the duration of one test; `db/transact!` etc. use it implicitly.
   Production code and tests share the same call shape."
  (:require [cljs.test :as t]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.schema :as schema]))

(defn ^:async empty-db
  "Return a fresh :memory conn with `:keep-history? true` + the seon
   substrate schema attached. Conn is independent of every other
   conn in the process."
  {:malli/schema [:=> [:cat [:map]] :seon.db/ref]}
  [_]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect cfg))]
      (await (d/transact! conn (substrate-schema-tx)))   ; from seon.client
      conn)))

(defn ^:async with-test-conn
  "Run thunk `f` against a fresh empty-db, with db/*conn* bound. f
   may return a Promise; we await it. Returns the value f resolved to."
  {:malli/schema [:=> [:cat [:map [::thunk fn?]]] :any]}
  [{::keys [thunk]}]
  (let [conn (await (empty-db {}))]
    (binding [db/*conn* conn]
      (await (thunk)))))
```

Usage:

```clojure
(deftest my-test
  (async done
    (-> (fixtures/with-test-conn
          {::fixtures/thunk
           #(go
              (a/<! (db/transact! {::db/tx-data [{::name "Alpha"}]}))
              (is (= 1 (count (db/query {::db/query '[:find ?e :where [?e ::name]]})))))})
        (.then done))))
```

A macro `with-test-conn-async` wraps the boilerplate:

```clojure
(defmacro with-test-conn-async [bind-sym & body]
  `(async done#
     (-> (fixtures/with-test-conn
           {::fixtures/thunk #(let [~bind-sym db/*conn*] (do ~@body))})
         (.then done#))))
```

Most tests get one-line setup. `:shared-conn?` flag in the request
opts the test into a cross-test conn — used only for the cross-agent
loop scenarios. **Default is isolation.**

**Rejected:** `:once` fixture that builds one conn for a whole ns.
Re-runs would silently see previous data; harder to reason about.

### D. Async via Promises — extend `run-vars` driver

`run-vars` today calls `(fn)` synchronously in the `try` at
`src/seon/test/runner.cljs:249`. Two shapes a test fn may return:

1. **Sync test** — body wraps every `(is …)` in straight-line code,
   fn returns `nil`. Today's case. Works.
2. **`(async done …)` test** — body returns a special marker; cljs.test
   notes "test is async" via env state. Today's case in
   `src/seon/db_test.cljs:153`. cljs.test handles the wait IF the
   driver respects `:async?` in `:current-test`.
3. **`^:async` body returning a Promise** — modern CLJS 1.12.145
   native await. The fn returns a Promise; `run-vars` should await it
   before `:end-test-var`.

**Proposed driver change** (`run-vars`, lines 243-256):

```clojure
(doseq [{:keys [sym fn]} present]
  (t/update-current-env! [:testing-vars] conj #js {:sym sym})
  (t/update-current-env! [:report-counters :test] inc)
  (t/do-report {:type :begin-test-var :var #js {:sym sym}})
  (try
    (let [v (fn)]
      (when (and (some? v) (fn? (.-then v)))   ; thenable
        (await v)))                            ; ← NEW: makes run-vars ^:async-safe
    (catch :default e
      (t/do-report {:type :error :message "..." :actual e})))
  (t/do-report {:type :end-test-var :var #js {:sym sym}})
  (t/update-current-env! [:testing-vars] rest))
```

That requires `run-vars` itself to become `^:async`. Callers
(`run-and-record!`) already are. The signature stays
`::run-request → ::run-result`; only the call boundary changes.

`(async done …)` still works because cljs.test's `async` macro
expands to a body that calls `done` on completion; we bridge by
**replacing the `t/test-vars` async-control plumbing with a Promise
the driver awaits**:

```clojure
(let [done-promise (js/Promise. ...)]
  (binding [t/*current-env* (assoc env :async-done #(.resolve done-promise))]
    (let [v (fn)]
      (cond
        (= v :cljs.test/async-stub) (await done-promise)
        (and (some? v) (fn? (.-then v))) (await v)
        :else nil))))
```

cljs.test's `async` macro pushes the test into a "wait for done" mode;
we read the env flag (its internal name is `:current-test` /
`:async?` — check at impl time against
`reference-code/` or the cljs.test source in node_modules).

**Concrete impl note:** the self-hosted cljs.test source ships under
the bootstrap output at `out/bootstrap/cljs/test.cljs.js` — read its
`test-var` implementation to confirm the exact env flag names before
shipping the driver change. (Cross-ref: `shadow-cljs.edn:228` lists
`cljs.test` in `:entries`.)

**Rejected:** `defasync-test` custom macro. cljs.test's `async` already
covers the case; adding our own would fragment the test-author surface
for no win.

### E. The five shared entrypoints

All in `src/seon/test/runner.cljs`. All map-in/map-out. All return the
same `::run-result`-shaped response (or a wrapped envelope for
multi-ns runs).

```clojure
;; ============================================================
;; ::selector — exactly one of these keys must be non-empty
;; ============================================================
(schema/register! ::selector
  [:map
   [::vars        {:optional true} [:vector :symbol]]
   [::ns          {:optional true} :symbol]
   [::nses        {:optional true} [:vector :symbol]]
   [::all?        {:optional true} :boolean]
   [::failed-only? {:optional true} :boolean]
   [::tag         {:optional true} :keyword]])

(schema/register! ::run-request
  ;; Existing schema bumped — adds the new selector keys.
  [:and
   ::selector
   [:fn {:error/message "exactly one selector key required"}
    (fn [m] (= 1 (count (filter #(get m %) [::vars ::ns ::nses
                                            ::all? ::failed-only? ::tag]))))]])

(schema/register! ::record? :boolean)

;; ::run-result — CANONICAL DEFINITION LIVES IN §5. This section just
;; references it. The shape includes ::run-id, ::selected-vars,
;; ::recorded?, ::recorded-syms (the union of what older drafts
;; previously defined here and in §4.H).
```

The five entrypoints:

```clojure
(defn ^:async run!
  "Universal entrypoint. Resolves the selector to a var list, calls
   run-vars, optionally records. Returns a ::run-result.

   Examples:
     ;; from REPL — one ns:
     (seon.test.runner/run! {::ns 'seon.db-test ::record? true})
     ;; from agent eval-batch — one var:
     (seon.test.runner/run! {::vars '[seon.db-test/transact!-round-trips-an-entity]})
     ;; from CLI:
     (seon.test.runner/run! {::all? true ::record? true})
     ;; re-run failures:
     (seon.test.runner/run! {::failed-only? true})"
  {:malli/schema [:=> [:cat [:and ::run-request [:map [::record? {:optional true} :boolean]]]]
                      ::run-result]}
  [{::keys [vars ns nses all? failed-only? tag record?] :as req}]
  (let [resolved (cond
                   vars         vars
                   ns           (vars-in-ns ns)
                   nses         (into [] (mapcat vars-in-ns) nses)
                   all?         (seon.test.suite/all-test-syms)
                   failed-only? (vars-currently-failing)
                   tag          (vars-with-tag tag))
        result   (await (run-vars-async {::vars resolved}))]
    (if record?
      (let [run-id    (stash-run! {::run-result result})
            tx-report (await (record-run! {::run-result result ::run-id run-id}))]
        (assoc result ::run-id run-id))
      result)))

;; Convenience wrappers — all funnel through run!. None of them have
;; their own logic; they're just typed entry points.
(defn ^:async run-ns!     [{::keys [ns]}]   (run! {::ns ns ::record? true}))
(defn ^:async run-all!    [_]               (run! {::all? true ::record? true}))
(defn ^:async run-failed! [_]               (run! {::failed-only? true ::record? true}))

;; Read most recent stashed run for the calling agent.
(defn last-result
  "Return the {::run-result ::run-id} for the most recent recorded
   run, by querying the DB for max :seon.test/last-run-id."
  {:malli/schema [:=> [:cat [:map]] [:maybe ::run-result]]}
  [_]
  (when-let [run-id (last-run-id-from-db)]
    {::run-id run-id ::run-result (fetch-run run-id)}))
```

The existing `run-vars`, `stash-run!`, `record-run!`, `run-and-record!`
stay — they're the building blocks `run!` composes. Nothing breaks; we
add a layer.

**Helper fns** that the new code needs:

```clojure
(defn vars-in-ns [ns-sym]
  ;; Walk cljs.analyzer.api/ns-interns; filter by :test meta; return FQ syms.
  ...)

(defn vars-currently-failing []
  ;; Single query: join the run-result projection against the unified
  ;; :seon.fn registry so de-tested syms drop out (see §4.O).
  ;; :find ?sym :where
  ;;   [?fn :seon.fn/sym ?sym]
  ;;   [?fn :seon.fn/test? true]              ; join-filter — GC built-in
  ;;   [?r  :seon.test/sym ?sym]
  ;;   [?r  :seon.test/last-failed-at ?f]
  ;;   (or [(missing? $ ?r :seon.test/last-passed-at)]
  ;;       [(and [?r :seon.test/last-passed-at ?p] [(< ?p ?f)])])
  ...)
```

### F. The `(user/run-tests)` equivalent

JVM has `(user/run-tests 'ns)` at `src/seon/dev/test.clj:108`. For
CLJS, the equivalent lives **inside the pod**, called over MCP:

```clojure
;; From shadow nREPL :7889 or mcp__seon_cljs__eval:
(seon.test.runner/run! {::ns 'seon.db-test ::record? true})
;; Pretty-printed for humans:
(seon.test.render/summary (seon.test.runner/run! {::ns 'seon.db-test}))
```

A tiny `seon.client` REPL helper `(seon.test/run)` (no args = `run-all!`,
one arg = ns) is fine sugar but **not the API**. The API is `run!`.

**CLI shim — `bin/seon test pod`:**

Add a `bin/seon test pod [ns]` subcommand. Implementation: send the
following to the pod's shadow nREPL :7889 (or a dedicated WIT command
once Phase 3 lands):

```clojure
(seon.test.runner/run!
  {::ns 'seon.db-test
   ::record? true})
```

Wait for the reply, print `seon.test.render/cli-summary` of the result,
exit 0/1 on `(zero? (+ fail error))`.

**Concrete impl: Babashka.** `bb` is the lightest, most idiomatic
option — one-file script, ~50ms startup, `babashka.nrepl-client`
ships built in. Rejected alternatives: Node `nrepl-client` npm
package (adds a JS dep tree to a Clojure pipeline); `clojure -X`
(multi-second JVM startup, completely wrong for a CLI shim).

`bin/seon-test-pod` (new, called by `bin/seon test pod`):

```clojure
#!/usr/bin/env bb
;; Run CLJS tests via the shadow nREPL on :7889. Argv: optional ns sym.
(require '[babashka.nrepl-client :as nrepl]
         '[clojure.edn :as edn])

(let [ns-arg  (first *command-line-args*)
      req     (if ns-arg
                (format "(seon.test.runner/run! {:seon.test.runner/ns '%s :seon.test.runner/record? true})" ns-arg)
                "(seon.test.runner/run! {:seon.test.runner/all? true :seon.test.runner/record? true})")
      conn    (nrepl/client {:host "127.0.0.1" :port 7889})
      reply   (nrepl/eval conn req)
      result  (edn/read-string (:value reply))
      {:keys [pass fail error]} (:seon.test.runner/summary result)]
  (println (format "%d pass, %d fail, %d error" pass fail error))
  (when (seq (:seon.test.runner/events result))
    ;; render fail/error events via seon.test.render on the pod side, eventually
    (doseq [ev (filter #(#{:fail :error} (:type %)) (:seon.test.runner/events result))]
      (println " " (:sym ev) "—" (or (:message ev) (:actual ev)))))
  (System/exit (if (zero? (+ fail error)) 0 1)))
```

Wire in `bin/seon` after the `process_command` case statement:

```bash
"test")
  case "$2" in
    pod) shift 2; exec bin/seon-test-pod "$@" ;;
    *)   echo "usage: bin/seon test pod [ns]" >&2; exit 2 ;;
  esac
  ;;
```

The pod must already be running (`bin/seon start pod`) — the shim
fails with a clear error if the nREPL connection refuses, rather
than auto-starting (see §8 decision).

### G. Auto-trigger from eval-batch (D4 of the spec)

When an agent eval lands a `(defn foo …)` that has a test
counterpart, fire the test.

**Mechanism:** a `seon.runtime` handler keyed on the program-graph tx.

```clojure
;; src/seon/runtime/test_autorun.cljs (NEW)

(handler/register!
  {:seon.handler/key   ::autorun-on-fn-redef
   :seon.handler/match {:seon.db/attr :seon.fn/source}     ; any fn redef
   :seon.handler/fn    autorun-on-fn-redef})

(defn ^:async autorun-on-fn-redef
  "When a :seon.fn entity gets a new :source, look up which tests
   exercise it — either by sibling-name convention (`foo-test`) or
   by `:seon.fn/test-targets` containing the redefined sym — and
   queue a run.

   Returns {:tx [] :effects [{:effect/type :run-tests
                              :seon.test/vars [<syms>]}]}."
  [{:seon.db/keys [tx-report db]}]
  (let [redefined-syms (->> (:tx-data tx-report)
                            (filter #(= :seon.fn/source (:a %)))
                            (map :e) distinct
                            (map #(:seon.fn/sym (db/pull {::db/ref %
                                                          ::db/pull-pattern [:seon.fn/sym]}))))
        sibling-syms   (map #(symbol (str % "-test")) redefined-syms)
        ;; Both sibling and target queries hit the same :seon.fn shape.
        existing       (db/q {::db/query '[:find [?sym ...]
                                           :in $ [?candidate ...]
                                           :where
                                           [?e :seon.fn/sym ?sym]
                                           [?e :seon.fn/test? true]
                                           [(= ?sym ?candidate)]]
                              ::db/args [sibling-syms]})
        targeted       (db/q {::db/query '[:find [?sym ...]
                                           :in $ [?changed ...]
                                           :where
                                           [?e :seon.fn/test? true]
                                           [?e :seon.fn/test-targets ?changed]
                                           [?e :seon.fn/sym ?sym]]
                              ::db/args [redefined-syms]})]
    (when-let [vs (seq (distinct (concat existing targeted)))]
      {:tx []
       :effects [{:effect/type :run-tests
                  :seon.test/vars (vec vs)}]})))
```

The `:run-tests` effect interpreter is one line:

```clojure
(defmethod run-effect! :run-tests [{:seon.test/keys [vars]}]
  (run! {::vars vars ::record? true}))
```

**Test counterpart resolution:**

- Convention: `foo` ↔ `foo-test` in same ns. (CLJS prevalent pattern,
  matches `src/seon/db_test.cljs` shape.)
- Override: a `:seon.fn` row with `:seon.fn/test? true` may name its
  targets explicitly via `:seon.fn/test-targets [:vector :symbol]`
  (agent-authored tests that don't follow the sibling convention).

When tests pass, `:seon.test/last-passed-at` updates. When tests fail,
`:seon.test/last-failed-at` and `:seon.test/last-failure-summary` land.
The warnings tile (read-only Datalog) shows failures automatically —
no notification, no acknowledgement. **Reactive context principle.**

### H. The shared interface — Malli sketch

```clojure
;; --- inputs ---
(schema/register! ::vars [:vector :symbol])
(schema/register! ::ns   :symbol)
(schema/register! ::nses [:vector :symbol])
(schema/register! ::all? :boolean)
(schema/register! ::failed-only? :boolean)
(schema/register! ::tag :keyword)
(schema/register! ::record? :boolean)

(schema/register! ::run-request
  [:and
   [:map
    [::vars         {:optional true} ::vars]
    [::ns           {:optional true} ::ns]
    [::nses         {:optional true} ::nses]
    [::all?         {:optional true} ::all?]
    [::failed-only? {:optional true} ::failed-only?]
    [::tag          {:optional true} ::tag]
    [::record?      {:optional true} ::record?]
    [::conn         {:optional true} :seon.db/ref]]
   [:fn {:error/message "exactly one selector key"}
    #(= 1 (count (keep % [::vars ::ns ::nses ::all? ::failed-only? ::tag])))]])

;; --- outputs ---  CANONICAL — single source of truth for ::run-result.
;; §4.E references this; the existing inline draft in §4.E is gone.
(schema/register! ::run-result
  [:map
   [::events         [:vector ::test-event]]
   [::summary        ::summary]
   [::run-id         {:optional true} ::run-id]
   [::selected-vars  {:optional true} ::vars]            ; what the selector resolved to
   [::recorded?      {:optional true} :boolean]          ; did record-run! land?
   [::recorded-syms  {:optional true} [:vector :string]] ; which syms got projections written
   [::trigger        {:optional true} ::trigger]])       ; how the run was kicked off

(schema/register! ::last-result-response
  [:maybe [:map [::run-id ::run-id] [::run-result ::run-result]]])
```

Every entrypoint is `[:=> [:cat ::run-request] ::run-result]` (or the
`last-result` variant). Validation is automatic via instrumentation.
The selector validator catches "you said `::all?` and `::ns` —
which?" at the boundary.

### I. Reporter / output

Pure data is already the bottom layer. Add a thin formatter:

```clojure
;; src/seon/test/render.cljs (NEW)

(defn summary
  "Plain-text REPL summary. Used by humans + the CLI shim."
  {:malli/schema [:=> [:cat [:map [::run-result ::run-result]]] :string]}
  [{::keys [run-result]}]
  ...)

(defn ::tile-hiccup
  "Hiccup for the recent-evals tile — feeds `seon.render/html-render`."
  ...)
```

`record-run!` already lands the projection in the DB. The
`seon.agent.view` / agent-view ctx render reads it via Datalog and
emits the tile. No new tile mechanism; it's a normal section function
per the reactive-context principle.

### J. Gaps & risks (concrete answers)

- **Self-hosted cljs.test gaps.** Confirmed `deftest`/`is`/`testing`/
  `async` work in the bootstrap bundle (`shadow-cljs.edn:228, 238`;
  cited in
  `docs/prds/agent-runtime/platform.md` Phase 1 row "cljs.test
  self-hosted"). `use-fixtures` is used in `src/seon/db_test.cljs:36`
  and is expected to work the same way; verify under self-host by
  running that test through `run!` post-move.
- **`with-redefs` self-host status.** Uncertain — needs probe. CLJS
  `with-redefs` is a macro that swaps var roots; self-hosted CLJS
  doesn't have JVM-style `Var` instances, so the JIT-compiled var
  table behavior may differ. **Action:** write a 3-line probe test
  before relying on `with-redefs` in any new test.
- **LMDB tests.** Pod has only `:memory` (no LMDB build for CLJS
  today — LMDB is JVM side). LMDB-specific tests stay on the JVM
  side under `test/seon/db/`.
- **Generative testing (test.check).** `defspec` registers a
  `deftest`-shaped var; it'll work as soon as
  `cljs.test.check.clojure-test` (or the equivalent) is in the
  bootstrap bundle. **Action:** add `clojure.test.check` to
  `shadow-cljs.edn:228` `:entries` and re-verify the
  `src/seon/db_test.cljs:407-433` `prop-*` deftests work — they're
  currently `dotimes`-and-`rand`-based hand-rolled properties, which
  works under self-host but loses test.check shrinking.

### K. Malli instrumentation during tests

The pod already installs Malli instrumentation at boot via
`seon.instrument/install!` (`src/seon/client.cljs:656-666`). The
machinery is `seon.instrument` (`.cljc`, both sides) — its docstring
explains the self-host gotcha: `malli.instrument/collect!` is JVM-only
because it reads `.cljc` files off disk, so the CLJS path uses a
`collect!` macro that expands at compile time, populates an atom, and
hands the registry to `mi/instrument!` at runtime. `malli.instrument`
itself **is available** in self-hosted CLJS — it's bundled in the
bootstrap entries (`src/seon/client.cljs:34`); only its file-reading
`collect!` is gone, and we replace that with the macro.

**Recommendation: instrumentation is ON during tests, period.** Three
reasons:

1. **Tests are the validation cycle.** If a test exercises a fn whose
   `:malli/schema` is wrong, we WANT the instrumentation error to
   fire there, not in production. The error envelope already
   distinguishes input/output/arity failures
   (`src/seon/error/instrument.cljc`), and the test runner already
   captures thrown errors into `::run-result` events — the
   error-envelope shape becomes the test failure.
2. **Per-Phase-5 reactive loop, the test IS the contract check.**
   When the agent redefines `foo` and `foo-test` fires, the test
   body calls `(foo …)` and instrumentation validates the new
   shape against the registered `:malli/schema`. If the schema
   itself was wrong, the test fails with a structural error rather
   than an obscure NPE downstream.
3. **CLJ side does the same thing.** `:seon.dev/instrumentation` is
   an Integrant component that survives `(user/reset)`; CLAUDE.md's
   "Function Instrumentation" section explicitly says "there is no
   off mode." CLJS pod should match.

**Gap (acceptable risk):** the bootstrap `collect!` macro fires at
compile time over source paths under `src/`. Agent-defined fns that
land via `eval-batch!` after boot are NOT in that registry —
`malli.instrument/instrument!` only wraps what `collect!` saw. For
agent-defined fns to be instrumented, `seon.eval/eval-batch!` (or
the analyzer-tee path right after it) must call
`(mi/-register! sym schema)` + `(mi/instrument! {:filters …})`
narrowed to that one sym. **Action:** add a one-line hook in
`build-tee-entities` (`src/seon/eval.cljs:648`) — for each newly-
defined fn with `:malli/schema` meta, register + instrument that
sym. Idempotent (`mi/instrument!` replaces the wrapper). This is the
real Phase 5 prerequisite for "agent-defined tests catch
agent-defined fn schema drift."

**Per-test override.** Some tests genuinely need to call a fn with
bad inputs to assert the error envelope (e.g.
`test/seon/error/instrument_test.cljc` if it existed). Pattern:

```clojure
(deftest rejects-bad-input
  (fixtures/with-instrumentation-disabled-for ['seon.foo/bar]
    (fn []
      (is (thrown-with-msg? :seon.error.kind/malli-instrument-input
            #"::name must be string"
            (foo/bar {::name 42}))))))
```

Implementation: temporarily `mi/unstrument!` for those syms, call
thunk, re-instrument in `finally`. Wraps the existing malli API; no
new mechanism.

**Rejected:** running tests with instrumentation OFF and a separate
"instrumented run" mode. That fragments the failure surface — the
test that passes uninstrumented but fails instrumented is a real
bug, and seeing it only in a special mode means agents skip the
mode.

### L. Reactive testing — the headline mechanism

The user's framing: **`run-all!` is what you do once, before declaring
victory. Everything else is reactive.** When code changes, tests that
exercise that code re-run automatically. Same loop for the human in
the editor and the agent in eval-batch.

Prior art surveyed:

- **kaocha-watch** (`reference-code/kaocha/src/kaocha/watch.clj`) —
  Hawk/beholder filesystem watcher + `tools.namespace.tracker` for
  ns-level dep tracking. Re-runs every test in a changed ns. Coarse
  (ns-level, not fn-level). We can do better.
- **shadow-cljs `:autorun`** — same coarse model, ties to shadow's
  rebuild pipeline. Doesn't help agent-defined tests (they never go
  through shadow).
- **lein-test-refresh** — same model again. Watcher + ns-level
  affected set.
- **Jest `--watch`** — uses the bundler's module-dep graph. Closer to
  what we want, but JS-only and not introspectable from Clojure.

**The Seon-native answer is better than any of them, because we
already own the program graph.** The analyzer
(`src/seon/analyzer_info.cljs`) writes `:seon.fn/source` per fn into
the DB; every fn redefinition produces a tx-data datom on
`:seon.fn/source`. The tx-listener (the same one that drives the
reactive context sections, per CLAUDE.md) sees those datoms in real
time. No filesystem watcher, no separate tracker — the DB tx-log IS
the change stream.

**The current edge model:** the analyzer indexes `:requires` /
`:uses` at the namespace level (`analyzer_info.cljs:128-141`
`ns-deps`). It does NOT yet write per-fn call edges (`:seon.fn/calls`
or `:seon.fn/callees`). Worth noting honestly:

- The JVM `seon.graph.ingest` namespace does extract per-fn call
  edges (Kondo-driven; see `src/seon/graph/ingest.clj`). The CLJS
  side does not yet have this.
- Until CLJS gets a per-fn call graph, the affected-tests query
  degrades to **ns-level granularity**: redefining any fn in
  `seon.foo` triggers every test in `seon.foo-test` (and any test
  ns whose `:requires` includes `seon.foo`). That's already
  significantly better than kaocha-watch because it's tx-triggered
  not file-saved-triggered, and because it includes agent-defined
  tests living in the DB.

**The affected-tests query (ns-level, Phase 2):**

> **PENDING — see `datahike-query-capabilities-2026-05-25.md`.** The
> `transitive-dependents` call below depends on whether Datahike
> supports recursive Datalog rules natively. A parallel research
> agent is currently auditing this. Two possible shapes:
>
> 1. **Native recursive rule** (if supported) — one query expresses
>    "every ns that transitively requires X" via a rule clause.
> 2. **Iterated joins in CLJS** (fallback) — walk `:seon.ns/requires`
>    in a loop, accumulating a fixed-point set, calling `db/q` each
>    iteration.
>
> The sketch below is structural — the `(transitive-dependents …)`
> call is a TODO until the audit lands. **Do NOT implement Phase 2
> step 10 until the audit returns** — the rule shape changes the
> ns and the call signature.

```clojure
(defn affected-test-syms-for-tx
  "Given a tx-report from the program-graph, return the set of
   test syms whose ns directly or transitively requires any ns
   whose fns were redefined.

   Unified discovery: every test is a :seon.fn row with
   :seon.fn/test? true. Sibling convention (`foo-test`) plus
   explicit :seon.fn/test-targets cover agent-authored tests."
  [{:keys [tx-data db]}]
  (let [changed-fn-eids  (->> tx-data
                              (filter #(= :seon.fn/source (:a %)))
                              (map :e) distinct)
        changed-ns-syms  (db/q {::db/query
                                '[:find [?ns-name ...]
                                  :in $ [?fn-eid ...]
                                  :where
                                  [?fn-eid :seon.fn/ns ?ns]
                                  [?ns :seon.ns/name ?ns-name]]
                                ::db/args [changed-fn-eids]})
        changed-syms     (db/q {::db/query
                                '[:find [?sym ...]
                                  :in $ [?fn-eid ...]
                                  :where [?fn-eid :seon.fn/sym ?sym]]
                                ::db/args [changed-fn-eids]})
        ;; PENDING: shape depends on datahike-query-capabilities audit.
        dependent-nses   (transitive-dependents db changed-ns-syms)
        all-affected-ns  (into (set dependent-nses) changed-ns-syms)]
    (distinct
      (concat
        ;; tests in affected nses — ONE query, unified :seon.fn shape
        (db/q {::db/query '[:find [?sym ...]
                            :in $ [?ns-name ...]
                            :where
                            [?fn :seon.fn/ns ?ns]
                            [?ns :seon.ns/name ?ns-name]
                            [?fn :seon.fn/sym ?sym]
                            [?fn :seon.fn/test? true]]
               ::db/args [all-affected-ns]})
        ;; tests with explicit targets pointing at any changed sym —
        ;; still the same :seon.fn shape, just a different filter.
        (db/q {::db/query '[:find [?sym ...]
                            :in $ [?changed ...]
                            :where
                            [?fn :seon.fn/test? true]
                            [?fn :seon.fn/test-targets ?changed]
                            [?fn :seon.fn/sym ?sym]]
               ::db/args [changed-syms]})))))
```

Two new attrs needed for this to work (both registered alongside
existing `:seon.fn/*` attrs in `src/seon/agent.cljs:251-261`):

- `:seon.fn/test?` (boolean) — set by the analyzer-tee when a
  `defn` carries `:test` meta or is a `deftest` expansion.
- `:seon.fn/test-targets` ([:vector :symbol]) — agent-authored
  explicit target overrides; rarely populated in practice
  (most tests rely on the sibling convention).
- `:seon.ns/requires` (vector of ref) — projection of the
  analyzer's `(ns-deps compile-state ns-sym)` (already implemented;
  just persist on each `:seon.fn` write or on first
  `eval-batch!`-derived ns-entity write).

**The on-tx handler (the spine):**

```clojure
;; src/seon/runtime/test_autorun.cljs (Phase 5 — promoted)

(handler/register!
  {:seon.handler/name  ::affected-tests
   :seon.handler/agent nil                                 ; substrate handler
   :seon.handler/match {:seon.handler.match/attr :seon.fn/source}
   :seon.handler/fn    `affected-tests-handler})

(defn ^:async affected-tests-handler
  "Reactive entrypoint. Runs in the tx-listener; computes affected
   tests; returns an effects map. The dispatcher already runs effects
   on the agent loop; we don't need our own scheduler."
  [{:seon.db/keys [tx-report] :as ctx}]
  (let [affected (affected-test-syms-for-tx tx-report)]
    (if (seq affected)
      {:effects [{:effect/type :run-tests
                  :seon.test/vars (vec affected)
                  :seon.test/trigger ::on-fn-redef}]}
      {})))

(defmethod run-effect! :run-tests
  [{:seon.test/keys [vars trigger]}]
  ;; Fire-and-forget; the run records its own results into the DB,
  ;; which (recursive principle) triggers any further reactive section.
  (await (runner/run! {::runner/vars vars
                       ::runner/record? true
                       ::runner/trigger trigger})))
```

**Why this is reactive-context-correct** (CLAUDE.md
"Reactive context — derived by default"):

- Nothing is stored. The handler doesn't write a "tests to run"
  queue. It computes the affected set from the current tx and
  returns an effect.
- The output (`:seon.test/last-failed-at` projection) is itself a
  derived-section input — the warnings tile queries
  `[?e :seon.test/last-failed-at ?f] [(> ?f cutoff)]` and surfaces
  the failure. When the agent fixes the fn, the next tx re-fires
  the handler, the test re-runs, `:seon.test/last-passed-at`
  updates and is now `> last-failed-at`, and the section returns
  empty. **Self-healing.**
- One mechanism, many uses: editor-save-triggered reload also goes
  through the analyzer-tee path (the dev hook reloads then
  re-analyzes), so the same handler fires for the human user. No
  editor plugin.

**Edge cases — known limitations:**

| Case | Behaviour | Mitigation |
|---|---|---|
| Test defined BEFORE the fn it tests | The `:seon.test/targets` resolves to no eid yet; handler skips. When the fn lands later, its `:seon.fn/source` tx fires the handler, which now finds the test → runs it. | Lazy resolution via sym, not eid. |
| Test itself is broken (compile error) | `eval-batch!` already captures the error in the error envelope; no `:seon.fn/source` lands; nothing triggers. Agent sees the eval error in their context. | None needed — fails closed. |
| Test redefinition (agent changes the test) | The new `:seon.fn/source` for the test sym IS a tx on `:seon.fn/source` — handler fires, finds the test in its OWN affected set, runs it once. | Trivial; intended. |
| Multimethod / protocol dispatch the analyzer can't see | Ns-level granularity already covers this (any change in the ns runs all tests in dependents). When per-fn edges arrive, this is the one place that still NEEDS the ns-level fallback. | Document; keep ns-level as the floor. |
| Cycle: test fixes fn, fn-tx triggers test, test passes, no further fires | Correct termination — `:seon.fn/source` tx doesn't fire if value unchanged. | None. |
| Storm: agent eval-batch redefines 50 fns at once | Handler runs once per tx-listener fire (one tx-report), computes one affected set, runs the union. O(1) handler fires per batch. | Already correct — tx-listener is per-tx, not per-datom. |
| Test runs forever / infinite loop | Per-test timeout (mirror the JVM `seon.eval/budget` pattern) wraps each `(fn)` call in `run-vars`. Default 5s; override via `::run-request` `::timeout-ms`. | Phase 5 ships with default timeout. |
| Test transacts to shared `*conn*` and pollutes other tests | `with-test-conn` (§4.C) gives each test a fresh in-memory conn. | Default isolation. |

### M. Shared interface — revised

The `run!` signature gains nothing; the `::trigger` key is an
informational annotation, not a mode switch. **The reactive daemon
is NOT a flag on `run!`; it's a separate handler that calls
`run!`.** Justification: keeping `run!` pure (request → result) means
the same fn serves the manual REPL caller, the CLI shim, the
auto-trigger handler, AND any future caller (e.g. a remote-trigger
schedule). The reactive loop is a *consumer* of `run!`, not a mode
of it.

Added field on `::run-request`:

```clojure
(schema/register! ::trigger
  [:enum ::manual ::on-fn-redef ::pre-victory ::cli])

(schema/register! ::run-request
  [:and
   [:map
    [::vars         {:optional true} ::vars]
    [::ns           {:optional true} ::ns]
    [::nses         {:optional true} ::nses]
    [::all?         {:optional true} ::all?]
    [::failed-only? {:optional true} ::failed-only?]
    [::tag          {:optional true} ::tag]
    [::record?      {:optional true} ::record?]
    [::trigger      {:optional true} ::trigger]          ; ← new
    [::timeout-ms   {:optional true} :int]               ; ← new
    [::conn         {:optional true} :seon.db/ref]]
   ...])
```

`::trigger` lands on the `:seon.test/last-*` projection so the
warnings tile can distinguish "I asked for this and it failed"
from "the reactive loop noticed it failed."

### N. Pre-Phase-1 probes — verify before building

Four assumptions the plan rests on. Each MUST pass against the
running pod before Phase 1 work starts. Probes run via shadow nREPL
:7889 (`mcp__seon_cljs__eval`); each lists the exact form, the
expected shape of a passing result, and the fallback if it fails.

**Probe 1 — `:test` meta survives `deftest` expansion under bootstrap.**

```clojure
(do
  (require '[cljs.test])
  (cljs.test/deftest probe-1-test (cljs.test/is true))
  (-> (cljs.analyzer.api/ns-interns 'cljs.user)
      (get 'probe-1-test)
      :meta
      :test))
```

- **Expected:** truthy (the gensym fn the `deftest` macro stores in
  `:test` meta).
- **If false:** the unified `:seon.fn/test?` flag in §4.A cannot
  be derived from the analyzer alone. Fallback: have the
  analyzer-tee inspect the raw form pre-expansion to detect
  `deftest` head, OR walk `cljs.test/get-current-env`'s registered
  test list. Pick fallback before Phase 1.

**Probe 2 — `with-redefs` works under self-host.**

```clojure
(do
  (defn probe-2 [] :original)
  (let [seen (atom nil)]
    (with-redefs [probe-2 (fn [] :redef)]
      (reset! seen (probe-2)))
    {:during @seen :after (probe-2)}))
```

- **Expected:** `{:during :redef, :after :original}`.
- **If `:during` is `:original`:** self-host `with-redefs` doesn't
  swap the globalThis fn binding. Fallback: dynvar-based stubbing
  helper in `seon.test.fixtures` that wraps targeted fns in
  `^:dynamic` shims at test time. Document in §4.C.

**Probe 3 — `use-fixtures :each` ordering with `(async done …)`.**

```clojure
(do
  (require '[cljs.test :as t])
  (def order (atom []))
  (t/use-fixtures :each
    {:before #(swap! order conj :before)
     :after  #(swap! order conj :after)})
  (t/deftest probe-3-test
    (t/async done
      (js/setTimeout
        (fn [] (swap! order conj :body) (done))
        10)))
  (t/run-tests)
  ;; after run completes:
  @order)
```

- **Expected:** `[:before :body :after]` (in that order).
- **If `:after` lands before `:body`:** cljs.test isn't awaiting the
  async marker before firing `:each` teardown. Fallback: bypass
  `:each` for async tests and put setup/teardown inside the
  Promise chain — document the constraint in §4.C.

**Probe 4 — `mi/instrument!` on a sym defined AFTER bootstrap.**

```clojure
(do
  (require '[malli.instrument :as mi]
           '[malli.core :as m]
           '[seon.schema :as schema])
  (schema/register! ::probe-4-in [:map [::probe-4/n :int]])
  (schema/register! ::probe-4-out [:map [::probe-4/n2 :int]])
  ;; Define AFTER boot — this is the path agent-eval'd fns take.
  (defn probe-4
    {:malli/schema [:=> [:cat ::probe-4-in] ::probe-4-out]}
    [{::probe-4/keys [n]}] {::probe-4/n2 (* 2 n)})
  (mi/-register! `probe-4 (m/schema [:=> [:cat ::probe-4-in] ::probe-4-out]))
  (mi/instrument! {:filters [(mi/-filter-var #(= % #'probe-4))]})
  ;; Now call it WRONG and check we get an instrumentation error:
  (try (probe-4 {::probe-4/n "not-an-int"})
       :no-throw
       (catch :default e (-> e ex-data :type))))
```

- **Expected:** a Malli instrumentation error type (the exact
  keyword depends on `seon.error.instrument` envelope shape —
  expect something like `:malli/explain-input` or our
  `:seon.error.kind/malli-instrument-input`).
- **If `:no-throw`:** instrument! doesn't wrap fns defined post-
  bootstrap; the §4.K Phase 2 hook is unreachable. Fallback: hand-
  wrap the fn at register time with `m/-instrument` directly,
  storing the wrapper in globalThis under the same munged path.
  This is significantly more invasive — confirm before Phase 2.

If any probe fails, update the plan BEFORE writing code; the
fallback shape may ripple through multiple phases.

### O. Test lifecycle / garbage collection

When an agent redefines `foo-test` such that it stops being a test
(e.g. removes `deftest` and replaces with a plain `defn`, or removes
the `:test` meta), the row at `:seon.test/sym = "…/foo-test"` with
`:seon.test/last-failed-at` would otherwise stay in the DB forever,
keeping the warnings tile noisy.

**Recommended mechanism: derive-not-store via join-filter.** The
warnings query (and `vars-currently-failing` in §4.E) joins the
`:seon.test/*` projection against the unified `:seon.fn/test? true`
registry. If a sym's `:seon.fn/test?` becomes false (or absent),
the join drops the row — the projection still sits in the DB, but
it's invisible to every query that matters.

```clojure
;; warnings tile query, post-GC:
'[:find ?sym ?summary
  :where
  [?fn :seon.fn/sym ?sym]
  [?fn :seon.fn/test? true]              ; join-filter — silently GCs
  [?r  :seon.test/sym ?sym]
  [?r  :seon.test/last-failed-at ?f]
  [?r  :seon.test/last-failure-summary ?summary]
  (or [(missing? $ ?r :seon.test/last-passed-at)]
      [(and [?r :seon.test/last-passed-at ?p] [(< ?p ?f)])])]
```

This matches the reactive-context principle exactly — no
acknowledgement, no retraction tx, no notification queue. When
the underlying truth (`:seon.fn/test? true`) goes away, the
derived view vanishes.

**Rejected:** analyzer-tee emits explicit retractions on
`:seon.test/*` when it sees `:seon.fn/test?` transition true → false.
Adds write amplification and a second mechanism for the same goal;
the join-filter is free.

A periodic compaction pass MAY later retract orphan
`:seon.test/*` rows for disk-space reasons — but that's a Phase 8+
concern, not a correctness issue.

## 5. Shared interface spec

**Canonical schema lives here** (§4.E and §4.H reference back to this):

```clojure
;; ::run-request — selector + options
(schema/register! ::run-request
  [:and
   [:map
    [::vars         {:optional true} ::vars]
    [::ns           {:optional true} ::ns]
    [::nses         {:optional true} ::nses]
    [::all?         {:optional true} ::all?]
    [::failed-only? {:optional true} ::failed-only?]
    [::tag          {:optional true} ::tag]
    [::record?      {:optional true} ::record?]
    [::trigger      {:optional true} ::trigger]
    [::timeout-ms   {:optional true} :int]
    [::conn         {:optional true} :seon.db/ref]]
   [:fn {:error/message "exactly one selector key required"}
    #(= 1 (count (keep % [::vars ::ns ::nses ::all? ::failed-only? ::tag])))]])

;; ::run-result — every entrypoint returns this shape.
(schema/register! ::run-result
  [:map
   [::events         [:vector ::test-event]]
   [::summary        ::summary]
   [::run-id         {:optional true} ::run-id]
   [::selected-vars  {:optional true} ::vars]
   [::recorded?      {:optional true} :boolean]
   [::recorded-syms  {:optional true} [:vector :string]]
   [::trigger        {:optional true} ::trigger]])
```

The five entrypoints, all in `src/seon/test/runner.cljs`:

| Fn | Signature | Notes |
|---|---|---|
| `run!` | `::run-request → ::run-result` | Universal; the others are sugar. |
| `run-ns!` | `[:map [::ns :symbol]] → ::run-result` | `(run! {::ns sym ::record? true})` |
| `run-all!` | `[:map] → ::run-result` | `(run! {::all? true ::record? true})` |
| `run-failed!` | `[:map] → ::run-result` | `(run! {::failed-only? true ::record? true})` |
| `last-result` | `[:map] → ::last-result-response` | DB lookup + globalThis fetch |

Plus the existing primitives (`run-vars`, `stash-run!`, `record-run!`,
`run-and-record!`) — unchanged in API, lifted to `^:async` for the
Promise-aware driver.

## 6. Implementation phases

> **Revised 2026-05-25:** the original sequencing (REPL runner →
> suite → fixtures → failed-only → auto-trigger → test.check → loop)
> pushed the reactive loop to Phase 5 because the runner was deemed
> the prerequisite. That's correct (the daemon literally calls
> `run!`), but the gap between "ships standalone value" Phase 1 and
> "the spine works end-to-end" Phase 5 is too wide given that the
> spine is now the headline feature. **New sequencing: get the
> reactive loop working for ONE test as early as possible (Phase 2),
> then expand both selectors and discovery around it.** Phase 1
> stays as a single-var runner because the daemon needs SOMETHING to
> call.

### Phase 1 — Single-var runner + Promise-aware driver (1-2 hr)

Smallest thing that makes humans productive:

1. Add `vars-in-ns` helper using `cljs.analyzer.api/ns-interns`.
2. Add `run-ns!` and `run!` (just `::vars` + `::ns` selectors). No
   `::all?`, no `::failed-only?` yet.
3. Promote `run-vars` to `^:async` with Promise-await on test-fn
   return value.
4. Move `src/seon/db_test.cljs` → `test/seon/db_test.cljs`.
5. Move `src/seon/render_test.cljs` → `test/seon/render_test.cljs`.
6. **Acceptance:** from shadow nREPL :7889,
   `(seon.test.runner/run-ns! {::ns 'seon.db-test ::record? true})`
   returns `::run-result` with all assertions captured; a follow-up
   `(seon.test.runner/last-result {})` reads it back.

### Phase 2 — Reactive spine, end-to-end for ONE test (1 day)

The whole point of the project. Ship this before run-all!, because
this is the feature.

7. Add `:seon.fn/test?` and `:seon.fn/test-targets` schema attrs
   (in `src/seon/agent.cljs:251+`).
8. Extend the analyzer-tee path (`src/seon/eval.cljs:648`,
   `build-tee-entities`) to set `:seon.fn/test?` true for any
   `defn` whose name ends `-test` or whose meta carries `:test`.
9. **Instrument agent-defined fns at write time.** Same
   `build-tee-entities` pass: for each newly-defined fn whose meta
   carries `:malli/schema`, call `(mi/-register! sym schema)` then
   `(mi/instrument! {:filters [(mi/-filter-var #(= % sym))]})`.
   This is the §4.K hook — load-bearing for the Phase 2 acceptance
   test, because schema-drift detection only fires if the redefined
   `foo` is actually wrapped. Idempotent (`mi/instrument!` replaces
   the wrapper). **Pre-req:** Probe 4 in §4.N must pass; if it
   doesn't, use the documented fallback before continuing.
10. Add `:seon.ns/requires` attr + write it from `analyzer-info/ns-deps`
    on each fn-defining eval-batch (or one shot on first sighting of
    that ns).
11. Implement `affected-test-syms-for-tx` (§4.L). Ns-level granularity
    is fine for Phase 2.
    **PENDING: `transitive-dependents` rule shape — see
    `datahike-query-capabilities-2026-05-25.md`.** Do not start this
    step until the audit lands.
12. Add `src/seon/runtime/test_autorun.cljs` with the
    `::affected-tests` handler. Wire `:run-tests` effect interpreter
    to call `runner/run!` from Phase 1.
13. **Acceptance:** in shadow nREPL,
    `(seon.eval/eval-batch! {... :forms ["(defn foo [x] (* x 2))"
                                          "(deftest foo-test (is (= 4 (foo 2))))"]})`
    lands both, then redefining `foo` to `(* x 3)` causes
    `:seon.test/last-failed-at` to become non-nil on `foo-test`
    WITHOUT any explicit `run!` call. Verified by Datalog query.
    Additional acceptance: redefining `foo`'s `:malli/schema` to be
    inconsistent with the fn body produces an instrumentation-error
    test failure (not a generic NPE), proving step 9 landed.

### Phase 3 — Suite + all-runner + CLI (half day)

The "before-victory" escape hatch.

14. Add `seon.test.suite` ns with hand-maintained requires.
15. Wire `seon.client/-main` to `(:require [seon.test.suite])`.
16. Implement `all-test-syms` via the unified `:seon.fn/test? true`
    Datalog query (§4.A).
17. Implement `run-all!`.
18. Add `bin/seon test pod` shim — Babashka script per §4.F.
19. **Acceptance:** `bin/seon test pod` runs the whole suite
    cold-start, exits 0/1 correctly, prints a readable summary.

### Phase 4 — Fixtures + render (half day)

20. Add `seon.test.fixtures/empty-db` + `with-test-conn`.
21. Add `with-test-conn-async` macro.
22. Add `seon.test.render/summary` + `tile-hiccup` (warnings tile
    reads `:seon.test/last-failed-at` joined against
    `:seon.fn/test? true` per §4.O — surfaces reactive failures
    in the agent's next render cycle, and silently GCs de-tested
    syms).
23. Refactor `db_test`'s `fresh-conn` to use the fixture.
24. **Acceptance:** test bodies shrink; agent sees reactive test
    failures in the warnings tile without polling; tests that lose
    their `:seon.fn/test?` flag drop out of the warnings tile.

### Phase 5 — Failed-only (½ day)

> Folded down: the formerly-Phase-5 `:seon.test/test` entity work is
> gone (unified into `:seon.fn` per §4.A). The agent-fn
> instrumentation hook moved to Phase 2 step 9.

25. Implement `vars-currently-failing` via the join-filter Datalog
    query (§4.E, §4.O).
26. Implement `run-failed!`.
27. Extend the Phase-2 handler to also match `:seon.fn/source` tx
    on rows where `:seon.fn/test? true` (so a redefined test
    re-runs itself). This is already covered by the unified handler
    — verify the match shape, no code change expected.
28. **Acceptance:** `(run-failed! {})` runs only the syms with
    `last-failed-at > last-passed-at`; redefining a failing test
    re-triggers it via the existing handler.

### Phase 6 — test.check integration (½ day)

29. Add `clojure.test.check` + `clojure.test.check.clojure-test` to
    `shadow-cljs.edn` `:entries` and `:macros`.
30. Re-author `prop-*` tests in `db_test` to use `defspec`.
31. **Acceptance:** `defspec` yields shrinking on failure.

### Phase 7 — Per-fn call graph + finer-grained affected set (1-2 days)

The Phase 2 affected-set is ns-level. Tighten it.

32. Extend `seon.analyzer-info` with `defs-with-callees` — walk each
    var's `:fn-var`'s body (the analyzer keeps it as IR) and extract
    invoked symbols. JVM has Kondo do this; CLJS we use the analyzer
    directly.
33. Persist `:seon.fn/callees` as a many-ref attr.
34. Tighten `affected-test-syms-for-tx` to walk `:seon.fn/callees`
    transitively rather than `:seon.ns/requires`.
35. **Acceptance:** redefining `seon.foo/bar` runs only the tests in
    `seon.foo-test` that transitively call `bar`, not every test in
    the ns.

### Phase 8 — Loop-strategy substrate (per loop-testing-strategy.md)

36. Build `with-test-pod`, `transact-and-tick!`,
    `tick-to-fixpoint!` on top of the now-stable runner +
    fixtures.
37. Layer 2-5 tests per the strategy doc.

## 7. Agent walkthrough — reactive test failure surfacing

A concrete trace, in the spirit of `loop-walkthrough-2026-05-25.md`,
of the loop that the user specifically asked about: agent defines a
fn + test, agent breaks the fn, agent sees the failure on the next
render WITHOUT calling `run!`.

**Initial state:** pod up, agent loop running, agent's recent-evals
tile empty.

### Step 1 — agent defines the fn and the test

Agent eval-batch:

```clojure
(seon.eval/eval-batch!
  {:seon.agent/id "a1"
   :seon.eval/forms ["(defn foo [x] (* x 2))"
                     "(deftest foo-test (is (= 4 (foo 2))))"]})
```

What happens inside the pod:

1. `cljs.js/eval-str` evaluates both forms in order. `foo` lands on
   globalThis at `seon.user.a1$foo`; `foo-test` at `seon.user.a1$foo_test`.
2. `seon.eval/build-tee-entities` snapshots `defs-since` and produces
   two tx-data maps (one per fn). Both transact in the same tx:

   ```clojure
   [{:seon.fn/sym "seon.user.a1/foo"
     :seon.fn/ns         [:seon.ns/name :seon.user.a1]
     :seon.fn/source     "(defn foo [x] (* x 2))"
     :seon.fn/fn-var?    true
     :seon.fn/test?      false
     :seon.fn/arglists   "([x])"
     :seon.fn/created-at #inst "2026-05-25T18:00:00Z"}
    {:seon.fn/sym "seon.user.a1/foo-test"
     :seon.fn/ns      [:seon.ns/name :seon.user.a1]
     :seon.fn/source  "(deftest foo-test (is (= 4 (foo 2))))"
     :seon.fn/fn-var? true
     :seon.fn/test?   true                                      ; ← Phase 2 attr
     :seon.fn/arglists "([])"
     :seon.fn/created-at #inst "2026-05-25T18:00:00Z"}]
   ```

3. Datahike commits. tx-listener fires. The `::affected-tests`
   handler matches (two datoms on `:seon.fn/source`) and computes
   `affected-test-syms-for-tx`:
   - `changed-fn-eids` = [<foo-eid>, <foo-test-eid>]
   - `changed-ns-syms` = [:seon.user.a1]
   - `dependent-nses` = [:seon.user.a1] (no other ns requires it)
   - Affected tests in those nses = `['seon.user.a1/foo-test]`
4. Handler returns `{:effects [{:effect/type :run-tests
                                :seon.test/vars ['seon.user.a1/foo-test]
                                :seon.test/trigger ::on-fn-redef}]}`.
5. Effect interpreter calls
   `(runner/run! {::vars ['seon.user.a1/foo-test] ::record? true ::trigger ::on-fn-redef})`.
6. `foo-test` runs. `(foo 2)` returns 4. `is` records `:pass`.
   `record-run!` writes:

   ```clojure
   [{:seon.test/sym "seon.user.a1/foo-test"
     :seon.test/last-passed-at #inst "..."
     :seon.test/last-run-id    "<id>"}]
   ```

7. The warnings-tile section function queries
   `[?e :seon.test/sym ?s] [?e :seon.test/last-failed-at ?f] [?e :seon.test/last-passed-at ?p] [(> ?f ?p)]`
   — returns empty. Tile renders nothing. No "you have a failing
   test" warning.

### Step 2 — agent redefines `foo` (breaks it)

```clojure
(seon.eval/eval-batch!
  {:seon.agent/id "a1"
   :seon.eval/forms ["(defn foo [x] (* x 3))"]})
```

1. `cljs.js/eval-str` replaces `seon.user.a1$foo` on globalThis. The
   `foo-test` var on globalThis is unchanged — it still closes over
   the var resolution, so it'll now see the new `foo`.
2. `build-tee-entities` sees `foo`'s digest changed (new `:source`).
   Transacts one datom: `:seon.fn/source` on the existing
   `:seon.user.a1/foo` entity.
3. tx-listener fires. Handler matches. `affected-test-syms-for-tx`:
   - `changed-fn-eids` = [<foo-eid>]
   - `changed-ns-syms` = [:seon.user.a1]
   - Affected tests = `['seon.user.a1/foo-test]`
4. Effect → `runner/run!` → `foo-test` runs. `(foo 2)` returns 6.
   `is` records `:fail` with `expected=4 actual=6`.
5. `record-run!` writes:

   ```clojure
   [{:seon.test/sym "seon.user.a1/foo-test"
     :seon.test/last-failed-at #inst "..."
     :seon.test/last-failure-summary
       "FAIL seon.user.a1/foo-test  expected: 4  actual: 6"
     :seon.test/last-run-id "<new-id>"
     :seon.test/trigger ::on-fn-redef}]
   ```

   Crucially, `:seon.test/last-passed-at` is **not** updated; it
   stays at its earlier value. So `last-failed-at > last-passed-at`.

### Step 3 — agent's next render cycle

The agent loop ticks (whatever woke it up — its own next prompt,
or the kick handler observing the failure tx). `agent-view` runs.
The warnings-tile section function:

```clojure
(defn render-failing-tests
  {:malli/schema [:=> [:cat ::ctx] [:vector :seon.render/hiccup]]}
  [{:seon.agent/keys [id]}]
  (let [rows (db/q '[:find ?sym ?summary ?trigger
                     :where
                     [?e :seon.test/sym ?sym]
                     [?e :seon.test/last-failed-at ?f]
                     [?e :seon.test/last-failure-summary ?summary]
                     [?e :seon.test/trigger ?trigger]
                     (or [(missing? $ ?e :seon.test/last-passed-at)]
                         [(and [?e :seon.test/last-passed-at ?p]
                               [(< ?p ?f)])])])]
    (for [[sym summary trigger] rows]
      [:div.warning
       [:span.dot "●"] " " sym " " summary
       [:span.subtle " (via " (name trigger) ")"]])))
```

Returns one row for `foo-test`. The tile renders:

```
● seon.user.a1/foo-test  FAIL expected: 4 actual: 6 (via on-fn-redef)
```

The next prompt the agent gets includes this tile in its ctx — **the
agent reads its own failure in its next turn's input.** No
notification, no separate "test failed" message, no acknowledgement.
Just derived context.

### Step 4 — agent fixes `foo`

```clojure
(seon.eval/eval-batch!
  {:seon.agent/id "a1"
   :seon.eval/forms ["(defn foo [x] (* x 2))"]})
```

Same loop. Handler fires. `foo-test` runs. Passes.
`:seon.test/last-passed-at` updates to NOW, which is now `>
last-failed-at`. Next render: warnings-tile query returns empty.
Warning disappears. **Self-healing per the reactive-context
principle — no clear, no ack, no notification queue.**

### What the agent's training does NOT need to know

- It does NOT need to call `(run-tests …)`.
- It does NOT need to know the handler exists.
- It DOES need to know "if I see a `:seon.test/last-failed-at`
  warning in my context, it means MY recent code change broke a
  test, and I should fix it." This is one sentence in the agent's
  system prompt, not a tool description.

## 8. Open questions / decisions needed

- **@user — auto-load `seon.test.suite` in production builds?** If yes,
  agent gets the test vars on globalThis at all times (good for
  introspection). If no, only dev builds load them. Recommendation:
  **yes, always load** — the suite ns is `(:require)`-only and adds
  ~kb to the bundle (test files are small); the upside is agents can
  ALWAYS list and run platform tests for self-verification.

- **@user — should `last-result` be per-agent or pod-wide?** Today's
  stash is keyed by `run-id` only; multiple agents in the same pod
  (Phase D sidecar) would interleave. Recommendation: add
  `:seon.agent/id` to the `:seon.test/last-*` row + filter
  `last-result` by `(seon.db/current-agent-id)`.

- **@user — `with-redefs` self-host probe.** Need a 3-line probe
  to confirm it works under the bootstrap build. If broken, design
  an alternative (e.g., dynvars + explicit stub fns).

- **@user — `bin/seon test pod` cold-start vs warm.** Cold-start
  needs the pod up. Should the shim auto-`bin/seon start pod` if
  the pod is down? Recommendation: **no, fail with a hint** — pod
  startup is multi-second and silent auto-starts make CI flakey.

- **@user — agent-defined test source storage.** A
  `:seon.test/source :string` field holds the test form text. Is
  the cap (say 8KB) right? Bigger tests spill to globalThis-blob
  like results do? Recommendation: cap at 8KB; flag in a follow-up
  if agents start writing larger tests.

- **Selective by tag.** `::tag :keyword` is in the schema sketch but
  no metadata on `deftest` carries it yet. cljs.test allows custom
  meta on test vars (`(deftest ^:slow foo …)`). Implementation
  trivial; do we need it day 1? **Recommendation:** ship the schema
  key, defer the `vars-with-tag` impl to when first needed.

- **Shared-DB tests.** `::shared-conn? true` opt-in for loop layer 3.
  Implementation: the fixture skips conn creation if a
  `::fixture-conn` is already bound. Defer to Phase 8.

- **@user — should the reactive loop run on EVERY redef, or only
  when the agent is idle?** Two options: (a) always — every
  `:seon.fn/source` tx triggers the handler immediately (current
  recommendation). (b) coalesce — tx-listener buffers redefs for N
  ms, runs the affected set once at end of burst. Recommendation:
  **always**, because `eval-batch!` is already a single tx (so a
  burst of redefs across one batch produces one handler fire), and
  rapid burst-eval-batch storms are not the common case. Revisit if
  perf bites.

- **@user — should test runs themselves transact, triggering more
  test runs?** `:seon.test/last-passed-at` IS a tx datom; if the
  handler matched any `:seon.fn/source` indiscriminately, a test
  run could fire itself. The proposed match
  `{:seon.handler.match/attr :seon.fn/source}` only matches
  `:seon.fn/source`, not `:seon.test/last-*`, so no cycle. Confirm
  at impl time.

- **@user — should `:seon.fn/test?` be derived (a property of the
  arglists meta) or persisted (a separate attr)?** Persisting is
  cheaper at query time and avoids needing to re-read the analyzer
  on every affected-set query. Recommendation: **persist** during
  the analyzer-tee write.

- **@user — instrument agent-defined fns on every eval-batch, or
  lazily on first call?** `mi/instrument!` per-sym is fast (~µs);
  doing it eagerly in `build-tee-entities` is simpler than wiring
  a lazy hook into `cljs.js`'s fn lookup. Recommendation: **eager,
  per-sym at write time**.

## 9. Appendix: reference-code findings

### `reference-code/kaocha/`

- Kaocha is JVM-only. `kaocha-cljs` exists separately and bridges
  JVM kaocha to a browser/node CLJS test runner via a websocket —
  **we don't want this**. Our CLJS runs in a long-lived Node
  process already; the bridge adds JVM-side complexity we don't
  need. The shipped `seon.test.runner` is the right shape: pure CLJS,
  data in / data out, no out-of-process protocol.
- The one borrow worth doing from kaocha: its **focus/skip meta
  filter** (`.kaocha.filter/focus-meta`, `tests.edn:5`). Translates
  cleanly to our `::tag` selector. Same idea, native CLJS impl.

### `reference-code/expectations/`

- `defexpect` is `deftest` with built-in `(expect expected actual)`.
  Nice author surface but **rejected for now** — we'd be adding a
  third assertion form alongside `is`/`are`, fragmenting agent
  test-authoring. Stay on `cljs.test/is`. (Revisit if agents start
  writing many tests and friction shows up.)
- Worth noting: expectations' "side-effects-explicit" pattern (a
  `:before`/`:after` map per test) mirrors our `use-fixtures :each`
  + `with-test-conn` design.

### `reference-code/test.check/`

- CLJS support is shipped (`cljs/clojure/test/check.cljs`,
  `cljs/clojure/test/check/clojure_test.cljc`). Self-hosted use
  needs both files compiled into the bootstrap bundle — see Phase 6.
- `defspec` registers a regular `deftest`-shaped var with `:test`
  meta. So the existing `resolve-test-fn` (`src/seon/test/runner.cljs:190-207`)
  finds it; the body runs `clojure.test.check/quick-check` and
  fires `is` events. **No special handling needed in `run-vars`** —
  drop test.check into bootstrap and `defspec` just works.
- Shrinking lives in the property runner, not in cljs.test, so
  our event capture sees the FINAL shrunk failure as a single
  `:fail` event — exactly what we want for the warnings tile.

### Self-hosted cljs.test (shadow's bootstrap bundle)

- Bundle source: `reference-code/` has no `shadow-cljs` clone;
  shadow ships `cljs.test` from upstream ClojureScript. The bundled
  copy lands in `out/bootstrap/cljs/test.cljs.js` at compile time.
- **Verified working:** `deftest`, `is`, `testing`, `async`,
  `use-fixtures :once` (one fixture call observed in
  `src/seon/db_test.cljs:36`). Used by the entire shipped Phase 2
  runner.
- **Probe needed:** `with-redefs`, `are`, `use-fixtures :each`
  ordering with async — three small probes pre-Phase 4.

### Key file paths cited in this document

- `src/seon/test/runner.cljs:1-383` — the shipped runner.
- `src/seon/db_test.cljs:1-433` — reference async test ns (will
  move to `test/`).
- `src/seon/render_test.cljs:1-133` — reference sync test ns (will
  move to `test/`).
- `test/seon/boot/preconditions_test.cljs:1-83` — reference test
  in canonical location.
- `shadow-cljs.edn:42-43` — `test/` on source path.
- `shadow-cljs.edn:226-244` — bootstrap `:entries` + `:macros` (where
  cljs.test, test.check additions belong).
- `src/seon/db.cljs:1-80` — conn model + dynvar.
- `src/seon/eval.cljs:569-770` — `eval-batch!` + `build-tee-entities`
  (Phase 4-5 integration points).
- `src/seon/dev/test.clj:108, 321, 357` — JVM analogues to mirror.
- `docs/prds/agent-runtime/platform.md` (Phase 2 + 2.5) — the
  shipped milestone this plan extends.
- `docs/prds/agent-runtime/loop-testing-strategy-2026-05-25.md` —
  the 5-layer strategy this plan ships substrate for.
