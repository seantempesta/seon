(ns seon.embed
  "Embedding-index foundation for the JVM database server.

   This namespace owns the LOCKED facts of the embedding-based retrieval
   substrate (PRD agent-runtime/embeddings-fn-retrieval-2026-06-18, Phase-2).
   It is general over ANY indexable string — functions are the originating
   use case, but the knowledge base (`my.kb.*`), namespaces, and arbitrary
   future kinds all flow through the SAME single attr + single index:

     - the `:seon/embedding` attribute schema — a single tuple value
       (`:db.type/tuple`, cardinality/one) holding an entity's source/text
       embedding. It is `:db.secondary/only`: the full vector lives ONLY in
       Proximum's own durable konserve store; the primary datahike indices
       (AEVT) hold a content hash, never the raw 1536 floats. The vector is
       therefore RESTORED from Proximum's konserve store on conn open — never
       rebuilt from AEVT (there are no vectors in AEVT to rebuild from). This
       is the whole point of Proximum's CoW persistence/versioning.
     - the `:seon.embed/index` `:proximum` secondary index over it
       (HNSW, cosine distance, dim 1536, capacity 10000).

   `install!` declares both onto a datahike conn. It is idempotent: re-running
   it on a conn that already carries the attr + index on its schema is a
   harmless datahike upsert.

   RESTORE-ON-OPEN (the P2-A.5 fix): datahike's `restore-secondary-indices`
   (writing.cljc) re-instantiates every secondary index on every conn open. For
   a `:proximum` index it dispatches `-sec-restore`, which `load-commit`s the
   committed HNSW from Proximum's konserve store. Our datahike fork's `:proximum`
   factory (src-secondary/.../proximum.clj) is CONNECT-IF-EXISTS: when the
   konserve store already exists it returns a passive restore-skeleton instead
   of `create-store`-ing a fresh one — so `-sec-restore`→`load-commit` populates
   the real index from durable storage. The durable konserve store therefore
   must survive a JVM restart, so it is a FILE backend sibling of the cluster's
   primary store (see `index-store-config`). No AEVT backfill, no delete-store
   dance, no rebuild.

   SHIP CONFIG — OFF by default behind ONE switch. The whole feature is inert
   unless `SEON_EMBED` is set in the database server's env (see
   `embed-feature-enabled?`): with it UNSET the explicit database initializer
   installs NO index, the transaction transform is a pass-through, and
   `backfill!` no-ops — a fresh consumer pays ZERO cost (no index, no Gemini
   call, byte-identical behavior). Set `SEON_EMBED=1` (+ `GEMINI_API_KEY`)
   before starting the database server to enable it. By default exactly ONE trigger
   is indexed: `:seon.fn/source`.

   P2-B — the embedding WRITE side (this ns, below the foundation). A
   writer receives immutable trigger-attribute → compose-function data; every
   entity carrying a configured trigger is
   embedded (Gemini `gemini-embedding-2`, dim 1536, L2-normalized) + indexed
   automatically on transact, with a SHA-256 `:seon.embed/source-hash` cache so
   an unchanged entity never pays a Gemini call. There is NO `:seon/kind` enum —
   the attribute IS the type (idiomatic Datomic). Functions (`:seon.fn/source`)
   are the only shipped trigger. Every configured trigger flows through the
   same `:seon/embedding` attr + single Proximum index.

   Writer boot composes `augment-tx-with-embeddings`, `initialize-database!`,
   and `knn-search` explicitly into one runtime value. Merely requiring this
   namespace never mutates the transaction path, operation dispatch, or
   connection initialization.

   The embedding query side (NL-query → KNN, retrieval-instruction prefix on the
   query) is P2-C — `knn` here is the direct index access it (and the harness)
   build on. DOCUMENTS get no prefix; only queries do (v2 has no taskType).

   Runs on the WIRE-SERVER classpath (the `:writer` alias), which exposes
   `reference-code/datahike/src-secondary` + the `org.replikativ/proximum`
   dep + the required `--add-modules jdk.incubator.vector` JVM flags. The
   database server transacts all writes as raw Datahike maps through its single
   conn (it does NOT route through `seon.db/transact!`); `install!` follows
   the same convention — it transacts directly via `datahike.api/transact`."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [datahike.api :as d]
    ;; Loading this ns registers the :proximum secondary-index type with
    ;; datahike's `datahike.index.secondary` multimethods. Required before
    ;; any tx that declares a `:db.secondary/type :proximum` index.
    [datahike.index.secondary.proximum]
    [datahike.index.secondary :as sec]
    ;; entity-set: build the EntityBitSet entity-filter for type-scoped KNN
    ;; (the pod resolves a :where to eids; the server restricts KNN to them).
    [datahike.index.entity-set :as es]
    ;; konserve.core + proximum.writing: read whether the Proximum konserve
    ;; store at a derived path already holds a COMMITTED index, so install!'s
    ;; stale-orphan-store cleanup uses the SAME definition of "committed store
    ;; exists" the fork's :proximum factory does (committed-index-exists?*).
    [konserve.core :as k]
    [proximum.writing :as pwr]
    [seon.ai.tokens :as tokens]
    [seon.db.datahike.schema :as dh-schema]
    [seon.schema :as schema]
    [taoensso.timbre :as log])
  (:import [com.google.genai Client]
           [com.google.genai.types EmbedContentConfig EmbedContentResponse]
           [java.io File]
           [java.security MessageDigest]
           [java.util UUID]
           [java.util.concurrent Callable Executors Future]))

;;; --- Locked constants ------------------------------------------------------

(def ^:const embedding-dim
  "Embedding dimensionality. The Proximum index `:dim` MUST equal the length
   of every `:seon/embedding` vector transacted. 1536 matches the chosen
   embeddings model's output dimensionality (PRD Q1 — resolved at 2b)."
  1536)

(def ^:const index-ident
  "`:db/ident` of the Proximum secondary index over `:seon/embedding`. Kind-
   agnostic: one index covers fns, the knowledge base, namespaces, and any
   future embeddable entity (the originating name `:seon.embed/fn-index` was
   renamed when the substrate generalized beyond fns)."
  :seon.embed/index)

(def ^:const distance
  "HNSW distance metric. MUST be `:cosine` — Proximum's `create-index`
   defaults to `:euclidean`, which is wrong for normalized text embeddings."
  :cosine)

(def ^:const capacity
  "HNSW index capacity. Proximum defaults to 10,000,000 → a multi-GB mmap.
   Keep small; grow when the corpus warrants."
  10000)

;;; --- Master feature switch (SHIP CONFIG: OFF by default) --------------------
;;;
;;; The WHOLE embedding-retrieval feature is OFF unless `SEON_EMBED` is set in
;;; the database server's env. This is the load-bearing "zero cost when not opted
;;; in" gate: with `SEON_EMBED` UNSET the explicit initializer does
;;; NOTHING (no `install!`, no `backfill!`), so NO Proximum index is ever
;;; declared on a fresh consumer's store; `augment-tx-with-embeddings` is a
;;; pass-through; `backfill!` no-ops. No index, no embed-on-write, no Gemini
;;; call — byte-identical to a writer without embedding in its runtime.
;;;
;;; TWO independent gates, deliberately split:
;;;   - `embed-feature-enabled?` (the MASTER switch — `SEON_EMBED` presence):
;;;     does the feature run AT ALL. The consumer opt-in.
;;;   - `gemini-client` non-nil (GEMINI_API_KEY presence, checked at each embed
;;;     site below): can actual embedding HAPPEN. Graceful no-key — the feature
;;;     can be enabled with no key (index declared, writes still commit, embeds
;;;     simply no-op) without erroring. Both must hold for vectors to be written.

(defn embed-feature-enabled?
  "True iff the embedding-retrieval feature is opted in — the `SEON_EMBED` env
   var is PRESENT (any value, incl. empty string) on the database server. UNSET ⇒
   the entire feature is inert: initialization installs no index, the
   transaction transform passes tx-data through untouched, and `backfill!`
   no-ops. This is the master switch; the GEMINI_API_KEY check (`gemini-client`)
   is the orthogonal can-embedding-actually-happen gate."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (some? (System/getenv "SEON_EMBED")))

;;; --- Schema ----------------------------------------------------------------
;;;
;;; `:seon/embedding` has a SINGLE-segment namespace (`seon`). This is
;;; deliberate (the attr is cross-cutting — it hangs off any embeddable
;;; entity, of any kind). `seon.schema/register!`'s multi-segment-namespace
;;; assertion is `:cljs`-gated only, so registering it from this `.clj` ns is
;;; fine. If the POD ever needs to register `:seon/embedding`, that assertion
;;; WILL fire — FLAG it then (out of scope here).
;;;
;;; A vector-of-floats carrying `:db.secondary/only true` is keyed by the
;;; bridge to a single tuple value (`:db.type/tuple`, cardinality/one) whose
;;; full vector lives ONLY in the Proximum secondary index; the primary
;;; datahike indices (AEVT) hold a content hash. The HNSW is the durable home
;;; of the vector — datahike RESTORES it from Proximum's konserve store on conn
;;; open (the P2-A.5 connect-if-exists fix), never rebuilds it from AEVT. See
;;; `seon.db.datahike.schema/schema->attr-partial` (clj — the `:db.secondary/only`
;;; tuple branch) and the CLJS sibling `seon.db.internal/malli->datahike-attr`.

(schema/register! :seon/embedding
                  [:vector {:db.secondary/only true} :float])

;;; --- Source-hash cache attr ------------------------------------------------
;;;
;;; `:seon.embed/source-hash` is the SHA-256 (hex) of the COMPOSED document
;;; string an entity's trigger-attr produced when its `:seon/embedding` was
;;; last computed. It lives in the primary Datahike database (a plain string,
;;; queryable/pullable — unlike the secondary-only embedding), so the
;;; embed-on-write path can read it back and SKIP the paid Gemini call when the
;;; composed text is unchanged. A docstring/source/title edit changes the
;;; composed string → changes the hash → re-embeds. One attr, shared by every
;;; embeddable entity-kind (fns, kb rows, …), same as `:seon/embedding`.
(schema/register! :seon.embed/source-hash :string)

;;; --- Index store config ----------------------------------------------------
;;;
;;; STORE BACKEND — DURABLE FILE store, a SIBLING of the cluster's primary
;;; datahike store.
;;;
;;; The `:seon/embedding` attr is `:db.secondary/only`: the full vector lives
;;; ONLY in this Proximum konserve store, NOT in the primary AEVT. So the store
;;; MUST survive a database-server restart, or the vectors are lost. A `:file`
;;; konserve store sibling of the primary LMDB store satisfies that with no
;;; lock contention (separate directories, separate konserve backends).
;;;
;;; The path is derived from the live conn's own primary `:store` config so the
;;; index store always lands beside whichever cluster store the conn opened
;;; (e.g. primary `data/clusters/default/db` →
;;; `data/clusters/default/embedding-index`). A `:memory`-backed primary conn
;;; (tests) falls back to a `:memory` index store whose `:id` mixes the index
;;; ident WITH the conn's primary :memory store id — non-durable (tests don't
;;; reopen), but COLLISION-FREE across conns in one JVM. (A bare per-index id
;;; collided two memory conns in the same JVM on the JVM-global memory konserve
;;; registry: the 2nd conn's `install!` saw the 1st's committed index, got a
;;; restore-skeleton on a fresh-declare path, and the next commit's `-sec-flush`
;;; threw "only -sec-restore should be called on a skeleton". Per-conn id fixes
;;; it.)
;;;
;;; RESTORE: our datahike fork's `:proximum` factory is connect-if-exists, so a
;;; reopen restores the committed HNSW from this store via `-sec-restore`→
;;; `load-commit` (NO create-store collision, NO AEVT rebuild — there are no
;;; vectors in AEVT). See the namespace docstring.
;;;
;;; SIBLING, NOT NESTED (the P2-A.5 robustness decision): the index store is a
;;; SIBLING of — never a subdir INSIDE — the primary store dir. konserve's
;;; `:file` `-keys` enumerates EVERY first-level dir entry as a candidate key
;;; (only `.nfs*` is filtered), and unrecognized entries are routed through
;;; foreign-key migration. A proximum store nested at `<primary>/embedding-index`
;;; would therefore (a) crash `k/keys` (konserve tries to migrate the subdir as
;;; an old-schema file — VERIFIED live, ClassCastException in migrate-file-v1),
;;; and (b) be swept as a non-reachable key by `d/gc-storage!`. Sibling layout
;;; keeps the two konserve keyspaces disjoint. The cost of the sibling layout —
;;; `bin/seon cluster reset` wipes only `<cluster>/db`, leaving the sibling
;;; `<cluster>/embedding-index` behind as a STALE orphan — is handled in
;;; `install!`: a fresh declare against a committed-but-orphaned store deletes
;;; the orphan before declaring (see `committed-index-store-exists?` /
;;; `delete-index-store!` / `install!`).

(defn index-store-id
  "Deterministic konserve store id (UUID) for the Proximum index, derived from
   the index ident. Konserve namespaces stored data under this `:id`; deriving
   it from the index ident keeps it stable across opens."
  {:malli/schema [:=> [:cat] :uuid]}
  []
  (UUID/nameUUIDFromBytes (.getBytes (str index-ident) "UTF-8")))

(defn- primary-store-path
  "The on-disk path of `conn`'s primary `:file` datahike store, or nil when the
   primary is not file-backed (e.g. a `:memory` test conn)."
  [conn]
  (let [store (get-in (d/db conn) [:config :store])]
    (when (= :file (:backend store))
      (:path store))))

(defn index-store-config
  "Durable konserve store-config for the Proximum index over `conn`'s embeddings.

   File-backed (`:file`) at a directory SIBLING of `conn`'s primary store
   (`<primary-parent>/embedding-index`), so the committed HNSW survives a
   database-server restart and `-sec-restore` can `load-commit` it on reopen. When
   `conn`'s primary store is NOT file-backed (a `:memory` test conn), falls back
   to a `:memory` store keyed by the index id (non-durable; tests don't reopen).
   Deterministic `:id` either way."
  {:malli/schema [:=> [:catn [:conn :any]] [:map-of :keyword :any]]}
  [conn]
  (let [id (index-store-id)]
    (if-let [primary (primary-store-path conn)]
      (let [parent (or (.getParent (File. ^String primary)) ".")
            path   (str parent "/embedding-index")]
        {:backend :file :path path :id id})
      {:backend :memory :id id})))

;;; --- Schema/index tx forms -------------------------------------------------

(defn embedding-attr-schema
  "The datahike attr declarations for the embedding substrate's PRIMARY-store
   attrs — `:seon/embedding` (secondary-only; AEVT holds the hash) AND
   `:seon.embed/source-hash` (the plain-string cache key). Both DERIVED from
   their registered Malli schemas via the bridge (never hand-written). Under
   the database server's `:schema-flexibility :write` conn, the hash attr must be
   declared before any embed-on-write tx asserts it. Returns a vector ready for
   `datahike.api/transact`."
  {:malli/schema [:=> [:cat] [:vector [:map-of :keyword :any]]]}
  []
  (dh-schema/malli-map->datahike-schema
    [:map
     [:seon/embedding :seon/embedding]
     [:seon.embed/source-hash :seon.embed/source-hash]]))

(defn index-def
  "The `:proximum` secondary-index entity declaring an HNSW index over
   `:seon/embedding` for `conn`. `:dim`/`:distance`/`:capacity` are the locked
   constants; `:store-config` is the DURABLE file store sibling of `conn`'s
   primary store (see `index-store-config`)."
  {:malli/schema [:=> [:catn [:conn :any]] [:map-of :keyword :any]]}
  [conn]
  {:db/ident            index-ident
   :db.secondary/type   :proximum
   :db.secondary/attrs  [:seon/embedding]
   :db.secondary/config {:dim          embedding-dim
                         :distance     distance
                         :capacity     capacity
                         :store-config (index-store-config conn)}})

(defn index-declared?
  "True iff `conn`'s current schema declares the `:seon.embed/index` secondary
   index (the index entity has been transacted). A reopened conn restores the
   index INSTANCE automatically from the durable konserve store (the P2-A.5
   connect-if-exists fix), so a declared index is also a live one — `install!`
   only needs to declare it once on a fresh store."
  {:malli/schema [:=> [:catn [:conn :any]] :boolean]}
  [conn]
  (boolean (get-in (d/db conn) [:schema index-ident :db.secondary/type])))

(defn index-live?
  "True iff the `:seon.embed/index` Proximum index INSTANCE is live on `conn`'s
   db (present in `:secondary-indices`). After a successful reopen-restore a
   declared index is also live. A DECLARED-but-NOT-live index is the failure
   signal: datahike's `restore-secondary-indices` could not `-sec-restore` the
   committed HNSW (with `:db.secondary/only` ON that is data loss — the vectors
   live ONLY in the konserve store)."
  {:malli/schema [:=> [:catn [:conn :any]] :boolean]}
  [conn]
  (boolean (get-in (d/db conn) [:secondary-indices index-ident])))

;;; --- Stale-orphan konserve store handling (the P2-A.5 robustness fix) -------
;;;
;;; The index store is a SIBLING of the primary store (it cannot be nested —
;;; see the index-store-config docstring). `bin/seon cluster reset` wipes only
;;; the primary database directory, so it leaves the sibling Proximum store
;;; behind. On the next boot `install!` declares the index FRESH (the primary
;;; database, being
;;; wiped, has no record of it) against that leftover COMMITTED store — and the
;;; fork's connect-if-exists factory returns a passive restore-skeleton (it sees
;;; a committed store, expects a `-sec-restore` that never comes on a declare).
;;; The skeleton becomes the live index; the next commit's `-sec-flush` hits the
;;; skeleton's `bug!` and CRASHES. So a fresh declare must first delete any
;;; committed-but-orphaned store at the index's path.

(defn committed-index-store-exists?
  "True iff `store-config` points at a konserve store that ALREADY holds a
   COMMITTED Proximum index (at least one branch). MIRRORS the fork factory's
   private `committed-index-exists?*` (proximum.clj) so `install!`'s cleanup and
   the factory agree on what 'a committed store' is: a store dir that merely
   exists but has no committed branch is NOT a committed store (the factory
   takes the CREATE path for it, no skeleton). Any backend error → false
   (treat as not-committed; let the declare proceed and surface the real error).
   No-op false for a `:memory` store-config (tests don't orphan a durable
   store)."
  {:malli/schema [:=> [:catn [:store-config [:map-of :keyword :any]]] :boolean]}
  [store-config]
  (boolean
   (when (= :file (:backend store-config))
     (try
       (and (k/store-exists? store-config {:sync? true})
            (let [store (pwr/connect-store-sync store-config)]
              (seq (k/get store :branches nil {:sync? true}))))
       (catch Throwable _ false)))))

(defn delete-index-store!
  "Delete the file-backed Proximum konserve store directory at `store-config`'s
   `:path`. Used by `install!` to remove a STALE orphan store before a fresh
   declare (the cluster-reset case). The store is a flat dir of `<uuid>.ksv`
   files plus the `:branches` key — deleting the directory removes the whole
   committed store so the factory takes the CREATE path on the follow-on
   declare. No-op (returns `{:seon.embed/deleted? false}`) for a non-`:file`
   store-config or a path that does not exist."
  {:malli/schema [:=> [:catn [:store-config [:map-of :keyword :any]]]
                  [:map [:seon.embed/deleted? :boolean]]]}
  [store-config]
  (if-let [path (when (= :file (:backend store-config)) (:path store-config))]
    (let [dir (io/file path)]
      (if (.exists dir)
        (do
          ;; konserve `:file` stores are a flat dir of <uuid>.ksv files (plus
          ;; the :branches key) — recursive delete removes the whole store.
          (doseq [^File f (reverse (file-seq dir))] (.delete f))
          (log/warn "embed: deleted STALE orphan Proximum index store at" path
                    "(committed store with no schema record — cluster-reset orphan)")
          {:seon.embed/deleted? true})
        {:seon.embed/deleted? false}))
    {:seon.embed/deleted? false}))

;;; --- Install ---------------------------------------------------------------

(defn install!
  "Declare the `:seon/embedding` attr + the `:seon.embed/index` Proximum index
   on datahike `conn` (a database-server connection — third-party Datahike value,
   hence `:any`).

   Idempotent, restore-safe, AND cluster-reset-safe by construction:
   - always upserts the `:seon/embedding` attr schema (harmless re-tx);
   - REOPEN (index already declared on the conn's schema): its instance was
     restored from the durable konserve store by datahike's
     `restore-secondary-indices` (our fork's connect-if-exists factory +
     `-sec-restore`→`load-commit`). This is a true no-op — no re-create, no
     AEVT rebuild. If restore FAILED (declared but not live), throw LOUDLY:
     with `:db.secondary/only` ON the vectors live ONLY in the konserve store,
     so silently re-declaring would lose them. Surface, never paper over.
   - FRESH declare (index not yet on the conn's schema): if a committed-but-
     ORPHANED Proximum store already exists at the index's sibling path (the
     `bin/seon cluster reset` case — primary database wiped, sibling Proximum
     store left behind), DELETE that stale store first. Otherwise the fork's
     connect-if-exists factory returns a passive restore-skeleton that becomes
     the live index and crashes on the next commit's `-sec-flush`. With a clean
     path (or after deleting the orphan) the declare creates a real index.

   Returns `{:seon.embed/installed? true}`."
  {:malli/schema [:=> [:catn [:conn :any]]
                  [:map [:seon.embed/installed? :boolean]]]}
  [conn]
  ;; The declared-but-not-live (restore-failed) check MUST come BEFORE any
  ;; transact: the first commit would otherwise re-instantiate the declared
  ;; index via the factory and crash on -sec-flush, masking the real cause. We
  ;; want the precise :seon.embed/index-restore-failed signal, not the skeleton
  ;; bug. (index-declared? reflects the durable primary schema, populated at
  ;; connect — it is correct before the first install! transact.)
  (when (and (index-declared? conn) (not (index-live? conn)))
    ;; REOPEN with a declared index whose instance did NOT restore ⇒ the durable
    ;; Proximum store is missing/corrupt. With :db.secondary/only the vectors
    ;; live ONLY there, so re-declaring would lose them. Fail loud (do NOT
    ;; silently delete the store or re-declare).
    (throw (ex-info (str "seon.embed/install!: the :seon.embed/index secondary "
                         "index is declared on this conn's schema but its instance "
                         "did NOT restore from the durable Proximum konserve store. "
                         "With :db.secondary/only ON the vectors live ONLY in that "
                         "store, so the index cannot be silently re-declared (that "
                         "would lose them). The konserve store is likely missing or "
                         "corrupt at its configured path. Restore it from backup or, "
                         "accepting vector loss, delete it AND retract the index "
                         "declaration, then re-declare to rebuild.")
                    {:seon.embed/error        :seon.embed/index-restore-failed
                     :seon.embed/index-ident  index-ident
                     :seon.embed/store-config (index-store-config conn)})))
  (d/transact conn (embedding-attr-schema))
  (when-not (index-declared? conn)
    ;; FRESH declare: delete any committed-but-orphaned store first so the
    ;; factory takes the CREATE path (not the restore-skeleton path that crashes
    ;; on the next -sec-flush). committed-index-store-exists? mirrors the
    ;; factory's own definition of "committed store", so the two agree. On a
    ;; clean path this is a cheap store-exists? false.
    (let [store-config (index-store-config conn)]
      (when (committed-index-store-exists? store-config)
        (delete-index-store! store-config))
      (d/transact conn [(index-def conn)])))
  {:seon.embed/installed? true})

;;; ===========================================================================
;;; P2-B — the embedding WRITE side (general, attribute-anchored)
;;; ===========================================================================
;;;
;;; The foundation above makes the substrate READY to receive vectors. This
;;; half produces them from immutable trigger-attribute → compose-function
;;; pipeline data. Every entity carrying a configured trigger is embedded and
;;; indexed automatically on transact, with a SHA-256 source-hash cache so an
;;; unchanged entity never pays a Gemini call.
;;;
;;; There is NO `:seon/kind` enum — the TRIGGER IS the attribute (idiomatic
;;; Datomic: attribute namespaces already classify entities). Functions and a
;;; knowledge base are entries in one immutable map over the same single
;;; `:seon/embedding` attr + single Proximum index.

;;; --- Immutable embedding configuration ------------------------------------
;;;
;;; A trigger-attr → compose-fn map is ordinary immutable writer-pipeline data.
;;; It is assembled once by boot and passed through transaction/backfill calls;
;;; requiring a namespace cannot mutate a running writer's behavior.

(schema/register! :seon.embed/trigger-attr :qualified-keyword)
(schema/register! :seon.embed/compose-fn [:fn fn?])
(schema/register! :seon.embed/embeddables
                  [:map-of :seon.embed/trigger-attr :seon.embed/compose-fn])

;;; --- Shipped embedding pipeline (functions only) ---------------------------
;;;
;;; Exactly ONE trigger ships by default: `:seon.fn/source`. A future
;;; database-backed configuration may project into this same immutable shape;
;;; it must not reintroduce a process-global registration API.
;;;
;;; `:seon.fn/sym` is the FQ identity string ("<ns>/<name>"), so it already
;;; carries the ns+name anchor; compose name+doc+source (research §4).

(defn- compose-fn-doc
  "Compose document for a `:seon.fn` entity: `<sym>\n<doc>\n<source>`. `sym` is
   already the FQ `<ns>/<name>` identity, the semantic anchor (research §4).
   Doc is optional; source is the retrieval payload."
  [{:seon.fn/keys [sym source doc]}]
  (str sym
       (when (seq doc) (str "\n" doc))
       (when (seq source) (str "\n" source))))

(defn default-embeddables
  "Return the immutable embedding pipeline shipped by Seon."
  {:malli/schema [:=> [:cat] :seon.embed/embeddables]}
  []
  {:seon.fn/source compose-fn-doc})

;;; Custom embedding triggers are immutable writer-pipeline data. A future
;;; database projection may extend `default-embeddables` before boot.

;;; --- Gemini embedding (java-genai, on the database server) ----------------
;;;
;;; Model `gemini-embedding-2`, outputDimensionality 1536, NO taskType (v2
;;; dropped it — the retrieval instruction goes in the QUERY text, which is the
;;; P2-C query side; DOCUMENTS get no prefix). The 1536 Matryoshka slice is NOT
;;; pre-normalized, so L2-normalize client-side before cosine/HNSW
;;; (research/gemini-embeddings-2026-06-18 + config-recommendation).

(def ^:const ^String embedding-model
  "Gemini embedding model id (free String arg). v2 is GA, 8192-token input, no
   taskType (research 2026-06-18, proven live in tmp/embed-spike)."
  "gemini-embedding-2")

;;; The Gemini client is built LAZILY so merely LOADING this namespace never
;;; reads GEMINI_API_KEY. A database server with no key boots and serves normally;
;;; embedding is simply inactive (the transaction transform and boot backfill
;;; no-op below). The client is heavyweight + connection-pool-backed, so it is
;;; built ONCE on the first real embed call when a key is present, then cached.
(defonce ^:private !client (atom nil))

(defn- gemini-client
  "The shared Gemini client, or nil when GEMINI_API_KEY is unset/blank. Built
   lazily + cached on the first call that finds a key — never at ns-load, so a
   key-less database server boots fine."
  ^Client []
  (or @!client
      (let [k (System/getenv "GEMINI_API_KEY")]
        (when-not (str/blank? k)
          (reset! !client (-> (Client/builder) (.apiKey ^String k) (.build)))))))

(defn- doc-config ^EmbedContentConfig []
  ;; outputDimensionality 1536; no taskType (v2). Same config object every
  ;; call — immutable, safe to reuse but cheap to rebuild.
  (-> (EmbedContentConfig/builder)
      (.outputDimensionality (Integer/valueOf (int embedding-dim)))
      (.build)))

(defn- l2-normalize
  "L2-normalize a vector of doubles → vector of floats. Reduced-dim Matryoshka
   slices (1536 < native 3072) are NOT pre-normalized, so this is required
   before cosine/HNSW."
  [v]
  (let [n (Math/sqrt (reduce + (map #(* % %) v)))]
    (mapv (fn [x] (float (if (zero? n) x (/ x n)))) v)))

(defn- embedding->vec
  "Extract one `ContentEmbedding` → an L2-normalized vector of floats."
  [content-embedding]
  (let [vals (-> content-embedding .values .get)]
    (l2-normalize (mapv double vals))))

(schema/register! :seon.embed/text [:string {:min 1}])
(schema/register! :seon.embed/vector [:vector :float])
(schema/register! :seon.embed/embed-texts-request
                  [:map [:seon.embed/texts [:vector :seon.embed/text]]])
(schema/register! :seon.embed/embed-texts-response
                  [:map [:seon.embed/vectors [:vector :seon.embed/vector]]])
(schema/register! :seon.embed/embed-text-request
                  [:map [:seon.embed/text :seon.embed/text]])
(schema/register! :seon.embed/embed-text-response
                  [:map [:seon.embed/vector :seon.embed/vector]])

;;; --- Bulk embedding: batching, bounded parallelism, rate-limit backoff ------
;;;
;;; Gemini caps a SINGLE input at 8,192 tokens (hard) and a request at
;;; ~250 texts / ~20k tokens (undocumented, community-observed). `embed-texts`
;;; is therefore BULK-SAFE for an arbitrarily large input: every text is
;;; truncated under the per-input cap, texts are packed into requests under BOTH
;;; a count and a token budget, requests run with BOUNDED parallelism (a fixed
;;; thread pool, so a huge bulk embed never spawns thousands of threads), and a
;;; 429/RESOURCE_EXHAUSTED / transient-5xx request is retried with exponential
;;; backoff + jitter (slow the embed, never lose a batch). Token counts use the
;;; project chars/4 estimate (no tokenizer dep); over-conservative is fine.

(def ^:const max-text-tokens
  "Per-INPUT cap (Gemini's hard limit is 8,192 tokens). A text estimated over
   this is truncated before embedding so one input never exceeds the model cap;
   set just under 8,192 for chars/4-estimate slack."
  8000)

(def ^:const max-batch-tokens
  "Per-REQUEST token budget (conservative < the ~20k community-observed cap). A
   batch packs texts until adding one more would exceed this."
  18000)

(def ^:const max-batch-texts
  "Per-REQUEST text-count budget (conservative < the ~250 community-observed
   cap). Whichever of this and `max-batch-tokens` binds first closes a batch."
  100)

(def ^:const max-embed-concurrency
  "Max Gemini requests in flight at once during a bulk embed. A fixed thread
   pool of this size bounds BOTH concurrency and thread count, so embedding a
   huge corpus never spawns thousands of threads."
  6)

(def ^:const embed-max-retries
  "Max RETRIES for one batch request on a retryable (429 / 5xx) error before
   surfacing a clear error. Total attempts = this + 1."
  5)

(def ^:const embed-base-backoff-ms
  "Base delay for exponential backoff: retry `n` waits ~`base`*2^n ms + jitter."
  500)

(defn- truncate-to-token-cap
  "Truncate `text` so its estimated tokens never exceed `max-text-tokens` (the
  per-input model cap). Logs when it truncates. Returns `text` unchanged when
   already within cap."
  [^String text]
  (let [cap-chars (tokens/estimate-chars max-text-tokens)]
    (if (> (count text) cap-chars)
      (let [truncated (subs text 0 cap-chars)]
        (log/warn "embed: truncating oversized input from"
                  (tokens/estimate text) "tokens to"
                  (tokens/estimate truncated) "tokens — exceeds per-input cap")
        truncated)
      text)))

(defn- plan-batches
  "Greedy-pack `indexed-texts` (seq of `[orig-idx text]`) into batches honoring
   BOTH `max-batch-texts` and `max-batch-tokens`. A truncated text always fits a
   batch alone (≤ `max-text-tokens` < `max-batch-tokens`). Returns a vector of
   batches, each a vector of `[orig-idx text]`."
  [indexed-texts]
  (let [{:keys [batches cur]}
        (reduce
         (fn [{:keys [batches cur cur-tokens]} [_ text :as it]]
           (let [t (tokens/estimate text)]
             (if (and (seq cur)
                      (or (>= (count cur) max-batch-texts)
                          (> (+ cur-tokens t) max-batch-tokens)))
               {:batches (conj batches cur) :cur [it] :cur-tokens t}
               {:batches batches :cur (conj cur it) :cur-tokens (+ cur-tokens t)}))
           )
         {:batches [] :cur [] :cur-tokens 0}
         indexed-texts)]
    (cond-> batches (seq cur) (conj cur))))

(defn- retryable-embed-error?
  "True iff `t` (or any cause) looks like a Gemini rate-limit (429 /
   RESOURCE_EXHAUSTED / quota) or a transient server error (5xx / UNAVAILABLE /
   DEADLINE_EXCEEDED) — the errors worth backing off + retrying rather than
   losing the batch."
  [^Throwable t]
  (boolean
   (loop [^Throwable e t]
     (when e
       (if (re-find #"(?i)429|resource[_ ]?exhausted|quota|rate.?limit|503|500|unavailable|deadline.?exceeded|internal error"
                    (str (.getName (class e)) " " (.getMessage e)))
         true
         (recur (.getCause e)))))))

(defn- backoff-sleep!
  "Sleep ~`embed-base-backoff-ms`*2^attempt ms plus up to 50% jitter (capped at
   30s) — the wait before retrying a rate-limited batch."
  [attempt]
  (let [base   (* embed-base-backoff-ms (long (Math/pow 2 attempt)))
        capped (min base 30000)
        jitter (long (* (rand) 0.5 capped))]
    (Thread/sleep (+ capped jitter))))

(defn- embed-batch!
  "Embed ONE batch of (already-truncated) `texts` in a single Gemini request,
   retrying retryable (429 / 5xx) errors with exponential backoff. Returns a
   vector of normalized float vectors aligned to `texts`. Throws a clear
   `:seon.embed/embed-request-failed` error after `embed-max-retries`."
  [^Client client texts]
  (let [jtexts (java.util.ArrayList. ^java.util.Collection (vec texts))]
    (loop [attempt 0]
      (let [outcome (try
                      (let [^EmbedContentResponse resp
                            (.embedContent (.models client) embedding-model
                                           jtexts (doc-config))]
                        (let [embs (-> resp .embeddings .get)]
                          {:ok (mapv (fn [i] (embedding->vec (.get embs i)))
                                     (range (.size embs)))}))
                      (catch Throwable t {:error t}))]
        (if-let [vs (:ok outcome)]
          vs
          (let [^Throwable t (:error outcome)
                retryable    (retryable-embed-error? t)]
            (if (and retryable (< attempt embed-max-retries))
              (do (log/warn "embed: retryable error on batch (attempt"
                            (inc attempt) "of" (inc embed-max-retries) ") —"
                            (.getMessage t) "— backing off")
                  (backoff-sleep! attempt)
                  (recur (inc attempt)))
              (throw (ex-info (str "seon.embed/embed-batch!: Gemini request failed"
                                   (when retryable
                                     (str " after " (inc embed-max-retries) " attempts")))
                              {:seon.embed/error      :seon.embed/embed-request-failed
                               :seon.embed/batch-size (count texts)
                               :seon.embed/retryable  retryable}
                              t)))))))))

(defn embed-texts
  "Embed document strings with Gemini, returning normalized `:seon.embed/vectors`
   aligned to input order (one L2-normalized float vector per text, length
   `embedding-dim`).

   BULK-SAFE for an arbitrarily large input: each text is truncated under the
   per-input token cap (`max-text-tokens`); texts are packed into requests
   honoring BOTH `max-batch-texts` and `max-batch-tokens`; requests run with
   bounded parallelism (`max-embed-concurrency`); each request retries
   429/RESOURCE_EXHAUSTED + transient 5xx with exponential backoff. A small
   input is still a single request."
  {:malli/schema [:=> [:cat :seon.embed/embed-texts-request]
                  :seon.embed/embed-texts-response]}
  [{:seon.embed/keys [texts]}]
  (let [client (or (gemini-client)
                   (throw (ex-info "seon.embed: embedding requested but GEMINI_API_KEY is not set"
                                   {:seon.embed/error :seon.embed/no-api-key})))]
    (if (empty? texts)
      {:seon.embed/vectors []}
      (let [truncated (mapv truncate-to-token-cap texts)
            indexed   (vec (map-indexed vector truncated))
            batches   (plan-batches indexed)
            pool      (Executors/newFixedThreadPool
                       (min max-embed-concurrency (count batches)))
            out       (object-array (count truncated))]
        (try
          (let [tasks   (mapv (fn [batch]
                                (reify Callable
                                  (call [_] (embed-batch! client (mapv second batch)))))
                              batches)
                futures (vec (.invokeAll pool ^java.util.Collection tasks))]
            (dotimes [b (count batches)]
              (let [batch (nth batches b)
                    vecs  (.get ^Future (nth futures b))]
                (dorun (map (fn [[idx _] v] (aset out (int idx) v)) batch vecs)))))
          (finally (.shutdown pool)))
        {:seon.embed/vectors (vec out)}))))

(defn embed-text
  "Embed a single document string with Gemini. Returns the normalized
   `:seon.embed/vector`. Thin wrapper over `embed-texts` (one-element batch)."
  {:malli/schema [:=> [:cat :seon.embed/embed-text-request]
                  :seon.embed/embed-text-response]}
  [{:seon.embed/keys [text]}]
  {:seon.embed/vector (first (:seon.embed/vectors
                              (embed-texts {:seon.embed/texts [text]})))})

;;; --- Source-hash (the cache key) -------------------------------------------

(defn- sha-256-hex [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bs))))

(defn- compose-doc
  "Compose a configured trigger's document for one entity."
  [embeddables trigger-attr entity-map]
  (when-let [compose (get embeddables trigger-attr)]
    (let [s (compose entity-map)]
      (when (and (string? s) (not (str/blank? s))) s))))

;;; --- ensure-embedding! / reindex! (single-entity, off the write lock) ------
;;;
;;; These return TX-DATA (a vector of datahike forms asserting `:seon/embedding`
;;; + `:seon.embed/source-hash`), they do NOT transact. The CALLER transacts —
;;; so the Gemini network call happens BEFORE `d/transact`, never inside a tx
;;; listener and never while holding the conn (research §8 + the locked
;;; constraint). `ensure-embedding!` honors the hash cache; `reindex!` forces.

(defn- entity-with-trigger
  "The single configured trigger-attr present in `entity-map`, or nil. (An
   entity with two trigger-attrs is degenerate; first-wins, deterministically
   by sorted attr.)"
  [embeddables entity-map]
  (some (fn [a] (when (contains? entity-map a) a))
        (sort (keys embeddables))))

(defn- embedding-tx-for
  "Given an entity's `:db/id` (or identity-bearing map), the composed `text`,
   and its `hash`, embed `text` and return tx-data asserting `:seon/embedding`
   + `:seon.embed/source-hash` onto `id-ref`. `id-ref` is whatever datahike
   accepts to identify the entity in a follow-on map (a numeric eid, a tempid
   string, or a lookup-ref vector)."
  [id-ref text hash]
  (let [v (:seon.embed/vector (embed-text {:seon.embed/text text}))]
    [{:db/id              id-ref
      :seon/embedding     v
      :seon.embed/source-hash hash}]))

(defn ensure-embedding!
  "Return tx-data that brings `entity-map`'s `:seon/embedding` up to date for
   its configured trigger-attr, embedding via Gemini ONLY when the composed
   document's SHA-256 differs from `current-hash` (the entity's stored
   `:seon.embed/source-hash`, nil when never embedded). When the hash matches,
   returns `[]` (no Gemini call, no tx). `id-ref` identifies the target entity
   in the returned assertion (numeric eid / tempid / lookup-ref).

   Off the write lock by construction: it embeds and returns data; the caller
   transacts."
  {:malli/schema [:=> [:catn
                       [:seon.embed/embeddables :seon.embed/embeddables]
                       [:id-ref :any]
                       [:entity-map [:map-of :keyword :any]]
                       [:current-hash [:or :nil :string]]]
                  [:vector :any]]}
  [embeddables id-ref entity-map current-hash]
  (if-let [attr (entity-with-trigger embeddables entity-map)]
    (if-let [text (compose-doc embeddables attr entity-map)]
      (let [hash (sha-256-hex text)]
        (if (= hash current-hash)
          []                                   ; unchanged — SKIP the paid call
          (embedding-tx-for id-ref text hash)))
      [])
    []))

(defn reindex!
  "Like `ensure-embedding!` but FORCES a re-embed regardless of the stored
   hash. Returns the asserting tx-data (or `[]` when the entity carries no
   configured trigger-attr / composes to blank)."
  {:malli/schema [:=> [:catn
                       [:seon.embed/embeddables :seon.embed/embeddables]
                       [:id-ref :any]
                       [:entity-map [:map-of :keyword :any]]]
                  [:vector :any]]}
  [embeddables id-ref entity-map]
  (if-let [attr (entity-with-trigger embeddables entity-map)]
    (if-let [text (compose-doc embeddables attr entity-map)]
      (embedding-tx-for id-ref text (sha-256-hex text))
      [])
    []))

;;; --- augment-tx-with-embeddings (explicit writer transform) ----------------
;;;
;;; Scans an incoming tx-data vector for entity MAPS carrying a configured
;;; trigger-attr whose composed-text hash CHANGED vs the entity's current
;;; stored hash, embeds them (Gemini, BEFORE the d/transact), and APPENDS
;;; assertion maps merging `:seon/embedding` + `:seon.embed/source-hash`.
;;;
;;; Cheap no-op when the tx carries no trigger attrs (the common case): one
;;; set-intersection over the tx's attr keys, then return the tx untouched.
;;;
;;; Only entity-MAP tx items are considered (the shape the pod's tee emits and
;;; the kb example uses). Vector forms ([:db/add ...]) are passed through
;;; untouched — the embed pipeline is map-shaped.

(defn- map-trigger-attr
  "The configured trigger-attr present in a tx-item map, or nil."
  [triggers item]
  (when (map? item)
    (some (fn [a] (when (contains? item a) a)) (sort triggers))))

(defn- resolve-id-ref
  "An id-ref the appended assertion can use to hit the SAME entity the tx-item
   creates/updates. Prefers an explicit `:db/id`; else the entity's identity
   attr as a lookup-ref `[ident-attr value]` (so the assertion upserts onto the
   same entity datahike resolves the tx-item to). `db` supplies the live schema
   to find identity attrs. Returns nil when neither is available (can't target
   → skip, don't guess)."
  [db item]
  (or (:db/id item)
      (let [schema (:schema db)]
        (some (fn [[a v]]
                (when (and (keyword? a)
                           (= :db.unique/identity (get-in schema [a :db/unique])))
                  [a v]))
              item))))

(defn- current-hash-for
  "The entity's currently-stored `:seon.embed/source-hash` (nil when absent or
   the entity doesn't exist yet). `id-ref` is a numeric eid or a lookup-ref."
  [db id-ref]
  (try
    (:seon.embed/source-hash (d/pull db [:seon.embed/source-hash] id-ref))
    (catch Throwable _ nil)))

(defn augment-tx-with-embeddings
  "Return `tx-data` with embedding assertions APPENDED for every entity-map item
   carrying a configured trigger-attr whose composed-document hash differs from
   the entity's stored hash. Embeds via Gemini BEFORE the caller's `d/transact`
   (never inside a listener / under the conn). No-op (returns `tx-data`
   unchanged, no Gemini call) when no item carries a trigger attr.

   `db` is the conn's CURRENT db value (for the schema + stored-hash lookups).
   `tx-data` is the raw incoming tx-data vector."
  {:malli/schema [:=> [:catn
                       [:seon.embed/embeddables :seon.embed/embeddables]
                       [:db :any]
                       [:tx-data [:vector :any]]]
                  [:vector :any]]}
  [embeddables db tx-data]
  (let [triggers (set (keys embeddables))]
    ;; Feature OFF (SEON_EMBED unset) OR no triggers OR no GEMINI_API_KEY →
    ;; embedding inactive: pass the tx through UNTOUCHED (byte-identical). Writes
    ;; never fail, and never call Gemini, just because embedding is unavailable.
    (if (or (not (embed-feature-enabled?))
            (empty? triggers)
            (nil? (gemini-client)))
      tx-data
      (let [;; collect {:id-ref :text :hash} for items that need (re)embedding
            pending
            (->> tx-data
                 (keep (fn [item]
                         (when-let [attr (map-trigger-attr triggers item)]
                           (when-let [text (compose-doc embeddables attr item)]
                             (let [hash    (sha-256-hex text)
                                   id-ref  (resolve-id-ref db item)]
                               (when (and id-ref
                                          (not= hash (current-hash-for db id-ref)))
                                 {:id-ref id-ref :text text :hash hash}))))))
                 vec)]
        (if (empty? pending)
          tx-data
          ;; ONE batch Gemini request for all changed docs (BEFORE transact).
          (let [{:seon.embed/keys [vectors]}
                (embed-texts {:seon.embed/texts (mapv :text pending)})
                assertions
                (mapv (fn [{:keys [id-ref hash]} v]
                        {:db/id                  id-ref
                         :seon/embedding         v
                         :seon.embed/source-hash hash})
                      pending vectors)]
            (into (vec tx-data) assertions)))))))

;;; --- Bounded backfill (boot) -----------------------------------------------
;;;
;;; On boot, embed entities that carry a configured trigger-attr but lack a
;;; current `:seon/embedding` (no source-hash, or a stale one). BOUNDED: cap N
;;; this pass, log what's deferred — never embed thousands synchronously at
;;; boot (cost + latency). One batch Gemini request, one transact.

(def ^:const backfill-cap
  "Max entities embedded per backfill pass — one bulk `embed-texts` call (which
   internally batches + parallelizes) and one transact per pass. The rest are
   logged as deferred and picked up by `drain-backfill!`'s next pass (or on
   their next write)."
  256)

(schema/register! :seon.embed/backfill!-response
                  [:map
                   [:seon.embed/embedded :int]
                   [:seon.embed/deferred :int]])

(defn- needs-embedding-eids
  "Entity-ids carrying `trigger-attr` whose stored `:seon.embed/source-hash`
   does NOT match the current composed-document hash (covers both never-embedded
   and stale). Returns a vector of `[eid text hash]` for the rows that need work."
  [embeddables db trigger-attr]
  (let [rows (d/q '[:find ?e
                    :in $ ?attr
                    :where [?e ?attr]]
                  db trigger-attr)]
    (->> rows
         (keep (fn [[eid]]
                 (let [ent  (d/pull db '[*] eid)
                       text (compose-doc embeddables trigger-attr ent)]
                   (when text
                     (let [hash (sha-256-hex text)]
                       (when (not= hash (:seon.embed/source-hash ent))
                         [eid text hash]))))))
         vec)))

(defn backfill!
  "Embed a bounded batch across the configured trigger attributes.

   Selects entities that lack a current `:seon/embedding`, issues one batch
   Gemini request, then one transaction. Returns
   `{:seon.embed/embedded n :seon.embed/deferred m}` — m is
   how many eligible entities were left for a later pass (the bound). A no-op
   (0/0) when nothing needs embedding."
  {:malli/schema [:=> [:catn
                       [:seon.embed/embeddables :seon.embed/embeddables]
                       [:conn :any]]
                  :seon.embed/backfill!-response]}
  [embeddables conn]
  ;; Feature OFF (SEON_EMBED unset) → no-op before any db scan or Gemini call.
  (if-not (embed-feature-enabled?)
    {:seon.embed/embedded 0 :seon.embed/deferred 0}
    (let [db      (d/db conn)
          triggers (keys embeddables)
          all     (vec (mapcat #(needs-embedding-eids embeddables db %) triggers))
          total   (count all)
          batch   (vec (take backfill-cap all))
          deferred (max 0 (- total (count batch)))]
      ;; Nothing to embed, OR no GEMINI_API_KEY (embedding inactive) → no-op.
      (if (or (empty? batch) (nil? (gemini-client)))
        {:seon.embed/embedded 0 :seon.embed/deferred 0}
        (let [{:seon.embed/keys [vectors]}
              (embed-texts {:seon.embed/texts (mapv second batch)})
              tx (mapv (fn [[eid _text hash] v]
                         {:db/id                  eid
                          :seon/embedding         v
                          :seon.embed/source-hash hash})
                       batch vectors)]
          (d/transact conn tx)
          (when (pos? deferred)
            (log/info "embed backfill bounded — embedded" (count batch)
                      "deferred" deferred "(picked up on next drain pass)"))
          {:seon.embed/embedded (count batch) :seon.embed/deferred deferred})))))

(def ^:const drain-pass-cap
  "Hard cap on `drain-backfill!` passes, so a pathological or actively-growing
   corpus can never spin boot forever. At `backfill-cap` (64) entities/pass this
   covers 64*`drain-pass-cap` entities — far beyond any realistic seed corpus;
   anything still deferred after that logs and waits for the next boot."
  256)

(defn drain-backfill!
  "Run `backfill!` repeatedly until nothing is deferred (or `drain-pass-cap`
   passes elapse) so the WHOLE embeddable corpus becomes searchable at boot, not
   just the first `backfill-cap` entities. Each pass is still ONE bounded Gemini
   batch + ONE transact (the per-pass bound `backfill!` enforces); draining just
   keeps issuing passes until coverage is complete. This is the 'later pass' the
   single-pass `backfill!` defers to — static seed fns are written once and never
   rewritten, so without an explicit drain the deferred remainder would never be
   embedded and stays invisible to retrieval. Returns the cumulative
   `{:seon.embed/embedded n :seon.embed/deferred m}` (m = still-deferred after the
   pass cap, normally 0). No-op (0/0) when the feature is off or nothing needs
   embedding."
  {:malli/schema [:=> [:catn
                       [:seon.embed/embeddables :seon.embed/embeddables]
                       [:conn :any]]
                  :seon.embed/backfill!-response]}
  [embeddables conn]
  (loop [pass 0 total-embedded 0]
    (let [{:seon.embed/keys [embedded deferred]}
          (backfill! embeddables conn)
          embedded' (+ total-embedded embedded)]
      (if (and (pos? embedded) (pos? deferred) (< (inc pass) drain-pass-cap))
        (recur (inc pass) embedded')
        (do
          (when (pos? deferred)
            (log/warn "embed drain-backfill! hit pass cap" drain-pass-cap
                      "— embedded" embedded' "still deferred" deferred
                      "(picked up on next boot)"))
          {:seon.embed/embedded embedded' :seon.embed/deferred deferred})))))

;;; --- KNN helper (direct, for harness + the query side) ---------------------
;;;
;;; The 3rd positional arg to Proximum's `-slice-ordered` is the ENTITY-FILTER:
;;; an `EntityBitSet` (built from a seq of eids via `es/entity-bitset-from-longs`)
;;; that the bridge tests each candidate against with `es/entity-bitset-contains?`
;;; (proximum.clj:73-76 → `prox/search-filtered`). nil = no scoping (search the
;;; whole index). This is the TYPE-SCOPING seam: the pod resolves a datalog
;;; `:where` to an eid set on its LOCAL db and passes those eids; the server
;;; restricts KNN to them. New scopes need no schema change — any `:where`.

(defn knn
  "K-nearest neighbours over the live Proximum index on `db` for an
   already-normalized query `qvec` (a seq of floats, length `embedding-dim`).
   Returns up to `k` `{:entity-id <eid> :distance <d>}` rows, distance-ascending
   — an empty vector when the index has no neighbours. When `eids` is a non-empty
   seq, the search is SCOPED to only those entity-ids (an `EntityBitSet`
   entity-filter); nil/empty `eids` searches the whole index. Returns nil only
   when the index isn't live on `db`."
  {:malli/schema [:function
                  [:=> [:catn [:db :any] [:qvec [:sequential :float]] [:k :int]]
                   [:or :nil [:vector [:map-of :keyword :any]]]]
                  [:=> [:catn [:db :any] [:qvec [:sequential :float]] [:k :int]
                              [:eids [:or :nil [:sequential :int]]]]
                   [:or :nil [:vector [:map-of :keyword :any]]]]]}
  ([db qvec k] (knn db qvec k nil))
  ([db qvec k eids]
   (when-let [vt (get-in db [:secondary-indices index-ident])]
     (let [entity-filter (when (seq eids) (es/entity-bitset-from-longs eids))]
       (sec/-slice-ordered vt {:vector (float-array qvec) :k k}
                           entity-filter nil :asc nil)))))

;;; ===========================================================================
;;; P2-C — the embedding QUERY side (NL query → KNN over the wire)
;;; ===========================================================================
;;;
;;; The pod is READ-ONLY and has NO Proximum/Gemini — query embedding + KNN
;;; happen HERE, on the database server (it owns the key + the index). The pod sends
;;; query TEXT (+ an optional eid set for type-scoping); this side embeds the
;;; query WITH a retrieval-instruction PREFIX (v2 has no taskType, so the
;;; instruction goes in the text — DOCUMENTS get no prefix, only queries do),
;;; runs KNN with the eid entity-filter, and returns `[{:eid :distance} …]`. The
;;; pod pulls full source LOCALLY from those eids (reads are local lazy db
;;; values). See `seon.embed` (CLJS sibling) for the pod-side `search`/
;;; `search-pull`.

(def ^:const ^String query-instruction-prefix
  "The retrieval-instruction prefix prepended to a QUERY before embedding.
   gemini-embedding-2 dropped taskType, so the retrieval instruction rides the
   text itself. DOCUMENTS are embedded with NO prefix (see `embed-text`); only
   queries get this, so the query and document live in compatible spaces while
   the instruction nudges the query toward retrieval. (The shape p2b-gate proved
   live.)"
  "Retrieve the entry whose content best answers this request:\n")

(defn query-vec
  "Embed an NL query string for KNN: prepend `query-instruction-prefix`, then
   embed via Gemini (same model/dim/normalize as documents). Returns the
   normalized query `:seon.embed/vector`."
  {:malli/schema [:=> [:cat :seon.embed/embed-text-request]
                  :seon.embed/embed-text-response]}
  [{:seon.embed/keys [text]}]
  (embed-text {:seon.embed/text (str query-instruction-prefix text)}))

;;; --- knn-search request/response shapes ------------------------------------

(schema/register! :seon.embed/query :seon.embed/text)         ; NL query text
(schema/register! :seon.embed/k [:int {:min 1}])
(schema/register! :seon.embed/eid :int)
(schema/register! :seon.embed/eids [:set :seon.embed/eid])
(schema/register! :seon.embed/distance :double)
(schema/register! :seon.embed/hit
                  [:map
                   [:seon.embed/eid :seon.embed/eid]
                   [:seon.embed/distance :seon.embed/distance]])
(schema/register! :seon.embed/hits [:vector :seon.embed/hit])

(schema/register! :seon.embed/knn-search-request
                  [:map
                   [:seon.embed/query :seon.embed/query]
                   [:seon.embed/k :seon.embed/k]
                   [:seon.embed/eids {:optional true} :seon.embed/eids]])
(schema/register! :seon.embed/knn-search-response
                  [:map [:seon.embed/hits :seon.embed/hits]])

(defn knn-search
  "Embed the NL `:seon.embed/query` (with the retrieval prefix) and run KNN over
   the live Proximum index on `db`, scoped to `:seon.embed/eids` when present.
   Returns `{:seon.embed/hits [{:seon.embed/eid e :seon.embed/distance d} …]}`,
   distance-ascending. The Gemini call happens HERE (the database server owns the
   key); the pod sends only the query TEXT + the optional eid scope.

   `db` is the conn's current db value (third-party datahike handle, hence
   `:any`). An index that isn't live yields no hits (empty vector)."
  {:malli/schema [:=> [:catn [:db :any] [:request :seon.embed/knn-search-request]]
                  :seon.embed/knn-search-response]}
  [db {:seon.embed/keys [query k eids]}]
  (let [qvec (:seon.embed/vector (query-vec {:seon.embed/text query}))
        rows (or (knn db qvec k eids) [])]
    {:seon.embed/hits (mapv (fn [{:keys [entity-id distance]}]
                              {:seon.embed/eid      (long entity-id)
                               :seon.embed/distance (double distance)})
                            rows)}))

;;; --- Explicit writer initialization ----------------------------------------

(schema/register! :seon.embed/initialized? :boolean)

(defn initialize-database!
  "Initialize optional embedding resources for one writer database."
  {:malli/schema [:=> [:catn
                       [:seon.embed/embeddables :seon.embed/embeddables]
                       [:conn :any]]
                  [:map [:seon.embed/initialized? :seon.embed/initialized?]]]}
  [embeddables conn]
  (if-not (embed-feature-enabled?)
    {:seon.embed/initialized? false}
    (do
      (install! conn)
      (try
        ;; Static seed functions are written once; drain bounded passes so
        ;; deferred rows do not stay invisible forever.
        (drain-backfill! embeddables conn)
        (catch Throwable throwable
          (log/warn throwable
                    "embedding backfill failed during database initialization; rows will embed on a later write")))
      {:seon.embed/initialized? true})))
