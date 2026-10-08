(ns seon.turn-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [datahike.versioning :as versioning]
            [seon.mcp :as mcp]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.id :as id]
            [seon.render.hiccup :as hiccup]
            [seon.repl :as repl]
            [seon.render :as render]
            [seon.batch]
            [seon.sci.eval :as sci.eval]
            [seon.sci.observe :as observe]
            [seon.test-support :as support]
            [seon.turn :as turn]
            [seon.turn.batch :as turn.batch]
            [seon.turn.world :as world]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.error :as error]
            [seon.env :as env]
            [seon.fn :as seon.fn]
            [seon.schema]
            [seon.program :as program]))

(defn- namespace-entity
  "A namespace row absent from the fixture branch, carrying the required
  definition digest its owner derives (`seon.program/definition-digest`)."
  {:malli/schema [:=> [:cat :seon.ns/name] :map]}
  [namespace-name]
  (let [entity {:seon.ns/name namespace-name}]
    (assoc entity :seon.program/definition-digest (program/definition-digest entity))))

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
      (let [{target :seon.ai/primary settings :seon.ai/settings}
            (#'turn/provider-targets {:seon.db/db (db/db connection)
                                      :seon.cluster/name "attempt-identity"
                                      :seon.agent/id "attempt-identity"})
            model (:seon.ai/model target)
            observed-at #inst "2026-09-19T00:00:00Z"
            recorded (#'turn/record-attempt!
                      {:seon.db/connection connection}
                      {:seon.turn/id "attempt-identity-turn"
                       :seon.agent/id "attempt-identity"
                       :seon.ai.attempt/ordinal 0
                       :seon.ai/target target
                       :seon.ai/settings settings}
                      observed-at)
            database (db/db connection)
            descriptor (db/pull database [:seon.ai.model/id]
                                [:seon.ai.model/id model])
            attempt (db/q '[:find ?attempt .
                            :where [?attempt :seon.ai.attempt/ordinal 0]]
                          database)]
        (is (nil? recorded))
        (is (= {:seon.ai.model/id model} descriptor))
        (is (int? attempt))
        (support/transacted! connection
                             [[:db/retractEntity [:seon.ai.model/id model]]])
        (let [entity (db/pull (db/db connection)
                           [:seon.ai/model :seon.ai.attempt/at]
                           attempt)]
          (is (= {:seon.ai/model model :seon.ai.attempt/at observed-at} entity)))))))

(defn- checked-transact! {:malli/schema [:=> [:cat :seon.db/connection :seon.store/transaction] :seon.db/transaction-report]}
  [connection transaction]
  (let [result (db/transact! connection transaction)]
    (when (or (:seon.db/invalid-read result) (:seon.schema/expected-value result) (:seon.db.write.attempt/request-id result)) (throw (ex-info (pr-str result) result)))
    result))

(deftest agent-budget-counts-a-call-that-closes-before-the-provider
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "budget-close")
     (support/transacted! connection
                          (agent/creation-tx {:seon.agent/id "budget-close-worker"
                                              :seon.ns/name 'my.agents.budget-close-worker
                                              :seon.cluster/name "budget-close"}))
     (support/transacted! connection
                          [{:seon.agent/id "budget-close-worker"
                            :seon.agent/settings {:seon.config.run/max-episode-runs 2}}])
     (support/assign-task! connection "budget-close-worker")
     (support/transacted! connection
                          (turn/open-tx {:seon.turn/id "first-closed-call"
                                         :seon.turn/agent [:seon.agent/id "budget-close-worker"]
                                         :seon.turn/opened-tx "datomic.tx"}))
     (support/transacted! connection (turn/close-tx {:seon.turn/id "first-closed-call"}))
     (is (= 1 (turn/episode-runs (db/db connection) "budget-close-worker"))
         "the turn PRD's outside-wake bound also counts pre-provider ordinary failures")
     (support/transacted! connection
                          (into (turn/open-tx {:seon.turn/id "same-transaction-system"
                                              :seon.turn/agent [:seon.agent/id "budget-close-worker"]
                                              :seon.turn/opened-tx "datomic.tx"})
                                (turn/close-tx {:seon.turn/id "same-transaction-system"})))
     (is (= 1 (turn/episode-runs (db/db connection) "budget-close-worker")))
     (support/transacted! connection
                          (turn/open-tx {:seon.turn/id "second-closed-call"
                                         :seon.turn/agent [:seon.agent/id "budget-close-worker"]
                                         :seon.turn/opened-tx "datomic.tx"}))
     (support/transacted! connection (turn/close-tx {:seon.turn/id "second-closed-call"}))
     (is (zero? (turn/turns-left (db/db connection) "budget-close-worker")))
     (is (nil? (turn/next-agent-work (db/db connection) {:seon.agent/id "budget-close-worker"}))))))

(deftest a-forms-read-evidence-is-only-what-the-form-read
  ;; The sink covered install, identity checks and profile derivation, so
  ;; `(quote 1)` carried 7,286 reads (evaluation 73041 on default).
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "form-read-proof"})
     (support/seed-cluster! connection "form-read-proof")
     (checked-transact! connection
                        (agent/creation-tx {:seon.agent/id "reader"
                                            :seon.ns/name 'my.agents.reader
                                            :seon.cluster/name "form-read-proof"}))
     (let [query '[:find ?name . :where [_ :seon.agent/id ?name]]
           ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.env/environment (support/environment "form-read-proof" connection)
                    :seon.db/connection connection
                    :seon.cluster/name "form-read-proof"
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.sci.eval/ctx ctx})
           [pure reading]
           (:seon.turn.loop/evaluated-sources
            (turn/evaluate-sources
            {:seon.turn.loop/cluster handle
             :seon.sci.eval/ctx ctx
             :seon.agent/id "reader"
             :seon.cluster.eval/ordinal 0
             :seon.ns/name 'my.agents.reader
             :seon.cluster.reply/sources
             [{:seon.cluster.eval/source "(inc 1)"}
              {:seon.cluster.eval/source (str "(seon.db/q '" (pr-str query) ")")}]}))
           evidence #(get-in % [:seon.sci.eval/evaluation :seon.cluster.eval/read-evidence])]
       (is (= "2" (get-in pure [:seon.sci.eval/evaluation :seon.eval/shown])))
       (is (empty? (evidence pure)) "a form that reads nothing records no read")
       ;; Stored evidence names the read's dependency plan, never a copy of
       ;; its request (b96d6e684): the form's one query reads one attribute.
       (is (= [#{:seon.agent/id}]
              (mapv #(into #{} (mapcat :datahike.query.source/attributes)
                           (get-in % [:datahike.read/dependency-plan :datahike.query.dependency/sources]))
                    (evidence reading)))
           "a form's one query is its one read")))))

(deftest a-response-shaped-map-settles-as-an-evaluated-value
  ;; A map naming `:seon.repl` keys is data like any other: it evaluates to itself.
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "refused-response-proof"})
     (support/seed-cluster! connection "refused-response-proof")
     (checked-transact! connection
                        (agent/creation-tx {:seon.agent/id "reader"
                                            :seon.ns/name 'my.agents.reader
                                            :seon.cluster/name "refused-response-proof"}))
     (let [ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.env/environment (support/environment "refused-response-proof" connection)
                    :seon.db/connection connection
                    :seon.cluster/name "refused-response-proof"
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.sci.eval/ctx ctx})
           [refused]
           (:seon.turn.loop/evaluated-sources
            (turn/evaluate-sources
             {:seon.turn.loop/cluster handle
              :seon.sci.eval/ctx ctx
              :seon.agent/id "reader"
              :seon.cluster.eval/ordinal 0
              :seon.ns/name 'my.agents.reader
              :seon.cluster.reply/sources [{:seon.cluster.eval/source "{:seon.repl/value 1}"}]}))
           evaluation (:seon.sci.eval/evaluation refused)]
       (is (= [:seon.ns/name 'my.agents.reader] (:seon.cluster.eval/ns evaluation)))
       (is (= {:seon.repl/value 1} (:seon.sci.admit/value evaluation)))))))

(deftest an-untrusted-frame-failure-is-an-error-value-carrying-its-instant
  (testing "a failure after SCI returned a form's value is the kept value's error, built by the one constructor: it carries :seon.error/at, so the settlement's diagnostic contract admits it"
    (let [failure-refusal #'sci.eval/failure-refusal]
      (doseq [thrown [(ex-info "loaded program changed" {:seon.error/message "no such schema"})
                      (ex-info "bare" {})
                      (IllegalStateException. "plain")]]
        (let [error (failure-refusal thrown)]
          (is (inst? (:seon.error/at error)))
          (is (string? (:seon.error/message error))))))))

(deftest open-run-tx-call-emits-only-while-its-run-is-open
  (testing "the writer, not a caller's pre-read, decides the run-dependent half"
    (support/with-database
     (fn [connection]
       (checked-transact!
        connection
        (into (support/agent-tx @connection "decider")
              [{:seon.turn/id "still-open" :seon.turn/agent [:seon.agent/id "decider"]
          :seon.turn/opened-tx "datomic.tx"}
               {:seon.turn/id "already-closed" :seon.turn/agent [:seon.agent/id "decider"]
          :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx"}]))
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
             ;; Match clear-history!: evaluation entities and their turn disappear
             ;; together; retaining an evaluation without its run is invalid.
             _ (checked-transact! connection
                                  [[:db.fn/retractEntity [:seon.cluster.eval/id evaluation-id]]
                                   [:db.fn/retractEntity [:seon.turn/id run-id]]])
             ;; The refusal the writer throws for a vanished run, from its producer.
             refusal (try (#'turn/refuse! `turn/close-call :seon.turn/no-such-run
                                          {:seon.turn/id run-id})
                          (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
             settlement (#'turn/settle!
                         {:seon.turn.loop/cluster handle
                          :seon.turn.loop/now (java.util.Date.)
                          :seon.agent/id "reader"
                          :seon.turn/id run-id
                          :seon.cluster.eval/ordinal 0
                          :seon.error/value refusal})
             database (db/db connection)
             rules (set (db/q '[:find [?rule ...]
                                :where [?error :seon.turn/rule ?rule]] database))]
         ;; The class: a settlement whose run-dependent half can no longer
         ;; apply must still commit the durable outcome. Re-issuing the close
         ;; that just refused made the second refusal identical by
         ;; construction and threw into the agent's flow proc.
         (is (map? settlement))
         (is (some? (:db-after (:seon.turn.loop/outcome settlement))))
         (is (contains? rules :seon.turn/no-such-run)
             "the refusal the loop actually met is the durable fact")
         (is (nil? (db/pull database [:db/id] [:seon.turn/id run-id]))
             "late settlement does not recreate the vanished turn")
         (is (nil? (db/pull database [:db/id] [:seon.cluster.eval/id evaluation-id]))
             "late settlement records the error without recreating an orphan evaluation")
         (is (empty? (db/q '[:find ?e :where [?e :seon.error/operation seon.turn/settle!]] database))
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
                 :seon.turn.loop/work {:seon.turn.work/situation :open :seon.agent/id "reader"}
                 :seon.turn.loop/now (java.util.Date.)
                 ;; The report answers the declared turn-report, as the proc's does.
                 :seon.turn.loop/report
                 (fn [outcome forms-run & _]
                   (swap! reported conj [outcome forms-run])
                   {:seon.agent/id "reader" :seon.turn.work/situation :open
                    :seon.turn.loop/forms-run forms-run :seon.turn.loop/outcome outcome})})
             database (db/db connection)
             refusals (db/q '[:find [?error ...]
                              :where [?error :seon.turn/rule _]]
                             database)]
         ;; Turn PRD §3/§14: a wake arriving while a turn is open has `:t`
         ;; greater than that turn's basis and opens the NEXT turn, answered
         ;; by the `:t` rule. Nothing is claimed and nothing is lost, so the
         ;; fence firing is the design — not a durable core fault that also
         ;; wakes the steward through `:seon.error/steward`.
         (is (= [[:released 0]] @reported))
         (is (empty? refusals))
         (is (= "already-running"
                (db/q '[:find ?id . :where
                        [?agent :seon.agent/id "reader"]
                        [?turn :seon.turn/agent ?agent]
                        [?turn :seon.turn/id ?id]
                        (not [?turn :seon.turn/closed-tx _])] database))
             "the open turn is untouched and the fence still holds"))))))

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
    (is (= "(+ 40 2)\n#:seon.repl{:result result/e7042, :value 42}"
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
    (is (str/starts-with? interrupted "(side-effect)"))
    (let [reply (support/reply (last (str/split-lines interrupted)))]
      (is (= {:interrupted? true :value? false}
             {:interrupted? (inst? (:seon.repl/interrupted reply))
              :value? (contains? reply :seon.repl/value)})
          "an interrupted evaluation answers its instant and no value")))
  (let [rendered
        (repl/render-ai
         {:seon.cluster.eval/source "(/ 1 0)"
          :seon.cluster.eval/ordinal 0
          :seon.cluster.eval/error "Divide by zero"
          :seon.eval/shown (pr-str {:seon.error/message "Divide by zero"
                                    :seon.error/exception-class 'java.lang.ArithmeticException})})]
    (is (= {:seon.error/message "Divide by zero"
            :seon.error/exception-class 'java.lang.ArithmeticException}
           (:seon.repl/error (support/reply (last (str/split-lines rendered)))))
        "the failure is the reply's :error, as the data it was shown as")))

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
            ;; The canonical producer analyzes and digests the entity the
            ;; writer reproduces (`support/program-entity`).
            settle-entity!
            (fn [run-id agent-id declaration source]
              (let [entity (support/program-entity @connection declaration source
                                             {:seon.schema.admission/source :agent})]
                (support/transacted!
                        connection
                        (into (support/agent-tx @connection agent-id)
                          [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}]))
                (support/transacted!
                        connection
                        (turn/receipt-start-tx
                         {::turn/id run-id
                          :seon.cluster.eval/ordinal 0
                          :seon.cluster.eval/at now}))
                ;; the definition's facts are the fold's (`seon.turn.batch`),
                ;; the receipt only its terminal facts
                (support/transacted! connection (#'turn.batch/entity-facts @connection entity))
                (support/transacted!
                        connection
                        (turn/receipt-settle-tx
                         {::turn/id run-id
                          :seon.cluster.eval/ordinal 0
                          :seon.eval/shown "nil"}))))]
        (support/transacted! connection [(support/namespace-entity @connection namespace-name)])
        (settle-entity!
         "pending-test-run" "pending-test-agent"
         [:seon.test/sym test-symbol]
         (str "(clojure.test/deftest ^{:seon.test/subject " function-symbol
              "} target-test (clojure.test/is true))"))
        (let [pending (db/pull @connection '[*]
                               [:seon.test/sym test-symbol])]
          (is (= function-symbol (:seon.test/subject pending))
              "the observed subject name is stored before a function entity exists")
          (is (nil? (:db/id (db/pull @connection [:db/id]
                                     [:seon.fn/sym function-symbol])))
              "settlement does not fabricate a function entity for the name")
          (is (some #{test-symbol}
                    (seon.fn/gate-set @connection function-symbol))))
        (settle-entity!
         "pending-function-run" "pending-function-agent"
         [:seon.fn/sym function-symbol]
         "(defn ^{:malli/schema [:=> [:cat] :int]} target [] 1)")
        (let [resolved (db/pull @connection '[:seon.test/subject]
                                [:seon.test/sym test-symbol])]
          (is (= function-symbol (:seon.test/subject resolved))
              "the value edge needs no repair when the function arrives")
          (is (some #{test-symbol}
                    (seon.fn/gate-set @connection function-symbol))))))))

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
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/transaction] [:or [:= :seon.turn-test/committed] :map]]}
  [connection tx-data]
  (try
    (let [result (db/transact! connection tx-data)]
      (if (or (:seon.db/invalid-read result) (:seon.schema/expected-value result) (:seon.db.write.attempt/request-id result))
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
                 (contains? refusal :seon.error/operation)))
        (is (= refusal result)
            "the serial eligibility read is the refusal; it is never absence or an open turn")
        (is (= before (db/basis-t (db/db connection)))
            "refusing eligibility commits no transaction")))))

(deftest an-episode-budget-read-refusal-reaches-next-work-verbatim
  (support/with-database
    (fn [connection]
      (support/transacted! connection (support/agent-tx @connection "unreadable-budget"))
      (support/assign-task! connection "unreadable-budget")
      (let [database (db/db connection)
            refusal (db/q '[:find ?entity .
                            :where [?entity :seon.audit/poison _]]
                          database)
            budget-query
            '[:find ?value . :in $ ?id :where
              [?agent :seon.agent/id ?id]
              [?agent :seon.agent/settings ?settings]
              [?settings :seon.config.run/max-episode-runs ?value]]
            query db/q]
        (is (and (map? refusal)
                 (contains? refusal :seon.error/at)
                 (contains? refusal :seon.error/layer)
                 (contains? refusal :seon.error/operation)))
        (with-redefs [db/q
                      (fn [form & arguments]
                        (if (= budget-query form)
                          refusal
                          (apply query form arguments)))]
          (is (= refusal
                 (turn/next-agent-work
                  database {:seon.agent/id "unreadable-budget"}))))))))

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

(deftest a-run-records-its-starting-namespace
  (with-model-database
    (fn [connection]
      (support/transacted!
              connection
              (into [(namespace-entity 'replay.start)]
                    cat
                    [(support/agent-tx @connection "replay-agent" 'replay.start)]))
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
                               [(namespace-entity 'replay.later)
                                {::turn/id "replay-run"
                                 ::turn/agent [:seon.agent/id "replay-agent"]
                                 :seon.turn/opened-tx "datomic.tx"}])
          (is (= 'replay.start
                 (get-in run [::turn/starting-ns :seon.ns/name]))))
      (is (= :outside
             (db/q '[:find ?origin .
                     :where
                     [?run :seon.turn/id "replay-run"]
                     [?form :seon.cluster.eval/run ?run]
                     [?form :seon.cluster.eval/origin ?origin]]
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

(deftest ^{:seon.test/long "Canonical turn creation, four evaluation settlements and runtime declaration analysis exercise the real writer and unresolved-call report. The two-analysis publication regression measured 20.54 s; this integration test includes additional settlement transactions."
           :seon.test/long-ms 60000}
  settlement-keeps-unresolved-call-and-require-names-as-values
  ;; Source observations survive independently of whether a declaration entity
  ;; currently exists. Resolution is a query; settlement never fabricates it.
  (support/with-database
    (fn [connection]
      (support/transacted!
              connection
              (into [(namespace-entity 'my.macro-caller)]
                    cat
                    [(support/agent-tx @connection "macro-caller" 'my.macro-caller)]))
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
      (let [macro-entity
            (db/pull @connection
                     [:db/id :seon.fn/source :seon.fn/macro?]
                     [:seon.fn/sym 'seon.bootstrap/help])]
        (is (:db/id macro-entity) "publication supplies the macro identity")
        (is (string? (:seon.fn/source macro-entity)))
        (is (true? (:seon.fn/macro? macro-entity))))
      (let [result
            (db/transact!
             connection
             (turn/receipt-settle-tx
              {::turn/id "macro-call-run"
               :seon.cluster.eval/ordinal 0
               :seon.eval/shown "nil"}))]
        (is (some? (:db-after result))
            "the settlement transaction commits"))
      (let [form (db/pull @connection
                          [:seon.fn/calls]
                          [:seon.cluster.eval/id
                           (turn/receipt-identity "macro-call-run" 0)])]
        (is (empty? (:seon.fn/calls form))
            "a receipt carries no calls: static form analysis is not a program entity"))
      (let [result
            (db/transact!
             connection
             (turn/receipt-settle-tx
              {::turn/id "macro-call-run"
               :seon.cluster.eval/ordinal 1
               :seon.cluster.eval/error "Could not resolve missing.target/nope"}))]
        (is (some? (:db-after result))
            "the error settlement commits without a dangling lookup ref"))
      (is (empty? (db/q '[:find ?target
                          :in $ ?form-id
                          :where
                          [?form :seon.cluster.eval/id ?form-id]
                          [?form :seon.fn/calls ?target]]
                        @connection
                        (turn/receipt-identity "macro-call-run" 1)))
          "a receipt carries no calls, resolved or not")
      (is (nil? (:db/id (db/pull @connection [:db/id]
                                 [:seon.fn/sym 'missing.target/nope])))
          "the observed call does not mint its target")
      (is (nil? (:db/id (db/pull @connection [:db/id]
                                 [:seon.ns/name 'unindexed.required]))))
      (let [result
            (db/transact!
             connection
             ;; the namespace facts the fold writes for a require
             [{:seon.ns/name 'my.macro-caller
               :seon.ns/source "(require 'unindexed.required)"
               :seon.ns/requires #{'unindexed.required}}])]
        (is (some? (:db-after result))
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
            entity (support/program-fn-entity @connection 'my.macro-caller/unresolved-caller source
                                              {:seon.schema.admission/source :agent})
            result (db/transact!
                    connection
                    ;; the definition's facts, as the fold writes them
                    (#'turn.batch/entity-facts @connection entity))
            report (seon.fn/unresolved-callers @connection)]
        (is (some? (:db-after result)))
        (is (not-any? #(= 'missing.target/nope (:seon.fn/callee %))
                      (:seon.program/unresolved-callers report))
            "a namespace without a program entity is outside the unresolved-call report")
        ;; SCI refuses to define a function calling an unresolved qualified symbol,
        ;; so the one path never stores one as a call; the definition itself is stored
        (is (some? (:db/id (db/pull @connection [:db/id]
                                    [:seon.fn/sym 'my.macro-caller/unresolved-caller]))))))))

(deftest receipt-transitions-preserve-one-terminal-outcome
  (let [start-tx (ns-resolve 'seon.turn 'receipt-start-tx)
        settle-tx (ns-resolve 'seon.turn 'receipt-settle-tx)]
    (is (some? start-tx) "the run owner provides the receipt-start transition")
    (is (some? settle-tx) "the run owner provides the receipt-settle transition")
    (when (and start-tx settle-tx)
      (with-model-database
        (fn [connection]
          (support/transacted! connection (support/agent-tx @connection "receipt-agent"))
          (support/transacted! connection
                             (turn/open-tx {::turn/id "receipts" ::turn/agent [:seon.agent/id "receipt-agent"] :seon.turn/opened-tx "datomic.tx"}))

          (let [start {::turn/id "receipts"
                       :seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/at t0}
                settle {::turn/id "receipts"
                        :seon.cluster.eval/ordinal 0
                        :seon.eval/shown "42"
                        :seon.cluster.eval/diagnostics
                        "[{:seon.error/message \"renderer evidence\"}]"}]
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
                                     "{:seon.error/message \"failed\"}"
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
              (is (= "[{:seon.error/message \"renderer evidence\"}]"
                     (:seon.cluster.eval/diagnostics receipt)))
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
             ;; A stored namespace: the plan declares an absent one only
             ;; from its evaluation's entity or the source that moved into it,
             ;; else the writer refuses the bare identity by name.
             (turn/plan-tx
              {::turn/id run-id ::turn/starting-ns [:seon.ns/name 'my.agents.a1]
               ::turn/sources [{:seon.cluster.eval/source "(+ 1 1)"}]})))
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
                                  (support/agents-tx @connection (vec agent-ids)))
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
        "state-machine property failed")))

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
                        round gen/nat]
           (with-model-database
             (fn [connection]
               (let [run-id (str "keep-" round "-" (count states)
)
                     agent-id (str "keeper-" run-id)
]
                 (support/transacted! connection
                                    (support/agent-tx @connection agent-id))
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
        "recovery property failed")))

(deftest recovery-closes-a-turn-with-no-evaluations
  (with-model-database
    (fn [connection]
      (support/transacted! connection (support/agent-tx @connection "cut"))
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

;; THE FAULTS UNIT lives on `:seon.error/agent`, whose renderer needs a run to
;; link to; that is why its regression sits beside the run model rather than in
;; the pure `seon.error-test`, which opens no database by design.
(deftest fault-unit-lists-agent-faults-newest-first-with-run-links
  (support/with-database
    (fn [connection]
      (checked-transact! connection
                         (into (support/agent-tx @connection "juno")
                               [{:seon.turn/id "run-1" :seon.turn/agent [:seon.agent/id "juno"]
                           :seon.turn/opened-tx "datomic.tx"}]))
      (doseq [[kind message millis turn-id] [[:seon.instrument/contract-violated "older fault" 1700000000000 "run-1"]
                                            [:seon.db/rejected "newer fault" 1700000060000 nil]]]
        (checked-transact!
         connection
         (error/commit-tx
          (db/db connection)
          ;; The message never enters an error's signature (`seon.error/signature`):
          ;; two faults are two sites, so each names its own operation.
          (cond-> {:seon.error/source {:seon.error/message message
                                       :seon.error/operation (symbol "fixture.faults" (name kind))}
                   :seon.error/declared-schema :seon.error/normalization-error
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
            completion (async/chan 1)
            ;; the bound's fault is one stored occurrence, found by its message
            faults #(db/q '[:find [?o ...] :in $ ?prefix
                            :where [?o :seon.error.occurrence/message ?m]
                                   [(clojure.string/starts-with? ?m ?prefix)]]
                          (db/db connection)
                          (str "Agent " (pr-str agent-id)))]
        ;; the fault is recorded through `seon.fault/fault!` in the cluster's
        ;; world: record mode, so the step returns, with root to deliver to
        (support/seed-cluster! connection cluster-name {})
        (cluster/seed-root-agent! connection cluster-name cluster/boot-process-identity)
        (checked-transact! connection (support/agent-tx @connection agent-id))
        (support/assign-task! connection agent-id)
        (async/>!! completion :seon.agent/ready)
        ;; The dial rides the handle exactly as the turn completion allowance
        ;; does: a fixture pins its own bound, and the caller wins.
        (let [cluster (assoc (support/cluster-handle
                              {:seon.db/connection connection
                               :seon.cluster/name cluster-name
                               :seon.env/environment (support/environment cluster-name connection)
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
          (is (= bound (:seon.turn.loop/write-refusals (:seon.turn.loop/parked parked))))
          (is (= bound (:seon.turn.loop/write-refusals
                        (:seon.turn.loop/parked parked))))
          (is (= bound (:seon.config.agent/write-refusal-bound
                        (:seon.turn.loop/parked parked))))
          (is (nil? (async/poll! wake-channel))
              "a parked proc offers no self-rewake")
          (let [[occurrence :as found] (faults)]
            (is (= 1 (count found)) "the bound fires exactly once, never once per attempt")
            (is (str/includes? (:seon.error.occurrence/message
                                (db/pull (db/db connection) [:seon.error.occurrence/message] occurrence))
                               (str "(bound " bound ")"))))
          ;; THE MEASUREMENT THAT MATTERS: the store stops growing.
          (let [recorded (count (faults))
                before (:max-tx (db/db connection))
                after-state (reduce (fn [state _] (pass! state))
                                    parked
                                    (range 5))]
            (is (= before (:max-tx (db/db connection)))
                "a parked agent transacts nothing on any later wake")
            (is (some? (:seon.turn.loop/parked after-state))
                "the park survives every later wake")
            (is (= recorded (count (faults)))
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

(deftest a-receipt-is-red-by-its-settled-facts-never-its-shown-text
  ;; THE CLASS: red-receipt? read SCI's unbound marker back out of shown text,
  ;; which prints as `#object[...]` and is not EDN. Redness is the settled
  ;; facts'. Since 9e61340b2 (d1efccd3ab7e) nothing walks a result after
  ;; evaluation: an unbound Var's value is a value, as in Clojure, so it
  ;; settles green like any other unreadable shown text.
  (support/with-database
   (fn [connection]
     (support/transacted! connection
                          (into (support/agent-tx @connection "unbound-receipt")
                                [(namespace-entity 'user)
                                 {:seon.turn/id "unbound-receipt-turn"
                                  :seon.turn/agent [:seon.agent/id "unbound-receipt"]
                                  :seon.turn/opened-tx "datomic.tx"}]))
     (let [ctx (support/fork-cluster-ctx connection)
           configuration (support/effective-config)]
       (doseq [[ordinal source red?] [[0 "(do (declare unbound-probe) [unbound-probe])" false]
                                      [1 "#'clojure.core/inc" false]
                                      [2 "{:example/plain 1}" false]]]
         (support/transacted! connection
                              (turn/receipt-start-tx
                               {:seon.turn/id "unbound-receipt-turn"
                                :seon.cluster.eval/ordinal ordinal
                                :seon.cluster.eval/at (java.util.Date.)}))
         (let [evaluation (support/evaluate
                           {:seon.cluster.eval/source source
                            :seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                            :seon.render/profile (render/agent-render-profile configuration)
                            :seon.sci.admit/caps (config/result-caps configuration)
                            :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})
               [settled] (turn/settlement-projection {:seon.db/connection connection} evaluation)
               facts (turn/evaluation-facts
                      {:seon.turn/id "unbound-receipt-turn"
                       :seon.cluster.eval/ordinal ordinal
                       :seon.sci.eval/evaluation evaluation
                       :seon.turn.loop/settlement-evaluation settled})
               _ (support/transacted! connection (turn/receipt-settle-tx facts))
               receipt (db/q '[:find (pull ?e [*]) .
                               :in $ ?ordinal
                               :where [?e :seon.cluster.eval/ordinal ?ordinal]
                               [?e :seon.cluster.eval/run ?run]
                               [?run :seon.turn/id "unbound-receipt-turn"]]
                             (db/db connection) ordinal)]
           (is (string? (:seon.eval/shown receipt)))
           (is (= red? (turn/red-receipt? receipt)))
           (is (= red? (= :unrouted-red
                          (:seon.turn.work/form-state
                           (turn/form-settlement (db/db connection)
                                                 (:seon.cluster.eval/id receipt))))))))))))

(deftest an-agent-dial-read-that-refuses-is-not-an-absent-dial
  ;; THE CLASS: `(or overlay carried effective)` read a refused config as an
  ;; absent dial, so the bound fell to the next source or to nil. A cluster
  ;; with no config entity makes the effective read refuse.
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "dial-reader"))
     ;; The handle is built from the shipped decisions (no cluster world), then
     ;; names the missing cluster: `cluster-handle` itself refuses a cluster
     ;; with no config entity, and the refusal under test is the dial read's.
     (let [handle (assoc (support/cluster-handle
                          {:seon.db/connection connection
                           :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
                           :seon.db.process/id cluster/boot-process-identity})
                         :seon.cluster/name "no-such-dial-cluster")
           refused (turn/write-refusal-bound (db/db connection) handle "dial-reader")]
       (is (= :seon.config/cluster (:seon.config/error-key refused)))
       (is (= "no-such-dial-cluster" (:seon.config/missing-effective refused)))
       (is (= 3 (turn/write-refusal-bound
                 (db/db connection)
                 (assoc handle :seon.config.agent/write-refusal-bound 3)
                 "dial-reader"))
           "a carried dial is read without asking the config")))))

(deftest a-replaced-result-is-shown-by-the-one-print-owner
  ;; A value that replaces a printed result (a disposition refusal inside the
  ;; loop, a refusal at the terminal writer) is rendered by
  ;; `seon.sci.eval/shown-result`, never by `pr-str`, and the saved text is
  ;; that rendering of the value that settles.
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection :seon.boot/cluster-name "one-print"})
     (support/seed-cluster! connection "one-print")
     (checked-transact! connection (agent/creation-tx {:seon.agent/id "printer" :seon.ns/name 'my.agents.printer
                                                       :seon.cluster/name "one-print"}))
     (let [ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.env/environment (support/environment "one-print" connection)
                    :seon.db/connection connection :seon.cluster/name "one-print"
                    :seon.db.process/id cluster/boot-process-identity :seon.sci.eval/ctx ctx})
           database (db/db connection)
           ;; a failure shows its small error map, printed structurally by the
           ;; owner (`seon.sci.eval/shown-failure`, 737fe0a5d); the whole error
           ;; stays the value
           owner #(:seon.eval/shown (#'turn/final-shown handle %1 "printer" (error/reply-value %2)
                                                        {:seon.render.value/options {:seon.render.value/structural? true}}))]
       (testing "a disposition that is not the last form is refused, and the refusal is the owner's text"
         (let [preview (turn/preview-sources {:seon.turn.loop/cluster handle :seon.db/db database
                                              :seon.sci.eval/ctx ctx :seon.agent/id "printer"
                                              :seon.ns/name 'my.agents.printer
                                              :seon.cluster.reply/text "(my.agent/done)\n(+ 1 1)"
                                              :seon.sci.admit/caps (:seon.sci.admit/caps handle)})
               refused (get-in preview [:seon.turn.loop/evaluated-sources 0 :seon.sci.eval/evaluation])
               value (:seon.sci.admit/value refused)]
           (is (:seon.turn/invalid-disposition-source value))
           (is (= (owner database value) (:seon.eval/shown refused)))
           (is (not= (pr-str value) (:seon.eval/shown refused)) "the retired raw printer's text is gone")))
       (testing "a terminal refusal saves the owner's rendering of the recorded error"
         (let [run-id "one-print-run"
               evaluation-id (id/evaluation run-id 0)
               _ (checked-transact!
                  connection
                  [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id "printer"]
                    :seon.turn/starting-ns [:seon.ns/name 'my.agents.printer] :seon.turn/opened-tx "datomic.tx"}
                   {:seon.cluster.eval/id evaluation-id :seon.cluster.eval/at (java.util.Date.)
                    :seon.cluster.eval/run [:seon.turn/id run-id] :seon.cluster.eval/ordinal 0
                    :seon.cluster.eval/ns [:seon.ns/name 'my.agents.printer]
                    :seon.cluster.eval/source "(+ 1 1)"}])
               refusal (try (#'turn/refuse! `turn/close-call :seon.turn/no-such-run {:seon.turn/id run-id})
                            (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
               settlement (#'turn/settle! {:seon.turn.loop/cluster handle :seon.turn.loop/now (java.util.Date.)
                                           :seon.agent/id "printer" :seon.turn/id run-id
                                           :seon.cluster.eval/ordinal 0 :seon.error/value refusal})
               after (db/db connection)
               shown (:seon.eval/shown (db/pull after [:seon.eval/shown] [:seon.cluster.eval/id evaluation-id]))]
           (is (some? (:db-after (:seon.turn.loop/outcome settlement))))
           (is (string? shown))
           (is (= (owner after (:seon.error/value settlement)) shown))))))))

(deftest a-reply-keeps-its-dispatch-macro-forms-as-code
  ;; The planner classified code by the value read, so a top-level reader
  ;; conditional (read to its branch), a regex and a tagged literal were
  ;; dropped as prose without a word. Structure is the authored syntax.
  (let [planned #(let [r (turn/planned-sources % 'user 100000)]
                   (if (map? r) (:seon.error/message r) (mapv :seon.cluster.eval/source r)))]
    (is (= ["(import java.util.Date)" "(Date.)" "#?(:clj 1 :cljs 2)" "#\"a+\"" "(str ::x)"]
           (planned "(import java.util.Date)\n(Date.)\n#?(:clj 1 :cljs 2)\n#\"a+\"\n(str ::x)")))
    (is (= ["#?(:clj 1 :cljs 2)"] (planned "#?(:clj 1 :cljs 2)")))
    (is (= ["#inst \"2026-09-25\""] (planned "#inst \"2026-09-25\"")))
    (testing "prose stays prose; a fence's code is planned"
      (is (string? (planned "just prose here")))
      ;; a lone word outside a fence is prose
      (is (= ["(+ 1 2)"] (planned "Here is code:\n```clojure\n(+ 1 2)\n```\nDone."))))))

(deftest a-fired-turn-bound-requests-its-owners-stop-and-settles-its-join-whole
  ;; Review lost-error-r1 R1/R2: a fired active-turn bound requests the
  ;; execution owner's stop before a panic records, and its join channel
  ;; carries the terminal throwable whole (the panic's receipt, caused by the
  ;; declared failure), never an unresolved channel.
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           stops (atom 0)
           observer (#'turn/arm-turn-completion-backstop!
                     {:seon.agent/executor (cluster/projection-executor
                                            (:seon.sci.eval/projection-state ctx))
                      :seon.agent/timeout-ms 100
                      :seon.agent/backstop-state (atom nil)
                      :seon.env/environment (env/of ctx)
                      :seon.agent/agent-id "fired-bound"
                      :seon.agent/process cluster/boot-process-identity
                      :seon.agent/run-id (atom nil)
                      :seon.fault/stop-request! #(swap! stops inc)})
           terminal (support/await-event! (:seon.agent/failure-channel observer)
                                          ::fired-bound (constantly true) 5000)
           receipt (:seon.fault/recorded (ex-data terminal))
           failure (if receipt (ex-cause terminal) terminal)]
       (is (instance? Throwable terminal))
       ;; The producer declares the error's identity; the completion event
       ;; that missed its bound is the evidence it names.
       (is (= 'seon.turn/turn-completion-error (:seon.error/operation (ex-data failure))))
       (is (= :seon.agent/turn-transform
              (get-in (ex-data failure) [:seon.error/data :seon.error/operation])))
       (when (and receipt (not (:seon.fault/committed? receipt)))
         (is (= 1 @stops) "an unstorable fault requested the owner's stop once"))))))

(defn- terminal-fixture
  "A seeded cluster, agent `printer`, and one open run `run-id` whose `sources`
  are started, unsettled receipts; answers the cluster handle."
  [connection cluster-name run-id sources]
  (config/apply! {:seon.db/connection connection :seon.boot/cluster-name cluster-name})
  (support/seed-cluster! connection cluster-name)
  (checked-transact! connection (agent/creation-tx {:seon.agent/id "printer" :seon.ns/name 'my.agents.printer
                                                    :seon.cluster/name cluster-name}))
  (checked-transact!
   connection
   (into [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id "printer"]
           :seon.turn/starting-ns [:seon.ns/name 'my.agents.printer] :seon.turn/opened-tx "datomic.tx"}]
         (map-indexed (fn [ordinal source]
                        {:seon.cluster.eval/id (id/evaluation run-id ordinal) :seon.cluster.eval/at (java.util.Date.)
                         :seon.cluster.eval/run [:seon.turn/id run-id] :seon.cluster.eval/ordinal ordinal
                         :seon.cluster.eval/origin :model :seon.cluster.eval/ns [:seon.ns/name 'my.agents.printer]
                         :seon.cluster.eval/source source}))
         sources))
  (support/cluster-handle
   {:seon.env/environment (support/environment cluster-name connection)
    :seon.db/connection connection :seon.cluster/name cluster-name
    :seon.db.process/id cluster/boot-process-identity :seon.sci.eval/ctx (support/fork-cluster-ctx connection)}))

(defn- settled-receipts [connection run-id]
  (mapv #(db/pull (db/db connection) [:seon.eval/shown :seon.cluster.eval/error :seon.cluster.eval/interrupted-at]
                  [:seon.cluster.eval/id (id/evaluation run-id %)])
        (range 2)))

(deftest ^{:seon/tasks #{'seon.turn/failed-run-left-unsettled}} a-throwable-escaping-the-turn-closes-its-run-and-propagates-whole
  ;; THE CLASS (audit 2026-09-26 M4 L2): a Throwable leaving the turn after
  ;; its run opened left the run open, so the agent's every later run refused
  ;; `agent-already-running`. The turn boundary closes it with the writer's
  ;; recovery decision, then the throwable continues whole as the one fault.
  (support/with-database
   (fn [connection]
     (let [run-id "escaped-run"
           handle (terminal-fixture connection "escaped-turn" run-id ["(sorted-map 'a 1)" "(+ 1 1)"])
           escaped (ClassCastException. "a later stage threw")
           answered (#'turn/close-escaped-run! handle "printer" escaped)
           database (db/db connection)]
       (is (identical? escaped answered) "the throwable itself propagates")
       (is (empty? (.getSuppressed ^Throwable answered)) "closing succeeded, so nothing is suppressed onto it")
       (is (some? (:seon.turn/closed-tx (db/pull database [:seon.turn/closed-tx] [:seon.turn/id run-id]))))
       (is (every? :seon.cluster.eval/interrupted-at (settled-receipts connection run-id))
           "the writer marks each unsettled form interrupted; nothing is replayed")
       (is (nil? (turn/open-for-agent database [:seon.agent/id "printer"]))
           "the agent holds no open run, so its next run can open")
       (is (identical? escaped (#'turn/close-escaped-run! handle "printer" escaped))
           "with no open run the boundary writes nothing and still answers the throwable")))))

(deftest ^{:seon/tasks #{'seon.turn/a-refused-settlement-is-stored-as-a-core-fault}} a-refused-settlement-keeps-each-evaluated-value
  ;; THE CLASS (audit 2026-09-26 M4 L3): a refused batch settlement guessed a
  ;; failing ordinal by re-applying entities, and every other form's shown text
  ;; was replaced by the refusal. The evaluated values stay; the refused
  ;; commit is each receipt's publication status.
  (support/with-database
   (fn [connection]
     (let [run-id "refused-batch"
           handle (terminal-fixture connection "refused-batch" run-id ["(+ 1 1)" "(* 3 3)"])
           configuration (support/effective-config)
           now (java.util.Date.)
           requests (mapv (fn [ordinal source]
                            {:seon.turn.loop/cluster handle :seon.turn.loop/now now :seon.agent/id "printer"
                             :seon.turn/id run-id :seon.cluster.eval/ordinal ordinal
                             :seon.sci.eval/evaluation
                             (support/evaluate
                              {:seon.cluster.eval/source source
                               :seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.db/db (db/db connection)
                               :seon.render/profile (render/agent-render-profile configuration)
                               :seon.sci.admit/caps (config/result-caps configuration)
                               :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})})
                          (range 2) ["(+ 1 1)" "(* 3 3)"])
           prepared (mapv (fn [{ordinal :seon.cluster.eval/ordinal evaluation :seon.sci.eval/evaluation}]
                            {:seon.turn.loop/receipt {:seon.turn/id run-id :seon.cluster.eval/ordinal ordinal
                                                      :seon.eval/shown (:seon.eval/shown evaluation)}})
                          requests)
           refusal (try (#'turn/refuse! `turn/close-call :seon.turn/no-such-run {:seon.turn/id run-id})
                        (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
           settled (#'turn/settle-batch-refusal! handle requests prepared refusal [])
           receipts (settled-receipts connection run-id)]
       (is (some? (:db-after (:outcome settled))))
       (is (= ["2" "9"] (mapv :seon.eval/shown receipts)) "each form shows the value it evaluated")
       (is (every? #(str/includes? (str (:seon.cluster.eval/error %)) (:seon.error/message refusal)) receipts)
           "each receipt names the refused publication")
       (is (some? (:seon.turn/closed-tx (db/pull (db/db connection) [:seon.turn/closed-tx] [:seon.turn/id run-id]))))))))

(deftest a-refused-refusal-settlement-is-answered-as-a-value
  ;; THE CLASS (stability sweep 2026-09-27 item 1, fault 317111871b50): when
  ;; the refusal arm's own write was refused, `settle-batch-refusal!` threw
  ;; past the reply and the caller read "awaited channel closed". WANTED: the
  ;; refusal returns as a value carrying its recorded fault's receipt, and
  ;; the run is closed.
  (support/with-database
   (fn [connection]
     (let [run-id "refused-refusal"
           handle (terminal-fixture connection "refused-refusal" run-id ["(+ 1 1)" "(* 3 3)"])
           ;; the fault is recorded in the cluster's world, with root to deliver to
           _ (cluster/seed-root-agent! connection "refused-refusal" cluster/boot-process-identity)
           now (java.util.Date.)
           configuration (support/effective-config)
           requests [{:seon.turn.loop/cluster handle :seon.turn.loop/now now :seon.agent/id "printer"
                      :seon.turn/id run-id :seon.cluster.eval/ordinal 0
                      :seon.sci.eval/evaluation
                      (support/evaluate {:seon.cluster.eval/source "(+ 1 1)"
                                         :seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.db/db (db/db connection)
                                         :seon.sci.admit/caps (config/result-caps configuration)
                                         :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})}]
           ;; the same ordinal twice: the writer's own terminal fence refuses
           ;; the refusal settlement (`receipt-settle-batch-call`)
           prepared [{:seon.turn.loop/receipt {:seon.turn/id run-id :seon.cluster.eval/ordinal 0 :seon.eval/shown "2"}}
                     {:seon.turn.loop/receipt {:seon.turn/id run-id :seon.cluster.eval/ordinal 0 :seon.eval/shown "2"}}]
           refusal (try (#'turn/refuse! `turn/close-call :seon.turn/no-such-run {:seon.turn/id run-id})
                        (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
           settled (#'turn/settle-batch-refusal! handle requests prepared refusal [])
           receipt (:seon.fault/recorded (:refused-outcome settled))]
       (is (nil? (:db-after (:outcome settled))) "the refusal settlement itself was refused")
       (is (= (:seon.error/message refusal) (:seon.error/message (:refused-outcome settled)))
           "the caller keeps the refusal its run caused")
       (is (true? (:seon.fault/committed? receipt)))
       (is (some? (:seon.turn/closed-tx (db/pull (db/db connection) [:seon.turn/closed-tx] [:seon.turn/id run-id])))
           "the run is closed, so the agent's next run can open")))))

(deftest a-definition-changed-since-the-run-read-it-is-refused-at-the-writer
  ;; Root ruling (AGENTS "decide at the authority"): the fold's program facts
  ;; check each definition's stored digest against the run's basis; another
  ;; writer's change in between refuses that form's facts (detect and push
  ;; back, never lock, never last-writer-wins).
  (support/with-database
   (fn [connection]
     (let [basis (db/db connection)
           request (support/code-request
                    {:seon.batch/world (world/agent-world {:seon.db/connection connection :seon.cluster/name "default"
                                                           :seon.sci.eval/base-ctx (sci.eval/base-ctx basis)}
                                                          {:seon.agent/id "stale-writer"})
                     :seon.batch/observe observe/observe
                     :seon.batch/start-ns 'user
                     :seon.batch/text "(defn stale-f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"})
           batch (seon.batch/evaluate-batch (dissoc request :seon.batch/settle))
           _ (is (= 'user/stale-f (get-in batch [:seon.batch/entries 0 :seon.batch/change :seon.batch/entities 0 :seon.fn/sym])))
           unchanged (turn.batch/program-facts request (db/db connection) (:seon.batch/program batch))
           _ (support/transacted!
              connection
              [(support/namespace-entity basis 'user)
               (support/program-fn-entity basis 'user/stale-f
                                          "(defn stale-f {:malli/schema [:=> [:cat :int] :int]} [x] (dec x))"
                                          {:seon.schema.admission/source :agent})])
           head (db/db connection)
           refused (turn.batch/program-facts request head (:seon.batch/program batch))
           [_ _ entries] (turn.batch/revalidate request head (vec (:seon.batch/entries batch)))]
       (is (vector? unchanged) "nothing changed underneath: the program's facts")
       (is (= [[:seon.fn/sym 'user/stale-f]] (:seon.error/offending refused))
           "the writer refuses the definition another writer changed since the basis")
       (is (= [[:seon.fn/sym 'user/stale-f]] (get-in entries [0 :seon.batch/facts-error :seon.error/offending]))
           "revalidation gives the form that proposed it the refusal as its facts error")))))

(deftest removing-a-namespace-no-declaration-names-retracts-nothing
  ;; Model proof: `my.program/remove-ns!` of an empty namespace SCI held but
  ;; never recorded answered nil from `removal-facts`, refusing its
  ;; transaction-data contract and recording a fault for a removal that
  ;; needs no retraction.
  (support/with-database
   (fn [connection]
     (let [basis (db/db connection)
           request (support/code-request
                    {:seon.batch/world (world/agent-world {:seon.db/connection connection :seon.cluster/name "default"
                                                           :seon.sci.eval/base-ctx (sci.eval/base-ctx basis)}
                                                          {:seon.agent/id "empty-removal"})
                     :seon.batch/start-ns 'user
                     :seon.batch/text "nil"})
           facts (turn.batch/form-facts request basis {:seon.batch/entities [] :seon.batch/schema-keys #{}
                                                       :seon.batch/removals ['never.recorded-namespace]
                                                       :seon.batch/namespaces #{'never.held-namespace}})]
       (is (= [] facts) "an unrecorded namespace's removal is an empty transaction, not a refusal")))))

(deftest a-settlement-that-throws-still-shows-each-evaluated-value
  ;; Root, 2026-09-28: while a settle stage threw (measurement), every reply
  ;; showed only the settle error and lost the value its form computed. The
  ;; path is total: the settlement's failure rides beside each value.
  (support/with-database
   (fn [connection]
     (let [run-id "throwing-settlement"
           handle (terminal-fixture connection "throwing-settlement" run-id ["(+ 1 1)" "(* 3 3)"])
           configuration (support/effective-config)
           now (java.util.Date.)
           evaluate #(support/evaluate
                      {:seon.cluster.eval/source %
                       :seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.db/db (db/db connection)
                       :seon.render/profile (render/agent-render-profile configuration)
                       :seon.sci.admit/caps (config/result-caps configuration)
                       :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})
           requests (mapv (fn [ordinal source]
                            {:seon.turn.loop/cluster handle :seon.turn.loop/now now :seon.agent/id "printer"
                             :seon.turn/id run-id :seon.cluster.eval/ordinal ordinal
                             :seon.sci.eval/evaluation (evaluate source)})
                          (range 2) ["(+ 1 1)" "(* 3 3)"])
           ;; a program without the fold's request: settle-batch! refuses its
           ;; own input, a throw inside the settlement
           settled (#'turn/settle-evaluated! handle requests {})
           receipts (settled-receipts connection run-id)]
       (is (= ["2" "9"] (mapv :seon.eval/shown receipts)) "each form shows the value it evaluated")
       (is (some? (:refused-outcome settled)) "the settlement's failure is answered as a value")
       (is (every? #(some? (:seon.cluster.eval/error %)) receipts)
           "each receipt names the failed settlement beside its value")))))

(deftest a-form-whose-facts-are-refused-says-it-was-not-admitted
  ;; Lane defn-refusal-reason, 2026-09-28: a defn whose body called another
  ;; function with the wrong arity replied `{:error "#'ns/f"}` (the Var's
  ;; name: the shown value beat the error text) with no refusal at all. The
  ;; refusal rides beside the value as data and says the declaration was not
  ;; admitted to the program.
  (let [refusal {:seon.error/message "Recorded source argument counts disagree with the final prepared arities."
                 :seon.error/at (java.util.Date.)
                 :seon.error/layer :seon.turn.batch/program
                 :seon.error/operation 'seon.turn.batch/program-facts
                 :seon.error/offending [{:seon.fn/sym 'my.x/lint3}]}
        receipt (#'turn/not-admitted {:seon.eval/shown "#'my.x/lint3"} refusal)]
    (is (str/starts-with? (:seon.cluster.eval/error receipt) "Not admitted to the program")
        "the receipt's error names the refusal, not the Var")
    (is (str/includes? (:seon.cluster.eval/diagnostics receipt) "Not admitted to the program")
        "the diagnostic carries the refusal whole beside the value")
    (is (str/includes? (:seon.cluster.eval/diagnostics receipt) "argument counts disagree")
        "the producer's own message survives in the diagnostic")
    (is (= "#'my.x/lint3" (:seon.eval/shown receipt)) "the value stands")))

(deftest a-plain-failing-call-never-carries-the-not-admitted-diagnostic
  ;; Root, 2026-09-28: `(my.program/no-such-fn 1)` (an unresolved qualified
  ;; symbol in a program namespace) carried "Not admitted to the program" though
  ;; the form declares nothing: SCI touched the namespace map, the facts stage
  ;; refused that namespace-only change, and `not-admitted` spoke for it.
  (support/with-database
   (fn [connection]
     (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name "plain-call-proof"})
     (support/seed-cluster! connection "plain-call-proof")
     (checked-transact! connection
                        (agent/creation-tx {:seon.agent/id "caller"
                                            :seon.ns/name 'my.agents.caller
                                            :seon.cluster/name "plain-call-proof"}))
     (let [ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.env/environment (support/environment "plain-call-proof" connection)
                    :seon.db/connection connection
                    :seon.cluster/name "plain-call-proof"
                    :seon.db.process/id cluster/boot-process-identity
                    :seon.sci.eval/ctx ctx})
           evaluations (mapv #(get % :seon.sci.eval/evaluation)
                             (:seon.turn.loop/evaluated-sources
                              (turn/evaluate-sources
                               {:seon.turn.loop/cluster handle
                                :seon.sci.eval/ctx ctx
                                :seon.agent/id "caller"
                                :seon.cluster.eval/ordinal 0
                                :seon.ns/name 'my.agents.caller
                                :seon.cluster.reply/sources
                                [{:seon.cluster.eval/source "(my.program/no-such-function 1)"}
                                 {:seon.cluster.eval/source "(my.task/no-such-function 1)"}]})))]
       (is (= 2 (count evaluations)))
       (is (not-any? #(str/includes? (str (:seon.cluster.eval/diagnostics %)) "Not admitted to the program")
                     evaluations)
           "a form that declares nothing has nothing to admit")))))

(deftest a-branch-operation-is-supplied-its-context-from-the-world
  ;; Lanes, 2026-09-28: `(my.agent/retire-branch! {...})` from MCP refused
  ;; its argument count: its context's store and routing were no world's
  ;; members. The execution's environment supplies them by schema name.
  (support/with-database
   (fn [connection]
     (let [execution (support/execution-handle connection)
           request (support/code-request
                    {:seon.batch/world (world/agent-world execution {:seon.agent/id "branch-lister"})
                     :seon.batch/observe observe/observe
                     :seon.batch/start-ns 'user
                     :seon.batch/text "(my.agent/branches)"})
           entry (first (:seon.batch/entries (seon.batch/evaluate-batch (dissoc request :seon.batch/settle))))]
       (is (contains? (:seon.batch/value entry) :my.agent/roster)
           "the one-argument call answers the store's roster")))))

(deftest a-call-omitting-its-database-sees-the-forms-own-write
  ;; Owner ruling 2026-09-28: no world holds a database value. A call that
  ;; omits its database is supplied its world connection's current value, so
  ;; within one evaluation a write is seen by the next call.
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "sees-own-write"))
     (let [request (support/code-request
                    {:seon.batch/world (world/agent-world {:seon.db/connection connection :seon.cluster/name "default"
                                                           :seon.sci.eval/base-ctx (sci.eval/base-ctx (db/db connection))}
                                                          {:seon.agent/id "sees-own-write"})
                     :seon.batch/observe observe/observe
                     :seon.batch/start-ns 'user
                     :seon.batch/text "(do (my.agent/settings! {:seon.config.eval/time-limit-ms 4321}) (my.agent/settings))"})
           entry (first (:seon.batch/entries (seon.batch/evaluate-batch (dissoc request :seon.batch/settle))))]
       (is (= 4321 (:seon.config.eval/time-limit-ms (:seon.batch/value entry)))
           "the read after the write, omitting its database, sees the write")))))

(deftest a-schema-key-changed-since-the-run-read-it-is-refused-at-the-writer
  ;; Root ruling 2026-09-28: the writer's stale guard covers the schema keys a
  ;; batch registers as well as its definitions (the old path's row writer
  ;; checked both; the fold's guard checked only definitions).
  (support/with-database
   (fn [connection]
     (let [request (support/code-request
                    {:seon.batch/world (world/agent-world {:seon.db/connection connection :seon.cluster/name "default"
                                                           :seon.sci.eval/base-ctx (sci.eval/base-ctx (db/db connection))}
                                                          {:seon.agent/id "stale-schema-writer"})
                     :seon.batch/observe observe/observe
                     :seon.batch/start-ns 'user
                     :seon.batch/text "(seon.schema/register! :stale.schema/label :string)"})
           batch (seon.batch/evaluate-batch (dissoc request :seon.batch/settle))
           _ (is (contains? (get-in batch [:seon.batch/program :schema-keys]) :stale.schema/label)
                 "the batch registers the key")
           _ (support/transacted! connection (support/schema-entities @connection [[:stale.schema/label :int]]))
           refused (turn.batch/program-facts request (db/db connection) (:seon.batch/program batch))]
       (is (= [[:seon.schema/key :stale.schema/label]] (:seon.error/offending refused))
           "the writer refuses a schema key another writer changed since the basis")))))

(deftest a-submitted-form-is-held-for-the-reply-under-its-namespace-lookup-ref
  ;; The phase-failure arm of `resume-turn` holds each planned form for the
  ;; reply by `fold-source`; `with-saved` refuses a namespace map with no
  ;; `:db/id`, so the arm's own failure replaced the saved refusal.
  (let [planned {:seon.cluster.eval/ordinal 0 :seon.cluster.eval/source "(+ 1 1)"
                 :seon.cluster.eval/ns {:seon.ns/name 'user}}
        report {:seon.agent/id "held-form" :seon.turn.work/situation :call
                :seon.turn.loop/forms-run 1 :seon.turn.loop/outcome :error}
        held (#'turn/fold-source planned)]
    (is (= [:seon.ns/name 'user] (:seon.cluster.eval/ns held)))
    (is (= report (#'turn/with-saved report {0 held} []))
        "the contract admits the held form; a settlement that saved none adds nothing")))

(defn- commits-since
  "The commit ids on `connection`'s head after commit `before`, walking parents."
  [connection before]
  (loop [head (versioning/commit-as-db connection (versioning/commit-id @connection)) found []]
    (let [cid (versioning/commit-id head)]
      (if (or (= cid before) (nil? head))
        found
        (recur (versioning/commit-as-db connection (first (versioning/parent-commit-ids head)))
               (conj found cid))))))

(defn- counted-request
  "[reply transactions-counted commits-made] of `code` run through `seon.mcp/call`."
  [running agent-id connection mode code]
  (let [before (versioning/commit-id @connection)
        reply (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                        "arguments" {"cluster" running "agent" agent-id
                                                                     "mode" mode "code" code}})))]
    [(:seon.cost/transactions (support/mcp-request reply)) (count (commits-since connection before))]))

(deftest a-replys-transactions-are-its-durable-commits
  (support/with-running-agent
   (fn [running agent-id connection]
     (counted-request running agent-id connection "sci" ":ping")
     (is (= [1 1] (counted-request running agent-id connection "sci" ":ping")) "an SCI ping commits its recording")
     (is (= [1 1] (counted-request running agent-id connection "jvm" "(+ 1 2)")) "a JVM request commits its recording")
     (let [[counted made] (counted-request running agent-id connection "sci"
                                           "(in-ns 'cc.one) (defn twice [x] (* 2 x))")]
       (is (= made counted) "a defn's reply counts the commits its branch advanced")))))
