(require '[seon.operator]
         '[seon.schema]
         '[seon.sci.reader]
         '[seon.error]
         '[seon.ai.tokens])

; Read-only JVM probe. No SCI evaluation, transaction, or lifecycle operation.
(let [connection (seon.operator/connection "default")
      projection (seon.schema/projection-from-database @connection)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [source "(my.plan/current! {:my.plan/item/id \"juniper/define\"})"
           events (seon.sci.reader/read
                   {:seon.sci.reader/text source
                    :seon.config.eval.result/max-source 1000})
           refusal (:seon.sci.reader/error (first events))
           shown (seon.error/render-ai refusal)
           parser-data
           (try
             (#'seon.sci.reader/read-events
              source {:seon.sci.reader/ns 'user
                      :seon.sci.reader/features #{:clj}})
             (catch Throwable failure
               (mapv #(select-keys (ex-data %) [:type :line :column :row :col :expr])
                     (take-while some? (iterate ex-cause failure)))))
           generic
           (seon.error/diagnostic
            {:seon.error/kind :seon.db/invalid-read
             :seon.error/message "Invalid input."
             :seon.error/diagnostic-layer :database-read
             :seon.error/diagnostic-operation 'seon.db/pull
             :seon.error/diagnostic-member :selector
             :seon.error/diagnostic-expected :string
             :seon.error/diagnostic-offending 42
             :seon.error/diagnostic-cause :seon.db/invalid-read
             :seon.error/diagnostic-evidence {}})
           generic-shown (seon.error/render-ai generic)]
       {:refusal.probe/reader refusal
        :refusal.probe/parser-data parser-data
        :refusal.probe/shown shown
        :refusal.probe/utf8-bytes (alength (.getBytes shown "UTF-8"))
        :refusal.probe/estimated-tokens (seon.ai.tokens/estimate shown)
        :refusal.probe/generic-shown generic-shown
        :refusal.probe/generic-utf8-bytes
        (alength (.getBytes generic-shown "UTF-8"))}))))
