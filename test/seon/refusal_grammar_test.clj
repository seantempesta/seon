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
            diagnostic (error/diagnostic
                        {:seon.error/kind :seon.db/invalid-read
                         :seon.error/message "Invalid selector."
                         :seon.error/diagnostic-layer :database-read
                         :seon.error/diagnostic-operation 'seon.db/pull
                         :seon.error/diagnostic-member :selector
                         :seon.error/diagnostic-expected :string
                         :seon.error/diagnostic-offending 42
                         :seon.error/diagnostic-cause :invalid-selector
                         :seon.error/diagnostic-evidence {}})
            shown (error/render-ai (assoc unit :seon.render/value diagnostic))]
        (doseq [fragment ["seon.db/pull refused :selector at []: expected a string"
                          "got an integer 42" "Fix:" "Example:"]]
          (is (str/includes? shown fragment) shown))
        (is (not (str/includes? shown "diagnostic-evidence")) shown)
        (let [refusal (db/transact! connection
                                    [{:seon.fn/sym "seon.id/id"
                                      :seon.fn/doc "incomplete"}])]
          (is (= :seon.db/invalid-write (:seon.error/kind refusal))
              (pr-str refusal))
          (is (str/includes? (:seon.error/message refusal)
                             ":core")
              (pr-str refusal))
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
                             {:seon.error/diagnostic-operation 'example/check
                              :seon.error/problems [description]})
              text (error/render-ai (assoc unit :seon.render/value refusal))]
          (is (= [7] (:seon.error/input description)))
          (is (= :int (:seon.error/expected description)))
          (is (= "wrong" (:seon.error/offending description)))
          (is (str/includes? text "Input: (7)" ) text)
          (is (not (str/includes? text "AFunction")) text))
        (let [wrapped (instrument/wrap-interpreted
                       'example/guard
                       "[:=> [:cat :int] :int [:fn {:error/message \"arguments and result must agree\"} clojure.core/map?]]"
                       (schema/handed-projection) :panic
                       (config/result-caps (config/defaults)) identity)
              refusal (support/refusal-data #(wrapped 7))
              text (error/render-ai (assoc unit :seon.render/value refusal))]
          (is (str/includes? text "Input: [7]" ) text)
          (is (str/includes? text "Result contract: :int") text))))))
