(ns my.data
  "Aggregate database rows without Datalog aggregate surprises.

   This namespace bridges asynchronous attribute-oriented row retrieval with
   pure item-envelope reducers for totals, grouping, and extremum selection.
   It preserves duplicate row values and returns source rows where identity
   matters. Query design and domain schemas remain with the calling namespace."
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
(schema/register! ::total     'number?)             ; output: a per-group sum

(schema/register! ::group-row [:map [::group ::group] [::total ::total]])
(schema/register! ::error     :string)              ; output: why a fetch failed

(schema/register! ::rows-request   [:map {:closed true} [::attr ::attr]])
(schema/register! ::rows-response                   ; rows — an envelope OR an error
  [:map
   [:seon.result/ok? :seon.result/ok?]
   [:seon.items/items {:optional true} :seon.items/items]
   [:seon.items/count {:optional true} :seon.items/count]
   [::error {:optional true} ::error]])
(schema/register! ::reduce-request  ; sum-by / max-by — items + the field
  [:map {:closed true} [:seon.items/items :seon.items/items] [::key ::key]])
(schema/register! ::group-request
  [:map {:closed true}
   [:seon.items/items :seon.items/items]
   [::group-key ::group-key]
   [::key ::key]])

(defn ^{:async true :seon.fn/agent-facing? true} rows
  "Fetch every entity carrying `attr` as self-describing maps.

   Attribute-presence as DATA — there are no kinds; you find a set by the
   attr it asserts. The root of every analysis pipeline: once rows are
   MAPS you reduce with plain Clojure, so the datalog `:with` dedup trap
   can't happen.

   ;; the worked chain — 'biggest spending category, and how much?':
   (let [exp    (rows {:my.data/attr :my.expense/amount-usd})
         totals (group-sum (merge exp {:my.data/group-key :my.expense/category
                                       :my.data/key       :my.expense/amount-usd}))]
     (max-by (merge totals {:my.data/key :my.data/total})))
   ; returns «map: :my.data/group :dining, :my.data/total 106»

   A failed query returns an ok?-false envelope whose `::error` carries
   the failure message — read `:seon.result/ok?` before reducing."
  {:malli/schema [:=> [:cat ::rows-request] ::rows-response]}
  [{::keys [attr]}]
  (let [result (await
                (db/query
                 '[:find [(pull ?e [*]) ...] :in $ ?a :where [?e ?a]]
                 attr))]
    (if (:seon.error/message result)
      {:seon.result/ok? false
       ::error (str "rows query failed: " (:seon.error/message result))}
      (let [items (vec result)]
        {:seon.result/ok?  true
         :seon.items/items items
         :seon.items/count (count items)}))))

(defn ^:seon.fn/agent-facing? sum-by
  "Total a numeric `key` across the given item maps.

   Reduces over MAPS, so the datalog `(sum ?x)`/`:with` dedup collapse
   cannot happen — two rows of 5
   stay 5+5=10. Rows missing KEY are skipped (matching `(sum ?x)` over only
   the entities that assert the attr).

   ;; (merge a producer envelope with the key, then reduce):
   (sum-by (merge (rows {:my.data/attr :my.subscription/name})
                  {:my.data/key :my.subscription/monthly-usd}))  ; returns 101"
  {:malli/schema [:=> [:cat ::reduce-request] ::total]}
  [{::keys [key] :seon.items/keys [items]}]
  (reduce + 0 (keep key items)))

(defn ^:seon.fn/agent-facing? max-by
  "Find the item map whose `key` is largest; the row, not the value.

   So 'which one is biggest' is one call, not a `(max ?x)`+rejoin. Ties:
   the FIRST row at the max wins (strict `>`); nil when no items.

   ;; the priciest subscription ENTITY (read its name off the row):
   (:my.subscription/name
     (max-by (merge (rows {:my.data/attr :my.subscription/name})
                    {:my.data/key :my.subscription/monthly-usd})))  ; returns \"Adobe CC\""
  {:malli/schema [:=> [:cat ::reduce-request] [:maybe :map]]}
  [{::keys [key] :seon.items/keys [items]}]
  (when (seq items)
    (reduce (fn [best it] (if (> (key it) (key best)) it best))
            (first items) (rest items))))

(defn ^:seon.fn/agent-facing? group-sum
  "Sum a numeric field per group, one total row per group value.

   Sums `key` per distinct `group-key` value into a `:seon.items/*`
   envelope. Each row is `{:my.data/group <value> :my.data/total <sum>}`. The
   reusable generalization of x3's filter-then-sum and source-stats'
   per-topic tallies. Emits an envelope (not a bare map) so it threads
   straight into [[max-by]] — group, THEN argmax over the groups:

   (max-by (merge (group-sum (merge (rows {:my.data/attr :my.expense/amount-usd})
                                    {:my.data/group-key :my.expense/category
                                     :my.data/key       :my.expense/amount-usd}))
                  {:my.data/key :my.data/total}))
   ; returns «map: :my.data/group :dining, :my.data/total 106»"
  {:malli/schema [:=> [:cat ::group-request] :seon.items/envelope]}
  [{::keys [group-key key] :seon.items/keys [items]}]
  (let [rows (->> items
                  (reduce (fn [m it] (update m (group-key it) (fnil + 0) (or (key it) 0)))
                          {})
                  (mapv (fn [[g t]] {::group g ::total t})))]
    {:seon.result/ok?  true
     :seon.items/items rows
     :seon.items/count (count rows)}))
