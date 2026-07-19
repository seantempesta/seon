(ns seon.render.surface
  "Normalize execution-child results into renderable surfaces.

   Pure transformations validate and order already-derived surface data. They
   neither invoke render functions nor acquire database values."
  (:require
   [clojure.string :as str]
   [seon.schema :as schema]
   [seon.web.view-unit :as view-unit]))

(schema/register! ::selection [:string {:min 1}])
(schema/register! ::label [:string {:min 1}])
(schema/register! ::read-attrs [:set :qualified-keyword])
(schema/register! ::touch :int)
(schema/register! ::focus-touch :int)
(schema/register! ::compact :seon.render.canvas/hiccup)
(schema/register! ::expanded :seon.render.canvas/hiccup)
(schema/register! ::surface
  [:map
   [::selection ::selection]
   [::label ::label]
   [:seon.agent.ctx/name {:optional true} :seon.agent.ctx/name]
   [::read-attrs ::read-attrs]
   [::touch ::touch]
   [::focus-touch ::focus-touch]
   [::compact ::compact]
   [::expanded ::expanded]])
(schema/register! ::surfaces [:vector ::surface])

(defn selection-key
  "Stable browser selection key for one context block name."
  {:malli/schema [:=> [:catn [:seon.agent.ctx/name :seon.agent.ctx/name]]
                  ::selection]}
  [block-name]
  (str "context-"
       (view-unit/identity-token {:seon.agent.ctx/name block-name})))

(defn- class-token? [attrs token]
  (boolean (some #{token}
                 (some-> (:class attrs "") (str/split #"\s+")))))

(defn- find-face [node class-token]
  (when (vector? node)
    (let [attrs (when (map? (second node)) (second node))
          children (if attrs (drop 2 node) (drop 1 node))]
      (if (class-token? attrs class-token)
        node
        (some #(find-face % class-token) children)))))

(defn face
  "Project one compact or expanded face without invoking its renderer again."
  [hiccup requested]
  (or (find-face hiccup (case requested
                          :compact "seon-card-compact"
                          :expanded "seon-card-expanded"))
      hiccup))

(defn materialized
  "Build one ordinary dual-face surface from a resolved hiccup value."
  [block hiccup]
  (when hiccup
    (let [block-name (:seon.agent.ctx/name block)
          selection (or (:seon.render.surface/selection block)
                        (when block-name (selection-key block-name))
                        "canvas")
          label (or (:seon.render.surface/label block)
                    (some-> block-name name)
                    "canvas")
          touch (or (:seon.render.surface/touch block) 0)]
      {::selection selection
       ::label label
       :seon.agent.ctx/name block-name
       ::read-attrs (into #{:seon.render/html}
                          (:seon.fn/read-attrs block))
       ::touch touch
       ::focus-touch (or (:seon.render.surface/focus-touch block) touch)
       ::compact (face hiccup :compact)
       ::expanded (face hiccup :expanded)})))

(defn latest-focus-selection
  "Select the latest surface; canvas wins an untouched tie."
  [surfaces]
  (::selection
   (last (sort-by (juxt ::focus-touch
                        #(if (= "canvas" (::selection %)) 1 0)
                        ::label)
                  surfaces))))
