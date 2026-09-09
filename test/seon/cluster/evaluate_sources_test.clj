(ns seon.cluster.evaluate-sources-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [seon.blob :as blob]
            [my.agent :as my.agent]
            [seon.cluster.agent :as agent]
            [seon.cluster.loop :as loop]
            [seon.turn :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest ordered-evaluation-retains-one-explicit-basis-without-publication
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "preview-batch")
     (db/transact! connection
                   [(:seon.config/desired-row
                     (config/compile-manifest
                      {:seon.boot/cluster-name "preview-batch"
                       :seon.config/manifest {}}))])
     (db/transact! connection
                   (agent/creation-tx
                    {:seon.cluster.agent/id "preview-batch-agent"
                     :seon.ns/name 'my.agents.preview-batch
                     :seon.cluster/name "preview-batch"}))
     ;; THE RUN AND ITS EVALUATION ROWS EXIST BEFORE ANY FORM RUNS, exactly
     ;; as the turn's one intent commit makes them: a handle is the stored
     ;; evaluation's durable identity, so a form can only name an earlier value
     ;; when that value's evaluation actually persisted.
     (db/transact! connection
                   [{:seon.turn/id "preview-run"
                     :seon.turn/agent
                     [:seon.cluster.agent/id "preview-batch-agent"]
                     :seon.turn/opened-at (java.util.Date.)}])
     (db/transact! connection
                   (into []
                         (map (fn [ordinal]
                                {:seon.cluster.eval/id
                                 (run/receipt-identity "preview-run" ordinal)
                                 :seon.cluster.eval/run
                                 [:seon.turn/id "preview-run"]
                                 :seon.cluster.eval/ordinal ordinal
                                 :seon.cluster.eval/at (java.util.Date.)}))
                         (range 7)))
     (my.agent/settings! {:seon.config.eval/time-limit-ms 1700}
                         connection "preview-batch-agent")
     (let [database @connection
           earlier-handle
           (admit/result-handle (run/receipt-identity "preview-run" 2))
           base (support/fork-cluster-ctx connection)
           forked (sci.eval/fork-for-turn
                   {:seon.sci.eval/ctx base
                    :seon.db/db database
                    :seon.db/connection connection
                    :seon.cluster.agent/id "preview-batch-agent"})
           defaults (config/defaults)
           channel (async/chan 1)
           cluster (merge defaults
                          {:seon.db/connection connection
                           :seon.cluster/name "preview-batch"
                           :seon.db.process/id "preview-test"
                           :seon.sci.eval/ctx base
                           :seon.cluster.wake/channel channel
                           :seon.render/context-channel channel
                           :seon.turn.loop/completion channel
                           :seon.sci.admit/caps (config/result-caps defaults)
                           :seon.config.eval/time-limit-ms 2000
                           :seon.config/on-core-error :panic})
           raw-source (str "(seon.db/pull [:seon.cluster.agent/id] [:seon.cluster.agent/id \"later-agent\"])\n"
                         "(in-ns 'preview.batch-next)\n"
                         "(+ 1 2)\n"
                         "(inc " earlier-handle ")\n"
                         "(seon.db/pull [:seon.cluster.agent/id] [:seon.cluster.agent/id \"later-agent\"])\n"
                         "(apply str (repeat 50000 \"x\"))\n"
                         "(throw (ex-info \"preview failure\" {}))")
           sources (loop/planned-sources
                    raw-source
                    'my.agents.preview-batch
                    (:seon.config.eval.result/max-source (config/result-caps defaults)))
           original-evaluate sci.eval/evaluate
           original-transact db/transact!
           evaluations (atom 0)
           writes (atom 0)]
       (try
         (let [opened-at (java.util.Date.)
               outcomes
               (with-redefs
                 [blob/stage! (fn [& _] (throw (ex-info "preview staged a blob" {})))
                  db/transact! (fn [& args]
                                 (swap! writes inc)
                                 (apply original-transact args))
                  sci.eval/evaluate
                  (fn [request]
                    (is (= 1700 (:seon.sci.eval/time-limit-ms request)))
                    (let [result (original-evaluate request)]
                      (when (= 1 (swap! evaluations inc))
                        (db/transact! connection [{:seon.cluster.agent/id "later-agent"}]))
                      result))]
                 (loop/evaluate-sources
                  {:seon.turn.loop/cluster cluster
                   :seon.db/db database
                   :seon.sci.eval/ctx (:seon.sci.eval/ctx forked)
                   :seon.cluster.agent/id "preview-batch-agent"
                   :seon.turn/id "preview-run"
                   :seon.cluster.eval/ordinal 0
                   :seon.ns/name 'my.agents.preview-batch
                   :seon.cluster.reply/sources sources}))
               closed-at (java.util.Date.)
               values (mapv #(get-in % [:seon.sci.eval/evaluation :seon.sci.admit/value]) outcomes)]
           (is (= 7 (count outcomes)))
           (is (nil? (first values)))
           (is (= [3 4 nil] (subvec values 2 5)))
           (is (= (apply str (repeat 50000 "x")) (nth values 5)))
           (is (string? (get-in (last outcomes) [:seon.sci.eval/evaluation :seon.cluster.eval/error])))
           (is (= (range 7) (map :seon.cluster.eval/ordinal outcomes)))
           (is (= [:seon.ns/name 'preview.batch-next]
                  (get-in (nth outcomes 2) [:seon.turn.loop/admitted-form :seon.cluster.eval/ns])))
           (is (every? #(= (db/basis-t database)
                           (get-in % [:seon.sci.eval/evaluation :seon.cluster.eval/read-basis-transaction])) outcomes))
           (is (seq (get-in (first outcomes) [:seon.sci.eval/evaluation :seon.cluster.eval/read-evidence])))
           (is (= 1 @writes) "only the deliberate concurrent database change was written")
           (is (nil? db/*read-database*) "the failing evaluation restores read custody")
           (is (= {:seon.cluster.agent/id "later-agent"}
                  (binding [db/*conn* connection]
                    (db/pull [:seon.cluster.agent/id] [:seon.cluster.agent/id "later-agent"]))
                  (binding [db/*conn* connection db/*read-database* database]
                    (db/pull @connection [:seon.cluster.agent/id] [:seon.cluster.agent/id "later-agent"]))))
           (is (= (db/q '[:find (count ?run) . :where [?run :seon.turn/id]] database)
                  (db/q '[:find (count ?run) . :where [?run :seon.turn/id]] @connection)))
           (is (every? #(inst? (get-in % [:seon.sci.eval/evaluation :seon.cluster.eval/at])) outcomes))
           (db/transact! connection [{:seon.turn/id "preview-run"
                                     :seon.turn/closed-at closed-at}])
           (is (nil? (:seon.error/kind
                      (db/transact! connection
                                    (run/open-tx
                                     {:seon.turn/id "active-during-add"
                                      :seon.turn/agent [:seon.cluster.agent/id "preview-batch-agent"]
                                      :seon.turn/opened-at closed-at})))))
           (let [request {:seon.turn.loop/cluster cluster
                          :seon.db/db database
                          :seon.turn/id "saved-preview"
                          :seon.turn/agent [:seon.cluster.agent/id "preview-batch-agent"]
                          :seon.turn/starting-ns [:seon.ns/name 'my.agents.preview-batch]
                          :seon.turn/reply raw-source
                          :seon.turn/opened-at opened-at
                          :seon.turn/closed-at closed-at
                          :seon.turn.loop/evaluated-sources outcomes}
                 prepared (run/record-evaluated-tx request)
                 _refusal (is (:seon.error/kind
                               (db/transact! connection (:seon.db/tx-data prepared))))
                 _close (db/transact!
                         connection
                         [{:seon.turn/id "active-during-add"
                           :seon.turn/closed-at closed-at}])
                 committed
                 (with-redefs [sci.eval/evaluate
                               (fn [& _] (throw (ex-info "saving re-executed source" {})))]
                   (blob/with-publication!
                     connection (:seon.blob/staged-writes prepared)
                     #(db/transact! connection
                                    (into (:seon.db/tx-data prepared)
                                          [(first (:seon.db/tx-data prepared))]))))
                 saved (db/pull @connection '[*] [:seon.turn/id "saved-preview"])
                 receipts (sort-by :seon.cluster.eval/ordinal
                                   (db/q '[:find [(pull ?evaluation [*]) ...]
                                           :in $ ?run-id
                                           :where [?run :seon.turn/id ?run-id]
                                           [?evaluation :seon.cluster.eval/run ?run]]
                                         @connection "saved-preview"))]
             (is (nil? (:seon.error/kind committed)) (pr-str (select-keys committed [:seon.error/kind :seon.error/message :seon.turn/refused])))
             (is (= "saved-preview"
                    (:seon.turn/id
                     (db/pull (run/opening-db @connection "saved-preview")
                              [:seon.turn/id]
                              [:seon.turn/id "saved-preview"]))))
             (is (= raw-source (:seon.turn/reply saved)))
             (is (= closed-at (:seon.turn/closed-at saved)))

             (is (nil?
                   (run/open-for-agent @connection
                                       [:seon.cluster.agent/id "preview-batch-agent"])))
             (is (= (count outcomes) (count receipts)))
             (is (= (mapv #(run/receipt-identity "saved-preview" %) (range (count outcomes)))
                    (mapv :seon.cluster.eval/id receipts)))
             (is (= (mapv #(get-in % [:seon.sci.eval/evaluation :seon.cluster.eval/at]) outcomes)
                    (mapv :seon.cluster.eval/at receipts)))
             (let [conflict (-> prepared :seon.db/tx-data first
                                (update 2 assoc :seon.turn/reply "different source"))
                   refused (db/transact! connection [{:my.plan.item/id "must-rollback"} conflict])]
               (is (= :seon.turn/recorded-content-conflict
                      (:seon.turn/refused refused)))
               (is (nil? (db/pull @connection [:my.plan.item/id]
                                  [:my.plan.item/id "must-rollback"]))))))
         (finally (async/close! channel)))))))
