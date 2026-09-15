(ns seon.turn-continue-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.context-blocks-fixture :as fixture]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.repl :as repl]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(deftest accepted-provider-replies-continue-until-done-refusal-or-bound
  (let [read-source "(seon.db/q '[:find (sum ?amount) . :where [?order :example/customer \"Ada\"] [?order :example/amount ?amount]])"
        done "(my.agent/done)\n(seon.db/transact! [{:example/order \"after-done\" :example/customer \"Ada\" :example/amount 999}])"
        refusal {:seon.error/kind :seon.ai/no-credential
                 :seon.error/message "Fixture provider refused the request."}]
    (doseq [[scenario replies limit expected disposition]
            [[:read-then-done [read-source done] 3 2 :wait]
             [:prose-then-done ["I will inspect the orders next." done] 3 2 :wait]
             [:empty-then-done ["" done] 3 2 :wait]
             [:comments-then-done [";; I will inspect the orders next." done] 3 2 :wait]
             [:done [done] 3 1 :wait]
             [:slow-provider [done] 3 1 :wait]
             [:completed ["(seon.run/complete \"Verified.\")"] 3 1 :completed]
             [:refusal [refusal] 3 1 nil]
             [:bound [read-source read-source] 2 2 nil]]]
      (testing (name scenario)
        (support/with-database
         (fn [connection]
           (config/apply! {:seon.db/connection connection
                          :seon.boot/cluster-name "loop-continue"
                          :seon.config/manifest {:seon.config.ai/no-provider true}})
           (db/transact! connection [{:seon.agent/id "root"
                                     :seon.agent/namespace {:seon.ns/name 'my.agents.root}}])
           (cluster/ensure-cluster-entity! connection "loop-continue" cluster/boot-process-identity)
           (let [ctx (support/fork-cluster-ctx connection)
                 environment (support/environment "loop-continue" connection)
                 routing (agent/routing)
                 requests (atom [])]
             (with-open [provider-progress (support/closeable (async/chan 1) async/close!)
                         events (support/closeable (async/chan (async/sliding-buffer 1)) async/close!)
                         faults (support/closeable (async/chan (async/sliding-buffer 16)) async/close!)
                         launcher (support/closeable
                                   (flow/start-work-launcher!
                                    {:seon.env/environment environment
                                     :seon.flow/configuration
                                     (select-keys (support/effective-config) flow/flow-workload-attributes)})
                                   flow/stop-work-launcher!)
                         handle-resource
                         (support/closeable
                          (support/cluster-handle
                           {:seon.env/environment environment :seon.db/connection connection
                            :seon.cluster/name "loop-continue" :seon.sci.eval/ctx ctx
                            :seon.flow/work-launcher @launcher
                            :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                            :seon.db.process/id cluster/boot-process-identity})
                          (fn [handle]
                            (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"})
                            (d/unlisten connection ::continuation)
                            (doseq [key [:seon.cluster.wake/channel :seon.render/context-channel
                                         :seon.turn.loop/completion]]
                              (async/close! (get handle key)))))]
               (let [handle @handle-resource
                     work-request {:seon.agent/id "juniper"}
                     closed-attempts
                     (fn [database]
                       (db/q '[:find [?attempt ...] :where
                              [?agent :seon.agent/id "juniper"]
                              [?turn :seon.turn/agent ?agent]
                              [?turn :seon.turn/closed-tx _]
                              [?turn :seon.turn/attempts ?attempt]] database))]
                 (swap! routing assoc :seon.agent/fault-channel @faults)
                 (cluster/ensure-entity! connection cluster/boot-process-identity
                                         {:seon.agent/id "juniper" :seon.cluster/name "loop-continue"
                                          :seon.ns/name 'my.agents.juniper})
                 (fixture/install! handle routing)
                 (let [settings (get-in (db/pull @connection '[{:seon.agent/settings [:db/id]}]
                                                [:seon.agent/id "juniper"])
                                        [:seon.agent/settings :db/id])]
                   (is (nil? (:seon.error/kind
                              (db/transact! connection
                                            [[:db/retract settings :seon.config.ai/no-provider true]
                                             [:db/add settings :seon.config.run/max-episode-runs limit]
                                             [:db/add settings :seon.config.eval/time-limit-ms 10000]
                                             [:db/add settings :seon.config.ai/timeout-ms 30000]
                                             [:db/add settings :seon.config.ai.retry/maximum-retries 0]])))))
                 (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "loop-continue"
                                :seon.config/manifest {:seon.config.ai/no-provider :seon.config/absent}})
                 (d/listen connection ::continuation (fn [_] (async/offer! @events true)))
                 (let [outside-t (turn/outside-wake-t @connection "juniper")]
                   ; Replace only the external provider response, as the reply tests do.
                   ; The proc, attempt writer, SCI, prompt and work derivation stay real.
                   (with-redefs [ai/complete
                                 (fn [request]
                                   (when (= scenario :slow-provider)
                                     (Thread/sleep 10000)
                                     (async/offer! @provider-progress true)
                                     (Thread/sleep 10000))
                                   (let [index (count (swap! requests conj request))
                                         reply (get replies (dec index) refusal)]
                                     (if (string? reply)
                                       {:seon.ai/text reply :seon.ai/finish-reason "stop"}
                                       reply)))]
                     (try
                       (agent/arm! {:seon.turn.loop/cluster handle
                                    :seon.agent/routing routing :seon.agent/id "juniper"})
                       (when (= scenario :slow-provider)
                         (support/await-event! @provider-progress ::provider-still-pending-at-eval-limit))
                       (support/await-event!
                        @events ::session-ended
                        (fn [_]
                          (>= (count (closed-attempts @connection)) expected)))
                       ; A system turn can finish before the proc opens its next
                       ; provider turn. Observe the required closed attempts and
                       ; the proc's completion event before inspecting next work.
                       (let [completion (:seon.turn.loop/completion (agent/armed routing "juniper"))
                             permit (support/await-event! completion ::last-pass-returned)]
                         (try
                           (is (nil? (turn/next-agent-work @connection work-request)))
                           (finally
                             (is (true? (async/offer! completion permit))))))
                       (finally
                         (agent/disarm! {:seon.agent/routing routing :seon.agent/id "juniper"}))))
                   (is (= expected (count @requests)))
                   (let [saved (evaluation/of-agent @connection "juniper")
                         opening (take-while #(= (:seon.cluster.eval/run (first saved))
                                                 (:seon.cluster.eval/run %)) saved)
                         stable-reads (filter
                                       #(let [form (read-string (:seon.cluster.eval/source %))]
                                          (or (= '(seon.agent/settings) form)
                                              (some #{:seon.runtime/listens}
                                                    (tree-seq coll? seq form)))) opening)
                         by-source (group-by :seon.cluster.eval/source saved)]
                     (is (= 2 (count stable-reads)) "both generated reads must exist")
                     (doseq [entry stable-reads]
                       (let [observations (get by-source (:seon.cluster.eval/source entry))]
                         (is (= (count observations) (count (distinct (map :seon.eval/shown observations))))
                             "provider continuation never repeats an unchanged settings/runtime value"))))
                   (is (= expected (count (closed-attempts @connection))))
                   (is (= outside-t (turn/outside-wake-t @connection "juniper"))
                       "self-continuation neither creates an outside wake nor refills the bound")
                   (is (= (- limit expected) (turn/turns-left @connection "juniper")))
                   (is (nil? (turn/next-agent-work @connection work-request)))
                   (is (= (if disposition #{[disposition]} #{})
                          (db/q '[:find ?disposition :where
                                  [?agent :seon.agent/id "juniper"]
                                  [?turn :seon.turn/agent ?agent]
                                  [?turn :seon.turn/disposition ?disposition]] @connection)))
                   (is (nil? (db/q '[:find ?order . :where [?order :example/order "after-done"]] @connection)))
                   (if (= scenario :refusal)
                     (is (seq (turn/deferred-triggers @connection "juniper")))
                     (is (empty? (turn/unanswered-wakes @connection "juniper" {}))))
                   (when (= scenario :read-then-done)
                     (let [entry (first (filter #(= read-source (:seon.cluster.eval/source %))
                                                (evaluation/of-agent @connection "juniper")))
                           next-prompt (:seon.ai/prompt (second @requests))]
                       (is (= "115" (:seon.eval/shown entry)))
                       (is (seq (:seon.cluster.eval/read-evidence entry)))
                       (is (and (string? next-prompt)
                                (str/includes? next-prompt (repl/render-ai entry))))))
                   (when (#{:prose-then-done :empty-then-done :comments-then-done} scenario)
                     (let [source (if (empty? (first replies)) "\n" (first replies))
                           entries (filterv #(= source
                                                 (:seon.cluster.eval/source %))
                                            (evaluation/of-agent @connection "juniper"))
                           entry (first entries)
                           next-prompt (:seon.ai/prompt (second @requests))
                           message "Your reply had no form; only comments/prose. Send a form."]
                       (is (= 1 (count entries)))
                       (is (= message (:seon.cluster.eval/error entry)))
                       (is (str/includes? (:seon.eval/shown entry)
                                          ":seon.cluster.reply/no-forms"))
                       (is (str/includes? (repl/render-ai entry) ":error"))
                       (is (and (string? next-prompt)
                                (str/includes? next-prompt (repl/render-ai entry))
                                (str/includes? next-prompt message)))
                       (println "READER-NO-FORMS" scenario
                                (pr-str (select-keys entry [:seon.cluster.eval/source :seon.cluster.eval/error]))
                                "next-prompt-contains-error" (str/includes? next-prompt (repl/render-ai entry))))
                     (is (empty? (db/q '[:find ?e :where [?e :seon.error/id]] @connection)))
                     (is (empty? (db/q '[:find ?message :where
                                        [?root :seon.agent/id "root"]
                                        [?message :seon.message/to ?root]] @connection))))
                   (is (nil? (async/poll! @faults)))
                   (println {:seon.test/scenario scenario :seon.test/provider-attempts (count @requests)
                             :seon.test/turns-left (turn/turns-left @connection "juniper")})))))))))))
