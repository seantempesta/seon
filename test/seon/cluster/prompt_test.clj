(ns seon.cluster.prompt-test
  "Recurring acceptance for the prompt's append-only REPL history."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.ai.tokens :as tokens]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.print :as print]
            [seon.cluster.agent :as agent]
            [seon.cluster.prompt :as prompt]
            [seon.render :as render]
            [seon.repl :as repl]
            [seon.turn :as turn]

            [seon.blob :as blob]
            [seon.test-support :as support])
  (:import [java.util Date]))

(def ^:private caps
  (assoc (config/result-caps (support/effective-config))
         :seon.config.eval.result/max-depth 12
         :seon.config.eval.result/max-collection 64
         :seon.config.eval.result/max-string 4096
         :seon.config.eval.result/max-source 65536
         :seon.config.eval.result/max-nodes 4096))

(defn- record-evaluation!
  [connection ctx run-id source]
  (let [handle (support/cluster-handle
                {:seon.db/connection connection
                 :seon.cluster/name "prompt-walk"
                 :seon.db.process/id cluster/boot-process-identity
                 :seon.sci.eval/ctx ctx})]
    (try
      (let [preview (turn/preview-sources
                     {:seon.turn.loop/cluster handle
                      :seon.db/db @connection
                      :seon.sci.eval/ctx ctx
                      :seon.agent/id "walker"
                      :seon.ns/name 'my.agents.walker
                      :seon.cluster.reply/text source
                      :seon.sci.admit/caps caps})
            prepared (turn/record-evaluated-tx
                       {:seon.turn.loop/cluster handle :seon.db/db @connection :seon.turn/id run-id :seon.turn/agent [:seon.agent/id "walker"] :seon.turn/starting-ns [:seon.ns/name 'my.agents.walker] :seon.turn/reply source :seon.turn/opened-tx "datomic.tx" :seon.turn/closed-tx "datomic.tx" :seon.turn.loop/evaluated-sources (:seon.turn.loop/evaluated-sources preview)})]
        (let [result (blob/with-publication!
                      connection (:seon.blob/staged-writes prepared)
                      #(db/transact! connection (:seon.db/tx-data prepared)))]
          (when (:seon.error/kind result)
            (throw (ex-info (str "Fixture evaluation was not recorded: " (pr-str result)) result)))
          result))
      (finally
        (doseq [key [:seon.cluster.wake/channel :seon.render/context-channel
                    :seon.turn.loop/completion]]
          (when-let [channel (get handle key)] (async/close! channel)))))))

(defn- planted
  [body]
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "prompt-walk")
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name "prompt-walk"})
      (support/transacted! connection
                         (agent/creation-tx
                          {:seon.agent/id "walker"
                           :seon.cluster/name "prompt-walk"
                           :seon.ns/name 'my.agents.walker}))
      (support/transacted! connection
                         [{:seon.message/id "walk-message" :seon.message/to [:seon.agent/id "walker"] :seon.message/content "inspect this walk" :seon.message/inbox [:seon.agent/id "walker"]}])
      (let [ctx (support/fork-cluster-ctx connection)]
        (record-evaluation! connection ctx "opening-history"
                            "(seon.db/pull [:seon.message/content] [:seon.message/id \"walk-message\"])")
      (support/transacted! connection
                         [{:seon.turn/id "walk-run" :seon.turn/agent [:seon.agent/id "walker"] :seon.turn/trigger [:seon.message/id "walk-message"] :seon.turn/opened-tx "datomic.tx"}])
        (body connection ctx)))))

(defn- request
  [connection ctx]
  {:seon.turn/id "walk-run"
   :seon.agent/id "walker"
   :seon.db/connection connection
   :seon.sci.admit/caps caps
   :seon.sci.eval/ctx ctx
   :seon.sci.eval/time-limit-ms (* 1000 support/event-backstop-seconds)
   :seon.render/profile (render/agent-render-profile (config/defaults))
   :seon.config/on-core-error :panic})

(deftest prompt-prices-the-exact-retained-history
  (planted
   (fn [connection ctx]
     (let [rendered (prompt/prompt @connection (request connection ctx))
           text (:seon.cluster.prompt/text rendered)
           contributions (:seon.context/contributions rendered)]
       (is (seq text))
       (is (str/includes? text "inspect this walk"))
       ;; THE OPENING DOES NOT SPEND THE BUDGET (turn PRD 14, ruled in
       ;; 97d1f69e0). `episode-runs` counts a turn only when it carries a
       ;; provider attempt, or a reply whose datom arrived LATER than the
       ;; turn's own identity. This fixture plants exactly the two shapes
       ;; that are not provider turns: `opening-history`, whose reply is
       ;; frozen in the same transaction as its identity, and `walk-run`,
       ;; open with neither attempt nor reply. Probed on the fixture:
       ;; identity tx 536870925 = reply tx 536870925, zero attempts,
       ;; `episode-runs` 0. An untouched budget is the ruled answer.
       (is (str/ends-with? text "turns left: 100 of 100")
           "neither the frozen opening nor an open turn spends a provider turn")
       (is (= :frame (:seon.render.block/name (last contributions))))
       (is (= text (apply str (map :seon.context.contribution/text contributions))))
       (is (= (range (count contributions))
              (map :seon.context.contribution/position contributions)))
       (is (= (get-in rendered [:seon.ai.tokens/budget-report :seon.ai.tokens/estimated])
              (reduce + (map :seon.context.contribution/tokens contributions))))
       (is (every? #(= (context/contribution-hash (:seon.context.contribution/text %))
                       (:seon.context.contribution/hash %)) contributions))
       (is (not (str/includes? text ";; REPL state")))))))

(deftest unobserved-messages-do-not-rewrite-stored-history
  (planted
   (fn [connection ctx]
     (let [before (:seon.cluster.prompt/text
                   (prompt/prompt @connection (request connection ctx)))]
       (support/transacted! connection
                            [{:seon.message/id "later" :seon.message/to [:seon.agent/id "walker"] :seon.message/content "not evaluated yet" :seon.message/inbox [:seon.agent/id "walker"]}])
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection (request connection ctx)))]
         (is (= before after) "a stored observation changes only through a later evaluation")
         (is (not (str/includes? after "not evaluated yet"))))))))

(deftest identical-context-does-not-depend-on-a-retained-prompt-cache
  (planted
   (fn [connection ctx]
     (let [database @connection
           before (prompt/prompt database (request connection ctx))
           basis (db/basis-t database)]
       (reset! (render/shared-cache ctx) {})
       (let [after (prompt/prompt database (request connection ctx))]
         (is (seq (:seon.cluster.prompt/text before)))
         (is (= (:seon.cluster.prompt/text before)
                (:seon.cluster.prompt/text after)))
         (is (= basis (db/basis-t @connection))))))))

(deftest later-evaluations-preserve-the-opening-history
  (planted
   (fn [connection ctx]
     (let [before (:seon.cluster.prompt/text
                   (prompt/prompt @connection (request connection ctx)))]
       (let [result (db/transact! connection [[:db/add [:seon.turn/id "walk-run"] :seon.turn/closed-tx "datomic.tx"]])]
         (is (not (:seon.error/kind result)) (pr-str result)))
       (record-evaluation! connection ctx "second-history" "(str \"SECOND-EVALUATION\")")
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection (request connection ctx)))]
         (is (= after before))
         (is (str/includes? after "inspect this walk"))
         (is (not (str/includes? after "SECOND-EVALUATION"))))))))

(deftest basis-only-transactions-do-not-append-history
  (planted
   (fn [connection ctx]
     (let [before (:seon.cluster.prompt/text
                   (prompt/prompt @connection
                                  (request connection ctx)))]
       (support/transacted! connection [])
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection
                                   (request connection ctx)))]
         (is (= before after)
             "a basis-only transaction creates no new history observation")
         (is (not (str/includes? after ";; REPL state"))
             "the deleted volatile suffix is not reconstructed"))))))

(deftest a-held-turn-can-render-without-a-message-trigger
  (planted
   (fn [connection ctx]
     (let [result (db/transact!
                   connection
                   [[:db/add [:seon.turn/id "walk-run"] :seon.turn/closed-tx "datomic.tx"]
                    {:seon.turn/id "plan-wake"
                     :seon.turn/agent [:seon.agent/id "walker"]
                     :seon.turn/opened-tx "datomic.tx"}])
           rendered (prompt/prompt @connection
                                   (assoc (request connection ctx)
                                          :seon.turn/id "plan-wake"))]
       (is (not (:seon.error/kind result)) (pr-str result))
       (is (not (:seon.error/kind rendered)) (pr-str rendered))
       (is (str/includes? (:seon.cluster.prompt/text rendered)
                          "inspect this walk"))))))

(defn- history-unit
  [ordinal rendered]
  {:seon.render.history/call-id [[:seon.cluster.eval/id (str "unit-" ordinal)]]
   :seon.render.history/subject [:seon.cluster.eval/id (str "unit-" ordinal)]
   :seon.render.history/bytes rendered})

(defn- planted-units
  "Ten units of about 110 characters each, each one nameable in the output."
  []
  (mapv (fn [ordinal]
          (history-unit ordinal
                        (str "my.agents.walker=> (unit-" ordinal " "
                             (apply str (repeat 80 \x)) ")")))
        (range 10)))

(deftest a-budget-smaller-than-the-history-keeps-whole-newest-units
  ;; S11 acceptance (a). SELECTION, NOT A CUT: every retained unit is whole —
  ;; its own result was bounded once already, by the value renderer at
  ;; evaluation time — and the omission is ONE elision value with a count, a
  ;; coordinate and a requery form, never a character offset inside a form.
  (let [calibration (tokens/prior-calibration 3.2)
        units (planted-units)
        selection (prompt/select units 200 calibration "walker")
        retained (:seon.render.history/units selection)
        elision (:seon.print/elision selection)
        text (prompt/compose selection)]
    (is (seq retained))
    (is (< (count retained) (count units)) "the budget dropped something")
    (is (= (mapv :seon.render.history/bytes (take-last (count retained) units))
           (mapv :seon.render.history/bytes retained))
        "the units kept are the NEWEST, oldest dropped first")
    (doseq [unit retained]
      (is (str/includes? text (:seon.render.history/bytes unit))
          "every retained unit appears whole"))
    (doseq [unit (drop-last (count retained) units)]
      (is (not (str/includes? text (:seon.render.history/bytes unit)))
          "a dropped unit contributes nothing"))
    (is (some? elision) "the omission is named")
    (is (= (- (count units) (count retained)) (:seon.print/omitted elision)))
    (is (= :evaluations (:seon.print/elision-unit elision)))
    (is (= :seon.config.ai/prompt-token-budget (:seon.print/bound-by elision)))
    (is (= (- (count units) (count retained))
           (:seon.render.data/next-offset elision))
        "next-offset IS the oldest surviving position in the requeried sequence")
    (is (= (count units) (:seon.render.data/total elision)))
    (is (seq (:seon.print/requery-form elision)) "the omitted units are askable")
    (is (str/starts-with? text (print/render-elision-ai elision))
        "exactly one elision value, named first")
    (is (not (str/includes? text ":seon.print/prefix"))
        "no character cut: a mid-form fit would carry fit-text's prefix")
    (is (= 1 (count (re-seq #":seon.print/elision-unit :evaluations" text)))
        "exactly one elision value in the composed history")
    (is (<= (tokens/estimate text (tokens/prior-calibration 3.2)) 200)
        "and the composition, elision included, fits the budget"))
  (testing "a cut never omits its whole subject: the newest unit always survives"
    (let [calibration (tokens/prior-calibration 3.2)
          units (planted-units)
          selection (prompt/select units 1 calibration "walker")]
      (is (= 1 (count (:seon.render.history/units selection))))
      (is (= (:seon.render.history/bytes (last units))
             (:seon.render.history/bytes
              (first (:seon.render.history/units selection))))))))

(deftest a-budget-larger-than-the-history-composes-the-same-bytes
  ;; S11 acceptance (b). Composition replaces the join, byte for byte.
  (planted
   (fn [connection ctx]
     (let [acquired (render/acquire-context!
                     (assoc (request connection ctx)
                            :seon.db/db @connection
                            :seon.render/distance 2))
           units (vec (:seon.render.history/entries acquired))
           calibration (tokens/prior-calibration 3.2)
           selection (prompt/select units 1000000 calibration "walker")]
       (is (seq units))
       (is (nil? (:seon.print/elision selection)) "nothing was dropped")
       (is (= (count units) (count (:seon.render.history/units selection))))
       (is (= (:seon.cluster.prompt/text acquired)
              (prompt/compose selection))
           "the composed history is byte-identical to the acquired join")
       (is (= (:seon.cluster.prompt/text acquired)
              (apply str (:seon.render.history/segments acquired))))))))

(deftest every-unit-is-priced-from-its-own-shown-text
  ;; S11 change (1): the estimate is DERIVED at composition, never stored.
  (let [calibration (tokens/prior-calibration 3.2)
        units (planted-units)
        selection (prompt/select units 1000000 calibration "walker")]
    (doseq [unit (:seon.render.history/units selection)]
      (is (= (tokens/estimate (:seon.render.history/bytes unit) calibration)
             (:seon.ai.tokens/estimate unit))))
    (is (every? #(nil? (:seon.ai.tokens/estimate %)) units)
        "the walk's own units carry no estimate, so nothing stores one")))

(deftest the-prompt-budget-selects-and-still-reports-its-verdict
  (planted
   (fn [connection ctx]
     (support/transacted! connection
                          [{:seon.agent/id "walker"
                            :seon.agent/settings
                            {:seon.config.ai/prompt-token-budget 3}}])
     (let [distances (atom [])
           unit-text (apply str (repeat 40 "x"))
           acquire (fn [render-request]
                     (swap! distances conj (:seon.render/distance render-request))
                     {:seon.cluster.prompt/text (str unit-text "\n\n" unit-text)
                      :seon.render.history/entries
                      [(history-unit 0 unit-text) (history-unit 1 unit-text)]
                      :seon.render.history/segments
                      [unit-text (str "\n\n" unit-text)]
                      :seon.db/db (:seon.db/db render-request)})]
       (with-redefs [render/acquire-context! acquire]
         (let [selected (prompt/prompt @connection (request connection ctx))
               text (:seon.cluster.prompt/text selected)
               contributions (:seon.context/contributions selected)]
           (is (= [2] @distances))
           (is (str/includes? text unit-text) "the newest unit survives whole")
           (is (str/includes? text ":seon.print/elision-unit :evaluations")
               "and the older one is named as an elision")
           (is (str/ends-with? text (repl/frame @connection "walker"))
               "the turn frame still closes the prompt")
           (is (= text (apply str (map :seon.context.contribution/text
                                       contributions)))
               "the contributions still decompose the exact prompt")
           (is (= 3 (get-in selected [:seon.ai.tokens/budget-report
                                      :seon.config.ai/prompt-token-budget])))
           (is (keyword? (get-in selected [:seon.ai.tokens/budget-report
                                           :seon.ai.tokens/verdict]))
               "budget-report stays the verdict on the composed result")))))))

(deftest the-history-path-never-refits-a-rendered-unit
  ;; S11 acceptance (d). ASSERTED AS BEHAVIOUR, NOT AS REACH, and the reason
  ;; is itself the finding: `seon.render/render-call` selects its producer at
  ;; call time, and the program graph records an edge from it to EVERY
  ;; declared AI producer — including `seon.render.value/render-ai`, which
  ;; owns the one legitimate clipping spot. So `seon.print/fit-text` is
  ;; reachable from any render call by construction, and a reach assertion
  ;; would either be vacuous or forbid the one cut that is supposed to happen.
  ;; What (d) actually forbids is a SECOND fit of a string a renderer already
  ;; produced, and that is observable: a character cut leaves `fit-text`'s
  ;; own fields — `:seon.print/prefix` and `:seon.print/elision-unit
  ;; :characters` — in the composed bytes.
  (let [calibration (tokens/prior-calibration 3.2)
        unit (history-unit 0 (str "my.agents.walker=> (long-one)\n"
                                  (apply str (repeat 4000 \y))))
        selection (prompt/select [unit] 1 calibration "walker")
        text (prompt/compose selection)]
    (is (str/includes? text (:seon.render.history/bytes unit))
        "the rendered unit crosses composition whole, under any budget")
    (is (not (str/includes? text ":seon.print/prefix"))
        "no retained prefix: nothing re-admitted the rendered string")
    (is (not (str/includes? text ":seon.print/elision-unit :characters"))
        "and no character cut fired on the history path")))

(defn- recorded-usage-tx
  "Facts one settled attempt already commits: the exact prompt characters
  on the run's capture, and the provider's own count on the attempt."
  [model ordinal characters provider-tokens]
  (let [run-id (str "usage-run-" ordinal)]
    [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id "walker"] :seon.turn/opened-tx "datomic.tx"
      :seon.turn/attempts [(str run-id "-attempt")]} 
     {:seon.context.capture/id (str run-id "-context-1")
      :seon.context.capture/run [:seon.turn/id run-id]
      :seon.context.capture/basis-t 1
      :seon.context.capture/prompt (apply str (repeat characters "x"))
      :seon.ai.tokens/characters characters}
     {:db/id (str run-id "-attempt")
      :seon.ai.attempt/id (str run-id "-0")
      :seon.ai.attempt/settings-edn "{}"
      :seon.ai.attempt/ordinal 0
      :seon.ai.attempt/at (Date. (+ 1700000100000 (* 1000 ordinal)))
      :seon.ai/endpoint "https://example.invalid/v1/chat/completions"
      :seon.ai/model model
      :seon.ai.usage/prompt-tokens provider-tokens
      :seon.ai.usage/completion-tokens 1
      :seon.ai.usage/total-tokens (inc provider-tokens)
      :seon.ai.attempt/usage-edn
      (pr-str {"prompt_tokens" provider-tokens
               "completion_tokens" 1
               "total_tokens" (inc provider-tokens)})}]))

(deftest calibration-uses-the-agents-recent-attempts-and-config-prior
  (planted
   (fn [connection ctx]
     (let [model (db/q '[:find ?model . :where [_ :seon.config.ai/model ?model]] @connection)
           foreign (assoc-in (recorded-usage-tx model 99 9000 1000)
                             [0 :seon.turn/agent] [:seon.agent/id "other"])]
       (support/transacted! connection
                            (agent/creation-tx {:seon.agent/id "other"
                                                :seon.cluster/name "prompt-walk"
                                                :seon.ns/name 'my.agents.other}))
       (support/transacted! connection
                            [{:seon.agent/id "walker"
                              :seon.agent/settings {:seon.config.ai/chars-per-token-prior 4.5}}])
       (support/transacted! connection foreign)
       (is (= 9.0 (:seon.ai.tokens/chars-per-token
                    (prompt/agent-calibration @connection "other" model))))
       (is (= 4.5 (:seon.ai.tokens/chars-per-token
                    (prompt/agent-calibration @connection "walker" model)))
           "another agent's usage cannot replace this agent's configured prior")
       (doseq [ordinal (reverse (range 12))]
         (support/transacted! connection
                              (recorded-usage-tx model ordinal
                                                 (if (< ordinal 2) 6400 2800) 1000)))
       (support/transacted! connection (recorded-usage-tx model 100 10000 0))
       (let [calibration (prompt/agent-calibration @connection "walker" model)
             rendered (prompt/prompt @connection (dissoc (request connection ctx) :seon.turn/id))]
         (is (= :seon.ai.tokens/observed (:seon.ai.tokens/basis calibration)))
         (is (= 10 (:seon.ai.tokens/sample-count calibration)))
         (is (= 2.8 (:seon.ai.tokens/chars-per-token calibration))
             "timestamps order the window; old and unbilled attempts do not distort it")
         (is (= 2.8 (get-in rendered [:seon.ai.tokens/budget-report :seon.ai.tokens/chars-per-token]))
             "the prompt budget uses the same agent-scoped measurement"))))))

(deftest provider-calibration-reports-over-budget-without-refusing
  ;; THE CLASS: the guard was correct against a measurement that was
  ;; not. `chars/4` ran ~23% low against DeepSeek, so prompts left the
  ;; process up to 3,059 tokens over a declared 32,768 with no refusal
  ;; (whole-system-arc observer, 2026-08-08). The budget now measures in
  ;; the units the provider bills in, fitted to this model's own
  ;; recorded usage.
  (planted
   (fn [connection ctx]
     (let [model (db/q '[:find ?model .
                         :where [_ :seon.config.ai/model ?model]]
                       @connection)]
       (support/transacted! connection
                            [{:seon.agent/id "walker"
                              :seon.agent/settings {:seon.config.ai/prompt-token-budget 100}}])
       (testing "with no recorded usage the measured prior is named"
         (let [calibration (prompt/model-calibration @connection model)]
           (is (= :seon.ai.tokens/shipped-prior
                  (:seon.ai.tokens/basis calibration)))
           (is (= 17 (:seon.ai.tokens/sample-count calibration)))
           (is (not (contains? calibration
                              :seon.ai.tokens/relative-error)))))
       ;; three settled attempts at a real 3.2 characters per token
       (doseq [ordinal [1 2 3]]
         (let [result (db/transact! connection
                                    (recorded-usage-tx model ordinal 32000 10000))]
           (is (not (:seon.error/kind result)) (pr-str result))))
       (testing "the calibration is fitted to those committed facts"
         (let [calibration (prompt/model-calibration @connection model)]
           (is (= :seon.ai.tokens/observed
                  (:seon.ai.tokens/basis calibration)))
           (is (= 3 (:seon.ai.tokens/sample-count calibration)))
           (is (= 3.2 (:seon.ai.tokens/chars-per-token calibration)))))
       ;; 340 characters: chars/4 says 85 and fits the 100-token budget;
       ;; the provider would count 106 and would not
       (let [text (apply str (repeat 340 "x"))]
         (with-redefs [render/acquire-context!
                       (fn [render-request]
                         {:seon.cluster.prompt/text text
                          :seon.db/db (:seon.db/db render-request)})]
           (let [result (prompt/prompt @connection
                                       (request connection ctx))]
             (is (= 106 (tokens/estimate text))
                 "the measured prior catches the first turn too")
             (is (= (str text "\n\n" (repl/frame @connection "walker"))
                    (:seon.cluster.prompt/text result)))
             (is (= :seon.ai.tokens/over
                    (get-in result [:seon.ai.tokens/budget-report
                                    :seon.ai.tokens/verdict])))
             (is (= (tokens/estimate (:seon.cluster.prompt/text result))
                    (get-in result [:seon.ai.tokens/budget-report
                                    :seon.ai.tokens/estimated])))
             (is (= :seon.ai.tokens/observed
                    (get-in result [:seon.ai.tokens/budget-report
                                    :seon.ai.tokens/basis]))
                 "the report names which basis measured it"))))))))
