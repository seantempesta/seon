(ns seon.render.web-prompt-test
  "Tests the debug prompt comparison's independent evidence labels."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.render.web :as web]))

(def ^:private latest-captured-prompt
  (ns-resolve 'seon.render.web 'latest-captured-prompt))
(def ^:private debug-prompt
  (ns-resolve 'seon.render.web 'debug-prompt))
(def ^:private debug-ai-html
  (ns-resolve 'seon.render.web 'debug-ai-html))

(deftest captured-prompt-retains-its-independent-evidence
  (with-redefs [db/q (fn [_]
                       [["captured text" 42 "capture-42"]])]
    (is (= {:seon.render.debug/prompt "captured text"
            :seon.render.debug/prompt-kind :captured
            :seon.render.debug/prompt-basis-t 42
            :seon.render.debug/prompt-id "capture-42"}
           (latest-captured-prompt :db :agent-1)))))

(deftest debug-prompt-keeps-captured-and-prospective-results
  (let [captured {:seon.render.debug/prompt "old"
                  :seon.render.debug/prompt-kind :captured
                  :seon.render.debug/prompt-basis-t 10
                  :seon.render.debug/prompt-id "capture-10"}
        prospective {:seon.render.debug/prompt "new"
                     :seon.render.debug/prompt-kind :prospective
                     :seon.render.debug/prompt-basis-t 11
                     :seon.render.debug/prompt-id [:prospective 11 :agent-1]}]
    (with-redefs-fn {#'seon.render.web/latest-captured-prompt
                     (constantly captured)
                     #'seon.render.web/prospective-prompt
                     (constantly prospective)}
      #(is (= {:seon.render.debug/prompt-kind :captured
               :seon.render.debug/prompt "old"
               :seon.render.debug/prompt-basis-t 10
               :seon.render.debug/prompt-id "capture-10"
               :seon.render.debug/captured captured
               :seon.render.debug/prospective prospective}
              (debug-prompt :db :connection :agent-1 :caps :context))))))

(deftest debug-html-labels-unavailable-prospective-prompt-honestly
  (testing "both panes retain their source label and database basis"
    (let [captured {:seon.render.debug/prompt "historical prompt"
                    :seon.render.debug/prompt-kind :captured
                    :seon.render.debug/prompt-basis-t 10
                    :seon.render.debug/prompt-id "capture-10"}
          prospective {:seon.render.debug/prompt-kind :unavailable
                       :seon.render.debug/prompt-basis-t 12
                       :seon.render.debug/prompt-id [:prospective 12 :agent-1]
                       :seon.error/value
                       {:seon.error/kind :seon.render.web/prospective-failed
                        :seon.error/data
                        {:seon.error/diagnostic-cause "test failure"}}}
          html (debug-ai-html
                "agent-1"
                {:seon.render.debug/prompt-kind :captured
                 :seon.render.debug/captured captured
                 :seon.render.debug/prospective prospective})]
      (is (.contains html "historical captured prompt"))
      (is (.contains html "newly computed prospective prompt"))
      (is (.contains html "database basis 10"))
      (is (.contains html "database basis 12"))
      (is (.contains html "historical prompt"))
      (is (.contains html "seon.render.web/prospective-failed")))))
