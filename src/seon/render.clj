(ns seon.render
  "THE ONE PROJECTION ROUTER. Any value reaches one bounded projection.

  Resolution is one ordered chain, most specific first:

  1. a declaration on the value itself;
  2. the requesting namespace's `render-<kind>` defn;
  3. the most-specific matching schema's attached declaration;
  4. the one admission-backed structural floor.

  A unit may wrap arbitrary data under `:seon.render/value`; declarations
  on that raw value outrank declarations on the wrapper. A requesting
  namespace is an explicit `:seon.render/namespace` symbol, never ambient
  `*ns*`, so the same database value renders reproducibly. Namespace and
  schema projection symbols remain late-resolved vars. There is no
  registration table, dispatch map, protocol, or consumer-side branch.

  WHY THIS EXISTS AS ITS OWN TINY NAMESPACE. The render contract
  (`docs/seon/architecture/ui.md`, \"The block and its two renders\")
  already had exactly this shape for exactly two kinds: `:seon.render/ai`
  → prompt text, `:seon.render/html` → a surface, selected by key
  presence with no stored discriminator, the symbol \"late-resolved each
  render\". The owner's direction is to admit that this was never about
  the UI: an error fact wants an `ai` projection (steering prose) and a
  `log` projection (a line), a failover notice wants `ai`, a metric
  wants something else. So the two-render rule becomes the special case
  of one open kind set, and the render contract keeps its two keys
  unchanged. Each new kind names its consumer; nothing here changes.

  RESOLUTION IS LATE AND VAR-BACKED, and that is load-bearing rather
  than incidental. `requiring-resolve` returns the VAR, and this
  namespace INVOKES the var rather than a fn it dereferenced earlier:
  re-evaluating the projection's `defn` against the running system
  changes the next render with no re-registration, which is the same
  hot-reload property `:seon.source/populate`,
  `:seon.cluster.loop/evaluate` and the schema gate's predicate-owner
  rule already rely on. A cached fn value would silently serve the old
  projection after a reload — the failure would look like a stale UI, so
  it is stated as a prohibition: NOTHING here memoizes a resolution.

  IT IS TOTAL, because it is on the error path. `seon.error`'s notices
  route through this router, so a router that threw would turn recording
  an error into a second error — the quarry's recursion fence
  (`src-old/seon/error.cljc:738-745`) restated for this owner. Every
  failure is therefore a flat `:seon.error/value`, and there is no
  `:seon.config/on-core-error` key: this namespace never panics, in any
  mode. A projection that throws is reported as a value naming its
  class, with the unit's declared symbol, so the broken projection is
  named rather than the caller.

  THE EXPLICIT KIND SET IS COMPUTED, never listed. `kinds` derives what a
  value explicitly declares — every key in the `seon.render`
  namespace whose value is a qualified symbol — so adding a kind to a
  producer makes it discoverable everywhere with no edit here. This is
  the no-hand-maintained-lists rule applied to the one place a registry
  would have been the obvious design.

  LITERALS ARE DECLARATIONS. An AI render may be a verbatim string and
  an HTML render may be a hiccup vector rather than a symbol.
  `declaration?` admits those two narrow runtime shapes, and `render`
  returns a non-symbol declaration as its own output.

  The structural floor is chosen by the requested boundary: HTML receives
  hiccup; every textual/open kind receives the AI text projection. The
  floor is deliberately not reported by `kinds`: it is universal capability,
  not a declaration stored redundantly on every value.

  Crash walk: pure resolution plus one call. Nothing here opens,
  commits, or holds anything, so a kill during a render loses a value
  that was never durable. Whether the PROJECTION is pure is the
  projection's own contract; the ones this repository ships are."
  (:require [datahike.api :as d]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/render.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Ambient walk custody
;;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private *walk-context* nil)

(defn call-with-walk-context
  "Call `body` with one agent's ambient walk custody."
  {:malli/schema
   [:=>
    [:catn
     [:seon.render.walk/context
      [:map {:closed true}
       [:seon.cluster.agent/id :seon.cluster.agent/id]
       [:seon.db/db {:optional true} :seon.db/database-value]
       [:seon.sci.admit/caps {:optional true} :seon.sci.admit/caps]
       [:seon.store/branch-connection
        {:optional true}
        :seon.store/branch-connection]]]
     [:seon.render.walk/body [:fn clojure.core/ifn?]]]
    :any]}
  [context body]
  (binding [*walk-context* context]
    (body)))

(defn- walk-error
  [message]
  (str ";; (seon.render/walk) => error\n" message))

(defn- ambient-database-value
  []
  (or (:seon.db/db *walk-context*)
      (some-> (:seon.store/branch-connection *walk-context*) deref)))

(defn- custody-cluster-name
  [db agent-id]
  (d/q '[:find ?cluster-name .
         :in $ ?agent-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?agent :seon.cluster.agent/cluster ?cluster]
         [?cluster :seon.cluster/name ?cluster-name]]
       db agent-id))

(defn- repl-state
  [db agent-id]
  (let [basis (long (:max-tx db))
        namespace-name
        (d/q '[:find ?name .
               :in $ ?agent-id
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?namespace :seon.ns/name ?name]]
             db agent-id)
        instant (:db/txInstant (d/pull db [:db/txInstant] basis))]
    (str ";; REPL state namespace=" (pr-str namespace-name)
         " basis=" basis
         " time=" (pr-str instant))))

(defn walk
  "Return the calling agent's labeled database walk as text.

  With no arguments, root is the agent whose held run supplied this eval's
  custody, depth is 2, and the database value is dereferenced here from that
  cluster's live branch connection. Prompt assembly binds its exact immutable
  database value and calls this same function. `:branch` is a labeled PATH
  from the output and restricts the result to that `get-in` subtree.

  Failures are text the agent can inspect; this boundary never throws."
  {:malli/schema
   [:function
    [:=> [:cat] :string]
    [:=>
     [:cat
      [:map {:closed true}
       [:root {:optional true} :seon.render.walk/lookup]
       [:depth {:optional true} [:int {:min 0}]]
       [:branch
        {:optional true}
        [:vector [:or :keyword :int]]]]]
     :string]]}
  ([]
   (walk {}))
  ([options]
   (try
     (let [db (ambient-database-value)
           agent-id (:seon.cluster.agent/id *walk-context*)]
       (cond
         (nil? db)
         (walk-error "No live cluster database is bound to this evaluation.")

         (nil? agent-id)
         (walk-error "No calling agent is bound to this evaluation.")

         :else
         (let [cluster-name (custody-cluster-name db agent-id)
               caps (or (:seon.sci.admit/caps *walk-context*)
                        (when cluster-name
                          (config/result-caps
                           (config/effective db cluster-name))))]
           (cond
             (or (empty? caps) (some nil? (vals caps)))
             (walk-error
              (if cluster-name
                (str "Cluster " (pr-str cluster-name)
                     " has no complete render caps.")
                (str "Agent " (pr-str agent-id)
                     " has neither ambient render caps nor a cluster "
                     "connection from which to derive them.")))

             :else
             (let [root (get options :root
                             [:seon.cluster.agent/id agent-id])
                   depth (long (get options :depth 2))
                   branch (:branch options)
                   neighborhood
                   ((requiring-resolve 'seon.render.walk/neighborhood)
                    {:seon.db/db db
                     :seon.render.walk/lookup root
                     :seon.render/kind :seon.render/ai
                     :seon.render/floor 'seon.render.block/data-prose
                     :seon.render/overrides {}
                     :seon.render/distance depth
                     :seon.sci.admit/caps caps})
                   selected (when (contains? options :branch)
                              (get-in neighborhood branch))]
               (if (and (contains? options :branch) (not (map? selected)))
                 (walk-error (str "No walk branch exists at "
                                  (pr-str branch) "."))
                 (str ((requiring-resolve 'seon.render.walk/prose)
                       db neighborhood
                       (cond-> {}
                         (contains? options :branch)
                         (assoc :seon.render.walk/branch branch)))
                      "\n" (repl-state db agent-id))))))))
     (catch Throwable failure
       (walk-error (str "Walk failed: "
                        (or (ex-message failure)
                            (.getName (class failure)))))))))

;;; ---------------------------------------------------------------------------
;;; Contract
;;; ---------------------------------------------------------------------------

(defn declaration?
  "True when `value` is a projection declaration rather than data.

  THREE SHAPES, and the narrowness is the mechanism rather than a
  restriction: a qualified SYMBOL to resolve and apply, a STRING that is
  its own output, or a VECTOR that is its own output. Anything else on a
  `seon.render`-namespaced key is presentation data —
  `:seon.render/priority 3` is the standing example, and admitting
  numbers would silently turn it into a kind."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (or (qualified-symbol? value)
      (string? value)
      (vector? value)))

(defn kinds
  "The output kinds `unit` declares.
  Every key in the `seon.render` namespace whose value is a
  `declaration?`. COMPUTED from the unit, so a producer that adds a kind
  is discoverable without an edit here and without a registry. The
  router's own request keys (`:seon.render/unit`, `:seon.render/kind`)
  can never be mistaken for declarations — a map and a keyword are
  neither symbol, string nor vector — which is why the rule needs no
  exclusion list.
  Returns the empty set for a map that declares nothing; a unit with no
  projections is an ordinary value, not an error."
  {:malli/schema [:=> [:cat :seon.render/unit] [:set :seon.render/kind]]}
  [unit]
  (let [value (get unit :seon.render/value unit)]
    (into #{}
          (keep (fn [[key declaration]]
                  (when (and (qualified-keyword? key)
                             (= "seon.render" (namespace key))
                             (declaration? declaration))
                    key)))
          (concat
           (when (map? value) value)
           (when (and (map? unit) (not (identical? unit value))) unit)))))

(defn- value-declaration
  [unit kind]
  (let [value (get unit :seon.render/value unit)]
    (or (when (map? value)
          (let [declaration (get value kind)]
            (when (declaration? declaration)
              declaration)))
        (let [declaration (get unit kind)]
          (when (declaration? declaration)
            declaration)))))

(defn- namespace-declaration
  [unit kind]
  (when-let [namespace-name (:seon.render/namespace unit)]
    (let [candidate
          (symbol (str namespace-name)
                  (str "render-" (name kind)))]
      (when
       (try
         (some? (requiring-resolve candidate))
         (catch Throwable _ false))
        candidate))))

(defn- schema-declaration
  [unit kind]
  (let [value (get unit :seon.render/value unit)]
    (when (map? value)
      (try
        (some
         (fn [row]
           (let [declaration (get row kind)]
             (when (declaration? declaration)
               declaration)))
         (schema/matching-shapes value))
        (catch Throwable _ nil)))))

(defn- floor-declaration
  [kind]
  (if (= :seon.render/html kind)
    'seon.render.block/data-panel
    'seon.render.block/data-prose))

(defn resolve-unit
  "Resolve one unit and derive whether only the floor can render it.

  The boolean records WHICH resolution branch won. It is deliberately not a
  symbol comparison: a value may explicitly name the generic floor, and that
  explicit choice did not fall through to it. The selected declaration is
  associated under `kind`, so every caller hands the same resolved unit to the
  projection and W4 can filter HTML without reimplementing precedence."
  {:malli/schema [:=> [:cat :seon.render/request] :seon.render/unit]}
  [{:seon.render/keys [unit kind]}]
  (let [specific (or (value-declaration unit kind)
                     (namespace-declaration unit kind)
                     (schema-declaration unit kind))
        floor? (nil? specific)]
    (assoc unit
           kind (or specific (floor-declaration kind))
           :seon.render/would-fall-to-floor? floor?)))

(defn render
  "Project `:seon.render/unit` into `:seon.render/kind`.
  Resolves the unit's declared symbol with `requiring-resolve` — loading
  the owning namespace if needed — and INVOKES THE VAR with the unit,
  so a re-evaluated projection takes effect immediately. On success:
  `{:seon.render/kind <kind> :seon.render/output <the projection>}`.

  Flat `:seon.error` values, never throws — this router runs on the
  error path and may not fault into it:
  - `::unresolvable` — the declared symbol does not resolve, naming the
    symbol. This is the same failure `:seon.source/populate` refuses
    on, and it is a bug in the producer, not in the caller;
  - `::projection-failed` — the projection itself threw, naming the
    symbol and the throwable's class. The projection is named because
    the projection is what is broken."
  {:malli/schema [:=> [:cat :seon.render/request]
                  [:or :seon.render/rendered :seon.error/value]]}
  [{:seon.render/keys [unit kind]}]
  (let [resolved-unit (resolve-unit {:seon.render/unit unit
                                     :seon.render/kind kind})
        declaration (get resolved-unit kind)]
    (cond
      ;; A LITERAL IS ITS OWN OUTPUT. No resolution, nothing to invoke,
      ;; and therefore nothing that can throw — a fixed string or a
      ;; fixed hiccup vector is the answer, and a block that just says a
      ;; fixed thing should not have to define a function to say it.
      (not (qualified-symbol? declaration))
      {:seon.render/kind kind
       :seon.render/output declaration}

      :else
      ;; the VAR, never a fn value taken once: re-evaluating the
      ;; projection's defn must change the next render
      (if-let [projection (try
                            (requiring-resolve declaration)
                            (catch Throwable _ nil))]
        (try
          {:seon.render/kind kind
           :seon.render/output (projection resolved-unit)}
          (catch Throwable failure
            {:seon.error/kind ::projection-failed
             :seon.error/message (str "The " declaration " projection threw: "
                                      (or (ex-message failure)
                                          (.getName (class failure))))
             :seon.error/data {:seon.render/kind kind
                               :seon.render/projection declaration
                               :seon.error/class (.getName (class failure))}}))
        {:seon.error/kind ::unresolvable
         :seon.error/message (str "The projection " declaration
                                  " does not resolve.")
         :seon.error/data {:seon.render/kind kind
                           :seon.render/projection declaration}}))))
