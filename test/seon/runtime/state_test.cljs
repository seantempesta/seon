(ns seon.runtime.state-test
  "Contract tests for `seon.runtime.state/reconcile!` (the holistic declarative-state
   sync primitive). The tests keep one ordinary database value across each
   coherent operation and exercise the pure compiler with authority-shaped
   acquisition data."
  (:require
    [cljs.test :refer [deftest is async]]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.schema :as schema]
    [seon.runtime.state :as state]))

;; --- scratch attrs: MIXED identity namespaces + a component parent ----------
;; Two identity attrs of DIFFERENT namespaces (proves reconcile keys off each
;; row's OWN identity attr, no taxonomy), plus a component-vector parent + a
;; non-identity child attr (proves the retract cascade).
(schema/register! :seon.runtime.state.scratch.a/id     [:string {:seon.db/identity true}])
(schema/register! :seon.runtime.state.scratch.a/label  :string)
(schema/register! :seon.runtime.state.scratch.a/tags   [:set :keyword])
(schema/register! :seon.runtime.state.scratch.b/id     [:string {:seon.db/identity true}])
(schema/register! :seon.runtime.state.scratch.b/note   :string)
(schema/register! :seon.runtime.state.scratch.parent/id   [:string {:seon.db/identity true}])
(schema/register! :seon.runtime.state.scratch.child/name  :keyword)
(schema/register! :seon.runtime.state.scratch.parent/kids
                  [:vector {:seon.db/component true} :seon.db/ref])
;; The first-use test returns these attrs only after the writer reports that
;; the initial database value became stale.
(schema/register! :seon.runtime.state.scratch.late/id
                  [:string {:seon.db/identity true}])
(schema/register! :seon.runtime.state.scratch.late/value :string)

(def ^:private desired-state
  [{:seon.runtime.state.scratch.a/id "keep1"
    :seon.runtime.state.scratch.a/label "new"
    :seon.runtime.state.scratch.a/tags #{:new :kept}}
   ;; :seon.runtime.state.scratch.b/note is deliberately omitted: exact desired
   ;; state retracts the stale scalar from this retained entity.
   {:seon.runtime.state.scratch.b/id "keepB"}
   {:seon.runtime.state.scratch.a/id "fresh1"
    :seon.runtime.state.scratch.a/label "added"}
   ;; A retained component collection is replaced exactly and its old owned
   ;; children must cascade away.
   {:seon.runtime.state.scratch.parent/id "pkeep"
    :seon.runtime.state.scratch.parent/kids
    [{:seon.runtime.state.scratch.child/name :gk3}]}])

(deftest acquisition-joins-one-pull-to-many-provenance-rows
  (let [entity {:db/id 41
                :seon.runtime.state.scratch.parent/id "one-pull"
                :seon.runtime.state.scratch.parent/kids
                [{:db/id 42 :seon.runtime.state.scratch.child/name :child}]}
        rows ((deref #'state/acquisition-rows)
              [[41 entity]]
              [[41 103] [41 101] [41 102]]
              [[103 :seon.db.process/config]
               [101 :seon.db.process/boot]
               [102 :seon.db.process/config]])]
    (is (= 1 (count rows))
        "one pulled component tree survives many scalar provenance rows")
    (is (= entity (:seon.runtime.state/entity (first rows)))
        "the component payload is neither duplicated nor rebuilt")
    (is (= 101 (:seon.runtime.state/first-tx (first rows))))
    (is (= :seon.db.process/boot
           (:seon.runtime.state/first-process (first rows)))
        "the process follows the minimum transaction")))

(deftest acquisition-does-not-manage-an-unattributed-first-transaction
  (let [entity {:db/id 51 :seon.runtime.state.scratch.a/id "unattributed-first"}
        rows ((deref #'state/acquisition-rows)
              [[51 entity]]
              [[51 200] [51 201]]
              [[201 :seon.db.process/config]])]
    (is (= 200 (:seon.runtime.state/first-tx (first rows))))
    (is (nil? (:seon.runtime.state/first-process (first rows)))
        "a later config touch cannot claim an entity born unattributed")))

(deftest lookup-ref-acquisition-addresses-only-explicit-identities
  (is (= #{[:seon.runtime.state.scratch.b/id "target"]}
         ((deref #'state/lookup-ref-pairs)
          {:seon.runtime.state.scratch.a/id "owner"
           :seon.runtime.state.scratch.a/ref
           [:seon.runtime.state.scratch.b/id "target"]})))
  (is (= #{[:seon.runtime.state.scratch.b/id "target"]}
         ((deref #'state/lookup-ref-pairs)
          {:seon.runtime.state.scratch.a/children
           [{:seon.runtime.state.scratch.a/id "left"}
            {:seon.runtime.state.scratch.a/ref
             [:seon.runtime.state.scratch.b/id "target"]}]}))
      "an ordinary two-member vector is traversed, not treated as a lookup ref"))

(deftest reconcile-queries-use-identity-keywords-in-attribute-position
  (doseq [query [(deref #'state/reconcile-state-query)
                 (deref #'state/reconcile-lookup-ref-query)
                 (deref #'state/reconcile-provenance-query)
                 (deref #'state/reconcile-transaction-process-query)]]
    (is (not-any? #(= '[?attribute :db/ident ?identity-attr] %)
                  (tree-seq coll? seq query))
        "Datahike attribute positions consume the bound ident keyword, not its schema eid"))
  (is (some #{'[?e ?identity-attr _]}
            (tree-seq coll? seq (deref #'state/reconcile-state-query))))
  (is (some #{'[?e ?identity-attr ?identity-value]}
            (tree-seq coll? seq (deref #'state/reconcile-lookup-ref-query))))
  (doseq [query [(deref #'state/reconcile-state-query)
                 (deref #'state/reconcile-provenance-query)
                 (deref #'state/reconcile-transaction-process-query)]]
    (is (some #(= [:in '$ '?identity-attr] (vec %))
              (partition 3 1 query))
        "each indexed query takes one scalar attribute keyword; Datahike intentionally does not resolve collection-bound attribute keywords in attribute position")))

(def authority-database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000032"})

(def authority-database-after
  (assoc authority-database
         :t 43
         :datahike/commit-id
         #uuid "00000000-0000-0000-0000-000000000033"))

(def authority-transaction-report
  {:db-before authority-database
   :db-after authority-database-after
   :tx-data []
   :tempids {}})

(defn- authority-acquisition
  ([entity-rows provenance-rows process-rows]
   (authority-acquisition {} entity-rows provenance-rows process-rows))
  ([installed-schema entity-rows provenance-rows process-rows]
   {::db/results
    [(protocol/success {::protocol/schema installed-schema})
     (protocol/success {:datahike.query/result entity-rows})
     (protocol/success {:datahike.query/result provenance-rows})
     (protocol/success {:datahike.query/result process-rows})]}))

(defn- reconcile-request
  ([] (reconcile-request nil))
  ([database]
   (cond->
     {:seon.runtime.state/desired []
      :seon.db/managed-scope #{:seon.db.process/boot}
      :seon.db/managed-identity-attrs #{:seon.runtime.state.scratch.a/id}}
     database (assoc ::db/db database))))

(deftest reconcile-acquires-one-database-and-fences-its-transaction
  (async done
    (let [original-db db/db
          original-execute db/execute-many
          original-transact db/transact!
          calls (atom [])
          restore! (fn []
                     (set! db/db original-db)
                     (set! db/execute-many original-execute)
                     (set! db/transact! original-transact))]
      (set! db/db
            (fn
              ([]
               (swap! calls conj [:db nil])
               (js/Promise.resolve authority-database))
              ([request]
               (js/Promise.reject
                (js/Error. (str "unexpected named database read " request))))))
      (set! db/execute-many
            (fn [request]
              (swap! calls conj [:acquire request])
              (js/Promise.resolve
               (authority-acquisition
                [[41 {:db/id 41 :seon.runtime.state.scratch.a/id "stale"}]]
                [[41 100]]
                [[100 :seon.db.process/boot]]))))
      (set! db/transact!
            (fn [& [request]]
              (swap! calls conj [:transact request])
              (js/Promise.resolve authority-transaction-report)))
      (-> (state/reconcile! (reconcile-request))
          (.then
           (fn [result]
             (let [[_ [_ acquire] [_ transact]] @calls]
               (is (= [:db :acquire :transact] (mapv first @calls)))
               (is (identical? authority-database (::db/db acquire)))
               (is (= 4 (count (::db/members acquire))))
               (is (identical? authority-database (::db/db transact)))
               (is (identical? authority-database (::db/expected-db transact)))
               (is (= [[:db.fn/retractEntity 41]] (::db/tx-data transact)))
               (is (= {:seon.runtime.state/ok? true
                       :seon.runtime.state/changed? true
                       :seon.runtime.state/operations 1
                       :seon.runtime.state/attempts 1}
                      result)))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (restore!) (done)))))))

(deftest explicit-database-convergence-performs-no-read-or-write
  (async done
    (let [original-db db/db
          original-execute db/execute-many
          original-transact db/transact!
          calls (atom [])
          restore! (fn []
                     (set! db/db original-db)
                     (set! db/execute-many original-execute)
                     (set! db/transact! original-transact))]
      (set! db/db
            (fn
              ([] (js/Promise.reject (js/Error. "unexpected current read")))
              ([_] (js/Promise.reject (js/Error. "unexpected named read")))))
      (set! db/execute-many
            (fn [request]
              (swap! calls conj [:acquire request])
              (js/Promise.resolve (authority-acquisition [] [] []))))
      (set! db/transact!
            (fn [& _]
              (swap! calls conj [:transact])
              (js/Promise.reject (js/Error. "convergence wrote"))))
      (-> (state/reconcile! (reconcile-request authority-database))
          (.then
           (fn [result]
             (is (= [:acquire] (mapv first @calls)))
             (is (identical? authority-database
                             (::db/db (second (first @calls)))))
             (is (= {:seon.runtime.state/ok? true
                     :seon.runtime.state/changed? false
                     :seon.runtime.state/operations 0
                     :seon.runtime.state/attempts 1}
                    result))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (restore!) (done)))))))

(deftest reconcile-returns-a-direct-database-error
  (async done
    (let [original-execute db/execute-many
          original-transact db/transact!
          database-error {:seon.error/message "write refused"
                          :seon.error/kind :core-bug}
          restore! (fn []
                     (set! db/execute-many original-execute)
                     (set! db/transact! original-transact))]
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               (authority-acquisition
                [[41 {:db/id 41 :seon.runtime.state.scratch.a/id "stale"}]]
                [[41 100]]
                [[100 :seon.db.process/boot]]))))
      (set! db/transact!
            (fn [& _] (js/Promise.resolve database-error)))
      (-> (state/reconcile! (reconcile-request authority-database))
          (.then (fn [result] (is (identical? database-error result))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (restore!) (done)))))))

(deftest first-use-schema-change-reacquires-and-recompiles
  (async done
    (let [original-db db/db
          original-execute db/execute-many
          original-transact db/transact!
          calls (atom [])
          desired [{:seon.runtime.state.scratch.late/id "late"
                    :seon.runtime.state.scratch.late/value "landed"}]
          request {:seon.runtime.state/desired desired
                   :seon.db/managed-scope #{:seon.db.process/boot}
                   :seon.db/managed-identity-attrs
                   #{:seon.runtime.state.scratch.late/id}
                   ::db/db authority-database}
          installed-after-first-use
          {:seon.runtime.state.scratch.late/id
           {:db/unique :db.unique/identity}
           :seon.runtime.state.scratch.late/value {}}
          stale-error
          {:seon.error/message "database advanced"
           :seon.error/data
           {::protocol/error-kind protocol/stale-database-value-error}}
          restore! (fn []
                     (set! db/db original-db)
                     (set! db/execute-many original-execute)
                     (set! db/transact! original-transact))]
      (set! db/db
            (fn
              ([] (js/Promise.reject (js/Error. "explicit db should be used")))
              ([request]
               (swap! calls conj [:db request])
               (js/Promise.resolve authority-database-after))))
      (set! db/execute-many
            (fn [request]
              (swap! calls conj [:acquire request])
              (js/Promise.resolve
               (authority-acquisition
                (if (identical? authority-database (::db/db request))
                  {}
                  installed-after-first-use)
                [] [] []))))
      (set! db/transact!
            (fn [& [request]]
              (swap! calls conj [:transact request])
              (js/Promise.resolve
               (if (identical? authority-database (::db/db request))
                 stale-error
                 (assoc authority-transaction-report
                        :db-before authority-database-after)))))
      (-> (state/reconcile! request)
          (.then
           (fn [result]
             (is (= {:seon.runtime.state/ok? true
                     :seon.runtime.state/changed? true
                     :seon.runtime.state/operations 1
                     :seon.runtime.state/attempts 2}
                    result))
             (is (= [:acquire :transact :db :acquire :transact]
                    (mapv first @calls)))
             (is (= "default"
                    (::db/database-name (second (nth @calls 2)))))
             (is (= desired (::db/tx-data (second (nth @calls 1)))))
             (is (= desired (::db/tx-data (second (nth @calls 4)))))
             (is (identical? authority-database-after
                             (::db/db (second (nth @calls 3)))))
             (is (identical? authority-database-after
                             (::db/expected-db (second (nth @calls 4)))))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (restore!) (done)))))))

(def ^:private installed-scratch-schema
  {:seon.runtime.state.scratch.a/id {:db/unique :db.unique/identity}
   :seon.runtime.state.scratch.a/label {}
   :seon.runtime.state.scratch.a/tags {:db/cardinality :db.cardinality/many}
   :seon.runtime.state.scratch.b/id {:db/unique :db.unique/identity}
   :seon.runtime.state.scratch.b/note {}
   :seon.runtime.state.scratch.parent/id {:db/unique :db.unique/identity}
   :seon.runtime.state.scratch.child/name {}
   :seon.runtime.state.scratch.parent/kids
   {:db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/isComponent true}})

(def ^:private managed-identity-attrs
  #{:seon.runtime.state.scratch.a/id
    :seon.runtime.state.scratch.b/id
    :seon.runtime.state.scratch.parent/id})

(def ^:private managed-processes
  #{:seon.db.process/boot :seon.db.process/config})

(defn- acquired-row
  [entity process]
  {::state/entity entity
   ::state/first-tx (:db/id entity)
   ::state/first-process process})

(defn- compile-state
  [rows desired]
  ((deref #'state/compile-reconcile-tx)
   {::state/installed-schema installed-scratch-schema
    ::state/rows rows}
   desired
   managed-processes
   managed-identity-attrs))

(deftest compiler-adds-updates-and-retracts-one-managed-population
  (let [rows
        [(acquired-row
          {:db/id 1
           :seon.runtime.state.scratch.a/id "keep1"
           :seon.runtime.state.scratch.a/label "old"
           :seon.runtime.state.scratch.a/tags #{:old :drop}}
          :seon.db.process/boot)
         (acquired-row
          {:db/id 2
           :seon.runtime.state.scratch.a/id "stale1"
           :seon.runtime.state.scratch.a/label "doomed"}
          :seon.db.process/boot)
         (acquired-row
          {:db/id 3
           :seon.runtime.state.scratch.b/id "keepB"
           :seon.runtime.state.scratch.b/note "remove-me"}
          :seon.db.process/config)
         (acquired-row
          {:db/id 4
           :seon.runtime.state.scratch.parent/id "pkeep"
           :seon.runtime.state.scratch.parent/kids
           [{:db/id 40 :seon.runtime.state.scratch.child/name :gk-old-1}
            {:db/id 41 :seon.runtime.state.scratch.child/name :gk-old-2}]}
          :seon.db.process/boot)
         (acquired-row
          {:db/id 5
           :seon.runtime.state.scratch.parent/id "pstale"
           :seon.runtime.state.scratch.parent/kids
           [{:db/id 50 :seon.runtime.state.scratch.child/name :gk1}]}
          :seon.db.process/boot)
         (acquired-row
          {:db/id 6
           :seon.runtime.state.scratch.a/id "agent1"
           :seon.runtime.state.scratch.a/label "mine"}
          :seon.db.process/repl)]
        compiled (compile-state rows desired-state)
        tx-data (::state/tx-data compiled)]
    (is (true? (::state/ok? compiled)))
    (is (some #{(nth desired-state 2)} tx-data)
        "a new desired entity is added by its identity")
    (is (some #{[:db.fn/retractAttribute
                 [:seon.runtime.state.scratch.a/id "keep1"]
                 :seon.runtime.state.scratch.a/label]}
              tx-data))
    (is (some #{[:db.fn/retractAttribute
                 [:seon.runtime.state.scratch.a/id "keep1"]
                 :seon.runtime.state.scratch.a/tags]}
              tx-data))
    (is (some #{{:seon.runtime.state.scratch.a/id "keep1"
                 :seon.runtime.state.scratch.a/label "new"
                 :seon.runtime.state.scratch.a/tags #{:new :kept}}}
              tx-data))
    (is (some #{[:db.fn/retractAttribute
                 [:seon.runtime.state.scratch.b/id "keepB"]
                 :seon.runtime.state.scratch.b/note]}
              tx-data)
        "an omitted scalar is retracted from a retained managed entity")
    (is (some #{[:db.fn/retractEntity 2]} tx-data))
    (is (some #{[:db.fn/retractEntity 5]} tx-data))
    (is (not-any?
         (fn [operation]
           (or (= [:db.fn/retractEntity 6] operation)
               (and (map? operation)
                    (= "agent1" (:seon.runtime.state.scratch.a/id operation)))))
         tx-data)
        "an entity born outside the managed processes is untouched")))

(deftest compiler-converges-without-a-transaction
  (let [rows
        (mapv (fn [eid entity]
                (acquired-row (assoc entity :db/id eid)
                              :seon.db.process/boot))
              (range 1 (inc (count desired-state)))
              desired-state)
        compiled (compile-state rows desired-state)]
    (is (= {::state/ok? true ::state/tx-data []} compiled))))

(deftest empty-cardinality-many-is-equivalent-to-absence
  (let [desired [{:seon.runtime.state.scratch.a/id "empty-many"
                  :seon.runtime.state.scratch.a/tags #{}}]
        rows [(acquired-row
               {:db/id 1 :seon.runtime.state.scratch.a/id "empty-many"}
               :seon.db.process/boot)]]
    (is (= {::state/ok? true ::state/tx-data []}
           (compile-state rows desired)))))

(deftest identity-less-input-is-rejected-before-a-database-read
  (async done
    (let [original-db db/db
          calls (atom [])
          restore! (fn [] (set! db/db original-db))]
      (set! db/db
            (fn
              ([] (swap! calls conj :db)
                  (js/Promise.reject (js/Error. "database read occurred")))
              ([_] (swap! calls conj :named-db)
                   (js/Promise.reject (js/Error. "database read occurred")))))
      (-> (state/reconcile!
           {:seon.runtime.state/desired
            [{:seon.runtime.state.scratch.a/label "no identity"}]
            :seon.db/managed-scope #{:seon.db.process/boot}
            :seon.db/managed-identity-attrs
            #{:seon.runtime.state.scratch.a/id}})
          (.then
           (fn [result]
             (is (false? (::state/ok? result)))
             (is (string? (::state/error result)))
             (is (empty? @calls))))
          (.catch (fn [exception] (is false (str exception))))
          (.finally (fn [] (restore!) (done)))))))

(deftest unmanaged-identity-collision-performs-no-write
  (async done
    (let [original-execute db/execute-many
          original-transact db/transact!
          writes (atom [])
          restore! (fn []
                     (set! db/execute-many original-execute)
                     (set! db/transact! original-transact))]
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               (authority-acquisition
                installed-scratch-schema
                [[41 {:db/id 41
                      :seon.runtime.state.scratch.a/id "owned-elsewhere"
                      :seon.runtime.state.scratch.a/label "preserve"}]]
                [[41 100]]
                [[100 :seon.db.process/repl]]))))
      (set! db/transact!
            (fn [& [request]]
              (swap! writes conj request)
              (js/Promise.reject (js/Error. "collision wrote"))))
      (-> (state/reconcile!
           {:seon.runtime.state/desired
            [{:seon.runtime.state.scratch.a/id "owned-elsewhere"
              :seon.runtime.state.scratch.a/label "must-not-land"}]
            :seon.db/managed-scope #{:seon.db.process/boot}
            :seon.db/managed-identity-attrs
            #{:seon.runtime.state.scratch.a/id}
            ::db/db authority-database})
          (.then
           (fn [result]
             (is (false? (::state/ok? result)))
             (is (= 1 (::state/attempts result)))
             (is (empty? @writes))))
          (.catch (fn [exception] (is false (str exception))))
          (.finally (fn [] (restore!) (done)))))))

(deftest component-replacement-and-parent-removal-use-cascading-operations
  (let [rows
        [(acquired-row
          {:db/id 4
           :seon.runtime.state.scratch.parent/id "pkeep"
           :seon.runtime.state.scratch.parent/kids
           [{:db/id 40 :seon.runtime.state.scratch.child/name :old}]}
          :seon.db.process/boot)
         (acquired-row
          {:db/id 5
           :seon.runtime.state.scratch.parent/id "pstale"
           :seon.runtime.state.scratch.parent/kids
           [{:db/id 50 :seon.runtime.state.scratch.child/name :orphan-if-not-cascaded}]}
          :seon.db.process/boot)]
        desired
        [{:seon.runtime.state.scratch.parent/id "pkeep"
          :seon.runtime.state.scratch.parent/kids
          [{:seon.runtime.state.scratch.child/name :new}]}]
        tx-data (::state/tx-data (compile-state rows desired))]
    (is (some #{[:db.fn/retractAttribute
                 [:seon.runtime.state.scratch.parent/id "pkeep"]
                 :seon.runtime.state.scratch.parent/kids]}
              tx-data)
        "replacing a component collection retracts the owning attribute")
    (is (some #{[:db.fn/retractEntity 5]} tx-data)
        "removing a component parent retracts the parent entity")
    (is (not-any? #(= :db/retract (first %))
                  (filter vector? tx-data))
        "plain edge retractions cannot leave component children orphaned")))
