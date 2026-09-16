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
    (doseq [test-symbol tests]
      (let [database (seon.db/db connection)
            result (try
                     (#'seon.test/with-test-loader
                      (fn [] (require (symbol (namespace (symbol test-symbol))) :reload)))
                     (seon.test/run
                      (#'seon.test/resolve-test (symbol test-symbol)) connection
                      {:seon.db/db database
                       :seon.test.run/provenance (seon.test.runner/provenance database)
                       :seon.test/remaining-ms 180000})
                     (catch Throwable failure
                       {:seon.test/sym test-symbol
                        :seon.error/message (ex-message failure)
                        :seon.error/data (ex-data failure)}))]
        (swap! call-graph-verification update :seon.test/results conj result)
        (spit "tmp/call-graph-fidelity/verification-results.edn" (pr-str @call-graph-verification))))
    (swap! call-graph-verification assoc :seon.fn/complete? true)))
