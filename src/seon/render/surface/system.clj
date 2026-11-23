(ns seon.render.surface.system
  "Thin delegation adapter: `[:seon.system/id :root]` renders through the ONE general
   `seon.render.surface.entity/project-entity-view` Upgrade-Ladder surface (zero hand-coded dashboard!)."
  (:require [seon.render.surface.entity :as sentity]))

(defn render-system-body
  "Delegates `:system` / `:workspace` directly to `seon.render.surface.entity/render-system-entity-body`
   rooted at `[:seon.system/id :root]`."
  {:malli/schema [:=> [:cat :map] [:vector :any]]
   :cost/ticks 40000000}
  [model]
  (sentity/render-system-entity-body model))
