(ns seon.competency-grade-test
  "A competency run is graded from its agent's stored facts."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.competency :as competency]
            [seon.db :as db]
            [seon.eval.drive]
            [seon.id]
            [seon.mcp :as mcp]
            [seon.operator.runtime]
            [seon.task :as task]
            [seon.test-support :as support]))

(defn- agent-eval
  "The reply map of `code` evaluated as `agent-id` through `seon.mcp/call`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :seon.schema/value]}
  [running agent-id code]
  (support/mcp-reply (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                               "arguments" {"code" code "agent" agent-id "cluster" running}})))))

(defn- wake!
  "Wake the running agent's proc once, as a message would."
  {:malli/schema [:=> [:cat :string :seon.agent/id] :nil]}
  [running agent-id]
  (async/offer! (:seon.cluster.wake/channel
                 (get-in @(:seon.agent/routing (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances running)))
                         [:seon.agent/armed agent-id]))
                :seon.agent/wake)
  nil)

(defn- model-run!
  "Assign `task-sym` to `agent-id`, answer its model calls with `replies` from a fixture provider under a cap
  of as many calls, and return once that many call rows are closed."
  {:malli/schema [:=> [:cat :string :seon.agent/id :seon.db/connection :seon.task/sym [:vector :string]] :nil]}
  [running agent-id connection task-sym replies]
  (let [closed #(or (db/q '[:find (count ?call) . :in $ ?agent-id
                            :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent]
                            [?call :seon.turn/attempts _] [?call :seon.turn/closed-tx _]]
                          (db/db connection) agent-id)
                    0)
        id (db/q '[:find ?id . :in $ ?sym :where [?t :seon.task/sym ?sym] [?t :seon.task/id ?id]] (db/db connection) task-sym)]
    (task/assign! {:seon.db/connection connection :seon.task/assigned id
                   :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch :main})
    (support/with-provider
     (atom []) (support/reply-queue replies)
     (fn [server]
       (agent-eval running agent-id (str "(my.agent/settings! "
                                         (seon.id/printed (assoc (support/provider-config server)
                                                                 :seon.config.ai/no-provider false
                                                                 :seon.config.run/max-episode-runs (count replies)))
                                         ")"))
       (let [cluster-name (:seon.cluster/name (support/execution-handle nil))
             outside (java.util.concurrent.FutureTask.
                      ^java.util.concurrent.Callable
                      #(seon.eval.drive/inbound! connection cluster-name cluster/boot-process-identity
                                                 agent-id "Work your task."))]
         (.start (Thread. outside))
         (.get outside))
       (wake! running agent-id)
       (loop [tries 600]
         (when (and (pos? tries) (< (closed) (count replies)))
           (Thread/sleep 50)
           (recur (dec tries))))
       nil))))

(deftest ^{:seon.test/long "One fixture-model pass whose form arms a contracted function, then its proof's step."
           :seon.test/long-ms 60000}
  a-fixture-model-run-grades-green
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [task 'seon.competency/area-is-contracted
           red (competency/grade (db/db connection) agent-id task)]
       (model-run! running agent-id connection task
                   ["(ns comp.geo)\n(defn area {:malli/schema [:=> [:cat [:int {:min 1}] [:int {:min 1}]] [:int {:min 1}]]} [w h] (* w h))\n(ns comp.geo)"])
       ;; the proof's step records after the call row closes: wait for what production waits for, its result
       (let [graded (loop [tries 300]
                      (let [graded (competency/grade (db/db connection) agent-id task)]
                        (if (or (:seon.competency/green? graded) (zero? tries))
                          graded
                          (do (Thread/sleep 100) (recur (dec tries))))))]
         (is (= {:green 1} (:seon.competency/proofs graded)))
         (is (= [false 0] [(:seon.competency/green? red) (:seon.competency/model-calls red)]) "before the run: red, no call")
         (is (= {:seon.competency/green? true :seon.competency/model-calls 1 :seon.competency/calls-to-green 1
                 :seon.competency/forms 3 :seon.competency/repeated-forms 1 :seon.competency/agent-errors 0
                 :seon.competency/no-form-calls 0}
                (select-keys graded [:seon.competency/green? :seon.competency/model-calls :seon.competency/calls-to-green
                                     :seon.competency/forms :seon.competency/repeated-forms
                                     :seon.competency/agent-errors :seon.competency/no-form-calls]))
             "one model call turned the proof green; its repeated ns form counts as wasted"))))))
