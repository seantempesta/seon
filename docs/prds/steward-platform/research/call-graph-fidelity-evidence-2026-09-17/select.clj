(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      changed (seon.db/since (seon.db/history database) 536871668)
      paths ["src/seon/fn.clj" "src/seon/fn/analyzer.clj"
             "src/seon/test/selection.clj" "src/seon/test/runner.clj"]
      functions (seon.db/q '[:find [?symbol ...]
                            :in $ $changed [?path ...]
                            :where [$changed ?function :seon.fn/source]
                            [?function :seon.fn/file ?file]
                            [?file :seon.fn.file/relative-path ?path]
                            [?function :seon.fn/sym ?symbol]]
                          database changed paths)
      named (seon.db/q '[:find [?symbol ...]
                        :in $ [?name ...]
                        :where [?namespace :seon.ns/name ?name]
                        [?test :seon.test/ns ?namespace]
                        [?test :seon.test/sym ?symbol]]
                      database ['seon.fn-test 'seon.program-test])
      reached (mapv #(seon.fn/tests-reaching database %) functions)
      refusal (some #(when (:seon.error/kind %) %) (concat [functions named] reached))]
  (when refusal (throw (ex-info "Selection refused" refusal)))
  (when (empty? functions) (throw (ex-info "No changed functions observed" {})))
  (let [tests (vec (sort (set (concat named (mapcat identity reached)))))
        namespaces (distinct (map (comp symbol namespace symbol) tests))]
    (reset! call-graph-verification {:seon.fn/changed functions :seon.test/selected tests :seon.test/results []})
    (spit "tmp/call-graph-fidelity/verification-selection.edn" (pr-str @call-graph-verification))
    (select-keys @call-graph-verification [:seon.fn/changed :seon.test/selected])))
