(ns seon.web.tile
  "The tile system — the decoupled interactive-feeds POC (spec:
   docs/prds/agent-fsm/interactive-feeds.md).

   ONE composable primitive: a *tile* is a region bound to a feed rendering a
   pure view over `seon.derive`. Everything the human sees is a tile — the
   agent's hero render, commentary, status, todos, debug, data — differing only
   in which view they render and how big they are.

   This ns is the transport engine + the (first) tile view, kept together for
   the POC. It is PURE READ — it consumes `seon.derive` + reads the local db
   value and never writes. Writes (interactions, the REPL) route through the
   R-owned `/call` family.

   Client contract: the browser loads `resources/public/js/packetstar.js` — one
   `EventSource` per tile region (NOT datastar), `innerHTML`-replace on each
   message, `data-action`→POST for interactions. So the SSE payload is just the
   tile's inner HTML (the target is implicit in which stream the client opened).

   Decoupled from the live `seon.web.inspector` transport on purpose: it stands
   up its OWN registry on NEW `/tile/*` routes so it never collides with the live
   UI. At integration this SUPERSEDES the inspector transport (one engine driving
   every surface) — do not keep two transports permanently.

   Engine seam mirrors inspector (`!sse-by-agent` + `schedule-push!` + `on-tx`),
   generalized to an arbitrary tile-key `[kind arg]` (e.g. `[:agent \"QMn-…\"]`)."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.log :as log]
    [seon.ui.components :as comp]
    [seon.ui.html :as html]))

;; ============================================================
;; Connection registry — tile-key -> [{:id :res :opened-at}]. Generalizes
;; inspector's !sse-by-agent to an arbitrary tile-key.
;; ============================================================

(defonce ^:private !tiles (atom {}))

(defn- add-conn! [k conn]
  (swap! !tiles update k (fnil conj []) conn))

(defn- remove-conn! [k conn-id]
  (swap! !tiles update k (fn [cs] (vec (remove #(= (:id %) conn-id) cs)))))

(defn- open-tile-keys []
  (set (keys @!tiles)))

;; ============================================================
;; Tile views — pure (db-value) -> hiccup over seon.derive. Time-travels for
;; free: pass (db/as-of db t) as `db`. Phosphor Terminal theme; classes are
;; LITERAL strings so Tailwind's source scan keeps them in output.css.
;; ============================================================

(defn agent-tile
  "The agent's status tile — pure (db, agent-id) -> hiccup. Uses the
   explicit-db `seon.derive` reads (NOT `derive-status`, which reads HEAD
   internally and would not time-travel)."
  [db agent-id]
  (let [a     (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id agent-id]})
        state (derive/derive-state db agent-id)
        turns (derive/agent-turn-count db agent-id)]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "flex items-center justify-between mb-2"}
      (comp/status-dot state)
      [:span {:class "text-xs text-text-500 tabular-nums"} (str "turn " turns)]]
     [:div {:class "text-sm text-text-50 font-medium leading-tight"}
      (or (:seon.agent/purpose a) "—")]
     [:div {:class "text-2xs text-text-500 mt-1"} agent-id]]))

(defn- render-tile
  "Render a tile-key `[kind arg]` to hiccup at HEAD. (as-of pinning is a later
   slice — the view fn already takes an explicit db.)"
  [tile-key]
  (let [[kind arg] tile-key]
    (case kind
      :agent (agent-tile @db/*conn* arg)
      [:div {:class "text-text-500 text-xs"} (str "unknown tile " (pr-str tile-key))])))

;; ============================================================
;; SSE payload — one EventSource per region, so the target is implicit; the
;; payload is just the tile's inner HTML as a default `message` event. (No
;; datastar envelope — packetstar does `el.innerHTML = e.data`.)
;; ============================================================

(defn- region-event [hiccup]
  (let [s (html/->string hiccup)]
    (str "data: " (str/replace s "\n" "\ndata: ") "\n\n")))

;; ============================================================
;; Push pipeline — reuse inspector's SHAPE (per-key 100ms trailing coalescer +
;; db/listen! tx-listener), generalized to tile-keys. No server-side change-hash
;; in the POC: re-render all open tiles on each tx, the client absorbs no-ops.
;; ============================================================

(defn- push-tile! [tile-key]
  (try
    (let [payload (region-event (render-tile tile-key))]
      (doseq [{:keys [res]} (get @!tiles tile-key)]
        (try (.write res payload)
             (catch :default e
               (log/error-console! "seon.web.tile" "write failed" e)))))
    (catch :default e
      (log/error-console! "seon.web.tile" "push! threw" e))))

(defonce ^:private !pending (atom {}))

(defn- schedule-push! [tile-key]
  (let [was-pending? (get @!pending tile-key)]
    (swap! !pending assoc tile-key true)
    (when-not was-pending?
      (js/setTimeout
        (fn []
          (swap! !pending dissoc tile-key)
          (push-tile! tile-key))
        100))))

(defn- on-tx
  "Any commit re-renders every open tile (the client drops no-ops). A
   per-tile basis-t / fingerprint dedup is a later slice."
  [_]
  (doseq [k (open-tile-keys)]
    (schedule-push! k)))

;; ============================================================
;; Lifecycle — db/listen! IS the refresh signal. Distinct key from inspector's
;; ::inspector so both listen without collision.
;; ============================================================

(defonce ^:private !installed? (atom false))

(defn install! []
  (db/listen! {:seon.db/key ::listener :seon.db/handler on-tx})
  (reset! !installed? true))

(defn uninstall! []
  (db/unlisten! {:seon.db/key ::listener})
  (reset! !installed? false))

(defn- ensure-installed! []
  (when-not @!installed? (install!)))

(defn ^:dev/before-load before-reload []
  (try (uninstall!) (catch :default _ nil)))

(defn ^:dev/after-load after-reload []
  (try (install!) (catch :default _ nil)))

;; ============================================================
;; HTTP — the page shell + the per-tile SSE stream. Served on NEW /tile/* routes
;; (decoupled from the live inspector). `serve.cljs` delegates via route?/handle!.
;; ============================================================

(defn- write-html! [^js res code body]
  (.writeHead res code #js {"Content-Type"  "text/html; charset=utf-8"
                            "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res body))

(defn- shell
  "The boot HTML for one agent tile: load output.css + packetstar.js, declare
   ONE tile region whose `data-tile` points at its SSE stream. packetstar opens
   the stream and innerHTML-replaces the region on each message."
  [agent-id]
  (str "<!DOCTYPE html>"
       (html/->string
         [:html {:lang "en"}
          [:head
           [:meta {:charset "utf-8"}]
           [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
           [:title (str "tile · " agent-id)]
           [:link {:rel "stylesheet" :href "/css/output.css"}]
           [:script {:src "/js/packetstar.js" :defer true}]]
          [:body {:class "bg-base-950 text-text-200 font-mono p-3"}
           [:div {:class "max-w-md"}
            [:div {:id        (str "tile-agent-" agent-id)
                   :data-tile (str "/tile/agent/" agent-id "/sse")}
             [:div {:class "text-text-500 text-xs"} "connecting…"]]]]])))

(defn- open-tile-sse! [^js req ^js res agent-id]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [k    [:agent agent-id]
        conn {:id (random-uuid) :res res :opened-at (js/Date.)}]
    (add-conn! k conn)
    (log/info-console! "seon.web.tile" "SSE OPEN"
                       {:tile k :conn-id (str (:id conn))
                        :total (count (get @!tiles k))})
    (.on req "close"
         (fn []
           (remove-conn! k (:id conn))
           (log/info-console! "seon.web.tile" "SSE CLOSE" {:conn-id (str (:id conn))})))
    ;; Initial render so the region paints before the first tx.
    (try (.write res (region-event (render-tile k)))
         (catch :default e
           (log/error-console! "seon.web.tile" "initial render failed" e)))))

(defn route?
  "True iff `path` is a tile route. `seon.web.serve` delegates here."
  [path]
  (str/starts-with? path "/tile"))

(defn handle!
  "Tile route dispatcher. Returns true if handled. Lazily installs the
   tx-listener on first hit (idempotent)."
  [^js req ^js res path]
  (ensure-installed!)
  (cond
    (re-matches #"/tile/agent/[^/]+/sse" path)
    (let [agent-id (second (re-matches #"/tile/agent/([^/]+)/sse" path))]
      (open-tile-sse! req res agent-id)
      true)

    (re-matches #"/tile/agent/[^/]+" path)
    (let [agent-id (second (re-matches #"/tile/agent/([^/]+)" path))]
      (write-html! res 200 (shell agent-id))
      true)

    :else false))
