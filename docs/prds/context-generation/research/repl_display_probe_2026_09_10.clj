;; Run with load-file through default's MCP JVM evaluation.
(require 'clojure.edn 'seon.operator 'seon.schema 'seon.render.value
         'seon.render 'seon.config 'seon.print)
(let [connection (seon.operator/connection "default")
      database @connection
      projection (seon.schema/projection-from-database database)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [raw [{:seon.config.ai.retry/base-delay-ms 500
                 :seon.config.ai.retry/maximum-retries 2
                 :seon.config.ai.retry/maximum-delay-ms 4000
                 :seon.config.ai.retry/maximum-total-delay-ms 3000
                 :seon.config.ai.retry/multiplier 2.0
                 :seon.config.ai.retry/jitter-fraction 0.25}]
           prepared (seon.render.value/prepare
                     {:seon.render/value raw
                      :seon.render.value/root [:seon.agent/id "juniper"]
                      :seon.render/profile
                      (seon.render/agent-render-profile
                       (seon.config/effective database "default"))})
           before (seon.print/emit-text
                   (:seon.render.value/tree prepared)
                   (assoc (:seon.render.value/options prepared)
                          :seon.print/width 80))
           after (seon.render.value/render-ai-data prepared)]
       {:seon.repl-display/before before
        :seon.repl-display/after after
        :seon.repl-display/before-bytes (alength (.getBytes before "UTF-8"))
        :seon.repl-display/after-bytes (alength (.getBytes after "UTF-8"))
        :seon.repl-display/value-equal?
        (= (clojure.edn/read-string before) (clojure.edn/read-string after))}))))
