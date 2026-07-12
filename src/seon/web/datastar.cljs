(ns seon.web.datastar
  "Datastar gzip-morph SSE streamer — the hyperlith `view = f(db)` model
   ported into the pod.

   Each route derives a view from the database. Initial paint sends the whole
   `#app-view`; later commits use renderer read-sets to send only complete,
   ID-addressed elements affected by the transaction. Equivalent open feeds
   share that render and receive the same gzip-compressed event.

   ## The surfaces (seeded `:seon.route/*` datoms → this ns's handlers)

     GET /agents       → the roster shim page ([[serve-agents-page!]]): loads
                         datastar.js + opens the feed via
                         `data-init=\"@get('/agents/feed')\"`, empty
                         `<main id=\"app-view\">` morph target.
     GET /agents/feed  → the long-lived gzip roster SSE stream
                         ([[open-roster-feed!]] → [[roster-view]]).
     GET /agent/{id}   → one agent's view ([[serve-agent-page!]]); its
     GET /agent/{id}/feed  gzip feed ([[open-agent-feed!]] → agent-view).
     GET /             → root's view ([[serve-root!]]); `/` IS root's
                         dashboard, so `/agents` is the explicit fleet roster.

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
    [clojure.string :as str]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.log :as log]
    [seon.render :as render]
    [seon.ui.header :as header]
    [seon.ui.html :as html]
    [seon.ui.agent-view :as agent-view]
    [seon.web.brand :as brand]))

;; ============================================================
;; Connection registry — one descriptor per open gzip stream. Render fns are
;; grouped by `:seon.web.feed/key`, so equivalent tabs render once per tx.
;; ============================================================

(defonce ^{:doc "Vector of fully namespaced feed descriptors — one per open
                  stream. Live descriptors sharing `:seon.web.feed/key` also
                  share one transaction-derived render."}
  !feeds (atom []))

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
;; view = f(db) — the live agent roster as `[:main#app-view …tiles…]`.
;; ============================================================

(def ^:private roster-state-dot
  "DERIVED FSM state → the Phosphor dot color class for a roster row."
  {:running    "text-signal"
   :idle       "text-text-400"
   :paused     "text-warning"
   :terminated "text-text-500"})

(defn- tile-preview
  "Agent `id`'s canvas as a clipped compact face for its roster tile, or nil.

   The canvas = the agent's canvas — `render/render-agent-canvas` (the ONE
   tile entry point: the pinned `:seon.render.canvas/content`, else the
   derived last-updated tile, else the welcome card; throw-safe). Clipped to
   a fixed height (inline style — no matching Tailwind class in the built
   vocabulary) with a stretched inset-0 anchor to `/agent/<id>` (the
   system-view card pattern: agent hiccup can CONTAIN `<a>`, and nested
   anchors split in the parser). Skipped for root: root's canvas is the `/`
   dashboard itself (`system-view`), which renders this roster's agents —
   embedding it in a row would recurse the whole dashboard into the list.
   Guarded: any failure → nil (row renders without a preview)."
  [db id]
  (when (not= id "root")
    (try
      (when-let [hiccup (:seon.render/hiccup
                          (render/render-agent-canvas
                            {:seon.db/db db :seon.agent/id id}))]
        [:div {:id    (str "app-agent-" id "-tile")
               :class "relative overflow-hidden border-t border-base-800/60 bg-base-950/40"
               :style "max-height:7rem"}
         hiccup
         [:a {:href       (str "/agent/" id)
              :aria-label (str "open agent " id)
              :class      "absolute inset-0"}]])
      (catch :default _ nil))))

(defn- agent-tile
  "One roster tile for `id` — a LINK to that agent's view (`/agent/<id>`)
   showing its id, DERIVED FSM state as a dot+text chip, its purpose line-1
   (when set), and its canvas compact face ([[tile-preview]] — the agent's
   canvas, morphed live with every commit). `derive-state`, the purpose
   pull, and the preview are each guarded so a single bad agent can never
   abort the whole-view render (the never-crash floor). Keeps the
   `app-agent-<id>` id so idiomorph anchors the tile."
  [db id]
  (let [state   (try (derive/derive-state db id) (catch :default _ :unknown))
        purpose (try (:seon.agent/purpose
                       (db/pull db '[:seon.agent/purpose] [:seon.agent/id id]))
                     (catch :default _ nil))
        p1      (when (seq purpose) (first (str/split-lines purpose)))
        dot-cls (get roster-state-dot state "text-text-500")]
    [:li {:id (str "app-agent-" id) :class "border-b border-base-800"}
     [:a {:href  (str "/agent/" id)
          :class "flex items-center gap-3 px-3 py-2 hover:bg-base-900 text-xs font-mono"}
      [:span {:class "text-signal font-semibold shrink-0 w-40 truncate"} id]
      [:span {:class (str "flex items-center gap-1 shrink-0 w-24 " dot-cls)}
       [:span "●"] [:span (name state)]]
      [:span {:class "text-text-400 truncate min-w-0"} (or p1 "")]]
     (tile-preview db id)]))

(defn roster-view
  "The live agent roster (`/agents`) as `[:main#app-view …rows…]` = f(db).

   Every agent is a tile (id, DERIVED FSM state, purpose line-1, and its
   canvas compact face — the agent's canvas) linking to its
   `/agent/<id>` view.

   Pure of external state — reads only the supplied db value, so the same
   db always renders the same hiccup. NEVER throws (the whole-view morph
   engine must be crash-proof): a render error degrades to a visible error
   tile inside `#app-view`. Root id is the morph target the shim
   page declares."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]] :any]}
  [db]
  (try
    (let [ids (->> (db/query {:seon.db/db    db
                              :seon.db/query '[:find ?id
                                               :where [?a :seon.agent/id ?id]]})
                   (map first)
                   sort)]
      [:main {:id "app-view" :class "flex flex-col gap-3 w-full"}
       (header/system-header db)
       header/header-spacer
       [:header {:class "flex items-center justify-between border-b border-base-800 pb-2"}
        [:div {:class "flex items-center gap-2"}
         [:a {:href "/" :class "text-text-400 text-xs font-mono"} "← home"]
         [:span {:class "text-text-500 text-2xs uppercase tracking-wider"} "agents"]]
        [:span {:id "app-count" :class "text-text-400 text-xs font-mono tabular-nums"}
         (str (count ids) " agent" (when (not= 1 (count ids)) "s"))]]
       (if (seq ids)
         (into [:ul {:id "app-roster"
                     :class "flex flex-col border border-base-800 rounded-md bg-base-900 overflow-hidden"}]
               (map #(agent-tile db %) ids))
         [:div {:id "app-empty" :class "text-text-500 text-xs font-mono"}
          "no agents yet"])])
    (catch :default e
      [:main {:id "app-view" :class "flex flex-col gap-3 w-full"}
       [:div {:id "app-error" :class "text-error text-xs font-mono"}
        (str "render error: " (.-message e))]])))

(defn- view-fn-patch
  "Render a connection's bound 0-arg `view-fn` → its `#app-view` SSE morph
   string. GUARDED: a throwing view degrades to a visible `#app-view-error`
   morph so one bad view never aborts the whole broadcast."
  [view-fn]
  (-> (try
        (view-fn)
        (catch :default e
          [:main {:id "app-view"}
           [:div {:id "app-error" :class "text-error text-xs font-mono"}
            (str "render error: " (.-message e))]]))
      html/->string
      patch-elements))

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

(defn- push-full!
  "Render and write one connection's initial full view."
  [{render-full :seon.web.feed/render-full :as conn}]
  (push-event! conn (view-fn-patch render-full)))

(defn- broadcast!
  "Render one targeted patch per unique live view and fan it to its feeds."
  [change]
  (let [groups (->> @!feeds
                    (filter :seon.web.feed/live?)
                    (group-by :seon.web.feed/key))]
    (doseq [[view-key conns] groups]
      (try
        (let [started (.now js/performance)
              render-change (:seon.web.feed/render-change (first conns))
              elements (render-change change)
              event (when (seq elements) (patch-hiccup-elements elements))
              render-ms (- (.now js/performance) started)]
          (when event
            (doseq [conn conns] (push-event! conn event)))
          (log/info-console! "seon.web.datastar" "broadcast"
                             {:seon.web.broadcast/view view-key
                              :seon.web.broadcast/connections (count conns)
                              :seon.web.broadcast/targets (count elements)
                              :seon.web.broadcast/render-ms
                              (.round js/Math render-ms)}))
        (catch :default e
          (log/error-console! "seon.web.datastar"
                              (str "broadcast failed for " view-key) e))))))

;; ============================================================
;; Coalescing — one trailing timer collapses a tx burst into one morph
;; (an agent turn commits many datoms; the human sees ONE re-render).
;; ============================================================

(defonce ^:private !pending-change (atom nil))
(defonce ^:private !broadcast-scheduled? (atom false))

(defn- merge-change [pending change]
  {:seon.db/db (:seon.db/db change)
   :seon.db/changed-attrs
   (into (or (:seon.db/changed-attrs pending) #{})
         (keys (:seon.db/attr-index change)))})

(defn- schedule-broadcast! [change]
  (swap! !pending-change merge-change change)
  (when-not @!broadcast-scheduled?
    (reset! !broadcast-scheduled? true)
    (js/setTimeout
      (fn []
        (let [pending @!pending-change]
          (reset! !pending-change nil)
          (reset! !broadcast-scheduled? false)
          (when pending (broadcast! pending))))
      16)))

;; ============================================================
;; Lifecycle — db/listen! IS the refresh signal.
;; ============================================================

(defn- on-tx [change] (schedule-broadcast! change))

(defonce ^:private !installed? (atom false))

(defn install!
  "Install the view tx-listener. Idempotent — same key replaces."
  []
  (db/listen! {:seon.db/key ::views :seon.db/handler on-tx})
  (reset! !installed? true))

(defn uninstall!
  "Remove the view tx-listener."
  []
  (db/unlisten! {:seon.db/key ::views})
  (reset! !installed? false))

(defn- ensure-installed! []
  (when-not @!installed? (install!)))

(defn ^:dev/before-load before-reload
  "Uninstall the view tx-listener before a hot reload."
  []
  (try (uninstall!) (catch :default _ nil)))

(defn ^:dev/after-load after-reload
  "Reinstall the view tx-listener after a hot reload."
  []
  (try (install!) (catch :default _ nil)))

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
         ;; forever (survives a pod/wire-server restart, a network blip, a
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
;; POST endpoints (`/chat`, `/agents/new`) — no Core change. A fixed bottom
;; bar + an inline-style spacer reserves scroll room so the last tile is never
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
   broadcast feed re-renders the `:transcript` tile. A 204 reply closes the
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

(defn- new-agent-bar-html
  "The `/agents` roster's new-agent affordance, as a raw HTML string.

   A fixed bottom bar (outside the morph) whose button INLINE-FETCH POSTs the
   existing `/agents/new` (optional form-urlencoded `purpose=`) then navigates
   to the new `/agent/<id>` on the 200 id-body. Inline JS (not datastar
   `@post`) because the response is the new id as plain text that we must READ
   and navigate to — copied from the web UI mission-control button. The
   endpoint is same-origin-gated and serializes creates (409 while one is in
   flight); errors land in the button's own text, never swallowed."
  []
  (html/->string
    (list
      [:div {:style "height:3.25rem"}]
      [:div {:id    "app-new-agent"
             :class "fixed bottom-0 left-0 right-0 z-10 flex items-center gap-2 border-t border-base-800 bg-base-900 px-3 py-2"}
       [:input {:id           "app-new-agent-purpose"
                :type         "text"
                :autocomplete "off"
                :placeholder  "purpose (optional)…"
                :class "flex-1 bg-base-950 border border-base-800 rounded px-2 py-1 text-text-100 text-xs font-mono"}]
       [:button {:id      "app-new-agent-btn"
                 :type    "button"
                 :class   "bg-base-800 hover:bg-base-700 text-signal border border-base-700 px-3 py-1 rounded text-xs font-mono"
                 :onclick (str "var b=this;b.disabled=true;b.textContent='booting…';"
                               "var p=document.getElementById('app-new-agent-purpose');"
                               "var body=p&&p.value?'purpose='+encodeURIComponent(p.value):'';"
                               "fetch('/agents/new',{method:'POST',"
                               "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                               "body:body})"
                               ".then(function(r){if(r.ok){r.text().then(function(id){"
                               "window.location='/agent/'+id.trim();});}"
                               "else{r.text().then(function(t){b.disabled=false;"
                               "b.textContent='\\u2717 '+(t||('HTTP '+r.status));});}})"
                               ".catch(function(e){b.disabled=false;b.textContent='\\u2717 '+e;});")}
        "+ new agent"]])))

(defn- agents-page-html
  "The `/agents` roster shim page (brand-aware head — see [[shim-html]]). Its
   feed effect opens `/agents/feed` → [[roster-view]]; carries the new-agent
   bar (P1c) as its OUTSIDE-the-morph human affordance."
  []
  (shim-html "agents" "bg-base-950 text-text-200 font-mono p-3" "/agents/feed"
             (new-agent-bar-html)))

(defn- open-feed!
  "Open a long-lived gzip SSE stream from one derived-view descriptor."
  [^js req ^js res feed]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream; charset=utf-8"
                           "Content-Encoding"  "gzip"
                           "Cache-Control"     "no-store"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (let [gz   (.createGzip zlib)
        id   (random-uuid)
        conn (assoc feed
                    :seon.web.feed/id id
                    :seon.web.feed/gzip gz
                    :seon.web.feed/response res
                    :seon.web.feed/pending-event (atom nil)
                    :seon.web.feed/draining? (atom false)
                    :seon.web.feed/opened-at (js/Date.))]
    (.on gz "error"  (fn [e] (log/error-console! "seon.web.datastar" "gz error" e)))
    (.on res "error" (fn [e] (log/error-console! "seon.web.datastar" "res error" e)))
    (.pipe gz res)
    (swap! !feeds conj conn)
    (log/info-console! "seon.web.datastar" "FEED OPEN"
                       {:seon.web.feed/id (str id)
                        :seon.web.feed/count (count @!feeds)})
    ;; First paint immediately so the page populates without waiting for a tx.
    (push-full! conn)
    (.on req "close"
         (fn []
           (swap! !feeds
                  (fn [cs]
                    (vec (remove #(= (:seon.web.feed/id %) id) cs))))
           (try (.end gz) (catch :default _ nil))
           (log/info-console! "seon.web.datastar" "FEED CLOSE"
                              {:seon.web.feed/id (str id)
                               :seon.web.feed/count (count @!feeds)})))))

;; ============================================================
;; Per-agent view (/agent/{id}) — the shim page + the feed bound to
;; that agent's `agent-view`. The router calls these public entries
;; with the `{id}` path-param.
;; ============================================================

(def ^:private safe-id-re
  ;; Agent ids are alphanumeric + `._:-` (`:seon.db/id` 14-char ids, plus
  ;; \"root\"). Validating the id before it lands in the shim page's HTML /
  ;; URL closes any injection via the path segment; anything else 404s.
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

(defn serve-agent-page!
  "Serve the per-agent view shim page (the seeded :seon.route/agent handler).
   A Ring handler: takes the Ring request `r`, self-extracts the node res + the
   `{id}` path-param. Invalid ids 404. Public — db->routes resolves its symbol
   via eval/lookup-value at request time."
  [r]
  (let [^js res (:seon.http/node-res r)
        id      (get-in r [:path-params :id])]
    (cond
      ;; A malformed id (injection attempt, junk path segment) → home.
      (not (safe-id? id))
      (do (.writeHead res 302 #js {"Location" "/" "Cache-Control" "no-store"})
          (.end res ""))
      ;; #28 — a well-formed but UNKNOWN agent (stale bookmark, reset store,
      ;; typo) gracefully redirects HOME rather than serving an empty view or
      ;; a raw 404. "root" always resolves (seeded), so it is never redirected.
      (not (agent-exists? id))
      (do (.writeHead res 302 #js {"Location" "/" "Cache-Control" "no-store"})
          (.end res ""))
      :else
      (do (.writeHead res 200 #js {"Content-Type"  "text/html; charset=utf-8"
                                   "Cache-Control" "no-store, no-cache, must-revalidate"})
          (.end res (agent-page-html id))))))

(defn serve-root!
  "Serve `/` — root's view (the all-agents dashboard).

   `root = /`
   `/` is the root agent's view, so it reuses the per-agent
   shim page bound to the literal id \"root\". The page's feed effect opens
   `/agent/root/feed` (already seeded), whose `agent-view` renders root's
   canvas = `seon.render.system/system-view` (root's seeded canvas content).
   ONE mechanism — no `/`-special page, no `/`-special feed. A Ring handler:
   injects the `\"root\"` path-param and delegates to [[serve-agent-page!]].
   Public — db->routes resolves its symbol at request time."
  [r]
  (serve-agent-page! (assoc-in r [:path-params :id] "root")))

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
  (ensure-installed!)
  (let [^js req (:seon.http/node-req r)
        ^js res (:seon.http/node-res r)
        id      (get-in r [:path-params :id])]
    (if (and (safe-id? id) (agent-exists? id))
      (let [t (parse-t req)]
        (if t
          (let [frozen (db/as-of @db/*conn* t)]
            (open-feed!
              req res
              {:seon.web.feed/key [:agent id :as-of t]
               :seon.web.feed/live? false
               :seon.web.feed/render-full
               #(agent-view/agent-view frozen id)
               :seon.web.feed/render-change (constantly [])}))
          (open-feed!
            req res
            {:seon.web.feed/key [:agent id]
             :seon.web.feed/live? true
             :seon.web.feed/render-full
             #(agent-view/agent-view @db/*conn* id)
             :seon.web.feed/render-change
             (fn [{dbv :seon.db/db attrs :seon.db/changed-attrs}]
               (agent-view/agent-view-changes dbv id attrs))})))
      (do (.writeHead res 404 #js {"Content-Type" "text/plain; charset=utf-8"
                                   "Cache-Control" "no-store"})
          (.end res "unknown agent id")))))

(defn serve-agents-page!
  "Serve GET /agents — the live agent roster shim page.

   The seeded :seon.route/agents handler. A Ring handler: self-extracts the
   node res. Public — db->routes resolves its symbol at request time."
  [r]
  (let [^js res (:seon.http/node-res r)]
    (.writeHead res 200 #js {"Content-Type"  "text/html; charset=utf-8"
                             "Cache-Control" "no-store, no-cache, must-revalidate"})
    (.end res (agents-page-html))))

(defn open-roster-feed!
  "Open GET /agents/feed — the roster gzip feed bound to [[roster-view]].

   The seeded :seon.route/agents-feed handler — the SAME whole-`#app-view` morph
   engine the per-agent view rides, so a new/terminated agent appears/updates
   live. A Ring handler: self-extracts node-req/node-res; lazily installs the
   tx-listener. Public — db->routes resolves its symbol."
  [r]
  (ensure-installed!)
  (let [^js req (:seon.http/node-req r)
        ^js res (:seon.http/node-res r)]
    (open-feed! req res
                {:seon.web.feed/key [:roster]
                 :seon.web.feed/live? true
                 :seon.web.feed/render-full #(roster-view @db/*conn*)
                 :seon.web.feed/render-change
                 (fn [{dbv :seon.db/db}] [(roster-view dbv)])})))
