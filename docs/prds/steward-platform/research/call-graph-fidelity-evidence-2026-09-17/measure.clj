(let [d (seon.db/db (seon.operator/connection "default"))
      fs (seon.db/q '[:find ?e ?s :where [?e :seon.fn/sym ?s]] d)
      incoming (set (seon.db/q '[:find [?e ...] :where [_ :seon.fn/calls ?e]] d))
      cap (seon.db/q '[:find [?s ...] :where [_ :seon.fn/capability-fn ?h] [?h :seon.fn/sym ?s]] d)
      checked-reach (fn [s] (let [r (seon.fn/tests-reaching d s)]
                              (when (:seon.error/kind r)
                                (throw (ex-info "Reach refused" r))) r))
      _ (doseq [r [fs cap incoming]]
          (when (:seon.error/kind r) (throw (ex-info "Measurement refused" r))))
      start (System/nanoTime)
      missing (seon.fn/functions-without-tests d)
      elapsed (/ (- (System/nanoTime) start) 1e6)]
  (when (:seon.error/kind missing) (throw (ex-info "Coverage query refused" missing)))
  {:seon.fn/total (count fs)
   :seon.fn/no-incoming (count (remove #(incoming (first %)) fs))
   :seon.fn/public-without-tests (count missing)
   :seon.fn/public-query-ms elapsed
   :seon.fn/capability-zero (count (filter #(empty? (checked-reach %)) cap))
   :seon.fn/print-zero (count (filter #(and (= "seon.print" (namespace (symbol (second %))))
                                          (empty? (checked-reach (second %)))) fs))
   :seon.fn/gate-costs
   (mapv (fn [s] (let [t (System/nanoTime) r (seon.fn/gate-set d s)]
                  (when (:seon.error/kind r) (throw (ex-info "Gate-set refused" r)))
                  [s (count r) (/ (- (System/nanoTime) t) 1e6)]))
         ["seon.id/digest" "seon.print/emit" "seon.fs.jvm/read" "seon.db/q"])})
