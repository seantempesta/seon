(ns seon.test-utils
  "Testing utilities and fixtures for Seon.

  Provides:
  - Datalevin test helpers with safe connection management
  - Time helpers for test data"
  (:require [clojure.test :refer [is]]
            [datalevin.constants :as dc]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as conn])
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

;; Reduce LMDB map size for all tests. Default is 1000 MiB per db; tests need only ~10 MiB.
;; This prevents OutOfMemoryError on direct buffer memory when many test connections are created.
(alter-var-root #'dc/*init-db-size* (constantly 10))

(defn with-small-db-size
  "Fixture that binds Datalevin init-db-size to 10 MiB for all tests in a namespace.
   Use as: (use-fixtures :once tu/with-small-db-size)"
  [f]
  (binding [dc/*init-db-size* 10]
    (f)))

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
   (binding [dc/*init-db-size* 10]
     (let [dir  (str "tmp/test-" (System/nanoTime))
           conn (d/create-conn dir db-schema {:kv-opts fast-kv-opts})]
       (try
         (f conn)
         (finally
           (when-not (d/closed? conn)
             (d/close conn))
           (delete-dir! dir)))))))

(defn with-test-datalevin
  "Fixture that provides a temporary Datalevin connection for AI tests.
   Binds db/*conn-manager* with a fake manager mapping :seon.ai to a temp conn,
   and db/*direct-mode* to true so reads/writes bypass the infrastructure flow."
  [f]
  (binding [dc/*init-db-size* 10]
    (let [dir (str "tmp/dl-test-" (UUID/randomUUID))
          conn (d/create-conn dir {} {:kv-opts fast-kv-opts})
          fake-mgr {::conn/port 0
                    ::conn/connections (atom {:seon.ai {::conn/connection conn}})}]
      (try
        (binding [db/*direct-mode* true
                  db/*conn-manager* fake-mgr]
          (f))
        (finally
          (when-not (d/closed? conn)
            (d/close conn))
          (delete-dir! dir))))))

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
