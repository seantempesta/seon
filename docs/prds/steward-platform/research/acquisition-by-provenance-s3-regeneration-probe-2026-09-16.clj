; Evaluate this one form in default's JVM. It compiles the exact owned source
; as lexical functions: no installed JVM Var or live base context is replaced.
(let [owner (the-ns 'seon.sci.eval)
      wanted '#{build-base-ctx install-jvm-root! install-function-from-database!
                install-row! install-first-party-namespaces! acquire-program!
                base-ctx same-program-root? regenerate-agent-context!}
      forms (binding [*ns* owner]
              (with-open [reader (java.io.PushbackReader.
                                  (clojure.java.io/reader "src/seon/sci/eval.clj"))]
                (loop [result []]
                  (let [form (read {:eof ::eof} reader)]
                    (if (= ::eof form) result (recur (conj result form)))))))
      declarations (for [form forms
                         :when (and (seq? form) (#{'defn 'defn-} (first form))
                                    (wanted (second form)))]
                     (let [body (drop 2 form)
                           body (if (string? (first body)) (rest body) body)
                           body (if (map? (first body)) (rest body) body)]
                       (cons (second form) body)))
      constructors (binding [*ns* owner]
                     (eval (list 'letfn (vec declarations)
                                 ['base-ctx 'regenerate-agent-context! 'install-row!])))
      database (seon.db/as-of (seon.db/db (seon.operator/connection "default"))
                              536871073)
      start (System/nanoTime)
      base ((first constructors) database)
      built (/ (- (System/nanoTime) start) 1e6)
      instance (get @seon.operator.runtime/running-instances "default")
      previous (get @(:seon.agent/context-state (:seon.turn.loop/cluster instance))
                    "s3-provenance-b")
      disposable (reduce (fn [ctx key] (update ctx key #(atom @%))) previous
                         [:env :seon.sci.eval/base-bindings
                          :seon.sci.kernel/installed-functions
                          :seon.sci.kernel/program-snapshot])
      started (System/nanoTime)
      next ((second constructors) disposable base)
      private-ms (/ (- (System/nanoTime) started) 1e6)
      admitted (seon.db/pull database
                             '[:seon.fn/sym :seon.fn/source :seon.schema.admission/source]
                             [:seon.fn/sym "seon.eval.drive/uuid-text"])
      install-start (System/nanoTime)
      installed (seon.schema/call-with-projection
                 (:seon.schema/projection base)
                 #((nth constructors 2)
                   {:seon.sci.eval/ctx base :seon.db/db database
                    :seon.program/row (assoc (dissoc admitted :db/id)
                                            :seon.fn/ns [:seon.ns/name 'seon.eval.drive])}))
      install-ms (/ (- (System/nanoTime) install-start) 1e6)
      evaluated (seon.sci.eval/evaluate
                 {:seon.sci.eval/ctx base
                  :seon.db/db database
                  :seon.schema/projection (:seon.schema/projection base)
                  :seon.cluster.eval/source "(seon.eval.drive/uuid-text)"
                  :seon.sci.admit/caps (seon.config/result-caps (seon.config/effective database "default"))
                  :seon.sci.eval/time-limit-ms 2000
                  :seon.config/on-core-error :panic})]
  {:s3/basis (seon.db/basis-t database)
   :s3/functions (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/source]] database)
   :s3/base-ms built :s3/private-ms private-ms
   :s3/reinstall-ms install-ms :s3/install-state (:seon.sci.eval/load-state installed)
   :s3/requested-as-of 536871073
   :s3/value (:seon.sci.admit/value evaluated)
   :s3/core-root-identical (identical? @#'seon.id/valid?
                                      @(sci.core/resolve base 'seon.id/valid?))
   :s3/load-results (get-in base [:seon.sci.eval/acquisition :seon.sci.eval/load-results])
   :s3/private-state (:seon.sci.eval/private-state next)
   :s3/result-store-identical (identical? (:seon.sci.eval/result-objects previous)
                                         (:seon.sci.eval/result-objects next))})
