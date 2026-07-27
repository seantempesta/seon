(ns n3-live-drive
  "N3 integration proof, phase 1: one REAL turn end to end.
  Boot the tower, install the loop proc on a flow graph, trigger the
  agent with one message, and watch the facts commit: run opened +
  claimed, trigger answered, plan frozen, fold receipts, defs
  accumulating across forms, run closed by my.run/complete.
  Run: DEEPSEEK_API_KEY=... clojure -M:dev -M -e \"(load-file \\\"tmp/n3-live-drive.clj\\\")\""
  (:require [seon.cluster :as cluster]
            [seon.cluster.wake :as wake]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]))

(defn- stamp [& parts]
  (println (str "[" (.toString (java.time.LocalTime/now)) "] "
                (apply str parts)))
  (flush))

(defn- await-fact
  "Poll `probe` against the live connection until truthy, else throw.

  Polls every 500ms up to `attempts` times; a timeout throws so the
  drive can never exit zero on a missed milestone. The proof watches;
  production wakes."
  [label connection probe attempts]
  (loop [n 0]
    (let [value (probe @connection)]
      (cond
        value (do (stamp "OK   " label " → " (pr-str value)) value)
        (>= n attempts)
        (throw (ex-info (str "MISS: " label " never became true")
                        {::label label}))
        :else (do (Thread/sleep 500) (recur (inc n)))))))

(stamp "PHASE 1 — booting the tower")
; a fresh root per run: the drive must fork from THIS source's
; ancestor, and an existing cluster branch is found, never re-forked
(def root "tmp/n3-drive/clusters")
(let [dir (java.io.File. root)]
  (when (.exists dir)
    (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))
(def instance (cluster/start! {:seon.boot/cluster-name "live"
                               :seon.boot/root root}))
(def connection (:seon.boot/cluster-connection instance))
(def dials (config/effective @connection "live"))
(stamp "tower up; effective dials: " (pr-str dials))

(def wake-channel (async/chan (async/sliding-buffer 1)))
(def faults (async/chan (async/sliding-buffer 8)))
(def handle
  {:seon.store/branch-connection connection
   :seon.cluster.run/process
   ;; <pid>-<start-millis>: the holder string boot recovery judges
   ;; against, so a run's holder and the live set are the same value
   (cluster/process-identity (:seon.boot/advertisement instance))
   :seon.cluster.wake/channel wake-channel
   :seon.cluster.loop/provider
   {:seon.ai/endpoint "https://api.deepseek.com/chat/completions"
    :seon.ai/model "deepseek-chat"
    :seon.ai/api-key-variable "DEEPSEEK_API_KEY"
    :seon.ai/timeout-ms 60000}
   :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
   :seon.sci.admit/caps (select-keys dials
                                     [:seon.config.eval.result/max-depth
                                      :seon.config.eval.result/max-collection
                                      :seon.config.eval.result/max-string
                                      :seon.config.eval.result/max-nodes])
   :seon.config.eval/time-limit-ms (:seon.config.eval/time-limit-ms dials)
   :seon.config/on-core-error (:seon.config/on-core-error dials)})

(wake/listen! {:seon.cluster.wake/connection connection
               :seon.cluster.wake/attributes (wake/wake-attributes)
               :seon.cluster.wake/channel wake-channel
               :seon.cluster.wake/fault-channel faults
               :seon.cluster.wake/key ::live})
(def graph (flow/create-flow
            {:procs {::loop {:proc (flow/process #'cluster.loop/step
                                                 {:workload :io})
                             :args handle}}
             :conns []}))
(flow/start graph)
(flow/resume graph)
(async/offer! wake-channel ::boot)
(stamp "graph running; loop armed on the wake channel")

(def outcome
  ; teardown ALWAYS runs — a thrown MISS must not leak the graph, the
  ; listener, or the store flock phase 2's reboot needs
  (try
    (stamp "TRIGGER — alice gets one message")
    (d/transact connection [{:seon.cluster.agent/id "alice"}])
    (d/transact connection
                [{:seon.cluster.message/id (str (random-uuid))
                  :seon.cluster.message/to [:seon.cluster.agent/id "alice"]
                  :seon.cluster.message/content
                  (str "Define a function that sums the integers 1..n, "
                       "call it with 10, then complete with the answer.")
                  :seon.cluster.message/at (java.util.Date.)}])
    (await-fact "run opened + claimed" connection
                (fn [db]
                  ; seq: an empty result set is truthy in Clojure and
                  ; faked this milestone on the previous drive
                  (seq (d/q '[:find ?id ?p :where
                              [?r :seon.cluster.run/id ?id]
                              [?r :seon.cluster.run/process ?p]]
                            db)))
                20)
    (await-fact "trigger answered (unanswered = [])" connection
                (fn [db]
                  (when (empty? (work/unanswered-triggers db "alice"))
                    :answered))
                20)
    (await-fact "plan frozen (model replied)" connection
                (fn [db]
                  (d/q '[:find ?d . :where
                         [_ :seon.cluster.run/plan-digest ?d]]
                       db))
                240)
    (await-fact "fold receipts (one :done per frozen form)" connection
                (fn [db]
                  ; count against the PLAN: a single early :done must not
                  ; pass while later forms still run (review-caught)
                  (let [forms (count (d/q '[:find ?f :where
                                            [?f :seon.cluster.run.form/ordinal]]
                                          db))
                        rows (sort-by
                              first
                              (d/q '[:find ?o ?s ?edn :where
                                     [?e :seon.cluster.eval/ordinal ?o]
                                     [?e :seon.cluster.eval/status ?s]
                                     [?e :seon.cluster.eval/result-edn ?edn]]
                                   db))]
                    (when (and (pos? forms)
                               (= forms (count rows))
                               (every? #(= :done (second %)) rows))
                      rows)))
                120)
    (await-fact "run closed by my.run/complete" connection
                (fn [db]
                  (d/q '[:find ?c . :where
                         [_ :seon.cluster.run/closed-at ?c]]
                       db))
                60)
    (stamp "faults channel: " (pr-str (async/poll! faults)))
    :complete
    (catch Throwable failure
      (stamp "DRIVE FAILED: " (ex-message failure))
      failure)
    (finally
      (stamp "TEARDOWN")
      (flow/stop graph)
      (wake/unlisten! {:seon.cluster.wake/connection connection
                       :seon.cluster.wake/key ::live})
      (cluster/stop! instance))))

(if (= :complete outcome)
  (do (stamp "PHASE 1 COMPLETE") (System/exit 0))
  (System/exit 1))
