(ns seon.web.datastar
  "Datastar gzip-morph SSE streamer — the hyperlith `view = f(db)` model
   ported into the pod.

   ONE render fn produces the whole view; datastar's `idiomorph` diffs the
   DOM client-side, so a re-render that pushes the whole element MORPHS only
   what changed. The stream is long-lived and gzip-compressed: every
   datahike commit re-renders `view = f(db)` and writes a
   `datastar-patch-elements` event (flushed immediately) to every open
   stream — a single whole-element morph (no per-tile `{id,html}` streaming).

   ## The two routes (added additively to seon.web.serve's dispatch)

     GET /world       → the shim page: loads datastar.js + opens the feed
                        via `data-init=\"@get('/world/feed')\"`, with an
                        empty `<main id=\"world\">` morph target.
     GET /world/feed  → the long-lived gzip SSE stream. On open it sends an
                        initial paint; thereafter every tx broadcasts a
                        whole-`#world` morph.

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
    [seon.ui.header :as header]
    [seon.ui.html :as html]
    [seon.ui.world :as world]
    [seon.web.brand :as brand]))

;; ============================================================
;; Connection registry — every open gzip stream (a /world roster or a
;; /agent/{id} world). Each entry carries its OWN `:view-fn` (a 0-arg
;; thunk → hiccup, bound to its route's params) so a single commit
;; re-renders DIFFERENT views per connection.
;; ============================================================

(defonce ^{:doc "Vector of `{:id <uuid> :gz <Gzip> :res <ServerResponse>
                  :view-fn <0-arg thunk → hiccup> :opened-at <Date>}` — one
                  entry per open feed. The tx-listener re-renders EACH
                  connection's own view and morphs it."}
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

;; ============================================================
;; view = f(db) — the live agent roster as `[:main#world …tiles…]`.
;; ============================================================

(defn- agent-tile
  "One roster tile for `id` — a LINK to that agent's world (`/agent/<id>`),
   showing its id + DERIVED FSM state. `derive-state` is guarded so a single
   bad agent can never abort the whole-view render. The `<a>` makes the roster
   navigable (P1) instead of a set of dead `<li>`s; `text-signal` marks it as a
   link in the Phosphor palette."
  [db id]
  (let [state (try (derive/derive-state db id) (catch :default _ :unknown))]
    [:li {:id (str "world-agent-" id) :class "world-tile"}
     [:a {:href (str "/agent/" id) :class "world-tile-id text-signal"}
      id
      [:span {:class "world-tile-state"} (str " ● " (name state))]]]))

(defn world-view
  "view = f(db): the live agent roster as `[:main#world …tiles…]`.

   Pure of external state — reads only the supplied db value, so the same
   db always renders the same hiccup. NEVER throws (the whole-view morph
   engine must be crash-proof): a render error degrades to a visible error
   tile inside `#world`."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]] :any]}
  [db]
  (try
    (let [ids (->> (db/query {:seon.db/db    db
                              :seon.db/query '[:find ?id
                                               :where [?a :seon.agent/id ?id]]})
                   (map first)
                   sort)]
      [:main {:id "world" :class "world"}
       (header/system-header db)
       header/header-spacer
       [:h1 {:class "world-title"} "Seon world"]
       [:div {:id "world-count" :class "world-count"}
        (str (count ids) " agent" (when (not= 1 (count ids)) "s"))]
       [:ul {:id "world-roster" :class "world-roster"}
        (for [id ids]
          (agent-tile db id))]])
    (catch :default e
      [:main {:id "world" :class "world"}
       [:div {:id "world-error" :class "world-error"}
        (str "render error: " (.-message e))]])))

(defn- view-fn-patch
  "Render a connection's bound 0-arg `view-fn` → its `#world` SSE morph
   string. GUARDED: a throwing view degrades to a visible `#world-error`
   morph so one bad view never aborts the whole broadcast."
  [view-fn]
  (-> (try
        (view-fn)
        (catch :default e
          [:main {:id "world"}
           [:div {:id "world-error" :class "text-error text-xs font-mono"}
            (str "render error: " (.-message e))]]))
      html/->string
      patch-elements))

;; ============================================================
;; Per-connection push + broadcast. Each connection renders its OWN
;; bound view (the /world roster vs a /agent/{id} world). Best-effort,
;; never throws.
;; ============================================================

(defn- push-conn!
  "Render `conn`'s OWN bound view and write it to its gzip stream, flushing
   so the bytes hit the wire immediately. Guards a closed stream; logs
   (never rethrows) on failure."
  [{:keys [gz res view-fn]}]
  (try
    (when-not (or (.-writableEnded ^js gz) (.-writableEnded ^js res))
      (.write ^js gz (view-fn-patch view-fn))
      (.flush ^js gz (.. zlib -constants -Z_SYNC_FLUSH)))
    (catch :default e
      (log/error-console! "seon.web.datastar" "push-conn! failed" e))))

(defn- broadcast!
  "Re-render EACH open feed's OWN bound view and morph it — per connection,
   so the /world roster and a /agent/{id} world reflect the same commit
   through different views."
  []
  (when (seq @!feeds)
    (let [conns @!feeds]
      (doseq [conn conns]
        (push-conn! conn))
      (log/info-console! "seon.web.datastar" "broadcast"
                         {:conns (count conns)}))))

;; ============================================================
;; Coalescing — one trailing timer collapses a tx burst into one morph
;; (an agent turn commits many datoms; the human sees ONE re-render).
;; ============================================================

(defonce ^:private !pending? (atom false))

(defn- schedule-broadcast! []
  (when-not @!pending?
    (reset! !pending? true)
    (js/setTimeout
      (fn []
        (reset! !pending? false)
        (broadcast!))
      50)))

;; ============================================================
;; Lifecycle — db/listen! IS the refresh signal.
;; ============================================================

(defn- on-tx [_] (schedule-broadcast!))

(defonce ^:private !installed? (atom false))

(defn install!
  "Install the world tx-listener. Idempotent — same key replaces."
  []
  (db/listen! {:seon.db/key ::world :seon.db/handler on-tx})
  (reset! !installed? true))

(defn uninstall!
  "Remove the world tx-listener."
  []
  (db/unlisten! {:seon.db/key ::world})
  (reset! !installed? false))

(defn- ensure-installed! []
  (when-not @!installed? (install!)))

(defn ^:dev/before-load before-reload
  "Uninstall the world tx-listener before a hot reload."
  []
  (try (uninstall!) (catch :default _ nil)))

(defn ^:dev/after-load after-reload
  "Reinstall the world tx-listener after a hot reload."
  []
  (try (install!) (catch :default _ nil)))

;; ============================================================
;; HTTP handlers — called from seon.web.serve when route? matched.
;; ============================================================

(defn- shim-html
  "The datastar world-shim page as a raw HTML string, BRAND-AWARE: the
   <head> routes through the seon.web.brand seams — the brand <title> via
   `page-title`, `data-theme` from the brand row, and the optional
   SEON_BRAND_CSS inlined AFTER output.css — so a downstream deploy's
   branding reaches the world page users actually navigate to (not just
   the inspector). Absent brand row + env → the shipped seon defaults.

   The shim itself: load datastar.js, open the long-lived feed via
   `data-init`, and present an empty `<main id=\"world\">` for the feed's
   first morph to fill. `title-suffix` is the brand-name suffix (\"world\",
   \"agent <id>\"); `feed-url` the data-init SSE URL — or `nil` to OMIT
   data-init, when the page's `extra-body` opens the feed itself (the
   /agent/{id} time-travel bar owns its feed via a single `data-effect` so it
   can re-open at a past `t`; a data-init here would be a second, competing
   stream). `extra-body` is a raw HTML string spliced in as a SIBLING of
   `<main id=\"world\">` (a human input — chat / new-agent / time-travel — that
   must live OUTSIDE the morphed `#world` so the feed's whole-element morph
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
         "<main id=\"world\""
         (when feed-url
           (str " data-init=\"@get('" feed-url
                "', {retryMaxCount: Infinity, openWhenHidden: false})\""))
         ">loading…</main>\n"
         extra-body
         "</body></html>")))

;; ============================================================
;; Human input bars — the surfaces the world page needs so a human can
;; OPERATE (not just observe). Each lives OUTSIDE `<main id=\"world\">` (a
;; SIBLING in <body>) so the feed's whole-`#world` morph never clobbers the
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
    (list
      ;; Spacer — reserves scroll room equal to the fixed bar's height so the
      ;; bar never hides the agent's last tile. Inline style (no Tailwind
      ;; height class is in the safelisted/built vocabulary).
      [:div {:style "height:3.25rem"}]
      [:form {:id                     "world-chat"
              (keyword "data-on:submit") (str "@post('/chat?agent=" id
                                              "', {contentType:'form'}); $text=''")
              :class "fixed bottom-0 left-0 right-0 z-10 flex items-center gap-2 border-t border-base-800 bg-base-900 px-3 py-2"}
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
        "send"]])))

(defn- time-travel-bar-html
  "#18 — historical time-travel on the world feed for `/agent/{id}`, as a raw
   HTML string. The live feed is `view = f(db)`; time-travel is the SAME feed
   rendered against `db-as-of-t` — a PAST snapshot that is naturally FROZEN
   (re-rendering `db-as-of-t` on a later tx yields identical bytes).

   A FIXED bar (a SIBLING of `#world`, OUTSIDE the morph so a whole-`#world`
   morph never clobbers the slider position/focus) OWNS the agent feed via ONE
   `data-effect` `@get`: datastar re-runs the effect when its referenced
   signals change AND auto-cancels the prior `@get` issued from the SAME
   attribute, so exactly ONE gzip stream targets `#world` at any time. (This is
   why the /agent shim omits `data-init` on `#world` — the effect is the SOLE
   opener; a data-init would be a second, competing stream that clobbers the
   frozen snapshot on the next live tx.)

   Signals: `$live` (true ⇒ the current auto-morphing feed, UNCHANGED), `$t`
   (the slider's live scrub position, for the readout), `$ct` (the COMMITTED
   tx-id the feed actually opens at — set on slider release so a drag doesn't
   open a stream per intermediate tick). Live ⇒ `@get('…/feed')`; scrubbing
   commits `$ct` + flips `$live` false ⇒ `@get('…/feed?t='+$ct)`. The domain is
   `[origin-t .. basis-t]` (datahike tx-ids; scrub to the floor = the empty
   pre-seed world); 'now / live' resets the slider to the basis + re-opens the
   live feed. `id` is `safe-id?`-validated, injection-safe in `@get('…')`.

   MINIMAL by intent — a raw tx-id slider + a live/as-of readout. The owner
   refines the timeline UX (human-readable timestamps, tick marks, a diff)."
  [id basis floor]
  (let [feed (str "/agent/" id "/feed")
        opts "{retryMaxCount: Infinity, openWhenHidden: false}"]
    (html/->string
      (list
        ;; Spacer — reserves scroll room above the chat bar (which sits at
        ;; bottom:0 and reserves its own 3.25rem) so neither fixed bar hides a
        ;; tile. Inline height (no Tailwind height class is in the built vocab).
        [:div {:style "height:3.25rem"}]
        [:div {:id "world-time"
               ;; The SOLE feed opener. Re-runs on $live/$ct change; each @get
               ;; auto-cancels the prior from THIS attribute → one stream.
               :data-effect (str "$live ? @get('" feed "', " opts ")"
                                 " : @get('" feed "?t=' + $ct, " opts ")")
               :data-signals (str "{t: " basis ", ct: " basis ", live: true}")
               ;; bottom:3.25rem (inline) stacks this bar above the chat bar;
               ;; fixed/left-0/right-0/z-10 are in the built vocabulary.
               :style "bottom:3.25rem"
               :class "fixed left-0 right-0 z-10 flex items-center gap-2 border-t border-base-800 bg-base-900 px-3 py-2"}
         [:span {:data-text  "$live ? '● live' : '⏸ as-of t=' + $ct"
                 :data-class "{'text-signal': $live, 'text-warning': !$live}"
                 :class      "text-xs font-mono shrink-0 w-32"}]
         [:input {:type      "range"
                  :min       floor
                  :max       basis
                  :data-bind "t"
                  ;; Commit on release (not on every input tick): set the as-of
                  ;; value + leave live mode. The effect re-opens at $ct.
                  (keyword "data-on:change") "$ct = $t; $live = false"
                  :class     "flex-1"}]
         [:button {:type  "button"
                   (keyword "data-on:click") (str "$live = true; $t = " basis "; $ct = " basis)
                   :class "bg-base-800 hover:bg-base-700 text-signal border border-base-700 px-3 py-1 rounded text-xs font-mono shrink-0"}
          "now / live"]]))))

(defn- new-agent-bar-html
  "P1c — the `/world` roster's new-agent affordance, as a raw HTML string.

   A fixed bottom bar (outside the morph) whose button INLINE-FETCH POSTs the
   existing `/agents/new` (optional form-urlencoded `purpose=`) then navigates
   to the new `/agent/<id>` on the 200 id-body. Inline JS (not datastar
   `@post`) because the response is the new id as plain text that we must READ
   and navigate to — copied from the inspector mission-control button. The
   endpoint is same-origin-gated and serializes creates (409 while one is in
   flight); errors land in the button's own text, never swallowed."
  []
  (html/->string
    (list
      [:div {:style "height:3.25rem"}]
      [:div {:id    "world-new-agent"
             :class "fixed bottom-0 left-0 right-0 z-10 flex items-center gap-2 border-t border-base-800 bg-base-900 px-3 py-2"}
       [:input {:id           "world-new-agent-purpose"
                :type         "text"
                :autocomplete "off"
                :placeholder  "purpose (optional)…"
                :class "flex-1 bg-base-950 border border-base-800 rounded px-2 py-1 text-text-100 text-xs font-mono"}]
       [:button {:id      "world-new-agent-btn"
                 :type    "button"
                 :class   "bg-base-800 hover:bg-base-700 text-signal border border-base-700 px-3 py-1 rounded text-xs font-mono"
                 :onclick (str "var b=this;b.disabled=true;b.textContent='booting…';"
                               "var p=document.getElementById('world-new-agent-purpose');"
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

(defn- world-page-html
  "The /world roster shim page (brand-aware head — see [[shim-html]]). Carries
   the new-agent bar (P1c) as its OUTSIDE-the-morph human affordance."
  []
  (shim-html "world" "bg-base-900 text-text-200 font-mono p-3" "/world/feed"
             (new-agent-bar-html)))

(defn- serve-world-page! [^js res]
  (.writeHead res 200 #js {"Content-Type"  "text/html; charset=utf-8"
                           "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res (world-page-html)))

(defn- open-feed!
  "Open a long-lived gzip-compressed SSE stream bound to `view-fn` (a 0-arg
   thunk → hiccup), register it, send the initial paint, and clean up on
   close."
  [^js req ^js res view-fn]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream; charset=utf-8"
                           "Content-Encoding"  "gzip"
                           "Cache-Control"     "no-store"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (let [gz   (.createGzip zlib)
        id   (random-uuid)
        conn {:id id :gz gz :res res :view-fn view-fn :opened-at (js/Date.)}]
    (.on gz "error"  (fn [e] (log/error-console! "seon.web.datastar" "gz error" e)))
    (.on res "error" (fn [e] (log/error-console! "seon.web.datastar" "res error" e)))
    (.pipe gz res)
    (swap! !feeds conj conn)
    (log/info-console! "seon.web.datastar" "FEED OPEN"
                       {:conn-id (str id) :total (count @!feeds)})
    ;; First paint immediately so the page populates without waiting for a tx.
    (push-conn! conn)
    (.on req "close"
         (fn []
           (swap! !feeds (fn [cs] (vec (remove #(= (:id %) id) cs))))
           (try (.end gz) (catch :default _ nil))
           (log/info-console! "seon.web.datastar" "FEED CLOSE"
                              {:conn-id (str id) :remaining (count @!feeds)})))))

;; ============================================================
;; Per-agent world (/agent/{id}) — the shim page + the feed bound to
;; that agent's `world-layout`. The router calls these public entries
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
   reset store, a typo) redirects HOME rather than serving an empty world."
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
  "The per-agent (/agent/{id}) world shim page (brand-aware head — see
   [[shim-html]]). `id` is pre-validated by `safe-id?`.

   Omits `data-init` on `#world` (passes `nil` feed-url): the time-travel bar
   owns the feed via its single `data-effect` (see [[time-travel-bar-html]]) so
   it can re-open at a past `t`. The slider's `[floor .. basis]` domain is read
   from the live db here (guarded — a missing conn degenerates to the origin,
   the page still serves + the effect still opens the live feed)."
  [id]
  (let [basis (try (db/basis-t @db/*conn*) (catch :default _ db/origin-t))]
    (shim-html (str "agent " id)
               "bg-base-950 text-text-200 font-mono p-3"
               nil
               (str (chat-form-html id)
                    (time-travel-bar-html id basis db/origin-t)))))

(defn serve-agent-page!
  "Serve the per-agent world shim page (the seeded :seon.route/agent handler).
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
      ;; typo) gracefully redirects HOME rather than serving an empty world or
      ;; a raw 404. "root" always resolves (seeded), so it is never redirected.
      (not (agent-exists? id))
      (do (.writeHead res 302 #js {"Location" "/" "Cache-Control" "no-store"})
          (.end res ""))
      :else
      (do (.writeHead res 200 #js {"Content-Type"  "text/html; charset=utf-8"
                                   "Cache-Control" "no-store, no-cache, must-revalidate"})
          (.end res (agent-page-html id))))))

(defn serve-root!
  "Serve `/` — root's world (the all-agents dashboard).

   `root = /`
   (root-os-vision): `/` is the root agent's world, so it reuses the per-agent
   shim page bound to the literal id \"root\". The page's feed effect opens
   `/agent/root/feed` (already seeded), whose `world-layout` renders root's
   canvas = `seon.render.system/system-view` (root's seeded live-tile content).
   ONE mechanism — no `/`-special page, no `/`-special feed. A Ring handler:
   injects the `\"root\"` path-param and delegates to [[serve-agent-page!]].
   Public — db->routes resolves its symbol at request time."
  [r]
  (serve-agent-page! (assoc-in r [:path-params :id] "root")))

(defn open-agent-feed!
  "Open the per-agent world gzip feed.

   The seeded :seon.route/agent-feed handler. A Ring handler: takes the
   Ring request `r`, self-extracts node-req/node-res + the `{id}` path-param.
   Lazily installs the tx-listener (idempotent). Invalid ids 404. Public —
   db->routes resolves its symbol.

   #18 — historical time-travel: an optional `?t=<tx-id>` binds the view to
   `db-as-of-t` instead of the live db. With `t`, the feed is the SAME
   `world-layout` rendered against `(db/as-of @*conn* t)` — a PAST snapshot that
   is naturally FROZEN (re-rendering it on a later tx yields identical bytes, so
   the broadcast harmlessly re-pushes the same #world). With NO `t` it is the
   current auto-morphing feed, UNCHANGED. A bad/absent `t` falls back to live."
  [r]
  (ensure-installed!)
  (let [^js req (:seon.http/node-req r)
        ^js res (:seon.http/node-res r)
        id      (get-in r [:path-params :id])]
    (if (safe-id? id)
      (let [t (parse-t req)]
        (open-feed! req res
                    (if t
                      #(world/world-layout (db/as-of @db/*conn* t) id)
                      #(world/world-layout @db/*conn* id))))
      (do (.writeHead res 404 #js {"Content-Type" "text/plain; charset=utf-8"})
          (.end res "invalid agent id")))))

(defn handle!
  "Dispatch a /world route (shim page or gzip SSE feed).

   The seeded :seon.route/world (shim page) +
   :seon.route/world-feed (gzip SSE feed) BOTH resolve here, routing on the
   path internally. A Ring handler: takes the Ring request `r`, self-extracts
   node-req/node-res/path. Lazily installs the tx-listener on first hit
   (idempotent). The router wraps this and appends the hijack sentinel, so the
   true/false return is ignored. Public — db->routes resolves its symbol."
  [r]
  (ensure-installed!)
  (let [^js req (:seon.http/node-req r)
        ^js res (:seon.http/node-res r)
        path    (:uri r)]
    (cond
      (= path "/world/feed") (do (open-feed! req res #(world-view @db/*conn*)) true)
      (= path "/world")      (do (serve-world-page! res) true)
      :else                  false)))
