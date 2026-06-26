(ns seon.web.tile
  "The tile system — DB-DRIVEN interactive feeds (spec:
   docs/prds/agent-fsm/interactive-feeds.md, [[context-render]]).

   ONE composable primitive: a *tile* is a region bound to a feed that renders a
   view resolved FROM DATA. Two things are data, not hardcoded:

   - DISPATCH: a tile carries a `:seon.render/html` SYMBOL; render time resolves
     it — a core symbol calls direct, an AGENT-authored symbol runs SCI-bounded.
     So the agent (or a downstream consumer) changes what a tile renders by
     TRANSACTING a symbol — no code change. (The hero already works this way via
     `render-agent-tile` resolving the agent's `:seon.render.live-tile/content`.)
   - LAYOUT: `/tile/console/<agent>` renders by QUERYING `:seon.tile/*` entities
     (which tiles, order via `:seon.ctx/priority`, width via `:seon.tile/span`).
     Transact a different set → a totally different UI. Absent any tile data, a
     PREWRITTEN default layout applies (prewritten fns referenced by symbol — not
     hardcoded dispatch), so it works out-of-box and is overridable by data.

   PURE READ at render time — consumes `seon.derive` / the local db value and the
   stored symbols; never writes. Writing tile entities (composing a UI) and the
   boot-seed are the agent's / a consumer's job (via `/call` / eval) — not this ns.

   Client contract: `resources/public/js/packetstar.js` — one `EventSource` per
   tile region (NOT datastar), innerHTML-replace per message, `data-action`→POST.

   Schema note (flagged to R): `:seon.tile/*` is UI-data schema registered here by
   convention (schema colocated with the data it shapes); the boot-seed is R/agent
   lane. See coordination.md _Interface changes_."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.log :as log]
    [seon.render :as render]
    [seon.ui.components :as comp]
    [seon.ui.html :as html]))

;; ============================================================
;; Tile-config entities (`:seon.tile/*`) drive the layout when present. The
;; schema is registered by R (schema lane) at integration — see coordination.md
;; _Needs_. Until then `console-tiles` falls back to the prewritten default, and
;; the queries tolerate the un-installed attrs (try/catch). The render DISPATCH
;; is already DB-driven: each tile stores its view as a `:seon.render/html`
;; SYMBOL, resolved at render time (core via `core-views`, agent via SCI later).
;; ============================================================

;; ============================================================
;; Connection registry — tile-id -> [{:id :res :opened-at}].
;; ============================================================

(defonce ^:private !tiles (atom {}))

(defn- add-conn! [k conn]
  (swap! !tiles update k (fnil conj []) conn))

(defn- remove-conn! [k conn-id]
  ;; Drop the key when its last connection closes, so `on-tx` never re-renders a
  ;; tile nobody is watching.
  (swap! !tiles (fn [m]
                  (let [cs (vec (remove #(= (:id %) conn-id) (get m k)))]
                    (if (seq cs) (assoc m k cs) (dissoc m k))))))

(defn- clip [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- agent-eid
  "The agent's entity id via a QUERY (works on an `as-of` db; `db/entity`/
   lookup-refs do NOT — `-lookup is not supported on AsOfDB`). Time-travel-safe."
  [db agent-id]
  (db/query {:seon.db/db db
             :seon.db/query '[:find ?e . :in $ ?a :where [?e :seon.agent/id ?a]]
             :seon.db/args [agent-id]}))

;; ============================================================
;; Prewritten view fns — referenced by SYMBOL from tile entities. Each takes the
;; injected input map {:seon.db/db :seon.agent/id} and returns hiccup. An
;; agent-authored view (its own symbol) follows the same convention, SCI-bounded.
;; Phosphor Terminal theme; classes are LITERAL (Tailwind source scan).
;; ============================================================

(defn status-view
  "The agent's status tile."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [a     (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})
        state (derive/derive-state db id)
        turns (derive/agent-turn-count db id)]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "flex items-center justify-between mb-2"}
      (comp/status-dot state)
      [:span {:class "text-xs text-text-500 tabular-nums"} (str "turn " turns)]]
     [:div {:class "text-sm text-text-50 font-medium leading-tight"}
      (or (:seon.agent/purpose a) "—")]
     [:div {:class "text-2xs text-text-500 mt-1"} id]]))

(defn commentary-view
  "The shared REPL transcript (demoted chat) — recent messages to/from the agent."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [me     (agent-eid db id)          ; query-based → time-travel-safe
        rows   (when me
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
        recent (->> rows (sort-by #(.getTime ^js (first %))) (take-last 8))
        line   (fn [[_ origin content]]
                 [:div {:class "text-xs leading-tight"}
                  [:span {:class (str "mr-1 font-medium "
                                      (case origin :human "text-info" :agent "text-eval"
                                        "text-text-500"))}
                   (case origin :human "›you" :agent "‹agent" "·core")]
                  [:span {:class "text-text-200"} (clip content 120)]])]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "text-2xs uppercase tracking-wider text-text-400 mb-2"} "commentary"]
     (if (seq recent)
       (into [:div {:class "flex flex-col gap-1"}] (map line recent))
       [:div {:class "text-xs text-text-500"} "no messages yet"])]))

(defn todos-view
  "The agent's todos — open first."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [me   (agent-eid db id)            ; query-based → time-travel-safe
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
        open (count (filter #(= :open (first %)) rows))
        row  (fn [[status title]]
               [:div {:class "text-xs leading-tight flex items-start gap-1.5"}
                [:span {:class (if (= :open status) "text-warning" "text-success")}
                 (if (= :open status) "☐" "☑")]
                [:span {:class (if (= :open status) "text-text-200" "text-text-500 line-through")}
                 (clip title 80)]])]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "flex items-center justify-between mb-2"}
      [:div {:class "text-2xs uppercase tracking-wider text-text-400"} "todos"]
      [:span {:class "text-2xs text-text-500"} (str open " open")]]
     (if (seq rows)
       (into [:div {:class "flex flex-col gap-1"}]
             (map row (sort-by #(if (= :open (first %)) 0 1) rows)))
       [:div {:class "text-xs text-text-500"} "no todos"])]))

(defn hero-view
  "The hero — the agent's OWN live tile (welcome default or wired content),
   itself DB-driven + SCI-bounded by `seon.render/render-agent-tile`."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  [:div {:class "rounded border border-base-700 bg-base-850 p-4 h-full overflow-auto"}
   (or (:seon.render/hiccup
         (render/render-agent-tile {:seon.db/db db :seon.agent/id id}))
       [:div {:class "text-text-500 text-xs"} "no tile"])])

;; The core views resolvable by SYMBOL. Core symbols map here (direct, fast);
;; AGENT-authored symbols resolve via SCI (`render-sci`). This is the resolution
;; table, not a renderer registry — tiles still STORE their view as a symbol;
;; this only turns a known core symbol into its fn without dragging the bootstrap
;; compiler (`seon.eval`) into the web require closure.
(def ^:private core-views
  {'seon.web.tile/hero-view       hero-view
   'seon.web.tile/status-view     status-view
   'seon.web.tile/todos-view      todos-view
   'seon.web.tile/commentary-view commentary-view})

;; ============================================================
;; The DB-driven layout — tile entities (or the prewritten default).
;; ============================================================

(defn- default-tiles
  "The prewritten default console for `agent-id` — data, not hardcoded dispatch.
   Each tile names a core view SYMBOL; the agent/consumer overrides by
   transacting `:seon.tile/*` entities for this console."
  [agent-id]
  [{:seon.tile/id (str agent-id ":hero") :seon.tile/console agent-id
    :seon.render/html 'seon.web.tile/hero-view :seon.ctx/priority 10 :seon.tile/span 2}
   {:seon.tile/id (str agent-id ":status") :seon.tile/console agent-id
    :seon.render/html 'seon.web.tile/status-view :seon.ctx/priority 20 :seon.tile/span 1}
   {:seon.tile/id (str agent-id ":todos") :seon.tile/console agent-id
    :seon.render/html 'seon.web.tile/todos-view :seon.ctx/priority 30 :seon.tile/span 1}
   {:seon.tile/id (str agent-id ":commentary") :seon.tile/console agent-id
    :seon.render/html 'seon.web.tile/commentary-view :seon.ctx/priority 40 :seon.tile/span 1}])

(defn- console-tiles
  "The tiles for a console — the DB `:seon.tile/*` entities for `agent-id`,
   ordered by `:seon.ctx/priority`; the prewritten default when none exist."
  [db agent-id]
  (let [eids (try (db/query {:seon.db/db db
                             :seon.db/query
                             '[:find [?t ...] :in $ ?c :where [?t :seon.tile/console ?c]]
                             :seon.db/args [agent-id]})
                  (catch :default _ nil))   ; attr not installed yet → default layout
        rows (when (seq eids)
               (map #(db/entity {:seon.db/db db :seon.db/ref %}) eids))]
    (sort-by #(or (:seon.ctx/priority %) 0)
             (if (seq rows) rows (default-tiles agent-id)))))

(defn- find-tile
  "Resolve a tile-id to its tile map — the DB entity, or the matching default
   spec (default ids are `<console>:<kind>`)."
  [db tile-id]
  (let [ent (try (db/entity {:seon.db/db db :seon.db/ref [:seon.tile/id tile-id]})
                 (catch :default _ nil))]   ; attr not installed yet → default spec
    (if (:seon.tile/id ent)
      ent
      (let [console (first (str/split tile-id #":"))]
        (first (filter #(= tile-id (:seon.tile/id %)) (default-tiles console)))))))

(defn- agent-frames
  "The agent's recent tx basis-points (the time-travel filmstrip) — distinct
   tx eids stamped with this agent's `:seon.db/agent-id`, newest last. Each tx
   eid is a valid `as-of` basis-t. Pure read."
  [db agent-id n]
  (->> (try (db/query {:seon.db/db db
                       :seon.db/query
                       '[:find ?tx ?inst :in $ ?a :where
                         [?tx :seon.db/agent-id ?a]
                         [?tx :db/txInstant ?inst]]
                       :seon.db/args [agent-id]})
            (catch :default _ nil))
       (sort-by second)
       (take-last n)
       (mapv (fn [[tx inst]] {:seon.tile/t tx :seon.tile/inst inst}))))

;; ============================================================
;; Render — resolve the tile's stored `:seon.render/html` symbol to hiccup.
;; ============================================================

(defn- resolve-view
  "Resolve a `:seon.render/html` slot to hiccup. Shallow-hiccup vector →
   verbatim; SYMBOL → its fn (agent-authored SCI-bounded, core direct)."
  [slot input]
  (cond
    (vector? slot) slot
    (symbol? slot)
    (if-let [f (get core-views slot)]
      (f input)
      ;; Agent-authored view symbols (SCI-bounded) are a follow-up integration;
      ;; the HERO tile is already agent-modifiable via `render-agent-tile`, which
      ;; SCI-bounds the agent's own tile fn internally.
      [:div {:class "text-text-500 text-xs"} (str "view not available (agent SCI tiles pending): " slot)])
    :else [:div {:class "text-text-500 text-xs"} (str "unrenderable tile slot: " (pr-str slot))]))

(defn- render-tile
  "Render a tile MAP to hiccup against `db` (HEAD or an `as-of` value — that is
   how a tile time-travels). Per-tile fault isolation: a throwing view becomes an
   error card, never a hung region."
  [db tile]
  (try
    (let [subject (:seon.tile/console tile)
          input   {:seon.db/db db :seon.agent/id subject :seon.tile/entity tile}]
      (resolve-view (:seon.render/html tile) input))
    (catch :default e
      [:div {:class "rounded border border-error bg-base-850 p-3 text-xs text-error"}
       (str "render error: " (ex-message e))])))

;; ============================================================
;; SSE payload — one EventSource per region, target implicit; payload is the
;; tile's inner HTML (packetstar does `el.innerHTML = e.data`).
;; ============================================================

(defn- region-event [hiccup]
  (let [s (html/->string hiccup)]
    (str "data: " (str/replace s "\n" "\ndata: ") "\n\n")))

(defn- push-tile! [tile-id]
  (try
    ;; PINNED conns (a `:basis-t`) are frozen — they rendered their as-of frame
    ;; at open and never update; only LIVE conns re-render on a tx.
    (let [live (remove :basis-t (get @!tiles tile-id))]
      (when (seq live)
        (let [db      @db/*conn*
              tile    (find-tile db tile-id)
              hiccup  (if tile
                        (render-tile db tile)
                        [:div {:class "text-text-500 text-xs"} (str "no tile " tile-id)])
              payload (region-event hiccup)]
          (doseq [{:keys [res]} live]
            (try (.write res payload)
                 (catch :default e
                   (log/error-console! "seon.web.tile" "write failed" e)))))))
    (catch :default e
      (log/error-console! "seon.web.tile" "push! threw" e))))

(defonce ^:private !pending (atom {}))

(defn- schedule-push! [tile-id]
  (let [was-pending? (get @!pending tile-id)]
    (swap! !pending assoc tile-id true)
    (when-not was-pending?
      (js/setTimeout
        (fn []
          (swap! !pending dissoc tile-id)
          (push-tile! tile-id))
        100))))

(defn- on-tx
  "Any commit re-renders every tile that has a LIVE conn (the client drops
   no-ops). Tiles watched only by pinned conns are skipped — they stay frozen."
  [_]
  (doseq [[k conns] @!tiles
          :when (some #(nil? (:basis-t %)) conns)]
    (schedule-push! k)))

;; ============================================================
;; Lifecycle — db/listen! IS the refresh signal.
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
;; HTTP — page shells + per-tile SSE streams on /tile/* routes.
;; ============================================================

(defn- write-html! [^js res code body]
  (.writeHead res code #js {"Content-Type"  "text/html; charset=utf-8"
                            "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res body))

(defn- query-t
  "The `?t=<basis-t>` time-travel cursor from the raw request url, or nil (live)."
  [^js req]
  (when-let [m (re-find #"[?&]t=([0-9]+)" (or (.-url req) ""))]
    (js/parseInt (second m) 10)))

(defn- db-at
  "The db value a cursor renders against — `as-of t` when pinned, else HEAD."
  [t]
  (if t (db/as-of @db/*conn* t) @db/*conn*))

(defn- redirect! [^js res location]
  (.writeHead res 302 #js {"Location" location "Cache-Control" "no-store"})
  (.end res ""))

(defn- head [title]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:title title]
   [:link {:rel "stylesheet" :href "/css/output.css"}]
   [:script {:src "/js/packetstar.js" :defer true}]])

(defn- region
  "A tile region: a stable-id element whose `data-tile` points at its SSE stream."
  [tile-id]
  [:div {:id (str "tile-" tile-id) :data-tile (str "/tile/t/" tile-id "/sse") :class "min-h-0"}
   [:div {:class "text-text-500 text-xs"} "connecting…"]])

(defn- header-bar [agent-id]
  [:div {:class "flex items-center justify-between mb-3 pb-2 border-b border-base-800"}
   [:div {:class "flex items-baseline gap-2"}
    [:span {:class "text-sm font-semibold text-text-50"} "seon"]
    [:span {:class "text-2xs text-text-500"} agent-id]]
   [:div {:class "flex items-center gap-3"}
    [:span {:class "inline-flex items-center gap-1 text-2xs text-success"}
     [:span {:class "w-1.5 h-1.5 rounded-full bg-success animate-pulse"}] "live"]
    [:a {:class "text-2xs text-amber-400 hover:text-amber-300"
         :href  (str "/tile/agent/" agent-id "/full")} "⛶ fullscreen"]]])

(defn- input-form
  "The input tile — the human's REPL prompt. A user-OWNED region the server never
   streams into (so focus/typing survive live updates). On submit packetstar
   routes a `(clojure form)` → `/eval` (quiet) and prose → `/chat` (wakes)."
  [agent-id]
  [:form {:data-send "1" :data-agent agent-id :class "mt-3 flex gap-2 shrink-0"}
   [:input {:name "text" :type "text" :autocomplete "off"
            :placeholder "talk to your agent…  —  a (clojure form) evals · prose wakes"
            :class (str "flex-1 bg-base-900 border border-base-700 rounded px-3 py-1.5 "
                        "text-xs text-text-50 placeholder:text-text-500 focus:border-amber-600 outline-none")}]
   [:button {:type "submit"
             :class "px-3 py-1.5 text-xs text-amber-400 hover:text-amber-300 border border-base-700 rounded"}
    "send ⏎"]])

(defn- console-shell
  "The console — masthead + a layout DERIVED from the console's tiles (span-2 →
   hero column, span-1 → rail; the tile list/order/spans are DATA) + the input
   tile. The two-column arrangement is the prewritten strategy over that data."
  [agent-id tiles]
  (let [span (fn [t] (or (:seon.tile/span t) 1))
        hero (into [:div {:class "col-span-2 flex flex-col gap-3 min-h-0"}]
                   (map #(region (:seon.tile/id %)) (filter #(>= (span %) 2) tiles)))
        rail (into [:div {:class "col-span-1 flex flex-col gap-3 min-h-0 overflow-auto"}]
                   (map #(region (:seon.tile/id %)) (remove #(>= (span %) 2) tiles)))
        grid [:div {:class "grid grid-cols-3 gap-3 flex-1 min-h-0"} hero rail]
        page [:html {:lang "en"}
              (head (str "console · " agent-id))
              [:body {:class "bg-base-950 text-text-200 font-mono p-3 h-screen flex flex-col"}
               (header-bar agent-id)
               grid
               (input-form agent-id)]]]
    (str "<!DOCTYPE html>" (html/->string page))))

(defn- hero-shell
  "The fullscreen hero — the agent's tile alone, full-bleed."
  [agent-id]
  (let [page [:html {:lang "en"}
              (head (str "tile · " agent-id))
              [:body {:class "bg-base-950 text-text-200 font-mono p-3"}
               [:div {:class "h-screen"} (region (str agent-id ":hero"))]]]]
    (str "<!DOCTYPE html>" (html/->string page))))

(defn- open-tile-sse! [^js req ^js res tile-id]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [t    (query-t req)               ; nil = live (tracks HEAD); set = pinned as-of
        db   (db-at t)
        conn {:id (random-uuid) :res res :basis-t t :opened-at (js/Date.)}]
    (add-conn! tile-id conn)
    (log/info-console! "seon.web.tile" "SSE OPEN"
                       {:tile tile-id :basis-t t :conn-id (str (:id conn))
                        :total (count (get @!tiles tile-id))})
    (.on req "close"
         (fn []
           (remove-conn! tile-id (:id conn))
           (log/info-console! "seon.web.tile" "SSE CLOSE" {:conn-id (str (:id conn))})))
    (try (when-let [tile (find-tile db tile-id)]
           (.write res (region-event (render-tile db tile))))
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
    (let [id (second (re-matches #"/tile/console/([^/]+)" path))]
      (write-html! res 200 (console-shell id (console-tiles @db/*conn* id)))
      true)

    (re-matches #"/tile/frames/[^/]+" path)
    (let [id     (second (re-matches #"/tile/frames/([^/]+)" path))
          frames (agent-frames @db/*conn* id 30)]
      (.writeHead res 200 #js {"Content-Type" "application/json" "Cache-Control" "no-store"})
      (.end res (js/JSON.stringify
                  (clj->js (mapv (fn [f] {:t (:seon.tile/t f) :inst (str (:seon.tile/inst f))}) frames))))
      true)

    (re-matches #"/tile/t/[^/]+/sse" path)
    (let [tid (second (re-matches #"/tile/t/([^/]+)/sse" path))]
      (open-tile-sse! req res tid)
      true)

    (re-matches #"/tile/agent/[^/]+/full" path)
    (let [id (second (re-matches #"/tile/agent/([^/]+)/full" path))]
      (write-html! res 200 (hero-shell id))
      true)

    (re-matches #"/tile/agent/[^/]+" path)
    (let [id (second (re-matches #"/tile/agent/([^/]+)" path))]
      (redirect! res (str "/tile/console/" id))
      true)

    :else false))
