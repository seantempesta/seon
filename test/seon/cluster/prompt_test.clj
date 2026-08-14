(ns seon.cluster.prompt-test
  "Recurring acceptance for the prompt's append-only REPL history."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow.core]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.ai.tokens :as tokens]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.cluster.prompt :as prompt]
            [seon.flow :as flow]
            [seon.render :as render]
            [seon.render.walk :as render.walk]
            [seon.render.web :as web]
            [seon.test-support :as support])
  (:import [java.util Date]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (support/environment "seon.cluster.prompt-test")))


(def ^:private caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

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
      (db/transact! connection
                  [{:seon.cluster.run/id "walk-run"
                    :seon.cluster.run/agent
                    [:seon.cluster.agent/id "walker"]
                    :seon.cluster.run/trigger
                    [:seon.cluster.message/id "walk-message"]
                    :seon.cluster.run/opened-at (Date. 1700000001000)}
                   {:seon.cluster.agent/id "walker"
                    :seon.cluster.agent/run
                    [:seon.cluster.run/id "walk-run"]}])
      (let [context-channel (async/chan)
            render-channel (async/chan (async/sliding-buffer 1))
            pages-channel (async/chan (async/sliding-buffer 1))
            stream-channel (async/chan (async/sliding-buffer 1))
            completion (async/promise-chan)
            interest (atom :all)
            ctx (support/fork-cluster-ctx connection)
            graph
            (flow.core/create-flow
             {:procs
              {:seon.render.web/render
               {:proc
                (flow/var-process
                 #'web/render-step :io
                 {:seon.env/environment @test-environment
                  :seon.render.web/render-channel render-channel
                  :seon.render/context-channel context-channel
                  :seon.render.web/pages-channel pages-channel
                  :seon.render.web/registration (atom {})
                  :seon.render.web/latest-packages (atom {})
                  :seon.render.web/interest interest
                  :seon.render.web/completion completion
                  :seon.render.web/root-agent-id "walker"
                  :seon.cluster.loop/cluster
                  {:seon.db/connection connection
                   :seon.cluster.loop/stream-channel stream-channel
                   :seon.sci.admit/caps caps
                   :seon.sci.eval/ctx ctx
                   :seon.config.eval/time-limit-ms
                   (:seon.config.eval/time-limit-ms (config/defaults))
                   :seon.config/on-core-error :panic
                   :seon.cluster.run/process "prompt-test"}})}}
              :conns []
              :io-exec
              (cluster/projection-executor
               (:seon.sci.eval/projection-state ctx))})
            {:keys [report-chan error-chan]} (flow.core/start graph)]
        (async/go-loop [] (when (async/<! report-chan) (recur)))
        (async/go-loop [] (when (async/<! error-chan) (recur)))
        (try
          (flow.core/resume graph)
          (body connection context-channel)
          (finally
            (flow.core/stop graph)
            (async/<!! completion)))))))

(defn- request
  [connection context-channel]
  {:seon.cluster.run/id "walk-run"
   :seon.cluster.agent/id "walker"
   :seon.db/connection connection
   :seon.sci.admit/caps caps
   :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
   :seon.sci.eval/time-limit-ms 2000
   :seon.config/on-core-error :panic
   :seon.render/context-channel context-channel})

(defn- acquire-context
  [connection context-channel]
  (render/acquire-context!
   context-channel
   (assoc (request connection context-channel)
          :seon.db/db @connection
          :seon.render/distance 1)))

(deftest prompt-is-derived-append-only-repl-history
  (planted
   (fn [connection context-channel]
     (let [render-request (request connection context-channel)
           rendered (prompt/prompt @connection render-request)
           text (:seon.cluster.prompt/text rendered)
           entries (render.walk/history
                    (assoc render-request
                           :seon.db/db @connection
                           :seon.render.walk/lookup
                           [:seon.cluster.agent/id "walker"]
                           :seon.render/distance 2
                           :seon.render/captured-calls (atom {})))
           contribution (first (:seon.context/contributions rendered))
           contributions (:seon.context/contributions rendered)
           web-directory-contributions
           (filterv
            #(str/includes?
              (:seon.context.contribution/text %)
              "(dir (quote my.web))")
            contributions)
           removed-block-read
           (db/q '[:find ?block
                   :where
                   [?agent :seon.cluster.agent/id "walker"]
                   [?agent :seon.cluster.agent/blocks ?block]]
                 @connection)]
       (is (= text
              (apply str
                     (map :seon.context.contribution/text contributions))))
       (is (= (count entries) (count contributions)))
       (is (= (range (count contributions))
              (map :seon.context.contribution/position contributions)))
       (is (= (get-in rendered
                      [:seon.ai.tokens/budget-report
                       :seon.ai.tokens/estimated])
              (reduce +
                      (map :seon.context.contribution/tokens
                           contributions)))
           "per-entry costs reconcile exactly to the checked whole prompt")
       (is (every?
            (fn [entry]
              (= (context/contribution-hash
                  (:seon.context.contribution/text entry))
                 (:seon.context.contribution/hash entry)))
            contributions))
       (is (= :walk (:seon.render.block/name contribution)))
       (is (= 1 (count web-directory-contributions))
           "the full toolkit directory is priced from its consumer-fit bytes")
       (is (< 0
              (:seon.context.contribution/tokens
               (first web-directory-contributions))
              (get-in rendered
                      [:seon.ai.tokens/budget-report
                       :seon.ai.tokens/estimated]))
           "one directory contribution is bounded inside the whole prompt")
       (is (seq entries))
       (is (every? (comp seq :seon.render.history/bytes) entries))
       (is (str/includes? text "inspect this walk")
           "the triggering message is an entry in the agent's history")
       (is (str/includes?
            text
            "my.agents.walker=> (my.message/read \"walk-message\")"))
       (is (not (str/includes? text ";; (seon.render/walk"))
           "the deleted labeled-walk prompt is not reconstructed")
       (is (not (str/includes? text ";; REPL state"))
           "volatile database metadata is not a synthetic history entry")
       (is (= :seon.db/invalid-read
              (:seon.error/kind removed-block-read))
           "the deleted presentation-block attribute is not installed")
       (is (= :seon.db/attribute-not-installed
              (get-in removed-block-read
                      [:seon.error/data :seon.error/diagnostic-cause])))
       (is (pos?
            (count
             (db/q '[:find ?cost
                     :where
                     [?cost :seon.render.cost/estimated-tokens]]
                   @connection)))
           "the production prompt request records every newly rendered cost")))))

(deftest every-call-derives-the-current-basis
  (planted
   (fn [connection context-channel]
     (let [before (:seon.cluster.prompt/text
                   (prompt/prompt @connection
                                  (request connection context-channel)))]
       (db/transact! connection
                   [{:seon.cluster.message/id "later"
                     :seon.cluster.message/to
                     [:seon.cluster.agent/id "walker"]
                     :seon.cluster.message/content "new durable fact"
                     :seon.cluster.message/at (Date. 1700000002000)}])
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection
                                   (request connection context-channel)))]
         (is (not= before after))
         (is (str/includes? after "new durable fact")))))))

(deftest identical-context-reuses-retained-ai-render-bytes
  (planted
   (fn [connection context-channel]
     (let [render-ai! render/render-ai
           invocations (atom 0)]
       (with-redefs [render/render-ai
                     (fn [render-request]
                       (swap! invocations inc)
                       (render-ai! render-request))]
         (let [first-context
               (prompt/prompt @connection
                              (request connection context-channel))
               after-first @invocations
               second-context
               (prompt/prompt @connection
                              (request connection context-channel))]
           (is (= (:seon.cluster.prompt/text first-context)
                  (:seon.cluster.prompt/text second-context)))
           (is (pos? after-first))
           (is (= after-first @invocations)
               "an identical context performs zero second-pass renderer invocations")))))))

(deftest unchanged-acquisition-performs-zero-database-door-reads
  (planted
   (fn [connection context-channel]
     (acquire-context connection context-channel)
     ;; The first real context render records its costs and therefore advances
     ;; the connection once. Let retained dependency evidence observe that
     ;; unrelated transaction before measuring a genuinely unchanged basis.
     (acquire-context connection context-channel)
     (let [reads (atom 0)
           counted (fn [f]
                     (fn [& arguments]
                       (swap! reads inc)
                       (apply f arguments)))]
       (with-redefs [db/q (counted db/q)
                     db/pull (counted db/pull)
                     db/pull-many (counted db/pull-many)
                     db/entity (counted db/entity)
                     db/datoms (counted db/datoms)]
         (acquire-context connection context-channel))
       (is (zero? @reads)
           "unchanged acquisition returns retained bytes without a db read")))))

(deftest one-new-message-appends-exactly-one-entry
  (planted
   (fn [connection context-channel]
     (let [before (:seon.cluster.prompt/text
                   (acquire-context connection context-channel))
           appended (atom [])
           append web/append-history]
       (db/transact! connection
                     [{:seon.cluster.message/id "one-new-message"
                       :seon.cluster.message/to
                       [:seon.cluster.agent/id "walker"]
                       :seon.cluster.message/content "one appended entry"
                       :seon.cluster.message/at (Date. 1700000004000)}])
       (let [after (with-redefs [web/append-history
                                 (fn [entries observations]
                                   (let [result (append entries observations)]
                                     (swap! appended conj
                                            (- (count result) (count entries)))
                                     result))]
                     (:seon.cluster.prompt/text
                      (acquire-context connection context-channel)))]
         (is (str/starts-with? after before) "all prior bytes are retained")
         (is (= [1] @appended)
             "one new message crosses append with exactly one entry"))))))

(deftest a-second-run-replaces-the-opening-task-and-puts-current-task-last
  (planted
   (fn [connection context-channel]
     (let [opening (:seon.cluster.prompt/text
                    (prompt/prompt @connection
                                   (request connection context-channel)))]
       (db/transact!
        connection
        [{:seon.cluster.message/id "current-task"
          :seon.cluster.message/to [:seon.cluster.agent/id "walker"]
          :seon.cluster.message/content "CURRENT-TASK-UNIQUE"
          :seon.cluster.message/at (Date. 1700000005000)}
         {:seon.cluster.run/id "current-run"
          :seon.cluster.run/agent [:seon.cluster.agent/id "walker"]
          :seon.cluster.run/trigger
          [:seon.cluster.message/id "current-task"]
          :seon.cluster.run/opened-at (Date. 1700000006000)}
         {:seon.cluster.agent/id "walker"
          :seon.cluster.agent/run [:seon.cluster.run/id "current-run"]}])
       (let [current-request
             (assoc (request connection context-channel)
                    :seon.cluster.run/id "current-run")
             current (:seon.cluster.prompt/text
                      (prompt/prompt @connection current-request))]
         (is (str/includes? opening "inspect this walk"))
         (is (not (str/includes? current "inspect this walk"))
             "the opening task is not emitted again from the full re-walk")
         (is (= 1 (count (re-seq #"CURRENT-TASK-UNIQUE" current)))
             "the current task appears exactly once")
         (is (str/ends-with? current "CURRENT-TASK-UNIQUE")
             "the current task is the final prompt bytes"))))))

(deftest basis-only-transactions-do-not-append-history
  (planted
   (fn [connection context-channel]
     (let [before (:seon.cluster.prompt/text
                   (prompt/prompt @connection
                                  (request connection context-channel)))]
       (db/transact! connection [])
       (let [after (:seon.cluster.prompt/text
                    (prompt/prompt @connection
                                   (request connection context-channel)))]
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
                  [{:seon.cluster.run/id "walk-run"
                    :seon.cluster.run/agent
                    [:seon.cluster.agent/id "walker"]
                    :seon.cluster.run/opened-at (Date.)}])
      (testing "the custody invariant remains independent of presentation"
        (is (= :seon.cluster.prompt/no-trigger
               (:seon.cluster.prompt/rule
                (support/refusal-data
                 #(prompt/prompt @connection
                                 (request connection
                                          (async/chan)))))))))))

(deftest prompt-budget-compacts-then-refuses-at-distance-zero
  (planted
   (fn [connection context-channel]
     (db/transact! connection
                   [{:seon.cluster.agent/id "walker"
                     :seon.config.ai/prompt-token-budget 3}])
     (let [distances (atom [])
           acquire (fn [_ render-request]
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
                                        (request connection context-channel))]
           (is (= [2 1] @distances))
           (is (= "fits nine" (:seon.cluster.prompt/text compacted)))
           (is (<= (-> compacted :seon.context/contributions first
                       :seon.context.contribution/tokens)
                   3))))
       (reset! distances [])
       (with-redefs [render/acquire-context!
                     (fn [_ render-request]
                       (swap! distances conj (:seon.render/distance render-request))
                       {:seon.cluster.prompt/text (apply str (repeat 40 "x"))
                        :seon.render.history/segments
                        [(apply str (repeat 40 "x"))]
                        :seon.db/db (:seon.db/db render-request)})]
         (let [refusal (prompt/prompt @connection
                                      (request connection context-channel))]
           (is (= [2 1 0] @distances))
           (is (= :seon.cluster.prompt/budget-exceeded
                  (:seon.error/kind refusal)))
           (is (= 0 (get-in refusal [:seon.error/data
                                     :seon.render/distance])))
           (is (= 3 (get-in refusal [:seon.error/data
                                     :seon.config.ai/prompt-token-budget])))))))))

(defn- recorded-usage-tx
  "Facts one settled attempt already commits: the exact prompt characters
  on the run's capture, and the provider's own count on the attempt."
  [model ordinal characters provider-tokens]
  (let [run-id (str "usage-run-" ordinal)]
    [{:seon.cluster.run/id run-id
      :seon.cluster.run/agent [:seon.cluster.agent/id "walker"]
      :seon.cluster.run/opened-at (Date. (+ 1700000100000 (* 1000 ordinal)))}
     {:seon.context.capture/id (str run-id "-context-1")
      :seon.context.capture/run [:seon.cluster.run/id run-id]
      :seon.context.capture/basis-t 1
      :seon.context.capture/prompt (apply str (repeat characters "x"))
      :seon.ai.tokens/characters characters}
     {:seon.ai.attempt/id (str run-id "-0")
      :seon.ai.attempt/run [:seon.cluster.run/id run-id]
      :seon.ai.attempt/ordinal 0
      :seon.ai.attempt/at (Date. (+ 1700000100000 (* 1000 ordinal)))
      :seon.ai/endpoint "https://example.invalid/v1/chat/completions"
      :seon.ai/model model
      :seon.ai.attempt/usage-edn
      (pr-str {"prompt_tokens" provider-tokens
               "completion_tokens" 1
               "total_tokens" (inc provider-tokens)})}]))

(deftest a-prompt-the-provider-counts-over-budget-is-refused-not-sent
  ;; THE CLASS: the guard was correct against a measurement that was
  ;; not. `chars/4` ran ~23% low against DeepSeek, so prompts left the
  ;; process up to 3,059 tokens over a declared 32,768 with no refusal
  ;; (whole-system-arc observer, 2026-08-08). The budget now measures in
  ;; the units the provider bills in, fitted to this model's own
  ;; recorded usage.
  (planted
   (fn [connection context-channel]
     (let [model (db/q '[:find ?model .
                         :where [_ :seon.config.ai/model ?model]]
                       @connection)]
       (db/transact! connection
                     [{:seon.cluster.agent/id "walker"
                       :seon.config.ai/prompt-token-budget 100}])
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
                       (fn [_ render-request]
                         {:seon.cluster.prompt/text text
                          :seon.db/db (:seon.db/db render-request)})]
           (let [refusal (prompt/prompt @connection
                                        (request connection context-channel))]
             (is (= 106 (tokens/estimate text))
                 "the measured prior catches the first turn too")
             (is (= :seon.cluster.prompt/budget-exceeded
                    (:seon.error/kind refusal))
                 "the calibrated estimate refuses it")
             (is (= 106 (get-in refusal [:seon.error/data
                                         :seon.ai.tokens/estimated])))
             (is (= :seon.ai.tokens/observed
                    (get-in refusal [:seon.error/data
                                     :seon.ai.tokens/basis]))
                 "the refusal names which basis measured it"))))))))
