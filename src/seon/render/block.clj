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
  (:require [seon.schema.edn :as schema.edn]))

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
  [_name]
  (throw (ex-info "seon.render.block/surface-id awaits implementation" {})))

(defn slot
  "An empty hole for the block named `name`.

  `[:div {:id \"surface-<name>\" :data-slot \"<name>\"}]` — a marker, not
  a resolution. A layout emits slots and knows nothing about what fills
  them; `expand` fills them. The hole and the filled surface carry the
  SAME id, so a morph replaces the hole in place and a reconnect that
  paints holes first is a coherent intermediate page rather than a torn
  one."
  {:malli/schema [:=> [:cat :seon.block/name] :seon.render/hiccup]}
  [_name]
  (throw (ex-info "seon.render.block/slot awaits implementation" {})))

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
  [_db _agent-id]
  (throw (ex-info "seon.render.block/blocks awaits implementation" {})))

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
  [_db _agent-id _block]
  (throw (ex-info "seon.render.block/unit awaits implementation" {})))

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
  [_db _agent-id _block _kind]
  (throw (ex-info "seon.render.block/surface awaits implementation" {})))

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
  [_db _request]
  (throw (ex-info "seon.render.block/surfaces awaits implementation" {})))

;;; ---------------------------------------------------------------------------
;;; Placement
;;; ---------------------------------------------------------------------------

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
    loop, naming the cycle. A depth counter would be a magic number
    standing in for an observable fact; the visited set on the path IS
    the observable fact."
  {:malli/schema [:=> [:cat :seon.render/hiccup :seon.render/surfaces]
                  :seon.render/hiccup]}
  [_hiccup _surfaces]
  (throw (ex-info "seon.render.block/expand awaits implementation" {})))

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

  Each entry keeps its own `surface-id`, because each entry is its own
  morph target. The page is assembled once for the initial paint and
  never again: after that the pipeline patches the ONE block that
  changed."
  {:malli/schema [:=> [:cat :any :seon.cluster.agent/id] :seon.render/page]}
  [_db _agent-id]
  (throw (ex-info "seon.render.block/page awaits implementation" {})))

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
  [_value _selection]
  (throw (ex-info "seon.render.block/select awaits implementation" {})))

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
  them."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [_unit]
  (throw (ex-info "seon.render.block/data-panel awaits implementation" {})))

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
  [_db _agent-id _blocks]
  (throw (ex-info "seon.render.block/install-tx awaits implementation" {})))
