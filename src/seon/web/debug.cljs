(ns seon.web.debug
  "Operator dev tools — the two surfaces that have NO world-page equivalent:

     GET /agent/<id>/debug      — the two-pane debug inspector: the EXACT
                                  bytes the LLM receives (left) beside the
                                  rendered context-section html twins (right),
                                  plus the per-section token + cache-line
                                  audit bar along the bottom.
     GET /agent/<id>/debug/sse  — SSE stream that re-morphs the debug panes.
     GET /data                  — the live datom browser: the kinds the
                                  cluster stored with row counts, drill-down
                                  to a kind's attrs + paginated rows.
     GET /data/sse              — SSE stream that re-morphs `#data-browser`.

   These are the operator/developer introspection surfaces (raw prompt,
   cache-line audit, stored datoms) — distinct from the agent-WORLD renderers
   (`seon.web.datastar`), which own `/`, `/world`, `/agent/<id>`. The routes
   are wired into `seon.web.router`'s static supplement as plain reitit routes.

   Both per-agent panes derive from `inspect/ctx-preview` (soul system block
   via `seon.ai/effective-system-prompt` + the ONE composer
   `seon.agent.ctx/assemble-context`), so they share their sources and cannot
   structurally diverge:
     - LEFT  `:seon.render/ai`   — the EXACT bytes the LLM would receive on its
       next render: the live SOUL system block FIRST, then the context
       sections as per-section texts (`:seon.render/section-texts`).
     - RIGHT `:seon.render/html` — the CONTEXT sections' html twins
       (`:seon.render/section-html`), each rendered as one right-pane card in
       render order. (The soul is the system MESSAGE, not a context section, so
       it has no html twin — the rendered view is the context surface; the full
       prompt text is the left pane.)

   Reactive: a single tx-listener subscribes to every commit. Per-agent tx fan
   out to that agent's open debug streams; core/seed tx fan out to ALL watching
   agents; the `::data` pseudo-agent watches EVERY commit so its row counts tick
   live. Pushes are coalesced per-agent on a 100ms trailing timer."
  (:require
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.ai.tokens :as tokens]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.usage :as ctx-usage]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.agent.inspect :as inspect]
    [seon.log :as log]
    [seon.render :as render]
    [seon.ui.components :as comp]
    [seon.ui.header :as header]
    [seon.ui.html :as html]
    [seon.web.brand :as brand]))

;; ============================================================
;; SSE connection registry — per-agent (+ the `::data` pseudo-agent).
;; Distinct from the main /sse registry in seon.web.serve so the
;; existing broadcast keeps behaving unchanged.
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
  "Set of agent-ids (incl. the `::data` pseudo-agent) that currently have
   at least one open SSE stream."
  []
  (->> @!sse-by-agent
       (filter (fn [[_ conns]] (seq conns)))
       (map first)
       set))

;; ============================================================
;; Data sources — one snapshot per render.
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

(defn- truncate-val [v n]
  (let [s (pr-str v)]
    (if (> (count s) n) (str (subs s 0 n) " …") s)))

;; ============================================================
;; Context bar — the per-section token + cache-line audit instrument.
;; DISPLAY-ONLY, all DERIVED at render time: per-section estimated tokens
;; (seon.ai.tokens/estimate over the SAME section-texts the LLM prompt is
;; built from — one render, two consumers), the STRUCTURAL cache breakpoint
;; (end of the byte-stable prefix = after :namespaces), and the LIVE cached
;; extent (exact, off the last turn's persisted :seon.agent.turn/llm-usage).
;; The divergence between the two markers is the point.
;; ============================================================

(def ^:private stable-section-names
  "Section names that form the byte-stable cacheable PREFIX (the composer
   caches sections with `:seon.agent.ctx/priority` ≤ `seon.agent.ctx/cache-breakpoint`
   = soul → :system → :namespaces). The structural cache-line falls right
   after the LAST of these in render order. Mirrors `core-default-ctx`."
  #{:soul-system :system :namespaces})

(defn- stable-section?
  "True when section `nm` belongs to the byte-stable cacheable prefix —
   one of [[stable-section-names]], OR any per-namespace split entry
   (`:namespaces/seon.db`), since the whole :namespaces body is part of
   the prefix."
  [nm]
  (or (contains? stable-section-names nm)
      (= "namespaces" (namespace nm))))

(defn- ns-section-name
  "Display name for one namespace block of the :namespaces section:
   `; namespace seon.db` → `:namespaces/seon.db`. nil for a chunk
   with no namespace header (the section's preamble line)."
  [block]
  (when-let [m (re-find #"(?m)^; namespace (\S+)" block)]
    (keyword "namespaces" (second m))))

(defn- expand-namespaces-section
  "Display-only: explode the single `:namespaces` section into ONE entry
   per namespace block, so the debug view lists each ns separately instead
   of one giant `:namespaces` blob. The LLM prompt is untouched — this only
   reshapes the per-section breakdown both panes + the context bar consume.
   Every other section passes through unchanged, in render order."
  [section-texts]
  (into []
        (mapcat
          (fn [{nm :seon.agent.ctx/name txt :seon.render/text :as sec}]
            (if (= nm :namespaces)
              (->> (str/split (or txt "") #"(?m)(?=^; namespace )")
                   (remove str/blank?)
                   (map (fn [chunk]
                          {:seon.agent.ctx/name    (or (ns-section-name chunk) :namespaces)
                           :seon.render/text chunk})))
              [sec])))
        section-texts))

(defn- context-bar-data
  "Derive the context-bar model for `agent-id` from the SAME per-section
   texts the LLM prompt is built from (`section-texts`):

     {::segments [{::name ::tokens ::stable?} …]  ; render order
      ::total-tokens N
      ::cache-line-tokens N        ; structural breakpoint (end of prefix)
      ::live-cached-tokens N|nil   ; exact, from last turn's usage
      ::provider-shape kw|nil}

   `::live-cached-tokens` is nil when no turn has run / usage is absent."
  [agent-id section-texts]
  (let [segs   (mapv (fn [{nm :seon.agent.ctx/name txt :seon.render/text}]
                       (let [t (or txt "")]
                         {::name    nm
                          ::tokens  (tokens/estimate t)
                          ::stable? (stable-section? nm)}))
                     section-texts)
        total  (reduce + 0 (map ::tokens segs))
        ;; Structural cache-line = cumulative tokens of every section up to
        ;; AND INCLUDING the last stable one, in render order. (Sections
        ;; sort static→volatile, so the stable prefix is contiguous.)
        last-stable-idx (->> (map-indexed vector segs)
                             (filter (comp ::stable? second))
                             (map first)
                             (reduce max -1))
        cache-line (->> segs
                        (take (inc last-stable-idx))
                        (map ::tokens)
                        (reduce + 0))
        usage  (try
                 (ctx-usage/extract
                   (:seon.agent.turn/llm-usage
                     (ctx/current-turn {:seon.agent/id agent-id})))
                 (catch :default _ nil))]
    {::segments           segs
     ::total-tokens       total
     ::cache-line-tokens  cache-line
     ::live-cached-tokens (some-> usage :seon.agent.ctx.usage/cached)
     ::provider-shape     (some-> usage :seon.agent.ctx.usage/provider-shape)}))

(defn- snapshot
  "Compute one render snapshot for `agent-id`:
     {:ai-text <string>
      :section-texts [{:seon.agent.ctx/name … :seon.render/text …} ...]
      :token-est <int>
      :html-cards [<card-map> ...]  ; one per SECTION html twin, in render order
      :agent <pulled entity or nil>}
   Each card-map: `{::hiccup ::kind ::card-key}` — the section's html
   twin, its name, and a stable card-key for idiomorph (the right pane
   mirrors the left's section set)."
  [agent-id]
  (let [db @db/*conn*
        {:seon.render/keys [text token-estimate section-texts section-html]}
        (inspect/ctx-preview {:seon.agent/id agent-id})
        ;; Display-only: the single 49k-token :namespaces blob dwarfs every
        ;; other section into an unreadable sliver — split it into one entry
        ;; per ns so the breakdown (both panes + the bottom bar) shows the
        ;; real per-ns distribution. The LLM prompt + token-estimate are
        ;; unchanged (they derive from `text`).
        section-texts (expand-namespaces-section (or section-texts []))
        ;; Each section twin → one right-pane card, in render order. The
        ;; card-key is the section name so idiomorph preserves the node
        ;; across SSE morphs.
        cards (->> section-html
                   (mapv (fn [{nm :seon.agent.ctx/name h :seon.render/hiccup}]
                           {::hiccup   h
                            ::kind     (str nm)
                            ::card-key (str "section-" (clojure.core/name nm))})))
        turn-durs []
        ;; The agent's OWN tile — rendered explicitly (the agent entity is
        ;; not a context section, so it has no section twin). Wired
        ;; `:seon.render.live-tile/content` wins; default is the core welcome.
        tile  (:seon.render/hiccup
                (render/render-agent-tile {:seon.db/db db
                                           :seon.agent/id agent-id}))
        ;; Just the ONE agent entity. State is DERIVED (no stored enum), so
        ;; the snapshot carries the projected state + turn count the header
        ;; fragments render.
        agent (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id agent-id]})
        state (derive/derive-state db agent-id)
        turn-count (derive/agent-turn-count db agent-id)]
    {:ai-text   (or text "")
     :agent-state state
     :agent-turn-count turn-count
     :section-texts (or section-texts [])
     :token-est  (or token-estimate 0)
     :context-bar (context-bar-data agent-id (or section-texts []))
     :html-cards cards
     :turn-durs  turn-durs
     ;; No render-cap elision on the right pane — the section twins ARE the
     ;; prompt sections (the transcript twin self-bounds via the transcript
     ;; block's :seon.agent.ctx.transcript/tiers). Kept at 0 so the pane's
     ;; existing elided-note branch is a no-op.
     :elided    0
     :agent-tile tile
     :agent      agent}))

;; ============================================================
;; Page rendering — full page (for initial GET) AND morph fragments
;; (for SSE pushes). The two panes use stable ids `#inspect-ai-<id>` and
;; `#inspect-html-<id>` so datastar morphs by id.
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
  [agent-id {:keys [agent-state agent-turn-count token-est turn-durs]}]
  (let [state (or agent-state :unknown)
        turns (or agent-turn-count 0)]
    [:header {:id (header-id agent-id)
              :class "flex items-center gap-3 p-2 border-b border-base-800 bg-base-900"}
     [:span {:class "text-xs font-mono text-text-200"} "agent " agent-id]
     (if (= :running state)
       [:span {:class "inline-flex items-center gap-1.5 text-xs font-mono text-amber-400"}
        [:span {:class "w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse"}]
        (str "thinking — turn " (inc turns))]
       (comp/status-dot state))
     [:span {:class "text-xs text-text-400"} (str "turn " turns)]
     (activity-sparkline turn-durs)
     [:span {:class "text-xs text-text-500 ml-auto"}
      (str "~" token-est " tokens")]
     [:a {:href (str "/agent/" agent-id)
          :class "text-xs text-amber-500 hover:text-amber-300"} "← agent"]
     [:a {:href "/world"
          :class "text-xs text-amber-500 hover:text-amber-300"} "← all agents"]]))

(def ^:private open-ai-sections
  "Left-pane sections that render EXPANDED by default — the dynamic
   tail. Everything else (system, capabilities, catalogs, ns-context,
   warnings) is static bulk the user has already read; it collapses to
   a one-line summary. `:context` is the divergence-fallback pseudo-
   section carrying the whole joined text; `:soul-system` is the live
   SOUL system message (block 1 of every LLM call) — kept open so the
   debug view shows the soul the agent actually receives."
  #{:soul-system :transcript :prompt :context})

(defn- fmt-int
  "`3214` → `\"3,214\"` — comma-grouped integer for summaries."
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
  [{sec-name :seon.agent.ctx/name sec-text :seon.render/text}]
  (let [open? (contains? open-ai-sections sec-name)]
    [:details (cond-> {:class "mb-1"
                       :data-seon-key (str "ai-sec-" (name sec-name))}
                open? (assoc :open true))
     [:summary {:class (str "cursor-pointer select-none text-xs font-mono "
                            "text-text-400 hover:text-text-200 py-0.5")}
      (str (name sec-name) " (" (fmt-int (tokens/estimate sec-text)) " tokens)")]
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

(defn- section-card
  "Render one SECTION HTML TWIN as a right-pane card: a section-name label
   + the section's hiccup. Stable `:id` (`section-<name>`) so idiomorph
   PRESERVES the node across SSE morphs and only genuinely-new sections
   animate in. The hiccup is the section's `:seon.render/html` twin (or a
   banner if it threw — the composer's guard never hands us nil)."
  [{::keys [hiccup kind card-key]}]
  [:div {:id card-key
         :class (str "border-l-2 border-amber-700/40 pl-2 py-1 "
                     "animate-appear")}
   [:div {:class "text-xs font-mono font-semibold text-text-400 mb-0.5"}
    kind]
   [:div {:class "mt-0.5"} hiccup]])

(defn- thinking-bubble
  "Placeholder bubble pinned under the newest card while the agent is
   `:active` — it 'resolves' into the real cards on the next morph.
   No stable id ON PURPOSE: every morph treats it as a fresh node, so
   it re-animates while real cards stay put."
  [turns]
  [:div {:class (str "flex items-center gap-2 py-1.5 px-2 mb-1 rounded "
                     "border border-amber-700/40 bg-amber-950/30 "
                     "text-xs font-mono text-amber-400 animate-appear")}
   [:span {:class "w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse"}]
   (str "thinking — turn " (inc turns) " …")])

(defn- html-pane-fragment
  [agent-id {:keys [html-cards agent-tile elided agent-state agent-turn-count]}]
  (let [running? (= :running agent-state)]
    [:div {:id (html-pane-id agent-id)
           :class "flex flex-col h-full overflow-hidden"}
     [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
      ":seon.render/html  (rendered view)"]
     [:div {:id (str "inspect-cards-" agent-id)
            :data-seon-scroll "1"
            :class "seon-agent-content flex-1 overflow-auto p-2 text-xs bg-base-950"}
      (when agent-tile
        [:div {:class (str "border border-amber-700/60 rounded p-1 mb-2 "
                           "bg-base-900/60")}
         agent-tile])
      (when (and elided (pos? elided))
        [:div {:class "text-text-500 italic text-xs font-mono px-2 py-0.5"}
         (str "… " elided " older " (if (= 1 elided) "entity" "entities")
              " elided")])
      (if (seq html-cards)
        (into [:div {:class "flex flex-col gap-2"}]
              (map section-card html-cards))
        [:div {:class "text-text-500 italic p-2"}
         "ask this agent something ↓ — every section the agent sees renders its html twin here live"])
      (when running?
        (thinking-bubble (or agent-turn-count 0)))]]))

;; ============================================================
;; The context bar — per-section token + cache-line viz along the bottom
;; of the debug inspector. A horizontal stacked bar: one segment per
;; rendered section (width ∝ estimated tokens), the STRUCTURAL cache
;; breakpoint as one marker, the LIVE cached extent as a second marker.
;; Display-only — re-rendered every SSE push (stable id so idiomorph
;; preserves it).
;; ============================================================

(defn- context-bar-id [agent-id] (str "inspect-ctxbar-" agent-id))

(defn- bar-segment
  "One section segment of the stacked bar. Width is a flex-grow weight
   (∝ tokens). Stable-prefix sections read amber (the cached prefix);
   volatile-tail sections read cooler. The section name + token count
   show inline when the segment is wide enough; always in the title."
  [{::keys [name tokens stable?]} total]
  (let [pct (if (pos? total) (* 100.0 (/ tokens total)) 0)
        wide? (>= pct 6)]
    [:div {:class (str "relative h-full flex items-center justify-center "
                       "overflow-hidden border-r border-base-950 "
                       (if stable?
                         "bg-amber-800/60 hover:bg-amber-700/70 "
                         "bg-base-700/70 hover:bg-base-600/80 "))
           :style (str "flex: " (max 0.01 tokens) " 1 0; min-width: 2px;")
           :title (str (clojure.core/name name) " · ~" (fmt-int tokens) " tok · "
                       (.toFixed pct 1) "%"
                       (when stable? " · cached prefix"))}
     (when wide?
       [:span {:class (str "px-1 truncate text-[10px] font-mono "
                           (if stable? "text-amber-50" "text-text-200"))}
        (str (clojure.core/name name) " " tokens)])]))

(defn- cache-marker
  "An absolutely-positioned vertical marker over the bar at `pct` of the
   total width, labeled above. `kind` = :structural (dashed amber, the
   composer breakpoint) or :live (solid green, the provider's actual
   cached extent)."
  [pct kind label]
  (let [structural? (= kind :structural)]
    [:div {:class "absolute top-0 bottom-0 pointer-events-none z-10"
           :style (str "left: " (min 100.0 (max 0.0 pct)) "%;")}
     [:div {:class (str "absolute top-0 bottom-0 w-px "
                        (if structural?
                          "bg-amber-300"
                          "bg-emerald-400"))
            :style (when structural? "background-image:repeating-linear-gradient(to bottom,#fcd34d 0 3px,transparent 3px 6px);")}]
     [:div {:class (str "absolute -top-4 whitespace-nowrap text-[10px] "
                        "font-mono px-1 rounded "
                        (if structural?
                          "text-amber-200 bg-base-900/80 -translate-x-1/2 left-0"
                          "text-emerald-300 bg-base-900/80 -translate-x-1/2 left-0"))}
      label]]))

(defn- context-bar-fragment
  "The bottom context bar for the debug inspector. Stacked per-section
   token segments + structural cache-line + live cached overlay. Stable
   `:id` so SSE morphs preserve it."
  [agent-id {:keys [context-bar]}]
  (let [{::keys [segments total-tokens cache-line-tokens
                 live-cached-tokens provider-shape]} context-bar
        struct-pct (if (pos? total-tokens)
                     (* 100.0 (/ cache-line-tokens total-tokens)) 0)
        live-pct   (when (and live-cached-tokens (pos? total-tokens))
                     (* 100.0 (/ live-cached-tokens total-tokens)))]
    [:div {:id (context-bar-id agent-id)
           :class (str "shrink-0 border-t border-base-800 bg-base-900 "
                       "px-2 pt-5 pb-1.5")}
     [:div {:class "flex items-center gap-3 mb-1 text-[10px] font-mono text-text-400"}
      [:span {:class "text-text-200"} "context"]
      [:span {:class "text-amber-400"}
       (str "~" total-tokens " tok total")]
      [:span {:class "text-amber-200"}
       (str "cache-line ~" cache-line-tokens " tok (after :namespaces)")]
      (if live-cached-tokens
        [:span {:class "text-emerald-300"}
         (str "live cached " live-cached-tokens " tok"
              (when provider-shape (str " · " (clojure.core/name provider-shape))))]
        [:span {:class "text-text-600 italic"} "no live usage yet"])
      [:span {:class "ml-auto text-text-600"} "stable prefix amber · volatile tail grey"]]
     [:div {:class "relative h-6 w-full flex rounded-sm overflow-visible bg-base-950 border border-base-800"}
      (if (seq segments)
        (into [:div {:class "flex w-full h-full rounded-sm overflow-hidden"}]
              (map #(bar-segment % total-tokens) segments))
        [:div {:class "flex items-center px-2 text-[10px] font-mono text-text-600"}
         "no context yet"])
      (cache-marker struct-pct :structural
                    (str "▏ cache-line " cache-line-tokens))
      (when live-pct
        (cache-marker live-pct :live
                      (str "live " live-cached-tokens " ▏")))]]))

(defn- chat-bar-fragment
  "Sticky bottom bar spanning both panes. Submits as a regular
   `application/x-www-form-urlencoded` POST to `/chat?agent=<id>`. We
   intercept the submit in inline JS, fetch() the form data, clear the
   input on success, and let the existing SSE registry push the
   re-rendered panes once the user message lands in the DB."
  [agent-id]
  [:form {:id "seon-chat-form"
          :action (str "/chat?agent=" agent-id)
          :method "post"
          :class (str "shrink-0 flex items-center gap-2 "
                      "border-t border-base-800 bg-base-900 px-2 py-1.5")
          :onsubmit (str "event.preventDefault();"
                        "var f=this;var i=f.elements['text'];"
                        "var e=document.getElementById('seon-chat-err');"
                        "var text=i.value;if(!text||!text.trim())return false;"
                        "fetch(f.action,{method:'POST',"
                        "headers:{'Content-Type':'application/x-www-form-urlencoded'},"
                        "body:'text='+encodeURIComponent(text)})"
                        ".then(function(r){if(r.ok){i.value='';i.focus();"
                        "if(e)e.textContent='';"
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
  "Inline style for the debug shell: bend a couple of atom-one-dark colors
   toward the Phosphor Terminal palette (amber emphasis) + minimal markdown
   body styling for narration (Tailwind's `prose` plugin isn't loaded)."
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
       ".markdown blockquote{border-left:2px solid #57534e;padding-left:0.5rem;color:#a8a29e;margin:0.2rem 0;}"))

(defn- brand-css-style
  "The downstream brand stylesheet as an inline [:style], or nil when
   SEON_BRAND_CSS is unset/unreadable. Rendered AFTER the output.css link
   in every page head so its token overrides win the cascade."
  []
  (when-let [css (brand/css-text)]
    [:style (html/raw css)]))

(defn- page-head
  "Shared <head> for the debug shell: output.css (+ the optional downstream
   brand stylesheet), highlight.js (+ the Clojure language module — NOT in
   the core CDN build), marked.js for `data-markdown` bodies, the Phosphor
   style overrides, and the Datastar module."
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
  "Inline page JS for the debug shell — the re-highlight / markdown /
   details-open-state / bottom-autoscroll passes plus the Cmd/Ctrl+Enter
   chat submit. Every pass is a no-op on nodes whose state already matches,
   so the MutationObserver settles instead of looping."
  (str "function seonHighlightAll(){"
       "if(!window.hljs)return;"
       "document.querySelectorAll('pre code.language-clojure').forEach(function(el){"
       "var t=el.textContent;"
       "if(el.__hlSrc===t)return;"
       "el.removeAttribute('data-highlighted');"
       "window.hljs.highlightElement(el);"
       "el.__hlSrc=t;});}"
       "function seonMarkdownAll(){"
       "if(!window.marked)return;"
       "document.querySelectorAll('[data-markdown]').forEach(function(el){"
       "var src=el.getAttribute('data-markdown')||'';"
       "if(el.__mdSrc===src&&el.childNodes.length>0)return;"
       "var esc=src.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');"
       "el.innerHTML=window.marked.parse(esc);"
       "el.__mdSrc=src;});}"
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
       "new MutationObserver(function(muts){"
       "var any=false;for(var i=0;i<muts.length;i++){"
       "if(muts[i].type==='childList'){any=true;break;}}"
       "if(any){seonHighlightAll();seonMarkdownAll();"
       "seonReapplyOpen();seonAutoscroll();}"
       "}).observe(document.body,{subtree:true,childList:true});"
       "document.addEventListener('keydown',function(e){"
       "if((e.metaKey||e.ctrlKey)&&e.key==='Enter'){"
       "var f=document.getElementById('seon-chat-form');"
       "if(f){e.preventDefault();f.requestSubmit();}}});"))

(defn- debug-shell
  "The full page for `/agent/<id>/debug` — the two-pane debug inspector
   (raw context sections left, rendered cards right) + the context-bar
   audit instrument + a chat bar. The header is inside the morph zone so
   it updates too; Esc returns to the agent's world page."
  [agent-id snap]
  (let [b (brand/info)]
    (str
      "<!DOCTYPE html>"
      (html/->string
        [:html {:lang "en" :data-theme (::brand/theme b)}
         (page-head (brand/page-title b (str "agent " agent-id " · debug")))
         [:body {:class "h-screen bg-base-950 text-text-50 font-sans antialiased flex flex-col"}
          ;; The persistent global status bar (fixed top) — a request-time
          ;; snapshot here (this page is server-rendered, not morphed). The
          ;; shrink-0 spacer reserves room under the fixed bar.
          (header/system-header (deref db/*conn*))
          [:div {:class "shrink-0" :style "height:2.25rem"}]
          [:div {:data-init (str "@get('/agent/" agent-id "/debug/sse')")
                 :data-on:online__window (str "@get('/agent/" agent-id "/debug/sse')")}]
          (header-fragment agent-id snap)
          [:div {:class "flex-1 grid h-0 min-h-0"
                 :style "grid-template-columns: 1fr 1fr;"}
           (ai-pane-fragment   agent-id snap)
           (html-pane-fragment agent-id snap)]
          (context-bar-fragment agent-id snap)
          (chat-bar-fragment agent-id)
          [:div {:class (str "shrink-0 px-2 py-0.5 text-right text-[10px] "
                             "font-mono text-text-500 bg-base-900 "
                             "border-t border-base-800")}
           "esc → agent"]
          [:script (html/raw page-script-js)]
          [:script (html/raw
                     (str "document.addEventListener('keydown',function(e){"
                          "if(e.key==='Escape'){window.location='/agent/"
                          agent-id "';}});"))]]]))))

;; ============================================================
;; /data — the live data browser. Level 1: the kinds the cluster stored,
;; with row counts. Level 2 (?kind=…): that kind's attrs + a paginated
;; rows table (pull [*], keys sorted, rows by :db/id). DEFAULT shows
;; post-bootstrap data only — provenance via `seon.db/bootstrap-row-ids`.
;; `?system=1` shows ALL rows. View state is the query string — a signal,
;; never stored. Live: every commit re-morphs `#data-browser` (`::data`
;; pseudo-agent key).
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
  (let [ns-kw (some-> (query-param req "ns") not-empty keyword)
        page (let [p (js/parseInt (or (query-param req "page") "0") 10)]
               (if (js/Number.isNaN p) 0 (max 0 p)))]
    {::data-ns      ns-kw
     ::data-page    page
     ::data-system? (= "1" (query-param req "system"))}))

(defn- data-qs
  "Query string (\"\" or \"?…\") for a /data view-params map."
  [{::keys [data-ns data-page data-system?]}]
  (let [qs (cond-> []
             data-ns          (conj (str "ns=" (js/encodeURIComponent
                                                 (subs (str data-ns) 1))))
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
  "One pass powering the whole /data page, grouping live attrs BY THEIR
   NAMESPACE (entities have no kind):
   `{::ns-groups {ns → #{eids}} ::attr-counts {ns → {attr → n}}}`.
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
                (let [ns-kw (keyword (namespace a))]
                  (-> acc
                      (update-in [::ns-groups ns-kw] (fnil conj #{}) e)
                      (update-in [::attr-counts ns-kw a] (fnil inc 0))))))
            {::ns-groups {} ::attr-counts {}}
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

(defn- data-ns-index
  "Level 1 — every stored attribute NAMESPACE with its row count,
   namespace-name order."
  [ns-groups params]
  (if (empty? ns-groups)
    [:div {:class "text-text-500 italic text-xs font-mono p-2"}
     "no data rows yet — agents store rows here as they work"]
    (into [:div {:class "flex flex-col"}]
          (map (fn [[ns-kw eids]]
                 [:a {:href (data-url (assoc params ::data-ns ns-kw
                                             ::data-page 0))
                      :class (str "flex items-baseline gap-2 px-2 py-1 "
                                  "border-b border-base-800/60 "
                                  "hover:bg-base-900 text-xs font-mono")}
                  [:span {:class "text-amber-400"} (str "● " ns-kw)]
                  [:span {:class "ml-auto text-text-400 tabular-nums"}
                   (str (count eids) (if (= 1 (count eids)) " row" " rows"))]]))
          (sort-by (comp str key) ns-groups))))

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

(defn- data-ns-detail
  "Level 2 — one attribute namespace: its attrs with counts + the
   paginated rows table. Rows ordered by :db/id ascending (insertion
   order, deterministic for a given db value); explicit prev/next."
  [db ns-kw eids attr-counts params]
  (let [sorted    (vec (sort eids))
        total     (count sorted)
        last-page (max 0 (quot (max 0 (dec total)) data-page-size))
        page      (min (::data-page params) last-page)
        start     (* page data-page-size)
        page-eids (subvec sorted (min start total)
                          (min (+ start data-page-size) total))]
    [:div {:class "flex flex-col gap-2"}
     [:div {:class "flex items-baseline gap-3 text-xs font-mono"}
      [:a {:href (data-url (assoc params ::data-ns nil ::data-page 0))
           :class "text-amber-500 hover:text-amber-300"} "← all namespaces"]
      [:span {:class "text-amber-400"} (str "● " ns-kw)]
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
        (str "no rows under " ns-kw " in this view — retracted, or stored "
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
  [{::keys [data-ns data-system?] :as params}]
  (let [db (deref db/*conn*)
        {::keys [ns-groups attr-counts]} (data-scan db data-system?)]
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
       [:a {:href "/world"
            :class "text-xs font-mono text-amber-500 hover:text-amber-300"}
        "← all agents"]]]
     (if data-ns
       (data-ns-detail db data-ns
                       (get ns-groups data-ns #{})
                       (get attr-counts data-ns {})
                       params)
       (data-ns-index ns-groups params))]))

(defn- data-page-html
  "Full page for GET /data — the SSE stream carries this view's params so
   every commit re-morphs the exact view the tab is on."
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
          ;; The persistent global status bar (fixed top, request-time
          ;; snapshot) + scroll spacer under it.
          (header/system-header (deref db/*conn*))
          header/header-spacer
          [:div {:data-init (str "@get('/data/sse" (data-qs params) "')")
                 :data-on:online__window
                 (str "@get('/data/sse" (data-qs params) "')")}]
          (data-browser-fragment params)]]))))

;; ============================================================
;; SSE wire + the per-agent / per-data tx-listener.
;; ============================================================

(defn- write-status! [^js res code mime body]
  (.writeHead res code #js {"Content-Type"  mime
                            "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res body))

(defn- agent-not-found-page
  "Small full page for `/agent/<id>/debug` when `<id>` has no entity in
   the cluster store — stale tabs/bookmarks land here after a pod restart
   onto a different store or a `bin/seon cluster reset`."
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
           [:a {:href "/world"
                :class "text-amber-500 hover:text-amber-300 text-xs underline"}
            "← all live agents"]]]]))))

(defn- patch-fragment
  "Wrap a hiccup fragment in a datastar-patch-elements SSE payload."
  [hiccup]
  (let [html-str (html/->string hiccup)]
    (str "event: datastar-patch-elements\n"
         "data: elements " (str/replace html-str "\n" "\ndata: elements ")
         "\n\n")))

(defn- debug-payloads
  "The debug view's morph fragments (header + both panes + context bar)
   as one SSE payload string."
  [agent-id snap]
  (str (patch-fragment (header-fragment agent-id snap))
       (patch-fragment (ai-pane-fragment agent-id snap))
       (patch-fragment (html-pane-fragment agent-id snap))
       (patch-fragment (context-bar-fragment agent-id snap))))

(defn- push-agent!
  "Re-render and write the debug morph fragments to every connection
   watching `agent-id`. The snapshot is computed at most once per push.
   Best-effort per-connection."
  [agent-id]
  (try
    (let [conns   (get @!sse-by-agent agent-id)
          payload (when (seq conns) (debug-payloads agent-id (snapshot agent-id)))]
      (doseq [{:keys [res]} conns]
        (try (some->> payload (.write res))
             (catch :default e
               (log/error-console! "seon.web.debug" "write failed" e))))
      (log/info-console! "seon.web.debug" "push"
                         {:agent agent-id :conns (count conns)}))
    (catch :default e
      (log/error-console! "seon.web.debug" "push! threw" e))))

(defn- push-data!
  "Re-render and write the `#data-browser` morph fragment to every
   connection watching /data. Each conn carries ITS view's params
   (kind/page/system) — identical param sets render once."
  []
  (try
    (doseq [[params cs] (group-by :params (get @!sse-by-agent ::data))]
      (let [payload (patch-fragment (data-browser-fragment params))]
        (doseq [{:keys [res]} cs]
          (try (.write res payload)
               (catch :default e
                 (log/error-console! "seon.web.debug" "data write failed" e))))))
    (catch :default e
      (log/error-console! "seon.web.debug" "push-data! threw" e))))

(defonce ^:private !pending (atom {}))

(defn- schedule-push! [agent-id]
  ;; First arrival in the window starts a 100ms trailing timer. Later
  ;; arrivals inside the window just keep `!pending` set; the timer's
  ;; callback drains it. `::data` is the data-browser pseudo-agent.
  (let [was-pending? (get @!pending agent-id)]
    (swap! !pending assoc agent-id true)
    (when-not was-pending?
      (js/setTimeout
        (fn []
          (swap! !pending dissoc agent-id)
          (case agent-id
            ::data (push-data!)
            (push-agent! agent-id)))
        100))))

(defn- on-tx
  "Fan a tx out to the watching agents it affects. Scope rules:
   - core tx (no `:seon.db/agent-id`) → ALL watching agents;
   - `:seon.db/origin :core-seed` → ALL watching agents EVEN when
     agent-stamped (boot-seed runs inside the booting agent's `with-agent`
     scope, but seed tx is core data every agent's render shows);
   - otherwise → only the stamping agent.
   The `::data` browser watches EVERY commit (its row counts tick on any
   agent's work). All datoms in one commit share the tx, so the first
   datom's tx eid is the commit's."
  [{:seon.db/keys [db datoms]}]
  (let [tx-eid   (some (fn [d] (:seon.db/tx d)) datoms)
        tx-ent   (when tx-eid (d/entity db tx-eid))
        scope-id (:seon.db/agent-id tx-ent)
        ;; Core data every agent's render shows: the append-only seed
        ;; (:core-seed) AND the reconcile-managed declarative set (:config —
        ;; routes + skills). Both fan out to ALL watching agents.
        seed?    (#{:core-seed :config} (:seon.db/origin tx-ent))
        watching (watching-agents)
        targets  (if (or (nil? scope-id) seed?)
                   (disj watching ::data)
                   (filter #(= % scope-id) watching))
        targets  (cond-> (set targets)
                   (contains? watching ::data) (conj ::data))]
    (doseq [aid targets]
      (schedule-push! aid))))

(defn install!
  "Install the debug tx-listener and kick off the brand env sync.

   Idempotent — re-installing replaces the prior handler. The SEON_BRAND_*
   env vars own the `:seon.web.brand` row, and install! is the web
   surface's boot hook (called after boot-seed! with the root conn bound).
   Fire-and-forget — sync! never rejects and logs its own failures."
  []
  (brand/sync!)
  (db/listen! {:seon.db/key     ::debug
               :seon.db/handler on-tx}))

(defn uninstall!
  "Remove the debug tx-listener."
  []
  (db/unlisten! {:seon.db/key ::debug}))

(defn ^:dev/before-load before-reload
  "Uninstall the debug tx-listener before a hot reload."
  []
  (try (uninstall!) (catch :default _ nil)))

(defn ^:dev/after-load after-reload
  "Reinstall the debug tx-listener after a hot reload."
  []
  (try (install!) (catch :default _ nil)))

;; ============================================================
;; HTTP handlers — wired as plain reitit routes by seon.web.router's
;; static supplement.
;; ============================================================

(defn- open-agent-sse!
  "Open a debug SSE stream for `agent-id` (`/agent/<id>/debug/sse`).
   Registers in the per-agent registry; `push-agent!` writes the debug
   fragment set on every relevant tx."
  [^js req ^js res agent-id]
  (.writeHead res 200 #js {"Content-Type"      "text/event-stream"
                           "Cache-Control"     "no-cache"
                           "Connection"        "keep-alive"
                           "X-Accel-Buffering" "no"})
  (.write res ": connected\n\n")
  (let [conn {:id (random-uuid) :res res :opened-at (js/Date.)}]
    (add-conn! agent-id conn)
    (log/info-console! "seon.web.debug" "SSE OPEN"
                       {:agent agent-id
                        :conn-id (str (:id conn))
                        :total (count (get @!sse-by-agent agent-id))})
    (.on req "close"
         (fn []
           (remove-conn! agent-id (:id conn))
           (log/info-console! "seon.web.debug" "SSE CLOSE"
                              {:agent agent-id :conn-id (str (:id conn))})))
    (try
      (.write res (debug-payloads agent-id (snapshot agent-id)))
      (catch :default e
        (log/error-console! "seon.web.debug" "initial render failed" e)))))

(defn- open-data-sse!
  "SSE stream for the /data browser. The connection pins ITS view's query
   params (kind/page/system) — `push-data!` re-renders exactly that view
   on every commit. Registered under the `::data` pseudo-agent key."
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
        (log/error-console! "seon.web.debug" "data initial render failed" e)))))

(defn debug-page!
  "GET /agent/<id>/debug — render the two-pane debug inspector.

   Guards a stale `<id>` (no entity in this store) with a clean 404 page
   rather than a 500 out of `snapshot`'s lookup-ref pull."
  [^js _req ^js res agent-id]
  (if (str/blank? agent-id)
    (write-status! res 404 "text/plain; charset=utf-8" "missing agent id")
    (if-not (agent-exists? agent-id)
      (write-status! res 404 "text/html; charset=utf-8" (agent-not-found-page agent-id))
      (write-status! res 200 "text/html; charset=utf-8"
                     (debug-shell agent-id (snapshot agent-id))))))

(defn debug-sse!
  "GET /agent/<id>/debug/sse — open the debug view's SSE stream.

   A stale `<id>` 404s cleanly (never registers a connection for a
   nonexistent agent — every later tx would re-render it and throw)."
  [^js req ^js res agent-id]
  (if (or (str/blank? agent-id) (not (agent-exists? agent-id)))
    (write-status! res 404 "text/plain; charset=utf-8"
                   (str "agent " agent-id " not found"))
    (open-agent-sse! req res agent-id)))

(defn data-page!
  "GET /data — the live datom browser."
  [^js req ^js res]
  (write-status! res 200 "text/html; charset=utf-8"
                 (data-page-html (data-params req))))

(defn data-sse!
  "GET /data/sse — the /data browser's SSE stream."
  [^js req ^js res]
  (open-data-sse! req res))
