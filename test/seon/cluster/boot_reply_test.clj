(ns seon.cluster.boot-reply-test
  "A request's refusal crosses the prepl as shown text, bounded by the AI profile."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.boot :as boot]
            [seon.cluster.store :as store]
            [seon.test-support :as support]))

(deftest a-refused-request-carries-its-large-evidence-as-bounded-shown-text
  (let [connection (:seon.db/connection (support/execution-handle nil))
        root (or (store/declared-operator-root) (System/getProperty "user.dir"))
        ;; A mismatched process identity refuses with the whole request as evidence.
        reply (boot/request! {:seon.operator/command :status
                              :seon.operator/managed-root root
                              :seon.boot/pid 1
                              :seon.boot/start-instant #inst "2000-01-01T00:00:00Z"
                              ::connection connection
                              ::items (vec (range 100000))})
        printed (pr-str reply)]
    (is (:seon.error/at reply) printed)
    (is (string? (:seon.error/offending reply)) "the evidence is shown text")
    (is (< (count printed) 20000) (str (count printed) " chars"))
    (is (str/includes? (:seon.error/offending reply) ":seon.print/omitted 99968"))
    (is (str/includes? (:seon.error/offending reply) ":seon.print/requery-refusal")
        "the elision names why there is no requery over the wire")
    (is (not-any? #(contains? % :seon.error/data) (:seon.error/chain reply))
        "no chain link carries raw ex-data")))
