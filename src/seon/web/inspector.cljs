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
     GET /agents           — pick an agent
     GET /agent/<id>       — the inspector page for <id>
     GET /agent/<id>/sse   — SSE stream for that agent"
  (:require
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent-view :as agent-view]
    [seon.db :as db]
    [seon.inspect :as inspect]
    [seon.log :as log]
    [seon.render :as render]
    [seon.render.default :as default]
    [seon.ui.components :as comp]
    [seon.ui.html :as html]))

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

(defn- list-agents-data
  "Pull `[id state turn-count]` rows for every `:seon.agent/id` entity.
   Sorted by id asc."
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
                   {:seon.agent/id         id
                    :seon.agent/state      (or (:seon.agent/state ent) :unknown)
                    ;; :seon.agent/turn-count the ATTR was retired
                    ;; 2026-05-22 — derived from the session log now.
                    :seon.agent/turn-count (default/agent-turn-count ent)}))))))

(defn- entity-kind-label
  "Best-effort kind label for an entity with no resolved renderer: the
   most common keyword NAMESPACE among its attrs (`seon.eval`,
   `seon.sticky`, …). Returns a string, `\"entity\"` when nothing
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
   - sticky preamble entities (`:seon.sticky/position` present) — the
     `:seon.system-prompt` / `:seon.conventions` cards;
   - `:seon.schema` kind rows (`:seon.schema/key` present);
   - `:seon.fn` / `:seon.ns` / `:seon.test` cards whose CREATION tx
     carries `:seon.db/origin :substrate-seed` (the substrate index,
     seeded at boot — agent-AUTHORED fns/nses/tests have `:agent`
     origin and stay expanded).
   Everything else (messages, evals, agent-authored entities) is
   DYNAMIC and renders expanded."
  [db entity]
  (boolean
    (or (:seon.sticky/position entity)
        (:seon.schema/key entity)
        ;; Identity attrs per the registered schemas: :seon.fn/sym +
        ;; :seon.test/sym (strings), :seon.ns/name (keyword). The #24
        ;; version checked :seon.fn/name, which is not a registered
        ;; attr — the fn clause was dead code (masked because
        ;; non-sticky :seon.fn cards are subsumed out of the window).
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
      (:seon.system-prompt/id entity)
      (:seon.conventions/id entity)
      (:seon.sticky/id entity)
      ""))

(defn- entity-gist
  "One-line gist for a collapsed card's summary: first line of the
   doc/content, truncated to 80 chars."
  [entity]
  (let [s    (or (:seon.fn/doc entity)
                 (:seon.system-prompt/content entity)
                 (:seon.conventions/content entity)
                 (:seon.message/content entity)
                 "")
        line (or (first (str/split-lines s)) "")]
    (if (> (count line) 80) (str (subs line 0 80) "…") line)))

;; ============================================================
;; Turn grouping (unit #24 item 2) — derive each card's turn from the
;; `:seon.session/turns` → `:seon.turn/messages|evals` component refs.
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
   turns sorted by `:seon.turn/at` — same derivation as the agent's
   `turn N` prompt line. Entities not hanging off any turn (sticky
   preamble, schema rows, substrate index) are absent from the map and
   keep their tx-time position in the card list."
  [db]
  (let [turn-rows  (d/q '[:find ?s ?turn ?tat
                          :where
                          [?s :seon.session/turns ?turn]
                          [?turn :seon.turn/at ?tat]]
                        db)
        turn->info (into {}
                         (for [[_s rows] (group-by first turn-rows)
                               :let [sorted (sort-by (fn [[_ _ ^js at]] (.getTime at))
                                                     rows)]
                               [i [_ turn at]] (map-indexed vector sorted)]
                           [turn {::turn-n (inc i) ::turn-at at}]))
        child-rows (d/q '[:find ?turn ?c
                          :where
                          (or [?turn :seon.turn/messages ?c]
                              [?turn :seon.turn/evals ?c])]
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
                               (get turn-info (:db/id e))))))
                   vec)
        ;; The agent's OWN tile (unit 1.4) — rendered explicitly (the
        ;; agent entity is not part of `visible-entities`). Per-entity
        ;; `:seon.render/html` override wins; default is
        ;; `seon.render.default/view`.
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

(defn- header-fragment
  [agent-id {:keys [agent char-count token-est handler-count]}]
  (let [state (or (:seon.agent/state agent) :unknown)
        ;; Derived — the :seon.agent/turn-count attr was retired.
        turns (default/agent-turn-count agent)]
    [:header {:id (header-id agent-id)
              :class "flex items-center gap-3 p-2 border-b border-base-800 bg-base-900"}
     [:span {:class "text-xs font-mono text-text-200"} "agent " agent-id]
     (comp/status-dot state)
     [:span {:class "text-xs text-text-400"} (str "turn " turns)]
     [:span {:class "text-xs text-text-400"} (str handler-count " handlers")]
     [:span {:class "text-xs text-text-500 ml-auto"}
      (str "~" token-est " tokens · " char-count " chars")]
     [:a {:href "/agents"
          :class "text-xs text-amber-500 hover:text-amber-300"} "← all agents"]]))

(def ^:private open-ai-sections
  "Left-pane sections that render EXPANDED by default — the dynamic
   tail. Everything else (system, capabilities, catalogs, ns-context,
   warnings) is static bulk the user has already read; it collapses to
   a one-line summary. `:context` is the divergence-fallback pseudo-
   section carrying the whole joined text (see
   `seon.inspect/per-section-texts`) — must stay open."
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
   render expanded as before."
  [{::keys [hiccup static? kind name gist card-key]}]
  (let [wrap-class "border-l-2 border-amber-700/40 pl-2 py-1 mb-1"]
    (if static?
      [:details {:class wrap-class :data-seon-key card-key}
       [:summary {:class (str "cursor-pointer select-none text-xs font-mono "
                              "text-text-400 hover:text-text-200")}
        [:span {:class "font-semibold text-text-300"} kind]
        (when (seq name)
          [:span {:class "text-amber-500/80"} (str " " name)])
        (when (seq gist)
          [:span {:class "text-text-500"} (str " — " gist)])]
       [:div {:class "mt-0.5"} hiccup]]
      [:div {:class wrap-class} hiccup])))

(defn- turn-separator
  [turn-n turn-at]
  [:div {:class "flex items-center gap-2 my-1 text-xs font-mono text-text-500"}
   [:span {:class "flex-1 border-t border-base-800"}]
   [:span (str "turn " turn-n
               (let [t (hh-mm-ss turn-at)]
                 (when (seq t) (str " · " t))))]
   [:span {:class "flex-1 border-t border-base-800"}]])

(defn- cards-with-separators
  "Interleave turn-separator rows into the chronological card list: a
   separator renders before the first card of each turn. Cards without
   a turn keep their tx-time position and never break a turn group."
  [cards]
  (loop [out [] last-turn nil cards cards]
    (if-let [{::keys [turn-n turn-at] :as card} (first cards)]
      (let [new-turn? (and (some? turn-n) (not= turn-n last-turn))]
        (recur (cond-> out
                 new-turn? (conj (turn-separator turn-n turn-at))
                 true      (conj (card-block card)))
               (if new-turn? turn-n last-turn)
               (rest cards)))
      out)))

(defn- html-pane-fragment
  [agent-id {:keys [html-cards agent-tile elided]}]
  [:div {:id (html-pane-id agent-id)
         :class "flex flex-col h-full overflow-hidden"}
   [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
    ":seon.render/html  (rendered view)"]
   ;; `data-seon-scroll` arms the client-side bottom-autoscroll: pinned
   ;; to newest while the user is at/near the bottom; never yanks while
   ;; they read history. Stable id so idiomorph preserves the element
   ;; (and its scrollTop + JS-property flag) across SSE morphs.
   [:div {:id (str "inspect-cards-" agent-id)
          :data-seon-scroll "1"
          :class "flex-1 overflow-auto p-2 text-xs bg-base-950"}
    ;; The agent's OWN tile — always ABOVE the per-entity cards. It
    ;; lives inside this morphed fragment, so every SSE push
    ;; re-renders it: the agent repoints `:seon.render/html` on its
    ;; entity and the tile updates live.
    (when agent-tile
      [:div {:class (str "border border-amber-700/60 rounded p-1 mb-2 "
                         "bg-base-900/60")}
       agent-tile])
    ;; render-cap note — oldest entities beyond the bound are not
    ;; materialized at all (seon.render/render-cap); collapsed static
    ;; cards make them low-value, so say how many were skipped.
    (when (and elided (pos? elided))
      [:div {:class "text-text-500 italic text-xs font-mono px-2 py-0.5"}
       (str "… " elided " older " (if (= 1 elided) "entity" "entities")
            " elided")])
    (if (seq html-cards)
      (into [:div {:class "flex flex-col"}] (cards-with-separators html-cards))
      [:div {:class "text-text-500 italic p-2"}
       (str "nothing here yet — every entity this agent sees (messages, "
            "evals, fns, schemas) renders here as a card the moment it "
            "is created")])]])

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
          :onsubmit (str "event.preventDefault();"
                        "var f=this;var i=f.elements['text'];"
                        "var text=i.value;if(!text||!text.trim())return false;"
                        "fetch(f.action,{method:'POST',"
                        "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                        "body:'text='+encodeURIComponent(text)})"
                        ".then(function(r){if(r.ok){i.value='';i.focus();}});"
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
   [:button {:type "submit"
             :class (str "bg-amber-900/70 hover:bg-amber-800 text-amber-50 "
                         "px-3 py-1 rounded text-xs font-mono")}
    "send"]])

(defn- inspector-shell
  "The full page for `/agent/<id>`. The body contains the two-pane grid;
   the header is inside the morph zone so it updates too."
  [agent-id snap]
  (str
    "<!DOCTYPE html>"
    (html/->string
      [:html {:lang "en" :data-theme "phosphor"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
        [:title (str "seon · agent " agent-id)]
        [:link {:rel "stylesheet" :href "/css/output.css"}]
        ;; highlight.js — atom-one-dark theme. Loaded from CDN; no
        ;; server-side dep. After SSE morphs swap in new <code> blocks,
        ;; we re-run `hljs.highlightAll()` via a MutationObserver on
        ;; the html pane (see inline script below).
        [:link {:rel "stylesheet"
                :href "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/atom-one-dark.min.css"}]
        [:script {:src "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/highlight.min.js"}]
        ;; Clojure is NOT in the hljs core CDN build — without this
        ;; module every code block logged "Could not find the language
        ;; 'clojure'" and fell back to no-highlight (observed live
        ;; 2026-06-09: 100+ console warnings per page load).
        [:script {:src "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/languages/clojure.min.js"}]
        ;; marked.js — renders `:seon.eval/narration` markdown into HTML
        ;; in the right pane only (the AI pane keeps raw markdown — LLMs
        ;; read it fine as text). Loaded from CDN; no server-side dep.
        [:script {:src "https://cdn.jsdelivr.net/npm/marked/marked.min.js"}]
        ;; Inline override: bend a couple of atom-one-dark colors toward
        ;; the Phosphor Terminal palette (amber emphasis), plus minimal
        ;; markdown body styling for narration (Tailwind's `prose` plugin
        ;; isn't loaded via CDN).
        [:style (html/raw
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
                       ".markdown blockquote{border-left:2px solid #57534e;padding-left:0.5rem;color:#a8a29e;margin:0.2rem 0;}"))]
        [:script {:type "module" :src "/js/datastar.js"}]]
       [:body {:class "h-screen bg-base-950 text-text-50 font-sans antialiased flex flex-col"}
        [:div {:data-init (str "@get('/agent/" agent-id "/sse')")
               :data-on:online__window (str "@get('/agent/" agent-id "/sse')")}]
        (header-fragment agent-id snap)
        [:div {:class "flex-1 grid h-0 min-h-0"
               :style "grid-template-columns: 1fr 1fr;"}
         (ai-pane-fragment   agent-id snap)
         (html-pane-fragment agent-id snap)]
        (chat-bar-fragment agent-id)
        ;; Re-highlight on every Datastar morph. The patched HTML lands
        ;; via `datastar-patch-elements`; we listen for any added <code>
        ;; node anywhere in the document and call hljs.highlightElement
        ;; on it (cheaper than highlightAll on every mutation).
        [:script (html/raw
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
                        "if(f){e.preventDefault();f.requestSubmit();}}});"))]]])))

(defn- agents-index-page
  []
  (let [rows (list-agents-data)]
    (str
      "<!DOCTYPE html>"
      (html/->string
        [:html {:lang "en" :data-theme "phosphor"}
         [:head
          [:meta {:charset "utf-8"}]
          [:title "seon · agents"]
          [:link {:rel "stylesheet" :href "/css/output.css"}]]
         [:body {:class "min-h-screen bg-base-950 text-text-50 font-sans p-4"}
          [:h1 {:class "text-lg font-semibold mb-3"} "agents"]
          (if (seq rows)
            [:table {:class "w-full max-w-3xl text-xs font-mono"}
             [:thead
              [:tr {:class "text-text-400 text-left"}
               [:th {:class "py-1.5 px-2"} "id"]
               [:th {:class "py-1.5 px-2"} "state"]
               [:th {:class "py-1.5 px-2"} "turns"]
               [:th {:class "py-1.5 px-2"} ""]]]
             (into [:tbody]
                   (for [{:seon.agent/keys [id state turn-count]} rows]
                     [:tr {:class "border-t border-base-800 hover:bg-base-900"}
                      [:td {:class "py-1.5 px-2 text-text-100"} id]
                      [:td {:class "py-1.5 px-2"} (comp/status-dot state)]
                      [:td {:class "py-1.5 px-2 text-text-300"} (str turn-count)]
                      [:td {:class "py-1.5 px-2"}
                       [:a {:href (str "/agent/" id)
                            :class "text-amber-500 hover:text-amber-300"} "inspect →"]]]))]
            [:p {:class "text-text-500 italic"} "no agents yet — boot one via the REPL"])]]))))

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

(defn route?
  "True iff this `path` is an inspector route. Called from
   `seon.web.serve`'s GET branch to delegate."
  [path]
  (or (= path "/agents")
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

(defn- push-agent!
  "Re-render and write the three morph fragments (header + both panes)
   to every connection watching `agent-id`. Best-effort per-connection."
  [agent-id]
  (try
    (let [snap (snapshot agent-id)
          payloads (str (patch-fragment (header-fragment agent-id snap))
                        (patch-fragment (ai-pane-fragment agent-id snap))
                        (patch-fragment (html-pane-fragment agent-id snap)))
          conns (get @!sse-by-agent agent-id)]
      (doseq [{:keys [res]} conns]
        (try (.write res payloads)
             (catch :default e
               (log/error-console! "seon.web.inspector" "write failed" e))))
      (log/info-console! "seon.web.inspector" "push"
                         {:agent agent-id :conns (count conns)
                          :chars (:char-count snap)}))
    (catch :default e
      (log/error-console! "seon.web.inspector" "push! threw" e))))

;; ============================================================
;; Coalescing — one trailing 100ms timer per agent-id. Bursts within
;; the window collapse into one render.
;; ============================================================

(defonce ^:private !pending (atom {}))

(defn- schedule-push! [agent-id]
  ;; First arrival in the window starts a 100ms trailing timer. Later
  ;; arrivals inside the window just keep `!pending` set; the timer's
  ;; callback drains it.
  (let [was-pending? (get @!pending agent-id)]
    (swap! !pending assoc agent-id true)
    (when-not was-pending?
      (js/setTimeout
        (fn []
          (swap! !pending dissoc agent-id)
          (push-agent! agent-id))
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
                   watching
                   (filter #(= % scope-id) watching))]
    (doseq [aid targets]
      (schedule-push! aid))))

(defn install!
  "Install the inspector tx-listener. Idempotent — re-installing
   replaces the prior handler."
  []
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

(defn- open-agent-sse! [^js req ^js res agent-id]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [conn {:id (random-uuid) :res res :opened-at (js/Date.)}]
    (add-conn! agent-id conn)
    (log/info-console! "seon.web.inspector" "SSE OPEN"
                       {:agent agent-id
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
      (let [snap (snapshot agent-id)]
        (.write res (patch-fragment (header-fragment agent-id snap)))
        (.write res (patch-fragment (ai-pane-fragment agent-id snap)))
        (.write res (patch-fragment (html-pane-fragment agent-id snap))))
      (catch :default e
        (log/error-console! "seon.web.inspector" "initial render failed" e)))))

(defn handle!
  "Inspector route dispatcher. Returns true if handled."
  [^js req ^js res path]
  (cond
    (= path "/agents")
    (do (write-status! res 200 "text/html; charset=utf-8" (agents-index-page))
        true)

    (str/starts-with? path "/agent/")
    (let [[agent-id rest-path] (parse-agent-id path)]
      (cond
        (str/blank? agent-id)
        (do (write-status! res 404 "text/plain; charset=utf-8" "missing agent id")
            true)

        (= rest-path "/sse")
        (do (open-agent-sse! req res agent-id) true)

        (or (= rest-path "") (= rest-path "/"))
        (do (write-status! res 200 "text/html; charset=utf-8"
                           (inspector-shell agent-id (snapshot agent-id)))
            true)

        :else
        (do (write-status! res 404 "text/plain; charset=utf-8"
                           (str "Not found: " path))
            true)))

    :else false))

