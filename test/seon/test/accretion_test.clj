(ns seon.test.accretion-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.test-support :as test-support]
            [seon.test.accretion :as accretion]))

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
