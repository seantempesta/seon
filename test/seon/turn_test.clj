(ns seon.turn-test
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.render.hiccup :as hiccup]
            [seon.render.transcript :as transcript]
            [seon.render.web :as web]
            [seon.repl :as repl]
            [seon.test-support :as support]
            [seon.turn :as turn]
            [clojure.main :as main]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.error :as error]
            [seon.fn :as seon.fn]
            [seon.schema]))

(deftest attempt-model-identity-survives-descriptor-retraction
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "attempt-identity")
      (support/transacted!
       connection
       (into (agent/creation-tx
              {:seon.agent/id "attempt-identity"
               :seon.ns/name 'my.agents.attempt-identity
               :seon.cluster/name "attempt-identity"})
             (turn/open-tx
              {:seon.turn/id "attempt-identity-turn"
               :seon.turn/agent [:seon.agent/id "attempt-identity"]
               :seon.turn/opened-tx "datomic.tx"})))
      (let [model "deepseek-flash"
            observed-at #inst "2026-09-19T00:00:00Z"
            recorded (#'turn/record-attempt!
                      {:seon.db/connection connection}
                      {:seon.turn/id "attempt-identity-turn"
                       :seon.agent/id "attempt-identity"
                       :seon.ai.attempt/ordinal 0
                       :seon.ai/target
                       {:seon.ai/endpoint "https://api.deepseek.com/chat/completions"
                        :seon.ai/model model}
                       :seon.ai/settings (support/effective-config)}
                      observed-at)
            database (db/db connection)
            descriptor (db/pull database [:seon.ai.model/id]
                                [:seon.ai.model/id model])
            attempt (db/q '[:find ?attempt .
                            :where [?attempt :seon.ai.attempt/ordinal 0]]
                          database)]
        (is (nil? recorded) (pr-str recorded))
        (is (= {:seon.ai.model/id model} descriptor))
        (is (int? attempt))
        (support/transacted! connection
                             [[:db/retractEntity [:seon.ai.model/id model]]])
        (let [row (db/pull (db/db connection)
                           [:seon.ai/model :seon.ai.attempt/at]
                           attempt)]
          (is (= {:seon.ai/model model :seon.ai.attempt/at observed-at} row)))))))

(defn- checked-transact! [connection transaction]
  (let [result (db/transact! connection transaction)]
    (when (:seon.error/kind result) (throw (ex-info (pr-str result) result)))
    result))

(defn- evaluations [database agent-id]
  (db/q '[:find [?evaluation ...] :in $ ?id
          :where [?agent :seon.agent/id ?id]
          [?turn :seon.turn/agent ?agent]
          [?evaluation :seon.cluster.eval/run ?turn]] database agent-id))

(deftest issue-budget-counts-a-call-that-closes-before-the-provider
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "issue-close-budget")
     (doseq [aid ["issue-close-worker" "conversation-close-worker"]]
       (support/transacted! connection
                            (agent/creation-tx {:seon.agent/id aid
                                                :seon.ns/name (symbol (str "my.agents." aid))
                                                :seon.cluster/name "issue-close-budget"})))
     (support/transacted! connection
                          [{:seon.issue/id "issue-close-budget"
                            :seon.issue/title "Bound a refused ordinary call"
                            :seon.issue/problem "A call that closes before a provider still spends an issue turn."
                            :seon.issue/status :open :seon.issue/severity :cleanup
                            :seon.issue/detector [:seon.fn/sym 'seon.issue.detect/public-without-doc]
                            :seon.issue/agent [:seon.agent/id "issue-close-worker"]
                            :seon.issue/budget 2}])
     (doseq [aid ["issue-close-worker" "conversation-close-worker"]]
       (support/transacted! connection
                            (turn/open-tx {:seon.turn/id aid :seon.turn/agent [:seon.agent/id aid]
                                           :seon.turn/opened-tx "datomic.tx"}))
       (support/transacted! connection (turn/close-tx {:seon.turn/id aid})))
     (is (= 1 (turn/episode-runs (db/db connection) "issue-close-worker")))
     (is (= 1 (turn/episode-runs (db/db connection) "conversation-close-worker"))
         "the turn PRD's outside-wake bound also counts pre-provider ordinary failures")
     (support/transacted! connection
                          (into (turn/open-tx {:seon.turn/id "same-transaction-system"
                                              :seon.turn/agent [:seon.agent/id "issue-close-worker"]
                                              :seon.turn/opened-tx "datomic.tx"})
                                (turn/close-tx {:seon.turn/id "same-transaction-system"})))
     (is (= 1 (turn/episode-runs (db/db connection) "issue-close-worker")))
     (support/transacted! connection
                          (turn/open-tx {:seon.turn/id "second-closed-call"
                                         :seon.turn/agent [:seon.agent/id "issue-close-worker"]
                                         :seon.turn/opened-tx "datomic.tx"}))
     (support/transacted! connection (turn/close-tx {:seon.turn/id "second-closed-call"}))
     (is (zero? (turn/turns-left (db/db connection) "issue-close-worker")))
     (is (nil? (turn/next-agent-work (db/db connection) {:seon.agent/id "issue-close-worker"}))))))

(deftest generated-read-evidence-rejects-turn-activity
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "generated-read-proof"})
     (support/seed-cluster! connection "generated-read-proof")
     (checked-transact! connection
                        (agent/creation-tx {:seon.agent/id "reader"
                                            :seon.ns/name 'my.agents.reader
                                            :seon.cluster/name "generated-read-proof"}))
     (let [source "(seon.db/q '[:find ?id :where [_ :seon.turn/id ?id]])"
           run-id "generated-read"
           evaluation-id (id/evaluation run-id 0)
           _ (checked-transact!
              connection
              [{:seon.turn/id run-id
                :seon.turn/agent [:seon.agent/id "reader"]
                :seon.turn.work/situation :generate
                :seon.turn/opened-tx "datomic.tx"}
               {:seon.cluster.eval/id evaluation-id
                :seon.cluster.eval/at (java.util.Date.)
                :seon.cluster.eval/run [:seon.turn/id run-id]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/author :system
                :seon.cluster.eval/source source}])
           ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.env/environment (support/environment "generated-read-proof" connection)
                    :seon.db/connection connection
                    :seon.cluster/name "generated-read-proof"
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.sci.eval/ctx ctx})
           request {:seon.turn.loop/cluster handle
                    :seon.sci.eval/ctx ctx
                    :seon.agent/id "reader"
                    :seon.turn/id run-id
                    :seon.cluster.eval/ordinal 0
                    :seon.ns/name 'my.agents.reader
                    :seon.cluster.reply/sources [{:seon.cluster.eval/source source}]}
           fault (try (turn/evaluate-sources request) nil
                      (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
       (is (= :seon.turn/generated-read-depends-on-turns (:seon.error/kind fault))
           (pr-str fault))
       (is (= source (get-in fault [:seon.error/data :seon.error/diagnostic-offending])))
       (is (= #{:seon.turn/id}
              (get-in fault [:seon.error/data :seon.error/diagnostic-evidence
                             :datahike.read/attributes])))
       (is (nil? (:seon.cluster.eval/read-evidence
                  (db/pull @connection [:seon.cluster.eval/read-evidence]
                           [:seon.cluster.eval/id evaluation-id]))))
       (checked-transact! connection
                          [[:db/add [:seon.cluster.eval/id evaluation-id]
                            :seon.cluster.eval/author :agent]])
       (is (seq (get-in (first (turn/evaluate-sources request))
                        [:seon.sci.eval/evaluation :seon.cluster.eval/read-evidence]))
           "an agent may explicitly inspect its own history")
       (checked-transact! connection
                          [[:db/add [:seon.cluster.eval/id evaluation-id]
                            :seon.cluster.eval/author :system]
                           [:db/add [:seon.turn/id run-id] :seon.turn.work/situation :call]])
       (is (seq (get-in (first (turn/evaluate-sources request))
                        [:seon.sci.eval/evaluation :seon.cluster.eval/read-evidence]))
           "a virtual reply is authored input, not generated context")))))

(deftest a-refused-generated-form-records-its-refusal
  (testing "an appended form whose evaluation is refused never stays bare"
   (support/with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection
                     :seon.boot/cluster-name "refused-generated-form"})
      (support/seed-cluster! connection "refused-generated-form")
      (checked-transact! connection
                         (agent/creation-tx {:seon.agent/id "reader"
                                             :seon.ns/name 'my.agents.reader
                                             :seon.cluster/name "refused-generated-form"}))
      (let [source "(seon.db/q '[:find ?id :where [_ :seon.turn/id ?id]])"
            run-id "refused-generated"
            evaluation-id (id/evaluation run-id 0)
            _ (checked-transact!
               connection
               [{:seon.turn/id run-id
                 :seon.turn/agent [:seon.agent/id "reader"]
                 :seon.turn.work/situation :generate
                 :seon.turn/starting-ns [:seon.ns/name 'my.agents.reader]
                 :seon.turn/opened-tx "datomic.tx"}
                {:seon.cluster.eval/id evaluation-id
                 :seon.cluster.eval/at (java.util.Date.)
                 :seon.cluster.eval/run [:seon.turn/id run-id]
                 :seon.cluster.eval/ordinal 0
                 :seon.cluster.eval/author :system
                 :seon.cluster.eval/ns [:seon.ns/name 'my.agents.reader]
                 :seon.cluster.eval/source source}])
            ctx (support/fork-cluster-ctx connection)
            handle (support/cluster-handle
                    {:seon.env/environment (support/environment "refused-generated-form" connection)
                     :seon.db/connection connection
                     :seon.cluster/name "refused-generated-form"
                     :seon.db.process/id cluster/boot-process-identity
                     :seon.sci.eval/ctx ctx})
            report (turn/turn {:seon.turn.loop/cluster handle
                               :seon.turn.work/next {:seon.turn.work/situation :resume
                                                     :seon.turn/id run-id
                                                     :seon.agent/id "reader"
                                                     :seon.cluster.eval/ordinal 0}}
                              (java.util.Date.))
            settled (db/pull (db/db connection)
                             [:seon.eval/shown :seon.cluster.eval/error
                              :seon.cluster.eval/source]
                             [:seon.cluster.eval/id evaluation-id])]
        (is (= :error (:seon.turn.loop/outcome report)) (pr-str report))
        ;; The class this pins: a form the loop appended and then refused
        ;; must carry a terminal fact. Neither shown nor error is absence
        ;; of signal read as health — no query can say what happened to it.
        (is (string? (:seon.eval/shown settled)) (pr-str settled))
        (is (string? (:seon.cluster.eval/error settled)) (pr-str settled))
        (is (str/includes? (str (:seon.eval/shown settled))
                           "generated-read-depends-on-turns")
            (pr-str settled))
        (is (some? (:seon.turn/closed-tx
                    (db/pull (db/db connection) [:seon.turn/closed-tx]
                             [:seon.turn/id run-id])))))))))

(deftest compaction-refuses-an-open-turn-at-the-writer
  (support/with-database
   (fn [connection]
     (checked-transact!
      connection
      [{:seon.agent/id "busy"}
       {:seon.turn/id "open" :seon.turn/agent [:seon.agent/id "busy"] :seon.turn/opened-tx "datomic.tx"}
       {:seon.cluster.eval/id "unfinished"
        :seon.cluster.eval/at (java.util.Date.)
        :seon.cluster.eval/run [:seon.turn/id "open"]
        :seon.cluster.eval/ordinal 0
        :seon.cluster.eval/source "(+ 1 1)"}])
     (let [before (db/basis-t @connection)
           result (turn/compact! {:seon.db/connection connection
                                  :seon.agent/id "busy"})]
       (is (some? (:seon.error/kind result)) (pr-str result))
       (is (= before (db/basis-t @connection)))
       (is (= 1 (count (evaluations @connection "busy"))))))))

(deftest open-run-tx-call-emits-only-while-its-run-is-open
  (testing "the writer, not a caller's pre-read, decides the run-dependent half"
    (support/with-database
     (fn [connection]
       (checked-transact!
        connection
        [{:seon.agent/id "decider"}
         {:seon.turn/id "still-open" :seon.turn/agent [:seon.agent/id "decider"]
          :seon.turn/opened-tx "datomic.tx"}
         {:seon.turn/id "already-closed" :seon.turn/agent [:seon.agent/id "decider"]
          :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx"}])
       (let [payload [[:db/add "probe" :seon.ns/name 'my.agents.decider]]
             call (fn [run-id]
                    (turn/open-run-tx-call (db/db connection) run-id payload))]
         (is (= payload (call "still-open"))
             "an open run still takes its settlement and close")
         (is (= [] (call "already-closed"))
             "a closed run needs no second close")
         (is (= [] (call "never-existed"))
             "a run that is gone already satisfies what the close wanted")
         (checked-transact! connection [[:db.fn/retractEntity [:seon.turn/id "still-open"]]])
         (is (= [] (call "still-open"))
             "the decision is made against the writer's current database"))))))

(deftest a-terminal-refusal-settles-when-its-run-has-vanished
  (testing "the terminal writer records the outcome, never a fault about recording it"
    (support/with-database
     (fn [connection]
       (config/apply! {:seon.db/connection connection
                       :seon.boot/cluster-name "vanished-run"})
       (support/seed-cluster! connection "vanished-run")
       (checked-transact! connection
                          (agent/creation-tx {:seon.agent/id "reader"
                                              :seon.ns/name 'my.agents.reader
                                              :seon.cluster/name "vanished-run"}))
       (let [run-id "vanished"
             evaluation-id (id/evaluation run-id 0)
             _ (checked-transact!
                connection
                [{:seon.turn/id run-id
                  :seon.turn/agent [:seon.agent/id "reader"]
                  :seon.turn/starting-ns [:seon.ns/name 'my.agents.reader]
                  :seon.turn/opened-tx "datomic.tx"}
                 {:seon.cluster.eval/id evaluation-id
                  :seon.cluster.eval/at (java.util.Date.)
                  :seon.cluster.eval/run [:seon.turn/id run-id]
                  :seon.cluster.eval/ordinal 0
                  :seon.cluster.eval/author :system
                  :seon.cluster.eval/ns [:seon.ns/name 'my.agents.reader]
                  :seon.cluster.eval/source "(+ 1 1)"}])
             handle (support/cluster-handle
                     {:seon.env/environment (support/environment "vanished-run" connection)
                      :seon.db/connection connection
                      :seon.cluster/name "vanished-run"
                      :seon.db.process/id cluster/boot-process-identity
                      :seon.sci.eval/ctx (support/fork-cluster-ctx connection)})
             ;; The turn entity disappears under the running loop — measured
             ;; on `default` 2026-09-16T15:33:01Z, where the live fixture's
             ;; history wipe retracted a turn one transaction after it opened.
             ;; Match clear-history!: evaluation rows and their turn disappear
             ;; together; retaining an evaluation without its run is invalid.
             _ (checked-transact! connection
                                  [[:db.fn/retractEntity [:seon.cluster.eval/id evaluation-id]]
                                   [:db.fn/retractEntity [:seon.turn/id run-id]]])
             refusal {:seon.error/kind :seon.turn/refused
                      :seon.turn/transition `turn/close-call
                      :seon.turn/rule :seon.turn/no-such-run
                      :seon.turn/refused :seon.turn/no-such-run
                      :seon.error/message "run transition refused: no-such-run"}
             settlement (#'turn/settle!
                         {:seon.turn.loop/cluster handle
                          :seon.turn.loop/now (java.util.Date.)
                          :seon.agent/id "reader"
                          :seon.turn/id run-id
                          :seon.cluster.eval/ordinal 0
                          :seon.error/value refusal})
             database (db/db connection)
             kinds (set (db/q '[:find [?kind ...]
                                :where [?error :seon.error/kind ?kind]] database))]
         ;; The class: a settlement whose run-dependent half can no longer
         ;; apply must still commit the durable outcome. Re-issuing the close
         ;; that just refused made the second refusal identical by
         ;; construction and threw into the agent's flow proc.
         (is (map? settlement) (pr-str settlement))
         (is (nil? (:seon.error/kind (:seon.turn.loop/outcome settlement)))
             (pr-str settlement))
         (is (contains? kinds :seon.turn/refused)
             "the refusal the loop actually met is the durable fact")
         (is (nil? (db/pull database [:db/id] [:seon.turn/id run-id]))
             "late settlement does not recreate the vanished turn")
         (is (nil? (db/pull database [:db/id] [:seon.cluster.eval/id evaluation-id]))
             "late settlement records the error without recreating an orphan evaluation")
         (is (not (contains? kinds
                             :seon.turn.loop/terminal-refusal-settlement-refused))
             "no core fault about the recording replaces the outcome")
         (is (nil? (:db/id (db/pull database [:db/id] [:seon.turn/id run-id])))
             "nothing resurrects the retracted turn"))))))

(deftest a-wake-meeting-an-open-turn-releases-without-a-fault
  (testing "the one-open-turn fence firing is ordinary loop flow, not a defect"
    (support/with-database
     (fn [connection]
       (config/apply! {:seon.db/connection connection
                       :seon.boot/cluster-name "busy-agent"})
       (support/seed-cluster! connection "busy-agent")
       (checked-transact! connection
                          (agent/creation-tx {:seon.agent/id "reader"
                                              :seon.ns/name 'my.agents.reader
                                              :seon.cluster/name "busy-agent"}))
       (checked-transact!
        connection
        [{:seon.turn/id "already-running"
          :seon.turn/agent [:seon.agent/id "reader"]
          :seon.turn/starting-ns [:seon.ns/name 'my.agents.reader]
          :seon.turn/opened-tx "datomic.tx"}])
       (let [handle (support/cluster-handle
                     {:seon.env/environment (support/environment "busy-agent" connection)
                      :seon.db/connection connection
                      :seon.cluster/name "busy-agent"
                      :seon.db.process/id cluster/boot-process-identity
                      :seon.sci.eval/ctx (support/fork-cluster-ctx connection)})
             reported (atom [])
             _ (#'turn/open-turn
                {:seon.turn.loop/cluster handle
                 :seon.turn.loop/work {:seon.agent/id "reader"}
                 :seon.turn.loop/now (java.util.Date.)
                 :seon.turn.loop/report (fn [& arguments] (swap! reported conj (vec arguments)))})
             database (db/db connection)
             refusals (db/q '[:find [?error ...]
                              :where [?error :seon.error/kind :seon.turn/refused]]
                             database)]
         ;; Turn PRD §3/§14: a wake arriving while a turn is open has `:t`
         ;; greater than that turn's basis and opens the NEXT turn, answered
         ;; by the `:t` rule. Nothing is claimed and nothing is lost, so the
         ;; fence firing is the design — not a durable core fault that also
         ;; wakes the steward through `:seon.error/steward`.
         (is (= [[:released 0]] @reported) (pr-str @reported))
         (is (empty? refusals) (pr-str refusals))
         (is (= "already-running"
                (db/q '[:find ?id . :where
                        [?agent :seon.agent/id "reader"]
                        [?turn :seon.turn/agent ?agent]
                        [?turn :seon.turn/id ?id]
                        (not [?turn :seon.turn/closed-tx _])] database))
             "the open turn is untouched and the fence still holds"))))))

(defn- virtual-turn-fixture []
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                     :seon.boot/cluster-name "virtual-turns"})
     (support/seed-cluster! connection "virtual-turns")
     (doseq [[id namespace] [["a" 'my.agents.a] ["b" 'my.agents.b]]]
       (checked-transact! connection
                          (agent/creation-tx {:seon.agent/id id
                                              :seon.ns/name namespace :seon.cluster/name "virtual-turns"})))
     (let [ctx (support/fork-cluster-ctx connection)
           environment (support/environment "virtual-turns" connection)
           launcher (flow/start-work-launcher!
                     {:seon.env/environment environment
                      :seon.flow/configuration
                      (select-keys (support/effective-config)
                                   flow/flow-workload-attributes)})
           routing (agent/routing)
           faults (async/chan (async/sliding-buffer 16))
           events (async/chan (async/sliding-buffer 1))
           transactions (atom [])
           stable-identities (atom nil)
           handle (support/cluster-handle
                   {:seon.env/environment environment
                    :seon.db/connection connection
                    :seon.cluster/name "virtual-turns"
                    :seon.flow/work-launcher launcher
                    :seon.flow/executor
                    (cluster/projection-executor
                     (:seon.sci.eval/projection-state ctx))
                    :seon.sci.eval/ctx ctx
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.turn.loop/stream-channel
                    (async/chan (async/sliding-buffer 1))})
           submit (fn [id & [source]]
                    (let [source (or source "(+ 1 1)")
                          result (#'web/change-context
                                  {:seon.render/context-action :virtual-turn
                                   :seon.turn.loop/cluster handle
                                   :seon.agent/routing routing
                                   :seon.agent/id id
                                   :seon.cluster.reply/text source})
                          turn-id (when-not (:seon.error/kind result)
                                    (:seon.turn/id
                                     (db/pull @connection [:seon.turn/id]
                                              (get-in (last (evaluation/of-agent @connection id))
                                                      [:seon.cluster.eval/run :db/id]))))
                          closed? #(some? (:seon.turn/closed-tx
                                           (db/pull @connection
                                                    [:seon.turn/closed-tx]
                                                    [:seon.turn/id turn-id])))]
                      (is (string? turn-id) (pr-str result))
                      (when-not (closed?)
                        (support/await-event! events ::closed (fn [_] (closed?))))
                      (is (closed?))
                      (is (empty?
                           (db/q '[:find [?attempt ...] :in $ ?id
                                   :where [?turn :seon.turn/id ?id]
                                   [?turn :seon.turn/attempts ?attempt]]
                                 @connection turn-id)))
                      (is (= source
                             (:seon.turn/reply
                              (db/pull @connection [:seon.turn/reply]
                                       [:seon.turn/id turn-id]))))
                      turn-id))]
       (swap! routing assoc :seon.agent/fault-channel faults)
       (d/listen connection ::turns
                 (fn [report]
                   (swap! transactions conj
                          {:seon.test/datoms (count (:tx-data report))
                           :seon.test/attributes
                           (into #{} (map :a) (:tx-data report))})
                   (async/offer! events report)))
       (try
         (let [previous-id "previous-jvm"
               seeded (checked-transact!
                       connection
                       [{:seon.turn/id previous-id :seon.turn/agent [:seon.agent/id "a"] :seon.turn/opened-tx "datomic.tx"}])]
           (is (nil? (:seon.error/kind seeded)) (pr-str seeded))
           (is (nil? (:seon.turn/closed-tx
                      (db/pull @connection '[*]
                               [:seon.turn/id previous-id]))))
           (is (= 1 (:seon.boot/recovered-runs
                     (#'cluster/recover-runs! connection))))
           (is (some? (:seon.turn/closed-tx
                        (db/pull @connection '[*]
                                 [:seon.turn/id previous-id]))))
           (is (empty? (evaluations @connection "a"))
               "boot invents no evaluation when the prior JVM stored no reply"))
         (doseq [id ["a" "b"]]
           (agent/arm! {:seon.turn.loop/cluster handle
                        :seon.agent/routing routing
                        :seon.agent/id id}))
         (reset! transactions [])
         (submit "a")
         (is (= [16 6 2] (mapv :seon.test/datoms @transactions))
             "the cold turn is open, evaluations, close, with no empty schedule recovery")
         (println {:seon.test/virtual-turn-transactions (count @transactions)
                   :seon.test/virtual-turn-datoms @transactions})
         (submit "b")
         (let [database @connection
               basis (db/basis-t database)
               saved (evaluation/of-agent database "a")]
           (is (= 1 (count saved)))
           (is (= "(+ 1 1)" (:seon.cluster.eval/source (first saved))))
           (is (= 'my.agents.a
                  (get-in (first saved) [:seon.cluster.eval/ns :seon.ns/name])))
           (is (= saved (evaluation/of-agent database "a")))
           (let [turn-id (:seon.turn/id
                          (db/pull database [:seon.turn/id]
                                   (get-in (first saved) [:seon.cluster.eval/run :db/id])))
                 render-request (merge handle
                                  {:seon.db/db database :seon.db/connection connection
                                   :seon.agent/id "a" :seon.turn/id turn-id
                                   :seon.sci.eval/time-limit-ms 2000})
                 rendered (transcript/render-ledger-turn render-request)
                 text (apply str (filter string? (tree-seq sequential? seq rendered)))
                 html (hiccup/->string rendered)]
             (is (str/includes? html "data-ledger-loaded") html)
             (is (str/includes? html "seon-ledger-full-context") html)
             (is (str/includes? text "(+ 1 1)") html)
             (is (not (str/includes? html "items, depth")) html)
             (is (not (str/includes? html "read-evidence")) html))
           (is (= basis (db/basis-t @connection)))
           (is (= :seon.eval/agent-not-found
                  (:seon.error/kind (evaluation/of-agent database "absent")))))
         (let [a (evaluations @connection "a")
               b (evaluations @connection "b")]
           (is (= 1 (count a)))
           (is (= 1 (count b)))
           (is (= 2 (edn/read-string
                      (:seon.eval/shown
                       (db/pull @connection [:seon.eval/shown]
                                (first a))))))
           (is (nil? (:seon.error/kind
                      (turn/compact! {:seon.db/connection connection
                                      :seon.agent/id "a"}))))
           (is (empty? (evaluations @connection "a")))
           (is (= b (evaluations @connection "b")))
           (submit "a")
           (is (= 1 (count (evaluations @connection "a")))))
         (reset! transactions [])
         (let [turn-id (submit "a" "(+ 1 1)\n(+ 2 2)\n(+ 3 3)")
               measured @transactions]
           (println {:seon.test/three-form-turn turn-id
                     :seon.test/transactions measured
                     :seon.test/transaction-count (count measured)
                     :seon.test/datom-count (reduce + (map :seon.test/datoms measured))})
           (is (= [30 16 2] (mapv :seon.test/datoms measured)))
           (is (= 3 (count measured)))
           (is (= 48 (reduce + (map :seon.test/datoms measured))))
           (is (= 1 (count (turn/receipt-settle-batch-tx
                            (mapv (fn [ordinal]
                                    {::turn/id turn-id
                                     :seon.cluster.eval/ordinal ordinal
                                     :seon.eval/shown "nil"})
                                  (range 3))))))
           (is (= ["2" "4" "6"]
                  (mapv :seon.eval/shown
                        (filter #(= (:db/id (db/pull @connection [:db/id]
                                                   [:seon.turn/id turn-id]))
                                    (get-in % [:seon.cluster.eval/run :db/id]))
                                (evaluation/of-agent @connection "a")))))
           (reset! stable-identities
                   [turn-id (mapv :seon.cluster.eval/id
                                  (take-last 3 (evaluation/of-agent @connection "a")))]))
         (submit "a" "(def private-state (atom 2))")
         (let [agent-context #(get-in (agent/armed routing %)
                                     [:seon.turn.loop/cluster
                                      :seon.sci.eval/agent-ctx])
               a-context (agent-context "a")
               private-object @(sci/resolve a-context 'my.agents.a/private-state)]
           (submit "b" "(+ 2 2)")
           (is (nil? (sci/resolve (agent-context "b") 'my.agents.a/private-state)))
           (is (nil? (sci/resolve ctx 'my.agents.a/private-state)))
           (submit "a" "(swap! private-state inc)")
           (is (identical? a-context (agent-context "a")))
           (is (identical? private-object
                           @(sci/resolve a-context 'my.agents.a/private-state)))
           (is (= 3 @private-object))
           (submit "a" "(identity private-state)")
           (let [saved (last (evaluation/of-agent @connection "a"))
                 result-name (:seon.repl/handle (repl/entity-emission saved))]
             (is (qualified-symbol? result-name))
             (is (identical? private-object @(sci/resolve a-context result-name)))
             (is (nil? (sci/resolve (agent-context "b") result-name)))
             (is (nil? (sci/resolve ctx result-name))))
           (checked-transact! connection
                         [{:seon.agent/id "c"
                           :seon.agent/namespace
                           {:seon.ns/name 'my.agents.c}}])
           (agent/arm! {:seon.turn.loop/cluster handle
                        :seon.agent/routing routing
                        :seon.agent/id "c"})
           (submit "c" "(+ 3 3)")
           (is (nil? (sci/resolve (agent-context "c") 'my.agents.a/private-state)))
           (is (nil? (sci/resolve (support/fork-cluster-ctx connection)
                                 'my.agents.a/private-state))
               "fresh acquisition cannot restore a private object from the database")
           (submit "a" "(defn shared {:malli/schema [:=> [:cat] :int]} [] 42)")
           (let [b-context (agent-context "b")]
             (submit "b" "(my.agents.a/shared)")
             (is (identical? b-context (agent-context "b")))
             (is (= 42 (edn/read-string
                         (:seon.eval/shown
                          (last (evaluation/of-agent @connection "b")))))))
           (is (empty? (filter #(= "seon.def" (namespace %))
                               (db/q '[:find [?key ...]
                                       :where [_ :seon.schema/key ?key]]
                                     @connection)))))
         (let [request {:seon.turn.loop/cluster handle
                        :seon.agent/id "a"
                        :seon.turn/write? true}
               opening (turn/system-turn request)
               opening-sources (mapv :seon.cluster.eval/source
                                     (filter #(= :none (:seon.turn/status %))
                                             (:seon.turn/forms opening)))]
           (is (nil? (:seon.error/kind opening)) (pr-str opening))
           (is (seq opening-sources) (pr-str opening))
           (is (string? (:seon.turn/id opening)))
           (let [rendered (transcript/render-ledger-turn
                        (merge handle
                               {:seon.db/db @connection :seon.db/connection connection
                                :seon.agent/id "a" :seon.turn/id (:seon.turn/id opening)
                                :seon.sci.eval/time-limit-ms 2000}))
                 text (apply str (filter string? (tree-seq sequential? seq rendered)))
                 html (hiccup/->string rendered)]
             (is (some #(= :none (:seon.turn/status %)) (:seon.turn/forms opening)))
             (is (str/includes? html "WE GENERATED"))
             (doseq [source opening-sources]
               (is (str/includes? text source)))
             (is (not (str/includes? html "items, depth"))))
           (let [basis (db/basis-t @connection)
                 unchanged (turn/system-turn (assoc request :seon.turn/write? false))]
             (is (seq (:seon.turn/forms unchanged)) (pr-str unchanged))
             (is (= #{}
                    (set (map (comp first edn/read-string :seon.cluster.eval/source)
                              (filter #(= :changed (:seon.turn/status %))
                                      (:seon.turn/forms unchanged)))))
                 "the saved opening does not read its own turn history")
             (is (nil? (:seon.turn/id unchanged)))
             (is (= basis (db/basis-t @connection))))
           (turn/compact! {:seon.db/connection connection
                           :seon.agent/id "a"})
           (let [fresh (turn/system-turn request)]
             (is (= opening-sources
                    (mapv :seon.cluster.eval/source (:seon.turn/forms fresh))))
             (is (every? #(= :none (:seon.turn/status %))
                         (:seon.turn/forms fresh))))
            (submit "a" "(my.message/inbox {})")
           ;; Message facts are changed with both procs stopped. Only the
           ;; explicit system walk runs: this proof cannot call a provider.
           (doseq [id ["a" "b" "c"]]
             (agent/disarm! {:seon.agent/routing routing
                             :seon.agent/id id}))
           (checked-transact! connection
                         [{:seon.message/id "to-b" :seon.message/to [:seon.agent/id "b"] :seon.message/content "For B"}])
           (let [other (turn/system-turn (assoc request :seon.turn/write? false))]
             (is (seq (:seon.turn/forms other)) (pr-str other))
             (is (= #{}
                    (set (map (comp first edn/read-string :seon.cluster.eval/source)
                              (filter #(= :changed (:seon.turn/status %))
                                      (:seon.turn/forms other))))))
             (is (= #{:unchanged}
                    (set (map :seon.turn/status
                              (filter #(= 'my.message/inbox
                                          (first (edn/read-string (:seon.cluster.eval/source %))))
                                      (:seon.turn/forms other)))))
                 "a peer message does not refresh this agent's inbox")
             (is (nil? (:seon.turn/id other))))
           (checked-transact! connection
                         [{:seon.message/id "to-a" :seon.message/to [:seon.agent/id "a"] :seon.message/content "For A"}])
           (let [basis (db/basis-t @connection)
                 preview (turn/system-turn (assoc request :seon.turn/write? false))
                 changed (filterv #(= :changed (:seon.turn/status %))
                                  (:seon.turn/forms preview))]
             (is (= basis (db/basis-t @connection)))
             (is (= #{'seon.db/pull 'my.message/inbox}
                    (set (map (comp first edn/read-string :seon.cluster.eval/source) changed)))
                 (pr-str preview))
             (is (seq (:seon.turn/changes (first changed))))
             (is (string? (:seon.turn/text (first changed))))
             (let [stored (turn/system-turn request)]
               (is (string? (:seon.turn/id stored)) (pr-str stored))
               (is (= 2 (count (db/q '[:find [?e ...] :in $ ?id
                                      :where [?turn :seon.turn/id ?id]
                                      [?e :seon.cluster.eval/run ?turn]]
                                    @connection (:seon.turn/id stored))))))))
         (finally
           (doseq [id ["a" "b" "c"]]
             (agent/disarm! {:seon.agent/routing routing
                             :seon.agent/id id}))
           (d/unlisten connection ::turns)
           (flow/stop-work-launcher! launcher)
           (doseq [channel [events faults
                            (:seon.cluster.wake/channel handle)
                            (:seon.render/context-channel handle)
                            (:seon.turn.loop/completion handle)
                            (:seon.turn.loop/stream-channel handle)]]
             (async/close! channel))))
       @stable-identities))))

(deftest virtual-turns-use-the-proc-and-compaction-is-agent-scoped
  (let [first-fork (virtual-turn-fixture)
        refork (virtual-turn-fixture)]
    (is (= first-fork refork)
        "the same virtual turns on fresh canonical forks retain their turn and evaluation identities")
    (is (= 3 (count (second first-fork))))))

(deftest evaluation-ai-is-only-the-repl-session
  (let [rendered
        (repl/render-ai
         {:db/id 7042
          :seon.cluster.eval/id "7042"
          :seon.cluster.eval/source "(+ 40 2)"
          :seon.cluster.eval/ordinal 0
          :seon.cluster.eval/ns {:seon.ns/name 'my.probe}
          :seon.eval/shown
          "42"})
        lines (str/split-lines rendered)]
    (is (= "my.probe=> (+ 40 2)\n#:seon.repl{:value 42, :result result/e7042}"
           rendered)
        "the handle is the evaluation's own entity id, never its ordinal")
    ;; THE RESPONSE IS ONE DATUM. It may wrap when a value is wide, but the
    ;; prompt line is followed by the map and by nothing else: a second
    ;; line that is not a continuation of that map would be a second grammar,
    ;; and a comment-shaped one would be something the agent should write.
    (is (= 2 (count lines)))
    (is (str/starts-with? (second lines) "#:seon.repl{"))
    (is (str/ends-with? (last lines) "}"))
    (is (not-any? #(str/starts-with? (str/trim %) ";") (rest lines))))
  (is (nil? (repl/render-ai {}))
      "an evaluation with no source is not a REPL entry")
  ;; AN INTERRUPTED EVALUATION SAYS SO. `interrupted-at` is an inst, not a
  ;; flag, and since the REPL grammar renders it the response is the instant
  ;; the boot cut the evaluation — never a bare prompt that reads exactly
  ;; like a form still running.
  (let [interrupted (repl/render-ai
                     {:seon.cluster.eval/source "(side-effect)"
                      :seon.cluster.eval/interrupted-at
                      (java.util.Date. 1785500000000)})]
    (is (str/starts-with? interrupted "user=> (side-effect)"))
    (is (str/includes? interrupted ":interrupted #inst") interrupted)
    (is (not (str/includes? interrupted ":value"))
        "an interrupted evaluation answers no value"))
  (let [triage-edn
        (try
          (/ 1 0)
          (catch Throwable throwable
            (pr-str (main/ex-triage (Throwable->map throwable)))))
        rendered
        (repl/render-ai
         {:seon.cluster.eval/source "(/ 1 0)"
          :seon.cluster.eval/ordinal 0
          :seon.cluster.eval/error "Divide by zero"
          :seon.cluster.eval/triage-edn triage-edn})]
    (is (str/includes? rendered ":error \"Execution error (ArithmeticException) at"))
    (is (str/includes? rendered "Divide by zero"))
    (is (not (str/includes? rendered "Form ")))
    (is (not (str/includes? rendered "failed:")))))

;;; ---------------------------------------------------------------------------
;;; In-memory database fixture
;;; ---------------------------------------------------------------------------

(defn- with-model-database [body]
  (support/with-database body))

(deftest unresolved-test-subjects-remain-symbol-values
  (with-model-database
    (fn [connection]
      (let [namespace-name 'fixture.pending
            test-symbol 'fixture.pending/target-test
            function-symbol 'fixture.pending/target
            now (java.util.Date. 1785000000000)
            settle-row!
            (fn [run-id agent-id row]
              (let [source (or (:seon.fn/source row) (:seon.test/source row))
                    namespace-ref (or (:seon.fn/ns row) (:seon.test/ns row))
                    row (second
                         (seon.fn/analyze-form
                          @connection source namespace-ref row))]
                (support/transacted!
                        connection
                        [{:seon.agent/id agent-id}
                         {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}])
                (support/transacted!
                        connection
                        (turn/receipt-start-tx
                         {::turn/id run-id
                          :seon.cluster.eval/ordinal 0
                          :seon.cluster.eval/at now}))
                (support/transacted!
                        connection
                        (turn/receipt-settle-tx
                         {::turn/id run-id
                          :seon.cluster.eval/ordinal 0
                          :seon.eval/shown "nil"
                          :seon.program/row row}))))]
        (support/transacted! connection [{:seon.ns/name namespace-name}])
        (settle-row!
         "pending-test-run" "pending-test-agent"
         {:seon.test/sym test-symbol
          :seon.test/ns [:seon.ns/name namespace-name]
          :seon.test/source
          (str "(clojure.test/deftest ^{:seon.test/subject " function-symbol
               "} target-test (clojure.test/is true))")
          :seon.schema.admission/source :agent
          :seon.test/subject function-symbol})
        (let [pending (db/pull @connection '[*]
                               [:seon.test/sym test-symbol])]
          (is (= function-symbol (:seon.test/subject pending))
              "the observed subject name is stored before a function row exists")
          (is (nil? (:db/id (db/pull @connection [:db/id]
                                     [:seon.fn/sym function-symbol])))
              "settlement does not fabricate a function row for the name")
          (is (some #{test-symbol}
                    (seon.fn/gate-set @connection function-symbol))))
        (settle-row!
         "pending-function-run" "pending-function-agent"
         {:seon.fn/sym function-symbol
          :seon.fn/ns [:seon.ns/name namespace-name]
          :seon.fn/source
          "(defn ^{:malli/schema [:=> [:cat] :int]} target [] 1)"
          :seon.fn/arglists "([])"
          :seon.fn/private? false
          :seon.fn/spec "[:=> [:cat] :int]"
          :seon.schema.admission/source :agent})
        (let [resolved (db/pull @connection '[:seon.test/subject]
                                [:seon.test/sym test-symbol])]
          (is (= function-symbol (:seon.test/subject resolved))
              "the value edge needs no repair when the function arrives")
          (is (some #{test-symbol}
                    (seon.fn/gate-set @connection function-symbol))))))))

(deftest batch-settlement-preserves-declaration-order
  (support/with-database
   (fn [connection]
     (let [now (java.util.Date.)]
       (support/transacted! connection
                            [{:seon.ns/name 'fixture.batch}
                             {:seon.agent/id "batch"}
                             {:seon.turn/id "batch" :seon.turn/agent [:seon.agent/id "batch"] :seon.turn/opened-tx "datomic.tx"}])
       (doseq [ordinal (range 2)]
         (support/transacted! connection
                              (turn/receipt-start-tx
                               {::turn/id "batch" :seon.cluster.eval/ordinal ordinal
                                :seon.cluster.eval/at now})))
       (let [sources [(str "(clojure.test/deftest "
                           "^{:seon.test/subject fixture.batch/target} "
                           "target-test (clojure.test/is true))")
                      "(defn ^{:malli/schema [:=> [:cat] :int]} target [] 1)"]
             preliminary-rows
             [{:seon.test/sym 'fixture.batch/target-test
               :seon.test/ns [:seon.ns/name 'fixture.batch]
               :seon.test/source (first sources)
               :seon.test/subject 'fixture.batch/target
               :seon.schema.admission/source :agent}
              {:seon.fn/sym 'fixture.batch/target
               :seon.fn/ns [:seon.ns/name 'fixture.batch]
               :seon.fn/source (second sources)
               :seon.fn/arglists "([])"
               :seon.fn/private? false
               :seon.fn/spec "[:=> [:cat] :int]"
               :seon.schema.admission/source :agent}]
             rows (mapv (fn [source row]
                          (second
                           (seon.fn/analyze-form
                            @connection source
                            (or (:seon.test/ns row) (:seon.fn/ns row)) row)))
                        sources preliminary-rows)
             result (db/transact!
                     connection
                     (turn/receipt-settle-batch-tx
                      (mapv (fn [ordinal row]
                              {::turn/id "batch" :seon.cluster.eval/ordinal ordinal
                               :seon.eval/shown "nil" :seon.program/row row})
                            (range 2) rows)))
             saved (db/pull @connection '[:seon.test/subject]
                            [:seon.test/sym 'fixture.batch/target-test])]
         (is (nil? (:seon.error/kind result)) (pr-str result))
         (is (= 'fixture.batch/target (:seon.test/subject saved))))))))

;; Deterministic clock: every generated time is an offset from t0.
(def ^:private t0-ms 1785000000000)
(defn- at [offset-ms] (java.util.Date. (long (+ t0-ms offset-ms))))
(def ^:private t0 (at 0))
(def ^:private t1 (at 300000))
(def ^:private t2 (at 1800000))

(defn- deepest-ex-data [error]
  (loop [throwable error
         found nil]
    (if throwable
      (recur (ex-cause throwable)
             (or (not-empty (ex-data throwable)) found))
      found)))

(defn- transact-or-refusal
  "Commit tx-data; a refusal returns its deepest ex-data as a value."
  [connection tx-data]
  (try
    (let [result (db/transact! connection tx-data)]
      (if (:seon.error/kind result)
        result
        ::committed))
    (catch Exception e
      (or (deepest-ex-data e)
          {::opaque (ex-message e)}))))

(defn- run-entity [connection run-id]
  (db/pull (db/db connection) '[*] [:seon.turn/id run-id]))

(defn- open-run-id [connection agent-id]
  (turn/open-for-agent (db/db connection)
                      [:seon.agent/id agent-id]))

;;; ---------------------------------------------------------------------------
;;; Derivations — state is computed from primitives, never stored
;;; ---------------------------------------------------------------------------

(deftest state-is-derived-from-primitives
  (testing "open is the absence of closed-at"
    (let [open-turn {:seon.turn/id "open"
                     :seon.turn/agent [:seon.agent/id "a"]
                     :seon.turn/opened-tx 1}]
      (is (true? (turn/open? open-turn)))
      (is (false? (turn/open? (assoc open-turn
                                     :seon.turn/closed-tx 2))))))
  )

(deftest an-open-turn-read-refusal-refuses-the-writer-verbatim
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            refusal (db/q '[:find ?entity .
                            :where [?entity :seon.audit/poison _]]
                          database)
            before (db/basis-t database)
            result (support/refusal-data
                    #(#'turn/require-open-run
                      refusal `turn/close-call {:seon.turn/id "unreadable"}))]
        (is (and (map? refusal)
                 (contains? refusal :seon.error/at)
                 (contains? refusal :seon.error/layer)
                 (contains? refusal :seon.error/operation)) (pr-str refusal))
        (is (= refusal result)
            "the serial eligibility read is the refusal; it is never absence or an open turn")
        (is (= before (db/basis-t (db/db connection)))
            "refusing eligibility commits no transaction")))))

(deftest an-episode-budget-read-refusal-reaches-next-work-verbatim
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            refusal (db/q '[:find ?entity .
                            :where [?entity :seon.audit/poison _]]
                          database)
            issue-budget-query
            '[:find ?budget . :in $ ?id :where
              [?agent :seon.agent/id ?id] [?issue :seon.issue/agent ?agent]
              [?issue :seon.issue/budget ?budget]]
            query db/q]
        (is (and (map? refusal)
                 (contains? refusal :seon.error/at)
                 (contains? refusal :seon.error/layer)
                 (contains? refusal :seon.error/operation)) (pr-str refusal))
        (with-redefs [db/q
                      (fn [form & arguments]
                        (if (= issue-budget-query form)
                          refusal
                          (apply query form arguments)))]
          (is (= refusal
                 (turn/next-agent-work
                  database {:seon.agent/id "unreadable-budget"}))))))))

(deftest a-history-read-refusal-cannot-suppress-declaration-divergence
  (support/with-database
    (fn [connection]
      (let [database (db/db connection)
            refusal (db/q '[:find ?entity .
                            :where [?entity :seon.audit/poison _]]
                          database)
            existing {:seon.schema/key :seon.audit/diverged
                      :seon.schema/form ":string"}
            result
            (with-redefs-fn
              {#'turn/opening-db (fn [_database _run-id] database)
               #'db/history (fn [_database] refusal)}
              #(support/refusal-data
                (fn []
                  (#'turn/declaration-diverged-since-open?
                   database {:seon.turn/id "history-refused"}
                   :seon.schema/key :seon.audit/diverged existing))))]
        (is (and (map? refusal)
                 (contains? refusal :seon.error/at)
                 (contains? refusal :seon.error/layer)
                 (contains? refusal :seon.error/operation)) (pr-str refusal))
        (is (= refusal result)
            "an unreadable history is the divergence refusal, never evidence that the run wrote the declaration")))))

(deftest interrupted-warning-is-one-derived-value
  (testing "clean evaluations derive no warning at all"
    (is (nil? (turn/interrupted-warning
               [{:seon.cluster.eval/ordinal 0
                 :seon.eval/shown "1"}
                {:seon.cluster.eval/ordinal 1}
                {:seon.cluster.eval/ordinal 2}]))))
  (testing "an interrupted evaluation derives exactly one warning naming
            the first interrupted ordinal and the missing tail"
    (let [warning (turn/interrupted-warning
                   [{:seon.cluster.eval/ordinal 0
                     :seon.eval/shown "1"}
                    {:seon.cluster.eval/ordinal 1
                     :seon.cluster.eval/interrupted-at t1}
                    {:seon.cluster.eval/ordinal 2}])]
      (is (= 1 (:seon.cluster.eval/ordinal warning)))
      (is (= 2 (:seon.turn/missing-results warning))))))

(deftest a-frozen-run-holds-exactly-one-evaluation-per-ordinal-and-no-form-entity
  ;; THE MERGE, ASSERTED AS A COUNT. A frozen plan of N sources leaves N
  ;; evaluation entities and nothing else: no twin form entity, no second
  ;; `:db.unique/identity` attribute that a derived identity string could
  ;; resolve against, and no run-side component list mirroring the back-edge
  ;; the evaluations already carry.
  (with-model-database
    (fn [connection]
      (support/transacted! connection [{:seon.agent/id "merged-agent"}])
      (support/transacted!
              connection
              (turn/open-tx {::turn/id "merged" ::turn/agent [:seon.agent/id "merged-agent"] :seon.turn/opened-tx "datomic.tx"}))

      (is (= ::committed
             (transact-or-refusal
              connection
              (turn/plan-tx {::turn/id "merged" :seon.db.process/id "p1" ::turn/starting-ns [:seon.ns/name 'user] :seon.cluster.eval/at t1 ::turn/sources [{:seon.cluster.eval/source "(+ 1 1)"}
                             {:seon.cluster.eval/source "(+ 2 2)"}
                             {:seon.cluster.eval/source "(+ 3 3)"}]}))))
      (let [database (db/db connection)
            run (run-entity connection "merged")
            evaluations
            (db/q '[:find [(pull ?evaluation [*]) ...]
                    :in $ ?run
                    :where [?evaluation :seon.cluster.eval/run ?run]]
                  database (:db/id run))]
        (is (= 3 (count evaluations))
            "one entity per (run, ordinal), never two")
        (is (= [0 1 2] (sort (map :seon.cluster.eval/ordinal evaluations))))
        (is (= 3 (count (set (map :db/id evaluations))))
            "three ordinals name three entities, not six")
        (is (= (set (map #(turn/receipt-identity "merged" %) [0 1 2]))
               (set (map :seon.cluster.eval/id evaluations)))
            "one identity derivation names every frozen ordinal")
        (is (= ["(+ 1 1)" "(+ 2 2)" "(+ 3 3)"]
               (mapv :seon.cluster.eval/source
                     (sort-by :seon.cluster.eval/ordinal evaluations)))
            "the frozen source is an attribute of the evaluation itself")
        (is (every? #(= :agent (:seon.cluster.eval/author %)) evaluations))
        (is (not-any? turn/terminal? evaluations)
            "the freeze asserts no terminal fact; that absence IS running")
        (is (empty?
             (filter #(and (keyword? %)
                           (= "seon.cluster.run.form" (namespace %)))
                     (keys (:schema database))))
            "no attribute of the deleted form family is installed")
        (is (nil? (find run :seon.turn/forms))
            "the run keeps no component mirror of the back-edge")))))

;;; ---------------------------------------------------------------------------
;;; Teaching examples — the call shapes, one committed lifecycle
;;; ---------------------------------------------------------------------------

(deftest one-run-lifecycle-teaches-the-call-shapes
  (with-model-database
    (fn [connection]
      (support/transacted! connection [{:seon.agent/id "teacher"}])
      (testing "open: run entity + agent pointer from ONE agent ref"
        (is (= ::committed
               (transact-or-refusal
                connection
                (turn/open-tx {::turn/id "lesson" ::turn/agent [:seon.agent/id "teacher"] :seon.turn/opened-tx "datomic.tx"}))))
        (is (= "lesson" (open-run-id connection "teacher"))))

      (testing "plan freezes once, with its ordered owned forms"
        (is (= ::committed
               (transact-or-refusal
                connection
                (turn/plan-tx {::turn/id "lesson" :seon.db.process/id "p1" ::turn/starting-ns [:seon.ns/name 'user] ::turn/sources [{:seon.cluster.eval/source "(+ 1 1)"}
                               {:seon.cluster.eval/source "(+ 2 2)"}]}))))
        (is (= ["(+ 1 1)" "(+ 2 2)"]
               (->> (db/q '[:find ?ordinal ?source
                           :in $ ?run-id
                           :where
                           [?run :seon.turn/id ?run-id]
                           [?form :seon.cluster.eval/run ?run]
                           [?form :seon.cluster.eval/ordinal ?ordinal]
                           [?form :seon.cluster.eval/source ?source]]
                         (db/db connection) "lesson")
                    (sort-by first)
                    (mapv second)))))
        (is (= #{:agent}
               (set
                (db/q '[:find [?author ...]
                        :in $ ?run-id
                        :where
                        [?run :seon.turn/id ?run-id]
                        [?form :seon.cluster.eval/run ?run]
                        [?form :seon.cluster.eval/author ?author]]
                      @connection "lesson"))))
      (testing "close settles the run and retracts the pointer it
                derived from the run's own agent connection"
        (is (= ::committed
               (transact-or-refusal
                connection
                (turn/close-tx {::turn/id "lesson" :seon.db.process/id "p1" :seon.turn/closed-tx "datomic.tx"}))))
        (let [entity (run-entity connection "lesson")]
          (is (false? (turn/open? entity)))
          )
        (is (nil? (open-run-id connection "teacher")))))))

(deftest generated-system-runs-grow-only-after-their-settled-prefix
  (with-model-database
    (fn [connection]
      (support/transacted!
              connection
              [{:seon.ns/name 'my.agents.generated}
               {:seon.agent/id "generated-agent"
                :seon.agent/namespace
                [:seon.ns/name 'my.agents.generated]}])
      (is (= ::committed
             (transact-or-refusal
              connection
              (turn/generated-run-tx
               @connection
               {:seon.agent/id "generated-agent" ::turn/id "generated-run" :seon.db.process/id "generated-process" :seon.turn/opened-tx "datomic.tx" ::turn/starting-ns [:seon.ns/name 'my.agents.generated]}))))
      (is (= {:seon.turn.work/situation :generate
              ::turn/starting-ns {:seon.ns/name 'my.agents.generated}}
             (db/pull @connection
                      '[:seon.turn.work/situation
                        {:seon.turn/starting-ns [:seon.ns/name]}]
                      [::turn/id "generated-run"])))
      (is (empty?
           (db/q '[:find ?form
                   :where
                   [?run :seon.turn/id "generated-run"]
                   [?form :seon.cluster.eval/run ?run]]
                 @connection)))
      (is (= ::committed
             (transact-or-refusal
              connection
              (turn/append-generated-tx
               {::turn/id "generated-run"
                :seon.db.process/id "generated-process"
                :seon.cluster.eval/at t0
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/source "(help)"
                :seon.ns/name 'my.agents.generated}))))
      (is (= {:seon.cluster.eval/ordinal 0
              :seon.cluster.eval/at t0
              :seon.cluster.eval/source "(help)"
              :seon.cluster.eval/ns {:seon.ns/name 'my.agents.generated}}
             (db/pull @connection
                      '[:seon.cluster.eval/ordinal :seon.cluster.eval/at
                        :seon.cluster.eval/source
                        {:seon.cluster.eval/ns [:seon.ns/name]}]
                      [:seon.cluster.eval/id
                       (turn/receipt-identity "generated-run" 0)]))
          "the durable generated form and running evaluation are atomic")
      (is (= ::turn/generated-prefix-unsettled
             (::turn/rule
              (transact-or-refusal
               connection
               (turn/append-generated-tx
                {::turn/id "generated-run"
                 :seon.db.process/id "generated-process"
                 :seon.cluster.eval/at t0
                 :seon.cluster.eval/ordinal 1
                 :seon.cluster.eval/source "(dir 'my.turn)"
                 :seon.ns/name 'my.agents.generated})))))
      (support/transacted!
              connection
              (turn/receipt-settle-tx
               {::turn/id "generated-run"
                :seon.cluster.eval/ordinal 0
                :seon.eval/shown "{:introduced 'my.turn}"}))
      (is (= ::committed
             (transact-or-refusal
              connection
              (turn/append-generated-tx
               {::turn/id "generated-run"
                :seon.db.process/id "generated-process"
                :seon.cluster.eval/at t1
                :seon.cluster.eval/ordinal 1
                :seon.cluster.eval/source "(dir 'my.turn)"
                :seon.ns/name 'my.agents.generated}))))
      (is (= [[0 :system "(help)"]
              [1 :system "(dir 'my.turn)"]]
             (db/q {:query
                    '[:find ?ordinal ?author ?source
                      :where
                      [?run :seon.turn/id "generated-run"]
                      [?form :seon.cluster.eval/run ?run]
                      [?form :seon.cluster.eval/ordinal ?ordinal]
                      [?form :seon.cluster.eval/author ?author]
                      [?form :seon.cluster.eval/source ?source]]
                    :args [@connection]
                    :order-by '[?ordinal :asc]})))
      (is (nil? (::turn/reply-size
                 (run-entity connection "generated-run")))))))

(deftest run-derives-its-opening-database-and-starting-namespace
  (with-model-database
    (fn [connection]
      (support/transacted!
              connection
              [{:seon.ns/name 'replay.start}
               {:seon.agent/id "replay-agent"
                :seon.agent/namespace
                [:seon.ns/name 'replay.start]}])
      (support/transacted!
                connection
                (turn/system-run-tx
                 @connection
                 {:seon.agent/id "replay-agent" ::turn/id "replay-run" :seon.db.process/id "replay-process" :seon.turn/opened-tx "datomic.tx" ::turn/starting-ns [:seon.ns/name 'replay.start] ::turn/sources [{:seon.cluster.eval/source "(def replayed 1)"}]}))
        (let [run (db/pull
                   @connection
                   '[* {:seon.turn/starting-ns [:seon.ns/name]}]
                   [::turn/id "replay-run"])]
          (support/transacted! connection
                               [{:seon.ns/name 'replay.later}
                                {::turn/id "replay-run"
                                 ::turn/agent [:seon.agent/id "replay-agent"]
                                 :seon.turn/opened-tx "datomic.tx"}])
          (let [opening (turn/opening-db @connection "replay-run")]
            (is (= "replay-run"
                   (::turn/id (db/pull opening [::turn/id]
                                     [::turn/id "replay-run"]))))
            (is (inst? (get-in (db/pull opening '[{:seon.turn/opened-tx [:db/txInstant]}]
                                         [::turn/id "replay-run"])
                               [:seon.turn/opened-tx :db/txInstant])))
            (is (= 'replay.start
                   (:seon.ns/name (db/pull opening [:seon.ns/name]
                                          [:seon.ns/name 'replay.start]))))
            (is (nil? (db/pull opening [:seon.ns/name]
                              [:seon.ns/name 'replay.later]))))
          (is (:seon.error/kind (turn/opening-db @connection "absent-run")))
          (is (= 'replay.start
                 (get-in run [::turn/starting-ns :seon.ns/name]))))
      (is (= :system
             (db/q '[:find ?author .
                     :where
                     [?run :seon.turn/id "replay-run"]
                     [?form :seon.cluster.eval/run ?run]
                     [?form :seon.cluster.eval/author ?author]]
                   @connection)))
      (is (= 'replay.start
             (db/q '[:find ?namespace-name .
                     :where
                     [?run :seon.turn/id "replay-run"]
                     [?form :seon.cluster.eval/run ?run]
                     [?form :seon.cluster.eval/ordinal 0]
                     [?form :seon.cluster.eval/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   @connection)))
      (is (= [{:seon.cluster.eval/ordinal 0
               :seon.cluster.eval/source "(def replayed 1)"
               :seon.ns/name 'replay.start}]
             (db/q '[:find ?ordinal ?source ?namespace-name
                     :keys seon.cluster.eval/ordinal
                           seon.cluster.eval/source
                           seon.ns/name
                     :where
                     [?run :seon.turn/id "replay-run"]
                     [?evaluation :seon.cluster.eval/run ?run]
                     [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                     [?evaluation :seon.cluster.eval/source ?source]
                     [?evaluation :seon.cluster.eval/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   @connection))
          "the system run records its complete pending evaluation intent"))))

(deftest system-run-refuses-a-concurrent-namespace-reassignment
  (with-model-database
    (fn [connection]
      (support/transacted!
              connection
              [{:seon.ns/name 'my.agents.before}
               {:seon.ns/name 'my.agents.after}
               {:seon.agent/id "moving-agent"
                :seon.agent/namespace
                [:seon.ns/name 'my.agents.before]}])
      (let [before @connection
            outcome
            (db/transact!
             connection
             (into
              [[:db/add [:seon.agent/id "moving-agent"]
                :seon.agent/namespace
                [:seon.ns/name 'my.agents.after]]]
              (turn/system-run-tx
               before
               {:seon.agent/id "moving-agent" ::turn/id "moving-run" :seon.db.process/id "moving-process" :seon.turn/opened-tx "datomic.tx" ::turn/starting-ns [:seon.ns/name 'my.agents.before] ::turn/sources [{:seon.cluster.eval/source "(+ 1 1)"}]})))]
        (is (= ::turn/starting-namespace-changed (::turn/rule outcome)))
        (is (= 'my.agents.before
               (db/q '[:find ?namespace-name .
                       :where
                       [?agent :seon.agent/id "moving-agent"]
                       [?agent :seon.agent/namespace ?namespace]
                       [?namespace :seon.ns/name ?namespace-name]]
                     @connection))
            "the refusing transaction also rolls back reassignment")
        (is (nil? (db/pull @connection [:db/id]
                           [::turn/id "moving-run"])))))))

(deftest settlement-keeps-unresolved-call-and-require-names-as-values
  ;; Source observations survive independently of whether a declaration row
  ;; currently exists. Resolution is a query; settlement never fabricates it.
  (support/with-database
    (fn [connection]
      (support/transacted!
              connection
              [{:seon.ns/name 'my.macro-caller}
               {:seon.agent/id "macro-caller"
                :seon.agent/namespace [:seon.ns/name 'my.macro-caller]}])
      (support/transacted!
              connection
              (turn/system-run-tx
               @connection
               {:seon.agent/id "macro-caller" ::turn/id "macro-call-run" :seon.db.process/id "macro-call-process" :seon.turn/opened-tx "datomic.tx" ::turn/starting-ns [:seon.ns/name 'my.macro-caller] ::turn/sources [{:seon.cluster.eval/source "(seon.bootstrap/help)"}
                 {:seon.cluster.eval/source "(missing.target/nope)"}
                 {:seon.cluster.eval/source
                  "(require 'unindexed.required)"}
                 {:seon.cluster.eval/source
                  "(defn unresolved-caller [] (missing.target/nope))"}]}))
      (let [macro-row
            (db/pull @connection
                     [:db/id :seon.fn/source :seon.fn/macro?]
                     [:seon.fn/sym 'seon.bootstrap/help])]
        (is (:db/id macro-row) "publication supplies the macro identity")
        (is (string? (:seon.fn/source macro-row)))
        (is (true? (:seon.fn/macro? macro-row))))
      (let [result
            (db/transact!
             connection
             (turn/receipt-settle-tx
              @connection
              {::turn/id "macro-call-run"
               :seon.cluster.eval/ordinal 0
               :seon.eval/shown "nil"}))]
        (is (not (:seon.error/kind result))
            "the settlement transaction commits"))
      (let [form (db/pull @connection
                          [:seon.fn/calls]
                          [:seon.cluster.eval/id
                           (turn/receipt-identity "macro-call-run" 0)])]
        (is (= #{'seon.bootstrap/help}
               (set (:seon.fn/calls form)))
            "the evaluation records the observed call as a symbol value"))
      (let [result
            (db/transact!
             connection
             (turn/receipt-settle-tx
              @connection
              {::turn/id "macro-call-run"
               :seon.cluster.eval/ordinal 1
               :seon.cluster.eval/error "Could not resolve missing.target/nope"}))]
        (is (not (:seon.error/kind result))
            "the error settlement commits without a dangling lookup ref"))
      (is (= #{['missing.target/nope]}
             (db/q '[:find ?target
                     :in $ ?form-id
                     :where
                     [?form :seon.cluster.eval/id ?form-id]
                     [?form :seon.fn/calls ?target]]
                   @connection
                   (turn/receipt-identity "macro-call-run" 1)))
          "an unresolved mention remains an honest symbol edge")
      (is (nil? (:db/id (db/pull @connection [:db/id]
                                 [:seon.fn/sym 'missing.target/nope])))
          "the observed call does not mint its target")
      (is (nil? (:db/id (db/pull @connection [:db/id]
                                 [:seon.ns/name 'unindexed.required]))))
      (let [result
            (db/transact!
             connection
             (turn/receipt-settle-tx
              @connection
              {::turn/id "macro-call-run"
               :seon.cluster.eval/ordinal 2
               :seon.eval/shown "nil"
               :seon.program/row
               {:seon.ns/name 'my.macro-caller
                :seon.ns/source "(require 'unindexed.required)"
                :seon.ns/requires
                #{'unindexed.required}}}))]
        (is (not (:seon.error/kind result))
            "an unresolved required namespace stores as a symbol value"))
      (is (nil? (:db/id (db/pull @connection [:db/id]
                                 [:seon.ns/name 'unindexed.required])))
          "settlement does not mint the required namespace identity")
      (is (= #{'unindexed.required}
             (set (:seon.ns/requires
                   (db/pull @connection [:seon.ns/requires]
                            [:seon.ns/name 'my.macro-caller])))))
      (let [source (str "(defn ^{:malli/schema [:=> [:cat] :int]} "
                        "unresolved-caller [] (missing.target/nope))")
            preliminary-row
            {:seon.fn/sym 'my.macro-caller/unresolved-caller
             :seon.fn/ns [:seon.ns/name 'my.macro-caller]
             :seon.fn/source source
             :seon.fn/arglists "([])"
             :seon.fn/private? false
             :seon.fn/spec "[:=> [:cat] :int]"
             :seon.schema.admission/source :agent}
            row (second
                 (seon.fn/analyze-form
                  @connection source (:seon.fn/ns preliminary-row)
                  preliminary-row))
            result (db/transact!
                    connection
                    (turn/receipt-settle-tx
                     {::turn/id "macro-call-run"
                      :seon.cluster.eval/ordinal 3
                      :seon.eval/shown "nil"
                      :seon.program/row row}))
            report (seon.fn/unresolved-callers @connection)]
        (is (not (:seon.error/kind result)) (pr-str result))
        (is (some #{{:seon.program/identity
                     [:seon.fn/sym
                      'my.macro-caller/unresolved-caller]
                     :seon.fn/callee 'missing.target/nope}}
                  (:seon.program/unresolved-callers report)))))))

(deftest receipt-transitions-preserve-one-terminal-outcome
  (let [start-tx (ns-resolve 'seon.turn 'receipt-start-tx)
        settle-tx (ns-resolve 'seon.turn 'receipt-settle-tx)]
    (is (some? start-tx) "the run owner provides the receipt-start transition")
    (is (some? settle-tx) "the run owner provides the receipt-settle transition")
    (when (and start-tx settle-tx)
      (with-model-database
        (fn [connection]
          (support/transacted! connection [{:seon.agent/id "receipt-agent"}])
          (support/transacted! connection
                             (turn/open-tx {::turn/id "receipts" ::turn/agent [:seon.agent/id "receipt-agent"] :seon.turn/opened-tx "datomic.tx"}))

          (let [start {::turn/id "receipts"
                       :seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/at t0}
                settle {::turn/id "receipts"
                        :seon.cluster.eval/ordinal 0
                        :seon.eval/shown "42"
                        :seon.cluster.eval/triage-edn
                        "{:clojure.error/cause \"triage evidence\"}"}]
            (is (= ::committed
                   (transact-or-refusal connection (start-tx start))))
            (is (not= ::committed
                      (transact-or-refusal connection (start-tx start)))
                "duplicate start is refused")
            (is (not= ::committed
                      (transact-or-refusal
                       connection
                       (settle-tx (dissoc settle
                                          :seon.eval/shown))))
                "a settle carrying no terminal fact is refused")
            ;; MISSING IS A TERMINAL FACT LIKE THE REST. An evaluation whose
            ;; value went over the storage bound RAN; reading its absent
            ;; result-edn as "still running" would re-attempt it.
            (is (not (turn/terminal? {}))
                "absence of terminal evidence remains unfinished")
            (is (= ::committed
                   (transact-or-refusal connection (settle-tx settle))))
            (doseq [terminal [settle
                              (assoc settle
                                     :seon.eval/shown
                                     "{:seon.error/kind :x}"
                                     :seon.cluster.eval/error "changed")]]
              (is (not= ::committed
                        (transact-or-refusal connection
                                             (settle-tx terminal)))
                  "a terminal receipt cannot settle again"))
            (let [receipt (db/pull @connection
                                  '[*]
                                  [:seon.cluster.eval/id
                                   (turn/receipt-identity "receipts" 0)])]
              (is (= "42" (:seon.eval/shown receipt))
                  "the first terminal outcome is preserved")
              (is (string? (:seon.eval/shown receipt))
                  "the stored observation is shown text")
              (is (= "{:clojure.error/cause \"triage evidence\"}"
                     (:seon.cluster.eval/triage-edn receipt)))
              (is (nil? (:seon.cluster.eval/error receipt))
                  "and the refused re-settle changed nothing")))
          ;; the takeover interleaving, re-expressed from the epoch era:
          ;; a running receipt under a DEAD holder is stamped by the
          ;; takeover itself, so the dead pass's late settle refuses by
          ;; PRESENCE — the fence the epoch used to claim to be.
          (let [start {::turn/id "receipts"
                       :seon.cluster.eval/ordinal 1
                       :seon.cluster.eval/at t0}]
            (is (= ::committed
                   (transact-or-refusal connection (start-tx start))))
            (is (= ::committed
                   (transact-or-refusal
                    connection
                    (turn/recover-tx {::turn/id "receipts" ::turn/now t1})))
                "a run held by a dead process is taken over")

            (is (= t1 (:seon.cluster.eval/interrupted-at
                       (db/pull @connection '[*]
                               [:seon.cluster.eval/id
                                (turn/receipt-identity "receipts" 1)])))
                "the takeover stamped the dead custody's running receipt
                 in the SAME transaction — the intermediate state never
                 exists")
            (is (not= ::committed
                      (transact-or-refusal
                       connection
                       (settle-tx {::turn/id "receipts"
                                   :seon.cluster.eval/ordinal 1
                                   :seon.eval/shown "1"})))
                "the dead pass's late settle refuses — by presence")
            (is (= ::turn/run-closed
                   (::turn/rule (transact-or-refusal
                                connection
                                (start-tx start))))
                "and `(run, ordinal)` identity makes re-execution
                 unrepresentable: an ordinal that ever had a receipt
                 refuses forever, across any custody change")))))))

(deftest settlement-refuses-a-divergent-program-change-since-opening
  (with-model-database
    (fn [connection]
      (let [namespace-name 'my.defs.shared
            agent-a "def-agent-a"
            agent-b "def-agent-b"
            run-a "defs-run-a"
            run-b "defs-run-b"
            qualified-id 'my.defs.shared/scratch
            agent-ref (fn [agent-id]
                        [:seon.agent/id agent-id])
            function-row
            (memoize
             (fn [result]
               (let [source
                     (str "(defn ^{:malli/schema [:=> [:cat] :int]} "
                          "scratch [] " result ")")
                     preliminary-row
                     {:seon.fn/sym qualified-id
                      :seon.fn/ns [:seon.ns/name namespace-name]
                      :seon.fn/source source
                      :seon.fn/arglists "([])"
                      :seon.fn/private? false
                      :seon.fn/spec "[:=> [:cat] :int]"
                      :seon.schema.admission/source :agent}]
                 (second
                  (seon.fn/analyze-form
                   @connection source (:seon.fn/ns preliminary-row)
                   preliminary-row)))))
            start!
            (fn [run-id ordinal]
              (db/transact!
               connection
               (turn/receipt-start-tx
                {::turn/id run-id
                 :seon.cluster.eval/ordinal ordinal
                 :seon.cluster.eval/at (at ordinal)})))
            settle!
            (fn [run-id ordinal request]
              (transact-or-refusal
               connection
               (turn/receipt-settle-tx
                (merge {::turn/id run-id
                        :seon.cluster.eval/ordinal ordinal
                        :seon.eval/shown "nil"}
                       request))))]
        (support/transacted!
                connection
                [{:seon.ns/name namespace-name
                  :seon.schema.admission/source :agent}
                 {:seon.agent/id agent-a}
                 {:seon.agent/id agent-b}])
        (doseq [[run-id agent-id] [[run-a agent-a] [run-b agent-b]]]
          (support/transacted!
                  connection
                  (turn/open-tx {::turn/id run-id ::turn/agent (agent-ref agent-id) :seon.turn/opened-tx "datomic.tx"})))

        (start! run-a 0)
        (is (= ::committed
               (settle! run-a 0 {:seon.program/row (function-row 1)})))

        (testing "a divergent definition from an older opening basis refuses"
          (start! run-b 1)
          (is (= ::turn/program-row-changed-after-open
                 (::turn/rule
                  (settle! run-b 1
                           {:seon.program/row (function-row 2)}))))
          (is (= (:seon.fn/source (function-row 1))
                 (:seon.fn/source
                  (db/pull @connection '[*]
                           [:seon.fn/sym qualified-id]))))
          (is (= ::committed
                 (settle! run-b 1
                          {:seon.program/row (function-row 1)}))
              "an identical declaration is an assertion-free success"))))))

;;; ---------------------------------------------------------------------------
;;; The state machine — generated command sequences against a pure model
;;; ---------------------------------------------------------------------------

(def ^:private agent-ids ["a1" "a2"])

(def ^:private run-ids ["r1" "r2" "r3"])

(def ^:private command-gen
  "One generated command. Times are monotonic per sequence position:
  the runner assigns now = (at (* index 60000))."
  (gen/one-of
   [(gen/tuple (gen/return :open)
               (gen/elements run-ids)
               (gen/elements agent-ids))

    (gen/tuple (gen/return :close)
               (gen/elements run-ids)
)
    (gen/tuple (gen/return :plan)
               (gen/elements run-ids)

               (gen/elements ["digest-a" "digest-b"]))
    (gen/tuple (gen/return :receipt-start)
               (gen/elements run-ids)
               (gen/choose 0 3))
    ;; the settled kind names WHICH terminal fact the settle asserts:
    ;; :done → result-edn, :error → error, :interrupted → interrupted-at
    (gen/tuple (gen/return :receipt-settle)
               (gen/elements run-ids)
               (gen/choose 0 3)
               (gen/elements [:done :error :interrupted]))
    (gen/return [:recover])]))

(def ^:private commands-gen
  (gen/one-of [(gen/vector command-gen 1 15)
               (gen/return [[:open "r1" "a1"]
                            [:receipt-start "r1" 0]
                            [:recover]
                            [:receipt-settle "r1" 0 :done]])]))

(defn- model-eligible?
  "The pure oracle: must this command COMMIT against `model`?"
  [model [op & args]]
  (let [run-of #(get-in model [:runs %])]
    (case op
      :open (let [[run-id agent-id] args]
              (or (some? (get-in model [:pointers agent-id]))
                  (nil? (run-of run-id))
                  (= agent-id (:agent (run-of run-id)))))

      :close
      (let [[run-id] args
            {:keys [closed] :as entry} (run-of run-id)]
        (and (some? entry)
             (not closed)))
      :plan (let [[run-id _digest] args
                  {:keys [closed digest] :as entry} (run-of run-id)
                  ordinal (count (filter #(= run-id (:run %))
                                         (vals (:receipts model))))]
              (and (some? entry)
                   (not closed)
                   (nil? (get-in model [:receipts [run-id ordinal]]))
                   (nil? digest)))
      :receipt-start
      (let [[run-id ordinal] args
            entry (run-of run-id)]
        (and (some? entry)
             (not (:closed entry))
             (nil? (get-in model [:receipts [run-id ordinal]]))))
      :receipt-settle
      (let [[run-id ordinal _kind] args
            entry (run-of run-id)
            receipt (get-in model [:receipts [run-id ordinal]])]
        (and (some? entry)
             (not (:closed entry))
             ;; running IS the absence of a settled fact
             (some? receipt)
             (nil? (:settled receipt))))
      :recover true)))

(defn- stamp-running-receipts
  "Settle every running receipt of `run-id` as :interrupted."
  [model run-id]
  (update model :receipts
          #(into {}
                 (map (fn [[[receipt-run ordinal :as k] receipt]]
                        [k (if (and (= receipt-run run-id)
                                    (nil? (:settled receipt)))
                             (assoc receipt :settled :interrupted)
                             receipt)]))
                 %)))

(defn- model-apply
  "Advance the pure model by one COMMITTED command."
  [model [op & args]]
  (case op
    :open (let [[run-id agent-id] args]
            (if (or (get-in model [:pointers agent-id])
                    (get-in model [:runs run-id]))
              model
              (-> model
                  (assoc-in [:runs run-id] {:agent agent-id})
                  (assoc-in [:pointers agent-id] run-id))))

    :close (let [[run-id] args
                 agent-id (get-in model [:runs run-id :agent])]
             (-> model
                 (update-in [:runs run-id]
                            #(-> % (assoc :closed true)
                                 (dissoc :process)))
                 (update :pointers dissoc agent-id)))
    :plan (let [[run-id digest] args
                ordinal (count (filter #(= run-id (:run %))
                                       (vals (:receipts model))))]
            (-> model
                (assoc-in [:runs run-id :digest] digest)
                (assoc-in [:receipts [run-id ordinal]]
                          {:id (turn/receipt-identity run-id ordinal)
                           :run run-id :ordinal ordinal})))
    :receipt-start (let [[run-id ordinal] args]
                     ;; no :settled key: a started receipt is running
                     ;; by the absence of any terminal fact
                     (assoc-in model [:receipts [run-id ordinal]]
                               {:id (turn/receipt-identity run-id ordinal)
                                :run run-id
                                :ordinal ordinal}))
    :receipt-settle (let [[run-id ordinal kind] args]
                      (assoc-in model
                                [:receipts [run-id ordinal] :settled]
                                kind))
    :recover (reduce
                (fn [acc [run-id entry]]
                    (cond
                      (:closed entry) acc
                      :else
                      (let [agent-id (:agent entry)]
                        (-> (stamp-running-receipts acc run-id)
                            (update-in [:runs run-id]
                                       #(-> %
                                            (assoc :closed true)
                                            ;; recovery marks what it cut
                                            (dissoc :process)))
                            (update :pointers dissoc agent-id)))))
                model
                (:runs model))))

(defn- execute!
  "Run one command against the real database. Returns ::committed or
  refusal data."
  [connection [op & args] now]
  (case op
    :open (let [[run-id agent-id] args]
            (transact-or-refusal
             connection
             (turn/open-tx {::turn/id run-id ::turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"})))

    :close (let [[run-id] args]
             (transact-or-refusal
              connection
              (turn/close-tx
               {::turn/id run-id :seon.turn/closed-tx "datomic.tx"})))
    :plan (let [[run-id digest] args]
            (transact-or-refusal
             connection
             (turn/plan-tx
              {::turn/id run-id ::turn/starting-ns [:seon.ns/name 'user] ::turn/sources [{:seon.cluster.eval/source "(+ 1 1)"}]})))
    :receipt-start (let [[run-id ordinal] args]
                     (transact-or-refusal
                      connection
                      (turn/receipt-start-tx
                       {::turn/id run-id
                        :seon.cluster.eval/ordinal ordinal
                        :seon.cluster.eval/at now})))
    :receipt-settle (let [[run-id ordinal kind] args]
                      (transact-or-refusal
                       connection
                       (turn/receipt-settle-tx
                        (merge {::turn/id run-id
                                :seon.cluster.eval/ordinal ordinal}
                               ;; the settle IS the terminal fact
                               (case kind
                                 :done {:seon.eval/shown "42"}
                                 :error {:seon.cluster.eval/error "boom"}
                                 :interrupted
                                 {:seon.cluster.eval/interrupted-at now})))))
    :recover (let [[live] args]
               (transact-or-refusal
                connection
                (into []
                      (mapcat
                       (fn [run-id]
                         ;; recover-call reads the run and its receipts
                         ;; AT TRANSACTION TIME; a missing or closed
                         ;; run contributes nothing
                         (turn/recover-tx
                          {::turn/id run-id

                           ::turn/now now})))
                      run-ids)))))

(defn- invariants-hold?
  "Durable-fact invariants checked after EVERY command."
  [connection model]
  (let [database-receipts
        ;; the settled kind is DERIVED from which terminal fact is
        ;; present — the same derivation every reader now performs
        (into #{}
              (map (fn [eid]
                     (let [receipt (db/pull @connection '[*] eid)
                           run-id (:seon.turn/id
                                   (db/pull @connection
                                           [:seon.turn/id]
                                           (:db/id
                                            (:seon.cluster.eval/run
                                             receipt))))]
                       (cond-> {:id (:seon.cluster.eval/id receipt)
                                :run run-id
                                :ordinal (:seon.cluster.eval/ordinal receipt)}
                         (:seon.eval/shown receipt)
                         (assoc :settled :done)
                         (:seon.cluster.eval/error receipt)
                         (assoc :settled :error)
                         (:seon.cluster.eval/interrupted-at receipt)
                         (assoc :settled :interrupted)))))
              (db/q '[:find [?receipt ...]
                     :where [?receipt :seon.cluster.eval/id _]]
                   @connection))]
    (and
     (every?
      identity
      (for [run-id run-ids
            :let [entry (get-in model [:runs run-id])]
            :when (some? entry)
            :let [entity (run-entity connection run-id)]]
        (and
         ;; the database agrees with the model on custody and closure
         (= (boolean (:closed entry))
            (some? (:seon.turn/closed-tx entity)))
         ;; the agent pointer exists exactly while its run is open
         (let [agent-id (:agent entry)
               pointer (open-run-id connection agent-id)]
           (if (:closed entry)
             (not= run-id pointer)
             (= run-id pointer)))
         ;; plan digest is write-once
         (= (some? (:digest entry)) (some? (::turn/reply-size entity))))))
     (= (set (vals (:receipts model))) database-receipts))))

(deftest transitions-agree-with-the-model
  ;; One FRESH database per trial (and per shrink step): the pure model
  ;; resets every trial, so the world it reasons about must too.
  (let [check
        (tc/quick-check
         60
         (prop/for-all [commands commands-gen]
           (with-model-database
             (fn [connection]
               (support/transacted! connection
                                  (mapv (fn [id] {:seon.agent/id id})
                                        agent-ids))
               (loop [commands commands
                      model {:runs {} :pointers {} :receipts {}}
                      index 0]
                 (if (empty? commands)
                   true
                   (let [command (first commands)
                         now (at (* (inc index) 60000))
                         expected (model-eligible? model command)
                         result (execute! connection command now)
                         committed? (= ::committed result)]
                     (cond
                       (not= expected committed?)
                       (do (println "ORACLE DISAGREEMENT"
                                    {:command command :now now
                                     :expected (if expected
                                                 :commit :refuse)
                                     :actual result
                                     :model model})
                           false)

                       :else
                       (let [model' (if committed?
                                      (model-apply model command)
                                      model)]
                         (if (invariants-hold? connection model')
                           (recur (rest commands) model' (inc index))
                           (do (println "INVARIANT VIOLATION"
                                        {:command command :model model'})
                               false))))))))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "state-machine property failed: " (pr-str check)))))

;;; ---------------------------------------------------------------------------
;;; Recovery preserves every terminal receipt byte-for-byte
;;; ---------------------------------------------------------------------------

(def ^:private receipt-state-gen
  ;; which terminal fact (if any) each seeded receipt carries: running
  ;; is the ABSENCE of all three — never a stored label
  (gen/elements [:running :done :error]))

(deftest recovery-preserves-terminal-receipts-exactly
  ;; One FRESH database per trial (the shared-database class, already
  ;; fixed once in the state machine, does not get a second life), and
  ;; ids derive from generated values only — replayable under the seed.
  (let [pull-receipts
        (fn [connection run-id]
          (->> (db/q '[:find [?r ...]
                      :in $ ?run-id
                      :where
                      [?run :seon.turn/id ?run-id]
                      [?r :seon.cluster.eval/run ?run]]
                    (db/db connection) run-id)
               (mapv #(db/pull (db/db connection) '[*] %))))
        pull-terminals
        (fn [connection run-id]
          (->> (pull-receipts connection run-id)
               (filterv #(or (:seon.eval/shown %)
                             (:seon.cluster.eval/error %)))
               (sort-by :seon.cluster.eval/ordinal)
               vec))
        check
        (tc/quick-check
         30
         (prop/for-all [states (gen/vector receipt-state-gen 1 5)
                        generated? gen/boolean
                        round gen/nat]
           (with-model-database
             (fn [connection]
               (let [run-id (str "keep-" round "-" (count states)
)
                     agent-id (str "keeper-" run-id)
]
                 (support/transacted! connection
                                    [{:seon.agent/id agent-id}])
                 (support/transacted! connection
                                    (turn/open-tx
                                     {::turn/id run-id ::turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}))

                 (support/transacted!
                         connection
                         (vec (map-indexed
                               (fn [ordinal state]
                                 (cond-> {:seon.cluster.eval/id
                                          (turn/receipt-identity run-id ordinal)
                                          :seon.cluster.eval/run
                                          [:seon.turn/id run-id]
                                          :seon.cluster.eval/ordinal ordinal
                                          :seon.cluster.eval/at t1}
                                   (= :done state)
                                   (assoc :seon.eval/shown
                                          (str ordinal))
                                   (= :error state)
                                   (assoc :seon.cluster.eval/error
                                          (str "boom-" ordinal))))
                               states)))
                 (when generated?
                   ;; One attribute on a turn that already exists is a datom,
                   ;; not a partial entity map: an identity-keyed map is read
                   ;; against the whole turn schema and refused for the keys
                   ;; the open transaction already wrote.
                   (support/transacted!
                    connection
                    [[:db/add [::turn/id run-id]
                      :seon.turn.work/situation :generate]]))
                 (let [terminals-before (pull-terminals connection run-id)
                       recovery
                       (turn/recover-tx
                        {::turn/id run-id

                         ::turn/now t2})
                       _ (support/transacted! connection recovery)
                       ;; recovery is IDEMPOTENT: running it again from
                       ;; current facts commits nothing new
                       _ (support/transacted! connection recovery)
                       entity (run-entity connection run-id)]
                   (and
                    (= (count states) (count (pull-receipts connection run-id)))
                    ;; settled receipts are IDENTICAL, whole entities
                    (= terminals-before (pull-terminals connection run-id))
                    (every? turn/terminal?
                            (pull-receipts connection run-id))
                    (inst? (:db/txInstant (db/pull @connection [:db/txInstant] (get-in entity [:seon.turn/closed-tx :db/id]))))
                    (empty? (turn/recover-call @connection
                                             {::turn/id run-id ::turn/now t2}))
                    (nil? (open-run-id connection agent-id))))))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "recovery property failed: " (pr-str check)))))

(deftest recovery-cannot-stamp-a-settled-receipt
  ;; the zombie audit's order-B soft spot, made unrepresentable
  ;; (custody revision, Revision 4): `recover-call` reads the receipt
  ;; AT TRANSACTION TIME, so there is no stale caller basis from which
  ;; a settled receipt could be stamped `interrupted-at`.
  (with-model-database
    (fn [connection]
      (support/transacted! connection [{:seon.agent/id "orderer"}])
      (support/transacted! connection
                         (turn/open-tx {::turn/id "order-b" ::turn/agent [:seon.agent/id "orderer"] :seon.turn/opened-tx "datomic.tx"}))

      (support/transacted! connection
                         (turn/receipt-start-tx {::turn/id "order-b"
                                                :seon.cluster.eval/ordinal 0
                                                :seon.cluster.eval/at t0}))
      ;; the settle lands FIRST; recovery then runs against whatever
      ;; the transaction sees — which includes that settle
      (support/transacted! connection
                         (turn/receipt-settle-tx {::turn/id "order-b"
                                                 :seon.cluster.eval/ordinal 0
                                                 :seon.eval/shown "2"}))
      (support/transacted! connection
                         (turn/recover-tx {::turn/id "order-b"

                                          ::turn/now t2}))
      (let [receipt (db/pull @connection '[*]
                            [:seon.cluster.eval/id (turn/receipt-identity "order-b" 0)])]
        (is (= "2" (:seon.eval/shown receipt)))
        (is (nil? (:seon.cluster.eval/interrupted-at receipt))
            "the settled receipt is byte-untouched — no contradiction
             fact can exist"))

      (is (some? (:seon.turn/closed-tx (run-entity connection "order-b")))
          "the interrupted run is ended")
      (is (nil? (open-run-id connection "orderer"))
          "and the agent pointer is retracted"))))

(deftest recovery-closes-a-turn-with-no-evaluations
  (with-model-database
    (fn [connection]
      (support/transacted! connection [{:seon.agent/id "cut"}])
      (support/transacted! connection
                           (turn/open-tx {::turn/id "cut-run" ::turn/agent [:seon.agent/id "cut"] :seon.turn/opened-tx "datomic.tx"}))
      (is (= ::committed
             (transact-or-refusal connection
                                  (turn/recover-tx {::turn/id "cut-run"
                                                   ::turn/now t2}))))
      (let [cut (run-entity connection "cut-run")]
        (is (= "cut-run" (::turn/id cut)))
        (is (inst? (:db/txInstant (db/pull @connection [:db/txInstant] (get-in cut [:seon.turn/closed-tx :db/id])))))
        (is (nil? (::turn/reply cut)))
        (is (empty? (db/q '[:find [?e ...]
                            :where [?e :seon.cluster.eval/id]] @connection)))
        (is (str/includes? (turn/render-ai (assoc cut :seon.db/db @connection))
                           "interrupted before the reply arrived"))))))

;;; ---------------------------------------------------------------------------
;;; Schema admissibility — the model refuses what it must
;;; ---------------------------------------------------------------------------

(deftest run-schema-admits-and-refuses
  (is (seon.schema/valid-candidate-value? (seon.schema/handed-projection)
       :seon.turn/turn
       {::turn/id "r1" ::turn/agent [:seon.agent/id "runner"] :seon.turn/opened-tx "datomic.tx"}))
  (is (not (seon.schema/valid-candidate-value? (seon.schema/handed-projection)
            :seon.turn/turn
            {::turn/agent [:seon.agent/id "runner"] :seon.turn/opened-tx "datomic.tx"}))
      "identity is required")
  (is (not (seon.schema/valid-candidate-value? (seon.schema/handed-projection)
            :seon.turn/turn
            {::turn/id "" ::turn/agent [:seon.agent/id "runner"] :seon.turn/opened-tx "datomic.tx"}))
      "a blank identity is refused"))

(deftest open-turn-is-derived-without-an-agent-pointer
  (with-model-database
    (fn [connection]
      (support/transacted! connection [{:seon.agent/id "derived"}])
      (support/transacted! connection
                           (turn/open-tx {::turn/id "derived-turn" ::turn/agent [:seon.agent/id "derived"] :seon.turn/opened-tx "datomic.tx"}))
      (is (= "derived-turn" (open-run-id connection "derived")))
      (doseq [attribute [:seon.agent/run :seon.agent/cluster
                         :seon.agent/instructions]]
        (is (nil? (seon.schema/schema-definition attribute))))
      (is (= #{:db/id :seon.agent/id :seon.agent/runtime}
             (set (keys (db/pull @connection '[*]
                                 [:seon.agent/id "derived"])))))
      (support/transacted! connection
                           [[:db/add [:seon.turn/id "derived-turn"]
                             :seon.turn/closed-tx "datomic.tx"]])
      (is (nil? (open-run-id connection "derived"))))))

;; THE FAULTS UNIT lives on `:seon.error/agent`, whose renderer needs a run to
;; link to; that is why its regression sits beside the run model rather than in
;; the pure `seon.error-test`, which opens no database by design.
(deftest fault-unit-lists-agent-faults-newest-first-with-run-links
  (support/with-database
    (fn [connection]
      (checked-transact! connection
                         [{:seon.agent/id "juno"}
                          {:seon.turn/id "run-1" :seon.turn/agent [:seon.agent/id "juno"]
                           :seon.turn/opened-tx "datomic.tx"}])
      (doseq [[kind message millis turn-id] [[:seon.instrument/contract-violated "older fault" 1700000000000 "run-1"]
                                            [:seon.db/rejected "newer fault" 1700000060000 nil]]]
        (checked-transact!
         connection
         (error/commit-tx
          (db/db connection)
          (cond-> {:seon.error/source {:seon.error/kind kind :seon.error/message message}
                   :seon.error/id message :seon.error/at (java.util.Date. millis)
                   :seon.error/process cluster/boot-process-identity
                   :seon.sci.admit/caps (config/result-caps (support/effective-config))
                   :seon.config.error/max-evidence-bytes 16384
                   :seon.config.error/recurrence-limit 100 :seon.agent/id "juno"}
            turn-id (assoc :seon.turn/id turn-id)))))
      (let [database (db/db connection)
            faults (db/q '[:find [(pull ?e [* {:seon.error/occurrences [* {:seon.error.occurrence/turn [:seon.turn/id]}]}]) ...]
                           :where [?e :seon.error/occurrences ?o]
                                  [?o :seon.error.occurrence/agent ?a]
                                  [?a :seon.agent/id "juno"]] database)
            rendered (error/render-faults-html faults database)]
        (is (= [:h2 "Faults (2)"] (nth rendered 2)))
        (is (= ["newer fault" "older fault"]
               (mapv #(some #{"newer fault" "older fault"} (tree-seq coll? seq %)) (subvec rendered 3))))
        (is (str/includes? (hiccup/->string (nth rendered 4)) "run-1"))
        (is (not (str/includes? (hiccup/->string (nth rendered 3)) "seon-error-run")))
        (is (str/includes? (pr-str (error/render-faults-html [] database)) "Faults (0)"))))))

;;; ---------------------------------------------------------------------------
;;; The declared write-refusal bound
;;; ---------------------------------------------------------------------------

(deftest a-refused-turn-write-is-bounded-and-commits-exactly-one-fault
  ;; THE STORM, AS A REGRESSION. On 2026-09-17 every turn write on `default`
  ;; was refused and the turn proc re-fired its own wake each time; each
  ;; refused attempt still flushed dirty index leaves, so the store grew ~1 GB
  ;; a minute with nothing committed. The proof is transaction COUNT from the
  ;; database — not a log line: past the declared bound the agent is parked,
  ;; further wakes transact nothing at all, and the bound firing is ONE
  ;; committed fault naming the refusal, the agent, the count and the bound.
  (support/with-database
    (fn [connection]
      (let [cluster-name "bounded-write-refusals-cluster"
            agent-id "bounded-write-refusals"
            bound 2
            fault-channel (async/chan 8)
            completion (async/chan 1)]
        (checked-transact! connection [{:seon.agent/id agent-id}])
        (async/>!! completion :seon.agent/ready)
        ;; The dial rides the handle exactly as the turn completion allowance
        ;; does: a fixture pins its own bound, and the caller wins.
        (let [cluster (assoc (support/cluster-handle
                              {:seon.db/connection connection
                               :seon.cluster/name cluster-name
                               :seon.sci.eval/ctx
                               (support/fork-cluster-ctx connection)
                               :seon.db.process/id
                               cluster/boot-process-identity})
                             :seon.config.agent/write-refusal-bound bound
                             :seon.config.agent/turn-completion-backstop-ms
                             (* 1000 support/event-backstop-seconds)
                             ;; The turn bound is armed on a carried executor;
                             ;; a handle without one is a refusal, not a
                             ;; default (AGENTS §5.3).
                             :seon.flow/executor
                             (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)
                             :seon.turn.loop/completion completion
                             :seon.agent/fault-channel fault-channel
                             :seon.agent/turn-backstop-state (atom nil))
              wake-channel (:seon.cluster.wake/channel cluster)
              ;; A REAL refused durable write: closing a turn that is not a
              ;; fact. The refusal comes back through `seon.db/transact!`
              ;; exactly as the projection failure did, so the bound is armed
              ;; against the shape it must actually catch.
              pass!
              (fn [state]
                (with-redefs [turn/next-agent-work
                              (fn [& _]
                                {:seon.turn.work/situation :close
                                 :seon.agent/id agent-id
                                 :seon.turn/id "no-such-turn"})
                              turn/more-agent-work? (fn [& _] true)]
                  (first (turn/step state :seon.agent/episode ::wake))))
              initial {:seon.agent/id agent-id
                       :seon.turn.loop/cluster cluster}
              ;; Every pass BELOW the bound re-fires, which is the ordinary
              ;; behaviour being bounded — not the defect. Drain what those
              ;; passes offered so the assertion below is about the PARKED
              ;; pass alone; a sliding-1 mailbox otherwise still holds the
              ;; previous pass's wake and reads as a re-fire that never
              ;; happened.
              before-park (reduce (fn [state _] (pass! state))
                                  initial
                                  (range (dec bound)))
              _ (is (some? (async/poll! wake-channel))
                    "a pass below the bound still re-fires")
              _ (is (nil? (async/poll! wake-channel))
                    "the mailbox is drained before the parking pass")
              parked (pass! before-park)]
          (is (some? (:seon.turn.loop/parked parked))
              "the agent is parked once the declared bound is reached")
          (is (= :seon.turn.loop/write-refusals-exhausted
                 (:seon.error/kind (:seon.turn.loop/parked parked))))
          (is (= bound (:seon.turn.loop/write-refusals
                        (:seon.turn.loop/parked parked))))
          (is (= bound (:seon.config.agent/write-refusal-bound
                        (:seon.turn.loop/parked parked))))
          (is (nil? (async/poll! wake-channel))
              "a parked proc offers no self-rewake")
          (let [fault (support/await-event! fault-channel ::write-refusal-fault)]
            (is (= :seon.turn.loop/write-refusals-exhausted
                   (:seon.error/kind (ex-data (:clojure.core.async.flow/ex fault)))))
            (is (= agent-id (:seon.agent/id fault)))
            (is (str/includes? (ex-message (:clojure.core.async.flow/ex fault))
                               (str bound)))
            (is (nil? (async/poll! fault-channel))
                "the bound fires exactly once, never once per attempt"))
          ;; THE MEASUREMENT THAT MATTERS: the store stops growing.
          (let [before (:max-tx (db/db connection))
                after-state (reduce (fn [state _] (pass! state))
                                    parked
                                    (range 5))]
            (is (= before (:max-tx (db/db connection)))
                "a parked agent transacts nothing on any later wake")
            (is (some? (:seon.turn.loop/parked after-state))
                "the park survives every later wake")
            (is (nil? (async/poll! fault-channel))
                "a parked agent commits no further faults")))))))

(deftest settlement-claims-covered-messages-without-removing-their-wakes
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "message-claims")
     (support/transacted! connection
                          (agent/creation-tx {:seon.agent/id "message-claims"
                                              :seon.ns/name 'my.agents.message-claims
                                              :seon.cluster/name "message-claims"}))
     (let [agent-ref [:seon.agent/id "message-claims"]
           opening {:seon.turn/id "claim-first" :seon.turn/agent agent-ref
                    :seon.turn/opened-tx "datomic.tx"}
           arrival (fn [id] {:seon.message/id id :seon.message/to agent-ref
                             :seon.message/content id})]
       (support/transacted! connection [(arrival "first") (arrival "second")])
       (support/transacted! connection (turn/open-tx opening))
       (support/transacted! connection [(arrival "later")])
       (doseq [request [opening (assoc opening :seon.turn/id "duplicate-open")]]
         (let [report (support/transacted! connection (turn/open-tx request))]
           (is (not-any? #(= :seon.turn/id (:a %)) (:tx-data report))
               "the writer admits an ordinary no-op for both stale opening shapes")))
       (is (= "claim-first" (turn/open-for-agent (db/db connection) agent-ref)))
       (is (= 3 (count (turn/unanswered-wakes (db/db connection) "message-claims" {})))
           "opening alone answers nothing")
       (support/transacted! connection [[:db/add [:seon.turn/id "claim-first"] :seon.turn/reply-size 1]])
       (let [report (support/transacted! connection (turn/close-tx {:seon.turn/id "claim-first"}))
             database (:db-after report)]
         (is (not-any? #(= :seon.message/to (:a %)) (:tx-data report)))
         (is (= #{"first" "second"}
                (set (db/q '[:find [?id ...] :where
                             [?turn :seon.turn/id "claim-first"]
                             [?turn :seon.turn/handled ?message]
                             [?message :seon.message/id ?id]] database))))
         (is (= [#:seon.message{:id "later"}]
                (turn/unanswered-triggers database "message-claims")))
         (is (= 3 (count (turn/unanswered-wakes database "message-claims"
                                               {:seon.turn.work/answered? :any}))))
         (is (= 3 (count (db/datoms (db/history database) :aevt :seon.message/to))))
         (is (every? :added (db/datoms (db/history database) :aevt :seon.message/to))))
       (support/transacted! connection (turn/open-tx (assoc opening :seon.turn/id "claim-next")))
       (support/transacted! connection [[:db/add [:seon.turn/id "claim-next"] :seon.turn/reply-size 1]])
       (support/transacted! connection (turn/close-tx {:seon.turn/id "claim-next"}))
       (let [database (db/db connection)]
         (is (empty? (turn/unanswered-wakes database "message-claims" {})))
         (is (= 3 (count (db/datoms database :aevt :seon.turn/handled))))
         (is (= #{"later"}
                (set (db/q '[:find [?id ...] :where
                             [?turn :seon.turn/id "claim-next"]
                             [?turn :seon.turn/handled ?message]
                             [?message :seon.message/id ?id]] database)))))))))
