(ns seon.test-preparation-test
  "Worker readiness includes the real canonical fixture acquisition."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support])
  (:import [java.io BufferedReader PrintWriter StringReader StringWriter]))

(deftest ^{:seon.test/platform "Worker readiness precedes every per-test clock."}
  worker-readiness-holds-a-usable-canonical-base
  (let [output (StringWriter.)
        reader (BufferedReader. (StringReader. ""))]
    (#'runner/worker-command-loop! "fixture-readiness" reader (PrintWriter. output))
    (let [line (first (str/split-lines (str output)))
          event (edn/read-string (subs line (count @#'runner/protocol-prefix)))
          base @#'test-support/database-base]
      (is (= :ready (::runner/worker-event event)))
      (is (= "fixture-readiness/readiness" (::runner/exchange-id event)))
      (is (nat-int? (::runner/fixture-preparation-ms event)))
      (is (realized? base) "Readiness must not leave acquisition to the first test.")
      (is (nil? (:seon.error/kind @base)))
      (test-support/with-database
        (fn [connection]
          (is (some? (:seon.test-support/connection @base)))
          (is (pos? (:max-tx @connection))))))))
