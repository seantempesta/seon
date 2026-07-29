(load-file "tmp/f4-drives/scripts/f4_common.clj")

(ns f4-drives.kill-child
  "The kill-9 child phases for the F4 crash proof."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [f4-drives.common :as common]
            [seon.cluster :as cluster]
            [seon.cluster.work :as work]))

(def proxy-endpoint
  "The request-recording proxy used only by the crash drive."
  "http://127.0.0.1:18090/v1/chat/completions")

(def fast-agent-ids ["kill-fast-1" "kill-fast-2" "kill-fast-3"])
(def slow-agent-ids ["kill-slow-1" "kill-slow-2" "kill-slow-3"])
(def all-agent-ids (into fast-agent-ids slow-agent-ids))

(defn fast-prompt
  "A real-model request whose one admitted form stays in SCI."
  [agent-id]
  (str "F4_FAST_EVAL " agent-id
       ". Return exactly one executable Clojure form and no prose or "
       "Markdown: (loop [] (recur))."))

(defn slow-prompt
  "A real-model request the recording proxy holds before client response."
  [agent-id]
  (str "F4_SLOW_CALL " agent-id
       ". Return exactly one executable Clojure form and no prose or "
       "Markdown: (my.run/complete \"" agent-id "-should-not-land\")."))

(defn rewake-prompt
  "The explicit outside trigger after recovery."
  [agent-id]
  (str "F4_RECOVERY_REWAKE " agent-id
       ". Return exactly one executable Clojure form and no prose or "
       "Markdown: (my.run/complete \"" agent-id "-adapt-ok\")."))

(defn run-id-map
  "Agent→run id for every trigger that has opened."
  [db messages]
  (let [runs
        (into {}
              (map
               (fn [[agent-id message-id]]
                 [agent-id
                  (:seon.cluster.run/id
                   (common/run-for-trigger db message-id))]))
              messages)]
    (when (every? some? (vals runs)) runs)))

(defn running-receipts
  "Every receipt with no terminal fact."
  [db]
  (->> (d/q
        '[:find ?id ?run-id ?ordinal ?at
          :where
          [?receipt :seon.cluster.eval/id ?id]
          [?receipt :seon.cluster.eval/run ?run]
          [?run :seon.cluster.run/id ?run-id]
          [?receipt :seon.cluster.eval/ordinal ?ordinal]
          [?receipt :seon.cluster.eval/at ?at]
          (not [?receipt :seon.cluster.eval/result-edn _])
          (not [?receipt :seon.cluster.eval/error _])
          (not [?receipt :seon.cluster.eval/interrupted-at _])]
        db)
       (sort-by second)
       vec))

(defn interrupted-receipts
  "Every receipt recovery cut."
  [db]
  (->> (d/q
        '[:find ?id ?run-id ?ordinal ?at ?interrupted
          :where
          [?receipt :seon.cluster.eval/id ?id]
          [?receipt :seon.cluster.eval/run ?run]
          [?run :seon.cluster.run/id ?run-id]
          [?receipt :seon.cluster.eval/ordinal ?ordinal]
          [?receipt :seon.cluster.eval/at ?at]
          [?receipt :seon.cluster.eval/interrupted-at ?interrupted]]
        db)
       (sort-by second)
       vec))

(defn attempt-count
  "Count attempt facts for the crash cluster."
  [db]
  (or
   (d/q '[:find (count ?attempt) .
          :where [?attempt :seon.ai.attempt/ordinal _]]
        db)
   0))

(defn capture-count
  "Count pre-provider prompt captures."
  [db]
  (or
   (d/q '[:find (count ?capture) .
          :where [?capture :seon.context.capture/id _]]
        db)
   0))

(defn old-run-census
  "Census every crash-wave run."
  [db run-ids]
  (into {}
        (map (fn [[agent-id run-id]]
               [agent-id (common/run-census db run-id)]))
        run-ids))

(defn phase-one!
  "Reach three mid-model calls and three running eval receipts."
  []
  (common/stamp "KILL PHASE 1: boot and enter mixed crash positions")
  (common/delete-tree! common/runtime-root)
  (let [instance
        (common/start-local!
         "f4-kill"
         {:seon.config.ai/endpoint proxy-endpoint})
        connection (common/connection instance)
        prompts
        (merge
         (into {} (map (juxt identity fast-prompt)) fast-agent-ids)
         (into {} (map (juxt identity slow-prompt)) slow-agent-ids))
        triggered (common/install-agents-and-triggers! connection prompts)]
    (common/write-edn!
     (str common/evidence-root "/kill-phase1-triggered.edn")
     triggered)
    (common/write-text!
     (str common/evidence-root "/kill-child.pid")
     (str (.pid (java.lang.ProcessHandle/current)) "\n"))
    (let [ready
          (common/await-db
           "three running receipts while three calls remain held"
           connection
           (fn [db]
             (let [run-ids
                   (run-id-map db (:f4-drives/messages triggered))
                   running (running-receipts db)
                   attempts (attempt-count db)
                   captures (capture-count db)]
               (when (and run-ids
                          (= 3 (count running))
                          (= 3 attempts)
                          (= 6 captures))
                 {:f4-drives/pid
                  (.pid (java.lang.ProcessHandle/current))
                  :f4-drives/source-head (common/git-head)
                  :f4-drives/source-digest (common/source-digest)
                  :f4-drives/process
                  (:seon.cluster.run/process
                   (:seon.cluster.loop/cluster instance))
                  :f4-drives/run-ids run-ids
                  :f4-drives/running-receipts running
                  :f4-drives/attempt-count attempts
                  :f4-drives/capture-count captures
                  :f4-drives/fleet (common/fleet-agents instance)
                  :f4-drives/census (old-run-census db run-ids)
                  :f4-drives/basis (common/basis connection)})))
           180000)]
      (common/write-edn!
       (str common/evidence-root "/kill-ready.edn") ready)
      (common/write-text!
       (str common/evidence-root "/kill-ready") "ready\n")
      (common/stamp "KILL READY: 3 running eval receipts, 3 held calls")
      ;; Deliberately no finally: the parent must end this JVM with SIGKILL.
      @(promise))))

(defn await-file
  "Wait for an orchestration marker."
  [path timeout-ms]
  (common/await-value
   path
   #(when (.exists (java.io.File. path)) path)
   timeout-ms))

(defn phase-two!
  "Recover without replay, then answer six explicit outside re-wakes."
  []
  (common/stamp "KILL PHASE 2: reboot and derive recovery")
  (let [phase-one
        (read-string
         (slurp (str common/evidence-root "/kill-ready.edn")))
        instance
        (common/start-local!
         "f4-kill"
         {:seon.config.ai/endpoint proxy-endpoint})
        connection (common/connection instance)
        run-ids (:f4-drives/run-ids phase-one)]
    (try
      (common/assert-drive!
       (= (:f4-drives/source-digest phase-one) (common/source-digest))
       "Crash recovery loaded different source bytes."
       {:f4-drives/phase-one-source-digest
        (:f4-drives/source-digest phase-one)
        :f4-drives/phase-two-source-digest (common/source-digest)})
      (let [recovered
            (common/await-db
             "all six old runs to settle after recovery"
             connection
             (fn [db]
               (let [runs
                     (into {}
                           (map
                            (fn [[agent-id run-id]]
                              [agent-id
                               (d/pull
                                db
                                [:seon.cluster.run/id
                                 :seon.cluster.run/closed-at
                                 :seon.cluster.run/plan-digest
                                 :seon.cluster.run/error]
                                [:seon.cluster.run/id run-id])]))
                           run-ids)
                     open-pointers
                     (d/q '[:find (count ?agent) .
                            :where
                            [?agent :seon.cluster.agent/run _]]
                          db)
                     interrupted (interrupted-receipts db)]
                 (when (and (every? :seon.cluster.run/closed-at
                                    (vals runs))
                            (zero? (or open-pointers 0))
                            (= 3 (count interrupted)))
                   {:f4-drives/boot-recovered-runs
                    (:seon.boot/recovered-runs instance)
                    :f4-drives/boot-recovery-operations
                    (:seon.boot/recovery-operations instance)
                    :f4-drives/source-head (common/git-head)
                    :f4-drives/source-digest (common/source-digest)
                    :f4-drives/runs runs
                    :f4-drives/interrupted-receipts interrupted
                    :f4-drives/attempt-count (attempt-count db)
                    :f4-drives/capture-count (capture-count db)
                    :f4-drives/census (old-run-census db run-ids)
                    :f4-drives/basis (common/basis connection)})))
             60000)]
        (common/assert-drive!
         (= 3 (:f4-drives/attempt-count recovered))
         "Recovery duplicated a paid call attempt fact."
         recovered)
        (common/write-edn!
         (str common/evidence-root "/kill-recovered.edn") recovered)
        (common/write-text!
         (str common/evidence-root "/kill-recovered-ready") "ready\n")
        (common/stamp "RECOVERED READY: old attempts remain 3; waiting "
                      "for parent to verify request log")
        (await-file
         (str common/evidence-root "/allow-rewake") 60000)
        (let [triggered
              (common/trigger-existing!
               connection
               (into {} (map (juxt identity rewake-prompt))
                     all-agent-ids))
              runs
              (common/await-db
               "all six explicit recovery re-wakes to close"
               connection
               #(common/closed-runs % (:f4-drives/messages triggered))
               180000)
              db @connection
              census
              (into {}
                    (map (fn [[agent-id run]]
                           [agent-id
                            (common/run-census
                             db (:seon.cluster.run/id run))]))
                    runs)
              result
              {:f4-drives/recovered recovered
               :f4-drives/rewake-triggered triggered
               :f4-drives/rewake-runs runs
               :f4-drives/rewake-timings-ms
               (into {}
                     (map (fn [[agent-id run]]
                            [agent-id
                             (common/duration-ms
                              (:f4-drives/triggered-at triggered)
                              run)]))
                     runs)
               :f4-drives/rewake-census census
               :f4-drives/attempt-count-after-rewake
               (attempt-count db)}]
          (doseq [[agent-id found] census]
            (common/assert-drive!
             (some
              #(str/includes?
                (second %) (str agent-id "-adapt-ok"))
              (:f4-drives/forms found))
             "An explicitly re-woken agent did not adapt and answer."
             {:f4-drives/agent agent-id
              :f4-drives/census found}))
          (common/assert-drive!
           (= 9 (:f4-drives/attempt-count-after-rewake result))
           "The six explicit re-wakes did not add exactly six attempts."
           result)
          (common/write-edn!
           (str common/evidence-root "/kill-complete.edn") result)
          (common/write-text!
           (str common/evidence-root "/kill-phase2-complete") "complete\n")
          (common/stamp "KILL PHASE 2 COMPLETE: all six agents adapted")))
      (finally
        (cluster/stop! instance)))))

(case (first *command-line-args*)
  "phase1" (phase-one!)
  "phase2" (phase-two!)
  (throw
   (ex-info "Use phase1 or phase2."
            {:f4-drives/arguments *command-line-args*})))
