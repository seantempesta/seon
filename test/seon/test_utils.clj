(ns seon.test-utils
  "Testing utilities and fixtures for Seon.

  Provides:
  - Datalevin test helpers with safe connection management
  - Test data generators
  - Property-based testing helpers"
  (:require [clojure.test :refer [is]]
            [datalevin.core :as d]
            [seon.db.schema :as schema]
            [malli.generator :as mg])
  (:import [java.io File]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Legacy Test Node Fixture (stub)
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-node*
  "Dynamic var for test database node. Retained for backward compatibility."
  nil)

(defn with-test-node
  "Legacy fixture stub. Tests that need a database should use with-test-datalevin instead."
  [f]
  (f))

;;; ---------------------------------------------------------------------------
;;; Datalevin Test Helpers
;;; ---------------------------------------------------------------------------

(def ^:private fast-kv-opts
  "KV options for fast test databases. :nosync skips fsync for speed."
  {:flags #{:nordahead :writemap :mapasync :nosync}})

(defn- delete-dir!
  "Recursively delete a directory and all its contents."
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^File child)))))

(defn with-temp-conn
  "Create a temporary Datalevin connection, run f with it, then clean up.

   Uses d/create-conn (not d/get-conn) to avoid the global connection cache.
   Uses :nosync for speed. Connection is closed and directory deleted on exit.

   Usage:
     (with-temp-conn schema
       (fn [conn]
         (d/transact! conn [{:name \"test\"}])
         (is (= 1 (count (d/q '[:find ?e :where [?e :name _]] @conn))))))

   Also works as a test fixture (0-arity schema, 1-arity test-fn):
     (with-temp-conn (fn [conn] ...))"
  ([f] (with-temp-conn {} f))
  ([db-schema f]
   (let [dir  (str "tmp/test-" (System/nanoTime))
         conn (d/create-conn dir db-schema {:kv-opts fast-kv-opts})]
     (try
       (f conn)
       (finally
         (when-not (d/closed? conn)
           (d/close conn))
         (delete-dir! dir))))))

(defn with-test-datalevin
  "Fixture that provides a temporary Datalevin connection for AI tests.
   Sets seon.ai.datalevin/*test-conn* so AI functions use it instead
   of the Integrant system connection.

   Usage:
     (use-fixtures :each with-test-datalevin)

   Can be composed with with-test-node for tests needing both."
  [f]
  (require 'seon.ai.datalevin)
  (let [dir (str "tmp/dl-test-" (UUID/randomUUID))
        conn (d/create-conn dir {} {:kv-opts fast-kv-opts})
        test-conn-var (resolve 'seon.ai.datalevin/*test-conn*)]
    (try
      (push-thread-bindings {test-conn-var conn})
      (try
        (f)
        (finally
          (pop-thread-bindings)))
      (finally
        (when-not (d/closed? conn)
          (d/close conn))
        (delete-dir! dir)))))

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
        expiries (map (fn [_] (java.time.Instant/now)) (range n-expiries))]
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
  [_ticker n-points]
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
