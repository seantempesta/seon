(let [database (seon.db/db (seon.operator/connection "default"))
      subjects '[seon.cluster.reply/sources seon.id/symbol-in seon.id/valid?
                 seon.render.test/render-html seon.db/pull-many seon.db/transact!]
      started (System/nanoTime)
      selected (seon.fn/gate-sets database subjects)
      elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
  {:seon.probe/basis (seon.db/basis-t database)
   :seon.probe/subjects-present
   (seon.db/q '[:find [?subject ...] :in $ [?subject ...]
               :where [_ :seon.fn/sym ?subject]] database subjects)
   :seon.probe/phantom-calls
   (seon.db/q '[:find ?caller ?target :in $ [[?caller ?target]]
               :where [?e :seon.fn/sym ?caller] [?e :seon.fn/calls ?target]]
             database
             '[[seon.db/pull-many seon.db/transact!]
               [seon.db/write-render-target-error seon.render.test/render-html]])
   :seon.probe/phantom-call-subjects-present
   (seon.db/q '[:find [?subject ...] :in $ [?subject ...]
               :where [_ :seon.fn/sym ?subject]] database
             '[seon.db/pull-many seon.db/write-render-target-error])
   :seon.probe/invokers
   (seon.db/q '[:find ?caller ?attribute
               :where [?e :seon.fn/sym ?caller] [?e :seon.fn/invokes ?attribute]] database)
   :seon.probe/render-target-retained
   (seon.db/q '[:find ?e
               :where [?e :seon.fn/sym seon.render/invoke-selected]
                      [?e :seon.fn/calls seon.render.test/render-html]] database)
   :seon.probe/gate-sets-ms elapsed-ms
   :seon.probe/selection
   (if (and (map? selected) (every? #(vector? (get selected %)) subjects))
     (mapv (fn [subject]
             {:seon.fn/sym subject :seon.probe/test-count (count (get selected subject))})
           subjects)
     selected)})
