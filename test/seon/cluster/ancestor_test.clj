(ns seon.cluster.ancestor-test
  "Sealed acceptance for the shared bootstrap ancestor (B2).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The implementation
  lane makes these green by implementing the seon.cluster.ancestor
  stubs ONLY — schemas and tests are byte-sealed; friction is reported,
  never resolved by weakening.

  Live against the `:file` backend under project-local tmp/, one
  physical store and one digest fixture tree per trial. The population
  is INJECTED as a qualified symbol naming a var in this namespace, so
  the fork mechanics are proven now and N5's indexer drops in later
  without touching a line of this suite. The dead-owner reclaim case
  uses a REAL exited process's pid, never a hand-picked number."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.cluster.ancestor :as ancestor]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.schema]))

;;; ---------------------------------------------------------------------------
;;; The injected population — a real var, named by symbol
;;; ---------------------------------------------------------------------------

(def ^:private probe-schema
  [{:db/ident :seon.ancestor.test/marker
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}])

(defn populate-probe!
  "Transact this suite's stand-in for the program-graph population."
  [{:keys [:seon.store/branch-connection]}]
  (d/transact branch-connection {:tx-data probe-schema})
  (d/transact branch-connection
              {:tx-data [{:seon.ancestor.test/marker "indexed"}]})
  nil)

(defn populate-fails!
  "A population that refuses — the ancestor name must never appear."
  [_population]
  (throw (ex-info "the indexer failed" {::injected true})))

;;; ---------------------------------------------------------------------------
;;; Scaffolding
;;; ---------------------------------------------------------------------------

(defn- delete-recursively! [path]
  (let [file (.getCanonicalFile (io/file path))]
    (when (.exists file)
      (doseq [child (reverse (file-seq file))]
        (.delete ^java.io.File child)))))

(defn- refusal
  [thunk]
  (try
    (thunk)
    ::committed
    (catch Exception failure
      (ex-data failure))))

(defn- with-store
  [body]
  (let [dir (str "tmp/ancestor-test/" (random-uuid) "/store")]
    (.mkdirs (.getParentFile (io/file dir)))
    (let [opened (store/open-store! {:seon.store/dir dir})]
      (try
        (body opened)
        (finally
          (store/release-store! opened)
          (delete-recursively! (str (io/file dir) "/..")))))))

(def ^:private fixture-digest
  "A digest-shaped value for the store cases, so `ensure!` is exercised
  without also depending on the file walk."
  (apply str (repeat 64 "a")))

(defn- ensure-request [store digest populate]
  {:seon.store/store store
   :seon.ancestor/digest digest
   :seon.ancestor/populate populate})

(defn- markers [connection]
  (set (d/q '[:find [?marker ...]
              :where [_ :seon.ancestor.test/marker ?marker]]
            @connection)))

(defn- write-files!
  "Write `contents` as ordered .clj files under a fresh root; return it."
  [contents]
  (let [root (str "tmp/ancestor-test/roots/" (random-uuid))]
    (.mkdirs (io/file root))
    (doseq [[index content] (map-indexed vector contents)]
      (spit (io/file root (str "file" index ".clj")) content))
    root))

;;; ---------------------------------------------------------------------------
;;; Identity
;;; ---------------------------------------------------------------------------

(deftest the-digest-is-a-pure-function-of-the-declared-roots
  (let [check
        (tc/quick-check
         25
         (prop/for-all [contents (gen/vector (gen/fmap #(str "(def x " % ")")
                                                       gen/nat)
                                             1 6)]
           (let [root (write-files! contents)]
             (try
               (let [digest (ancestor/digest {:seon.ancestor/roots [root]})]
                 (and
                  ;; deterministic and spelling-free
                  (= digest (ancestor/digest {:seon.ancestor/roots [root]}))
                  (= digest (ancestor/digest {:seon.ancestor/roots
                                              [(str "./" root)]}))
                  ;; a file the digest does not claim to cover is invisible
                  (do (spit (io/file root "notes.txt") "irrelevant")
                      (= digest
                         (ancestor/digest {:seon.ancestor/roots [root]})))
                  ;; one changed byte is a different ancestor
                  (do (spit (io/file root "file0.clj")
                            (str (first contents) " ;; edited"))
                      (not= digest
                            (ancestor/digest {:seon.ancestor/roots [root]})))))
               (finally
                 (delete-recursively! root)))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "digest property failed: " (pr-str check)))))

(deftest the-digest-refuses-a-root-that-is-not-there
  (is (= :seon.cluster.ancestor/root-absent
         (:seon.cluster.ancestor/rule
          (refusal #(ancestor/digest
                     {:seon.ancestor/roots
                      [(str "tmp/ancestor-test/absent-" (random-uuid))]}))))))

(deftest the-branch-name-carries-the-digest
  (let [branch (ancestor/ancestor-branch fixture-digest)]
    (is (keyword? branch))
    (is (= branch (ancestor/ancestor-branch fixture-digest)))
    (is (not= branch (ancestor/ancestor-branch
                      (apply str (repeat 64 "b")))))
    (is (str/includes? (name branch) fixture-digest)
        "the roster alone answers which ancestors exist")))

;;; ---------------------------------------------------------------------------
;;; The build
;;; ---------------------------------------------------------------------------

(deftest ensure-builds-once-and-then-does-nothing
  (with-store
    (fn [opened]
      (let [built (ancestor/ensure!
                   (ensure-request opened fixture-digest
                                   'seon.cluster.ancestor-test/populate-probe!))
            branch (ancestor/ancestor-branch fixture-digest)]
        (is (= branch (:seon.ancestor/branch built)))
        (is (true? (:seon.ancestor/built? built)))
        (is (contains? (registry/roster opened) branch))
        (testing "no scratch branch survives a completed build"
          (is (empty? (filter #(str/starts-with? (name %) "building-")
                              (registry/roster opened)))))
        (let [connection (store/open-branch! opened branch)
              basis (:max-tx @connection)]
          (try
            (testing "the injected population landed"
              (is (= #{"indexed"} (markers connection))))
            (testing "and the ancestor's own facts landed beside it"
              (is (= #{fixture-digest}
                     (set (d/q '[:find [?digest ...]
                                 :where [_ :seon.ancestor/digest ?digest]]
                               @connection))))
              (is (seq (d/q '[:find [?at ...]
                              :where [_ :seon.ancestor/built-at ?at]]
                            @connection))))
            (testing "a second ensure is ZERO work — the roster is the cache"
              (let [again (ancestor/ensure!
                           (ensure-request
                            opened fixture-digest
                            'seon.cluster.ancestor-test/populate-fails!))]
                (is (false? (:seon.ancestor/built? again)))
                (is (= branch (:seon.ancestor/branch again))
                    "and it never called the population — that one throws")
                (is (= basis (:max-tx @connection))
                    "not one transaction was issued")))
            (finally
              (d/release connection))))))))

(deftest a-failed-population-never-publishes-the-ancestor-name
  (with-store
    (fn [opened]
      (is (map? (refusal
                 #(ancestor/ensure!
                   (ensure-request opened fixture-digest
                                   'seon.cluster.ancestor-test/populate-fails!))))
          "the failure propagates loudly")
      (is (not (contains? (registry/roster opened)
                          (ancestor/ancestor-branch fixture-digest)))
          "the ancestor name only ever appears complete")
      (testing "and the next ensure rebuilds over the wreckage"
        (let [built (ancestor/ensure!
                     (ensure-request
                      opened fixture-digest
                      'seon.cluster.ancestor-test/populate-probe!))]
          (is (true? (:seon.ancestor/built? built)))
          (let [connection (store/open-branch!
                            opened (:seon.ancestor/branch built))]
            (try
              (is (= #{"indexed"} (markers connection)))
              (finally
                (d/release connection)))))))))

(deftest an-unresolvable-population-refuses-before-any-branch
  (with-store
    (fn [opened]
      (is (= :seon.cluster.ancestor/populate-unresolvable
             (:seon.cluster.ancestor/rule
              (refusal #(ancestor/ensure!
                         (ensure-request
                          opened fixture-digest
                          'seon.cluster.ancestor-test/no-such-population!))))))
      (is (= #{:db} (registry/roster opened))
          "nothing was created"))))

;;; ---------------------------------------------------------------------------
;;; The scratch branch — a build's only durable trace
;;; ---------------------------------------------------------------------------

(defn- scratch-branch
  "A `:building-<pid>-<start-millis>-<uuid>` name, as `ensure!` writes it."
  [pid start-millis]
  (keyword (str "building-" pid "-" start-millis "-" (random-uuid))))

(defn- dead-pid
  "The pid of a process that has provably exited."
  []
  (let [process (.start (ProcessBuilder. ^java.util.List ["/bin/sh" "-c" "exit 0"]))]
    (.waitFor process)
    (.pid process)))

(deftest a-dead-builders-scratch-is-reclaimed-not-obeyed
  (with-store
    (fn [opened]
      ;; a build that was killed after its scratch branch reached the roster
      (let [abandoned (scratch-branch (dead-pid) 0)]
        (registry/branch! {:seon.store/store opened
                           :seon.cluster.registry/from :db
                           :seon.store/branch abandoned})
        (let [built (ancestor/ensure!
                     (ensure-request
                      opened fixture-digest
                      'seon.cluster.ancestor-test/populate-probe!))]
          (is (true? (:seon.ancestor/built? built))
              "a dead owner blocks nothing")
          (is (not (contains? (registry/roster opened) abandoned))
              "and its scratch was retired, not left to accumulate")
          (is (contains? (registry/roster opened)
                         (ancestor/ancestor-branch fixture-digest))))))))

(deftest a-live-builders-scratch-refuses-the-second-build
  (with-store
    (fn [opened]
      (let [{:seon.boot/keys [pid start-instant]}
            (cluster.process/current-identity)
            live (scratch-branch pid (inst-ms start-instant))]
        (registry/branch! {:seon.store/store opened
                           :seon.cluster.registry/from :db
                           :seon.store/branch live})
        (let [data (refusal
                    #(ancestor/ensure!
                      (ensure-request
                       opened fixture-digest
                       'seon.cluster.ancestor-test/populate-probe!)))]
          (is (= :seon.cluster.ancestor/build-in-progress
                 (:seon.cluster.ancestor/rule data)))
          (is (= live (:seon.cluster.ancestor/branch data))
              "the refusal names the scratch branch that is holding it"))
        (is (not (contains? (registry/roster opened)
                            (ancestor/ancestor-branch fixture-digest)))
            "two builds of one digest never race")))))
