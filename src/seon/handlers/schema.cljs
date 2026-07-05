(ns seon.handlers.schema
  "Renderers for `:seon.schema` entities — Malli schemas the agent has
   registered via `(seon.schema/register! …)`.

   Each entity renders WHAT THE SCHEMA IS (the live shape from the
   Malli registry), not the eval source that registered it. The shape
   is queried at render time so a re-registration with a new shape
   surfaces immediately."
  (:require
    [clojure.string :as str]
    [seon.schema :as schema]))

(defn- live-shape
  "Pull the current shape from the Malli registry. Returns the raw
   Malli form (keyword or vector) or nil if not registered. Falling
   back to nil keeps the renderer safe in tests where the schema
   was retracted between tx and render."
  [k]
  (when (keyword? k)
    (try (schema/schema-definition k) (catch :default _ nil))))

(defn- shape-summary
  "One-line `pr-str` of a Malli form, truncated. Built-in IntoSchema
   instances pr-str as their head keyword (e.g. `:string`); vector
   forms render compactly."
  [shape n]
  (let [s (pr-str shape)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

(defn- shape-type
  "Best-effort head of the schema — for the pill in the HTML pane.
   `:string`, `:int`, `:enum`, `:map`, `:vector`, etc. Defaults to
   `:any` for opaque IntoSchema instances we can't introspect."
  [shape]
  (cond
    (keyword? shape) shape
    (vector? shape)  (first shape)
    :else            :any))

(defn render-ai
  "One-line summary: `[schema :ns/key] :shape <shape-snippet>`."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :string]]}
  [{:seon.render/keys [node entity]}]
  (let [entity (or node entity)
        k     (:seon.schema/key entity)
        shape (live-shape k)
        shape-text (if shape (shape-summary shape 100) "<not registered>")]
    (str "[schema " (pr-str k) "]  :shape " shape-text)))

(defn render-html
  "Card showing the schema key, its head-type pill, and pretty-printed shape.

   Shape is syntax-highlighted so highlight.js
   colorizes it like the eval cards."
  {:malli/schema [:=> [:cat :seon.render/section-request] [:maybe :seon.render.live-tile/hiccup]]}
  [{:seon.render/keys [node entity]}]
  (let [entity (or node entity)
        k     (:seon.schema/key entity)
        shape (live-shape k)
        head  (shape-type shape)
        shape-text (if shape (pr-str shape) "<not registered>")
        anchor (str "seon-schema-" (str/replace (str k) #"[^A-Za-z0-9_-]" "_"))]
    [:div {:id anchor :class "py-1"}
     [:div {:class "flex items-baseline gap-2 flex-wrap"}
      [:span {:class "text-xs font-mono font-semibold text-amber-400"} "schema"]
      [:span {:class "text-xs font-mono text-text-100"} (pr-str k)]
      [:span {:class "text-xs font-mono text-amber-300/70"} (str head)]]
     [:pre {:class "text-xs whitespace-pre-wrap mt-0.5 rounded bg-base-900 p-1.5 overflow-x-auto"}
      [:code {:class "language-clojure hljs"} shape-text]]
     ;; "generate sample" deferred — no eval-against-compile-state
     ;; route yet; see report.
     ]))

