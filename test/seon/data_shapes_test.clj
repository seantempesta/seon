(ns seon.data-shapes-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.cluster.message :as message]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.ai :as ai]
            [seon.render]
            [seon.sci.eval]
            [seon.db :as db]
            [seon.id :as id]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.context-blocks-fixture :as fixture]
            [seon.test-support :as support]))

(defn plan-probe
  "Apply the chart's raw forms to the canonical Juniper plan, without committing."
  [database]
  ;; The raw Datahike forms below bypass `seon.db/transact!`, so they also
  ;; bypass its ONE encoding seam. `:my.plan.item/done-query` is an EDN-encoded
  ;; attribute stored as a string; encode the seed through the same bridge the
  ;; writer uses instead of handing Datahike an unencoded query vector.
  (let [projection (schema/projection-from-database database)
        seed (:db-after
              (d/with database
                      (schema.datahike/encode-transaction-in
                       projection
                       (into (agent/creation-tx
                              {:seon.agent/id "juniper"
                               :seon.ns/name 'my.agents.juniper
                               :seon.cluster/name "data-lane"})
                             [{:seon.agent/id "juniper"
                               :seon.agent/plan (update fixture/authored-plan :my.plan/steps set)}]))))
        plan-ref [:my.plan/agent [:seon.agent/id "juniper"]]
        before (d/pull seed '[:db/id {:my.plan/steps [:my.plan.item/id]}] plan-ref)
        complete (d/with seed [[:db/add [:my.plan.item/id "juniper/read"]
                               :my.plan.item/completed-tx "datomic.tx"]])
        add (d/with (:db-after complete)
                    [{:my.plan/agent [:seon.agent/id "juniper"]
                      :my.plan/steps [{:my.plan.item/id "orders/verify"
                                       :my.plan.item/title "Verify the new total"
                                       :my.plan.item/done-when "…"
                                       :my.plan.item/position (long (count (:my.plan/steps fixture/authored-plan)))}]}])
        current (d/with (:db-after add)
                        [[:db/add plan-ref :my.plan/current-step
                          [:my.plan.item/id "juniper/define"]]])
        remove (d/with (:db-after current)
                       [[:db.fn/retractEntity [:my.plan.item/id "orders/verify"]]])
        after (d/pull (:db-after remove)
                      '[:db/id {:my.plan/steps [:my.plan.item/id]}
                        {:my.plan/current-step [:my.plan.item/id]}] plan-ref)
        shown (d/pull (:db-after complete)
                      '[{:my.plan.item/completed-tx [:db/txInstant]}]
                      [:my.plan.item/id "juniper/read"])]
    {:seon.test/before before
     :seon.test/after after
     :seon.test/added (d/pull (:db-after add)
                            '[:db/id {:my.plan/steps [:my.plan.item/id]}] plan-ref)
     :seon.test/completed shown
     :seon.test/completed-instant (:v (first (filter #(= :db/txInstant (:a %)) (:tx-data complete))))
     :seon.test/removed (d/pull (:db-after remove) '[*] [:my.plan.item/id "orders/verify"])
     :seon.test/reports
     (into {} (map (fn [[op report]]
                    [op (mapv (fn [datom] [(:e datom) (:a datom) (:v datom)
                                           (:tx datom) (:added datom)])
                              (:tx-data report))]))
           [[:seon.test/complete complete] [:seon.test/add add]
            [:seon.test/current current] [:seon.test/remove remove]])}))

(deftest raw-plan-forms-preserve-the-component-and-use-transaction-time
  (support/with-database
   (fn [connection]
     (let [probe (plan-probe @connection)]
       (is (= (count (:my.plan/steps fixture/authored-plan))
              (count (get-in probe [:seon.test/before :my.plan/steps]))))
       (is (= (inc (count (:my.plan/steps fixture/authored-plan)))
              (count (get-in probe [:seon.test/added :my.plan/steps]))))
       (is (= (count (:my.plan/steps fixture/authored-plan))
              (count (get-in probe [:seon.test/after :my.plan/steps]))))
       (is (apply = (map #(get-in probe [% :db/id])
                        [:seon.test/before :seon.test/added :seon.test/after])))
       (is (= "juniper/define"
              (get-in probe [:seon.test/after :my.plan/current-step :my.plan.item/id])))
       (is (nil? (:seon.test/removed probe)))
       (is (inst? (:seon.test/completed-instant probe)))
       (is (= (:seon.test/completed-instant probe)
              (get-in probe [:seon.test/completed :my.plan.item/completed-tx :db/txInstant])))))))

(defn message-runtime-probe
  "Exercise inbox edges, transaction refs, and the runtime component on real data."
  [database]
  (let [seed (:db-after (d/with database
                               (into (agent/creation-tx {:seon.agent/id "sender"
                                                        :seon.ns/name 'my.agents.sender
                                                        :seon.cluster/name "data-lane"})
                                     (agent/creation-tx {:seon.agent/id "recipient"
                                                        :seon.ns/name 'my.agents.recipient
                                                        :seon.cluster/name "data-lane"}))))
        ask (message/send "recipient" "Please verify the total.")
        delivered (message/delivery seed {:my.message/value ask :seon.agent/id "sender"
                                          :seon.turn/id "probe-send" :seon.cluster.eval/ordinal 0
                                          :seon.config.message/max-chain 16})
        send-report (d/with seed (:seon.message/rows delivered))
        inbox-selector '[{:seon.message/_to [:seon.message/id :seon.message/content
                                                {:seon.message/from [:seon.agent/id]}]}]
        before (d/pull (:db-after send-report) inbox-selector [:seon.agent/id "recipient"])
        opened (d/with (:db-after send-report)
                       (turn/open-tx {:seon.turn/id "data-shapes-turn"
                                      :seon.turn/agent [:seon.agent/id "recipient"]
                                      :seon.turn/opened-tx "datomic.tx"
                                      :seon.turn/trigger [:seon.message/id (:seon.message/id ask)]}))
        answer (message/send "sender" "The total is verified." (:seon.message/id ask))
        answered (message/delivery (:db-after opened)
                                   {:my.message/value answer :seon.agent/id "recipient"
                                    :seon.turn/id "data-shapes-turn" :seon.cluster.eval/ordinal 0
                                    :seon.config.message/max-chain 16})
        answer-report (d/with (:db-after opened) (:seon.message/rows answered))
        close-report (d/with (:db-after answer-report)
                             (into [[:db/add [:seon.turn/id "data-shapes-turn"] :seon.turn/reply-size 1]]
                                   (turn/close-tx {:seon.turn/id "data-shapes-turn"})))
        listen-report (d/with (:db-after close-report)
                              [{:seon.runtime/agent [:seon.agent/id "recipient"]
                                :seon.runtime/listens [{:seon.listen/attribute :seon.message/to}]}])
        result (:db-after listen-report)]
    {:seon.test/message-id (:seon.message/id ask)
     :seon.test/before before
     :seon.test/after (d/pull result inbox-selector [:seon.agent/id "recipient"])
     :seon.test/message (d/pull result '[:seon.message/id
                                       {:seon.message/to [:seon.agent/id]}
                                       {:seon.turn/_handled [:seon.turn/id]}]
                               [:seon.message/id (:seon.message/id ask)])
     :seon.test/open-id (turn/open-for-agent (:db-after opened) [:seon.agent/id "recipient"])
     :seon.test/closed-id (turn/open-for-agent result [:seon.agent/id "recipient"])
     :seon.test/runtime (d/pull result '[{:seon.agent/runtime
                                        [:seon.runtime/agent
                                         {:seon.runtime/turns [:seon.turn/id
                                                              {:seon.turn/opened-tx [:db/id :db/txInstant]}
                                                              {:seon.turn/closed-tx [:db/id :db/txInstant]}]}
                                         {:seon.runtime/listens [*]}]}]
                               [:seon.agent/id "recipient"])
     :seon.test/reports
     (into {} (map (fn [[operation report]]
                     [operation (mapv (fn [datom] [(:e datom) (:a datom) (:v datom)
                                                   (:tx datom) (:added datom)]) (:tx-data report))]))
           [[:seon.test/send send-report] [:seon.test/open opened]
            [:seon.test/answer answer-report] [:seon.test/close close-report]
            [:seon.test/listen listen-report]])}))

(deftest messages-and-turns-have-one-owning-edge-and-transaction-time
  (support/with-database
   (fn [connection]
     (let [probe (message-runtime-probe @connection)
           runtime (get-in probe [:seon.test/runtime :seon.agent/runtime])
           recorded-turn (first (:seon.runtime/turns runtime))]
       ;; Minted message identities come from the one identity derivation, so
       ;; the expected length is derived from it rather than mirrored here.
       (is (= (count (id/id)) (count (:seon.test/message-id probe))))
       (is (= 1 (count (get-in probe [:seon.test/before :seon.message/_to]))))
       (is (= 1 (count (get-in probe [:seon.test/after :seon.message/_to]))))
       (is (= "recipient" (get-in probe [:seon.test/message :seon.message/to :seon.agent/id])))
       (is (= [#:seon.turn{:id "data-shapes-turn"}]
              (get-in probe [:seon.test/message :seon.turn/_handled])))
       (is (= "data-shapes-turn" (:seon.test/open-id probe)))
       (is (nil? (:seon.test/closed-id probe)))
       (is (= 1 (count (:seon.runtime/turns runtime))))
       (is (inst? (get-in recorded-turn [:seon.turn/opened-tx :db/txInstant])))
       (is (inst? (get-in recorded-turn [:seon.turn/closed-tx :db/txInstant])))
       (is (= :seon.message/to (:seon.listen/attribute (first (:seon.runtime/listens runtime)))))))))

(deftest handling-an-outside-message-retains-its-budget-basis
  (support/with-database
   (fn [connection]
     (let [seed (:db-after (d/with @connection
                                  (agent/creation-tx {:seon.agent/id "outside-recipient"
                                                       :seon.ns/name 'my.agents.outside-recipient
                                                       :seon.cluster/name "data-lane"})))
           arrival (d/with seed (message/inbound-tx seed
                                                   {:seon.agent/id "outside-recipient"
                                                    :seon.message/inbound-content "Please verify this."
                                                    :seon.config.eval.result/max-string 1000}))
           database (:db-after arrival)
           message-id (d/q '[:find ?id . :where [?m :seon.message/id ?id]] database)
           opened (d/with database (turn/open-tx {:seon.turn/id "outside-turn"
                                                 :seon.turn/agent [:seon.agent/id "outside-recipient"]
                                                 :seon.turn/opened-tx "datomic.tx"
                                                 :seon.turn/trigger [:seon.message/id message-id]}))
           closed (d/with (:db-after opened)
                           (into [{:seon.turn/id "outside-turn" :seon.turn/reply-size 0
                                   :seon.turn/reply ""}]
                                 (turn/close-tx {:seon.turn/id "outside-turn"})))
           after (:db-after closed)
           basis (turn/outside-wake-t database "outside-recipient")]
       (is (pos? basis))
       (is (= basis (turn/outside-wake-t after "outside-recipient")))
       (is (= 1 (count (:seon.message/_to (d/pull after '[{:seon.message/_to [:seon.message/id]}]
                                                 [:seon.agent/id "outside-recipient"])))))
       (is (= [#:seon.turn{:id "outside-turn"}]
              (:seon.turn/_handled (d/pull after '[{:seon.turn/_handled [:seon.turn/id]}]
                                          [:seon.message/id message-id]))))))))

(deftest provider-reasoning-is-retained-only-by-an-explicit-setting
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
     (let [created (db/transact! connection
                                (agent/creation-tx {:seon.agent/id "reasoning-agent"
                                                    :seon.ns/name 'my.agents.reasoning :seon.cluster/name "default"}))
           opened (db/transact! connection
                               (turn/open-tx {:seon.turn/id "reasoning-turn"
                                              :seon.turn/agent [:seon.agent/id "reasoning-agent"]
                                              :seon.turn/opened-tx "datomic.tx"}))]
       (is (some? (:db-after created)) (pr-str created))
       (is (some? (:db-after opened)) (pr-str opened)))
     (doseq [[ordinal retain?] [[0 false] [1 true]]]
       (let [changed (db/transact! connection
                                   [{:seon.config/agent [:seon.agent/id "reasoning-agent"]
                                     :seon.config.ai/retain-reasoning retain?}])
             settings (ai/settings (support/effective-config)
                                   (ai/agent-overlay @connection "reasoning-agent"))
             recorded (#'turn/record-attempt!
        {:seon.db/connection connection}
        {:seon.ai/target {:seon.ai/endpoint "https://api.deepseek.com/chat/completions"
                          :seon.ai/model "deepseek-v4-flash"}
         :seon.ai/settings settings
         :seon.turn/id "reasoning-turn" :seon.agent/id "reasoning-agent"
         :seon.ai.attempt/ordinal ordinal
         :seon.ai/reasoning-content "A bounded provider trace."}
        (java.util.Date.))]
         (is (some? (:db-after changed)) (pr-str changed))
         (is (= retain? (:seon.config.ai/retain-reasoning settings)))
         (is (nil? recorded) (pr-str recorded))))
     (let [rows (db/q '[:find [(pull ?attempt [*]) ...]
                        :where [?attempt :seon.ai.attempt/id]] @connection)
           by-ordinal (into {} (map (juxt :seon.ai.attempt/ordinal identity)) rows)]
       (is (= #{0 1} (set (keys by-ordinal))))
       (is (not-any? #(get (by-ordinal 0) %)
                     [:seon.ai.attempt/reasoning :seon.ai.attempt/reasoning-blob
                      :seon.ai.attempt/reasoning-size]))
       (is (= "A bounded provider trace." (:seon.ai.attempt/reasoning (by-ordinal 1))))
       (is (every? #(not (get % :seon.ai.attempt/sent-body)) rows))))))

(deftest attempt-usage-is-queryable
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
     (support/transacted!
             connection
             (into (agent/creation-tx
                    {:seon.agent/id "usage-facts"
                     :seon.ns/name 'my.agents.usage-facts
                     :seon.cluster/name "default"})
                   (turn/open-tx
                    {:seon.turn/id "usage-facts-turn"
                     :seon.turn/agent [:seon.agent/id "usage-facts"]
                     :seon.turn/opened-tx "datomic.tx"})))
     (let [usage {"prompt_tokens" 22134 "completion_tokens" 184
                  "total_tokens" 22318
                  "prompt_tokens_details" {"cached_tokens" 21504}
                  "prompt_cache_hit_tokens" 21504
                  "prompt_cache_miss_tokens" 630}
           result (#'turn/record-attempt!
                   {:seon.db/connection connection}
                   {:seon.turn/id "usage-facts-turn"
                    :seon.agent/id "usage-facts"
                    :seon.ai.attempt/ordinal 0
                    :seon.ai/target
                    {:seon.ai/endpoint "https://api.deepseek.com/chat/completions"
                     :seon.ai/model "deepseek-flash"}
                    :seon.ai/settings (support/effective-config)
                    :seon.ai/usage usage}
                   (java.util.Date.))
           row (db/q '[:find (pull ?a [*]) .
                        :where [?a :seon.ai.attempt/ordinal 0]]
                     (db/db connection))]
       (is (nil? result) (pr-str result))
       (is (= (ai/normalize-usage usage)
              (select-keys row (keys (ai/normalize-usage usage)))))
       (is (= usage
              (edn/read-string (:seon.ai.attempt/usage-edn row))))))))

(deftest attempt-settings-and-model-are-related-entities
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
     (support/transacted!
             connection
             (into (agent/creation-tx
                    {:seon.agent/id "usage-facts"
                     :seon.ns/name 'my.agents.usage-facts
                     :seon.cluster/name "default"})
                   (turn/open-tx
                    {:seon.turn/id "usage-facts-turn"
                     :seon.turn/agent [:seon.agent/id "usage-facts"]
                     :seon.turn/opened-tx "datomic.tx"})))
     (let [usage {"prompt_tokens" 22134 "completion_tokens" 184
                  "total_tokens" 22318
                  "prompt_tokens_details" {"cached_tokens" 21504}
                  "prompt_cache_hit_tokens" 21504
                  "prompt_cache_miss_tokens" 630}
           result (#'turn/record-attempt!
                   {:seon.db/connection connection}
                   {:seon.turn/id "usage-facts-turn"
                    :seon.agent/id "usage-facts"
                    :seon.ai.attempt/ordinal 0
                    :seon.ai/target
                    {:seon.ai/endpoint "https://api.deepseek.com/chat/completions"
                     :seon.ai/model "deepseek-flash"}
                    :seon.ai/settings (support/effective-config)
                    :seon.ai/usage usage}
                   (java.util.Date.))
           row (db/q '[:find (pull ?a [* {:seon.ai.attempt/model [:seon.ai.model/id]}]) .
                        :where [?a :seon.ai.attempt/ordinal 0]]
                     (db/db connection))]
       (is (nil? result) (pr-str result))
       (is (= "deepseek-flash" (get-in row [:seon.ai.attempt/model :seon.ai.model/id])))
       (is (= (get-in row [:seon.ai.attempt/settings :db/id])
              (get-in (db/pull (db/db connection) [:seon.agent/settings]
                               [:seon.agent/id "usage-facts"])
                      [:seon.agent/settings :db/id])))
       (is (some? (:seon.ai.attempt/settings row)))))))

(deftest evaluation-renderer-is-a-program-reference
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "renderer-facts")
     (support/transacted! connection
              [{:seon.ns/name 'user}
               {:seon.agent/id "renderer-facts"}
               {:seon.turn/id "renderer-facts-turn"
                :seon.turn/agent [:seon.agent/id "renderer-facts"]
                :seon.turn/opened-tx "datomic.tx"}])
     (let [ctx (support/fork-cluster-ctx connection "renderer-facts")
           configuration (support/effective-config)]
       (doseq [[ordinal source expected] [[0 "(dir seon.repl)" 'seon.repl/render-directory-ai]
                                          [1 "{:example/plain 1}" nil]]]
         (support/transacted! connection
                  (turn/receipt-start-tx
                   {:seon.turn/id "renderer-facts-turn"
                    :seon.cluster.eval/ordinal ordinal :seon.cluster.eval/at (java.util.Date.)}))
         (let [evaluation (seon.sci.eval/evaluate
                            {:seon.cluster.eval/source source
                             :seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                             :seon.render/profile (seon.render/agent-render-profile configuration)
                             :seon.sci.admit/caps (config/result-caps configuration)
                             :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)
                             :seon.config/on-core-error :panic})
               [settled] (turn/settlement-projection {:seon.db/connection connection} evaluation)
               facts (turn/evaluation-facts
                      {:seon.turn/id "renderer-facts-turn"
                       :seon.cluster.eval/ordinal ordinal
                       :seon.sci.eval/evaluation evaluation
                       :seon.turn.loop/settlement-evaluation settled})
               result (db/transact! connection (turn/receipt-settle-tx facts))
               row (db/q '[:find (pull ?e [:seon.eval/shown :seon.eval/renderer
                                           {:seon.eval/renderer-fn [:db/id :seon.fn/sym]}]) .
                           :in $ ?ordinal
                           :where [?e :seon.cluster.eval/ordinal ?ordinal]]
                         (db/db connection) ordinal)]
           (is (nil? (:seon.cluster.eval/error evaluation)) (pr-str evaluation))
           (is (:db-after result) (pr-str result))
           (is (= expected (:seon.eval/renderer row)))
           (if expected
             (do
               (is (= (str expected) (get-in row [:seon.eval/renderer-fn :seon.fn/sym])))
               (is (pos-int? (get-in row [:seon.eval/renderer-fn :db/id]))))
             (is (empty? (select-keys row [:seon.eval/renderer :seon.eval/renderer-fn]))))
           (is (= (:seon.eval/shown evaluation)
                  (:seon.eval/shown row)))))))))

(deftest listen-patterns-retain-optional-entity-and-logical-value
  (support/with-database
   (fn [connection]
     (let [created (db/transact! connection
                                (agent/creation-tx {:seon.agent/id "listener"
                                                    :seon.ns/name 'my.agents.listener
                                                    :seon.cluster/name "default"}))
           changed (db/transact! connection
                                [{:seon.runtime/agent [:seon.agent/id "listener"]
                                  :seon.runtime/listens
                                  [{:seon.listen/attribute :seon.message/content
                                    :seon.listen/entity [:seon.agent/id "listener"]
                                    :seon.listen/value "ready"}]}])
           runtime (db/pull @connection
                            '[{:seon.runtime/listens
                               [:seon.listen/attribute :seon.listen/value
                                {:seon.listen/entity [:seon.agent/id]}]}]
                            [:seon.runtime/agent [:seon.agent/id "listener"]])]
       (is (some? (:db-after created)) (pr-str created))
       (is (some? (:db-after changed)) (pr-str changed))
       (is (= [{:seon.listen/attribute :seon.message/content
                :seon.listen/entity {:seon.agent/id "listener"}
                :seon.listen/value "ready"}]
              (:seon.runtime/listens runtime)))))))

(deftest a-system-turn-leaves-the-inbox-edge-unhandled
  (support/with-database
   (fn [connection]
     (let [created (d/with @connection
                           (agent/creation-tx {:seon.agent/id "system-recipient"
                                               :seon.ns/name 'my.agents.system-recipient
                                               :seon.cluster/name "default"}))
           incoming (d/with (:db-after created)
                            [{:seon.message/id "system-message"
                              :seon.message/to [:seon.agent/id "system-recipient"]
                              :seon.message/content "Still waiting for an answer."}])
           opened (d/with (:db-after incoming)
                          (turn/system-run-tx (:db-after incoming)
                           {:seon.agent/id "system-recipient"
                            :seon.turn/id "system-only"
                            :seon.turn/starting-ns [:seon.ns/name 'my.agents.system-recipient]
                            :seon.turn/opened-tx "datomic.tx"
                            :seon.turn/trigger [:seon.message/id "system-message"]
                            :seon.turn/sources [{:seon.cluster.eval/source "(+ 1 1)"
                                                 :seon.ns/name 'my.agents.system-recipient}]}))
           closed (d/with (:db-after opened) (turn/close-tx {:seon.turn/id "system-only"}))
           row (d/pull (:db-after closed)
                       '[:seon.turn/_handled {:seon.message/to [:seon.agent/id]}]
                       [:seon.message/id "system-message"])]
       (is (= "system-recipient" (get-in row [:seon.message/to :seon.agent/id])))
       (is (nil? (:seon.turn/_handled row)))))))
