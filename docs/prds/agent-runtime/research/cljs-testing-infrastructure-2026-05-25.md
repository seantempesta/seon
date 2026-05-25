---
type: research
status: draft
tags: [research, testing, agent, prd]
---

# CLJS testing infrastructure — research + plan

Plan for the V0 CLJS pod's testing surface. One shared API serves
humans (REPL), agents (eval-batch), and CI (CLI). Foundation already
exists in `src/seon/test/runner.cljs:1-383` (`run-vars` /
`stash-run!` / `record-run!` / `run-and-record!`); this document
extends — never replaces — that foundation.

Cross-references:

- [loop-testing-strategy-2026-05-25.md](loop-testing-strategy-2026-05-25.md) — the 5-layer strategy this plan slots beneath.
- [platform.md](../platform.md) Phase 2 — where the runner was sequenced.
- `src/seon/test/runner.cljs` — the shipped foundation.

## 1. TL;DR

1. **Keep `seon.test.runner` as the only entrypoint.** Extend it with
   four new map-in/map-out selectors (`run-ns!`, `run-all!`,
   `run-failed!`, `last-result`) that all funnel into the existing
   `run-vars` core. No `seon.test2`, no `seon.test.api` parallel ns.
2. **Hybrid discovery.** Platform tests use a hand-maintained
   discovery ns `seon.test.suite` whose `(:require …)` block lists
   every test ns — required by `seon.client/-main` at boot so the
   munged-globalThis lookup finds them. Agent-defined tests register
   as `:seon.test/test` entities (DB-backed) and dispatch by symbol
   the same way.
3. **`test/` is the canonical location.** Move
   `src/seon/db_test.cljs` and `src/seon/render_test.cljs` to
   `test/seon/db_test.cljs` and `test/seon/render_test.cljs`. `test/`
   is already on the shadow source path (`shadow-cljs.edn:42-43`).
   Tests that need to be co-required by production builds stay in
   `src/` (none today; preconditions_test is already in `test/`).
4. **Async via Promises, not `(async done …)`.** Add an `^:async`-aware
   driver path inside `run-vars` that, when a test fn returns a
   thenable, awaits it before emitting `:end-test-var`. cljs.test's
   `(async done …)` form keeps working (it's just a callback that
   resolves a Promise we wait on). `defspec` from test.check needs a
   small wrapper that wraps the property runner in a thenable.
5. **One CLI shim, one REPL fn.** `bin/seon test pod` (cold-start
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

**Agent-defined tests: A2 — `:seon.test/test` entity.**

When an agent's eval-batch redefines a `defn` with a `:test` form (or a
sibling `foo-test`), the analyzer-tee path (already in
`seon.eval/build-tee-entities` — see `src/seon/eval.cljs:648`) emits
a `:seon.test/test` entity:

```clojure
(schema/register! :seon.test/test [:map
                                   [:seon.test/sym :string]    ; FQN as string (identity)
                                   [:seon.test/source :string] ; the (deftest …) form's text
                                   [:seon.test/defined-at :inst]])

(schema/register! :seon.test/sym [:string {:seon.db/identity true}])
```

Discovery for `run-all!`:

```clojure
(into (platform-syms) (map symbol) (db/query
  {::db/query '[:find [?sym ...] :where [_ :seon.test/sym ?sym]]}))
```

Same `resolve-test-fn` dispatch as platform tests — agent tests live
on globalThis as soon as their `(deftest …)` eval lands. No second
mechanism.

**Rejected:** build-time codegen of the discovery ns (adds rebuild
latency; doesn't help agent-defined tests anyway).

### B. Test file location

**Rule: all CLJS tests live in `test/`.** Move:

- `src/seon/db_test.cljs` → `test/seon/db_test.cljs`
- `src/seon/render_test.cljs` → `test/seon/render_test.cljs`

`test/seon/boot/preconditions_test.cljs` is already correct. `test/`
is already on shadow's `:source-paths` (`shadow-cljs.edn:42-43`), and
the entries comment block (`shadow-cljs.edn:21-28`) explicitly
documents this layout.

**Agent-defined tests have no file.** They live as `:seon.test/test`
entities in the DB; their source string in `:seon.test/source`. The
agent's eval re-defines them, so on pod restart they re-emerge by
replaying the program-graph entities — same mechanism that restores
`:seon.fn` defs.

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

;; ::run-result extended with ::run-id when recorded
(schema/register! ::run-result
  [:map
   [::events  [:vector ::test-event]]
   [::summary ::summary]
   [::run-id  {:optional true} ::run-id]
   [::recorded-syms {:optional true} [:vector :string]]])
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
  ;; Datalog: :find ?sym :where [?e :seon.test/sym ?sym]
  ;;                          [?e :seon.test/last-failed-at ?f]
  ;;                          [(missing? $ ?e :seon.test/last-passed-at)]
  ;; OR last-failed-at > last-passed-at
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

Pseudo:

```bash
# in bin/seon, after the process_command case:
"test")
  case "$2" in
    pod)
      shift 2
      exec node -e "..." # pipes EDN over nREPL
      ;;
  esac
  ;;
```

(Implementation detail. The shape: same as `bin/test` but talks to
shadow nREPL.)

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
  "When a :seon.fn entity gets a new :source, look up
   :seon.test/test entities targeting it (by name OR by `<sym>-test`
   sibling) and queue a run.

   Returns {:tx [] :effects [{:effect/type :run-tests
                              :seon.test/vars [<syms>]}]}."
  [{:seon.db/keys [tx-report db]}]
  (let [redefined-fns (->> (:tx-data tx-report)
                           (filter #(= :seon.fn/source (:a %)))
                           (map :e)
                           distinct)
        sibling-syms  (for [eid redefined-fns
                            :let [fn-sym (db/pull {::db/ref eid
                                                   ::db/pull-pattern [:seon.fn/sym]})]]
                        (symbol (str (:seon.fn/sym fn-sym) "-test")))
        existing      (filter test-sym-exists? sibling-syms)]
    (when (seq existing)
      {:tx []
       :effects [{:effect/type :run-tests
                  :seon.test/vars (vec existing)}]})))
```

The `:run-tests` effect interpreter is one line:

```clojure
(defmethod run-effect! :run-tests [{:seon.test/keys [vars]}]
  (run! {::vars vars ::record? true}))
```

**Test counterpart resolution:**

- Convention: `foo` ↔ `foo-test` in same ns. (CLJS prevalent pattern,
  matches `src/seon/db_test.cljs` shape.)
- Override: a `:seon.test/test` entity may name its targets via a
  `:seon.test/targets [:vector :symbol]` field (agent-authored tests).

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

;; --- outputs ---  (extends existing run-result)
(schema/register! ::run-result
  [:map
   [::events         [:vector ::test-event]]
   [::summary        ::summary]
   [::run-id         {:optional true} ::run-id]
   [::selected-vars  {:optional true} ::vars]    ; what the selector resolved to
   [::recorded?      {:optional true} :boolean]])

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

## 5. Shared interface spec

See §4.H. The five entrypoints, all in `src/seon/test/runner.cljs`:

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

Each phase ships standalone value.

### Phase 1 — REPL ns runner (1-2 hr)

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

### Phase 2 — Suite + all-runner + CLI (half day)

7. Add `seon.test.suite` ns with hand-maintained requires.
8. Wire `seon.client/-main` to `(:require [seon.test.suite])`.
9. Implement `all-test-syms` via `cljs.analyzer.api`.
10. Implement `run-all!`.
11. Add `bin/seon test pod` shim (nREPL client + EDN response).
12. **Acceptance:** `bin/seon test pod` runs the whole suite cold-start,
    exits 0/1 correctly, prints a readable summary.

### Phase 3 — Fixtures + render (half day)

13. Add `seon.test.fixtures/empty-db` + `with-test-conn`.
14. Add `with-test-conn-async` macro.
15. Add `seon.test.render/summary` + `tile-hiccup`.
16. Refactor `db_test`'s `fresh-conn` to use the fixture.
17. **Acceptance:** test bodies shrink; one canonical pattern in use
    across `db_test`, `preconditions_test`, and any new tests.

### Phase 4 — Failed-only + DB-backed `:seon.test/test` (1 day)

18. Implement `vars-currently-failing` via Datalog.
19. Implement `run-failed!`.
20. Add `:seon.test/test` entity schema (sym/source/defined-at/targets).
21. Extend discovery to union platform-syms + DB-stored agent-syms.
22. **Acceptance:** agent eval `(deftest some-agent-test …)` lands a
    `:seon.test/test` row; `(run-all! {})` includes it.

### Phase 5 — Auto-trigger handler (1 day)

23. Add `src/seon/runtime/test_autorun.cljs` handler.
24. Wire `:run-tests` effect interpreter.
25. **Acceptance:** agent redefines `foo`, `foo-test` exists, `:seon.test/last-{passed,failed}-at` updates without explicit `run!`.

### Phase 6 — test.check integration (½ day)

26. Add `clojure.test.check` + `clojure.test.check.clojure-test` to
    `shadow-cljs.edn` `:entries` and `:macros`.
27. Re-author `prop-*` tests in `db_test` to use `defspec`.
28. **Acceptance:** `defspec` yields shrinking on failure.

### Phase 7 — Loop-strategy substrate (per loop-testing-strategy.md)

29. Build `with-test-pod`, `transact-and-tick!`,
    `tick-to-fixpoint!` on top of the now-stable runner +
    fixtures.
30. Layer 2-5 tests per the strategy doc.

## 7. Open questions / decisions needed

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
  `::fixture-conn` is already bound. Defer to Phase 7.

## 8. Appendix: reference-code findings

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
