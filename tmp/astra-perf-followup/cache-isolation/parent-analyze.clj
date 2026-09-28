(defn analyze
  "Analyze captured source text, complete source roots, or individual files.

  `::sources` is the one race-free form: a map of canonical source path to the
  exact text the caller captured. clj-kondo reads that text from a private
  mirror instead of re-reading the file, so the rows and columns it reports
  and the text a caller slices with them are the same bytes even while the
  file is being edited. `::paths` remains for whole directories, the
  synthesized stdin buffer, and callers that own no capture."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [::paths {:optional true} [:vector {:min 1} [:string {:min 1}]]]
      [::cache-root {:optional true} :string]
      [::config-root {:optional true} :string]
      [::hooks {:optional true :description "clj-kondo :hooks merged into the analysis config (expansion-hooks)."} :map]
      [::sources {:optional true}
       [:map-of {:min 1} [:string {:min 1}] :string]]]]
    [:map
     [::namespace-definitions [:vector :map]]
     [::namespace-usages [:vector :map]]
     [::var-definitions [:vector :map]]
     [::var-usages [:vector :map]]
     [::java-class-usages [:vector :map]]
     [::protocol-impls [:vector :map]]
     [::keywords [:vector :map]]
     [::findings [:vector :map]]
     [::unanalyzed-namespaces [:set :symbol]]
     [::mentioned-symbols [:set :qualified-symbol]]]]}
  [{::keys [paths sources cache-root config-root hooks]}]
  (when-not (or (seq paths) (seq sources))
    (throw (ex-info "Analysis requires either captured sources or paths."
                    {})))
  ;; A trusted diagnostic must preserve each call as one coherent record.
  ;; clj-kondo's parallel analysis has combined an outer call's location and
  ;; arity with an inner call's resolved var, then emitted the corruption
  ;; twice. Its sequential path retains the same linters and dependency cache.
  (let [mirror-root (when (seq sources)
                      (let [parent (io/file "." mirror-directory)]
                        (.mkdirs parent)
                        (.toFile
                         (java.nio.file.Files/createTempDirectory
                          (.toPath parent) "analysis"
                          (into-array java.nio.file.attribute.FileAttribute [])))))
        lint-paths (if mirror-root (write-mirror! mirror-root sources) paths)
        stdin-source (when (some #{"-"} paths) (slurp *in*))
        result (try
                 (binding [*in* (if stdin-source
                                 (java.io.BufferedReader. (java.io.StringReader. stdin-source))
                                 *in*)]
                 (invoke-kondo
                  ;; ONE cache rule, decided here: only the checkout's own
                  ;; declared source may read or write the shared dependency
                  ;; cache. A synthesized stdin buffer (2026-08-29) and a
                  ;; fixture root under `tmp/` (2026-09-16) are the same
                  ;; defect — kondo keys the cache by namespace name, so a
                  ;; decoy `seon.error` would answer for the real one in every
                  ;; later analysis in this JVM. A mirror is not a decoy: it
                  ;; carries the checkout file's own captured bytes, and
                  ;; `checkout-source?` reads the source path back out of it.
                  (cond-> {:lint lint-paths :config (cond-> publication-config hooks (assoc :hooks hooks))}
                    cache-root (assoc :cache-dir cache-root)
                    config-root (assoc :config-dir config-root)
                    (and (nil? cache-root) (not (every? checkout-source? lint-paths)))
                    (assoc :cache false))))
                 (finally
                   (when mirror-root (delete-tree! mirror-root))))
        raw-analysis (:analysis result)
        spans (into {}
                    (map (fn [filename]
                           [filename
                            (parsed-facts filename
                                          (or (get sources (analyzed-source-path filename))
                                              stdin-source (slurp filename)))]))
                    ;; Every captured source is parsed here, once: its facts
                    ;; and its mentioned symbols (`::mentioned-symbols`).
                    (distinct (concat (when mirror-root lint-paths)
                                      (map :filename (concat (:var-usages raw-analysis)
                                                             (:var-definitions raw-analysis))))))
        raw-analysis (update raw-analysis :var-definitions
                             (fn [definitions]
                               (mapv (fn [entry]
                                       (cond-> entry
                                         (and (= 'clojure.core/def (:defined-by entry))
                                              (get-in spans [(:filename entry) ::literal-defs
                                                             [(:row entry) (:col entry)]]))
                                         (assoc :literal-def? true)
                                         (get-in spans [(:filename entry) ::task-maps-after-name
                                                        [(:row entry) (:col entry)]])
                                         (assoc :task-map-after-name? true)
                                         (nil? (:meta entry))
                                         (as-> entry
                                           (if-let [attributes (get-in spans [(:filename entry) ::namespaced-attribute-maps
                                                                              [(:row entry) (:col entry)]])]
                                             (assoc entry :meta attributes)
                                             entry)))) definitions)))
        quotes (mapcat ::var-quotes (vals spans))
        require-forms (into [] (comp (mapcat ::require-forms) (map location)) (vals spans))
        attributed (group-by #(if (:class %) :java-class-usages :var-usages)
                             (attributed-usages (assoc raw-analysis :var-quotes quotes)))
        analysis (merge raw-analysis attributed)]
    (with-namespace-clauses
    require-forms
    {::namespace-definitions
     (filterv jvm-entry?
              (normalized-entries analysis :namespace-definitions namespace-definition))
     ::namespace-usages
     (filterv jvm-entry?
              (normalized-entries analysis :namespace-usages namespace-usage))
     ::var-definitions
     (filterv jvm-entry?
              (normalized-entries analysis :var-definitions var-definition))
     ::var-usages
     (filterv jvm-entry?
              (normalized-entries analysis :var-usages var-usage))
     ::java-class-usages
     (filterv jvm-entry?
              (normalized-entries analysis :java-class-usages
                                  #(merge (location %)
                                          (present-values % [:class :method-name :call :lang
                                                             :from :from-var :import]))))
     ::protocol-impls
     (normalized-entries analysis :protocol-impls
                         #(merge (location %)
                                 (present-values % [:protocol-ns :protocol-name
                                                    :method-name :impl-ns
                                                    :defined-by :defined-by->lint-as])))
     ::keywords
     (filterv jvm-entry?
              (normalized-entries analysis :keywords keyword-usage))
     ::findings
     (->> (:findings result)
          (map finding)
          (filter jvm-entry?)
          (sort-by entry-order)
          vec)
     ::unanalyzed-namespaces (unanalyzed-namespaces (::cache-root result) raw-analysis)
     ::mentioned-symbols (into #{} (mapcat ::mentioned-symbols) (vals spans))})))
