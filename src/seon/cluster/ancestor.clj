(ns seon.cluster.ancestor
  "The ancestor: one branch every cluster is born from, built once.

  DRAFT CONTRACT LAYER FOR ORCHESTRATOR SEAL (drafted 2026-07-27 — the
  B2 rung, grounded in research/b2-plan-2026-07-27.md §0, §5.2-§5.3 and
  §9). Nothing here is implemented: every body throws
  `awaits implementation`. Once sealed, the implementation lane fills
  the stubs until test/seon/cluster/ancestor_test.clj is green and may
  not loosen a schema or a test. Friction is reported, never resolved
  by weakening.

  The model:

  - ONE deliberate build indexes all code into the ancestor; a fresh
    cluster is a near-instant BRANCH of it, never a re-index (owner
    ruling 2026-07-27, plan README). The ancestor is a branch and not a
    directory, so its bytes are stored exactly once for every
    descendant — structural sharing on any backend.
  - ANCESTOR IDENTITY IS A DIGEST OVER THE DECLARED ROOTS, not over a
    build artifact and not over a cluster name (b2-plan §5.2 retires
    both halves of State A's `(application-digest, cluster-name)` key).
    `digest` is a pure function of file bytes: it is true in dev with no
    artifact chain, and the branch name carries it, so the roster alone
    answers \"which ancestors exist\".
  - THE ROSTER IS THE WHOLE CACHE. `ensure!` reads it; when
    `:ancestor-<digest>` is present the call is over — no connect, no
    comparison, no rebuild.
  - THE POPULATION IS INJECTED, AS DATA. `:seon.ancestor/populate` is a
    QUALIFIED SYMBOL, resolved with `requiring-resolve` and invoked with
    a live connection to the scratch branch; it transacts whatever the
    caller declares the ancestor to contain — schema facts today
    (`seon.schema.edn/load!` plus activation), program-graph facts when
    N5's indexer exists. A symbol keeps the request an ordinary
    printable value (no opaque function type on a contract boundary),
    so the fork mechanics do not wait on the producer and a suite can
    build a two-row ancestor by naming its own var.
  - PUBLISH BY RENAME-AT-END. The build runs on a scratch
    `:building-<pid>-<start-millis>-<uuid>` branch and only then
    branches `:ancestor-<digest>` from the finished scratch head and
    retires the scratch. `:ancestor-<digest>` therefore only ever
    appears COMPLETE — every crash row below depends on that
    discipline, and a partial ancestor under the real name would be
    undetectable.
  - The scratch name carries its owner's (pid, start-instant) because a
    live build and an abandoned one are the same durable state
    otherwise. A dead owner's scratch is reclaimed; a live owner's
    scratch refuses `::build-in-progress`.
  - EVERY branch operation goes through `seon.cluster.registry`, the one
    branch-lifecycle owner (b2-plan §0.6 condition 1). This namespace
    never calls `datahike.api/branch!` or `delete-branch!`.
  - Refusals are loud ex-info
    `{:seon.error/kind ::refused ::rule <which>}`, matching B0/B1
    (`src/seon/cluster/store.clj:161-167`).

  Crash walk (kill -9 at any point):

  - mid build, BEFORE the scratch branch reaches the roster: a head
    blob nothing points at. Invisible; GC sweeps it; `ensure!`
    rebuilds;
  - mid build, AFTER `:building-<…>` is in the roster: a partial
    ancestor under a scratch name. The next `ensure!` finds its owning
    process dead, retires it, and rebuilds. The `:ancestor-<digest>`
    name never appeared;
  - between the publishing `branch!`'s head write and its `:branches`
    update (`versioning.cljc:255-257`): an orphan head blob, not in the
    roster → GC sweeps it and `ensure!` re-runs;
  - after `:ancestor-<digest>` lands in the roster: a complete
    ancestor. Nothing to do — `ensure!` returns `::built? false` and
    does zero work;
  - while a scratch build is live in ANOTHER process: `ensure!` refuses
    `::build-in-progress` rather than racing a second build to the same
    name."
  (:require [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/ancestor.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Pure derivations
;;; ---------------------------------------------------------------------------

(defn digest
  "The ancestor digest of the declared source roots.
  SHA-256 over the sorted sequence of `[path, sha256(bytes)]` for every
  `.clj`, `.cljc`, and `.edn` file under each root — the schema EDN is
  inside `src/`, so one rule covers both halves of b2-plan §5.2. Pure,
  order-free (the roots are sorted, and each root's files are sorted by
  their path relative to it), and spelling-free (each root is
  canonicalized once, so `x` and `./x` are one root).
  Refuses `::root-absent` when a declared root is not a directory: an
  ancestor keyed by a digest of nothing is the one failure this
  function must never produce silently."
  {:malli/schema [:=> [:cat :seon.ancestor/digest-request]
                  :seon.ancestor/digest]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `digest})))

(defn ancestor-branch
  "The ONE branch name for a digest: `:ancestor-<digest>`.
  One derivation — the digest is discoverable from the roster alone."
  {:malli/schema [:=> [:cat :seon.ancestor/digest] :seon.ancestor/branch]}
  [digest]
  (throw (ex-info "awaits implementation" {::fn `ancestor-branch})))

;;; ---------------------------------------------------------------------------
;;; The build
;;; ---------------------------------------------------------------------------

(defn ensure!
  "Ensure `:ancestor-<digest>` exists on the store; build it if absent.
  Present in the roster → `{::branch b ::built? false}` and ZERO work:
  no connection, no population call, no transaction.
  Absent → branch a scratch `:building-<pid>-<start-millis>-<uuid>` off
  `:db`, connect to it through `seon.cluster.store/open-branch!`,
  `requiring-resolve` `:seon.ancestor/populate` and invoke it with
  `{:seon.store/branch-connection conn
  :seon.ancestor/digest d}`, transact the ancestor's own two facts
  (`:seon.ancestor/digest`, `:seon.ancestor/built-at`) with their
  attribute declarations DERIVED from the registered schema through
  `seon.schema.datahike/malli->datahike-schema` — never hand-written —
  release the connection, publish `:ancestor-<digest>` from the scratch
  head through `seon.cluster.registry/branch!`, and retire the scratch.
  Returns `{::branch b ::built? true}`.
  A `:building-*` branch whose owning process is DEAD is retired first
  and the build proceeds; one whose owner is ALIVE refuses
  `::build-in-progress` with that branch named — two builds of one
  digest must never race.
  A population function that throws leaves the scratch branch behind
  and propagates: the ancestor name did not appear, so the next
  `ensure!` reclaims and retries. A `:seon.ancestor/populate` symbol
  that does not resolve refuses `::populate-unresolvable` BEFORE any
  branch is created."
  {:malli/schema [:=> [:cat :seon.ancestor/ensure-request]
                  :seon.ancestor/ensured]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `ensure!})))
