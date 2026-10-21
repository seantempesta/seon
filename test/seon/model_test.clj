(ns seon.model-test
  "The model edge as data: a provider's reply body read into the completion (task
  seon.fences/budgets-are-data-and-spend-is-measured). Run: bin/test seon.model-test"
  (:require [clojure.test :refer [deftest is testing]] [seon.model :as model]))

(deftest a-reply-without-usage-records-no-tokens
  (testing "the provider sent no usage: the completion states no token counts (absent is not 0)"
    (let [c (model/parse-reply "{\"choices\":[{\"message\":{\"content\":\"(+ 1 2)\"}}]}" 12.0 2048)]
      (is (= "(+ 1 2)" (:text c)))
      (is (= #{} (set (filter #{:prompt-tokens :completion-tokens} (keys c)))))))
  (testing "usage sent: both counts, as the provider measured them"
    (let [c (model/parse-reply "{\"choices\":[{\"message\":{\"content\":\"x\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}" 12.0 2048)]
      (is (= [7 3] [(:prompt-tokens c) (:completion-tokens c)])))))
