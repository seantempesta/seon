(ns seon.items
  "Define the shared envelope for paged collections.

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
