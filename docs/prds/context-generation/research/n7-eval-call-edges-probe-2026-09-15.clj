;; Evaluate these forms through MCP JVM mode on default, one at a time.
;; They do not operate the cluster lifecycle or call a provider.
;; The two seon.test/run forms record their results in default.
;; The observed default already had the canonical test dependencies loaded.
(require 'clojure.edn 'seon.fn 'seon.db 'seon.operator 'seon.test
         'seon.test-support 'seon.fn-test 'seon.operator.runtime
         'seon.cluster.source)

;; This exact ordinary-form request refused before the change.
(let [database (seon.db/db (seon.operator/connection "default"))]
  (seon.fn/analyze-form
   database
   "(do (seon.db/q '[:find ?e :where [?e :seon.agent/id]]) (my.turn/wait))"
   [:seon.ns/name 'seon.db] nil))

;; A mixed batch proves span-local projection and same-batch resolution.
;; Analyze only: neither the function nor the test is installed in default.
(let [database (seon.db/db (seon.operator/connection "default"))
      namespace-ref [:seon.ns/name 'seon.db]
      source "(do (seon.db/q '[:find ?e :where [?e :seon.agent/id]]) (my.turn/wait))"
      definition (str "(defn n7-observed [] " source ")")
      test-source "(clojure.test/deftest n7-observed-test (n7-observed))"]
  (seon.fn/analyze-forms
   database
   [{:seon.cluster.eval/source definition
     :seon.cluster.eval/ns namespace-ref
     :seon.program/row
     {:seon.fn/sym "seon.db/n7-observed"
      :seon.fn/ns namespace-ref :seon.fn/source definition
      :seon.fn/arglists "([])" :seon.fn/private? false
      :seon.schema.admission/source :agent}}
    {:seon.cluster.eval/source test-source
     :seon.cluster.eval/ns namespace-ref
     :seon.program/row
     {:seon.test/sym "seon.db/n7-observed-test"
      :seon.test/ns namespace-ref :seon.test/source test-source
      :seon.schema.admission/source :agent}}
    {:seon.cluster.eval/source source :seon.cluster.eval/ns namespace-ref}
    {:seon.cluster.eval/source "(let [map identity] (map :sample/value))"
     :seon.cluster.eval/ns namespace-ref}]))

;; Persist the already analyzed declaration rows in a canonical isolated
;; fixture. This verifies graph reachability, not ordinary-turn persistence.
(let [analyzed
      (:n7/mixed-batch
       (clojure.edn/read-string
        (slurp "docs/prds/context-generation/research/n7-eval-call-edges-evidence-2026-09-15.edn")))]
  (seon.test-support/with-database
   (fn [connection]
     (let [function-report (seon.db/transact! connection [(second (nth analyzed 0))])
           test-report (seon.db/transact! connection [(second (nth analyzed 1))])
           database (seon.db/db connection)]
       {:n7/function-committed? (some? (:db-after function-report))
        :n7/test-committed? (some? (:db-after test-report))
        :n7/reaching (seon.fn/tests-reaching database "seon.db/n7-observed")
        :n7/transitive-reach?
        (contains? (set (seon.fn/tests-reaching database "my.turn/wait"))
                   "seon.db/n7-observed-test")}))))

;; Source loading avoids relying on an unadvertised test classpath in PREPL.
(load-file "test/seon/fn_test.clj")
(seon.test/run
 #'seon.fn-test/ordinary-form-analysis-keeps-call-edges-without-a-declaration
 (seon.operator/connection "default"))
(seon.test/run
 #'seon.fn-test/defining-forms-share-one-form-local-kondo-batch
 (seon.operator/connection "default"))

;; Observe fixture source identity independently of the test verdict.
(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      instance (get @seon.operator.runtime/running-instances "default")]
  {:n7/adopted
   (seon.db/pull database [:seon.source/commit-id] [:seon.cluster/name "default"])
   :n7/published (seon.cluster.source/current (:seon.store/store instance))
   :n7/stored-contract
   (:seon.fn/spec (seon.db/pull database [:seon.fn/spec]
                               [:seon.fn/sym "seon.fn/analyze-forms"]))
   :n7/fixture-contract
   (seon.test-support/with-database
    (fn [fixture]
      (:seon.fn/spec
       (seon.db/pull (seon.db/db fixture) [:seon.fn/spec]
                    [:seon.fn/sym "seon.fn/analyze-forms"]))))})
