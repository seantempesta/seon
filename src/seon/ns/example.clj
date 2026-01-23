(ns seon.ns.example
  "Example namespace demonstrating the view system.

   Shows how to:
   - Define schemas with :seon/view property
   - Implement render* methods for custom view types
   - Create typed values using map-in API
   - Render in different contexts

   Note: This namespace implements render* multimethod for custom types.
   Public API uses map-in/map-out pattern per CONVENTIONS.md."
  (:require [seon.ns.view :as view]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration with View Type
;;; ---------------------------------------------------------------------------

;; Register a schema with :seon/view property in Malli schema properties
(schema/register! ::widget
  [:map {:seon/view :example/widget}
   [::name :string]
   [::value :int]
   [::active? {:optional true} :boolean]])

(schema/register! ::dashboard
  [:map {:seon/view :example/dashboard}
   [::title :string]
   [::widgets [:vector ::widget]]])

;;; ---------------------------------------------------------------------------
;;; Helper for typed values
;;; ---------------------------------------------------------------------------

(defn- typed-value
  "Helper to create typed value for nested rendering.
   Wraps view/typed with positional args for internal use."
  [view-type value]
  (view/typed {::view/view-type view-type ::view/value value}))

;;; ---------------------------------------------------------------------------
;;; Custom Render Methods for :example/widget
;;; ---------------------------------------------------------------------------

(defmethod view/render* [:html :example/widget]
  [value _format]
  (let [{::keys [name value active?]} value]
    [:div {:class (str "border rounded p-3 "
                       (if active?
                         "border-success/50 bg-success/10"
                         "border-base-700 bg-base-850"))}
     [:div {:class "flex justify-between items-center"}
      [:span {:class "font-mono text-text-50 font-medium"} name]
      (when active?
        [:span {:class "w-2 h-2 rounded-full bg-success animate-pulse"}])]
     [:div {:class "text-2xl font-bold text-signal mt-2"} value]]))

(defmethod view/render* [:ai :example/widget]
  [value _format]
  (let [{::keys [name value active?]} value]
    (str "Widget '" name "': " value (when active? " (active)"))))

(defmethod view/render* [:human :example/widget]
  [value _format]
  (let [{::keys [name value active?]} value]
    (str name " = " value (when active? " *"))))

(defmethod view/render* [:raw :example/widget]
  [value _format]
  (pr-str value))

;;; ---------------------------------------------------------------------------
;;; Custom Render Methods for :example/dashboard
;;; ---------------------------------------------------------------------------

(defmethod view/render* [:html :example/dashboard]
  [value _format]
  (let [{::keys [title widgets]} value]
    [:div {:class "space-y-4"}
     [:h2 {:class "text-lg font-semibold text-text-50"} title]
     [:div {:class "grid grid-cols-3 gap-3"}
      (for [w widgets]
        (view/render (typed-value :example/widget w) :html))]]))

(defmethod view/render* [:ai :example/dashboard]
  [value _format]
  (let [{::keys [title widgets]} value]
    (str "Dashboard: " title "\n"
         (count widgets) " widgets:\n"
         (->> widgets
              (map #(str "  - " (view/render (typed-value :example/widget %) :ai)))
              (clojure.string/join "\n")))))

;;; ---------------------------------------------------------------------------
;;; Example Data
;;; ---------------------------------------------------------------------------

(def sample-widget
  "A sample widget value with view type metadata."
  (typed-value :example/widget
               {::name "CPU Usage"
                ::value 73
                ::active? true}))

(def sample-dashboard
  "A sample dashboard with multiple widgets."
  (typed-value :example/dashboard
               {::title "System Monitor"
                ::widgets [{::name "CPU" ::value 73 ::active? true}
                           {::name "Memory" ::value 45 ::active? false}
                           {::name "Disk" ::value 82 ::active? true}]}))

;; Example using schema metadata (view type extracted from schema)
(def widget-from-schema
  "Widget with schema metadata - view type comes from schema properties."
  (view/with-schema {::view/schema-key ::widget
                     ::view/value {::name "Network"
                                   ::value 12
                                   ::active? false}}))

;;; ---------------------------------------------------------------------------
;;; Live Atom Example
;;; ---------------------------------------------------------------------------

;; A live counter atom - demonstrates live rendering in namespace view.
(defonce counter (atom 0))

(defn increment!
  "Increment the counter."
  []
  (swap! counter inc))

(defn reset-counter!
  "Reset the counter to zero."
  []
  (reset! counter 0))

;;; ---------------------------------------------------------------------------
;;; REPL Exploration
;;; ---------------------------------------------------------------------------

(comment
  ;; Render the sample widget in different formats
  (view/render sample-widget :html)
  (view/render sample-widget :ai)
  (view/render sample-widget :human)
  (view/render sample-widget :raw)

  ;; Using the full map-in API
  (view/render-value {::view/value sample-widget ::view/format :html})

  ;; Render the dashboard
  (view/render sample-dashboard :html)
  (view/render sample-dashboard :ai)

  ;; Check view type extraction
  (view/view-type {::view/value sample-widget})        ; => :example/widget
  (view/view-type {::view/value sample-dashboard})     ; => :example/dashboard
  (view/view-type {::view/value widget-from-schema})   ; => :example/widget (from schema)
  (view/view-type {::view/value {:plain "map"}})       ; => :default

  ;; Test atom live view
  (increment!)
  @counter

  ;; View this namespace at http://localhost:8080/ns/seon.ns.example
  ;; The counter atom value updates live via SSE

  nil)
