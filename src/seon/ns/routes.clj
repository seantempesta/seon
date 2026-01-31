(ns seon.ns.routes
  "HTTP routes for namespace introspection and function calls.

   Provides:
   - /ns/{namespace} - Custom render (if namespace has `render` fn)
                       or static introspection view (vars, functions, schemas)
   - /ns/{namespace}?id={id} - Instance view (passed to render)
   - /ns/{namespace}/{function} - Function call (POST only, for reactive UI actions)

   Convention: If a namespace has a public `render` function, it will be called
   with {:format :html/:ai/:raw :id optional-id}. This allows namespaces to
   provide custom views of their data in multiple formats.

   Note: Functions here are Ring handlers using positional args, following
   the pattern of other web handlers in seon.web.*"
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as h]
            [org.httpkit.server :as hk]
            [ring.util.codec :as codec]
            [seon.ns.introspect :as introspect]
            [seon.ns.view :as view]
            [seon.web.html :as html]
            [seon.web.sse :as sse]
            [seon.web.components :as ui]
            [seon.web.reactive.actions :as actions]
            [seon.web.reactive.instance :as instance]
            [seon.web.reactive.transform :as transform]
            [taoensso.timbre :as log]))

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
  "Check if namespace has a public render function for page rendering.
   The render function must accept a single map argument with :format and :id keys.
   This distinguishes page render functions from other render functions like seon.ns.view/render."
  [ns-sym]
  (when-let [ns-obj (find-ns ns-sym)]
    (when-let [render-var (ns-resolve ns-obj 'render)]
      (and (var? render-var)
           (fn? @render-var)
           ;; Check that it accepts a single argument (the options map)
           (let [arglists (:arglists (meta render-var))]
             (some #(= 1 (count %)) arglists))))))

(defn- namespace-has-reactive-render?
  "Check if namespace has a reactive render-content function.
   A reactive namespace has:
   - A public render-content function that takes 1 arg (ctx value map)
   This is distinct from render which takes {:format :id} options map."
  [ns-sym]
  (when-let [ns-obj (find-ns ns-sym)]
    (when-let [render-var (ns-resolve ns-obj 'render-content)]
      (and (var? render-var)
           (fn? @render-var)
           (let [arglists (:arglists (meta render-var))]
             (some #(= 1 (count %)) arglists))))))

(defn- get-initial-state
  "Get initial state for a reactive namespace.
   Calls the namespace's initial-state function if it exists,
   otherwise returns an empty map."
  [ns-sym]
  (if-let [ns-obj (find-ns ns-sym)]
    (if-let [init-var (ns-resolve ns-obj 'initial-state)]
      (when (and (var? init-var) (fn? @init-var))
        (@init-var))
      {})
    {}))

(defn- get-render-content-fn
  "Get the render-content function from a namespace."
  [ns-sym]
  (when-let [ns-obj (find-ns ns-sym)]
    (when-let [render-var (ns-resolve ns-obj 'render-content)]
      @render-var)))

(defn- call-namespace-render
  "Call the namespace's render function with format and id."
  [ns-sym format id]
  (let [render-fn @(ns-resolve (find-ns ns-sym) 'render)]
    (render-fn {:format format :id id})))

(defn- view-toggle-button
  "Toggle button to switch between custom render and introspection view.
   Only shown when namespace has a render function."
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
       "← Custom View"
       "Introspect →")]))

(defn- render-introspection-view
  "Render the introspection view for a namespace (functions, vars, atoms, etc.)."
  [ns-sym session-id show-toggle?]
  (if-let [data (introspect/introspect ns-sym)]
    (let [{:keys [ns-name doc functions vars atoms multimethods requires]} data]
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

(defn- render-custom-view
  "Render namespace with its custom render function, including toggle button.
   The custom render function returns an HTML string (already rendered).
   We prepend a toggle button by injecting it after the opening main tag."
  [ns-sym session-id]
  (let [custom-html (call-namespace-render ns-sym :html session-id)
        toggle-html (h/html
                     [:div {:class "mb-4 flex justify-end"}
                      (view-toggle-button ns-sym nil session-id)])]
    ;; Inject toggle after <main id="morph"> opening tag
    (str/replace custom-html
                 #"(<main[^>]*>)"
                 (str "$1" toggle-html))))

(defn- render-namespace-content
  "Render full namespace view content.
   If namespace has a `render` function, calls it with {:format :html :id id}.
   Otherwise falls back to introspection view.

   Params:
   - ns-sym: The namespace symbol
   - session-id: Optional session/instance ID
   - view: Optional view mode - \"introspect\" forces introspection view"
  [ns-sym session-id view]
  (let [has-render? (namespace-has-render? ns-sym)
        force-introspect? (= view "introspect")]
    (cond
      ;; Force introspection view if requested
      force-introspect?
      (render-introspection-view ns-sym session-id has-render?)

      ;; Use custom render if available
      has-render?
      (render-custom-view ns-sym session-id)

      ;; Default to introspection (no toggle since no custom render exists)
      :else
      (render-introspection-view ns-sym session-id false))))

;;; ---------------------------------------------------------------------------
;;; Reactive Instance Page Rendering
;;; ---------------------------------------------------------------------------

(defn- reactive-instance-page
  "Render full HTML page for a reactive instance.
   Similar to demo.clj's full-page but using the instance's render-content."
  [ns-sym instance-id]
  (let [instance-info (instance/get-instance {::instance/id instance-id})
        ctx-atom (::instance/atom instance-info)]
    (if ctx-atom
      (let [render-fn (get-render-content-fn ns-sym)
            ctx-val @ctx-atom
            content-hiccup (when render-fn (render-fn ctx-val))
            transformed (when content-hiccup
                          (transform/transform-hiccup ns-sym content-hiccup instance-id))]
        (str
         "<!DOCTYPE html>"
         (h/html
          [:html {:lang "en"}
           [:head
            [:meta {:charset "UTF-8"}]
            [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
            [:title (str ns-sym " - Seon")]
            [:link {:rel "stylesheet" :href "/css/output.css"}]
            [:script {:type "module" :src html/datastar-js}]
            [:script {:src "/js/scittle.js"}]
            [:script {:src "/js/seon-debug.js"}]]
           [:body.bg-base-bg.text-text-primary.font-mono.min-h-screen
            [:div.container.mx-auto.p-6.max-w-4xl
             [:header.mb-6
              [:div.flex.items-center.justify-between
               [:h1.text-xl.font-bold.text-accent-primary (str ns-sym)]
               [:a {:href (str "/ns/" ns-sym "?view=introspect&instance=" instance-id)
                    :class "px-2 py-1 text-xs font-mono rounded border text-text-500 border-base-700 hover:border-base-600 hover:text-text-200"}
                "Introspect →"]]
              [:p.text-text-secondary.text-sm.mt-1
               "Instance: " [:code.text-signal instance-id]]]
             ;; Main content - SSE connects via data-init
             [:main#reactive-content
              {:data-init (str "@post('/ns/" ns-sym "?instance=" instance-id "')")}
              (or transformed [:div.text-text-muted "Loading..."])]
             [:footer.mt-8.pt-4.border-t.border-surface-2.text-text-muted.text-sm
              [:a.text-accent-primary.hover:underline {:href "/"} "← Back to dashboard"]]]]])))
      ;; Instance not found
      (str
       "<!DOCTYPE html>"
       (h/html
        [:html {:lang "en"}
         [:head
          [:meta {:charset "UTF-8"}]
          [:title "Instance Not Found"]]
         [:body.bg-base-bg.text-text-primary.font-mono.min-h-screen.flex.items-center.justify-center
          [:div.text-center
           [:h1.text-xl.font-bold.text-error "Instance Not Found"]
           [:p.text-text-muted.mt-2 "The instance " [:code instance-id] " does not exist."]
           [:a.text-accent-primary.hover:underline.mt-4.inline-block
            {:href (str "/ns/" ns-sym)} "Create new instance"]]]])))))

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
                (instance/register-client! {::instance/id instance-id
                                            ::instance/channel channel})
                (instance/force-push! {::instance/id instance-id}))
     :on-close (fn [channel _status]
                 (log/debug "SSE client disconnected from instance" {:ns ns-sym :instance instance-id})
                 (instance/unregister-client! {::instance/id instance-id
                                               ::instance/channel channel}))}))

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
        (codec/form-decode qs))))

;;; ---------------------------------------------------------------------------
;;; HTTP Handlers
;;; ---------------------------------------------------------------------------

(defn namespace-page
  "Serve the namespace view HTML page.

   For reactive namespaces (those with render-content fn):
   - Without ?instance param: Create new instance and redirect
   - With ?instance param: Serve reactive page for that instance

   For non-reactive namespaces:
   - Serve introspection view as before"
  [request]
  (let [ns-str (get-in request [:path-params :namespace])
        ns-sym (symbol ns-str)
        params (parse-query-params request)
        instance-id (get params "instance")
        view (get params "view")
        is-reactive? (namespace-has-reactive-render? ns-sym)]
    (cond
      ;; Reactive namespace without instance → create and redirect
      (and is-reactive? (nil? instance-id) (not= view "introspect"))
      (let [initial-val (get-initial-state ns-sym)
            result (instance/create-instance! {::instance/namespace ns-sym
                                               ::instance/initial-value initial-val})
            new-id (::instance/id result)
            render-fn (get-render-content-fn ns-sym)]
        ;; Set render function for SSE push
        (when render-fn
          (instance/set-render-fn! {::instance/id new-id
                                    ::instance/render-fn render-fn}))
        (log/info "Created reactive instance, redirecting" {:ns ns-sym :instance new-id})
        {:status 302
         :headers {"Location" (str "/ns/" ns-sym "?instance=" new-id)}})

      ;; Reactive with instance → serve instance page
      (and is-reactive? instance-id (not= view "introspect"))
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (reactive-instance-page ns-sym instance-id)}

      ;; Not reactive or explicit introspect view → existing behavior
      :else
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (html/base-page
              {:title (str ns-str " - Seon")
               :active-page nil
               :skeleton (namespace-skeleton ns-sym)})})))

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
                        (render-namespace-content ns-sym session-id view)))
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

(defn function-call-handler
  "Handle POST /ns/:namespace/:function requests.

   This is the new URL pattern for reactive UI actions.
   Reuses signal extraction and function resolution from actions.clj.

   For instance-based calls (?instance=xxxx):
   - Adds :seon.reactive/ctx atom to signals for action to use

   Function names are URL-decoded to handle %21 -> ! etc."
  [request]
  (let [ns-str (get-in request [:path-params :namespace])
        fn-str (get-in request [:path-params :function])
        params (parse-query-params request)
        instance-id (get params "instance")
        ;; URL-decode function name to handle %21 -> ! etc.
        fn-decoded (codec/url-decode fn-str)
        ns-sym (symbol ns-str)
        fn-sym (symbol fn-decoded)
        body (:body request)]
    (log/info "Function call" {:ns ns-sym :fn fn-sym :instance instance-id :body body})
    (if-let [action-fn (actions/resolve-action ns-sym fn-sym)]
      (try
        (let [base-signals (actions/extract-signals body)
              ;; Add instance ctx if present
              signals (if instance-id
                        (let [inst (instance/get-instance {::instance/id instance-id})
                              ctx-atom (::instance/atom inst)]
                          (if ctx-atom
                            (assoc base-signals :seon.reactive/ctx ctx-atom)
                            base-signals))
                        base-signals)]
          (log/info "Executing function" {:ns ns-sym :fn fn-sym :signals (dissoc signals :seon.reactive/ctx)})
          (action-fn signals)
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body "{\"success\":true}"})
        (catch Exception e
          (log/error e "Function execution failed" {:ns ns-sym :fn fn-sym})
          {:status 500
           :headers {"Content-Type" "application/json"}
           :body (str "{\"success\":false,\"error\":\"" (.getMessage e) "\"}")}))
      (do
        (log/warn "Function not found" {:ns ns-sym :fn fn-sym})
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body "{\"success\":false,\"error\":\"Function not found\"}"}))))

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
