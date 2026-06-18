(ns seon.embed
  "Embedding-index FOUNDATION for the wire-server (JVM, sole datahike writer).

   This namespace owns the LOCKED facts of the embedding-based fn-retrieval
   feature (PRD agent-runtime/embeddings-fn-retrieval-2026-06-18, Phase-2):

     - the `:seon/embedding` attribute schema — a single tuple value
       (`:db.type/tuple`, cardinality/one) holding the per-function source
       embedding. The raw vector persists in the PRIMARY datahike indices
       (AEVT) — that is the durable truth. We deliberately DO NOT set
       `:db.secondary/only`: a secondary-only attr stores only a content hash
       in the primary, so the real vector would be lost if the in-memory
       Proximum index ever failed to restore (and the AEVT-backfill on boot
       would feed hashes, not vectors, to the index). Keeping the vector in
       AEVT makes the Proximum HNSW a pure DERIVED CACHE that datahike rebuilds
       from datoms on every conn open.
     - the `:seon.embed/fn-index` `:proximum` secondary index over it
       (HNSW, cosine distance, dim 1536, capacity 10000).

   `install!` declares both onto a datahike conn. It is idempotent: it skips
   the index def entirely when a `:seon.embed/fn-index` secondary-index entry
   already exists on the conn's current schema, and re-transacting the same
   attr `:db/ident` is a harmless datahike upsert.

   RESTORE-ON-OPEN: datahike's `restore-secondary-indices` (writing.cljc)
   re-instantiates every secondary index on every conn open by calling
   `sec/create-index`, which for `:proximum` unconditionally creates a fresh
   konserve store (`create-store-sync`). A *memory* store with a fixed id
   collides on a second `create-index` within a JVM, the index is dropped, and
   KNN stops working. `delete-index-store!` drops the store id from konserve's
   registry; `install!` calls it, and any caller re-opening a conn in the SAME
   JVM calls it before re-connecting so restore starts clean. A fresh JVM has a
   clean registry, so the wire-server's normal restart path never collides. The
   store is throwaway — the durable vectors live in AEVT and re-index on open.

   IMPORTANT — this is the FOUNDATION only. It does NOT embed text (no Gemini
   call), does NOT add a wire verb, and does NOT touch ctx rendering. Those
   are Phase-2 steps 2b/2c/2d. Embedding vectors are transacted by the caller
   (later: the embed-on-persist pipeline); this ns only makes the substrate
   ready to receive them.

   Runs on the WIRE-SERVER classpath (the `:writer` alias), which exposes
   `reference-code/datahike/src-secondary` + the `org.replikativ/proximum`
   dep + the required `--add-modules jdk.incubator.vector` JVM flags. The
   wire-server transacts ALL writes as RAW datahike maps through its single
   conn (it does NOT route through `seon.db/transact!`); `install!` follows
   the same convention — it transacts directly via `datahike.api/transact`."
  (:require
    [datahike.api :as d]
    ;; Loading this ns registers the :proximum secondary-index type with
    ;; datahike's `datahike.index.secondary` multimethods. Required before
    ;; any tx that declares a `:db.secondary/type :proximum` index.
    [datahike.index.secondary.proximum]
    [konserve.memory :as km]
    [seon.db.datahike.schema :as dh-schema]
    [seon.schema :as schema])
  (:import [java.util UUID]))

;;; --- Locked constants ------------------------------------------------------

(def ^:const embedding-dim
  "Embedding dimensionality. The Proximum index `:dim` MUST equal the length
   of every `:seon/embedding` vector transacted. 1536 matches the chosen
   embeddings model's output dimensionality (PRD Q1 — resolved at 2b)."
  1536)

(def ^:const index-ident
  "`:db/ident` of the Proximum secondary index over `:seon/embedding`."
  :seon.embed/fn-index)

(def ^:const distance
  "HNSW distance metric. MUST be `:cosine` — Proximum's `create-index`
   defaults to `:euclidean`, which is wrong for normalized text embeddings."
  :cosine)

(def ^:const capacity
  "HNSW index capacity. Proximum defaults to 10,000,000 → a multi-GB mmap.
   Keep small; grow when the corpus warrants."
  10000)

;;; --- Schema ----------------------------------------------------------------
;;;
;;; `:seon/embedding` has a SINGLE-segment namespace (`seon`). This is
;;; deliberate (the attr is cross-cutting — it hangs off any `:seon.fn`).
;;; `seon.schema/register!`'s multi-segment-namespace assertion is `:cljs`-
;;; gated only, so registering it from this `.clj` ns is fine. If the POD
;;; ever needs to register `:seon/embedding`, that assertion WILL fire —
;;; FLAG it then (out of scope here).
;;;
;;; A vector-of-floats is keyed by the bridge to emit a single tuple value
;;; (`:db.type/tuple`, cardinality/one) — never a cardinality-many scalar
;;; attr. We deliberately DO NOT set `:db.secondary/only`: see the namespace
;;; docstring (durable vector lives in primary AEVT; HNSW is a derived cache).
;;; See `seon.db.datahike.schema/schema->attr-partial` (clj) and
;;; `seon.db.internal/malli->datahike-attr` (cljs).

(schema/register! :seon/embedding
                  [:vector :float])

;;; --- Index store config ----------------------------------------------------
;;;
;;; STORE BACKEND — :memory, deterministic id, DELETED-BEFORE-CREATE.
;;;
;;; RESOLVED (2026-06-18, P2-A). The Proximum konserve store is a pure DERIVED
;;; CACHE: the durable embedding vectors live in the primary AEVT (we dropped
;;; `:db.secondary/only`), and datahike re-indexes the HNSW from AEVT on every
;;; conn open. So the store's *contents* never need to survive — only its
;;; *creation* must not collide.
;;;
;;; The collision: `proximum/create-index` UNCONDITIONALLY calls
;;; `create-store-sync` -> konserve `-create-store`, which THROWS when a store
;;; with that id already exists (memory: JVM-global `memory-store-registry`;
;;; file: the on-disk dir). datahike's `restore-secondary-indices` and
;;; `finalize-secondary-indices` both call `create-index` with the SAME stored
;;; `:store-config` (so a fixed id collides on any second `create-index` in a
;;; JVM — a re-`install!`, or a same-JVM conn re-open). A fresh JVM has a clean
;;; registry, so the wire-server's normal restart path never collides; only a
;;; same-process re-open does.
;;;
;;; Fix: a :memory backend with a deterministic id, plus `delete-index-store!`
;;; which drops that id from konserve's registry. `install!` calls it before
;;; transacting; callers that re-open a conn in the SAME JVM (e.g. the live
;;; verification harness, or any in-process reconnect) call it before
;;; re-connecting so restore's `create-index` starts from a clean slate. The
;;; store is throwaway, so deleting it loses nothing — the vectors reload from
;;; AEVT.

(defn index-store-id
  "Deterministic konserve store id (UUID) for the Proximum index, derived from
   the index ident. Stable across opens so `delete-index-store!` can target it
   without a live conn."
  {:malli/schema [:=> [:cat] :uuid]}
  []
  (UUID/nameUUIDFromBytes (.getBytes (str index-ident) "UTF-8")))

(defn- index-store-config
  "In-memory konserve store-config for the Proximum index, with a
   deterministic `:id`. The store is a derived cache (see the section comment);
   `delete-index-store!` clears it before each `create-index`."
  []
  {:backend :memory
   :id      (index-store-id)})

(defn delete-index-store!
  "Drop the Proximum index's konserve memory store from the JVM-global
   registry, so the NEXT `create-index` (via `install!` or a conn re-open's
   `restore-secondary-indices`) does not collide on the existing id. Safe to
   call when no store exists (returns `{:seon.embed/store-deleted? false}`).
   Loses nothing durable — the vectors live in AEVT and re-index on open."
  {:malli/schema [:=> [:cat] [:map [:seon.embed/store-deleted? :boolean]]]}
  []
  {:seon.embed/store-deleted?
   (boolean (km/delete-mem-store (index-store-id)))})

;;; --- Schema/index tx forms -------------------------------------------------

(defn embedding-attr-schema
  "The datahike attr declaration for `:seon/embedding`, DERIVED from the
   registered Malli schema via the bridge (never hand-written). Returns a
   single-entry vector ready for `datahike.api/transact`."
  {:malli/schema [:=> [:cat] [:vector [:map-of :keyword :any]]]}
  []
  (dh-schema/malli-map->datahike-schema
    [:map [:seon/embedding :seon/embedding]]))

(defn index-def
  "The `:proximum` secondary-index entity declaring an HNSW index over
   `:seon/embedding`. `:dim`/`:distance`/`:capacity` are the locked constants;
   `:store-config` is an in-memory konserve store (a derived cache — see the
   store-config section comment)."
  {:malli/schema [:=> [:cat] [:map-of :keyword :any]]}
  []
  {:db/ident            index-ident
   :db.secondary/type   :proximum
   :db.secondary/attrs  [:seon/embedding]
   :db.secondary/config {:dim          embedding-dim
                         :distance     distance
                         :capacity     capacity
                         :store-config (index-store-config)}})

(defn index-live?
  "True iff `conn`'s current db has a LIVE `:seon.embed/fn-index` instance in
   `:secondary-indices` (not merely declared on the schema). A reopened conn
   carries the index on its schema but NOT in `:secondary-indices` — its
   versioned restore fails (the in-memory Proximum store is wiped on release),
   so the instance must be rebuilt from AEVT (see `install!`)."
  {:malli/schema [:=> [:catn [:conn :any]] :boolean]}
  [conn]
  (boolean (get-in (d/db conn) [:secondary-indices index-ident])))

;;; --- Install ---------------------------------------------------------------

(defn install!
  "Declare the `:seon/embedding` attr + ensure a LIVE `:seon.embed/fn-index`
   Proximum index instance on datahike `conn` (a wire-server conn handle —
   third-party datahike value, hence `:any`).

   Genuinely idempotent AND restore-safe:
   - always upserts the `:seon/embedding` attr schema (harmless re-tx);
   - when the index instance is already live (`index-live?`), does NOTHING else
     — a no-op that neither re-instantiates nor re-`create-store`s the live
     index, nor disturbs its konserve store;
   - otherwise (first install, OR a freshly reopened conn whose versioned
     restore failed), clears any stale konserve store id (`delete-index-store!`,
     so `create-index` cannot collide) and transacts the index def. datahike's
     `finalize-secondary-indices` then instantiates the index; because the
     embedding vectors live in the primary AEVT (we dropped
     `:db.secondary/only`), `instantiate-secondary` sees the datoms, marks the
     index `:building`, and the writer backfills the HNSW from AEVT. This makes
     `install!` the single restore-on-open recovery point: call it once per
     conn open and KNN works whether the conn is brand-new or reopened.
   Returns `{:seon.embed/installed? true}`."
  {:malli/schema [:=> [:catn [:conn :any]]
                  [:map [:seon.embed/installed? :boolean]]]}
  [conn]
  (d/transact conn (embedding-attr-schema))
  (when-not (index-live? conn)
    ;; About to (re)instantiate via finalize-secondary-indices -> create-index.
    ;; Clear any stale konserve store id so create-store does not collide. The
    ;; store is a derived cache; deleting it loses nothing (vectors are in AEVT
    ;; and re-index on the :building backfill).
    (delete-index-store!)
    (d/transact conn [(index-def)]))
  {:seon.embed/installed? true})
