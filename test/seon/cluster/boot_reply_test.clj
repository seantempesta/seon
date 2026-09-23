(ns seon.cluster.boot-reply-test
  "A request's refusal crosses the prepl as bounded text from the value
  renderer's projection-free core."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
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
    (is (str/includes? (:seon.error/offending reply) "…") "the bound is marked")
    (is (not-any? #(contains? % :seon.error/data) (:seon.error/chain reply))
        "no chain link carries raw ex-data")))

(deftype Hostile []
  Object
  (toString [_] (throw (IllegalStateException. "toString must not run"))))

(defmethod print-method Hostile [_ _]
  (throw (IllegalStateException. "print-method must not run")))

(deftest a-refusal-whose-evidence-cannot-print-still-names-its-cause
  (let [root (or (store/declared-operator-root) (System/getProperty "user.dir"))
        reply (boot/request! {:seon.operator/command :status
                              :seon.operator/managed-root root
                              :seon.boot/pid 1
                              :seon.boot/start-instant #inst "2000-01-01T00:00:00Z"
                              ::hostile (Hostile.)})]
    (is (= reply (edn/read-string (pr-str reply))) "the reply reads back whole")
    (is (= "Request process identity does not match this process." (:seon.error/message reply)))
    (is (string? (:seon.error/offending reply)) "the evidence is bounded text")
    (is (not (str/includes? (pr-str reply) "must not run"))
        "the object's print-method and toString never ran")))
