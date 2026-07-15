(ns seon.render.system
  "Root's SYSTEM VIEW — the `/` dashboard that IS root's agent-view canvas.

   The all-agents overview is the root agent's view. Generic agents fall
   through their canvas to `welcome` (their
   latest-reply card); root's `:seon.render.canvas/content` is seeded to
   [[system-view]] here, so the SAME `render-agent-canvas` seam every agent
   uses renders the fleet/system view for root. ONE mechanism, one seam — no
   special layout, no special route.

   [[system-view]] is the canvas content fn: a pure function of the db
   value (passed explicitly), returning the `:seon.render/html-response`
   envelope (`:seon.render/hiccup` for the human card, `:seon.render/ai` for
   root's prompt understanding of the fleet — same data, agent-facing). It
   composes three derived sub-views, top-down — the scan path of someone
   running a fleet: *is it healthy? who's doing what? what does it know?
   what just happened?*

     1. VITALS    ([[fleet-summary]]) — agent count by derived state, total
                  turns/evals, last activity, embeddings on/off.
     2. AGENTS    — a card grid using each agent's shared derived focus and
                  compact surface + state, turns, and purpose. Root's own card
                  is first and remains summary-only to prevent recursion.
     3. ACTIVITY  ([[recent-activity]]) — the UNFILTERED cross-agent event
                  stream (messages + evals, no per-agent filter), newest
                  first, each line linking to its agent.

   Everything is DERIVED at render time — nothing stored, nothing to clear.
   The view is a function of the db, so it self-heals: a fixed problem stops
   rendering its surface on the next morph."
  (:require
    [clojure.string :as str]
    [my.plan :as plan]
    [seon.agent.message :as message]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.eval :as seval]
    [seon.embed :as embed]
    [seon.render.surface :as surface]
    [seon.runtime.recovery :as recovery]
    [seon.schema :as schema]))

;; ============================================================
;; The literal root id (carved into `:seon.agent/id` — agent.cljs). Root's
;; card renders a COMPACT system summary. Root's focused canvas IS system-view,
;; so materializing it in its own fleet card would recurse.
;; ============================================================

(def ^:private root-id "root")

;; ------------------------------------------------------------
;; Small derivation helpers — local, pure.
;; ------------------------------------------------------------

(defn- ^js short-time
  "`HH:mm:ss` for a stored `#inst`, host-local; a non-Date → an empty string
   (activity lines tolerate a missing time rather than throwing — this is a
   glance surface, not the byte-stable transcript)."
  [inst]
  (if (instance? js/Date inst)
    (try (.toLocaleTimeString ^js inst "sv-SE")
         (catch :default _ (subs (.toISOString ^js inst) 11 19)))
    ""))

(defn- truncate
  "One-line clamp of `s` to a token `budget` (newlines → spaces, ellipsis)."
  [s budget]
  (-> (str s) (str/replace #"\s+" " ") str/trim
      (tokens/clip-str budget)))

(defn- all-agent-ids
  "Every agent id in the db (root + children), root FIRST then the rest
   sorted — the grid order."
  [db]
  (let [ids (or (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find [?id ...] :where [?a :seon.agent/id ?id]]})
                [])]
    (->> ids
         (sort-by (fn [id] [(if (= root-id id) 0 1) id]))
         vec)))

(defn- latest-human-message
  "Exact newest human-origin message addressed to `id`, or nil."
  [db id]
  (some->> (db/query
             {:seon.db/db db
              :seon.db/query
              '[:find ?content ?at ?m
                :in $ ?id
                :where
                [?a :seon.agent/id ?id]
                [?m :seon.agent.message/to ?a]
                [?m :seon.agent.message/origin :human]
                [?m :seon.agent.message/content ?content]
                [?m :seon.agent.message/at ?at]]
              :seon.db/args [id]})
           (sort-by (fn [[_ at eid]] [(.getTime ^js at) eid]))
           last
           first))

(defn- latest-agent-message
  "Exact newest message sent by agent `id`, or nil."
  [db id]
  (some->> (db/query
             {:seon.db/db db
              :seon.db/query
              '[:find ?content ?at ?m
                :in $ ?id
                :where
                [?a :seon.agent/id ?id]
                [?m :seon.agent.message/from ?a]
                [?m :seon.agent.message/content ?content]
                [?m :seon.agent.message/at ?at]]
              :seon.db/args [id]})
           (sort-by (fn [[_ at eid]] [(.getTime ^js at) eid]))
           last
           first))

;; ============================================================
;; 1. Vitals / fleet summary — the derived pulse.
;; ============================================================

(schema/register! ::run-turn :int)
(schema/register! ::run-line
  [:map
   [:seon.agent.run/id :string]
   [:seon.agent.run/started-at :inst]
   [:seon.agent.run/trigger :keyword]
   [:seon.agent.run/status :keyword]
   [:seon.agent.run/turn-limit :int]
   [:seon.agent.run/deadline :inst]
   [:seon.agent.run/last-beat-at {:optional true} :inst]
   [:seon.agent.run/paused-at {:optional true} :inst]
   [:seon.agent.run/remaining-ms {:optional true} :int]
   [::run-turn ::run-turn]])

(schema/register! ::agent-line
  [:map
   [:seon.agent/id      :seon.agent/id]
   [:seon.derive/state  :seon.derive/state]
   [::turns             :int]
   [:seon.agent/purpose {:optional true} :string]
   [::parent             {:optional true} :seon.agent/id]
   [::children           [:vector :seon.agent/id]]
   [::plan               {:optional true} :my.plan/position]
   [::latest-human       {:optional true} :string]
   [::latest-output      {:optional true} :string]
   [::run                {:optional true} ::run-line]
   [::last-run           {:optional true} :seon.derive/closed-run]
   [::latest-failure     {:optional true} :string]
   [::focus-selection   :seon.render.surface/selection]
   [::focus-label       :seon.render.surface/label]
   [::root?             :boolean]])

(schema/register! ::fleet
  [:map
   [::agents          [:vector ::agent-line]]
   [::state-counts    [:map-of :seon.derive/state :int]]
   [::total-turns     :int]
   [::total-evals     :int]
   [::last-activity   {:optional true} :inst]
   [::embedding?      :boolean]])

(defn fleet-summary
  "DERIVE the fleet pulse from db `db`.

   One [[agent-line]] per agent (identity/relationships, derived state,
   plan position, latest human instruction/output, open-run facts, newest
   failed eval, focused surface, and purpose), the state-count breakdown,
   total turns + evals across the cluster, the last-activity instant, and
   whether semantic embeddings are on (`SEON_EMBED`). Pure read."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]] ::fleet]}
  [db]
  (let [ids    (all-agent-ids db)
        recent-limit (config/root-recent-limit db)
        agents (mapv
                 (fn [id]
                   (let [a       (db/entity {:seon.db/db db
                                             :seon.db/ref [:seon.agent/id id]})
                         purpose (:seon.agent/purpose a)
                         parent  (:seon.agent/id (:seon.agent/parent a))
                         children (->> (db/query
                                         {:seon.db/db db
                                          :seon.db/query
                                          '[:find [?cid ...] :in $ ?pid
                                            :where
                                            [?p :seon.agent/id ?pid]
                                            [?c :seon.agent/parent ?p]
                                            [?c :seon.agent/id ?cid]]
                                          :seon.db/args [id]})
                                       sort vec)
                         plan-position (::plan/position
                                         (plan/position
                                           {:seon.db/db db :seon.agent/id id}))
                         latest-human (latest-human-message db id)
                         latest-output (latest-agent-message db id)
                         current-run (when-let [run (derive/current-run db id)]
                                       (assoc run ::run-turn
                                              (derive/run-turn-count
                                                db (:seon.agent.run/id run))))
                         last-run (derive/latest-closed-run db id)
                         latest-failure (some->> (seval/recent
                                                   {:seon.db/db db
                                                    :seon.agent/id id
                                                    :seon.eval/recent-limit recent-limit})
                                                  (filter #(false? (:seon.eval/ok? %)))
                                                  last
                                                  :seon.eval/error)
                         catalog (surface/surface-catalog db id)
                         selection (surface/latest-focus-selection catalog)
                         focused (some #(when (= selection
                                                (::surface/selection %))
                                          %)
                                       catalog)]
                     (cond-> {:seon.agent/id     id
                              :seon.derive/state (derive/derive-state db id)
                              ::turns            (derive/agent-turn-count db id)
                              ::children         children
                              ::focus-selection  selection
                              ::focus-label      (::surface/label focused)
                              ::root?            (= root-id id)}
                       (string? purpose) (assoc :seon.agent/purpose purpose)
                       (string? parent) (assoc ::parent parent)
                       plan-position (assoc ::plan plan-position)
                       (string? latest-human) (assoc ::latest-human latest-human)
                       (string? latest-output) (assoc ::latest-output latest-output)
                       current-run (assoc ::run current-run)
                       last-run (assoc ::last-run last-run)
                       (string? latest-failure) (assoc ::latest-failure latest-failure))))
                 ids)
        evals  (or (db/query {:seon.db/db db
                              :seon.db/query
                              '[:find (count ?ev) . :where [?ev :seon.eval/at _]]})
                   0)
        last-msg (db/query {:seon.db/db db
                            :seon.db/query
                            '[:find (max ?at) . :where [?m :seon.agent.message/at ?at]]})
        last-ev  (db/query {:seon.db/db db
                            :seon.db/query
                            '[:find (max ?at) . :where [?ev :seon.eval/at ?at]]})
        last-at  (->> [last-msg last-ev]
                      (filter #(instance? js/Date %))
                      (sort-by #(.getTime ^js %))
                      last)]
    (cond-> {::agents       agents
             ::state-counts (frequencies (map :seon.derive/state agents))
             ::total-turns  (reduce + 0 (map ::turns agents))
             ::total-evals  evals
             ::embedding?   (embed/enabled?)}
      last-at (assoc ::last-activity last-at))))

;; ============================================================
;; 3. Recent cross-agent activity — the UNFILTERED event stream.
;; ============================================================

(schema/register! ::activity-event
  [:map
   [::at    :inst]
   [::kind  [:enum :eval :message]]
   [::text  :string]
   [::href  :string]
   [::label :string]])

(defn- eval-activity
  "Recent EVAL events across ALL agents (no per-agent filter) — the
   denormalized `:seon.eval/agent` ref gives the owning agent in one hop."
  [db n]
  (->> (seval/recent-all
         {:seon.db/db db
          :seon.eval/recent-limit n})
       (keep (fn [{at :seon.eval/at
                   src :seon.eval/source
                   agent :seon.eval/agent}]
               (let [aid  (:seon.agent/id agent)
                     text (truncate src 20)]
                 ;; Skip blank-source rows (a bare `}` / whitespace-only form
                 ;; split out of a multi-line eval) — noise in a glance feed.
                 (when (and aid (not (str/blank? text)))
                   {::at    at
                    ::kind  :eval
                    ::label aid
                    ::href  (str "/agent/" aid)
                    ::text  text}))))))

(defn- message-activity
  "Recent MESSAGE events across ALL agents (no per-agent filter). Attributed
   to the SENDER when it's an agent; a human-origin message is attributed to
   its first agent recipient (so the line still links into the fleet)."
  [db n]
  (->> (message/recent-all
         {:seon.db/db db
          :seon.agent.message/recent-limit n})
       (keep (fn [m]
               (let [at      (:seon.agent.message/at m)
                     from    (:seon.agent.message/from m)
                     from-id (:seon.agent/id from)
                     to-id   (some :seon.agent/id (:seon.agent.message/to m))
                     aid     (or from-id to-id)
                     label   (cond from-id from-id
                                   (:seon.user/id from) (str "✉ " (:seon.user/id from))
                                   :else "✉")]
                 (when (and at aid)
                   {::at    at
                    ::kind  :message
                    ::label label
                    ::href  (str "/agent/" aid)
                    ::text  (truncate (:seon.agent.message/content m) 20)}))))))

(defn recent-activity
  "The UNFILTERED cross-agent event stream over db `db`.

   Messages + evals
   from EVERY agent UNIONed, newest-first, capped at `n`. The one-argument
   form reads the root recent limit from the same immutable database value. The
   reactive-context property made literal: a query that doesn't filter by
   `:seon.agent/id` sees the whole cluster. Each event links to its agent.
   Pure read."
  {:malli/schema [:function
                  [:=> [:catn [:seon.db/db :seon.db/db-val]] [:vector ::activity-event]]
                  [:=> [:catn [:seon.db/db :seon.db/db-val] [::n :int]] [:vector ::activity-event]]]}
  ([db] (recent-activity db (config/root-recent-limit db)))
  ([db n]
   (->> (concat (eval-activity db n) (message-activity db n))
        (filter #(instance? js/Date (::at %)))
        (sort-by #(.getTime ^js (::at %)))
        reverse
        (take n)
        vec)))

;; ============================================================
;; Rendering — hiccup (human) + ai (root's prompt). Children are SEQS
;; (doall'd) so the `seon.ui.html` splicer never hits a vector in child
;; position, and the render-agent-canvas serialization backstop forces them
;; inside its guard.
;; ============================================================

(def ^:private state-dot
  {:idle       ["●" "text-text-500"]
   :running    ["●" "text-amber-400"]
   :paused     ["●" "text-text-400"]
   :terminated ["○" "text-text-600"]})

(defn- vitals-hiccup [{::keys [state-counts total-turns total-evals
                               last-activity embedding?] :as fleet}]
  (let [n (count (::agents fleet))
        chip (fn [label v] [:span {:class "text-text-400"}
                            [:span {:class "text-text-200"} (str v)] " " label])]
    [:div {:class (str "flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2 "
                       "border-b border-base-800 bg-base-900/60 text-xs font-mono")}
     [:span {:class "text-text-100 font-semibold"} (str n " agents")]
     [:span {:class "text-text-600"} "·"]
     (doall
       (for [st [:idle :running :paused :terminated]
             :let [c (get state-counts st 0)]
             :when (pos? c)]
         (let [[dot cls] (state-dot st)]
           [:span {:key (name st) :class (str cls " mr-2")}
            dot " " [:span {:class "text-text-300"} (str c " " (name st))]])))
     [:span {:class "text-text-600"} "·"]
     (chip "turns" total-turns)
     (chip "evals" total-evals)
     [:span {:class "text-text-600"} "·"]
     [:span {:class "text-text-500"}
      (if last-activity (str "last " (short-time last-activity)) "no activity yet")]
     [:span {:class (if embedding? "text-amber-400" "text-text-600")}
      (if embedding? "⌁ embeddings on" "embeddings off")]
     [:a {:href "/data"
          :class "ml-auto text-amber-500 hover:text-amber-300"}
      "database →"]]))

(defn- root-card-hiccup
  "Root's own summary card, without recursively materializing its focus."
  [{::keys [turns focus-label] :keys [seon.agent/purpose]}]
  [:div {:class "flex flex-col gap-1 p-3 h-full"}
   [:div {:class "flex items-center gap-2"}
    [:span {:class "text-amber-400"} "★"]
    [:span {:class "text-sm font-mono text-text-100"} "root"]
    [:span {:class "text-[10px] uppercase tracking-wide text-amber-500/80 border border-amber-700/50 rounded px-1"}
     "system"]]
   [:div {:class "text-xs text-text-400"}
    (or purpose "supervisor — the fleet at a glance")]
   [:div {:class "mt-auto text-[10px] font-mono text-text-600"}
    (str turns " turns · focused " focus-label " · you are here")]])

(defn- agent-card-hiccup
  "One agent card using the shared derived focus and compact materializer."
  [db {:keys [seon.agent/id]
       ::keys [root? turns focus-selection focus-label]
       state :seon.derive/state :as line}]
  (let [[dot cls] (state-dot state)
        body (if root?
               (root-card-hiccup line)
               (or (surface/materialize-surface
                     {:seon.db/db db
                      :seon.agent/id id
                      ::surface/selection focus-selection
                      ::surface/face :compact})
                   [:div {:class "p-3 text-xs text-text-500 italic"}
                    "no focused surface yet"]))]
    [:div {:class (str "relative flex flex-col h-44 border rounded overflow-hidden "
                       "transition-colors animate-appear "
                       (if root?
                         "border-amber-700/60 bg-base-900/40 hover:border-amber-500"
                         "border-base-800 hover:border-amber-700/70"))}
     [:div {:class "flex-1 min-h-0 overflow-hidden"} body]
     [:div {:class (str "shrink-0 flex items-center gap-2 px-3 py-1 "
                        "border-t border-base-800 bg-base-900/80 text-xs font-mono")}
      [:span {:class cls} dot]
      [:span {:class "text-text-200 truncate"} id]
      [:span {:class "text-text-400"} (name state)]
      [:span {:class "text-text-600 truncate"}
       (str "turn " turns " · " focus-label)]
      [:span {:class "ml-auto text-amber-500"} "open →"]]
     [:a {:href (str "/agent/" id)
          :aria-label (str "open agent " id)
          :class "absolute inset-0"}]]))

(defn- grid-hiccup [db {::keys [agents]}]
  [:div {:class "p-3"}
   [:div {:class "grid gap-3"
          :style "grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));"}
    (doall (map #(agent-card-hiccup db %) agents))]])

(defn- recovery-hiccup
  "Compact unexpected-exit facts still awaiting root's judgment."
  [notices]
  [:div {:class (str "border-b border-amber-800/70 bg-amber-950/30 "
                     "px-3 py-2 text-xs")
         :data-seon-recovery-notice "true"}
   [:div {:class "font-semibold text-amber-300"}
    "Unexpected exit recovered"]
   (doall
     (for [{:seon.runtime.recovery/keys [id detail at agents runs turns]}
           notices]
       [:div {:key id :class "mt-1 flex flex-wrap items-baseline gap-x-2 gap-y-1"}
        [:span {:class "font-mono text-text-500"} (short-time at)]
        [:span {:class "text-text-200"}
         (str (count agents) " agent" (when-not (= 1 (count agents)) "s")
              " restored to idle")]
        (doall
          (for [agent-id agents]
            [:a {:key agent-id
                 :href (str "/agent/" agent-id)
                 :class "font-mono text-amber-400 hover:text-amber-200"}
             agent-id]))
        [:span {:class "text-text-500"}
         (str (count runs) " runs · " (count turns) " interrupted turns")]
        (when detail
          [:span {:class "text-text-400"} detail])]))
   [:div {:class "mt-1 text-text-400"}
    "Review the affected agents and resume only when appropriate."]])

(defn- activity-hiccup [events]
  [:div {:class "px-3 py-2 border-t border-base-800"}
   [:div {:class "text-xs font-semibold text-text-200 mb-1"} "recent activity"]
   (if (seq events)
     [:div {:class "flex flex-col divide-y divide-base-800/40 text-[11px] font-mono"}
      (doall
        (for [[i {::keys [at kind text href label]}] (map-indexed vector events)
              :let [eval? (= :eval kind)]]
          [:div {:key   (str i)
                 :class (str "relative flex items-baseline gap-2.5 px-1 py-1 "
                             "hover:bg-base-800/50")}
           ;; time — muted, fixed-width so the columns line up
           [:span {:class "shrink-0 tabular-nums w-12 text-text-600"} (short-time at)]
           ;; kind glyph — amber λ for evals, neutral ✉ for messages: sort by eye
           [:span {:class (str "shrink-0 w-3 text-center "
                               (if eval? "text-amber-500" "text-text-300"))
                   :title (name kind)}
            (if eval? "λ" "✉")]
           ;; agent — a quiet fixed column you can scan down
           [:span {:class "shrink-0 w-24 truncate text-text-400"} label]
           ;; content — dim monospace for eval SOURCE, bright prose for MESSAGES
           [:span {:class (str "min-w-0 truncate "
                               (if eval? "text-text-500" "text-text-100"))}
            text]
           ;; whole row clickable without underlining the text
           [:a {:href href :aria-label (str "open " label) :class "absolute inset-0"}]]))]
     [:div {:class "text-[11px] font-mono text-text-600 italic"}
      "nothing has happened yet"])])

;; ------------------------------------------------------------
;; The AI twin — root's prompt understanding of the fleet (same data).
;; ------------------------------------------------------------

(defn- fleet-ai [{::keys [agents state-counts total-turns total-evals
                          last-activity embedding?]}]
  (str/join
    "\n"
    (concat
      ["; SYSTEM VIEW — your fleet at a glance (root)"
       (str "; " (count agents) " agents: "
            (str/join " · " (for [st [:idle :running :paused :terminated]]
                              (str (get state-counts st 0) " " (name st)))))
       (str "; " total-turns " turns · " total-evals " evals · "
            (if last-activity (str "last activity " (short-time last-activity))
                "no activity yet")
            " · embeddings " (if embedding? "on" "off"))
       "; AGENTS"]
      (mapcat
        (fn [{:keys [seon.agent/id seon.agent/purpose]
              ::keys [turns root? focus-label parent children plan latest-human
                      latest-output run last-run latest-failure]
              state :seon.derive/state}]
          (let [progress (:my.plan/progress plan)
                run-id   (:seon.agent.run/id run)]
            (remove
              nil?
              [(str "; - " (when root? "★ ") id " [" (name state) "] " turns " turns"
                    " · focused " focus-label
                    (when purpose (str " — " (truncate purpose 15)))
                    (when parent (str " · parent " parent))
                    (when (seq children)
                      (str " · children "
                           (truncate (str/join "," children) 20))))
               (when plan
                 (str ";   plan " (truncate (:my.plan/title plan) 20) " → "
                      (if (:my.plan/active? plan) "active " "next ")
                      (truncate (:my.plan/step-title plan) 20) " ("
                      (:my.plan/done progress) "/" (:my.plan/total progress) ")"))
               (when run
                 (str ";   run " run-id " · turn "
                      (::run-turn run) "/" (:seon.agent.run/turn-limit run)
                      " · started " (short-time (:seon.agent.run/started-at run))
                      " · deadline " (short-time (:seon.agent.run/deadline run))
                      (when-let [beat (:seon.agent.run/last-beat-at run)]
                        (str " · beat " (short-time beat)))
                      (when (:seon.agent.run/paused-at run) " · PAUSED")))
               (when (and (nil? run) last-run)
                 (str ";   last run "
                      (name (:seon.agent.run/closed-reason last-run))
                      " · closed " (short-time (:seon.agent.run/closed-at last-run))
                      (when-let [result (:seon.agent.run/result last-run)]
                        (str " · result " (truncate result 30)))))
               (when latest-human
                 (str ";   human: " (truncate latest-human 30)))
               (when latest-output
                 (str ";   output: " (truncate latest-output 30)))
               (when latest-failure
                 (str ";   ⚠ newest eval: " (truncate latest-failure 30)))])))
        agents))))

(defn- activity-ai [events]
  (str/join
    "\n"
    (cons "; RECENT ACTIVITY (every agent — unfiltered)"
          (for [{::keys [at kind text label]} events]
            (str "; " (short-time at) " " label " "
                 (if (= :eval kind) "λ" "✉") " " text)))))

(defn- recovery-ai
  "Database-derived crash facts for root's existing canvas AI twin."
  [notices]
  (when (seq notices)
    (str/join
      "\n"
      (concat
        ["; UNEXPECTED EXIT RECOVERY — these agents were restored to idle"
         "; Review their interrupted work and decide whether any should resume."]
        (for [{:seon.runtime.recovery/keys [detail at agents runs turns]}
              notices]
          (str "; " (short-time at)
               " agents=" (pr-str agents)
               " runs=" (count runs)
               " interrupted-turns=" (count turns)
               (when detail (str " detail=" (pr-str detail)))))))))

;; ============================================================
;; The public canvas content fn — root's seeded canvas.
;; ============================================================

(schema/register! ::view-input
  [:map
   [:seon.db/db :seon.db/db]
   [:seon.agent/id {:optional true} :seon.agent/id]])

(defn system-view
  "Root's canvas content (`:seon.render.canvas/content` symbol).

   Called
   by the shared canvas renderer with the render input map (carrying the db
   value); returns the `:seon.render/html-response` envelope — the system
   dashboard hiccup for the human card + root's fleet understanding for its
   prompt. Reads the db EXPLICITLY (purity); composes [[fleet-summary]],
   the agent grid, and [[recent-activity]]."
  {:malli/schema [:=> [:cat ::view-input] :seon.render/html-response]}
  [{:seon.db/keys [db]}]
  (let [db       (or db @db/*conn*)
        fleet    (fleet-summary db)
        recoveries (recovery/pending-notices {:seon.db/db db})
        activity (recent-activity db)]
    {:seon.render/hiccup
     [:div {:class "seon-card flex flex-col bg-base-950 text-text-200"}
      (vitals-hiccup fleet)
      (when (seq recoveries) (recovery-hiccup recoveries))
      (grid-hiccup db fleet)
      (activity-hiccup activity)]
     :seon.render/ai
     (->> [(fleet-ai fleet)
           (recovery-ai recoveries)
           (activity-ai activity)]
          (remove str/blank?)
          (str/join "\n;\n"))}))
