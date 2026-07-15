(ns seon.render.system-test
  "Derived root fleet context over one immutable database value."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [my.plan :as plan]
    [seon.agent :as agent]
    [seon.agent.message :as message]
    [seon.agent.run :as run]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.derive :as derive]
    [seon.render.system :as system]))

(defn- with-conn
  "Run `body` with a fresh connection installed as the ambient root."
  [body]
  (-> (client/open-agent-conn!)
      (.then
        (fn [conn]
          (let [original db/*conn*]
            (set! db/*conn* conn)
            (-> (js/Promise.resolve (body conn))
                (.finally (fn [] (set! db/*conn* original)))))))))

(deftest fleet-summary-carries-actionable-per-agent-state
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.user/id "user"}
                    {:seon.agent/id "root"}]})
                (.then
                  (fn [{ok? :seon.db/ok?}]
                    (is (true? ok?) "root fixture commits before child refs it")
                    (agent/mint!
                      {:seon.agent/purpose "Investigate the parser"
                       :seon.agent/parent [:seon.agent/id "root"]})))
                (.then
                  (fn [created]
                    (let [worker-id (:seon.agent/id created)]
                      (is (string? worker-id) (str "worker allocated: " (pr-str created)))
                      (-> (plan/plan!
                            {:seon.agent/id worker-id
                             :my.plan/title "Repair parsing"
                             :my.plan/goal "Prose never crashes the turn"
                             :my.plan/children
                             [{:my.plan/title "Reproduce the malformed reply"
                               :my.plan/ref "repro"}]})
                          (.then
                            (fn [_]
                              (message/message!
                                {:seon.agent.message/from [:seon.user/id "user"]
                                 :seon.agent.message/to [[:seon.agent/id worker-id]]
                                 :seon.agent.message/origin :human
                                 :seon.agent.message/content "Fix the parser first."})))
                          (.then
                            (fn [_]
                              (message/message!
                                {:seon.agent.message/from [:seon.agent/id worker-id]
                                 :seon.agent.message/to [[:seon.user/id "user"]]
                                 :seon.agent.message/content "I reproduced the failure."})))
                          (.then
                            (fn [_]
                              (reduce
                                (fn [promise i]
                                  (.then
                                    promise
                                    (fn []
                                      (message/message!
                                        {:seon.agent.message/from
                                         [:seon.agent/id worker-id]
                                         :seon.agent.message/to
                                         [[:seon.user/id "user"]]
                                         :seon.agent.message/content
                                         (str "agent chatter " i)}))))
                                (js/Promise.resolve)
                                (range 13))))
                          (.then
                            (fn [_]
                              (run/open-run!
                                {:seon.agent/id worker-id
                                 :seon.agent.run/trigger :message
                                 :seon.agent.run/turn-limit 50
                                 :seon.agent.run/deadline (js/Date. 9999999999999)})))
                          (.then
                            (fn [_]
                              (db.id/allocate!
                                {::db.id/allocations
                                 [{::db.id/key ::eval-id
                                   ::db.id/identity-attr :seon.eval/id}]
                                 ::db.id/transaction-builder
                                 (fn [ids]
                                   {:seon.db/tx-data
                                    [{:seon.eval/id (::eval-id ids)
                                      :seon.eval/agent [:seon.agent/id worker-id]
                                      :seon.eval/at (js/Date. 1000)
                                      :seon.eval/source "(broken)"
                                      :seon.eval/ns :my.agent.worker
                                      :seon.eval/status :error
                                      :seon.eval/ok? false
                                      :seon.eval/error
                                      "Unable to resolve symbol broken"}]})
                                 :seon.db/conn conn})))
                          (.then
                            (fn [_]
                              (let [status (binding [db/*conn* nil]
                                             (derive/derive-status
                                               {:seon.db/db @conn
                                                :seon.agent/id worker-id
                                                :seon.agent/now (js/Date. 2000)}))
                                    fleet (system/fleet-summary @conn)
                                    by-id (into {} (map (juxt :seon.agent/id identity))
                                                (::system/agents fleet))
                                    root (get by-id "root")
                                    worker (get by-id worker-id)]
                                (testing "every agent has one stable relationship row"
                                  (is (= :running (:seon.agent/state status))
                                      "status reads the explicit immutable database")
                                  (is (= #{"root" worker-id} (set (keys by-id))))
                                  (is (= [worker-id] (::system/children root)))
                                  (is (= "root" (::system/parent worker))))
                                (testing "the worker row contains facts root needs to act"
                                  (is (= "Investigate the parser"
                                         (:seon.agent/purpose worker)))
                                  (is (= "Fix the parser first."
                                         (::system/latest-human worker)))
                                  (is (= "agent chatter 12"
                                         (::system/latest-output worker)))
                                  (is (= "Repair parsing"
                                         (get-in worker [::system/plan :my.plan/title])))
                                  (is (= "Reproduce the malformed reply"
                                         (get-in worker [::system/plan :my.plan/step-title])))
                                  (is (= "Unable to resolve symbol broken"
                                         (::system/latest-failure worker)))
                                  (is (= 50
                                         (get-in worker [::system/run
                                                         :seon.agent.run/turn-limit]))))))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "fleet derivation threw: " error))
                  (done))))))
