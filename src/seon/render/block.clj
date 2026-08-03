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
  (:require [seon.schema.edn :as schema.edn]))

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
