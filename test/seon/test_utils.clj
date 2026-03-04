(ns seon.test-utils
  "Testing utilities and fixtures for Seon.

  Provides:
  - Datalevin test helpers with safe connection management
  - Time helpers for test data"
  (:require [clojure.test :refer [is]]
            [datalevin.core :as d]
            [seon.db :as db])
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
         (is (= 1 (count (d/q '[:find ?e :where [?e :name _]] @conn))))))"
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
   of the Integrant system connection."
  [f]
  (require 'seon.ai.datalevin)
  (let [dir (str "tmp/dl-test-" (UUID/randomUUID))
        conn (d/create-conn dir {} {:kv-opts fast-kv-opts})
        test-conn-var (resolve 'seon.ai.datalevin/*test-conn*)]
    (try
      (push-thread-bindings {test-conn-var conn
                             #'db/*direct-write* true})
      (try
        (f)
        (finally
          (pop-thread-bindings)))
      (finally
        (when-not (d/closed? conn)
          (d/close conn))
        (delete-dir! dir)))))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn gen-uuid
  "Generate a random UUID."
  []
  (UUID/randomUUID))

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
