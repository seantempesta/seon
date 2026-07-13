(ns seon.server.wire-props-test
  "Generative / property tests for the JVM wire-server contract.

   `handle-op` correctness is checked against a fresh in-memory Datahike
   connection. These tests call `seon.server.wire/handle-op` directly (no UDS
   subprocess) so the generative suite stays fast. For generated entity sets:
        - transact then `q` returns what was written;
        - transact then `pull` returns the entity;
        - basis-t strictly increases across successful transacts;
        - a read op (`q`) does not change basis-t.

   Generators are kept small (1-8 entities, ~50 trials) deliberately — the
   point is contract coverage, not load.

   Property tests drive `clojure.test.check/quick-check` from inside plain
   `deftest`s (rather than `defspec`) so clj-kondo resolves every symbol; the
   `passing?` helper turns a quick-check result map into a single assertion
   that prints the shrunk counterexample on failure."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.server.test-util :as tu :refer [*ctx*]]
            [seon.server.wire :as wire]))

(set! *warn-on-reflection* true)

(defn- check
  "Run a property `n` times and return the quick-check result map."
  [n property]
  (tc/quick-check n property))

(defn- passing?
  "Assert a quick-check result passed, surfacing the shrunk counterexample."
  [result]
  (is (true? (:pass? result))
      (str "shrunk counterexample: "
           (pr-str (get-in result [:shrunk :smallest]))
           " (failed after " (:num-tests result) " tests)")))

(def ^:private schema-tx
  "String-identity entity with a long attr and a keyword attr. Declared up
   front because the store uses :schema-flexibility :write."
  [{:db/ident :ent/id   :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :ent/n    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :ent/tag  :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(defn- op!
  "Run one direct handler call through the shared current request builder."
  [conn op extra]
  (wire/handle-op (:runtime *ctx*) conn (tu/request op extra)))

(defn- with-wire-conn
  "Run one test against an isolated fixture with its schema installed once."
  [test-fn]
  (tu/with-fresh-writer
   (fn []
     (let [response (op! (:conn *ctx*)
                         "transact"
                         {:seon.store.wire/tx-data schema-tx})]
       (when-not (true? (:seon.store.wire/ok response))
         (throw (ex-info "Property-test schema installation failed."
                         {:seon.server.wire/response response})))
       (test-fn)))))

(use-fixtures :each with-wire-conn)

(defn- fixture-conn
  "The current test fixture's isolated Datahike connection."
  []
  (:conn *ctx*))

(defn- ok? [resp] (true? (:seon.store.wire/ok resp)))

(def gen-entity
  "One entity: a string id, a long, and a keyword tag."
  (gen/let [id  gen/string-alphanumeric
            n   gen/large-integer
            tag gen/keyword-ns]
    {:ent/id id :ent/n n :ent/tag tag}))

(def gen-entity-set
  "1-8 entities with DISTINCT ids (string identity collapses dupes, which
   would make per-id assertions ambiguous)."
  (gen/let [ents (gen/vector gen-entity 1 8)]
    (->> ents
         (reduce (fn [acc e] (assoc acc (:ent/id e) e)) {})
         vals
         vec)))

(deftest transact-then-query-returns-written
  (passing?
   (check 50
          (prop/for-all [ents gen-entity-set]
            (let [conn (fixture-conn)
                  tx   (op! conn "transact" {:seon.store.wire/tx-data ents})]
              (and (ok? tx)
                   (every?
                    (fn [{:keys [:ent/id :ent/n :ent/tag]}]
                      (let [r (op!
                               conn "q"
                               {:seon.store.wire/query '[:find ?n ?tag :in $ ?id :where
                                                         [?e :ent/id ?id]
                                                         [?e :ent/n ?n]
                                                         [?e :ent/tag ?tag]]
                                :seon.store.wire/args [id]})
                            rows (:seon.store.wire/result r)]
                        (and (ok? r) (= #{[n tag]} rows))))
                    ents)))))))

(deftest transact-then-pull-returns-entity
  (passing?
   (check 50
          (prop/for-all [ents gen-entity-set]
            (let [conn (fixture-conn)
                  tx   (op! conn "transact" {:seon.store.wire/tx-data ents})]
              (and (ok? tx)
                   (every?
                    (fn [{:keys [:ent/id] :as ent}]
                      (let [pull-resp (op!
                                       conn "pull"
                                       {:seon.store.wire/selector '[:ent/id :ent/n :ent/tag]
                                        :seon.store.wire/eid [:ent/id id]})
                            pm (:seon.store.wire/result pull-resp)
                            want (select-keys ent [:ent/id :ent/n :ent/tag])]
                        (and (ok? pull-resp)
                             (= want (select-keys pm [:ent/id :ent/n :ent/tag])))))
                    ents)))))))

(deftest transact-basis-t-strictly-increases
  (passing?
   (check 50
          (prop/for-all [batches (gen/vector gen-entity-set 1 5)]
            (let [conn (fixture-conn)]
              (loop [[b & more] batches
                     prev nil]
                (if (nil? b)
                  true
                  (let [r  (op! conn "transact" {:seon.store.wire/tx-data b})
                        bt (:seon.store.wire/basis-t r)]
                    (if (and (ok? r)
                             (integer? bt)
                             (or (nil? prev) (> (long bt) (long prev))))
                      (recur more bt)
                      false)))))))))

(deftest read-does-not-change-basis-t
  (passing?
   (check 50
          (prop/for-all [ents gen-entity-set]
            (let [conn   (fixture-conn)
                  tx     (op! conn "transact" {:seon.store.wire/tx-data ents})
                  bt-tx  (:seon.store.wire/basis-t tx)
                  q1     (op! conn "q"
                              {:seon.store.wire/query '[:find ?e :where [?e :ent/id _]]})
                  q2     (op! conn "q"
                              {:seon.store.wire/query '[:find ?e :where [?e :ent/id _]]})]
              (and (ok? tx) (ok? q1) (ok? q2)
                   (= bt-tx (:seon.store.wire/basis-t q1) (:seon.store.wire/basis-t q2))))))))
