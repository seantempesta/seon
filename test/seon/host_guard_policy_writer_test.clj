(ns seon.host-guard-policy-writer-test
  "Guard-policy acquisition rejects absent data before the guarded door."
  (:require [clojure.test :refer [deftest is]]
            [seon.host.context :as context]
            [seon.host.sample :as sample]))

(def complete-row [101 102 103 104 105])

(deftest absent-or-incomplete-guard-policy-is-loud
  (doseq [row [nil [] [1 2 3 4]]]
    (with-redefs [context/query-writer-at!
                  (fn [_writer _database _query _arguments] row)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"lacks a complete SCI guard policy"
           (sample/acquire-guard-policy! ::writer ::database))))))

(deftest complete-positive-guard-policy-is-data
  (with-redefs [context/query-writer-at!
                (fn [_writer _database _query _arguments] complete-row)]
    (is (= {:seon.config.guard/agent-eval-fuel 101
            :seon.config.guard/authored-render-fuel 102
            :seon.config.guard/plan-fuel 103
            :seon.config.guard/deadline-ms 104
            :seon.config.guard/output-cap 105}
           (sample/acquire-guard-policy! ::writer ::database)))))
