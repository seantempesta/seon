(ns seon.cluster.store-transact-test
  "Sealed acceptance draft for the transaction wrapper (N3, C5-C6).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). A NEW file rather
  than an edit to the sealed store suite: the accretion is new
  functions, so its acceptance is new tests.

  `refusal` is pure and needs no database — the whole point of probe
  D's finding is that a transition's own data survives at the third
  link of the cause chain, so classification is a walk over values.
  `transact!` is then live against a real connection, because the
  outcome that matters most (a refusing transaction function) can only
  be produced by a real writer."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.cluster.store :as store]
            [seon.schema]
            [seon.schema.datahike :as schema.datahike]))

;;; ---------------------------------------------------------------------------
;;; C6 — the pure cause-chain walk
;;; ---------------------------------------------------------------------------

(defn- wrap
  "Bury `throwable` under `depth` empty-data wrappers, as datahike's own
  throwable-promise does."
  [throwable depth]
  (reduce (fn [inner _] (ex-info "wrapped" {} inner))
          throwable
          (range depth)))

(deftest the-deepest-data-survives-any-nesting
  (let [check
        (tc/quick-check
         100
         (prop/for-all [depth (gen/choose 0 6)
                        rule gen/keyword]
           (let [buried (wrap (ex-info "refused" {:seon.error/kind ::refused
                                                  :rule rule})
                              depth)]
             (= rule (:rule (store/refusal buried)))))
         :seed 20260727)]
    (is (true? (:result check)) (str "refusal walk failed: " (pr-str check)))))

(deftest a-clean-throwable-carries-no-refusal
  (is (nil? (store/refusal (RuntimeException. "no data"))))
  (is (nil? (store/refusal (wrap (RuntimeException. "no data") 3))))
  (is (nil? (store/refusal (ex-info "empty" {})))))

;;; ---------------------------------------------------------------------------
;;; C5 — four outcomes, four shapes, never a throw
;;; ---------------------------------------------------------------------------

(def ^:private attributes
  [:seon.cluster.agent/id :seon.cluster.run/id :seon.cluster.run/agent
   :seon.cluster.run/opened-at :seon.cluster.run/claim-epoch])

(defn- with-connection [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection (schema.datahike/malli->datahike-schema attributes))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn refusing-call
  "A transaction function that refuses, exactly as N2's transitions do."
  [_db request]
  (throw (ex-info "transition refused"
                  {:seon.error/kind :seon.cluster.run/refused
                   :seon.cluster.run/rule :seon.cluster.run/ineligible
                   :seon.cluster.run/request request})))

(deftest a-committed-transaction-returns-its-report
  (with-connection
    (fn [connection]
      (let [outcome (store/transact! connection
                                     [{:seon.cluster.agent/id "agent-a"}])]
        (is (map? outcome))
        (is (contains? outcome :db-after) "the report, not a wrapper")
        (is (nil? (:seon.error/kind outcome)))))))

(deftest our-own-refusal-comes-back-by-name
  (with-connection
    (fn [connection]
      (let [outcome (store/transact!
                     connection
                     [[:db.fn/call #'refusing-call {:probe true}]])]
        (is (= :seon.cluster.run/refused (:seon.error/kind outcome))
            "the transition's own kind, verbatim")
        (is (= :seon.cluster.run/ineligible
               (:seon.cluster.run/rule outcome))
            "and its own rule — what makes a fence test honest")
        (is (= {:probe true} (:seon.cluster.run/request outcome)))))))

(deftest a-datahike-abort-is-distinguishable-from-ours
  (with-connection
    (fn [connection]
      (let [outcome (store/transact! connection
                                     [{:seon.cluster.run/claim-epoch
                                       "not-an-int"}])]
        (is (= :seon.db/rejected (:seon.error/kind outcome)))
        (is (= :transact/schema (:error (:seon.error/data outcome)))
            "datahike's own classification, by value")))))

(deftest nothing-throws-out-of-the-wrapper
  (with-connection
    (fn [connection]
      (doseq [tx-data [[{:seon.cluster.agent/id "agent-b"}]
                       [[:db.fn/call #'refusing-call {}]]
                       [{:seon.cluster.run/claim-epoch "nope"}]
                       [[:db/add "nonsense" :nothing/here 1]]]]
        (is (map? (store/transact! connection tx-data))
            "every outcome is a value the run loop can branch on")))))
