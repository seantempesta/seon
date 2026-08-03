(ns seon.render.walk
  "The neighbourhood traversal — one entity, its neighbours, rendered.

  THE GUARDRAIL NAMED THIS FILE (owner, 2026-07-28 close): \"ALL
  machinery lives in dedicated namespaces: the traversal in
  seon.render.walk.\" So every renderer in this system stays a normal
  Clojure function — one unit map in, data out — and everything that
  makes a WALK a walk (which neighbour is next, what it costs, when to
  stop, whose lens renders it) lives here and only here. A renderer that
  wants a neighbour calls one function; it never learns a protocol, an
  argument convention or an annotation.

  WHAT AN AGENT'S CONTEXT IS (owner ruling, 2026-07-28 post-midnight #2):
  `render(its namespace, distance N)`. This namespace is the `render(_,
  N)` half — it takes one entity and one hop budget and produces the
  rendered neighbourhood as a VALUE. Assembly into prose and page fragments
  consumes the same flat render-call units. There is one traversal discipline
  and no Hiccup-marker walker beside it.

  THE RESOLUTION CHAIN IS THE DESIGN (owner ruling, 2026-07-28
  post-midnight #3), most specific first, in `projection`:

  1. the VIEWER's local override for the data type — and the viewer is
     CONSTANT through the whole walk. Every hop renders through the original
     agent's overrides; perspective never silently shifts to whichever
     namespace happens to own the intermediate node. That constancy is
     why `overrides` rides the request rather than the unit;
  2. the OWNING NAMESPACE's default — the entity's own stored
     declaration if it carries one, then its FAMILY's, declared as a
     `:seon.render/ai`/`:seon.render/html` property on the registered
     entity map (`seon.schema`'s own idiom: `:seon.fn`, `:seon.ns` and
     `:seon.schema` already declare theirs that way, and `shape-rows`
     lifts them). Declaring a family default is therefore a schema EDN
     line plus a plain function — no registry, no table here;
  3. the FLOOR: the code/data panels, \"a good fallback as it's the truth
     of the system.\" Nothing is ever unrenderable.

  DISTANCE IS SPENT ON CONNECTIONS, one hop each: the root is rendered at
  the requested distance, a neighbour
  at distance-1, and a walk with no hops left follows nothing. Distance
  is an ARGUMENT to the renderer and never a property of it — this
  namespace puts it on the unit under `:seon.render/distance` and the
  renderer MAY read it. \"Distance 0 renders the name only\" is therefore
  a fact about the good default renderers, not a rule enforced here: a
  renderer decides what it does with the budget it was handed, which is
  what makes the convention compositional rather than imposed.

  NEIGHBOURS RUN BOTH WAYS, and that is not a special case. An agent
  holds one forward ref (its open run) and is POINTED AT by everything
  that matters — its runs, the messages sent to it, the errors recorded
  against it. A traversal that followed only forward refs would render an
  agent as an almost empty entity, so `refs` reads both directions from
  the same database value. Reverse neighbours are ordered newest-first
  and bounded by the SAME `:seon.sci.admit/caps` collection dial the eval
  door and the generic panel already use; a second width dial here would
  be a magic number and would drift from the first.

  TOTAL, because the prompt path is an error path. Every failure is a
  value: an unresolvable lookup, a family with no default, a projection
  that throws — each becomes a node carrying a flat `:seon.error/value`,
  never an exception into the loop. The one guard that is not a value is
  the node budget, which elides.

  Crash walk: pure over a database value. Nothing here opens, commits or
  holds anything."
  (:require [clojure.string :as str]
            [seon.db :as db]
            [seon.ai.tokens :as tokens]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Identifying the family
;;; ---------------------------------------------------------------------------

(defn transacted
  "A pulled entity in the shape it was TRANSACTED in.

  `db/pull` is the canonical read and it wraps every ref as `{:db/id N}`,
  while the registered attribute schema describes the value a caller
  TRANSACTS — an eid, a tempid or a lookup ref. So a pulled entity does
  not validate against its own registered entity map, and family
  identification would find nothing at all. This unwraps pull's syntax
  back to the eid the ref actually is (and a cardinality-many ref back to
  the SET its schema declares, the same restoration
  `seon.render.block/blocks` already performs on `:seon.context/inputs`).

  It is used for IDENTIFICATION ONLY. Renderers receive the pulled
  entity, because pull's shape is what a renderer wants to read."
  {:malli/schema [:=> [:cat :map] :map]}
  [entity]
  (into {}
        (map (fn [[attribute value]]
               [attribute
                (cond
                  ;; `contains?` and not a key-set equality: `db/pull`
                  ;; expands COMPONENT refs into their full child maps,
                  ;; so an equality check would leave a run's forms as
                  ;; maps in a ref position and the entity would match
                  ;; no family at all. The eid is what identification
                  ;; wants either way.
                  (and (map? value) (contains? value :db/id))
                  (:db/id value)

                  (and (sequential? value)
                       (seq value)
                       (every? (fn [element]
                                 (and (map? element)
                                      (contains? element :db/id)))
                               value))
                  (into #{} (map :db/id) value)

                  ;; every other cardinality-many value pulls as a
                  ;; vector where the schema declares a set — the same
                  ;; boundary restoration, one place
                  (sequential? value) (set value)

                  :else value)]))
        (dissoc entity :db/id)))

(defn family
  "The registered entity shapes `entity` belongs to, most specific first.

  ONE derivation, and it is the schema's own: `matching-shapes` returns
  every registered map schema the value validates against, already
  ranked. A family default renderer is therefore declared where the
  family is declared — as a `:seon.render/ai` or `:seon.render/html`
  property on the entity map — and discovered here with no table.

  Total: an unactivated projection, or a value that matches nothing,
  yields `[]`. A traversal must never fail because a family has not been
  described yet; that is exactly when the floor earns its keep."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [entity]
  (try
    (schema/matching-shapes (transacted entity))
    (catch Throwable _ [])))

;;; ---------------------------------------------------------------------------
;;; The resolution chain
;;; ---------------------------------------------------------------------------

(defn- specific-projection
  [unit kind overrides]
  (or (some (fn [{shape-key :seon.schema/key}] (get overrides shape-key))
            (family unit))
      (let [declared (get unit kind)]
        (when (qualified-symbol? declared) declared))
      (some (fn [row] (get row kind)) (family unit))))

(defn projection
  "The projection symbol for `unit` in `kind`. THE RESOLUTION CHAIN.

  Most specific first — viewer override, the entity's own declaration,
  its family's default, the kind's floor — and each step is data the caller
  already has, so \"why did it render that way?\" is answered by reading one
  map rather than by tracing a dispatch.

  `overrides` is the VIEWER's, keyed by registered schema key, and it is
  passed unchanged to every hop: the viewer is constant through the whole
  walk. `floor` is the kind's own last resort and is required, because a
  kind with no floor would make some value unrenderable and the whole
  point of the floor is that none is.

  Returns a declaration, never nil: `seon.render/declaration?` holds for
  everything this can produce."
  {:malli/schema [:=> [:cat :seon.render/unit :seon.render.walk/resolution]
                  :seon.render/projection]}
  [unit {:seon.render/keys [kind overrides floor]}]
  (or (specific-projection unit kind overrides)
      floor))

;;; ---------------------------------------------------------------------------
;;; The connections
;;; ---------------------------------------------------------------------------

(declare eid-of)

(defn- concrete-entity
  "Pull every attribute the entity actually carries."
  [db eid]
  (db/pull db '[*] eid))

(defn- forward-refs
  "`[attribute eid]` for every ref value the entity itself carries."
  [entity]
  (into []
        (mapcat (fn [[attribute value]]
                  (cond
                    (and (map? value) (contains? value :db/id))
                    [[attribute (:db/id value)]]

                    (sequential? value)
                    (keep (fn [element]
                            (when (and (map? element) (contains? element :db/id))
                              [attribute (:db/id element)]))
                          value)

                    :else nil)))
        (sort-by (comp str key) (dissoc entity :db/id :seon.db/db))))

(defn- installed-ref-attributes
  "Every installed ref attribute, derived from the database schema."
  [db]
  (into []
        (comp
         (filter (comp keyword? key))
         (filter (fn [[_ properties]]
                   (= :db.type/ref (:db/valueType properties))))
         (map key))
        (sort-by (comp str key) (:schema db))))

(defn- reverse-refs
  "`[attribute eid]` for every entity that POINTS AT this one.

  Newest first, then bounded, then re-ordered oldest-first for reading:
  the newest `max-collection` neighbours per attribute are the ones a
  reader wants, and reading them in the order they happened is how a
  history reads. Entity id ascending IS commit order for facts committed
  in sequence, which `seon.render.root/messages-html` already relies on.

  The bound is the caps' own collection dial. A dedicated neighbourhood
  width would be a second size dial to keep in step with the first, and
  inventing a number here is the banned magic constant.

  Each installed ref attribute is one exact AVET slice. Datahike indexes
  refs by construction, so this is bounded to the named attribute and
  target without either an unbound Datalog scan or a registered-family
  membership filter. The installed schema is the complete attribute source."
  [db eid caps]
  (let [width (long (:seon.config.eval.result/max-collection caps))]
    (vec
     (mapcat
      (fn [attribute]
        (let [sources (->> (db/datoms db :avet attribute eid)
                           (map :e)
                           distinct
                           sort
                           reverse)
              kept (take width sources)
              elided (- (count sources) (count kept))]
          (cond-> (mapv (fn [source]
                          {:seon.render.walk/attribute attribute
                           :seon.render.walk/target source})
                        (sort kept))
            (pos? elided)
            (conj
             {:seon.render.walk/attribute attribute
              :seon.error/value
              {:seon.error/kind ::elided
               :seon.error/message
               (str "elided " elided " reverse " attribute
                    " connection" (when-not (= 1 elided) "s")
                    " at the configured collection cap")
               :seon.error/data
               {:seon.render.walk/attribute attribute
                :seon.render.walk/elided-count elided}}}))))
      (installed-ref-attributes db)))))

(defn- asked-for-run-edges
  [db entity caps]
  (when (:seon.cluster.agent/id entity)
    (let [agent-eid (:db/id entity)
          width (long (:seon.config.eval.result/max-collection caps))
          run-eids (->> (db/q '[:find [?run ...]
                                :in $ ?agent
                                :where
                                [?message :seon.cluster.message/from ?agent]
                                [?run :seon.cluster.run/trigger ?message]]
                              db agent-eid)
                        sort
                        reverse)
          kept (take width run-eids)
          elided (- (count run-eids) (count kept))]
      (cond->
       (mapv (fn [run-eid]
               {:seon.render.walk/attribute
                :seon.render.walk/asked-for-run
                :seon.render.walk/target run-eid})
             (sort kept))
        (pos? elided)
        (conj
         {:seon.render.walk/attribute :seon.render.walk/asked-for-run
          :seon.error/value
          {:seon.error/kind ::elided
           :seon.error/message
           (str "elided " elided " asked-for run connection"
                (when-not (= 1 elided) "s")
                " at the configured collection cap")
           :seon.error/data
           {:seon.render.walk/attribute :seon.render.walk/asked-for-run
            :seon.render.walk/elided-count elided}}})))))

(def ^:private derived-edge-functions
  [asked-for-run-edges])

(defn- derived-refs
  [db entity caps]
  (into []
        (mapcat (fn [derive-fn] (or (derive-fn db entity caps) [])))
        derived-edge-functions))

(defn refs
  "Every connection of the entity at `eid`, both directions, ordered.

  Forward refs first (what this entity says about itself), then reverse
  refs (what the rest of the database says about it), each attribute
  group in attribute-name order. Deterministic, because two derivations
  of one database value must be the same value — the property equality
  suppression and re-derivable capture both depend on.

  No entity or connection is classified out of the walk. Renderers decide
  what to omit, and repeated targets through distinct attributes remain
  distinct connections; traversal turns later visits into explicit
  back-references."
  {:malli/schema [:=> [:cat :any :int :seon.sci.admit/caps]
                  [:vector :seon.render.walk/connection]]}
  [db eid caps]
  (let [entity (concrete-entity db eid)]
    (into []
          (concat
           (map (fn [[attribute target]]
                  {:seon.render.walk/attribute attribute
                   :seon.render.walk/target target})
                (forward-refs entity))
           (reverse-refs db eid caps)
           (derived-refs db entity caps)))))

;;; ---------------------------------------------------------------------------
;;; The walk
;;; ---------------------------------------------------------------------------

(defn- eid-of
  "The entity id `lookup` names at `db`, or nil when nothing answers."
  [db lookup]
  (try
    (:db/id (db/pull db [:db/id] lookup))
    (catch Throwable _ nil)))

(defn- entity-last-changed
  "The newest transaction touching `eid`, derived from this database value."
  [db eid]
  (if eid
    (reduce (fn [latest datom] (max latest (long (:tx datom))))
            0
            (db/datoms db :eavt eid))
    0))

(defn- assigned-namespace-eid
  [db root-eid]
  (db/q '[:find ?namespace .
         :in $ ?agent
         :where [?agent :seon.cluster.agent/namespace ?namespace]]
       db root-eid))

(defn- namespace-render-distance
  [root-namespace-eid eid entity traversal-hops]
  (if (contains? entity :seon.ns/name)
    (if (= root-namespace-eid eid) 1 2)
    traversal-hops))

(defn- visible-connections
  [entity connections]
  (if (contains? entity :seon.ns/name)
    (filterv #(= :seon.ns/requires
                 (:seon.render.walk/attribute %))
             connections)
    connections))

(defn- namespace-name-at
  [db eid]
  (:seon.ns/name (db/pull db [:seon.ns/name] eid)))

(defn- entity-lookup
  [db eid]
  (let [entity (concrete-entity db eid)]
    (if-let [namespace-name (:seon.ns/name entity)]
      [:seon.ns/name namespace-name]
      eid)))

(defn- owning-namespace
  "The one namespace explicitly named by the value or one of its direct refs.

  This deliberately does not inspect keyword text. An entity with no explicit
  namespace edge has no owning namespace at this boundary and therefore falls
  through to its matching schema property and the structural floor."
  [db entity]
  (let [names (cond-> (into #{}
                            (keep (fn [[_attribute target]]
                                    (namespace-name-at db target)))
                            (forward-refs entity))
                (:seon.ns/name entity) (conj (:seon.ns/name entity)))]
    (when (= 1 (count names))
      (first names))))

(declare units)

(defn neighborhood
  "One entity and its neighbours, rendered, as a VALUE.

  THE PILOT'S WHOLE MECHANISM. A node is
  `{lookup, projection, output, distance, neighbours}` and a neighbour
  entry adds the `attribute` it was reached through, so a consumer can
  say \"through :seon.cluster.run/agent\" without re-querying. It is a
  value rather than text or hiccup because the two assemblies want
  different shapes from the same walk; `prose` is the ai one.

  DISTANCE IS SPENT PER CONNECTION: the root renders at the requested
  distance, each neighbour one hop cheaper,
  and a node with no hops left follows nothing. Namespace rendering is
  root-relative: the root agent's assigned namespace receives distance 1,
  while every other namespace receives distance 2. Namespace renderers
  absorb their members; traversal preserves only `:seon.ns/requires` edges.
  This normalization changes renderer input, never the traversal hop budget.

  Three bounds, none of them a clock: the hop budget (what was asked
  for), the caps' node budget (the absolute one — a graph that fans out
  needs a node budget and not merely a loop guard), and the caps' collection
  budget on each reverse attribute. Every active bound emits a quiet elision
  node. A per-walk rendered set stops cycles and fan-in from rendering the
  same entity again; it never silently changes a renderer's input.

  Every failure is a node carrying a flat `:seon.error/value`. Nothing
  throws."
  {:malli/schema [:=> [:cat :seon.render.walk/request] :seon.render.walk/node]}
  [{:keys [:seon.db/db :seon.sci.admit/caps]
    :seon.render/keys [kind overrides floor]
    viewer-namespace :seon.render/namespace
    :seon.render.walk/keys [lookup]
    :as request}]
  (let [remaining (volatile! (long (:seon.config.eval.result/max-nodes caps)))
        rendered-eids (volatile! #{})
        hops (long (get request :seon.render/distance 1))
        root-eid (eid-of db lookup)
        root-namespace-eid (when root-eid
                             (assigned-namespace-eid db root-eid))]
    (letfn [(elision-node [lookup attribute hops failure]
              (let [message (:seon.error/message failure)]
                (cond-> {:seon.render.walk/lookup lookup
                         :seon.render/distance hops
                         :seon.render.walk/changed-at 0
                         :seon.render/projection 'seon.render.walk/elision
                         :seon.render/output
                         (if (= kind :seon.render/html)
                           [:span {:class "seon-walk-elision"
                                   :data-walk-elided "true"}
                            "… " message]
                           message)}
                  (:seon.render/output request)
                  (assoc :seon.error/value failure)

                  attribute (assoc :seon.render.walk/attribute attribute))))
            (marker [lookup attribute hops failure]
              (if (= ::elided (:seon.error/kind failure))
                (elision-node lookup attribute hops failure)
                (cond-> {:seon.render.walk/lookup lookup
                         :seon.render/distance hops
                         :seon.render.walk/changed-at 0
                         :seon.error/value failure}
                  attribute (assoc :seon.render.walk/attribute attribute))))
            (child [connection hops]
              (let [{:seon.render.walk/keys [attribute target lookup]
                     failure :seon.error/value} connection]
                (cond
                  failure (marker (or lookup attribute) attribute hops failure)
                  target (node (if (:seon.render/output request)
                                 (entity-lookup db target)
                                 target)
                               target attribute hops)
                  lookup (node lookup (eid-of db lookup) attribute hops)
                  :else
                  (marker attribute attribute hops
                          {:seon.error/kind ::no-such-entity
                           :seon.error/message
                           "A derived connection had no target."}))))
            (node [lookup eid attribute hops]
              (let [base (cond-> {:seon.render.walk/lookup lookup
                                  :seon.render/distance hops
                                  :seon.render.walk/changed-at
                                  (entity-last-changed db eid)}
                           attribute
                           (assoc :seon.render.walk/attribute attribute))]
                (cond
                  (neg? (vswap! remaining dec))
                  (elision-node lookup attribute hops
                                {:seon.error/kind ::elided
                                 :seon.error/message
                                 (str "elided — this neighbourhood is larger "
                                      "than the configured node cap")})

                  (nil? eid)
                  (assoc base :seon.error/value
                         {:seon.error/kind ::no-such-entity
                          :seon.error/message
                          (str "Nothing in the database answers to "
                               (pr-str lookup) ".")})

                  (contains? @rendered-eids eid)
                  (assoc base :seon.render.walk/back-reference? true)

                  :else
                  (let [_ (vswap! rendered-eids conj eid)
                        pulled (try
                                 (concrete-entity db eid)
                                 (catch Throwable _ nil))]
                    (if (or (nil? pulled) (nil? (:db/id pulled)))
                      (assoc base :seon.error/value
                             {:seon.error/kind ::no-such-entity
                              :seon.error/message
                              (str "Nothing in the database answers to "
                                   (pr-str lookup) ".")})
                      (let [render-distance
                            (namespace-render-distance
                             root-namespace-eid eid pulled hops)
                            render-base (assoc base
                                               :seon.render/distance
                                               render-distance)
                            typed-output (:seon.render/output request)
                            owner (owning-namespace db pulled)
                            legacy-resolution
                            (when-not typed-output
                              (let [unit
                                    (cond-> (assoc pulled
                                                   :seon.db/db db
                                                   :seon.sci.admit/caps caps
                                                   :seon.render/distance
                                                   render-distance)
                                      viewer-namespace
                                      (assoc :seon.render/namespace
                                             viewer-namespace))
                                    specific
                                    (specific-projection unit kind overrides)
                                    resolved
                                    (render/resolve-unit
                                     {:seon.render/unit
                                      (cond-> unit specific
                                        (assoc kind specific))
                                      :seon.render/kind kind})
                                    floor?
                                    (:seon.render/would-fall-to-floor? resolved)]
                                {:seon.render/unit
                                 (cond-> resolved floor? (assoc kind floor))
                                 :seon.render/projection (get resolved kind)
                                 :seon.render/would-fall-to-floor? floor?}))
                            render-request
                            (when typed-output
                              (cond-> (assoc request
                                             :seon.render/value pulled
                                             :seon.render/distance
                                             render-distance)
                                owner
                                (assoc :seon.render/namespace owner)))
                            rendered
                            (if typed-output
                              ((if (= typed-output :seon.render/html)
                                 render/render-html
                                 render/render-ai)
                               render-request)
                              (render/render
                               {:seon.render/unit
                                (:seon.render/unit legacy-resolution)
                                :seon.render/kind kind}))
                            rendered-output
                            (if typed-output rendered
                                (:seon.render/output rendered))
                            render-failure
                            (when (:seon.error/kind rendered) rendered)
                            failure-outcome
                            (when (and render-failure owner)
                              (render/renderer-failure
                               {:seon.db/db db
                                :seon.render/namespace owner
                                :seon.error/value render-failure}))
                            failure-output
                            (when render-failure
                              (or (get failure-outcome (or typed-output kind))
                                  (if (= (or typed-output kind)
                                         :seon.render/html)
                                    [:div {:class "seon-render-unavailable"}
                                     "renderer unavailable"]
                                    "Renderer unavailable.")))
                            connections
                            (visible-connections
                             pulled
                             (try (refs db eid caps)
                                  (catch Throwable failure
                                    [{:seon.error/value
                                      {:seon.error/kind
                                       ::connections-failed
                                       :seon.error/message
                                       (str "Could not derive "
                                            "connections: "
                                            (.getMessage failure))}}])))
                            with-render
                            (cond-> render-base
                              legacy-resolution
                              (assoc :seon.render/projection
                                     (:seon.render/projection legacy-resolution)
                                     :seon.render/would-fall-to-floor?
                                     (:seon.render/would-fall-to-floor?
                                      legacy-resolution))
                              render-failure
                              (assoc :seon.error/value render-failure
                                     :seon.render/output failure-output)

                              (seq (:seon.db/tx-data failure-outcome))
                              (assoc :seon.db/tx-data
                                     (:seon.db/tx-data failure-outcome))

                              (not render-failure)
                              (assoc :seon.render/output rendered-output))]
                        (cond
                          (pos? hops)
                          (assoc with-render :seon.render.walk/neighbours
                                 (mapv #(child % (dec hops)) connections))

                          (seq connections)
                          (assoc with-render :seon.render.walk/neighbours
                                 [(marker
                                   lookup nil hops
                                   {:seon.error/kind ::elided
                                    :seon.error/message
                                    "elided connections at the requested distance cap"})])

                          :else with-render)))))))]
      (let [tree
            (if root-eid
              (node lookup root-eid nil hops)
              {:seon.render.walk/lookup lookup
               :seon.render/distance hops
               :seon.render.walk/changed-at 0
               :seon.error/value
               {:seon.error/kind ::no-such-entity
                :seon.error/message
                (str "Nothing in the database answers to "
                     (pr-str lookup) ".")}})]
        (if (:seon.render/output request)
          (units tree)
          tree)))))

;;; ---------------------------------------------------------------------------
;;; Assembly — the ai kind
;;; ---------------------------------------------------------------------------

(defn units
  "Flatten a rendered neighbourhood into one deterministically ordered vector.

  Each result is the direct renderer-call unit: address, path, distance,
  changed basis, output or error. Consumers never unwrap a recursive node.
  The root is the stable head and ordinary branches retain grouped
  last-changed order. Repeated logical lookups and back-references contribute
  no second unit."
  {:malli/schema
   [:=> [:cat :seon.render.walk/node] :seon.render.walk/units]}
  [node]
  (letfn [(flatten-units [node path depth branch]
            (let [failure (:seon.error/value node)
                  output (:seon.render/output node)
                  present? (or failure
                               (and (string? output)
                                    (not (str/blank? output)))
                               (and (some? output)
                                    (not (string? output))))
                  here (when present?
                         [(cond->
                           (assoc (dissoc node :seon.render.walk/neighbours)
                                  :seon.render.walk/path path
                                  :seon.render.walk/found-depth depth)
                            branch
                            (assoc :seon.render.walk/branch branch)
                            (nil? failure)
                            (dissoc :seon.error/value)
                            (nil? output)
                            (dissoc :seon.render/output))])]
              (into (or here [])
                    (mapcat
                     (fn [[index child]]
                       (let [child-path (conj path
                                              :seon.render.walk/neighbours
                                              index)]
                         (flatten-units child child-path (inc depth)
                                        (or branch child-path))))
                     (map-indexed vector
                                  (:seon.render.walk/neighbours node))))))]
    (letfn [(sort-key [unit]
              (let [path (:seon.render.walk/path unit)]
                [(if (empty? path) 0 1)
                 (:seon.render.walk/changed-at unit)
                 (:seon.render.walk/branch unit)
                 path]))
            (logical-key [unit]
              (when-not (= 'seon.render.walk/elision
                           (:seon.render/projection unit))
                [:seon.render.walk/lookup
                 (:seon.render.walk/lookup unit)]))]
      (->> (flatten-units node [] 0 nil)
             (remove :seon.render.walk/back-reference?)
             (sort-by sort-key)
             (reduce
              (fn [{seen-values :seen accumulated :units :as state} unit]
                (let [logical-value (logical-key unit)]
                  (if (and logical-value
                           (contains? seen-values logical-value))
                    state
                    {:seen (cond-> seen-values
                             logical-value (conj logical-value))
                     :units (conj accumulated unit)})))
              {:seen #{} :units []})
             :units))))

(defn prose
  "A rendered neighbourhood as text. THE `:seon.render/ai` ASSEMBLY.

  Each unit gets one compact comment carrying its depth and projection. A
  branch root also retains the literal `:branch` path accepted by
  `seon.render/walk`, so shortening presentation never removes the drill
  handle. A node that rendered nothing contributes nothing — omission is
  nil-punning here exactly as it is in a block, so a family with nothing to
  say costs no tokens.

  A node carrying a flat error contributes ITS MESSAGE, because a reader
  told nothing about a neighbour that exists would reason from a gap it
  cannot see. That is the same rule `seon.cluster.prompt` applies to a
  failed block."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.render.walk/node]
     [:maybe :string]]
    [:=>
     [:cat
      :seon.db/database-value
      :seon.render.walk/node
      [:map {:closed true}
       [:seon.render.walk/branch
        {:optional true}
        [:vector [:or :keyword :int]]]]]
     [:maybe :string]]]}
  ([db node]
   (prose db node {}))
  ([db node {requested-branch :seon.render.walk/branch}]
   (letfn [(provenance [node]
            (or (:seon.render/projection node)
                (get-in node [:seon.error/value :seon.error/kind])
                :seon.render.walk/unknown))
          (within-branch? [path]
            (or (nil? requested-branch)
                (and (<= (count requested-branch) (count path))
                     (= requested-branch
                        (subvec path 0 (count requested-branch))))))
          (elision-unit? [unit]
            (= 'seon.render.walk/elision
               (:seon.render/projection unit)))
          (unit-lines [unit]
            (let [path (:seon.render.walk/path unit)
                  depth (:seon.render.walk/found-depth unit)
                  failure (:seon.error/value unit)
                  output (:seon.render/output unit)
                  text (cond
                         (string? output) output
                         (some? output) (pr-str output)
                         failure (:seon.error/message failure))]
              [(str ";; d" depth " · " (pr-str (provenance unit))
                    (when (= path (:seon.render.walk/branch unit))
                      (str " · :branch " (pr-str path))))
               text]))]
     (let [root (:seon.render.walk/lookup node)
           requested-depth (:seon.render/distance node)
           basis (long (:max-tx db))
           options (cond-> {:root root :depth requested-depth}
                     (some? requested-branch)
                     (assoc :branch requested-branch))
           header (str ";; (seon.render/walk " (pr-str options) ")"
                       " => root=" (pr-str root)
                       " basis=" basis
                       " depth=" requested-depth
                       (when (some? requested-branch)
                         (str " branch=" (pr-str requested-branch))))
           ordered (->> (units node)
                        (filter (comp within-branch?
                                      :seon.render.walk/path)))
           elisions (filter elision-unit? ordered)
           visible (remove elision-unit? ordered)
           elision-line
           (when (seq elisions)
             (let [former-noise (str/join "\n" (keep :seon.render/output
                                                       elisions))]
               (str ";; " (count elisions) " branches elided · "
                    (tokens/estimate former-noise) " tokens · inspect with "
                    "(seon.render/walk "
                    (pr-str {:root root :depth (inc requested-depth)}) ")")))
           lines (concat [header]
                         (when elision-line [elision-line])
                         (mapcat unit-lines visible))
           text (str/join "\n" lines)]
       (when-not (str/blank? text) text)))))
