(ns seon.agent.debug-test
  "Point-in-time database contract for agent context preview."
  (:require
    [cljs.test :refer [async deftest is]]
    [clojure.string :as str]
    [seon.agent.debug :as debug]
    [seon.client :as client]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.id :as db.id]))

(deftest preview-keeps-system-and-context-on-one-database-point
  (async done
    (let [original db/*conn*]
      (-> (client/open-agent-conn!)
          (.then
            (fn [conn]
              (set! db/*conn* conn)
              (-> (db.id/allocate!
                    {::db.id/allocations
                     [{::db.id/key ::agent
                       ::db.id/identity-attr :seon.agent/id}]
                     ::db.id/transaction-builder
                     (fn [ids]
                       {:seon.db/tx-data
                        [{:seon.agent/id (::agent ids)}
                         {:seon.config/id config/cluster-config-id
                          :seon.config/system-text "frozen system"}]})
                     :seon.db/conn conn})
                  (.then
                    (fn [allocation]
                      (let [agent-id (get-in allocation [::db.id/ids ::agent])
                            frozen @conn
                            config-eid
                            (db/query
                              {:seon.db/db frozen
                               :seon.db/query
                               '[:find ?config .
                                 :in $ ?config-id
                                 :where
                                 [?config :seon.config/id ?config-id]]
                               :seon.db/args [config/cluster-config-id]})]
                        (-> (db/transact!
                              {:seon.db/conn conn
                               :seon.db/tx-data
                               [{:db/id config-eid
                                 :seon.config/system-text "live system"}]})
                            (.then
                              (fn [_]
                                (let [preview
                                      (debug/ctx-preview
                                        {:seon.db/db frozen
                                         :seon.agent/id agent-id
                                         :seon.render/formats #{:ai}})
                                      system-block
                                      (first
                                        (:seon.agent.ctx/rendered-blocks
                                          preview))]
                                  (is (= "frozen system"
                                         (:seon.render/text system-block)))
                                  (is (str/starts-with?
                                        (:seon.render/text preview)
                                        "frozen system")
                                      "the full prompt uses the same point")
                                  (is (not (str/starts-with?
                                            (:seon.render/text preview)
                                            "live system"))
                                      "the newer ambient value cannot leak")))))))))))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))
          (.finally (fn [] (set! db/*conn* original)))))))
