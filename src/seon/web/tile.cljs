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
    [seon.agent.inspect :as inspect]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.log :as log]
    [seon.render :as render]
    [seon.render.sci :as render-sci]
    [seon.ui.components :as comp]
    [seon.ui.html :as html]
    [seon.ui.markdown :as md]))

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
;; `!debugs` keys the per-agent DEBUG-overlay multiplexed stream — ONE SSE
;; per open debug overlay carrying patches for its three regions (exact /
;; rendered / breakdown bar). Same multiplexed pattern as `!consoles`.
(defonce ^:private !debugs (atom {}))

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
     (into [:div {:class "text-sm text-text-50 font-medium leading-tight mb-3 break-words"}]
           (md/inline (or purpose "—")))
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
                  (into [:span {:class "text-text-200"}] (md/inline (clip content 120)))])]
    ;; `max-h-[55vh]` (not `h-full`) so this bounds itself + scrolls internally
    ;; inside the now-scrolling rail, instead of trying to fill the whole column.
    [:div {:class "rounded border border-base-700 bg-base-850 p-3 max-h-[55vh] flex flex-col min-h-0"}
     [:div {:class "text-2xs uppercase tracking-wider text-text-400 mb-2 shrink-0"} "commentary"]
     (if (seq recent)
       (into [:div {:class "flex flex-col gap-1 flex-1 overflow-auto min-h-0"}] (map line recent))
       [:div {:class "text-xs text-text-500"} "no messages yet"])]))

(defn todos-view
  "The agent's todos — open first. A todo carrying a `:seon.agent.todo/message`
   back-ref is MESSAGE-ORIGIN (the human-inbound auto-todo): it renders as
   `✉ Respond: \"…\"` so a reply is the obvious next move, distinct from the
   plain ☐/☑ of agent-created todos. Message-origin is read INLINE here via
   `get-else` (the ref's eid is truthy, the `false` default means absent)."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [me   (agent-eid db id)            ; query-based → time-travel-safe
        rows (when me
               (db/query {:seon.db/db db
                          :seon.db/query
                          '[:find ?status ?title ?msg
                            :in $ ?me
                            :where
                            [?t :seon.agent.todo/owner ?me]
                            [?t :seon.agent.todo/status ?status]
                            [?t :seon.agent.todo/title ?title]
                            [(get-else $ ?t :seon.agent.todo/message false) ?msg]]
                          :seon.db/args [me]}))
        open (count (filter #(= :open (first %)) rows))
        row  (fn [[status title msg]]
               (let [open? (= :open status)
                     ;; truthy eid = has a message back-ref → message-origin
                     msg?  (boolean msg)
                     tcls  (if open? "text-text-200" "text-text-500 line-through")
                     ;; the title as inline markdown; message-origin quotes it
                     ;; after a "Respond:" affordance.
                     body  (md/inline (clip title 80))
                     kids  (if msg?
                             (concat [[:span {:class "text-info font-medium mr-1"} "Respond:"]
                                      "“"]
                                     body ["”"])
                             body)]
                 [:div {:class "text-xs leading-tight flex items-start gap-1.5"}
                  [:span {:class (cond msg? "text-info" open? "text-warning" :else "text-success")}
                   (cond msg? "✉" open? "☐" :else "☑")]
                  (into [:span {:class tcls}] kids)]))]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "flex items-center justify-between mb-2"}
      [:div {:class "text-2xs uppercase tracking-wider text-text-400"} "todos"]
      [:span {:class "text-2xs text-text-500"} (str open " open")]]
     (if (seq rows)
       (into [:div {:class "flex flex-col gap-1"}]
             (map row (sort-by #(if (= :open (first %)) 0 1) rows)))
       [:div {:class "text-xs text-text-500"} "no todos"])]))

(defn progress-view
  "The agent's todo completion as a horizontal bar — `:done` todos over total
   (owner = the agent entity), with an `N / M done` label. A filled `bg-success`
   portion over a `bg-base-900` track, width = done%. Empty (no todos) reads a
   muted note. Pure read; time-travel-safe (the counts derive from an `as-of`
   db just as well, since the todo rows are query-based)."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [me    (agent-eid db id)            ; query-based → time-travel-safe
        ;; bind the todo eid `?t` too — without it `:find ?status` collapses to a
        ;; SET of distinct statuses (all-done → one row). `?t` makes each todo a
        ;; distinct tuple, so total/done count todos, not statuses.
        rows  (when me
                (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?t ?status
                             :in $ ?me
                             :where
                             [?t :seon.agent.todo/owner ?me]
                             [?t :seon.agent.todo/status ?status]]
                           :seon.db/args [me]}))
        total (count rows)
        done  (count (filter #(= :done (second %)) rows))
        pct   (if (pos? total) (js/Math.round (/ (* 100.0 done) total)) 0)]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "flex items-center justify-between mb-2"}
      [:div {:class "text-2xs uppercase tracking-wider text-text-400"} "progress"]
      [:span {:class "text-2xs text-text-500 tabular-nums"} (str done " / " total " done")]]
     (if (pos? total)
       ;; the track + a width-NN% fill; inline width % since Tailwind has no
       ;; arbitrary-percent utility in the build (owner-blessed inline style).
       [:div {:class "h-2 w-full rounded-full bg-base-900 overflow-hidden"}
        [:div {:class "h-full rounded-full bg-success" :style (str "width:" pct "%")}]]
       [:div {:class "text-xs text-text-500"} "no todos yet"])]))

(defn toolkit-view
  "The agent's OWN toolkit — the fns it has authored in its namespace, so the
   human watches the harness GROW as a first-class panel. Pure read over the
   `:seon.fn` rows whose `:seon.ns` is the agent's `my.agent.<id>` namespace."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [nsname (keyword (str "my.agent." id))
        rows   (when (contains? (db/installed-schema db) :seon.fn/sym)
                 (db/query {:seon.db/db db
                            :seon.db/query
                            '[:find ?sym ?doc
                              :in $ ?nsname
                              :where
                              [?n :seon.ns/name ?nsname]
                              [?e :seon.fn/ns ?n]
                              [?e :seon.fn/sym ?sym]
                              [(get-else $ ?e :seon.fn/doc "") ?doc]]
                            :seon.db/args [nsname]}))
        tools  (sort-by first rows)
        short  (fn [sym] (let [s (str sym) i (str/index-of s "/")] (if i (subs s (inc i)) s)))
        row    (fn [[sym doc]]
                 [:div {:class "text-xs leading-tight flex items-start gap-1.5"}
                  [:span {:class "text-eval shrink-0"} "ƒ"]
                  [:div {:class "min-w-0"}
                   [:span {:class "text-text-100 font-medium"} (short sym)]
                   (when (seq doc)
                     (into [:span {:class "text-text-500 ml-1 break-words"}]
                           (md/inline (clip doc 64))))]])]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "flex items-center justify-between mb-2"}
      [:div {:class "text-2xs uppercase tracking-wider text-text-400"} "toolkit"]
      [:span {:class "text-2xs text-text-500"}
       (str (count tools) (if (= 1 (count tools)) " tool" " tools"))]]
     (if (seq tools)
       (into [:div {:class "flex flex-col gap-1"}] (map row tools))
       [:div {:class "text-xs text-text-500"} "no tools yet — the agent builds its own"])]))

(defn- decode-section-text
  "Render an agent-authored `:seon.render/ai` robustly. `add-section!` stores the
   string verbatim, so an agent that over-escapes (passes a pr-str'd string —
   wrapped in literal quotes, `\\n` instead of newlines) gets stored that way and
   reads ugly in BOTH its own context AND here. Defensively unwrap + unescape so
   the markdown still renders (flagged to R as a STEERING issue — the lean-context
   examples should show passing RAW markdown, not pr-str'd)."
  [s]
  (let [s (str s)
        s (if (and (>= (count s) 2) (str/starts-with? s "\"") (str/ends-with? s "\""))
            (subs s 1 (dec (count s)))
            s)]
    (-> s (str/replace "\\n" "\n") (str/replace "\\\"" "\""))))

(defn context-view
  "The sections↔tiles convergence: the agent's OWN pinned context — the sections
   it `add-section!`'d to KEEP — rendered nicely from each section's markdown
   `:seon.render/ai` twin via `md->hiccup` (the dual-render lean: the agent writes
   markdown text, the user sees it as HTML). So anything the agent pins to its
   context auto-appears here as a first-class tile. Agent's view == user's view."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [secs    (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?nm ?pri ?ai
                             :in $ ?a
                             :where
                             [?e :seon.agent/id ?a]
                             [?e :seon.agent/sections ?s]
                             [?s :seon.ctx/name ?nm]
                             [?s :seon.ctx/priority ?pri]
                             [(get-else $ ?s :seon.render/ai "") ?ai]]
                           :seon.db/args [id]})
        ordered (sort-by second secs)
        card    (fn [[nm _ ai]]
                  [:div {:class "border-l-2 border-amber-700/40 pl-2 py-1"}
                   [:div {:class "text-2xs font-mono uppercase tracking-wider text-text-400 mb-1"}
                    (name nm)]
                   (md/md->hiccup (decode-section-text ai)
                                  {:wrap-class "markdown text-xs text-text-200"})])]
    [:div {:class "rounded border border-base-700 bg-base-850 p-3"}
     [:div {:class "flex items-center justify-between mb-2"}
      [:div {:class "text-2xs uppercase tracking-wider text-text-400"} "context"]
      [:span {:class "text-2xs text-text-500"} (str (count secs) " pinned")]]
     (if (seq ordered)
       (into [:div {:class "flex flex-col gap-3"}] (map card ordered))
       [:div {:class "text-xs text-text-500"}
        "nothing pinned yet — the agent uses add-section! to keep things here"])]))

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
   'seon.web.tile/progress-view   progress-view
   'seon.web.tile/toolkit-view    toolkit-view
   'seon.web.tile/context-view    context-view
   'seon.web.tile/commentary-view commentary-view})

;; The agent-referenceable catalog of the prewritten view SYMBOLS — each entry
;; says what the view renders + the DB data it reads. EVERY core view is
;; PARAMETERLESS: it takes only the injected `{:seon.db/db :seon.agent/id}` and
;; queries the DB itself, so an agent (or a downstream consumer) reaches for one
;; by transacting a tile whose `:seon.render/html` IS the symbol — no args, no
;; wiring. This is the public teaching surface for "which prewritten tile do I
;; want"; the resolution table is `core-views` above.
(def prebuilt-views
  "Catalog: core view SYMBOL → `{:seon.ui/desc :seon.ui/expects}`. Every view is
   parameterless (reads only the injected `{:seon.db/db :seon.agent/id}` and
   queries the DB). Set a tile's `:seon.render/html` to one of these symbols."
  {'seon.web.tile/hero-view
   {:seon.ui/desc    "The agent's own live tile — its welcome default or wired content, itself DB-driven + SCI-bounded. The primary surface (span-2 in the default layout)."
    :seon.ui/expects "Parameterless. Renders the agent's `:seon.render.live-tile/content` via render-agent-tile."}
   'seon.web.tile/status-view
   {:seon.ui/desc    "The agent's status card: a state dot, its purpose, and turns / open-todos / msgs metrics."
    :seon.ui/expects "Parameterless. Reads :seon.agent/purpose, derived state + turn-count, open todos, and messages to/from the agent."}
   'seon.web.tile/todos-view
   {:seon.ui/desc    "The agent's todos, open first; a message-origin todo renders as a 'Respond:' affordance."
    :seon.ui/expects "Parameterless. Reads the agent's :seon.agent.todo/* (owner = the agent): status, title, and the message back-ref."}
   'seon.web.tile/progress-view
   {:seon.ui/desc    "The agent's todo completion as a horizontal bar — done vs total — with an 'N / M done' label."
    :seon.ui/expects "Parameterless. Reads :seon.agent.todo/status (owner = the agent), counting :done over the total."}
   'seon.web.tile/toolkit-view
   {:seon.ui/desc    "The agent's OWN toolkit — the fns it has authored in its `my.agent.<id>` namespace, so the human watches the harness grow."
    :seon.ui/expects "Parameterless. Reads :seon.fn/sym + :seon.fn/doc for the agent's namespace."}
   'seon.web.tile/context-view
   {:seon.ui/desc    "The agent's OWN pinned context — the sections it add-section!'d to keep — rendered from each section's markdown twin."
    :seon.ui/expects "Parameterless. Reads :seon.agent/sections → :seon.ctx/name, :seon.ctx/priority, :seon.render/ai."}
   'seon.web.tile/commentary-view
   {:seon.ui/desc    "The shared REPL transcript (demoted chat) — the recent messages to/from the agent, newest last."
    :seon.ui/expects "Parameterless. Reads :seon.agent.message/* (to/from the agent): at, origin, content."}
   'seon.web.tile/activity-view
   {:seon.ui/desc    "The always-visible header strip — state dot · current turn · latest eval · graceful stop/resume. What the agent is doing NOW (rendered into the masthead activity region, not a standalone rail tile)."
    :seon.ui/expects "Parameterless. Reads derived state + turn-count and the agent's latest eval source."}})

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
   {:seon.tile/id (str agent-id ":progress") :seon.tile/console agent-id
    :seon.render/html 'seon.web.tile/progress-view :seon.ctx/priority 33 :seon.tile/span 1}
   {:seon.tile/id (str agent-id ":toolkit") :seon.tile/console agent-id
    :seon.render/html 'seon.web.tile/toolkit-view :seon.ctx/priority 35 :seon.tile/span 1}
   {:seon.tile/id (str agent-id ":context") :seon.tile/console agent-id
    :seon.render/html 'seon.web.tile/context-view :seon.ctx/priority 37 :seon.tile/span 1}
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

(defn- latest-eval-summary
  "The agent's most recent eval source (what it just ran), one-lined + clipped —
   the 'what it's doing' detail for the activity indicator. nil if it has run
   nothing in its own ns."
  [db id]
  (let [ns-kw (keyword (str "my.agent." id))
        rows  (db/query {:seon.db/db db
                         :seon.db/query
                         '[:find ?src ?at :in $ ?ns :where
                           [?e :seon.eval/ns ?ns]
                           [?e :seon.eval/source ?src]
                           [?e :seon.eval/at ?at]]
                         :seon.db/args [ns-kw]})
        latest (last (sort-by #(.getTime ^js (second %)) rows))
        src    (some-> latest first str str/trim)]
    (when (and src (seq src)) (clip (str/replace src #"\s+" " ") 52))))

(defn- stop-control
  "The graceful STOP / RESUME button for the agent's open run, derived from
   `state` — the proper way for the human to halt a running agent. `:running`
   ⇒ `■ stop` (POST /stop PAUSES the open run → derived `:paused`, the drive
   loop exits, resumable); `:paused` ⇒ `▶ resume` (POST /resume clears the
   pause AND re-drives the loop); any other state ⇒ nothing (no run to act on).
   The `data-action` URL is POSTed by packetstar's document-delegated click
   handler; since the activity region re-renders per tx, the button
   appears/vanishes with the state automatically — no client wiring needed."
  [id state]
  (let [btn (fn [action label colour]
              [:button {:type        "button"
                        :data-action (str action "?agent=" id)
                        :class       (str "shrink-0 ml-1 text-2xs leading-none "
                                          "border border-base-700 rounded px-2 py-1 "
                                          "bg-transparent cursor-pointer hover:text-amber-300 "
                                          colour)}
               label])]
    (case state
      :running (btn "/stop"   "■ stop"   "text-warning")
      :paused  (btn "/resume" "▶ resume" "text-success")
      nil)))

(defn- activity-view
  "Always-visible 'what the agent is doing NOW', derived live: a state dot +
   the current turn + (when active) the latest thing it ran + the graceful
   stop/resume control. This is the AGENT-activity signal — distinct from the
   scrubber's live/pinned, which is about time-travel, not what the agent is
   doing. Rendered into the header's `#tile-<id>:activity` region and patched on
   every tx via `console-payload`, so the stop button tracks the live state."
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [state   (derive/derive-state db id)
        turns   (derive/agent-turn-count db id)
        active? (not= :idle state)
        detail  (when active? (latest-eval-summary db id))]
    [:div {:class "flex items-center gap-2 text-2xs min-w-0"}
     (comp/status-dot state)
     [:span {:class "text-text-500 shrink-0"} (str "turn " turns)]
     (when detail
       [:span {:class "text-eval truncate min-w-0 font-mono"} (str "· " detail)])
     (stop-control id state)]))

(defn- activity-region-id [agent-id] (str agent-id ":activity"))

(defn- console-payload
  "All of a console's tiles as one stream of patches (rendered against `db`),
   PLUS the always-visible header activity region — so 'what the agent is doing'
   updates live on every tx alongside the tiles."
  [db console-id]
  (str (tile-patch (activity-region-id console-id)
                   (activity-view {:seon.db/db db :seon.agent/id console-id}))
       (apply str (map (fn [t] (tile-patch (:seon.tile/id t) (render-tile db t)))
                       (console-tiles db console-id)))))

;; ============================================================
;; The DEBUG overlay — a developer view of the agent's CONTEXT, three
;; regions: LEFT the EXACT bytes the LLM receives, RIGHT the rendered html
;; twin, BOTTOM a per-section token breakdown bar. PURE READ — derives from
;; the db value + the captured prompt blob; never writes (nothing rendered
;; is persisted). The left pane is FAITHFUL by construction: it shows the
;; CAPTURED turn blob (`:seon.agent.turn/prompt-file`, written verbatim by
;; `seon.debug` when SEON_DEBUG_CAPTURE is on) when present, else a LIVE
;; re-render via the SAME single producer the turn uses
;; (`seon.agent.inspect/ctx-preview` → `seon.ctx/render-context` +
;; `seon.ai/debug-full-prompt`). The bottom bar partitions THAT SAME exact
;; text, so its tokens derive from the same bytes the left pane shows.
;; ============================================================

(def ^:private stable-debug-sections
  "Section names of the byte-stable cacheable PREFIX (the composer caches
   sections with `:seon.ctx/priority` ≤ stable-priority-max): the system
   block + soul → agents → shared-instructions → namespaces. Rendered amber
   on the breakdown bar; the volatile tail reads grey."
  #{:system :soul :agents :shared-instructions :namespaces})

(defn- stable-debug? [nm] (contains? stable-debug-sections nm))

(defn- read-text-file
  "UTF-8 text of `path`, or nil when unreadable. Never throws (a missing
   capture blob just falls back to the live re-render)."
  [path]
  (try (.readFileSync (js/require "node:fs") path "utf8")
       (catch :default _ nil)))

(defn- latest-turn-prompt-file
  "The `:seon.agent.turn/prompt-file` of the agent's most-recent turn that
   captured one (by `:at`), or nil. That blob is the EXACT prompt bytes the
   agent's LLM received that turn. Pure read; degrades to nil (→ live
   re-render) if capture is off or the run/turn attrs are absent."
  [db agent-id]
  (->> (try (db/query {:seon.db/db db
                       :seon.db/query
                       '[:find ?at ?file :in $ ?aid :where
                         [?a :seon.agent/id ?aid]
                         [?r :seon.agent.run/agent ?a]
                         [?t :seon.agent.turn/run ?r]
                         [?t :seon.agent.turn/at ?at]
                         [?t :seon.agent.turn/prompt-file ?file]]
                       :seon.db/args [agent-id]})
            (catch :default _ nil))
       (sort-by first)
       last
       second))

(defn- split-exact-sections
  "Partition the EXACT prompt bytes `full` into labeled segments for the
   breakdown bar — the SAME bytes the left pane shows. The system block
   (everything before the first section) is one `:system` segment; each
   context section is one segment delimited by the `;;; ┌─ <name> ─`
   fold-brackets `seon.ctx/render-context-ai` emits. Every byte is assigned
   to exactly one segment, so the segment tokens sum to the total.

   The bracket match is LINE-ANCHORED (`^…` with the `m` flag, via `.exec`
   so the CLJS `str/split` m-flag-drop doesn't bite): the real brackets sit
   at column 0, while the system block DOCUMENTS the bracket syntax
   mid-sentence — anchoring keeps that prose inside the `:system` segment."
  [full]
  (let [full (or full "")
        re   (js/RegExp. "^;;; ┌─ (\\S+) ─" "gm")
        ms   (loop [acc []]
               (if-let [m (.exec re full)]
                 (recur (conj acc [(.-index m) (aget m 1)]))
                 acc))
        idxs (mapv first ms)]
    (if (empty? ms)
      [{::sname :prompt ::stext full}]
      (let [system (subs full 0 (first idxs))
            secs   (map-indexed
                     (fn [k [i nm]]
                       {::sname (keyword nm)
                        ::stext (subs full i (or (get idxs (inc k)) (count full)))})
                     ms)]
        (into (if (str/blank? system) [] [{::sname :system ::stext system}])
              secs)))))

(defn- debug-snapshot
  "One pure-read debug render snapshot for `agent-id` against HEAD.
   `::exact` is the faithful left-pane text (captured blob → live re-render),
   `::source` :captured|:live; `::sections` partitions that exact text for
   the bar; `::section-html` is the live html twin for the right pane (never
   captured); `::token-est` is chars/4 over the WHOLE exact text."
  [agent-id]
  (let [db       @db/*conn*
        preview  (inspect/ctx-preview {:seon.agent/id agent-id})
        file     (latest-turn-prompt-file db agent-id)
        captured (when file (read-text-file file))
        [exact source] (if (and captured (not (str/blank? captured)))
                         [captured :captured]
                         [(or (:seon.render/text preview) "") :live])]
    {::exact        exact
     ::source       source
     ::token-est    (tokens/estimate exact)
     ::sections     (split-exact-sections exact)
     ::section-html (or (:seon.render/section-html preview) [])}))

(defn- debug-rendered-inner
  "The right-pane inner content — one card per context section html twin, in
   render order. `.seon-agent-content` (input.css) gives the agent-authored
   hiccup Phosphor element styling despite Tailwind preflight."
  [section-html]
  (if (seq section-html)
    (into [:div {:class "flex flex-col gap-2"}]
          (map (fn [{nm :seon.ctx/name h :seon.render/hiccup}]
                 [:div {:class "border-l-2 border-amber-700/40 pl-2 py-1"}
                  [:div {:class (str "text-[10px] font-mono font-semibold text-text-400 "
                                     "mb-0.5 uppercase tracking-wider")}
                   (name nm)]
                  [:div {:class "mt-0.5"} h]]))
          section-html)
    [:div {:class "text-text-500 italic text-xs p-2"} "no rendered sections yet"]))

(defn- debug-bar-segment
  "One section segment of the breakdown bar — width is a flex weight ∝ its
   estimated tokens; stable-prefix sections read amber, the volatile tail
   grey. The name + token count show inline when the segment is wide enough,
   always in the hover title."
  [{::keys [sname stext]} total]
  (let [tok     (tokens/estimate stext)
        pct     (if (pos? total) (* 100.0 (/ tok total)) 0)
        stable? (stable-debug? sname)
        wide?   (>= pct 5)]
    [:div {:class (str "relative h-full flex items-center justify-center overflow-hidden "
                       "border-r border-base-950 "
                       (if stable?
                         "bg-amber-800/60 hover:bg-amber-700/70 "
                         "bg-base-700/70 hover:bg-base-600/80 "))
           :style (str "flex: " (max 0.01 tok) " 1 0; min-width: 2px;")
           :title (str (name sname) " · ~" tok " tok · " (.toFixed pct 1) "%"
                       (when stable? " · cached prefix"))}
     (when wide?
       [:span {:class (str "px-1 truncate text-[10px] font-mono "
                           (if stable? "text-amber-50" "text-text-200"))}
        (str (name sname) " " tok)])]))

(defn- debug-bar-inner
  "The bottom breakdown bar inner content — a headline (total tokens +
   legend) over a horizontal stacked bar, one segment per context section
   weighted by its tokens. `total` (sum of segment tokens) drives the
   widths; `token-est` (chars/4 of the whole exact text) is the headline."
  [sections token-est]
  (let [segs  (vec sections)
        total (reduce + 0 (map #(tokens/estimate (::stext %)) segs))]
    [:div {:class "flex flex-col gap-1"}
     [:div {:class "flex items-center gap-3 text-[10px] font-mono text-text-400"}
      [:span {:class "text-text-200"} "context budget"]
      [:span {:class "text-amber-400"} (str "~" token-est " tok total")]
      [:span {:class "text-text-600"} (str (count segs) " sections")]
      [:span {:class "ml-auto text-text-600"} "stable prefix amber · volatile tail grey"]]
     [:div {:class (str "relative h-6 w-full flex rounded-sm overflow-hidden "
                        "bg-base-950 border border-base-800")}
      (if (seq segs)
        (into [:div {:class "flex w-full h-full"}]
              (map #(debug-bar-segment % total) segs))
        [:div {:class "flex items-center px-2 text-[10px] font-mono text-text-600"}
         "no context yet"])]]))

(defn- debug-region-id [region agent-id] (str "dbg-" region "-" agent-id))

(defn- debug-payload
  "All three debug regions as one stream of multiplexed patches (rendered
   against HEAD). The exact pane payload is the raw text (escaped by
   `tile-patch` → set as the `<pre>`'s innerHTML, so its scroll survives)."
  [agent-id]
  (let [{::keys [exact sections section-html token-est]} (debug-snapshot agent-id)]
    (str (tile-patch (debug-region-id "exact" agent-id) exact)
         (tile-patch (debug-region-id "rendered" agent-id) (debug-rendered-inner section-html))
         (tile-patch (debug-region-id "bar" agent-id) (debug-bar-inner sections token-est)))))

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

(defn- push-debug! [agent-id]
  (try
    (let [live (get @!debugs agent-id)]
      (when (seq live)
        (let [payload (debug-payload agent-id)]
          (doseq [{:keys [res]} live]
            (try (.write res payload)
                 (catch :default e (log/error-console! "seon.web.tile" "debug write failed" e)))))))
    (catch :default e (log/error-console! "seon.web.tile" "push-debug! threw" e))))

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
          (case kind
            :tile    (push-tile! id)
            :console (push-console! id)
            :debug   (push-debug! id)))
        100))))

(defn- on-tx
  "Any commit re-renders every per-tile + console stream that has a LIVE conn
   (the client drops no-ops). Streams watched only by pinned conns stay frozen."
  [_]
  (doseq [[id conns] @!tiles    :when (some #(nil? (:basis-t %)) conns)] (schedule! :tile id))
  (doseq [[id conns] @!consoles :when (some #(nil? (:basis-t %)) conns)] (schedule! :console id))
  (doseq [[id conns] @!debugs   :when (seq conns)]                       (schedule! :debug id)))

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
     ;; "now" (viewing the current frame), a STEADY dim dot — the pulse is
     ;; reserved for the agent-activity indicator (header left), so the two
     ;; aren't confused (this is time-travel state, not what the agent is doing).
     (if (nil? t)
       [:span {:class "inline-flex items-center gap-1 text-text-400"}
        [:span {:class "w-1.5 h-1.5 rounded-full bg-text-500"}] "now"]
       [:span {:class "text-warning"} (str "⏸ " (if idx (inc idx) "·") "/" n)])
     (a "▶" (cond (nil? t)                  nil
                  (and idx (< idx (dec n))) (url (nth ts (inc idx)))
                  :else                     (url nil)))
     (when t [:a {:class "text-text-400 hover:text-text-200 ml-1" :href (url nil)} "live"])]))

(defn- header-bar [agent-id t]
  [:div {:class "flex items-center justify-between gap-3 mb-3 pb-2 border-b border-base-800"}
   [:div {:class "flex items-center gap-3 min-w-0"}
    [:div {:class "flex items-baseline gap-2 shrink-0"}
     [:span {:class "text-sm font-semibold text-text-50"} "seon"]
     [:span {:class "text-2xs text-text-500"} agent-id]]
    ;; ALWAYS-VISIBLE live agent activity — what it's doing right NOW (state ·
    ;; turn · latest action). A patchable region (`tile-<id>:activity`) the
    ;; console multiplex re-renders on every tx (see `console-payload`).
    [:div {:id (str "tile-" (activity-region-id agent-id))
           :class "min-w-0 flex items-center border-l border-base-800 pl-3"}
     (activity-view {:seon.db/db @db/*conn* :seon.agent/id agent-id})]]
   [:div {:class "flex items-center gap-3 shrink-0"}
    (scrubber agent-id t)
    ;; Opens the DEBUG overlay (the agent's context, three regions) over the
    ;; console without a URL change — packetstar toggles `#seon-debug-overlay`
    ;; (⚙ / backtick, Esc/backdrop closes). Document-delegated click, so the
    ;; button survives any future morph.
    [:button {:id "seon-debug-toggle" :type "button"
              :class (str "text-2xs text-amber-400 hover:text-amber-300 cursor-pointer "
                          "bg-transparent border-0 p-0")}
     "⚙ debug"]
    [:a {:class "text-2xs text-amber-400 hover:text-amber-300"
         :href  (str "/tile/agent/" agent-id "/full")} "⛶ fullscreen"]]])

(defn- input-form
  "The input tile — the human's REPL prompt. A user-OWNED region the server never
   streams into (so focus/typing survive live updates). On submit packetstar
   routes a `(clojure form)` → `/eval` (quiet) and prose → `/chat` (wakes)."
  [agent-id]
  [:form {:data-send "1" :data-agent agent-id :class "mt-3 flex gap-2 shrink-0"}
   [:input {:name "text" :type "text" :autocomplete "off"
            ;; The example uses the agent's REAL home-ns alias (`todo`, not
            ;; `seon.agent.todo`) — agents copy what they see, so the UI teaches
            ;; the friendly aliases (coordination: R's lean-context ask #1).
            :placeholder "talk to your agent…  —  prose wakes · a form evals, e.g. (todo/list-open {})"
            :class (str "flex-1 bg-base-900 border border-base-700 rounded px-3 py-1.5 "
                        "text-xs text-text-50 placeholder:text-text-500 focus:border-amber-600 outline-none")}]
   [:button {:type "submit"
             :class "px-3 py-1.5 text-xs text-amber-400 hover:text-amber-300 border border-base-700 rounded"}
    "send ⏎"]])

(defn- console-shell
  "The console — masthead + a layout DERIVED from the console's tiles (span-2 →
   hero column, span-1 → rail; the tile list/order/spans are DATA) + the input
   tile. The two-column arrangement is the prewritten strategy over that data.
   `open-debug?` server-renders the debug overlay already open (the `?debug=1`
   deep-link — also what the headless screenshot uses)."
  [agent-id tiles t open-debug?]
  (let [span   (fn [x] (or (:seon.tile/span x) 1))
        rails  (vec (remove #(>= (span %) 2) tiles))
        ;; hero column: span-2 tiles, each fills (big min-height on phones).
        ;; `min-w-0` lets this GRID ITEM shrink below content width (grid items
        ;; default to min-width:auto) — without it a wide tile overflows a phone.
        hero   (into [:div {:class "min-w-0 lg:col-span-2 flex flex-col gap-2 sm:gap-3 lg:min-h-0"}]
                     (map #(console-region (:seon.tile/id %)
                                           "min-h-[42vh] lg:min-h-0 lg:flex-1")
                          (filter #(>= (span %) 2) tiles)))
        ;; rail column: span-1 tiles stack at NATURAL height; the column itself
        ;; SCROLLS (`lg:overflow-y-auto`) when they exceed the viewport, instead of
        ;; spilling off-page. (`_` unused — every rail tile is `shrink-0` now; the
        ;; old last-tile-fills behaviour fought the scroll.) `min-w-0` lets the grid
        ;; item shrink on a phone.
        rail   (into [:div {:class (str "min-w-0 lg:col-span-1 flex flex-col gap-2 sm:gap-3 "
                                        "lg:min-h-0 lg:overflow-y-auto")}]
                     (map (fn [tl] (console-region (:seon.tile/id tl) "shrink-0"))
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
                 (input-form agent-id)
                 ;; The full-viewport debug overlay — an iframe onto the
                 ;; standalone /tile/debug/<id> page (its own live SSE inside).
                 ;; packetstar toggles `.open` + sets the iframe src on demand.
                 ;; Reuses `#seon-debug-overlay` (input.css); `.tile` gives the
                 ;; inset-panel + dim-backdrop variant so a backdrop click
                 ;; closes it. `open-debug?` (?debug=1) renders it already open
                 ;; with the iframe src set (deep-link + deterministic shot).
                 [:div {:id "seon-debug-overlay" :class (if open-debug? "tile open" "tile")}
                  [:button {:id "seon-debug-close" :type "button" :title "close (Esc)"} "✕"]
                  [:iframe (cond-> {:id "seon-debug-frame"
                                    :title (str "debug · " agent-id)
                                    :data-src (str "/tile/debug/" agent-id)}
                             open-debug? (assoc :src (str "/tile/debug/" agent-id)))]]]]]
    (str "<!DOCTYPE html>" (html/->string page))))

(defn- debug-source-badge
  "Honest provenance chip for the exact pane: captured turn bytes
   (guaranteed faithful) vs a live re-render at current db (faithful to the
   prompt the agent WOULD see now, via the same producer)."
  [source]
  (if (= source :captured)
    [:span {:class "text-2xs text-emerald-400" :title "the captured prompt blob the agent's LLM received"}
     "● captured turn bytes"]
    [:span {:class "text-2xs text-amber-400"
            :title "no capture blob — re-rendered now via the same producer the turn uses"}
     "● live re-render @ head"]))

(defn- debug-shell
  "The standalone debug page (`/tile/debug/<id>`, loaded inside the console
   overlay iframe): LEFT the EXACT context the agent's LLM receives (a
   scrollable `<pre>`, whitespace preserved), RIGHT the rendered html twin,
   BOTTOM the per-section token breakdown bar. Live via its own multiplexed
   SSE (`data-console`); the three regions are patched by `#tile-<region>`."
  [agent-id]
  (let [{::keys [exact source sections section-html token-est]} (debug-snapshot agent-id)
        page
        [:html {:lang "en"}
         (head (str "debug · " agent-id))
         [:body {:class (str "bg-base-950 text-text-200 font-mono h-screen flex flex-col "
                             "overflow-hidden")
                 :data-console (str "/tile/debug/" agent-id "/sse")}
          ;; masthead
          [:div {:class (str "shrink-0 flex items-center justify-between px-3 py-2 "
                             "border-b border-base-800 bg-base-900")}
           [:div {:class "flex items-baseline gap-2"}
            [:span {:class "text-sm font-semibold text-text-50"} "debug"]
            [:span {:class "text-2xs text-text-500"} agent-id]
            (debug-source-badge source)]
           [:a {:class "text-2xs text-amber-400 hover:text-amber-300"
                :href (str "/tile/console/" agent-id)} "← console"]]
          ;; the two panes — exact left, rendered right
          [:div {:class "flex-1 grid min-h-0" :style "grid-template-columns: 45% 55%;"}
           ;; LEFT — the exact bytes
           [:div {:class "flex flex-col min-h-0 border-r border-base-800"}
            [:div {:class (str "shrink-0 px-2 py-1 text-2xs font-mono text-text-400 "
                               "bg-base-900 border-b border-base-800")}
             ":seon.render/text — the exact bytes the LLM receives"]
            ;; The `<pre>` IS the patched region (stable id), so its scrollTop
            ;; survives each live patch. `whitespace-pre` preserves the exact
            ;; whitespace; long lines scroll horizontally (overflow-auto).
            [:pre {:id (str "tile-" (debug-region-id "exact" agent-id))
                   :class (str "flex-1 overflow-auto whitespace-pre text-[11px] "
                               "leading-snug text-text-100 bg-base-950 p-3 m-0")}
             exact]]
           ;; RIGHT — the rendered html twin
           [:div {:class "flex flex-col min-h-0"}
            [:div {:class (str "shrink-0 px-2 py-1 text-2xs font-mono text-text-400 "
                               "bg-base-900 border-b border-base-800")}
             ":seon.render/html — the rendered context"]
            [:div {:id (str "tile-" (debug-region-id "rendered" agent-id))
                   :class (str "seon-agent-content flex-1 overflow-auto text-xs "
                               "bg-base-950 p-2")}
             (debug-rendered-inner section-html)]]]
          ;; BOTTOM — the per-section token breakdown bar
          [:div {:class "shrink-0 border-t border-base-800 bg-base-900 px-3 py-2"}
           [:div {:id (str "tile-" (debug-region-id "bar" agent-id))}
            (debug-bar-inner sections token-est)]]]]]
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

(defn- open-debug-sse!
  "The MULTIPLEXED debug-overlay stream — ONE SSE carrying patches for the
   three debug regions (exact / rendered / bar) of `agent-id`, re-rendered
   live on every commit. Always tracks HEAD (no time-travel)."
  [^js req ^js res agent-id]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [conn {:id (random-uuid) :res res :opened-at (js/Date.)}]
    (reg-add! !debugs agent-id conn)
    (log/info-console! "seon.web.tile" "DEBUG SSE OPEN"
                       {:agent agent-id :conn-id (str (:id conn))})
    (.on req "close"
         (fn []
           (reg-remove! !debugs agent-id (:id conn))
           (log/info-console! "seon.web.tile" "DEBUG SSE CLOSE" {:conn-id (str (:id conn))})))
    (try (.write res (debug-payload agent-id))
         (catch :default e
           (log/error-console! "seon.web.tile" "debug initial render failed" e)))))

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
    (let [id     (second (re-matches #"/tile/console/([^/]+)" path))
          t      (query-t req)
          debug? (boolean (re-find #"[?&]debug=1" (or (.-url req) "")))]
      (write-html! res 200 (console-shell id (console-tiles @db/*conn* id) t debug?))
      true)

    (re-matches #"/tile/debug/[^/]+/sse" path)
    (let [id (second (re-matches #"/tile/debug/([^/]+)/sse" path))]
      (open-debug-sse! req res id)
      true)

    (re-matches #"/tile/debug/[^/]+" path)
    (let [id (second (re-matches #"/tile/debug/([^/]+)" path))]
      (write-html! res 200 (debug-shell id))
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
