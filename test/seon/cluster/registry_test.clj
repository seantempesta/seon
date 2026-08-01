(ns seon.cluster.registry-test
  "Sealed acceptance for the branch-lifecycle registry (B2).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The implementation
  lane makes these green by implementing the seon.cluster.registry
  stubs ONLY — schemas and tests are byte-sealed; friction is reported,
  never resolved by weakening.

  Every test is LIVE against the `:file` backend under project-local
  tmp/, one physical store per trial, released and deleted in
  `finally`. There are no fixtures for the things that matter: the
  ancestor is a real branch with real rows, the clusters are real
  branches with real writes, and the collection proof reads real
  on-disk bytes. `a-concurrent-create-wave-loses-nothing` is the
  standing regression for the roster race the fork fixed (submodule
  `357ffc87`; before it, twelve concurrent creates reported eleven
  successes and landed nine — b2-plan §0.3)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.schema]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch]))

;;; ---------------------------------------------------------------------------
;;; Live store scaffolding
;;; ---------------------------------------------------------------------------

(def ^:private probe-schema
  [{:db/ident :seon.registry.test/marker
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :seon.cluster.eval/result-blob
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private source-branch :current-src)

(defn- delete-recursively! [path]
  (test-support/delete-recursively! path))

(defn- markers
  "Every marker visible through one connection."
  [connection]
  (set (d/q '[:find [?marker ...]
              :where [_ :seon.registry.test/marker ?marker]]
            @connection)))

(defn- write-marker! [connection marker]
  (d/transact connection {:tx-data [{:seon.registry.test/marker marker}]}))

(defn- refusal
  "Run `thunk`, returning its refusal ex-data — or ::committed."
  [thunk]
  (try
    (thunk)
    ::committed
    (catch Exception failure
      (ex-data failure))))

(defn- with-source-store
  "Call `body` with an open store carrying one published source branch.
  The source branch holds the schema plus the marker \"ancestral\", so every
  descendant inherits real rows and a sibling's invisibility is a fact
  about data, not about an empty branch."
  [body]
  (let [dir (str "tmp/registry-test/" (random-uuid) "/store")]
    (.mkdirs (.getParentFile (io/file dir)))
    (let [opened (store/open-store! {:seon.store/dir dir})]
      (try
        (d/transact (:seon.store/connection opened) probe-schema)
        (write-marker! (:seon.store/connection opened) "ancestral")
        (registry/branch! {:seon.store/store opened
                           :seon.cluster.registry/from :db
                           :seon.store/branch source-branch})
        (body opened)
        (finally
          (store/release-store! opened)
          (delete-recursively! (str (io/file dir) "/..")))))))

(defn- cluster-request [store cluster-name]
  {:seon.store/store store
   :seon.boot/cluster-name cluster-name
   :seon.source/commit-id
   (registry/branch-commit-id {:seon.store/store store
                               :seon.store/branch source-branch})})

(deftest result-blob-lifetime-follows-live-branch-datoms
  (with-source-store
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "blob-owner"))
      (let [branch (registry/cluster-branch "blob-owner")
            connection (store/open-branch! opened branch)
            content "the complete settled result"
            digest (blob/put! connection content)]
        (try
          (d/transact connection
                      [{:seon.registry.test/marker "blob receipt"
                        :seon.cluster.eval/result-blob digest}])
          (finally
            (d/release connection)))
        (registry/collect! opened)
        (is (= content (blob/get (:seon.store/connection opened) digest))
            "a digest referenced from a live branch extends the GC mark")
        (registry/retire-branch! {:seon.store/store opened
                                  :seon.store/branch branch})
        (registry/collect! opened)
        (is (nil? (blob/get (:seon.store/connection opened) digest))
            "retiring the last referencing branch makes the blob collectible")))))

;;; ---------------------------------------------------------------------------
;;; Pure derivation
;;; ---------------------------------------------------------------------------

(deftest cluster-branch-is-one-injective-derivation
  (let [check
        (tc/quick-check
         100
         (prop/for-all [left (gen/fmap #(str "c" %) gen/nat)
                        right (gen/fmap #(str "c" %) gen/nat)]
           (let [branch-left (registry/cluster-branch left)
                 branch-right (registry/cluster-branch right)]
             (and (keyword? branch-left)
                  (= branch-left (registry/cluster-branch left))
                  ;; distinct names never collide on one branch, and a
                  ;; cluster branch is never the main branch
                  (not= :db branch-left)
                  (= (= left right) (= branch-left branch-right)))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "cluster-branch derivation failed: " (pr-str check)))))

;;; ---------------------------------------------------------------------------
;;; Creation
;;; ---------------------------------------------------------------------------

(deftest ensure-cluster-branches-from-the-source-commit-and-is-idempotent
  (with-source-store
    (fn [opened]
      (let [created (registry/ensure-cluster! (cluster-request opened "alice"))
            branch (registry/cluster-branch "alice")]
        (is (= branch (:seon.store/branch created)))
        (is (true? (:seon.cluster/created? created)))
        (is (contains? (registry/roster opened) branch)
            "the roster is the fact")
        (testing "the cluster inherits every source row"
          (let [connection (store/open-branch! opened branch)]
            (try
              (is (= #{"ancestral"} (markers connection)))
              (finally
                (d/release connection)))))
        (testing "a second ensure creates nothing and changes no roster"
          (let [before (registry/roster opened)
                again (registry/ensure-cluster!
                       (cluster-request opened "alice"))]
            (is (false? (:seon.cluster/created? again)))
            (is (= branch (:seon.store/branch again)))
            (is (= before (registry/roster opened)))))))))

(deftest two-clusters-write-independently
  (with-source-store
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "alice"))
      (registry/ensure-cluster! (cluster-request opened "bob"))
      (let [alice (store/open-branch! opened (registry/cluster-branch "alice"))
            bob (store/open-branch! opened (registry/cluster-branch "bob"))]
        (try
          (write-marker! alice "only-alice")
          (write-marker! bob "only-bob")
          (is (= #{"ancestral" "only-alice"} (markers alice)))
          (is (= #{"ancestral" "only-bob"} (markers bob))
              "siblings share the ancestor and nothing else")
          (finally
            (d/release alice)
            (d/release bob))))
      (testing "the source branch itself is never written to"
        (let [connection (store/open-branch! opened source-branch)]
          (try
            (is (= #{"ancestral"} (markers connection)))
            (finally
              (d/release connection))))))))

(deftest a-concurrent-create-wave-loses-nothing
  ;; The L6 scar at branch granularity: `branch!` used to read-modify-write
  ;; `:branches` unsynchronized, so a caller told :ok could find its cluster
  ;; gone (b2-plan §0.3). The fork serializes roster mutation per store
  ;; (submodule 357ffc87); this is that fix's standing regression.
  (with-source-store
    (fn [opened]
      (let [names (mapv #(str "wave-" %) (range 8))
            latch (CountDownLatch. 1)
            wave (mapv (fn [cluster-name]
                         (future
                           (.await latch)
                           (registry/ensure-cluster!
                            (cluster-request opened cluster-name))))
                       names)
            _ (.countDown latch)
            results (mapv deref wave)
            final (registry/roster opened)]
        (is (every? :seon.cluster/created? results)
            "every caller was told it created its cluster")
        (is (every? #(contains? final (registry/cluster-branch %)) names)
            "and every cluster is in the roster — none silently lost")
        (testing "each branch is genuinely usable, not an orphan head"
          (doseq [cluster-name names]
            (let [connection (store/open-branch!
                              opened (registry/cluster-branch cluster-name))]
              (try
                (is (= #{"ancestral"} (markers connection)))
                (finally
                  (d/release connection))))))))))

;;; ---------------------------------------------------------------------------
;;; Reset
;;; ---------------------------------------------------------------------------

(deftest reset-returns-a-cluster-to-source-state
  (with-source-store
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "alice"))
      (let [branch (registry/cluster-branch "alice")
            connection (store/open-branch! opened branch)]
        (write-marker! connection "drift")
        (is (= #{"ancestral" "drift"} (markers connection)))
        (testing "a live connection refuses the reset, by name and early"
          (is (= :seon.cluster.registry/cluster-connected
                 (:seon.cluster.registry/rule
                  (refusal #(registry/reset-cluster!
                             (cluster-request opened "alice")))))))
        (d/release connection))
      (let [reset (registry/reset-cluster! (cluster-request opened "alice"))]
        (is (true? (:seon.cluster/created? reset))
            "the branch after a reset is a new branch")
        (let [connection (store/open-branch!
                          opened (registry/cluster-branch "alice"))]
          (try
            (is (= #{"ancestral"} (markers connection))
                "reset to the source commit: the drift is gone")
            (finally
              (d/release connection))))))))

(deftest an-unwritten-cluster-retires-beside-its-unwritten-sibling
  ;; Branch-off copies the source's head commit id verbatim, so two
  ;; never-written clusters and their source all name ONE commit. A
  ;; descent test that is not STRICT reads that as mutual descent and
  ;; refuses to retire — which would break reset for every cluster that
  ;; has not written yet. This is that rule's falsifier.
  (with-source-store
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "quiet-a"))
      (registry/ensure-cluster! (cluster-request opened "quiet-b"))
      (is (nil? (registry/retire-branch!
                 {:seon.store/store opened
                  :seon.store/branch (registry/cluster-branch "quiet-a")})))
      (is (contains? (registry/roster opened)
                     (registry/cluster-branch "quiet-b"))
          "the sibling is untouched")
      (is (true? (:seon.cluster/created?
                  (registry/reset-cluster!
                   (cluster-request opened "quiet-b"))))
          "and an unwritten cluster still resets"))))

;;; ---------------------------------------------------------------------------
;;; Retirement and collection — b2-plan §0.7 as a sealed test
;;; ---------------------------------------------------------------------------

(defn- store-bytes [dir]
  (reduce + 0 (map #(.length ^java.io.File %)
                   (filter #(.isFile ^java.io.File %)
                           (file-seq (io/file dir))))))

(deftest retiring-one-cluster-reclaims-only-its-own-tail
  (with-source-store
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "keep"))
      (registry/ensure-cluster! (cluster-request opened "doomed"))
      ;; each cluster writes an UNSHARED tail big enough for collection to
      ;; be visible in bytes, never in a counter we control
      (doseq [[cluster-name prefix] [["keep" "keep-"] ["doomed" "doomed-"]]]
        (let [connection (store/open-branch!
                          opened (registry/cluster-branch cluster-name))]
          (try
            (doseq [batch (partition-all 250 (range 1000))]
              (d/transact connection
                          {:tx-data (mapv (fn [n]
                                            {:seon.registry.test/marker
                                             (str prefix n " "
                                                  (apply str (repeat 200 \x)))})
                                          batch)}))
            (finally
              (d/release connection)))))
      (let [dir (:seon.store/dir opened)
            grown (store-bytes dir)
            doomed (registry/cluster-branch "doomed")]
        (is (nil? (registry/retire-branch! {:seon.store/store opened
                                            :seon.store/branch doomed})))
        (is (not (contains? (registry/roster opened) doomed))
            "the roster is the fact — the branch is gone before any sweep")
        (let [swept (registry/collect! opened)]
          (is (pos? swept) "the doomed tail was reclaimed")
          (is (< (store-bytes dir) grown) "and the bytes actually shrank"))
        (testing "the survivor and the source branch are whole"
          (let [connection (store/open-branch!
                            opened (registry/cluster-branch "keep"))]
            (try
              (is (contains? (markers connection) "ancestral"))
              (is (= 1000 (count (filter #(str/starts-with? % "keep-")
                                         (markers connection)))))
              (finally
                (d/release connection))))
          (let [connection (store/open-branch! opened source-branch)]
            (try
              (is (= #{"ancestral"} (markers connection)))
              (finally
                (d/release connection)))))
        (testing "collection is idempotent"
          (is (zero? (registry/collect! opened))))
        (testing "retiring an absent branch is already done, never an error"
          (is (nil? (registry/retire-branch! {:seon.store/store opened
                                              :seon.store/branch doomed}))))))))

;;; ---------------------------------------------------------------------------
;;; Refusals
;;; ---------------------------------------------------------------------------

(deftest refusals-name-their-rule
  (with-source-store
    (fn [opened]
      (testing "a cluster refuses an unavailable source commit"
        (is (= :seon.cluster.registry/source-absent
               (:seon.cluster.registry/rule
                (refusal #(registry/ensure-cluster!
                           {:seon.store/store opened
                            :seon.boot/cluster-name "orphan"
                            :seon.source/commit-id (random-uuid)}))))))
      (testing "the main branch is the store; it is never retired"
        (is (= :seon.cluster.registry/cannot-retire-main
               (:seon.cluster.registry/rule
                (refusal #(registry/retire-branch!
                           {:seon.store/store opened
                            :seon.store/branch :db}))))))
      (testing "a connected branch refuses retirement"
        (registry/ensure-cluster! (cluster-request opened "alice"))
        (let [connection (store/open-branch!
                          opened (registry/cluster-branch "alice"))]
          (try
            ;; the write also puts alice's head STRICTLY ahead of the
            ;; ancestor's, which the next case depends on
            (write-marker! connection "alice-wrote")
            (is (= :seon.cluster.registry/cluster-connected
                   (:seon.cluster.registry/rule
                    (refusal #(registry/retire-branch!
                               {:seon.store/store opened
                                :seon.store/branch
                                (registry/cluster-branch "alice")})))))
            (finally
              (d/release connection)))))
      (testing "retiring a source name preserves its descendant history through GC"
        (let [source-commit
              (registry/branch-commit-id {:seon.store/store opened
                                          :seon.store/branch source-branch})]
          (is (nil? (registry/retire-branch! {:seon.store/store opened
                                              :seon.store/branch source-branch})))
          (is (not (contains? (registry/roster opened) source-branch)))
          (is (pos? (registry/collect! opened)))
          (let [connection (store/open-branch!
                            opened (registry/cluster-branch "alice"))]
            (try
              (is (= #{"ancestral" "alice-wrote"} (markers connection)))
              (finally
                (d/release connection))))
          (is (true? (:seon.cluster/created?
                      (registry/branch! {:seon.store/store opened
                                         :seon.cluster.registry/from source-commit
                                         :seon.store/branch :retained-history}))))
          (let [connection (store/open-branch! opened :retained-history)]
            (try
              (is (= #{"ancestral"} (markers connection))
                  "the ancestor commit itself remains branchable after GC")
              (finally
                (d/release connection))))))
      (testing "a source that names nothing refuses"
        (is (= :seon.cluster.registry/source-absent
               (:seon.cluster.registry/rule
                (refusal #(registry/branch!
                           {:seon.store/store opened
                            :seon.cluster.registry/from :nowhere
                            :seon.store/branch :cluster-nowhere})))))))))
