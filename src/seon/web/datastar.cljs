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
    [seon.log :as log]
    [seon.schema :as schema]
    [seon.ui.header :as header]
    [seon.ui.html :as html]
    [seon.ui.agent-view :as agent-view]
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
                  [:or
                   :seon.ui.agent-view/dependencies
                   :seon.db/read-observations
                   [:map-of :qualified-keyword [:set :qualified-keyword]]])
(schema/register! ::element :seon.render.canvas/hiccup)
(schema/register! ::elements [:vector :seon.render.canvas/hiccup])
(schema/register! ::event :string)
(schema/register! ::last-event :string)
(schema/register! ::render-thunk 'fn?)
(schema/register! ::observed-render-request
  [:map
   [:seon.db/db :seon.db/db-val]
   [::render-thunk ::render-thunk]])
(schema/register! ::observed-render-response
  [:map
   [::element ::element]
   [::dependencies :seon.db/read-observations]])
(schema/register! ::observed-transition-request
  [:map
   [:seon.db/db :seon.db/db-val]
   [::dependencies :seon.db/read-observations]
   [::render-thunk ::render-thunk]])
(schema/register! ::observed-transition-response
  [:map
   [::elements ::elements]
   [::dependencies :seon.db/read-observations]])
(schema/register! ::view-state
  [:map
   [::view-id ::view-id]
   [::catalog ::catalog]
   [::active-tokens ::active-tokens]])
(schema/register! ::views [:map-of ::view-id ::view-state])
(schema/register! ::feed-key
  [:or
   [:tuple [:= :seon.web.feed/agent] :seon.agent/id]
   [:tuple [:= :seon.web.feed/agent] :seon.agent/id
    [:= :seon.web.feed/as-of] :int]
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
   [:seon.web.feed/render-full ::render-full]
   [:seon.web.feed/render-change ::render-change]
   [::dependencies {:optional true} ::dependencies]
   [::view-id {:optional true} ::view-id]
   [::catalog {:optional true} ::catalog]
   [::active-tokens {:optional true} ::active-tokens]])
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

(defn render-observed
  "Render one synchronous view and retain its immutable database reads."
  {:malli/schema [:=> [:cat ::observed-render-request]
                  ::observed-render-response]}
  [{dbv :seon.db/db thunk ::render-thunk}]
  (let [capture (db/capture-reads
                  {:seon.db/db dbv :seon.db/thunk thunk})]
    {::element (:seon.db/result capture)
     ::dependencies (:seon.db/read-observations capture)}))

(defn transition-observed
  "Rerender one view only when one of its captured reads changed."
  {:malli/schema [:=> [:cat ::observed-transition-request]
                  ::observed-transition-response]}
  [{dbv :seon.db/db observations ::dependencies thunk ::render-thunk}]
  (if (or (empty? observations)
          (some #(db/read-observation-changed?
                   {:seon.db/db dbv :seon.db/read-observation %})
                observations))
    (let [rendered (render-observed
                     {:seon.db/db dbv ::render-thunk thunk})]
      {::elements [(::element rendered)]
       ::dependencies (::dependencies rendered)})
    {::elements []
     ::dependencies observations}))

;; ============================================================
;; Feed registry — socket-owning views consume normalized subscriptions. A
;; subscription key is the feed's stable semantic key plus its active-unit
;; fingerprint. One subscription owns the render transition and dependency
;; authority; equivalent sockets never own competing copies of either.
;; ============================================================

(def ^:private empty-feed-registry
  {::views {}
   ::subscriptions {}})

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

;; ============================================================
;; view = f(db) — the live agents view as `[:main#app-view …rows…]`.
;; ============================================================

(defn- view-fn-patch
  "Render a bound full-view fn into one event plus learned dependencies.

   A plain hiccup result remains valid. A database-observed view returns
   `::element` and `::dependencies`; the normalized subscription owns both."
  [view-fn]
  (try
    (let [rendered (view-fn)
          observed? (and (map? rendered) (contains? rendered ::element))
          element (if observed? (::element rendered) rendered)]
      (cond-> {::event (-> element html/->string patch-elements)}
        (and observed? (contains? rendered ::dependencies))
        (assoc ::dependencies (::dependencies rendered))))
    (catch :default e
      {::event
       (-> [:main {:id "app-view"}
            [:div {:id "app-error" :class "text-error text-xs font-mono"}
             (str "render error: " (.-message e))]]
           html/->string
           patch-elements)})))

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

(defn- shared-full-event!
  "Return one current full patch shared by equivalent open views.

   A normalized subscription is already the render authority for equivalent
   sockets. Its first consumer renders the full view; later consumers reuse
   those exact bytes until a database transaction invalidates the value. The
   cache is bounded by live subscriptions and disappears with the final
   consumer—there is no second cache registry or retained closed view."
  [{render-full :seon.web.feed/render-full
    subscription-key ::subscription-key}]
  (let [subscription (get-in @!feeds [::subscriptions subscription-key])]
    (or (::full-event subscription)
        (let [rendered (view-fn-patch render-full)
              event (::event rendered)
              subscription-id (::subscription-id subscription)]
          (swap! !feeds
                 (fn [registry]
                   (if (= subscription-id
                          (get-in registry [::subscriptions subscription-key
                                            ::subscription-id]))
                     (cond-> (assoc-in registry
                                       [::subscriptions subscription-key
                                        ::full-event]
                                       event)
                       (contains? rendered ::dependencies)
                       (assoc-in [::subscriptions subscription-key
                                  ::dependencies]
                                 (::dependencies rendered)))
                     registry)))
          event))))

(defn- push-full!
  "Write one connection's normalized subscription full view."
  [conn]
  (push-event! conn (shared-full-event! conn)))

(defn- subscription-key-for
  "Normalized subscription coordinate for one feed or socket view."
  [feed]
  [(:seon.web.feed/key feed)
   (active-fingerprint (::active-tokens feed))])

(defn- subscription-from-feed
  "Create one normalized render authority from the first consumer's plan."
  [subscription-key feed]
  {::subscription-id (random-uuid)
   ::subscription-key subscription-key
   ::consumer-view-ids #{}
   ::live? (:seon.web.feed/live? feed)
   ::render-change (:seon.web.feed/render-change feed)
   ::dependencies (or (::dependencies feed) {})})

(defn- socket-consumer
  "Remove subscription authority from a socket-owning view descriptor."
  [feed subscription-key]
  (-> feed
      (assoc ::subscription-key subscription-key)
      (dissoc :seon.web.feed/key
              :seon.web.feed/live?
              :seon.web.feed/render-change
              ::dependencies)))

(defn- detach-view
  "Remove one socket consumer and prune its unobserved subscription."
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

(defn- attach-feed
  "Attach a socket consumer to exactly one normalized subscription authority."
  [registry feed]
  (let [view-id (::view-id feed)
        subscription-key (subscription-key-for feed)
        previous (get-in registry [::views view-id])
        same-subscription? (= subscription-key (::subscription-key previous))
        detached (if (or (nil? previous) same-subscription?)
                   registry
                   (detach-view registry view-id))
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
      (let [detached (detach-view registry view-id)
            subscription (or (get-in detached [::subscriptions next-key])
                             (-> old-subscription
                                 (assoc ::subscription-id (random-uuid)
                                        ::subscription-key next-key
                                        ::consumer-view-ids #{})
                                 (dissoc ::full-event)))
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

(defn- broadcast!
  "Transition each live subscription once and fan its patch to consumers."
  [change]
  (doseq [[subscription-key subscription] (::subscriptions @!feeds)
          :when (::live? subscription)]
    (try
      (let [started (.now js/performance)
              transition ((::render-change subscription) subscription change)
              _ (when-not (map? transition)
                  (throw (js/Error. "subscription render must return a map")))
              elements (::elements transition)
              event (when (seq elements) (patch-hiccup-elements elements))
              render-ms (- (.now js/performance) started)
              subscription-id (::subscription-id subscription)]
          (when (contains? transition ::dependencies)
            (swap! !feeds
                   (fn [registry]
                     (if (= subscription-id
                            (get-in registry [::subscriptions subscription-key
                                              ::subscription-id]))
                       (assoc-in registry [::subscriptions subscription-key
                                           ::dependencies]
                                 (::dependencies transition))
                       registry))))
          (when event
            ;; Re-read ownership after rendering. A unit activation or reconnect
            ;; can rebind this view while the transition is running; an obsolete
            ;; subscription id must never push its stale patch into the new
            ;; socket, even when the semantic key happens to be identical.
            (let [[before registry]
                  (swap-vals!
                    !feeds
                    (fn [registry]
                      (if (and (= subscription-id
                                  (get-in registry
                                          [::subscriptions subscription-key
                                           ::subscription-id]))
                               (not= event
                                     (get-in registry
                                             [::subscriptions subscription-key
                                              ::last-event])))
                        (assoc-in registry
                                  [::subscriptions subscription-key ::last-event]
                                  event)
                        registry)))
                  current-subscription
                  (get-in registry [::subscriptions subscription-key])
                  previous-event
                  (get-in before [::subscriptions subscription-key ::last-event])
                  current-event (::last-event current-subscription)
                  changed-event? (and (= subscription-id
                                         (::subscription-id current-subscription))
                                      (not= previous-event current-event))
                  connections
                  (when changed-event?
                    (keep #(get-in registry [::views %])
                          (::consumer-view-ids current-subscription)))]
              (doseq [conn connections] (push-event! conn event))
              (when changed-event?
                (log/info-console! "seon.web.datastar" "broadcast"
                                   {:seon.web.broadcast/view (first subscription-key)
                                    :seon.web.broadcast/connections (count connections)
                                    :seon.web.broadcast/targets (count elements)
                                    :seon.web.broadcast/changed-attrs
                                    (sort (:seon.db/changed-attrs change))
                                    :seon.web.broadcast/render-ms
                                    (.round js/Math render-ms)})))))
      (catch :default e
        (log/error-console! "seon.web.datastar"
                            (str "broadcast failed for " subscription-key) e)))))

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
  (into (or (:seon.db/changed-attrs change) #{})
        (keys (:seon.db/attr-index change))))

(defn- merge-attr-index [pending change]
  (merge-with into
              (or (:seon.db/attr-index pending) {})
              (or (:seon.db/attr-index change) {})))

(defn- merge-change [pending change]
  (let [attrs (set/union (or (:seon.db/changed-attrs pending) #{})
                         (change-attrs change))
        attr-index (merge-attr-index pending change)
        db-before (or (:seon.db/db-before pending)
                      (:seon.db/db-before change))
        latest-db (if (contains? change :seon.db/db)
                    (:seon.db/db change)
                    (:seon.db/db pending))
        datoms (into (vec (or (:seon.db/datoms pending) []))
                     (or (:seon.db/datoms change) []))]
    (cond->
      {:seon.db/db latest-db
       :seon.db/changed-attrs attrs
       :seon.db/attr-index attr-index
       :seon.db/datoms datoms
       :seon.web.broadcast/structural?
       (or (:seon.web.broadcast/structural? pending)
           (agent-view/structural-change? (change-attrs change)))}
      (some? db-before) (assoc :seon.db/db-before db-before))))

(defn- running-eval-record-commit?
  "True when one commit records eval children of still-running turns."
  [change]
  (try
    (let [datoms (or (:seon.db/datoms change) [])
          post-db (:seon.db/db change)
          eval-eids
          (into #{}
                (comp
                  (filter :seon.db/added?)
                  (filter #(= :seon.eval/id (:seon.db/a %)))
                  (map :seon.db/e))
                datoms)
          eval-links
          (into []
                (comp
                  (filter :seon.db/added?)
                  (filter #(= :seon.agent.turn/evals (:seon.db/a %)))
                  (filter #(contains? eval-eids (:seon.db/v %))))
                datoms)
          linked-eval-eids (into #{} (map :seon.db/v) eval-links)
          owner-turn-eids (into #{} (map :seon.db/e) eval-links)]
      (and post-db
           (seq eval-eids)
           (= eval-eids linked-eval-eids)
           (seq owner-turn-eids)
           (every?
             (fn [turn-eid]
               (= :running
                  (:seon.agent.turn/status
                    (db/pull {:seon.db/db post-db
                              :seon.db/pull-pattern
                              [:seon.agent.turn/status]
                              :seon.db/ref turn-eid}))))
             owner-turn-eids)))
    (catch :default _
      ;; Recognition is an optimization. Any malformed or unreadable change
      ;; remains render-worthy rather than risking a suppressed update.
      false)))

(defn- broadcast-due-at
  [enqueued-at now structural?]
  (min (+ enqueued-at maximum-coalesce-ms)
       (+ now (if structural? structural-settle-ms normal-settle-ms))))

(declare drain-coalescer!)

(defn- schedule-broadcast! [change]
  (let [now (monotonic-ms)
        current @!coalescer
        pending (merge-change (::pending-change current) change)
        running-eval? (running-eval-record-commit? change)]
    (cond
      (and running-eval? (::timer current))
      ;; A prior deliberate change already earned this frame. Merge the eval
      ;; evidence, but never postpone or replace the timer that owns it.
      (reset! !coalescer (assoc current ::pending-change pending))

      running-eval?
      ;; Eval outcomes remain durable database facts. While their owning turn
      ;; is running, retain their presentation evidence until a non-eval fact
      ;; (normally the terminal turn status) schedules the existing renderer.
      (reset! !coalescer {::pending-change pending})

      :else
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
              (clear-broadcast-timeout! old-timer))))))))

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

(defn- invalidate-full-events
  "Drop cached first paints after one authoritative database change."
  [registry]
  (update registry ::subscriptions
          (fn [subscriptions]
            (into {}
                  (map (fn [[subscription-key subscription]]
                         [subscription-key (dissoc subscription ::full-event)]))
                  subscriptions))))

(defn- on-tx [change]
  ;; Invalidate immediately, even when the coalescer deliberately delays the
  ;; open sockets' partial morph. A newly opened view must always render from
  ;; the latest database value rather than reuse a pre-transaction first paint.
  (swap! !feeds invalidate-full-events)
  (schedule-broadcast! change))

(defn install!
  "Install the view tx-listener. Idempotent — same key replaces."
  []
  (db/listen! {:seon.db/key ::views :seon.db/handler on-tx}))

(defn uninstall!
  "Remove the view listener, pending broadcast, and shared heartbeat."
  []
  (try
    (db/unlisten! {:seon.db/key ::views})
    (finally
      (clear-coalescer!)
      (stop-heartbeat!))))

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
  (let [b (brand/info)]
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
  (let [catalog (if (contains? feed ::catalog)
                  (::catalog feed)
                  (or (::catalog previous) []))
        available (into #{} (map ::token) catalog)
        requested-active (if previous
                           (::active-tokens previous)
                           (or (::active-tokens feed) #{}))
        active-tokens (set/intersection requested-active available)]
    (assoc feed
           ::view-id view-id
           ::catalog catalog
           ::active-tokens active-tokens
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

(defn- open-feed!
  "Open a long-lived gzip SSE stream from one derived-view descriptor."
  [^js req ^js res feed]
  ;; Datahike owns listener membership. The stable key makes this idempotent
  ;; and replaces a pre-reload callback with the current definition.
  (install!)
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream; charset=utf-8"
                           "Content-Encoding"  "gzip"
                           "Cache-Control"     "no-store"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
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
    (let [attached (get-in @!feeds [::views view-id])]
      ;; `attach-feed` adds the normalized subscription coordinate. First
      ;; paint must use that registry-owned descriptor, not the pre-attach
      ;; local socket, or every page aliases through a nil subscription and
      ;; can receive another view's cached HTML.
      (push-full! attached))
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

(defn- write-unit-response!
  "Finish one unit-control response with explicit status and content type."
  [^js res status content-type body]
  (.writeHead res status #js {"Content-Type" content-type
                              "Cache-Control" "no-store"})
  (.end res body)
  nil)

(defn handle-view-unit!
  "Activate or deactivate one trusted unit in an open ephemeral view."
  {:malli/schema [:=> [:catn [::ring-request ::ring-request]] :nil]}
  [r]
  (let [^js res (:seon.http/node-res r)
        query-string (:query-string r)
        view-id (query-value query-string "view")
        token (query-value query-string "unit")
        active-value (query-value query-string "active")
        active? (case active-value "1" true "0" false ::invalid-active)]
    (cond
      (or (not (safe-view-id? view-id))
          (not (seq token))
          (= ::invalid-active active?))
      (write-unit-response! res 400 "text/plain; charset=utf-8" "invalid unit request")

      (nil? (get-in @!feeds [::views view-id]))
      (write-unit-response! res 410 "text/plain; charset=utf-8" "view is closed")

      :else
      (let [view (get-in @!feeds [::views view-id])
            catalog (::catalog view)
            target (descriptor-by-token catalog token)]
        (if-not target
          (write-unit-response! res 404 "text/plain; charset=utf-8" "unknown unit")
          (try
            (let [transition (transition-active-set
                               {::catalog catalog
                                ::active-tokens (::active-tokens view)
                                ::token token
                                ::active? active?})
                  deactivated (::deactivated-tokens transition)
                  inactive-elements (into []
                                          (comp (filter #(contains? deactivated
                                                                    (::token %)))
                                                (map inactive-stub))
                                          catalog)
                  elements (if active?
                             (into [(active-unit target)] inactive-elements)
                             [(inactive-stub target)])
                  body (->> elements
                            (map html/->string)
                            (str/join "\n")
                            patch-elements)
                  feed-id (:seon.web.feed/id view)]
              (swap! !feeds
                     update-owned-view view-id feed-id
                     #(assoc % ::active-tokens (::active-tokens transition)))
              ;; Datastar fetch actions consume event streams. Returning bare
              ;; HTML here succeeded at HTTP while doing nothing in the DOM,
              ;; leaving expanded debug disclosures as empty stubs.
              (write-unit-response! res 200
                                    "text/event-stream; charset=utf-8"
                                    body))
            (catch :default e
              (log/error-console! "seon.web.datastar" "unit producer failed" e)
              (write-unit-response! res 500 "text/plain; charset=utf-8"
                                    "unit render failed"))))))))

(defn reconcile-view-catalog!
  "Replace one open view's trusted catalog and retain only still-present active units.

   Returns the retained active tokens, or the empty set when the view closed
   before reconciliation. Producers are never invoked."
  {:malli/schema [:=> [:cat ::reconcile-catalog-request] ::active-tokens]}
  [{view-id ::view-id catalog ::catalog}]
  (if-let [view (get-in @!feeds [::views view-id])]
    (let [available (into #{} (map ::token) catalog)
          retained (set/intersection (::active-tokens view) available)
          feed-id (:seon.web.feed/id view)]
      (swap! !feeds
             update-owned-view view-id feed-id
             #(assoc % ::catalog catalog ::active-tokens retained))
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

(defn- agent-exists?
  "True iff `id` resolves to a live `:seon.agent/id` entity in the cluster
   store. Guards the per-agent page: an unknown/stale id (a bookmark from a
   reset store, a typo) redirects HOME rather than serving an empty view."
  [id]
  (boolean
    (seq (db/query {:seon.db/db    @db/*conn*
                    :seon.db/query '[:find ?e :in $ ?id :where [?e :seon.agent/id ?id]]
                    :seon.db/args  [id]}))))

(defn- parse-t
  "Parse the optional `?t=<tx-id>` from a node req URL into a datahike
   time-point (a tx-id number). Returns nil for an absent/blank/non-numeric
   `t` → the live feed. Never throws (a bad `t` falls back to live)."
  [^js req]
  (try
    (let [url  (or (.-url ^js req) "")
          qidx (str/index-of url "?")]
      (when qidx
        (let [t (.get (js/URLSearchParams. (subs url (inc qidx))) "t")]
          (when (and t (not= "" t))
            (let [n (js/parseInt t 10)]
              (when (js/Number.isFinite n) n))))))
    (catch :default _ nil)))

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
      (not (agent-exists? id))
      (do (.writeHead res 302 #js {"Location" "/" "Cache-Control" "no-store"})
          (.end res ""))
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

(defn open-agent-feed!
  "Open the per-agent view gzip feed.

   The seeded :seon.route/agent-feed handler. A Ring handler: takes the
   Ring request `r`, self-extracts node-req/node-res + the `{id}` path-param.
   Lazily installs the tx-listener (idempotent). Invalid or stale ids 404. Public —
   db->routes resolves its symbol.

   #18 — historical time-travel: an optional `?t=<tx-id>` binds the view to
   `db-as-of-t` instead of the live db. With `t`, the feed is the SAME
   `agent-view` rendered against `(db/as-of @*conn* t)` — a PAST snapshot that
   is naturally FROZEN (re-rendering it on a later tx yields identical bytes, so
   the broadcast harmlessly re-pushes the same #app-view). With NO `t` it is the
   current auto-morphing feed, UNCHANGED. A bad/absent `t` falls back to live."
  [r]
  (let [^js req (:seon.http/node-req r)
        ^js res (:seon.http/node-res r)
        id      (get-in r [:path-params :id])
        view-id (requested-view-id req)]
    (if (and (safe-id? id) (agent-exists? id))
      (let [t (parse-t req)]
        (if t
          (let [frozen (db/as-of @db/*conn* t)]
            (open-feed!
              req res
              (cond->
                {:seon.web.feed/key [:seon.web.feed/agent id
                                     :seon.web.feed/as-of t]
                 :seon.web.feed/live? false
                 :seon.web.feed/render-full
                 #(agent-view/agent-view frozen id)
                 :seon.web.feed/render-change
                 (fn [_subscription _change] {::elements []})}
                view-id (assoc ::view-id view-id))))
          (open-feed!
            req res
            (cond->
              {:seon.web.feed/key [:seon.web.feed/agent id]
               :seon.web.feed/live? true
               :seon.web.feed/render-full
               #(let [rendered (agent-view/render-agent-view @db/*conn* id)]
                  {::element (::agent-view/element rendered)
                   ::dependencies (::agent-view/dependencies rendered)})
               :seon.web.feed/render-change
               (fn [{dependencies ::dependencies}
                    {dbv :seon.db/db attrs :seon.db/changed-attrs}]
                 (let [transition
                       (agent-view/transition
                         {:seon.db/db dbv
                          :seon.agent/id id
                          ::agent-view/changed-attrs attrs
                          ::agent-view/dependencies dependencies})]
                   {::elements (::agent-view/elements transition)
                    ::dependencies (::agent-view/dependencies transition)}))}
              view-id (assoc ::view-id view-id)))))
      (do (.writeHead res 404 #js {"Content-Type" "text/plain; charset=utf-8"
                                   "Cache-Control" "no-store"})
          (.end res "unknown agent id")))))
