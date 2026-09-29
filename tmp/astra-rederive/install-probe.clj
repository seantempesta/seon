(let [source-ns (the-ns 'seon.cluster)
      scratch (create-ns 'scratch.astra-rederive)
      names '#{selected-analyzer-digest full-source-refresh! rehearse-source!}
      forms (binding [*ns* source-ns]
              (with-open [r (java.io.PushbackReader. (clojure.java.io/reader "/Users/sean/src/seon/src/seon/cluster.clj"))]
                (loop [forms []]
                  (let [form (read {:eof ::eof} r)]
                    (if (= ::eof form) forms
                      (recur (cond-> forms
                               (and (seq? form) (#{'defn 'defn-} (first form)) (names (second form)))
                               (conj form))))))))]
  (binding [*ns* scratch]
    (clojure.core/refer 'clojure.core)
    (doseq [[alias target] (ns-aliases source-ns)] (clojure.core/alias alias (ns-name target)))
    (doseq [[_ type] (ns-imports source-ns)] (.importClass scratch type))
    (eval (cons 'declare names))
    (letfn [(rewrite [form]
              (cond
                (and (seq? form) (= 'quote (first form))) form
                (symbol? form)
                (let [v (ns-resolve source-ns form)]
                  (if (and (var? v) (= source-ns (:ns (meta v))))
                    (if (names (:name (meta v)))
                      (symbol (str (ns-name scratch)) (str (:name (meta v))))
                      (list 'deref (list 'var (symbol "seon.cluster" (str (:name (meta v)))))))
                    form))
                :else (clojure.walk/walk rewrite identity form)))]
      (doseq [form forms]
        (eval (list* (first form) (second form) (map rewrite (drop 2 form)))))))
  {:scratch (ns-name scratch) :definitions names})
