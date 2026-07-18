(ns seon.agent.multiagent-test
  "Focused authority-facade proof for agent birth, delegation, and metadata."
  (:require
   [clojure.string :as str]
   [cljs.test :refer [async deftest is]]
   [seon.agent :as agent]
   [seon.agent.ctx :as ctx]
   [seon.agent.message :as message]
   [seon.agent.runtime :as runtime]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.id :as db.id]
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
              (is (= configuration @received-configuration)
                  "creation receives the decoded ordinary singleton")
              (is (identical? database (::db/db @transaction)))
              (is (identical? database (::db/expected-db @transaction)))
              (let [[agent-row home-row] (::db/tx-data @transaction)]
                (is (= configured-requires
                       (:seon.eval/home-requires agent-row)))
                (is (str/includes? (:seon.ns/source home-row)
                                   "[seon.db :as configured-db]"))))))
       done
       [[#(set! db/db %) db!]
        [#(set! db/pull-many %) pull-many]
        [#(set! db/transact! %) transact!]
        [#(set! ctx/initial-agent-context %) initial-agent-context]]))))

(deftest delegate-commits-child-and-first-task-once-before-hosting
  (async done
    (let [available? admission/available?
          current-agent-id db/current-agent-id
          db! db/db
          pull-many db/pull-many
          initial-agent-context ctx/initial-agent-context
          initial-message message/initial-agent-transaction
          allocate! db.id/allocate!
          resume! runtime/resume!
          standalone-message message/agent
          allocation (atom nil)
          committed? (atom false)
          hosted (atom nil)
          creation-configurations (atom [])
          standalone-calls (atom 0)]
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
               (is (= [[:seon.agent/id "parent"]
                       [:seon.config/id config/cluster-config-id]]
                      (::db/refs request)))
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
                (reset! committed? true)
                (js/Promise.resolve
                 {:db-before database
                  :db-after database-after
                  :tx-data (::db/tx-data built)
                  :tempids {}
                  ::db.id/ids ids}))))
      (set! runtime/resume!
            (fn [request]
              (is @committed? "hosting begins only after the database commit")
              (reset! hosted request)
              (js/Promise.resolve
               {:seon.agent/id "child"
                :seon.agent/ns 'my.agent.child
                :seon.agent.runtime/resumed? true})))
      (set! message/agent
            (fn [& _]
              (swap! standalone-calls inc)
              (js/Promise.resolve {:seon.error/message "forbidden"})))
      (finish!
       (-> (agent/delegate!
            {:seon.agent/purpose "research"
             :seon.agent.message/content "investigate"})
           (.then
            (fn [result]
              (is (= {:seon.agent/id "child"} result))
              (is (= {:seon.agent/id "child"} @hosted))
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
        [#(set! db/pull-many %) pull-many]
        [#(set! ctx/initial-agent-context %) initial-agent-context]
        [#(set! message/initial-agent-transaction %) initial-message]
        [#(set! db.id/allocate! %) allocate!]
        [#(set! runtime/resume! %) resume!]
        [#(set! message/agent %) standalone-message]]))))

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
