; Set seon.publish-cost.parent-root to the committed parent archive before evaluating.
(let [directory (System/getProperty "seon.publish-cost.parent-root")
      parent-forms (binding [*ns* (the-ns 'seon.cluster)]
                     (with-open [r (java.io.PushbackReader. (clojure.java.io/reader (str directory "/src/seon/cluster.clj")))]
                       (loop [found {}]
                         (let [f (read {:eof ::end} r)]
                           (if (= ::end f) found
                             (recur (if (and (seq? f) (#{'defn 'defn-} (first f)) (#{'analyzer-reach 'selected-analyzer-digest} (second f)))
                                      (assoc found (second f) (cons 'fn (drop-while #(not (vector? %)) (nnext f)))) found)))))))
      parent (binding [*ns* (the-ns 'seon.cluster)]
               (eval (list 'let ['analyzer-reach (get parent-forms 'analyzer-reach)] (get parent-forms 'selected-analyzer-digest))))
      store (:seon.store/store (get @seon.operator.runtime/running-instances "probe"))
      database (seon.cluster.source/database store (:seon.source/commit-id (seon.cluster.source/current store)))
      files (into {} (seon.db/q '[:find ?p ?d :where [?f :seon.fn.file/relative-path ?p] [?f :seon.fn.file/digest ?d]] database))
      entities (seon.fn/file-entities database ["src/seon/fn.clj"] :seon.fn/file)
      zero (apply str (repeat 64 "0"))
      changed (mapv #(cond-> % (= 'seon.fn/analyze-entities (:seon.fn/sym %)) (assoc :seon.program/definition-digest zero :seon.fn/calls #{})) entities)
      candidate (assoc files "src/seon/fn.clj" zero)
      scenarios [[#{} [] files files]
                 [#{"src/seon/fn.clj"} entities files files]
                 [#{"src/seon/fn.clj"} changed candidate files]
                 [#{"src/seon/fn.clj"} [] (dissoc files "src/seon/fn.clj") files]
                 [#{"src/seon/fn.clj"} changed candidate candidate]
                 [#{"src/seon/fn.clj"} [] files files]
                 [#{"src/seon/fn.clj"} entities files candidate]]]
  (try
    (mapv (fn [args]
            (let [old (apply parent database args) new (apply #'seon.cluster/selected-analyzer-digest database args)]
              {:equal? (= old new) :parent old :change new})) scenarios)
    (finally (datahike.api/release-materialized-db database))))
