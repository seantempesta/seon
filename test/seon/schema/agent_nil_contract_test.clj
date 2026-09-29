(ns seon.schema.agent-nil-contract-test
  "Agent-entered function contracts treat `:nil` as the exact singleton type
   Malli declares (`-nil-schema`, reference-code/malli/src/malli/core.cljc:811),
   and a function-contract refusal names the function symbol it refused."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [seon.schema :as schema]))

(def ^:private agent-admission {:seon.schema.admission/source :agent})

(defn- admission
  "`:admitted`, or the refusal's ex-data when agent admission throws."
  [projection function-symbol contract]
  (try (schema/projection-with-function-contract projection function-symbol contract agent-admission)
       :admitted
       (catch clojure.lang.ExceptionInfo failure (ex-data failure))))

(defn- packaged-projection
  "The packaged declaration population, each declaration admitted as `:core`
   as the database's packaged rows are."
  []
  (let [forms (schema/snapshot)]
    (schema/build-projection
     forms {}
     {:seon.schema/schema-admissions
      (zipmap (keys forms) (repeat {:seon.schema.admission/source :core}))})))

(defn- edn-round-trip?
  [value]
  (try (= value (edn/read-string (pr-str value)))
       (catch RuntimeException _ false)))

(defn- core-nil-contracts
  "Loaded `seon.*` function contracts mentioning `:nil` that an agent could
   re-enter as source text (EDN round-trips)."
  []
  (for [loaded (all-ns)
        :when (.startsWith (str (ns-name loaded)) "seon.")
        [interned interned-var] (ns-interns loaded)
        :let [contract (:malli/schema (meta interned-var))]
        :when (and contract
                   (some #{:nil} (tree-seq coll? seq contract))
                   (edn-round-trip? contract))]
    [(symbol (str (ns-name loaded)) (str interned)) contract]))

(deftest agent-contracts-admit-nil-and-refuse-nilable-unions-alike
  (let [projection (packaged-projection)
        subject 'scratch.agent-nil-contract/probe]
    (testing "`:nil` in an argument union and as a bare return is exact"
      (is (= :admitted (admission projection subject [:=> [:cat [:or :nil :int]] :int])))
      (is (= :admitted (admission projection subject [:=> [:cat :int] :nil]))))
    (testing "`[:or :nil X]` refuses exactly where `[:maybe X]` refuses"
      (doseq [[_position maybe-form or-form expected]
              [[:bare-return
                [:=> [:cat :int] [:maybe :int]]
                [:=> [:cat :int] [:or :nil :int]]
                :seon.schema/nilable-return]
               [:map-value
                [:=> [:cat [:map [:scratch.agent-nil-contract/x [:maybe :int]]]] :int]
                [:=> [:cat [:map [:scratch.agent-nil-contract/x [:or :int :nil]]]] :int]
                :seon.schema/nilable-map-value]]]
        (let [maybe-refusal (admission projection subject maybe-form)
              or-refusal (admission projection subject or-form)]
          (is (= expected (:seon.schema/error maybe-refusal)))
          (is (= expected (:seon.schema/error or-refusal)))
          (is (= subject (get or-refusal expected))))))
    (testing "an undefined slot is advice, never a refusal (owner ruling 2026-09-24)"
      (is (= :admitted (admission projection subject [:=> [:cat :any] :int]))))
    (testing "no packaged core contract is refused for :nil or through the helper's contract"
      (let [contracts (core-nil-contracts)
            outcomes (for [[function-symbol contract] contracts]
                       [function-symbol (admission projection function-symbol contract)])]
        (is (seq contracts))
        (doseq [[function-symbol outcome] outcomes
                :when (not= :admitted outcome)]
          (is (qualified-keyword? (:seon.schema/error outcome)))
          (is (or (= function-symbol (:seon.schema/identity outcome))
                  (qualified-keyword? (:seon.schema/identity outcome))))
          (is (not= :nil (:seon.schema/schema-type outcome))))))))
