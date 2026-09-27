(ns seon.gen.loop-test
  "Whole-turn execution over a real cluster, with only provider text supplied.

  Ruling 67 settles the whole reply as one batch. A failed form leaves
  its error on its evaluation and later forms still run. A red form whose
  namespace another agent owns is assigned to that owner
  (`seon.problems/assignment-value`), so it settles `:routed`; declination
  is an explicit message protocol, covered by its own tests. Completion
  prose does not erase failed evaluation evidence."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow.core]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.core :as datahike]
            [seon.db :as db]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]

            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.render :as render]
            [seon.render.web :as web]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.gen.loop-test")))

(def ^:private process
  (cluster/process-identity {:seon.boot/pid 4242
                             :seon.boot/start-instant (Date. 1700000000000)}))

(def ^:private now (Date. 1700000000000))

;;; ---------------------------------------------------------------------------
;;; The cast, created through the ONE formal path
;;; ---------------------------------------------------------------------------

;;; S3's lesson: there is no namespace-less agent. The planner owns
;;; `my.gen.planner` exactly as alpha and beta own theirs, and it is
;;; that ownership — not a table in this file — that decides where a
;;; red form goes.
(def ^:private cast-spec
  [["root" 'my.agents.root]
   ["planner" 'my.gen.planner]
   ["alpha" 'my.gen.alpha]
   ["beta" 'my.gen.beta]])

(def ^:private cast-entities
  (into []
        (mapcat (fn [[agent-id namespace-name]]
                  (agent/creation-tx {:seon.agent/id agent-id
                                      :seon.cluster/name "generate-code-v0"
                                      :seon.ns/name namespace-name})))
        cast-spec))

(defn- with-render-context-proc
  "Run the production render proc that answers prompt context requests."
  [cluster body]
  (let [context-channel
        (test-support/render-context-channel
         (render/agent-render-profile config/defaults))
        render-channel (async/chan (async/sliding-buffer 1))
        runtime-eval-channel (async/chan (async/sliding-buffer 1))
        pages-channel (async/chan (async/sliding-buffer 1))
        stream-channel (async/chan (async/sliding-buffer 1))
        completion (async/promise-chan)
        cluster (assoc cluster
                       :seon.env/environment @test-environment
                       :seon.render/context-channel context-channel
                       :seon.turn.loop/stream-channel stream-channel)
        graph
        (flow.core/create-flow
         {:procs
          {:seon.render.web/render
           {:proc
            (seon.flow/var-process
             #'web/render-step :io
             {:seon.env/environment @test-environment
              :seon.render.web/render-channel render-channel
              :seon.render.web/runtime-eval-channel runtime-eval-channel
              :seon.render/context-channel context-channel
              :seon.render.web/pages-channel pages-channel
              :seon.render.web/registration (atom {})
              :seon.render.web/latest-packages (atom {})
              :seon.render.web/interest (atom :all)
              :seon.render.web/completion completion
              :seon.render.web/root-agent-id "planner"
              :seon.turn.loop/cluster cluster})}}
          :conns []})
        {:keys [report-chan error-chan]} (flow.core/start graph)]
    (async/go-loop [] (when (async/<! report-chan) (recur)))
    (async/go-loop [] (when (async/<! error-chan) (recur)))
    (try
      (flow.core/resume graph)
      (body cluster)
      (finally
        (flow.core/stop graph)
        (async/<!! completion)))))

(defn- with-gen-cluster
  "One in-process cluster with the v0 cast and the production dials."
  [body]
  (test-support/with-database
   (fn [connection]
     (let [launcher
           (seon.flow/start-work-launcher!
            {:seon.env/environment @test-environment
             ::seon.flow/configuration
             (assoc (select-keys (test-support/effective-config)
                                 seon.flow/flow-workload-attributes)
                    :seon.config.flow.compute/concurrency 2
                    :seon.config.flow.io/queue-depth 2
                    :seon.config.flow.io/concurrency 2)})]
      (try
       (test-support/seed-cluster! connection "generate-code-v0")
       (test-support/transacted! connection
                               (conj cast-entities
                                     (:seon.config/desired-row
                                      (config/compile-manifest
                                       {:seon.boot/cluster-name "generate-code-v0"
                                        :seon.config/manifest
                                        {:seon.config.run/max-episode-runs 100}}))))
       ;; Unresolved calls cross the ordinary evaluator boundary; the
       ;; run loop's second static-admission pass was deleted with the
       ;; minimal turn, so no lint bypass is needed.
       (with-render-context-proc
         (test-support/cluster-handle
         {:seon.db/connection connection
          :seon.cluster/name "generate-code-v0"
          :seon.flow/work-launcher launcher
          :seon.db.process/id process
          :seon.sci.eval/ctx (test-support/fork-cluster-ctx connection)
          :seon.cluster.wake/channel
          (async/chan (async/sliding-buffer 1))
          :seon.config.eval/time-limit-ms 2000
          :seon.config.error/recurrence-limit 3
          :seon.config.message/max-chain 4
          :seon.sci.admit/caps
          (assoc (config/result-caps (test-support/effective-config))
                 :seon.config.eval.result/max-depth 6
                 :seon.config.eval.result/max-collection 8
                 :seon.config.eval.result/max-string 4096
                 :seon.config.eval.result/max-nodes 256)})
         body)
       (finally
         (seon.flow/stop-work-launcher! launcher)))))))

(defn- agent-ids
  [db]
  (sort (db/q '[:find [?id ...] :where [?e :seon.agent/id ?id]] db)))

(defn- planner-census
  [database]
  (let [run-id
        (->> (db/q '[:find ?id ?opened
                    :where
                    [?run :seon.turn/id ?id]
                    [?run :seon.turn/opened-tx ?opened]
                    [?run :seon.turn/attempts ?attempt]
                    [?run :seon.turn/agent ?agent]
                    [?agent :seon.agent/id "planner"]]
                  database)
             (sort-by second)
             ffirst)
        count-run-members
        (fn [attribute]
          (count
           (db/q '[:find ?member
                   :in $ ?run-id ?attribute
                   :where
                   [?run :seon.turn/id ?run-id]
                   [?member ?attribute ?run]]
                 database run-id attribute)))
        terminal-receipt-count
        (count
         (db/q '[:find ?receipt
                 :in $ ?run-id
                 :where
                 [?run :seon.turn/id ?run-id]
                 [?receipt :seon.cluster.eval/run ?run]
                 (or [?receipt :seon.eval/shown _]
                     [?receipt :seon.cluster.eval/error _]
                     [?receipt :seon.cluster.eval/interrupted-at _])]
               database run-id))]
    {:seon.gen.loop/run-id run-id
     :seon.gen.loop/form-count
     (count-run-members :seon.cluster.eval/run)
     :seon.gen.loop/receipt-count
     (count-run-members :seon.cluster.eval/run)
     :seon.gen.loop/terminal-receipt-count terminal-receipt-count}))

(defn- complete-planner-census?
  [expected-form-count census]
  (and (some? (:seon.gen.loop/run-id census))
       (= expected-form-count
          (:seon.gen.loop/form-count census)
          (:seon.gen.loop/receipt-count census)
          (:seon.gen.loop/terminal-receipt-count census))))

(defn- drive!
  "Drive agent passes through the planner's exact terminal receipt census."
  [cluster limit expected-form-count]
  (let [connection (:seon.db/connection cluster)
        events (async/chan (async/sliding-buffer 1))
        listener-key (random-uuid)]
    (datahike/listen! connection listener-key #(async/put! events %))
    (try
      (loop [passes 0]
        (when (< passes limit)
          ;; a deterministic clock that still advances: run order is a
          ;; fact this suite reads, and a wall clock makes two runs in the
          ;; same millisecond order themselves at random
          (let [at (Date. (+ (inst-ms now) (* 1000 (long passes))))
                work (some (fn [agent-id]

                             (turn/next-agent-work
                              @connection
                              {:seon.agent/id agent-id
                               :seon.db.process/id process
                               :seon.turn.work/now at}))
                           (agent-ids @connection))]
            (when work
              (turn/turn {:seon.turn.loop/cluster cluster
                                  :seon.turn.work/next work}
                                 at)
              (recur (inc passes))))))
      (let [event
            (test-support/await-event!
             events
             {:seon.gen.loop/event ::planner-terminal-census
              :seon.gen.loop/expected-form-count expected-form-count
              :seon.gen.loop/observed (planner-census @connection)}
             #(complete-planner-census?
               expected-form-count
               (planner-census (:db-after %))))]
        (:seon.gen.loop/run-id (planner-census (:db-after event))))
      (finally
        (datahike/unlisten! connection listener-key)
        (async/close! events)))))

;;; ---------------------------------------------------------------------------
;;; The staged attempt — failure is INJECTED, so the design is what is
;;; being measured rather than the model's mood
;;; ---------------------------------------------------------------------------

(def ^:private program
  "Two namespaces in one reply, each with one form that fails.
  The failures are INJECTED so the observable is the design and not the
  model's mood: an unresolved symbol is red through the ordinary
  evaluator, receipt and admission path, with nothing stubbed."
  (str "I will set up both namespaces.\n"
       "(in-ns 'my.gen.alpha)\n"
       "(defn widget-total [n] (* n 3))\n"
       "(alpha-helper-missing)\n"
       "(in-ns 'my.gen.beta)\n"
       "(defn beta-label [] \"beta\")\n"
       "(beta-helper-missing)\n"))

(defn- prompt-agent-id
  "The focal agent owns the namespace in the first REPL prompt."
  [prompt]
  (when-let [prompt-end (str/index-of prompt "=>")]
    (let [namespace-name (subs prompt 0 prompt-end)]
      (some (fn [[agent-id owned-namespace]]
              (when (= namespace-name (str owned-namespace))
                agent-id))
            cast-spec))))

;;; ---------------------------------------------------------------------------
;;; Queries — every milestone is a fact
;;; ---------------------------------------------------------------------------

(defn- states
  "The derived state of every form of the planner's plan, by ordinal."
  [db run-id]
  (into {}
        (map (juxt :seon.cluster.eval/ordinal
                   :seon.turn.work/form-state))
        (:seon.turn.work/forms (turn/plan-settlement db run-id))))

;;; ---------------------------------------------------------------------------
;;; The loop
;;; ---------------------------------------------------------------------------

(deftest completion-prose-does-not-erase-failed-evaluations
  ;; A completion reply is delivered, but it does not rewrite prior errors.
  (with-gen-cluster
   (fn [cluster]
     (let [connection (:seon.db/connection cluster)]
       (test-support/transacted! connection
                               [{:seon.message/id "goal-1" :seon.message/to [:seon.agent/id "planner"] :seon.message/from [:seon.agent/id "root"] :seon.message/content "Build the helpers."}])
       ;; every owner is mute; only the planner ever answers, and it
       ;; answers by claiming it is done
       (let [run-id
             (with-redefs [ai/complete
                           (fn [_projection {prompt :seon.ai/prompt}]
                             {:seon.ai/text
                              (if (= "planner" (prompt-agent-id prompt))
                                (str program
                                     "(seon.run/complete \"the program is built\")")
                                "(seon.run/wait \"saying nothing\")")})]
               (drive! cluster 12 7))
             db @connection]
         (is (= "the program is built"
                (db/q '[:find ?content .
                       :where
                       [?m :seon.message/to ?to]
                       [?to :seon.agent/id "root"]
                       [?m :seon.message/content ?content]]
                     db))
             "the planner said it was finished")
         (is (false? (:seon.turn.work/settled?
                      (turn/plan-settlement db run-id)))
             "and the facts contradict it — an unsettled evaluation
              keeps the plan open no matter what the reply says")
         ;; WANTED: each red stays on its own evaluation and is routed to its
         ;; namespace's owner (alpha, beta), never erased by the prose; the
         ;; planner is not those namespaces' owner, so neither is unrouted.
         (is (= #{:routed}
                (set (vals (select-keys (states db run-id) [2 5]))))
             "both failures remain on their original evaluations, routed to their owners"))))))
