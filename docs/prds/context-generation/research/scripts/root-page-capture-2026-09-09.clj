; Invoke the returned function through MCP JVM mode in the selected root.
; HTTP compact/system-turn actions happen separately; this capture only reads.
(require 'seon.operator.runtime 'seon.eval 'seon.db 'seon.render 'seon.config
         'seon.schema 'seon.env 'seon.cluster.prompt)
(fn [cluster-name output-path]
  (let [instance (get @seon.operator.runtime/running-instances cluster-name)
        cluster (:seon.turn.loop/cluster instance)
        connection (:seon.db/connection cluster)
        database @connection
        evaluations (seon.eval/of-agent database "root")
        request {:seon.agent/id "root"
                 :seon.turn/id
                 (:seon.turn/id
                  (seon.db/pull database [:seon.turn/id]
                                (get-in (last evaluations) [:seon.cluster.eval/run :db/id])))
                 :seon.db/connection connection
                 :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
                 :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                 :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms cluster)
                 :seon.config/on-core-error (:seon.config/on-core-error cluster)}
        prompt (seon.schema/call-with-projection
                (:seon.schema/projection (seon.env/of (:seon.sci.eval/ctx cluster)))
                #(seon.cluster.prompt/prompt
                  database (assoc request :seon.render/profile
                                  (seon.render/agent-render-profile
                                   (seon.config/effective database cluster-name)))))
        text (:seon.cluster.prompt/text prompt)]
    (when-not (and (seq evaluations) (string? text) (seq text))
      (throw (ex-info "Root prompt capture has no evaluations or prompt" prompt)))
    (spit output-path text)
    {:seon.probe/path output-path
     :seon.probe/bytes (alength (.getBytes text "UTF-8"))
     :seon.probe/evaluations (count evaluations)
     :seon.probe/errors (vec (keep :seon.cluster.eval/error evaluations))
     :seon.probe/sources (mapv :seon.cluster.eval/source evaluations)}))
