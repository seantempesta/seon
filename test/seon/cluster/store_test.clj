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
            [konserve.utils :as konserve.utils]
            [seon.cluster.store :as store]
            [seon.schema]
            [seon.test-support :as test-support])
  (:import [java.io File]
           [java.util.concurrent CompletableFuture]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(defn- fresh-dir []
  (let [dir (str "tmp/store-test/" (random-uuid) "/store")]
    (.mkdirs (.getParentFile (io/file dir)))
    dir))

(def ^:private probe-schema
  [{:db/ident :seon.store.test/marker
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.store.test/measurement
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}])

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
    (is (true? (:fuse-index-roots? configuration)))
    (is (= {:diff-buf-size 256} (:index-config configuration)))
    (is (= {:backend :self} (:writer configuration)))
    (is (= :write (:schema-flexibility configuration)))))

;;; ---------------------------------------------------------------------------
;;; Lifecycle — live against the :file backend
;;; ---------------------------------------------------------------------------

(deftest store-creation-never-follows-a-symlink-out-of-its-directory
  (let [root (str "tmp/store-test/delete-symlink-" (random-uuid))
        dir (io/file root "store")
        outside (io/file root "outside")
        sentinel (io/file outside "must-survive.txt")
        link (io/file dir "linked-elsewhere")]
    (try
      (.mkdirs dir)
      (.mkdirs outside)
      (spit sentinel "do not delete me")
      (java.nio.file.Files/createSymbolicLink
       (.toPath link)
       (.toAbsolutePath (.toPath outside))
       (make-array java.nio.file.attribute.FileAttribute 0))
      (let [opened (store/open-store! {:seon.store/dir (.getPath dir)})]
        (try
          (is (true? (:seon.store/created? opened)))
          (is (.exists sentinel)
              "store recreation deletes the link entry, not its target")
          (is (not (.exists link))
              "the stale link is absent from the newly created store")
          (finally
            (store/release-store! opened))))
      (finally
        (test-support/delete-recursively! root)))))

(deftest file-store-executes-ordered-multi-key-operations
  (let [dir (fresh-dir)]
    (try
      (let [opened (store/open-store! {:seon.store/dir dir})]
        (try
          (is (true? (konserve.utils/multi-key-capable?
                      (:store @(:seon.store/connection opened))))
              "the application pin exposes the filestore batch Datahike builds")
          (finally
            (store/release-store! opened))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest open-write-release-reopen-preserves-data
  (let [dir (fresh-dir)]
    (try
      (let [opened (store/open-store! {:seon.store/dir dir})]
        (is (seon.schema/valid-candidate-value? :seon.store/store opened))
        (is (true? (:seon.store/created? opened)))
        (d/transact (:seon.store/connection opened) probe-schema)
        (d/transact (:seon.store/connection opened)
                    [{:seon.store.test/marker "survives"}])
        (is (nil? (store/release-store! opened)))
        (is (nil? (store/release-store! opened)) "release is idempotent")
        (let [reopened (store/open-store! {:seon.store/dir dir})]
          (try
            (is (false? (:seon.store/created? reopened))
                "a complete store is opened, never recreated")
            (is (= #{"survives"} (markers reopened)))
            (finally
              (store/release-store! reopened)))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest create-settings-apply-only-to-fresh-stores
  (testing "a legacy store reopens by adopting its stored configuration"
    (let [dir (fresh-dir)
          legacy-configuration
          (dissoc (store/datahike-configuration dir)
                  :fuse-index-roots? :index-config)]
      (try
        (d/create-database legacy-configuration)
        (let [opened (store/open-store! {:seon.store/dir dir})]
          (try
            (is (false? (:seon.store/created? opened)))
            (is (not (true? (get-in @(:seon.store/connection opened)
                                    [:config :fuse-index-roots?]))))
            (is (not= 256
                      (get-in @(:seon.store/connection opened)
                              [:config :index-config :diff-buf-size])))
            (finally
              (store/release-store! opened))))
        (finally
          (test-support/delete-recursively! (str (io/file dir) "/.."))))))
  (testing "a fresh store persists fused roots and the diff buffer"
    (let [dir (fresh-dir)]
      (try
        (let [opened (store/open-store! {:seon.store/dir dir})]
          (try
            (is (true? (:seon.store/created? opened)))
            (is (true? (get-in @(:seon.store/connection opened)
                               [:config :fuse-index-roots?])))
            (is (= 256
                   (get-in @(:seon.store/connection opened)
                           [:config :index-config :diff-buf-size])))
            (finally
              (store/release-store! opened))))
        (finally
          (test-support/delete-recursively! (str (io/file dir) "/..")))))))

(deftest transact-normalizes-only-jdk-integers
  (let [dir (fresh-dir)]
    (try
      (let [opened (store/open-store! {:seon.store/dir dir})
            connection (:seon.store/connection opened)]
        (try
          (d/transact connection probe-schema)
          (testing "Integer values commit from entity maps and datom vectors"
            (let [outcome
                  (store/transact!
                   connection
                   [{:seon.store.test/marker "entity-map"
                     :seon.store.test/measurement (Integer/valueOf 7)}
                    [:db/add "datom-vector"
                     :seon.store.test/marker "datom-vector"]
                    [:db/add "datom-vector"
                     :seon.store.test/measurement (Integer/valueOf 8)]])
                  stored
                  (into {}
                        (d/q '[:find ?marker ?measurement
                               :where
                               [?entity :seon.store.test/marker ?marker]
                               [?entity :seon.store.test/measurement
                                ?measurement]]
                             @connection))]
              (is (contains? outcome :db-after))
              (is (= {"entity-map" 7
                      "datom-vector" 8}
                     stored))
              (is (every? #(identical? Long (class %)) (vals stored)))))
          (testing "Double remains invalid for a long attribute"
            (let [outcome
                  (store/transact!
                   connection
                   [{:seon.store.test/marker "double"
                     :seon.store.test/measurement (Double/valueOf 9.0)}])]
              (is (= :seon.db/rejected (:seon.error/kind outcome)))
              (is (not (contains? (markers opened) "double"))
                  "the refused transaction commits nothing")))
          (finally
            (store/release-store! opened))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest one-holder-per-store-in-one-process
  (let [dir (fresh-dir)]
    (try
      (let [held (store/open-store! {:seon.store/dir dir})]
        (try
          (testing "a second open of a held store refuses immediately"
            (is (thrown? Exception (store/open-store! {:seon.store/dir dir}))))
          (finally
            (store/release-store! held)))
        (testing "after release the store opens again"
          (let [reopened (store/open-store! {:seon.store/dir dir})]
            (is (false? (:seon.store/created? reopened)))
            (store/release-store! reopened))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest a-jvm-hosts-independent-stores
  (let [dir-a (fresh-dir)
        dir-b (fresh-dir)]
    (try
      (let [a (store/open-store! {:seon.store/dir dir-a})
            b (store/open-store! {:seon.store/dir dir-b})]
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
        (test-support/delete-recursively! (str (io/file dir-a) "/.."))
        (test-support/delete-recursively! (str (io/file dir-b) "/.."))))))

;;; ---------------------------------------------------------------------------
;;; The first-create kill window — :db present, :branches missing
;;; ---------------------------------------------------------------------------

(deftest genesis-window-repairs-by-recreate
  (let [dir (fresh-dir)]
    (try
      ;; a complete store with one durable marker...
      (let [victim (store/open-store! {:seon.store/dir dir})]
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
      (let [repaired (store/open-store! {:seon.store/dir dir})]
        (try
          (is (true? (:seon.store/created? repaired))
              "mid-genesis means nothing durable existed — recreate")
          (d/transact (:seon.store/connection repaired) probe-schema)
          (is (= #{} (markers repaired))
              "the recreated store is empty")
          (finally
            (store/release-store! repaired))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

;;; ---------------------------------------------------------------------------
;;; The flock across processes — a real child JVM holds the store
;;; ---------------------------------------------------------------------------

(deftest the-flock-fences-across-processes
  (let [dir (fresh-dir)
        ready-directory (.getParentFile (io/file dir))
        ready-file (io/file ready-directory "held.ready")
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
                    (.start))
        readiness (CompletableFuture.)
        child-output (atom [])
        output-reader
        (future
          (try
            (with-open [reader (io/reader (.getInputStream process))]
              (loop []
                (when-let [line (.readLine reader)]
                  (swap! child-output conj line)
                  (if (= "held" line)
                    (.complete readiness ::child-holding)
                    (recur)))))
            (catch java.io.IOException _)))
        _ (.thenAccept
           (.onExit process)
           (reify java.util.function.Consumer
             (accept [_ exited]
               (.complete readiness exited))))]
    (try
      ;; a cold JVM loads Clojure + Datahike before it can hold; the
      ;; child's `held` line follows ready-file creation and is authoritative;
      ;; the clock in await-event! is only the foreign-process backstop
      (let [observed
            (test-support/await-event! readiness ::child-readiness)]
        (when (instance? Process observed)
          (throw (ex-info "the child JVM exited before holding"
                          {::exit (.exitValue process)
                           ::output (str/join "\n" @child-output)}))))
      (testing "a live foreign holder refuses this process's open"
        (is (thrown? Exception (store/open-store! {:seon.store/dir dir}))))
      (testing "the OS releases a killed holder's flock"
        (.destroyForcibly process)
        (test-support/await-event!
         (.onExit process)
         ::child-exit-after-kill)
        (let [survivor (store/open-store! {:seon.store/dir dir})]
          (try
            (is (false? (:seon.store/created? survivor))
                "the child's completed store opens cleanly after SIGKILL")
            (finally
              (store/release-store! survivor)))))
      (finally
        (when (.isAlive process)
          (.destroyForcibly process)
          (.join (.onExit process)))
        (future-cancel output-reader)
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

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
      (let [held (store/open-store! {:seon.store/dir dir})]
        (try
          (is (thrown? Exception (store/open-store! {:seon.store/dir dir}))
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
            (try
              (test-support/await-event!
               (.onExit process)
               ::refused-child-exit)
              (is (not (.exists ready-file))
                  "the foreign JVM never acquired — the fence survived the
                   in-process refusal")
              (is (not (zero? (.exitValue process)))
                  "the child exited by refusal, not by success")
              (finally
                (when (.isAlive process)
                  (.destroyForcibly process)
                  (.join (.onExit process))))))
          (finally
            (store/release-store! held))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest a-failed-release-never-drops-the-fence
  ;; a live connection behind a dropped fence is the two-writers loss;
  ;; when the Datahike release throws, the flock must survive it
  (let [dir (fresh-dir)]
    (try
      (let [held (store/open-store! {:seon.store/dir dir})]
        (with-redefs [d/release (fn [& _]
                                  (throw (ex-info "injected release fault"
                                                  {::injected true})))]
          (is (thrown? Exception (store/release-store! held))
              "the failure propagates loudly"))
        (is (thrown? Exception (store/open-store! {:seon.store/dir dir}))
            "the fence survived the failed release")
        (is (nil? (store/release-store! held))
            "a later successful release still works")
        (let [reopened (store/open-store! {:seon.store/dir dir})]
          (is (false? (:seon.store/created? reopened)))
          (store/release-store! reopened)))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest open-branch-refuses-what-the-roster-refutes
  (let [dir (fresh-dir)]
    (try
      (let [held (store/open-store! {:seon.store/dir dir})]
        (try
          (is (thrown? Exception
                       (store/open-branch! held :cluster-nowhere))
              "a branch absent from the roster refuses")
          (finally
            (store/release-store! held))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))
