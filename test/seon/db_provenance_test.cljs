(ns seon.db-provenance-test
  "Behavioral proof for transaction provenance genesis and convergence."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as d]
    [seon.agent.ctx.render-fns]
    [seon.agent.message]
    [seon.db :as db]
    [seon.db.process :as process]
    [seon.schema :as schema]))

(schema/register! ::item-id [:string {:seon.db/identity true}])
(schema/register! ::value :string)

(defn- fresh-conn
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false}))))))

(defn- tx-provenance
  [db-value tx]
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
       db-value tx))

(deftest fresh-genesis-tags-human-and-lazy-schema-writes
  (async done
    (-> (fresh-conn)
        (.then
          (fn ^:async prove-fresh [conn]
            (let [first-result (await (db/ensure-provenance! {:seon.db/conn conn}))
                  genesis-tx  (:seon.db/genesis-tx first-result)
                  human-tx    (:seon.db/human-tx first-result)]
              (is (= :fresh-genesis (:seon.db/provenance-action first-result)))
              (is (= 3 (d/q '[:find (count ?p) .
                              :where [?p :seon.db.process/id]] @conn)))
              (is (= #{[:seon.agent/id "root"] [:seon.user/id "user"]}
                     (d/q '[:find ?a ?id
                            :where
                            (or-join [?e ?a ?id]
                              (and [?e :seon.agent/id ?id]
                                   [(ground :seon.agent/id) ?a])
                              (and [?e :seon.user/id ?id]
                                   [(ground :seon.user/id) ?a]))]
                          @conn)))
              (is (empty? (tx-provenance @conn genesis-tx))
                  "the mathematical base transaction does not claim authorship")
              (is (= #{[:seon.agent/id "root" :seon.db.process/boot]}
                     (tx-provenance @conn human-tx)))
              (let [write (await (db/transact!
                                   {:seon.db/conn conn
                                    :seon.db/tx-data
                                    [{::item-id "fresh" ::value "v1"}]}))
                    data-tx (:seon.db/tx write)
                    schema-tx (dec data-tx)]
                (is (true? (:seon.db/ok? write)))
                (is (= #{[:seon.user/id "user" :seon.db.process/repl]}
                       (tx-provenance @conn schema-tx))
                    "the separate first-use schema transaction is attributed")
                (is (= #{[:seon.user/id "user" :seon.db.process/repl]}
                       (tx-provenance @conn data-tx))))
              (let [before (db/basis-t @conn)
                    again  (await (db/ensure-provenance! {:seon.db/conn conn}))]
                (is (= :converged (:seon.db/provenance-action again)))
                (is (= before (db/basis-t @conn))
                    "a converged ensure emits no transaction")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "fresh genesis threw: " e)) (done))))))

(deftest active-agent-selects-agent-user-and-repl-process
  (async done
    (-> (fresh-conn)
        (.then
          (fn ^:async prove-agent [conn]
            (await (db/ensure-provenance! {:seon.db/conn conn}))
            (let [agent-id "agentlegacy001"
                  created  (await (db/transact!
                                    {:seon.db/conn conn
                                     :seon.db/tx-data
                                     [{:seon.agent/id agent-id}]}))
                  write    (await
                             (db/with-agent
                               agent-id
                               (fn []
                                 (db/transact!
                                   {:seon.db/conn conn
                                    :seon.db/tx-data
                                    [{::item-id "agent" ::value "v2"}]}))))]
              (is (true? (:seon.db/ok? created)))
              (is (true? (:seon.db/ok? write)))
              (is (= #{[:seon.agent/id agent-id :seon.db.process/repl]}
                     (tx-provenance @conn (:seon.db/tx write)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "agent provenance threw: " e)) (done))))))
