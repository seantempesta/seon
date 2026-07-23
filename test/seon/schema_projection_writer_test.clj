(ns seon.schema-projection-writer-test
  "Portable committed-row projection and explicit map-shape proofs."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.ai.tokens :as tokens]
            [seon.db.protocol :as protocol]
            [seon.schema :as schema]))

(def rows
  (let [core-tx {:seon.db/process
                 {:seon.db.process/id :seon.db.process/boot}}]
    {:seon.schema/schema-rows
   #{[:projection.test/id ":int" core-tx]
     [:projection.test/shape
      "[:map [:projection.test/id :projection.test/id]]"
      core-tx]}
   :seon.schema/function-contract-rows
   #{["projection.test/read"
      "[:=> [:cat :projection.test/shape] :int]"
      core-tx]}}))

(deftest relation-sets-build-the-same-complete-projection
  (let [from-set (schema/projection-from-rows rows)
        from-vector
        (schema/projection-from-rows
          (update-vals rows vec))]
    (is (= (:seon.schema.projection/fingerprint from-set)
           (:seon.schema.projection/fingerprint from-vector)))
    (is (= #{'projection.test/read}
           (set (keys (:seon.schema.projection/function-contracts from-set)))))))

(deftest optimized-build-preserves-the-pre-refactor-fingerprint
  (is (= 393623503
         (:seon.schema.projection/fingerprint
          (schema/projection-from-rows rows)))
      "the optimized validation path is byte-identical to the old builder"))

(deftest fingerprint-guarded-reuse-only-skips-an-identical-build
  (let [fresh (schema/projection-from-rows rows)
        reused (schema/projection-from-rows rows fresh)
        mismatched
        (assoc fresh :seon.schema.projection/fingerprint
               (inc (:seon.schema.projection/fingerprint fresh)))
        rebuilt (schema/projection-from-rows rows mismatched)]
    (is (identical? fresh reused)
        "equal input identity returns the retained projection object")
    (is (not (identical? mismatched rebuilt))
        "a fingerprint mismatch falls through to full construction")
    (is (= (:seon.schema.projection/fingerprint fresh)
           (:seon.schema.projection/fingerprint rebuilt)))))

(deftest explicit-map-status-and-explanation-ignore-process-candidates
  (let [projection (schema/projection-from-rows rows)
        valid {:projection.test/id 1}
        invalid {:projection.test/id "wrong"}]
    (is (= [:projection.test/shape]
           (mapv :seon.schema/key
                 (schema/matching-shapes-in projection valid))))
    (is (= [:projection.test/shape]
           (mapv :seon.schema/key
                 (schema/candidate-shapes-in projection invalid))))
    (is (map? (schema/explain-shape-in projection
                                      :projection.test/shape invalid)))))

(deftest duplicate-and-unresolved-rows-fail-the-complete-build
  (is (thrown? clojure.lang.ExceptionInfo
               (schema/projection-from-rows
                 {:seon.schema/schema-rows
                  [[:projection.test/id ":int"]
                   [:projection.test/id ":string"]]
                  :seon.schema/function-contract-rows []})))
  (is (thrown? clojure.lang.ExceptionInfo
               (schema/projection-from-rows
                 {:seon.schema/schema-rows
                  [[:projection.test/shape
                    "[:map [:projection.test/missing :projection.test/missing]]"]]
                  :seon.schema/function-contract-rows []}))))

(deftest missing-provenance-fails-closed-as-agent-authored
  (let [projection
        (schema/projection-from-rows
         {:seon.schema/schema-rows [[:projection.test/id ":int"]]
          :seon.schema/function-contract-rows []})
        admission
        (get-in projection
                [:seon.schema.projection/schema-admissions
                 :projection.test/id])]
    (is (= :agent (:seon.schema.admission/source admission)))
    (is (re-find #"Re-register"
                 (:seon.schema.admission/note admission))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"named predicate schema"
       (schema/projection-from-rows
        {:seon.schema/schema-rows [[:projection.test/value ":any"]]
         :seon.schema/function-contract-rows []}))))

(deftest agent-contract-completeness-is-structural
  (let [agent {:seon.schema.admission/source :agent}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"open agent-authored argument map"
         (schema/assert-complete-contract!
          {:seon.schema/identity 'projection.test/open
           :seon.schema/definition
           [:=> [:cat [:map [:projection.test/id :int]]] :int]
           :seon.schema/admission agent})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"bare nilable return"
         (schema/assert-complete-contract!
          {:seon.schema/identity 'projection.test/nilable
           :seon.schema/definition [:=> [:cat :int] [:maybe :int]]
           :seon.schema/admission agent})))
    (is (= 1
           (count
            (schema/assert-complete-contract!
             {:seon.schema/identity 'projection.test/advisory
              :seon.schema/definition
              [:=> [:cat [:maybe :int]] :int]
              :seon.schema/admission agent}))))))

(deftest core-contract-backlog-remains-advisory
  (is (= [{:seon.schema.advisory/kind :seon.schema.advisory/maybe
           :seon.schema/identity 'projection.test/core-nilable
           :seon.schema/path []}]
         (schema/assert-complete-contract!
          {:seon.schema/identity 'projection.test/core-nilable
           :seon.schema/definition [:=> [:cat :int] [:maybe :int]]
           :seon.schema/admission
           {:seon.schema.admission/source :core}}))))

(deftest named-predicate-generators-produce-valid-values
  (let [sci-options
        (schema/predicate-sci-options
         {'seon.schema/malli-form? schema/malli-form?
          'seon.db.protocol/ordinary-wire-value?
          protocol/ordinary-wire-value?
          'seon.ai.tokens/printable-value? tokens/printable-value?})
        options {::m/sci-options sci-options}]
    (doseq [schema-key [:seon.schema/malli-form
                        :seon.db.protocol/ordinary-wire-value
                        :seon.ai.tokens/printable-value]]
      (let [compiled (m/schema schema-key options)]
        (dotimes [_ 40]
          (let [generated (mg/generate compiled)]
            (is (m/validate compiled generated)
                (str schema-key " generated " (pr-str generated)))))))))

(deftest guarded-predicate-admission-has-an-execution-planner-seam
  (let [definition
        [:fn {:error/message "must be text"
              :gen/schema :string}
         'projection.test/text?]
        request
        {:seon.schema/identity :projection.test/text
         :seon.schema/definition definition
         :seon.schema/admission {:seon.schema.admission/source :agent}
         :seon.schema/predicate-functions
         {'projection.test/text? string?}}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"pure, capability-free transitive"
         (schema/assert-complete-contract! request)))
    (is (empty?
         (schema/assert-complete-contract!
         (assoc request :seon.schema/pure-predicate-symbols
                 #{'projection.test/text?}))))))

(deftest agent-registration-candidate-rejects-any
  (let [delta (schema/begin-registration-delta)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"named predicate schema"
         (schema/call-with-registration-delta
          delta
          #(schema/register! :projection.test/undefined :any))))
    (is (empty? (schema/changed-keys delta)))))
