(ns ^{:seon.test/platform
       "Moving part: the file store and its process-root flock."}
    seon.cluster.store-test
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
            [seon.db :as db]
            [seon.schema]
            [seon.test-support :as test-support])
  (:import [java.io File]
           [java.util.concurrent CompletableFuture TimeUnit]))

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
  (set (db/q '[:find [?marker ...]
              :where [_ :seon.store.test/marker ?marker]]
            @(:seon.store/connection-object store))))

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
  (let [configuration (store/datahike-configuration "tmp/x/store")
        non-temporal (store/datahike-configuration "tmp/x/store" false)]
    (is (= :file (get-in configuration [:store :backend])))
    (is (= (.getCanonicalPath (io/file "tmp/x/store"))
           (get-in configuration [:store :path]))
        "the path is canonical: every spelling is the ONE store")
    (is (= configuration
           (store/datahike-configuration "./tmp/x/store")))
    (is (true? (:fuse-index-roots? configuration)))
    (is (= (:index-config configuration) (:index-config non-temporal))
        "index tuning is one creation decision, independent of history policy")
    (is (= {:backend :self} (:writer configuration)))
    (is (= :write (:schema-flexibility configuration)))
    (is (true? (:keep-history? configuration))
        "ordinary stores retain history by default")
    (is (false? (:keep-history? non-temporal))
        "the creation seam accepts an explicit non-temporal policy")))

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
                      (:store @(:seon.store/connection-object opened))))
              "the application pin exposes the filestore batch Datahike builds")
          (finally
            (store/release-store! opened))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest open-write-release-reopen-preserves-data
  (let [dir (fresh-dir)]
    (try
      (let [opened (store/open-store! {:seon.store/dir dir})]
        (is (seon.schema/valid-candidate-value? (seon.schema/handed-projection) :seon.store/store opened))
        (is (true? (:seon.store/created? opened)))
        (test-support/transacted! (:seon.store/connection-object opened) probe-schema)
        (test-support/transacted! (:seon.store/connection-object opened)
                                [{:seon.store.test/marker "survives"}])
        (is (nil? (store/release-store! opened)))
        ;; A SECOND RELEASE IS A NO-OP RETURNING NIL, which is what
        ;; `release-store!` has always documented and what its body does:
        ;; the flock's own validity IS the released? fact, derived inside
        ;; the function. The previous expectation here asserted a contract
        ;; ACCIDENT — a shape member demanding a live connection and a valid
        ;; lock — which contradicted the docstring two lines above it and
        ;; made every RETAINED store value (the one a stopped
        ;; `:seon.boot/instance` still holds) unrepresentable. The store
        ;; value records what this process opened; liveness is decided at
        ;; the release and at Datahike, never pre-read in a shape.
        (is (nil? (store/release-store! opened))
            "releasing a released store is a no-op, as its docstring rules")
        (is (seon.schema/valid-candidate-value? (seon.schema/handed-projection) :seon.store/store opened)
            "a released store value is still a store value")
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
          declared-index-config
          (:index-config (store/datahike-configuration dir))
          legacy-configuration
          (dissoc (store/datahike-configuration dir)
                  :fuse-index-roots? :index-config)]
      (try
        (d/create-database legacy-configuration)
        (let [opened (store/open-store! {:seon.store/dir dir})]
          (try
            (is (false? (:seon.store/created? opened)))
            (is (not (true? (get-in @(:seon.store/connection-object opened)
                                    [:config :fuse-index-roots?]))))
            (is (not= declared-index-config
                      (get-in @(:seon.store/connection-object opened)
                              [:config :index-config])))
            (finally
              (store/release-store! opened))))
        (finally
          (test-support/delete-recursively! (str (io/file dir) "/.."))))))
  (testing "a fresh store persists fused roots and the declared index tuning"
    (let [dir (fresh-dir)
          declared-index-config
          (:index-config (store/datahike-configuration dir))]
      (try
        (let [opened (store/open-store! {:seon.store/dir dir})]
          (try
            (is (true? (:seon.store/created? opened)))
            (is (true? (get-in @(:seon.store/connection-object opened)
                               [:config :fuse-index-roots?])))
            (is (= declared-index-config
                   (get-in @(:seon.store/connection-object opened)
                           [:config :index-config])))
            (finally
              (store/release-store! opened))))
        (finally
          (test-support/delete-recursively! (str (io/file dir) "/..")))))))

(deftest history-policy-is-creation-fixed-and-reopens-from-the-branch-record
  (let [dir (fresh-dir)]
    (try
      (let [opened
            (store/open-store!
             {:seon.store/dir dir
              :seon.config.db/keep-history? false})]
        (is (true? (:seon.store/created? opened)))
        (is (false? (get-in @(:seon.store/connection-object opened)
                            [:config :keep-history?])))
        (let [failure (db/history @(:seon.store/connection-object opened))]
          (is ((seon.schema/projection-validator
                (seon.schema/handed-projection) :seon.config/error) failure))
          (is (= :seon.config.db/keep-history? (:seon.config/error-key failure))))
        (store/release-store! opened))
      (testing "an omitted request adopts the persisted policy"
        (let [reopened (store/open-store! {:seon.store/dir dir})]
          (try
            (is (false? (:seon.store/created? reopened)))
            (is (false? (get-in @(:seon.store/connection-object reopened)
                                [:config :keep-history?])))
            (finally
              (store/release-store! reopened)))))
      (testing "an explicit conflicting request is refused by Datahike"
        (let [refusal
              (test-support/refusal-data
               #(store/open-store!
                 {:seon.store/dir dir
                  :seon.config.db/keep-history? true}))]
          (is (= :create-time-fixed-index-config-mismatch (:type refusal)))
          (is (= {:given true :stored false}
                 (get-in refusal [:conflicts :keep-history?])))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest temporal-stores-answer-all-three-database-views
  (let [dir (fresh-dir)]
    (try
      (let [opened (store/open-store! {:seon.store/dir dir})
            connection (:seon.store/connection-object opened)]
        (try
          (test-support/transacted! connection probe-schema)
          (let [basis (:max-tx @connection)]
            (test-support/transacted! connection
                                    [{:seon.store.test/marker "after-basis"}])
            (is (store/database-value? (db/history @connection)))
            (is (store/database-value? (db/as-of @connection basis)))
            (is (store/database-value? (db/since @connection basis))))
          (finally
            (store/release-store! opened))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest branch-connections-inherit-the-root-history-representation
  (let [dir (fresh-dir)]
    (try
      (let [opened
            (store/open-store!
             {:seon.store/dir dir
              :seon.config.db/keep-history? false})
            main (:seon.store/connection-object opened)
            branch :non-temporal-test]
        (try
          (test-support/transacted! main probe-schema)
          (d/branch! main :db branch)
          (let [connection (store/open-branch! opened branch)]
            (try
              (is (false? (get-in @connection [:config :keep-history?])))
              (test-support/transacted! connection
                                      [{:seon.store.test/marker "current-reads-work"}])
              (is (= #{["current-reads-work"]}
                     (db/q '[:find ?marker
                            :where [_ :seon.store.test/marker ?marker]]
                          @connection)))
              (let [failure (db/history @connection)]
                (is ((seon.schema/projection-validator
                      (seon.schema/handed-projection) :seon.config/error) failure))
                (is (= :seon.config.db/keep-history? (:seon.config/error-key failure))))
              (finally
                (d/release connection))))
          (finally
            (store/release-store! opened))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest transact-normalizes-only-jdk-integers
  (let [dir (fresh-dir)]
    (try
      (let [opened (store/open-store! {:seon.store/dir dir})
            connection (:seon.store/connection-object opened)]
        (try
          (test-support/transacted! connection probe-schema)
          (testing "Integer values commit from entity maps and datom vectors"
            (let [outcome
                  (db/transact!
                   connection
                   [{:seon.store.test/marker "entity-map"
                     :seon.store.test/measurement (Integer/valueOf 7)}
                    [:db/add "datom-vector"
                     :seon.store.test/marker "datom-vector"]
                    [:db/add "datom-vector"
                     :seon.store.test/measurement (Integer/valueOf 8)]])
                  stored
                  (into {}
                        (db/q '[:find ?marker ?measurement
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
                  (db/transact!
                   connection
                   [{:seon.store.test/marker "double"
                     :seon.store.test/measurement (Double/valueOf 9.0)}])]
              (is ((seon.schema/projection-validator
                    (seon.schema/handed-projection) :seon.db.write/validation-refusal)
                   outcome))
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
          (test-support/transacted! (:seon.store/connection-object a) probe-schema)
          (test-support/transacted! (:seon.store/connection-object b) probe-schema)
          (test-support/transacted! (:seon.store/connection-object a)
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
        (test-support/transacted! (:seon.store/connection-object victim) probe-schema)
        (test-support/transacted! (:seon.store/connection-object victim)
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
          (test-support/transacted! (:seon.store/connection-object repaired) probe-schema)
          (is (= #{} (markers repaired))
              "the recreated store is empty")
          (finally
            (store/release-store! repaired))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))))))

(deftest creation-never-deletes-a-complete-store-or-an-undeclared-root
  ;; The class that wiped the development store: delete-then-create reached
  ;; with a root inferred from the working directory
  ;; (docs/seon/issues/a-platform-tier-test-wiped-the-checkouts-store.md).
  ;; The evidence is a scratch checkout this test BUILDS — a `data/store` with
  ;; a real branch roster and a sentinel beside it. The pooled worker's own
  ;; checkout has no `data/store` at all, so asserting on it reads absence as
  ;; health; the scratch tree's bytes before and after are the observable.
  (let [dir (fresh-dir)
        scratch (str "tmp/store-test-" (random-uuid))
        scratch-store (.getCanonicalPath (io/file scratch "data" "store"))
        sentinel (io/file scratch "data" "sentinel.txt")
        checkout (.getCanonicalPath (io/file (System/getProperty "user.dir")))
        checkout-store (.getCanonicalPath (io/file checkout "data" "store"))
        rule (fn [f]
               (try (f) ::admitted
                    (catch Throwable error
                      (:seon.cluster.store/rule (ex-data error)))))
        tree (fn [root]
               (let [prefix (count (.getCanonicalPath (io/file root)))]
                 (into (sorted-map)
                       (for [^File f (file-seq (io/file root))
                             :when (.isFile f)]
                         [(subs (.getCanonicalPath f) prefix)
                          (hash (vec (java.nio.file.Files/readAllBytes
                                      (.toPath f))))]))))]
    (try
      (.mkdirs (io/file scratch "data"))
      (let [built (store/open-store! {:seon.store/dir scratch-store})]
        (test-support/transacted! (:seon.store/connection-object built)
                                  probe-schema)
        (test-support/transacted! (:seon.store/connection-object built)
                                  [{:seon.store.test/marker "scratch-checkout"}])
        (store/release-store! built))
      (spit sentinel "a byte the admission must never touch")
      (let [before (tree scratch)]
        (is (contains? before "/data/sentinel.txt") "the sentinel is written")
        (is (some #(str/includes? % "/store/") (keys before))
            "the scratch checkout holds a real store")
        (testing "an inferred root is refused before any deletion"
          (is (= :seon.cluster.store/undeclared-destructive-root
                 (rule #(store/admit-destructive-path!
                         {:seon.cluster.store/root nil
                          :seon.cluster.store/target scratch-store}))))
          (is (= :seon.cluster.store/relative-destructive-root
                 (rule #(store/admit-destructive-path!
                         {:seon.cluster.store/root "."
                          :seon.cluster.store/target "./data/store"}))))
          (is (= :seon.cluster.store/undeclared-checkout-deletion
                 (rule #(store/admit-destructive-path!
                         {:seon.cluster.store/root checkout
                          :seon.cluster.store/target checkout-store
                          :seon.cluster.store/declared-root
                          (.getCanonicalPath (io/file dir))})))
              "only a JVM launched to operate the checkout may destroy its data")
          (is (= :seon.cluster.store/destructive-path-outside-root
                 (rule #(store/admit-destructive-path!
                         {:seon.cluster.store/root
                          (.getCanonicalPath (io/file dir))
                          :seon.cluster.store/target scratch-store
                          :seon.cluster.store/declared-root checkout})))
              "a real store outside the deletion authority is refused")
          (is (= before (tree scratch))
              "every refusal left the scratch checkout byte-identical")))
      (testing "a complete store is never recreated"
        (let [opened (store/open-store! {:seon.store/dir dir})]
          (test-support/transacted! (:seon.store/connection-object opened)
                                    probe-schema)
          (test-support/transacted! (:seon.store/connection-object opened)
                                    [{:seon.store.test/marker "durable"}])
          (store/release-store! opened))
        (is (= :seon.cluster.store/complete-store-not-recreated
               (rule #((ns-resolve 'seon.cluster.store 'create-store!)
                       (.getCanonicalPath (io/file dir))
                       (store/datahike-configuration dir))))
            "the authority re-decides at the seam, whatever the caller read")
        (let [reopened (store/open-store! {:seon.store/dir dir})]
          (try
            (is (= #{"durable"} (markers reopened))
                "the durable marker survived the refused recreation")
            (finally (store/release-store! reopened)))))
      (finally
        (test-support/delete-recursively! (str (io/file dir) "/.."))
        (test-support/delete-recursively! scratch))))) 

;;; ---------------------------------------------------------------------------
;;; The flock across processes — a real child JVM holds the store
;;; ---------------------------------------------------------------------------

(deftest ^{:seon.test/long
           "Cold child JVM loads the store and its dependencies before proving the operating-system fence."
           :seon.test/long-ms 60000}
  the-flock-fences-across-processes
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
            (catch java.io.IOException error
              (.completeExceptionally readiness error))))
        _ (.thenAccept
           (.onExit process)
           (reify java.util.function.Consumer
             (accept [_ exited]
               (.complete readiness exited))))]
    (try
      ;; a cold JVM loads Clojure + Datahike before it can hold; the
      ;; child's `held` line follows ready-file creation and is authoritative;
      ;; the declared long bound is the foreign-process backstop
      (let [observed
            (.get readiness
                  (:seon.test/long-ms (meta #'the-flock-fences-across-processes))
                  TimeUnit/MILLISECONDS)]
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

(deftest ^{:seon.test/long
           "Cold child JVM loads the store before proving an in-process refusal retains the operating-system fence."
           :seon.test/long-ms 60000}
  an-in-process-refusal-never-drops-the-os-fence
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
              (.get (.onExit process)
                    (:seon.test/long-ms (meta #'an-in-process-refusal-never-drops-the-os-fence))
                    TimeUnit/MILLISECONDS)
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
