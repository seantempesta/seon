(ns seon.test.accretion-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]
            [seon.test.accretion :as accretion]))

(defn- admit!
  [connection entities]
  (let [result (db/transact! connection entities)]
    (when-not (:db-after result)
      (throw (ex-info "Auto-check fixture admission failed." result)))
    result))

(deftest refusal-renderers-accept-the-declaring-error-shape
  (is (= [:=> [:cat :seon.test.accretion/install-refused-error]
          :string]
         (:malli/schema (meta #'accretion/render-ai))))
  (is (= [:=> [:cat :seon.test.accretion/install-refused-error]
          :seon.render/hiccup]
         (:malli/schema (meta #'accretion/render-html)))))

(deftest source-harvest-keeps-refusal-render-contracts-coherent
  (let [entities (seon.fn/program-entities {:seon.fn/roots seon.fn/source-roots})
        contracts
        (into {}
              (keep (fn [{function-symbol :seon.fn/sym
                          contract :seon.fn/spec}]
                      (when contract
                        [(symbol function-symbol)
                         (edn/read-string contract)])))
              entities)
        projection
        (schema/build-projection
         (schema.edn/packaged-forms)
         contracts
         {:seon.schema/validate-render-contracts? true})]
    (is (= [:=> [:cat :seon.test.accretion/install-refused-error]
            :string]
           (get-in projection
                   [:seon.schema.projection/function-contracts
                    'seon.test.accretion/render-ai])))
    (is (= 'seon.test.accretion/render-ai
           (get-in projection
                   [:seon.schema.projection/shape-rows
                    :seon.test.accretion/install-refused-error
                    :seon.render/ai])))))

(deftest generatability-is-derived-by-malli-generator-construction
  (testing "test.chuck enables Malli regex generation on the runtime classpath"
    (is (true? (accretion/generatable? [:re #"[a-z]+"]))))
  (testing "an unannotated predicate honestly has no generator"
    (is (false? (accretion/generatable?
                 [:fn {:error/message "must be an integer"} int?])))))

(deftest schema-entities-record-the-derived-generatable-fact
  (let [generatable
        (accretion/schema-row
         {:fixture/token [:re #"[a-z]+"]}
         {:seon.schema/key :fixture/token
          :seon.schema/form "[:re #\"[a-z]+\"]"})
        non-generatable
        {:seon.schema/key :fixture/custody
         :seon.schema/form "[:fn fixture/custody?]"
         :seon.schema/generatable? false}]
    ;; The fact is data; an evaluation that admits a non-generatable schema
    ;; records it (:seon.eval/ungeneratable) and a declared bound makes it a
    ;; finding in the reply map, never :out prose.
    (is (true? (:seon.schema/generatable? generatable)))
    (is (false? (:seon.schema/generatable? non-generatable)))))

(deftest one-gate-set-query-includes-edges-subjects-and-pending-tests
  (test-support/with-database
    (fn [connection]
      (admit!
       connection
       [(test-support/namespace-entity @connection 'fixture.gate)])
      (let [source "(defn target [] 1)\n(defn caller [] (target))"]
        (admit! connection
                [(test-support/program-fn-entity @connection 'fixture.gate/target source)
                 (test-support/program-fn-entity @connection 'fixture.gate/caller source)]))
      (let [test-entity (fn [test-symbol source overrides]
                       (test-support/program-entity @connection [:seon.test/sym test-symbol] source overrides))]
        (admit! connection
                [(test-entity 'fixture.gate/direct-test "(clojure.test/deftest direct-test (target))" {})
                 (test-entity 'fixture.gate/caller-test "(clojure.test/deftest caller-test (caller))" {})
                 (test-entity 'fixture.gate/subject-test "(clojure.test/deftest subject-test)"
                           {:seon.test/subject 'fixture.gate/target})
                 (test-entity 'fixture.gate/pending-test "(clojure.test/deftest pending-test)"
                           {:seon.test/subject 'fixture.gate/future})]))
      ;; The branch also holds this test's own entity, which quotes the fixture symbols.
      (is (= ['fixture.gate/caller-test
              'fixture.gate/direct-test
              'fixture.gate/subject-test]
             (filterv #(= "fixture.gate" (namespace %))
                      (seon.fn/gate-set @connection 'fixture.gate/target))))
      (is (= ['fixture.gate/pending-test]
             (filterv #(= "fixture.gate" (namespace %))
                      (seon.fn/gate-set @connection 'fixture.gate/future))))
      (is (= (seon.fn/gate-set @connection 'fixture.gate/target)
             (seon.fn/tests-reaching @connection 'fixture.gate/target))))))

(deftest ^{:seon.test/long "Constructs a generator for every packaged schema (3,350): 2.5 s at the REPL, 7.4 s under a request on a scratch cluster at 0e53eb8b1."
           :seon.test/long-ms 12000}
  every-packaged-schema-answers-generatability-as-a-boolean
  ;; 169 packaged schemas (e.g. :seon.sci.eval/evaluation) named their
  ;; generator by symbol in `:gen/gen`; malli handed the symbol to gen/fmap and
  ;; threw an AssertionError that only a catch-everything hid. The fork now
  ;; resolves the symbol, and any other "cannot generate" is a typed ex-info.
  ;; The projection route (`schema-row-in`, what publication uses) answers
  ;; exactly what the raw-forms route answers, for every packaged key.
  (let [forms (schema.edn/packaged-forms)
        projection (schema/declaration-projection forms)
        answers (into {} (map (fn [schema-key]
                                [schema-key (:seon.schema/generatable?
                                             (accretion/schema-row-in projection {:seon.schema/key schema-key}))]))
                      (keys forms))]
    (is (every? boolean? (vals answers)))
    (is (true? (answers :seon.sci.eval/evaluation))
        "a map whose member names its generator by symbol generates")
    (is (true? (answers :seon.test/var)))
    (is (= answers
           (into {} (map (fn [schema-key]
                           [schema-key (:seon.schema/generatable?
                                        (accretion/schema-row forms {:seon.schema/key schema-key}))]))
                 (keys forms)))
        "the raw-forms route agrees on every key")))
