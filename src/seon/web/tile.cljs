(ns seon.web.tile
  "The tile system — the decoupled interactive-feeds POC (spec:
   docs/prds/agent-fsm/interactive-feeds.md).

   ONE composable primitive: a *tile* is a region bound to a feed rendering a
   pure view over `seon.derive` / the local db value. Everything the human sees
   is a tile — the agent's hero render, commentary, status, todos — differing
   only in which view they render and how big they are. A page is a shell of
   tiles; the `console` lays out ~2/3 hero + ~1/3 rail of tiles.

   PURE READ — it consumes `seon.derive` + reads the local db value and never
   writes. Writes (interactions, the REPL) route through the R-owned `/call`
   family.

   Client contract: the browser loads `resources/public/js/packetstar.js` — one
   `EventSource` per tile region (NOT datastar), `innerHTML`-replace on each
   message, `data-action`→POST for interactions. The SSE payload is just the
   tile's inner HTML (the target is implicit in which stream the client opened).

   Decoupled from the live `seon.web.inspector` transport on purpose (NEW
   `/tile/*` routes). At integration this SUPERSEDES the inspector transport —
   one engine driving every surface; not two transports permanently.

   A tile-key is `[kind agent-id]` (e.g. `[:agent-render \"vKt-…\"]`)."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.log :as log]
    [seon.render :as render]
    [seon.ui.components :as comp]
    [seon.ui.html :as html]))

;; ============================================================
;; Connection registry — tile-key -> [{:id :res :opened-at}].
;; ============================================================

(defonce ^:private !tiles (atom {}))

(defn- add-conn! [k conn]
  (swap! !tiles update k (fnil conj []) conn))

(defn- remove-conn! [k conn-id]
  ;; Drop the key entirely when its last connection closes, so `on-tx` never
  ;; re-renders a tile nobody is watching (avoids unbounded dead-key DB reads).
  (swap! !tiles (fn [m]
                  (let [cs (vec (remove #(= (:id %) conn-id) (get m k)))]
                    (if (seq cs) (assoc m k cs) (dissoc m k))))))

(defn- open-tile-keys []
  (set (keys @!tiles)))

(defn- clip [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

;; ============================================================
;; Tile views — pure (db-value) -> hiccup. Time-travels for free (pass
;; (db/as-of db t)). Phosphor Terminal theme; classes are LITERAL so Tailwind's
;; source scan keeps them in output.css.
;; ============================================================

(defn agent-tile
  "The agent's status tile — pure (db, agent-id) -> hiccup over the explicit-db
   `seon.derive` reads (NOT `derive-status`, which reads HEAD internally)."
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

(defn commentary-tile
  "The shared REPL transcript (demoted chat) — the agent's recent messages, a
   running log. Pure read: a Datalog query for messages to/from the agent."
  [db agent-id]
  (let [me   (:db/id (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id agent-id]}))
        rows (when me
               (db/query {:seon.db/db db
                          :seon.db/query
                          '[:find ?at ?origin ?content
                            :in $ ?me
                            :where
                            (or [?m :seon.agent.message/to ?me]
                                [?m :seon.agent.message/from ?me])
                            [?m :seon.agent.message/at ?at]
                            [?m :seon.agent.message/origin ?origin]
                            [?m :seon.agent.message/content ?content]]
                          :seon.db/args [me]}))
        recent (->> rows (sort-by #(.getTime ^js (first %))) (take-last 8))]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "text-2xs uppercase tracking-wider text-text-400 mb-2"} "commentary"]
     (if (seq recent)
       (into [:div {:class "flex flex-col gap-1"}]
             (for [[_ origin content] recent]
               [:div {:class "text-xs leading-tight"}
                [:span {:class (str "mr-1 font-medium "
                                    (case origin
                                      :human "text-info"
                                      :agent "text-eval"
                                      "text-text-500"))}
                 (case origin :human "›you" :agent "‹agent" "·core")]
                [:span {:class "text-text-200"} (clip content 120)]]))
       [:div {:class "text-xs text-text-500"} "no messages yet"])]))

(defn todos-tile
  "The agent's todos — pure Datalog read by owner. Open first."
  [db agent-id]
  (let [me   (:db/id (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id agent-id]}))
        rows (when me
               (db/query {:seon.db/db db
                          :seon.db/query
                          '[:find ?status ?title
                            :in $ ?me
                            :where
                            [?t :seon.agent.todo/owner ?me]
                            [?t :seon.agent.todo/status ?status]
                            [?t :seon.agent.todo/title ?title]]
                          :seon.db/args [me]}))
        open (count (filter #(= :open (first %)) rows))]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "flex items-center justify-between mb-2"}
      [:div {:class "text-2xs uppercase tracking-wider text-text-400"} "todos"]
      [:span {:class "text-2xs text-text-500"} (str open " open")]]
     (if (seq rows)
       (into [:div {:class "flex flex-col gap-1"}]
             (for [[status title] (sort-by #(if (= :open (first %)) 0 1) rows)]
               [:div {:class "text-xs leading-tight flex items-start gap-1.5"}
                [:span {:class (if (= :open status) "text-warning" "text-success")}
                 (if (= :open status) "☐" "☑")]
                [:span {:class (if (= :open status)
                                 "text-text-200"
                                 "text-text-500 line-through")}
                 (clip title 80)]]))
       [:div {:class "text-xs text-text-500"} "no todos"])]))

(defn- hero-tile
  "The hero — the agent's OWN live tile (welcome default or wired content),
   rendered SCI-bounded by `seon.render/render-agent-tile`. That returns a
   `:seon.render/html-response` map; take its `:seon.render/hiccup`."
  [db agent-id]
  [:div {:class "rounded border border-base-700 bg-base-850 p-4 h-full overflow-auto"}
   (or (:seon.render/hiccup
         (render/render-agent-tile {:seon.db/db db :seon.agent/id agent-id}))
       [:div {:class "text-text-500 text-xs"} "no tile"])])

(defn- render-tile
  "Render a tile-key `[kind agent-id]` to hiccup at HEAD. Per-tile fault
   isolation: a throwing view becomes an error card, never a hung region."
  [tile-key]
  (try
    (let [[kind arg] tile-key
          db @db/*conn*]
      (case kind
        :agent        (agent-tile db arg)
        :agent-render (hero-tile db arg)
        :commentary   (commentary-tile db arg)
        :todos        (todos-tile db arg)
        [:div {:class "text-text-500 text-xs"} (str "unknown tile " (pr-str tile-key))]))
    (catch :default e
      [:div {:class "rounded border border-error bg-base-850 p-3 text-xs text-error"}
       (str "render error: " (ex-message e))])))

;; ============================================================
;; SSE payload — one EventSource per region, target implicit; payload is the
;; tile's inner HTML as a default `message` event (packetstar does
;; `el.innerHTML = e.data`).
;; ============================================================

(defn- region-event [hiccup]
  (let [s (html/->string hiccup)]
    (str "data: " (str/replace s "\n" "\ndata: ") "\n\n")))

;; ============================================================
;; Push pipeline — inspector's SHAPE (per-key 100ms trailing coalescer +
;; db/listen! tx-listener), generalized to tile-keys. No server-side change-hash
;; in the POC: re-render all open tiles on each tx; the client absorbs no-ops.
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
  "Any commit re-renders every open tile (client drops no-ops). Per-tile
   basis-t / fingerprint dedup is a later slice."
  [_]
  (doseq [k (open-tile-keys)]
    (schedule-push! k)))

;; ============================================================
;; Lifecycle — db/listen! IS the refresh signal. Distinct key from inspector.
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
;; HTTP — page shells + per-tile SSE streams on NEW /tile/* routes. serve.cljs
;; delegates via route?/handle!.
;; ============================================================

(defn- write-html! [^js res code body]
  (.writeHead res code #js {"Content-Type"  "text/html; charset=utf-8"
                            "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res body))

(defn- head [title]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title title]
   [:link {:rel "stylesheet" :href "/css/output.css"}]
   [:script {:src "/js/packetstar.js" :defer true}]])

(defn- region
  "A tile region: a stable-id element whose `data-tile` points at its SSE
   stream. packetstar opens the stream and innerHTML-replaces it per message."
  [id stream-url]
  [:div {:id id :data-tile stream-url :class "min-h-0"}
   [:div {:class "text-text-500 text-xs"} "connecting…"]])

(defn- shell
  "Single-tile page (the agent status tile)."
  [agent-id]
  (str "<!DOCTYPE html>"
       (html/->string
         [:html {:lang "en"}
          (head (str "tile · " agent-id))
          [:body {:class "bg-base-950 text-text-200 font-mono p-3"}
           [:div {:class "max-w-md"}
            (region (str "tile-agent-" agent-id) (str "/tile/agent/" agent-id "/sse"))]]])))

(defn- header-bar
  "The console masthead — identity + a global live indicator + a fullscreen
   link to the hero alone."
  [agent-id]
  [:div {:class "flex items-center justify-between mb-3 pb-2 border-b border-base-800"}
   [:div {:class "flex items-baseline gap-2"}
    [:span {:class "text-sm font-semibold text-text-50"} "seon"]
    [:span {:class "text-2xs text-text-500"} agent-id]]
   [:div {:class "flex items-center gap-3"}
    [:span {:class "inline-flex items-center gap-1 text-2xs text-success"}
     [:span {:class "w-1.5 h-1.5 rounded-full bg-success animate-pulse"}] "live"]
    [:a {:class "text-2xs text-amber-400 hover:text-amber-300"
         :href  (str "/tile/agent/" agent-id "/full")} "⛶ fullscreen"]]])

(defn- console-shell
  "The console — masthead + ~2/3 hero tile + ~1/3 rail of tiles. Every slot is a
   tile (the composability the feature exists to prove)."
  [agent-id]
  (str "<!DOCTYPE html>"
       (html/->string
         [:html {:lang "en"}
          (head (str "console · " agent-id))
          [:body {:class "bg-base-950 text-text-200 font-mono p-3"}
           (header-bar agent-id)
           [:div {:class "grid grid-cols-3 gap-3"
                  :style "height: calc(100vh - 4rem)"}
            ;; hero — 2/3
            [:div {:class "col-span-2 min-h-0"}
             (region (str "tile-hero-" agent-id) (str "/tile/agent/" agent-id "/render/sse"))]
            ;; rail — 1/3, stacked tiles
            [:div {:class "col-span-1 flex flex-col gap-3 min-h-0 overflow-auto"}
             (region (str "tile-status-" agent-id) (str "/tile/agent/" agent-id "/sse"))
             (region (str "tile-todos-" agent-id) (str "/tile/agent/" agent-id "/todos/sse"))
             (region (str "tile-commentary-" agent-id) (str "/tile/agent/" agent-id "/commentary/sse"))]]]])))

(defn- hero-shell
  "The fullscreen hero — the agent's tile alone, full-bleed (the immersive
   mode for when the user trusts the agent and wants only its surface)."
  [agent-id]
  (str "<!DOCTYPE html>"
       (html/->string
         [:html {:lang "en"}
          (head (str "tile · " agent-id))
          [:body {:class "bg-base-950 text-text-200 font-mono p-3"}
           [:div {:class "h-screen"}
            (region (str "tile-hero-" agent-id) (str "/tile/agent/" agent-id "/render/sse"))]]])))

(defn- tile-key
  "Path kind segment -> a tile-key. Absent kind = the status tile."
  [agent-id kind]
  [(case kind
     nil          :agent
     "render"     :agent-render
     "commentary" :commentary
     "todos"      :todos
     (keyword kind))
   agent-id])

(defn- open-tile-sse! [^js req ^js res tk]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [conn {:id (random-uuid) :res res :opened-at (js/Date.)}]
    (add-conn! tk conn)
    (log/info-console! "seon.web.tile" "SSE OPEN"
                       {:tile tk :conn-id (str (:id conn))
                        :total (count (get @!tiles tk))})
    (.on req "close"
         (fn []
           (remove-conn! tk (:id conn))
           (log/info-console! "seon.web.tile" "SSE CLOSE" {:conn-id (str (:id conn))})))
    (try (.write res (region-event (render-tile tk)))
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
    (re-matches #"/tile/console/[^/]+" path)
    (do (write-html! res 200 (console-shell (second (re-matches #"/tile/console/([^/]+)" path))))
        true)

    (re-matches #"/tile/agent/[^/]+/full" path)
    (do (write-html! res 200 (hero-shell (second (re-matches #"/tile/agent/([^/]+)/full" path))))
        true)

    (re-matches #"/tile/agent/[^/]+(?:/[^/]+)?/sse" path)
    (let [[_ id kind] (re-matches #"/tile/agent/([^/]+)(?:/([^/]+))?/sse" path)]
      (open-tile-sse! req res (tile-key id kind))
      true)

    (re-matches #"/tile/agent/[^/]+" path)
    (do (write-html! res 200 (shell (second (re-matches #"/tile/agent/([^/]+)" path))))
        true)

    :else false))
