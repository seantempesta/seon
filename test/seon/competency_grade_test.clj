(ns seon.competency-grade-test
  "A competency run is graded from its agent's stored facts."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.host :as host]
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

(defn- fixture-answer
  "A fixture model for T1: its solution, unindented as a model writes it, until a request shows it was
  evaluated, then a reply with no form."
  {:malli/schema [:=> [:cat :map] :string]}
  [document]
  (if (some #(str/includes? (str (get % "content")) "symbol comp.geo/area") (get document "messages"))
    ";; The proof is green; I wait."
    "(ns comp.geo)\n(defn area {:malli/schema [:=> [:cat [:int {:min 1}] [:int {:min 1}]] [:int {:min 1}]]} [w h] (* w h))"))

(defn- home-root!
  "Name the member's branch as root's home, as a running cluster's root row names its own branch, so a task
  home exists apart from the bench agents' branches."
  {:malli/schema [:=> [:cat :seon.db/connection] :nil]}
  [connection]
  (let [database (db/db connection)
        branch (get-in database [:config :branch])
        root (db/pull database [:db/id] [:seon.agent/id "root"])]
    (db/transact! connection (if (:db/id root)
                               [[:db/add (:db/id root) :seon.agent/branch branch]]
                               [[:db.fn/call #'host/home-call "root" branch 'user
                                 (:seon.cluster/name (support/execution-handle nil))]]))
    nil))

(deftest ^{:seon.test/long "Four fixture-model runs on their own branches, each one model call, its proof's step and a waiting reply."
           :seon.test/long-ms 120000}
  a-launch-grades-each-run
  (support/with-running-agent
   (fn [running _agent-id connection]
     (home-root! connection)
     (support/with-provider
      (atom []) fixture-answer
      (fn [server]
        (let [settings (assoc (support/provider-config server)
                              :seon.config.ai/no-provider false :seon.config.run/max-episode-runs 3)
              grades (competency/launch!
                      {:seon.competency/cluster running
                       :seon.competency/cells [{:seon.competency/task 'seon.competency/area-is-contracted
                                                :seon.competency/strategy :defaults :seon.competency/settings settings}
                                               {:seon.competency/task 'seon.competency/area-is-contracted-typed
                                                :seon.competency/strategy :defaults :seon.competency/settings settings}]
                       :seon.competency/runs 2 :seon.competency/poll-ms 100 :seon.competency/deadline-ms 60000})]
          (is (= (repeat 4 {:seon.competency/green? true :seon.competency/calls-to-green 1
                            :seon.competency/model-calls 2 :seon.competency/no-form-calls 1
                            :seon.competency/terminal :waiting :seon.competency/retired? true
                            :seon.competency/max-episode-runs 3})
                 (map #(-> (select-keys % [:seon.competency/green? :seon.competency/calls-to-green
                                           :seon.competency/model-calls :seon.competency/no-form-calls
                                           :seon.competency/terminal :seon.competency/retired?])
                           (assoc :seon.competency/max-episode-runs
                                  (get-in % [:seon.competency/variant :seon.config.run/max-episode-runs])))
                      grades))
              "each run turned its proof green in one call, replied with no form and waited, carries its variant and was retired")
          (is (= [['seon.competency/area-is-contracted 2 2 1] ['seon.competency/area-is-contracted-typed 2 2 1]]
                 (mapv (juxt :seon.competency/task :seon.competency/runs :seon.competency/green
                             :seon.competency/calls-to-green-median)
                       (competency/table grades)))
              "one table row per cell")))))))
