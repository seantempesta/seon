(ns seon.sci.render-source-test
  "A pair that returns REPL entries reaches the agent only evaluated: each form
  with its result (`seon.sci.eval/evaluated-source`), else the value printed
  by the floor beside one diagnostic. Never a form without its result."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval.drive :as drive]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.render.transcript :as transcript]
            [seon.sci.eval :as evaluation]
            [seon.test-support :as support]))

(defn- tokens
  "The shipped prior of `seon.ai.tokens`: ceil(chars / 3.2)."
  [text]
  (long (Math/ceil (/ (count text) 3.2))))

(defn- unanswered-forms
  "Top-level forms of `shown` not followed by their `;=> ` result."
  [shown]
  (let [lines (str/split-lines shown)]
    (keep-indexed (fn [index line]
                    (when (and (str/starts-with? line "(")
                               (not (some #(str/starts-with? % ";=> ")
                                          (drop (inc index) lines))))
                      line))
                  lines)))

(defn- request
  [connection ctx source handle]
  {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
   :seon.db/connection connection :seon.agent/id "render-source"
   :seon.cluster.eval/source source
   :seon.repl/handle handle
   :seon.sci.admit/caps (config/result-caps config/defaults)
   :seon.sci.eval/time-limit-ms 10000})

(deftest a-pair-s-forms-are-shown-with-their-results
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "render-source" {})
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source handle] (evaluation/evaluate (request connection ctx source handle)))]
       (testing "a contract refusal: the pair's reads evaluated against the held refusal"
         (let [failed (run "(my.message/send {:my.message/to 42 :my.message/content \"Hello\"})" 'result/erendersource1)
               shown (:seon.eval/shown failed)]
           (is (:seon.cluster.eval/error failed))
           (is (str/includes? shown ";=> [#:seon.error{:path"))
           (is (str/includes? shown "(doc my.message/send)"))
           (is (empty? (unanswered-forms shown)))
           (is (not (find (:seon.sci.admit/value failed) :seon.error/doc))
               "printing never enriches the value")
           (is (<= (tokens shown) 250))))
       (testing "an agent's own exception"
         (let [shown (:seon.eval/shown (run "(/ 1 0)" 'result/erendersource2))]
           (is (str/includes? shown ";=> [[clojure.core// "))
           (is (empty? (unanswered-forms shown)))
           (is (<= (tokens shown) 100))))
       (testing "an error nested in a vector renders by its pair, read through value-at"
         (let [error (:seon.sci.admit/value
                      (run "(my.message/send {:my.message/to 42 :my.message/content \"Hello\"})" 'result/erendersource3))
               shown (:seon.eval/shown (evaluation/shown-result
                                        [error] (request connection ctx "[e]" 'result/erendersource4) nil))]
           (is (str/includes? shown "(seon.print/value-at result/erendersource4 [0])"))
           (is (str/includes? shown ";=> "))))))))

(deftest a-render-form-that-fails-shows-the-value-and-one-diagnostic
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "render-source" {})
     (let [ctx (support/fork-cluster-ctx connection)
           failing (#'evaluation/evaluated-source
                    (request connection ctx "(/ 1 0)" nil) {} ";; boom\n(/ 1 0)")]
       (testing "the failing form is named, never shown as an answer"
         (is (nil? (:seon.render/source failing)))
         (is (str/includes? (get-in failing [:seon.error/value :seon.error/message]) "Divide by zero")))
       (testing "with no evaluator the floor prints the value beside one diagnostic"
         (let [error (:seon.sci.admit/value
                      (evaluation/evaluate (request connection ctx "(/ 1 0)" 'result/erendersource5)))
               shown (:seon.eval/shown (evaluation/shown-result
                                        error (dissoc (request connection ctx "(/ 1 0)" 'result/erendersource5)
                                                      :seon.sci.eval/time-limit-ms)
                                        nil))]
           (is (str/includes? shown ":seon.error/message"))
           (is (= 1 (count (re-seq #";; renderer failed" shown))))
           (is (not (str/includes? shown ";=> ")))))))))

(deftest ^{:seon.test/long "One agent arming and one ordinary run with its settlement commit (184-229 ms warm per run on default, 2026-09-26), plus two history reads."
           :seon.test/long-ms 10000}
  the-next-context-reads-the-evaluated-render-without-evaluating
  (let [target (support/execution-handle nil)
        connection (:seon.db/connection target)
        cluster-name (:seon.cluster/name target)
        agent-id (str "render-source-" (id/id))
        environment (support/environment cluster-name connection)
        ctx (support/fork-cluster-ctx connection cluster-name)
        routing (support/attempt-routing connection)
        faults (async/chan (async/sliding-buffer 16))]
    (with-open [launcher (support/closeable
                          (flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                                  flow/flow-workload-attributes)})
                          flow/stop-work-launcher!)]
      (let [handle (support/cluster-handle
                    {:seon.env/environment environment :seon.db/connection connection :seon.cluster/name cluster-name
                     :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                     :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.db.process/id cluster/boot-process-identity})
            agent-request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id agent-id}]
        (swap! routing assoc :seon.agent/fault-channel faults)
        (support/transacted! connection (support/agent-tx @connection agent-id))
        (agent/arm! agent-request)
        (let [result (agent/evaluate-source! (assoc agent-request :seon.agent/segmenter 'seon.turn/code-sources
                                                    :seon.cluster.reply/text "(/ 1 0)"))
              ;; the run on the reply's own value (answered when applied)
              saved (:seon.eval/shown (first (drive/run-receipts (:seon.db/db result) [(:seon.turn/id result)])))
              context #(:seon.render.history/printed-value
                        (first (transcript/history-entries
                                {:seon.db/db (:seon.db/db result) :seon.agent/id agent-id
                                 :seon.render.transcript/selected-run-id (:seon.turn/id result)
                                 :seon.sci.admit/caps (config/result-caps config/defaults)})))]
          (is (str/includes? saved ";=> "))
          (is (empty? (unanswered-forms saved)))
          (testing "the context is the saved evaluated text: history reads take no SCI context, so they evaluate nothing"
            (is (str/starts-with? (context) saved))
            (is (= (context) (context))))))
      (async/close! faults))))
