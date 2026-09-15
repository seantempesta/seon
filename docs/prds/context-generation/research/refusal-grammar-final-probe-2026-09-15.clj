(require '[seon.operator] '[seon.schema] '[seon.sci.reader] '[seon.error]
         '[seon.ai.tokens] '[seon.sci.eval] '[seon.instrument] '[malli.core]
         '[seon.db])

; Read-only JVM proof: no SCI evaluation, transaction, or lifecycle operation.
(let [connection (seon.operator/connection "default")
      raw-database @connection
      projection (seon.schema/projection-from-database raw-database)
      database (vary-meta raw-database assoc :seon.schema/projection projection)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (let [unit {:seon.db/db database
                 :seon.render/profile
                 {:seon.render.profile/id :refusal.probe/profile
                  :seon.render.profile/token-budget 2048
                  :seon.render.profile/max-depth 8
                  :seon.render.profile/max-children 10
                  :seon.render.profile/max-string-length 256
                  :seon.render.profile/composition :multiline}}
           show (fn [value]
                  (let [text (seon.error/render-ai (assoc unit :seon.render/value value))]
                    {:refusal.probe/text text
                     :refusal.probe/tokens (seon.ai.tokens/estimate text)}))
           reader-cases
           (mapv
            (fn [source]
              (let [events (seon.sci.reader/read
                            {:seon.sci.reader/text source
                             :seon.config.eval.result/max-source 1000})]
                (assoc (show (:seon.sci.reader/error (first events)))
                       :refusal.probe/events (count events))))
            ["(my.plan/current! {:my.plan/item/id \"juniper/define\"})"
             "Gate:\narguments: wrong\nexpected: a vector\nactual: a list\n(+ 1 2)"])
           generic (seon.error/diagnostic
                    {:seon.error/kind :seon.db/invalid-read
                     :seon.error/message "Invalid selector."
                     :seon.error/diagnostic-layer :database-read
                     :seon.error/diagnostic-operation 'seon.db/pull
                     :seon.error/diagnostic-member :selector
                     :seon.error/diagnostic-expected :string
                     :seon.error/diagnostic-offending 42
                     :seon.error/diagnostic-cause :invalid-selector
                     :seon.error/diagnostic-evidence {}})]
       {:refusal.probe/sparse-write
        (select-keys (#'seon.db/write-error
                      database projection
                      [{:seon.fn/sym "seon.id/id" :seon.fn/doc "incomplete"}])
                     [:seon.error/kind :seon.error/message])
        :refusal.probe/retention
        (let [object {:probe/value "retained"}
              refusal (#'seon.instrument/violation
                       {} :malli.core/invalid-input
                       {:fn-name 'seon.id/id
                        :input (malli.core/schema [:cat :int]) :args [object]})
              data (:seon.error/data refusal)]
          {:same-object (identical? object (first (:seon.error/diagnostic-offending data)))
           :same-problem-object (identical? object (get-in data [:seon.error/problems 0 :seon.error/offending]))
           :serialized-args-present (contains? data :seon.instrument/args)})
        :refusal.probe/readers reader-cases
        :refusal.probe/diagnostic (show generic)
        :refusal.probe/documentation
        (select-keys (seon.sci.eval/documentation-value database 'seon.db/pull 'seon.db/pull)
                     [:in :out])}))))
