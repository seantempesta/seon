(ns seon.refusal-grammar-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest reference-expectations-describe-the-schema-not-the-failure-category
  (let [check (m/schema :seon.cluster.eval/run
                        {:registry (:seon.schema.projection/registry
                                    (schema/handed-projection))})]
    (doseq [category [:malli.core/missing-key :malli.core/invalid-type]]
      (let [description (error/explain-problem
                         {:seon.error/problem {:schema check :type category :value false}
                          :seon.error/path [:seon.cluster.eval/run]
                          :seon.error/argument "transaction data"})]
        (is (str/includes? (:seon.error/expected-description description)
                           "an integer or a string or a tuple with 2 entries"))
        (is (not (str/includes? (:seon.error/expected-description description)
                                "invalid type")))
        (is (not (str/includes? (:seon.error/expected-description description)
                                "unknown error")))))))

(deftest collection-refusals-name-the-failing-member-and-element-form
  (let [description
        (error/explain-problem
         {:seon.error/problem
          {:schema (m/schema [:set :qualified-keyword])
           :value #{"seon.fn.index/17904"}}
          :seon.error/path [0 :seon.schema/references]
          :seon.error/argument "transaction data"})]
    (is (= :qualified-keyword (:seon.error/expected description)))
    (is (= "seon.fn.index/17904" (:seon.error/offending description)))
    (is (= "a collection member satisfying a namespaced keyword"
           (:seon.error/expected-description description)))
    (is (= "a collection member that is a string"
           (:seon.error/actual-description description)))))

(deftest a-bounded-schema-names-its-bound
  ;; printer-fixes-opus 2026-09-27: `[:int {:min 1}]` refusing 0 read
  ;; "expected an integer, got an integer 0".
  (doseq [[schema value expected]
          [[[:int {:min 1}] 0 "an integer at least 1"]
           [[:double {:min 0 :max 1}] 2.0 "a double from 0 to 1"]
           [[:string {:min 1}] "" "a string of length at least 1"]
           [[:vector {:max 2} :int] [1 2 3] "a vector of count at most 2"]
           [[:int {:min 2 :max 2}] 3 "an integer exactly 2"]
           [:int "x" "an integer"]]]
    (let [description (error/explain-problem
                       {:seon.error/problem (first (:errors (m/explain schema value)))
                        :seon.error/path []
                        :seon.error/argument "argument 0"})]
      (is (= expected (:seon.error/expected-description description)) (pr-str schema))
      (is (not (str/includes? (:seon.error/fix description) "Convert the sequence with vec"))
          "a vector over its bound is not told to become a vector"))))

(deftest structured-refusals-share-one-actionable-rendering
  (support/with-database
    (fn [connection]
      (let [unit {:seon.db/db @connection
                  :seon.render/profile
                  {:seon.render.profile/id ::profile
                   :seon.render.profile/token-budget 2048
                   :seon.render.profile/max-depth 8
                   :seon.render.profile/max-children 10
                   :seon.render.profile/max-string-length 256
                   :seon.render.profile/composition :multiline}}
            diagnostic {:seon.error/message "Invalid selector."
                         :seon.error/layer :database-read
                         :seon.error/operation 'seon.db/pull
                         :seon.error/expected :string
                         :seon.error/offending 42
                         :seon.error/data {:seon.error/member :selector}}
            shown (error/render-ai (assoc unit :seon.render/value diagnostic))]
        ;; "Example:" comes from the operation's docstring Examples; seon.db/pull
        ;; declares none, and the stored-doc enrichment that supplied one was
        ;; deleted in b1f291fbc (printing no longer enriches errors).
        (doseq [fragment ["seon.db/pull refused :selector: expected a string"
                          "got an integer 42" "Fix:"]]
          (is (str/includes? shown fragment) shown))
        (is (not (str/includes? shown "seon.error/data")) shown)
        (let [refusal (db/transact! connection
                                    [{:seon.fn/sym "seon.refusal-grammar-test/incomplete"
                                      :seon.fn/doc "incomplete"}])]
          (is (string? (:seon.db.write.attempt/request-id refusal))
              (pr-str refusal))
          ;; The refusal names its first problem as a sentence; concrete enum
          ;; choices are proven below on the enum itself (the fixture's
          ;; admission-source enum is no longer its first problem).
          (is (str/includes? (:seon.error/message refusal)
                             "expected a namespaced symbol, got a string")
              (pr-str refusal))
          (is (= "either :core or :agent"
                 (:seon.error/expected-description
                  (error/explain-problem
                   {:seon.error/problem {:schema (m/schema [:enum :core :agent]) :value :x}
                    :seon.error/path [0 :seon.schema.admission/source]
                    :seon.error/argument "transaction data"}))))
          (is (not (str/includes? (:seon.error/message refusal)
                                  "with a value satisfying missing required key"))
              (pr-str refusal)))
        (let [compiled (m/schema [:=> [:cat [:= 7]] :int]
                                {::m/function-checker mg/function-checker
                                 ::mg/=>iterations 1})
              problem (first (:errors (m/explain compiled (constantly "wrong"))))
              description (error/explain-problem
                           {:seon.error/problem problem :seon.error/path []
                            :seon.error/argument "return value"})
              refusal (assoc diagnostic :seon.error/data
                             {:seon.error/problems [description]
                              :seon.error/operation 'example/check})
              text (error/render-ai (assoc unit :seon.render/value refusal))]
          (is (= [7] (:seon.error/input description)))
          (is (= :int (:seon.error/expected description)))
          (is (= "wrong" (:seon.error/offending description)))
          (is (str/includes? text "Input: (7)" ) text)
          (is (not (str/includes? text "AFunction")) text))
        (let [wrapped (instrument/wrap-interpreted
                       'example/guard
                       "[:=> [:cat :int] :int [:fn {:error/message \"arguments and result must agree\"} clojure.core/map?]]"
                       (schema/handed-projection) (config/result-caps config/defaults) identity)
              refusal (support/refusal-data #(wrapped 7))
              text (error/render-ai (assoc unit :seon.render/value refusal))]
          (is (str/includes? text "Input: [7]" ) text)
          (is (str/includes? text "Result contract: :int") text))))))
