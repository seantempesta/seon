;; Compile a candidate tree's packaged schema population and the contracts of
;; named source files against it, as build-projection does at boot. Defines
;; nothing. Bind `root` and `files` by editing the two lines below, or eval
;; with (let [root .. files ..] ...) wrapping.
(fn [root files]
  (let [t0 (System/nanoTime)
        dir (java.io.File. (str root "/resources/seon/schemas"))
        declared (reduce (fn [acc ^java.io.File f]
                           (merge acc (clojure.edn/read-string (slurp f))))
                         {}
                         (sort-by #(.getName ^java.io.File %)
                                  (filter #(.endsWith (.getName ^java.io.File %) ".edn") (file-seq dir))))
        forms (seon.schema.edn/derive-config-forms declared)
        contracts (into {}
                        (for [file files
                              :let [text (slurp (str root "/" file))
                                    rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))
                                    ns-form (binding [*read-eval* false] (read {:eof ::eof :read-cond :allow} rdr))
                                    ns-name (second ns-form)
                                    ;; ::alias/k resolves through the loaded namespace's aliases.
                                    _ (when-not (find-ns ns-name) (require ns-name))
                                    read-all (binding [*read-eval* false *ns* (or (find-ns ns-name) *ns*)]
                                               (loop [out []]
                                                 (let [f (read {:eof ::eof :read-cond :allow} rdr)]
                                                   (if (= ::eof f) out (recur (conj out f))))))]
                              form read-all
                              :when (and (seq? form) (#{'defn 'defn-} (first form)))
                              :let [attr (first (filter map? (take 3 (drop 2 form))))
                                    contract (:malli/schema attr)]
                              :when contract]
                          [(symbol (str ns-name) (str (second form))) contract]))
        projection (try (seon.schema/build-projection forms contracts)
                        (catch Throwable t {:refused (ex-message t) :data (select-keys (ex-data t) [:seon.schema/key :seon.schema/invalid-schema :seon.schema/member])}))]
    {:schemas (count forms) :contracts (count contracts)
     :compiled? (not (contains? projection :refused))
     :refusal (when (:refused projection) projection)
     :ms (/ (- (System/nanoTime) t0) 1e6)}))
