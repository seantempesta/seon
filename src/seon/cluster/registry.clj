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
    `reachable-in-branch` over EVERY branch and seeds each head
    unconditionally
    (`reference-code/datahike/src/datahike/gc.cljc:26,60-70,136-143`),
    so collecting after one cluster is retired can never take a
    sibling's or the ancestor's data. Proven live (b2-plan §0.7: the
    doomed tail's 139 objects swept, survivor and ancestor whole, a
    second pass swept nothing).
  - The branches read-modify-write race that made this unsafe is FIXED in
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

  - mid cluster `branch!`, BEFORE the store's branches update: an orphan head
    blob nothing points at. The cluster branch is absent, so
    `ensure-cluster!` branches again and GC sweeps the orphan;
  - after the cluster branch is in the store's branches, before its first
    connect: a complete branch — the resume path connects and goes.
    The store's branches ARE the fact; nothing is detected;
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
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.db.interface :as dbi]
            [seon.cluster.branch :as cluster.branch]
            [seon.cache]
            [seon.db :as db]
            [seon.id :as id]
            [seon.error.refusal :as error.refusal]
            [seon.config :as config]
            [seon.schema :as schema]
            [datahike.connections :as connections]
            [datahike.store :as datahike.store]
            [konserve.core :as k]
            [seon.cluster.store :as store]
            [seon.schema.edn :as schema.edn])
  (:import [org.replikativ.persistent_sorted_set ANode Branch PersistentSortedSet Slot]))

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

(def ^{:arglists '([cluster-name])
       :doc "RETIRING: callers move to `seon.cluster.branch/cluster-branch` in one
  scripted sweep (their files are held by other owners); then this name goes."}
  cluster-branch
  #'cluster.branch/cluster-branch)

;;; ---------------------------------------------------------------------------
;;; The store's branches
;;; ---------------------------------------------------------------------------

(defn branches
  "The store's branches, read through its main connection.
  The branches are the FACT: a branch in it exists, a branch absent from
  it does not, whatever blobs are on disk
  (`reference-code/datahike/src/datahike/versioning.cljc:182-189`)."
  {:malli/schema [:=> [:cat :seon.store/store] :seon.cluster.registry/branches]}
  [store]
  (set (d/branches (:seon.store/connection-object store))))

;;; ---------------------------------------------------------------------------
;;; Reading what the store already knows
;;; ---------------------------------------------------------------------------

(declare retire-branch!)

;;; The connection's own konserve store instance. Two connections to one
;;; physical store hold DIFFERENT instances (gc_guard.cljc:47-50), which
;;; is exactly why the store's branches mutation is serialized by store id in the
;;; fork; for plain reads either instance answers the same bytes.
(defn- konserve-store [store]
  (:store @(:seon.store/connection-object store)))

(defn- head-record
  "The stored record under a branch keyword or a commit id."
  [konserve key]
  (k/get konserve key nil {:sync? true}))

(defn held-branches
  "The branches `database`'s store holds now, read from its `:branches` record: the store's one fact of which branches
  exist (`branches` reads the same record through a connection)."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.cluster.registry/branches]}
  [database]
  (set (head-record (:store (db/schema-database database)) :branches)))

(defn head-commit-id
  "The commit `branch` of `database`'s store names now, read from its head record; nil when the store's branches
  hold no such branch."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch] [:maybe :seon.source/commit-id]]}
  [database branch]
  (when (contains? (held-branches database) branch)
    (get-in (head-record (:store (db/schema-database database)) branch) [:meta :datahike/commit-id])))

(defn branch-commit-id
  "The commit ID currently named by a branch. A branch the store does not
  hold answers `:seon.cluster.registry/branch-missing-error`, naming the store
  and the branch, so no caller mistakes absence for a commit. A caller holding
  the store's branches as its basis passes them (`:seon.cluster.registry/branches`),
  so a branch deleted since still answers the head it read: the head record
  stays until collection.

  ## Example

  ```clojure
  (branch-commit-id {:seon.store/store store :seon.store/branch :current-src})
  ```"
  {:malli/schema [:=> [:cat :seon.cluster.registry/branch-commit-request]
                  [:or :seon.source/commit-id :seon.cluster.registry/branch-missing-error]]}
  [{:keys [:seon.store/store :seon.store/branch] held :seon.cluster.registry/branches}]
  (or (when (contains? (or held (branches store)) branch)
        (get-in (head-record (konserve-store store) branch)
                [:meta :datahike/commit-id]))
      (error.refusal/diagnostic
       (java.util.Date.) :seon.store/branch 'seon.cluster.registry/branch-commit-id
       {:seon.error/message (str "The store at " (:seon.store/dir store)
                                 " holds no branch " branch ".")
        ::branch-missing true
        :seon.store/dir (:seon.store/dir store)
        :seon.store/branch branch})))

(defn head-guard-tx
  "Transaction data that refuses, inside the writer, unless `branch` still
  names `expected-commit-id` in the transacting database's physical store.

  A write derived from one published commit therefore never commits after
  another publication moved the head; the transactor orders the rest. The
  refusal carries Datahike's own `:stale-branch-head` data
  (`reference-code/datahike/src/datahike/versioning.cljc:374`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.source/expected-head] [:= []]]}
  [database {branch :seon.source/branch expected :seon.source/expected-commit-id}]
  (let [current (get-in (head-record (:store database) branch) [:meta :datahike/commit-id])]
    (when-not (= expected current)
      (throw (ex-info "Branch head changed before this derived write."
                      {:type :stale-branch-head
                       :branch branch
                       :expected-current-commit expected
                       :current-commit current})))
    []))

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
  ;; the store value's own opened configuration: a file store's, or an
  ;; overlay's (`store/open-overlay!`), whose connection ids differ
  (let [configuration (assoc (:config @(:seon.store/connection-object store))
                             :branch branch)]
    (connections/active-connection
     (datahike.store/connection-id configuration))))

(defn on-branch
  "Call `f` with a connection to `branch` of `store` for this one action, holding its own reference
  (`seon.cluster.store/acquire-branch!`) until `f` returns, so another holder's release never closes it under `f`."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch [:=> [:cat :seon.db/connection] :seon.schema/value]]
                  :seon.schema/value]}
  [store branch f]
  (let [acquired (store/acquire-branch! store branch)]
    (try (f acquired) (finally (store/release-branch! acquired)))))

(defn apply-config-to-branches!
  "`seon.config/apply!` the document at `path` on each of `branches` of `request`'s store, each held for its one
  action (`on-branch`) whether or not a graph runs there: a branch reads its own config row, so root applies
  explicitly per branch and a merge never carries config. Answers each branch's apply result.

  ## Example

  ```clojure
  (seon.cluster.registry/apply-config-to-branches!
   {:seon.db/connection connection :seon.boot/cluster-name \"default\" :seon.store/store store}
   [:chain-f1f6dc8e6652] \"cap.edn\")
  ```"
  {:malli/schema [:=> [:cat [:map [:seon.store/store :seon.store/store]
                                  [:seon.db/connection :seon.db/connection]
                                  [:seon.boot/cluster-name :seon.boot/cluster-name]]
                       [:sequential :seon.store/branch] :seon.config/path]
                  [:vector [:map [:seon.store/branch :seon.store/branch]
                            [:seon.reconcile/result :seon.reconcile/result]]]]}
  [request branches path]
  (mapv (fn [branch]
          {:seon.store/branch branch
           :seon.reconcile/result
           (on-branch (:seon.store/store request) branch
                      (fn [connection]
                        (db/with-branch {:seon.db/connection connection}
                          #(config/apply! (assoc (select-keys request [:seon.boot/cluster-name])
                                                 :seon.db/connection connection)
                                          path))))})
        branches))

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
  the store's branches — including when Datahike's own
  `:branch-already-exists` (`versioning.cljc:233-235`) refuses a lost
  race, which is idempotence, not a failure.
  Refuses `::source-absent` (`::from` names no branch or commit)."
  {:malli/schema [:=> [:cat :seon.cluster.registry/branch-request]
                  :seon.cluster.registry/branch-result]}
  [{:keys [:seon.store/store :seon.store/branch]
    source :seon.cluster.registry/from
    reachability-permit :datahike.gc-guard/reachability-permit}]
  (if (contains? (branches store) branch)
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
          ; caller asked for is in the store's branches (versioning.cljc:233-235)
          :branch-already-exists
          {:seon.store/branch branch :seon.cluster/created? false}

          (:branch-does-not-exist :commit-not-found)
          (refuse! ::source-absent
                   (str "no branch or commit " source " to branch from")
                   {::dir (:seon.store/dir store)
                    :seon.cluster.registry/from source
                    :seon.store/branch branch})

          (throw failure))))))

(defn fork-entity
  "The cluster row a fork records: its name, its config row and the exact source
  commit its program descends from, which a test request's world branches from."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name :seon.source/commit-id] :map]}
  [cluster-name source-commit]
  {:seon.cluster/name cluster-name
   :seon.cluster/config [:seon.config/cluster cluster-name]
   :seon.source/commit-id source-commit})

(defn- record-fork!
  "Record the exact source commit with the new branch's cluster entity."
  {:malli/schema [:=> [:cat :seon.store/store :seon.boot/cluster-name
                       :seon.source/commit-id [:or :nil :map]] :nil]}
  [store cluster-name source-commit supplied-projection]
  (let [connection (store/open-branch! store (cluster-branch cluster-name))]
    (try
      (let [database (db/db connection)
            projection (or supplied-projection (db/carried-projection database)
                           (schema/handed-projection)
                           (schema/projection-from-database database))]
        ;; The new branch's own write, never the caller's custody.
        (db/with-branch
         {:seon.db/connection connection}
         #(schema/call-with-projection
           projection
           (fn []
             (let [compiled (config/compile-manifest {:seon.boot/cluster-name cluster-name})
                   result (db/transact!
                           connection
                           {:tx-data [(:seon.config/desired-row compiled)
                                      (fork-entity cluster-name source-commit)]})]
               (when-not (:db-after result)
                 (throw (ex-info "The fork's source commit could not be recorded." result))))))))
      (finally (store/release-branch! connection))))
  nil)

(defn fork-of
  "`branch`'s own fork entity in `database`, or nil when it holds none. Of the
  fork entities naming `branch`, its own is the one asserted last: an earlier
  one came down from a retired branch of that name."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch] [:maybe :seon.branch/fork]]}
  [database branch]
  ;; Its native-typed datoms, read undecoded: a pull derives the value's declarations first, and every
  ;; declaration a branch-side reader documents reads its fork entity.
  (when-let [named (seq (db/datoms database :avet :seon.branch/name branch))]
    (into {} (keep (fn [[_ attribute value]]
                     (when (#{:seon.branch/fork-id :seon.branch/name :seon.branch/target :seon.branch/base-t} attribute)
                       [attribute value])))
          (db/undecoded-datoms database :eavt [(:e (apply max-key :tx named))]))))

(defn branch-fork-entity
  "The fork entity of `branch`, forked from the branch `parent` toward
  `target` with `database` its value at the fork: a fresh fork id, the target
  and its base-t attribute. Forked from its target, the base-t is the fork's
  t. Forked from a work branch toward that branch's own target (a nested
  fork), it inherits that branch's own base-t: a t in the history the fork inherited, so a walk of the fork's own
  lineage from it never reads another branch's counter. A parent
  with no fork entity toward `target` gives no base and refuses by name."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch :seon.store/branch :seon.store/branch
                       :seon.db/database-value]
                  [:or :seon.branch/fork :seon.error/value]]}
  [_store parent target branch database]
  (let [inherited (when (not= parent target) (fork-of database parent))]
    (cond
      (= parent target)
      {:seon.branch/fork-id (id/id) :seon.branch/name branch :seon.branch/target target
       :seon.branch/base-t (db/basis-t database)}

      (= target (:seon.branch/target inherited))
      {:seon.branch/fork-id (id/id) :seon.branch/name branch :seon.branch/target target
       :seon.branch/base-t (:seon.branch/base-t inherited)}

      :else
      (error.refusal/diagnostic
       (java.util.Date.) :seon.store/branch 'seon.cluster.registry/branch-fork-entity
       {:seon.error/message "The parent branch has no fork entity toward this target, so the fork would have no merge base."
        :seon.error/expected target :seon.error/offending parent :seon.store/branch branch}))))

(defn base-branch
  "The branches entry holding a work branch's immutable merge base."
  {:malli/schema [:=> [:cat :seon.store/branch] :seon.store/branch]}
  [branch]
  (keyword "base" (str (when-let [space (namespace branch)] (str space "/")) (name branch))))

(defn base-commit-id
  "The commit retained by a work branch's base ref, or nil when the store's
  branches (`held`, the caller's basis, else read now) hold no such ref:
  `delete-branch!` leaves a deleted ref's head record until collection, and
  that record is no merge base."
  {:malli/schema [:function
                  [:=> [:cat :seon.db/database-value :seon.store/branch] [:maybe :seon.source/commit-id]]
                  [:=> [:cat :seon.db/database-value :seon.cluster.registry/branches :seon.store/branch]
                   [:maybe :seon.source/commit-id]]]}
  ([database branch] (base-commit-id database (held-branches database) branch))
  ([database held branch]
   (let [base (base-branch branch)]
     (when (contains? held base)
       (get-in (head-record (:store (db/schema-database database)) base) [:meta :datahike/commit-id])))))

(defn advance-base!
  "Move a work branch's retained base to the immutable value just merged."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/branch] :seon.source/commit-id]}
  [database branch]
  (d/force-branch! database (base-branch branch) (d/parent-commit-ids database)))

(defn fork!
  "Create a work branch as `branch!` does and, when this call created it,
  retain its immutable base commit in `base-branch` and write its fork entity
  (`branch-fork-entity`): its merge base
  toward `:seon.branch/target` (default: the parent) until a merge advances
  it. The parent is `::from` when it names a branch, else the branch of the
  source value `:seon.db/db`. A commit with no source value refuses before
  creating anything; a fork with no base toward its target, or a refused
  write, retires the new branch and answers the error value."
  {:malli/schema [:=> [:cat :seon.cluster.registry/branch-request]
                  [:or :seon.cluster.registry/branch-result :seon.error/value]]}
  [{store :seon.store/store branch :seon.store/branch from :seon.cluster.registry/from
    source-value :seon.db/db target :seon.branch/target :as request}]
  (let [parent (if (keyword? from) from (get-in source-value [:config :branch]))
        result (if parent
                 (branch! (dissoc request :seon.branch/target))
                 (error.refusal/diagnostic
                  (java.util.Date.) :seon.store/branch 'seon.cluster.registry/fork!
                  {:seon.error/message "A fork from a commit names its source value (:seon.db/db), the branch it forks from."
                   :seon.error/expected :seon.db/db :seon.store/branch branch}))]
    (if-not (:seon.cluster/created? result)
      result
      (let [connection (store/open-branch! store branch)
            written
            (try
              (let [database (db/db connection)
                    fork (branch-fork-entity store parent (or target parent) branch database)
                    projection (or (db/carried-projection database) (schema/handed-projection)
                                   (schema/projection-from-database database))]
                (if (:seon.error/at fork)
                  fork
                  (let [retained (branch! {:seon.store/store store :seon.store/branch (base-branch branch)
                                           :seon.cluster.registry/from
                                           (if (= parent (or target parent))
                                             (db/commit-id database)
                                             (base-commit-id database parent))})]
                    (if (:seon.error/at retained)
                      retained
                      (db/with-branch
                       {:seon.db/connection connection}
                       #(schema/call-with-projection
                         projection
                         (fn [] (db/transact! connection {:tx-data [fork]}))))))))
              (finally (store/release-branch! connection)))]
        (if (:db-after written)
          result
          (do (retire-branch! {:seon.store/store store :seon.store/branch branch})
              written))))))

(defn ensure-cluster!
  "Ensure `:cluster-<name>` exists at the requested source commit.
  Idempotent by the store's branches: a second call returns
  `::created? false` and writes nothing. Concurrent calls from N
  threads produce N branches and zero orphans — the fork's serialized
  branches mutation is what makes that true, and it is a standing
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
        ;; A branch the store does not hold yet is created: no expected head.
        current-commit (let [head (branch-commit-id {:seon.store/store store
                                                     :seon.store/branch branch})]
                         (when-not (::branch-missing head) head))]
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
  "Remove a branch and its retained base ref from the store's branches. Data survives until GC.
  `delete-branch!` removes the branch entry only
  (`versioning.cljc:279-320`); the bytes go when `collect!` runs, and a
  branch absent from the store's branches is ALREADY DONE — Datahike's
  `:branch-does-not-exist` is the success path for a re-run, not an
  error (the mid-delete crash row).
  Translates Datahike's `:branch-has-active-connection` to
  `::cluster-connected` and `:cannot-delete-main-db-branch` to
  `::cannot-retire-main`. Descendant branches do
  not prevent retirement: each remaining branch independently
  roots its head and parent commits during collection
  (`gc.cljc:22-81`), so deleting an ancestor's branch name cannot make
  a descendant lose data."
  {:malli/schema [:=> [:cat :seon.cluster.registry/retire-request] :nil]}
  [{:keys [:seon.store/store :seon.store/branch]}]
  (try
    (d/delete-branch! (:seon.store/connection-object store) branch)
    (catch clojure.lang.ExceptionInfo failure
      (case (:type (ex-data failure))
        ; the branches are the fact: a branch already gone is already done
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
  (when (contains? (branches store) (base-branch branch))
    (retire-branch! {:seon.store/store store :seon.store/branch (base-branch branch)}))
  nil)

(defn- cluster-claims
  "The branches `branch` claims when it is a cluster branch, else nil: itself,
  the branch of every agent row on it whose admission is open and the branch of
  every chain fact on it (`:seon.chain/branch`), held chains included.
  A cluster branch is one whose own `:seon.cluster/name` row names it through
  `cluster.branch/cluster-branch`. A fork inherits its cluster's name row, so
  the comparison, never the branch's spelling, tells a cluster from its forks."
  {:malli/schema [:=> [:cat :seon.store/connection-object :seon.store/branch]
                  [:maybe [:set :seon.store/branch]]]}
  [connection branch]
  (let [db (d/branch-as-db connection branch)]
    (try
      ;; a query, not an index read: `:db` and `:current-src` install no
      ;; `:seon.cluster/name`, and `d/datoms` refuses an unknown attribute
      (when (some #(= branch (cluster.branch/cluster-branch %))
                  (d/q '[:find [?name ...] :where [_ :seon.cluster/name ?name]] db))
        (into #{branch}
              (d/q '[:find [?branch ...]
                     :where (or-join [?branch]
                                 (and [?agent :seon.agent/branch ?branch]
                                      (not [?agent :seon.agent/admission-closed-tx]))
                                 [_ :seon.chain/branch ?branch])]
                   db)))
      (finally (d/release-materialized-db db)))))

(defn retire-unclaimed!
  "Retire every branch no fact claims and no connection here holds.

  Answers the retired set.

  An execution-owned branch is otherwise unlinked only by its owner's release,
  so a branch whose process died stays a collection root forever. Claims are
  `claimed` (the caller's declared branches, e.g. `:current-src`), the main
  `:db`, every cluster branch and each open agent row's branch on it
  (`cluster-claims`). Boot calls this before it acquires any branch: a merge
  candidate, a checkpoint or an MCP branch never hosted is not claimed by a
  fact and does not survive a restart."
  {:malli/schema [:=> [:cat :seon.store/store [:set :seon.store/branch]]
                  :seon.cluster.registry/retired]}
  [store claimed]
  (let [connection (:seon.store/connection-object store)
        branches (branches store)
        claims (into (conj claimed :db) (mapcat #(cluster-claims connection %)) branches)
        held (into claims (filter #(branch-connected? store %)) branches)
        held (into held (map base-branch) held)
        unclaimed (into #{} (remove held) branches)]
    (run! #(retire-branch! {:seon.store/store store :seon.store/branch %}) unclaimed)
    unclaimed))

(defn- attributes-valued-as
  "The schema keys of `db` whose declared form resolves to `target`, the
  target's own key aside (its form is the base type it refines)."
  {:malli/schema [:=> [:cat :seon.db/database-value :qualified-keyword] [:vector :qualified-keyword]]}
  [db target]
  (let [forms
        (into {}
              (map (fn [[schema-key serialized-form]]
                     [schema-key (edn/read-string serialized-form)]))
              (d/q '[:find ?schema-key ?form
                     :where
                     [?schema :seon.schema/key ?schema-key]
                     [?schema :seon.schema/form ?form]]
                   db))
        ;; An attribute's VALUE is a `target` when its form resolves, through
        ;; schema references, an `:and` with a target child, or an `:or` whose
        ;; every alternative is one, to `target`. A map, collection or entity
        ;; schema that merely contains a target member is not: its stored
        ;; values are entity ids (component refs), never the target's values.
        valued-as?
        (fn valued-as? [form seen]
          (cond
            (= target form) true
            (and (keyword? form)
                 (not (contains? seen form)))
            (boolean
             (when-let [referenced (get forms form)]
               (valued-as? referenced (conj seen form))))
            (and (vector? form) (#{:and :merge} (first form)))
            (boolean (some #(valued-as? % seen) (remove map? (rest form))))
            (and (vector? form) (= :or (first form)))
            (let [alternatives (remove map? (rest form))]
              (boolean (and (seq alternatives)
                            (every? #(valued-as? % seen) alternatives))))
            :else false))]
    (into []
          (keep (fn [[attribute form]]
                  (when (and (not= attribute target)
                             (valued-as? form #{attribute}))
                    attribute)))
          forms)))

(defn referenced-blobs
  "Derive blob references across the supplied store branches.

  Walk the current and (when requested) temporal AEVT trees, visiting each
  shared subtree once per digest-attribute set. A buffered child is identified
  by BOTH its stored address and pending diff; Branch.child applies that diff.
  Attribute ranges prune unrelated subtrees. The fold is local to this call,
  so neither a moving branch nor a changed schema can reuse stale references.
  Collection callers hold the store's exclusive reachability permit."
  {:malli/schema
   [:=> [:cat :seon.db/connection [:set :seon.store/branch] :boolean]
    [:set :seon.blob/digest]]}
  [connection branches include-history?]
  (letfn [(walk [^ANode node storage attributes state]
            (if (instance? Branch node)
              (let [^Branch node node
                    bounds (.keys node)
                    slots (.slots node)]
                (reduce
                 (fn [[seen digests :as state] i]
                   (let [lower (when (pos? i) (:a (nth bounds (dec i))))
                         upper (:a (nth bounds i))
                         address (.address node i)
                         slot (when slots (aget ^objects slots i))
                         diff (when slot (.-diff ^Slot slot))
                         node-key [attributes address diff]]
                     (if (or (and address (contains? seen node-key))
                             (not-any? #(and (or (nil? lower) (not (neg? (compare % lower))))
                                             (not (pos? (compare % upper)))) attributes))
                       state
                       (walk (.child node storage i) storage attributes
                             [(cond-> seen address (conj node-key)) digests]))))
                 state (range (.len node))))
              (update state 1 into
                      (comp (filter #(contains? attributes (:a %))) (map :v))
                      (.keys node))))
          (index-blobs [state index attributes]
            (let [^PersistentSortedSet index index
                  address (.-_address index)
                  node-key [attributes address nil]]
              (if (and address (contains? (first state) node-key))
                state
                (walk (.root index) (.-_storage index) attributes
                      (update state 0 #(cond-> % address (conj node-key)))))))]
    (second
     (reduce
      (fn [state branch]
        (let [database (d/branch-as-db connection branch)]
          (try
            (let [attributes (set (attributes-valued-as database :seon.blob/digest))
                  attributes (if (get-in database [:config :attribute-refs?])
                               (into #{} (keep #(dbi/-ref-for database %)) attributes)
                               attributes)
                  indices (cond-> [(:aevt database)]
                            (and include-history? (get-in database [:config :keep-history?]))
                            (conj (:temporal-aevt database)))]
              (reduce #(index-blobs %1 %2 attributes) state indices))
            (finally (d/release-materialized-db database)))))
      [#{} #{}] branches))))

(defn- branch-heads
  {:malli/schema [:=> [:cat :seon.store/store [:seqable :seon.store/branch]] [:vector [:map [:seon.store/branch :seon.store/branch] [:seon.source/commit-id :seon.source/commit-id]]]]}
  [store branches]
  (mapv
   (fn [branch]
     (let [commit-id (branch-commit-id {:seon.store/store store
                                        :seon.store/branch branch})]
       (when (:seon.error/at commit-id)
         (throw (ex-info (:seon.error/message commit-id) commit-id)))
       {:seon.store/branch branch
        :seon.source/commit-id commit-id}))
   (sort-by str branches)))

(defn- elapsed-ms
  [started-ns]
  (quot (- (System/nanoTime) started-ns) 1000000))

(def plain-cutoff
  "The `remove-before` of the plain collection: the epoch, so Datahike marks
  every branch's whole commit ancestry (`gc.cljc` `reachable-in-branch`
  keeps every commit newer than it) and sweeps only what no branch
  reaches: an unlinked branch's commits and nodes. Every commit on a live
  branch's ancestry is marked, so a value held on a branch that stays live
  survives it. A value on a branch retired while held, or a commit no live
  branch reaches, does not. Retention pruning is a separate policy
  (`retention-cutoff`, `retained-before`), which keeps a held head for at
  least one snapshot window."
  (java.util.Date. 0))

(defn- named-commits
  "The commits `database`'s current facts name: the values of
  `:seon.source/commit-id` and of every attribute declared as one. A test
  world reads its cluster's adopted commit by id (`seon.test/world-base`), so a
  named commit is read whether or not a branch head still holds it."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:set :seon.source/commit-id]]}
  [database]
  (into #{}
        (mapcat #(d/q '[:find [?commit ...] :in $ ?a :where [_ ?a ?commit]] database %))
        (conj (attributes-valued-as database :seon.source/commit-id) :seon.source/commit-id)))

(defn retained-before
  "The latest cutoff that keeps every commit the facts name.

  It is `remove-before`, or the instant just before the oldest stored commit
  a branch head's facts name when that is earlier. Datahike marks every
  commit newer than `remove-before` along each branch's ancestry, so a commit
  the facts still name (`named-commits`) is never pruned by a retention
  window (`retention-cutoff`). A work branch's merge base with its target
  (`seon.cluster.source/branch-merge-base`) counts as named, so the commit and
  every commit the graph walks to reach it survive, and the merge base stays
  derivable. A current branch head is always marked, so naming one moves
  nothing."
  {:malli/schema [:=> [:cat :seon.store/store :inst] :inst]}
  [store remove-before]
  (let [connection (:seon.store/connection-object store)
        konserve (konserve-store store)
        heads (into #{} (map :seon.source/commit-id) (branch-heads store (branches store)))
        named (into #{}
                    (comp (mapcat (fn [branch]
                                    (let [database (d/branch-as-db connection branch)
                                          ;; a work branch's merge base with its target: Datahike's commit graph,
                                          ;; walked through every commit between it and both heads
                                          base ((requiring-resolve 'seon.cluster.source/branch-merge-base) database)]
                                      (try (cond-> (named-commits database) (uuid? base) (conj base))
                                           (finally (d/release-materialized-db database))))))
                          (remove heads))
                    (branches store))
        instants (keep #(let [{{:datahike/keys [updated-at created-at]} :meta} (head-record konserve %)]
                          (some-> (or updated-at created-at) .getTime dec))
                       named)]
    (if (seq instants)
      (java.util.Date. (min (.getTime ^java.util.Date remove-before) (apply min instants)))
      remove-before)))

(defn- collect-and-inventory!
  "Use Datahike's reachability and konserve's candidate selection for either mode."
  {:malli/schema [:=> [:cat :seon.store/store :inst :map]
                  :seon.cluster.registry/inventory]}
  [store remove-before options]
  (let [started-ns (System/nanoTime)
        connection (:seon.store/connection-object store)
        heads (atom [])
        dry-run? (true? (:seon.operator.collect/dry-run? options))
        {candidates :konserve.gc/candidates observed :konserve.gc/observed-keys}
        @(d/gc-storage
          connection remove-before
          (-> options
              (assoc :datahike.gc/reachable-extension
                     (fn [{:datahike.gc/keys [branches]}]
                       (reset! heads (branch-heads store branches))
                       (referenced-blobs connection branches true)))
              (assoc-in [:datahike.gc/sweep-opts :konserve.gc/dry-run?]
                        dry-run?)
              (assoc-in [:datahike.gc/sweep-opts :konserve.gc/report?] true)))]
    {:seon.cluster.registry/key-count (count observed)
     :seon.cluster.registry/observed-keys observed
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
        heads (branch-heads store (branches store))
        observations
        (mapv
         (fn [{commit-id :seon.source/commit-id branch :seon.store/branch}]
           (let [record (head-record (konserve-store store) commit-id)
                 database (d/commit-as-db connection commit-id)]
             (try
               [(or (get-in record [:meta :datahike/updated-at])
                    (get-in record [:meta :datahike/created-at]))
                (mapv #(assoc (d/pull database
                                      [:seon.config/cluster :seon.config.db/snapshot-window-ms] %)
                              :seon.store/branch branch)
                      (d/q '[:find [?e ...] :where [?e :seon.config/cluster]] database))]
               (finally (d/release-materialized-db database)))))
         heads)
        policies (mapcat second observations)
        undeclared (remove :seon.config.db/snapshot-window-ms policies)]
    (when (or (empty? policies) (seq undeclared))
      (throw (ex-info (str "Every cluster configuration must declare snapshot retention before implicit collection."
                           (if (seq undeclared)
                             (str " Undeclared: "
                                  (str/join ", " (map #(str (:seon.store/branch %) " cluster "
                                                            (pr-str (:seon.config/cluster %)))
                                                      undeclared))
                                  ". Fix: apply a configuration declaring :seon.config.db/snapshot-window-ms to that cluster, or unlink its branch.")
                             " No branch holds a cluster configuration."))
                      {:seon.config/error-key :seon.config.db/snapshot-window-ms
                       :seon.config/rule :seon.config/required-absent
                       :seon.config/undeclared (mapv #(select-keys % [:seon.store/branch :seon.config/cluster])
                                                     undeclared)})))
    (java.util.Date.
     (- (apply max (map #(.getTime ^java.util.Date (first %)) observations))
        (apply max (map :seon.config.db/snapshot-window-ms policies))))))

(defn collect!
  "Collect or inventory this store's unreachable objects.
  One owner per store — the process, never a cluster (§0.6 condition
  3) — and it runs where the writers are, which is this JVM
  (`gc.cljc:105-115`). Whole-store by nature: the mark is a union over
  every branch, so the cost scales with total data and the
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
