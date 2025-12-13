(ns ml-options.test-utils
  "Testing utilities and fixtures for ML Options Trading.

  Provides:
  - In-memory XTDB node fixture
  - Test data generators
  - Property-based testing helpers"
  (:require [clojure.test :refer :all]
            [ml-options.db.schema :as schema]
            [malli.generator :as mg])
  (:import [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; XTDB Test Fixture
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-node*
  "Dynamic var for test XTDB node."
  nil)

(defn with-test-node
  "Fixture that provides an in-memory XTDB node for tests.

  Usage:
    (use-fixtures :each with-test-node)

  Then access the node via *test-node*"
  [f]
  ;; Require at runtime to avoid loading XTDB during compilation
  (require '[xtdb.node :as xtn])
  (require '[xtdb.api :as xt])
  (let [start-node (resolve 'xtn/start-node)]
    (with-open [node (start-node)]
      (binding [*test-node* node]
        (f)))))

(defmacro with-temp-node
  "Execute body with a temporary XTDB node.

  Usage:
    (with-temp-node [node]
      (xt/submit-tx node [...])
      (is (= ...)))"
  [[node-sym] & body]
  `(do
     (require '[xtdb.node :as xtn#])
     (let [start-node# (resolve 'xtn#/start-node)]
       (with-open [~node-sym (start-node#)]
         ~@body))))

;;; ---------------------------------------------------------------------------
;;; Test Data Generators
;;; ---------------------------------------------------------------------------

(defn gen-uuid
  "Generate a random UUID."
  []
  (UUID/randomUUID))

(defn gen-option-quote
  "Generate a random option quote.

  Args:
    overrides - Map of fields to override

  Returns:
    Valid option quote map"
  ([]
   (gen-option-quote {}))
  ([overrides]
   (merge (mg/generate schema/OptionQuote {:registry @schema/registry
                                           :size 10})
          {:xt/id (gen-uuid)}
          overrides)))

(defn gen-option-chain
  "Generate a complete options chain for testing.

  Args:
    ticker - Underlying symbol
    spot - Current spot price
    n-strikes - Number of strikes per expiry
    n-expiries - Number of expiration dates

  Returns:
    Sequence of option quotes"
  [ticker spot n-strikes n-expiries]
  (let [strike-range (range (* spot 0.8) (* spot 1.2) (/ (* spot 0.4) n-strikes))
        expiries (map #(java.time.Instant/now) (range n-expiries))]
    (for [strike strike-range
          expiry expiries
          opt-type [:call :put]]
      (gen-option-quote {:asset/ticker ticker
                         :option/strike strike
                         :option/type opt-type
                         :option/expiry expiry}))))

(defn gen-iv-surface
  "Generate a test IV surface.

  Args:
    ticker - Underlying symbol
    n-points - Number of surface points

  Returns:
    Valid IV surface map"
  [ticker n-points]
  (mg/generate schema/IVSurface {:registry @schema/registry
                                 :size n-points}))

(defn gen-trading-signal
  "Generate a test trading signal.

  Args:
    overrides - Map of fields to override

  Returns:
    Valid trading signal map"
  ([]
   (gen-trading-signal {}))
  ([overrides]
   (merge (mg/generate schema/TradingSignal {:registry @schema/registry})
          overrides)))

;;; ---------------------------------------------------------------------------
;;; Test Assertions
;;; ---------------------------------------------------------------------------

(defn valid-quote?
  "Check if a map is a valid option quote."
  [m]
  (schema/validate schema/OptionQuote m))

(defn valid-surface?
  "Check if a map is a valid IV surface."
  [m]
  (schema/validate schema/IVSurface m))

(defn valid-signal?
  "Check if a map is a valid trading signal."
  [m]
  (schema/validate schema/TradingSignal m))

;;; ---------------------------------------------------------------------------
;;; Property Testing Helpers
;;; ---------------------------------------------------------------------------

(defmacro defprop
  "Define a property-based test.

  Usage:
    (defprop my-property
      {:num-tests 100}
      [x (mg/generator :int)]
      (is (= x x)))"
  [name opts bindings & body]
  `(clojure.test/deftest ~name
     (let [result#
           (clojure.test.check.clojure-test/defspec
             ~(symbol (str name "-prop"))
             ~(:num-tests opts 100)
             (clojure.test.check.properties/for-all
              ~bindings
              ~@body))]
       (is (:pass? result#)
           (str "Property failed: " (:result result#))))))

;; TODO: Fix this function - for-all is a macro and can't be resolved dynamically
;; (defn check-property
;;   "Run a property check with custom options.
;;
;;   Args:
;;     gen - Generator for input
;;     prop-fn - Property function (returns truthy if property holds)
;;     opts - Options {:num-tests :seed}
;;
;;   Returns:
;;     Test result map"
;;   [gen prop-fn & {:keys [num-tests seed] :or {num-tests 100}}]
;;   (require '[clojure.test.check :as tc])
;;   (require '[clojure.test.check.properties :as prop])
;;   (let [quick-check (resolve 'tc/quick-check)
;;         for-all (resolve 'prop/for-all)]
;;     (quick-check num-tests
;;                  (for-all [x gen]
;;                    (prop-fn x))
;;                  (when seed {:seed seed}))))

;;; ---------------------------------------------------------------------------
;;; Time Helpers
;;; ---------------------------------------------------------------------------

(defn days-ago
  "Create an Instant n days ago."
  [n]
  (.minus (java.time.Instant/now)
          (java.time.Duration/ofDays n)))

(defn days-from-now
  "Create an Instant n days from now."
  [n]
  (.plus (java.time.Instant/now)
         (java.time.Duration/ofDays n)))
