(ns one-path-impl2.p1
  "P1 measurement: a preview system turn's stages for one agent, each timed on
  default's live value. (one-path-impl2.p1/stages \"root\")"
  (:require [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.turn :as turn]))

(defn- ms-since [t] (Math/round (/ (- (System/nanoTime) t) 1e6)))

(defn stages
  "Per-stage milliseconds of `seon.turn/system-turn`'s preview for `agent-id`,
  with each selected unit's status and preview time."
  [agent-id]
  (let [handle (:seon.turn.loop/cluster (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default"))
        t0 (System/nanoTime)
        database (db/db (:seon.db/connection handle))
        ns-name (sci.eval/agent-namespace database agent-id)
        t1 (System/nanoTime)
        declared (#'turn/declared-sources handle database agent-id ns-name)
        declared-ms (ms-since t1)
        t2 (System/nanoTime)
        latest (#'turn/latest-evaluations database agent-id)
        latest-ms (ms-since t2)
        t3 (System/nanoTime)
        plan (#'turn/system-plan database (:seon.turn/forms declared) latest)
        plan-ms (ms-since t3)
        selected (filterv #(not= :unchanged (:seon.turn/status %)) plan)
        t4 (System/nanoTime)
        units (mapv (fn [source]
                      (let [t (System/nanoTime)]
                        (when-not (:seon.turn/rendered source)
                          (turn/preview-sources {:seon.turn.loop/cluster handle :seon.db/db database
                                                 :seon.sci.eval/ctx (:seon.sci.eval/ctx handle) :seon.agent/id agent-id
                                                 :seon.ns/name (:seon.ns/name source)
                                                 :seon.cluster.reply/text (:seon.cluster.eval/source source)
                                                 :seon.sci.admit/caps (:seon.sci.admit/caps handle)}))
                        [(:seon.turn/status source) (boolean (:seon.turn/rendered source)) (ms-since t)
                         (let [s (:seon.cluster.eval/source source)] (subs s 0 (min 45 (count s))))]))
                    selected)]
    {:ms {:declared-sources declared-ms :latest-evaluations latest-ms :system-plan plan-ms
          :previews (ms-since t4) :total (ms-since t0)}
     :declared (count (:seon.turn/forms declared)) :kept (count latest)
     :plan (frequencies (map :seon.turn/status plan))
     :selected units}))
