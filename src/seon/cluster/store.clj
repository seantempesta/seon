(ns seon.cluster.store
  "The store: one Datahike store per cluster, opened under the lifetime
  flock.

  CONTRACT LAYER (orchestrator-authored, 2026-07-27 — the B1 rung,
  grounded in research/datahike-multistore-2026-07-27.md; every rule
  below carries file:line evidence there). The schemas and function
  contracts are SEALED: the implementation lane fills the stub bodies
  until test/seon/cluster/store_test.clj is green and may not loosen a
  schema or a test. Friction is reported, never resolved by weakening.

  The model:

  - One store component per canonical physical store; a JVM may host N
    concurrently; nothing obtains \"the\" store from an ambient
    singleton — callers hold the store value `open-store!` returns.
  - Exactly ONE live write connection per physical store, and clusters
    never share a store (O2/L6: two JVMs writing one store silently
    destroyed 40/40 commits). Datahike's own serialization stops at the
    connection boundary — its process registry and Konserve's lock
    atoms do NOT span two JVMs — so the fence is OURS: one non-blocking
    exclusive flock on the store's lock file, acquired BEFORE existence
    check, creation, or connect, held until final release. The lock
    file lives BESIDE the store directory (never inside Konserve's key
    namespace, which enumerates its files).
  - Datahike runs itself: the `:self` writer is a serial loop per
    connection with its own transaction/commit queues. This namespace
    never builds a writer, never queues across stores — it opens,
    verifies, and releases.
  - Readiness is a COMPLETELY opened connection over a COMPLETELY
    initialized store. The first-create kill window is real: `:db` can
    exist while `:branches` is missing. `open-store!` detects that
    state and repairs by RECREATE — provably mid-genesis means nothing
    durable existed, and clusters always reset to current code and
    pages, never migrate.
  - Errors refuse loudly as ex-info {:seon.error/kind
    :seon.cluster.store/refused, ::rule <which>}: a foreign flock, a
    store that fails initialization verification after repair, an
    already-open store in this process.

  Crash walk (kill -9 at any point):
  - before the flock: nothing exists, next boot proceeds;
  - after flock, before create: an empty/partial store dir with no
    `:db` — recreate;
  - mid-create (`:db` present, `:branches` missing) — detected,
    recreate;
  - after create/connect: a complete store; the OS released the dead
    process's flock, reopen proceeds cleanly and committed datoms are
    all present (the child-process falsifier proves exactly this);
  - a live holder is NEVER displaced: the flock refusal is immediate
    and loud, no waiting, no takeover."
  (:require [datahike.api :as d]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(schema/register! :seon.store/dir [:string {:min 1}])
; the lock file BESIDE the store directory — derived, never configured
(schema/register! :seon.store/lock-file [:string {:min 1}])

(defn connection?
  "True for a live Datahike connection."
  [value]
  (some? (:conn (meta value))))

(defn file-lock?
  "True for a held java.nio.channels.FileLock."
  [value]
  (and (instance? java.nio.channels.FileLock value)
       (.isValid ^java.nio.channels.FileLock value)))

(schema/register-core-predicate! 'seon.cluster.store/connection?
                                 connection?)
(schema/register-core-predicate! 'seon.cluster.store/file-lock?
                                 file-lock?)

(schema/register!
 :seon.store/store
 [:map {:closed true}
  [:seon.store/dir :seon.store/dir]
  [:seon.store/lock-file :seon.store/lock-file]
  [:seon.store/connection [:fn 'seon.cluster.store/connection?]]
  [:seon.store/lock [:fn 'seon.cluster.store/file-lock?]]
  ; true when open-store! created (or recreated) the store this call
  [:seon.store/created? :boolean]])

;;; ---------------------------------------------------------------------------
;;; Pure derivations
;;; ---------------------------------------------------------------------------

(defn lock-file
  "The store's lock-file path.
  A sibling of the store directory (`<store-dir>.lock`) so it never enters Konserve's key namespace.
  One derivation — no other code builds this path."
  {:malli/schema [:=> [:cat :seon.store/dir] :seon.store/lock-file]}
  [store-dir]
  (throw (ex-info "awaits implementation" {::fn `lock-file})))

(defn datahike-configuration
  "The one Datahike configuration for a cluster store.
  `:file` backend at `store-dir`, `:self` writer, write-time schema flexibility. Pure
  data — the single place the configuration shape lives."
  {:malli/schema [:=> [:cat :seon.store/dir] [:map]]}
  [store-dir]
  (throw (ex-info "awaits implementation" {::fn `datahike-configuration})))

;;; ---------------------------------------------------------------------------
;;; Lifecycle
;;; ---------------------------------------------------------------------------

(defn open-store!
  "Open (creating if absent) the one store at `store-dir`, fenced.
  Order is the contract: acquire the non-blocking exclusive flock on
  `(lock-file store-dir)` FIRST — a held lock refuses immediately
  ({::rule ::held-elsewhere}, including a second open in this same
  process) — then existence-check, then create or verify: a store whose
  genesis is incomplete (`:db` present, `:branches` missing — the
  first-create kill window) is deleted and recreated; a complete store
  is connected and its main branch verified readable. Returns the store
  value; the flock descriptor stays held inside it until
  `release-store!`."
  {:malli/schema [:=> [:cat :seon.store/dir] :seon.store/store]}
  [store-dir]
  (throw (ex-info "awaits implementation" {::fn `open-store!})))

(defn release-store!
  "Release the store: Datahike release first, then the flock.
  Idempotent — releasing a released store is a no-op returning nil.
  After release the same process (or any other) may open the store
  again."
  {:malli/schema [:=> [:cat :seon.store/store] :nil]}
  [store]
  (throw (ex-info "awaits implementation" {::fn `release-store!})))
