(ns seon.web.debug
  "Operator dev tools — the two surfaces that have NO agent-view equivalent:

     GET /agent/<id>/debug      — the two-pane debug view: the EXACT
                                  bytes the LLM receives (left) beside the
                                  rendered context-block html twins (right),
                                  plus the per-block token + cache-line
                                  audit bar along the bottom.
     GET /agent/<id>/debug/feed — shared gzip Datastar feed for the debug view.
     GET /data                  — the live database browser: installed
                                  attributes plus bounded AEVT pages.
     GET /data/feed             — shared gzip Datastar feed for the data view.

   These are the operator/developer introspection surfaces (raw prompt,
   cache-line audit, database facts) — distinct from the agent-view renderers
   (`seon.web.datastar`), which own `/`, `/agents`, `/agent/<id>`. The routes
   are wired into `seon.web.router`'s static supplement as plain reitit routes.

   Both panes derive from `agent-debug/ctx-preview`, whose rendered context blocks
   come from the same frozen DB projection as the prompt. The left pane shows
   exact AI text; the right pane shows only blocks declaring an HTML twin.

   Debug is dormant until its route is opened. It uses the same view-unit
   catalog, gzip feed registry, tx listener, backpressure, and morph framing as
   every normal page. Raw AI bodies and HTML twins are inactive stubs until the
  operator expands them."
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.usage :as ctx-usage]
    [seon.db :as db]
    [seon.db.browser :as db-browser]
    [seon.derive :as derive]
    [seon.agent.debug :as agent-debug]
    [seon.log :as log]
    [seon.render.surface :as surface]
    [seon.schema :as schema]
    [seon.ui.components :as comp]
    [seon.ui.header :as header]
    [seon.ui.html :as html]
    [seon.web.brand :as brand]
    [seon.web.datastar :as datastar]))

(schema/register! ::ring-request :map)
(schema/register! ::debug-format [:enum :ai :html])
(schema/register! ::debug-block-index :int)

;; ============================================================
;; Data sources — one snapshot per render.
;; ============================================================

(defn- agent-exists?
  "True iff `agent-id` resolves to a live `:seon.agent/id` entity in the
   cluster database. Guards the page + SSE routes: stale tabs/bookmarks
   carry ids from prior databases (pre-flip pods, reset clusters) — without
   this check the page 500s on `snapshot`'s lookup-ref pull and the SSE
   registers a connection that throws on every subsequent tx."
  [agent-id]
  (boolean
    (seq (db/query {:seon.db/query '[:find ?e
                                     :in $ ?id
                                     :where [?e :seon.agent/id ?id]]
                    :seon.db/args  [agent-id]}))))

;; ============================================================
;; Context bar — the per-block token + cache-line audit instrument.
;; DISPLAY-ONLY, all DERIVED at render time: per-block estimated tokens
;; (seon.ai.tokens/estimate over the SAME block texts the LLM prompt is
;; built from — one render, two consumers), the STRUCTURAL cache breakpoint
;; (end of the byte-stable prefix = after :namespaces), and the LIVE cached
;; extent (exact, off the last turn's persisted :seon.agent.turn/llm-usage).
;; The divergence between the two markers is the point.
;; ============================================================

(defn- ns-block-name
  "Display name for one namespace block of the :namespaces block:
   `; namespace seon.db` → `:namespaces/seon.db`. nil for a chunk
   with no namespace header (the block's preamble line)."
  [block]
  (when-let [m (re-find #"(?m)^; namespace (\S+)" block)]
    (keyword "namespaces" (second m))))

(defn- expand-namespaces-block
  "Display-only: explode the single `:namespaces` block into ONE entry
   per namespace block, so the debug view lists each ns separately instead
   of one giant `:namespaces` blob. The LLM prompt is untouched — this only
   reshapes the per-block breakdown both panes + the context bar consume.
   Every other block passes through unchanged, in render order."
  [block-texts]
  (into []
        (mapcat
          (fn [{nm :seon.agent.ctx/name txt :seon.render/text :as sec}]
            (if (= nm :namespaces)
              (->> (str/split (or txt "") #"(?m)(?=^; namespace )")
                   (remove str/blank?)
                   (map (fn [chunk]
                          (assoc sec
                                 :seon.agent.ctx/name
                                 (or (ns-block-name chunk) :namespaces)
                                 :seon.render/text chunk))))
              [sec])))
        block-texts))

(defn- context-bar-data
  "Derive the context-bar model for `agent-id` from the SAME per-block
   texts the LLM prompt is built from (`block-texts`):

     {::segments [{::name ::tokens ::stable?} …]  ; render order
      ::total-tokens N
      ::cache-line-tokens N        ; structural breakpoint (end of prefix)
      ::live-cached-tokens N|nil   ; exact, from last turn's usage
      ::provider-shape kw|nil}

   `::live-cached-tokens` is nil when no turn has run / usage is absent."
  [agent-id block-texts total-tokens]
  (let [body-segs (mapv (fn [{nm :seon.agent.ctx/name
                            priority :seon.agent.ctx/priority
                            txt :seon.render/text}]
                       (let [t (or txt "")]
                         {::name    nm
                          ::tokens  (tokens/estimate t)
                          ::stable? (<= (or priority js/Number.MAX_SAFE_INTEGER)
                                        (ctx/cache-breakpoint))}))
                     block-texts)
        body-total (reduce + 0 (map ::tokens body-segs))
        assembly-tokens (max 0 (- total-tokens body-total))
        segs (cond-> body-segs
               (pos? assembly-tokens)
               (conj {::name :prompt-assembly
                      ::tokens assembly-tokens
                      ::stable? false}))
        ;; Structural cache-line = cumulative tokens of every block up to
        ;; AND INCLUDING the last stable one, in render order. (Blocks
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
     ::total-tokens       total-tokens
     ::cache-line-tokens  cache-line
     ::live-cached-tokens (some-> usage :seon.agent.ctx.usage/cached)
     ::provider-shape     (some-> usage :seon.agent.ctx.usage/provider-shape)}))

(defn- snapshot
  "Compute the exact AI projection and cheap debug diagnostics for one agent.

   HTML twins and the canvas are deliberately absent: each is an independent
   view-unit producer and is invoked only while its `<details>` is open."
  [agent-id]
  (let [{:seon.render/keys [text token-estimate]
         rendered-blocks :seon.agent.ctx/rendered-blocks}
        (agent-debug/ctx-preview {:seon.agent/id agent-id
                                  :seon.render/formats #{:ai}})
        ;; Display-only: the single 49k-token :namespaces blob dwarfs every
        ;; other block into an unreadable sliver — split it into one entry
        ;; per ns so the breakdown (both panes + the bottom bar) shows the
        ;; real per-ns distribution. The LLM prompt + token-estimate are
        ;; unchanged (they derive from `text`).
        block-texts (expand-namespaces-block
                        (filterv #(contains? % :seon.render/text)
                                 (or rendered-blocks [])))
        turn-durs []
        db @db/*conn*
        state (derive/derive-state db agent-id)
        turn-count (derive/agent-turn-count db agent-id)]
    {:ai-text   (or text "")
     :agent-state state
     :agent-turn-count turn-count
     :block-texts (or block-texts [])
     :token-est  (or token-estimate 0)
     :context-bar (context-bar-data agent-id (or block-texts [])
                                    (or token-estimate 0))
     :turn-durs  turn-durs}))

;; ============================================================
;; Page rendering — full page (for initial GET) AND morph fragments
;; (for SSE pushes). The two panes use stable ids `#debug-ai-<id>` and
;; `#debug-html-<id>` so datastar morphs by id.
;; ============================================================

(defn- ai-pane-id   [agent-id] (str "debug-ai-" agent-id))
(defn- html-pane-id [agent-id] (str "debug-html-" agent-id))
(defn- header-id    [agent-id] (str "debug-header-" agent-id))

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
     [:a {:href "/"
          :class "text-xs text-amber-500 hover:text-amber-300"} "← all agents"]]))

(defn- fmt-int
  "`3214` → `\"3,214\"` — comma-grouped integer for summaries."
  [n]
  (let [s (str n)]
    (->> (reverse s)
         (partition-all 3)
         (map (partial apply str))
         (str/join ",")
         (str/reverse))))

(defn- unit-url
  "The trusted one-shot activation URL for an open debug view unit."
  [view-id descriptor]
  (str "/view/unit?view=" (js/encodeURIComponent view-id)
       "&unit=" (js/encodeURIComponent (::datastar/token descriptor))
       "&active="))

(defn- unit-toggle-expression
  "Activate while this exact details element is open; deactivate on close."
  [view-id descriptor]
  (str "if (evt.target === el) { @get('"
       (unit-url view-id descriptor)
       "' + (el.open ? '1' : '0'), {retry: 'never'}) }"))

(defn- raw-block-body
  "Current exact AI body for one stable snapshot block coordinate."
  [!snapshot block-index block-name]
  (let [snap @!snapshot
        blocks (:block-texts snap)
        indexed (when (<= 0 block-index) (nth blocks block-index nil))
        block (cond
                (= -1 block-index)
                {:seon.render/text (:ai-text snap)}

                (= block-name (:seon.agent.ctx/name indexed)) indexed

                :else
                (some #(when (= block-name (:seon.agent.ctx/name %)) %) blocks))]
    [:pre {:class (str "whitespace-pre-wrap text-xs font-mono "
                       "text-text-100 mt-0.5")}
     (or (:seon.render/text block) "(block no longer present)")]))

(defn- debug-definitions
  "Cheap unit definitions for raw AI bodies and HTML-capable surfaces."
  [agent-id !snapshot surface-catalog]
  (let [exact-def
        {::datastar/coordinate
         {:seon.agent/id agent-id
          ::debug-format :ai
          ::debug-block-index -1
          :seon.agent.ctx/name :exact-prompt}
         ::datastar/label "exact prompt"
         ::datastar/order 0
         ::datastar/producer
         #(raw-block-body !snapshot -1 :exact-prompt)}
        raw-defs
        (map-indexed
          (fn [index {block-name :seon.agent.ctx/name}]
            {::datastar/coordinate
             {:seon.agent/id agent-id
              ::debug-format :ai
              ::debug-block-index index
              :seon.agent.ctx/name block-name}
             ::datastar/label (name block-name)
             ::datastar/order (inc index)
             ::datastar/producer
             #(raw-block-body !snapshot index block-name)})
          (:block-texts @!snapshot))
        html-defs
        (keep-indexed
          (fn [index {selection ::surface/selection
                      label ::surface/label}]
            (when (not= "canvas" selection)
              {::datastar/coordinate
               {:seon.agent/id agent-id
                ::debug-format :html
                ::surface/selection selection}
               ::datastar/label label
               ::datastar/order index
               ::datastar/producer
               #(surface/materialize-surface
                  {:seon.db/db @db/*conn*
                   :seon.agent/id agent-id
                   ::surface/selection selection
                   ::surface/face :expanded})}))
          surface-catalog)]
    (vec (concat [exact-def] raw-defs html-defs))))

(defn- debug-projection
  "One exact AI snapshot plus the non-rendering HTML surface catalog."
  [agent-id !snapshot]
  (let [snap (snapshot agent-id)
        _ (reset! !snapshot snap)
        surfaces (surface/surface-catalog @db/*conn* agent-id)
        catalog (datastar/unit-catalog
                  (debug-definitions agent-id !snapshot surfaces))]
    {:seon.web.debug/snapshot snap
     :seon.web.debug/catalog catalog}))

(defn- descriptors-for
  [catalog format]
  (filterv #(= format (get (::datastar/coordinate %) ::debug-format)) catalog))

(defn- ai-block-details
  "One lazy `<details>` per exact AI block."
  [view-id active-tokens descriptor
   {sec-name :seon.agent.ctx/name sec-text :seon.render/text}]
  (let [active? (contains? active-tokens (::datastar/token descriptor))]
    [:details (cond-> {:class "mb-1"
                       :data-seon-key (str "ai-sec-" (::datastar/token descriptor))
                       :data-on:toggle (unit-toggle-expression view-id descriptor)}
                active? (assoc :open true))
     [:summary {:class (str "cursor-pointer select-none text-xs font-mono "
                            "text-text-400 hover:text-text-200 py-0.5")}
      (str (name sec-name) " (" (fmt-int (tokens/estimate sec-text)) " tokens)")]
     (datastar/unit-element descriptor active?)]))

(defn- ai-pane-fragment
  [agent-id view-id {:keys [ai-text block-texts]} catalog active-tokens]
  (let [descriptors (descriptors-for catalog :ai)
        displayed-blocks
        (into [{:seon.agent.ctx/name :exact-prompt
                :seon.render/text ai-text}]
              block-texts)]
  [:div {:id (ai-pane-id agent-id)
         :class "flex flex-col h-full overflow-hidden border-r border-base-800"}
   [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
    ":seon.render/ai  (exact prompt and source-block breakdown)"]
   (cond
     (str/blank? ai-text)
     [:pre {:class (str "flex-1 overflow-auto p-3 text-xs font-mono "
                        "whitespace-pre-wrap text-text-100 bg-base-950")}
      "(empty context)"]

     (seq displayed-blocks)
     (into [:div {:class "flex-1 overflow-auto p-3 bg-base-950"}]
           (map (fn [[descriptor block]]
                  (ai-block-details view-id active-tokens descriptor block)))
           (map vector descriptors displayed-blocks))

     :else
     [:pre {:class (str "flex-1 overflow-auto p-3 text-xs font-mono "
                        "whitespace-pre-wrap text-text-100 bg-base-950")}
      ai-text])]))

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
  [agent-id view-id {:keys [agent-state agent-turn-count]} catalog active-tokens]
  (let [running? (= :running agent-state)
        descriptors (descriptors-for catalog :html)]
    [:div {:id (html-pane-id agent-id)
           :class "flex flex-col h-full overflow-hidden"}
     [:div {:class "px-2 py-1 text-xs font-mono text-text-400 bg-base-900 border-b border-base-800"}
     ":seon.render/html  (rendered view)"]
     [:div {:id (str "debug-cards-" agent-id)
            :class "seon-agent-content flex-1 overflow-auto p-2 text-xs bg-base-950"}
      (if (seq descriptors)
        (into [:div {:class "flex flex-col gap-2"}]
              (map (fn [descriptor]
                     (let [active? (contains? active-tokens
                                              (::datastar/token descriptor))]
                       [:details
                        (cond-> {:class (str "border-l-2 border-amber-700/40 "
                                             "pl-2 py-1 animate-appear")
                                 :data-seon-key
                                 (str "html-sec-" (::datastar/token descriptor))
                                 :data-on:toggle
                                 (unit-toggle-expression view-id descriptor)}
                          active? (assoc :open true))
                        [:summary {:class (str "cursor-pointer select-none text-xs "
                                               "font-mono font-semibold text-text-400 "
                                               "hover:text-text-200")}
                         (::datastar/label descriptor)]
                        (datastar/unit-element descriptor active?)])))
              descriptors)
        [:div {:class "text-text-500 italic p-2"}
         "no context block currently declares an HTML twin"])
      (when running?
        (thinking-bubble (or agent-turn-count 0)))]]))

;; ============================================================
;; The context bar — per-block token + cache-line viz along the bottom
;; of the debug view. A horizontal stacked bar: one segment per
;; rendered block (width ∝ estimated tokens), the STRUCTURAL cache
;; breakpoint as one marker, the LIVE cached extent as a second marker.
;; Display-only — re-rendered every SSE push (stable id so idiomorph
;; preserves it).
;; ============================================================

(defn- context-bar-id [agent-id] (str "debug-ctxbar-" agent-id))

(defn- bar-segment
  "One block segment of the stacked bar. Width is a flex-grow weight
   (∝ tokens). Stable-prefix blocks read amber (the cached prefix);
   volatile-tail blocks read cooler. The block name + token count
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
  "The bottom context bar for the debug view. Stacked per-block
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
     [:div {:class "flex flex-wrap items-center gap-x-3 gap-y-0.5 mb-1 text-[10px] font-mono text-text-400"}
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
      [:span {:class "shrink-0 text-text-600"} "stable prefix amber · volatile tail grey"]]
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
  "Shared debug head: local output CSS, brand overrides, and Datastar only.

   Markdown and Clojure highlighting are rendered server-side, so a collapsed
   debug page downloads no legacy CDN renderers."
  [title]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   [:title title]
   [:link {:rel "stylesheet" :href "/css/output.css"}]
   (brand-css-style)
   [:style (html/raw page-style-css)]
   [:script {:type "module" :src "/js/datastar.js"}]])

(defn- debug-app-view
  "The live debug morph target. Only active unit producers run."
  [agent-id view-id snap catalog active-tokens]
  [:main {:id "app-view"
          :class "flex-1 min-h-0 flex flex-col overflow-hidden"}
   (header/system-header @db/*conn*)
   header/header-spacer
   (header-fragment agent-id snap)
   [:div {:class "flex-1 grid h-0 min-h-0"
          :style "grid-template-columns: 1fr 1fr;"}
    (ai-pane-fragment agent-id view-id snap catalog active-tokens)
    (html-pane-fragment agent-id view-id snap catalog active-tokens)]
   (context-bar-fragment agent-id snap)])

(defn- debug-shell
  "Cheap `/agent/<id>/debug` page shell; its feed performs the first projection."
  [agent-id view-id]
  (let [b (brand/info)]
    (str
      "<!DOCTYPE html>"
      (html/->string
        [:html {:lang "en" :data-theme (::brand/theme b)}
         (page-head (brand/page-title b (str "agent " agent-id " · debug")))
         [:body {:class "h-screen bg-base-950 text-text-50 font-sans antialiased flex flex-col"}
          [:main {:id "app-view"
                  :class "flex-1 min-h-0 flex items-center justify-center text-xs font-mono text-text-500"}
           "loading debug view…"]
          [:div {:id "debug-feed-opener"
                 :style "display:none"
                 :data-init
                 (str "@get('/agent/" agent-id "/debug/feed?view=" view-id
                      "', {retryMaxCount: Infinity, openWhenHidden: false})")}]
          (chat-bar-fragment agent-id)
          [:div {:class (str "shrink-0 px-2 py-0.5 text-right text-[10px] "
                             "font-mono text-text-500 bg-base-900 "
                             "border-t border-base-800")}
           "esc → agent"]
          [:script (html/raw
                     (str "document.addEventListener('keydown',function(e){"
                          "if(e.key==='Escape'){window.location='/agent/"
                          agent-id "';return;}"
                          "if((e.metaKey||e.ctrlKey)&&e.key==='Enter'){"
                          "var f=document.getElementById('seon-chat-form');"
                          "if(f){e.preventDefault();f.requestSubmit();}}});"))]]]))))

;; ============================================================
;; /data — the live database browser. The default navigator reads only the
;; installed schema. Opening an attribute reads one bounded AEVT page; the URL
;; carries its cursor. No render scans the complete entity set or history.
;; View state is the query string, never a database projection.
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
        attr (some-> (query-param req "attr") not-empty keyword)
        cursor
        (try
          (let [value (some-> (query-param req "cursor")
                              not-empty
                              reader/read-string)]
            (when (and (vector? value)
                       (= 3 (count value))
                       (int? (nth value 0))
                       (int? (nth value 2)))
              value))
          (catch :default _ nil))]
    {::data-ns      ns-kw
     ::data-attr    attr
     ::data-cursor  cursor
     ::data-system? (= "1" (query-param req "system"))}))

(defn- data-qs
  "Query string (\"\" or \"?…\") for a /data view-params map."
  [{::keys [data-ns data-attr data-cursor data-system?]}]
  (let [qs (cond-> []
             data-ns          (conj (str "ns=" (js/encodeURIComponent
                                                 (subs (str data-ns) 1))))
             data-attr        (conj (str "attr=" (js/encodeURIComponent
                                                   (subs (str data-attr) 1))))
             data-cursor      (conj (str "cursor=" (js/encodeURIComponent
                                                     (pr-str data-cursor))))
             data-system?     (conj "system=1"))]
    (if (seq qs) (str "?" (str/join "&" qs)) "")))

(defn- data-url [params] (str "/data" (data-qs params)))

(defn- data-toggle-link
  "The system-data toggle — same view, `system` query param flipped
   (open attribute and cursor reset because the navigator differs)."
  [{::keys [data-system?] :as params}]
  [:a {:href (data-url (assoc params ::data-ns nil
                              ::data-attr nil
                              ::data-cursor nil
                              ::data-system? (not data-system?)))
       :class (str "text-xs font-mono "
                   (if data-system?
                     "text-amber-400 hover:text-amber-300"
                     "text-text-400 hover:text-text-200"))}
   (if data-system?
     "● system data shown — hide"
     "○ show system data")])

(defn- data-ns-index
  "Every installed attribute namespace and its attribute count."
  [ns-groups params]
  (if (empty? ns-groups)
    [:div {:class "text-text-500 italic text-xs font-mono p-2"}
     "no domain attributes installed yet — show system data or let an agent define one"]
    (into [:div {:class "flex flex-col"}]
          (map (fn [[ns-kw attributes]]
                 [:a {:href (data-url (assoc params ::data-ns ns-kw
                                             ::data-attr nil
                                             ::data-cursor nil))
                      :class (str "flex items-baseline gap-2 px-2 py-1 "
                                  "border-b border-base-800/60 "
                                  "hover:bg-base-900 text-xs font-mono")}
                  [:span {:class "text-amber-400"} (str "● " ns-kw)]
                  [:span {:class "ml-auto text-text-400 tabular-nums"}
                   (str (count attributes)
                        (if (= 1 (count attributes)) " attribute" " attributes"))]]))
          (sort-by (comp str key) ns-groups))))

(defn- data-row-table
  "One bounded AEVT page. Values are clipped for display, never at read time."
  [rows]
  [:div {:class "overflow-x-auto"}
   [:table {:class "text-xs font-mono w-full"}
    [:thead
     [:tr {:class "text-left text-text-400 border-b border-base-700"}
      [:th {:class "pr-3 py-0.5 font-normal"} "entity"]
      [:th {:class "pr-3 py-0.5 font-normal"} "value"]
      [:th {:class "pr-3 py-0.5 font-normal"} "transaction"]]]
    (into [:tbody]
          (map (fn [{::db-browser/keys [entity value transaction]}]
                 [:tr {:class "border-b border-base-800/60 align-top"}
                  [:td {:class "pr-3 py-0.5 text-amber-400 tabular-nums"}
                   (str entity)]
                  [:td {:class "pr-3 py-0.5 text-text-100 break-all"}
                   (tokens/bounded-pr-str value 30)]
                  [:td {:class "pr-3 py-0.5 text-text-400 tabular-nums"}
                   (str transaction)]]))
          rows)]])

(defn- data-attribute-detail
  [db attr params]
  (let [{::db-browser/keys [rows more? next-cursor]}
        (db-browser/attribute-page
          (cond-> {:seon.db/db db
                   ::db-browser/attribute attr
                   ::db-browser/limit data-page-size}
            (::data-cursor params)
            (assoc ::db-browser/cursor (::data-cursor params))))]
    [:div {:class "flex flex-col gap-2"}
     [:div {:class "flex items-baseline gap-3 text-xs font-mono"}
      [:span {:class "text-amber-400"} (str attr)]
      [:span {:class "text-text-500"}
       (tokens/bounded-pr-str (db-browser/attribute-schema db attr) 60)]]
     (if (seq rows)
       (data-row-table rows)
       [:div {:class "text-text-500 italic text-xs font-mono"}
        "this installed attribute currently has no datoms"])
     [:div {:class "flex items-center gap-3 text-xs font-mono"}
      (when (::data-cursor params)
        [:a {:href (data-url (assoc params ::data-cursor nil))
             :class "text-amber-500 hover:text-amber-300"}
         "← first page"])
      (when more?
        [:a {:href (data-url (assoc params ::data-cursor next-cursor))
             :class "text-amber-500 hover:text-amber-300"}
         "next page →"])]]))

(defn- data-ns-detail
  "One namespace's installed attributes and an optional bounded attr page."
  [db ns-kw attributes params]
  (let [selected (::data-attr params)
        selected (when (some #{selected} attributes) selected)]
    [:div {:class "flex flex-col gap-2"}
     [:div {:class "flex items-baseline gap-3 text-xs font-mono"}
      [:a {:href (data-url (assoc params ::data-ns nil
                                  ::data-attr nil
                                  ::data-cursor nil))
           :class "text-amber-500 hover:text-amber-300"} "← all namespaces"]
      [:span {:class "text-amber-400"} (str "● " ns-kw)]
      [:span {:class "text-text-400"}
       (str (count attributes)
            (if (= 1 (count attributes)) " attribute" " attributes"))]]
     (into [:div {:class "flex flex-wrap gap-1 text-xs font-mono"}]
           (map (fn [attribute]
                  [:a {:href (data-url (assoc params
                                              ::data-attr attribute
                                              ::data-cursor nil))
                       :class (str "border rounded px-1.5 py-0.5 "
                                   (if (= attribute selected)
                                     "border-amber-700 text-amber-300"
                                     "border-base-800 text-text-400 hover:text-text-200"))}
                   (str attribute)]))
           attributes)
     (when selected
       (data-attribute-detail db selected params))]))

(defn- data-browser-fragment
  "The whole /data surface — ONE morph target (`#data-browser`),
   derived 100% from the DB at render time."
  [db {::keys [data-ns data-system?] :as params}]
  (let [ns-groups (db-browser/attribute-groups db data-system?)]
    [:div {:id "data-browser" :class "flex flex-col gap-3"}
     [:div {:class "flex items-baseline gap-4 flex-wrap"}
      [:h1 {:class "text-sm font-mono font-semibold text-text-100"}
       "data"]
      [:span {:class "text-xs font-mono text-text-500"}
       (if data-system?
         "all installed attributes"
         "domain attributes — bounded index reads")]
      [:div {:class "ml-auto flex items-baseline gap-4"}
       (data-toggle-link params)
       [:a {:href "/"
            :class "text-xs font-mono text-amber-500 hover:text-amber-300"}
        "← all agents"]]]
     (if data-ns
       (data-ns-detail db data-ns
                       (get ns-groups data-ns [])
                       params)
       (data-ns-index ns-groups params))]))

(defn- data-page-html
  "Cheap shell for GET /data. The shared feed owns first paint and updates."
  [params view-id]
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
          [:div {:data-init (str "@get('/data/feed" (data-qs params)
                                 (if (str/blank? (data-qs params)) "?" "&")
                                 "view=" view-id "')")
                 :data-on:online__window
                 (str "@get('/data/feed" (data-qs params)
                      (if (str/blank? (data-qs params)) "?" "&")
                      "view=" view-id "')")}]
          [:div {:id "data-browser"
                 :class "text-xs font-mono text-text-500"}
           "loading data…"]]]))))

;; ============================================================
;; HTTP response helpers.
;; ============================================================

(defn- write-status! [^js res code mime body]
  (.writeHead res code #js {"Content-Type"  mime
                            "Cache-Control" "no-store, no-cache, must-revalidate"})
  (.end res body))

(defn- agent-not-found-page
  "Small full page for `/agent/<id>/debug` when `<id>` has no entity in
   the cluster database — stale tabs/bookmarks land here after a pod restart
   onto a different database or a `bin/seon cluster reset`."
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
            (str "agent " agent-id " is not in this cluster database")]
           [:div {:class "text-text-500 text-xs mb-4"}
            "it belonged to a previous database — this tab is stale"]
           [:a {:href "/"
                :class "text-amber-500 hover:text-amber-300 text-xs underline"}
            "← all live agents"]]]]))))

;; ============================================================
;; HTTP handlers.
;; ============================================================

(defn- debug-feed-definition
  [agent-id view-id]
  (let [!snapshot (atom {})
        initial (debug-projection agent-id !snapshot)
        initial-catalog (:seon.web.debug/catalog initial)
        render-debug
        (fn []
          (let [projection (debug-projection agent-id !snapshot)
                catalog (:seon.web.debug/catalog projection)
                active (datastar/reconcile-view-catalog!
                         {::datastar/view-id view-id
                          ::datastar/catalog catalog})]
            (debug-app-view agent-id view-id
                            (:seon.web.debug/snapshot projection)
                            catalog active)))]
    {:seon.web.feed/key [:seon.web.feed/debug agent-id view-id]
     :seon.web.feed/live? true
     ::datastar/view-id view-id
     ::datastar/catalog initial-catalog
     ::datastar/active-tokens #{}
     :seon.web.feed/render-full
     #(datastar/render-observed
        {:seon.db/db @db/*conn*
         ::datastar/render-thunk render-debug})
     :seon.web.feed/render-change
     (fn [{observations ::datastar/dependencies}
          {dbv :seon.db/db}]
       (datastar/transition-observed
        {:seon.db/db dbv
         ::datastar/dependencies observations
         ::datastar/render-thunk render-debug}))}))

(defn- data-feed-definition
  "One normalized render authority for an exact /data query projection."
  [params view-id]
  {:seon.web.feed/key [:seon.web.feed/data
                       (some-> (::data-ns params) str)
                       (some-> (::data-attr params) str)
                       (some-> (::data-cursor params) pr-str)
                       (::data-system? params)]
   :seon.web.feed/live? true
   ::datastar/view-id view-id
   :seon.web.feed/render-full
   #(datastar/render-observed
      {:seon.db/db @db/*conn*
       ::datastar/render-thunk
       (fn [] (data-browser-fragment @db/*conn* params))})
   :seon.web.feed/render-change
   (fn [{observations ::datastar/dependencies}
        {dbv :seon.db/db}]
     (datastar/transition-observed
      {:seon.db/db dbv
       ::datastar/dependencies observations
       ::datastar/render-thunk
       (fn [] (data-browser-fragment dbv params))}))})

(defn debug-page!
  "GET /agent/<id>/debug — serve a cheap shell with no debug projection.

   The separate feed performs the first AI render and publishes only stubs for
   closed raw/HTML bodies."
  {:malli/schema [:=> [:cat ::ring-request] :any]}
  [r]
  (let [^js res (:seon.http/node-res r)
        agent-id (get-in r [:path-params :id])]
  (if (str/blank? agent-id)
    (write-status! res 404 "text/plain; charset=utf-8" "missing agent id")
    (if-not (agent-exists? agent-id)
      (write-status! res 404 "text/html; charset=utf-8" (agent-not-found-page agent-id))
      (write-status! res 200 "text/html; charset=utf-8"
                     (debug-shell agent-id (datastar/new-view-id)))))))

(defn debug-feed!
  "GET /agent/<id>/debug/feed — open the shared gzip Datastar view.

   A stale id never registers a view or invokes a producer."
  {:malli/schema [:=> [:cat ::ring-request] :any]}
  [r]
  (let [^js res (:seon.http/node-res r)
        agent-id (get-in r [:path-params :id])
        view-id (or (datastar/request-view-id r)
                    (datastar/new-view-id))]
  (if (or (str/blank? agent-id) (not (agent-exists? agent-id)))
    (write-status! res 404 "text/plain; charset=utf-8"
                   (str "agent " agent-id " not found"))
    (datastar/open-view-feed! r (debug-feed-definition agent-id view-id)))))

(defn data-page!
  "GET /data — the live datom browser."
  [^js req ^js res]
  (write-status! res 200 "text/html; charset=utf-8"
                 (data-page-html (data-params req) (datastar/new-view-id))))

(defn data-feed!
  "GET /data/feed — open the shared gzip Datastar data-browser view."
  {:malli/schema [:=> [:cat ::ring-request] :any]}
  [r]
  (let [params (data-params (:seon.http/node-req r))
        view-id (or (datastar/request-view-id r)
                    (datastar/new-view-id))]
    (datastar/open-view-feed! r (data-feed-definition params view-id))))
