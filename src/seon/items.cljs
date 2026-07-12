(ns seon.items
  "The shared self-describing-collection envelope — a `:seon.items/*`
   paged-list shape: a vector of self-describing entity maps
   (`:seon.items/items`) plus their `:seon.items/count`, tagged with the
   `:seon.result/ok?` discriminator. Registered ONCE here and referenced
   (never inlined) by every producer/consumer of a collection result —
   `my.data` (the aggregation toolkit), and the upcoming
   `my.recall`/`my.schedule`/`my.canvas`.

   Keyword namespaces = real code namespaces: the `:seon.items/*` shapes
   live in `seon.items`, the ns whose name the keywords carry — not in
   their first consumer."
  (:require
    ;; loads the `:seon.result/ok?` registration the envelope references,
    ;; so `register!` for it runs before `:seon.items/envelope` below.
    [seon.result]
    [seon.schema :as schema]))

(schema/register! :seon.items/items [:vector :map]) ; each item a self-describing entity map
(schema/register! :seon.items/count :int)

(schema/register! :seon.items/envelope               ; what every collection PRODUCER emits
  [:map
   [:seon.result/ok? :seon.result/ok?]
   [:seon.items/items :seon.items/items]
   [:seon.items/count :seon.items/count]])
