(ns seon.eval.repair-batch-test
  "Pure read-error guidance retained from the former database batch harness."
  (:require
   [cljs.test :refer [deftest is testing]]
   [seon.config :as config]
   [seon.eval :as eval]))

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
