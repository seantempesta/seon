(ns seon.sci.documentation-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.program :as program]
            [seon.sci.eval :as evaluation]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest a-named-private-override-documents-the-jvm-boundary
  (support/with-database
   (fn [connection]
     (let [function-symbol (symbol "seon.eval.drive/uuid-text")]
       ;; A stored declaration is replaced through its canonical writer, which
       ;; digests the override (an assoc onto the indexed row is refused).
       (support/transacted!
        connection
        (support/replacement-tx
         (db/db connection) [:seon.fn/sym function-symbol]
         "(defn- uuid-text {:malli/schema [:=> [:cat] :string]} [] \"private override\")"
         {:seon.schema.admission/source :agent :seon.fn/private? true}))
       (let [database (db/db connection)
             ctx (support/fork-cluster-ctx connection)
             doc (evaluation/documentation-value ctx database function-symbol function-symbol)
             directory (evaluation/directory-value database 'seon.eval.drive true)]
         (is (= "Accepted database override for SCI; JVM callers retain the compiled definition until write-back and reload."
                (:seon.schema.admission/note doc))
             "the override's documentation names the JVM boundary")
         (is (not-any? #(= function-symbol (:sym %)) (:functions directory))
             "a private override stays out of the public directory"))))))

(deftest documentation-keeps-named-contracts-and-guarded-result-shapes
  (support/with-database
    (fn [connection]
      (let [doc (evaluation/documentation-value (support/fork-cluster-ctx connection) (db/db connection)
                                                'seon.db/pull 'seon.db/pull)
            contract (#'evaluation/documentation-contract
                      (db/db connection)
                      {:seon.fn/spec
                       "[:function [:=> [:cat :int] :string [:fn {:error/message \"pair relation\"} clojure.core/vector?]] [:=> [:cat [:or :string :int] :boolean] :keyword]]"})]
        (is (contains? doc :in))
        (is (not-any? #{:gen/gen} (tree-seq coll? seq (:out doc)))
            "the result shapes keep no generator property")
        (is (= [[:cat :int] [:cat [:or :string :int] :boolean]] (:in contract)))
        (is (= [:string :keyword] (:out contract)))))))

(deftest documentation-is-returned-data-without-a-second-printed-copy
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (support/evaluate
                  {:seon.sci.eval/ctx ctx
                   :seon.cluster.eval/source source
                   :seon.sci.admit/caps (config/result-caps config/defaults)
                   :seon.sci.eval/time-limit-ms 2000}))
           directory (run "(dir my.agent)")
           documentation (run "(doc my.agent/settings!)")
           missing (run "(doc my.agent/does-not-exist)")
           missing-ns (run "(dir missing.namespace)")
           empty-ns (run "(do (in-ns 'fixture.empty-doc) (dir fixture.empty-doc))")
           directory-value (:seon.sci.admit/value directory)
           entities (:functions directory-value)
           entity (:seon.sci.admit/value documentation)]
       (is (seq entities))
       (is (some #(= 'my.agent/settings! (:sym %)) entities))
       (is (every? #(and (:in %) (:out %)) entities))
       (is (every? #(not (str/includes? (:doc %) "\n")) entities))
       (is (= #{:summary :body :example :arglists :in :out :supplied} (set (keys entity))))
       (is (= (first (str/split-lines (:seon.fn/doc (db/pull (db/db connection) [:seon.fn/doc]
                                    [:seon.fn/sym 'my.agent/settings!]))))
              (:summary entity)))
       (is (= (:in entity)
              (:in (first (filter #(= 'my.agent/settings! (:sym %)) entities)))))
       (is (= :map (first (get-in directory-value [:schemas :my.agent/settings-request]))))
       (is (= :map (first (second (:in entity)))))
       (doseq [target ["my.message/send" "my.agent/done"]
               :let [doc (:seon.sci.admit/value (run (str "(doc " target ")")))]]
         (is (= (conj #{:summary :body :example :in :out} :arglists :supplied)
                (set (keys doc))))
         (is (not (str/blank? (:summary doc))))
         (is (not (str/blank? (:body doc))))
         (is (seq (read-string (:example doc)))))
       (doseq [result [directory documentation missing missing-ns]]
         (is (empty? (:seon.cluster.eval/output result))))
       (is (symbol? (get-in missing [:seon.sci.admit/value :seon.sci.eval/documentation-unavailable])))
       (is (= 'seon.sci.eval/documentation-unavailable
              (get-in missing [:seon.sci.admit/value :seon.error/operation])))
       (is (symbol? (get-in missing-ns [:seon.sci.admit/value :seon.sci.eval/documentation-unavailable])))
       (is (= 'seon.sci.eval/documentation-unavailable
              (get-in missing-ns [:seon.sci.admit/value :seon.error/operation])))
       (is (= {:schemas {} :functions []} (:seon.sci.admit/value empty-ns)))
       (is (nil? (:seon.cluster.eval/error empty-ns)))))))

(deftest doc-classifies-authored-references
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (support/evaluate
                  {:seon.sci.eval/ctx ctx
                   :seon.cluster.eval/source source
                   :seon.sci.admit/caps (config/result-caps config/defaults)
                   :seon.sci.eval/time-limit-ms 2000}))
           value (comp :seon.sci.admit/value run)
           unavailable? #(contains? % :seon.sci.eval/documentation-unavailable)]
       (doseq [source ["(doc nosuchvar)" "(doc 'x)" "(doc 42)" "(doc \"x\")"]]
         (is (unavailable? (value source)) source))
       (let [program (value "(doc seon.task/create!)")
             library (value "(doc clojure.set/union)")
             aliased (value "(do (require '[clojure.set :as s]) (doc s/union))")]
         (is (every? #(contains? program %) [:summary :arglists :in :out]) "a program function keeps its contract")
         (is (= (set (keys library)) #{:summary :body :example :arglists}) "a library Var has no invented contract")
         (is (seq (:arglists library)))
         (is (= library aliased) "an alias resolves to the same Var"))
       (is (empty? (:seon.cluster.eval/output (run "(doc 'x)"))))
       (let [schema (value "(doc :seon.task/intent)")
             namespace-doc (value "(doc seon.task)")
             empty-ns (value "(do (in-ns 'fixture.empty-doc) (doc fixture.empty-doc))")
             sci-ns (value "(doc clojure.set)")]
         (is (= :seon.task/intent (:key schema)))
         (is (= (edn/read-string (:seon.schema/form (db/pull (db/db connection) [:seon.schema/form]
                                                              [:seon.schema/key :seon.task/intent])))
                (:form schema))
             "the declared form is returned parsed")
         (is (unavailable? (value "(doc :no.such/schema)")))
         (is (contains? (set (:publics namespace-doc)) 'seon.task/create!))
         (is (not (str/blank? (:summary namespace-doc))))
         (is (not-any? #(contains? namespace-doc %) [:in :out]))
         (is (= {:publics []} empty-ns) "a known empty namespace has no publics")
         (is (contains? (set (:publics sci-ns)) 'clojure.set/union))
         (is (unavailable? (value "(doc no.such.namespace)"))))))))

(deftest directory-observes-a-function-installed-after-context-acquisition
  (support/with-database
   (fn [connection]
     (is (:db-after (db/transact! connection
                                 [(support/namespace-entity @connection 'fixture.own-functions)])))
     (let [ctx (support/fork-cluster-ctx connection)
           configuration (support/effective-config)
           captured (atom [])
           run (fn [source]
                 (reset! captured [])
                 (binding [db/*read-evidence-sink* captured]
                   (support/evaluate
                    {:seon.sci.eval/ctx ctx
                     :seon.db/db (db/db connection)
                     :seon.db/connection connection
                     :seon.cluster.eval/ns [:seon.ns/name 'fixture.own-functions]
                     :seon.cluster.eval/source source
                     :seon.sci.admit/caps (config/result-caps configuration)
                     :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms configuration)})))
           initial (run "(dir fixture.own-functions)")
           evidence (db/read-evidence @captured)
           source "(defn identity-number \"Return the number.\nA durable function.\" {:malli/schema [:=> [:cat :int] :int]} [number] number)"
           defined (run source)
           ;; evaluate admits nothing since S10 (5e22bc5f6): the entity is the
           ;; one its canonical writer produces for the same source
           entity (support/program-fn-entity (db/db connection)
                                             'fixture.own-functions/identity-number source)]
       (is (= [] (get-in initial [:seon.sci.admit/value :functions])))
       (is (seq evidence))
       (is (true? (db/read-evidence-current? (db/db connection) evidence)))
       (is (nil? (:seon.cluster.eval/error defined)))
       (is (= 'fixture.own-functions/identity-number (:seon.fn/sym entity)))
       (let [written-on (db/db connection)
             written (db/transact! connection
                                   [(support/namespace-entity @connection 'fixture.own-functions)
                                    (program/canonical-row entity)])]
         (is (:db-after written))
         (when (:db-after written)
           (evaluation/install-evaluated-rows!
            {:seon.sci.eval/ctx ctx :seon.db/db (:db-after written) :seon.db/transaction-report written
             :seon.sci.eval/written-on written-on
             :seon.sci.eval/installations
             [{:seon.program/row entity :seon.sci.eval/evaluation defined}]})))
       (is (false? (db/read-evidence-current? (db/db connection) evidence)))
       (let [directory (run "(dir fixture.own-functions)")]
         (is (nil? (:seon.cluster.eval/error directory)))
         (is (= [{:sym 'fixture.own-functions/identity-number
                  :arglists '([number])
                  :doc "Return the number."
                  :in [:cat :int] :out :int}]
                (get-in directory [:seon.sci.admit/value :functions]))))
       (doseq [source ["(doc identity-number)"
                       "(doc fixture.own-functions/identity-number)"]]
         (let [documented (run source)]
           (is (= {:summary "Return the number." :body "A durable function."
                   :example "" :arglists '([number]) :in [:cat :int] :out :int}
                  (:seon.sci.admit/value documented)))
           (is (seq (db/read-evidence @captured)))))))))

(deftest retained-context-receives-the-current-repl-macro-through-its-core-alias
  (support/with-database
   (fn [connection]
     (let [base (support/fork-cluster-ctx connection)
           request {:seon.sci.eval/ctx base :seon.db/db (db/db connection)
                    :seon.agent/id "documentation"}
           retained (:seon.sci.eval/ctx (evaluation/fork-for-turn request))
           old-dir @(sci/resolve retained 'clojure.core/dir)]
       (#'evaluation/install-program-doc! base (db/db connection) (schema/handed-projection))
       (is (not (identical? old-dir @(sci/resolve base 'clojure.core/dir))))
       (let [updated (:seon.sci.eval/ctx
                      (evaluation/fork-for-turn
                       (assoc request :seon.sci.eval/agent-ctx retained)))]
         (doseq [function ['dir 'doc]
                 namespace-name ['clojure.core 'clojure.repl]]
           (let [qualified (symbol (str namespace-name) (str function))]
             (is (identical? @(sci/resolve base qualified)
                             @(sci/resolve updated qualified))
                 "receives its current macro root"))))))))

(deftest bare-test-macros-resolve-without-namespace-referrals
  (support/with-database
   (fn [connection]
     (doseq [ctx [(evaluation/build-base-ctx (seon.schema/handed-projection))
                 (support/fork-cluster-ctx connection)]]
       (sci/add-namespace! ctx 'fixture.bare-tests {})
       (sci/binding [sci/ns (sci/create-ns 'fixture.bare-tests)]
         (doseq [test-symbol ['deftest 'is]]
           (is (some? (sci/resolve ctx test-symbol))))
         (sci/eval-string* ctx "(deftest bare-arithmetic (is (= 2 (+ 1 1))))")
         (is (fn? (:test (meta (sci/resolve ctx 'bare-arithmetic))))))))))

(deftest retained-context-receives-new-bare-test-referrals
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "retained-tests" 'fixture.retained-tests))
     (let [base (support/fork-cluster-ctx connection)
           _ (sci/eval-string* base
                              "(do (ns-unmap 'clojure.core 'deftest) (ns-unmap 'clojure.core 'is))")
           request {:seon.sci.eval/ctx base :seon.db/db (db/db connection)
                    :seon.agent/id "retained-tests"}
           retained (:seon.sci.eval/ctx (evaluation/fork-for-turn request))]
       (sci/binding [sci/ns (sci/create-ns 'fixture.retained-tests)]
         (is (nil? (sci/resolve retained 'deftest)))
         (is (nil? (sci/resolve retained 'is))))
       (#'evaluation/install-program-doc! base (db/db connection) (schema/handed-projection))
       (let [updated (:seon.sci.eval/ctx
                      (evaluation/fork-for-turn
                       (assoc request :seon.sci.eval/agent-ctx retained)))]
         (sci/binding [sci/ns (sci/create-ns 'fixture.retained-tests)]
           (doseq [test-symbol ['deftest 'is]]
             (is (some? (sci/resolve updated test-symbol))))
           (sci/eval-string* updated "(deftest retained-arithmetic (is (= 4 (* 2 2))))")
           (is (fn? (:test (meta (sci/resolve updated 'retained-arithmetic)))))))))))

(deftest a-contract-mistake-refuses-and-doc-answers-its-contract
  (support/preserving-instrumentation-state
   (fn []
    (support/with-database
     (fn [connection]
       ;; No self-arming: the runner must arm before the canonical SCI base
       ;; copies core JVM callables. Cluster configuration alone cannot repair
       ;; a callable already copied into the cached base.
       (support/seed-cluster! connection "documentation"
                              {})
       (let [ctx (support/fork-cluster-ctx connection)
             run (fn [source]
                   (support/evaluate
                    {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                     :seon.db/connection connection :seon.agent/id "documentation"
                     :seon.cluster.eval/source source
                     :seon.sci.admit/caps (config/result-caps config/defaults)
                     :seon.sci.eval/time-limit-ms 10000}))
             documentation (:seon.sci.admit/value (run "(doc my.message/send)"))
             failed (run "(my.message/send {:my.message/to 42 :my.message/content \"Hello\"})")
             value (:seon.sci.admit/value failed)]
         (is (= (resolve 'my.message/send)
                (:seon.instrument/var (meta @(sci/resolve ctx 'my.message/send))))
             "SCI must acquire the canonical armed host callable, not a pre-arming copy")
         (is (= :input (:seon.instrument/check value)))
         (is (= 'my.message/send (:seon.instrument/fn value)))
         ;; The refusal is never enriched with the documentation; `(doc
         ;; my.message/send)` answers it. A render offering that read is no
         ;; longer guessed (fix forms are producer-supplied only, 0e69bd5ab).
         (is (not (find value :seon.error/doc)))
         (doseq [term ["nonempty string subject identity token"
                       ":my.message/assignment" ":seon.message/from"]]
           (is (str/includes? (:body documentation) term)))
         (is ((schema/projection-validator (schema/handed-projection) :seon.error/value) value))
         (is (empty? (db/q '[:find ?m :where [?m :seon.message/id]] (db/db connection))))))))))

(deftest an-absent-declaration-is-a-typed-statement-not-an-empty-one
  ;; `doc` and `dir` used to render an absent :seon.fn/spec as [], absent
  ;; arglists as () and an absent docstring as "": three claims the entity does
  ;; not support. An agent reads this surface before deciding how to call a
  ;; function, so absence must say so (AGENTS.md section 2.4).
  (support/with-database
   (fn [connection]
     (support/transacted!
      connection
      [(assoc (support/program-fn-entity (db/db connection) 'my.turn/undeclared "(defn undeclared [] true)")
              :seon.fn/private? false)])
     (let [database (db/db connection)
           doc (evaluation/documentation-value (support/fork-cluster-ctx connection) database
                                               'my.turn/undeclared
                                               'my.turn/undeclared)
           entity (some #(when (= 'my.turn/undeclared (:sym %)) %)
                     (:functions (evaluation/directory-value database 'my.turn false)))]
       (is (contains? doc :in))
       (is (some? entity) "the undeclared entity is still listed by dir")
       (doseq [[_label value attribute]
               [["doc :in" (:in doc) :seon.fn/spec]
                ["doc :out" (:out doc) :seon.fn/spec]
]]
         (is (= attribute (:seon.sci.eval/missing-declaration value)))
         (is (= 'seon.sci.eval/declaration-absent (:seon.error/operation value))))
       (is (not= [] (:in doc)) "an absent contract never reads as a declared empty one")
       (is (not= "" (:summary doc)))
       (is (str/includes? (:summary doc) (str :seon.fn/doc)))
       (is (str/includes? (:doc entity) (str :seon.fn/doc)))))))

(deftest clojure-test-at-the-agent-repl-reports-into-the-evaluations-output
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "clojure-test" 'fixture.clojure-test))
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (support/evaluate
                  {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                   :seon.db/connection connection :seon.agent/id "clojure-test"
                   :seon.cluster.eval/ns [:seon.ns/name 'fixture.clojure-test]
                   :seon.cluster.eval/source source
                   :seon.sci.admit/caps (config/result-caps config/defaults)
                   :seon.sci.eval/time-limit-ms 10000}))]
       (sci/binding [sci/ns (sci/create-ns 'fixture.clojure-test)]
         (sci/eval-string* ctx "(deftest a-failing-test (is (= 1 2)))")
         (sci/eval-string* ctx "(deftest a-passing-test (is (= 1 1)) (is (= 2 2)))"))
       (let [direct (run "(a-failing-test)")
             current (run "(clojure.test/run-tests)")
             named (run "(clojure.test/run-tests 'fixture.clojure-test)")
             summary {:test 2 :pass 2 :fail 1 :error 0 :type :summary}]
         ;; The FAIL line nests any enclosing runner's testing vars (this
         ;; test's own name when run as a member), so only its subject is fixed.
         (is (re-find #"FAIL in \(.*\ba-failing-test\)" (str (:seon.cluster.eval/output direct)))
             "the failing test's report reaches the evaluation's output")
         (doseq [result [current named]]
           (is (= summary (:seon.sci.admit/value result)))
           (is (str/includes? (str (:seon.cluster.eval/output result)) "Testing fixture.clojure-test"))
           (is (str/includes? (str (:seon.cluster.eval/output result))
                              "Ran 2 tests containing 3 assertions."))))))))

(deftest a-test-error-report-prints-the-exception-without-host-frames
  (support/with-database
   (fn [connection]
     (support/transacted! connection (support/agent-tx @connection "test-error" 'fixture.test-error))
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (support/evaluate
                  {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)
                   :seon.db/connection connection :seon.agent/id "test-error"
                   :seon.cluster.eval/ns [:seon.ns/name 'fixture.test-error]
                   :seon.cluster.eval/source source
                   :seon.sci.admit/caps (config/result-caps config/defaults)
                   :seon.sci.eval/time-limit-ms 10000}))]
       (sci/binding [sci/ns (sci/create-ns 'fixture.test-error)]
         (sci/eval-string* ctx "(deftest boom (is (= 1 (throw (ex-info \"boom\" {:a 1})))))"))
       (let [output (str (:seon.cluster.eval/output (run "(clojure.test/run-tests 'fixture.test-error)")))]
         (is (and (str/includes? output "clojure.lang.ExceptionInfo") (str/includes? output "boom")))
         (is (< (quot (count output) 4) 300) "the report is a few lines, not the host stack"))))))

(deftest source-at-the-agent-repl-prints-the-stored-declaration
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           run (fn [source]
                 (support/evaluate
                  {:seon.sci.eval/ctx ctx
                   :seon.cluster.eval/source source
                   :seon.sci.admit/caps (config/result-caps config/defaults)
                   :seon.sci.eval/time-limit-ms 2000}))
           stored (:seon.fn/source (db/pull (db/db connection) [:seon.fn/source]
                                            [:seon.fn/sym 'my.agent/settings!]))
           qualified (run "(clojure.repl/source my.agent/settings!)")
           aliased (run "(do (require '[my.agent :as agent]) (clojure.repl/source agent/settings!))")
           missing (run "(clojure.repl/source fixture.missing/nothing)")]
       (is (string? stored))
       (is (= (str stored "\n") (:seon.cluster.eval/output qualified)))
       (is (= (str stored "\n") (:seon.cluster.eval/output aliased)))
       (is (= "Source not found\n" (:seon.cluster.eval/output missing)))
       (is (= stored (evaluation/stored-source (db/db connection) 'my.agent/settings!)))))))

(deftest an-example-ends-at-the-next-fixed-heading
  ;; The example ran to the docstring's end, so a heading after it (Costs,
  ;; Errors, `## Known issues`) was read as example forms (tasks eba5d142ef12,
  ;; eaf75398383c); a one-line `Example: (form)` yielded no example.
  (is (= {:summary "Read one." :example "(a 1)\n(b 2)"
          :body "Edge cases: none.\nCosts: one query.\n\n## Known issues\n\nx"}
         (evaluation/docstring-parts
          "Read one.\n\n  Example:\n  (a 1)\n  (b 2)\n  Edge cases: none.\n  Costs: one query.\n\n  ## Known issues\n\n  x")))
  (is (= "(trim \"a \")" (:example (evaluation/docstring-parts "Trim a title.\n\n  Example: (trim \"a \")"))))
  (is (= {:summary "Open tasks." :body "## Costs\n\nOne query." :example "(my.task/open)"}
         (evaluation/docstring-parts "Open tasks.\n\n  ## Costs\n\n  One query.\n\n  Example:\n  (my.task/open)")))
  (is (= "" (:example (evaluation/docstring-parts "No example here.")))))

(deftest a-refused-schema-read-keeps-its-refusal
  ;; A database that has not installed the schema attributes refuses the pull;
  ;; only a successful read that finds no schema is "unavailable".
  (let [config {:store {:backend :memory :id (random-uuid)} :schema-flexibility :read}
        _ (d/create-database config)
        connection (d/connect config)
        refused (#'evaluation/schema-documentation (d/db connection) :a/b :a/b)]
    (is (db/read-refusal? refused))
    (is (not (contains? refused :seon.sci.eval/documentation-unavailable)))
    (d/release connection)
    (d/delete-database config)))

(deftest namespace-publics-need-no-evaluated-form
  (support/with-database
   (fn [connection]
     (let [ctx (support/fork-cluster-ctx connection)
           publics #(set (:publics (#'evaluation/namespace-documentation ctx (db/db connection) 'clojure.set 'clojure.set)))]
       (sci/eval-string* ctx "(ns-unmap 'clojure.core 'ns-publics)")
       (is (contains? (publics) 'clojure.set/union)
           "the public names come from SCI's namespace table, not an evaluated ns-publics call")))))
