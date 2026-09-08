(ns seon.render.web-debug-test
  "Schema-authored block metadata and honest unavailable dependencies."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest blocks-use-the-values-schema-documentation
  (support/with-database
    {::support/extra-schema
     [{:seon.schema/key ::title :seon.schema/form ":string"}
      {:seon.schema/key ::notebook
       :seon.schema/form
       (pr-str [:map {:seon.db/attributes true
                      :title "Notebook"
                      :description "Notes for this concern."}
                [::title ::title]])}]}
    (fn [connection]
      (let [database @connection
            projection (schema/projection-from-database database)
            metadata (#'web/block-metadata projection database
                                            {::title "Example"} ::unlabelled nil)]
        (is (= ::notebook (:seon.schema/key metadata)))
        (is (= "Notebook" (:seon.render.web/block-title metadata)))
        (is (= "Notes for this concern."
               (:seon.render.web/block-description metadata)))
        (doseq [value [nil 42 "text" [{::title "Example"}]]]
          (is (map? (#'web/block-metadata projection database value ::unlabelled nil))
              "A scalar, absent attribute, or collection never enters entity transacting."))))))

(deftest unavailable-evaluation-query-is-shown-once
  (let [missing {:seon.render.web/function-unavailable 'example.missing/evaluations
                 :seon.error/kind :seon.render.web/function-unavailable
                 :seon.error/message "Not yet available: example.missing/evaluations"}
        html (#'web/debug-ai-html "example"
                                  {:seon.render.debug/request {}
                                   :seon.render.debug/evaluations missing})
        function-name "example.missing/evaluations"]
    (is (str/includes? html (str "Not yet available: " function-name)))
    (is (= (str/index-of html function-name) (str/last-index-of html function-name)))
    (is (not (str/includes? html "Context now")))
    (is (not (str/includes? html "Would-be system turn")))))
