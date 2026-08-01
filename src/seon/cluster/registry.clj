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
    `{:seon.error/kind ::refused ::rule <which>}`, matching B0/B1
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
            [datahike.connections :as connections]
            [datahike.store :as datahike.store]
            [konserve.core :as k]
            [konserve.gc :as konserve.gc]
            [seon.cluster.store :as store]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/registry.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(defn- refuse!
  "Refuse loudly with the one registry error shape."
  [rule message data]
  (throw (ex-info message
                  (assoc data
                         :seon.error/kind ::refused
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
  (`reference-code/datahike/src/datahike/versioning.cljc:206-214`)."
  {:malli/schema [:=> [:cat :seon.store/store] :seon.cluster.registry/roster]}
  [store]
  (set (d/branches (:seon.store/connection store))))

;;; ---------------------------------------------------------------------------
;;; Reading what the store already knows
;;; ---------------------------------------------------------------------------

(declare retire-branch!)

;;; The connection's own konserve store instance. Two connections to one
;;; physical store hold DIFFERENT instances (gc_guard.cljc:47-50), which
;;; is exactly why the roster mutation is serialized by store id in the
;;; fork; for plain reads either instance answers the same bytes.
(defn- konserve-store [store]
  (:store @(:seon.store/connection store)))

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

(defn- commit-present?
  [store commit-id]
  (some? (head-record (konserve-store store) commit-id)))

(defn- branch-connected?
  "True when THIS process already holds a connection to `branch`.
  The same `[store-id branch]` connection-id lookup `open-branch!` uses
  (`src/seon/cluster/store.clj:354,360`); Datahike reference-counts a
  second connect into the SAME connection, so presence here is the only
  honest answer to \"is anyone still holding it\"."
  [store branch]
  (let [configuration (assoc (store/datahike-configuration
                              (:seon.store/dir store))
                             :branch branch)]
    (contains? @connections/*connections*
               (datahike.store/connection-id configuration))))

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
    source :seon.cluster.registry/from}]
  (if (contains? (roster store) branch)
    {:seon.store/branch branch :seon.cluster/created? false}
    (try
      (d/branch! (:seon.store/connection store) source branch)
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

(defn ensure-cluster!
  "Ensure `:cluster-<name>` exists at the requested source commit.
  Idempotent by the roster: a second call returns
  `::created? false` and writes nothing. Concurrent calls from N
  threads produce N branches and zero orphans — the fork's serialized
  roster mutation is what makes that true, and it is a standing
  regression, not an assumption.
  The source is an exact immutable commit ID, so publication can advance
  while this operation still forks the database value its caller chose.
  Refuses `::source-absent` when that commit is unavailable."
  {:malli/schema [:=> [:cat :seon.cluster.registry/cluster-request]
                  :seon.cluster.registry/branch-result]}
  [{:keys [:seon.store/store :seon.boot/cluster-name]
    source-commit :seon.source/commit-id}]
  (when-not (commit-present? store source-commit)
    (refuse! ::source-absent
             (str "the source commit " source-commit " is unavailable")
             {::dir (:seon.store/dir store)
              :seon.source/commit-id source-commit
              :seon.boot/cluster-name cluster-name}))
  (branch! {:seon.store/store store
            :seon.cluster.registry/from source-commit
            :seon.store/branch (cluster-branch cluster-name)}))

(defn reset-cluster!
  "Return a cluster to an exact source commit.
  This is L18 exactly — reset to current code and pages, never migrate
  — and it is `delete-branch!` + `branch!`, NEVER `delete-database`
  (b2-plan §0.6 condition 2). The old tail becomes unreachable and the
  next `collect!` reclaims it; siblings and the source branch are untouched
  by construction (§0.7).
  Always returns `::created? true`: the branch after the call is new.
  Refuses `::cluster-connected` (this process still holds a connection
  to that branch — Datahike refuses too at
  `versioning.cljc:279-288`, but we refuse EARLIER and by name) and
  `::source-absent`."
  {:malli/schema [:=> [:cat :seon.cluster.registry/cluster-request]
                  :seon.cluster.registry/branch-result]}
  [{:keys [:seon.store/store :seon.boot/cluster-name]
    source-commit :seon.source/commit-id
    :as request}]
  (let [branch (cluster-branch cluster-name)]
    ;; Refuse before retiring the existing cluster. A bad requested commit
    ;; must never turn a reset attempt into data loss.
    (when-not (commit-present? store source-commit)
      (refuse! ::source-absent
               (str "the source commit " source-commit " is unavailable")
               {::dir (:seon.store/dir store)
                :seon.source/commit-id source-commit
                :seon.boot/cluster-name cluster-name}))
    (retire-branch! {:seon.store/store store :seon.store/branch branch})
    (ensure-cluster! request)))

(defn retire-branch!
  "Remove one branch from the roster. Idempotent; data survives until GC.
  `delete-branch!` removes the roster entry only
  (`versioning.cljc:261-289`); the bytes go when `collect!` runs, and a
  branch absent from the roster is ALREADY DONE — Datahike's
  `:branch-does-not-exist` is the success path for a re-run, not an
  error (the mid-delete crash row).
  Refuses `::cluster-connected` (a live connection to that branch in
  this process) and `::cannot-retire-main` (`:db` — the genesis branch
  is the store, and Datahike refuses it too). Descendant branches do
  not prevent retirement: each remaining roster branch independently
  roots its head and parent commits during collection
  (`gc.cljc:22-81`), so deleting an ancestor's roster name cannot make
  a descendant lose data."
  {:malli/schema [:=> [:cat :seon.cluster.registry/retire-request] :nil]}
  [{:keys [:seon.store/store :seon.store/branch]}]
  (when (= :db branch)
    (refuse! ::cannot-retire-main
             "the main :db branch is the store; it is never retired"
             {::dir (:seon.store/dir store) :seon.store/branch branch}))
  (when (contains? (roster store) branch)
    (when (branch-connected? store branch)
      (refuse! ::cluster-connected
               (str "branch " branch " still has a connection in this process")
               {::dir (:seon.store/dir store) :seon.store/branch branch}))
    (try
      (d/delete-branch! (:seon.store/connection store) branch)
      (catch clojure.lang.ExceptionInfo failure
        ; the roster is the fact: a branch already gone is already done
        (when-not (= :branch-does-not-exist (:type (ex-data failure)))
          (throw failure)))))
  nil)

(defonce ^:private collect-monitor (Object.))

(defn- blob-digest-attributes
  [db]
  (into []
        (keep (fn [[attribute serialized-form]]
                (when (= :seon.blob/digest
                         (edn/read-string serialized-form))
                  attribute)))
        (d/q '[:find ?attribute ?form
               :where
               [?schema :seon.schema/key ?attribute]
               [?schema :seon.schema/form ?form]]
             db)))

(defn- branch-blobs
  [store branch]
  (let [db (d/branch-as-db (:seon.store/connection store) branch)]
    (try
      (let [digest-attributes (blob-digest-attributes db)
            history-db (try (d/history db)
                            (catch Throwable _ db))]
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

(defn- referenced-blobs
  [store]
  (into #{}
        (mapcat #(branch-blobs store %))
        (roster store)))

(defn collect!
  "Collect this store's unreachable objects; returns how many were swept.
  One owner per store — the process, never a cluster (§0.6 condition
  3) — and it runs where the writers are, which is this JVM
  (`gc.cljc:105-115`). Whole-store by nature: the mark is a union over
  every roster branch, so the cost scales with total data and the
  isolation is structural. Idempotent: a second pass over the same
  state sweeps zero (proven, b2-plan §0.7)."
  {:malli/schema [:=> [:cat :seon.store/store] :seon.cluster.registry/swept]}
  [store]
  (locking collect-monitor
    (let [blob-keys (referenced-blobs store)
          sweep! konserve.gc/sweep!]
      ;; Datahike refers this exact Var. Rebinding it keeps Datahike's one
      ;; safe-point mark/sweep operation intact while extending the mark by
      ;; one fact-derived hop. `collect-monitor` serializes Seon's sole GC
      ;; entry point so no store observes another store's derived set.
      (with-redefs [konserve.gc/sweep!
                    (fn
                      ([konserve reachable cutoff]
                       (sweep! konserve (into reachable blob-keys) cutoff))
                      ([konserve reachable cutoff batch-size]
                       (sweep! konserve
                               (into reachable blob-keys)
                               cutoff
                               batch-size)))]
        (count @(d/gc-storage (:seon.store/connection store)))))))
