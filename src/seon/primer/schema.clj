(ns seon.primer.schema
  "Malli schemas for Primer domain."
  (:require [malli.core :as m]))

(def registry
  (atom (m/default-schemas)))

;; Action - what user can do
(def Action
  [:map
   [:action/id :keyword]
   [:action/label :string]
   [:action/handler :qualified-symbol]
   [:action/args {:optional true} :map]])

;; Scene - current view state
(def Scene
  [:map
   [:scene/id :string]
   [:scene/template :keyword]
   [:scene/params :map]
   [:scene/actions [:vector Action]]])

;; Ctx - the whole context atom
(def Ctx
  [:map
   [:primer/current-scene {:optional true} Scene]
   [:primer/child-id {:optional true} :string]])
