(ns seon.schema-test
  "Regression proofs for the canonical schema registration boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]
            [seon.schema.edn]))

(defn- refusal
  [thunk]
  (try
    (thunk)
    ::committed
    (catch clojure.lang.ExceptionInfo failure
      failure)))

(deftest canonical-definition-keeps-resolvable-predicate-symbols
  (let [definition
        [:=> [:cat :qualified-symbol [:fn 'clojure.core/ifn?]]
         :qualified-symbol]]
    (is (= definition (schema/canonical-definition definition {})))
    (is (schema/malli-form? definition))))

(deftest canonical-self-references-refuse-at-registration
  (let [state (schema/snapshot-state)
        schema-key :seon.schema-test/self]
    (try
      (doseq [[label definition]
              [["a direct canonical reference"
                [:or :string [:vector schema-key]]]
               ["an explicit canonical `:ref`"
                [:or :string [:vector [:ref schema-key]]]]]]
        (testing label
          (let [failure
                (refusal #(schema/register! schema-key definition))
                data (ex-data failure)]
            (is (instance? clojure.lang.ExceptionInfo failure)
                "the admission gate returns a legible refusal")
            (is (= :seon.schema/cyclic-reference
                   (:seon.schema/error data)))
            (is (= schema-key (:seon.schema/identity data)))
            (is (= [schema-key schema-key]
                   (:seon.schema/cycle-path data)))
            (is (= :user-input (:seon.error/kind data)))
            (is (str/includes? (ex-message failure)
                               (pr-str [schema-key schema-key]))
                "the refusal names the complete cycle")
            (is (not (schema/registered? schema-key))
                "a refused declaration never reaches the collector"))))
      (finally
        (schema/restore-state! state)))))

(deftest canonical-mutual-recursion-refuses-but-local-recursion-is-supported
  (let [left :seon.schema-test/left
        right :seon.schema-test/right
        local :seon.schema-test/local-recursion
        local-node :seon.schema-test.local/node
        state (schema/snapshot-state)]
    (try
      (testing "a complete mutually recursive canonical population refuses"
        (let [failure
              (refusal
               #(seon.schema.edn/admit
                 {:seon.schema/forms
                  {left [:or :string [:vector right]]
                   right [:or :int [:vector [:ref left]]]}}))
              data (ex-data failure)]
          (is (instance? clojure.lang.ExceptionInfo failure))
          (is (= :seon.schema/cyclic-reference
                 (:seon.schema/error data)))
          (is (= [left right left]
                 (:seon.schema/cycle-path data)))))
      (testing "Malli's local recursive registry remains a supported shape"
        (let [definition
              [:schema
               {:registry
                {local-node
                 [:or :string [:vector [:ref local-node]]]}}
               [:ref local-node]]]
          (is (= local (schema/register! local definition)))
          (is (schema/valid-candidate-value?
               local ["root" ["leaf"]]))
          (is (schema/registered? local))))
      (finally
        (schema/restore-state! state)))))
