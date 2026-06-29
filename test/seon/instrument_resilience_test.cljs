(ns seon.instrument-resilience-test
  "Boot-resilience guard: a SINGLE persisted `:seon.fn` row whose
   `:malli/schema` references a schema name that no longer resolves (a
   renamed/pruned schema ghost) must NEVER crash the whole pod boot.

   The live incident (acme diffusion verification, agent ad64b807): the
   store held a stale `my.data/rows` fn whose persisted spec referenced the
   OLD `:my.data/items-envelope`; the schema had been renamed to
   `:seon.items/envelope` and the old name no longer registered. At boot
   `seon.instrument/instrument-from-db!` read that spec, handed it to
   `mi/instrument!`, and malli threw `:malli.core/invalid-schema` →
   `auto-boot FAILED — exiting`. One stale row took down the pod.

   The fix (errors-as-values applied to boot): `instrument-from-db!` BUILDS
   each schema against the live registry before registering it; a spec that
   can't resolve is counted `:unresolvable-schema` and left UNINSTRUMENTED,
   so boot proceeds. This ns proves the degrade: a conn carrying only an
   unresolvable fn row returns a stats map (never throws) and registers
   nothing.

   The malli `:cljs` function-schema registry is process-global; this ns
   snapshots and restores it around the call so the internal `mi/instrument!`
   runs on an EMPTY registry (a true no-op) and nothing leaks into the other
   (uninstrumented) test namespaces.

   Deterministic — a fresh :memory datahike conn, never the live pod."
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.instrument :as instrument]
    ;; loaded so `seon.render.value/sample` is a LIVE var the row resolves
    ;; to (clearing the `:no-var` branch) — the ghost path needs a real fn.
    [seon.render.value]))

(def ^:private bad-sym
  "A REAL live var (resolves via the munged global path) so the row clears
   the `:no-var` branch and reaches the schema-resolve check — the exact
   path the live ghost took."
  "seon.render.value/sample")

(def ^:private bad-spec
  "Reads fine as EDN, but references a schema name that is NOT in the
   registry — the renamed/pruned-ghost shape."
  "[:=> [:cat :int] :totally/nonexistent-schema-xyz]")

(defn ^:async build-conn
  "Promise of a fresh :memory conn carrying ONE fn row with an unresolvable
   spec — the minimal reproduction of the boot-crash store."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      false}]
    (await (d/create-database cfg))
    (let [conn (await (d/connect cfg {:sync? false}))]
      ;; The two program-graph attrs instrument-from-db! reads, schema'd
      ;; exactly as seon.agent registers them (`:seon.fn/sym` is a string
      ;; identity; `:seon.fn/spec` a plain string).
      (await (d/transact! conn {:tx-data [{:db/ident       :seon.fn/sym
                                           :db/valueType   :db.type/string
                                           :db/cardinality :db.cardinality/one
                                           :db/unique      :db.unique/identity}
                                          {:db/ident       :seon.fn/spec
                                           :db/valueType   :db.type/string
                                           :db/cardinality :db.cardinality/one}]}))
      (await (d/transact! conn {:tx-data [{:seon.fn/sym  bad-sym
                                           :seon.fn/spec bad-spec}]}))
      conn)))

(deftest unresolvable-schema-degrades-not-crashes
  (async done
    (-> (build-conn)
        (.then
          (fn [conn]
            ;; The seed spec must be GENUINELY unresolvable — a guard with
            ;; teeth (otherwise a green run would mean nothing).
            (is (thrown? :default (m/schema (reader/read-string bad-spec)))
                "seed spec must be genuinely unresolvable")
            ;; Snapshot + clear the global :cljs registry so the internal
            ;; mi/instrument! is a true no-op and nothing leaks.
            (let [snapshot (m/function-schemas :cljs)]
              (m/-deregister-function-schemas! :cljs)
              (try
                ;; The boot call: it RETURNS rather than throwing.
                (let [stats (instrument/instrument-from-db! @conn)]
                  (is (map? stats)
                      "instrument-from-db! returns a stats map, never throws")
                  (when (:seon.instrument/enabled? stats)
                    (is (= 1 (:unresolvable-schema stats))
                        "the one ghost row is counted as :unresolvable-schema")
                    (is (= 0 (:registered stats))
                        "the ghost row is NEVER registered for instrumentation")
                    (is (nil? (get-in (m/function-schemas :cljs)
                                      ['seon.render.value 'sample]))
                        "the ghost fn must stay OUT of the function-schema registry")))
                (finally
                  ;; Restore the registry exactly as we found it.
                  (m/-deregister-function-schemas! :cljs)
                  (doseq [[ns-sym fns] snapshot
                          [fn-sym entry] fns]
                    (m/-register-function-schema!
                      ns-sym fn-sym (:schema entry)
                      (dissoc entry :schema :ns :name) :cljs identity)))))))
        (.catch (fn [e]
                  (is false (str "deftest threw: " (ex-message e)))))
        (.finally done))))
