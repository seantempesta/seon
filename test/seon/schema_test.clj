(ns seon.schema-test
  "Regression proofs for the canonical schema registration boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.error :as me]
            [seon.db]
            [seon.schema :as schema]
            [seon.schema.edn]
            [seon.schema.internal :as schema.internal]))

(defn- refusal
  [thunk]
  (try
    (thunk)
    ::committed
    (catch clojure.lang.ExceptionInfo failure
      failure)))

(defn- registration-delta
  []
  (schema/begin-registration-delta
   (schema/build-projection (schema/registered-schemas))))

(deftest canonical-definition-keeps-admitted-predicate-symbols
  (let [definition
        [:=> [:cat :qualified-symbol [:fn 'clojure.core/ifn?]]
         :qualified-symbol]]
    (is (= definition (schema/canonical-definition definition {})))
    (is (schema/malli-form? definition))
    (is (false? (schema/malli-form? [:fn 'clojure.java.shell/sh]))
        "schema validation never loads an arbitrary predicate namespace")))

(deftest named-predicate-violations-humanize-to-the-declared-requirement
  (let [humanized
        (me/humanize
         (schema/explain-candidate-value
          :seon.db/database-value "not a database value"))]
    (is (str/includes? (pr-str humanized)
                       "must be an immutable Datahike database value"))
    (is (not (str/includes? (pr-str humanized) "unknown error")))))

(deftest canonical-self-references-refuse-at-registration
  (let [schema-key :seon.schema-test/self]
      (doseq [[label definition]
              [["a direct canonical reference"
                [:or :string [:vector schema-key]]]
               ["an explicit canonical `:ref`"
                [:or :string [:vector [:ref schema-key]]]]]]
        (testing label
          (let [delta (registration-delta)
                failure
                (refusal
                 #(schema/call-with-registration-delta
                   delta (fn [] (schema/register! schema-key definition))))
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
            (is (nil? (schema/registration-delta-form delta schema-key))
                "a refused declaration never reaches the delta"))))))

(deftest canonical-mutual-recursion-refuses-but-local-recursion-is-supported
  (let [left :seon.schema-test/left
        right :seon.schema-test/right
        local :seon.schema-test/local-recursion
        local-node :seon.schema-test.local/node]
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
        (let [delta (registration-delta)
              definition
              [:schema
               {:registry
                {local-node
                 [:or :string [:vector [:ref local-node]]]}}
               [:ref local-node]]]
          (is (= local
                 (schema/call-with-registration-delta
                  delta (fn [] (schema/register! local definition)))))
          (schema/call-with-registration-delta
           delta
           (fn []
             (is (schema/valid-candidate-value?
                  local ["root" ["leaf"]]))))
          (is (= definition
                 (schema/registration-delta-form delta local)))))))

(deftest map-shapes-accrete-additional-top-level-attributes
  (let [schema-key :seon.schema-test/rendered-entity
        render-html 'seon.schema-test/render-html
        forms (assoc (schema/registered-schemas)
                     schema-key
                     [:map {:seon.render/html render-html}
                      [:seon.schema-test/id :string]
                      [:seon.schema-test/rank {:optional true} :int]])
        projection (schema/build-projection forms)
        base {:seon.schema-test/id "one"}
        additional (assoc base
                          :seon.render/html
                          'my.agent/render-html)
        invalid (assoc additional :seon.schema-test/rank "first")]
    (testing "shape identity survives accretion"
      (is (= [schema-key schema-key]
             (mapv (fn [value]
                     (-> (schema/matching-shapes-in projection value)
                         first
                         :seon.schema/key))
                   [base additional])))
      (is (= render-html
             (-> (schema/matching-shapes-in projection additional)
                 first
                 :seon.render/html))
          "custom Malli render properties survive in the shape row")
      (is (empty? (schema/matching-shapes-in projection invalid))
          "an invalid declared optional attribute still refuses"))))

(deftest canonical-rows-carry-arbitrary-namespaced-properties
  (let [schema-key :seon.schema-test/class
        definition
        [:map {:seon.error/class true
               :gen/schema :string
               :seon.unknown/property :ignored}
         [:seon.error/message :seon.error/message]]
        forms {:seon.error/class [:= true]
               :gen/schema :seon.schema/definition
               schema-key definition}
        row (some #(when (= schema-key (:seon.schema/key %)) %)
                  (schema/canonical-schema-rows forms))]
    (is (= true (:seon.error/class row)))
    (is (not (contains? row :gen/schema))
        "a declared but non-storable property remains compile-time Malli data")
    (is (not (contains? row :seon.unknown/property))
        "an undeclared property remains compile-time Malli data")
    (is (= (pr-str definition) (:seon.schema/form row)))))

(deftest matching-shapes-derive-required-attributes-through-and-refs
  (let [state (schema/snapshot-state)]
    (try
      (let [forms {:seon.error/message :string
                   :seon.error/refusal-value
                   [:map [:seon.error/message :seon.error/message]]
                   :seon.schema-test/refused [:= true]
                   :seon.schema-test/refused-error
                   [:and {:seon.error/class true
                          :seon.render/ai 'seon.error/refusal-prose}
                    :seon.error/refusal-value
                    [:map
                     [:seon.schema-test/refused
                      :seon.schema-test/refused]]]}
            projection (schema/build-projection forms)
            value {:seon.schema-test/refused true
                   :seon.error/message "The transition was refused."}
            row (get (:seon.schema.projection/shape-rows projection)
                     :seon.schema-test/refused-error)]
        (is (= #{:seon.schema-test/refused :seon.error/message}
               (:seon.schema/required-attrs row)))
        (is (= 'seon.error/refusal-prose (:seon.render/ai row)))
        (is (= :seon.schema-test/refused-error
               (-> (schema/matching-shapes-in projection value)
                   first
                   :seon.schema/key))))
      (finally
        (schema/restore-state! state)))))

(deftest agent-authored-function-input-maps-accrete
  (is (empty?
       (schema/assert-complete-contract!
        {:seon.schema/identity 'my.agent/accreting
         :seon.schema/definition
         [:=>
          [:cat [:map [:my.agent/required :string]]]
          :string]
         :seon.schema/admission
         {:seon.schema.admission/source :agent}}))))

(deftest one-declaration-validates-only-its-dependency-closure
  (let [unrelated
        (into {}
              (map (fn [index]
                     [(keyword "seon.schema-test.unrelated" (str index))
                      :string]))
              (range 1024))
        projection (schema/build-projection unrelated)
        admission {:seon.schema.admission/source :agent}
        binding-walks (atom 0)
        population-compilations (atom 0)
        original-bind schema/bind-predicates
        original-compile schema.internal/assert-compilable-schema!
        [schema-candidate function-candidate]
        (with-redefs
          [schema/bind-predicates
           (fn [& args]
             (swap! binding-walks inc)
             (apply original-bind args))
           schema.internal/assert-compilable-schema!
           (fn [& args]
             (swap! population-compilations inc)
             (apply original-compile args))]
          [(schema/projection-with-schema
            projection :seon.schema-test.incremental/score
            [:int {:min 0 :max 100}] admission)
           (schema/projection-with-function-contract
            projection 'seon.schema-test.incremental/accept
            [:=> [:cat :string] :string] admission)])]
    (is (zero? @population-compilations)
        "one declaration never enters complete-population compilation")
    (is (< @binding-walks 16)
        "predicate binding is bounded by the two changed declarations")
    (is (= [:int {:min 0 :max 100}]
           (get-in schema-candidate
                   [:seon.schema.projection/forms
                    :seon.schema-test.incremental/score])))
    (is (= [:=> [:cat :string] :string]
           (get-in function-candidate
                   [:seon.schema.projection/function-contracts
                    'seon.schema-test.incremental/accept])))))
