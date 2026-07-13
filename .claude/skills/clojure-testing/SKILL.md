---
name: clojure-testing
description: "Test patterns for Seon. Use when writing or debugging .cljs tests, when an async/Promise test never finishes, when an ambient database read sees the wrong connection, or when setting up a fresh in-memory Datahike connection. Covers focused bin/test-cljs selection, cljs.test/async, capability-verb envelopes, root set! of db/*conn*, and the separate bin/test-writer database-server gate."
---

# Clojure Testing — pod-first

The application suite is **ClojureScript**, run in a fresh isolated Node test
runtime via `bin/test-cljs`. Tests double as the worked manual for the surface they
cover — read `test/seon/db_test.cljs`, `test/seon/ctx_test.cljs`,
`test/my/kb_test.cljs`, `test/my/skills_test.cljs` as the canonical examples.

> Hand-offs: `^:async`/`await`/Promise semantics → **`clojurescript`**; what
> `db/transact!` / `db/query` actually do + the envelope shape →
> **`datahike`**; errors-as-values / no-bare-keys mindset →
> **`data-oriented-clojure`**. How to run the suite is in the shared
> repository instructions under "Testing".

## Running

```bash
bin/test-cljs              # compile (DEV) + run every *-test ns
bin/test-cljs --no-build   # skip compile; rerun out/test/test.js
bin/test-cljs --test=seon.db-test
bin/test-cljs --test=seon.db-test/one-behavior
bin/test-writer            # retained JVM database-server boundary
```

It compiles **DEV, not release** on purpose: the core resolves fns by walking
`goog.global` at munged paths (`seon.eval/lookup-value`, malli's CLJS
instrument), which Closure `:simple`/`:advanced` would flatten away. Use it as
the batch checkpoint **once per unit of work**, not after each sub-step
(`Test cadence = token economy` in the shared instructions). To verify ONE
behavior fast, eval
the fn directly against the live pod instead of running a whole ns.

**Never fire overlapping `cljs.test/run-tests` in the LIVE pod** — it wedges the
shared async continuation. Restart (`bin/seon restart`) for a pristine run;
`bin/test-cljs` is the isolated path (its own JVM, no live-pod contention).

## Fresh in-memory datahike conn per test

The pod doesn't embed Datahike — but a test may open a real `:memory` Datahike
connection directly (no database server), seeded like the pod boots. Each test gets its own
instance (a fresh `:id` random-uuid) so they never see each other's data. This
is a Promise (datahike connect is async):

```clojure
(require '[datahike.api :as d] '[seon.db :as db] '[seon.client :as client])

(defn- fresh-conn
  "Promise of a fresh :memory conn carrying the pod's boot schema."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact! conn {:tx-data (into (db/malli->datahike-schema
                                                         client/agent-bootstrap-attrs)
                                                        (db/tx-meta-datahike-schema))})
                     (.then (fn [_] conn))))))))
```

Domain attrs need NOT be pre-installed — `db/transact!` lazy-installs an attr's
schema on its first write. Pre-seed only the pod's boot schema (above).

## The big CLJS gotcha: root `set!`, not `binding`

The pod's verbs read `db/*conn*` **ambiently** (db-omitted), exactly as in
production. To make those reads hit YOUR test conn you must `set!` the **root**
binding — a `binding` form pops at the first `await`/microtask boundary, so it
would not survive a single async hop:

```clojure
(defn- with-conn
  "Fresh seeded conn set! as the ROOT db/*conn* for body (conn → Promise);
   prior root restored after. NOT binding — CLJS dynamic bindings don't
   survive await."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))
```

Because that root is a SHARED global the whole suite mutates, a concurrent
test's fiber can `set!` it between your async hops. Guard each `.then` that does
an ambient read by **re-pinning** the conn first — a synchronous read right
after a `set!` can't be interleaved:

```clojure
(defn- pinned [conn f] (fn [x] (set! db/*conn* conn) (f x)))
```

## Async tests — `cljs.test/async` + the envelope

`db/transact!` (and every `^:async` capability verb) ALWAYS resolves to a data
**envelope** — it never rejects, never throws into the caller. Assert on the
envelope's `:seon.db/ok?` (an eval can "succeed" yet the write did NOT happen):

```clojure
(deftest append-then-read-back
  (async done
    (-> (with-conn
          (fn [_conn]
            (-> (db/transact! {:seon.db/tx-data [{:my.kb.shared/id "shared"
                                                  :my.kb.shared/instructions
                                                  [{:my.kb.shared/text "store provenance"
                                                    :my.kb.shared/at (js/Date. 1000)}]}]})
                (.then (fn [{ok? :seon.db/ok?}]
                         (is (true? ok?) "an append is ONE nested-map transact"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
```

Always call `done` on BOTH the success and the `.catch` rail — a forgotten
`done` is the usual cause of an async test that "hangs" until the runner times
out. A rejection should fail loudly (`(is false …)`), not silently pass.

## Common failure patterns

| Symptom | Likely cause | Fix |
|---|---|---|
| Async test never finishes | `done` not called on one rail (often the error rail) | call `done` in BOTH `.then` and `.catch` |
| Ambient read sees another test's data | `binding` instead of root `set!`, or no re-pin before the read | use `with-conn` + `pinned` |
| "Unregistered attributes in transaction" | missing `schema/register!` for an attr | register it in the owning ns |
| `:malli.core/invalid-input/output` on a call | args/return don't match `:malli/schema` | read the explain — fix the call or the schema, don't coerce |
| Empty `#{}` from a query that should match | attr misspelled, type mismatch, or ref-join-as-keyword | see the `datahike` skill's read traps |

## Generative checks stay inside the same suite

Malli generators work in ClojureScript (`mg/generate`, `mg/sample`), but they do
not create a third test mechanism. Put the property in a normal `cljs.test`
namespace and run it through `bin/test-cljs`. Database properties should use a
fresh connection and exercise the same `schema/register!` → lazy install →
transact → read-back boundary as the application.

## Key test files

| File | What it teaches |
|---|---|
| `test/seon/db_test.cljs` | `fresh-conn`, instrument-in-test setup, the envelope contract, query/pull shapes |
| `test/seon/ctx_test.cljs` | `with-conn` root-`set!`, context composition contracts |
| `test/my/kb_test.cljs` | `pinned` re-pin pattern, append/read-back, the DB-as-manual idiom |
| `test/my/skills_test.cljs` | derived-state assertions (no stored flags), corpus-scan-can't-bit-rot |
