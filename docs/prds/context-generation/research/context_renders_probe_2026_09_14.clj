(ns context-renders-probe-2026-09-14
  "Read-only opening measurements against one captured default database."
  (:require [clojure.string :as str]
            [seon.ai.tokens :as tokens]
            [seon.db :as db]
            [seon.operator.runtime :as runtime]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.turn :as turn]))

(defn opening!
  "Save a read-only opening preview and its UTF-8 bytes and estimated tokens."
  [path]
  (let [handle (:seon.turn.loop/cluster (get @runtime/running-instances "default"))
        database @(:seon.db/connection handle)]
    (schema/call-with-projection-state
     (:seon.sci.eval/projection-state handle)
     (fn []
       (let [declared (#'turn/declared-sources handle database "juniper" 'my.agents.juniper)
             sources (:seon.turn/forms declared)
             _ (when-not (seq sources)
                 (throw (ex-info "Opening has no declared sources" declared)))
             entries (mapv
                      (fn [source]
                        (let [preview (turn/preview-sources
                                       {:seon.turn.loop/cluster handle :seon.db/db database
                                        :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                                        :seon.agent/id "juniper" :seon.ns/name 'my.agents.juniper
                                        :seon.cluster.reply/text (:seon.cluster.eval/source source)
                                        :seon.sci.admit/caps (:seon.sci.admit/caps handle)})
                              item (first (:seon.turn.loop/evaluated-sources preview))]
                          (when (or (:seon.error/kind preview) (nil? item))
                            (throw (ex-info "Opening preview unavailable" preview)))
                          (repl/text (merge (:seon.turn.loop/admitted-form item)
                                            (:seon.sci.eval/evaluation item)
                                            source)))) sources)
             text (str/join "\n\n" entries)
             result {:seon.probe/basis (db/basis-t database)
                     :seon.probe/evaluations (count entries)
                     :seon.probe/bytes (alength (.getBytes text "UTF-8"))
                     :seon.probe/tokens (tokens/estimate text)
                     :seon.probe/text text}]
         (spit path (pr-str result))
         (dissoc result :seon.probe/text))))))
