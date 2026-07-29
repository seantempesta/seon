(ns cold-resume-probe
  "LIVE reproduction of the cold-resume prefix loss, driven through the
  real loop with the real evaluator — no kill -9 needed.

  The insight the REPL gave up: a crash is not the only way to lose the
  ctx. The fold BREAKS on any errored form (`next-ordinal` is nil when
  the outcome carries an error), leaving the run open with unsettled
  ordinals. The next wake derives `:resume` at the next ordinal and
  forks a BRAND NEW ctx. So the prefix loss reproduces in-process, in
  milliseconds, deterministically.

  Load into a live cluster JVM:
    (load-file \"tmp/repl-experiments/cold_resume_probe.clj\")
    (cold-resume-probe/run! [\"(def x 41)\" \"(boom)\" \"(inc x)\"])"
  (:require [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.work :as work]
            [seon.flow :as seon.flow]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike])
  (:import [java.util Date]))

(def process
  (cluster/process-identity {:seon.boot/pid 4242
                             :seon.boot/start-instant (Date. 1700000000000)}))
(def now (Date. 1700000000000))

(def seed-blocks
  [{:seon.render.block/name :identity :seon.render.block/band :anchor
    :seon.render.block/priority 0
    :seon.render/ai 'seon.context/identity-ai}])

(defn with-cluster [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (seon.flow/install-work-launcher!
       {:seon.flow/configuration
        {:seon.config.flow.compute/queue-depth 10
         :seon.config.flow.compute/concurrency 2}})
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (schema/canonical-database-attributes)))
      (d/transact connection
                  [{:seon.cluster.agent/id "agent-a"
                    :seon.cluster.agent/blocks seed-blocks}
                   {:seon.config/cluster "probe"
                    :seon.config.run/max-episode-runs 100}
                   {:seon.cluster.message/id "m-1"
                    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.message/content "go"
                    :seon.cluster.message/at now}])
      (body {:seon.store/branch-connection connection
             :seon.cluster.run/process process
             :seon.cluster.wake/channel
             (clojure.core.async/chan (clojure.core.async/sliding-buffer 1))
             :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
             :seon.config.eval/time-limit-ms 2000
             :seon.config/on-core-error :record
             :seon.config.error/recurrence-limit 3
             :seon.config.message/max-chain 2
             :seon.sci.admit/caps
             {:seon.config.eval.result/max-depth 6
              :seon.config.eval.result/max-collection 8
              :seon.config.eval.result/max-string 4096
              :seon.config.eval.result/max-nodes 256}})
      (finally
        (seon.flow/stop-installed-work-launcher!)
        (d/release connection)
        (d/delete-database configuration)))))

(defn plan! [connection sources]
  (d/transact
   connection
   {:tx-data
    (into [{:seon.ns/name 'my.agents.agent-a}
           {:seon.cluster.agent/id "agent-a"
            :seon.cluster.agent/namespace [:seon.ns/name 'my.agents.agent-a]}
           {:seon.cluster.run/id "run-1"
            :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
            :seon.cluster.run/opened-at now
            :seon.cluster.run/process process
            :seon.cluster.run/plan-digest (apply str (repeat 64 "a"))}
           {:seon.cluster.agent/id "agent-a"
            :seon.cluster.agent/run [:seon.cluster.run/id "run-1"]}]
          (map-indexed
           (fn [ordinal source]
             {:seon.cluster.run.form/id (str "f-" ordinal)
              :seon.cluster.run.form/run [:seon.cluster.run/id "run-1"]
              :seon.cluster.run.form/ordinal ordinal
              :seon.cluster.run.form/source source})
           sources))
    :tx-meta {:seon.db/trigger [:seon.cluster.message/id "m-1"]}}))

(defn receipts [db]
  (sort-by :seon.cluster.eval/ordinal
           (d/q '[:find [(pull ?r [:seon.cluster.eval/ordinal
                                   :seon.cluster.eval/result-edn
                                   :seon.cluster.eval/error]) ...]
                  :where [?r :seon.cluster.eval/run ?run]
                  [?run :seon.cluster.run/id "run-1"]]
                db)))

(defn drive! [cluster limit]
  (let [connection (:seon.store/branch-connection cluster)]
    (loop [passes 0 reports []]
      (let [work (work/next-agent-work
                  @connection
                  {:seon.cluster.agent/id "agent-a"
                   :seon.cluster.run/process process
                   :seon.cluster.work/now (Date.)})]
        (if (or (nil? work) (>= passes limit))
          reports
          (recur (inc passes)
                 (conj reports
                       [(:seon.cluster.work/situation work)
                        (:seon.cluster.run.form/ordinal work)
                        (cluster.loop/turn
                         {:seon.cluster.loop/cluster cluster
                          :seon.cluster.work/next work}
                         (Date.))])))))))

(defn settle!
  "Stamp terminal receipts on `ordinals` — exactly the facts a dead
  process's committed work plus `recover-tx` leave behind. Driving after
  this is a COLD resume: the fold starts mid-plan with a fresh ctx, and
  no test needs to kill a JVM to get there."
  [connection ordinals]
  (d/transact connection
              (mapv (fn [ordinal]
                      {:seon.cluster.eval/id (str "e-" ordinal)
                       :seon.cluster.eval/run [:seon.cluster.run/id "run-1"]
                       :seon.cluster.eval/ordinal ordinal
                       :seon.cluster.eval/at now
                       :seon.cluster.eval/result-edn "\"settled by a dead process\""})
                    ordinals)))

(defn run!
  "Drive `sources` as one plan and report every receipt plus the passes.
  `:settled` pre-stamps terminal receipts, making the drive a cold resume."
  ([sources] (run! sources nil))
  ([sources settled]
   (with-cluster
     (fn [cluster]
       (let [connection (:seon.store/branch-connection cluster)]
         (plan! connection sources)
         (when (seq settled) (settle! connection settled))
         (let [reports (drive! cluster 12)]
           {:passes (mapv (fn [[situation ordinal report]]
                            [situation ordinal
                             (:seon.cluster.loop/outcome report)])
                          reports)
            :receipts (receipts @connection)}))))))
