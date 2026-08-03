(ns seon.render.block
  "Blocks — the one mechanism behind every page and every prompt.

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
  surfaces into a page and the retained structural value floors. It owns no
  transport, registry, renderer table, slot expander, or second walk.

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
  (:require [seon.render :as render]
            [seon.render.hiccup :as hiccup]
            [seon.render.value :as value]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; The address
;;; ---------------------------------------------------------------------------

(defn surface-id
  "The DOM element id for the block named `name`. THE one derivation.

  One function so a render call and its morph target cannot drift. There is
  no second place that builds the id by string concatenation.
  `:transcript` → `\"surface-transcript\"`.

  INJECTIVE, and that is a requirement rather than an observation: two
  blocks sharing an id would silently morph over each other. A qualified
  name keeps its namespace (`:my.plan/tree` → `\"surface-my.plan_2f_tree\"`,
  the `/` escaped as hex) so `:a/x` and `:b/x` cannot collide, and every
  character outside `[A-Za-z0-9._-]` is escaped rather than dropped —
  dropping is what makes two different names one id."
  {:malli/schema [:=> [:cat :seon.render.block/name] :seon.render/surface-id]}
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

(defn distance
  "The hops of neighborhood `request` asks for. THE ONE DEFAULT SITE.

  Distance is IMPLIED 1 and never required, so every caller that says
  nothing asks for the ordinary reach and no caller has to know the
  number. This is the only place `1` is written: `unit` puts it on the
  unit so a projection is CALLED with it, and `page` seeds the expansion
  with it so the hops it spends are the hops that were asked for."
  {:malli/schema [:=> [:cat :map] :seon.render/distance]}
  [request]
  (get request :seon.render/distance 1))

(defn unit
  "The unit `seon.render/render` receives for one block.

  The block's own map plus the request's database value, agent id,
  caps, and — when supplied — the live-process snapshot. ONE builder
  for prompt, page, debug and capture, so every projection, AI
  admission, generic panel and bounded expansion sees the SAME caps
  and the SAME snapshot.

  THE DATABASE VALUE IS PART OF THE UNIT, never ambient. A projection
  that consulted a latest value would render a page at a basis the rest
  of the page was not rendered at, and \"what the agent saw at turn N\"
  would stop being re-derivable. Every projection this repository ships
  reads `:seon.db/db` or reads nothing.

  THE UNIT CARRIES ITS DISTANCE, which is what makes distance an
  argument TO the renderer rather than a property of it: the projection
  is CALLED with the hops it may spend and decides what to do with them,
  and a projection that never looks is correct. Implied 1, applied here
  and nowhere else."
  {:malli/schema [:=> [:cat :seon.render/unit-request :seon.render.block/block]
                  :seon.render/unit]}
  [request block]
  (merge block
         {:seon.render/distance (distance request)}
         (select-keys request [:seon.db/db
                               :seon.cluster.agent/id
                               :seon.sci.admit/caps
                               :seon.cluster.run/live-processes
                               ;; the TRANSIENT stream snapshot (F2 §2):
                               ;; channel-borne presentation the render
                               ;; pass threads through, never a fact
                               :seon.ai/partial])))

(defn surface
  "Render one block into one kind. Never throws.

  Returns a `:seon.render/surface`: name, id, kind, and EITHER the
  output OR a flat `:seon.error/value`. Nil output means the projection
  omitted itself; the web consumer preserves its identified wrapper.
  Failure is a sibling of success rather than a key beside it, because
  `:seon.error/value` is a closed shape registered once.

  ISOLATION IS STRUCTURAL. Four failures are all values here, and none
  of them can reach a neighbour:
  - the block declares no such kind — `seon.render/render` says so;
  - the projection does not resolve, or throws — the landed router
    already returns a value naming the projection, and this passes it
    through unchanged rather than re-wrapping it;
  - a nested render boundary returns a flat error value — the outer
    router preserves it as output, and this boundary restores it to the
    surface's error sibling without changing its kind or evidence;
  - the projection returns something that is not the kind's grammar —
    for `:seon.render/html` that means `hiccup?` refuses, and this is
    the one check the router cannot make, because a kind's grammar
    belongs to the kind's consumer. A refusal names the block and the
    shape that arrived.

  That third case is the quarry's silent bug made loud: the old
  serializer elided a bare map child and the page looked fine
  (`src-old/seon/ui/html.cljc`, `render-content`'s map branch and its
  own flag). Here the block gets an error card with its name on it."
  {:malli/schema [:=> [:cat :seon.render/unit-request :seon.render.block/block
                       :seon.render/kind]
                  :seon.render/surface]}
  [request block kind]
  (let [name (:seon.render.block/name block)
        base {:seon.render.block/name name
              :seon.render/surface-id (surface-id name)
              :seon.render/kind kind}
        rendered (render/render {:seon.render/unit (unit request block)
                                 :seon.render/kind kind})]
    (cond
      ;; the landed router already named what is broken; passing its
      ;; value through unchanged is the difference between one error
      ;; owner and two
      (:seon.error/kind rendered)
      (assoc base :seon.error/value rendered)

      ;; A projection may itself be a render boundary. The router wraps
      ;; every successful return so an ordinary output map is
      ;; unambiguous; a returned value that satisfies the one closed
      ;; error shape is nevertheless still an error at the block
      ;; boundary, not html for the grammar check to reclassify.
      (schema/valid-candidate-value? :seon.error/value
                                     (:seon.render/output rendered))
      (assoc base :seon.error/value (:seon.render/output rendered))

      ;; the ONE check the router cannot make: a kind's grammar belongs
      ;; to the kind's consumer, and html's consumer is a browser. Nil
      ;; is omission, not malformed hiccup.
      (and (= :seon.render/html kind)
           (some? (:seon.render/output rendered))
           (not (hiccup/hiccup? (:seon.render/output rendered))))
      (assoc base :seon.error/value
             {:seon.error/kind ::not-hiccup
              :seon.error/message
              (str "The " name " block's html render returned something that "
                   "is not hiccup.")
              :seon.error/data
              {:seon.render.block/name name
               :seon.render/projection (get block kind)
               ::shape (let [output (:seon.render/output rendered)]
                         (if (nil? output)
                           "nil"
                           (.getName (class output))))}})

      :else
      (assoc base :seon.render/output (:seon.render/output rendered)))))

;;; ---------------------------------------------------------------------------
;;; Generic default + specialist — the reusable selection shape
;;; ---------------------------------------------------------------------------

(defn- floor-unit
  [unit]
  (if (contains? unit :seon.render/value)
    unit
    (let [omitted-render-keys
          (into []
                (comp
                 (filter #(= "seon.render" (namespace %)))
                 (filter #(nil? (get unit %))))
                (keys unit))]
      (assoc unit :seon.render/value
             (apply dissoc unit
                    :seon.db/db
                    :seon.render/distance
                    :seon.render/namespace
                    :seon.render/would-fall-to-floor?
                    :seon.sci.admit/caps
                    (concat (render/kinds unit) omitted-render-keys))))))

(defn data-panel
  "`:seon.render/html`'s GENERIC default: any value, as a readable panel.

  The kind's floor. Every html render is either this or a specialist
  that a producer chose over it, so nothing is ever unrenderable and no
  producer has to write a renderer before it can be seen — which is the
  property that makes the pattern worth having.

  Reads a value under `:seon.render/value` from the unit, so a
  producer declaring `{:seon.render/html `data-panel :seon.render/value
  x}` needs nothing else. A unit with no such key panels the unit itself,
  minus its own projection declarations and
  omitted render keys: a bare map is data too, and printing a symbol
  back at a reader is noise.

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
  (value/render-html (floor-unit unit)))

(defn data-prose
  "`:seon.render/ai`'s GENERIC default: any value, as readable EDN.

  THE OTHER KIND'S FLOOR, and the owner's own justification for it:
  \"code is a good fallback as it's the truth of the system.\" An agent
  reads Clojure; a value it has no specialist for is still legible to it
  as the data it actually is, so nothing in a neighbourhood is ever
  unrenderable and no family has to write a lens before it can be seen.

  Deliberately the LOWEST-fidelity ai render, the same way `data-panel`
  is for html: a floor that tried to be clever would compete with the
  family defaults instead of backstopping them.

  Same value selection and the same ONE bounding owner as `data-panel` —
  a `:seon.render/value` when the producer supplies that key (including nil), the
  unit minus its own declarations otherwise, bounded by the caps the
  eval door already carries. Caps are REQUIRED and there is no shipped
  constant, because a second set of size dials would drift from the
  first."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (value/render-ai (floor-unit unit)))
