---
type: issue
status: open
severity: major
tags: [issue, database, testing]
---

# A refused transaction loses its `ex-data`, so refusals are indistinguishable

## Problem

Datahike's writer runs the transaction on its own thread and rethrows the
failure to the caller. The rethrown exception carries an **empty `ex-data`**:
the original `ex-info`'s data survives only as printed text inside
`(.getMessage e)`, and `(ex-cause e)` is a bare
`java.util.concurrent.ExecutionException` with no data at all.

Every refusal therefore looks identical to a caller. A correct fence (a
`[:db.fn/call ...]` transition refusing an ineligible request, a
`:transact/cas` abort) cannot be told apart from an unrelated crash (a schema
violation, a coercion bug, a typo in an attribute name).

## Evidence

REPL-verified 2026-07-27 against the vendored fork. Save the probe below as
`tmp/datahike-claims-probe.clj` and run
`clojure -M:test tmp/datahike-claims-probe.clj`:

```text
:TOP-CLASS        clojure.lang.ExceptionInfo
:TOP-MSG          clojure.lang.ExceptionInfo: refused #:probe{:why :ineligible}
:TOP-EXDATA       {}
:CAUSE-CLASS      java.util.concurrent.ExecutionException
:CAUSE-EXDATA     nil
:CAS-TOP-EXDATA   {}
:CAS-CAUSE-EXDATA nil
```

The same probe confirms the behaviour the refusal is supposed to deliver IS
correct — `:db.fn/call` receives the mid-transaction database value, and a
throw inside it aborts the entire transaction atomically
(`:A-UNCHANGED nil` after a vector whose first operation would have written).
Only the FAILURE CLASSIFICATION is lost.

### The probe

```clojure
;; Probe for the datahike skill's newly-asserted claims (2026-07-27).
;;   clojure -M:test tmp/datahike-claims-probe.clj
(require '[datahike.api :as d] '[seon.schema :as schema]
         '[seon.schema.datahike :as sd])

(schema/register! :probe/id [:string {:seon.db/identity true}])
(schema/register! :probe/ptr :seon.db/ref)
(schema/register! :probe/n :int)

(let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}]
  (d/create-database cfg)
  (let [conn (d/connect cfg)]
    (d/transact conn (sd/malli->datahike-schema [:probe/id :probe/ptr :probe/n]))
    (d/transact conn [{:probe/id "a"} {:probe/id "b"}])

    ;; CLAIM 1: :db.fn/call applies f to the mid-transaction db and splices tx-data.
    (let [f (fn [db req]
              (let [n (count (d/q '[:find ?e :where [?e :probe/id]] db))]
                [{:probe/id (:id req) :probe/n (long n)}]))]
      (d/transact conn [[:db.fn/call f {:id "c"}]])
      (println :CALL-SPLICED-N (d/q '[:find ?n . :where [?e :probe/id "c"] [?e :probe/n ?n]] @conn)))

    ;; CLAIM 2: :db.fn/call throwing aborts the WHOLE transaction atomically.
    (let [boom (fn [_db _req] (throw (ex-info "refused" {:probe/refused true})))]
      (println :REFUSAL
               (try (d/transact conn [{:probe/id "a" :probe/n 999}
                                      [:db.fn/call boom {}]])
                    :committed
                    (catch Exception e (ex-data e))))
      (println :A-UNCHANGED (d/q '[:find ?n . :where [?e :probe/id "a"] [?e :probe/n ?n]] @conn)))

    ;; CLAIM 3: CAS with old=nil asserts the attribute is ABSENT.
    (println :CAS-NIL-ON-ABSENT
             (try (d/transact conn [[:db.fn/cas [:probe/id "b"] :probe/ptr nil [:probe/id "a"]]])
                  :committed (catch Exception e (:error (ex-data e)))))
    (println :CAS-NIL-ON-PRESENT
             (try (d/transact conn [[:db.fn/cas [:probe/id "b"] :probe/ptr nil [:probe/id "a"]]])
                  :committed (catch Exception e (:error (ex-data e)))))

    ;; CLAIM 4: :db/cas is an accepted alias.
    (println :DB-CAS-ALIAS
             (try (d/transact conn [[:db/cas [:probe/id "c"] :probe/n 2 7]])
                  :committed (catch Exception e (:error (ex-data e)))))

    (d/release conn)
    (d/delete-database cfg)))

;; CLAIM 5: what does a refusal's exception actually carry?
(let [cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write}]
  (d/create-database cfg)
  (let [conn (d/connect cfg)]
    (d/transact conn (sd/malli->datahike-schema [:probe/id :probe/n]))
    (d/transact conn [{:probe/id "a"}])
    (try (d/transact conn [[:db.fn/call (fn [_ _] (throw (ex-info "refused" {:probe/why :ineligible}))) {}]])
         (catch Exception e
           (println :TOP-CLASS (class e))
           (println :TOP-MSG (.getMessage e))
           (println :TOP-EXDATA (ex-data e))
           (println :CAUSE-CLASS (class (ex-cause e)))
           (println :CAUSE-EXDATA (ex-data (ex-cause e)))))
    (try (d/transact conn [[:db.fn/cas [:probe/id "a"] :probe/n 5 7]])
         (catch Exception e
           (println :CAS-TOP-EXDATA (ex-data e))
           (println :CAS-CAUSE-EXDATA (ex-data (ex-cause e)))))
    (d/release conn) (d/delete-database cfg)))
```

## Impact

`test/seon/cluster/run_test.clj` has:

```clojure
(defn- transact-or-refusal
  "Commit tx-data; a refusal (any throw) returns its ex-data as a value."
  [connection tx-data]
  (try (d/transact connection tx-data) ::committed
       (catch Exception e (or (ex-data e) {::opaque (ex-message e)}))))
```

`(ex-data e)` is `{}`, which is truthy, so the `or` always takes it and the
`::opaque` message branch is **dead code**. The docstring's claim that a
refusal "returns its ex-data" is false.

The suite is currently sound only because every assertion compares against
`::committed` and treats anything else as "refused". That makes the
state-machine property vulnerable to the green-for-the-wrong-reason class: a
transition that fails for a reason the model never contemplated (the
`java.lang.Integer` vs `:db.type/long` trap, an uninstalled attribute, a
renamed key) counts as a correct refusal and the property passes.

## Owner

The store owner / transaction boundary — whichever namespace ends up wrapping
`d/transact` for the fresh tree. Options, cheapest first:

1. the boundary re-parses `(.getMessage e)` for `:error` — works today, ugly,
   and a message-format change breaks it silently;
2. the boundary wraps its own refusals in a fact it writes or a sentinel value
   the transition returns, rather than relying on a throw to carry data;
3. fix it upstream in the vendored fork so the writer preserves the original
   exception's data when rethrowing (`reference-code/datahike/.../writer.cljc`).

Option 3 is the real fix and the fork is ours.

## Acceptance

A refused transaction is distinguishable from an unrelated failure by a VALUE,
not a message string: `transact-or-refusal` (or its successor) returns the
refusing transition's own data, `run_test.clj`'s dead `::opaque` branch is
gone, and at least one test asserts that a transition refused for the SPECIFIC
reason the model predicted.

## Notes

Recorded in the `datahike` and `clojure-testing` skills (2026-07-27) so nobody
writes `(:error (ex-data e))` and believes it works.
