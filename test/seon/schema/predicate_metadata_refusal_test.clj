(ns seon.schema.predicate-metadata-refusal-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.registry :as mr]
            [seon.config :as config]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.test-support :as support]
            [seon.print.value]))

(def ^:private policy
  {:seon.config.error/max-evidence-bytes 8192})

(defn- refusal-of
  "Arm `authored` for `operation` around `body` and call it with `arguments`."
  [projection operation authored body arguments]
  (let [wrapped (#'instrument/compiled-wrapper projection operation authored body
                 (config/result-caps config/defaults) policy)]
    (support/refusal-data #(apply wrapped arguments))))

(deftest ^{:seon/tasks #{"407264e2b69d" "7095e91b3f42"}}
  a-refusal-carries-its-producers-arm-identity-for-any-predicate
  ;; E2 (agent-path root-cause audit M3): a contract evaluated from `defn`
  ;; metadata holds the predicate's root function, which names nothing. The
  ;; refusal carries the arm identity fixed when the wrapper was armed, so it
  ;; never asks durable canonicalization to name a compiled callable.
  (support/with-database
   (fn [_]
     (let [projection (schema/handed-projection)
           operation 'seon.schema.predicate-metadata-refusal-test/probe
           registry (:seon.schema.projection/registry projection)
           evaluated [:=> [:cat [:fn clojure.core/ifn?] :int] :any]
           refusal (refusal-of projection operation evaluated (fn [f x] (f x)) [1 2])
           valid? (schema/projection-validator projection :seon.instrument/contract-error)]
       (is (nil? (mr/schema registry operation)) "armed from the evaluated metadata itself")
       (is (not (contains? (set (vals (schema/predicate-functions-in projection))) ifn?)))
       (is (valid? refusal) (seon.print.value/bounded-text refusal))
       (is (= :input (:seon.instrument/check refusal)))
       (is (= operation (:seon.error/operation refusal)))
       (is (= 1 (:seon.error/offending (first (:seon.error/problems refusal)))))
       (is (str/starts-with? (:seon.error/message refusal) (str operation " refused"))
           (:seon.error/message refusal))
       (is (not (str/includes? (:seon.error/message refusal) "could not be built")))
       (is (= (id/digest 64 [operation 0 :input]) (:seon.error/expected-shape refusal))
           "the producer's declared arm: function, arity ordinal, check")
       (let [multi [:function
                    [:=> [:cat [:fn clojure.core/ifn?]] :any]
                    [:=> [:cat [:fn clojure.core/ifn?] :int] :any]]
             two (refusal-of projection operation multi (fn ([f] (f)) ([f x] (f x))) [1 2])
             returned (refusal-of projection operation
                                  [:=> [:cat :int] [:fn clojure.core/ifn?]] identity [3])]
         (is (valid? two) (seon.print.value/bounded-text two))
         (is (= (id/digest 64 [operation 1 :input]) (:seon.error/expected-shape two))
             "the second declared arity is ordinal 1")
         (is (valid? returned) (seon.print.value/bounded-text returned))
         (is (= (id/digest 64 [operation 0 :output]) (:seon.error/expected-shape returned))))))))

(deftest ^{:seon/tasks #{"407264e2b69d"}}
  an-interpreted-contract-naming-a-core-predicate-refuses-readably
  ;; The SCI path: the committed spec names the predicate; binding resolves
  ;; it to its root, which the refusal never has to name again.
  (support/with-database
   (fn [_]
     (let [projection (schema/handed-projection)
           wrapped (instrument/wrap-interpreted
                    'seon.schema.predicate-metadata-refusal-test/interpreted
                    "[:=> [:cat [:fn clojure.core/ifn?] :int] :any]"
                    projection (config/result-caps config/defaults)
                    (fn [f x] (f x)))
           thrown (try (wrapped 1 2) nil (catch clojure.lang.ExceptionInfo failure failure))
           refusal (ex-data thrown)]
       (is (= 3 (wrapped inc 2)))
       (is ((schema/projection-validator projection :seon.instrument/contract-error) refusal)
           (seon.print.value/bounded-text refusal))
       (is (= :seon.instrument/contract-error
              (:seon.error/declared-schema (meta refusal)))
           "the producer's declared identity rides on the thrown value")
       (is (not (str/includes? (ex-message thrown) "could not be built")) (ex-message thrown))))))
