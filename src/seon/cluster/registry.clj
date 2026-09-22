(ns seon.cluster.registry
  "The registry: the ONE owner of branch lifecycle on a physical store.

  The model — BRANCH-PER-CLUSTER (b2-plan §0 verdict, owner-adopted):

  - One physical store per process root; a cluster is a BRANCH of it.
    Branch-off is 17 ms and one blob (b2-plan §0.5), so the published
    source bytes are stored ONCE for every cluster that descends from
    them, on any backend.
  - Two branches share exactly ONE mutable durable key, `:branches`,
    and only on create/delete: a commit writes content-addressed values
    and then the branch's OWN head
    (`reference-code/datahike/src/datahike/writing.cljc:503-552`).
    Concurrent cluster writes touch no shared mutable state.
  - THIS NAMESPACE HOLDS THE ONLY CONNECTION THAT CALLS `branch!`,
    `delete-branch!`, OR `gc-storage` (b2-plan §0.6 conditions 1 and
    3), and that connection is the store's already-flock-held main
    connection — no second connection is ever opened for lifecycle.
    A cluster receives a branch connection and never the branch API,
    so cluster A holds no handle that can delete cluster B.
  - `delete-database` is NEVER called (§0.6 condition 2). Resetting one
    cluster is `delete-branch!` + `branch!` from the ancestor; deleting
    the store is an operator-only whole-system action.
  - GC isolation is STRUCTURAL, not policy: the mark unions
    `reachable-in-branch` over EVERY roster branch and seeds each head
    unconditionally
    (`reference-code/datahike/src/datahike/gc.cljc:26,60-70,136-143`),
    so collecting after one cluster is retired can never take a
    sibling's or the ancestor's data. Proven live (b2-plan §0.7: the
    doomed tail's 139 objects swept, survivor and ancestor whole, a
    second pass swept nothing).
  - The roster read-modify-write race that made this unsafe is FIXED in
    our fork (submodule `357ffc87`, \"Serialize branch roster mutations
    by store\"), applying the fork's own store-id-keyed guard idiom
    (`gc_guard.cljc:47-52`) to `versioning.cljc:255-257,289`. Before
    that fix twelve concurrent creates reported eleven successes and
    landed nine (b2-plan §0.3). `a-concurrent-create-wave-loses-nothing`
    is that scar's standing regression.
  - Refusals are loud ex-info
    `{::refused <which> ::rule <which>}`, matching B0/B1
    (`src/seon/cluster/store.clj:161-167`).

  Crash walk (kill -9 at any point; the OS releases the store's flock,
  so the next boot always re-acquires it):

  - mid cluster `branch!`, BEFORE the roster update: an orphan head
    blob nothing points at. The cluster branch is absent, so
    `ensure-cluster!` branches again and GC sweeps the orphan;
  - after the cluster branch is in the roster, before its first
    connect: a complete branch — the resume path connects and goes.
    The roster IS the fact; nothing is detected;
  - mid `delete-branch!` (`k/update … disj`): one key write that either
    happened or did not. `retire-branch!` re-runs; Datahike's
    `:branch-does-not-exist` reads as ALREADY DONE, never as an error;
  - mid GC sweep: some unreachable objects deleted, some not. Nothing
    to detect — the safe point (`gc.cljc:88-105`) guarantees everything
    deleted was already unreachable, and a later pass finishes;
  - mid cluster commit: values written, head not flipped. Datahike's
    values-then-pointer barrier means reopen sees the old head and the
    orphan values are collected;
  - mid concurrent `branch!` from two connections: covered by the fork
    fix above. Without it this row was NOT DETECTABLE — the caller was
    told `:ok` and the branch was gone (b2-plan §0.3)."
  (:require [clojure.edn :as edn]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.config :as config]
            [seon.schema :as schema]
            [datahike.connections :as connections]
            [datahike.store :as datahike.store]
            [konserve.core :as k]
            [seon.cluster.store :as store]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(defn- refuse!
  "Refuse loudly with the one registry error shape."
  {:malli/schema [:=> [:cat :keyword :string :map] :nil]}
  [rule message data]
  (throw (ex-info message
                  (assoc data

                         ::refused rule
                         ::rule rule))))

;;; ---------------------------------------------------------------------------
;;; Pure derivations
;;; ---------------------------------------------------------------------------

(defn cluster-branch
  "The ONE branch name for a cluster: `:cluster-<name>`.
  One derivation — no other code builds this keyword, so a cluster's
  name and its branch can never disagree."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name] :seon.store/branch]}
  [cluster-name]
  (keyword (str "cluster-" cluster-name)))

;;; ---------------------------------------------------------------------------
;;; The roster
;;; ---------------------------------------------------------------------------

(defn roster
  "The store's branch roster, read through its main connection.
  The roster is the FACT: a branch in it exists, a branch absent from
  it does not, whatever blobs are on disk
  (`reference-code/datahike/src/datahike/versioning.cljc:182-189`)."
  {:malli/schema [:=> [:cat :seon.store/store] :seon.cluster.registry/roster]}
  [store]
  (set (d/branches (:seon.store/connection-object store))))

;;; ---------------------------------------------------------------------------
;;; Reading what the store already knows
;;; ---------------------------------------------------------------------------

(declare retire-branch!)

;;; The connection's own konserve store instance. Two connections to one
;;; physical store hold DIFFERENT instances (gc_guard.cljc:47-50), which
;;; is exactly why the roster mutation is serialized by store id in the
;;; fork; for plain reads either instance answers the same bytes.
(defn- konserve-store [store]
  (:store @(:seon.store/connection-object store)))

(defn- head-record
  "The stored record under a branch keyword or a commit id."
  [konserve key]
  (k/get konserve key nil {:sync? true}))

(defn branch-commit-id
  "The commit ID currently named by a branch, or nil when absent."
  {:malli/schema [:=> [:cat :seon.cluster.registry/branch-commit-request]
                  [:maybe :seon.source/commit-id]]}
  [{:keys [:seon.store/store :seon.store/branch]}]
  (get-in (head-record (konserve-store store) branch)
          [:meta :datahike/commit-id]))

(defn connection-branch-commit-id
  "Read a branch head through an already supplied connection's physical store."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/branch]
                  [:maybe :seon.source/commit-id]]}
  [connection branch]
  (get-in (head-record (:store @connection) branch)
          [:meta :datahike/commit-id]))

(defn active-branch-connection
  "The active connection to `:seon.store/branch`, or nil when absent.
  Borrows Datahike's registered connection without acquiring an owning
  reference; the caller must never release it. The registry entry is the
  process-local custody fact for the `[store-id branch]` writer."
  {:malli/schema [:=> [:cat :seon.cluster.registry/branch-commit-request]
                  [:maybe :seon.db/connection]]}
  [{:keys [:seon.store/store :seon.store/branch]}]
  (let [configuration (assoc (store/datahike-configuration
                              (:seon.store/dir store))
                             :branch branch)]
    (connections/active-connection
     (datahike.store/connection-id configuration))))

(defn- branch-connected?
  "True when THIS process already holds a connection to `branch`."
  [store branch]
  (some? (active-branch-connection {:seon.store/store store
                                    :seon.store/branch branch})))

;;; ---------------------------------------------------------------------------
;;; Branch lifecycle — the one owner
;;; ---------------------------------------------------------------------------

(defn branch!
  "Create `:seon.store/branch` from `::from`, idempotently.
  The one primitive every other creation in the system is built from
  (`ensure-cluster!`, `reset-cluster!`, and the ancestor build's scratch
  and publish steps all call THIS, never `datahike.api/branch!`).
  `::from` is a branch keyword or a commit UUID — the commit form is
  supported because `:commit-graph?` defaults true
  (`versioning.cljc:222-228`) and lets a cluster descend from a retired
  ancestor's commit.
  Returns `{:seon.store/branch b :seon.cluster/created? true}` when this
  call created it and `::created? false` when the branch was already in
  the roster — including when Datahike's own
  `:branch-already-exists` (`versioning.cljc:233-235`) refuses a lost
  race, which is idempotence, not a failure.
  Refuses `::source-absent` (`::from` names no branch or commit)."
  {:malli/schema [:=> [:cat :seon.cluster.registry/branch-request]
                  :seon.cluster.registry/branch-result]}
  [{:keys [:seon.store/store :seon.store/branch]
    source :seon.cluster.registry/from
    reachability-permit :datahike.gc-guard/reachability-permit}]
  (if (contains? (roster store) branch)
    {:seon.store/branch branch :seon.cluster/created? false}
    (try
      (if reachability-permit
        (d/branch! (:seon.store/connection-object store)
                   source
                   branch
                   {:sync? true
                    :datahike.gc-guard/reachability-permit
                    reachability-permit})
        (d/branch! (:seon.store/connection-object store) source branch))
      {:seon.store/branch branch :seon.cluster/created? true}
      (catch clojure.lang.ExceptionInfo failure
        (case (:type (ex-data failure))
          ; a lost race is idempotence, not a failure: the branch the
          ; caller asked for is in the roster (versioning.cljc:233-235)
          :branch-already-exists
          {:seon.store/branch branch :seon.cluster/created? false}

          (:branch-does-not-exist :commit-not-found)
          (refuse! ::source-absent
                   (str "no branch or commit " source " to branch from")
                   {::dir (:seon.store/dir store)
                    :seon.cluster.registry/from source
                    :seon.store/branch branch})

          (throw failure))))))

(defn- record-fork!
  "Record the exact source commit with the new branch's cluster row."
  {:malli/schema [:=> [:cat :seon.store/store :seon.boot/cluster-name
                       :seon.source/commit-id [:or :nil :map]] :nil]}
  [store cluster-name source-commit supplied-projection]
  (let [connection (store/open-branch! store (cluster-branch cluster-name))]
    (try
      (let [database (db/db connection)
            projection (or supplied-projection (db/carried-projection database)
                           (schema/handed-projection)
                           (schema/projection-from-database database))]
        (schema/call-with-projection
         projection
         (fn []
           (let [compiled (config/compile-manifest {:seon.boot/cluster-name cluster-name})
                 result (db/transact!
                         connection
                         {:tx-data [(:seon.config/desired-row compiled)
                                    {:seon.cluster/name cluster-name
                                     :seon.cluster/config [:seon.config/cluster cluster-name]
                                     :seon.source/commit-id source-commit}]})]
             (when-not (:db-after result)
               (throw (ex-info "The fork's source commit could not be recorded." result)))))))
      (finally (store/release-branch! connection))))
  nil)

(defn ensure-cluster!
  "Ensure `:cluster-<name>` exists at the requested source commit.
  Idempotent by the roster: a second call returns
  `::created? false` and writes nothing. Concurrent calls from N
  threads produce N branches and zero orphans — the fork's serialized
  roster mutation is what makes that true, and it is a standing
  regression, not an assumption.
  The source is an exact immutable commit ID, so publication can advance
  while this operation still forks the database value its caller chose.
  A new branch records that commit on its cluster row in one transaction
  with the row's required config. Boot preserves the recorded source fact.
  Refuses `::source-absent` when that commit is unavailable."
  {:malli/schema [:=> [:cat :seon.cluster.registry/cluster-request]
                  :seon.cluster.registry/branch-result]}
  [{:keys [:seon.store/store :seon.boot/cluster-name]
    source-commit :seon.source/commit-id
    projection :seon.schema/projection
    reachability-permit :datahike.gc-guard/reachability-permit}]
  (let [result (branch! {:seon.store/store store
                         :seon.cluster.registry/from source-commit
                         :seon.store/branch (cluster-branch cluster-name)
                         :datahike.gc-guard/reachability-permit reachability-permit})]
    (when (:seon.cluster/created? result)
      (record-fork! store cluster-name source-commit projection))
    result))

(defn reset-cluster!
  "Return a cluster to an exact source commit.
  This is L18 exactly — reset to current code and pages, never migrate.
  Datahike's `force-branch!` writes immutable values before atomically
  replacing the branch head, so a failed reset leaves the old head reachable;
  the old tail becomes unreachable only after the replacement commits. The
  next `collect!` reclaims it; siblings and the source branch are untouched.
  Always returns `::created? true`: the branch after the call is new.
  Refuses `::cluster-connected` (this process still holds a connection
  to that branch — Datahike refuses too at
  `versioning.cljc:279-288`, but we refuse EARLIER and by name) and
  `::source-absent`."
  {:malli/schema [:=> [:cat :seon.cluster.registry/cluster-request]
                  :seon.cluster.registry/branch-result]}
  [{:keys [:seon.store/store :seon.boot/cluster-name]
    source-commit :seon.source/commit-id
    projection :seon.schema/projection}]
  (let [branch (cluster-branch cluster-name)
        current-commit (branch-commit-id {:seon.store/store store
                                          :seon.store/branch branch})]
    (when (branch-connected? store branch)
      (refuse! ::cluster-connected
               (str "branch " branch " still has a connection in this process")
               {::dir (:seon.store/dir store) :seon.store/branch branch}))
    (let [source-db (d/commit-as-db (:seon.store/connection-object store)
                                    source-commit)]
      (when-not source-db
        (refuse! ::source-absent
                 (str "the source commit " source-commit " is unavailable")
                 {::dir (:seon.store/dir store)
                  :seon.source/commit-id source-commit
                  :seon.boot/cluster-name cluster-name}))
      (d/force-branch! source-db branch #{source-commit}
                       {:expected-current-commit current-commit})
      (record-fork! store cluster-name source-commit projection)
      {:seon.store/branch branch :seon.cluster/created? true})))

(defn retire-branch!
  "Remove one branch from the roster. Idempotent; data survives until GC.
  `delete-branch!` removes the roster entry only
  (`versioning.cljc:279-320`); the bytes go when `collect!` runs, and a
  branch absent from the roster is ALREADY DONE — Datahike's
  `:branch-does-not-exist` is the success path for a re-run, not an
  error (the mid-delete crash row).
  Translates Datahike's `:branch-has-active-connection` to
  `::cluster-connected` and `:cannot-delete-main-db-branch` to
  `::cannot-retire-main`. Descendant branches do
  not prevent retirement: each remaining roster branch independently
  roots its head and parent commits during collection
  (`gc.cljc:22-81`), so deleting an ancestor's roster name cannot make
  a descendant lose data."
  {:malli/schema [:=> [:cat :seon.cluster.registry/retire-request] :nil]}
  [{:keys [:seon.store/store :seon.store/branch]}]
  (try
    (d/delete-branch! (:seon.store/connection-object store) branch)
    (catch clojure.lang.ExceptionInfo failure
      (case (:type (ex-data failure))
        ; the roster is the fact: a branch already gone is already done
        :branch-does-not-exist nil
        :cannot-delete-main-db-branch
        (refuse! ::cannot-retire-main
                 "the main :db branch is the store; it is never retired"
                 {::dir (:seon.store/dir store) :seon.store/branch branch})
        :branch-has-active-connection
        (refuse! ::cluster-connected
                 (str "branch " branch " still has a connection in this process")
                 {::dir (:seon.store/dir store) :seon.store/branch branch})
        (throw failure))))
  nil)

(defn- blob-digest-attributes
  [db]
  (let [forms
        (into {}
              (map (fn [[schema-key serialized-form]]
                     [schema-key (edn/read-string serialized-form)]))
              (d/q '[:find ?schema-key ?form
                     :where
                     [?schema :seon.schema/key ?schema-key]
                     [?schema :seon.schema/form ?form]]
                   db))
        digest-schema?
        (fn digest-schema? [form seen]
          (cond
            (= :seon.blob/digest form) true
            (and (keyword? form)
                 (not (contains? seen form)))
            (when-let [referenced (get forms form)]
              (digest-schema? referenced (conj seen form)))
            (coll? form)
            (boolean (some #(digest-schema? % seen) form))
            :else false))]
    (into []
          (keep (fn [[attribute form]]
                  (when (and (not= attribute :seon.blob/digest)
                             (digest-schema? form #{attribute}))
                    attribute)))
          forms)))

(defn- branch-blobs
  [connection branch include-history?]
  (let [db (d/branch-as-db connection branch)]
    (try
      (let [digest-attributes (blob-digest-attributes db)
            history-view (if include-history? (db/history db) db)
            history-db (cond
                         (= :seon.config.db/keep-history?
                            (:seon.config/error-key history-view)) db
                         (db/database-value? history-view) history-view
                         :else (throw (ex-info "Cannot determine retained blob references."
                                               history-view)))]
        (into #{}
              (mapcat
               (fn [attribute]
                 (d/q '[:find [?digest ...]
                        :in $ ?attribute
                        :where [_ ?attribute ?digest]]
                      history-db attribute)))
              digest-attributes))
      (finally
        (d/release-materialized-db db)))))

(defn referenced-blobs
  "Derive blob references across the supplied store branches.

  Collection callers hold the store's exclusive reachability permit while
  deriving branches, calling this function, and deleting objects. Historical
  collection includes history; byte retention needs only current datoms."
  {:malli/schema
   [:=> [:cat :seon.db/connection [:set :seon.store/branch] :boolean]
    [:set :seon.blob/digest]]}
  [connection branches include-history?]
  (into #{}
        (mapcat #(branch-blobs connection % include-history?))
        branches))

(defn- branch-heads
  {:malli/schema [:=> [:cat :seon.store/store [:seqable :seon.store/branch]] [:vector [:map [:seon.store/branch :seon.store/branch] [:seon.source/commit-id :seon.source/commit-id]]]]}
  [store branches]
  (mapv
   (fn [branch]
     (let [commit-id (branch-commit-id {:seon.store/store store
                                        :seon.store/branch branch})]
       (when-not commit-id
         (throw
          (ex-info "A roster branch has no readable head commit ID."
                   {:seon.store/branch branch :seon.cluster.registry/branch-head-absent true})))
       {:seon.store/branch branch
        :seon.source/commit-id commit-id}))
   (sort-by str branches)))

(defn- elapsed-ms
  [started-ns]
  (quot (- (System/nanoTime) started-ns) 1000000))

(defn- collect-and-inventory!
  "Use Datahike's reachability and konserve's candidate selection for either mode."
  {:malli/schema [:=> [:cat :seon.store/store :inst :map]
                  :seon.cluster.registry/inventory]}
  [store remove-before options]
  (let [started-ns (System/nanoTime)
        connection (:seon.store/connection-object store)
        heads (atom [])
        key-count (atom 0)
        dry-run? (true? (:seon.operator.collect/dry-run? options))
        candidates
        @(d/gc-storage
          connection remove-before
          (-> options
              (assoc :datahike.gc/reachable-extension
                     (fn [{:datahike.gc/keys [branches] gc-store :datahike.gc/store}]
                       (reset! heads (branch-heads store branches))
                       (reset! key-count (count (k/keys gc-store {:sync? true})))
                       (referenced-blobs connection branches true)))
              (assoc-in [:datahike.gc/sweep-opts :konserve.gc/dry-run?]
                        dry-run?)))]
    {:seon.cluster.registry/key-count @key-count
     :seon.cluster.registry/candidates candidates
     :seon.cluster.registry/branches @heads
     :seon.cluster.registry/mark-duration-ms (elapsed-ms started-ns)
     :seon.cluster.registry/swept (if dry-run? 0 (count candidates))}))

(defn retention-cutoff
  "Derive a collection cutoff from captured branch heads and their config facts.
  Each cluster configuration must opt in. The largest window governs the store;
  the captured newest head anchors time, so idle wall time alone expires nothing."
  {:malli/schema [:=> [:cat :seon.store/store] :inst]}
  [store]
  (let [connection (:seon.store/connection-object store)
        heads (branch-heads store (roster store))
        observations
        (mapv
         (fn [{commit-id :seon.source/commit-id}]
           (let [record (head-record (konserve-store store) commit-id)
                 database (d/commit-as-db connection commit-id)]
             (try
               [(or (get-in record [:meta :datahike/updated-at])
                    (get-in record [:meta :datahike/created-at]))
                (mapv #(d/pull database
                                        [:seon.config/cluster :seon.config.db/snapshot-window-ms] %)
                                (d/q '[:find [?e ...] :where [?e :seon.config/cluster]] database))]
               (finally (d/release-materialized-db database)))))
         heads)
        policies (mapcat second observations)]
    (when (or (empty? policies)
              (some #(nil? (:seon.config.db/snapshot-window-ms %)) policies))
      (throw (ex-info "Every cluster configuration must declare snapshot retention before implicit collection."
                      {:seon.config/error-key :seon.config.db/snapshot-window-ms
                       :seon.config/rule :seon.config/required-absent})))
    (java.util.Date.
     (- (apply max (map #(.getTime ^java.util.Date (first %)) observations))
        (apply max (map :seon.config.db/snapshot-window-ms policies))))))

(defn collect!
  "Collect or inventory this store's unreachable objects.
  One owner per store — the process, never a cluster (§0.6 condition
  3) — and it runs where the writers are, which is this JVM
  (`gc.cljc:105-115`). Whole-store by nature: the mark is a union over
  every roster branch, so the cost scales with total data and the
  isolation is structural.

  The one-and two-argument arities answer the swept count. The
  three-argument arity answers the whole inventory — the swept count with
  the observed logical key count, candidate keys and collection duration — for
  the real collection as well as for `:seon.operator.collect/dry-run? true`,
  because both need the same denominator.

  A SECOND PASS OVER A LIVE STORE DOES NOT SWEEP ZERO. Every write that lands
  while the first pass runs is older than the second pass's `remove-before`,
  so it is judged on its own reachability, and a fixed point exists only on a
  quiet store. Idempotence on a quiet store (b2-plan §0.7) is a property of
  this function; it is NOT a completeness criterion for a collection, whose
  correctness comes from Datahike's safe point (`datahike/gc_guard.cljc`) and
  is verified by reopening every recorded root (`seon.operator/collect!`)."
  {:malli/schema
   [:function
    [:=> [:cat :seon.store/store] :seon.cluster.registry/swept]
    [:=> [:cat :seon.store/store :inst] :seon.cluster.registry/swept]
    [:=> [:cat :seon.store/store :inst [:map]]
     :seon.cluster.registry/inventory]]}
  ([store]
   (collect! store (retention-cutoff store)))
  ([store remove-before]
   (:seon.cluster.registry/swept (collect! store remove-before {})))
  ([store remove-before options]
   (collect-and-inventory! store remove-before options)))
