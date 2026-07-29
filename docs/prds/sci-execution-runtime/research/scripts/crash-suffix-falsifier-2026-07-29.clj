;; Plant the audit's exact crash state on a LIVE cluster, then read it back
;; after a kill -9 + reboot. Two forms; ordinal 0 has a RUNNING receipt (no
;; terminal fact) and ordinal 1 is an OBSERVABLE CAPABILITY: my.message/send
;; commits a durable message row, so "did the suffix execute after reboot?"
;; is a query, not an inference.
;;
;;   px xp-a  '(load-file "tmp/repl-experiments/plant_crash.clj")'
;;   px xp-a  '(plant-crash/plant! "xp-a")'      ; then kill -9, reboot
;;   px xp-a  '(plant-crash/verdict "xp-a")'
(ns plant-crash
  (:require [datahike.api :as d]
            [seon.cluster :as cluster])
  (:import [java.util Date]))

(defn instance [name] (get @@#'seon.cluster/running-instances name))
(defn connection [name] (:seon.boot/cluster-connection (instance name)))
(defn process [name]
  (cluster/process-identity (:seon.boot/advertisement (instance name))))

(def run-id "crash-suffix-run")

(defn plant!
  "Commit: a peer agent, a trigger to root, a run root HOLDS with a frozen
  two-form plan, and a RUNNING receipt on ordinal 0. Exactly the audit's
  pre-kill state."
  [name]
  (let [conn (connection name)
        now (Date.)]
    ;; the trigger FIRST and alone: tx-meta resolves a lookup ref against
    ;; db-before, so a message created in the same transaction it is
    ;; referenced from is not there yet (one live probe, one clear refusal)
    (d/transact conn
                [{:seon.cluster.agent/id "peer"}
                 {:seon.cluster.message/id "crash-trigger"
                  :seon.cluster.message/to [:seon.cluster.agent/id "root"]
                  :seon.cluster.message/content "plant"
                  :seon.cluster.message/at now}])
    (d/transact
     conn
     {:tx-data
      [{:seon.cluster.run/id run-id
        :seon.cluster.run/agent [:seon.cluster.agent/id "root"]
        :seon.cluster.run/opened-at now
        :seon.cluster.run/process (process name)
        :seon.cluster.run/plan-digest (apply str (repeat 64 "a"))}
       {:seon.cluster.agent/id "root"
        :seon.cluster.agent/run [:seon.cluster.run/id run-id]}
       {:seon.cluster.run.form/id "crash-f-0"
        :seon.cluster.run.form/run [:seon.cluster.run/id run-id]
        :seon.cluster.run.form/ordinal 0
        :seon.cluster.run.form/source "(def planted 1)"}
       {:seon.cluster.run.form/id "crash-f-1"
        :seon.cluster.run.form/run [:seon.cluster.run/id run-id]
        :seon.cluster.run.form/ordinal 1
        :seon.cluster.run.form/source
        "(my.message/send \"peer\" \"THE SUFFIX EXECUTED AFTER THE CRASH\")"}
       ;; the running receipt: present, with NO terminal fact
       {:seon.cluster.eval/id "crash-e-0"
        :seon.cluster.eval/run [:seon.cluster.run/id run-id]
        :seon.cluster.eval/ordinal 0
        :seon.cluster.eval/at now}]
      :tx-meta {:seon.db/trigger [:seon.cluster.message/id "crash-trigger"]}})
    {:planted true :pid (:seon.boot/pid (:seon.boot/advertisement (instance name)))}))

(defn verdict
  "Everything the falsifier asks about, from facts."
  [name]
  (let [db @(connection name)]
    {:receipts (sort-by :seon.cluster.eval/ordinal
                        (d/q '[:find [(pull ?r [:seon.cluster.eval/ordinal
                                                :seon.cluster.eval/result-edn
                                                :seon.cluster.eval/error
                                                :seon.cluster.eval/interrupted-at]) ...]
                               :where [?r :seon.cluster.eval/run ?run]
                               [?run :seon.cluster.run/id "crash-suffix-run"]]
                             db))
     :run (d/pull db [:seon.cluster.run/process :seon.cluster.run/closed-at]
                  [:seon.cluster.run/id run-id])
     :agent-still-points?
     (some? (d/q '[:find ?r . :where [?a :seon.cluster.agent/id "root"]
                   [?a :seon.cluster.agent/run ?r]] db))
     ;; THE CAPABILITY QUESTION: did a message the suffix would have sent
     ;; come into existence after the crash?
     :messages-to-peer
     (d/q '[:find [?content ...]
            :where [?m :seon.cluster.message/to ?to]
            [?to :seon.cluster.agent/id "peer"]
            [?m :seon.cluster.message/content ?content]]
          db)}))
