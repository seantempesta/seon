;; One read-only evaluation in the existing default JVM. Compile the actual
;; before/after owner forms into local closures; do not replace any runtime Var.
(let [historical (let [process (.start (ProcessBuilder.
                                       ["git" "show" "3f0be21ed:src/seon/fn.clj"]))
                       source (with-open [stream (.getInputStream process)] (slurp stream))]
                   (when-not (zero? (.waitFor process))
                     (throw (ex-info "Cannot read historical owner" {})))
                   source)
      owner-forms
      (fn [source]
        (binding [*ns* (the-ns 'seon.fn)]
          (with-open [reader (java.io.PushbackReader. (java.io.StringReader. source))]
            (loop [forms {}]
              (let [form (read {:eof nil} reader)]
                (if (nil? form) forms
                    (recur (if (and (seq? form)
                                    (#{'gate-set-in 'gate-sets} (second form)))
                             (assoc forms (second form) form) forms))))))))
      anonymous (fn [form] (cons 'fn (drop-while #(not (vector? %)) (drop 2 form))))
      compile-owner
      (fn [source]
        (let [forms (owner-forms source)]
          (binding [*ns* (the-ns 'seon.fn)]
            (eval (list 'let ['gate-set-in (anonymous (get forms 'gate-set-in))]
                        (anonymous (get forms 'gate-sets)))))))
      before (compile-owner historical)
      after (compile-owner (slurp "src/seon/fn.clj"))
      database (seon.db/db (seon.operator/connection "default"))
      candidates (mapv first ((ns-resolve 'seon.issue.detect 'declarations) database nil))
      start (System/nanoTime)
      old-selection (before database candidates)
      before-ms (/ (- (System/nanoTime) start) 1e6)
      start (System/nanoTime)
      new-selection (after database candidates)
      after-ms (/ (- (System/nanoTime) start) 1e6)]
  {:basis (seon.db/basis-t database)
   :candidates (count candidates)
   :reference-facts (count (seon.db/datoms database :aevt :seon.fn/references))
   :before-ms before-ms :after-ms after-ms
   :before-error (:seon.error/kind old-selection)
   :after-error (:seon.error/kind new-selection)
   :widened (count (filter (fn [s] (< (count (get old-selection s))
                                     (count (get new-selection s)))) candidates))
   :lost-tests (reduce + (map (fn [s]
                               (count (remove (set (get new-selection s))
                                              (get old-selection s)))) candidates))})
