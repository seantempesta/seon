(ns seon.web.datastar
  "Datastar gzip-morph SSE streamer — the hyperlith `view = f(db)` model
   ported into the pod.

   ONE render fn produces the whole view; datastar's `idiomorph` diffs the
   DOM client-side, so a re-render that pushes the whole element MORPHS only
   what changed. The stream is long-lived and gzip-compressed: every
   datahike commit re-renders `view = f(db)` and writes a
   `datastar-patch-elements` event (flushed immediately) to every open
   stream. This replaces packetstar's per-tile `{id,html}` streaming with a
   single whole-element morph.

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
  "One roster tile for `id` — its id + DERIVED FSM state. `derive-state` is
   guarded so a single bad agent can never abort the whole-view render."
  [db id]
  (let [state (try (derive/derive-state db id) (catch :default _ :unknown))]
    [:li {:id (str "world-agent-" id) :class "world-tile"}
     [:span {:class "world-tile-id"} id]
     [:span {:class "world-tile-state"} (str " ● " (name state))]]))

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

(defn uninstall! []
  (db/unlisten! {:seon.db/key ::world})
  (reset! !installed? false))

(defn- ensure-installed! []
  (when-not @!installed? (install!)))

(defn ^:dev/before-load before-reload []
  (try (uninstall!) (catch :default _ nil)))

(defn ^:dev/after-load after-reload []
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
   \"agent <id>\"); `feed-url` the data-init SSE URL. Raw string (not
   hiccup) so the data-init single quotes stay literal and the doctype
   leads."
  [title-suffix body-class feed-url]
  (let [b (brand/info)]
    (str "<!doctype html>\n"
         "<html lang=\"en\" data-theme=\"" (::brand/theme b) "\"><head><meta charset=\"utf-8\">\n"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
         "<title>" (brand/page-title b title-suffix) "</title>\n"
         "<link rel=\"stylesheet\" href=\"/css/output.css\">\n"
         (brand/css-style-tag)
         "<script type=\"module\" src=\"/js/datastar.js\"></script>\n"
         "</head>\n"
         "<body class=\"" body-class "\">\n"
         "<main id=\"world\" data-init=\"@get('" feed-url "')\">loading…</main>\n"
         "</body></html>")))

(defn- world-page-html
  "The /world roster shim page (brand-aware head — see [[shim-html]])."
  []
  (shim-html "world" "bg-base-900 text-text-200 font-mono p-3" "/world/feed"))

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

(defn- agent-page-html
  "The per-agent (/agent/{id}) world shim page (brand-aware head — see
   [[shim-html]]). `id` is pre-validated by `safe-id?`."
  [id]
  (shim-html (str "agent " id)
             "bg-base-950 text-text-200 font-mono p-3"
             (str "/agent/" id "/feed")))

(defn serve-agent-page!
  "Serve the per-agent world shim page for agent `id`. Public — the router
   calls it with the `{id}` path-param. Invalid ids 404."
  [^js res id]
  (if (safe-id? id)
    (do (.writeHead res 200 #js {"Content-Type"  "text/html; charset=utf-8"
                                 "Cache-Control" "no-store, no-cache, must-revalidate"})
        (.end res (agent-page-html id)))
    (do (.writeHead res 404 #js {"Content-Type" "text/plain; charset=utf-8"})
        (.end res "invalid agent id"))))

(defn open-agent-feed!
  "Open the per-agent world gzip feed bound to `#(world/world-layout @db id)`.
   Public — the router calls it with the `{id}` path-param. Lazily installs
   the tx-listener (idempotent). Invalid ids 404."
  [^js req ^js res id]
  (ensure-installed!)
  (if (safe-id? id)
    (open-feed! req res #(world/world-layout @db/*conn* id))
    (do (.writeHead res 404 #js {"Content-Type" "text/plain; charset=utf-8"})
        (.end res "invalid agent id"))))

(defn route?
  "True iff `path` is a /world route. `seon.web.serve` delegates here."
  [path]
  (or (= path "/world") (= path "/world/feed")))

(defn handle!
  "Dispatch a /world route. Returns true if handled. Lazily installs the
   tx-listener on first hit (idempotent)."
  [^js req ^js res path]
  (ensure-installed!)
  (cond
    (= path "/world/feed") (do (open-feed! req res #(world-view @db/*conn*)) true)
    (= path "/world")      (do (serve-world-page! res) true)
    :else                  false))
