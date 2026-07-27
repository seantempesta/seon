(ns seon.cluster.registry
  "The registry: the ONE owner of branch lifecycle on a physical store.

  DRAFT CONTRACT LAYER FOR ORCHESTRATOR SEAL (drafted 2026-07-27 — the
  B2 rung, grounded in research/b2-plan-2026-07-27.md §0, §5 and §9;
  every rule below carries file:line evidence there or in a committed
  probe). Nothing here is implemented: every body throws
  `awaits implementation`. Once the orchestrator seals it the
  implementation lane fills the stubs until
  test/seon/cluster/registry_test.clj is green, and may not loosen a
  schema or a test. Friction is reported, never resolved by weakening.

  The model — BRANCH-PER-CLUSTER (b2-plan §0 verdict, owner-adopted):

  - One physical store per process root; a cluster is a BRANCH of it.
    Branch-off is 17 ms and one blob (b2-plan §0.5), so the ancestor's
    bytes are stored ONCE for every cluster that descends from it, on
    any backend.
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
  (:require [datahike.api :as d]
            [seon.cluster.store :as store]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/registry.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Pure derivations
;;; ---------------------------------------------------------------------------

(defn cluster-branch
  "The ONE branch name for a cluster: `:cluster-<name>`.
  One derivation — no other code builds this keyword, so a cluster's
  name and its branch can never disagree."
  {:malli/schema [:=> [:cat :seon.boot/cluster-name] :seon.store/branch]}
  [cluster-name]
  (throw (ex-info "awaits implementation" {::fn `cluster-branch})))

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
  (throw (ex-info "awaits implementation" {::fn `roster})))

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
  [request]
  (throw (ex-info "awaits implementation" {::fn `branch!})))

(defn ensure-cluster!
  "Ensure `:cluster-<name>` exists, branching from the ancestor if absent.
  Idempotent by the roster: a second call returns
  `::created? false` and writes nothing. Concurrent calls from N
  threads produce N branches and zero orphans — the fork's serialized
  roster mutation is what makes that true, and it is a standing
  regression, not an assumption.
  Refuses `::ancestor-absent` (`:seon.ancestor/branch` is not in the
  roster — a cluster is never silently branched from `:db`)."
  {:malli/schema [:=> [:cat :seon.cluster.registry/cluster-request]
                  :seon.cluster.registry/branch-result]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `ensure-cluster!})))

(defn reset-cluster!
  "Return a cluster to ancestor state: retire its branch, branch it again.
  This is L18 exactly — reset to current code and pages, never migrate
  — and it is `delete-branch!` + `branch!`, NEVER `delete-database`
  (b2-plan §0.6 condition 2). The old tail becomes unreachable and the
  next `collect!` reclaims it; siblings and the ancestor are untouched
  by construction (§0.7).
  Always returns `::created? true`: the branch after the call is new.
  Refuses `::cluster-connected` (this process still holds a connection
  to that branch — Datahike refuses too at
  `versioning.cljc:279-288`, but we refuse EARLIER and by name) and
  `::ancestor-absent`."
  {:malli/schema [:=> [:cat :seon.cluster.registry/cluster-request]
                  :seon.cluster.registry/branch-result]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `reset-cluster!})))

(defn retire-branch!
  "Remove one branch from the roster. Idempotent; data survives until GC.
  `delete-branch!` removes the roster entry only
  (`versioning.cljc:261-289`); the bytes go when `collect!` runs, and a
  branch absent from the roster is ALREADY DONE — Datahike's
  `:branch-does-not-exist` is the success path for a re-run, not an
  error (the mid-delete crash row).
  Refuses `::cluster-connected` (a live connection to that branch in
  this process); `::cannot-retire-main` (`:db` — the genesis branch is
  the store, and Datahike refuses it too); and
  `::cannot-retire-live-ancestor`: another roster branch STRICTLY
  descends from this one.
  Descent is read from the commit graph, never from a naming
  convention (L17): branch `o` strictly descends from `b` when `b`'s
  head commit id appears in `o`'s head's PARENT ancestry — the
  `[:meta :datahike/parents]` walk `versioning.cljc/branch-history`
  performs — excluding `o`'s own head commit id.
  STRICTLY is load-bearing and was found by probe. A freshly branched
  cluster SHARES its source's head commit id
  (`tmp/b2-draft-probe/head_config_probe.clj`: `:cluster-a` and
  `:ancestor-x` reported one commit id), so a non-strict test is
  SYMMETRIC and would refuse to retire an unwritten cluster whose
  unwritten sibling shares that head — breaking `reset-cluster!` for
  every never-used cluster. The boundary case it admits is deliberate
  and costs nothing: retiring an ancestor whose only descendant has
  written nothing is allowed, because that descendant's own roster
  entry seeds its head unconditionally in the GC mark
  (`gc.cljc:26,60-70`) and the two branches are byte-identical anyway."
  {:malli/schema [:=> [:cat :seon.cluster.registry/retire-request] :nil]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `retire-branch!})))

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
  (throw (ex-info "awaits implementation" {::fn `collect!})))
