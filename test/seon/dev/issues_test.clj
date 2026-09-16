(ns seon.dev.issues-test
  (:require [clojure.test] [seon.dev.issues]))

(clojure.test/deftest issue-cli-reports-absence-and-refusals
 (clojure.test/is (= 0 (seon.dev.issues/exit-code {:seon.issue/count 0 :seon.issue/refusals []})))
 (clojure.test/is (= 1 (seon.dev.issues/exit-code nil)))
 (clojure.test/is (= 1 (seon.dev.issues/exit-code {:seon.issue/count 1 :seon.issue/refusals [{}]})))
 (clojure.test/is (= 'do (first (seon.dev.issues/query-form ["--check"]))))
 (clojure.test/is (thrown? clojure.lang.ExceptionInfo (seon.dev.issues/query-form ["--unknown"]))))
