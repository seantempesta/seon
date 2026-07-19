(ns seon.eval.repair-batch-test
  "Pure read-error guidance retained from the former database batch harness."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.config :as config]
   [seon.eval :as eval]
   [seon.repl :as repl]))

(def ^:private configuration (config/resolve-config-singleton {}))

(deftest read-error-guidance-distinguishes-truncation-from-delimiters
  (let [eof (eval/read-error-message
             configuration
             "Unexpected EOF while reading string. [at line 2, column 26]"
             "(message/agent \"abc\" (str \"# Report\nlots cut off mid")
        delimiter (eval/read-error-message
                   configuration
                   "Unmatched delimiter: ] [at line 1, column 8]"
                   "(foo bar])")]
    (testing "truncated output teaches store-data and send-pointer"
      (is (re-find #"(?i)truncat" eof))
      (is (re-find #"(?i)pointer" eof))
      (is (re-find #"my\.kb|:seon\.items" eof))
      (is (not (re-find #"(?i)fix the delimiter" eof))))
    (testing "an unmatched delimiter keeps delimiter guidance"
      (is (re-find #"(?i)fix the delimiter" delimiter))
      (is (not (re-find #"(?i)pointer" delimiter))))))

(deftest preflight-skips-referred-macro-invocations
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
         (fn [compile-state]
           (-> (eval/eval
                compile-state
                "(ns scratch.repair-macro (:require [cljs.test :refer [deftest is]]))"
                {:seon.config/configuration configuration
                 :seon.eval/starting-ns 'cljs.user
                 :seon.eval/analyze-deps? false})
               (.then
                (fn [result]
                  (is (:seon.eval/ok? result))
                  (let [eligible? (deref #'eval/preflight-eligible?)]
                    (is (false?
                         (eligible? configuration compile-state
                                    "(deftest works (is (= 1 1)))"
                                    'scratch.repair-macro))
                        "compile-only repair never expands a live macro")
                    (is (true?
                         (eligible? configuration compile-state
                                    "(missing-function 1)"
                                    'scratch.repair-macro))
                        "ordinary symbol repair remains enabled")))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str error))
                  (done))))))
