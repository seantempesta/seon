; Evaluate this one form in default's JVM. It compiles the exact owned source
; as lexical functions: no installed JVM Var or live base context is replaced.
(do
 (require '[clojure.java.io] '[seon.db] '[seon.operator]
          '[seon.operator.runtime] '[seon.env] '[seon.schema] '[seon.program]
          '[seon.sci.eval] '[seon.config] '[seon.id] '[sci.core])
 (let [owner (the-ns 'seon.sci.eval)
      wanted '#{build-base-ctx install-jvm-root! install-function-from-database!
                install-row! install-first-party-namespaces! acquire-program!
                base-ctx same-program-root? regenerate-agent-context! fork-for-turn
                function-doc-map documentation-value}
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
                                 ['base-ctx 'fork-for-turn 'install-row! 'documentation-value])))
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
                          :seon.sci.kernel/program-snapshot seon.env/state-carrier])
      started (System/nanoTime)
      forked ((second constructors)
              {:seon.sci.eval/ctx base :seon.sci.eval/agent-ctx disposable
               :seon.db/db database :seon.agent/id "s3-provenance-b"})
      next (:seon.sci.eval/ctx forked)
      private-ms (/ (- (System/nanoTime) started) 1e6)
      target (symbol "seon.eval.drive/uuid-text")
      retained-root? (identical? @(sci.core/resolve base target)
                                 @(sci.core/resolve next target))
      fresh (:seon.sci.eval/ctx
             ((second constructors)
              {:seon.sci.eval/ctx base :seon.db/db database
               :seon.agent/id "s3-provenance-c"}))
      fresh-root? (identical? @(sci.core/resolve base target)
                              @(sci.core/resolve fresh target))
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
                  :seon.config/on-core-error :panic})
      unavailable (filter #(= :unavailable (:seon.sci.eval/load-state %))
                          (get-in base [:seon.sci.eval/acquisition :seon.sci.eval/load-results]))
      src-identities (set (seon.db/q '[:find [?name ...] :where
                                       [?f :seon.fn/sym ?name] [?f :seon.fn/file ?file]
                                       [?file :seon.fn.file/relative-root "src"]] database))
      unavailable-src (filter #(src-identities (:seon.fn/sym %)) unavailable)
      current (seon.db/db (seon.operator/connection "default"))
      core-start (System/nanoTime)
      core-base ((first constructors) current)
      core-ms (/ (- (System/nanoTime) core-start) 1e6)
      reverted (:seon.sci.eval/ctx
                ((second constructors)
                 {:seon.sci.eval/ctx core-base :seon.sci.eval/agent-ctx next
                  :seon.db/db current :seon.agent/id "s3-provenance-b"}))]
  {:s3/basis (seon.db/basis-t database)
   :s3/function-identities (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/sym]] database)
   :s3/sourced-functions (seon.db/q '[:find (count ?f) . :where [?f :seon.fn/source]] database)
   :s3/base-ms built :s3/private-ms private-ms
   :s3/reinstall-ms install-ms :s3/install-state (:seon.sci.eval/load-state installed)
   :s3/requested-as-of 536871073
   :s3/value (:seon.sci.admit/value evaluated)
   :s3/core-root-identical (identical? @#'seon.id/valid?
                                      @(sci.core/resolve base 'seon.id/valid?))
   :s3/retained-root-identical retained-root?
   :s3/fresh-root-identical fresh-root?
   :s3/overrides (seon.program/overrides database)
   :s3/doc-note (:seon.schema.admission/note
                 ((nth constructors 3) database target target))
   :s3/core-base-ms core-ms
   :s3/current-admission (:seon.schema.admission/source
                          (seon.db/pull current [:seon.schema.admission/source]
                                        [:seon.fn/sym (str target)]))
   :s3/reverted-root-identical
   (identical? @(ns-resolve 'seon.eval.drive 'uuid-text)
               @(sci.core/resolve reverted target))
   :s3/load-results (filterv #(not= :unavailable (:seon.sci.eval/load-state %))
                             (get-in base [:seon.sci.eval/acquisition :seon.sci.eval/load-results]))
   :s3/unavailable-count (count unavailable)
   :s3/unavailable-src-count (count unavailable-src)
   :s3/unavailable-src-sample (vec (take 8 unavailable-src))
   :s3/private-state (:seon.sci.eval/private-state forked)
   :s3/context-identical (identical? disposable next)
   :s3/result-store-identical (identical? (:seon.sci.eval/result-objects previous)
                                         (:seon.sci.eval/result-objects next))}))
