(ns seon.ui.viewer
  "Value viewer with multimethod dispatch for rendering Clojure values as Hiccup.

   Dispatch is based on type or ::viewer metadata on the value.
   This allows custom rendering for specific value types while providing
   sensible defaults for all Clojure data structures.

   Phase 1: Basic unstyled HTML output
   Phase 2 will add: expand/collapse, Tailwind styling, truncation"
  (:require [dev.onionpancakes.chassis.core :as h]))

;; ============================================================================
;; Multimethod Dispatch
;; ============================================================================

(defmulti render-value
  "Render a Clojure value as Hiccup.

   Dispatch hierarchy:
   1. ::viewer metadata on the value (for custom renderers)
   2. Type of the value

   opts map can contain:
   - :depth - Current nesting depth (for truncation in Phase 2)
   - :limit - Max items to show in collections (Phase 2)"
  (fn [v _opts]
    (or (::viewer (meta v))
        (type v))))

;; ============================================================================
;; Default Renderers
;; ============================================================================

(defmethod render-value :default [v _opts]
  [:code {:class "text-text-200"} (pr-str v)])

(defmethod render-value nil [_ _opts]
  [:span {:class "text-text-400 italic"} "nil"])

(defmethod render-value Boolean [v _opts]
  [:span {:class "text-info font-medium"} (str v)])

(defmethod render-value Long [v _opts]
  [:span {:class "text-success"} (str v)])

(defmethod render-value Integer [v _opts]
  [:span {:class "text-success"} (str v)])

(defmethod render-value Double [v _opts]
  [:span {:class "text-success"} (str v)])

(defmethod render-value Float [v _opts]
  [:span {:class "text-success"} (str v)])

(defmethod render-value java.math.BigDecimal [v _opts]
  [:span {:class "text-success"} (str v "M")])

(defmethod render-value clojure.lang.BigInt [v _opts]
  [:span {:class "text-success"} (str v "N")])

(defmethod render-value clojure.lang.Ratio [v _opts]
  [:span {:class "text-success"} (str v)])

(defmethod render-value String [v _opts]
  [:span {:class "text-warning"} (pr-str v)])

(defmethod render-value clojure.lang.Keyword [v _opts]
  [:span {:class "text-eval"} (str v)])

(defmethod render-value clojure.lang.Symbol [v _opts]
  [:span {:class "text-error"} (str v)])

(defmethod render-value java.util.UUID [v _opts]
  [:span {:class "text-log-hook font-mono text-sm"} (str v)])

(defmethod render-value java.util.Date [v _opts]
  [:span {:class "text-log-result"} (str v)])

(defmethod render-value java.time.Instant [v _opts]
  [:span {:class "text-log-result"} (str v)])

;; ============================================================================
;; Collection Renderers (Phase 1: Simple, no expand/collapse)
;; ============================================================================

(defmethod render-value clojure.lang.IPersistentMap [m opts]
  (if (empty? m)
    [:span {:class "text-text-400"} "{}"]
    [:div {:class "pl-4 border-l border-base-700"}
     [:span {:class "text-text-400"} "{"]
     (for [[k v] m]
       [:div {:class "flex gap-2 ml-2"}
        (render-value k opts)
        (render-value v opts)])
     [:span {:class "text-text-400"} "}"]]))

(defmethod render-value clojure.lang.IPersistentVector [v opts]
  (if (empty? v)
    [:span {:class "text-text-400"} "[]"]
    [:div {:class "pl-4 border-l border-base-700"}
     [:span {:class "text-text-400"} "["]
     (for [item v]
       [:div {:class "ml-2"}
        (render-value item opts)])
     [:span {:class "text-text-400"} "]"]]))

(defmethod render-value clojure.lang.IPersistentSet [s opts]
  (if (empty? s)
    [:span {:class "text-text-400"} "#{}"]
    [:div {:class "pl-4 border-l border-base-700"}
     [:span {:class "text-text-400"} "#{"]
     (for [item s]
       [:div {:class "ml-2"}
        (render-value item opts)])
     [:span {:class "text-text-400"} "}"]]))

(defmethod render-value clojure.lang.ISeq [s opts]
  (if (empty? s)
    [:span {:class "text-text-400"} "()"]
    [:div {:class "pl-4 border-l border-base-700"}
     [:span {:class "text-text-400"} "("]
     (for [item (take 100 s)]  ; Limit lazy seqs
       [:div {:class "ml-2"}
        (render-value item opts)])
     (when (> (count (take 101 s)) 100)
       [:div {:class "ml-2 text-text-400 italic"} "..."])
     [:span {:class "text-text-400"} ")"]]))

;; ============================================================================
;; Special Type Renderers
;; ============================================================================

(defmethod render-value clojure.lang.IAtom [a _opts]
  [:div {:class "border border-base-700 rounded p-2 bg-base-900"}
   [:div {:class "text-xs text-text-400 mb-1"} "Atom"]
   (render-value @a {})])

(defmethod render-value clojure.lang.Var [v _opts]
  [:span {:class "text-log-launch"}
   (str "#'" (.-ns v) "/" (.-sym v))])

(defmethod render-value Class [c _opts]
  [:span {:class "text-error"} (.getName c)])

(defmethod render-value java.util.regex.Pattern [p _opts]
  [:span {:class "text-signal"} (str "#\"" (.pattern p) "\"")])

;; ============================================================================
;; Namespace View Renderer
;; ============================================================================

(defn render-function-card
  "Render a function as a card with name, arglists, and doc."
  [{:keys [name arglists doc]}]
  [:div {:class "border border-base-700 rounded p-3 mb-2 bg-base-850"}
   [:div {:class "font-mono font-medium text-text-50"} (str name)]
   (when (seq arglists)
     [:div {:class "text-sm text-text-200 mt-1"}
      (for [args arglists]
        [:code {:class "mr-2"} (pr-str args)])])
   (when doc
     [:div {:class "text-sm text-text-400 mt-2 whitespace-pre-wrap"} doc])])

(defn render-var-card
  "Render a var as a card with name and value."
  [{:keys [name value]}]
  [:div {:class "border border-base-700 rounded p-3 mb-2 bg-base-850"}
   [:div {:class "font-mono font-medium text-text-50"} (str name)]
   [:div {:class "mt-1"}
    (render-value value {})]])

(defn render-atom-card
  "Render an atom as a card with name and current value."
  [{:keys [name atom]}]
  [:div {:class "border border-warning/30 rounded p-3 mb-2 bg-warning/10"}
   [:div {:class "flex items-center gap-2"}
    [:div {:class "font-mono font-medium text-text-50"} (str name)]
    [:span {:class "text-xs bg-warning/20 text-warning px-1.5 py-0.5 rounded"} "atom"]]
   [:div {:class "mt-2"}
    (render-value @atom {})]])

(defn render-multimethod-card
  "Render a multimethod as a card with dispatch info."
  [{:keys [name doc dispatch-fn method-count]}]
  [:div {:class "border border-eval/30 rounded p-3 mb-2 bg-eval/10"}
   [:div {:class "flex items-center gap-2"}
    [:div {:class "font-mono font-medium text-text-50"} (str name)]
    [:span {:class "text-xs bg-eval/20 text-eval px-1.5 py-0.5 rounded"} "multimethod"]]
   (when doc
     [:div {:class "text-sm text-text-400 mt-2"} doc])
   [:div {:class "text-xs text-text-400 mt-2"}
    [:span {:class "font-medium"} "Dispatch: "]
    [:code dispatch-fn]]
   [:div {:class "text-xs text-text-400"}
    [:span {:class "font-medium"} "Methods: "]
    method-count]])

(defn render-namespace-view
  "Render the full namespace introspection view.

   data - Result of seon.ns.introspect/introspect
   session-id - Optional session ID for live views (Phase 5)"
  [data _session-id]
  (let [{:keys [ns-name doc functions vars atoms multimethods requires]} data]
    (h/html
     [:main#morph
      ;; Header
      [:div {:class "mb-6"}
       [:h1 {:class "text-3xl font-bold tracking-tight font-mono"} (str ns-name)]
       (when doc
         [:p {:class "text-text-400 mt-2 whitespace-pre-wrap"} doc])]

      ;; Functions section
      (when (seq functions)
        [:section {:class "mb-8"}
         [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-4"}
          (str "Functions (" (count functions) ")")]
         (for [f functions]
           (render-function-card f))])

      ;; Multimethods section
      (when (seq multimethods)
        [:section {:class "mb-8"}
         [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-4"}
          (str "Multimethods (" (count multimethods) ")")]
         (for [mm multimethods]
           (render-multimethod-card mm))])

      ;; Atoms section
      (when (seq atoms)
        [:section {:class "mb-8"}
         [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-4"}
          (str "Atoms (" (count atoms) ")")]
         (for [a atoms]
           (render-atom-card a))])

      ;; Vars section
      (when (seq vars)
        [:section {:class "mb-8"}
         [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-4"}
          (str "Vars (" (count vars) ")")]
         (for [v vars]
           (render-var-card v))])

      ;; Requires section
      (when (seq requires)
        [:section {:class "mb-8"}
         [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-4"}
          (str "Requires (" (count requires) ")")]
         [:div {:class "bg-base-850 border border-base-700 rounded p-3"}
          [:table {:class "w-full text-sm"}
           [:tbody
            (for [[alias ns] (sort-by first requires)]
              [:tr
               [:td {:class "font-mono text-eval pr-4"} (str alias)]
               [:td {:class "font-mono text-text-200"} (str ns)]])]]]])])))

(comment
  ;; Test rendering
  (render-value {:a 1 :b "hello" :c [1 2 3]} {})

  (render-value nil {})
  (render-value true {})
  (render-value 42 {})
  (render-value "hello" {})
  (render-value :keyword {})
  (render-value 'symbol {})

  nil)
