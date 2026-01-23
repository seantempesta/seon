(ns seon.ns.routes
  "HTTP routes for namespace introspection views.

   Provides:
   - /ns/{namespace} - Custom render (if namespace has `render` fn)
                       or static introspection view (vars, functions, schemas)
   - /ns/{namespace}?id={id} - Instance view (passed to render)

   Convention: If a namespace has a public `render` function, it will be called
   with {:format :html/:ai/:raw :id optional-id}. This allows namespaces to
   provide custom views of their data in multiple formats.

   Note: Functions here are Ring handlers using positional args, following
   the pattern of other web handlers in seon.web.*"
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as h]
            [ring.util.codec :as codec]
            [seon.ns.introspect :as introspect]
            [seon.ns.view :as view]
            [seon.web.html :as html]
            [seon.web.sse :as sse]
            [seon.web.components :as ui]))

;;; ---------------------------------------------------------------------------
;;; Namespace View Rendering
;;; ---------------------------------------------------------------------------

(defn- render-function-row
  "Render a function as a table row."
  [{:keys [name arglists doc]}]
  [:tr {:class "hover:bg-base-800 border-b border-base-700/50"}
   [:td {:class "py-2 px-3 font-mono text-text-50 text-sm"} (str name)]
   [:td {:class "py-2 px-3 font-mono text-text-200 text-xs"}
    (when (seq arglists)
      (for [args arglists]
        [:code {:class "mr-2 text-text-300"} (pr-str args)]))]
   [:td {:class "py-2 px-3 text-text-400 text-xs max-w-md truncate"}
    (when doc (first (str/split-lines doc)))]])

(defn- render-var-row
  "Render a var as a table row."
  [{:keys [name value]}]
  [:tr {:class "hover:bg-base-800 border-b border-base-700/50"}
   [:td {:class "py-2 px-3 font-mono text-text-50 text-sm"} (str name)]
   [:td {:class "py-2 px-3 text-xs"} (view/render value :html)]])

(defn- render-atom-row
  "Render an atom as a table row with live value."
  [{:keys [name atom]}]
  [:tr {:class "hover:bg-base-800 border-b border-base-700/50"}
   [:td {:class "py-2 px-3"}
    [:span {:class "font-mono text-text-50 text-sm"} (str name)]
    [:span {:class "ml-2 text-xs bg-warning/20 text-warning px-1.5 py-0.5 rounded"} "atom"]]
   [:td {:class "py-2 px-3 text-xs"} (view/render @atom :html)]])

(defn- render-multimethod-row
  "Render a multimethod as a table row."
  [{:keys [name doc dispatch-fn method-count]}]
  [:tr {:class "hover:bg-base-800 border-b border-base-700/50"}
   [:td {:class "py-2 px-3"}
    [:span {:class "font-mono text-text-50 text-sm"} (str name)]
    [:span {:class "ml-2 text-xs bg-eval/20 text-eval px-1.5 py-0.5 rounded"} "multi"]]
   [:td {:class "py-2 px-3 text-xs text-text-400"}
    [:span "dispatch: "] [:code {:class "text-text-300"} dispatch-fn]
    [:span {:class "ml-3"} method-count " methods"]]])

(defn- render-requires-table
  "Render namespace requires as a compact table."
  [requires]
  [:table {:class "w-full text-xs"}
   [:tbody
    (for [[alias ns] (sort-by first requires)]
      [:tr {:class "hover:bg-base-800"}
       [:td {:class "py-1 px-2 font-mono text-eval w-24"} (str alias)]
       [:td {:class "py-1 px-2 font-mono text-text-200"} (str ns)]])]])

(defn- namespace-has-render?
  "Check if namespace has a public render function."
  [ns-sym]
  (when-let [ns-obj (find-ns ns-sym)]
    (when-let [render-var (ns-resolve ns-obj 'render)]
      (and (var? render-var)
           (fn? @render-var)))))

(defn- call-namespace-render
  "Call the namespace's render function with format and id."
  [ns-sym format id]
  (let [render-fn @(ns-resolve (find-ns ns-sym) 'render)]
    (render-fn {:format format :id id})))

(defn- render-namespace-content
  "Render full namespace view content.
   If namespace has a `render` function, calls it with {:format :html :id id}.
   Otherwise falls back to introspection view."
  [ns-sym session-id]
  ;; Check for custom render function first
  (if (namespace-has-render? ns-sym)
    (h/html
     [:main#morph
      (call-namespace-render ns-sym :html session-id)])
    ;; Fall back to introspection
    (if-let [data (introspect/introspect ns-sym)]
      (let [{:keys [ns-name doc functions vars atoms multimethods requires]} data]
        (h/html
         [:main#morph
          ;; Header
          [:div {:class "mb-4"}
           [:h1 {:class "text-lg font-semibold tracking-tight font-mono"} (str ns-name)]
           (when session-id
             [:p {:class "text-text-400 text-xs mt-0.5"}
              "Session: " [:code {:class "text-signal"} session-id]])
           (when doc
             [:p {:class "text-text-400 text-sm mt-2 whitespace-pre-wrap max-w-3xl"} doc])]

          ;; Functions section
          (when (seq functions)
            [:section {:class "mb-6"}
             (ui/section-header (str "FUNCTIONS (" (count functions) ")"))
             [:div {:class "bg-base-850 rounded overflow-hidden"}
              [:table {:class "w-full"}
               [:thead
                [:tr {:class "border-b border-base-700"}
                 [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-48"} "Name"]
                 [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Arglists"]
                 [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Doc"]]]
               [:tbody
                (for [f functions]
                  (render-function-row f))]]]])

          ;; Multimethods section
          (when (seq multimethods)
            [:section {:class "mb-6"}
             (ui/section-header (str "MULTIMETHODS (" (count multimethods) ")"))
             [:div {:class "bg-base-850 rounded overflow-hidden"}
              [:table {:class "w-full"}
               [:thead
                [:tr {:class "border-b border-base-700"}
                 [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-48"} "Name"]
                 [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Info"]]]
               [:tbody
                (for [mm multimethods]
                  (render-multimethod-row mm))]]]])

          ;; Atoms section (live values)
          (when (seq atoms)
            [:section {:class "mb-6"}
             (ui/section-header (str "ATOMS (" (count atoms) ")"))
             [:div {:class "bg-base-850 rounded overflow-hidden"}
              [:table {:class "w-full"}
               [:thead
                [:tr {:class "border-b border-base-700"}
                 [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-48"} "Name"]
                 [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Value"]]]
               [:tbody
                (for [a atoms]
                  (render-atom-row a))]]]])

          ;; Vars section
          (when (seq vars)
            [:section {:class "mb-6"}
             (ui/section-header (str "VARS (" (count vars) ")"))
             [:div {:class "bg-base-850 rounded overflow-hidden"}
              [:table {:class "w-full"}
               [:thead
                [:tr {:class "border-b border-base-700"}
                 [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-48"} "Name"]
                 [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Value"]]]
               [:tbody
                (for [v vars]
                  (render-var-row v))]]]])

          ;; Requires section
          (when (seq requires)
            [:section {:class "mb-6"}
             (ui/section-header (str "REQUIRES (" (count requires) ")"))
             [:div {:class "bg-base-850 rounded p-3"}
              (render-requires-table requires)]])]))

      ;; Namespace not found
      (h/html
       [:main#morph
        [:div {:class "text-center py-12"}
         [:h1 {:class "text-lg font-semibold text-text-50 mb-4"} "Namespace Not Found"]
         [:p {:class "text-text-400 text-sm"}
          "The namespace " [:code {:class "font-mono text-eval"} (str ns-sym)] " is not loaded."]
         [:a {:href "/"
              :class "inline-block mt-4 text-signal hover:text-warning text-sm"}
          "Back to Dashboard"]]]))))

;;; ---------------------------------------------------------------------------
;;; Skeleton Loading State
;;; ---------------------------------------------------------------------------

(defn- namespace-skeleton
  "Skeleton loading state for namespace page."
  [ns-sym]
  [:div
   [:h1 {:class "text-lg font-semibold tracking-tight font-mono"} (str ns-sym)]
   [:p {:class "text-text-400 text-xs mt-0.5 mb-4"} "Loading..."]
   [:div {:class "space-y-4"}
    (for [_ (range 3)]
      [:div {:class "bg-base-850 rounded p-3"}
       [:div {:class "h-4 w-32 bg-base-700 rounded animate-skeleton mb-2"}]
       [:div {:class "h-3 w-48 bg-base-700 rounded animate-skeleton"}]])]])

;;; ---------------------------------------------------------------------------
;;; HTTP Handlers
;;; ---------------------------------------------------------------------------

(defn namespace-page
  "Serve the namespace view HTML page."
  [request]
  (let [ns-str (get-in request [:path-params :namespace])
        ns-sym (symbol ns-str)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (html/base-page
            {:title (str ns-str " - Seon")
             :active-page nil
             :skeleton (namespace-skeleton ns-sym)})}))

;; Dynamic handler cache - keyed by namespace only
(defonce ^:private namespace-handlers (atom {}))

(defn- parse-query-params
  "Parse query string into map. Ring middleware isn't applied to SSE routes."
  [req]
  (or (:query-params req)
      (when-let [qs (:query-string req)]
        (codec/form-decode qs))))

(defn- get-namespace-handler
  "Get or create SSE handler for a namespace view."
  [ns-sym]
  (if-let [handler (get @namespace-handlers ns-sym)]
    handler
    (let [render-fn (fn [req]
                      (let [params (parse-query-params req)
                            session-id (get params "id")]
                        (render-namespace-content ns-sym session-id)))
          handler (sse/render-handler render-fn :poll-ms 2000)]
      (swap! namespace-handlers assoc ns-sym handler)
      handler)))

(defn namespace-sse
  "SSE handler for namespace view - renders introspection data."
  [request]
  (let [ns-str (get-in request [:path-params :namespace])
        ns-sym (symbol ns-str)
        handler (get-namespace-handler ns-sym)]
    (handler request)))

;;; ---------------------------------------------------------------------------
;;; Hot Reload Support
;;; ---------------------------------------------------------------------------

(defn after-ns-reload
  "Called by clj-reload after namespace reload. Clears SSE handler cache."
  []
  (reset! namespace-handlers {}))

;;; ---------------------------------------------------------------------------
;;; Route Registration Helper
;;; ---------------------------------------------------------------------------

(def route-patterns
  "Dynamic route patterns for namespace introspection.
   Use with seon.web.routes/dynamic-routes."
  [{:method :get
    :pattern #"/ns/([a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'namespace-page}
   {:method :post
    :pattern #"/ns/([a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'namespace-sse}])
