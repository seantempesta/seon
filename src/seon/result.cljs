(ns seon.result
  "Define the shared success marker for capability results.

   Keyword namespaces = real code namespaces: the `:seon.result/*` shapes
   live in `seon.result`, the ns whose name the keywords carry — not in
   their first consumer."
  (:require
    [seon.schema :as schema]))

(schema/register! :seon.result/ok? :boolean) ; success (true) / error (false) discriminator
