(ns seon.test.accretion-test
  (:require [clojure.test :refer [deftest is testing]]
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
