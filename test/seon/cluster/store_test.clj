(ns seon.cluster.store-test
  "Sealed acceptance for the store rung (B1).

  Orchestrator-authored (2026-07-27). The implementation lane makes
  these green by implementing the seon.cluster.store stubs ONLY —
  schemas and tests are byte-sealed; friction is reported, never
  resolved by weakening. The lifecycle tests are LIVE against the
  `:file` backend under project-local tmp/; the flock proof runs a REAL
  child JVM holding the store through the contract under test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [konserve.core :as k]
            [konserve.filestore :as filestore]
            [seon.cluster.store :as store]
            [seon.schema])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(defn- fresh-dir []
  (let [dir (str "tmp/store-test/" (random-uuid) "/store")]
    (.mkdirs (.getParentFile (io/file dir)))
    dir))

(defn- delete-recursively! [path]
  (let [file (.getCanonicalFile (io/file path))]
    (when (.exists file)
      (doseq [child (reverse (file-seq file))]
        (.delete ^java.io.File child)))))

(def ^:private probe-schema
  [{:db/ident :seon.store.test/marker
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}])

(defn- markers [store]
  (set (d/q '[:find [?marker ...]
              :where [_ :seon.store.test/marker ?marker]]
            @(:seon.store/connection store))))

;;; ---------------------------------------------------------------------------
;;; Pure derivations
;;; ---------------------------------------------------------------------------

(deftest lock-file-is-one-sibling-derivation
  (let [check
        (tc/quick-check
         100
         (prop/for-all [segment (gen/fmap #(str "s" %) gen/nat)]
           (let [dir (str "tmp/store-test/" segment "/store")
                 lock (store/lock-file dir)]
             (and (string? lock)
                  (not= lock dir)
                  ;; a sibling, never inside the store directory
                  (not (str/starts-with? lock (str dir "/")))
                  (= lock (store/lock-file dir))
                  ;; every spelling of one physical directory yields
                  ;; the ONE lock file — two spellings, one fence
                  (= lock (store/lock-file (str "./" dir))))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "lock-file derivation failed: " (pr-str check)))))

(deftest configuration-is-the-one-shape
  (let [configuration (store/datahike-configuration "tmp/x/store")]
    (is (= :file (get-in configuration [:store :backend])))
    (is (= (.getCanonicalPath (io/file "tmp/x/store"))
           (get-in configuration [:store :path]))
        "the path is canonical: every spelling is the ONE store")
    (is (= configuration
           (store/datahike-configuration "./tmp/x/store")))
    (is (= :write (:schema-flexibility configuration)))))

;;; ---------------------------------------------------------------------------
;;; Lifecycle — live against the :file backend
;;; ---------------------------------------------------------------------------

(deftest open-write-release-reopen-preserves-data
  (let [dir (fresh-dir)]
    (try
      (let [opened (store/open-store! dir)]
        (is (seon.schema/valid-candidate-value? :seon.store/store opened))
        (is (true? (:seon.store/created? opened)))
        (d/transact (:seon.store/connection opened) probe-schema)
        (d/transact (:seon.store/connection opened)
                    [{:seon.store.test/marker "survives"}])
        (is (nil? (store/release-store! opened)))
        (is (nil? (store/release-store! opened)) "release is idempotent")
        (let [reopened (store/open-store! dir)]
          (try
            (is (false? (:seon.store/created? reopened))
                "a complete store is opened, never recreated")
            (is (= #{"survives"} (markers reopened)))
            (finally
              (store/release-store! reopened)))))
      (finally
        (delete-recursively! (str (io/file dir) "/.."))))))

(deftest one-holder-per-store-in-one-process
  (let [dir (fresh-dir)]
    (try
      (let [held (store/open-store! dir)]
        (try
          (testing "a second open of a held store refuses immediately"
            (is (thrown? Exception (store/open-store! dir))))
          (finally
            (store/release-store! held)))
        (testing "after release the store opens again"
          (let [reopened (store/open-store! dir)]
            (is (false? (:seon.store/created? reopened)))
            (store/release-store! reopened))))
      (finally
        (delete-recursively! (str (io/file dir) "/.."))))))

(deftest a-jvm-hosts-independent-stores
  (let [dir-a (fresh-dir)
        dir-b (fresh-dir)]
    (try
      (let [a (store/open-store! dir-a)
            b (store/open-store! dir-b)]
        (try
          (d/transact (:seon.store/connection a) probe-schema)
          (d/transact (:seon.store/connection b) probe-schema)
          (d/transact (:seon.store/connection a)
                      [{:seon.store.test/marker "only-a"}])
          (is (= #{"only-a"} (markers a)))
          (is (= #{} (markers b)) "stores share nothing")
          (finally
            (store/release-store! a)
            (store/release-store! b))))
      (finally
        (delete-recursively! (str (io/file dir-a) "/.."))
        (delete-recursively! (str (io/file dir-b) "/.."))))))

;;; ---------------------------------------------------------------------------
;;; The first-create kill window — :db present, :branches missing
;;; ---------------------------------------------------------------------------

(deftest genesis-window-repairs-by-recreate
  (let [dir (fresh-dir)]
    (try
      ;; a complete store with one durable marker...
      (let [victim (store/open-store! dir)]
        (d/transact (:seon.store/connection victim) probe-schema)
        (d/transact (:seon.store/connection victim)
                    [{:seon.store.test/marker "pre-window"}])
        (store/release-store! victim))
      ;; ...manufactured into the mid-genesis state Datahike can leave
      ;; behind on a first-create kill: :db present, :branches missing
      (let [konserve (filestore/connect-fs-store dir :opts {:sync? true})]
        (k/dissoc konserve :branches {:sync? true})
        (is (some? (k/get konserve :db nil {:sync? true}))
            "the window is real: :db survives without :branches"))
      (let [repaired (store/open-store! dir)]
        (try
          (is (true? (:seon.store/created? repaired))
              "mid-genesis means nothing durable existed — recreate")
          (d/transact (:seon.store/connection repaired) probe-schema)
          (is (= #{} (markers repaired))
              "the recreated store is empty")
          (finally
            (store/release-store! repaired))))
      (finally
        (delete-recursively! (str (io/file dir) "/.."))))))

;;; ---------------------------------------------------------------------------
;;; The flock across processes — a real child JVM holds the store
;;; ---------------------------------------------------------------------------

(deftest the-flock-fences-across-processes
  (let [dir (fresh-dir)
        ready-file (io/file (str (io/file dir) "/../held.ready"))
        java-command (.getPath
                      (File. (System/getProperty "java.home") "bin/java"))
        process (-> (ProcessBuilder.
                     ^java.util.List
                     [java-command
                      "-cp" (System/getProperty "java.class.path")
                      "clojure.main"
                      "-m" "seon.cluster.store-child"
                      dir
                      (.getPath ready-file)])
                    (.redirectErrorStream true)
                    (.start))]
    (try
      ;; a cold JVM loads Clojure + Datahike before it can hold; the
      ;; ready file is authoritative, the clock is only the
      ;; foreign-process backstop
      (let [limit (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 30))]
        (loop []
          (cond
            (.exists ready-file) true
            (not (.isAlive process))
            (throw (ex-info "the child JVM exited before holding"
                            {::exit (.exitValue process)
                             ::output (slurp (.getInputStream process))}))
            (< (System/nanoTime) limit) (do (Thread/sleep 10) (recur))
            :else (throw (ex-info "the child never reported holding" {})))))
      (testing "a live foreign holder refuses this process's open"
        (is (thrown? Exception (store/open-store! dir))))
      (testing "the OS releases a killed holder's flock"
        (.destroyForcibly process)
        (is (.waitFor process 20 TimeUnit/SECONDS))
        (let [survivor (store/open-store! dir)]
          (try
            (is (false? (:seon.store/created? survivor))
                "the child's completed store opens cleanly after SIGKILL")
            (finally
              (store/release-store! survivor)))))
      (finally
        (.destroyForcibly process)
        (delete-recursively! (str (io/file dir) "/.."))))))

(deftest an-in-process-refusal-never-drops-the-os-fence
  ;; fcntl drops EVERY lock a process holds on a file when ANY of its
  ;; descriptors closes — so the same-process refusal path must never
  ;; open a second descriptor it then closes. The interaction proof:
  ;; hold here, refuse here, and a foreign JVM must STILL be refused.
  (let [dir (fresh-dir)
        ready-file (io/file (str (io/file dir) "/../still-held.ready"))
        java-command (.getPath
                      (File. (System/getProperty "java.home") "bin/java"))]
    (try
      (let [held (store/open-store! dir)]
        (try
          (is (thrown? Exception (store/open-store! dir))
              "the in-process second open refuses")
          (let [process (-> (ProcessBuilder.
                             ^java.util.List
                             [java-command
                              "-cp" (System/getProperty "java.class.path")
                              "clojure.main"
                              "-m" "seon.cluster.store-child"
                              dir
                              (.getPath ready-file)])
                            (.redirectErrorStream true)
                            (.start))]
            (is (.waitFor process 30 TimeUnit/SECONDS)
                "the child settles: refused opens exit, they never hang")
            (is (not (.exists ready-file))
                "the foreign JVM never acquired — the fence survived the
                 in-process refusal")
            (is (not (zero? (.exitValue process)))
                "the child exited by refusal, not by success"))
          (finally
            (store/release-store! held))))
      (finally
        (delete-recursively! (str (io/file dir) "/.."))))))
