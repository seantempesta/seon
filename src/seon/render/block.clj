(ns seon.render.block
  "Blocks — the one mechanism behind every page and every prompt.

  CONTRACT LAYER (drafted 2026-07-27 for ORCHESTRATOR SEAL — N4 package
  1). Every body throws `awaits implementation`.

  THE OWNER'S RULING, 2026-07-27 night, is the whole design: the root
  interface \"is really just different context blocks that return
  :seon.render/ai and :seon.render/html\", so root and agent views are
  ONE mechanism. The quarry proves that was already true — `/` and
  `/agent/{id}` share one shim, one feed shape and one render entry, and
  root differs only in the DATA of one block
  (`src-old/seon/route.cljs:64-72`, `src-old/seon/web/datastar.cljs:929-935`,
  `src-old/seon/agent/ctx/driver.cljs:249`). Root is per-cluster and root
  is an agent, so nothing here knows the word \"root\".

  WHAT THIS NAMESPACE IS, exactly: the derivation from a database value
  to an ordered set of RENDERED SURFACES, the placement of those
  surfaces into a page, and — because N4 is where it becomes reusable —
  the GENERIC-DEFAULT-PLUS-SPECIALIST selection shape (`select`,
  `data-panel`) that every output kind's producers use to decide which
  projection a value's key points at. It owns no transport, no registry,
  no renderer table and no second router — a block IS a unit for
  `seon.render`, so routing one is the landed `seon.render/render` with
  no edit.

  THE ONE STRUCTURAL CHANGE FROM THE QUARRY, and the reason the owner's
  \"faster + more responsive\" is a design property rather than an
  optimization pass: THE OLD UI MORPHED ONE ELEMENT. Every live update
  replaced the entire `<main id=\"app-view\">` subtree — the header, the
  rail, every panel — on any relevant datom
  (`src-old/seon/web/datastar.cljs:127-141,175-190`; the whole-page
  render is `src-old/seon/agent/ctx/driver.cljs:205-338`). A one-token
  transcript append re-rendered, re-serialized and re-sent the page. At
  the owner's 16 ms budget that is the budget, spent on parts nobody
  changed.

  Here the MORPH TARGET IS THE BLOCK. `surface-id` derives a stable DOM
  id per block, each block renders independently, and equality
  suppression is per block — so a transcript append re-renders the
  transcript and sends the transcript, and the header's bytes are never
  recomputed. The block set was ALREADY the natural unit; the old system
  simply had no per-block address, and inventing one costs one function.
  Everything downstream follows: interest is per block, the registration
  memory is keyed by block, and the 32-tab falsifier's one evaluation is
  one evaluation of one block.

  Consequence the seal must carry: the quarry's `#morph`-scoped CSS
  animations (`resources/public/css/input.css:227-298`) were already dead
  against `#app-view` and must be re-pointed at the surface ids, and
  Datastar's client-side pane signal (`$selected`) survives unchanged —
  it exists precisely so switching panes needs no server round-trip.

  Crash walk: pure over a database value. Nothing here opens, commits or
  holds anything; a kill during a render loses hiccup nobody had sent,
  and the next render derives the same value from the same facts."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.schema.edn :as schema.edn]
            [seon.sci.admit :as admit]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/block.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The address
;;; ---------------------------------------------------------------------------

(defn surface-id
  "The DOM element id for the block named `name`. THE one derivation.

  One function so the hole and the patch can never drift: `slot` emits
  this id, the morph targets this id, and there is no second place that
  builds it by string concatenation. `:transcript` → `\"surface-transcript\"`.

  INJECTIVE, and that is a requirement rather than an observation: two
  blocks sharing an id would silently morph over each other. A qualified
  name keeps its namespace (`:my.plan/tree` → `\"surface-my.plan--tree\"`)
  so `:a/x` and `:b/x` cannot collide, and every character outside
  `[A-Za-z0-9._-]` is escaped rather than dropped — dropping is what
  makes two different names one id."
  {:malli/schema [:=> [:cat :seon.block/name] :seon.render/surface-id]}
  [name]
  ;; `[A-Za-z0-9.-]` pass through, so the ordinary case is unchanged and
  ;; a hyphenated name stays readable. `_` is the escape introducer and
  ;; therefore doubles; everything else becomes `_<hex>_`. That is what
  ;; makes the map injective — a scheme that DROPPED unsafe characters
  ;; is exactly how two names become one id and two blocks morph over
  ;; each other.
  (let [text (subs (str name) 1)
        builder (StringBuilder. (+ 8 (.length ^String text)))]
    (.append builder "surface-")
    (dotimes [index (.length ^String text)]
      (let [character (.charAt ^String text index)]
        (cond
          (= \_ character) (.append builder "__")
          (or (Character/isLetterOrDigit character)
              (= \. character)
              (= \- character)) (.append builder character)
          :else (doto builder
                  (.append "_")
                  (.append (Integer/toHexString (int character)))
                  (.append "_")))))
    (.toString builder)))

(defn slot
  "An empty hole for the block named `name`.

  `[:div {:id \"surface-<name>\" :data-slot \"<name>\"}]` — a marker, not
  a resolution. A layout emits slots and knows nothing about what fills
  them; `expand` fills them. The hole and the filled surface carry the
  SAME id, so a morph replaces the hole in place and a reconnect that
  paints holes first is a coherent intermediate page rather than a torn
  one."
  {:malli/schema [:=> [:cat :seon.block/name] :seon.render/hiccup]}
  [name]
  ;; the explicit `""` child is not decoration: it makes the hole a
  ;; non-void element with a closing tag, so `<div id="surface-x"></div>`
  ;; is a stable morph target from the first paint.
  [:div {:id (surface-id name) :data-slot (subs (str name) 1)} ""])

(defn entity-slot
  "A hole for the ENTITY reached by `lookup`, to be filled by rendering it.

  The second hole kind, and deliberately the same shape as the first:
  filling a slot and following a connection are the same act, so they
  are the same marker with a different key and the same bounded walk
  fills both. `lookup` is anything `d/pull` accepts as an eid — a
  `:db/id`, or a lookup ref like `[:seon.cluster.run/id \"run-7f21\"]`.

  This is what makes a rendered unit EMBED its refs: a renderer that
  wants the run behind `:seon.error/run` emits this, and expansion
  renders that run in place, and whatever the run's own render embeds is
  expanded in turn until the budget says stop."
  {:malli/schema [:=> [:cat :any] :seon.render/hiccup]}
  [lookup]
  [:div {:data-ref (pr-str lookup)} ""])

;;; ---------------------------------------------------------------------------
;;; The derivation
;;; ---------------------------------------------------------------------------

(defn blocks
  "The agent's complete block set at `db`, ordered.

  ORDER IS DERIVED: `:seon.block/priority` ascending, with the block
  name as a stable tiebreaker, so two derivations of one database value
  are the same value. The stored collection is a SET and has no order to
  lose.

  ONE COLLECTION, no merge. Each agent owns its complete set, seeded at
  creation, so this is one pull and not a reconciliation against a
  default catalog — the quarry's own conclusion
  (`src-old/seon/agent/ctx.cljc:1619-1641`) and the reason \"what does
  this agent see?\" has a single answer.

  An agent with no blocks derives `[]`. That is a legitimate agent, not
  an error and not a cue to substitute defaults: an absent block tree
  means no blocks."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id]
                  [:vector :seon.block/block]]}
  [db agent-id]
  (->> (d/q '[:find [(pull ?block [*]) ...]
              :in $ ?agent-id
              :where
              [?agent :seon.cluster.agent/id ?agent-id]
              [?agent :seon.cluster.agent/blocks ?block]]
            db agent-id)
       ;; the entity id is Datahike's, not ours; a block carrying it
       ;; would not validate as one
       (map (fn [block] (dissoc block :db/id)))
       (sort-by (juxt :seon.block/priority :seon.block/name))
       vec))

(defn unit
  "The unit `seon.render/render` receives for one block.

  The block's own map, plus the exact immutable database value under
  `:seon.db/db` and the agent under `:seon.cluster.agent/id`. That is
  the whole composition — a block IS a unit, and the projection reads
  what it needs from the unit it is handed.

  THE DATABASE VALUE IS PART OF THE UNIT, never ambient. A projection
  that consulted a latest value would render a page at a basis the rest
  of the page was not rendered at, and \"what the agent saw at turn N\"
  would stop being re-derivable. Every projection this repository ships
  reads `:seon.db/db` or reads nothing."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id :seon.block/block]
                  :seon.render/unit]}
  [db agent-id block]
  (assoc block
         :seon.db/db db
         :seon.cluster.agent/id agent-id))

(defn surface
  "Render one block into one kind. Never throws.

  Returns a `:seon.render/surface`: name, id, kind, and EITHER the
  output OR a flat `:seon.error/value`. Failure is a sibling of success
  rather than a key beside it, because `:seon.error/value` is a closed
  shape registered once.

  ISOLATION IS STRUCTURAL. Three failures are all values here, and none
  of them can reach a neighbour:
  - the block declares no such kind — `seon.render/render` says so, and
    an ai-only block asked for html is not an error at the CALLER (see
    `surfaces`), it simply produces no surface;
  - the projection does not resolve, or throws — the landed router
    already returns a value naming the projection, and this passes it
    through unchanged rather than re-wrapping it;
  - the projection returns something that is not the kind's grammar —
    for `:seon.render/html` that means `hiccup?` refuses, and this is
    the one check the router cannot make, because a kind's grammar
    belongs to the kind's consumer. A refusal names the block and the
    shape that arrived.

  That third case is the quarry's silent bug made loud: the old
  serializer elided a bare map child and the page looked fine
  (`src-old/seon/ui/html.cljc`, `render-content`'s map branch and its
  own flag). Here the block gets an error card with its name on it."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id :seon.block/block
                       :seon.render/kind]
                  :seon.render/surface]}
  [db agent-id block kind]
  (let [name (:seon.block/name block)
        base {:seon.block/name name
              :seon.render/surface-id (surface-id name)
              :seon.render/kind kind}
        rendered (render/render {:seon.render/unit (unit db agent-id block)
                                 :seon.render/kind kind})]
    (cond
      ;; the landed router already named what is broken; passing its
      ;; value through unchanged is the difference between one error
      ;; owner and two
      (:seon.error/kind rendered)
      (assoc base :seon.error/value rendered)

      ;; the ONE check the router cannot make: a kind's grammar belongs
      ;; to the kind's consumer, and html's consumer is a browser
      (and (= :seon.render/html kind)
           (not (hiccup/hiccup? (:seon.render/output rendered))))
      (assoc base :seon.error/value
             {:seon.error/kind ::not-hiccup
              :seon.error/message
              (str "The " name " block's html render returned something that "
                   "is not hiccup.")
              :seon.error/data
              {:seon.block/name name
               :seon.render/projection (get block kind)
               ::shape (let [output (:seon.render/output rendered)]
                         (if (nil? output)
                           "nil"
                           (.getName (class output))))}})

      :else
      (assoc base :seon.render/output (:seon.render/output rendered)))))

(defn surfaces
  "The agent's blocks rendered into one kind, in order.

  PRESENCE DECIDES PLACEMENT — the whole selection mechanism, and the
  reason no block carries a flag saying where it goes. A block that does
  not declare the requested kind is OMITTED, so an html-only widget
  costs the prompt zero tokens and an ai-only warning occupies no pixels
  (`src-old/seon/agent/ctx.cljc:1675-1689`, whose inverse rule is the
  same rule).

  One block, one evaluation, whatever the number of consumers. This
  function is the one place a block is rendered, so the prompt and every
  open tab read one value at one database value — the property the
  32-tab falsifier measures and the reason there is ONE registration per
  block rather than one per projection."
  {:malli/schema [:=> [:cat :any :seon.render/surfaces-request]
                  :seon.render/surfaces]}
  [db {:keys [:seon.cluster.agent/id :seon.render/kind]}]
  (into []
        (comp
         ;; PRESENCE DECIDES PLACEMENT — omission, not an error. A block
         ;; that says nothing about a kind has nothing to say there.
         (filter (fn [block] (contains? block kind)))
         (map (fn [block] (surface db id block kind))))
        (blocks db id)))

;;; ---------------------------------------------------------------------------
;;; Placement
;;; ---------------------------------------------------------------------------

(defn entity-unit
  "One entity at `db`, as a unit the router can project.

  A pulled entity IS a unit, and that is the whole reason ref-following
  needs no new mechanism: the pull is a map of qualified keys, and if
  the entity carries a stored `:seon.render/html` the router resolves it
  exactly as it resolves a block's. The database value rides along under
  `:seon.db/db`, so whatever the entity's renderer needs to query, it
  queries at the same basis the rest of the page was rendered at.

  Nested ref values arrive from `d/pull` as `{:db/id N}` maps, which the
  hiccup grammar refuses — correctly, since a ref is not content. They
  become expandable holes rather than printed maps: see `expand`.

  Returns a flat error value when `lookup` resolves to nothing, because
  a dangling ref is a fact about the database and not a reason to stop
  rendering a page."
  {:malli/schema [:=> [:cat :any :any] [:or :seon.render/unit :seon.error/value]]}
  [db lookup]
  (let [pulled (try (d/pull db '[*] lookup) (catch Throwable _ nil))]
    (if (or (nil? pulled) (nil? (:db/id pulled)))
      {:seon.error/kind ::no-such-entity
       :seon.error/message (str "Nothing in the database answers to "
                                (pr-str lookup) ".")
       :seon.error/data {::lookup (pr-str lookup)}}
      (assoc pulled :seon.db/db db))))

(defn- error-card
  "A failed surface, as hiccup that keeps the block's address.

  It carries the block's own id, so the error occupies exactly the space
  the working surface would have and the next successful render morphs
  over it in place. Naming the block is the whole point: the quarry's
  equivalent failure elided the content and the page looked fine."
  [surface failure]
  [:div {:id (:seon.render/surface-id surface)
         :class "seon-error-card"
         :data-block (subs (str (:seon.block/name surface)) 1)}
   [:span {:class "seon-error-card-name"}
    (str (:seon.block/name surface))]
   [:span {:class "seon-error-card-message"}
    (:seon.error/message failure)]])

(defn expand
  "Replace every slot in `hiccup` with the named surface, to fixpoint.

  A rendered surface may itself contain slots, so expansion recurses:
  that is what makes a layout a layout. `layout-vs-surface is a role,
  never stored` — a render with child slots is a layout, a render with
  none is a leaf — so nothing declares which it is and a block can
  become either by changing what it returns.

  Three refusals, each a value in place rather than a throw, so one bad
  slot costs one hole and not the page:
  - a slot naming a block the agent does not own — the hole stays,
    carrying a legible note. This is the quarry's self-healing behaviour
    (`src-old/seon/render.cljc:1047-1056`): name the block, and the next
    render fills it;
  - a slot naming a block whose surface failed — the surface's error
    card fills the hole, so the failure appears WHERE it belongs;
  - a CYCLE — a slots b slots a. Refused at the hole that closes the
    loop, naming the cycle. The visited set along the PATH is the
    observable fact, so a chain that closes is caught exactly where it
    closes;
  - the BUDGET is exhausted. The hole stays, saying so.

  THE VISITED SET IS NOT ENOUGH ON ITS OWN, and the first draft of this
  function shipped believing it was. A visited set is per PATH, so it
  refuses cycles and permits fan-out: a slots b twice, b slots c twice,
  and twenty-two blocks with no cycle anywhere expand to four million
  nodes. Measured — `tmp/n4_expand_blowup.clj` OOMs the JVM at depth 22.
  A graph that can fan out needs a NODE budget, not just a loop guard,
  which is the same lesson `seon.sci.admit` already paid for on value
  trees; expansion therefore takes the SAME four dials rather than a
  second set to drift from them.

  Bounded expansion is also the general mechanism, not a slot-specific
  one. A unit's rendered form embeds its refs as units, and following a
  connection is the same act as filling a slot: ask the router for a
  node, descend, count it, stop at the budget or at a node already on
  the path. The entity graph can genuinely cycle where a value tree
  could not, so the visited set is load-bearing there and the budget
  bounds the fan-out. Ref-following is its own owner (it needs the
  database value and a per-node router call); this function is that
  owner's discipline, proven on the closed case first.

  Deterministic: depth-first, left to right, so one input always elides
  the same holes. A budget that elided a different subtree per call
  would make equality suppression meaningless."
  {:malli/schema [:=> [:cat :seon.render/hiccup :seon.render/expansion]
                  :seon.render/hiccup]}
  [hiccup {:keys [:seon.render/surfaces :seon.db/db]
           caps :seon.sci.admit/caps}]
  (let [by-id (into {} (map (juxt :seon.render/surface-id identity)) surfaces)
        remaining (volatile! (long (:seon.config.eval.result/max-nodes caps)))
        max-depth (long (:seon.config.eval.result/max-depth caps))]
    (letfn [(note [hole text]
              ;; the HOLE stays, carrying why. Self-healing, the
              ;; quarry's behaviour: name the block, and the next render
              ;; fills it.
              (conj (subvec hole 0 2) text))
            (fill [hole visited depth]
              (let [id (:id (nth hole 1))
                    slot-name (:data-slot (nth hole 1))]
                (cond
                  (contains? visited id)
                  (note hole (str "cycle: " slot-name
                                  " is already being expanded on this path"))

                  (>= depth max-depth)
                  (note hole (str "not expanded: " slot-name
                                  " is deeper than the configured depth"))

                  :else
                  (if-let [found (by-id id)]
                    (if-let [failure (:seon.error/value found)]
                      (error-card found failure)
                      (walk (:seon.render/output found)
                            (conj visited id)
                            (inc depth)))
                    (note hole (str "no block named " slot-name
                                    " — install one and this fills itself"))))))
            (slot? [node]
              (and (vector? node)
                   (map? (nth node 1 nil))
                   (contains? (nth node 1) :data-slot)))
            (ref? [node]
              (and (vector? node)
                   (map? (nth node 1 nil))
                   (contains? (nth node 1) :data-ref)))
            (follow [hole visited depth]
              ;; FOLLOWING A CONNECTION IS FILLING A SLOT. Same budget,
              ;; same per-path visited set, same in-place refusals — the
              ;; only difference is where the node comes from, and the
              ;; entity graph can genuinely cycle where a block set
              ;; merely fans out.
              (let [encoded (:data-ref (nth hole 1))]
                (cond
                  (contains? visited encoded)
                  (note hole (str "cycle: " encoded
                                  " is already being expanded on this path"))

                  (>= depth max-depth)
                  (note hole (str "not expanded: " encoded
                                  " is deeper than the configured depth"))

                  (nil? db)
                  (note hole (str "not expanded: " encoded
                                  " needs a database value on the expansion"))

                  :else
                  (let [lookup (try (edn/read-string encoded)
                                    (catch Throwable _ ::unreadable))
                        unit (if (= ::unreadable lookup)
                               {:seon.error/kind ::unreadable-ref
                                :seon.error/message
                                (str "This ref is not readable: " encoded)}
                               (entity-unit db lookup))]
                    (if (:seon.error/kind unit)
                      (note hole (:seon.error/message unit))
                      ;; the entity's OWN declaration if it has one, and
                      ;; the kind's generic default if it does not — so a
                      ;; ref to something nobody wrote a renderer for is
                      ;; still legible, which is what makes /data work
                      ;; with zero authoring
                      (let [declared (contains? unit :seon.render/html)
                            rendered
                            (render/render
                             {:seon.render/unit
                              (if declared
                                unit
                                (assoc unit :seon.render/html
                                       `data-panel
                                       :seon.sci.admit/caps caps
                                       :seon.render/value (dissoc unit :seon.db/db)))
                              :seon.render/kind :seon.render/html})]
                        (if (:seon.error/kind rendered)
                          (note hole (:seon.error/message rendered))
                          (walk (:seon.render/output rendered)
                                (conj visited encoded)
                                (inc depth)))))))))
            (walk [node visited depth]
              ;; every node counts, and the budget is checked BEFORE the
              ;; work rather than after: a check that fires once the
              ;; subtree is already built has not bounded anything
              (if (neg? (vswap! remaining dec))
                ;; deterministic, because the walk is depth-first and
                ;; left-to-right: one input always elides the same holes,
                ;; which equality suppression depends on
                [:span {:class "seon-expansion-elided"}
                 "elided — this page is larger than the configured caps"]
                (cond
                  (slot? node) (fill node visited depth)
                  (ref? node) (follow node visited depth)
                  ;; an element keeps its HEAD and its attributes: only
                  ;; children are walked. Walking the whole vector let an
                  ;; exhausted budget replace a `:div` TAG with an
                  ;; elision span, which is not an element at all — the
                  ;; grammar caught it, which is what the grammar is for.
                  (vector? node)
                  (let [attributed? (and (map? (nth node 1 nil))
                                         (not (hiccup/raw? (nth node 1))))
                        prefix (subvec node 0 (if attributed? 2 1))]
                    (into prefix
                          (map (fn [child] (walk child visited depth)))
                          (subvec node (count prefix))))
                  ;; a seq child stays a SEQ — turning it into a vector
                  ;; would make it look like an element with a non-tag
                  ;; head, which the grammar rightly refuses. `doall`
                  ;; because a lazy page is a page that renders somewhere
                  ;; else, later, on a thread nobody chose.
                  (sequential? node)
                  (doall (map (fn [child] (walk child visited depth)) node))
                  :else node)))]
      (walk hiccup #{} 0))))

(defn page
  "The agent's html page: the top-level surfaces, expanded, in order.

  TOP-LEVEL IS DERIVED, and this is where the old `:layout` flag would
  have gone if the design needed one. A block is top-level when no other
  block's surface slots it in — so a layout that slots `:transcript` and
  `:canvas` makes those two its children by SAYING SO in its hiccup, and
  a block nobody slots stands on its own as a root card. Nothing is
  declared, nothing is stored, and moving a block into a layout is one
  edit in one render fn.

  Several top-level blocks are the normal case, not a degenerate one:
  the architecture's root cards, canvas, context surfaces, debug and
  `/data` are independently patched units, and a page is their ordered
  concatenation. One top-level block that slots everything else is the
  same mechanism arranged differently.

  Returns a VECTOR OF ELEMENTS, which is deliberately not itself hiccup:
  a vector whose head is a vector is not an element, and the grammar
  refuses it. A caller places the entries — `(seq page)` splices them as
  a fragment. That refusal is a feature; the alternative is a container
  this function invented, and placement belongs to whoever owns the
  document.

  Each entry keeps its own `surface-id`, because each entry is its own
  morph target. The page is assembled once for the initial paint and
  never again: after that the pipeline patches the ONE block that
  changed."
  {:malli/schema [:=> [:cat :any :seon.render/page-request] :seon.render/page]}
  [db {:keys [:seon.cluster.agent/id] caps :seon.sci.admit/caps}]
  (let [agent-id id
        surfaces (surfaces db {:seon.cluster.agent/id agent-id
                               :seon.render/kind :seon.render/html})
        ;; every id some OTHER surface slots. Derived by looking at what
        ;; the hiccup says, which is what makes `layout-vs-surface is a
        ;; role` executable rather than aspirational.
        slotted (into #{}
                      (mapcat (fn [surface]
                                (keep (fn [node]
                                        (when (and (vector? node)
                                                   (map? (nth node 1 nil))
                                                   (contains? (nth node 1)
                                                              :data-slot))
                                          (:id (nth node 1))))
                                      (tree-seq sequential? seq
                                                (:seon.render/output surface)))))
                      surfaces)
        top (remove (fn [surface]
                      (contains? slotted (:seon.render/surface-id surface)))
                    surfaces)
        ;; EVERY surface slotted and none top-level means the slots form
        ;; a closed cycle. Rendering nothing would be the silent failure
        ;; this design refuses, so every surface becomes top-level and
        ;; `expand`'s visited set turns the cycle into a legible note in
        ;; the hole that closes it.
        roots (if (and (empty? top) (seq surfaces)) surfaces top)]
    (mapv (fn [surface]
            (if-let [failure (:seon.error/value surface)]
              (error-card surface failure)
              (expand (:seon.render/output surface)
                      {:seon.render/surfaces surfaces
                       :seon.sci.admit/caps caps
                       :seon.db/db db})))
          roots)))

;;; ---------------------------------------------------------------------------
;;; Generic default + specialist — the reusable selection shape
;;; ---------------------------------------------------------------------------

(defn select
  "The projection symbol for one value and one kind.

  THE SHAPE THE OWNER RULED, 2026-07-27 night, named here because N4 is
  where it becomes reusable: every output kind has a GENERIC default
  that can project any value of the family, and a producer that knows
  more points that value's key at a SPECIALIST — chosen WHERE THE UNIT
  IS BUILT, computed from the value's own attributes, never by a
  conditional at the consumer.

  The first specialist whose predicate accepts `value` wins; ordering is
  the producer's judgement and this makes no attempt to score
  specificity. No specialists, or none that accept, yields the default —
  so a family with nothing special to say writes no special code.

  WHY THE CONSUMER SIDE DISAPPEARS. The key on the unit is the entire
  answer, so a consumer hands the unit to `seon.render/render` and takes
  what comes back. `seon.error`'s steering prose stops being a function
  anybody calls and becomes the DEFAULT that `:seon.render/ai` points
  at; a malli violation's detailed explanation becomes a specialist the
  error producer selects from the fact's own attributes. Neither name
  ever leaves its producer, which is what makes \"only through the
  router\" a property of the code rather than a rule people remember.

  TOTAL, because it runs where units are built and that is often the
  error path. A predicate that throws is treated as not accepting and
  the next rule is tried — a broken rule costs its own specialist, never
  the render. Both halves are late-resolved symbols, so re-evaluating
  either a rule or a renderer changes the next render.

  A rule that does not resolve is likewise not accepting. The producer's
  bug is a generically-rendered value, which is legible, rather than an
  exception where a value was expected."
  {:malli/schema [:=> [:cat :any :seon.render/selection]
                  :seon.render/projection]}
  [value {:seon.render/keys [default specialists]}]
  (or (some (fn [[rule projection]]
              ;; a rule that cannot answer has not accepted. Late
              ;; resolution keeps the rule as hot-reloadable as the
              ;; renderer it picks; the try makes a broken rule cost its
              ;; own specialist and nothing else.
              (when (try
                      (when-let [accepts? (requiring-resolve rule)]
                        (accepts? value))
                      (catch Throwable _ false))
                projection))
            specialists)
      default))

(defn data-panel
  "`:seon.render/html`'s GENERIC default: any value, as a readable panel.

  The kind's floor. Every html render is either this or a specialist
  that a producer chose over it, so nothing is ever unrenderable and no
  producer has to write a renderer before it can be seen — which is the
  property that makes the pattern worth having.

  Reads the value under `:seon.render/value` from the unit, so a
  producer declaring `{:seon.render/html `data-panel :seon.render/value
  x}` needs nothing else. A unit with no such key panels the unit
  itself, minus its own projection declarations: a bare map is data too,
  and printing a symbol back at a reader is noise.

  Never throws and never prints an unbounded value: nesting beyond the
  configured depth and collections beyond the configured width render
  as an explicit elision marker rather than a wall. The bound is the
  same `:seon.sci.admit/caps` the eval door already carries, because a
  second set of size dials would drift from the first.

  It is the LOWEST-fidelity renderer on purpose. A panel that tried to
  be clever would compete with the specialists instead of backstopping
  them.

  Caps are REQUIRED and there is no default here. A shipped constant
  would be a second set of size dials drifting from the config facts,
  and inventing one is the banned magic number — so a unit that supplies
  none gets a card saying so, which is loud, legible, and impossible to
  mistake for a rendered value."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (if-let [caps (:seon.sci.admit/caps unit)]
    (let [value (if (contains? unit :seon.render/value)
                  (:seon.render/value unit)
                  ;; a bare unit is data too — but printing its own
                  ;; projection symbols back at the reader is noise
                  (apply dissoc unit :seon.db/db (render/kinds unit)))
          ;; ONE bounding owner. `admit` already walks once, elides past
          ;; the configured depth and width, never dereferences an
          ;; IDeref and cannot loop on a cycle. Rebuilding any of that
          ;; here would be a second codec to keep in step with the first.
          {:seon.sci.admit/keys [value capped?]}
          (admit/admit {:seon.sci.admit/value value
                        :seon.sci.admit/caps caps
                        ;; nothing is armed: this is not an eval, and
                        ;; admission's bounds are the whole guard here
                        :seon.sci.admit/interrupt-fn (fn [])
                        :seon.config/on-core-error :log})]
      [:div {:class "seon-data-panel"}
       (letfn [(panel [node]
                 (cond
                   (map? node)
                   [:dl {:class "seon-data-map"}
                    (doall (mapcat (fn [[key setting]]
                                     [[:dt {:class "seon-data-key"} (str key)]
                                      [:dd {:class "seon-data-value"} (panel setting)]])
                                   (sort-by (comp str first) (seq node))))]

                   (set? node)
                   [:ul {:class "seon-data-set"}
                    (doall (map (fn [entry] [:li (panel entry)])
                                (sort-by str (seq node))))]

                   (sequential? node)
                   [:ol {:class "seon-data-list"}
                    (doall (map (fn [entry] [:li (panel entry)]) node))]

                   (string? node) [:span {:class "seon-data-string"} node]
                   (nil? node) [:span {:class "seon-data-nil"} "nil"]
                   :else [:span {:class "seon-data-scalar"} (str node)]))]
         (panel value))
       (when capped?
         ;; the honest signal, kept: a reader must never have to guess
         ;; whether an elision marker was the value's own data
         [:p {:class "seon-data-capped"}
          "elided — this value is larger than the configured caps"])])
    [:div {:class "seon-error-card"}
     [:span {:class "seon-error-card-message"}
      (str "This panel needs :seon.sci.admit/caps on the unit; without "
           "them nothing bounds what it would print.")]]))

;;; ---------------------------------------------------------------------------
;;; Writing a block set
;;; ---------------------------------------------------------------------------

(defn install-tx
  "Transaction data upserting `blocks` into the agent's set. PURE.

  Returns tx-data; the CALLER commits it through
  `seon.cluster.store/transact!`. The same shape `seon.error/refusal`
  established — one owner derives, one owner writes, and this namespace
  stays pure over a database value.

  UPSERT BY NAME, within one agent. The name is a plain keyword and NOT
  a database identity, because two agents each own a `:transcript` and a
  store-wide unique name would forbid exactly that. Uniqueness is
  therefore per agent and enforced here: an existing block of the same
  name is REPLACED wholesale, never merged, so removing a key from a
  block removes it from the block — a merge would make a block's fields
  un-deletable, which is how the quarry's config overlay
  (`src-old/seon/config/resolve.cljc:1443-1454`) behaves and is right for
  a MANIFEST overlay but wrong for an install.

  Empty `blocks` yields empty tx-data. Nothing to say is no transaction,
  the same rule `seon.reconcile` proved: converged means zero writes."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id
                       [:vector :seon.block/block]]
                  [:vector :any]]}
  [db agent-id blocks]
  (if (empty? blocks)
    []
    (let [names (into #{} (map :seon.block/name) blocks)
          replaced (d/q '[:find [?block ...]
                          :in $ ?agent-id ?names
                          :where
                          [?agent :seon.cluster.agent/id ?agent-id]
                          [?agent :seon.cluster.agent/blocks ?block]
                          [?block :seon.block/name ?name]
                          [(contains? ?names ?name)]]
                        db agent-id names)]
      ;; RETRACT then ADD, rather than merge: removing a key from a block
      ;; must remove it from the block. A merge would make
      ;; `:seon.render/ai` un-deletable and quietly keep a block in the
      ;; prompt after its author took it out.
      (-> (mapv (fn [entity] [:db/retractEntity entity]) (sort replaced))
          (conj {:seon.cluster.agent/id agent-id
                 :seon.cluster.agent/blocks (vec blocks)})))))
