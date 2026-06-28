(ns seon.ui.header
  "The persistent global status bar — `system-header = f(db)` — rendered as a
   fixed top bar on EVERY view (every `/agent/{id}`, root `/`, the `/world`
   roster, `/data`, `/debug`). One pure function of the db value: same db
   always renders the same hiccup, nothing stored, self-healing.

   It is the fleet's pulse at a glance, left→right:

     BRAND      — the deployment's `SEON_BRAND_NAME` (via `seon.web.brand`),
                  linking home to `/` (root's system dashboard).
     AGENTS     — color-coded dots + counts of the DERIVED FSM state across
                  ALL agents (`seon.derive/derive-state`, surfaced through
                  `seon.render.system/fleet-summary` — DRY, one counter).
     THROUGHPUT — the live token rate + totals. `seon.agent.turn/at` +
                  `:seon.agent.turn/llm-usage` are the only stored signals;
                  there is NO per-turn duration, so an instantaneous
                  tokens/sec is NOT honestly derivable. Instead [[throughput]]
                  reports a ROLLING rate — tokens from turns STARTED in the
                  last 60 s ÷ 60 s — beside the all-time token total and the
                  turn/eval counts. The rolling rate is the honest \"is the
                  fleet busy right now\" signal; it is 0 when nothing ran
                  recently.
     STORE      — datom count (links `/data`) + embeddings on/off (`SEON_EMBED`).
     ACTIONS    — a `+ new agent` button (POSTs `/agents/new`, then SWITCHES
                  to the new `/agent/{id}`) + small `/` and `⛁ data` links +
                  a subtle system-health dot.

   On the morphed world pages the header rides inside `#world`, so every
   commit re-renders it and the stats tick LIVE. On the server-rendered
   `/data` and `/debug` pages it is a request-time snapshot. The `+ new
   agent` button uses an inline `prompt()` for the optional purpose (no
   persistent input that a live morph would clobber)."
  (:require
    [seon.agent.ctx.usage :as usage]
    [seon.db :as db]
    [seon.render.system :as system]
    [seon.schema :as schema]
    [seon.web.brand :as brand]))

;; ============================================================
;; Throughput — the honest derivation. No per-turn duration is stored, so a
;; true instantaneous tokens/sec is impossible; we report a ROLLING-window
;; rate plus all-time totals. `:total_tokens` (openai/DeepSeek) is the full
;; call cost; for the Anthropic shape we sum extract's input-total + output.
;; ============================================================

(def ^:private window-ms
  "Trailing window for the rolling token rate (60 s)."
  60000)

(schema/register! ::tokens   :int)
(schema/register! ::tok-per-sec :double)
(schema/register! ::throughput
  [:map
   [::total-tokens ::tokens]       ; all-time tokens (input incl. cache + output)
   [::recent-tokens ::tokens]      ; tokens from turns started in the last window
   [::tok-per-sec   ::tok-per-sec] ; recent-tokens / window-seconds — honest rolling rate
   [::last-tokens   ::tokens]])    ; the most recent turn's full token cost

(defn- turn-tokens
  "Full token cost of one turn's persisted `:seon.agent.turn/llm-usage` EDN
   string — input total (incl. cache) + output — via the shared
   `seon.agent.ctx.usage/extract` (no duplicate parsing). 0 when usage is
   absent/unparseable (a stub-LLM turn)."
  [usage-str]
  (if-let [{::usage/keys [total output]} (usage/extract usage-str)]
    (+ (or total 0) (or output 0))
    0))

(defn throughput
  "Derive the fleet's token throughput from db `db`: every turn's start
   instant + usage, reduced to the all-time total, the rolling-window total,
   the honest rolling tokens/sec (window total ÷ 60 s), and the last turn's
   cost. Pure read — `:seon.agent.turn/at` + `:seon.agent.turn/llm-usage` are
   the only inputs."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]] ::throughput]}
  [db]
  (let [rows (db/query {:seon.db/db db
                        :seon.db/query
                        '[:find ?at ?u
                          :where
                          [?t :seon.agent.turn/at ?at]
                          [?t :seon.agent.turn/llm-usage ?u]]})
        now  (.getTime (js/Date.))
        cut  (- now window-ms)
        rows (->> rows
                  (keep (fn [[at u]]
                          (when (instance? js/Date at)
                            [(.getTime ^js at) (turn-tokens u)])))
                  (sort-by first))
        total  (reduce + 0 (map second rows))
        recent (reduce + 0 (->> rows (filter #(>= (first %) cut)) (map second)))
        last*  (or (some-> (last rows) second) 0)]
    {::total-tokens  total
     ::recent-tokens recent
     ::tok-per-sec   (/ recent (/ window-ms 1000.0))
     ::last-tokens   last*}))

;; ============================================================
;; State dots — the same Phosphor palette the system view uses (a glance
;; vocabulary that stays consistent across surfaces).
;; ============================================================

(def ^:private state-dot
  {:idle       ["●" "text-text-400"]
   :running    ["▸" "text-amber-400"]
   :paused     ["⚠" "text-warning"]
   :terminated ["○" "text-text-600"]})

(defn- agents-chunk
  "The agents-by-state cluster: idle + running ALWAYS shown (the two states
   that matter at a glance), paused/terminated only when present. Counts come
   from `fleet-summary`'s `::state-counts` — one counter, not re-derived."
  [{::system/keys [state-counts agents]}]
  (let [n (count agents)]
    [:span {:class "flex items-center gap-2"}
     [:span {:class "text-text-200"} (str n)]
     [:span {:class "text-text-500"} (if (= 1 n) "agent" "agents")]
     (for [st [:idle :running :paused :terminated]
           :let [c (get state-counts st 0)]
           :when (or (#{:idle :running} st) (pos? c))]
       (let [[dot cls] (state-dot st)]
         [:span {:key (name st) :class "flex items-center gap-1"}
          [:span {:class cls} dot]
          [:span {:class "text-text-300"} (str c)]
          [:span {:class "text-text-600"} (name st)]]))]))

(defn- throughput-chunk
  "The throughput cluster: the rolling tok/s (amber when the fleet is live),
   the all-time token total, and the turn/eval counts."
  [{::system/keys [total-turns total-evals]} {::keys [tok-per-sec total-tokens last-tokens]}]
  (let [live? (pos? tok-per-sec)
        fmt-k (fn [n] (if (>= n 1000)
                        (str (.toFixed (/ n 1000.0) 1) "k")
                        (str n)))]
    [:span {:class "flex items-center gap-2"}
     [:span {:class (if live? "text-amber-400" "text-text-600")
             :title "rolling tokens/sec over the last 60s (no per-turn duration is stored)"}
      (str (.toFixed tok-per-sec 1) " tok/s")]
     [:span {:class "text-text-600"} "·"]
     [:span {:class "text-text-400" :title "all-time tokens (input incl. cache + output)"}
      [:span {:class "text-text-200"} (fmt-k total-tokens)] " tok"]
     [:span {:class "text-text-600"
             :title (str "last turn " last-tokens " tok")}
      (str total-turns "t/" total-evals "e")]]))

(defn- store-chunk
  "The store + embeddings cluster: datom count (links `/data`) + the
   `SEON_EMBED` indicator."
  [db {::system/keys [embedding?]}]
  (let [{:seon.db/keys [datom-count]} (db/store-inventory {:seon.db/db db})]
    [:span {:class "flex items-center gap-2"}
     [:a {:href  "/data"
          :class "flex items-center gap-1 text-text-400 hover:text-amber-300"
          :title "open the data browser"}
      [:span {:class "text-text-200"} (str (or datom-count 0))]
      "⛁ datoms"]
     [:span {:class (if embedding? "text-amber-400" "text-text-600")
             :title (if embedding? "SEON_EMBED on — semantic recall active"
                                   "SEON_EMBED off")}
      (if embedding? "⌁ embed" "embed off")]]))

(def ^:private new-agent-onclick
  "Inline new-agent action: prompt for an optional purpose, POST `/agents/new`
   (the same same-origin-gated door the roster bar uses), then SWITCH to the
   new `/agent/{id}` on the id body. Inline JS (not datastar `@post`) because
   the response is the new id we must READ + navigate to. Errors land in the
   button text — never swallowed. No persistent input → nothing for a live
   `#world` morph to clobber."
  (str "var b=this;"
       "var p=window.prompt('purpose for the new agent (optional)','');"
       "if(p===null)return;"
       "b.disabled=true;b.textContent='booting…';"
       "var body=p&&p.trim()?'purpose='+encodeURIComponent(p.trim()):'';"
       "fetch('/agents/new',{method:'POST',"
       "headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})"
       ".then(function(r){if(r.ok){r.text().then(function(id){"
       "window.location='/agent/'+id.trim();});}"
       "else{r.text().then(function(t){b.disabled=false;"
       "b.textContent='+ new agent';alert('create failed: '+(t||('HTTP '+r.status)));});}})"
       ".catch(function(e){b.disabled=false;b.textContent='+ new agent';alert('create failed: '+e);});"))

(defn- actions-chunk
  "The right-side action cluster: `+ new agent`, the home + data links, and a
   subtle system-health dot (amber when any agent is running)."
  [{::system/keys [state-counts]}]
  (let [running? (pos? (get state-counts :running 0))]
    [:span {:class "flex items-center gap-3"}
     [:button {:type    "button"
               :onclick new-agent-onclick
               :class   (str "bg-base-800 hover:bg-base-700 text-signal border "
                             "border-base-700 px-2 py-0.5 rounded text-xs font-mono")}
      "+ new agent"]
     [:a {:href "/" :class "text-text-400 hover:text-amber-300"} "home"]
     [:a {:href "/world" :class "text-text-400 hover:text-amber-300"} "agents"]
     [:span {:class (if running? "text-amber-400" "text-text-600")
             :title (if running? "an agent is running" "fleet idle")}
      "●"]]))

;; ============================================================
;; system-header = f(db) — the public fixed top bar.
;; ============================================================

(defn system-header
  "view = f(db): the persistent global status bar as a fixed top `<header>`.
   Pure of external state (reads only the supplied db value); NEVER throws —
   a render error degrades to a minimal brand-only bar so the bar can sit on
   every page without endangering the page. Composes [[agents-chunk]] (fleet
   state from `fleet-summary`), [[throughput-chunk]], [[store-chunk]], and
   [[actions-chunk]] (the `+ new agent` switch). Place ONE per page; reserve
   scroll room with a sibling spacer (the bar is `position:fixed`)."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]] :any]}
  [db]
  (try
    (let [fleet (system/fleet-summary db)
          thru  (throughput db)
          brand (brand/info db)]
      [:header {:id    "system-header"
                :style "top:0"
                :class (str "fixed left-0 right-0 z-20 flex items-center gap-x-4 gap-y-1 "
                            "flex-wrap border-b border-base-800 bg-base-900/95 backdrop-blur "
                            "px-3 py-1.5 text-xs font-mono")}
       [:a {:href  "/"
            :class "flex items-center gap-1.5 text-text-100 font-semibold hover:text-amber-300 shrink-0"}
        [:span {:class "text-amber-400"} "◆"]
        (::brand/name brand)]
       [:span {:class "text-text-700"} "│"]
       (agents-chunk fleet)
       [:span {:class "text-text-700"} "│"]
       (throughput-chunk fleet thru)
       [:span {:class "text-text-700"} "│"]
       (store-chunk db fleet)
       [:span {:class "ml-auto"} (actions-chunk fleet)]])
    (catch :default e
      [:header {:id    "system-header"
                :style "top:0"
                :class (str "fixed left-0 right-0 z-20 flex items-center gap-2 "
                            "border-b border-base-800 bg-base-900/95 px-3 py-1.5 "
                            "text-xs font-mono text-text-400")}
       [:a {:href "/" :class "text-text-100 font-semibold"} "seon"]
       [:span {:class "text-error"} (str "header error: " (or (.-message e) (str e)))]])))

(def header-spacer
  "A sibling spacer reserving scroll room equal to the fixed header's height
   so the bar never hides the first tile/content. Inline height (no Tailwind
   height class is guaranteed in the built vocabulary)."
  [:div {:id "system-header-spacer" :style "height:2.25rem"}])
