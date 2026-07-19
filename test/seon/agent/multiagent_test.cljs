(ns seon.agent.multiagent-test
  "Focused authority-facade proof for agent birth, delegation, and metadata."
  (:require
   [clojure.string :as str]
   [cljs.test :refer [async deftest is]]
   [seon.agent :as agent]
   [seon.agent.ctx :as ctx]
   [seon.agent.message :as message]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.id :as db.id]
   [seon.db.protocol :as protocol]
   [seon.derive :as derive]
   [seon.runtime.admission :as admission]))

(def database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(def database-after
  (assoc database :t 43
         :datahike/commit-id
         #uuid "cccccccc-cccc-4ccc-8ccc-cccccccccccc"))

(def latest-database
  (assoc database :t 44
         :datahike/commit-id
         #uuid "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))

(def configuration
  (config/resolve-config-singleton {}))

(def stored-configuration
  (update configuration :seon.config/always pr-str))

(defn- finish!
  [promise done restorations]
  (-> promise
      (.catch (fn [error] (is false (str "unexpected rejection: " error))))
      (.finally
       (fn []
         (doseq [[restore value] restorations]
           (restore value))
         (done)))))

(deftest agent-id-readers-use-one-ordinary-database-value
  (async done
    (let [db! db/db
          armable derive/armable-agent-ids
          resumable derive/resumable-agent-ids
          db-calls (atom 0)
          calls (atom [])]
      (set! db/db
            (fn
              ([]
               (swap! db-calls inc)
               (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! derive/armable-agent-ids
            (fn [db-value]
              (swap! calls conj [:armable db-value])
              (js/Promise.resolve ["idle"])))
      (set! derive/resumable-agent-ids
            (fn [db-value]
              (swap! calls conj [:resumable db-value])
              (js/Promise.resolve ["idle" "running"])))
      (finish!
       (-> (agent/armable-agent-ids {})
           (.then (fn [ids]
                    (is (= ["idle"] ids))
                    (agent/resumable-agent-ids!
                     {:seon.db/db database-after})))
           (.then (fn [ids]
                    (is (= ["idle" "running"] ids))
                    (is (= 1 @db-calls)
                        "an explicit database value performs no ambient read")
                    (agent/resumable-agent-ids!)))
           (.then (fn [ids]
                    (is (= ["idle" "running"] ids))
                    (is (= 2 @db-calls))
                    (is (= [[:armable database]
                            [:resumable database-after]
                            [:resumable database]]
                           @calls)))))
       done
       [[#(set! db/db %) db!]
        [#(set! derive/armable-agent-ids %) armable]
        [#(set! derive/resumable-agent-ids %) resumable]]))))

(deftest spawn-depth-is-derived-from-one-pulled-parent-tree
  (async done
    (let [pull db/pull
          requests (atom [])]
      (set! db/pull
            (fn
              ([request]
               (swap! requests conj request)
               (js/Promise.resolve
                {:seon.agent/id "child"
                 :seon.agent/parent
                 {:seon.agent/id "parent"
                  :seon.agent/parent {:seon.agent/id "root"}}}))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (finish!
       (-> (agent/spawn-depth database "child")
           (.then
            (fn [depth]
              (is (= 2 depth))
              (is (= 1 (count @requests)))
              (is (identical? database (::db/db (first @requests)))))))
       done
       [[#(set! db/pull %) pull]]))))

(deftest create-reuses-one-decoded-configuration-for-complete-birth
  (async done
    (let [db! db/db
          pull-many db/pull-many
          transact! db/transact!
          initial-agent-context ctx/initial-agent-context
          pull-request (atom nil)
          transaction (atom nil)
          received-configuration (atom nil)
          configured-requires '[[seon.db :as configured-db]]]
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! db/pull-many
            (fn
              ([request]
               (reset! pull-request request)
               (js/Promise.resolve [nil nil stored-configuration]))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull-many arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull-many arity")))))
      (set! ctx/initial-agent-context
            (fn [request]
              (reset! received-configuration
                      (:seon.config/configuration request))
              {:seon.eval/home-requires configured-requires}))
      (set! db/transact!
            (fn [& call-args]
              (let [request (first call-args)]
                (reset! transaction request)
                (js/Promise.resolve
                 {:db-before database
                  :db-after database-after
                  :tx-data (::db/tx-data request)
                  :tempids {}}))))
      (finish!
       (-> (agent/create! {:seon.agent/id "created"})
           (.then
            (fn [result]
              (is (= {:seon.agent/id "created"} result))
              (is (identical? database (::db/db @pull-request)))
              (is (= [[:seon.agent/id "created"]
                      [:seon.ns/name :my.agent.created]
                      [:seon.config/id config/cluster-config-id]]
                     (::db/refs @pull-request)))
              (is (= 4096 (::db/max-results @pull-request)))
              (is (= configuration @received-configuration)
                  "creation receives the decoded ordinary singleton")
              (is (identical? database (::db/db @transaction)))
              (is (identical? database (::db/expected-db @transaction)))
              (let [[home-row agent-row] (::db/tx-data @transaction)]
                (is (= configured-requires
                       (:seon.eval/home-requires agent-row)))
                (is (= (:db/id home-row)
                       (:seon.agent/namespace agent-row))
                    "a new namespace and its agent share one transaction tempid")
                (is (= :my.agent.created (:seon.ns/name home-row)))
                (is (str/includes? (:seon.ns/source home-row)
                                   "[seon.db :as configured-db]"))))))
       done
       [[#(set! db/db %) db!]
        [#(set! db/pull-many %) pull-many]
        [#(set! db/transact! %) transact!]
        [#(set! ctx/initial-agent-context %) initial-agent-context]]))))

(deftest delegate-commits-child-and-first-task-once
  (async done
    (let [available? admission/available?
          current-agent-id db/current-agent-id
          db! db/db
          query db/query
          pull-many db/pull-many
          initial-agent-context ctx/initial-agent-context
          initial-message message/initial-agent-transaction
          allocate! db.id/allocate!
          standalone-message message/agent
          allocation (atom nil)
          namespace-reads (atom 0)
          creation-configurations (atom [])
          standalone-calls (atom 0)]
      (set! admission/available? (constantly true))
      (set! db/current-agent-id (constantly "parent"))
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! db/query
            (fn [request]
              (is (identical? database (::db/db request)))
              (is (= 64 (::db/max-results request)))
              (let [read (swap! namespace-reads inc)]
                (js/Promise.resolve (when (= 2 read) 7000)))))
      (set! db/pull-many
            (fn
              ([request]
               (is (identical? database (::db/db request)))
               (is (= [[:seon.agent/id "parent"]
                       [:seon.config/id config/cluster-config-id]]
                      (::db/refs request)))
               (is (= 4096 (::db/max-results request)))
               (js/Promise.resolve
                [{:seon.agent/id "parent"} stored-configuration]))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull-many arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull-many arity")))))
      (set! ctx/initial-agent-context
            (fn [request]
              (swap! creation-configurations conj
                     (:seon.config/configuration request))
              {:seon.eval/home-requires '[[seon.db :as db]]}))
      (set! message/initial-agent-transaction
            (fn [db-value from content]
              (is (identical? database db-value))
              (is (= [:seon.agent/id "parent"] from))
              (is (= "investigate" content))
              (js/Promise.resolve
               {:seon.agent.message/allocations
                [{::db.id/key :seon.agent.message/id
                  ::db.id/identity-attr :seon.agent.message/id}]
                :seon.agent.message/transaction-builder
                (fn [ids child-ref]
                  {:seon.db/tx-data
                   [{:seon.agent.message/id
                     (get ids :seon.agent.message/id)
                     :seon.agent.message/from [:seon.agent/id "parent"]
                     :seon.agent.message/to [child-ref]
                     :seon.agent.message/content "investigate"
                     :seon.agent.message/hops 1
                     :seon.agent.message/origin :agent}]})})))
      (set! db.id/allocate!
            (fn [request]
              (reset! allocation request)
              (let [ids {:seon.agent/id "child"
                         :seon.agent.message/id "message"}
                    built ((::db.id/transaction-builder request) ids)]
                (js/Promise.resolve
                 {:db-before database
                  :db-after database-after
                  :tx-data (::db/tx-data built)
                  :tempids {}
                  ::db.id/ids ids}))))
      (set! message/agent
            (fn [& _]
              (swap! standalone-calls inc)
              (js/Promise.resolve {:seon.error/message "forbidden"})))
      (finish!
       (-> (agent/delegate!
            {:seon.agent/namespace 'my.tax
             :seon.agent/purpose "research"
             :seon.agent.message/content "investigate"})
           (.then
            (fn [result]
              (is (= {:seon.agent/id "child"} result))
              (is (zero? @standalone-calls))
              (is (= #{:seon.agent/id :seon.agent.message/id}
                     (set (map ::db.id/key
                               (::db.id/allocations @allocation)))))
              (let [built
                    ((::db.id/transaction-builder @allocation)
                     {:seon.agent/id "child"
                      :seon.agent.message/id "message"})
                    tx (::db/tx-data built)
                    child-index
                    (first (keep-indexed
                            #(when (= "child" (:seon.agent/id %2)) %1) tx))
                    message-index
                    (first (keep-indexed
                            #(when (= "message"
                                      (:seon.agent.message/id %2)) %1) tx))]
                (is (identical? database (::db/db @allocation)))
                (is (identical? database (::db/expected-db built)))
                (is (< child-index message-index)
                    "the child identity precedes its task lookup ref")
                (is (= [[:seon.agent/id "child"]]
                       (:seon.agent.message/to (nth tx message-index))))
                (is (= [:seon.ns/name :my.tax]
                       (:seon.agent/namespace (nth tx child-index))))
                (is (not-any? :seon.ns/name tx)
                    "an existing namespace declaration is not overwritten")
                (is (= [configuration configuration]
                       @creation-configurations)
                    "every child tx build receives the decoded singleton")
                (is (identical? (first @creation-configurations)
                                (second @creation-configurations))
                    "one acquired config value is reused across tx builds")))))
       done
       [[#(set! admission/available? %) available?]
        [#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! db/query %) query]
        [#(set! db/pull-many %) pull-many]
        [#(set! ctx/initial-agent-context %) initial-agent-context]
        [#(set! message/initial-agent-transaction %) initial-message]
        [#(set! db.id/allocate! %) allocate!]
        [#(set! message/agent %) standalone-message]]))))

(deftest start-reacquires-after-concurrent-births
  (async done
    (let [available? admission/available?
          current-agent-id db/current-agent-id
          db! db/db
          pull-many db/pull-many
          allocate! db.id/allocate!
          databases [database database-after latest-database]
          db-calls (atom 0)
          acquired (atom [])
          allocations (atom 0)]
      (set! admission/available? (constantly true))
      (set! db/current-agent-id (constantly nil))
      (set! db/db
            (fn
              ([]
               (let [index (swap! db-calls inc)]
                 (js/Promise.resolve (nth databases (dec index)))))
              ([_] (js/Promise.reject (js/Error. "unexpected db request")))))
      (set! db/pull-many
            (fn
              ([request]
               (swap! acquired conj (::db/db request))
               (js/Promise.resolve [stored-configuration]))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull-many arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull-many arity")))))
      (set! db.id/allocate!
            (fn [request]
              (let [attempt (swap! allocations inc)]
                (if (< attempt 3)
                  (js/Promise.resolve
                   {:seon.error/message "The database changed before commit."
                    :seon.error/data
                    {::protocol/error-kind
                     protocol/stale-database-value-error}})
                  (js/Promise.resolve
                   {:db-before latest-database
                    :db-after (assoc latest-database :t 45)
                    :tx-data []
                    :tempids {}
                    ::db.id/ids {:seon.agent/id "child"}})))))
      (finish!
       (-> (agent/start! {})
           (.then
            (fn [result]
              (is (= {:seon.agent/id "child"} result))
              (is (= databases @acquired)
                  "each stale transaction reacquires all database-derived input")
              (is (= 3 @db-calls))
              (is (= 3 @allocations)))))
       done
       [[#(set! admission/available? %) available?]
        [#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! db/pull-many %) pull-many]
       [#(set! db.id/allocate! %) allocate!]]))))

(deftest delegate-to-namespace-messages-existing-resident-without-birth
  (async done
    (let [available? admission/available?
          current-agent-id db/current-agent-id
          db! db/db
          query db/query
          pull-many db/pull-many
          message! message/message!
          allocate! db.id/allocate!
          query-count (atom 0)
          message-request (atom nil)
          allocation-count (atom 0)]
      (set! admission/available? (constantly true))
      (set! db/current-agent-id (constantly "parent"))
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! db/pull-many
            (fn
              ([request]
               (is (identical? database (::db/db request)))
               (js/Promise.resolve
                [{:seon.agent/id "parent"} stored-configuration]))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull-many arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull-many arity")))))
      (set! db/query
            (fn [request]
              (is (= 64 (::db/max-results request)))
              (js/Promise.resolve
               (if (= 1 (swap! query-count inc)) "tax-resident" 7000))))
      (set! message/message!
            (fn [request]
              (reset! message-request request)
              (js/Promise.resolve
               {:seon.agent.message/id "message"
                :seon.agent.message/hops 1})))
      (set! db.id/allocate!
            (fn [_]
              (swap! allocation-count inc)
              (js/Promise.resolve {:seon.error/message "must not allocate"})))
      (finish!
       (-> (agent/delegate!
            {:seon.agent/namespace 'my.tax
             :seon.agent.message/content "continue the tax work"})
           (.then
            (fn [result]
              (is (= {:seon.agent/id "tax-resident"} result))
              (is (zero? @allocation-count))
              (is (identical? database (::db/db @message-request)))
              (is (= [:seon.agent/id "parent"]
                     (:seon.agent.message/from @message-request)))
              (is (= [[:seon.agent/id "tax-resident"]]
                     (:seon.agent.message/to @message-request))))))
       done
       [[#(set! admission/available? %) available?]
        [#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! db/query %) query]
        [#(set! db/pull-many %) pull-many]
        [#(set! message/message! %) message!]
        [#(set! db.id/allocate! %) allocate!]]))))

(deftest set-purpose-authorizes-and-writes-at-one-database-value
  (async done
    (let [current-agent-id db/current-agent-id
          db! db/db
          pull db/pull
          transact! db/transact!
          transaction (atom nil)]
      (set! db/current-agent-id (constantly "parent"))
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! db/pull
            (fn
              ([_]
               (js/Promise.resolve
                {:seon.agent/id "child"
                 :seon.agent/parent {:seon.agent/id "parent"}}))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! db/transact!
            (fn [& args]
              (let [request (first args)]
              (reset! transaction request)
              (js/Promise.resolve
               {:db-before database :db-after database-after
                :tx-data (::db/tx-data request) :tempids {}}))))
      (finish!
       (-> (agent/set-purpose!
            {:seon.agent/id "child" :seon.agent/purpose "new purpose"})
           (.then
            (fn [result]
              (is (= {:seon.agent/id "child"
                      :seon.agent/purpose "new purpose"}
                     result))
              (is (identical? database (::db/db @transaction)))
              (is (identical? database (::db/expected-db @transaction))))))
       done
       [[#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! db/pull %) pull]
        [#(set! db/transact! %) transact!]]))))
