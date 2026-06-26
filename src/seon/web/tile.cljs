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
    [seon.render.sci :as render-sci]
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

;; Two registries. `!tiles` keys per-tile streams (the /full hero, direct access);
;; `!consoles` keys the MULTIPLEXED console stream — ONE SSE per console page
;; carrying patches for ALL its tiles, so a console of N tiles costs ONE browser
;; connection (not N) and never starves the HTTP/1.1 pool that POSTs need.
(defonce ^:private !tiles (atom {}))
(defonce ^:private !consoles (atom {}))

(defn- reg-add! [reg k conn]
  (swap! reg update k (fnil conj []) conn))

(defn- reg-remove! [reg k conn-id]
  (swap! reg (fn [m]
               (let [cs (vec (remove #(= (:id %) conn-id) (get m k)))]
                 (if (seq cs) (assoc m k cs) (dissoc m k))))))

(defn- add-conn! [k conn]    (reg-add! !tiles k conn))
(defn- remove-conn! [k cid]  (reg-remove! !tiles k cid))

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
  (let [q       (fn [query] (db/query {:seon.db/db db :seon.db/query query :seon.db/args [id]}))
        state   (derive/derive-state db id)
        turns   (derive/agent-turn-count db id)
        ;; queries (not lookup-refs) so the panel time-travels on an as-of db.
        purpose (q '[:find ?p . :in $ ?a :where [?e :seon.agent/id ?a] [?e :seon.agent/purpose ?p]])
        todos   (or (q '[:find (count ?td) . :in $ ?a :where
                         [?e :seon.agent/id ?a]
                         [?td :seon.agent.todo/owner ?e] [?td :seon.agent.todo/status :open]]) 0)
        msgs    (or (q '[:find (count ?m) . :in $ ?a :where
                         [?e :seon.agent/id ?a]
                         (or [?m :seon.agent.message/to ?e] [?m :seon.agent.message/from ?e])]) 0)
        metric  (fn [label v]
                  [:div [:div {:class "text-text-500"} label]
                   [:div {:class "text-sm text-text-100 tabular-nums"} v]])]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "flex items-center justify-between gap-2 mb-2"}
      (comp/status-dot state)
      ;; `min-w-0 break-all` wraps a long agent id rather than clipping it off
      ;; the right edge of a narrow (phone) panel.
      [:span {:class "text-2xs text-text-500 min-w-0 break-all text-right"} id]]
     [:div {:class "text-sm text-text-50 font-medium leading-tight mb-3 break-words"} (or purpose "—")]
     [:div {:class "grid grid-cols-3 gap-2 text-2xs"}
      (metric "turns" turns)
      (metric "todos" todos)
      (metric "msgs" msgs)]]))

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
    [:div {:class "rounded border border-base-700 bg-base-850 p-3 h-full flex flex-col min-h-0"}
     [:div {:class "text-2xs uppercase tracking-wider text-text-400 mb-2 shrink-0"} "commentary"]
     (if (seq recent)
       (into [:div {:class "flex flex-col gap-1 flex-1 overflow-auto min-h-0"}] (map line recent))
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
  ;; Vertically center the (often-sparse) agent render. `flex items-center` (a
  ;; flex ROW) centers on the cross axis reliably — note `.seon-tile { height:100% }`
  ;; means the agent's tile fills this child, so a flex-col `justify-center`/`my-auto`
  ;; bottom-aligned it. The child is `w-full` so text wraps at the FULL tile width
  ;; (a `justify-center` row would shrink it to a 1-char sliver); `min-w-0` /
  ;; `break-words` / `overflow-x-hidden` keep content inside a phone viewport.
  ;; `tile-hero` (input.css) forces the live tile's EXPANDED face at any width —
  ;; the hero is the PRIMARY surface, so it never falls back to the clamped,
  ;; text-clipping compact grid-cell face on a narrow phone.
  [:div {:class (str "tile-hero rounded border border-base-700 bg-base-850 p-4 sm:p-6 h-full "
                     "flex items-center overflow-y-auto overflow-x-hidden")}
   [:div {:class "w-full min-w-0 max-w-full break-words"}
    (or (:seon.render/hiccup
          (render/render-agent-tile {:seon.db/db db :seon.agent/id id}))
        [:div {:class "text-text-500 text-xs"} "no tile"])]])

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
  (let [eids (when (contains? (db/installed-schema db) :seon.tile/console)
               (try (db/query {:seon.db/db db
                               :seon.db/query
                               '[:find [?t ...] :in $ ?c :where [?t :seon.tile/console ?c]]
                               :seon.db/args [agent-id]})
                    (catch :default _ nil)))   ; not installed yet → default layout
        rows (when (seq eids)
               (map #(db/entity {:seon.db/db db :seon.db/ref %}) eids))]
    (sort-by #(or (:seon.ctx/priority %) 0)
             (if (seq rows) rows (default-tiles agent-id)))))

(defn- find-tile
  "Resolve a tile-id to its tile map — the DB entity, or the matching default
   spec (default ids are `<console>:<kind>`)."
  [db tile-id]
  (let [ent (when (contains? (db/installed-schema db) :seon.tile/id)
              (try (db/entity {:seon.db/db db :seon.db/ref [:seon.tile/id tile-id]})
                   (catch :default _ nil)))]   ; not installed yet → default spec
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
      (f input)                                 ; a core view — direct call
      ;; an AGENT-authored view symbol → SCI-bounded (a runaway agent fn must not
      ;; freeze the single-threaded pod). This is how the agent renders its OWN
      ;; tile views; the hero already does it via `render-agent-tile`.
      (if (and (render-sci/bounding-enabled?) (render-sci/agent-authored-sym? slot))
        (let [r (render-sci/invoke-bounded slot input)]
          (cond
            (:seon.render.sci/interrupt r)   [:div {:class "text-warning text-xs"} "tile interrupted"]
            (:seon.render.sci/fallthrough r) [:div {:class "text-text-500 text-xs"} "no tile source"]
            :else r))
        [:div {:class "text-error text-xs"} (str "unknown view: " slot)]))
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

(defn- region-event
  "A single-tile SSE frame — `el.innerHTML = e.data` (per-tile streams)."
  [hiccup]
  (let [s (html/->string hiccup)]
    (str "data: " (str/replace s "\n" "\ndata: ") "\n\n")))

(defn- tile-patch
  "A multiplexed SSE patch — `event: patch`, data = JSON `{id, html}`. The
   console client applies it to `#tile-<id>`. JSON keeps it one `data:` line."
  [tile-id hiccup]
  (str "event: patch\ndata: "
       (js/JSON.stringify #js {:id tile-id :html (html/->string hiccup)})
       "\n\n"))

(defn- console-payload
  "All of a console's tiles as one stream of patches (rendered against `db`)."
  [db console-id]
  (apply str (map (fn [t] (tile-patch (:seon.tile/id t) (render-tile db t)))
                  (console-tiles db console-id))))

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

(defn- push-console! [console-id]
  (try
    (let [live (remove :basis-t (get @!consoles console-id))]
      (when (seq live)
        (let [payload (console-payload @db/*conn* console-id)]
          (doseq [{:keys [res]} live]
            (try (.write res payload)
                 (catch :default e (log/error-console! "seon.web.tile" "console write failed" e)))))))
    (catch :default e (log/error-console! "seon.web.tile" "push-console! threw" e))))

(defonce ^:private !pending (atom {}))

(defn- schedule!
  "Coalesce a push for `[kind id]` (`:tile` or `:console`) into a 100ms trailing
   timer."
  [kind id]
  (let [k [kind id]]
    (when-not (get @!pending k)
      (swap! !pending assoc k true)
      (js/setTimeout
        (fn []
          (swap! !pending dissoc k)
          (case kind :tile (push-tile! id) :console (push-console! id)))
        100))))

(defn- on-tx
  "Any commit re-renders every per-tile + console stream that has a LIVE conn
   (the client drops no-ops). Streams watched only by pinned conns stay frozen."
  [_]
  (doseq [[id conns] @!tiles    :when (some #(nil? (:basis-t %)) conns)] (schedule! :tile id))
  (doseq [[id conns] @!consoles :when (some #(nil? (:basis-t %)) conns)] (schedule! :console id)))

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
  "A self-streaming tile region (its own per-tile SSE) — for single-tile pages."
  [tile-id]
  [:div {:id (str "tile-" tile-id) :data-tile (str "/tile/t/" tile-id "/sse") :class "min-h-0"}
   [:div {:class "text-text-500 text-xs"} "connecting…"]])

(defn- console-region
  "A tile region patched by the console's MULTIPLEXED stream (no own SSE) — a
   stable `#tile-<id>` the patch protocol targets by id. `extra` carries the
   per-slot flex sizing (fill vs content-height)."
  [tile-id extra]
  ;; `min-w-0` lets this grid/flex child shrink below its content's intrinsic
  ;; width (grid/flex items default to min-width:auto) — without it a wide tile
  ;; pushes the whole column past a phone viewport.
  [:div {:id (str "tile-" tile-id) :class (str "min-w-0 min-h-0 flex flex-col " extra)}
   [:div {:class "text-text-500 text-xs"} "connecting…"]])

(defn- scrubber
  "The whole-screen time-travel control — ◀ step back · ● live / ⏸ frame i/n ·
   ▶ step forward · live. A link reloads the console at `?t=<basis-t>` (pins all
   tiles `as-of` that frame via the multiplexed stream); no `?t` = live HEAD.
   Frames are the agent's tx basis-points (always from HEAD)."
  [agent-id t]
  (let [ts  (mapv :seon.tile/t (agent-frames @db/*conn* agent-id 40))
        n   (count ts)
        idx (when t (first (keep-indexed (fn [i x] (when (= x t) i)) ts)))
        url (fn [tt] (str "/tile/console/" agent-id (when tt (str "?t=" tt))))
        a   (fn [label href]
              (if href
                [:a {:class "text-amber-400 hover:text-amber-300" :href href} label]
                [:span {:class "text-text-500 opacity-30"} label]))]
    [:div {:class "flex items-center gap-2 text-2xs"}
     (a "◀" (cond
              (nil? t)             (when (seq ts) (url (last ts)))   ; live → pause at latest frame
              (and idx (pos? idx)) (url (nth ts (dec idx)))
              :else                nil))
     (if (nil? t)
       [:span {:class "inline-flex items-center gap-1 text-success"}
        [:span {:class "w-1.5 h-1.5 rounded-full bg-success animate-pulse"}] "live"]
       [:span {:class "text-warning"} (str "⏸ " (if idx (inc idx) "·") "/" n)])
     (a "▶" (cond (nil? t)                  nil
                  (and idx (< idx (dec n))) (url (nth ts (inc idx)))
                  :else                     (url nil)))
     (when t [:a {:class "text-text-400 hover:text-text-200 ml-1" :href (url nil)} "live"])]))

(defn- header-bar [agent-id t]
  [:div {:class "flex items-center justify-between mb-3 pb-2 border-b border-base-800"}
   [:div {:class "flex items-baseline gap-2"}
    [:span {:class "text-sm font-semibold text-text-50"} "seon"]
    [:span {:class "text-2xs text-text-500"} agent-id]]
   [:div {:class "flex items-center gap-3"}
    (scrubber agent-id t)
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
  [agent-id tiles t]
  (let [span   (fn [x] (or (:seon.tile/span x) 1))
        rails  (vec (remove #(>= (span %) 2) tiles))
        last-i (dec (count rails))
        ;; hero column: span-2 tiles, each fills (big min-height on phones).
        ;; `min-w-0` lets this GRID ITEM shrink below content width (grid items
        ;; default to min-width:auto) — without it a wide tile overflows a phone.
        hero   (into [:div {:class "min-w-0 lg:col-span-2 flex flex-col gap-2 sm:gap-3 lg:min-h-0"}]
                     (map #(console-region (:seon.tile/id %)
                                           "min-h-[42vh] lg:min-h-0 lg:flex-1")
                          (filter #(>= (span %) 2) tiles)))
        ;; rail column: span-1 tiles stack; the LAST (commentary) fills the rest.
        ;; `min-w-0` (as on the hero column) lets this grid item shrink on a phone.
        rail   (into [:div {:class "min-w-0 lg:col-span-1 flex flex-col gap-2 sm:gap-3 lg:min-h-0"}]
                     (map-indexed (fn [i tl]
                                    (console-region (:seon.tile/id tl)
                                                    (if (= i last-i) "lg:flex-1 lg:min-h-0" "shrink-0")))
                                  rails))
        ;; ONE col on phones (stacks + scrolls); 3 cols filling the viewport on
        ;; desktop. The multiplexed stream carries ?t so a pinned console freezes
        ;; every tile as-of that frame.
        grid   [:div {:class "grid grid-cols-1 lg:grid-cols-3 gap-2 sm:gap-3 lg:flex-1 lg:min-h-0"}
                hero rail]
        stream (str "/tile/console/" agent-id "/sse" (when t (str "?t=" t)))
        page   [:html {:lang "en"}
                (head (str "console · " agent-id))
                [:body {:class (str "bg-base-950 text-text-200 font-mono p-2 sm:p-3 gap-2 sm:gap-3 "
                                    "min-h-screen lg:h-screen flex flex-col overflow-x-hidden")
                        :data-console stream}
                 (header-bar agent-id t)
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

(defn- open-console-sse!
  "The MULTIPLEXED console stream — ONE SSE carrying patches for every tile in
   the console (so the page costs one browser connection, not N)."
  [^js req ^js res console-id]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [t    (query-t req)
        db   (db-at t)
        conn {:id (random-uuid) :res res :basis-t t :opened-at (js/Date.)}]
    (reg-add! !consoles console-id conn)
    (log/info-console! "seon.web.tile" "CONSOLE SSE OPEN"
                       {:console console-id :basis-t t :conn-id (str (:id conn))})
    (.on req "close"
         (fn []
           (reg-remove! !consoles console-id (:id conn))
           (log/info-console! "seon.web.tile" "CONSOLE SSE CLOSE" {:conn-id (str (:id conn))})))
    (try (.write res (console-payload db console-id))
         (catch :default e
           (log/error-console! "seon.web.tile" "console initial render failed" e)))))

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
    (re-matches #"/tile/console/[^/]+/sse" path)
    (let [id (second (re-matches #"/tile/console/([^/]+)/sse" path))]
      (open-console-sse! req res id)
      true)

    (re-matches #"/tile/console/[^/]+" path)
    (let [id (second (re-matches #"/tile/console/([^/]+)" path))
          t  (query-t req)]
      (write-html! res 200 (console-shell id (console-tiles @db/*conn* id) t))
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
