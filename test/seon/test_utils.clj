(ns seon.test-utils
  "Testing utilities and fixtures for Seon.

  Provides:
  - Datahike-on-:memory test fixture (`with-test-db`,
    `with-test-db-fixture`) — the canonical fixture for tests that
    exercise the live `seon.db` API. Stands up an isolated datahike
    flow per test, binds `seon.db/*datahike-flow*`, and tears down on
    exit. Never touches the running orchestrator's `:seon.db/flow`.
  - `transact-full-graph!` — canonical helper for transacting a
    fully-extracted `seon.graph.extract/extract-graph-from-file` map
    into a fixture db. Handles the shape↔entry cycle via stub-then-fill
    and Integer→Long coercion of row attributes. Use this rather than
    re-deriving the dependency order inline in each test.
  - Legacy datalevin test helpers (`with-temp-conn`, `with-test-datalevin`)
    — retained until the apply-everywhere migration completes. New
    tests use the datahike fixture above.
  - Time helpers for test data.

  Design doc: `docs/prds/datahike-migration/test-fixture-design.md`."
  (:require [clojure.test :refer [is]]
            [datalevin.constants :as dc]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datahike.flow :as dh-flow]
            [seon.db.datalevin.conn :as conn]
            [seon.graph.extract :as extract]
            [seon.schema :as schema])
  (:import [java.io File]
           [java.util UUID]))

;;; ---------------------------------------------------------------------------
;;; Datahike Test Fixture (canonical — use this for new tests)
;;; ---------------------------------------------------------------------------
;;;
;;; Stands up an isolated `:memory` datahike flow under a gensym-suffixed
;;; db-name, installs the caller-provided schemas, binds
;;; `seon.db/*datahike-flow*` to it, and runs the body. All
;;; `seon.db/transact!`, `seon.db/query`, etc. against the body's
;;; declared namespaces route through the fixture's flow — never the
;;; running orchestrator's `:seon.db/flow`.
;;;
;;; Each fixture instance gets a unique internal db-name suffix; the
;;; underlying konserve `:memory` store is identified by a UUID derived
;;; from that suffix, so the global registry never serves stale data to
;;; a fresh fixture. Tests are independent across both sequential and
;;; (future) parallel execution.
;;;
;;; Two entry points:
;;;
;;;   (with-test-db config body-fn)
;;;     Function. Builds the flow, runs `body-fn`, tears down. Returns
;;;     body-fn's value.
;;;
;;;   (with-test-db-fixture config)
;;;     Returns a `(fn [f] ...)` shape suitable for
;;;     `(use-fixtures :each (with-test-db-fixture {...}))`.
;;;
;;; Config (all keys namespaced under `seon.test-utils`):
;;;
;;;   ::namespaces  required vector of caller-facing db-name keywords
;;;                 (`:seon.runtime`, `:my.test/ns`, etc.). The fixture
;;;                 internally creates a unique suffixed db-name for
;;;                 each one.
;;;   ::schemas     optional map of db-name -> Malli :map schema, keyed
;;;                 by the SAME db-name passed in ::namespaces. The
;;;                 fixture forwards these to
;;;                 `seon.db.datahike.flow/build-datahike-flow!`'s
;;;                 `::namespace-schemas`. Schemas land at conn-process
;;;                 :init via `seon.db.datahike.schema/malli-map->datahike-schema`.
;;;
;;; Aliasing (lands via `seon.db`'s `::aliases` extension):
;;;   The fixture stashes a logical→internal alias map in
;;;   `*datahike-flow*`'s `::aliases` slot. The body fn calls
;;;   `(seon.db/transact! :seon.runtime [...])` with the LOGICAL name;
;;;   `seon.db` resolves through the alias map before dispatch, so the
;;;   call lands in the test's isolated conn-process. Entity stamping
;;;   still uses the logical name (Decision 7), preserving semantic
;;;   identity on the data. The alias map is also bound to
;;;   `*test-db-aliases*` for tests that want to inspect it.

(def ^:dynamic *test-db-aliases*
  "Map of logical db-name -> internal (suffixed) db-name, bound by
   `with-test-db-fixture` during each test. nil outside a fixture."
  nil)

(def ^:dynamic *test-db-flow*
  "The current fixture's flow-state map, bound by `with-test-db-fixture`
   during each test. nil outside a fixture. Tests rarely need this; use
   it only when calling into `seon.db.datahike.flow/subscribe!` or
   inspecting `::dh-flow/pids`."
  nil)

(schema/register! ::namespaces
                  [:vector {:min 1} :keyword])

(schema/register! ::schemas
                  [:map-of :keyword :any])

(schema/register! ::aliases
                  [:map-of :keyword :keyword])

(schema/register! ::flow ::dh-flow/flow-state)

(schema/register! ::with-test-db-config
                  [:map
                   [::namespaces ::namespaces]
                   [::schemas {:optional true} ::schemas]])

(schema/register! ::with-test-db-args
                  [:map
                   [::aliases ::aliases]
                   [::flow ::flow]])

(defn- ^:no-doc gensym-suffix
  "Build a gensym-style namespaced keyword that the fixture uses
   internally as the datahike-side db-name. Encodes the caller's logical
   name in the keyword's local part so logs stay readable, and
   appends a nanotime counter for collision-free isolation across both
   sequential test runs and (future) parallel runs."
  [logical-db-name counter]
  (let [base-slug (if (namespace logical-db-name)
                    (str (namespace logical-db-name) "__" (name logical-db-name))
                    (name logical-db-name))]
    (keyword "seon.test-utils.iso"
             (str base-slug "-" (System/nanoTime) "-" counter))))

(defn- ^:no-doc derive-internal-config
  "Build the alias map (logical -> internal) and the keyword-keyed
   `::namespace-schemas` map keyed by internal name."
  [{::keys [namespaces schemas]}]
  (let [counter (atom 0)
        aliases (into {}
                      (map (fn [logical]
                             [logical (gensym-suffix logical (swap! counter inc))]))
                      namespaces)
        internal-schemas (when schemas
                           (into {}
                                 (keep (fn [[logical internal]]
                                         (when-let [s (get schemas logical)]
                                           [internal s])))
                                 aliases))]
    {::aliases aliases
     ::internal-namespaces (vec (vals aliases))
     ::internal-schemas internal-schemas}))

(defn with-test-db
  "Run `body-fn` against an isolated datahike `:memory` flow.

   `config`:
     ::namespaces (required) — vector of caller-facing db-name keywords.
     ::schemas    (optional) — map of logical db-name -> Malli :map schema.

   `body-fn` is `(fn [args] ...)` where `args` is a map:
     ::aliases — map of logical db-name -> internal (suffixed) db-name.
                 The body can use logical names directly with `seon.db`
                 (`(db/transact! :seon.runtime [...])`); the alias map is
                 also installed on the bound `*datahike-flow*` so
                 `seon.db` resolves it transparently. Tests may consult
                 the map for introspection or to bypass `seon.db`.
     ::flow    — the underlying `seon.db.datahike.flow/flow-state` map,
                 for tests that need to call `dh-flow/subscribe!` or
                 inspect `::dh-flow/pids` directly. Most tests won't.

   The body's return value is returned.

   Cleanup runs in `finally`: pauses the flow, halts conn-processes
   (which release each datahike connection), and unbinds
   `seon.db/*datahike-flow*`. The konserve memory-store-registry entry
   for each internal name remains in the global registry until JVM
   exit — design doc covers why this is acceptable."
  {:malli/schema [:=> [:cat ::with-test-db-config fn?] :any]}
  [config body-fn]
  (let [{::keys [aliases internal-namespaces internal-schemas]}
        (derive-internal-config config)
        build-req (cond-> #:seon.db.datahike.flow{:namespaces internal-namespaces
                                                  :backend :memory}
                    (seq internal-schemas)
                    (assoc :seon.db.datahike.flow/namespace-schemas
                           internal-schemas))
        built (dh-flow/build-datahike-flow! build-req)
        ;; Install the logical→internal alias map directly on the flow-state
        ;; so `seon.db` resolves logical db-names transparently. See
        ;; `seon.db/resolve-db-name` (the 2026-05-14 fixture-aliasing extension).
        fs (assoc built :seon.db.datahike.flow/aliases aliases)]
    (try
      (binding [db/*datahike-flow* fs]
        (body-fn {::aliases aliases
                  ::flow fs}))
      (finally
        (try
          (dh-flow/stop-datahike-flow!
            #:seon.db.datahike.flow{:flow (::dh-flow/flow fs)})
          (catch Throwable _))))))

(defn with-test-db-fixture
  "Build a `clojure.test`-shaped fixture for `use-fixtures :each`.

   The fixture builds an isolated datahike flow per test, binds it on
   `seon.db/*datahike-flow*`, and tears down on exit. The flow's alias
   map is stashed in `*test-db-aliases*` so the body can resolve
   logical-to-internal db-names.

   Example:

     (use-fixtures :each
       (tu/with-test-db-fixture
         {::tu/namespaces [:seon.runtime]
          ::tu/schemas    {:seon.runtime my.ns/runtime-malli-schema}}))

     (deftest my-test
       (let [runtime (tu/test-db-name :seon.runtime)]
         (db/transact! runtime [{:my/id \"x\"}])
         (is (= 1 (count (db/query runtime '[:find ?e :where [?e :my/id _]]))))))

   See `with-test-db` for config details."
  {:malli/schema [:=> [:cat ::with-test-db-config] fn?]}
  [config]
  (fn run-fixture [f]
    (with-test-db config
      (fn [{::keys [aliases flow]}]
        (binding [*test-db-aliases* aliases
                  *test-db-flow* flow]
          (f))))))

(defn test-db-name
  "Resolve a logical db-name (`:seon.runtime`) to the fixture's internal
   suffixed name (`:seon.test-utils.iso/seon.runtime-...-1`).

   Returns the logical name unchanged if no fixture is active (so the
   helper can be used in tests that may or may not use the fixture).
   Throws if a fixture is active but doesn't know the requested
   db-name — that's almost always a config bug (forgot to add the
   db-name to `::namespaces`)."
  {:malli/schema [:=> [:cat :keyword] :keyword]}
  [logical-db-name]
  (if-let [aliases *test-db-aliases*]
    (or (get aliases logical-db-name)
        (throw (ex-info
                 (str "No fixture alias for " logical-db-name
                      ". Did you add it to ::namespaces in with-test-db-fixture's config?")
                 {:requested logical-db-name
                  :known (set (keys aliases))})))
    logical-db-name))

;;; ---------------------------------------------------------------------------
;;; Full-Graph Transact Helper
;;; ---------------------------------------------------------------------------
;;;
;;; `transact-full-graph!` encapsulates the dependency-order trap of
;;; transacting a fully-extracted `seon.graph.extract/extract-graph-from-file`
;;; map into a datahike-backed db.
;;;
;;; Two issues need handling, both surfaced during the workout-test
;;; migration (smells #11 and #13 in
;;; `docs/prds/datahike-migration/remaining.md`):
;;;
;;;   1. Shapes and entries form a cycle — shapes carry
;;;      `:seon.shape/entries` lookup-refs to entries; some entries
;;;      carry `:seon.entry/value-shape` lookup-refs back to shapes.
;;;      Datahike resolves lookup-refs only against pre-existing
;;;      entities, not same-tx tempids — so we must transact shape
;;;      stubs (id-only) before entries, then fill shapes afterwards.
;;;
;;;   2. `seon.graph.extract` emits `:seon.fn/row`, `:seon.var/row`,
;;;      and `:seon.call/row` as `java.lang.Integer`. The datahike
;;;      bridge maps Malli `:int` to `:db.type/long` and strictly
;;;      rejects Integer. We Long-coerce those attrs at transact time.
;;;
;;; Canonical dependency order:
;;;
;;;     namespaces → specs → shape stubs (id-only) → entries
;;;       → full shapes → functions → vars → call-edges → ns-deps

(def ^:private graph-long-attrs
  "Attrs the datahike bridge stores as `:db.type/long` but
   `seon.graph.extract` emits as `java.lang.Integer`. Long-coerced
   at transact time inside `transact-full-graph!`."
  #{:seon.fn/row :seon.var/row :seon.call/row})

(defn- coerce-ints->longs
  "Coerce any `Integer` values on long-typed datahike attrs to `Long`
   so the datahike bridge accepts the entity map. See smell #11."
  [coll]
  (mapv (fn [m]
          (reduce-kv (fn [acc k v]
                       (assoc acc k (if (and (graph-long-attrs k) (integer? v))
                                      (long v)
                                      v)))
                     {} m))
        coll))

(def graph-categories
  "Ordered list of graph categories the helper transacts. Each entry is
   `[category-key extract-key]`. The order is the canonical dependency
   order — specs feed function refs; shape stubs precede entries to
   break the shape↔entry cycle; full shapes follow entries; functions /
   vars / call-edges / ns-deps consume everything above. `:shape-stubs`
   is synthetic (derived from shapes); the rest map 1:1 to
   `seon.graph.extract` keys."
  [[:namespaces  ::extract/namespaces]
   [:specs       ::extract/specs]
   [:shape-stubs ::extract/shapes]  ;; transacted id-only
   [:entries     ::extract/entries]
   [:shapes      ::extract/shapes]
   [:functions   ::extract/functions]
   [:vars        ::extract/vars]
   [:call-edges  ::extract/call-edges]
   [:ns-deps     ::extract/ns-deps]])

(def default-include
  "Default category set for `transact-full-graph!`. Mirrors what
   workout-test transacted pre-helper (specs, the shape↔entry pair,
   functions). Callers that want to populate vars / call-edges /
   ns-deps must pass `::include` explicitly AND widen their fixture
   schema to cover the corresponding attrs."
  #{:specs :shape-stubs :entries :shapes :functions})

(schema/register! ::db-name :keyword)
(schema/register! ::graph :any)
(schema/register! ::include
                  [:set [:enum :namespaces :specs :shape-stubs :entries
                         :shapes :functions :vars :call-edges :ns-deps]])
(schema/register! ::counts [:map-of :keyword :int])

(schema/register! ::transact-full-graph-request
                  [:map
                   [::db-name ::db-name]
                   [::graph ::graph]
                   [::include {:optional true} ::include]])

(schema/register! ::transact-full-graph-response
                  [:map
                   [::counts ::counts]])

(defn- ^:no-doc payload-for
  "Build the entity coll for a single graph category, applying
   stub-projection for `:shape-stubs` and Long-coercion for the
   Integer-row categories."
  [category graph]
  (case category
    :shape-stubs (mapv #(select-keys % [:seon.shape/id])
                       (::extract/shapes graph))
    :functions   (coerce-ints->longs (::extract/functions graph))
    :vars        (coerce-ints->longs (::extract/vars graph))
    :call-edges  (coerce-ints->longs (::extract/call-edges graph))
    (vec (get graph (second (first (filter #(= category (first %))
                                           graph-categories)))))))

(defn transact-full-graph!
  "Transact a fully-extracted `seon.graph.extract/extract-graph-from-file`
   map into `db-name` in the canonical dependency order. Handles the
   shape↔entry lookup-ref cycle via stub-then-fill (datahike resolves
   lookup-refs only against pre-existing entities) and Long-coerces
   `:seon.fn/row` / `:seon.var/row` / `:seon.call/row` Integers to
   match the datahike `:db.type/long` bridge.

   Order: namespaces → specs → shape stubs → entries → full shapes →
   functions → vars → call-edges → ns-deps.

   `::include` (optional) — set of category keywords to transact.
   Defaults to `default-include` (specs / shape-stubs / entries /
   shapes / functions), matching workout-test's pre-helper behavior.
   Callers that opt in to `:namespaces` / `:vars` / `:call-edges` /
   `:ns-deps` must widen their fixture schema to cover the
   corresponding attrs, or datahike rejects the transact at
   schema-check time.

   Returns `{::counts {<category> <n>}}` — counts of entities actually
   transacted per included category."
  {:malli/schema [:=> [:cat ::transact-full-graph-request]
                  ::transact-full-graph-response]}
  [{::keys [db-name graph include]
    :or {include default-include}}]
  (let [counts (reduce
                (fn [acc [category _]]
                  (if (contains? include category)
                    (let [payload (payload-for category graph)]
                      (if (seq payload)
                        (do (db/transact! db-name payload)
                            (assoc acc category (count payload)))
                        (assoc acc category 0)))
                    acc))
                {}
                graph-categories)]
    {::counts counts}))

;;; ---------------------------------------------------------------------------
;;; Legacy Test Node Fixture (stub)
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-node*
  "Dynamic var for test database node. Retained for backward compatibility."
  nil)

(defn with-test-node
  "Legacy fixture stub. Tests that need a database should use with-test-datalevin instead."
  [f]
  (f))

;;; ---------------------------------------------------------------------------
;;; Datalevin Test Helpers
;;; ---------------------------------------------------------------------------

(def ^:private fast-kv-opts
  "KV options for fast test databases. :nosync skips fsync for speed."
  {:flags #{:nordahead :writemap :mapasync :nosync}})

;; Reduce LMDB map size for all tests. Default is 1000 MiB per db; tests need only ~10 MiB.
;; This prevents OutOfMemoryError on direct buffer memory when many test connections are created.
(alter-var-root #'dc/*init-db-size* (constantly 10))

(defn with-small-db-size
  "Fixture that binds Datalevin init-db-size to 10 MiB for all tests in a namespace.
   Use as: (use-fixtures :once tu/with-small-db-size)"
  [f]
  (binding [dc/*init-db-size* 10]
    (f)))

(defn- delete-dir!
  "Recursively delete a directory and all its contents."
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))]
        (.delete ^File child)))))

(defn with-temp-conn
  "Create a temporary Datalevin connection, run f with it, then clean up.

   Uses d/create-conn (not d/get-conn) to avoid the global connection cache.
   Uses :nosync for speed. Connection is closed and directory deleted on exit.

   Usage:
     (with-temp-conn schema
       (fn [conn]
         (d/transact! conn [{:name \"test\"}])
         (is (= 1 (count (d/q '[:find ?e :where [?e :name _]] @conn))))))"
  ([f] (with-temp-conn {} f))
  ([db-schema f]
   (binding [dc/*init-db-size* 10]
     (let [dir  (str "tmp/test-" (System/nanoTime))
           conn (d/create-conn dir db-schema {:kv-opts fast-kv-opts})]
       (try
         (f conn)
         (finally
           (when-not (d/closed? conn)
             (d/close conn))
           (delete-dir! dir)))))))

(defn with-test-datalevin
  "Fixture that provides a temporary Datalevin connection for AI tests.
   Binds db/*conn-manager* with a fake manager mapping :seon.ai to a temp conn,
   and db/*direct-mode* to true so reads/writes bypass the infrastructure flow."
  [f]
  (binding [dc/*init-db-size* 10]
    (let [dir (str "tmp/dl-test-" (UUID/randomUUID))
          conn (d/create-conn dir {} {:kv-opts fast-kv-opts})
          fake-mgr {::conn/port 0
                    ::conn/connections (atom {:seon.ai {::conn/connection conn}})}]
      (try
        (binding [db/*direct-mode* true
                  db/*conn-manager* fake-mgr]
          (f))
        (finally
          (when-not (d/closed? conn)
            (d/close conn))
          (delete-dir! dir))))))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn gen-uuid
  "Generate a random UUID."
  []
  (UUID/randomUUID))

(defn days-ago
  "Create an Instant n days ago."
  [n]
  (.minus (java.time.Instant/now)
          (java.time.Duration/ofDays n)))

(defn days-from-now
  "Create an Instant n days from now."
  [n]
  (.plus (java.time.Instant/now)
         (java.time.Duration/ofDays n)))
