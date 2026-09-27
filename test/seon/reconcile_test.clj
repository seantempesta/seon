(ns seon.reconcile-test
  "Sealed acceptance draft for provenance-scoped exact reconciliation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.config :as config]
            [seon.db :as db]
            [seon.reconcile :as reconcile]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

(set! *warn-on-reflection* true)

(schema.edn/load! {})

(def ^:private managing-process
  ;; The test's own managing process, so the managed slice is exactly the
  ;; entities these tests write. The member's branch already holds its cluster's
  ;; config entity under boot's `seon.db.process/config` provenance; managing as
  ;; that process planned the entity's retraction on every apply, which the
  ;; writer refuses (the cluster entity requires :seon.cluster/config).
  ;; Production's `config/apply!` retains the configs a cluster entity
  ;; references; this suite's subject is the reconciler itself.
  "seon.db.process/reconcile-test")

(def ^:private unmanaged-process
  "seon.db.process/unmanaged")

(defn- with-model-database
  ;; A branch rather than `:fresh-store?`: a fresh store transacts the whole
  ;; attribute schema, 1.2 s each (1,260 attributes, measured at default
  ;; da10c8912), which 60 property trials cannot afford.
  [body]
  (test-support/with-database
    (fn [connection]
      (test-support/transacted! connection
                                [{:seon.db.process/id managing-process}
                                 {:seon.db.process/id unmanaged-process}])
      (body connection))))

(defn- transaction-meta
  [process]
  {:seon.db/process [:seon.db.process/id process]})

(defn- transact-as!
  [connection process tx-data]
  (test-support/transacted! connection
                            {:tx-data tx-data
                             :tx-meta (transaction-meta process)}))

(defn- request
  [desired]
  {::reconcile/desired desired
   ::reconcile/process managing-process})

(defn- config-entity
  [cluster queue-depth]
  (:seon.config/desired-row
   (config/compile-manifest
    {:seon.boot/cluster-name cluster
     :seon.config/manifest
     {:seon.config.flow.io/queue-depth queue-depth
      :seon.config.flow.compute/concurrency 18}})))

(deftest fixture-provenance-is-real-transaction-metadata
  (with-model-database
    (fn [connection]
      (transact-as! connection
                    managing-process
                    [(config-entity "provenance-proof" 10)])
      (is (= managing-process
             (db/q
              '[:find ?process-id .
                :where
                [?entity :seon.config/cluster "provenance-proof"]
                [?entity :seon.config.flow.io/queue-depth _ ?tx]
                [?tx :seon.db/process ?process]
                [?process :seon.db.process/id ?process-id]]
              @connection))))))

(deftest first-apply-writes-and-reapply-is-zero-writes
  (with-model-database
    (fn [connection]
      (let [desired [(config-entity "alpha" 10)]
            before (:max-tx @connection)
            first-result (reconcile/reconcile! connection (request desired))
            after-first (:max-tx @connection)
            second-result (reconcile/reconcile! connection (request desired))
            after-second (:max-tx @connection)]
        (is (= {::reconcile/converged? false
                ::reconcile/operations 1}
               first-result))
        (is (< before after-first) "the first apply commits")
        (is (= {::reconcile/converged? true
                ::reconcile/operations 0}
               second-result))
        (is (= after-first after-second)
            "converged means no transaction entity and unchanged :max-tx")))))

(deftest a-flat-write-refusal-is-the-reconcile-result
  (with-model-database
    (fn [connection]
      (let [refusal {:seon.db.write.attempt/request-id "reconcile-refusal" :seon.error/at (java.util.Date.) :seon.error/layer :seon.db/invocation :seon.error/operation 'seon.db/transact!
                     :seon.error/message "injected reconcile refusal"}
            result
            (with-redefs [db/transact! (fn [& _] refusal)]
              (reconcile/reconcile!
               connection
               (request [(config-entity "refused" 10)])))]
        (is (= refusal result))))))

(deftest a-hand-edit-is-repaired-by-the-next-apply
  (with-model-database
    (fn [connection]
      (let [desired [(config-entity "drifted" 10)]]
        (reconcile/reconcile! connection (request desired))
        (transact-as! connection
                      unmanaged-process
                      [{:db/id [:seon.config/cluster "drifted"]
                        :seon.config.flow.io/queue-depth 99}])
        (is (= 99
               (:seon.config.flow.io/queue-depth
                (db/pull @connection
                        '[*]
                        [:seon.config/cluster "drifted"]))))
        (reconcile/reconcile! connection (request desired))
        (is (= 10
               (:seon.config.flow.io/queue-depth
                (db/pull @connection
                        '[*]
                        [:seon.config/cluster "drifted"]))))))))

(deftest a-managed-identity-absent-from-desired-is-retracted
  (with-model-database
    (fn [connection]
      (reconcile/reconcile!
       connection
       (request [(config-entity "keep" 10)
                 (config-entity "remove" 11)]))
      (reconcile/reconcile!
       connection
       (request [(config-entity "keep" 10)]))
      (is (some? (db/pull @connection
                         '[:seon.config/cluster]
                         [:seon.config/cluster "keep"])))
      (is (nil? (db/pull @connection
                        '[:seon.config/cluster]
                        [:seon.config/cluster "remove"]))))))

(deftest planning-pulls-only-the-provenance-managed-slice
  (with-model-database
    (fn [connection]
      (let [managed (config-entity "managed-slice" 10)
            foreign
            (mapv #(config-entity (str "foreign-slice-" %) 10) (range 20))]
        (transact-as! connection managing-process [managed])
        (transact-as! connection unmanaged-process foreign)
        (let [managed-eid
              (db/q
               '[:find ?entity .
                 :where
                 [?entity :seon.config/cluster "managed-slice"]]
               @connection)
              original-pull db/pull
              pulled-eids (atom [])
              tx-data
              (with-redefs
               [db/pull
                (fn [& args]
                  (swap! pulled-eids conj (last args))
                  (apply original-pull args))]
                (reconcile/plan @connection (request [managed])))]
          (is (empty? tx-data) "the managed entity is already exact")
          (is (= [managed-eid] @pulled-eids)
              "foreign identities are unrepresentable in the pull slice"))))))

(deftest malformed-and-conflicting-identities-refuse
  (with-model-database
    (fn [connection]
      (testing "no registered identity attribute"
        (is (= ::reconcile/no-identity
               (::reconcile/rule
                (test-support/refusal-data
                 #(reconcile/plan
                   @connection
                   (request [{}])))))))
      (testing "two registered identity attributes"
        (is (= ::reconcile/two-identities
               (::reconcile/rule
                (test-support/refusal-data
                 #(reconcile/plan
                   @connection
                   (request
                    [{:seon.config/cluster "two"
                      :seon.turn/id "also-two"}])))))))
      (testing "the same upsert handle appears twice"
        (doseq [size [1 8 12]
                ordinals [(range size) (reverse (range size))]]
          (let [names (mapv #(str "duplicate-" %) ordinals)
                entities (mapv #(config-entity % 10) (concat names names))
                refusal (test-support/refusal-data
                         #(reconcile/plan @connection (request entities)))]
            (is (= ::reconcile/duplicate-identity (::reconcile/rule refusal)))
            (is (= [:seon.config/cluster (first names)]
                   (::reconcile/identity refusal))
                "evidence follows desired-entity order, never hash-map order"))))
      (testing "an unmanaged entity already owns the desired identity"
        (transact-as! connection
                      unmanaged-process
                      [(config-entity "outside" 77)])
        (is (= ::reconcile/identity-outside-scope
               (::reconcile/rule
                (test-support/refusal-data
                 #(reconcile/plan
                   @connection
                   (request [(config-entity "outside" 10)]))))))))))

(def ^:private desired-population-generator
  (gen/fmap
   (fn [entries]
     (mapv (fn [[ordinal queue-depth mode]]
             (assoc (config-entity (str "generated-" ordinal) queue-depth)
                    :seon.config.eval/time-limit-ms mode))
           entries))
   ;; distinct BY IDENTITY ordinal — gen/vector-distinct has no :key
   ;; option; vector-distinct-by is the supported API
   (gen/vector-distinct-by
    first
    (gen/tuple (gen/choose 0 40)
               (gen/choose 1 100)
               (gen/elements [1000 2000]))
    {:min-elements 0
     :max-elements 12})))

(deftest apply-then-reapply-converges-over-generated-populations
  (let [check
        (tc/quick-check
         60
         (prop/for-all [desired desired-population-generator]
           (with-model-database
             (fn [connection]
               (reconcile/reconcile! connection (request desired))
               (let [before (:max-tx @connection)
                     result (reconcile/reconcile! connection (request desired))
                     after (:max-tx @connection)]
                 (and (= {::reconcile/converged? true
                          ::reconcile/operations 0}
                         result)
                      (= before after))))))
         :seed 20260727)]
    (test-support/assert-check!
     check
     "Whole-domain reconciliation idempotence failed.")))
