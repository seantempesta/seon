(ns seon.test.accretion-test
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]
            [seon.test.accretion :as accretion]))

(deftest refused-install-feedback-is-complete-grouped-and-renderable
  (let [test-result
        (fn [test-symbol]
          {:seon.test/sym test-symbol
           :seon.test/pass-count 0
           :seon.test/fail-count 1
           :seon.test/error-count 0
           :seon.test/failing-assertions [(apply str (repeat 64 "a"))]
           :seon.test/failure-message "expected: 2\n  actual: 1"})
        report
        (accretion/gate-report
         {:seon.fn/sym "fixture.feedback/target"
          :seon.test.accretion/results
          (mapv test-result (map #(str "fixture.feedback/red-" %) (range 5)))
          :seon.test.accretion/auto-check
          {:seon.test.accretion/seed 42
           :seon.test.accretion/case-count 25
           :seon.test.accretion/executed-count 7
           :seon.test.accretion/capabilities #{}
           :seon.test.accretion/status :failed
           :seon.test.accretion/failure
           {:seon.test.accretion/arguments [0]
            :seon.test.accretion/actual "wrong"
            :seon.test.accretion/expected ":int"
            :seon.test.accretion/pass? false}}})
        refusal (accretion/install-refusal report)
        rendered (accretion/render-ai refusal)]
    (is (= "Gate: 5 tests · 0 passed · 5 failed · auto-check 7/25 · install refused"
           (:seon.test.accretion/orientation report)))
    (is (= 6
           (reduce + (map (comp count :seon.test.accretion/failures)
                          (:seon.test.accretion/failure-groups report))))
        "the complete value retains every example and auto-check failure")
    (test-support/with-database
      (fn [connection]
        (is (schema/valid-candidate-value?
             (:seon.schema.projection/forms
              (schema/projection-from-database @connection))
             :seon.test.accretion/install-refused-error refusal))))
    (is (= 3 (count (re-seq #"Test fixture.feedback/red-" rendered))))
    (is (re-find #"2 more in the complete gate-report blob" rendered))
    (is (re-find #"Auto-check seed 42" rendered))
    (is (= [:article {:class "seon-family-entry seon-test-gate-refusal"}
            [:pre rendered]]
           (accretion/render-html refusal)))
    (is (= (accretion/seed-for "receipt-id")
           (accretion/seed-for "receipt-id")))))

(deftest refusal-renderers-accept-the-declaring-error-shape
  (is (= [:=> [:cat :seon.test.accretion/install-refused-error]
          :seon.render/ai]
         (:malli/schema (meta #'accretion/render-ai))))
  (is (= [:=> [:cat :seon.test.accretion/install-refused-error]
          :seon.render/hiccup]
         (:malli/schema (meta #'accretion/render-html)))))

(deftest generatability-is-derived-by-malli-generator-construction
  (testing "test.chuck enables Malli regex generation on the runtime classpath"
    (is (true? (accretion/generatable? [:re #"[a-z]+"]))))
  (testing "an unannotated predicate honestly has no generator"
    (is (false? (accretion/generatable?
                 [:fn {:error/message "must be an integer"} int?])))))

(deftest schema-rows-record-the-derived-fact-and-teaching-advisory
  (let [generatable
        (accretion/schema-row
         {:fixture/token [:re #"[a-z]+"]}
         {:seon.schema/key :fixture/token
          :seon.schema/form "[:re #\"[a-z]+\"]"})
        non-generatable
        {:seon.schema/key :fixture/custody
         :seon.schema/form "[:fn fixture/custody?]"
         :seon.schema/generatable? false}]
    (is (true? (:seon.schema/generatable? generatable)))
    (is (nil? (accretion/non-generatable-advisory generatable)))
    (is (= "Schema :fixture/custody has no Malli generator; functions using it will skip auto-check."
           (accretion/non-generatable-advisory non-generatable)))))

(deftest one-gate-set-query-includes-edges-subjects-and-pending-tests
  (test-support/with-database
    {::test-support/extra-schema
     [{:db/ident :seon.test/pending-subject
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one}]}
    (fn [connection]
      (db/transact!
       connection
       [{:seon.ns/name 'fixture.gate :seon.ns/source "(ns fixture.gate)"}
        {:seon.fn/sym "fixture.gate/target"
         :seon.fn/ns [:seon.ns/name 'fixture.gate]
         :seon.fn/source "(defn target [] 1)"
         :seon.fn/arglists "([])" :seon.fn/private? false}
        {:seon.fn/sym "fixture.gate/caller"
         :seon.fn/ns [:seon.ns/name 'fixture.gate]
         :seon.fn/source "(defn caller [] (target))"
         :seon.fn/arglists "([])" :seon.fn/private? false
         :seon.fn/calls [[:seon.fn/sym "fixture.gate/target"]]}])
      (db/transact!
       connection
       [{:seon.test/sym "fixture.gate/direct-test"
         :seon.test/ns [:seon.ns/name 'fixture.gate]
         :seon.test/source "(deftest direct-test)"
         :seon.fn/calls [[:seon.fn/sym "fixture.gate/target"]]}
        {:seon.test/sym "fixture.gate/caller-test"
         :seon.test/ns [:seon.ns/name 'fixture.gate]
         :seon.test/source "(deftest caller-test)"
         :seon.fn/calls [[:seon.fn/sym "fixture.gate/caller"]]}
        {:seon.test/sym "fixture.gate/subject-test"
         :seon.test/ns [:seon.ns/name 'fixture.gate]
         :seon.test/source "(deftest subject-test)"
         :seon.test/subject [:seon.fn/sym "fixture.gate/target"]}
        {:seon.test/sym "fixture.gate/pending-test"
         :seon.test/ns [:seon.ns/name 'fixture.gate]
         :seon.test/source "(deftest pending-test)"
         :seon.test/pending-subject "fixture.gate/future"}])
      (is (= ["fixture.gate/caller-test"
              "fixture.gate/direct-test"
              "fixture.gate/subject-test"]
             (seon.fn/gate-set @connection "fixture.gate/target")))
      (is (= ["fixture.gate/pending-test"]
             (seon.fn/gate-set @connection "fixture.gate/future")))
      (is (= (seon.fn/gate-set @connection "fixture.gate/target")
             (seon.fn/tests-reaching @connection "fixture.gate/target"))))))

(deftest candidate-tests-run-on-a-copy-on-write-turn-fork
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.agent/id "candidate-author"
         :seon.cluster.agent/namespace
         {:seon.ns/name 'fixture.candidate
          :seon.ns/source
          (str "(ns fixture.candidate "
               "(:require [clojure.test :refer [deftest is]]))")}}
        {:seon.fn/sym "fixture.candidate/target"
         :seon.fn/ns [:seon.ns/name 'fixture.candidate]
         :seon.fn/source
         (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
              "target [x] x)")
         :seon.fn/arglists "([x])"
         :seon.fn/private? false
         :seon.fn/spec "[:=> [:cat :int] :int]"}
        {:seon.test/sym "fixture.candidate/green-test"
         :seon.test/ns [:seon.ns/name 'fixture.candidate]
         :seon.test/source
         (str "(clojure.test/deftest green-test "
              "(clojure.test/is (= 2 (target 1))))")}
        {:seon.test/sym "fixture.candidate/red-test"
         :seon.test/ns [:seon.ns/name 'fixture.candidate]
         :seon.test/source
         (str "(clojure.test/deftest red-test "
              "(clojure.test/is (= 3 (target 1))))")}])
      (let [parent (test-support/fork-cluster-ctx connection)
            _ (when-not (sci/find-ns parent 'fixture.candidate)
                (sci/add-namespace! parent 'fixture.candidate {}))
            _ (sci/binding [sci/ns (sci/create-ns 'fixture.candidate)]
                (sci/eval-string*
                 parent
                 (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                      "target [x] x)")))
            parent-var (sci/resolve parent 'fixture.candidate/target)
            parent-root-bytes
            (.getBytes
             (binding [*print-meta* true]
               (pr-str
                (first
                 (sci/var-root-data
                  parent ['fixture.candidate/target]))))
             java.nio.charset.StandardCharsets/UTF_8)
            result
            (sci.eval/evaluate-candidate
             {:seon.sci.eval/ctx parent
              :seon.db/db @connection
              :seon.db/connection connection
              :seon.cluster.agent/id "candidate-author"
              :seon.cluster.run.form/source
              (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                   "target [x] (inc x))")
              :seon.test.accretion/gate-set
              ["fixture.candidate/green-test"
               "fixture.candidate/red-test"]
              :seon.sci.eval/time-limit-ms 2000
              :seon.sci.admit/caps
              (config/result-caps (config/defaults))
              :seon.config/on-core-error :panic})
            candidate (:seon.test.accretion/candidate-ctx result)
            results (:seon.test.accretion/results result)
            parent-root-after
            (.getBytes
             (binding [*print-meta* true]
               (pr-str
                (first
                 (sci/var-root-data
                  parent ['fixture.candidate/target]))))
             java.nio.charset.StandardCharsets/UTF_8)]
        (testing "the candidate function and every gate test run"
          (is (= ["fixture.candidate/green-test"
                  "fixture.candidate/red-test"]
                 (mapv :seon.test/sym results)))
          (is (= [0 1] (mapv :seon.test/fail-count results)))
          (is (= 2
                 (sci/eval-string*
                  candidate "(fixture.candidate/target 1)"))))
        (testing "the parent retains byte-identical Var state"
          (is (identical? parent-var
                          (sci/resolve parent 'fixture.candidate/target)))
          (is (java.util.Arrays/equals parent-root-bytes parent-root-after))
          (is (= 1
                 (sci/eval-string*
                  parent "(fixture.candidate/target 1)"))))))))

(deftest auto-check-is-seeded-shrunk-and-derived-pure
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.agent/id "auto-check-author"
         :seon.cluster.agent/namespace
         {:seon.ns/name 'fixture.auto-check
          :seon.ns/source "(ns fixture.auto-check)"}}
        {:seon.fn/sym "fixture.auto-check/capability"
         :seon.effect/capability 'fixture.auto-check/handler}])
      (let [parent (test-support/fork-cluster-ctx connection)
            candidate-request
            (fn [source]
              (sci.eval/evaluate-candidate
               {:seon.sci.eval/ctx parent
                :seon.db/db @connection
                :seon.db/connection connection
                :seon.cluster.agent/id "auto-check-author"
                :seon.cluster.run.form/source source
                :seon.test.accretion/gate-set []
                :seon.sci.eval/time-limit-ms 2000
                :seon.sci.admit/caps
                (config/result-caps (config/defaults))
                :seon.config/on-core-error :panic}))
            check-request
            (fn [candidate row]
              {:seon.sci.eval/ctx candidate
               :seon.db/db @connection
               :seon.program/row row
               :seon.config.test/auto-check-cases 25
               :seon.test.accretion/seed 424242
               :seon.sci.eval/time-limit-ms 2000
               :seon.sci.admit/caps
               (config/result-caps (config/defaults))
               :seon.config/on-core-error :panic})
            bad
            (candidate-request
             (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                  "bad [x] \"wrong\")"))
            bad-row
            (get-in bad [:seon.test.accretion/evaluation :seon.program/row])
            bad-request
            (check-request (:seon.test.accretion/candidate-ctx bad) bad-row)
            first-failure (sci.eval/auto-check-candidate bad-request)
            reproduced (sci.eval/auto-check-candidate bad-request)
            shape-only
            (candidate-request
             (str "(defn ^{:malli/schema "
                  "[:=> [:cat :int :int] :int]} add [a b] 1)"))
            shape-only-row
            (get-in shape-only
                    [:seon.test.accretion/evaluation :seon.program/row])
            shape-only-check
            (sci.eval/auto-check-candidate
             (check-request
              (:seon.test.accretion/candidate-ctx shape-only)
              shape-only-row))
            effectful-check
            (sci.eval/auto-check-candidate
             (check-request
              (:seon.test.accretion/candidate-ctx bad)
              (assoc bad-row
                     :seon.fn/calls
                     [[:seon.fn/sym
                       "fixture.auto-check/capability"]])))]
        (testing "the same seed reproduces the shrunk failure"
          (is (= :failed (:seon.test.accretion/status first-failure))
              (pr-str first-failure))
          (is (= 424242 (:seon.test.accretion/seed first-failure)))
          (is (= (:seon.test.accretion/failure first-failure)
                 (:seon.test.accretion/failure reproduced)))
          (is (seq (get-in first-failure
                           [:seon.test.accretion/failure
                            :seon.test.accretion/arguments])))
          (is (string?
               (get-in first-failure
                       [:seon.test.accretion/failure
                        :seon.test.accretion/explanation]))))
        (testing "contract shape does not invent semantic properties"
          (is (= :passed (:seon.test.accretion/status shape-only-check))
              (pr-str shape-only-check))
          (is (= 25
                 (:seon.test.accretion/executed-count shape-only-check))))
        (testing "capability reachability skips auto-check honestly"
          (is (= :skipped-effectful
                 (:seon.test.accretion/status effectful-check)))
          (is (= 0
                 (:seon.test.accretion/executed-count effectful-check)))
          (is (= #{"fixture.auto-check/capability"}
                 (:seon.test.accretion/capabilities effectful-check))))))))
