(ns my.data
  "Turn stored rows into the number your human asked for — SUM, argMAX,
   group-then-sum — WITHOUT hand-rolling a datalog aggregate. Datalog's
   aggregates have two traps these verbs make unreachable:

     - the `(sum ?x)` DEDUP collapse — an aggregate runs over the
       deduplicated projected tuples, so two rows of 5 sum to 5, not 10,
       unless you remember `:with ?e` (see the old my.kb/source-stats
       warning). Here you reduce over MAPS with plain Clojure, which never
       dedups — the footgun is structurally gone.
     - the argMAX value-vs-entity trap — `(max ?c)` gives you 45, NOT the
       entity that costs 45, so you need a second join to recover the name.
       [[max-by]] returns the ROW, so 'which one is biggest' is one call.

   THE PIPELINE — two PRODUCERS emit a `:seon.items/*` envelope (a vector of
   self-describing entity maps + a count), two REDUCERS consume those items
   plus a `:my.data/key` and emit a scalar / a row:

     PRODUCE  rows        :my.data/attr      → :seon.items/* envelope
              group-sum   items + group-key  → :seon.items/* envelope
     REDUCE   sum-by      items + key        → number
              max-by      items + key        → the winning row (or nil)

   The universal arrow is `(reducer (merge (producer …) {:my.data/key k}))`:
   a producer's envelope already carries `:seon.items/items`, so merging in
   the key gives a valid reducer request. All four verbs are SYNC (reads
   only — no `^:async`, no Promise to trip on) and map-in/map-out.

   NB rows does NOT clip — aggregation needs every row, so a truncated
   collection would silently corrupt a sum."
  (:refer-clojure :exclude [key])
  (:require
    ;; the shared `:seon.items/*` collection envelope + `:seon.result/ok?`
    ;; discriminator live in `seon.items` (which pulls `seon.result`) — Core
    ;; owns them; my.data REFERENCES, never re-registers. Required for load
    ;; order so those register! calls run before my.data's schemas below.
    [seon.items]
    [seon.db :as db]
    [seon.schema :as schema]))

;;; FIELD SHAPES — attr names are keywords; a grouped value is domain data
;;; (`:any`); a sum is numeric.

(schema/register! ::attr      :keyword)             ; rows: the presence attr to scan
(schema/register! ::key       :keyword)             ; reducers: the numeric field to aggregate
(schema/register! ::group-key :keyword)             ; group-sum: the field to group BY
(schema/register! ::group     :any)                 ; output: a grouped value (e.g. :dining)
(schema/register! ::total     number?)              ; output: a per-group sum

(schema/register! ::group-row [:map [::group ::group] [::total ::total]])

(schema/register! ::rows-request   [:map [::attr ::attr]])
(schema/register! ::reduce-request  ; sum-by / max-by — items + the field
  [:map [:seon.items/items :seon.items/items] [::key ::key]])
(schema/register! ::group-request
  [:map [:seon.items/items :seon.items/items] [::group-key ::group-key] [::key ::key]])

(defn rows
  "Every entity carrying `attr`, pulled to self-describing maps.

   Attribute-presence as DATA — there are no kinds; you find a set by the
   attr it asserts. The root of every analysis pipeline: once rows are
   MAPS you reduce with plain Clojure, so the datalog `:with` dedup trap
   can't happen.

   ;; the worked chain — 'biggest spending category, and how much?':
   (let [exp    (rows {:my.data/attr :my.expense/amount-usd})
         totals (group-sum (merge exp {:my.data/group-key :my.expense/category
                                       :my.data/key       :my.expense/amount-usd}))]
     (max-by (merge totals {:my.data/key :my.data/total})))
   ; ⟹ «map: :my.data/group :dining, :my.data/total 106»"
  {:malli/schema [:=> [:cat ::rows-request] :seon.items/envelope]}
  [{::keys [attr]}]
  (let [items (vec (db/query '[:find [(pull ?e [*]) ...] :in $ ?a :where [?e ?a]] attr))]
    {:seon.result/ok?  true
     :seon.items/items items
     :seon.items/count (count items)}))

(defn sum-by
  "Total of `key` across the given item maps.

   Reduces over MAPS, so the datalog `(sum ?x)`/`:with` dedup collapse
   cannot happen — two rows of 5
   stay 5+5=10. Rows missing KEY are skipped (matching `(sum ?x)` over only
   the entities that assert the attr).

   ;; (merge a producer envelope with the key, then reduce):
   (sum-by (merge (rows {:my.data/attr :my.subscription/name})
                  {:my.data/key :my.subscription/monthly-usd}))  ; ⟹ 101"
  {:malli/schema [:=> [:cat ::reduce-request] ::total]}
  [{::keys [key] :seon.items/keys [items]}]
  (reduce + 0 (keep key items)))

(defn max-by
  "The item MAP whose `key` is largest — returns the ENTITY, not the value.

   So 'which one is biggest' is one call, not a `(max ?x)`+rejoin. Ties:
   the FIRST row at the max wins (strict `>`); nil when no items.

   ;; the priciest subscription ENTITY (read its name off the row):
   (:my.subscription/name
     (max-by (merge (rows {:my.data/attr :my.subscription/name})
                    {:my.data/key :my.subscription/monthly-usd})))  ; ⟹ \"Adobe CC\""
  {:malli/schema [:=> [:cat ::reduce-request] [:maybe :map]]}
  [{::keys [key] :seon.items/keys [items]}]
  (when (seq items)
    (reduce (fn [best it] (if (> (key it) (key best)) it best))
            (first items) (rest items))))

(defn group-sum
  "Sum `key` per distinct `group-key` value → a `:seon.items/*` envelope.

   Each row is `{:my.data/group <value> :my.data/total <sum>}`. The
   reusable generalization of x3's filter-then-sum and source-stats'
   per-topic tallies. Emits an envelope (not a bare map) so it threads
   straight into [[max-by]] — group, THEN argmax over the groups:

   (max-by (merge (group-sum (merge (rows {:my.data/attr :my.expense/amount-usd})
                                    {:my.data/group-key :my.expense/category
                                     :my.data/key       :my.expense/amount-usd}))
                  {:my.data/key :my.data/total}))
   ; ⟹ «map: :my.data/group :dining, :my.data/total 106»"
  {:malli/schema [:=> [:cat ::group-request] :seon.items/envelope]}
  [{::keys [group-key key] :seon.items/keys [items]}]
  (let [rows (->> items
                  (reduce (fn [m it] (update m (group-key it) (fnil + 0) (or (key it) 0)))
                          {})
                  (mapv (fn [[g t]] {::group g ::total t})))]
    {:seon.result/ok?  true
     :seon.items/items rows
     :seon.items/count (count rows)}))
