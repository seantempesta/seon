(ns ^{:seon.test/platform
       "Moving part: branch fork, branches publication, retirement, and reset."}
    seon.cluster.registry-test
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
  standing regression for the branches race the fork fixed (submodule
  `357ffc87`; before it, twelve concurrent creates reported eleven
  successes and landed nine — b2-plan §0.3)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [konserve.core :as k]
            [seon.db :as db]
            [seon.config :as config]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.merge :as merge]
            [seon.cluster.store :as store]
            [seon.schema]
            [seon.schema.datahike :as schema-datahike]
            [seon.sci.eval :as sci.eval]
            [seon.task :as task]
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
   {:db/ident :seon.registry.test/payload-blob
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :seon.registry.test/archive-blob
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn- probe-schema-entities [projection forms probe-keys]
  ;; Keep the canonical dependency closure of the synthetic declarations and
  ;; of the writer's own dials (the `seon.config.db` family `seon.db/transact!`
  ;; reads its write and validation bounds from): once these entities exist the
  ;; store's declared program is its projection, so without the dials every
  ;; later write refuses. Unrelated render declarations require program
  ;; definitions, not this file-store fixture's schema-only population.
  (let [references (:seon.schema.projection/schema-dependencies projection)]
    (loop [needed (into (set probe-keys)
                        (filter #(= "seon.config.db" (namespace %)))
                        (keys forms))]
      (let [expanded (into needed
                           (mapcat #(get references %))
                           needed)]
        (if (= needed expanded)
          (seon.schema/canonical-schema-entities projection (select-keys forms expanded))
          (recur expanded))))))

(def ^:private source-branch :current-src)

(defn- delete-recursively! [path]
  (test-support/delete-recursively! path))

(defn- markers
  "Every marker visible through one connection."
  [connection]
  (set (db/q '[:find [?marker ...]
              :where [_ :seon.registry.test/marker ?marker]]
            @connection)))

(defn- write-marker! [connection marker]
  (test-support/transacted! connection {:tx-data [{:seon.registry.test/marker marker}]}))

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
  ([body]
   (with-source-store {} body))
  ([store-request body]
   (let [dir (str "tmp/registry-test/" (random-uuid) "/store")]
     (.mkdirs (.getParentFile (io/file dir)))
     (let [opened
           (store/open-store! (assoc store-request :seon.store/dir dir))]
       (try
         (let [probe-forms {:seon.registry.test/payload-blob :seon.blob/digest
                            :seon.registry.test/archive-blob :seon.blob/digest}
               projection (reduce-kv
                           (fn [projection key form]
                             (seon.schema/projection-with-schema
                              projection key form {:seon.schema.admission/source :core}))
                           (seon.schema/handed-projection) probe-forms)
               forms (:seon.schema.projection/forms projection)
               connection (:seon.store/connection-object opened)]
           (test-support/transacted!
            connection
            (into (schema-datahike/malli->datahike-schema-in
                   projection (seon.schema/canonical-database-attributes projection))
                  probe-schema))
           (test-support/transacted!
            connection (probe-schema-entities projection forms (keys probe-forms))))
         (write-marker! (:seon.store/connection-object opened) "ancestral")
         (registry/branch! {:seon.store/store opened
                            :seon.cluster.registry/from :db
                            :seon.store/branch source-branch})
         (body opened)
         (finally
           (store/release-store! opened)
           (delete-recursively! (str (io/file dir) "/.."))))))))

(defn- cluster-request [store cluster-name]
  {:seon.store/store store
   :seon.boot/cluster-name cluster-name
   :seon.source/commit-id
   (registry/branch-commit-id {:seon.store/store store
                               :seon.store/branch source-branch})})

(deftest blob-lifetime-follows-schema-derived-history-reachability
  (with-source-store
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "blob-owner"))
      (let [branch (registry/cluster-branch "blob-owner")
            connection (store/open-branch! opened branch)
            content "the complete settled result"
            digest (blob/put! connection content)]
        (try
          (let [report
                (test-support/transacted! connection
                                          [{:seon.registry.test/marker "historical payload"
                                            :seon.registry.test/archive-blob digest}])
                entity-id
                (:db/id
                 (db/pull (:db-after report) [:db/id]
                         [:seon.registry.test/marker "historical payload"]))]
            (test-support/transacted! connection
                                    [[:db/retract entity-id :seon.registry.test/archive-blob digest]]))
          (finally
            (d/release connection)))
        (registry/collect! opened (java.util.Date. 0))
        (is (= content (blob/get (:seon.store/connection-object opened) digest))
            "a digest reachable only through a declared blob attribute's history extends the GC mark")
        (registry/retire-branch! {:seon.store/store opened
                                  :seon.store/branch branch})
        (registry/collect! opened (java.util.Date. 0))
        (is (nil? (blob/get (:seon.store/connection-object opened) digest))
            "retiring the last referencing branch makes the blob collectible")))))

(deftest non-temporal-collection-marks-current-blob-references
  (with-source-store
    {:seon.config.db/keep-history? false}
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "current-blob-owner"))
      (let [branch (registry/cluster-branch "current-blob-owner")
            connection (store/open-branch! opened branch)
            content "the current non-temporal result"
            digest (blob/put! connection content)]
        (try
          (test-support/transacted! connection
                                  [{:seon.registry.test/marker "current blob"
                                    :seon.registry.test/payload-blob digest}])
          (finally
            (d/release connection)))
        (registry/collect! opened (java.util.Date. 0))
        (is (= content (blob/get (:seon.store/connection-object opened) digest))
            "current references remain live when historical datoms are absent")))))

(deftest only-digest-valued-attributes-are-swept-as-blob-references
  ;; A ref or symbol attribute whose PROPERTIES name a schema containing a
  ;; digest (a component entity, an entity schema) stores entity ids and symbols;
  ;; reading those as digests refused every sweep of a store holding error
  ;; evidence (`referenced-blobs` returned 41112, 2026-09-23).
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           attributes (set (#'registry/attributes-valued-as database :seon.blob/digest))]
       (is (contains? attributes :seon.error/shown-blob))
       (is (every? (set (#'registry/attributes-valued-as database :seon.source/commit-id))
                   [:seon.task/merge-base :seon.task.attempt/commit])
           "the commits a merge and a relaunch read by id are declared commit ids")
       (is (not (contains? attributes :seon.agent/runtime))
           "a component ref naming an entity schema is not a digest")
       (is (not (contains? attributes :seon.test/sym))
           "an entity identity naming its entity schema is not a digest")))))

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
        "cluster-branch derivation failed")))

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
        (is (contains? (registry/branches opened) branch)
            "the store's branches is the fact")
        (testing "the cluster inherits every source entity"
          (let [connection (store/open-branch! opened branch)]
            (try
              (is (= #{"ancestral"} (markers connection)))
              (finally
                (d/release connection)))))
        (testing "a second ensure creates nothing and changes no branches"
          (let [before (registry/branches opened)
                again (registry/ensure-cluster!
                       (cluster-request opened "alice"))]
            (is (false? (:seon.cluster/created? again)))
            (is (= branch (:seon.store/branch again)))
            (is (= before (registry/branches opened)))))))))

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
  ;; gone (b2-plan §0.3). The fork serializes branches mutation per store
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
            final (registry/branches opened)]
        (is (every? :seon.cluster/created? results)
            "every caller was told it created its cluster")
        (is (every? #(contains? final (registry/cluster-branch %)) names)
            "and every cluster is in the store's branches — none silently lost")
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

(deftest failed-forced-refork-keeps-the-prior-branch-reachable
  (with-source-store
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "atomic-refork"))
      (let [branch (registry/cluster-branch "atomic-refork")
            connection (store/open-branch! opened branch)]
        (write-marker! connection "prior")
        (d/release connection)
        (let [before (registry/branch-commit-id
                      {:seon.store/store opened :seon.store/branch branch})
              failure (ex-info "injected refork failure"
                               {})]
          (with-redefs [d/force-branch! (fn [& _] (throw failure))]
            (is (identical?
                 failure
                 (try
                   (registry/reset-cluster!
                    (cluster-request opened "atomic-refork"))
                   nil
                   (catch Throwable thrown thrown)))))
          (is (= before
                 (registry/branch-commit-id
                  {:seon.store/store opened :seon.store/branch branch})))
          (is (contains? (registry/branches opened) branch))
          (let [reopened (store/open-branch! opened branch)]
            (try
              (is (= #{"ancestral" "prior"} (markers reopened)))
              (finally
                (d/release reopened)))))))))

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
      (is (contains? (registry/branches opened)
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

(deftest dry-run-enumerates-candidates-without-deleting
  (with-source-store
    (fn [opened]
      (let [konserve (:store @(:seon.store/connection-object opened))
            cutoff (java.util.Date. 0)
            opts {:seon.operator.collect/dry-run? true}]
        (registry/collect! opened cutoff {})
        (is (empty? (:seon.cluster.registry/candidates
                     (registry/collect! opened cutoff opts))))
        (k/assoc konserve :seon.registry.test/orphan :present {:sync? true})
        (let [before (set (map :key (k/keys konserve {:sync? true})))
              bytes-before (store-bytes (:seon.store/dir opened))
              result (registry/collect! opened cutoff opts)]
          (is (= #{:seon.registry.test/orphan}
                 (:seon.cluster.registry/candidates result)))
          (is (= (count before) (:seon.cluster.registry/key-count result)))
          (is (zero? (:seon.cluster.registry/swept result)))
          (is (= before (set (map :key (k/keys konserve {:sync? true})))))
          (is (= bytes-before (store-bytes (:seon.store/dir opened))))
          (is (= (sort-by str (registry/branches opened))
                 (mapv :seon.store/branch (:seon.cluster.registry/branches result)))))))))

(deftest implicit-retention-requires-declared-cluster-windows
  (with-source-store
    (fn [opened]
      (let [connection (:seon.store/connection-object opened)]
        (is (= :seon.config/required-absent
               (:seon.config/rule (refusal #(registry/retention-cutoff opened)))))
        ; A row that declares no window blocks the whole store's collection, so
        ; the refusal must name the cluster and branch that hold it.
        (test-support/transacted!
         connection
         [(:seon.config/desired-row
           (config/compile-manifest
            {:seon.boot/cluster-name "legacy"
             :seon.config/manifest {:seon.config.db/snapshot-window-ms :seon.config/absent}}))])
        (let [refused (refusal #(registry/retention-cutoff opened))]
          (is (= [{:seon.store/branch :db :seon.config/cluster "legacy"}]
                 (:seon.config/undeclared refused))))
        ; The subject is the stored policy read, using the compiler's complete entity.
        (test-support/transacted!
         connection
         [(:seon.config/desired-row
           (config/compile-manifest
            {:seon.boot/cluster-name "legacy"
             :seon.config/manifest {:seon.config.db/snapshot-window-ms 0}}))])
        (let [head (registry/branch-commit-id {:seon.store/store opened
                                              :seon.store/branch :db})
              record (k/get (:store @connection) head nil {:sync? true})]
          (is (= (get-in record [:meta :datahike/updated-at])
                 (registry/retention-cutoff opened)))
          (is (nat-int? (registry/collect! opened)))
          (is (= head (registry/branch-commit-id {:seon.store/store opened
                                                 :seon.store/branch :db}))))))))

(deftest retention-keeps-every-commit-a-current-fact-names
  ;; A cluster's adoption record names its source commit, which test worlds
  ;; read by id (`seon.test/world-base`); a later publication leaves it a
  ;; non-head commit. A check JVM's scheduled collection swept such a commit
  ;; under the one-hour window, and every check then read a missing node.
  (with-source-store
    (fn [opened]
      (let [adopted (:seon.source/commit-id (cluster-request opened "adopter"))
            branch (registry/cluster-branch "adopter")
            _ (registry/ensure-cluster! (cluster-request opened "adopter"))
            cluster (store/open-branch! opened branch)
            source (store/open-branch! opened source-branch)]
        (try
          (test-support/transacted!
           cluster
           [(:seon.config/desired-row
             (config/compile-manifest
              {:seon.boot/cluster-name "adopter"
               :seon.config/manifest {:seon.config.db/snapshot-window-ms 0}}))])
          (doseq [n (range 3)]
            (write-marker! (:seon.store/connection-object opened) (str "main-" n))
            (write-marker! source (str "published-" n))
            (write-marker! cluster (str "cluster-" n)))
          (finally
            (store/release-branch! cluster)
            (store/release-branch! source)))
        (is (not-any? #{adopted} (map #(registry/branch-commit-id {:seon.store/store opened
                                                                   :seon.store/branch %})
                                      (registry/branches opened)))
            "the adopted commit is no branch's head")
        (registry/collect! opened (registry/retained-before opened (registry/retention-cutoff opened)) {})
        (let [connection (:seon.store/connection-object opened)
              database (d/commit-as-db connection adopted)]
          (is (k/exists? (:store @connection) adopted {:sync? true})
              "the commit the adoption record names survives the collection")
          (when database
            (try
              (is (contains? (markers (atom database)) "ancestral")
                  "and every node of its indexes reads")
              (finally (d/release-materialized-db database)))))))))

(deftest a-fact-naming-a-current-head-leaves-the-retention-cutoff-unchanged
  ;; A head is always marked by a collection, so a fact naming one needs no
  ;; older cutoff to keep it. On a copy of default's store one such fact moved
  ;; the cutoff back 95.8 hours.
  (with-source-store
    (fn [opened]
      ;; The cluster's adoption record names the source branch's head.
      (registry/ensure-cluster! (cluster-request opened "adopter"))
      (Thread/sleep 20)
      (write-marker! (:seon.store/connection-object opened) "later")
      (let [cutoff (java.util.Date.)]
        (is (= cutoff (registry/retained-before opened cutoff)))))))

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
              (test-support/transacted! connection
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
        (is (not (contains? (registry/branches opened) doomed))
            "the store's branches is the fact — the branch is gone before any sweep")
        (let [collected (registry/collect! opened (java.util.Date. 0) {})
              swept (:seon.cluster.registry/swept collected)]
          (is (pos? swept) "the doomed tail was reclaimed")
          (is (< (store-bytes dir) grown) "and the bytes actually shrank")
          (testing "the report names logical keys selected by the sweep"
            (is (= swept (count (:seon.cluster.registry/candidates collected))))
            (is (> (:seon.cluster.registry/key-count collected) swept))
            (is (not (neg? (:seon.cluster.registry/mark-duration-ms collected))))))
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
        (testing "collection is idempotent on a quiet store"
          (is (zero? (registry/collect! opened (java.util.Date. 0)))))
        (testing "a sweep with no candidates still answers its inventory"
          (let [collected (registry/collect! opened (java.util.Date. 0) {})]
            (is (zero? (:seon.cluster.registry/swept collected)))
            (is (empty? (:seon.cluster.registry/candidates collected)))
            (is (pos? (:seon.cluster.registry/key-count collected)))))
        (testing "retiring an absent branch is already done, never an error"
          (is (nil? (registry/retire-branch! {:seon.store/store opened
                                              :seon.store/branch doomed}))))))))

(deftest the-plain-collection-keeps-held-values-adopted-commits-and-concurrent-writes
  ;; The claim `:seon.config.maintenance/collect?` waited on: a collection
  ;; loses no node an open branch, a held database value or an in-flight
  ;; transaction reads. The plain collection (`registry/plain-cutoff`) keeps
  ;; every branch's whole ancestry, so a held value and a non-head
  ;; commit named as an adopted source stay whole while a retired branch goes.
  (with-source-store
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "live"))
      (registry/ensure-cluster! (cluster-request opened "doomed"))
      (let [live (store/open-branch! opened (registry/cluster-branch "live"))]
        (try
          (dotimes [n 5] (write-marker! live (str "held-" n)))
          (let [held @live
                adopted (:datahike/commit-id (:meta held))
                held-markers (markers live)]
            (dotimes [n 5] (write-marker! live (str "later-" n)))
            (let [doomed (store/open-branch! opened (registry/cluster-branch "doomed"))]
              (try (dotimes [n 5] (write-marker! doomed (str "doomed-" n)))
                   (finally (d/release doomed))))
            (registry/retire-branch! {:seon.store/store opened
                                      :seon.store/branch (registry/cluster-branch "doomed")})
            (let [writer (future (dotimes [n 10] (write-marker! live (str "during-" n))) :done)
                  collected (registry/collect! opened registry/plain-cutoff {})]
              (is (= :done (deref writer 30000 :timeout)) "the concurrent writer finished")
              (is (pos? (:seon.cluster.registry/swept collected)) "the retired branch was reclaimed")
              (let [cold (fn [database]
                           (set (d/q '[:find [?marker ...] :where [_ :seon.registry.test/marker ?marker]]
                                     database)))]
                (is (= held-markers (cold held)) "the held value still reads")
                (is (= held-markers (cold (d/commit-as-db (:seon.store/connection-object opened) adopted)))
                    "a non-head adopted commit is still readable")
                (d/release live)
                (let [fresh (store/open-branch! opened (registry/cluster-branch "live"))]
                  (try
                    (is (= 21 (count (markers fresh)))
                        "a fresh connection reads the ancestral, held, later and concurrent markers")
                    (finally (d/release fresh)))))))
          (finally (try (d/release live) (catch Throwable _))))))))

;;; ---------------------------------------------------------------------------
;;; Refusals
;;; ---------------------------------------------------------------------------

(deftest refusals-name-their-rule
  (with-source-store
    (fn [opened]
      (testing "an absent branch has no commit id: a declared error names store and branch"
        (is (uuid? (registry/branch-commit-id {:seon.store/store opened
                                               :seon.store/branch source-branch})))
        (let [missing (registry/branch-commit-id {:seon.store/store opened
                                                  :seon.store/branch :no-such-branch})]
          ;; The armed output contract validates the declared error's shape.
          (is (true? (:seon.cluster.registry/branch-missing missing)))
          (is (inst? (:seon.error/at missing)))
          (is (= [(:seon.store/dir opened) :no-such-branch]
                 [(:seon.store/dir missing) (:seon.store/branch missing)]))
          (is (= 'seon.cluster.registry/branch-commit-id (:seon.error/operation missing)))))
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
          (is (not (contains? (registry/branches opened) source-branch)))
          (is (pos? (registry/collect! opened (java.util.Date. 0))))
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

(deftest boot-retires-only-branches-no-fact-claims
  ;; A dead process's branches have no owner left to release them, so each
  ;; would root its data forever (90 of 99 branches, 2026-09-26). The
  ;; claims are facts: the cluster branch, each open agent entity's branch on it,
  ;; and the caller's declared branches. A fork inherits the cluster's name
  ;; entity, so it must not read as a cluster; a connected branch is never taken.
  (with-source-store
    (fn [opened]
      (registry/ensure-cluster! (cluster-request opened "alice"))
      (let [cluster (registry/cluster-branch "alice")
            fork! #(registry/branch! {:seon.store/store opened
                                      :seon.cluster.registry/from cluster
                                      :seon.store/branch %})
            _ (run! fork! [:agent-open :agent-closed :agent-orphan :agent-held])
            connection (store/open-branch! opened cluster)
            held (store/open-branch! opened :agent-held)]
        (try
          (test-support/transacted!
           connection
           {:tx-data [{:seon.agent/id "open" :seon.agent/branch :agent-open}
                      {:seon.agent/id "closed" :seon.agent/branch :agent-closed
                       :seon.agent/admission-closed-tx "datomic.tx"}]})
          (is (= #{:agent-closed :agent-orphan}
                 (registry/retire-unclaimed! opened #{source-branch})))
          (is (= #{:db source-branch cluster :agent-open :agent-held}
                 (registry/branches opened)))
          (is (= #{} (registry/retire-unclaimed! opened #{source-branch}))
              "a second pass finds nothing")
          (finally
            (d/release held)
            (d/release connection)))))))

(deftest ^{:seon/tasks #{'seon.cluster.source/merge-base-survives-collection}}
  an-idle-branch-keeps-the-commit-it-forked-from-through-collection
  ;; The lane forks from `src`'s head F and commits once; `src` then moves on.
  ;; A collection whose cutoff is past every commit (the window an idle branch
  ;; outlives) keeps each branch head but follows no parent, so F's commit
  ;; goes. The merge base, which `seon.cluster.source/compare-branch` (and so
  ;; `my.program/diff` and `my.program/write-branch!`) needs, is the lane's
  ;; own value as of its recorded fork t, which history keeps.
  (with-source-store
    (fn [opened]
      (let [src-connection (store/open-branch! opened source-branch)
            lane :lane
            head-of #(registry/branch-commit-id {:seon.store/store opened :seon.store/branch %})
            marker-set #(set (d/q '[:find [?m ...] :where [_ :seon.registry.test/marker ?m]] %))]
        (try
          (write-marker! src-connection "before-fork")
          (let [fork (marker-set @src-connection)]
            (registry/fork! {:seon.store/store opened :seon.cluster.registry/from source-branch
                             :seon.store/branch lane})
            (let [lane-connection (store/open-branch! opened lane)]
              (try (write-marker! lane-connection "on-lane")
                   (finally (store/release-branch! lane-connection))))
            (write-marker! src-connection "after-1")
            (write-marker! src-connection "after-2")
            (registry/collect! opened (java.util.Date. (+ (System/currentTimeMillis) 60000)) {})
            (let [head (source/commit-database opened (head-of source-branch))
                  branch (source/commit-database opened (head-of lane))
                  base (#'source/merge-base (registry/branches opened) branch head source-branch lane)
                  compared (source/compare-branch opened lane head source-branch)]
              (try
                (is (= fork (when-not (:seon.error/at base) (marker-set base)))
                    "the merge base holds exactly what the lane forked from")
                (is (= #{} (:changed compared))
                    "the comparison `my.program/diff` and `my.program/write-branch!` share reads the lane's changes since its fork")
                (finally (source/release! [head branch (:branch compared)])))))
          (finally (store/release-branch! src-connection)))))))

(deftest ^{:seon/tasks #{'seon.cluster.source/merge-base-survives-collection}}
  a-branch-without-its-own-fork-entity-refuses-its-merge-base-by-name
  (with-source-store
    (fn [opened]
      (registry/branch! {:seon.store/store opened :seon.cluster.registry/from source-branch
                         :seon.store/branch :bare})
      (let [branch (source/commit-database opened (registry/branch-commit-id {:seon.store/store opened :seon.store/branch :bare}))]
        (try
          (is (= {:seon.program/missing-evidence :seon.program/history :seon.store/branch :bare}
                 (select-keys (#'source/merge-base (registry/branches opened) branch branch source-branch :bare)
                              [:seon.program/missing-evidence :seon.store/branch])))
          (finally (source/release! [branch])))))))

(deftest ^{:seon/tasks #{'seon.cluster.source/merge-base-survives-collection}}
  a-reused-branch-name-never-takes-the-retired-forks-merged-base
  ;; Old :lane merges into `src` (its advanced base-t recorded there under its
  ;; fork id) and retires. A new :lane of the same name forks `src`, inheriting
  ;; that fork entity; its merge base is its own fork whether the stale base-t
  ;; lies above its head (the first reuse) or below it (the second, after
  ;; `src` moved past it).
  (with-source-store
    (fn [opened]
      (let [src-connection (store/open-branch! opened source-branch)
            head-of #(registry/branch-commit-id {:seon.store/store opened :seon.store/branch %})
            marker-set #(set (d/q '[:find [?m ...] :where [_ :seon.registry.test/marker ?m]] %))
            fork-lane! (fn [] (registry/fork! {:seon.store/store opened :seon.cluster.registry/from source-branch
                                               :seon.store/branch :lane}))
            on-lane! (fn [markers] (let [c (store/open-branch! opened :lane)]
                                     (try (run! #(write-marker! c %) markers) (finally (store/release-branch! c)))))
            base-markers (fn []
                           (let [head (source/commit-database opened (head-of source-branch))
                                 branch (source/commit-database opened (head-of :lane))
                                 base (#'source/merge-base (registry/branches opened) branch head source-branch :lane)]
                             (try [(registry/fork-of branch :lane) (when-not (:seon.error/at base) (marker-set base))]
                                  (finally (source/release! [head branch])))))
            retire! #(registry/retire-branch! {:seon.store/store opened :seon.store/branch :lane})]
        (try
          (fork-lane!)
          (on-lane! (map #(str "old-" %) (range 6)))
          (let [old (source/commit-database opened (head-of :lane))]
            (try (test-support/transacted! src-connection
                                           [(assoc (registry/fork-of old :lane) :seon.branch/base-t (db/basis-t old))])
                 (finally (source/release! [old]))))
          (retire!)
          (testing "the stale base-t lies above the new branch's head"
            (let [fork (marker-set @src-connection)
                  _ (fork-lane!)
                  _ (on-lane! ["new-on-lane"])
                  [own base] (base-markers)]
              (is (= :lane (:seon.branch/name own)))
              (is (= fork base) "the base is the new fork, not the retired branch's merged value")))
          (retire!)
          (run! #(write-marker! src-connection (str "src-" %)) (range 10))
          (testing "the stale base-t lies below the new branch's head"
            (let [fork (marker-set @src-connection)
                  _ (fork-lane!)
                  _ (on-lane! ["newer-on-lane"])
                  [_ base] (base-markers)]
              (is (= fork base) "the base is the new fork, not src as of the stale t")))
          (finally (store/release-branch! src-connection)))))))

(defn- with-program-store
  "Call `body` with a file store of its own and its main connection, carrying the executing program's
  attributes and projection, as `seon.test-support/with-database`'s fresh store does in memory: collection
  is store-global, so its proof needs a store no other test writes."
  {:malli/schema [:=> [:cat [:=> [:cat :seon.store/store :seon.db/connection] :nil]] :nil]}
  [body]
  (let [root (str "tmp/registry-test/" (random-uuid))
        opened (store/open-store! {:seon.store/dir (str root "/store")})
        connection (:seon.store/connection-object opened)
        handle (test-support/execution-handle db/*conn*)
        source (db/db (:seon.db/connection handle))]
    (try
      (d/transact connection (into [] (keep (fn [[ident attribute]]
                                              (when (and (keyword? ident) (namespace ident)
                                                         (not (str/starts-with? (namespace ident) "db")))
                                                (dissoc attribute :db/id))))
                                   (d/schema source)))
      (db/carry-connection-projection-state!
       connection (sci.eval/projection-state @connection (db/carried-projection source) (:seon.cluster/name handle)))
      (db/with-branch {:seon.db/connection connection} #(body opened connection))
      (finally
        (store/release-store! opened)
        (delete-recursively! root)))
    nil))

(defn- clean-key-count
  "The store's logical key count once collection left nothing unreachable."
  {:malli/schema [:=> [:cat :seon.store/store] [:int {:min 0}]]}
  [store]
  (registry/collect! store registry/plain-cutoff {})
  (:seon.cluster.registry/key-count (registry/collect! store registry/plain-cutoff {})))

(deftest
  a-deleted-attempts-storage-is-reclaimed-by-the-next-collection
  ;; Datahike's mark (`gc.cljc` reachable-in-branch) walks every head; an end copy is a plain
  ;; transaction with no parent link (`seon.merge/merge!`), so a deleted attempt's commits and nodes reach
  ;; nothing and are swept.
  (with-program-store
   (fn [store connection]
     (let [handle (task/task-handle 'fixture.tasks "reclaimed")
           branch :attempt-reclaimed
           ;; this store holds the program's attributes and no cluster, so the assignment's agent is its
           ;; identity alone: the merge validates the assignment it moves, never the agent it names
           _ (d/transact connection [{:seon.agent/id "reclaimed"}])
           _ (test-support/transacted! connection [(test-support/task-row handle "Original")])
           [row] (task/attempt-tx (db/db connection) handle "reclaimed" branch)
           _ (test-support/transacted! connection [row])
           base (db/db connection)
           a (:db/id (db/pull base [:db/id] [:seon.task/assignment (:seon.task/assignment row)]))
           ;; the fork below is taken at `base`, so `base` is the assignment's merge base
           _ (test-support/transacted! connection [[:db/add a :seon.task/merge-base (db/commit-id base)]])
           baseline (clean-key-count store)]
       (is (:seon.cluster/created? (registry/fork! {:seon.store/store store :seon.store/branch branch
                                                     :seon.cluster.registry/from (db/commit-id base) :seon.db/db base})))
       (let [fork (db/carry-connection-projection-state! (store/open-branch! store branch)
                                                         (:seon.sci.eval/projection-state (meta connection)))
             commits (try (db/with-branch
                           {:seon.db/connection fork}
                           #(doall (for [n (range 10)]
                                     (do (test-support/transacted! fork [{:db/id [:seon.task/id handle]
                                                                          :seon.task/plan (str "Refined " n ".")}])
                                         (db/commit-id (db/db fork))))))
                          (finally (store/release-branch! fork)))
             before-copy (clean-key-count store)
             copied (db/with-branch {:seon.db/connection connection}
                                    #(merge/merge! {:seon.db/connection connection :seon.store/store store}
                                                   (:seon.task/assignment (db/pull (db/db connection) [:seon.task/assignment] a))))
             with-copy (clean-key-count store)]
         (is (= "Refined 9." (:seon.task/plan (db/pull (db/db connection) [:seon.task/plan] [:seon.task/id handle]))))
         (is (uuid? (:seon.task/merge-commit copied)))
         (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})
         (let [collected (registry/collect! store registry/plain-cutoff {})]
           (is (pos? (:seon.cluster.registry/swept collected)) "the next collection sweeps the deleted branch")
           (is (every? #(nil? (k/get (:store @connection) % nil {:sync? true})) commits)
               "every commit only the branch wrote is gone")
           (is (= (+ baseline (- with-copy before-copy)) (clean-key-count store))
               "the store holds its baseline plus the one copied transaction")))
       nil))))

(deftest
  a-re-forked-branch-answers-its-own-fork-commit-as-its-merge-base
  ;; `datahike.api/delete-branch!` leaves a deleted branch's head record in the store until collection; the
  ;; merge base is the base ref the store's branches hold, so a retired branch has none and a re-fork under
  ;; the same name answers the commit that fork made
  (with-program-store
   (fn [store connection]
     (let [branch :attempt-re-forked
           fork! #(registry/fork! {:seon.store/store store :seon.store/branch branch
                                   :seon.cluster.registry/from :db :seon.db/db (db/db connection)})
           first-fork (db/commit-id (db/db connection))
           _ (is (:seon.cluster/created? (fork!)))
           _ (is (= first-fork (registry/base-commit-id (db/db connection) branch)))
           _ (d/transact connection [])
           second-fork (db/commit-id (db/db connection))]
       (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})
       (is (nil? (registry/base-commit-id (db/db connection) branch)) "a retired branch has no merge base")
       (is (:seon.cluster/created? (fork!)))
       (is (= second-fork (registry/base-commit-id (db/db connection) branch))
           "the re-fork's merge base is the commit it forked at")
       (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})
       nil))))

(deftest
  a-deleted-branch-answers-the-branch-missing-error-and-a-re-fork-its-new-commit
  ;; `datahike.api/delete-branch!` leaves a deleted branch's head record in the store until collection; the
  ;; store's branches are the fact, so a deleted branch is missing and a re-fork answers the commit it made
  (with-program-store
   (fn [store connection]
     (let [branch :attempt-deleted-head
           request {:seon.store/store store :seon.store/branch branch}
           fork! #(registry/fork! {:seon.store/store store :seon.store/branch branch
                                   :seon.cluster.registry/from :db :seon.db/db (db/db connection)})
           _ (is (:seon.cluster/created? (fork!)))
           first-head (registry/branch-commit-id request)
           _ (is (uuid? first-head) "a held branch answers its commit")
           _ (d/transact connection [])]
       (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})
       (is (true? (:seon.cluster.registry/branch-missing (registry/branch-commit-id request)))
           "a deleted branch is missing though its head record remains")
       (is (:seon.cluster/created? (fork!)))
       (is (and (uuid? (registry/branch-commit-id request)) (not= first-head (registry/branch-commit-id request)))
           "the re-fork answers its own new commit, not the deleted branch's")
       (registry/retire-branch! {:seon.store/store store :seon.store/branch branch})
       nil))))
