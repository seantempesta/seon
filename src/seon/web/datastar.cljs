(ns seon.web.datastar
  "Datastar gzip-morph SSE streamer — the hyperlith `view = f(db)` model
   ported into the pod.

   Each route derives a view from the database. Initial paint sends the whole
   `#app-view`; later commits replay each unit's runtime-observed database reads
   and send only complete, ID-addressed elements whose result changed.
   Equivalent open feeds share that render and receive the same gzip-compressed
   event.

   ## The surfaces (seeded `:seon.route/*` datoms → this ns's handlers)

     GET /agent/{id}   → one agent's view ([[serve-agent-page!]]); its
     GET /agent/{id}/feed  gzip feed ([[open-agent-feed!]] → agent-view).
     GET /             → root's view ([[serve-root!]]); `/` IS root's
                         dashboard and fleet list.

   ## Wire format (grounded in datastar consts.clj + the proven ds-spike.js)

   Event `datastar-patch-elements`; each HTML line is one `data: elements
   <line>` dataline; the default patch mode is `outer` (morph the element
   with the matching id), so a plain whole-element morph needs no
   selector/mode dataline. A blank line terminates the event.

   ## gzip (Content-Encoding: gzip) — proven against our datastar.js

   `zlib.createGzip()` is piped to the response; each event is written then
   `gz.flush(Z_SYNC_FLUSH)`ed so the compressed bytes hit the wire
   immediately. The browser transparently gunzips before datastar's fetch
   reader sees text. Crash-proofed: error handlers on gz + res, a
   writableEnded guard before every write, and `req.on('close')` ends the
   gzip stream + deregisters the connection."
  (:require
    ["node:zlib" :as zlib]
    [clojure.set :as set]
    [clojure.string :as str]
    [seon.db :as db]
    [seon.db.coordinate :as db.coordinate]
    [seon.db.protocol :as protocol]
    [seon.execution :as execution]
    [seon.execution.host :as execution.host]
    [seon.log :as log]
    [seon.schema :as schema]
    [seon.ui.agent-view :as agent-view]
    [seon.ui.html :as html]
    [seon.web.brand :as brand]
    [seon.web.view-unit :as view-unit]))

;; ============================================================
;; View units — stable, pure presentation coordinates.
;;
;; A definition names one renderer without invoking it. `unit-catalog` adds
;; only values derived from the coordinate, so inactive page shells can be
;; built without paying for content. Tokens are opaque client handles: the
;; eventual route resolves them through its trusted server-side catalog and
;; never decodes client input into code.
;; ============================================================

(schema/register! ::coordinate-value :seon.web.view-unit/coordinate-value)
(schema/register! ::coordinate :seon.web.view-unit/coordinate)
(schema/register! ::token :seon.web.view-unit/token)
(schema/register! ::dom-id [:string {:min 1}])
(schema/register! ::label :string)
(schema/register! ::order :int)
(schema/register! ::exclusive-group :qualified-keyword)
(schema/register! ::producer 'fn?)
(schema/register! ::definition
  [:map
   [::coordinate ::coordinate]
   [::producer ::producer]
   [::label {:optional true} ::label]
   [::order {:optional true} ::order]
   [::exclusive-group {:optional true} ::exclusive-group]])
(schema/register! ::definitions [:vector ::definition])
(schema/register! ::descriptor
  [:map
   [::coordinate ::coordinate]
   [::token ::token]
   [::dom-id ::dom-id]
   [::producer ::producer]
   [::label {:optional true} ::label]
   [::order {:optional true} ::order]
   [::exclusive-group {:optional true} ::exclusive-group]])
(schema/register! ::catalog [:vector ::descriptor])
(schema/register! ::active-tokens [:set ::token])
(schema/register! ::demanded-tokens [:set ::token])
(schema/register! ::active? :boolean)
(schema/register! ::transition-request
  [:map
   [::catalog ::catalog]
   [::active-tokens ::active-tokens]
   [::token ::token]
   [::active? ::active?]])
(schema/register! ::activated-tokens [:set ::token])
(schema/register! ::deactivated-tokens [:set ::token])
(schema/register! ::transition-response
  [:map
   [::active-tokens ::active-tokens]
   [::activated-tokens ::activated-tokens]
   [::deactivated-tokens ::deactivated-tokens]])
(schema/register! ::fingerprint [:string {:min 1}])
(schema/register! ::view-id [:string {:min 1 :max 128}])
(schema/register! ::optional-view-id [:maybe ::view-id])
(schema/register! ::dependencies
  [:or [:= :all] [:set :qualified-keyword]])
(schema/register! ::element :seon.render.canvas/hiccup)
(schema/register! ::elements [:vector :seon.render.canvas/hiccup])
(schema/register! ::serialized-elements [:vector :string])
(schema/register! ::event :string)
(schema/register! ::last-event :string)
(schema/register! ::full-event-coordinate ::db.coordinate/coordinate)
(schema/register! ::view-state
  [:map
   [::view-id ::view-id]
   [::catalog ::catalog]
   [::active-tokens ::active-tokens]
   [::demanded-tokens ::demanded-tokens]])
(schema/register! ::views [:map-of ::view-id ::view-state])
(schema/register! ::feed-key
  [:or
   [:tuple [:= :seon.web.feed/agent] :seon.agent/id]
   [:tuple [:= :seon.web.feed/agent] :seon.agent/id
    [:= :seon.web.feed/at] ::db.coordinate/coordinate]
   [:tuple [:= :seon.web.feed/data]
    [:maybe :string] [:maybe :string] [:maybe :string] :boolean]
   [:tuple [:= :seon.web.feed/debug] :seon.agent/id ::view-id]])
(schema/register! ::render-full 'fn?)
(schema/register! ::render-change 'fn?)
(schema/register! ::live? :boolean)
(schema/register! ::feed-definition
  [:map
   [:seon.web.feed/key ::feed-key]
   [:seon.web.feed/live? ::live?]
   [:seon.web.feed/coordinate {:optional true} ::db.coordinate/coordinate]
   [:seon.web.feed/render-full ::render-full]
   [:seon.web.feed/render-change ::render-change]
   [::dependencies {:optional true} ::dependencies]
   [::view-id {:optional true} ::view-id]
   [::catalog {:optional true} ::catalog]
   [::active-tokens {:optional true} ::active-tokens]
   [::demanded-tokens {:optional true} ::demanded-tokens]])
(schema/register! ::reconcile-catalog-request
  [:map [::view-id ::view-id] [::catalog ::catalog]])
;; Ring is a third-party request boundary; Seon's owned unit state above is
;; fully named and uses concrete schemas.
(schema/register! ::ring-request :map)

(defn unit-token
  "Stable opaque token derived from a canonical unit coordinate."
  {:malli/schema [:=> [:catn [::coordinate ::coordinate]] ::token]}
  [coordinate]
  (view-unit/coordinate-token coordinate))

(defn unit-dom-id
  "Stable DOM id derived from a unit coordinate."
  {:malli/schema [:=> [:catn [::coordinate ::coordinate]] ::dom-id]}
  [coordinate]
  (str "seon-unit-" (unit-token coordinate)))

(defn unit-catalog
  "Compile cheap unit definitions into stable descriptors."
  {:malli/schema [:=> [:catn [::definitions ::definitions]] ::catalog]}
  [definitions]
  (->> definitions
       (map (fn [{coordinate ::coordinate :as definition}]
              (assoc definition
                     ::token (unit-token coordinate)
                     ::dom-id (unit-dom-id coordinate))))
       (sort-by (juxt #(get % ::order 0) ::token))
       vec))

(defn inactive-stub
  "Render a stable empty unit target without invoking its producer."
  {:malli/schema [:=> [:catn [::descriptor ::descriptor]]
                  :seon.render.canvas/hiccup]}
  [{token ::token dom-id ::dom-id label ::label}]
  [:div (cond-> {:id dom-id
                 :data-seon-unit token
                 :data-seon-unit-active "false"}
          (seq label) (assoc :aria-label label))])

(defn transition-active-set
  "Apply one activation or deactivation to an ephemeral active set."
  {:malli/schema [:=> [:catn [::transition-request ::transition-request]]
                  ::transition-response]}
  [{catalog ::catalog
    active-tokens ::active-tokens
    token ::token
    active? ::active?}]
  (let [target (some #(when (= token (::token %)) %) catalog)
        group (::exclusive-group target)
        competing (if (and target active? group)
                    (into #{}
                          (comp (filter #(= group (::exclusive-group %)))
                                (map ::token)
                                (filter active-tokens)
                                (remove #{token}))
                          catalog)
                    #{})
        next-active (cond
                      (nil? target) active-tokens
                      active? (-> active-tokens
                                  (set/difference competing)
                                  (conj token))
                      :else (disj active-tokens token))]
    {::active-tokens next-active
     ::activated-tokens (set/difference next-active active-tokens)
     ::deactivated-tokens (set/difference active-tokens next-active)}))

(defn active-fingerprint
  "Order-independent fingerprint of an ephemeral active unit set."
  {:malli/schema [:=> [:catn [::active-tokens ::active-tokens]] ::fingerprint]}
  [active-tokens]
  (view-unit/encode-text (pr-str (vec (sort active-tokens)))))

;; ============================================================
;; Feed registry — socket-owning views consume normalized subscriptions. A
;; subscription key is the feed's stable semantic key plus its active-unit
;; fingerprint. One subscription owns the render transition and dependency
;; authority; equivalent sockets never own competing copies of either.
;; ============================================================

(def ^:private empty-feed-registry
  {::views {}
   ::subscriptions {}
   ::listener-installed? false})

(defonce ^{:doc "Ephemeral views and their normalized subscription authorities.
                  Reconnecting one view replaces only its socket. Equivalent
                  views share one render transition and dependency set until
                  the final consumer closes."}
  !feeds (atom empty-feed-registry))

;; ============================================================
;; SSE framing — the datastar-patch-elements builder.
;; ============================================================

(defn patch-elements
  "Build a `datastar-patch-elements` SSE event string from an HTML string.

   Each HTML line becomes one `data: elements <line>` dataline; a blank
   line terminates the event. The default patch mode is `outer` (morph the
   element with the matching id), so a whole-element morph needs no
   selector/mode dataline."
  {:malli/schema [:=> [:catn [::html :string]] :string]}
  [html-str]
  (str "event: datastar-patch-elements\n"
       "data: elements " (str/replace html-str "\n" "\ndata: elements ")
       "\n\n"))

(defn- patch-hiccup-elements
  "Build one Datastar event containing complete, ID-addressed hiccup elements."
  [elements]
  (->> elements
       (map html/->string)
       (str/join "\n")
       patch-elements))

(defn- patch-rendered-elements
  "Frame complete Hiccup and already-serialized unit elements together."
  [elements serialized-elements]
  (->> (concat (map html/->string elements) serialized-elements)
       (str/join "\n")
       patch-elements))

;; ============================================================
;; view = f(db) — the live agents view as `[:main#app-view …rows…]`.
;; ============================================================

(defn- render-error-patch [error]
  (let [message (if (some? error)
                  (or (try (.-message error) (catch :default _ nil))
                      (str error))
                  "unknown render failure")]
    {::event
     (-> [:main {:id "app-view"}
          [:div {:id "app-error" :class "text-error text-xs font-mono"}
           (str "render error: " message)]]
         html/->string
         patch-elements)
     ::target-count 1}))

(defn- rendered-view-patch [rendered]
  (let [observed? (and (map? rendered) (contains? rendered ::element))
        element (if observed? (::element rendered) rendered)]
    (cond-> {::event (-> element html/->string patch-elements)
             ::target-count 1}
      (and observed? (contains? rendered ::dependencies))
      (assoc ::dependencies (::dependencies rendered))

      (and observed? (contains? rendered ::catalog))
      (assoc ::catalog (::catalog rendered))

      (and observed? (contains? rendered ::view-id))
      (assoc ::view-id (::view-id rendered)))))

(defn- promise-like? [value]
  (and (some? value)
       (or (object? value) (fn? value))
       (fn? (.-then value))))

(defn- view-fn-patch
  "Render a bound full-view fn into one event plus learned dependencies.

   A plain hiccup result remains valid. A database-observed view returns
   `::element` and `::dependencies`; the normalized subscription owns both.
   Async views retain the same result shape and visible error boundary."
  [view-fn]
  (try
    (let [rendered (view-fn)]
      (if (promise-like? rendered)
        (.then rendered rendered-view-patch render-error-patch)
        (rendered-view-patch rendered)))
    (catch :default error
      (render-error-patch error))))

;; ============================================================
;; Per-connection push + broadcast. Equivalent feeds share a render; each
;; gzip stream independently applies latest-wins backpressure. Best-effort.
;; ============================================================

(declare push-event!)

(defn- drain-feed!
  "Resume one backpressured feed with only its newest pending event."
  [{pending :seon.web.feed/pending-event
    draining? :seon.web.feed/draining?
    :as conn}]
  (reset! draining? false)
  (when-let [event @pending]
    (reset! pending nil)
    (push-event! conn event)))

(defn- push-event!
  "Write one rendered event, retaining only the newest event under pressure."
  [{gz :seon.web.feed/gzip
    res :seon.web.feed/response
    pending :seon.web.feed/pending-event
    draining? :seon.web.feed/draining?
    :as conn}
   event]
  (try
    (cond
      (or (.-writableEnded ^js gz) (.-writableEnded ^js res))
      (reset! pending nil)

      @draining?
      (reset! pending event)

      :else
      (let [accepted? (.write ^js gz event)]
        (.flush ^js gz (.. zlib -constants -Z_SYNC_FLUSH))
        (when-not accepted?
          (reset! draining? true)
          (.once ^js gz "drain" #(drain-feed! conn)))))
    (catch :default e
      (log/error-console! "seon.web.datastar" "push-event! failed" e))))

(def ^:private heartbeat-interval-ms 15000)

(defonce ^{:private true
           :doc "The one timer keeping every currently open feed proxy-safe."}
  !heartbeat-timer (atom nil))

(defn- set-heartbeat-interval! [callback]
  (js/setInterval callback heartbeat-interval-ms))

(defn- clear-heartbeat-interval! [timer]
  (js/clearInterval timer))

(defn- push-heartbeat!
  "Flush one inert SSE comment without displacing pending application state."
  [{gz :seon.web.feed/gzip
    res :seon.web.feed/response
    draining? :seon.web.feed/draining?
    :as conn}]
  (try
    (when (and (not (.-writableEnded ^js gz))
               (not (.-writableEnded ^js res))
               (not @draining?))
      (let [accepted? (.write ^js gz ": keep-alive\n\n")]
        (.flush ^js gz (.. zlib -constants -Z_SYNC_FLUSH))
        (when-not accepted?
          (reset! draining? true)
          (.once ^js gz "drain" #(drain-feed! conn)))))
    (catch :default e
      (log/error-console! "seon.web.datastar" "feed heartbeat failed" e))))

(defn- heartbeat! []
  (doseq [conn (vals (::views @!feeds))]
    (push-heartbeat! conn)))

(defn- ensure-heartbeat! []
  (when-not @!heartbeat-timer
    (reset! !heartbeat-timer (set-heartbeat-interval! heartbeat!))))

(defn- stop-heartbeat! []
  (when-let [timer @!heartbeat-timer]
    (clear-heartbeat-interval! timer)
    (reset! !heartbeat-timer nil)))

(declare merge-change start-render! reconcile-view-catalog!)

(defn- transition-patch [transition serialized-elements-by-token]
  (when-not (map? transition)
    (throw (js/Error. "subscription render must return a map")))
  (let [elements (::elements transition)
        serialized (into (vec (::serialized-elements transition))
                         (vals serialized-elements-by-token))
        event (when (or (seq elements) (seq serialized))
                (patch-rendered-elements elements serialized))]
    (cond-> {::event event
             ::target-count (+ (count elements) (count serialized))}
      (contains? transition ::dependencies)
      (assoc ::dependencies (::dependencies transition))

      (contains? transition ::catalog)
      (assoc ::catalog (::catalog transition))

      (contains? transition ::view-id)
      (assoc ::view-id (::view-id transition))

      (::render-full? transition)
      (assoc ::render-full? true))))

(defn- render-request-result [subscription request]
  (try
    (if (::render-full? request)
      (view-fn-patch #((:seon.web.feed/render-full subscription)))
      (let [rendered ((::render-change subscription)
                      subscription (::change request))]
        (if (promise-like? rendered)
          (.then rendered
                 #(transition-patch % (::serialized-elements-by-token request))
                 render-error-patch)
          (transition-patch rendered (::serialized-elements-by-token request)))))
    (catch :default error
      (render-error-patch error))))

(defn- pending-render-request [subscription active pending request]
  (cond
    (or (::render-full? request)
        (not (::full-event-committed? subscription)))
    (assoc request ::render-full? true)

    (::render-full? pending)
    (assoc pending ::render-id (::render-id request)
           ::render-number (::render-number request))

    :else
    (let [prior (or pending active)]
      (assoc request
             ::change (merge-change (::change prior) (::change request))
             ::serialized-elements-by-token
             (merge (::serialized-elements-by-token prior)
                    (::serialized-elements-by-token request))))))

(defn- subscription-affected?
  "Whether committed attributes can change one subscription's projection."
  [subscription change]
  (let [dependencies (::dependencies subscription)
        changed-attrs (:seon.db/changed-attrs change)]
    (or (= :all dependencies)
        (nil? dependencies)
        (empty? changed-attrs)
        (boolean (seq (set/intersection dependencies changed-attrs))))))

(defn- record-complete-event
  "Retain one complete serialized render inside its live subscription."
  [subscription active rendered]
  (let [complete? (or (::render-full? active) (::render-full? rendered))
        event (when complete? (::event rendered))
        coordinate (or (:seon.db/coordinate rendered)
                       (get-in active [::change :seon.db/coordinate]))]
    (cond-> subscription
      event
      (assoc ::full-event event
             ::full-event-committed? true)

      (and coordinate event)
      (assoc ::full-event-coordinate coordinate))))

(defn- finish-render!
  [subscription-key subscription-id render-id rendered]
  (let [[before after]
        (swap-vals!
         !feeds
         (fn [registry]
           (let [path [::subscriptions subscription-key]
                 subscription (get-in registry path)
                 active (::active-render subscription)]
             (if (and (= subscription-id (::subscription-id subscription))
                      (= render-id (::render-id active)))
               (if-let [pending (::pending-render subscription)]
                 (assoc-in registry path
                           (-> subscription
                               (assoc ::active-render pending)
                               (dissoc ::pending-render)))
                 (assoc-in
                  registry path
                  (cond-> (record-complete-event
                           (dissoc subscription ::active-render)
                           active rendered)
                    (contains? rendered ::dependencies)
                    (assoc ::dependencies (::dependencies rendered))

                    (and (not (::render-full? active))
                         (::event rendered)
                         (not= (::event rendered) (::last-event subscription)))
                    (assoc ::last-event (::event rendered)))))
               registry))))
        before-subscription (get-in before [::subscriptions subscription-key])
        after-subscription (get-in after [::subscriptions subscription-key])
        active-before (::active-render before-subscription)
        promoted (::active-render after-subscription)
        completed? (and (= subscription-id (::subscription-id after-subscription))
                        (= render-id (::render-id active-before))
                        (nil? promoted))
        event (::event rendered)
        emit? (and completed?
                   event
                   (or (::render-full? active-before)
                       (not= (::last-event before-subscription)
                             (::last-event after-subscription))))
        consumer-view-ids (::consumer-view-ids after-subscription)
        _ (when (and completed?
                     (contains? rendered ::catalog)
                     (::view-id rendered))
            (reconcile-view-catalog!
              {::view-id (::view-id rendered)
               ::catalog (::catalog rendered)}))
        connections
        (when emit?
          (keep #(get-in @!feeds [::views %]) consumer-view-ids))]
    (doseq [conn connections]
      (push-event! conn event))
    (when (and promoted
               (not= render-id (::render-id promoted)))
      (start-render! subscription-key subscription-id))))

(defn- start-render! [subscription-key subscription-id]
  (let [subscription (get-in @!feeds [::subscriptions subscription-key])
        request (some-> (::active-render subscription)
                        (assoc ::render-started-at (.now js/performance)))]
    (when (and (= subscription-id (::subscription-id subscription)) request)
      (swap! !feeds
             (fn [registry]
               (if (= (::render-id request)
                      (get-in registry [::subscriptions subscription-key
                                        ::active-render ::render-id]))
                 (assoc-in registry
                           [::subscriptions subscription-key ::active-render]
                           request)
                 registry)))
      (let [rendered (render-request-result subscription request)]
        (if (promise-like? rendered)
          (.then rendered
                 #(finish-render! subscription-key subscription-id
                                  (::render-id request) %)
                 #(finish-render! subscription-key subscription-id
                                  (::render-id request)
                                  (render-error-patch %)))
          (finish-render! subscription-key subscription-id
                          (::render-id request) rendered))))))

(defn- enqueue-render! [subscription-key request]
  (let [subscription (get-in @!feeds [::subscriptions subscription-key])
        subscription-id (::subscription-id subscription)
        render-number (inc (or (::render-number subscription) 0))
        request (assoc request
                       ::render-id (random-uuid)
                       ::render-number render-number)
        [_ after]
        (swap-vals!
         !feeds
         (fn [registry]
           (let [path [::subscriptions subscription-key]
                 current (get-in registry path)]
             (if (= subscription-id (::subscription-id current))
               (assoc-in
                registry path
                (if (::active-render current)
                  (-> current
                      (assoc ::render-number render-number)
                      (assoc ::pending-render
                             (pending-render-request
                              current (::active-render current)
                              (::pending-render current) request)))
                  (assoc current
                         ::render-number render-number
                         ::active-render request)))
               registry))))
        active (get-in after [::subscriptions subscription-key ::active-render])]
    (when (= (::render-id request) (::render-id active))
      (start-render! subscription-key subscription-id))))

(defn- push-full!
  "Write one shared full view, or join the normalized render already in flight."
  [conn]
  (let [subscription-key (::subscription-key conn)
         subscription (get-in @!feeds [::subscriptions subscription-key])
         event (::full-event subscription)
         full-rendering?
         (or (::render-full? (::active-render subscription))
             (::render-full? (::pending-render subscription)))]
     (cond
       event (push-event! conn event)
       full-rendering? nil
       :else (enqueue-render! subscription-key {::render-full? true}))))

(defn- subscription-key-for
  "Normalized subscription coordinate for one feed or socket view."
  [feed]
  [(:seon.web.feed/key feed)
   (active-fingerprint (::active-tokens feed))])

(defn- subscription-from-feed
  "Create one normalized render authority from the first consumer's plan."
  [subscription-key feed]
  (cond->
   {::subscription-id (random-uuid)
    ::subscription-key subscription-key
    ::consumer-view-ids #{}
    ::live? (:seon.web.feed/live? feed)
    ::render-number 0
    ::full-event-committed? false
    :seon.web.feed/render-full (:seon.web.feed/render-full feed)
    ::render-change (:seon.web.feed/render-change feed)}
    (contains? feed ::dependencies)
    (assoc ::dependencies (::dependencies feed))))

(defn- socket-consumer
  "Remove subscription authority from a socket-owning view descriptor."
  [feed subscription-key]
  (-> feed
      (assoc ::subscription-key subscription-key)
      (dissoc :seon.web.feed/key
              :seon.web.feed/live?
              :seon.web.feed/render-full
              :seon.web.feed/render-change
              ::dependencies)))

(defn- detach-subscription
  "Remove one socket consumer from only its normalized subscription."
  [registry view-id]
  (if-let [view (get-in registry [::views view-id])]
    (let [subscription-key (::subscription-key view)
          without-view (-> registry
                           (update ::views dissoc view-id)
                           (update-in [::subscriptions subscription-key
                                       ::consumer-view-ids]
                                      disj view-id))]
      (if (seq (get-in without-view [::subscriptions subscription-key
                                     ::consumer-view-ids]))
        without-view
        (update without-view ::subscriptions dissoc subscription-key)))
    registry))

(defn- detach-view
  [registry view-id]
  (detach-subscription registry view-id))

(defn- attach-feed
  "Attach a socket consumer to exactly one normalized subscription authority."
  [registry feed]
  (let [view-id (::view-id feed)
        subscription-key (subscription-key-for feed)
        previous (get-in registry [::views view-id])
        same-subscription? (= subscription-key (::subscription-key previous))
        detached (if (or (nil? previous) same-subscription?)
                   registry
                   (detach-subscription registry view-id))
        subscription (or (get-in detached [::subscriptions subscription-key])
                         (subscription-from-feed subscription-key feed))
        consumer (socket-consumer feed subscription-key)]
    (-> detached
        (assoc-in [::views view-id] consumer)
        (assoc-in [::subscriptions subscription-key]
                  (update subscription ::consumer-view-ids conj view-id)))))

(defn- rebind-view
  "Move one updated view to the subscription matching its active units."
  [registry view-id updated-view]
  (let [old-key (::subscription-key updated-view)
        old-subscription (get-in registry [::subscriptions old-key])
        feed-key (first old-key)
        next-key [feed-key (active-fingerprint (::active-tokens updated-view))]]
    (if (= old-key next-key)
      (assoc-in registry [::views view-id] updated-view)
      (let [detached (detach-subscription registry view-id)
            subscription (or (get-in detached [::subscriptions next-key])
                             (-> old-subscription
                                 (assoc ::subscription-id (random-uuid)
                                        ::subscription-key next-key
                                        ::render-number 0
                                        ::full-event-committed? false
                                        ::consumer-view-ids #{})
                                 (dissoc ::full-event
                                         ::active-render
                                         ::pending-render)))
            consumer (assoc updated-view ::subscription-key next-key)]
        (-> detached
            (assoc-in [::views view-id] consumer)
            (assoc-in [::subscriptions next-key]
                      (update subscription ::consumer-view-ids conj view-id)))))))

(defn- update-owned-view
  "Update and rebind a view only while the expected socket still owns it."
  [registry view-id feed-id update-view]
  (if-let [view (get-in registry [::views view-id])]
    (if (= feed-id (:seon.web.feed/id view))
      (rebind-view registry view-id (update-view view))
      registry)
    registry))

(declare commit-registry!)

(defn- emitted-elements-for-subscription
  "Distinct managed elements emitted for one subscription's consumers."
  [emitted-by-view consumer-view-ids]
  (reduce (fn [elements-by-token view-id]
            (merge elements-by-token (get emitted-by-view view-id {})))
          {}
          consumer-view-ids))

(defn- broadcast!
  "Render each normalized live subscription once, then fan out its patch."
  [change]
  (doseq [[subscription-key subscription] (::subscriptions @!feeds)
          :when (and (::live? subscription)
                     (subscription-affected? subscription change))]
    (enqueue-render! subscription-key
                     {::render-full? false
                      ::change change
                      ::serialized-elements-by-token {}})))

;; ============================================================
;; Coalescing — one lifecycle-owned state collapses a tx burst into one
;; morph. Running eval-record commits can retain evidence without a timer.
;; The first render-worthy enqueue fixes a maximum deadline; later ordinary
;; commits may move the trailing edge toward it, but never beyond it.
;; ============================================================

(def ^:private normal-settle-ms 16)
(def ^:private structural-settle-ms 300)
(def ^:private maximum-coalesce-ms 500)

(def ^:private empty-coalescer {})

(defonce ^{:private true
           :doc "One pending Datastar broadcast and its optional timer."}
  !coalescer (atom empty-coalescer))

(defn- monotonic-ms [] (.now js/performance))

(defn- set-broadcast-timeout! [callback delay-ms]
  (js/setTimeout callback delay-ms))

(defn- clear-broadcast-timeout! [timer]
  (js/clearTimeout timer))

(defn- change-attrs [change]
  (or (:seon.db/changed-attrs change) #{}))

(defn- event-change [event]
  (let [datoms (vec (or (::protocol/datoms event) []))
        attrs (into #{} (map :seon.db/a) datoms)]
    {:seon.db/coordinate (::protocol/coordinate event)
     :seon.db/changed-attrs attrs
     :seon.db/datoms datoms
     :seon.web.broadcast/structural?
     (agent-view/structural-change? attrs)}))

(defn- merge-change [pending change]
  {:seon.db/coordinate (or (:seon.db/coordinate change)
                            (:seon.db/coordinate pending))
   :seon.db/changed-attrs
   (set/union (or (:seon.db/changed-attrs pending) #{})
              (or (:seon.db/changed-attrs change) #{}))
   :seon.db/datoms
   (into (vec (or (:seon.db/datoms pending) []))
         (or (:seon.db/datoms change) []))
   :seon.web.broadcast/structural?
   (or (:seon.web.broadcast/structural? pending)
       (:seon.web.broadcast/structural? change))})

(defn- broadcast-due-at
  [enqueued-at now structural?]
  (min (+ enqueued-at maximum-coalesce-ms)
       (+ now (if structural? structural-settle-ms normal-settle-ms))))

(declare drain-coalescer!)

(defn- schedule-broadcast! [change]
  (let [now (monotonic-ms)
        current @!coalescer
        pending (merge-change (::pending-change current) change)]
    (let [enqueued-at (or (::enqueued-at current) now)
            due-at (broadcast-due-at
                     enqueued-at now
                     (:seon.web.broadcast/structural? pending))]
        (if (and (::timer current) (= due-at (::due-at current)))
          ;; Once the maximum deadline is reached, keep its already-owned timer.
          ;; Clearing and recreating a zero-delay timer here would let continuous
          ;; transaction callbacks starve the render indefinitely.
          (reset! !coalescer
                  (assoc current
                         ::pending-change pending
                         ::enqueued-at enqueued-at))
          (let [timer-token (random-uuid)
                delay-ms (max 0 (- due-at now))
                timer (set-broadcast-timeout!
                        #(drain-coalescer! timer-token)
                        delay-ms)]
            ;; Publish pending data + timer ownership as one value. Node cannot
            ;; run the new timeout until this stack returns; a queued old
            ;; callback is fenced by timer-token and becomes inert after reset.
            (reset! !coalescer
                    {::pending-change pending
                     ::timer timer
                     ::timer-token timer-token
                     ::enqueued-at enqueued-at
                     ::due-at due-at})
            (when-let [old-timer (::timer current)]
              (clear-broadcast-timeout! old-timer)))))))

(defn- drain-coalescer! [timer-token]
  (let [[before _]
        (swap-vals! !coalescer
                    (fn [state]
                      (if (= timer-token (::timer-token state))
                        empty-coalescer
                        state)))
        ready (when (= timer-token (::timer-token before))
                (::pending-change before))]
    (when ready (broadcast! ready))))

(defn- clear-coalescer! []
  (let [[before _] (reset-vals! !coalescer empty-coalescer)]
    (when-let [timer (::timer before)]
      (clear-broadcast-timeout! timer))))

;; ============================================================
;; Lifecycle — db/listen! IS the refresh signal.
;; ============================================================

(defn- advance-full-events
  "Invalidate affected renders and advance unchanged renders to the commit."
  [registry change]
  (update registry ::subscriptions
          (fn [subscriptions]
            (into {}
                  (map (fn [[subscription-key subscription]]
                         [subscription-key
                          (cond
                            (not (::live? subscription)) subscription
                            (subscription-affected? subscription change)
                            (dissoc subscription
                                    ::full-event
                                    ::full-event-coordinate)
                            :else
                            (cond-> subscription
                              (::full-event subscription)
                              (assoc ::full-event-coordinate
                                     (:seon.db/coordinate change))))]))
                  subscriptions))))

(defn- on-tx [event]
  (let [change (event-change event)]
    ;; Invalidate only affected subscriptions before their coalesced morph.
    ;; Unaffected complete bytes remain valid and advance to this coordinate.
    (swap! !feeds advance-full-events change)
    (when (some (fn [[_ subscription]]
                  (and (::live? subscription)
                       (subscription-affected? subscription change)))
                (::subscriptions @!feeds))
      (schedule-broadcast! change))))

(defn install!
  "Install the view tx-listener. Idempotent — same key replaces."
  []
  (let [result (db/listen! {:seon.db/key ::views :seon.db/handler on-tx})]
    (swap! !feeds assoc ::listener-installed? true)
    result))

(defn- release-runtime!
  "Release listener and timer ownership from one prior runtime state."
  [listener-installed?]
  (try
    (when listener-installed?
      (db/unlisten! {:seon.db/key ::views}))
    (finally
      (clear-coalescer!)
      (stop-heartbeat!))))

(defn uninstall!
  "Remove the view listener, pending broadcast, and shared heartbeat."
  []
  (let [[before _]
        (swap-vals! !feeds assoc ::listener-installed? false)
        listener-installed?
        (or (true? (::listener-installed? before))
            (and (nil? (::listener-installed? before))
                 (seq (::views before))))]
    (release-runtime! listener-installed?)))

(defn ^:dev/before-load before-reload
  "Uninstall the view tx-listener before a hot reload."
  []
  (try (uninstall!) (catch :default _ nil)))

(defn ^:dev/after-load after-reload
  "Restore listener and heartbeat only when a feed survived hot reload."
  []
  (try
    (when (seq (::views @!feeds))
      (install!)
      (ensure-heartbeat!))
    (catch :default _ nil)))

;; ============================================================
;; HTTP handlers — called from seon.web.serve when route? matched.
;; ============================================================

(defn- shim-html
  "The datastar app-shim page as a raw HTML string, BRAND-AWARE: the
   <head> routes through the seon.web.brand seams — the brand <title> via
   `page-title`, `data-theme` from the brand row, and the optional
   SEON_BRAND_CSS inlined AFTER output.css — so a downstream deploy's
   branding reaches the view page users actually navigate to (not just
   the web UI). Absent brand row + env → the shipped seon defaults.

   The shim itself: load datastar.js, open the long-lived feed via a
   `data-init` on a hidden SIBLING opener div (OUTSIDE the morph target —
   a data-init on `#app-view` itself is stripped by the first whole-element
   morph, killing the stream), and present an empty `<main id=\"app-view\">`
   for the feed's
   first morph to fill. `title-suffix` is the brand-name suffix (\"agents\",
   \"agent <id>\"); `feed-url` the data-init SSE URL — or `nil` to OMIT
   data-init, when the page's `extra-body` opens the feed itself (the
   /agent/{id} time-travel bar owns its feed via a single `data-effect` so it
   can re-open at a past `t`; a data-init here would be a second, competing
   stream). `extra-body` is a raw HTML string spliced in as a SIBLING of
   `<main id=\"app-view\">` (a human input — chat / new-agent / time-travel — that
   must live OUTSIDE the morphed `#app-view` so the feed's whole-element morph
   never clobbers its focus/value). Raw string (not hiccup) so the data-init
   single quotes stay literal and the doctype leads."
  [title-suffix body-class feed-url extra-body]
  (let [b (brand/info nil)]
    (str "<!doctype html>\n"
         "<html lang=\"en\" data-theme=\"" (::brand/theme b) "\"><head><meta charset=\"utf-8\">\n"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
         "<title>" (brand/page-title b title-suffix) "</title>\n"
         "<link rel=\"stylesheet\" href=\"/css/output.css\">\n"
         ;; The `.hljs-*` palette for SERVER-side Clojure source cards
         ;; (seon.ui.clojure/clj->hiccup, via seon.render/block) — the eval
         ;; cards on the transcript/canvas highlight without any client JS.
         "<link rel=\"stylesheet\" href=\"/css/highlight-github-dark.css\">\n"
         ;; Phosphor Terminal control styling — the native-blue range thumb/
         ;; track (the time-travel scrubber) + the default blue focus ring on
         ;; text inputs (chat / new-agent bars) are the two loudest breaks on
         ;; an otherwise warm-black/amber page. `accent-color` tints the range
         ;; amber cross-browser; the focus ring becomes an amber 1px ring.
         ;; Placed BEFORE the optional brand stylesheet so a downstream brand
         ;; still wins the cascade.
         "<style>"
         "input[type=range]{accent-color:#f0b429;}"
         "input[type=text]:focus,input[type=text]:focus-visible"
         "{outline:none;border-color:#b45309;box-shadow:0 0 0 1px #b45309;}"
         "</style>\n"
         (brand/css-style-tag)
         "<script type=\"module\" src=\"/js/datastar.js\"></script>\n"
         "</head>\n"
         "<body class=\"" body-class "\">\n"
         ;; `retryMaxCount: Infinity` keeps the long-lived stream reconnecting
         ;; forever (survives a pod/database-server restart, a network blip, a
         ;; laptop sleep→wake) with datastar's backoff; `openWhenHidden: false`
         ;; drops the stream while the tab is hidden and REOPENS on return —
         ;; cheap, and reopen = a fresh full `view=f(db)` repaint (no since-t
         ;; replay needed in this model). Both verified-supported in our shipped
         ;; datastar.js RC.7.
         "<main id=\"app-view\">loading…</main>\n"
         ;; The feed OPENER is a SIBLING of `#app-view`, never `#app-view` itself:
         ;; the feed's whole-element morph replaces `#app-view`'s attributes with
         ;; the pushed element's (which carries no `data-init`), so an opener
         ;; ON the morph target is stripped by its own first paint — datastar
         ;; cancels the stream ~100ms after open and the page goes dead
         ;; (the 2026-07-11 '/agents never updates' bug). Same
         ;; outside-the-morph rule as the human input bars.
         (when feed-url
           (str "<div id=\"app-feed-opener\" style=\"display:none\""
                " data-init=\"@get('" feed-url
                "', {retryMaxCount: Infinity, openWhenHidden: false})\"></div>\n"))
         extra-body
         "</body></html>")))

;; ============================================================
;; Human input bars — the surfaces the view page needs so a human can
;; OPERATE (not just observe). Each lives OUTSIDE `<main id=\"app-view\">` (a
;; SIBLING in <body>) so the feed's whole-`#app-view` morph never clobbers the
;; input's focus/value. They reuse the already-routed, same-origin-gated
;; POST endpoints (`/chat`, `/agents`) — no Core change. A fixed bottom
;; bar + an inline-style spacer reserves scroll room so the last card is never
;; hidden behind the bar. Only output.css-present utilities are used (the
;; spacer height is an inline style, not a Tailwind class).
;; ============================================================

(defn- chat-form-html
  "P0 — the human→agent chat input for `/agent/{id}`, as a raw HTML string.

   A static `<form>` (outside the morph) that submits a DATASTAR FORM-MODE
   POST to the existing `/chat?agent=<id>`: `data-on:submit` (datastar
   auto-prevents the native submit on a `<form>`) runs
   `@post(url,{contentType:'form'})`, which reads THIS form's named fields and
   posts them `application/x-www-form-urlencoded` — exactly the `text=` shape
   the `/chat` handler parses (the same wire contract the legacy chat bar
   used). `data-bind=\"text\"` keeps the input value in a datastar signal so a
   trailing `$text=''` clears it after send; `required` blocks a blank send
   client-side. The agent's reply needs NO handling here: it transacts and the
   broadcast feed re-renders the `:transcript` surface. A 204 reply closes the
   datastar stream cleanly (no morph from this POST). `id` is pre-validated by
   `safe-id?`, so it is injection-safe inside the single-quoted `@post('…')`."
  [id]
  (html/->string
    [:form {:id                     "app-chat"
              (keyword "data-on:submit") (str "@post('/chat?agent=" id
                                              "', {contentType:'form'}); $text=''")
              :class "shrink-0 flex items-center gap-2 border-t border-base-800 bg-base-900 px-3 py-2"}
       [:input {:type         "text"
                :name         "text"
                :data-bind    "text"
                :required     true
                :autocomplete "off"
                :autofocus    true
                :placeholder  (str "message agent " id " …")
                :class "flex-1 bg-base-950 border border-base-800 rounded px-2 py-1 text-text-100 text-xs font-mono"}]
       [:button {:type  "submit"
                 :class "bg-base-800 hover:bg-base-700 text-signal border border-base-700 px-3 py-1 rounded text-xs font-mono"}
        "send"]]))

(defn- agent-feed-opener-html
  "The hidden live-feed owner for an agent page, outside the morph target.

   Time-travel remains a server capability through `?t=`, but its unfinished
   controls are intentionally absent from the normal agent view."
  [id]
  (let [feed (str "/agent/" id "/feed")
        opts "{retryMaxCount: Infinity, openWhenHidden: false}"]
    ;; Keep the sole live-feed owner outside #app-view so morphs cannot remove
    ;; it. The unfinished time-travel controls are intentionally hidden from
    ;; the normal agent view; debug can expose that capability deliberately.
    (html/->string
      [:div {:id "app-agent-feed"
             :style "display:none"
             :data-init (str "@get('" feed "', " opts ")")}])))

(def ^:private safe-view-id-re #"[A-Za-z0-9._~-]{1,128}")

(defn- safe-view-id?
  "True when a client view id is safe as an ephemeral registry key."
  [view-id]
  (boolean (and view-id (re-matches safe-view-id-re view-id))))

(defn- prepare-feed
  "Attach inherited view state and one fresh socket to a feed descriptor."
  [feed previous view-id feed-id gz res]
  (let [same-feed? (= (:seon.web.feed/key feed)
                      (first (::subscription-key previous)))
        catalog (if (contains? feed ::catalog)
                  (::catalog feed)
                  (if same-feed? (or (::catalog previous) []) []))
        available (into #{} (map ::token) catalog)
        demanded-tokens
        (set/intersection
          available
          (if (contains? feed ::demanded-tokens)
            (::demanded-tokens feed)
            (if same-feed? (or (::demanded-tokens previous) #{}) #{})))
        requested-active (if same-feed?
                           (::active-tokens previous)
                           (or (::active-tokens feed) #{}))
        active-tokens (into demanded-tokens
                            (set/intersection requested-active available))]
    (assoc feed
           ::view-id view-id
           ::catalog catalog
           ::active-tokens active-tokens
           ::demanded-tokens demanded-tokens
           :seon.web.feed/id feed-id
           :seon.web.feed/gzip gz
           :seon.web.feed/response res
           :seon.web.feed/pending-event (atom nil)
           :seon.web.feed/draining? (atom false)
           :seon.web.feed/opened-at (js/Date.))))

(defn- replace-feed!
  "Make `conn` the sole socket owner for its view and return the prior owner."
  [conn]
  (let [view-id (::view-id conn)
        previous (get-in @!feeds [::views view-id])]
    (swap! !feeds attach-feed conn)
    previous))

(defn- release-feed!
  "Release a view only when `feed-id` still owns its current socket."
  [view-id feed-id]
  (if (= feed-id (:seon.web.feed/id (get-in @!feeds [::views view-id])))
    (do (swap! !feeds detach-view view-id) true)
    false))

(defn- close-feed-socket!
  "End one gzip socket without changing view ownership."
  [conn]
  (when-let [gz (:seon.web.feed/gzip conn)]
    (try (.end ^js gz) (catch :default _ nil))))

(defn close-all-feeds!
  "Close every Datastar feed and release its complete runtime state."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (let [[before _] (reset-vals! !feeds empty-feed-registry)
        connections (vals (::views before))
        listener-installed? (true? (::listener-installed? before))
        runtime-owned? (or listener-installed?
                           (seq connections)
                           (seq @!coalescer)
                           (some? @!heartbeat-timer))]
    (doseq [conn connections]
      (close-feed-socket! conn))
    (when runtime-owned?
      (release-runtime! listener-installed?))
    nil))

(defn- open-feed!
  "Open a long-lived gzip SSE stream from one derived-view descriptor."
  [^js req ^js res feed]
  ;; Datahike owns listener membership. The stable key makes this idempotent
  ;; and replaces a pre-reload callback with the current definition.
  (install!)
  (.writeHead
    res 200
    (clj->js
      (cond-> {"Content-Type"      "text/event-stream; charset=utf-8"
               "Content-Encoding"  "gzip"
               "Cache-Control"     "no-store"
               "Connection"        "keep-alive"
               "X-Accel-Buffering" "no"}
        (:seon.web.feed/coordinate feed)
        (assoc "Seon-Database-Coordinate"
               (pr-str (:seon.web.feed/coordinate feed))))))
  (let [gz (.createGzip zlib)
        feed-id (random-uuid)
        supplied-view-id (::view-id feed)
        view-id (if (safe-view-id? supplied-view-id)
                  supplied-view-id
                  (str (random-uuid)))
        previous (get-in @!feeds [::views view-id])
        conn (prepare-feed feed previous view-id feed-id gz res)
        closed? (atom false)
        close!
        (fn []
          (when (compare-and-set! closed? false true)
            (let [released? (release-feed! view-id feed-id)]
              (close-feed-socket! conn)
              (when (and released? (empty? (::views @!feeds)))
                (uninstall!))
              (log/info-console! "seon.web.datastar" "FEED CLOSE"
                                 {:seon.web.feed/id (str feed-id)
                                  :seon.web.datastar/view-id view-id
                                  :seon.web.feed/released? released?
                                  :seon.web.feed/count
                                  (count (::views @!feeds))}))))]
    (.on gz "error"
         (fn [e]
           (log/error-console! "seon.web.datastar" "gz error" e)
           (close!)))
    (.on res "error"
         (fn [e]
           (log/error-console! "seon.web.datastar" "res error" e)
           (close!)))
    (.pipe gz res)
    (when-let [replaced (replace-feed! conn)]
      (close-feed-socket! replaced))
    (push-full! (get-in @!feeds [::views view-id]))
    (ensure-heartbeat!)
    (log/info-console! "seon.web.datastar" "FEED OPEN"
                       {:seon.web.feed/id (str feed-id)
                        :seon.web.datastar/view-id view-id
                        :seon.web.feed/count (count (::views @!feeds))})
    ;; `ServerResponse.close` is the raw Node equivalent of a stream abort. It
    ;; follows the response/socket lifetime; `IncomingMessage.close` changed
    ;; semantics across Node releases and is not the ownership authority.
    (.once res "close" close!)
    (.once req "aborted" close!)
    view-id))

(defn- query-value
  "Decoded query value named `parameter`, or nil when absent or malformed."
  [query-string parameter]
  (try
    (.get (js/URLSearchParams. (or query-string "")) parameter)
    (catch :default _ nil)))

(defn- requested-view-id
  "Safe `view` query value from one node request, or nil."
  [^js req]
  (try
    (let [url (or (.-url req) "")
          qidx (str/index-of url "?")
          view-id (when qidx (query-value (subs url (inc qidx)) "view"))]
      (when (safe-view-id? view-id) view-id))
    (catch :default _ nil)))

(defn request-view-id
  "The validated ephemeral `view` query value from one Ring request."
  {:malli/schema [:=> [:cat ::ring-request] ::optional-view-id]}
  [r]
  (requested-view-id (:seon.http/node-req r)))

(defn- descriptor-by-token
  "Trusted catalog descriptor for `token`, or nil."
  [catalog token]
  (some #(when (= token (::token %)) %) catalog))

(defn active-unit
  "Materialize one descriptor behind its complete stable unit wrapper."
  {:malli/schema [:=> [:catn [::descriptor ::descriptor]]
                  :seon.render.canvas/hiccup]}
  [{token ::token dom-id ::dom-id producer ::producer}]
  [:div {:id dom-id
         :data-seon-unit token
         :data-seon-unit-active "true"}
   (producer)])

(defn unit-element
  "Render one descriptor as either an inactive stub or its active content."
  {:malli/schema [:=> [:catn [::descriptor ::descriptor]
                             [::active? ::active?]]
                  :seon.render.canvas/hiccup]}
  [descriptor active?]
  (if active? (active-unit descriptor) (inactive-stub descriptor)))

(defn unit-element-html-in-view
  "Serialize a unit directly; no server-side rendered-output cache exists."
  {:malli/schema [:=> [:catn [::view-id ::view-id]
                             [::descriptor ::descriptor]
                             [::active? ::active?]]
                  :string]}
  [_view-id descriptor active?]
  (html/->string (unit-element descriptor active?)))

(defn- commit-registry!
  "Commit one synchronous pure registry transition without retrying effects."
  [transition]
  (let [[registry result] (transition @!feeds)]
    ;; Node executes this synchronous read/derive/reset extent without an
    ;; interleaving task. Unlike `swap!`, reset never retries a producer.
    (reset! !feeds registry)
    result))

(defn- write-unit-response!
  "Finish one unit-control response with explicit status and content type."
  [^js res status content-type body]
  (.writeHead res status #js {"Content-Type" content-type
                              "Cache-Control" "no-store"})
  (.end res body)
  nil)

(defn handle-view-unit!
  "Activate or deactivate one trusted unit in an open ephemeral view."
  [r]
  (let [res (:seon.http/node-res r)
        view-id (query-value (:query-string r) "view")
        token (query-value (:query-string r) "unit")
        active? (case (query-value (:query-string r) "active")
                  "1" true "0" false ::invalid-active)
        view (get-in @!feeds [::views view-id])
        target (descriptor-by-token (::catalog view) token)]
    (cond
      (or (not (safe-view-id? view-id)) (= ::invalid-active active?))
      (write-unit-response! res 400 "text/plain; charset=utf-8" "invalid unit request")
      (nil? view)
      (write-unit-response! res 410 "text/plain; charset=utf-8" "view is closed")
      (nil? target)
      (write-unit-response! res 404 "text/plain; charset=utf-8" "unknown unit")
      (and (not active?) (contains? (::demanded-tokens view) token))
      (write-unit-response! res 409 "text/plain; charset=utf-8" "unit is required by this view")
      :else
      (let [transition (transition-active-set
                        {::catalog (::catalog view)
                         ::active-tokens (::active-tokens view)
                         ::token token ::active? active?})
            changed (into [(unit-element target active?)]
                          (map inactive-stub)
                          (filter #(contains? (::deactivated-tokens transition) (::token %))
                                  (::catalog view)))]
        (swap! !feeds assoc-in [::views view-id ::active-tokens]
               (::active-tokens transition))
        (write-unit-response! res 200 "text/event-stream; charset=utf-8"
                              (patch-rendered-elements changed []))))))

(defn reconcile-view-catalog!
  "Replace one view catalog and retain only available active units."
  [{view-id ::view-id catalog ::catalog}]
  (if-let [view (get-in @!feeds [::views view-id])]
    (let [available (into #{} (map ::token) catalog)
          retained (set/intersection (::active-tokens view) available)]
      (swap! !feeds assoc-in [::views view-id ::catalog] catalog)
      (swap! !feeds assoc-in [::views view-id ::active-tokens] retained)
      retained)
    #{}))

(defn view-active-tokens
  "The current ephemeral active-unit set for one open view."
  {:malli/schema [:=> [:catn [::view-id ::view-id]] ::active-tokens]}
  [view-id]
  (or (::active-tokens (get-in @!feeds [::views view-id])) #{}))

(defn new-view-id
  "Mint one ephemeral browser-view identity."
  {:malli/schema [:=> [:cat] ::view-id]}
  []
  (str (random-uuid)))

(defn open-view-feed!
  "Open one derived view on the shared gzip Datastar feed registry."
  {:malli/schema [:=> [:catn [::ring-request ::ring-request]
                             [::feed-definition ::feed-definition]]
                  ::view-id]}
  [r feed]
  (open-feed! (:seon.http/node-req r) (:seon.http/node-res r) feed))

;; ============================================================
;; Per-agent view (/agent/{id}) — the shim page + the feed bound to
;; that agent's `agent-view`. The router calls these public entries
;; with the `{id}` path-param.
;; ============================================================

(def ^:private safe-id-re
  ;; Limit current and preserved agent ids to URL/HTML-safe alphanumerics and
  ;; separators at this boundary (including the reserved `root`). Anything
  ;; else 404s before the path value reaches the shim page's HTML.
  #"[A-Za-z0-9._:-]+")

(defn- safe-id? [id] (boolean (and id (re-matches safe-id-re id))))

(defn- ^:async agent-exists? [id]
  (boolean
   (seq (await (db/query {:seon.db/query
                          '[:find ?e :in $ ?id
                            :where [?e :seon.agent/id ?id]]
                          :seon.db/args [id]})))))

(def ^:private canonical-uuid-re
  #"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(def ^:private branch-param-re #"[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)?")

(def ^:private historical-query-keys
  ["database-id" "branch" "commit-id" "t"])

(defn- selector-error
  [message supplied]
  {:seon.error/message message
   :seon.error/kind :invalid-database-coordinate
   :seon.error/data {:seon.web.historical/supplied supplied
                     :seon.web.historical/required historical-query-keys}})

(defn- parse-historical-coordinate
  "Parse one all-or-none historical selector from a node request URL.

   No coordinate fields means the live feed. Any partial, blank, malformed,
   or noncanonical selector is an error value; history never falls back live."
  [^js req]
  (try
    (let [url  (or (.-url ^js req) "")
          qidx (str/index-of url "?")]
      (when qidx
        (let [params (js/URLSearchParams. (subs url (inc qidx)))
              supplied (into {}
                             (map (fn [key] [key (.get params key)]))
                             historical-query-keys)
              present (filterv #(.has params %) historical-query-keys)]
          (cond
            (empty? present) nil

            (not= (count historical-query-keys) (count present))
            (selector-error "historical feed coordinate is incomplete" supplied)

            :else
            (let [database-id (get supplied "database-id")
                  branch (get supplied "branch")
                  commit-id (get supplied "commit-id")
                  t-value (get supplied "t")]
              (if-not (and (re-matches canonical-uuid-re database-id)
                           (re-matches branch-param-re branch)
                           (re-matches canonical-uuid-re commit-id)
                           (re-matches #"[0-9]+" t-value))
                (selector-error "historical feed coordinate is malformed" supplied)
                (let [t-number (js/Number t-value)
                      point {::db.coordinate/database-id (uuid database-id)
                             ::db.coordinate/branch (keyword branch)
                             ::db.coordinate/commit-id (uuid commit-id)
                             ::db.coordinate/t t-number}]
                  (if (and (js/Number.isSafeInteger t-number)
                           (schema/valid-candidate-value?
                             ::db.coordinate/coordinate point))
                    point
                    (selector-error
                      "historical feed coordinate is outside the supported value domain"
                      supplied)))))))))
    (catch :default e
      (selector-error "historical feed coordinate could not be parsed"
                      {:seon.error/message (.-message e)}))))

(defn- write-historical-error!
  [^js res error]
  (.writeHead res 422 #js {"Content-Type" "application/edn; charset=utf-8"
                           "Cache-Control" "no-store"})
  (.end res (pr-str error))
  nil)

(defn- agent-page-html
  "The per-agent (/agent/{id}) view shim page (brand-aware head — see
   [[shim-html]]). `id` is pre-validated by `safe-id?`.

   The hidden [[agent-feed-opener-html]] owns the live stream outside the morph
   target; chat is a normal-flow bottom dock."
  [id]
  (shim-html (str "agent " id)
             "h-screen overflow-hidden flex flex-col bg-base-950 text-text-200 font-mono p-3"
             nil
             (str (chat-form-html id)
                  (agent-feed-opener-html id))))

(defn- write-agent-page!
  "Write the shared agent shim for already-validated `id`."
  [^js res id]
  (.writeHead res 200 #js {"Content-Type"  "text/html; charset=utf-8"
                           "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res (agent-page-html id)))

(defn serve-agent-page!
  "Serve the per-agent view shim page (the seeded :seon.route/agent handler).
   A Ring handler: takes the Ring request `r`, self-extracts the node res + the
   `{id}` path-param. Invalid ids 404. Public — db->routes resolves its symbol
   via eval/lookup-value at request time."
  [r]
  (let [^js res (:seon.http/node-res r)
        id      (get-in r [:path-params :id])]
    (cond
      (= id "root")
      (do (.writeHead res 302 #js {"Location" "/" "Cache-Control" "no-store"})
          (.end res ""))

      ;; A malformed id (injection attempt, junk path segment) → home.
      (not (safe-id? id))
      (do (.writeHead res 302 #js {"Location" "/" "Cache-Control" "no-store"})
          (.end res ""))
      ;; #28 — a well-formed but unknown agent (stale bookmark, reset database,
      ;; typo) gracefully redirects HOME rather than serving an empty view or
      ;; a raw 404.
      :else (write-agent-page! res id))))

(defn serve-root!
  "Serve `/` — root's view (the all-agents dashboard).

   `root = /`
   `/` is the root agent's view, so it reuses the shared agent
   shim page bound to the literal id \"root\". The page's feed effect opens
   `/agent/root/feed` (already seeded), whose `agent-view` renders root's
   canvas = `seon.render.system/system-view` (root's seeded canvas content).
   One mechanism and no root-special feed; `/agent/root` canonicalizes to `/`.
   Public — db->routes resolves its symbol at request time."
  [r]
  (write-agent-page! (:seon.http/node-res r) "root"))

(def agent-view-function 'seon.execution.runtime/render-agent-view!)

(defn- ^:async render-agent-view-at! [id coordinate]
  (let [message (await (execution.host/invoke-compiled!
                       coordinate id agent-view-function [{:seon.agent/id id}]))]
    (if (= execution/result-message (::execution/message message))
      (agent-view/render-agent-view (::execution/result message))
      [:main {:id "app-view" :class "text-error text-xs font-mono"}
       (str "render error: "
            (or (get-in message [::execution/error :seon.error/message])
                "execution child failed"))])))

(defn- live-agent-feed-definition [id view-id]
  (cond->
   {:seon.web.feed/key [:seon.web.feed/agent id]
    :seon.web.feed/live? true
    ;; The current page acquisition includes a global datom count. Until that
    ;; value becomes its own independently derived stream, every commit can
    ;; change this projection; claiming narrower surface attrs would be wrong.
    ::dependencies :all
    :seon.web.feed/render-full
    (fn []
      (-> (db/head-coordinate)
          (.then (fn [coordinate]
                   (-> (render-agent-view-at! id coordinate)
                       (.then (fn [element]
                                {::element element
                                 :seon.db/coordinate coordinate})))))))
    :seon.web.feed/render-change
    (fn [_subscription change]
      (-> (render-agent-view-at! id (:seon.db/coordinate change))
          (.then (fn [element]
                   {::elements [element]
                    ::render-full? true}))))}
   view-id (assoc ::view-id view-id)))

(defn ^:async open-agent-feed!
  "Open the per-agent view gzip feed.

   The seeded :seon.route/agent-feed handler. A Ring handler: takes the
   Ring request `r`, self-extracts node-req/node-res + the `{id}` path-param.
   Lazily installs the tx-listener (idempotent). Invalid or stale ids 404. Public —
   db->routes resolves its symbol.

   A historical request supplies all four `database-id`, `branch`, `commit-id`,
   and `t` query fields. That ordinary coordinate is passed to the compiled
   child and used as the frozen subscription key. Partial or malformed
   coordinates return a structured 422; only an absent coordinate opens live."
  [r]
  (let [^js req (:seon.http/node-req r)
        ^js res (:seon.http/node-res r)
        id      (get-in r [:path-params :id])
        view-id (requested-view-id req)]
    (if (and (safe-id? id) (await (agent-exists? id)))
      (let [selector (parse-historical-coordinate req)]
        (cond
          (:seon.error/message selector)
          (write-historical-error! res selector)

          selector
          (open-feed!
           req res
           (cond->
            {:seon.web.feed/key [:seon.web.feed/agent id
                                 :seon.web.feed/at selector]
             :seon.web.feed/live? false
             :seon.web.feed/coordinate selector
             :seon.web.feed/render-full
             (fn []
               (-> (render-agent-view-at! id selector)
                   (.then (fn [element]
                            {::element element
                             :seon.db/coordinate selector}))))
             :seon.web.feed/render-change
             (fn [_subscription _change] {::elements []})}
            view-id (assoc ::view-id view-id)))

          :else
          (open-feed! req res (live-agent-feed-definition id view-id))))
      (do (.writeHead res 404 #js {"Content-Type" "text/plain; charset=utf-8"
                                   "Cache-Control" "no-store"})
          (.end res "unknown agent id")))))
