;; Run through MCP JVM evaluation only on the disposable root-cluster instance.
;; Reseed the opening with the existing compaction/system-turn owners, then
;; use the provider's own prompt function. No provider request is sent.
(require 'seon.operator.runtime 'seon.db 'seon.turn 'seon.cluster.prompt
         'seon.eval 'seon.render 'seon.config 'seon.schema 'seon.env)
(let [instance (get @seon.operator.runtime/running-instances "root-cluster")
      cluster (:seon.turn.loop/cluster instance)
      connection (:seon.db/connection cluster)
      checked (fn [result]
                (when (:seon.error/kind result)
                  (throw (ex-info "Root capture refused" result)))
                result)
      _ (checked (seon.db/transact!
                  connection
                  [{:seon.config/agent [:seon.agent/id "root"]
                    :seon.config.ai/no-provider true}]))
      _ (checked (seon.turn/compact! {:seon.db/connection connection
                                    :seon.agent/id "root"}))
      opening (seon.schema/call-with-projection
               (:seon.schema/projection (seon.env/of (:seon.sci.eval/ctx cluster)))
               #(checked (seon.turn/system-turn
                           {:seon.turn.loop/cluster cluster
                            :seon.agent/id "root" :seon.turn/write? true})))
      request {:seon.turn/id (:seon.turn/id opening)
               :seon.agent/id "root"
               :seon.db/connection connection
               :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
               :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
               :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms cluster)
               :seon.config/on-core-error (:seon.config/on-core-error cluster)}
      prompt (checked
              (seon.schema/call-with-projection
               (:seon.schema/projection (seon.env/of (:seon.sci.eval/ctx cluster)))
               #(seon.cluster.prompt/prompt
                 @connection
                 (assoc request :seon.render/profile
                        (seon.render/agent-render-profile
                         (seon.config/effective @connection "root-cluster"))))))
      text (:seon.cluster.prompt/text prompt)
      path "docs/prds/context-generation/research/root-cluster-prompt-2026-09-09.txt"]
  (spit path text)
  {:seon.cluster.status/capture path
   :seon.cluster.status/bytes (alength (.getBytes text "UTF-8"))
   :seon.cluster.status/evaluations (count (seon.eval/of-agent @connection "root"))
   :seon.cluster.status/turn (:seon.turn/id opening)})
