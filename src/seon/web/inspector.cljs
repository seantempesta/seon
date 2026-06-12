(ns seon.web.inspector
  "Agent inspector UI — what the agent sees, both as AI text and HTML.

   Two columns per agent:
     - LEFT  `:seon.render/ai`   — `(inspect/ctx-preview ...)` text, the
       exact bytes the LLM would receive on its next render.
     - RIGHT `:seon.render/html` — same set of entities; for each entity,
       resolve its `:seon.render/html` slot and call it (or fall back to
       the `:seon.render/ai` text wrapped in <pre>).

   Reactive: a single tx-listener subscribes to every commit. For each
   tx-report we read `:seon.db/agent-id` from the tx eid in db-after.
   Substrate tx (nil agent-id) fan out to ALL watching agents; per-agent
   tx fan out only to that agent's connections. Pushes are coalesced
   per-agent on a 100ms trailing timer so a burst of tx within one
   turn produces one render.

   Routes (mounted from seon.web.serve):
     GET /agents                 — mission control (the tile grid)
     GET /agent/<id>             — the CONSUMER view: chat bubbles +
                                   input left, the expanded live tile
                                   right (live-tiles PRD §1 Surface 2)
     GET /agent/<id>/sse         — SSE stream for the consumer view
     GET /agent/<id>/debug       — the two-pane debug inspector
                                   (today's view, kept exactly)
     GET /agent/<id>/debug/sse   — SSE stream for the debug view

   ONE tx-listener serves both per-agent views: each SSE connection is
   tagged with its view; `push-agent!` renders the right fragment set
   per view from the same coalesced push."
  (:require
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent-view :as agent-view]
    [seon.agent.findings :as findings]
    [seon.db :as db]
    [seon.agent.inspect :as inspect]
    [seon.log :as log]
    [seon.render :as render]
    [seon.render.chat :as chat]
    [seon.render.default :as default]
    [seon.ui.components :as comp]
    [seon.ui.html :as html]
    [seon.web.brand :as brand]))

;; ============================================================
;; SSE connection registry — per-agent. Distinct from the main
;; /sse registry in seon.web.serve so the existing broadcast keeps
;; behaving unchanged.
;;
;; Shape: atom of {agent-id -> [{:id <uuid> :res <ServerResponse>} ...]}
;; ============================================================

(defonce ^:private !sse-by-agent (atom {}))

(defn- add-conn! [agent-id conn]
  (swap! !sse-by-agent update agent-id (fnil conj []) conn))

(defn- remove-conn! [agent-id conn-id]
  (swap! !sse-by-agent update agent-id
         (fn [conns] (vec (remove #(= conn-id (:id %)) conns)))))

(defn- watching-agents
  "Set of agent-ids that currently have at least one open SSE stream."
  []
  (->> @!sse-by-agent
       (filter (fn [[_ conns]] (seq conns)))
       (map first)
       set))

;; ============================================================
;; Data sources — one snapshot per render. Returns the AI text + the
;; per-entity hiccup list.
;; ============================================================

(defn- agent-exists?
  "True iff `agent-id` resolves to a live `:seon.agent/id` entity in the
   cluster store. Guards the page + SSE routes: stale tabs/bookmarks
   carry ids from prior stores (pre-flip pods, reset clusters) — without
   this check the page 500s on `snapshot`'s lookup-ref pull and the SSE
   registers a connection that throws on every subsequent tx."
  [agent-id]
  (boolean
    (seq (db/query {:seon.db/query '[:find ?e
                                     :in $ ?id
                                     :where [?e :seon.agent/id ?id]]
                    :seon.db/args  [agent-id]}))))

(defn- list-agents-data
  "Pull `[id state turn-count completed-at?]` rows for every
   `:seon.agent/id` entity. Sorted by id asc. `:seon.agent/completed-at`
   is present only when the agent is completed (absent = active — see
   `seon.agent/complete!`); the dashboard groups on it."
  []
  (let [conn db/*conn*
        db   @conn
        rows (db/query
               {:seon.db/db db
                :seon.db/query
                '[:find ?id
                  :where [?a :seon.agent/id ?id]]})]
    (->> rows
         (map first)
         sort
         (mapv (fn [id]
                 (let [ent (db/entity {:seon.db/db db
                                       :seon.db/ref [:seon.agent/id id]})]
                   (cond->
                     {:seon.agent/id         id
                      :seon.agent/state      (or (:seon.agent/state ent) :unknown)
                      ;; :seon.agent/turn-count the ATTR was retired
                      ;; 2026-05-22 — derived from the session log now.
                      :seon.agent/turn-count (default/agent-turn-count ent)}
                     (some? (:seon.agent/completed-at ent))
                     (assoc :seon.agent/completed-at
                            (:seon.agent/completed-at ent)))))))))

(def ^:private sample-attr-preference
  "Attr NAMES whose string value makes the best one-line sample for a
   kind, in preference order — claim/title-ish first."
  ["claim" "title" "question" "name" "text"])

(defn- kind-sample
  "A short sample string for one user-domain kind: the first live
   string value under the kind's OWN attrs across `rows`, preferring
   claim/title-ish attr names. Clipped at 140 chars (display only —
   the stored rows are complete)."
  [kind rows]
  (let [knm (name kind)
        own-strings
        (fn [row]
          (into {}
                (keep (fn [[a v]]
                        (when (and (keyword? a) (= knm (namespace a)))
                          (cond
                            (string? v) [(name a) v]
                            (and (sequential? v) (some string? v))
                            [(name a) (first (filter string? v))]))))
                row))
        m (some #(let [s (own-strings %)] (when (seq s) s)) rows)
        s (when m
            (or (some m sample-attr-preference)
                (val (first (sort-by key m)))))]
    (when s
      (if (> (count s) 140) (str (subs s 0 140) "…") s))))

(defn- findings-data
  "Per-KIND summary of the cluster's stored user-domain knowledge —
   `[{::kind ::row-count ::sample} …]`, derived via
   `seon.agent.findings/user-domain-kinds`: the SAME derivation the
   agent's `:findings` context rung renders from, so the dashboard and
   the prompt can never disagree (the legacy bare-ns `:finding/*`
   query read \"0 findings\" while agents SAW findings in context).
   Cross-agent BY DESIGN: no agent filter."
  [db]
  (mapv (fn [[kind _attrs rows]]
          (cond-> {::kind      kind
                   ::row-count (count rows)}
            (some? (kind-sample kind rows))
            (assoc ::sample (kind-sample kind rows))))
        (findings/user-domain-kinds db)))

(defn- findings-row-total
  "Total stored rows across the pane's kinds — the honest headline
   number for the knowledge group summaries."
  [findings]
  (transduce (map ::row-count) + 0 findings))

(declare data-scan)

(defn- cluster-stats
  "Headline numbers for the mission-control strip. All derived live
   from the DB — they tick up as agents work. `::fact-count` is the
   /data browser's DEFAULT row count — distinct post-bootstrap data
   rows via the SAME `data-scan` derivation (provenance from
   `seon.db/bootstrap-row-ids`), so the header chip and /data can
   never disagree. Findings ARE facts here — the separate findings
   chip died with the legacy bare-ns attr query. The datom/fn/schema/
   test counts back the `?system=1` machinery row."
  [db]
  {::agent-count  (count (d/q '[:find ?e :where [?e :seon.agent/id]] db))
   ::turn-count   (count (d/q '[:find ?t :where [?t :seon.agent.turn/at]] db))
   ::fact-count   (count (into #{} cat (vals (::kinds (data-scan db false)))))
   ::datom-count  (count (d/datoms db :eavt))
   ::fn-count     (count (d/q '[:find ?e :where [?e :seon.fn/sym]] db))
   ::schema-count (count (d/q '[:find ?e :where [?e :seon.schema/key]] db))
   ::test-count   (count (d/q '[:find ?e :where [?e :seon.test/sym]] db))})

(defn- kind-card
  "One stored-knowledge KIND: dot+text header (kind · row count) plus a
   sample claim/title string from its rows."
  [{::keys [kind row-count sample]}]
  [:div {:class (str "border border-base-800 rounded p-2 bg-base-900/60 "
                     "animate-appear")}
   [:div {:class "text-xs font-mono text-amber-400"}
    (str "● " kind " · " row-count (if (= 1 row-count) " row" " rows"))]
   (when (seq sample)
     [:div {:class "text-xs text-text-100 leading-snug mt-1"} sample])])

(defn- knowledge-cards
  [findings]
  (into [:div {:class "grid gap-2"
               :style "grid-template-columns:repeat(auto-fill,minmax(320px,1fr));"}]
        (map kind-card)
        findings))

(defn- entity-kind-label
  "Best-effort kind label for an entity with no resolved renderer: the
   most common keyword NAMESPACE among its attrs (`seon.eval`,
   `my.kb.instruction`, ...). Returns a string, `\"entity\"` when nothing
   namespaced is present."
  [entity]
  (or (->> (keys entity)
           (keep #(when (keyword? %) (namespace %)))
           (remove #(= "db" %))
           frequencies
           (sort-by (comp - val))
           ffirst)
      "entity"))

(defn- truncate-val [v n]
  (let [s (pr-str v)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

(defn- unknown-entity-card
  "Styled fallback for entities whose kind has no `:seon.render/html`
   handler — a kind header + key/value table instead of a raw pr-str
   blob. The right pane never shows XML-ish EDN dumps."
  [entity]
  (let [kind (entity-kind-label entity)
        rows (->> (dissoc entity :db/id)
                  (filter (fn [[k _]] (keyword? k)))
                  (sort-by (comp str first)))]
    [:div {:class "py-1"}
     [:div {:class "flex items-baseline gap-2"}
      [:span {:class "text-xs font-mono font-semibold text-text-300"} kind]
      [:span {:class "text-xs text-text-500"} "(no renderer for this kind)"]]
     (into [:table {:class "mt-0.5 text-xs font-mono"}]
           (map (fn [[k v]]
                  [:tr
                   [:td {:class "pr-3 align-top text-text-400 whitespace-nowrap"}
                    (pr-str k)]
                   [:td {:class "text-text-100 break-all"}
                    (truncate-val v 160)]]))
           rows)]))

(defn- render-entity-hiccup
  "Render `entity` to hiccup. Resolves the render symbol via
   `seon.render/render-entity-html` (per-entity override OR entity-kind
   schema property — Phase 1 pattern, commit d7e3185). Falls back to a
   styled key/value card (`unknown-entity-card`) when html resolution
   yields nil — never a raw pr-str/EDN dump."
  [db agent-id entity]
  (let [input {:seon.db/db db
               :seon.agent/id agent-id
               :seon.render/entity entity}]
    (or
      (try
        (render/render-entity-html input)
        (catch :default e
          [:div {:class "text-error text-xs font-mono"}
           "render error: " (or (.-message e) (str e))]))
      (unknown-entity-card entity))))

;; ============================================================
;; Static-vs-dynamic card discriminator + summaries (unit #24 item 1)
;; ============================================================

(defn- entity-creation-origin
  "The `:seon.db/origin` of the tx that FIRST asserted anything on
   `eid` (the creation tx). nil when the entity has no datoms or the
   tx carries no origin."
  [db eid]
  (when eid
    (when-let [txs (seq (map (fn [^js d] (.-tx d)) (d/datoms db :eavt eid)))]
      (:seon.db/origin (d/entity db (apply min txs))))))

(defn- static-entity?
  "STATIC = collapsed-by-default in the right pane. Discriminator:
   - `:seon.schema` kind rows (`:seon.schema/key` present);
   - `:seon.fn` / `:seon.ns` / `:seon.test` cards whose CREATION tx
     carries `:seon.db/origin :substrate-seed` (the substrate index,
     seeded at boot — agent-AUTHORED fns/nses/tests have `:agent`
     origin and stay expanded).
   Everything else (messages, evals, agent-authored entities) is
   DYNAMIC and renders expanded."
  [db entity]
  (boolean
    (or (:seon.schema/key entity)
        ;; Identity attrs per the registered schemas: :seon.fn/sym +
        ;; :seon.test/sym (strings), :seon.ns/name (keyword). The #24
        ;; version checked :seon.fn/name, which is not a registered
        ;; attr — the fn clause was dead code (masked because
        ;; window :seon.fn cards are subsumed out of the window).
        (and (or (:seon.fn/sym entity)
                 (:seon.ns/name entity)
                 (:seon.test/sym entity))
             (= :substrate-seed
                (entity-creation-origin db (:db/id entity)))))))

(defn- entity-summary-name
  "Short identifying name for a collapsed card's summary line."
  [entity]
  (or (some-> (:seon.schema/key entity) pr-str)
      (some-> (:seon.fn/sym entity) str)
      (some-> (:seon.ns/name entity) pr-str)
      (some-> (:seon.test/sym entity) str)
      ""))

(defn- entity-gist
  "One-line gist for a collapsed card's summary: first line of the
   doc/content, truncated to 80 chars."
  [entity]
  (let [s    (or (:seon.fn/doc entity)
                 (:seon.agent.message/content entity)
                 "")
        line (or (first (str/split-lines s)) "")]
    (if (> (count line) 80) (str (subs line 0 80) "…") line)))

;; ============================================================
;; Turn grouping (unit #24 item 2) — derive each card's turn from the
;; `:seon.agent.session/turns` → `:seon.agent.turn/messages|evals` component refs.
;; ============================================================

(defn- hh-mm-ss
  [at]
  (if (instance? js/Date at)
    (let [pad #(if (< % 10) (str "0" %) (str %))]
      (str (pad (.getHours at)) ":" (pad (.getMinutes at))
           ":" (pad (.getSeconds at))))
    ""))

(defn- turn-info-by-child
  "Map of child eid (message/eval) → `{::turn-n <int> ::turn-at <Date>}`.
   Turn NUMBER is the 1-based index of the turn within its session's
   turns sorted by `:seon.agent.turn/at` — same derivation as the agent's
   `turn N` prompt line. Entities not hanging off any turn (schema
   rows, substrate index) are absent from the map and keep their
   tx-time position in the card list."
  [db]
  (let [turn-rows  (d/q '[:find ?s ?turn ?tat
                          :where
                          [?s :seon.agent.session/turns ?turn]
                          [?turn :seon.agent.turn/at ?tat]]
                        db)
        turn->info (into {}
                         (for [[_s rows] (group-by first turn-rows)
                               :let [sorted (sort-by (fn [[_ _ ^js at]] (.getTime at))
                                                     rows)]
                               [i [_ turn at]] (map-indexed vector sorted)]
                           [turn {::turn-n (inc i) ::turn-at at}]))
        child-rows (d/q '[:find ?turn ?c
                          :where
                          (or [?turn :seon.agent.turn/messages ?c]
                              [?turn :seon.agent.turn/evals ?c])]
                        db)]
    (into {}
          (keep (fn [[turn c]]
                  (when-let [info (get turn->info turn)]
                    [c info])))
          child-rows)))

(defn- snapshot
  "Compute one render snapshot for `agent-id`:
     {:ai-text <string>
      :section-texts [{:seon.ctx/name … :seon.render/text …} ...]
      :token-est <int>
      :char-count <int>
      :html-cards [<card-map> ...]  ; one per visible entity, in render order
      :agent <pulled entity or nil>}
   Each card-map: `{::hiccup ::static? ::kind ::name ::gist ::card-key
   ::turn-n ::turn-at}` (turn keys absent for non-turn entities)."
  [agent-id]
  (let [{:seon.db/keys [db]} (agent-view/agent-view {:seon.agent/id agent-id})
        ;; ONE composer for the left-pane text (`seon.agent/assemble-context`,
        ;; the exact bytes the agent receives) + the entity list behind it —
        ;; both via `inspect/ctx-preview`, so the webview can NEVER diverge
        ;; from what the LLM sees.
        {:seon.render/keys [text entities token-estimate section-texts]}
        (inspect/ctx-preview {:seon.agent/id agent-id})
        turn-info (turn-info-by-child db)
        cards (->> entities
                   (keep (fn [e]
                           (when-let [h (render-entity-hiccup db agent-id e)]
                             (merge
                               {::hiccup   h
                                ::static?  (static-entity? db e)
                                ::kind     (entity-kind-label e)
                                ::name     (entity-summary-name e)
                                ::gist     (entity-gist e)
                                ::card-key (str "card-" (:db/id e))}
                               (when-let [d (:seon.eval/duration-ms e)]
                                 {::dur d})
                               (get turn-info (:db/id e))))))
                   vec)
        ;; Per-turn elapsed = Σ eval duration-ms within the turn. Powers
        ;; the turn-separator badge + the header activity sparkline.
        turn-durs (->> cards
                       (filter ::turn-n)
                       (group-by ::turn-n)
                       (sort-by key)
                       (mapv (fn [[n cs]] [n (reduce + 0 (keep ::dur cs))])))
        ;; The agent's OWN tile (unit 1.4) — rendered explicitly (the
        ;; agent entity is not part of `visible-entities`). Wired
        ;; `:seon.render.live-tile/content` wins; default is
        ;; `seon.render.live-tile/welcome`.
        tile  (:seon.render/hiccup
                (render/render-agent-tile {:seon.db/db db
                                           :seon.agent/id agent-id}))
        ent   (default/all-running-agents db)
        agent (some #(when (= agent-id (:seon.agent/id %)) %) ent)]
    {:ai-text   (or text "")
     :section-texts (or section-texts [])
     :char-count (count (or text ""))
     :token-est  (or token-estimate 0)
     :html-cards cards
     :turn-durs  turn-durs
     ;; Knowledge is CROSS-AGENT by design: read the UNFILTERED conn,
     ;; not the agent-view FilteredDB — agent B must see agent A's
     ;; findings (the demo money-shot is exactly that reuse).
     :findings   (findings-data @db/*conn*)
     ;; render-cap overflow (seon.render/renderable-entities) — rides
     ;; as metadata on the entities vector so the response schema is
     ;; unchanged. Surfaced as the "older elided" note in the pane.
     :elided    (or (:seon.render/elided (meta entities)) 0)
     :agent-tile tile
     :agent      agent
     :handler-count
     (count (try (:seon.handler/list (inspect/handlers {:seon.agent/id agent-id}))
                 (catch :default _ [])))}))

;; ============================================================
;; Page rendering — full page (for initial GET) AND morph fragments
;; (for SSE pushes).
;;
;; The two panes use stable ids `#inspect-ai-<id>` and
;; `#inspect-html-<id>` so datastar morphs by id when the SSE event
;; contains a re-rendered fragment.
;; ============================================================

(defn- ai-pane-id   [agent-id] (str "inspect-ai-" agent-id))
(defn- html-pane-id [agent-id] (str "inspect-html-" agent-id))
(defn- header-id    [agent-id] (str "inspect-header-" agent-id))

(defn- fmt-ms
  "`1234` → `\"1.2s\"`, `53` → `\"53ms\"`."
  [ms]
  (if (>= ms 1000)
    (str (.toFixed (/ ms 1000) 1) "s")
    (str ms "ms")))

(defn- activity-sparkline
  "Last 12 turns' eval time as a strip of tiny bars — pure divs, no
   deps. Hover a bar for `turn N · 1.2s`."
  [turn-durs]
  (when (seq turn-durs)
    (let [last12 (vec (take-last 12 turn-durs))
          mx     (max 1 (apply max (map second last12)))]
      (into [:div {:class "flex items-end gap-px h-3.5"
                   :title "eval time per turn"}]
            (map (fn [[n d]]
                   [:div {:class "w-1 rounded-sm bg-amber-600/70"
                          :style (str "height:"
                                      (max 12 (js/Math.round (* 100 (/ d mx))))
                                      "%")
                          :title (str "turn " n " · " (fmt-ms d))}]))
            last12))))

(defn- header-fragment
  [agent-id {:keys [agent char-count token-est handler-count turn-durs]}]
  (let [state (or (:seon.agent/state agent) :unknown)
        ;; Derived — the :seon.agent/turn-count attr was retired.
        turns (default/agent-turn-count agent)]
    [:header {:id (header-id agent-id)
              :class "flex items-center gap-3 p-2 border-b border-base-800 bg-base-900"}
     [:span {:class "text-xs font-mono text-text-200"} "agent " agent-id]
     (if (= :running state)
       ;; The live "the agent is thinking RIGHT NOW" pulse — resolves
       ;; back to the plain status dot on the next morph when the turn
       ;; completes.
       [:span {:class "inline-flex items-center gap-1.5 text-xs font-mono text-amber-400"}
        [:span {:class "w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse"}]
        (str "thinking — turn " (inc turns))]
       (comp/status-dot state))
     [:span {:class "text-xs text-text-400"} (str "turn " turns)]
     [:span {:class "text-xs text-text-400"} (str handler-count " handlers")]
     (activity-sparkline turn-durs)
     [:span {:class "text-xs text-text-500 ml-auto"}
      (str "~" token-est " tokens · " char-count " chars")]
     [:a {:href (str "/agent/" agent-id)
          :class "text-xs text-amber-500 hover:text-amber-300"} "← chat"]
     [:a {:href "/agents"
          :class "text-xs text-amber-500 hover:text-amber-300"} "← all agents"]]))

(def ^:private open-ai-sections
  "Left-pane sections that render EXPANDED by default — the dynamic
   tail. Everything else (system, capabilities, catalogs, ns-context,
   warnings) is static bulk the user has already read; it collapses to
   a one-line summary. `:context` is the divergence-fallback pseudo-
   section carrying the whole joined text (see
   `seon.agent.inspect/per-section-texts`) — must stay open."
  #{:transcript :prompt :context})

(defn- fmt-chars
  "`3214` → `\"3,214\"` — comma-grouped char count for summaries."
  [n]
  (let [s (str n)]
    (->> (reverse s)
         (partition-all 3)
         (map (partial apply str))
         (str/join ",")
         (str/reverse))))

(defn- ai-section-details
  "One `<details>` per context section. `data-seon-key` keys the
   client-side open-state guard (user toggles survive SSE morphs)."
  [{sec-name :seon.ctx/name sec-text :seon.render/text}]
  (let [open? (contains? open-ai-sections sec-name)]
    [:details (cond-> {:class "mb-1"
                       :data-seon-key (str "ai-sec-" (name sec-name))}
                open? (assoc :open true))
     [:summary {:class (str "cursor-pointer select-none text-xs font-mono "
                            "text-text-400 hover:text-text-200 py-0.5")}
      (str (name sec-name) " (" (fmt-chars (count sec-text)) " chars)")]
     [:pre {:class "whitespace-pre-wrap text-xs font-mono text-text-100 mt-0.5"}
      sec-text]]))

(defn- ai-pane-fragment
  [agent-id {:keys [ai-text section-texts]}]
  [:div {:id (ai-pane-id agent-id)
         :class "flex flex-col h-full overflow-hidden border-r border-base-800"}
   [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
    ":seon.render/ai  (what the LLM sees)"]
   (cond
     (str/blank? ai-text)
     [:pre {:class (str "flex-1 overflow-auto p-3 text-xs font-mono "
                        "whitespace-pre-wrap text-text-100 bg-base-950")}
      "(empty context)"]

     (seq section-texts)
     (into [:div {:class "flex-1 overflow-auto p-3 bg-base-950"}]
           (map ai-section-details)
           section-texts)

     :else
     [:pre {:class (str "flex-1 overflow-auto p-3 text-xs font-mono "
                        "whitespace-pre-wrap text-text-100 bg-base-950")}
      ai-text])])

(defn- card-block
  "Render one card-map. STATIC cards wrap in a `<details>` collapsed by
   default — summary = kind + name + one-line gist. DYNAMIC cards
   render expanded, visually weighted for chat-first reading:
   - `seon.agent.message` → the conversation. Full-width, no machinery rail.
   - `seon.eval`    → the machinery. Indented + dimmer border, so the
     eye follows the dialogue and the evals read as the agent's 'work
     shown' underneath it.
   Every dynamic card carries a stable `:id` (`card-<eid>`) so
   idiomorph PRESERVES existing nodes across SSE morphs and only
   genuinely-new cards get the `.animate-appear` first-paint
   fade/drift (`@starting-style` in input.css — no JS)."
  [{::keys [hiccup static? kind name gist card-key]}]
  (let [wrap-class (case kind
                     "seon.eval"
                     "border-l-2 border-base-700/80 pl-2 py-1 mb-1 ml-3 opacity-90"
                     "seon.agent.message"
                     "py-1 mb-1"
                     "border-l-2 border-amber-700/40 pl-2 py-1 mb-1")]
    (if static?
      [:details {:class "border-l-2 border-amber-700/40 pl-2 py-1 mb-1"
                 :data-seon-key card-key}
       [:summary {:class (str "cursor-pointer select-none text-xs font-mono "
                              "text-text-400 hover:text-text-200")}
        [:span {:class "font-semibold text-text-300"} kind]
        (when (seq name)
          [:span {:class "text-amber-500/80"} (str " " name)])
        (when (seq gist)
          [:span {:class "text-text-500"} (str " — " gist)])]
       [:div {:class "mt-0.5"} hiccup]]
      [:div {:id card-key :class (str wrap-class " animate-appear")} hiccup])))

(defn- turn-separator
  [turn-n turn-at dur]
  [:div {:class "flex items-center gap-2 my-1 text-xs font-mono text-text-500"}
   [:span {:class "flex-1 border-t border-base-800"}]
   [:span (str "turn " turn-n
               (let [t (hh-mm-ss turn-at)]
                 (when (seq t) (str " · " t)))
               (when (and dur (pos? dur))
                 (str " · " (fmt-ms dur))))]
   [:span {:class "flex-1 border-t border-base-800"}]])

(defn- cards-with-separators
  "Interleave turn-separator rows into the chronological card list: a
   separator renders before the first card of each turn (with the
   turn's Σ eval time from `turn-durs`). Cards without a turn keep
   their tx-time position and never break a turn group."
  [cards turn-durs]
  (let [dur-by-turn (into {} turn-durs)]
    (loop [out [] last-turn nil cards cards]
      (if-let [{::keys [turn-n turn-at] :as card} (first cards)]
        (let [new-turn? (and (some? turn-n) (not= turn-n last-turn))]
          (recur (cond-> out
                   new-turn? (conj (turn-separator turn-n turn-at
                                                   (get dur-by-turn turn-n)))
                   true      (conj (card-block card)))
                 (if new-turn? turn-n last-turn)
                 (rest cards)))
        out))))

(defn- knowledge-group
  "Collapsible 'cluster knowledge' group at the top of the right pane —
   the money-shot surface. Open by default; the open-state guard
   (`data-seon-key`) keeps the user's toggle across morphs. Renders
   nothing when no findings exist yet — derived, self-healing."
  [findings]
  (when (seq findings)
    [:details {:open true
               :data-seon-key "cluster-knowledge"
               :class (str "border border-amber-700/40 rounded p-1.5 mb-2 "
                           "bg-amber-950/20")}
     [:summary {:class (str "cursor-pointer select-none text-xs font-mono "
                            "text-amber-400 hover:text-amber-300")}
      (let [total (findings-row-total findings)]
        (str "◆ what this cluster has learned — " total
             (if (= 1 total) " row" " rows")
             " across " (count findings)
             (if (= 1 (count findings)) " kind" " kinds")))]
     [:div {:class "mt-1.5"} (knowledge-cards findings)]]))

(defn- thinking-bubble
  "Placeholder bubble pinned under the newest card while the agent is
   `:running` — it 'resolves' into the real cards on the next morph.
   No stable id ON PURPOSE: every morph treats it as a fresh node, so
   it re-animates while real cards stay put."
  [turns]
  [:div {:class (str "flex items-center gap-2 py-1.5 px-2 mb-1 rounded "
                     "border border-amber-700/40 bg-amber-950/30 "
                     "text-xs font-mono text-amber-400 animate-appear")}
   [:span {:class "w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse"}]
   (str "thinking — turn " (inc turns) " …")])

(defn- html-pane-fragment
  [agent-id {:keys [html-cards agent-tile elided findings turn-durs agent]}]
  (let [running? (= :running (:seon.agent/state agent))]
    [:div {:id (html-pane-id agent-id)
           :class "flex flex-col h-full overflow-hidden"}
     [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
      ":seon.render/html  (rendered view)"]
     ;; `data-seon-scroll` arms the client-side bottom-autoscroll: pinned
     ;; to newest while the user is at/near the bottom; never yanks while
     ;; they read history. Stable id so idiomorph preserves the element
     ;; (and its scrollTop + JS-property flag) across SSE morphs.
     ;; `seon-agent-content` opts this pane into the base content
     ;; layer (input.css): semantic HTML in agent-rendered cards gets
     ;; Phosphor element styling despite Tailwind preflight.
     [:div {:id (str "inspect-cards-" agent-id)
            :data-seon-scroll "1"
            :class "seon-agent-content flex-1 overflow-auto p-2 text-xs bg-base-950"}
      ;; The agent's OWN tile — always ABOVE the per-entity cards. It
      ;; lives inside this morphed fragment, so every SSE push
      ;; re-renders it: the agent repoints `:seon.render/html` on its
      ;; entity and the tile updates live.
      (when agent-tile
        [:div {:class (str "border border-amber-700/60 rounded p-1 mb-2 "
                           "bg-base-900/60")}
         agent-tile])
      ;; Cross-agent knowledge — any agent's findings render here.
      (knowledge-group findings)
      ;; render-cap note — oldest entities beyond the bound are not
      ;; materialized at all (seon.render/render-cap); collapsed static
      ;; cards make them low-value, so say how many were skipped.
      (when (and elided (pos? elided))
        [:div {:class "text-text-500 italic text-xs font-mono px-2 py-0.5"}
         (str "… " elided " older " (if (= 1 elided) "entity" "entities")
              " elided")])
      (if (seq html-cards)
        (into [:div {:class "flex flex-col"}]
              (cards-with-separators html-cards turn-durs))
        [:div {:class "text-text-500 italic p-2"}
         "ask this agent something ↓ — every message, eval, fn and schema it touches will appear here live"])
      (when running?
        (thinking-bubble (default/agent-turn-count agent)))]]))

(defn- chat-bar-fragment
  "Sticky bottom bar spanning both panes. Submits as a regular
   `application/x-www-form-urlencoded` POST to `/chat?agent=<id>` —
   the same endpoint Datastar `data-on-click__post` would call. We
   intercept the submit in inline JS, fetch() the form data, clear
   the input on success, and let the existing SSE registry push the
   re-rendered panes once the user message lands in the DB.

   No reload on submit; SSE fans the user-message tx out and the
   inspector's tx-listener re-renders both panes within ~100ms."
  [agent-id]
  [:form {:id "seon-chat-form"
          :action (str "/chat?agent=" agent-id)
          :method "post"
          :class (str "shrink-0 flex items-center gap-2 "
                      "border-t border-base-800 bg-base-900 px-2 py-1.5")
          ;; Failure is SHOWN, never swallowed: a non-ok response (422
          ;; dead agent, 500) lands its body in #seon-chat-err — before
          ;; this, a human typing into a stale tab got NO feedback at
          ;; all (observed live 2026-06-10: post-flip tabs pointing at
          ;; pre-flip agent ids silently 422'd on every send).
          :onsubmit (str "event.preventDefault();"
                        "var f=this;var i=f.elements['text'];"
                        "var e=document.getElementById('seon-chat-err');"
                        "var text=i.value;if(!text||!text.trim())return false;"
                        "fetch(f.action,{method:'POST',"
                        "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                        "body:'text='+encodeURIComponent(text)})"
                        ".then(function(r){if(r.ok){i.value='';i.focus();"
                        "if(e)e.textContent='';"
                        ;; Sending a message re-PINS the chat to the
                        ;; bottom — even if the user had scrolled up
                        ;; reading history, their own send is an
                        ;; explicit 'jump to newest' (otherwise the
                        ;; agent's reply lands offscreen and the page
                        ;; looks dead — the one case the pinned
                        ;; autoscroll's read-protection got wrong).
                        "var sc=document.querySelector('[data-seon-scroll]');"
                        "if(sc){sc.__seonAtBottom=true;sc.scrollTop=sc.scrollHeight;}}"
                        "else{r.text().then(function(t){"
                        "if(e)e.textContent='\\u2717 '+(t||('HTTP '+r.status));});}})"
                        ".catch(function(err){if(e)e.textContent='\\u2717 '+err;});"
                        "return false;")}
   [:input {:type "text"
            :name "text"
            :placeholder (str "message agent " agent-id " …  (Cmd/Ctrl+Enter to send)")
            :autocomplete "off"
            :autofocus true
            :class (str "flex-1 bg-base-950 border border-base-800 rounded "
                        "px-2 py-1 text-amber-50 text-xs font-mono "
                        "placeholder:text-text-500 "
                        "focus:outline-none focus:border-amber-700")}]
   [:span {:id "seon-chat-err"
           :class (str "shrink max-w-[40%] truncate text-red-400 "
                       "text-xs font-mono")}]
   [:button {:type "submit"
             :class (str "bg-amber-900/70 hover:bg-amber-800 text-amber-50 "
                         "px-3 py-1 rounded text-xs font-mono")}
    "send"]])

(def ^:private page-style-css
  "Inline style shared by both per-agent shells: bend a couple of
   atom-one-dark colors toward the Phosphor Terminal palette (amber
   emphasis), minimal markdown body styling for narration/bubbles
   (Tailwind's `prose` plugin isn't loaded via CDN), and the consumer
   bubble's larger markdown body (`.seon-bubble` is absent from the
   debug view, so the override is inert there)."
  (str "code.hljs{background:transparent !important;padding:0 !important;}"
       ".hljs-keyword,.hljs-built_in{color:#fbbf24;}"
       ".hljs-string{color:#fde68a;}"
       ".hljs-symbol,.hljs-literal{color:#fcd34d;}"
       ".hljs-comment{color:#78716c;font-style:italic;}"
       ".markdown{color:#d6d3d1;font-size:0.75rem;line-height:1.4;}"
       ".markdown h1{font-size:0.95rem;color:#fbbf24;margin:0.4rem 0 0.2rem;font-weight:600;}"
       ".markdown h2{font-size:0.85rem;color:#fcd34d;margin:0.35rem 0 0.15rem;font-weight:600;}"
       ".markdown h3{font-size:0.8rem;color:#fde68a;margin:0.3rem 0 0.1rem;font-weight:600;}"
       ".markdown p{margin:0.2rem 0;}"
       ".markdown ul,.markdown ol{margin:0.2rem 0 0.2rem 1rem;}"
       ".markdown li{margin:0.05rem 0;list-style:disc;}"
       ".markdown ol li{list-style:decimal;}"
       ".markdown code{font-family:ui-monospace,monospace;color:#fde68a;background:#1c1917;padding:0 0.2rem;border-radius:0.15rem;}"
       ".markdown pre{background:#1c1917;padding:0.3rem 0.4rem;border-radius:0.2rem;overflow-x:auto;margin:0.2rem 0;}"
       ".markdown pre code{background:transparent;padding:0;}"
       ".markdown strong{color:#fef3c7;font-weight:600;}"
       ".markdown em{color:#fde68a;font-style:italic;}"
       ".markdown a{color:#fbbf24;text-decoration:underline;}"
       ".markdown blockquote{border-left:2px solid #57534e;padding-left:0.5rem;color:#a8a29e;margin:0.2rem 0;}"
       ".seon-bubble .markdown{font-size:0.875rem;line-height:1.5;}"))

(defn- brand-css-style
  "The downstream brand stylesheet as an inline [:style], or nil when
   SEON_BRAND_CSS is unset/unreadable (C-17). Rendered AFTER the
   output.css link in every page head so its token overrides
   (--color-base-*, --color-amber-*, fonts) win the cascade."
  []
  (when-let [css (brand/css-text)]
    [:style (html/raw css)]))

(defn- page-head
  "Shared <head> for both per-agent shells: output.css (+ the optional
   downstream brand stylesheet, C-17), highlight.js
   (+ the Clojure language module — NOT in the core CDN build; without
   it every code block warned and fell back to no-highlight, observed
   live 2026-06-09), marked.js for `data-markdown` bodies, the
   Phosphor style overrides, and the Datastar module."
  [title]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:title title]
   [:link {:rel "stylesheet" :href "/css/output.css"}]
   (brand-css-style)
   [:link {:rel "stylesheet"
           :href "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-dark.min.css"}]
   [:script {:src "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/highlight.min.js"}]
   [:script {:src "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/languages/clojure.min.js"}]
   [:script {:src "https://cdn.jsdelivr.net/npm/marked/marked.min.js"}]
   [:style (html/raw page-style-css)]
   [:script {:type "module" :src "/js/datastar.js"}]])

(def ^:private page-script-js
  "Inline page JS shared by both per-agent shells — the re-highlight /
   markdown / details-open-state / bottom-autoscroll passes plus the
   Cmd/Ctrl+Enter chat submit. Every pass is a no-op on nodes whose
   state already matches, so the MutationObserver settles instead of
   looping. (See the inline comments below for the hard-won details.)"
  ;; Settle guard (__hlSrc property): highlightElement
  ;; mutates childList, which re-fires the observer below —
  ;; skipping nodes whose text hasn't changed since their
  ;; last highlight makes the pass a no-op the second time
  ;; around instead of looping. textContent is stable
  ;; across highlighting (spans wrap, text unchanged).
  (str "function seonHighlightAll(){"
       "if(!window.hljs)return;"
       "document.querySelectorAll('pre code.language-clojure').forEach(function(el){"
       "var t=el.textContent;"
       "if(el.__hlSrc===t)return;"
       "el.removeAttribute('data-highlighted');"
       "window.hljs.highlightElement(el);"
       "el.__hlSrc=t;});}"
       ;; marked.js: walk every [data-markdown] container,
       ;; render its raw text into innerHTML once (then mark
       ;; it done so re-runs on subsequent mutations skip).
       ;; XSS: getAttribute returns the UNescaped original
       ;; text and marked passes inline HTML through, so we
       ;; escape &/</> BEFORE parsing — agent-authored
       ;; <script>/<img onerror> renders as visible text,
       ;; while markdown (bold/code/lists/headings) still
       ;; formats normally.
       ;;
       ;; Re-render guard: keyed on the JS property __mdSrc
       ;; (NOT a data- attribute — Datastar's idiomorph can
       ;; carry old attributes across a morph while CLEARING
       ;; the rendered children, which left every message
       ;; body empty after the first SSE push). A node
       ;; re-renders when its source changed OR its children
       ;; were wiped; otherwise it's skipped, so the
       ;; MutationObserver below can't loop.
       "function seonMarkdownAll(){"
       "if(!window.marked)return;"
       "document.querySelectorAll('[data-markdown]').forEach(function(el){"
       "var src=el.getAttribute('data-markdown')||'';"
       "if(el.__mdSrc===src&&el.childNodes.length>0)return;"
       "var esc=src.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');"
       "el.innerHTML=window.marked.parse(esc);"
       "el.__mdSrc=src;});}"
       ;; Open-state survival across morphs: the server
       ;; always re-renders <details> with their DEFAULT
       ;; open state, and idiomorph SYNCS attributes — so
       ;; an SSE push would clobber every user toggle
       ;; (verified empirically: morph removes the user's
       ;; `open`). Same class of guard as __mdSrc: user
       ;; intent lives in a JS-side map (window.seonOpen,
       ;; keyed by data-seon-key) that the morph can't
       ;; touch; the observer pass reapplies it. Recording
       ;; happens on summary CLICK (a user gesture), via
       ;; setTimeout(0) so we read .open AFTER the default
       ;; toggle action ran. Programmatic .open changes
       ;; (our own reapply) never record.
       "window.seonOpen=window.seonOpen||{};"
       "document.addEventListener('click',function(e){"
       "var s=e.target&&e.target.closest?e.target.closest('summary'):null;"
       "if(!s)return;var d=s.parentElement;"
       "if(d&&d.tagName==='DETAILS'&&d.hasAttribute('data-seon-key')){"
       "setTimeout(function(){"
       "window.seonOpen[d.getAttribute('data-seon-key')]=d.open;},0);}});"
       "function seonReapplyOpen(){"
       "document.querySelectorAll('details[data-seon-key]').forEach(function(d){"
       "var k=d.getAttribute('data-seon-key');"
       "if(k in window.seonOpen&&d.open!==window.seonOpen[k]){"
       "d.open=window.seonOpen[k];}});}"
       ;; Bottom-autoscroll for [data-seon-scroll] panes:
       ;; pinned to newest while the user is at/near the
       ;; bottom (40px slack); a capturing scroll listener
       ;; records intent on the element as a JS property
       ;; (morph-proof — idiomorph preserves the node via
       ;; its stable id). Undefined flag = treat as
       ;; at-bottom (first render scrolls to newest).
       "document.addEventListener('scroll',function(e){"
       "var c=e.target;"
       "if(c&&c.hasAttribute&&c.hasAttribute('data-seon-scroll')){"
       "c.__seonAtBottom=(c.scrollTop+c.clientHeight>=c.scrollHeight-40);}},true);"
       "function seonAutoscroll(){"
       "document.querySelectorAll('[data-seon-scroll]').forEach(function(c){"
       "if(c.__seonAtBottom!==false){c.scrollTop=c.scrollHeight;}});}"
       "document.addEventListener('DOMContentLoaded',function(){"
       "seonHighlightAll();seonMarkdownAll();"
       "seonReapplyOpen();seonAutoscroll();});"
       ;; ANY childList mutation re-runs the passes — a
       ;; Datastar morph can be removal-only (it clears the
       ;; markdown container's rendered children), so
       ;; gating on addedNodes misses it. Both passes are
       ;; no-ops on already-rendered nodes, so the observer
       ;; settles instead of looping.
       ;; seonReapplyOpen runs BEFORE seonAutoscroll —
       ;; reopening a details changes scrollHeight, so the
       ;; bottom-pin must measure after it. Both are no-ops
       ;; when state already matches, so the observer
       ;; settles instead of looping.
       "new MutationObserver(function(muts){"
       "var any=false;for(var i=0;i<muts.length;i++){"
       "if(muts[i].type==='childList'){any=true;break;}}"
       "if(any){seonHighlightAll();seonMarkdownAll();"
       "seonReapplyOpen();seonAutoscroll();}"
       "}).observe(document.body,{subtree:true,childList:true});"
       ;; Cmd/Ctrl+Enter submits the chat form from anywhere.
       "document.addEventListener('keydown',function(e){"
       "if((e.metaKey||e.ctrlKey)&&e.key==='Enter'){"
       "var f=document.getElementById('seon-chat-form');"
       "if(f){e.preventDefault();f.requestSubmit();}}});"))

(defn- inspector-shell
  "The full page for `/agent/<id>/debug` — the two-pane debug
   inspector (raw context sections left, rendered cards right), kept
   exactly as it was when it lived at `/agent/<id>` (live-tiles PRD
   §1 Surface 3). The body contains the two-pane grid; the header is
   inside the morph zone so it updates too."
  [agent-id snap]
  (let [b (brand/info)]
    (str
      "<!DOCTYPE html>"
      (html/->string
        [:html {:lang "en" :data-theme (::brand/theme b)}
         (page-head (brand/page-title b (str "agent " agent-id " · debug")))
         [:body {:class "h-screen bg-base-950 text-text-50 font-sans antialiased flex flex-col"}
          [:div {:data-init (str "@get('/agent/" agent-id "/debug/sse')")
                 :data-on:online__window (str "@get('/agent/" agent-id "/debug/sse')")}]
          (header-fragment agent-id snap)
          [:div {:class "flex-1 grid h-0 min-h-0"
                 :style "grid-template-columns: 1fr 1fr;"}
           (ai-pane-fragment   agent-id snap)
           (html-pane-fragment agent-id snap)]
          (chat-bar-fragment agent-id)
          [:div {:class (str "shrink-0 px-2 py-0.5 text-right text-[10px] "
                             "font-mono text-text-500 bg-base-900 "
                             "border-t border-base-800")}
           "esc → chat"]
          [:script (html/raw page-script-js)]
          ;; Escape leaves debug for the chat view. Standalone tab →
          ;; navigate to /agent/<id>; inside the consumer page's debug
          ;; OVERLAY iframe → ask the parent to close it (the parent's
          ;; own Esc handler can't fire while focus is in the iframe).
          ;; Same one-keybinding pattern as the overlay script.
          [:script (html/raw
                     (str "document.addEventListener('keydown',function(e){"
                          "if(e.key!=='Escape')return;"
                          "if(window.self!==window.top){"
                          "try{window.parent.postMessage('seon-debug-close','*');}"
                          "catch(err){}}"
                          "else{window.location='/agent/"
                          agent-id "';}});"))]]]))))

;; ============================================================
;; The CONSUMER agent view — `/agent/<id>` (live-tiles PRD §1
;; Surface 2). Split screen: chat bubbles + input left, the SAME live
;; tile expanded right. One render mechanism — the right pane is wide
;; enough that the `.seon-tile` container query selects the expanded
;; blocks (breakpoint 480px, resources/public/css/input.css).
;; ============================================================

(defn- chat-pane-id   [agent-id] (str "chat-pane-" agent-id))
(defn- tile-pane-id   [agent-id] (str "tile-pane-" agent-id))
(defn- chat-header-id [agent-id] (str "chat-header-" agent-id))

(defn- consumer-snapshot
  "Compute one consumer-view render snapshot for `agent-id`:
   `{:agent <entity> :tile <hiccup-or-nil> :messages [<bubble-msg> …]}`.
   Deliberately CHEAPER than [[snapshot]] — no ctx-preview (the
   consumer view never shows the raw prompt), just the conversation
   query + the tile render against the live conn."
  [agent-id]
  (let [db  @db/*conn*
        ent (db/entity {:seon.db/db db
                        :seon.db/ref [:seon.agent/id agent-id]})]
    {:agent    ent
     :tile     (:seon.render/hiccup
                 (render/render-agent-tile {:seon.db/db db
                                            :seon.agent/id agent-id}))
     :messages (:seon.render.chat/messages
                 (chat/conversation {:seon.agent/id agent-id
                                     :seon.db/db db}))}))

(defn- consumer-header-fragment
  "Consumer-view header — breathing room over density (PRD §5): the
   agent's id + live status, the `⚙ debug` cross-link. Morph target
   (the thinking pulse resolves on the next push)."
  [agent-id {:keys [agent]}]
  (let [state (or (:seon.agent/state agent) :unknown)
        turns (default/agent-turn-count agent)]
    [:header {:id (chat-header-id agent-id)
              :class "flex items-center gap-3 px-4 py-3 border-b border-base-800 bg-base-900"}
     [:span {:class "text-sm font-mono text-text-100"} (str "agent " agent-id)]
     (if (= :running state)
       [:span {:class "inline-flex items-center gap-1.5 text-xs font-mono text-amber-400"}
        [:span {:class "w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse"}]
        "thinking …"]
       (comp/status-dot state))
     [:span {:class "text-xs font-mono text-text-500"} (str "turn " turns)]
     ;; Opens the debug view as a full-viewport OVERLAY (iframe onto
     ;; /agent/<id>/debug) — no URL change (live-tiles PRD §1 Surface
     ;; 3 / U6). Backtick opens it too; Esc or the button closes.
     ;; Stable id + document-delegated click handler in
     ;; [[debug-overlay-script-js]] — morph-proof.
     [:button {:id "seon-debug-toggle"
               :type "button"
               :class (str "ml-auto text-xs font-mono text-text-400 "
                           "hover:text-amber-300 border border-base-700 "
                           "rounded px-2 py-1 cursor-pointer")}
      "⚙ debug"]
     [:a {:href "/agents"
          :class "text-xs font-mono text-amber-500 hover:text-amber-300"}
      "← all agents"]]))

(defn- chat-pane-fragment
  "Left pane — the bubble stream (`seon.render.chat/bubble-stream`),
   bottom-pinned via the shared `data-seon-scroll` autoscroll."
  [agent-id {:keys [messages]}]
  [:div {:id (chat-pane-id agent-id)
         :data-seon-scroll "1"
         :class "flex-1 overflow-auto p-4 bg-base-950"}
   (:seon.render/hiccup
     (chat/bubble-stream {:seon.render.chat/messages (or messages [])}))])

(defn- tile-pane-fragment
  "Right pane — the SAME live tile as the root grid, given room: the
   pane is wider than the 480px container breakpoint, so the tile's
   `.seon-tile-expanded` blocks show. Identical wired value, zero
   tile-side awareness of the surface."
  [agent-id {:keys [tile]}]
  [:div {:id (tile-pane-id agent-id)
         :class "h-full overflow-auto border-l border-base-800 bg-base-900/40 p-4"}
   (or tile
       [:div {:class "text-sm text-text-500 italic p-4"}
        "no tile yet — the agent will draw here as it works"])])

(def ^:private debug-overlay-script-js
  "Consumer-view-only script (live-tiles PRD §1 Surface 3 / U6): the
   debug view opens as a full-viewport overlay WITHOUT a URL change —
   an iframe lazily pointed at `/agent/<id>/debug` (the zero-
   duplication floor: the real debug page, its own SSE stream inside).
   Open: the `⚙ debug` header button (document-delegated click —
   morph-proof) or backtick. Close: Esc or the button. The backtick
   is FOCUS-GUARDED — never fires while the chat input (or any
   input/textarea/select/contentEditable) has focus. Closing resets
   the iframe to about:blank so its SSE stream tears down."
  (str
    "(function(){"
    "function seonDebugOverlay(open){"
    "var o=document.getElementById('seon-debug-overlay');"
    "if(!o)return;"
    "var f=document.getElementById('seon-debug-frame');"
    "var isOpen=o.classList.contains('open');"
    "if(open===undefined){open=!isOpen;}"
    "if(open===isOpen)return;"
    "if(open){f.src=f.getAttribute('data-src');o.classList.add('open');}"
    "else{o.classList.remove('open');f.src='about:blank';}}"
    "document.addEventListener('click',function(e){"
    "var t=e.target.closest?e.target.closest('#seon-debug-toggle'):null;"
    "if(t){e.preventDefault();seonDebugOverlay();}});"
    ;; The debug page posts 'seon-debug-close' when Esc is pressed
    ;; WITH FOCUS INSIDE the overlay iframe (the parent's keydown
    ;; can't see that) — same close path as the parent's own Esc.
    "window.addEventListener('message',function(e){"
    "if(e.data==='seon-debug-close'){seonDebugOverlay(false);}});"
    "document.addEventListener('keydown',function(e){"
    "if(e.key==='Escape'){seonDebugOverlay(false);return;}"
    "if(e.key==='`'){"
    "var t=e.target,tag=t&&t.tagName;"
    "if(tag==='INPUT'||tag==='TEXTAREA'||tag==='SELECT'"
    "||(t&&t.isContentEditable))return;"
    "e.preventDefault();seonDebugOverlay(true);}});"
    "})();"))

(defn- debug-overlay-fragment
  "The (initially hidden) full-viewport overlay shell — static, NOT a
   morph target; [[debug-overlay-script-js]] toggles `.open` and sets
   the iframe src on demand (styles in input.css)."
  [agent-id]
  [:div {:id "seon-debug-overlay"}
   [:iframe {:id "seon-debug-frame"
             :title (str "debug view for agent " agent-id)
             :data-src (str "/agent/" agent-id "/debug")}]])

(defn- consumer-shell
  "The full consumer page for `/agent/<id>`: chat bubbles + input
   left, the expanded live tile right. Reuses the existing
   `POST /chat?agent=<id>` path via [[chat-bar-fragment]] and the
   shared page script (markdown bubbles, bottom-autoscroll,
   Cmd/Ctrl+Enter). Carries the debug overlay shell + its script
   ([[debug-overlay-fragment]] — Surface 3 without leaving the page)."
  [agent-id snap]
  (let [b (brand/info)]
    (str
      "<!DOCTYPE html>"
      (html/->string
        [:html {:lang "en" :data-theme (::brand/theme b)}
         (page-head (brand/page-title b (str "agent " agent-id)))
         [:body {:class "h-screen bg-base-950 text-text-50 font-sans antialiased flex flex-col"}
          [:div {:data-init (str "@get('/agent/" agent-id "/sse')")
                 :data-on:online__window (str "@get('/agent/" agent-id "/sse')")}]
          (consumer-header-fragment agent-id snap)
          [:div {:class "flex-1 grid h-0 min-h-0"
                 :style "grid-template-columns: minmax(0,1fr) minmax(0,1fr);"}
           [:div {:class "flex flex-col h-full overflow-hidden"}
            (chat-pane-fragment agent-id snap)
            (chat-bar-fragment agent-id)]
           (tile-pane-fragment agent-id snap)]
          (debug-overlay-fragment agent-id)
          [:script (html/raw page-script-js)]
          [:script (html/raw debug-overlay-script-js)]]]))))

(defn- stat-cell
  "One headline number in the mission-control strip. Ticks up live —
   the dash fragment re-morphs on every commit. With `href` the whole
   cell is a link (FACTS → /data: drill-down is one click away)."
  ([label value] (stat-cell label value nil))
  ([label value href]
   [(if href :a :div)
    (cond-> {:class (str "flex flex-col px-3 py-1.5 border border-base-800 "
                         "rounded bg-base-900/60 min-w-20"
                         (when href
                           " hover:border-amber-700/70 transition-colors"))}
      href (assoc :href href))
    [:span {:class "text-base leading-tight font-mono font-semibold text-amber-400 tabular-nums"}
     (str value)]
    [:span {:class "text-[10px] uppercase tracking-wider text-text-400"}
     label]]))

(defn- agent-grid-tile
  "One clickable agent tile on the index — the agent's own
   `render-agent-tile` surface wrapped in a link-card. No inline
   fallback: `render-agent-tile` always resolves to a wired value or
   `seon.render.live-tile/welcome` (the ONE default — live-tiles PRD
   §8.4); nil hiccup only for a nonexistent entity, which the grid
   (derived from live agent rows) never hands it."
  [db {:seon.agent/keys [id turn-count]}]
  (let [tile (:seon.render/hiccup
               (render/render-agent-tile {:seon.db/db db
                                          :seon.agent/id id}))]
    ;; flex-col + flex-1/shrink-0 split: the grid stretches the anchor,
    ;; and the tile's own `h-full` would otherwise consume 100% and
    ;; push the footer past the `overflow-hidden` edge (observed live
    ;; 2026-06-09: footer rendered at y=255 inside a 175px-tall card).
    ;; FIXED h-44: grid tiles are uniform-height cards regardless of
    ;; content — a long reply or tall agent hiccup CLIPS (paired with
    ;; the .seon-tile-compact max-height clamp in input.css), never
    ;; grows the cell (observed live 2026-06-11: unbounded vertical
    ;; growth).
    ;;
    ;; DIV + stretched overlay link, NOT a wrapping <a>: agent hiccup
    ;; legitimately contains [:a …] links, and an <a> inside an <a> is
    ;; invalid HTML — the parser SPLITS the outer anchor and ejects the
    ;; tile content out of the card (observed live 2026-06-11: the
    ;; verification tile blew the whole grid row apart). The absolute
    ;; inset-0 anchor keeps the entire card clickable.
    [:div {:class (str "relative flex flex-col h-44 border border-base-800 "
                       "rounded overflow-hidden hover:border-amber-700/70 "
                       "transition-colors animate-appear")}
     [:div {:class "flex-1 min-h-0 overflow-hidden"} tile]
     [:div {:class (str "shrink-0 flex items-center px-3 py-1 "
                        "border-t border-base-800 "
                        "bg-base-900/80 text-xs font-mono")}
      [:span {:class "text-text-500"} (str "turn " turn-count)]
      [:span {:class "ml-auto text-amber-500"} "open →"]]
     [:a {:href (str "/agent/" id)
          :aria-label (str "open agent " id)
          :class "absolute inset-0"}]]))

(defn- agents-dash-fragment
  "The whole mission-control surface — ONE morph target (`#agents-dash`)
   so the SSE listener re-renders strip + tiles + knowledge atomically.
   Derived 100% from the DB at render time.

   Header chips are USER-meaningful by default: AGENTS · TURNS
   (activity a demo viewer parses instantly) + FACTS (the /data
   default row count — one derivation, see `cluster-stats`; links to
   /data). Zero-count chips are hidden. `system?` (the `?system=1`
   param, same as /data) adds the machinery row: datoms · fns ·
   schemas · tests."
  [system?]
  (let [db   @db/*conn*
        rows (list-agents-data)
        ;; Lifecycle split (P3.5/#31): completed agents (stamped via
        ;; seon.agent/complete!) are HISTORY — grouped collapsed at the
        ;; bottom, never resumed, never triggered. Active = absent attr.
        active    (vec (remove :seon.agent/completed-at rows))
        completed (vec (filter :seon.agent/completed-at rows))
        {::keys [agent-count turn-count fact-count
                 datom-count fn-count schema-count test-count]}
        (cluster-stats db)
        findings (findings-data db)
        b        (brand/info db)]
    [:div {:id "agents-dash" :class "flex flex-col gap-4"}
     [:div {:class "flex items-center gap-4 flex-wrap"}
      [:div
       [:h1 {:class "text-lg font-semibold tracking-tight"}
        (brand/page-title b "cluster")]
       [:p {:class "text-text-400 text-xs mt-0.5 font-mono"}
        (::brand/tagline b)]
       [:div {:class "flex items-baseline gap-3 mt-1"}
        [:a {:href "/data"
             :class (str "text-xs font-mono "
                         "text-amber-500 hover:text-amber-300")}
         "⛁ data browser →"]
        ;; System-counts toggle — same `system` query param as /data.
        [:a {:href (if system? "/agents" "/agents?system=1")
             :class (str "text-xs font-mono "
                         (if system?
                           "text-amber-400 hover:text-amber-300"
                           "text-text-500 hover:text-text-300"))}
         (if system?
           "● system counts shown — hide"
           "○ system counts")]]]
      [:div {:class "flex gap-2 ml-auto items-stretch"}
       (when (or system? (pos? agent-count))
         (stat-cell "agents" agent-count))
       (when (or system? (pos? turn-count))
         (stat-cell "turns" turn-count))
       (when (or system? (pos? fact-count))
         (stat-cell "facts" fact-count "/data"))
       ;; Machinery — revealed by ?system=1 only (default header shows
       ;; user-meaningful counts, never fns/schemas/tests/datoms).
       (when system? (stat-cell "datoms"  datom-count))
       (when system? (stat-cell "fns"     fn-count))
       (when system? (stat-cell "schemas" schema-count))
       (when system? (stat-cell "tests"   test-count))
       ;; New-agent affordance — POSTs to /agents/new (the injected
       ;; seon.client/start-agent! boot path: trigger armed, live) and
       ;; navigates to the new /agent/<id> page on success. Boot takes
       ;; a few seconds (replay + substrate seed) — the button shows
       ;; progress; errors land in its own text, never swallowed.
       ;; NOTE: this fragment re-morphs on every commit (boot commits
       ;; plenty), so idiomorph may visually reset the label mid-boot;
       ;; the server-side in-flight guard (409) makes double-clicks
       ;; harmless.
       ;; Optional PURPOSE for the new agent (self-context spec
       ;; 2026-06-10): the words typed here seed its durable :purpose
       ;; section — "Your human created you for: <text>" — rendered
       ;; every turn. Left empty, the agent gets the
       ;; acquire-your-purpose placeholder instead.
       [:input
        {:id "seon-new-agent-purpose"
         :type "text"
         :placeholder "purpose (optional)…"
         :class (str "px-2 py-1.5 border border-base-700 rounded "
                     "bg-base-900 text-text-300 placeholder-text-500 "
                     "text-xs font-mono w-56")}]
       [:button
        {:id "seon-new-agent"
         :type "button"
         :class (str "px-3 py-1.5 border border-amber-700/60 rounded "
                     "bg-amber-950/40 hover:bg-amber-900/50 "
                     "text-amber-400 hover:text-amber-300 "
                     "text-xs font-mono cursor-pointer")
         :onclick (str "var b=this;b.disabled=true;b.textContent='booting…';"
                       "var p=document.getElementById('seon-new-agent-purpose');"
                       "var body=p&&p.value?"
                       "'purpose='+encodeURIComponent(p.value):'';"
                       "fetch('/agents/new',{method:'POST',"
                       "headers:{'Content-Type':"
                       "'application/x-www-form-urlencoded'},"
                       "body:body})"
                       ".then(function(r){"
                       "if(r.ok){r.text().then(function(id){"
                       "window.location='/agent/'+id.trim();});}"
                       "else{r.text().then(function(t){"
                       "b.disabled=false;"
                       "b.textContent='\\u2717 '+(t||('HTTP '+r.status));});}})"
                       ".catch(function(e){b.disabled=false;"
                       "b.textContent='\\u2717 '+e;});")}
        "+ new agent"]]]
     (if (seq active)
       (into [:div {:class "grid gap-2"
                    :style "grid-template-columns:repeat(auto-fill,minmax(300px,1fr));"}]
             (map #(agent-grid-tile db %))
             active)
       [:p {:class "text-text-500 italic text-xs"}
        "no active agents — boot one via the REPL and it will appear here live"])
     (when (seq findings)
       [:section
        [:h2 {:class (str "text-xs font-semibold text-amber-400 uppercase "
                          "tracking-wider mb-2 font-mono")}
         (str "◆ what this cluster has learned · "
              (findings-row-total findings) " rows · "
              (count findings) " kinds")]
        (knowledge-cards findings)])
     ;; Completed agents — queryable history, collapsed at the bottom.
     ;; Not resumed at boot, no trigger armed; un-complete is an explicit
     ;; retract of :seon.agent/completed-at (see seon.agent/complete!).
     (when (seq completed)
       [:details {:class "mt-2"}
        [:summary {:class (str "text-xs font-semibold text-text-400 uppercase "
                               "tracking-wider mb-2 font-mono cursor-pointer "
                               "hover:text-text-300 select-none")}
         (str "◇ completed agents · " (count completed))]
        (into [:div {:class "grid gap-2 opacity-60"
                     :style "grid-template-columns:repeat(auto-fill,minmax(300px,1fr));"}]
              (map #(agent-grid-tile db %))
              completed)])]))

(defn- agents-index-page
  "Full page for GET /agents. `system?` (the `?system=1` machinery-row
   toggle) rides the SSE URL too, so every commit re-morphs the exact
   header the tab is on."
  [system?]
  (let [b   (brand/info)
        sse (str "@get('/agents/sse" (when system? "?system=1") "')")]
    (str
      "<!DOCTYPE html>"
      (html/->string
        [:html {:lang "en" :data-theme (::brand/theme b)}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
          [:title (brand/page-title b "agents")]
          [:link {:rel "stylesheet" :href "/css/output.css"}]
          (brand-css-style)
          [:script {:type "module" :src "/js/datastar.js"}]]
         [:body {:class "min-h-screen bg-base-950 text-text-50 font-sans p-4"}
          [:div {:data-init sse
                 :data-on:online__window sse}]
          (agents-dash-fragment system?)]]))))

;; ============================================================
;; /data — the live data browser (task #28). Level 1: the kinds the
;; cluster stored, with row counts. Level 2 (?kind=…): that kind's
;; attrs + a paginated rows table (pull [*], keys sorted, rows by
;; :db/id). DEFAULT shows post-bootstrap data only — provenance via
;; `seon.db/bootstrap-row-ids`, the SAME per-row derivation
;; `store-inventory` splits by (one mechanism, no name-lists).
;; `?system=1` shows ALL rows (the demo move: the whole system is
;; data). View state is the query string — a signal, never stored.
;; Live: every commit re-morphs `#data-browser` over the same SSE
;; pattern the /agents dash rides (`::data` pseudo-agent key).
;; ============================================================

(def ^:private data-page-size 50)

(defn- query-param
  "Pull one query-string value out of `req.url`. nil when absent."
  [^js req k]
  (try
    (let [u (js/URL. (str "http://x" (.-url req)))]
      (.get (.-searchParams u) k))
    (catch :default _ nil)))

(defn- data-params
  "The /data browser's view params from the request URL."
  [^js req]
  (let [kind (some-> (query-param req "kind") not-empty keyword)
        page (let [p (js/parseInt (or (query-param req "page") "0") 10)]
               (if (js/Number.isNaN p) 0 (max 0 p)))]
    {::data-kind    kind
     ::data-page    page
     ::data-system? (= "1" (query-param req "system"))}))

(defn- data-qs
  "Query string (\"\" or \"?…\") for a /data view-params map."
  [{::keys [data-kind data-page data-system?]}]
  (let [qs (cond-> []
             data-kind        (conj (str "kind=" (js/encodeURIComponent
                                                   (subs (str data-kind) 1))))
             (pos? data-page) (conj (str "page=" data-page))
             data-system?     (conj "system=1"))]
    (if (seq qs) (str "?" (str/join "&" qs)) "")))

(defn- data-url [params] (str "/data" (data-qs params)))

(defn- data-attr?
  "Counted attrs: namespaced, not datahike's own (`db`/`db.*`)."
  [a]
  (let [n (namespace a)]
    (boolean (and n (not= n "db") (not (str/starts-with? n "db."))))))

(defn- data-scan
  "One pass powering the whole /data page:
   `{::kinds {kind → #{eids}} ::attr-counts {kind → {attr → n}}}`.
   Rows are post-bootstrap data rows by default (bootstrap rows and tx
   provenance entities excluded — `seon.db/bootstrap-row-ids` is the
   shared derivation); `system?` includes every row."
  [db system?]
  (let [bootstrap (if system? #{} (db/bootstrap-row-ids db))
        tx-ids    (if system?
                    #{}
                    (into #{} (map first)
                          (db/query {:seon.db/db db
                                     :seon.db/query
                                     '[:find ?tx :where [_ _ _ ?tx]]})))
        pairs     (db/query {:seon.db/db db
                             :seon.db/query '[:find ?e ?a :where [?e ?a _]]})]
    (reduce (fn [acc [e a]]
              (if (or (not (data-attr? a))
                      (contains? bootstrap e)
                      (contains? tx-ids e))
                acc
                (let [kind (keyword (namespace a))]
                  (-> acc
                      (update-in [::kinds kind] (fnil conj #{}) e)
                      (update-in [::attr-counts kind a] (fnil inc 0))))))
            {::kinds {} ::attr-counts {}}
            pairs)))

(defn- data-toggle-link
  "The system-data toggle — same view, `system` query param flipped
   (page reset: the row sets differ)."
  [{::keys [data-system?] :as params}]
  [:a {:href (data-url (assoc params ::data-page 0
                              ::data-system? (not data-system?)))
       :class (str "text-xs font-mono "
                   (if data-system?
                     "text-amber-400 hover:text-amber-300"
                     "text-text-400 hover:text-text-200"))}
   (if data-system?
     "● system data shown — hide"
     "○ show system data")])

(defn- data-kind-index
  "Level 1 — every stored kind with its row count, kind-name order."
  [kinds params]
  (if (empty? kinds)
    [:div {:class "text-text-500 italic text-xs font-mono p-2"}
     "no data rows yet — agents store rows here as they work"]
    (into [:div {:class "flex flex-col"}]
          (map (fn [[kind eids]]
                 [:a {:href (data-url (assoc params ::data-kind kind
                                             ::data-page 0))
                      :class (str "flex items-baseline gap-2 px-2 py-1 "
                                  "border-b border-base-800/60 "
                                  "hover:bg-base-900 text-xs font-mono")}
                  [:span {:class "text-amber-400"} (str "● " kind)]
                  [:span {:class "ml-auto text-text-400 tabular-nums"}
                   (str (count eids) (if (= 1 (count eids)) " row" " rows"))]]))
          (sort-by (comp str key) kinds))))

(defn- data-row-table
  "The page's rows as one table: columns = :db/id + the union of the
   page rows' attrs, keys sorted; cells pr-str'd, display-clipped (the
   stored values are complete)."
  [db eids]
  (let [rows (mapv #(db/pull db '[*] %) eids)
        cols (into [:db/id]
                   (->> rows
                        (mapcat keys)
                        (remove #(= :db/id %))
                        distinct
                        (sort-by str)))]
    [:div {:class "overflow-x-auto"}
     [:table {:class "text-xs font-mono w-full"}
      [:thead
       (into [:tr {:class "text-left text-text-400 border-b border-base-700"}]
             (map (fn [c] [:th {:class "pr-3 py-0.5 whitespace-nowrap font-normal"}
                           (pr-str c)]))
             cols)]
      (into [:tbody]
            (map (fn [row]
                   (into [:tr {:class "border-b border-base-800/60 align-top"}]
                         (map (fn [c]
                                [:td {:class "pr-3 py-0.5 text-text-100 break-all"}
                                 (if (contains? row c)
                                   (truncate-val (get row c) 120)
                                   "")]))
                         cols)))
            rows)]]))

(defn- data-kind-detail
  "Level 2 — one kind: its attrs with counts + the paginated rows
   table. Rows ordered by :db/id ascending (insertion order,
   deterministic for a given db value); explicit prev/next."
  [db kind eids attr-counts params]
  (let [sorted    (vec (sort eids))
        total     (count sorted)
        last-page (max 0 (quot (max 0 (dec total)) data-page-size))
        page      (min (::data-page params) last-page)
        start     (* page data-page-size)
        page-eids (subvec sorted (min start total)
                          (min (+ start data-page-size) total))]
    [:div {:class "flex flex-col gap-2"}
     [:div {:class "flex items-baseline gap-3 text-xs font-mono"}
      [:a {:href (data-url (assoc params ::data-kind nil ::data-page 0))
           :class "text-amber-500 hover:text-amber-300"} "← all kinds"]
      [:span {:class "text-amber-400"} (str "● " kind)]
      [:span {:class "text-text-400"}
       (str total (if (= 1 total) " row" " rows"))]]
     (when (seq attr-counts)
       (into [:div {:class "flex flex-wrap gap-x-4 gap-y-0.5 text-xs font-mono text-text-400"}]
             (map (fn [[a n]]
                    [:span (pr-str a) " "
                     [:span {:class "text-text-200 tabular-nums"} (str n)]]))
             (sort-by (comp str key) attr-counts)))
     (if (zero? total)
       [:div {:class "text-text-500 italic text-xs font-mono"}
        (str "no rows under " kind " in this view — retracted, or stored "
             "by the bootstrap (toggle system data)")]
       [:div {:class "flex flex-col gap-2"}
        (data-row-table db page-eids)
        [:div {:class "flex items-center gap-3 text-xs font-mono text-text-400"}
         (if (pos? page)
           [:a {:href (data-url (assoc params ::data-page (dec page)))
                :class "text-amber-500 hover:text-amber-300"} "‹ prev"]
           [:span {:class "text-text-600"} "‹ prev"])
         [:span {:class "tabular-nums"}
          (str "rows " (inc start) "–" (+ start (count page-eids))
               " of " total)]
         (if (< page last-page)
           [:a {:href (data-url (assoc params ::data-page (inc page)))
                :class "text-amber-500 hover:text-amber-300"} "next ›"]
           [:span {:class "text-text-600"} "next ›"])]])]))

(defn- data-browser-fragment
  "The whole /data surface — ONE morph target (`#data-browser`),
   derived 100% from the DB at render time."
  [{::keys [data-kind data-system?] :as params}]
  (let [db (deref db/*conn*)
        {::keys [kinds attr-counts]} (data-scan db data-system?)]
    [:div {:id "data-browser" :class "flex flex-col gap-3"}
     [:div {:class "flex items-baseline gap-4 flex-wrap"}
      [:h1 {:class "text-sm font-mono font-semibold text-text-100"}
       "data"]
      [:span {:class "text-xs font-mono text-text-500"}
       (if data-system?
         "every row in the store — the whole system is data"
         "what this cluster stored after bootstrap")]
      [:div {:class "ml-auto flex items-baseline gap-4"}
       (data-toggle-link params)
       [:a {:href "/agents"
            :class "text-xs font-mono text-amber-500 hover:text-amber-300"}
        "← all agents"]]]
     (if data-kind
       (data-kind-detail db data-kind
                         (get kinds data-kind #{})
                         (get attr-counts data-kind {})
                         params)
       (data-kind-index kinds params))]))

(defn- data-page
  "Full page for GET /data — same shell pattern as the /agents index;
   the SSE stream carries this view's params so every commit re-morphs
   the exact view the tab is on."
  [params]
  (let [b (brand/info)]
    (str
      "<!DOCTYPE html>"
      (html/->string
        [:html {:lang "en" :data-theme (::brand/theme b)}
         [:head
          [:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
          [:title (brand/page-title b "data")]
          [:link {:rel "stylesheet" :href "/css/output.css"}]
          (brand-css-style)
          [:script {:type "module" :src "/js/datastar.js"}]]
         [:body {:class "min-h-screen bg-base-950 text-text-50 font-sans p-4"}
          [:div {:data-init (str "@get('/data/sse" (data-qs params) "')")
                 :data-on:online__window
                 (str "@get('/data/sse" (data-qs params) "')")}]
          (data-browser-fragment params)]]))))

;; ============================================================
;; Routing — called from serve.cljs router via `route?` + `handle!`.
;; ============================================================

(defn- write-status! [^js res code mime body]
  (.writeHead res code #js {"Content-Type"  mime
                            "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res body))

(defn- parse-agent-id
  "Pull the `<id>` out of `/agent/<id>` and `/agent/<id>/sse`. Returns
   `[id rest-of-path]`."
  [path]
  (let [trimmed (subs path (count "/agent/"))]
    (if-let [slash (str/index-of trimmed "/")]
      [(subs trimmed 0 slash) (subs trimmed slash)]
      [trimmed ""])))

(defn- agent-not-found-page
  "Small full page for `/agent/<id>` when `<id>` has no entity in the
   cluster store — stale tabs and bookmarks land here after a pod
   restart onto a different store or a `bin/seon cluster reset`."
  [agent-id]
  (let [b (brand/info)]
    (str
      "<!DOCTYPE html>"
      (html/->string
        [:html {:lang "en" :data-theme (::brand/theme b)}
         [:head
          [:meta {:charset "utf-8"}]
          [:title (brand/page-title b (str "agent " agent-id " not found"))]
          [:link {:rel "stylesheet" :href "/css/output.css"}]
          (brand-css-style)]
         [:body {:class "h-screen bg-base-950 text-text-50 font-mono antialiased flex items-center justify-center"}
          [:div {:class "text-center"}
           [:div {:class "text-amber-400 text-sm mb-2"}
            (str "agent " agent-id " is not in this cluster store")]
           [:div {:class "text-text-500 text-xs mb-4"}
            "it belonged to a previous store — this tab is stale"]
           [:a {:href "/agents"
                :class "text-amber-500 hover:text-amber-300 text-xs underline"}
            "← all live agents"]]]]))))

(defn route?
  "True iff this `path` is an inspector route. Called from
   `seon.web.serve`'s GET branch to delegate."
  [path]
  (or (= path "/agents")
      (= path "/agents/sse")
      (= path "/data")
      (= path "/data/sse")
      (and (str/starts-with? path "/agent/")
           (> (count path) (count "/agent/")))))

(defn- patch-fragment
  "Wrap a hiccup fragment in a datastar-patch-elements SSE payload.
   Inlined here (rather than calling `seon.web.sse/patch-elements`) so
   this ns has no edge into `seon.web.serve` — keeping the require graph
   acyclic since `serve` now requires `inspector`."
  [hiccup]
  (let [html-str (html/->string hiccup)]
    (str "event: datastar-patch-elements\n"
         "data: elements " (str/replace html-str "\n" "\ndata: elements ")
         "\n\n")))

(defn- debug-payloads
  "The debug view's three morph fragments (header + both panes) as one
   SSE payload string."
  [agent-id snap]
  (str (patch-fragment (header-fragment agent-id snap))
       (patch-fragment (ai-pane-fragment agent-id snap))
       (patch-fragment (html-pane-fragment agent-id snap))))

(defn- consumer-payloads
  "The consumer view's three morph fragments (header + bubbles + tile)
   as one SSE payload string."
  [agent-id csnap]
  (str (patch-fragment (consumer-header-fragment agent-id csnap))
       (patch-fragment (chat-pane-fragment agent-id csnap))
       (patch-fragment (tile-pane-fragment agent-id csnap))))

(defn- push-agent!
  "Re-render and write the morph fragments to every connection
   watching `agent-id` — ONE tx fan-out serves BOTH views: each conn
   is tagged `:view` (:consumer | :debug) and gets its view's
   fragment set; each view's snapshot is computed at most once per
   push. Best-effort per-connection."
  [agent-id]
  (try
    (let [conns    (get @!sse-by-agent agent-id)
          views    (set (map #(get % :view :debug) conns))
          dbg      (when (contains? views :debug)
                     (debug-payloads agent-id (snapshot agent-id)))
          consumer (when (contains? views :consumer)
                     (consumer-payloads agent-id (consumer-snapshot agent-id)))]
      (doseq [{:keys [res] :as conn} conns]
        (let [payload (if (= :consumer (get conn :view :debug)) consumer dbg)]
          (try (some->> payload (.write res))
               (catch :default e
                 (log/error-console! "seon.web.inspector" "write failed" e)))))
      (log/info-console! "seon.web.inspector" "push"
                         {:agent agent-id :conns (count conns)
                          :views views}))
    (catch :default e
      (log/error-console! "seon.web.inspector" "push! threw" e))))

;; ============================================================
;; Coalescing — one trailing 100ms timer per agent-id. Bursts within
;; the window collapse into one render.
;; ============================================================

(defn- push-index!
  "Re-render and write the `#agents-dash` morph fragment to every
   connection watching the index. Each conn carries ITS header view
   (`:system?` — the machinery-row toggle); identical views render
   once. Best-effort per-connection."
  []
  (try
    (doseq [[system? cs] (group-by :system? (get @!sse-by-agent ::index))]
      (let [payload (patch-fragment (agents-dash-fragment system?))]
        (doseq [{:keys [res]} cs]
          (try (.write res payload)
               (catch :default e
                 (log/error-console! "seon.web.inspector"
                                     "index write failed" e))))))
    (catch :default e
      (log/error-console! "seon.web.inspector" "push-index! threw" e))))

(defn- push-data!
  "Re-render and write the `#data-browser` morph fragment to every
   connection watching /data. Each conn carries ITS view's params
   (kind/page/system) — identical param sets render once. Best-effort
   per-connection."
  []
  (try
    (doseq [[params cs] (group-by :params (get @!sse-by-agent ::data))]
      (let [payload (patch-fragment (data-browser-fragment params))]
        (doseq [{:keys [res]} cs]
          (try (.write res payload)
               (catch :default e
                 (log/error-console! "seon.web.inspector"
                                     "data write failed" e))))))
    (catch :default e
      (log/error-console! "seon.web.inspector" "push-data! threw" e))))

(defonce ^:private !pending (atom {}))

(defn- schedule-push! [agent-id]
  ;; First arrival in the window starts a 100ms trailing timer. Later
  ;; arrivals inside the window just keep `!pending` set; the timer's
  ;; callback drains it. `::index` / `::data` are the agents-index and
  ;; data-browser pseudo-agents.
  (let [was-pending? (get @!pending agent-id)]
    (swap! !pending assoc agent-id true)
    (when-not was-pending?
      (js/setTimeout
        (fn []
          (swap! !pending dissoc agent-id)
          (case agent-id
            ::index (push-index!)
            ::data  (push-data!)
            (push-agent! agent-id)))
        100))))

;; ============================================================
;; Tx-listener — figure out which agents this tx affects, schedule
;; pushes for the watching ones.
;; ============================================================

(defn- on-tx
  "Fan a tx out to the watching agents it affects. Scope rules:
   - substrate tx (no `:seon.db/agent-id`) → ALL watching agents;
   - `:seon.db/origin :substrate-seed` → ALL watching agents EVEN
     when agent-stamped (the boot-seed runs inside the booting agent's
     `with-agent` scope today, but `agent-view` shows seed tx to every
     agent — without this clause the OTHER agents' panes went stale on
     every boot);
   - otherwise → only the stamping agent.
   All datoms in one commit share the tx, so the first datom's tx eid
   is the commit's."
  [{:seon.db/keys [db datoms]}]
  (let [tx-eid   (some (fn [d] (:seon.db/tx d)) datoms)
        tx-ent   (when tx-eid (d/entity db tx-eid))
        scope-id (:seon.db/agent-id tx-ent)
        seed?    (= :substrate-seed (:seon.db/origin tx-ent))
        watching (watching-agents)
        targets  (if (or (nil? scope-id) seed?)
                   (disj watching ::index ::data)
                   (filter #(= % scope-id) watching))
        ;; The agents-index dashboard AND the /data browser watch
        ;; EVERY commit — their numbers/rows tick on any agent's work.
        targets  (cond-> (set targets)
                   (contains? watching ::index) (conj ::index)
                   (contains? watching ::data)  (conj ::data))]
    (doseq [aid targets]
      (schedule-push! aid))))

(defn install!
  "Install the inspector tx-listener. Idempotent — re-installing
   replaces the prior handler. Also kicks off the brand env sync
   (C-17): the SEON_BRAND_* env vars own the `:seon.web.brand` row,
   and install! is the web surface's boot hook (called after
   boot-seed! with the root conn bound). Fire-and-forget — sync!
   never rejects and logs its own failures."
  []
  (brand/sync!)
  (db/listen! {:seon.db/key     ::inspector
               :seon.db/handler on-tx}))

(defn uninstall!
  []
  (db/unlisten! {:seon.db/key ::inspector}))

(defn ^:dev/before-load before-reload []
  (try (uninstall!) (catch :default _ nil)))

(defn ^:dev/after-load after-reload []
  (try (install!) (catch :default _ nil)))

;; ============================================================
;; HTTP handlers — called from seon.web.serve when route? matched.
;; ============================================================

(defn- open-agent-sse!
  "Open a per-agent SSE stream for `view` (:consumer | :debug) —
   `/agent/<id>/sse` and `/agent/<id>/debug/sse` respectively. Both
   register in the same per-agent registry; `push-agent!` writes each
   conn its view's fragment set."
  [^js req ^js res agent-id view]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [conn {:id (random-uuid) :res res :view view :opened-at (js/Date.)}]
    (add-conn! agent-id conn)
    (log/info-console! "seon.web.inspector" "SSE OPEN"
                       {:agent agent-id
                        :view view
                        :conn-id (str (:id conn))
                        :total (count (get @!sse-by-agent agent-id))})
    (.on req "close"
         (fn []
           (remove-conn! agent-id (:id conn))
           (log/info-console! "seon.web.inspector" "SSE CLOSE"
                              {:agent agent-id :conn-id (str (:id conn))})))
    ;; Send an initial render immediately so the page populates without
    ;; waiting for the next tx.
    (try
      (.write res (if (= :consumer view)
                    (consumer-payloads agent-id (consumer-snapshot agent-id))
                    (debug-payloads agent-id (snapshot agent-id))))
      (catch :default e
        (log/error-console! "seon.web.inspector" "initial render failed" e)))))

(defn- open-index-sse!
  "SSE stream for the `/agents` mission-control page. Registered under
   the `::index` pseudo-agent key in the same registry; `on-tx` fans
   every commit to it."
  [^js req ^js res]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [system? (= "1" (query-param req "system"))
        conn    {:id (random-uuid) :res res :system? system?
                 :opened-at (js/Date.)}]
    (add-conn! ::index conn)
    (.on req "close" (fn [] (remove-conn! ::index (:id conn))))
    (try
      (.write res (patch-fragment (agents-dash-fragment system?)))
      (catch :default e
        (log/error-console! "seon.web.inspector" "index initial render failed" e)))))

(defn- open-data-sse!
  "SSE stream for the /data browser. The connection pins ITS view's
   query params (kind/page/system) — `push-data!` re-renders exactly
   that view on every commit. Registered under the `::data`
   pseudo-agent key in the same registry."
  [^js req ^js res]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [params (data-params req)
        conn   {:id (random-uuid) :res res :params params
                :opened-at (js/Date.)}]
    (add-conn! ::data conn)
    (.on req "close" (fn [] (remove-conn! ::data (:id conn))))
    (try
      (.write res (patch-fragment (data-browser-fragment params)))
      (catch :default e
        (log/error-console! "seon.web.inspector"
                            "data initial render failed" e)))))

(defn handle!
  "Inspector route dispatcher. Returns true if handled.

   The `/agent/<id>` family (live-tiles U2 route swap):
     \"\"            → consumer view (bubbles + expanded tile)
     \"/sse\"        → consumer SSE stream
     \"/debug\"      → debug two-pane inspector (today's view, verbatim)
     \"/debug/sse\"  → debug SSE stream"
  [^js req ^js res path]
  (cond
    (= path "/agents")
    (do (write-status! res 200 "text/html; charset=utf-8"
                       (agents-index-page (= "1" (query-param req "system"))))
        true)

    (= path "/agents/sse")
    (do (open-index-sse! req res) true)

    (= path "/data")
    (do (write-status! res 200 "text/html; charset=utf-8"
                       (data-page (data-params req)))
        true)

    (= path "/data/sse")
    (do (open-data-sse! req res) true)

    (str/starts-with? path "/agent/")
    (let [[agent-id rest-path] (parse-agent-id path)]
      (cond
        (str/blank? agent-id)
        (do (write-status! res 404 "text/plain; charset=utf-8" "missing agent id")
            true)

        ;; Dead-agent guard (§4 inspector SSE bug): ids from a prior
        ;; store (pre-flip pod runs, reset clusters) 404 cleanly here.
        ;; Ordering matters — the SSE branches must NOT register a
        ;; connection for a nonexistent agent (every later tx would
        ;; re-render it and throw), and the page branches must not 500
        ;; out of `snapshot`'s lookup-ref pull.
        (not (agent-exists? agent-id))
        (do (if (or (= rest-path "/sse") (= rest-path "/debug/sse"))
              (write-status! res 404 "text/plain; charset=utf-8"
                             (str "agent " agent-id " not found"))
              (write-status! res 404 "text/html; charset=utf-8"
                             (agent-not-found-page agent-id)))
            true)

        (= rest-path "/sse")
        (do (open-agent-sse! req res agent-id :consumer) true)

        (= rest-path "/debug/sse")
        (do (open-agent-sse! req res agent-id :debug) true)

        (= rest-path "/debug")
        (do (write-status! res 200 "text/html; charset=utf-8"
                           (inspector-shell agent-id (snapshot agent-id)))
            true)

        (or (= rest-path "") (= rest-path "/"))
        (do (write-status! res 200 "text/html; charset=utf-8"
                           (consumer-shell agent-id (consumer-snapshot agent-id)))
            true)

        :else
        (do (write-status! res 404 "text/plain; charset=utf-8"
                           (str "Not found: " path))
            true)))

    :else false))

