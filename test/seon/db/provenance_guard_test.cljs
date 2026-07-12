(ns seon.db.provenance-guard-test
  "Transaction provenance is selected once at the public transact boundary.

   The durable contract is deliberately small: one existing user ref and one
   stable process ref. Agent scopes choose agent/repl, unscoped host work
   chooses human/repl, and boot/config writers establish an explicit pair.
   Runtime-only context and caller attempts to smuggle `:seon.db/*` tx metadata
   never reach the store."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as d]
    [seon.agent]
    [seon.agent.message]
    [seon.db :as db]
    [seon.db.process :as process]
    [seon.schema :as schema]))

(schema/register! ::name :string)

(def ^:private agent-id "provenance-guard-agent")

(defn- fresh-conn
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then
          (fn ^:async seed [conn]
            (await (db/ensure-provenance! {:seon.db/conn conn}))
            (await
              (db/with-tx-context
                {:seon.db/user [:seon.agent/id "root"]
                 :seon.db/process (process/lookup-ref ::process/boot)}
                (fn []
                  (db/transact!
                    {:seon.db/conn conn
                     :seon.db/tx-data [{:seon.agent/id agent-id}]}))))
            conn)))))

(defn- tx-provenance
  [conn envelope]
  (let [tx (::db/tx envelope)]
    (d/q '[:find ?user-attr ?user-id ?process-id
           :in $ ?tx
           :where
           [?tx :seon.db/user ?user]
           [?tx :seon.db/process ?process]
           [?process :seon.db.process/id ?process-id]
           (or-join [?user ?user-attr ?user-id]
             (and [?user :seon.agent/id ?user-id]
                  [(ground :seon.agent/id) ?user-attr])
             (and [?user :seon.user/id ?user-id]
                  [(ground :seon.user/id) ?user-attr]))]
         @conn tx)))

(defn- scoped-transact!
  [conn context agent]
  (let [write (fn []
                (db/transact!
                  {:seon.db/conn conn
                   :seon.db/tx-data [{::name "x"}]}))
        in-context (if context
                     #(db/with-tx-context context write)
                     write)]
    (if agent
      (db/with-agent agent in-context)
      (in-context))))

(deftest default-provenance-follows-execution-scope
  (async done
    (-> (fresh-conn)
        (.then
          (fn ^:async prove-defaults [conn]
            (let [human (await (scoped-transact! conn nil nil))
                  agent (await (scoped-transact! conn nil agent-id))]
              (testing "unscoped host work belongs to the human through REPL"
                (is (= #{[:seon.user/id "user" ::process/repl]}
                       (tx-provenance conn human))))
              (testing "an active agent is its own user through REPL"
                (is (= #{[:seon.agent/id agent-id ::process/repl]}
                       (tx-provenance conn agent)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest explicit-root-process-pair-is-preserved
  (async done
    (-> (fresh-conn)
        (.then
          (fn ^:async prove-explicit [conn]
            (let [boot (await
                         (scoped-transact!
                           conn
                           {:seon.db/user [:seon.agent/id "root"]
                            :seon.db/process
                            (process/lookup-ref ::process/boot)}
                           nil))]
              (is (= #{[:seon.agent/id "root" ::process/boot]}
                     (tx-provenance conn boot))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest database-namespace-caller-metadata-cannot-replace-selection
  (async done
    (-> (fresh-conn)
        (.then
          (fn ^:async prove-filter [conn]
            (let [write (await
                          (db/transact!
                            {:seon.db/conn conn
                             :seon.db/tx-data [{::name "filtered"}]
                             :seon.db/opts
                             {:tx-meta
                              {:seon.db/user [:seon.agent/id "root"]
                               :seon.db/process
                               (process/lookup-ref ::process/config)}}}))]
              (is (true? (::db/ok? write)))
              (is (= #{[:seon.user/id "user" ::process/repl]}
                     (tx-provenance conn write))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
