; Evaluate forms individually through MCP jvm on default. Never reload dependency
; namespaces or test-support: default retains its original dependency classes.
; The two reducer function definitions can be evaluated over those classes.
(require '[clojure.java.io] '[datahike.core] '[seon.db] '[seon.fn]
         '[seon.operator] '[seon.schema] '[seon.test] '[seon.test.runner])

(binding [*ns* (the-ns 'datahike.db.transaction)]
  (with-open [reader (java.io.PushbackReader.
                     (clojure.java.io/reader
                      "reference-code/datahike/src/datahike/db/transaction.cljc"))]
    (loop [installed []]
      (let [form (read {:eof nil :read-cond :allow :features #{:clj}} reader)]
        (if form
          (if (and (seq? form) (#{'defn 'defn-} (first form))
                   (#{'validate-report 'transact-tx-data} (second form)))
            (do (eval form) (recur (conj installed (second form))))
            (recur installed))
          installed)))))

; Immutable live-data prototype, NOT a substitute for canonical fixture tests.
(let [database (seon.db/db (seon.operator/connection "default"))
      projection (seon.schema/projection-from-database database)
      validate (#'seon.db/write-report-validator projection)
      complete {:seon.schedule/id "f2-report-probe"
                :seon.schedule/expression "0 4 * * *"
                :seon.schedule/zone-id "UTC"}
      accepted (datahike.core/with database [complete]
                                   {:datahike/validate-report validate})
      after (:db-after accepted)
      updated (datahike.core/with after
                                 [{:seon.schedule/id "f2-report-probe"
                                   :seon.schedule/expression "7 4 * * *"}]
                                 {:datahike/validate-report validate})
      invalid (try
                (datahike.core/with database
                                   [[:db/add -1 :seon.schedule/id "f2-incomplete"]]
                                   {:datahike/validate-report validate})
                nil
                (catch Exception failure
                  (:datahike/validation-refusal (ex-data failure))))]
  {:f2/partial-retains-zone
   (= "UTC" (:seon.schedule/zone-id
              (seon.db/pull (:db-after updated) '[*]
                            [:seon.schedule/id "f2-report-probe"])))
   :f2/refusal (select-keys invalid
                            [:seon.error/kind :seon.db/attribute :seon.db/offending])
   :f2/entity (get-in invalid [:seon.error/data :seon.db/entity])})

; Read the actual live schema of function identities before choosing the argument
; type: the old default uses strings; the symbols lane changes this declaration.
(let [database (seon.db/db (seon.operator/connection "default"))
      changed ["seon.db/transact-call" "seon.db/write-map-error"
               "seon.db/carry-connection-projection-state!"]
      reach (mapv #(seon.fn/tests-reaching database %) changed)]
  {:f2/changed changed
   :f2/reach-counts (mapv #(if (vector? %) (count %) %) reach)
   :f2/selected (count (distinct (mapcat #(if (vector? %) % []) reach)))
   :f2/destroyer-count
   (count (seon.db/q '[:find ?function :where [?function :seon.fn/destroys _]]
                    database))})

; Canonical iteration. Reload ONLY the test namespace through its owning loader.
(do
  (#'seon.test/with-test-loader (fn [] (require 'seon.db-test :reload)))
  (def f2-final-run
    (future
      (let [connection (seon.operator/connection "default")]
        (seon.test/run
         (#'seon.test/resolve-test
          'seon.db-test/all-transaction-grammars-validate-the-resulting-entity)
         connection
         {:seon.test/remaining-ms 180000
          :seon.test.run/provenance
          (seon.test.runner/provenance (seon.db/db connection))}))))
  :f2/started)

(if (realized? f2-final-run) @f2-final-run :f2/running)

; After recording the result only.
(when (realized? f2-final-run) (ns-unmap 'user 'f2-final-run))
