(ns seon.render.surface.debug
  "Thin compatibility adapter delegating `:debug` wire building, ladder rendering, and body
   rendering to the holistic Context & Cache glass-box (`seon.render.surface.context-lab`)."
  (:require [seon.render.surface.context-lab :as clab]))

(defn build-debug-wire
  "Delegates to `seon.render.surface.context-lab/build-context-lab-wire`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map :keyword :any :map] :map]
   :cost/ticks 45000000}
  [emdb ebdb aid ag br gather-fn o]
  (clab/build-context-lab-wire emdb ebdb aid ag br gather-fn o))

(defn render-debug-ladder
  "Delegates to `seon.render.surface.context-lab/render-debug-ladder`."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]]] :any]
   :cost/ticks 500000}
  [items]
  (clab/render-debug-ladder items))

(defn render-debug-body
  "Delegates to `seon.render.surface.context-lab/render-context-lab-body`."
  {:malli/schema [:=> [:cat :map] [:vector :any]]
   :cost/ticks 40000000}
  [model]
  (clab/render-context-lab-body model))
