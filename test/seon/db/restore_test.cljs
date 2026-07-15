(ns seon.db.restore-test
  "Fresh-Datahike proof for durable restore completion facts."
  (:require
    [cljs.test :refer [async deftest is]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.agent]
    [seon.agent.message]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.db.process :as process]
    [seon.db.replica :as replica]
    [seon.db.restore :as restore]))

(def ^:private completion-id "restore00001")
(def ^:private database-id
  #uuid "11111111-1111-4111-8111-111111111111")
(def ^:private from-commit-id
  #uuid "22222222-2222-4222-8222-222222222222")
(def ^:private to-commit-id
  #uuid "33333333-3333-4333-8333-333333333333")
(def ^:private forced-commit-id
  #uuid "44444444-4444-4444-8444-444444444444")

(def ^:private completion
  {::restore/id completion-id
   ::restore/db-name :default
   ::restore/database-id database-id
   ::restore/from-branch :db
   ::restore/from-commit-id from-commit-id
   ::restore/from-t 536870920
   ::restore/to-branch :seon.branch/retained
   ::restore/to-commit-id to-commit-id
   ::restore/to-t 536870900
   ::restore/forced-commit-id forced-commit-id
   ::restore/undo-branch :seon.branch/undo-restore00001
   ::restore/target-branch :seon.branch/target-restore00001})

(def ^:private completion-attrs
  [::restore/id
   ::restore/db-name
   ::restore/database-id
   ::restore/from-branch
   ::restore/from-commit-id
   ::restore/from-t
   ::restore/to-branch
   ::restore/to-commit-id
   ::restore/to-t
   ::restore/forced-commit-id
   ::restore/undo-branch
   ::restore/target-branch
   ::restore/core-overlay-digest
   ::restore/config-overlay-digest])

(defn- with-fresh-conn
  [body]
  (let [config {:store {:backend :memory :id (random-uuid)}
                :schema-flexibility :write
                :keep-history? true}]
    (-> (d/create-database config)
        (.then (fn [_] (d/connect config {:sync? false})))
        (.then
          (fn ^:async seed-provenance [conn]
            (await (db/ensure-provenance! {:seon.db/conn conn}))
            (await
              (d/transact!
                conn
                {:tx-data (db/malli->datahike-schema completion-attrs)}))
            (let [prior db/*conn*]
              (set! db/*conn* conn)
              (-> (js/Promise.resolve (body conn))
                  (.finally
                    (fn []
                      (set! db/*conn* prior)
                      (d/release conn))))))))))

(defn- record-as-boot!
  [value]
  (db/with-tx-context
    {:seon.db/user [:seon.agent/id "root"]
     :seon.db/process (process/lookup-ref ::process/boot)}
    (fn [] (restore/record! value))))

(defn- stored-completion
  [database]
  (-> (db/entity {:seon.db/db database
                  :seon.db/ref [::restore/id completion-id]})
      (select-keys (keys completion))))

(deftest completion-schema-is-the-architecture-fact
  (is (m/validate ::restore/completion completion))
  (is (m/validate ::restore/completion
                  (assoc completion
                         ::restore/core-overlay-digest "core-digest"
                         ::restore/config-overlay-digest "config-digest")))
  (is (not (m/validate ::restore/completion
                       (assoc completion ::restore/status :done)))
      "the closed fact has no phase or status")
  (is (not (m/validate ::restore/completion
                       (assoc completion ::restore/blob-digest "digest")))
      "blob verification remains a transition precondition, not a fact")
  (let [facets (db/malli->datahike-schema
                 [::restore/id
                  ::restore/db-name
                  ::restore/database-id
                  ::restore/from-branch
                  ::restore/from-commit-id
                  ::restore/from-t
                  ::restore/to-branch
                  ::restore/to-commit-id
                  ::restore/to-t
                  ::restore/forced-commit-id
                  ::restore/undo-branch
                  ::restore/target-branch
                  ::restore/core-overlay-digest
                  ::restore/config-overlay-digest])]
    (is (= 14 (count facets)) "identity plus thirteen architecture values")
    (is (= :db.unique/identity
           (:db/unique (first facets))))))

(deftest completion-schema-precedes-its-generator-policy
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (let [database @conn
                  installed (db/installed-schema database)
                  policies (db.id/generator-policies
                             {::db.id/db-value database})
                  [schema-t policy-t]
                  (first
                    (db/query
                      {:seon.db/db (db/history database)
                       :seon.db/query
                       '[:find ?schema-t ?policy-t
                         :where
                         [_ :db/ident :seon.db.restore/id ?schema-t true]
                         [?schema :seon.schema/key :seon.db.restore/id]
                         [?schema :seon.db.id/generator
                          :seon.db.id.generator/compact ?policy-t true]]}))]
              (is (every? #(contains? installed %) restore/completion-attrs)
                  "fresh pod schema installs the complete restore fact")
              (is (= :seon.db.id.generator/compact
                     (get policies ::restore/id)))
              (is (and (int? schema-t) (int? policy-t) (< schema-t policy-t))
                  "native identity schema commits before generator policy")
              (d/release conn))))
        (.then (fn [_] (done)))
        (.catch
          (fn [error]
            (is false (str "fresh restore schema proof threw " error))
            (done))))))

(deftest record-commits-one-exact-fact-with-root-boot-provenance
  (async done
    (-> (with-fresh-conn
          (fn ^:async prove-record [conn]
            (let [result (await (record-as-boot! completion))
                  coordinate (::restore/completion-coordinate result)
                  database @conn
                  transaction (::coordinate/t coordinate)
                  transaction-entity
                  (db/entity {:seon.db/db database
                              :seon.db/ref transaction})]
              (is (true? (::restore/ok? result)))
              (is (true? (::restore/recorded? result)))
              (is (false? (::restore/already-completed? result)))
              (is (= completion (::restore/completion result)))
              (is (= completion (stored-completion database)))
              (is (= coordinate (db/head-coordinate database)))
              (is (= "root"
                     (get-in transaction-entity
                             [:seon.db/user :seon.agent/id])))
              (is (= ::process/boot
                     (get-in transaction-entity
                             [:seon.db/process ::process/id]))))))
        (.then (fn [_] (done)))
        (.catch (fn [exception]
                  (is false (str "completion record threw: " exception))
                  (done))))))

(deftest exact-retry-returns-original-head-without-a-transaction
  (async done
    (-> (with-fresh-conn
          (fn ^:async prove-retry [conn]
            (let [first-result (await (record-as-boot! completion))
                  first-head (db/head-coordinate @conn)
                  retry-result (await (record-as-boot! completion))
                  retry-head (db/head-coordinate @conn)]
              (is (true? (::restore/ok? first-result)))
              (is (true? (::restore/ok? retry-result)))
              (is (false? (::restore/recorded? retry-result)))
              (is (true? (::restore/already-completed? retry-result)))
              (is (= first-head retry-head)
                  "an equal completion retry emits no transaction")
              (is (= first-head
                     (::restore/completion-coordinate retry-result))))))
        (.then (fn [_] (done)))
        (.catch (fn [exception]
                  (is false (str "completion retry threw: " exception))
                  (done))))))

(deftest later-head-retry-returns-the-original-completion-coordinate
  (async done
    (-> (with-fresh-conn
          (fn ^:async prove-later-head-gap [conn]
            (let [first-result (await (record-as-boot! completion))
                  completion-coordinate
                  (::restore/completion-coordinate first-result)
                  later-envelope
                  (await
                    (db/with-tx-context
                      {:seon.db/user [:seon.agent/id "root"]
                       :seon.db/process (process/lookup-ref ::process/boot)}
                      (fn []
                        (db/transact!
                          {:seon.db/tx-data
                           [{:seon.user/id "later-user"}]}))))
                  later-head (db/head-coordinate @conn)
                  resolver-requests (atom [])
                  retry-result
                  (await
                   (with-redefs
                     [replica/resolve-transaction-coordinate!
                      (fn ^:async resolve-completion [request]
                        (swap! resolver-requests conj request)
                        {::protocol/success? true
                         ::protocol/coordinate completion-coordinate})]
                     (record-as-boot! completion)))]
              (is (true? (::restore/ok? first-result)))
              (is (true? (:seon.db/ok? later-envelope)))
              (is (not= completion-coordinate later-head)
                  "the completion transaction is no longer the branch head")
              (is (true? (::restore/ok? retry-result)))
              (is (true? (::restore/already-completed? retry-result)))
              (is (= completion-coordinate
                     (::restore/completion-coordinate retry-result)))
              (is (= [{::protocol/head-coordinate later-head
                       ::protocol/transaction-id
                       (::coordinate/t completion-coordinate)}]
                     @resolver-requests))
              (is (= later-head (db/head-coordinate @conn))
                  "the resolved retry emits no transaction"))))
        (.then (fn [_] (done)))
        (.catch (fn [exception]
                  (is false (str "later-head retry threw: " exception))
                  (done))))))

(deftest conflicting-same-id-fails-closed-without-changing-the-fact
  (async done
    (-> (with-fresh-conn
          (fn ^:async prove-conflict [conn]
            (let [first-result (await (record-as-boot! completion))
                  first-head (db/head-coordinate @conn)
                  conflicting (assoc completion
                                     ::restore/core-overlay-digest
                                     "not-the-selected-overlay")
                  conflict-result (await (record-as-boot! conflicting))]
              (is (true? (::restore/ok? first-result)))
              (is (false? (::restore/ok? conflict-result)))
              (is (map? (:seon/error conflict-result)))
              (is (= first-head (db/head-coordinate @conn))
                  "a conflicting identity emits no transaction")
              (is (= completion (stored-completion @conn))
                  "optional absence remains semantically load-bearing"))))
        (.then (fn [_] (done)))
        (.catch (fn [exception]
                  (is false (str "completion conflict threw: " exception))
                  (done))))))

(deftest coordinate-resolution-preserves-the-writer-error-kind
  (async done
    (let [head {::coordinate/database-id database-id
                ::coordinate/branch :db
                ::coordinate/commit-id forced-commit-id
                ::coordinate/t 536870930}]
      (-> (js/Promise.resolve
           (with-redefs
             [replica/resolve-transaction-coordinate!
              (fn ^:async fail-resolution [_request]
                {::protocol/success? false
                 ::protocol/error-kind protocol/non-ancestor-error
                 ::protocol/error "frozen head is not an ancestor"})]
             (db/resolve-transaction-coordinate!
              {:seon.db/head-coordinate head
               :seon.db/transaction-id 536870929})))
          (.then
           (fn [result]
             (is (= protocol/non-ancestor-error
                    (get-in result
                            [:seon.error/ex-data
                             ::protocol/error-kind])))
             (done)))
          (.catch
           (fn [exception]
             (is false (str "coordinate failure mapping threw: " exception))
             (done)))))))
