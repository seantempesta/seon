(ns seon.ns.routes
  "HTTP routes for namespace introspection and function calls.

   Provides:
   - /ns/{namespace} - Page render (if namespace has a spec-discovered renderer)
                       or static introspection view (vars, functions, schemas)
   - /ns/{namespace}?id={id} - Instance view (passed to renderer)
   - /ns/{namespace}/{function} - Function call (POST only, for reactive UI actions)

   Page renderers are discovered from the code graph, not declared explicitly.
   A function is a page renderer iff its input spec contains a *ctx* key and
   its output spec contains :seon.render/html.

   Note: Functions here are Ring handlers using positional args, following
   the pattern of other web handlers in seon.web.*"
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as h]
            [integrant.repl.state :as state]
            [org.httpkit.server :as hk]
            [seon.ctx :as ctx]
            [seon.ns.introspect :as introspect]
            [seon.ns.lifecycle :as lifecycle]
            [seon.ns.view :as view]
            [seon.render :as render]
            [seon.web.html :as html]
            [seon.web.sse :as sse]
            [seon.web.components :as ui]
            [seon.render.default-page :as default-page]
            [seon.web.reactive.actions :as actions]
            [seon.web.reactive.encoding :as encoding]
            [seon.web.reactive.transform :as transform]
            [malli.core :as m]
            [malli.error :as me]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Time Formatting
;;; ---------------------------------------------------------------------------

(defn- relative-time
  "Format an instant as relative time (e.g., '5 min ago')."
  [^java.util.Date inst]
  (let [now (System/currentTimeMillis)
        then (.getTime inst)
        diff-ms (- now then)
        diff-min (quot diff-ms 60000)
        diff-hours (quot diff-min 60)
        diff-days (quot diff-hours 24)]
    (cond
      (< diff-min 1) "just now"
      (< diff-min 60) (str diff-min " min ago")
      (< diff-hours 24) (str diff-hours (if (= diff-hours 1) " hour ago" " hours ago"))
      :else (str diff-days (if (= diff-days 1) " day ago" " days ago")))))

;;; ---------------------------------------------------------------------------
;;; Graph Connection Helper
;;; ---------------------------------------------------------------------------

(defn- get-conn
  "Get Datalevin graph connection from running Integrant system."
  []
  (some-> state/system :seon/runtime-db :conn))

;;; ---------------------------------------------------------------------------
;;; Lifecycle Wrappers
;;; ---------------------------------------------------------------------------

(defn- dynamic-namespace?
  "Check if namespace is dynamic via lifecycle module."
  [ns-sym]
  (when-let [conn (get-conn)]
    (::lifecycle/dynamic? (lifecycle/dynamic-namespace? {::lifecycle/conn conn
                                                         ::lifecycle/ns-sym ns-sym}))))

;;; ---------------------------------------------------------------------------
;;; Instance Helpers
;;; ---------------------------------------------------------------------------

(defn- instances-for-namespace
  "Get all instances for a specific namespace, sorted newest first."
  [ns-sym]
  (ctx/instances-for-namespace ns-sym))

;;; ---------------------------------------------------------------------------
;;; Content Negotiation
;;; ---------------------------------------------------------------------------

(defn- negotiate-format
  "Determine output format from request.
   Priority: ?format= query param > Accept header > default :html."
  [params request]
  (if-let [fmt (get params "format")]
    (case fmt
      "html" :html
      "ai" :ai
      "raw" :raw
      :html)
    (let [accept (get-in request [:headers "accept"] "")]
      (cond
        (str/includes? accept "text/plain") :ai
        (str/includes? accept "application/edn") :raw
        :else :html))))

(defn- build-ns-data
  "Build namespace data map for render-namespace.
   Uses introspect to gather namespace info and puts it under ::render/ns-vars."
  [ns-sym]
  (let [introspection (introspect/introspect ns-sym)]
    (cond-> {}
      introspection (assoc ::render/ns-vars introspection))))

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

(defn- render-instance-row
  "Render an instance as a table row."
  [ns-sym {::ctx/keys [instance-id] :keys [created-at]}]
  (let [cc (ctx/client-count {::ctx/instance-id instance-id})]
    [:tr {:class "hover:bg-base-800 border-b border-base-700/50"}
     ;; ID (clickable link)
     [:td {:class "py-2 px-3"}
      [:a {:href (str "/ns/" ns-sym "?instance=" instance-id)
           :class "font-mono text-signal hover:text-warning text-sm"}
       instance-id]]
     ;; Clients with status dot
     [:td {:class "py-2 px-3"}
      [:span {:class "inline-flex items-center gap-1.5"}
       [:span {:class (str "w-1.5 h-1.5 rounded-full "
                           (if (pos? cc) "bg-signal animate-pulse" "bg-text-500"))}]
       [:span {:class "text-xs text-text-200"} cc]]]
     ;; Created time
     [:td {:class "py-2 px-3 text-xs text-text-400"}
      (relative-time created-at)]
     ;; Actions
     [:td {:class "py-2 px-3 text-right"}
      [:div {:class "inline-flex gap-2"}
       [:a {:href (str "/ns/" ns-sym "?instance=" instance-id)
            :class "px-2 py-0.5 text-xs font-mono rounded border text-text-400 border-base-700 hover:border-base-600 hover:text-text-200"}
        "View"]
       [:form {:method "POST"
               :action (str "/ns/" ns-sym "/destroy-instance!?id=" instance-id)
               :class "inline"}
        [:button {:type "submit"
                  :class "px-2 py-0.5 text-xs font-mono rounded border text-error/70 border-base-700 hover:border-error/50 hover:text-error"}
         "x"]]]]]))

(defn- render-instances-section
  "Render the instances section for a reactive namespace."
  [ns-sym]
  (let [instances (instances-for-namespace ns-sym)]
    [:section {:class "mb-6"}
     [:div {:class "flex items-center justify-between mb-2"}
      (ui/section-header (str "INSTANCES (" (count instances) ")"))
      [:form {:method "POST"
              :action (str "/ns/" ns-sym "/create-instance!")
              :class "inline"}
       [:button {:type "submit"
                 :class "px-2 py-1 text-xs font-mono rounded border text-signal border-signal/30 hover:border-signal/50 hover:bg-signal/10"}
        "+ New Instance"]]]
     (if (seq instances)
       [:div {:class "bg-base-850 rounded overflow-hidden"}
        [:table {:class "w-full"}
         [:thead
          [:tr {:class "border-b border-base-700"}
           [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-20"} "ID"]
           [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-20"} "Clients"]
           [:th {:class "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"} "Created"]
           [:th {:class "text-right py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider w-32"} "Actions"]]]
         [:tbody
          (for [inst instances]
            (render-instance-row ns-sym inst))]]]
       ;; Empty state
       [:div {:class "bg-base-850 rounded p-6 text-center"}
        [:p {:class "text-text-500 text-sm"} "No active instances"]
        [:p {:class "text-text-400 text-xs mt-1"} "Create one to get started"]])]))

(defn- find-graph-render-fn
  "Find the page renderer for a namespace from the code graph.
   Returns a wrapped function (ctx-value) -> hiccup, or nil.
   Discovery is 100% spec-driven: the scanner sets :seon.fn/page-renderer?
   based on input spec containing *ctx* and output spec containing :seon.render/html."
  [ns-sym]
  (when-let [conn (get-conn)]
    (let [{::lifecycle/keys [render-fn]}
          (lifecycle/find-page-render-fn {::lifecycle/conn conn
                                          ::lifecycle/ns-sym ns-sym})]
      (when render-fn
        (lifecycle/make-render-fn {::lifecycle/render-fn render-fn
                                   ::lifecycle/ns-sym ns-sym})))))

(defn- build-available-keys
  "Build the set of available data keys for renderer resolution.
   Combines ctx key, conn key, web params, and def var keys."
  [ns-sym params]
  (let [ns-str (str ns-sym)
        ctx-key (keyword ns-str "*ctx*")
        conn-key (keyword ns-str "*conn*")
        web-params (render/namespace-web-params params ns-str)
        ;; Start with ctx + conn (always available for dynamic ns)
        base-keys #{ctx-key conn-key}]
    (into base-keys (keys web-params))))

(defn- build-renderer-input
  "Build the full input map for a resolved renderer.
   Assembles data from all sources: ctx, conn, web params."
  [ns-sym instance-id params]
  (let [ns-str (str ns-sym)
        ctx-key (keyword ns-str "*ctx*")
        conn-key (keyword ns-str "*conn*")
        web-params (render/namespace-web-params params ns-str)
        ctx-atom (when instance-id
                   (ctx/get-atom {::ctx/instance-id instance-id}))
        ctx-val (when ctx-atom @ctx-atom)
        conn (get-conn)]
    (cond-> {}
      ctx-val (assoc ctx-key ctx-val)
      conn (assoc conn-key conn)
      web-params (merge web-params))))

(defn- view-toggle-button
  "Toggle button to switch between custom render and introspection view.
   Only shown when namespace has a graph-discovered page renderer."
  [ns-sym current-view session-id]
  (let [introspect? (= current-view "introspect")
        ;; Build toggle URL preserving id param
        base-url (str "/ns/" ns-sym)
        toggle-url (if introspect?
                     (if session-id
                       (str base-url "?id=" session-id)
                       base-url)
                     (if session-id
                       (str base-url "?id=" session-id "&view=introspect")
                       (str base-url "?view=introspect")))]
    [:a {:href toggle-url
         :class "px-2 py-1 text-xs font-mono rounded border transition-colors text-text-500 border-base-700 hover:border-base-600 hover:text-text-200"}
     (if introspect?
       "<- Custom View"
       "Introspect ->")]))

(defn- render-introspection-view
  "Render the introspection view for a namespace (functions, vars, atoms, etc.)."
  [ns-sym session-id show-toggle?]
  (if-let [data (introspect/introspect ns-sym)]
    (let [{:keys [ns-name doc functions vars atoms multimethods requires]} data
          is-dynamic? (dynamic-namespace? ns-sym)]
      (h/html
       [:main#morph
        ;; Header with optional toggle
        [:div {:class "mb-4"}
         [:div {:class "flex items-center justify-between"}
          [:h1 {:class "text-lg font-semibold tracking-tight font-mono"} (str ns-name)]
          (when show-toggle?
            (view-toggle-button ns-sym "introspect" session-id))]
         (when session-id
           [:p {:class "text-text-400 text-xs mt-0.5"}
            "Session: " [:code {:class "text-signal"} session-id]])
         (when doc
           [:p {:class "text-text-400 text-sm mt-2 whitespace-pre-wrap max-w-3xl"} doc])]

        ;; Instances section (only for dynamic namespaces)
        (when is-dynamic?
          (render-instances-section ns-sym))

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
        "Back to Dashboard"]]])))

(defn- render-for-format
  "Render namespace content for non-HTML formats (:ai, :raw).
   Uses the render pipeline: page renderer > default renderer."
  [ns-sym fmt]
  (let [ns-data (build-ns-data ns-sym)
        result (render/render-namespace {::render/ns-data ns-data
                                         ::render/format fmt})]
    (case fmt
      :ai {:status 200
           :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body (str result)}
      :raw {:status 200
            :headers {"Content-Type" "application/edn; charset=utf-8"}
            :body (pr-str result)})))

(defn- render-namespace-content
  "Render full namespace view content for HTML format (SSE polling).
   Uses resolve-renderer with available keys for specificity-based resolution.
   Non-HTML formats are handled separately by render-for-format.

   Params:
   - ns-sym: The namespace symbol
   - session-id: Optional session/instance ID
   - view: Optional view mode - \"introspect\" forces introspection view
   - params: Parsed query params map (string keys)"
  [ns-sym session-id view params]
  (let [conn (get-conn)
        available-keys (build-available-keys ns-sym params)
        resolved (when conn
                   (render/resolve-renderer conn available-keys (str ns-sym)))
        graph-render (when-not resolved (find-graph-render-fn ns-sym))
        has-renderer? (or (some? resolved) (some? graph-render))
        force-introspect? (= view "introspect")]
    (cond
      ;; Force introspection view if requested
      force-introspect?
      (render-introspection-view ns-sym session-id has-renderer?)

      ;; Resolved renderer via specificity algorithm
      resolved
      (let [input (build-renderer-input ns-sym session-id params)
            result (resolved input)
            hiccup (:seon.render/html result)
            toggle-html (h/html
                         [:div {:class "mb-4 flex justify-end"}
                          (view-toggle-button ns-sym nil session-id)])
            custom-html (h/html [:main#morph hiccup])]
        (str/replace custom-html
                     #"(<main[^>]*>)"
                     (str "$1" toggle-html)))

      ;; Legacy graph-discovered page renderer
      graph-render
      (let [ctx-key (::lifecycle/ctx-spec-key
                     (lifecycle/ctx-spec-key {::lifecycle/ns-sym ns-sym}))
            hiccup (graph-render {ctx-key {}})
            toggle-html (h/html
                         [:div {:class "mb-4 flex justify-end"}
                          (view-toggle-button ns-sym nil session-id)])
            custom-html (h/html [:main#morph hiccup])]
        (str/replace custom-html
                     #"(<main[^>]*>)"
                     (str "$1" toggle-html)))

      ;; Default page template for dynamic namespaces without custom renderer
      (dynamic-namespace? ns-sym)
      (let [ctx-key (keyword (str ns-sym) "*ctx*")
            input {ctx-key {}}
            result (default-page/render-default-page input)
            hiccup (:seon.render/html result)
            toggle-html (h/html
                         [:div {:class "mb-4 flex justify-end"}
                          (view-toggle-button ns-sym nil session-id)])
            custom-html (h/html [:main#morph hiccup])]
        (str/replace custom-html
                     #"(<main[^>]*>)"
                     (str "$1" toggle-html)))

      ;; Default: introspection view
      :else
      (render-introspection-view ns-sym session-id false))))

;;; ---------------------------------------------------------------------------
;;; Reactive Instance Page Rendering
;;; ---------------------------------------------------------------------------

(defn- reactive-instance-page
  "Render full HTML page for a reactive instance.
   Uses base-page for consistent theming (dark bg, nav, highlight.js).
   The render-fn comes from the ctx registry (set by lifecycle/ensure-instance!)."
  [ns-sym instance-id]
  (let [ctx-atom (ctx/get-atom {::ctx/instance-id instance-id})]
    (if ctx-atom
      (let [entry (ctx/get-entry {::ctx/instance-id instance-id})
            render-fn (:render-fn entry)
            ctx-val @ctx-atom
            content-hiccup (when render-fn (render-fn ctx-val))
            transformed (when content-hiccup
                          (transform/transform-hiccup ns-sym content-hiccup instance-id))
            ;; The renderer produces [:main#morph ...] but base-page already wraps
            ;; skeleton in [:main#morph skeleton]. Extract inner content to avoid
            ;; nested #morph elements (which breaks Datastar's getElementById lookup).
            morph-children (if (and transformed
                                    (vector? transformed)
                                    (= :main#morph (first transformed)))
                             (if (map? (second transformed))
                               (let [[_ attrs & children] transformed]
                                 (into [:div attrs] children))
                               (into [:div] (rest transformed)))
                             (or transformed [:div.text-text-500 "Loading..."]))]
        (html/base-page
         {:title (str ns-sym " - Seon")
          :active-page nil
          :header
          [:header.mb-6
           [:div.flex.items-center.justify-between
            [:h1.text-xl.font-bold.text-amber-400 (str ns-sym)]
            [:a {:href (str "/ns/" ns-sym "?view=introspect&instance=" instance-id)
                 :class "px-2 py-1 text-xs font-mono rounded border text-text-500 border-base-700 hover:border-base-600 hover:text-text-200"}
             "Introspect ->"]]
           [:p.text-text-400.text-sm.mt-1
            "Instance: " [:code.text-amber-300 instance-id]]]
          :skeleton morph-children}))
      ;; Instance not found
      (html/base-page
       {:title "Instance Not Found"
        :active-page nil
        :skeleton
        [:div.flex.items-center.justify-center.min-h-96
         [:div.text-center
          [:h1.text-xl.font-bold.text-red-400 "Instance Not Found"]
          [:p.text-text-400.mt-2 "The instance " [:code instance-id] " does not exist."]
          [:a.text-amber-400.hover:underline.mt-4.inline-block
           {:href (str "/ns/" ns-sym)} "Create new instance"]]]}))))

(defn- instance-sse-handler
  "SSE handler for reactive instance updates.
   Registers the client channel with the instance for push updates."
  [ns-sym instance-id request]
  (hk/as-channel request
    {:on-open (fn [channel]
                (log/debug "SSE client connected for instance" {:ns ns-sym :instance instance-id})
                ;; Send headers once when connection opens
                (hk/send! channel
                          {:status 200
                           :headers {"Content-Type" "text/event-stream"
                                     "Cache-Control" "no-cache"
                                     "Connection" "keep-alive"}}
                          false)
                ;; Register client and push initial content
                (ctx/register-client! {::ctx/instance-id instance-id
                                       ::ctx/channel channel})
                (ctx/force-push! {::ctx/instance-id instance-id}))
     :on-close (fn [channel _status]
                 (log/debug "SSE client disconnected from instance" {:ns ns-sym :instance instance-id})
                 (ctx/unregister-client! {::ctx/instance-id instance-id
                                          ::ctx/channel channel}))}))

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
;;; Query Parameter Parsing
;;; ---------------------------------------------------------------------------

(defn- parse-query-params
  "Parse query string into map. Ring middleware isn't applied to SSE routes."
  [req]
  (or (:query-params req)
      (when-let [qs (:query-string req)]
        (into {} (for [pair (.split ^String qs "&")
                       :let [[k v] (.split ^String pair "=" 2)]]
                   [(java.net.URLDecoder/decode k "UTF-8")
                    (when v (java.net.URLDecoder/decode v "UTF-8"))])))))

;;; ---------------------------------------------------------------------------
;;; Auto-Injection Helpers
;;; ---------------------------------------------------------------------------

(defn- inject-ctx-conn
  "Auto-inject *ctx* and *conn* values into a signal map for a function call.
   Resolves the namespace's injected dynamic vars and adds their values
   under the appropriate keys (ending in *ctx* and *conn*)."
  [ns-sym signals]
  (let [ctx-var (find-var (symbol (str ns-sym) "*ctx*"))
        conn-var (find-var (symbol (str ns-sym) "*conn*"))
        ctx-key (keyword (str ns-sym) "*ctx*")
        conn-key (keyword (str ns-sym) "*conn*")]
    (cond-> signals
      (and ctx-var (bound? ctx-var))
      (assoc ctx-key @@ctx-var)
      (and conn-var (bound? conn-var))
      (assoc conn-key @conn-var))))

(defn- extract-fn-input-schema
  "Extract the input map schema from a function's Malli schema.
   Schema is [:=> [:cat [:map ...]] ...]. Returns the [:map ...] or nil."
  [action-fn]
  (when-let [schema (:malli/schema (meta action-fn))]
    (let [cat-schema (second schema)
          map-schema (second cat-schema)]
      (when (and (vector? map-schema) (= :map (first map-schema)))
        map-schema))))

;;; ---------------------------------------------------------------------------
;;; HTTP Handlers
;;; ---------------------------------------------------------------------------

(defn namespace-page
  "Serve the namespace view HTML page.

   For dynamic namespaces (detected via lifecycle/dynamic-namespace?):
   - Without ?instance param: Use lifecycle/ensure-instance! and redirect
   - With ?instance param: Serve reactive page for that instance

   For non-dynamic namespaces:
   - Serve introspection view as before"
  [request]
  (let [ns-str (get-in request [:path-params :namespace])
        ns-sym (symbol ns-str)
        params (parse-query-params request)
        fmt (negotiate-format params request)]
    ;; Fast path: non-HTML formats return immediately, no reactive/SSE machinery
    (if (#{:ai :raw} fmt)
      (render-for-format ns-sym fmt)
      (let [instance-id (get params "instance")
            view (get params "view")
            conn (get-conn)
            is-dynamic? (dynamic-namespace? ns-sym)]
        (cond
          ;; Dynamic namespace without instance -> ensure-instance! and redirect
          (and is-dynamic? (nil? instance-id) (not= view "introspect"))
          (let [{::lifecycle/keys [instance-id]}
                (lifecycle/ensure-instance! (cond-> {::lifecycle/ns-sym ns-sym}
                                              conn (assoc ::lifecycle/conn conn)))]
            (log/info "Ensured dynamic instance, redirecting" {:ns ns-sym :instance instance-id})
            {:status 302
             :headers {"Location" (str "/ns/" ns-sym "?instance=" instance-id)}})

          ;; Dynamic with instance -> serve instance page
          (and is-dynamic? instance-id (not= view "introspect"))
          (do
            ;; Ensure instance exists (resume from Datalevin or create fresh)
            (lifecycle/ensure-instance! (cond-> {::lifecycle/ns-sym ns-sym
                                                  ::lifecycle/instance-id instance-id}
                                          conn (assoc ::lifecycle/conn conn)))
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (reactive-instance-page ns-sym instance-id)})

          ;; HTML: existing behavior (SSE page with skeleton)
          :else
          {:status 200
           :headers {"Content-Type" "text/html; charset=utf-8"}
           :body (html/base-page
                  {:title (str ns-str " - Seon")
                   :active-page nil
                   :skeleton (namespace-skeleton ns-sym)})})))))

;; Dynamic handler cache - keyed by namespace only
(defonce ^:private namespace-handlers (atom {}))

(defn- get-namespace-handler
  "Get or create SSE handler for a namespace view."
  [ns-sym]
  (if-let [handler (get @namespace-handlers ns-sym)]
    handler
    (let [render-fn (fn [req]
                      (let [params (parse-query-params req)
                            session-id (get params "id")
                            view (get params "view")]
                        (render-namespace-content ns-sym session-id view params)))
          handler (sse/render-handler render-fn :poll-ms 2000)]
      (swap! namespace-handlers assoc ns-sym handler)
      handler)))

(defn namespace-sse
  "SSE handler for namespace view.

   For reactive instances (with ?instance param):
   - Uses instance SSE handler with push updates

   For introspection view:
   - Uses polling SSE handler"
  [request]
  (let [ns-str (get-in request [:path-params :namespace])
        ns-sym (symbol ns-str)
        params (parse-query-params request)
        instance-id (get params "instance")]
    (if instance-id
      ;; Instance-based SSE with push updates
      (instance-sse-handler ns-sym instance-id request)
      ;; Legacy polling SSE for introspection
      (let [handler (get-namespace-handler ns-sym)]
        (handler request)))))

;;; ---------------------------------------------------------------------------
;;; Hot Reload Support
;;; ---------------------------------------------------------------------------

(defn after-ns-reload
  "Called by clj-reload after namespace reload. Clears SSE handler cache."
  []
  (reset! namespace-handlers {}))

;;; ---------------------------------------------------------------------------
;;; Function Call Handler
;;; ---------------------------------------------------------------------------

(defn- handle-create-instance!
  "Handle POST /ns/:namespace/create-instance! - uses lifecycle to create instance."
  [ns-sym]
  (let [conn (get-conn)
        {::lifecycle/keys [instance-id]}
        (lifecycle/ensure-instance! (cond-> {::lifecycle/ns-sym ns-sym}
                                      conn (assoc ::lifecycle/conn conn)))]
    (log/info "Created instance via action" {:ns ns-sym :instance instance-id})
    {:status 302
     :headers {"Location" (str "/ns/" ns-sym "?instance=" instance-id)}}))

(defn- handle-destroy-instance!
  "Handle POST /ns/:namespace/destroy-instance!?id=xxxx - destroys instance and redirects."
  [ns-sym instance-id]
  (if instance-id
    (do
      (ctx/destroy! {::ctx/instance-id instance-id})
      (log/info "Destroyed instance via action" {:ns ns-sym :instance instance-id})
      {:status 302
       :headers {"Location" (str "/ns/" ns-sym "?view=introspect")}})
    {:status 400
     :headers {"Content-Type" "application/json"}
     :body "{\"success\":false,\"error\":\"Missing id parameter\"}"}))

(defn function-call-handler
  "Handle POST /ns/:namespace/:function requests.

   Signals arrive as nested JSON from Datastar's dot-notation encoding.
   The encoding layer (seon.web.reactive.encoding) decodes them back to
   fully qualified Clojure keywords — no schema introspection needed.

   Auto-injects *ctx* and *conn* values from the namespace's injected vars
   into the signal map, so functions receive ctx/conn without explicit wiring.

   Validates decoded signals against the function's Malli input schema
   before calling. Returns 400 with humanized errors on validation failure.

   Function names are URL-decoded to handle %21 -> ! etc."
  [request]
  (let [ns-str (get-in request [:path-params :namespace])
        fn-str (get-in request [:path-params :function])
        params (parse-query-params request)
        instance-id (get params "instance")
        id-param (get params "id")
        ;; URL-decode function name to handle %21 -> ! etc.
        fn-decoded (java.net.URLDecoder/decode fn-str "UTF-8")
        ns-sym (symbol ns-str)
        fn-sym (symbol fn-decoded)
        body (:body request)]
    (log/info "Function call" {:ns ns-sym :fn fn-sym :instance instance-id :body body})
    (cond
      ;; Instance management actions
      (= fn-sym 'create-instance!)
      (handle-create-instance! ns-sym)

      (= fn-sym 'destroy-instance!)
      (handle-destroy-instance! ns-sym id-param)

      ;; Regular function call
      :else
      (if-let [action-fn (actions/resolve-action ns-sym fn-sym)]
        (try
          (let [;; Decode signals using the encoding layer — preserves full
                ;; qualified keywords through the Datastar round-trip.
                ;; No more schema introspection needed for re-namespacing.
                decoded-signals (encoding/decode-signals body)
                ;; Auto-inject *ctx* and *conn* from namespace vars
                injected (inject-ctx-conn ns-sym decoded-signals)
                ;; Add instance ctx atom if present
                signals (if instance-id
                          (let [ctx-atom (ctx/get-atom {::ctx/instance-id instance-id})]
                            (if ctx-atom
                              (assoc injected :seon.reactive/ctx ctx-atom)
                              injected))
                          injected)
                ;; Validate after injection so auto-injected keys satisfy schema
                input-schema (extract-fn-input-schema action-fn)
                _ (when (and input-schema (seq signals))
                    (when-not (m/validate input-schema signals)
                      (throw (ex-info "Invalid input"
                                      {:errors (me/humanize (m/explain input-schema signals))}))))]
            (log/info "Executing function" {:ns ns-sym :fn fn-sym :signals (dissoc signals :seon.reactive/ctx)})
            (action-fn signals)
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body "{\"success\":true}"})
          (catch Exception e
            (let [data (ex-data e)
                  status (if (:errors data) 400 500)]
              (log/error e "Function execution failed" {:ns ns-sym :fn fn-sym})
              {:status status
               :headers {"Content-Type" "application/json"}
               :body (str "{\"success\":false,\"error\":\"" (.getMessage e) "\"}")})))
        (do
          (log/warn "Function not found" {:ns ns-sym :fn fn-sym})
          {:status 404
           :headers {"Content-Type" "application/json"}
           :body "{\"success\":false,\"error\":\"Function not found\"}"})))))

;;; ---------------------------------------------------------------------------
;;; Route Registration Helper
;;; ---------------------------------------------------------------------------

(def route-patterns
  "Dynamic route patterns for namespace introspection and function calls.
   Use with seon.web.routes/dynamic-routes.

   Order matters: function call pattern must come BEFORE namespace pattern
   because the function pattern is more specific."
  [;; Function call: POST /ns/:namespace/:function
   ;; Function names can contain ! ? - _ etc.
   {:method :post
    :pattern #"/ns/([a-z][a-z0-9._-]*)/([a-zA-Z][a-zA-Z0-9_!?*-]*)"
    :params [:namespace :function]
    :handler #'function-call-handler}
   ;; Namespace view: GET /ns/:namespace
   {:method :get
    :pattern #"/ns/([a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'namespace-page}
   ;; Namespace SSE: POST /ns/:namespace (no function = SSE for introspection)
   {:method :post
    :pattern #"/ns/([a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'namespace-sse}])
