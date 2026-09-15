; Evaluate this one form through MCP JVM mode on default. No database writes.
(require 'seon.operator 'seon.db 'seon.render)

(let [connection (seon.operator/connection "default")
      observations
      (mapv
       (fn [_]
         (let [before @connection
               start (System/nanoTime)
               http (.openConnection
                     (.toURL (java.net.URI. "http://127.0.0.1:7994/ns/my.agents.juniper/debug")))]
           (.setConnectTimeout http 30000)
           (.setReadTimeout http 30000)
           (try
             (let [status (.getResponseCode http)
                   body (with-open [stream (.getInputStream http)] (slurp stream))]
               {:seon.probe/status status
                :seon.probe/ms (/ (- (System/nanoTime) start) 1e6)
                :seon.probe/bytes (alength (.getBytes body "UTF-8"))
                :seon.probe/basis-before (seon.db/basis-t before)
                :seon.probe/basis-after (seon.db/basis-t @connection)
                :seon.probe/source (seon.render/source-generation @connection)})
             (finally (.disconnect http)))))
       (range 4))]
  {:seon.probe/warmup (first observations)
   :seon.probe/samples (subvec observations 1)})
