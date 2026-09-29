(ns seon.data-shapes-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [datahike.api :as d]
            [seon.cluster :as cluster]
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
            [seon.test-support :as support]))

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
     :seon.test/runtime (d/pull result '[{:seon.turn/_agent [:seon.turn/id
                                                            {:seon.turn/opened-tx [:db/id :db/txInstant]}
                                                            {:seon.turn/closed-tx [:db/id :db/txInstant]}]}
                                        {:seon.agent/runtime
                                         [:seon.runtime/agent
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
           turns (get-in probe [:seon.test/runtime :seon.turn/_agent])
           recorded-turn (first turns)]
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
       (is (= 1 (count turns)))
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
           message-id (d/q '[:find ?id . :where [?a :seon.agent/id "outside-recipient"]
                              [?m :seon.message/to ?a] [?m :seon.message/id ?id]] database)
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
       (is (some? (:db-after created)))
       (is (some? (:db-after opened))))
     (doseq [[ordinal retain?] [[0 false] [1 true]]]
       ;; Absence is the off setting: the dial admits only `true`.
       (let [changed (when retain?
                       (db/transact! connection
                                     [{:seon.config/agent [:seon.agent/id "reasoning-agent"]
                                       :seon.config.ai/retain-reasoning true}]))
             settings (ai/settings (support/effective-config)
                                   (ai/agent-overlay @connection "reasoning-agent"))
             recorded (#'turn/record-attempt!
        {:seon.db/connection connection}
        {:seon.ai/target (assoc (:seon.ai/primary (ai/targets (schema/handed-projection) (support/effective-config))) :seon.ai/model "deepseek-v4-flash")
         :seon.ai/settings settings
         :seon.turn/id "reasoning-turn" :seon.agent/id "reasoning-agent"
         :seon.ai.attempt/ordinal ordinal
         :seon.ai/reasoning-content "A bounded provider trace."}
        (java.util.Date.))]
         (when retain? (is (some? (:db-after changed))))
         (is (= retain? (boolean (:seon.config.ai/retain-reasoning settings))))
         (is (nil? recorded))))
     (let [entities (db/q '[:find [(pull ?attempt [*]) ...]
                        :in $ [?id ...]
                        :where [?attempt :seon.ai.attempt/id ?id]]
                      @connection (mapv #(#'turn/attempt-id "reasoning-turn" %) [0 1]))
           by-ordinal (into {} (map (juxt :seon.ai.attempt/ordinal identity)) entities)]
       (is (= #{0 1} (set (keys by-ordinal))))
       (is (not-any? #(get (by-ordinal 0) %)
                     [:seon.ai.attempt/reasoning :seon.ai.attempt/reasoning-blob
                      :seon.ai.attempt/reasoning-size]))
       (is (= "A bounded provider trace." (:seon.ai.attempt/reasoning (by-ordinal 1))))
       (is (every? #(not (get % :seon.ai.attempt/sent-body)) entities))))))

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
                    (assoc (:seon.ai/primary (ai/targets (schema/handed-projection) (support/effective-config))) :seon.ai/model "deepseek-flash")
                    :seon.ai/settings (support/effective-config)
                    :seon.ai/usage usage}
                   (java.util.Date.))
           entity (db/q '[:find (pull ?a [*]) .
                        :in $ ?id
                        :where [?a :seon.ai.attempt/id ?id]]
                     (db/db connection) (#'turn/attempt-id "usage-facts-turn" 0))]
       (is (nil? result))
       (is (= (ai/normalize-usage usage)
              (select-keys entity (keys (ai/normalize-usage usage)))))
       (is (= usage
              (edn/read-string (:seon.ai.attempt/usage-edn entity))))))))

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
                    (assoc (:seon.ai/primary (ai/targets (schema/handed-projection) (support/effective-config))) :seon.ai/model "deepseek-flash")
                    :seon.ai/settings (support/effective-config)
                    :seon.ai/usage usage}
                   (java.util.Date.))
           entity (db/q '[:find (pull ?a [*]) .
                        :in $ ?id
                        :where [?a :seon.ai.attempt/id ?id]]
                     (db/db connection) (#'turn/attempt-id "usage-facts-turn" 0))]
       (is (nil? result))
       (is (= "deepseek-flash" (:seon.ai/model entity))
           "the attempt keeps the model identity itself, not a descriptor ref")
       (is (= (get-in entity [:seon.ai.attempt/settings :db/id])
              (get-in (db/pull (db/db connection) [:seon.agent/settings]
                               [:seon.agent/id "usage-facts"])
                      [:seon.agent/settings :db/id])))
       (is (some? (:seon.ai.attempt/settings entity)))))))

(deftest evaluation-renderer-is-a-program-reference
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "renderer-facts")
     (support/transacted! connection
              (into (support/agent-tx @connection "renderer-facts")
                [(support/namespace-entity @connection 'user)
               
                 {:seon.turn/id "renderer-facts-turn"
                  :seon.turn/agent [:seon.agent/id "renderer-facts"]
                  :seon.turn/opened-tx "datomic.tx"}]))
     (let [ctx (support/fork-cluster-ctx connection "renderer-facts")
           configuration (support/effective-config)]
       (doseq [[ordinal source expected] [[0 "(dir seon.repl)" 'seon.repl/render-directory-ai]
                                          [1 "{:example/plain 1}" nil]]]
         (support/transacted! connection
                  (turn/receipt-start-tx
                   {:seon.turn/id "renderer-facts-turn"
                    :seon.cluster.eval/ordinal ordinal :seon.cluster.eval/at (java.util.Date.)}))
         (let [evaluation (support/evaluate
                            {:seon.cluster.eval/source source
                             :seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                             :seon.render/profile (seon.render/agent-render-profile configuration)
                             :seon.sci.admit/caps (config/result-caps configuration)
                             :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})
               [settled] (turn/settlement-projection {:seon.db/connection connection} evaluation)
               facts (turn/evaluation-facts
                      {:seon.turn/id "renderer-facts-turn"
                       :seon.cluster.eval/ordinal ordinal
                       :seon.sci.eval/evaluation evaluation
                       :seon.turn.loop/settlement-evaluation settled})
               result (db/transact! connection (turn/receipt-settle-tx facts))
               entity (db/pull (db/db connection)
                            [:seon.eval/shown :seon.eval/renderer
                             {:seon.eval/renderer-fn [:db/id :seon.fn/sym]}]
                            [:seon.cluster.eval/id
                             (turn/receipt-identity "renderer-facts-turn" ordinal)])]
           (is (nil? (:seon.cluster.eval/error evaluation)))
           (is (:db-after result))
           (is (= expected (:seon.eval/renderer entity)))
           (if expected
             (do
               (is (= expected (get-in entity [:seon.eval/renderer-fn :seon.fn/sym])))
               (is (pos-int? (get-in entity [:seon.eval/renderer-fn :db/id]))))
             (is (empty? (select-keys entity [:seon.eval/renderer :seon.eval/renderer-fn]))))
           (is (= (:seon.eval/shown evaluation)
                  (:seon.eval/shown entity)))))))))

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
       (is (some? (:db-after created)))
       (is (some? (:db-after changed)))
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
           entity (d/pull (:db-after closed)
                       '[:seon.turn/_handled {:seon.message/to [:seon.agent/id]}]
                       [:seon.message/id "system-message"])]
       (is (= "system-recipient" (get-in entity [:seon.message/to :seon.agent/id])))
       (is (nil? (:seon.turn/_handled entity)))))))

(deftest a-turn-is-its-own-owning-value-so-writing-one-never-reads-the-history
  ;; Write validation expands each changed entity's whole owning value
  ;; (seon.db/write-owned-values-error). A turn owned by its agent's runtime
  ;; made every open and close re-validate every turn the agent ever ran
  ;; (41.8 ms and 439 entities at 433 turns, task 4bb788513862); a turn names
  ;; its agent instead, so no component attribute refers to it.
  (support/with-database
   (fn [connection]
     (support/transacted! connection (agent/creation-tx {:seon.agent/id "owner"
                                                         :seon.ns/name 'my.agents.owner
                                                         :seon.cluster/name "data-lane"}))
     (let [runtime (get-in (db/pull @connection [{:seon.agent/runtime [:db/id]}] [:seon.agent/id "owner"])
                           [:seon.agent/runtime :db/id])
           reports (vec (for [n (range 3)
                              :let [run-id (str "owned-turn-" n)]]
                          [(db/transact! connection (turn/open-tx {:seon.turn/id run-id
                                                                   :seon.turn/agent [:seon.agent/id "owner"]
                                                                   :seon.turn/opened-tx "datomic.tx"}))
                           (db/transact! connection (turn/close-tx {:seon.turn/id run-id}))]))
           database @connection
           turns (db/q '[:find [?t ...] :where [?a :seon.agent/id "owner"] [?t :seon.turn/agent ?a]] database)
           components (keep (fn [[attribute properties]] (when (:db/isComponent properties) attribute))
                            (:schema database))]
       (is (every? :db-after (apply concat reports)))
       (is (= 3 (count turns)))
       (is (= [] (vec (for [turn turns a components
                            datom (d/datoms database :avet a turn)]
                        [a (:e datom)])))
           "no component attribute owns a turn")
       (is (not-any? #(= runtime (:e %)) (mapcat (comp :tx-data first) reports))
           "opening a turn writes nothing on the agent's runtime")))))

(deftest each-agent-reads-its-own-clusters-cap-where-two-clusters-share-a-branch
  ;; A cluster branched from another keeps that cluster's config row beside
  ;; its own (seon.config/apply-compiled! retains every row a cluster owns),
  ;; so a dial read from "the" config row met whichever came first: a declared
  ;; cap of 1 read as the inherited 100 (39 provider calls, turn-test).
  (support/with-database
   (fn [connection]
     (let [inherited (db/q '[:find ?name . :where [_ :seon.cluster/name ?name]] @connection)
           apply-cap! (fn [cluster-name cap]
                        (config/apply! {:seon.db/connection connection :seon.boot/cluster-name cluster-name
                                        :seon.config/manifest {:seon.config.run/max-episode-runs cap}}))]
       (apply-cap! inherited 5)
       (apply-cap! "cap-two" 2)
       (cluster/ensure-cluster-entity! connection "cap-two" cluster/boot-process-identity)
       (support/transacted! connection
                            (into (agent/creation-tx {:seon.agent/id "cap-one-agent" :seon.ns/name 'my.agents.cap-one-agent
                                                      :seon.cluster/name inherited})
                                  (agent/creation-tx {:seon.agent/id "cap-two-agent" :seon.ns/name 'my.agents.cap-two-agent
                                                      :seon.cluster/name "cap-two"})))
       (support/transacted! connection
                            (into (agent/creation-tx {:seon.agent/id "unnamed-agent" :seon.ns/name 'my.agents.unnamed-agent
                                                      :seon.cluster/name "cap-two"})
                                  [[:db/retract [:seon.agent/id "unnamed-agent"] :seon.agent/cluster "cap-two"]]))
       (let [database @connection]
         (is (= 2 (count (db/q '[:find [?name ...] :where [_ :seon.config/cluster ?name]] database)))
             "both clusters' config rows are on the branch")
         (is (= 5 (turn/turns-left database "cap-one-agent")))
         (is (= 2 (turn/turns-left database "cap-two-agent")))
         (is (= :seon.agent/cluster (:seon.config/error-key (turn/turns-left database "unnamed-agent")))
             "an agent that names no cluster is refused by name"))))))
