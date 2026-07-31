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
  rendered neighbourhood as a VALUE. Assembly into prose is `prose`
  below; assembly into a page is `seon.render.block/expand`, which spends
  distance the same way over hiccup holes. Two assemblers, one traversal
  discipline, and neither is a second router: every node here is rendered
  by `seon.render/render` exactly like a block.

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

  DISTANCE IS SPENT ON CONNECTIONS, one hop each, exactly as `expand`
  spends it: the root is rendered at the requested distance, a neighbour
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
            [datahike.api :as d]
            [seon.render :as render]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/walk.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Identifying the family
;;; ---------------------------------------------------------------------------

(defn transacted
  "A pulled entity in the shape it was TRANSACTED in.

  `d/pull` is the canonical read and it wraps every ref as `{:db/id N}`,
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
                  ;; `contains?` and not a key-set equality: `d/pull`
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
  (or (some (fn [{shape-key :seon.schema/key}] (get overrides shape-key))
            (family unit))
      (let [declared (get unit kind)]
        (when (qualified-symbol? declared) declared))
      (some (fn [row] (get row kind)) (family unit))
      floor))

;;; ---------------------------------------------------------------------------
;;; The connections
;;; ---------------------------------------------------------------------------

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

(defn- reverse-refs
  "`[attribute eid]` for every entity that POINTS AT this one.

  Newest first, then bounded, then re-ordered oldest-first for reading:
  the newest `max-collection` neighbours per attribute are the ones a
  reader wants, and reading them in the order they happened is how a
  history reads. Entity id ascending IS commit order for facts committed
  in sequence, which `seon.render.root/messages-html` already relies on.

  The bound is the caps' own collection dial. A dedicated neighbourhood
  width would be a second size dial to keep in step with the first, and
  inventing a number here is the banned magic constant."
  [db eid caps]
  (let [width (long (:seon.config.eval.result/max-collection caps))]
    ;; the attribute position binds the IDENT ITSELF in Datahike — a
    ;; `[?a :db/ident ?attribute]` join returns nothing, measured
    ;; (`tmp/context_pilot_probe3.clj`), because the datom already
    ;; carries the keyword.
    (->> (d/q '[:find ?source ?attribute
                :in $ ?target
                :where [?source ?attribute ?target]]
              db eid)
         (group-by second)
         (sort-by (comp str key))
         (into []
               (mapcat (fn [[attribute pairs]]
                         (->> pairs
                              (map first)
                              sort
                              reverse
                              (take width)
                              sort
                              (map (fn [source] [attribute source])))))))))

(defn- dedupe-by
  "A transducer keeping the FIRST element per `key-fn` value.
  `clojure.core/dedupe` only collapses adjacent duplicates, and the two
  mentions of one neighbour are not adjacent — a forward ref and the
  reverse ref that answers it sit in different attribute groups."
  [key-fn]
  (fn [rf]
    (let [seen (volatile! #{})]
      (fn ([] (rf))
        ([result] (rf result))
        ([result element]
         (let [k (key-fn element)]
           (if (contains? @seen k)
             result
             (do (vswap! seen conj k) (rf result element)))))))))

(defn apparatus?
  "True when the entity at `eid` is APPARATUS rather than world.

  A neighbourhood is the world an agent lives in. Two structurally
  different things sit in the same graph without being part of that
  world, and both were found by DERIVING one and reading the result
  rather than by reasoning:

  - THE VIEW'S OWN PARTS — an agent's blocks. They are how it is being
    looked at, not something it is connected to, so following them is a
    cycle in meaning: the block set is an INPUT to the very render being
    derived. The first live derivation showed exactly how badly it
    reads — the agent's own `:identity` block came back through the walk
    rendered against a block ENTITY and announced \"You are agent .\"
    with no id at all, because a block's projection expects the unit
    `seon.render.block/unit` builds. This is
    `seon.render.block/expand`'s \"a slot is NOT a hop\" rule arriving on
    the entity side.
  - THE DATABASE'S OWN BOOKKEEPING — transaction entities. Every fact
    has one and it points at whatever the transaction's meta named, so
    the second derivation reached the trigger message's transaction and
    printed `{:db/txInstant … :seon.db/trigger …}` through the floor.
    Provenance is real and has its own owner; it is not a neighbour.

  PRESENCE, never a list: a block IS an entity carrying
  `:seon.render.block/name` and a transaction IS an entity carrying
  `:db/txInstant`. Each rule is one attribute's presence, derived from
  the fact, so nothing here is enumerated and nothing drifts."
  {:malli/schema [:=> [:cat :any :int] :boolean]}
  [db eid]
  (boolean
   (try
     (let [probe (d/pull db [:seon.render.block/name :db/txInstant] eid)]
       (or (:seon.render.block/name probe) (:db/txInstant probe)))
     (catch Throwable _ false))))

(defn refs
  "Every connection of the entity at `eid`, both directions, ordered.

  Forward refs first (what this entity says about itself), then reverse
  refs (what the rest of the database says about it), each attribute
  group in attribute-name order. Deterministic, because two derivations
  of one database value must be the same value — the property equality
  suppression and re-derivable capture both depend on.

  Connections into the APPARATUS are excluded (`apparatus?`), and connections
  are DEDUPLICATED by target with the first mention winning: an agent
  points at its open run and that run points back, so the naive walk
  rendered the same run twice under two attribute names. Forward first
  means the entity's own word about a neighbour is the one that names
  it."
  {:malli/schema [:=> [:cat :any :int :seon.sci.admit/caps]
                  [:vector :seon.render.walk/connection]]}
  [db eid caps]
  (let [entity (d/pull db '[*] eid)]
    (into []
          (comp
           (remove (fn [[_ target]] (apparatus? db target)))
           (map (fn [[attribute target]]
                  {:seon.render.walk/attribute attribute
                   :seon.render.walk/target target}))
           (dedupe-by :seon.render.walk/target))
          (concat (forward-refs entity) (reverse-refs db eid caps)))))

;;; ---------------------------------------------------------------------------
;;; The walk
;;; ---------------------------------------------------------------------------

(defn- eid-of
  "The entity id `lookup` names at `db`, or nil when nothing answers."
  [db lookup]
  (try
    (:db/id (d/pull db [:db/id] lookup))
    (catch Throwable _ nil)))

(defn neighborhood
  "One entity and its neighbours, rendered, as a VALUE.

  THE PILOT'S WHOLE MECHANISM. A node is
  `{lookup, projection, output, distance, neighbours}` and a neighbour
  entry adds the `attribute` it was reached through, so a consumer can
  say \"through :seon.cluster.run/agent\" without re-querying. It is a
  value rather than text or hiccup because the two assemblies want
  different shapes from the same walk; `prose` is the ai one.

  DISTANCE IS SPENT PER CONNECTION, exactly as `expand` spends it: the
  root renders at the requested distance, each neighbour one hop cheaper,
  and a node with no hops left follows nothing. The unit carries the
  distance so the renderer may read it — that is the whole call
  convention, and a renderer that never looks is correct.

  Three bounds, none of them a clock: the hop budget (what was asked
  for), the caps' node budget (the absolute one — a graph that fans out
  needs a node budget and not merely a loop guard, the lesson
  `seon.render.block/expand` already paid for), and a per-PATH visited
  set (the entity graph genuinely cycles: an agent's run points back at
  the agent).

  Every failure is a node carrying a flat `:seon.error/value`. Nothing
  throws."
  {:malli/schema [:=> [:cat :seon.render.walk/request] :seon.render.walk/node]}
  [{:keys [:seon.db/db :seon.sci.admit/caps]
    :seon.render/keys [kind overrides floor]
    :seon.render.walk/keys [lookup]
    :as request}]
  (let [remaining (volatile! (long (:seon.config.eval.result/max-nodes caps)))
        hops (long (get request :seon.render/distance 1))]
    (letfn [(node [lookup eid attribute hops visited]
              (let [pulled (try (d/pull db '[*] eid) (catch Throwable _ nil))
                    base (cond-> {:seon.render.walk/lookup lookup
                                  :seon.render/distance hops}
                           attribute
                           (assoc :seon.render.walk/attribute attribute))]
                (cond
                  (neg? (vswap! remaining dec))
                  (assoc base :seon.error/value
                         {:seon.error/kind ::elided
                          :seon.error/message
                          (str "elided — this neighbourhood is larger than "
                               "the configured caps")})

                  (or (nil? pulled) (nil? (:db/id pulled)))
                  (assoc base :seon.error/value
                         {:seon.error/kind ::no-such-entity
                          :seon.error/message
                          (str "Nothing in the database answers to "
                               (pr-str lookup) ".")})

                  :else
                  (let [unit (assoc pulled
                                    :seon.db/db db
                                    :seon.sci.admit/caps caps
                                    :seon.render/distance hops)
                        chosen (projection
                                unit
                                (cond-> {:seon.render/kind kind
                                         :seon.render/floor floor}
                                  (contains? request :seon.render/overrides)
                                  (assoc :seon.render/overrides overrides)))
                        rendered (render/render
                                  {:seon.render/unit (assoc unit kind chosen)
                                   :seon.render/kind kind})
                        with-render
                        (cond-> (assoc base :seon.render/projection chosen)
                          (:seon.error/kind rendered)
                          (assoc :seon.error/value rendered)

                          (not (:seon.error/kind rendered))
                          (assoc :seon.render/output
                                 (:seon.render/output rendered)))]
                    (if-not (pos? hops)
                      with-render
                      (assoc with-render :seon.render.walk/neighbours
                             (into []
                                   (keep
                                    (fn [{target :seon.render.walk/target
                                          attribute
                                          :seon.render.walk/attribute}]
                                      (when-not (contains? visited target)
                                        (node target target attribute
                                              (dec hops)
                                              (conj visited target)))))
                                   (refs db eid caps))))))))]
      (if-let [eid (eid-of db lookup)]
        (node lookup eid nil hops #{eid})
        {:seon.render.walk/lookup lookup
         :seon.render/distance hops
         :seon.error/value
         {:seon.error/kind ::no-such-entity
          :seon.error/message (str "Nothing in the database answers to "
                                   (pr-str lookup) ".")}}))))

;;; ---------------------------------------------------------------------------
;;; Assembly — the ai kind
;;; ---------------------------------------------------------------------------

(defn prose
  "A rendered neighbourhood as text. THE `:seon.render/ai` ASSEMBLY.

  Depth is INDENTATION, and the connection is named on the line that
  introduces its node, so a reader (and a model) can see that the run
  under an agent was reached through `:seon.cluster.run/agent` rather
  than inferring it. A node that rendered nothing contributes nothing and
  neither does its indentation — omission is nil-punning here exactly as
  it is in a block, so a family with nothing to say costs no tokens.

  A node carrying a flat error contributes ITS MESSAGE, because a reader
  told nothing about a neighbour that exists would reason from a gap it
  cannot see. That is the same rule `seon.cluster.prompt` applies to a
  failed block."
  {:malli/schema [:=> [:cat :seon.render.walk/node] [:maybe :string]]}
  [node]
  (letfn [(lines [node depth]
            (let [pad (str/join (repeat depth "  "))
                  attribute (:seon.render.walk/attribute node)
                  failure (:seon.error/value node)
                  output (get node :seon.render/output)
                  text (cond
                         failure (:seon.error/message failure)
                         (string? output) (when-not (str/blank? output) output)
                         (some? output) (pr-str output))
                  head (when text
                         (str pad
                              (when attribute (str "(" attribute ") "))
                              (str/replace text "\n" (str "\n" pad))))]
              (into (if head [head] [])
                    (mapcat (fn [child]
                              (lines child (if head (inc depth) depth))))
                    (:seon.render.walk/neighbours node))))]
    (let [text (str/join "\n" (lines node 0))]
      (when-not (str/blank? text) text))))
