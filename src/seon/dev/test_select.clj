(ns seon.dev.test-select
  "Dependency-aware test selection.

   Uses the code graph to find test namespaces affected by a change,
   then runs them via clojure.test. This avoids running the entire test
   suite when only a few namespaces are affected.

   Key functions:
   - affected-namespaces     - Changed ns + its dependents
   - affected-test-namespaces - Filter to existing test namespaces
   - run-affected-tests!     - Run kaocha/clojure.test on affected tests

   Example:
     (require '[seon.dev.test-select :as ts])

     ;; Find what tests to run after editing seon.schema
     (ts/affected-test-namespaces
       {::ts/db-name :seon.runtime ::ts/ns-name \"seon.schema\"})
     ;; => [seon.schema-test seon.ai-test ...]

     ;; Run them
     (ts/run-affected-tests!
       {::ts/db-name :seon.runtime ::ts/ns-name \"seon.schema\"})"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [seon.dev.verify :as verify]
            [seon.graph.query :as gq]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::db-name
                  [:keyword {:description "Database name keyword for graph queries"}])

(schema/register! ::ns-name
                  [:string {:min 1 :description "Changed namespace name"}])

(schema/register! ::depth
                  [:enum {:description "How deep to look for dependents"}
                   :direct :transitive])

(schema/register! ::affected-namespaces
                  [:vector {:description "Namespaces affected by the change"}
                   :string])

(schema/register! ::test-namespaces
                  [:vector {:description "Test namespace symbols to run"}
                   symbol?])

(schema/register! ::test-results
                  [:map
                   [::total-tests :int]
                   [::total-pass :int]
                   [::total-fail :int]
                   [::total-error :int]
                   [::success :boolean]
                   [::namespaces-tested [:vector symbol?]]
                   [::details [:vector :any]]])

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn affected-namespaces
  "Given a changed namespace, return it plus its dependents from the graph.

   Options:
     ::depth - :direct (default) or :transitive

   Returns vector of namespace name strings including the changed ns.

   Example:
     (affected-namespaces {::db-name :seon.runtime ::ns-name \"seon.schema\"})
     ;; => [\"seon.schema\" \"seon.ai\" \"seon.dev.hook\" ...]"
  [{::keys [db-name ns-name depth] :or {depth :direct}}]
  (let [dependents (if (= depth :transitive)
                     (gq/transitive-dependents-of {::gq/db-name db-name ::gq/ns-name ns-name})
                     (gq/dependents-of {::gq/db-name db-name ::gq/ns-name ns-name}))]
    (into [ns-name] (remove #{ns-name}) dependents)))

(defn affected-test-namespaces
  "Map affected namespaces to their test counterparts, filtering to those that exist.

   Appends '-test' to each affected namespace, then checks if that namespace
   can be found on the classpath.

   Returns vector of test namespace symbols.

   Example:
     (affected-test-namespaces {::db-name :seon.runtime ::ns-name \"seon.schema\"})
     ;; => [seon.schema-test seon.ai-test]"
  [{::keys [db-name ns-name depth] :or {depth :direct}}]
  (let [affected (affected-namespaces {::db-name db-name ::ns-name ns-name ::depth depth})]
    (->> affected
         (map (fn [ns-str]
                (if (str/ends-with? ns-str "-test")
                  ns-str
                  (str ns-str "-test"))))
         (map symbol)
         (filter (fn [test-sym]
                   (try
                     ;; Check if namespace source exists on classpath
                     (let [path (-> (str test-sym)
                                    (str/replace "." "/")
                                    (str/replace "-" "_")
                                    (str ".clj"))]
                       (some? (clojure.java.io/resource path)))
                     (catch Exception _ false))))
         vec)))

(defn run-affected-tests!
  "Run tests for all namespaces affected by a change.

   Finds affected test namespaces via the graph, then runs each one
   using seon.dev.verify/run-unit-tests. Returns aggregated results.

   Options:
     ::depth - :direct (default) or :transitive
     ::warn-threshold-ms - Warn if estimated time exceeds this (default 30000)

   Example:
     (run-affected-tests! {::db-name :seon.runtime ::ns-name \"seon.trading.signals\"})
     ;; => {::success true ::total-tests 12 ::namespaces-tested [seon.trading.signals-test] ...}"
  [{::keys [db-name ns-name depth warn-threshold-ms]
    :or {depth :direct warn-threshold-ms 30000}}]
  (let [test-nses (if db-name
                    (affected-test-namespaces
                     {::db-name db-name ::ns-name ns-name ::depth depth})
                    ;; Fallback: no graph, just try the changed ns's test
                    (let [test-sym (symbol (str ns-name "-test"))]
                      (try
                        (let [path (-> (str test-sym)
                                       (str/replace "." "/")
                                       (str/replace "-" "_")
                                       (str ".clj"))]
                          (if (clojure.java.io/resource path)
                            [test-sym]
                            []))
                        (catch Exception _ []))))]
    (when (> (count test-nses) 10)
      (log/warn "Running tests for" (count test-nses) "namespaces - this may take a while"))
    (if (empty? test-nses)
      {::success true
       ::total-tests 0
       ::total-pass 0
       ::total-fail 0
       ::total-error 0
       ::namespaces-tested []
       ::details []}
      (let [start (System/currentTimeMillis)
            results (mapv (fn [test-ns]
                            (let [r (verify/run-unit-tests
                                     {::verify/test-ns test-ns})]
                              (assoc r ::verify/test-ns test-ns)))
                          test-nses)
            elapsed (- (System/currentTimeMillis) start)]
        (when (> elapsed warn-threshold-ms)
          (log/warn "Affected tests took" elapsed "ms"))
        {::success (every? ::verify/success results)
         ::total-tests (reduce + 0 (keep ::verify/test-count results))
         ::total-pass (reduce + 0 (keep ::verify/pass-count results))
         ::total-fail (reduce + 0 (keep ::verify/fail-count results))
         ::total-error (reduce + 0 (keep ::verify/error-count results))
         ::namespaces-tested test-nses
         ::elapsed-ms elapsed
         ::details results}))))
