(ns seon.ui.header
  "The persistent global status bar — `system-header = f(db)` — rendered as a
   fixed top bar on EVERY view (every `/agent/{id}`, root `/`, the `/agents`
   agent list, `/data`, `/debug`). One pure function of the db value: same db
   always renders the same hiccup, nothing stored, self-healing.

   It is the fleet's pulse at a glance, left→right:

     BRAND      — the deployment's `SEON_BRAND_NAME` (via `seon.web.brand`),
                  linking home to `/` (root's system dashboard).
     AGENTS     — color-coded dots + counts of the DERIVED FSM state across
                  ALL agents (`seon.derive/derive-state`, surfaced through
                  `seon.render.system/fleet-summary` — DRY, one counter).
     DATABASE   — the current index's maintained datom count (links `/data`)
                  + embeddings on/off (`SEON_EMBED`). No broad database scan.
     ACTIONS    — a `+ new agent` button (POSTs `/agents`, then switches
                  to the new `/agent/{id}`) + small `/` and `⛁ data` links +
                  a subtle system-health dot.

   On the morphed app views the header rides inside `#app-view`, so every
   commit re-renders it and the stats tick LIVE. On the server-rendered
   `/data` and `/debug` pages it is a request-time snapshot. The `+ new
   agent` button creates immediately without a modal; the agents page owns the
   optional-purpose input outside its live morph target."
  (:require
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.render.system :as system]
    [seon.schema :as schema]
    [seon.web.brand :as brand]))

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

(defn- database-chunk
  "The db + embeddings cluster: datom count (links `/data`) + the
   `SEON_EMBED` indicator."
  [db {::system/keys [embedding?]}]
  (let [datom-count (db/datom-count db)]
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

(defn- storms-chunk
  "HEALTH SIGNAL — agents currently THRASHING on broken evals
   (`seon.derive/error-storms`: an anomalous recent eval-failure rate).
   DERIVED, so it vanishes the instant an agent's evals recover; renders
   NOTHING (incl. its own leading divider) when the fleet is healthy. Each
   storming agent links to its `/agent/{id}` so the human can intervene."
  [storms]
  (when (seq storms)
    [:span {:class "flex items-center gap-2"}
     [:span {:class "text-text-700"} "│"]
     [:span {:class "text-warning"
             :title "agents with an anomalous recent eval-failure rate"}
      "⚠"]
     (for [{:seon.agent/keys [id] :seon.derive/keys [failed window consec]} storms]
       [:a {:key   id
            :href  (str "/agent/" id)
            :class "flex items-center gap-1 text-warning hover:text-amber-300"
            :title (str failed "/" window " recent evals failed"
                        (when (>= consec 2) (str " · " consec " in a row")))}
        [:span {:class "font-semibold"} id]
        [:span {:class "text-text-500"} "erroring"]])]))

(def ^:private new-agent-onclick
  "Inline new-agent action: POST `/agents`, then switch to the new agent.

   The universal header creates an unpurposed agent immediately. The agents
   page's outside-the-morph form is the one place for an optional purpose. Inline JS
   (not Datastar `@post`) reads the returned id before navigating. Failures
   stay visible in the button instead of depending on modal browser APIs."
  (str "var b=this;"
       "b.disabled=true;b.textContent='booting…';"
       "fetch('/agents',{method:'POST',"
       "headers:{'Content-Type':'application/x-www-form-urlencoded'},body:''})"
       ".then(function(r){if(r.ok){r.text().then(function(id){"
       "window.location='/agent/'+id.trim();});}"
       "else{r.text().then(function(t){b.disabled=false;"
       "b.textContent='✗ '+(t||('HTTP '+r.status));});}})"
       ".catch(function(e){b.disabled=false;b.textContent='✗ '+e;});"))

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
     [:a {:href "/agents" :class "text-text-400 hover:text-amber-300"} "agents"]
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
   state from `fleet-summary`), [[database-chunk]], and
   [[actions-chunk]] (the `+ new agent` switch). Place ONE per page; reserve
   scroll room with a sibling spacer (the bar is `position:fixed`)."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]] :any]}
  [db]
  (try
    (let [fleet (system/fleet-summary db)
          brand (brand/info db)
          state-counts (::system/state-counts fleet)]
      [:header {:id    "system-header"
                :data-agent-count (count (::system/agents fleet))
                :data-idle-agents (get state-counts :idle 0)
                :data-running-agents (get state-counts :running 0)
                :data-paused-agents (get state-counts :paused 0)
                :data-terminated-agents (get state-counts :terminated 0)
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
       (database-chunk db fleet)
       (storms-chunk (derive/error-storms db))
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
   so the bar never hides the first canvas/content. Inline height (no Tailwind
   height class is guaranteed in the built vocabulary)."
  [:div {:id "system-header-spacer" :style "height:2.25rem"}])
