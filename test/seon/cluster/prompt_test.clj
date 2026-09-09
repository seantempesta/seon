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
            [seon.cluster.agent :as agent]
            [seon.cluster.prompt :as prompt]
            [seon.render :as render]
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
                      :seon.cluster.agent/id "walker"
                      :seon.ns/name 'my.agents.walker
                      :seon.cluster.reply/text source
                      :seon.sci.admit/caps caps})
            prepared (turn/record-evaluated-tx
                       {:seon.turn.loop/cluster handle
                        :seon.db/db @connection
                        :seon.turn/id run-id
                        :seon.turn/agent [:seon.cluster.agent/id "walker"]
                        :seon.turn/starting-ns [:seon.ns/name 'my.agents.walker]
                        :seon.turn/reply source
                        :seon.turn/opened-at (:seon.turn/opened-at preview)
                        :seon.turn/closed-at (:seon.turn/closed-at preview)
                        :seon.turn.loop/evaluated-sources
                        (:seon.turn.loop/evaluated-sources preview)})]
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
      (db/transact! connection
                  (agent/creation-tx
                   {:seon.cluster.agent/id "walker"
                    :seon.cluster/name "prompt-walk"
                    :seon.ns/name 'my.agents.walker}))
      (db/transact! connection
                  [{:seon.cluster.message/id "walk-message"
                    :seon.cluster.message/to
                    [:seon.cluster.agent/id "walker"]
                    :seon.cluster.message/content "inspect this walk"
                    :seon.cluster.message/at (Date. 1700000000000)}])
      (let [ctx (support/fork-cluster-ctx connection)]
        (record-evaluation! connection ctx "opening-history"
                            "(seon.db/pull [:seon.cluster.message/content] [:seon.cluster.message/id \"walk-message\"])")
      (db/transact! connection
                  [{:seon.turn/id "walk-run"
                    :seon.turn/agent
                    [:seon.cluster.agent/id "walker"]
                    :seon.turn/trigger
                    [:seon.cluster.message/id "walk-message"]
                    :seon.turn/opened-at (Date. 1700000001000)}])
        (body connection ctx)))))

(defn- request
  [connection ctx]
  {:seon.turn/id "walk-run"
   :seon.cluster.agent/id "walker"
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
       (db/transact! connection
                     [{:seon.cluster.message/id "later"
                       :seon.cluster.message/to [:seon.cluster.agent/id "walker"]
                       :seon.cluster.message/content "not evaluated yet"
                       :seon.cluster.message/at (Date. 1700000002000)}])
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
       (db/transact! connection [{:seon.turn/id "walk-run"
                                 :seon.turn/closed-at (Date.)}])
       (record-evaluation! connection ctx "second-history" "(str \"SECOND-EVALUATION\")")
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection (request connection ctx)))]
         (is (str/starts-with? after before))
         (is (str/includes? after "inspect this walk"))
         (is (str/includes? after "SECOND-EVALUATION")))))))

(deftest basis-only-transactions-do-not-append-history
  (planted
   (fn [connection ctx]
     (let [before (:seon.cluster.prompt/text
                   (prompt/prompt @connection
                                  (request connection ctx)))]
       (db/transact! connection [])
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection
                                   (request connection ctx)))]
         (is (= before after)
             "a basis-only transaction creates no new history observation")
         (is (not (str/includes? after ";; REPL state"))
             "the deleted volatile suffix is not reconstructed"))))))

(deftest a-held-run-without-a-trigger-refuses
  (support/with-database
    (fn [connection]
      (support/seed-cluster! connection "no-trigger")
      (db/transact! connection
                  (agent/creation-tx
                   {:seon.cluster.agent/id "walker"
                    :seon.cluster/name "no-trigger"
                    :seon.ns/name 'my.agents.walker}))
      (db/transact! connection
                  [{:seon.turn/id "walk-run"
                    :seon.turn/agent
                    [:seon.cluster.agent/id "walker"]
                    :seon.turn/opened-at (Date.)}])
      (testing "the custody invariant remains independent of presentation"
        (is (= :seon.cluster.prompt/no-trigger
               (:seon.cluster.prompt/rule
                (support/refusal-data
                 #(prompt/prompt @connection
                                 (request connection
                                          (support/fork-cluster-ctx connection)))))))))))

(deftest prompt-budget-is-informational-and-does-not-compact
  (planted
   (fn [connection ctx]
     (db/transact! connection
                   [{:seon.cluster.agent/id "walker"
                     :seon.agent/settings {:seon.config.ai/prompt-token-budget 3}}])
     (let [distances (atom [])
           acquire (fn [render-request]
                     (let [distance (:seon.render/distance render-request)]
                       (swap! distances conj distance)
                       {:seon.cluster.prompt/text
                        (if (= 1 distance) "fits nine" (apply str (repeat 40 "x")))
                        :seon.render.history/segments
                        [(if (= 1 distance)
                           "fits nine"
                           (apply str (repeat 40 "x")))]
                        :seon.db/db (:seon.db/db render-request)}))]
       (with-redefs [render/acquire-context! acquire]
         (let [compacted (prompt/prompt @connection
                                        (request connection ctx))]
           (is (= [2] @distances))
           (is (= (apply str (repeat 40 "x"))
                  (:seon.cluster.prompt/text compacted)))
           (is (= :seon.ai.tokens/over
                  (get-in compacted [:seon.ai.tokens/budget-report
                                     :seon.ai.tokens/verdict])))))
       (reset! distances [])
       (with-redefs [render/acquire-context!
                     (fn [render-request]
                       (swap! distances conj (:seon.render/distance render-request))
                       {:seon.cluster.prompt/text (apply str (repeat 40 "x"))
                        :seon.render.history/segments
                        [(apply str (repeat 40 "x"))]
                        :seon.db/db (:seon.db/db render-request)})]
         (let [complete (prompt/prompt @connection
                                       (request connection ctx))]
           (is (= [2] @distances))
           (is (= (apply str (repeat 40 "x"))
                  (:seon.cluster.prompt/text complete)))
           (is (= 3 (get-in complete [:seon.ai.tokens/budget-report
                                      :seon.config.ai/prompt-token-budget])))))))))

(defn- recorded-usage-tx
  "Facts one settled attempt already commits: the exact prompt characters
  on the run's capture, and the provider's own count on the attempt."
  [model ordinal characters provider-tokens]
  (let [run-id (str "usage-run-" ordinal)]
    [{:seon.turn/id run-id
      :seon.turn/agent [:seon.cluster.agent/id "walker"]
      :seon.turn/opened-at (Date. (+ 1700000100000 (* 1000 ordinal)))}
     {:seon.context.capture/id (str run-id "-context-1")
      :seon.context.capture/run [:seon.turn/id run-id]
      :seon.context.capture/basis-t 1
      :seon.context.capture/prompt (apply str (repeat characters "x"))
      :seon.ai.tokens/characters characters}
     {:seon.ai.attempt/id (str run-id "-0")
      :seon.turn/_attempts [:seon.turn/id run-id]
      :seon.ai.attempt/ordinal 0
      :seon.ai.attempt/at (Date. (+ 1700000100000 (* 1000 ordinal)))
      :seon.ai/endpoint "https://example.invalid/v1/chat/completions"
      :seon.ai/model model
      :seon.ai.attempt/usage-edn
      (pr-str {"prompt_tokens" provider-tokens
               "completion_tokens" 1
               "total_tokens" (inc provider-tokens)})}]))

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
       (db/transact! connection
                     [{:seon.cluster.agent/id "walker"
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
         (db/transact! connection
                       (recorded-usage-tx model ordinal 32000 10000)))
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
             (is (= text (:seon.cluster.prompt/text result)))
             (is (= :seon.ai.tokens/over
                    (get-in result [:seon.ai.tokens/budget-report
                                    :seon.ai.tokens/verdict])))
             (is (= 106 (get-in result [:seon.ai.tokens/budget-report
                                        :seon.ai.tokens/estimated])))
             (is (= :seon.ai.tokens/observed
                    (get-in result [:seon.ai.tokens/budget-report
                                    :seon.ai.tokens/basis]))
                 "the report names which basis measured it"))))))))
