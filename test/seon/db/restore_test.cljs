(ns seon.db.restore-test
  "Ordinary-data proof for durable restore completion facts."
  (:require
    [cljs.test :refer [async deftest is]]
    [malli.core :as m]
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.db.restore :as restore]))

(def ^:private plan-digest (apply str (repeat 64 "a")))
(def ^:private database-id
  #uuid "11111111-1111-4111-8111-111111111111")

(defn- point [commit-id t]
  {::coordinate/database-id database-id
   ::coordinate/branch :db
   ::coordinate/commit-id commit-id
   ::coordinate/t t})

(def ^:private predecessor
  (point #uuid "22222222-2222-4222-8222-222222222222" 536870920))
(def ^:private completion-point
  (point #uuid "33333333-3333-4333-8333-333333333333" 536870921))
(def ^:private later-point
  (point #uuid "44444444-4444-4444-8444-444444444444" 536870922))

(def ^:private completion-claim
  {::restore/plan-digest plan-digest
   ::restore/db-name :default
   ::restore/database-id database-id
   ::restore/from-branch :db
   ::restore/from-commit-id
   #uuid "55555555-5555-4555-8555-555555555555"
   ::restore/from-t 536870900
   ::restore/to-branch :seon.branch/retained
   ::restore/to-commit-id
   #uuid "66666666-6666-4666-8666-666666666666"
   ::restore/to-t 536870899
   ::restore/forced-commit-id (::coordinate/commit-id predecessor)
   ::restore/undo-branch :seon.branch/undo-restore00001
   ::restore/target-branch :seon.branch/target-restore00001})

(def ^:private completion
  (assoc completion-claim ::restore/id "restore00001"))

(def ^:private installed-schema
  (into {} (map (fn [attr] [attr {}])) restore/completion-attrs))

(defn- publication-rows [value tx]
  (->> (keys value)
       (mapv (fn [attr] [attr tx]))
       (sort-by (comp str first))
       vec))

(defn- acquired
  ([coordinate] (acquired coordinate nil))
  ([coordinate value]
   {::db/coordinate coordinate
    ::db/results
    [{::protocol/success? true
      ::protocol/schema installed-schema}
     {::protocol/success? true
      ::protocol/result value}
     {::protocol/success? true
      :datahike.query/result
      (if value
        (publication-rows value (::coordinate/t completion-point))
        [])}
     {::protocol/success? true
      :datahike.query/result :seon.db.id.generator/compact}]}))

(defn- run-stubbed
  [execute-many allocate! resolve-coordinate! body done]
  (let [original-execute-many db/execute-many
        original-allocate! db.id/allocate!
        original-resolve db/resolve-transaction-coordinate!]
    (set! db/execute-many execute-many)
    (set! db.id/allocate! allocate!)
    (set! db/resolve-transaction-coordinate! resolve-coordinate!)
    (-> (js/Promise.resolve (body))
        (.catch (fn [error] (is false (str "restore proof threw: " error))))
        (.finally
          (fn []
            (set! db/execute-many original-execute-many)
            (set! db.id/allocate! original-allocate!)
            (set! db/resolve-transaction-coordinate! original-resolve)
            (done))))))

(defn- unexpected [operation]
  (fn [& _]
    (js/Promise.reject (js/Error. (str "unexpected " operation)))))

(deftest completion-schema-is-the-architecture-fact
  (is (m/validate ::restore/completion-claim completion-claim))
  (is (not (contains? completion-claim ::restore/id)))
  (is (m/validate ::restore/current-completion completion))
  (is (not (m/validate ::restore/completion-claim
                       (assoc completion-claim ::restore/status :done))))
  (is (= restore/completion-attrs
         [::restore/id ::restore/plan-digest ::restore/db-name
          ::restore/database-id ::restore/from-branch ::restore/from-commit-id
          ::restore/from-t ::restore/to-branch ::restore/to-commit-id
          ::restore/to-t ::restore/forced-commit-id ::restore/undo-branch
          ::restore/target-branch ::restore/core-overlay-digest
          ::restore/config-overlay-digest])))

(deftest record-acquires-once-and-pins-exact-readback
  (async done
    (let [requests (atom [])
          allocation (atom nil)
          responses (atom [(acquired predecessor)
                           (acquired completion-point completion)])]
      (run-stubbed
        (fn [request]
          (swap! requests conj request)
          (let [[before _] (swap-vals! responses #(vec (rest %)))]
            (js/Promise.resolve (first before))))
        (fn [request]
          (reset! allocation request)
          (let [built ((::db.id/transaction-builder request)
                       {::restore/completion "restore00001"})]
            (is (= predecessor (:seon.db/expected-coordinate built)))
            (is (= [completion] (:seon.db/tx-data built)))
            (is (= [{::db.id/candidate-key ::restore/completion
                     ::db.id/lookup-ref [::restore/plan-digest plan-digest]}]
                   (::db.id/dependent-identities built)))
            (js/Promise.resolve
              {:seon.db/ok? true
               :seon.db/coordinate completion-point
               ::db.id/ids {::restore/completion "restore00001"}})))
        (unexpected "coordinate resolution")
        (fn ^:async run []
          (let [result
                (await
                  (restore/record!
                    {::restore/completion-claim completion-claim
                     ::restore/expected-coordinate predecessor}))]
            (is (true? (::restore/ok? result)))
            (is (true? (::restore/recorded? result)))
            (is (false? (::restore/already-completed? result)))
            (is (= completion-point (::restore/completion-coordinate result)))
            (is (= 4 (count (::db/members (first @requests)))))
            (is (not (contains? (first @requests) ::db/coordinate)))
            (is (= completion-point (::db/coordinate (second @requests))))
            (is (= {::restore/id :seon.db.id.generator/compact}
                   (::db.id/generator-policies @allocation)))))
        done))))

(deftest exact-and-later-head-retries-do-not-write
  (async done
    (let [requests (atom [])
          resolution (atom nil)]
      (run-stubbed
        (fn [request]
          (swap! requests conj request)
          (js/Promise.resolve
            (acquired (if (= 1 (count @requests)) completion-point later-point)
                      completion)))
        (unexpected "allocation")
        (fn [request]
          (reset! resolution request)
          (js/Promise.resolve completion-point))
        (fn ^:async run []
          (let [exact (await
                        (restore/record!
                          {::restore/completion-claim completion-claim
                           ::restore/expected-coordinate predecessor}))
                later (await
                        (restore/record!
                          {::restore/completion-claim completion-claim
                           ::restore/expected-coordinate predecessor}))]
            (is (true? (::restore/already-completed? exact)))
            (is (= completion-point (::restore/completion-coordinate exact)))
            (is (= completion-point (::restore/completion-coordinate later)))
            (is (= {:seon.db/head-coordinate later-point
                    :seon.db/transaction-id (::coordinate/t completion-point)}
                   @resolution))))
        done))))

(deftest stale-predecessor-and-conflicting-winner-fail-closed
  (async done
    (let [responses
          (atom [(acquired later-point)
                 (acquired predecessor)
                 (acquired completion-point
                           (assoc completion ::restore/db-name :other))])
          allocations (atom 0)]
      (run-stubbed
        (fn [_]
          (let [[before _] (swap-vals! responses #(vec (rest %)))]
            (js/Promise.resolve (first before))))
        (fn [_]
          (swap! allocations inc)
          (js/Promise.resolve
            {:seon.db/ok? false
             :seon.db/error {:seon.error/message "competing writer"}}))
        (unexpected "coordinate resolution")
        (fn ^:async run []
          (let [stale (await
                        (restore/record!
                          {::restore/completion-claim completion-claim
                           ::restore/expected-coordinate predecessor}))
                conflict (await
                           (restore/record!
                             {::restore/completion-claim completion-claim
                              ::restore/expected-coordinate predecessor}))]
            (is (false? (::restore/ok? stale)))
            (is (false? (::restore/ok? conflict)))
            (is (= 1 @allocations))))
        done))))

(deftest readiness-is-pure-over-ordinary-acquired-facts
  (let [rows (publication-rows completion (::coordinate/t completion-point))
        request {::restore/completion completion
                 ::restore/current-completion completion
                 ::restore/completion-coordinate completion-point
                 ::restore/current-coordinate completion-point
                 ::restore/publication-rows rows
                 :seon.runtime.admission/state
                 {:seon.runtime.admission/status :publishing}}
        expected {::restore/ready? true
                  ::restore/executable? false
                  ::restore/completion completion
                  ::restore/completion-coordinate completion-point}]
    (is (= expected (restore/readiness request)))
    (is (= {::restore/ready? false ::restore/executable? false}
           (restore/readiness
             (assoc request ::restore/current-coordinate later-point))))
    (is (= {::restore/ready? false ::restore/executable? false}
           (restore/readiness
             (assoc request ::restore/current-coordinate
                    (assoc completion-point ::coordinate/commit-id
                           (random-uuid))))))
    (is (= {::restore/ready? false ::restore/executable? true}
           (restore/readiness
             (assoc request :seon.runtime.admission/state
                    {:seon.runtime.admission/status :available}))))
    (let [invalid (restore/readiness (assoc request ::restore/unexpected true))]
      (is (false? (::restore/ok? invalid)))
      (is (false? (::restore/ready? invalid)))
      (is (false? (::restore/executable? invalid))))))
