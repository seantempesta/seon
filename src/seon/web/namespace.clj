(ns seon.web.namespace
  "Namespace introspection web handlers.

   Provides routes for viewing any loaded namespace at /{namespace}.
   Example: /seon.ai.claude shows functions, vars, atoms for that namespace."
  (:require [seon.ns.introspect :as introspect]
            [seon.ui.viewer :as viewer]
            [seon.web.html :as html]
            [seon.web.sse :as sse]
            [dev.onionpancakes.chassis.core :as h]))

;; ============================================================================
;; Skeleton (shown while SSE loads)
;; ============================================================================

(defn- namespace-skeleton
  "Skeleton loading state for namespace page."
  [ns-sym]
  [:div
   [:h1 {:class "text-3xl font-bold tracking-tight font-mono"} (str ns-sym)]
   [:p {:class "text-text-400 text-sm mt-1 mb-6"} "Loading..."]
   ;; Skeleton sections
   [:div {:class "space-y-4"}
    (for [_ (range 3)]
      [:div {:class "bg-base-850 border border-base-700 rounded p-3"}
       [:div {:class "h-5 w-48 bg-base-700 rounded animate-skeleton mb-2"}]
       [:div {:class "h-4 w-32 bg-base-700 rounded animate-skeleton"}]])]])

;; ============================================================================
;; Content Renderer
;; ============================================================================

(defn- namespace-content
  "Render namespace introspection content for SSE."
  [ns-sym session-id]
  (if-let [data (introspect/introspect ns-sym)]
    (viewer/render-namespace-view data session-id)
    (h/html
     [:main#morph
      [:div {:class "text-center py-12"}
       [:h1 {:class "text-3xl font-bold text-text-50 mb-4"} "Namespace Not Found"]
       [:p {:class "text-text-400"}
        "The namespace " [:code {:class "font-mono"} (str ns-sym)] " is not loaded."]
       [:a {:href "/"
            :class "inline-block mt-4 text-signal hover:text-warning"}
        "← Back to Dashboard"]]])))

;; ============================================================================
;; Handlers
;; ============================================================================

(defn namespace-page
  "Serve the namespace view HTML shim page."
  [request]
  (let [ns-str (get-in request [:path-params :namespace])
        ns-sym (symbol ns-str)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (html/base-page
            {:title (str ns-str " - Seon")
             :active-page nil  ; No nav item highlighted
             :skeleton (namespace-skeleton ns-sym)})}))

;; No handler cache needed - fresh handler each request guarantees current bindings.
;; This is the permanent fix for SSE live reload issues.

(defn namespace-sse
  "SSE handler for namespace view - renders introspection data.
   Creates a fresh handler per request to ensure current function bindings."
  [request]
  (let [ns-str (get-in request [:path-params :namespace])
        ns-sym (symbol ns-str)
        session-id (get-in request [:query-params "id"])
        render-fn (fn [_req] (namespace-content ns-sym session-id))
        handler (sse/render-handler render-fn :poll-ms 2000)]
    (handler request)))

;; No after-ns-reload needed - per-entity handlers are created fresh each request.
