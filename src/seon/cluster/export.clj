(ns seon.cluster.export
  "Export: a self-contained copy of a store, re-identified to its new path.

  DRAFT CONTRACT LAYER FOR ORCHESTRATOR SEAL (drafted 2026-07-27 — the
  B2 rung, grounded in research/b2-plan-2026-07-27.md §0.8, §2.1-§2.4,
  §5.6 and §9). Nothing here is implemented: every body throws
  `awaits implementation`. Once sealed, the implementation lane fills
  the stubs until test/seon/cluster/export_test.clj is green and may not
  loosen a schema or a test. Friction is reported, never resolved by
  weakening.

  The model:

  - Branch-per-cluster won the creation path (b2-plan §0), so clone is
    NO LONGER how a cluster is made. It survives for the three jobs
    branches cannot do (§0.8): export/backup, import/move to another
    process or machine, and shipping a base system built offline. That
    is also the escape hatch from the one-store-one-process topology
    constraint (§0.4).
  - A cloned store directory CANNOT simply be opened. Datahike compares
    the stored `[:config :store :id]` against the connect-time id and
    raises `:store-identity-mismatch`
    (`reference-code/datahike/src/datahike/connector.cljc:159-169`),
    and B1 derives the id from the canonical path
    (`src/seon/cluster/store.clj:152-153`), so a copy at a new path
    always mismatches. Re-identifying is MANDATORY, not optional:
    reusing the source id instead collides on Datahike's
    `[store-id branch]` connection id and the second open is refused
    (§2.4).
  - `:allow-unsafe-config` is REJECTED as the fix (§2.2): the flag
    rides the live config into the next commit's stored config, so
    every later flag-free connect fails a different way. A one-time
    fork problem must not become a permanent config asymmetry.
  - EVERY BRANCH HEAD CARRIES ITS OWN STORED CONFIG, so `reidentify!`
    rewrites the whole roster, not only `:db`. Probed live
    (`tmp/b2-draft-probe/head_config_probe.clj`): with only `:db`
    rewritten, `d/connect` to `:db` succeeded and `:ancestor-x` refused
    `:store-identity-mismatch`. An export whose cluster branches cannot
    be opened is not an export.
  - THE TEMP NAME IS THE SAFETY. Clone into
    `<parent>/.store.<uuid>.tmp`, re-identify there, and only then move
    it atomically to `<parent>/store` — quarried verbatim from State
    A's operator (`script/seon/dev/cluster.clj:58-90`). A
    mis-identified store therefore never exists at a path anything
    opens.
  - The source is passed as the OPEN, flock-held store value: this
    process is provably the only writer while the copy is taken, and
    Datahike's values-then-pointer barrier means even a copy taken mid
    commit opens at the previous head with the new values unreachable.
  - Refusals are loud ex-info
    `{:seon.error/kind ::refused ::rule <which>}`, matching B0/B1
    (`src/seon/cluster/store.clj:161-167`).

  Crash walk (kill -9 at any point; the export path owns no durable
  state in the SOURCE store, so a killed export is always garbage the
  next export overwrites):

  - mid clone: a partial `.store.<uuid>.tmp`. Never named `store`,
    never opened, discarded by the next export;
  - after the clone, before `reidentify!`: a temp directory carrying
    the SOURCE's store id. Same answer — the name is the fence;
  - mid `reidentify!` (some branch heads rewritten, some not): still
    only the temp name; a partially re-identified store is never
    reachable under `store`;
  - after the atomic move: a complete, openable export. `reidentify!`
    is idempotent on a store already carrying its own path-derived id,
    so a re-run over the finished export is a no-op."
  (:require [seon.cluster.store :as store]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/export.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Contracts
;;; ---------------------------------------------------------------------------

(defn reidentify!
  "Rewrite a copied store's stored identity to match its own path.
  One `k/get` / `k/assoc` pair per branch in `:branches`, plus `:db`,
  setting `[:config :store :id]` to the path-derived id
  `seon.cluster.store/datahike-configuration` would present and
  `[:config :store :path]` to the canonical path — measured at 13.8 ms
  for a 15,000-datom store (§2.3). Runs BEFORE any `d/connect` and
  before the directory takes its final name. Returns the canonical
  store directory.
  IDEMPOTENT: a store already carrying its own path-derived id is
  rewritten to the same values.
  Refuses `::no-branch-head` (`:db` absent — the directory is not a
  store) and `::genesis-incomplete` (`:branches` absent — the
  first-create kill window, which B1 repairs by recreate and which an
  export must never carry forward)."
  {:malli/schema [:=> [:cat :seon.store/dir] :seon.store/dir]}
  [store-dir]
  (throw (ex-info "awaits implementation" {::fn `reidentify!})))

(defn export!
  "Copy an open store to `<parent-dir>/store` as an independent store.
  Clone the source directory into `<parent-dir>/.store.<uuid>.tmp`
  (`/bin/cp -cR` on macOS, `cp --reflink=auto -a` on Linux — copy-on-
  write where the filesystem provides it, a byte copy where it does
  not), `reidentify!` the temp, then move it atomically onto
  `<parent-dir>/store`. Returns that canonical path; the result opens
  through `seon.cluster.store/open-store!` with its own flock, its own
  store id, and every branch of the source intact.
  Refuses `::export-exists` (`<parent-dir>/store` is already present —
  an export never overwrites a store). A host with no known
  copy-on-write command FALLS BACK, loudly (one warning naming the
  fallback and the host): create a fresh store at the temp path and
  re-transact every branch's datoms from the source — slower, never
  unavailable (robust-and-roll-with-it is the standing owner lean;
  b2-plan §9's original shape). `::clone-unsupported` refuses only
  when the fallback ALSO fails, carrying both causes."
  {:malli/schema [:=> [:cat :seon.export/request] :seon.export/path]}
  [request]
  (throw (ex-info "awaits implementation" {::fn `export!})))
