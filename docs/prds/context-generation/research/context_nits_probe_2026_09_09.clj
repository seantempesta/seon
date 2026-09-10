(ns context-nits-probe-2026-09-09
  (:require [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.note :as note]
            [seon.operator :as operator]
            [seon.operator.runtime :as runtime]
            [seon.render.transcript :as transcript]
            [seon.sci.eval :as evaluation]))

(defn capture!
  "Execute read-only forms on the adopted default and retain exact shown bytes."
  [label]
  (let [instance (get @runtime/running-instances "default")
        database @(operator/connection "default")
        handle (:seon.turn.loop/cluster instance)
        forms [(transcript/render-runtime-ai
                {:seon.db/db database :seon.render/value
                 (db/pull database '[*] [:seon.runtime/agent [:seon.agent/id "juniper"]])})
               (note/render-notes-ai {:seon.agent/id "juniper"})
               "(dir my.message)" "(doc my.message/send)"
               "(doc my.agent/done)" "(doc my.plan)" "(doc my.note)"]
        rows (mapv (fn [form]
                     (let [result (evaluation/evaluate
                                   (assoc handle :seon.db/db database
                                          :seon.cluster.eval/source form
                                          :seon.sci.eval/time-limit-ms 10000))]
                       (assert (not (:seon.cluster.eval/error result)) (pr-str result))
                       {:source form :value (:seon.sci.admit/value result)
                        :shown (:seon.eval/shown result)
                        :bytes (alength (.getBytes ^String (:seon.eval/shown result) "UTF-8"))})) forms)
        evidence {:adopted (db/q '[:find ?commit :where [?e :seon.source/commit-id ?commit]] database)
                  :published (source/current (:seon.store/store instance))
                  :rows rows}]
    (spit (str "docs/prds/context-generation/research/context-nits-" label "-2026-09-09.edn")
          (pr-str evidence))
    (assoc (dissoc evidence :rows) :shown-bytes (mapv :bytes rows))))

(defn capture-contract!
  "Verify a refused call teaches its documentation without sending a message."
  []
  (let [instance (get @runtime/running-instances "default")
        database @(operator/connection "default")
        result (evaluation/evaluate
                (assoc (:seon.turn.loop/cluster instance)
                       :seon.db/db database
                       :seon.cluster.eval/source
                       "(my.message/send {:my.message/to 42 :my.message/content \"Hello\"})"
                       :seon.sci.eval/time-limit-ms 10000))
        value (:seon.sci.admit/value result)
        evidence (select-keys result [:seon.sci.admit/value :seon.eval/shown
                                      :seon.cluster.eval/error :seon.eval/duration-ms])]
    (assert (= :seon.instrument/contract-violated (:seon.error/kind value)))
    (assert (= "Return an addressed message for the turn to deliver."
               (get-in value [:seon.error/doc :summary])) (pr-str evidence))
    (spit "docs/prds/context-generation/research/context-nits-contract-2026-09-09.edn"
          (pr-str evidence))
    {:shown-bytes (alength (.getBytes ^String (:seon.eval/shown result) "UTF-8"))
     :kind (:seon.error/kind value)}))
