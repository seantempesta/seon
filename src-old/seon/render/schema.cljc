(ns seon.render.schema
  "Registered data shapes shared by context blocks and the render engine.

   This namespace is deliberately dependency-free beyond `seon.schema` so
   schema ownership never determines whether the context compiler or renderer
   loads first. Rendering behavior remains in `seon.render` and
   `seon.render.canvas`."
  (:require
   [seon.schema :as schema]))

;; A shallow, pure-data hiccup bound. The render boundary performs the deep
;; structural validation; registered schemas must survive source round trips.
(schema/register!
 :seon.render.canvas/hiccup
 [:and [:vector :any] [:cat :keyword [:* :any]]])

;; One stored canvas value: no canvas, a qualified function symbol, or literal
;; hiccup. Mixed values are encoded as EDN strings by the database bridge.
(schema/register!
 :seon.render.canvas/content
 [:or {:default :none}
  [:enum :none]
  :symbol
  :seon.render.canvas/hiccup])

;; The two block slots. They live here because both `seon.agent.ctx` and the
;; renderer validate them; neither runtime owner should load the other merely
;; to compile an ordinary data schema.
(schema/register! :seon.render/ai [:or :string :symbol])
(schema/register! :seon.render/html :seon.render.canvas/content)
