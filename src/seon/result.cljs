(ns seon.result
  "The shared result discriminator `:seon.result/ok?` — a boolean that
   marks an envelope as a success (true) or an error (false). Registered
   ONCE here so every result-carrying shape (the `seon.items` collection
   envelope, function responses) references it instead of inlining `:boolean`.

   Keyword namespaces = real code namespaces: the `:seon.result/*` shapes
   live in `seon.result`, the ns whose name the keywords carry — not in
   their first consumer."
  (:require
    [seon.schema :as schema]))

(schema/register! :seon.result/ok? :boolean) ; success (true) / error (false) discriminator
