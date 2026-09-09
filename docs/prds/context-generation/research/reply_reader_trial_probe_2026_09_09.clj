(ns reply-reader-trial-probe-2026-09-09
  (:require [clojure.edn :as edn]
            [seon.db :as db]
            [seon.operator.runtime :as runtime]
            [seon.schema :as schema]
            [seon.turn :as turn]))

(defn probe!
  "Evaluate the exact saved first trial through real SCI; no provider call.
  Select a disposable fixture cluster explicitly. The all-datoms query also
  exercises the value renderer's complete-set ordering before AI elision."
  [cluster-name]
  (let [handle (:seon.turn.loop/cluster
                (get @runtime/running-instances cluster-name))
        trial (edn/read-string
                (slurp "docs/prds/context-generation/research/help_trial_2026_09_09.edn"))]
    (assert handle "The explicitly selected cluster must be running.")
    (schema/call-with-projection-state
      (:seon.sci.eval/projection-state handle)
      #(let [result (turn/preview-sources
                      {:seon.turn.loop/cluster handle
                       :seon.db/db (db/db (:seon.db/connection handle))
                       :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                       :seon.agent/id "juniper"
                       :seon.ns/name 'my.agents.juniper
                       :seon.cluster.reply/text
                       (get-in trial [:seon.trial/completion :seon.ai/text])
                       :seon.sci.admit/caps (:seon.sci.admit/caps handle)})]
         (if (:seon.error/kind result)
           result
           (mapv (fn [entry]
                   (select-keys (:seon.sci.eval/evaluation entry)
                                [:seon.cluster.eval/error :seon.eval/duration-ms
                                 :seon.cluster.eval/read-evidence]))
                 (:seon.turn.loop/evaluated-sources result)))))))

(defn observe
  "Read the fixture's current turn and fault boundary without changing it."
  [cluster-name]
  (let [handle (:seon.turn.loop/cluster
                (get @runtime/running-instances cluster-name))
        database (db/db (:seon.db/connection handle))]
    {:seon.test/basis (db/basis-t database)
     :seon.test/turns
     (db/q '[:find (pull ?turn [:db/id :seon.turn/id :seon.turn/opened-at
                               :seon.turn/closed-at :seon.turn/plan-digest])
             :where [?agent :seon.agent/id "juniper"]
                    [?turn :seon.turn/agent ?agent]] database)
     :seon.test/messages
     (db/q '[:find ?id ?content
             :where [?agent :seon.agent/id "juniper"]
                    [?message :seon.cluster.message/to ?agent]
                    [?message :seon.cluster.message/id ?id]
                    [?message :seon.cluster.message/content ?content]] database)}))
