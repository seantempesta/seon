(ns seon.render.system
  "Root's SYSTEM VIEW — the `/` dashboard that IS root's world canvas.

   `root = /` (root-os-vision): the all-agents overview IS the root agent's
   world. Generic agents fall through their live tile to `welcome` (their
   latest-reply card); root's `:seon.render.live-tile/content` is seeded to
   [[system-view]] here, so the SAME `render-agent-tile` seam every agent
   uses renders the fleet/system view for root. ONE mechanism, one seam — no
   special layout, no special route.

   [[system-view]] is the live-tile content fn: a pure function of the db
   value (passed explicitly), returning the `:seon.render/html-response`
   envelope (`:seon.render/hiccup` for the human card, `:seon.render/ai` for
   root's prompt understanding of the fleet — same data, agent-facing). It
   composes four derived sub-views, top-down — the scan path of someone
   running a fleet: *is it healthy? who's doing what? what does it know?
   what just happened?*

     1. VITALS    ([[fleet-summary]]) — agent count by derived state, total
                  turns/evals, last activity, embeddings on/off.
     2. AGENTS    — a card grid, one `render/render-agent-tile` preview per
                  agent + derived state dot + turn count + purpose, each card
                  a link to `/agent/{id}`. Root's own card first, marked.
     3. STORE     ([[store-summary]]) — `seon.db/store-inventory`: which attrs
                  hold data, the system's memory at a glance. Links to `/data`.
     4. ACTIVITY  ([[recent-activity]]) — the UNFILTERED cross-agent event
                  stream (messages + evals, no per-agent filter), newest
                  first, each line linking to its agent.

   Everything is DERIVED at render time — nothing stored, nothing to clear.
   The view is a function of the db, so it self-heals: a fixed problem stops
   rendering its surface on the next morph."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.render :as render]
    [seon.schema :as schema]))

;; ============================================================
;; The literal root id (carved into `:seon.agent/id` — agent.cljs). Root's
;; card renders a COMPACT system summary, NOT `render-agent-tile`: root's
;; tile content IS system-view, so re-rendering it per card would recurse.
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
  "One-line clamp of `s` to `n` chars (newlines → spaces), with an ellipsis."
  [s n]
  (let [s (-> (str s) (str/replace #"\s+" " ") str/trim)]
    (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s)))

(defn- all-agent-ids
  "Every agent id in the store (root + children), root FIRST then the rest
   sorted — the grid order."
  [db]
  (let [ids (or (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find [?id ...] :where [?a :seon.agent/id ?id]]})
                [])]
    (->> ids
         (sort-by (fn [id] [(if (= root-id id) 0 1) id]))
         vec)))

;; ============================================================
;; 1. Vitals / fleet summary — the derived pulse.
;; ============================================================

(schema/register! ::agent-line
  [:map
   [:seon.agent/id      :seon.agent/id]
   [:seon.derive/state  :seon.derive/state]
   [::turns             :int]
   [:seon.agent/purpose {:optional true} :string]
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

   One [[agent-line]] per agent (id,
   derived state, turn count, purpose, root?), the state-count breakdown,
   total turns + evals across the cluster, the last-activity instant, and
   whether semantic embeddings are on (`SEON_EMBED`). Pure read."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]] ::fleet]}
  [db]
  (let [ids    (all-agent-ids db)
        agents (mapv
                 (fn [id]
                   (let [a       (db/entity {:seon.db/db db
                                             :seon.db/ref [:seon.agent/id id]})
                         purpose (:seon.agent/purpose a)]
                     (cond-> {:seon.agent/id     id
                              :seon.derive/state (derive/derive-state db id)
                              ::turns            (derive/agent-turn-count db id)
                              ::root?            (= root-id id)}
                       (string? purpose) (assoc :seon.agent/purpose purpose))))
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
             ::embedding?   (some? (.. js/process -env -SEON_EMBED))}
      last-at (assoc ::last-activity last-at))))

;; ============================================================
;; 3. Store / schema overview — the system's memory at a glance.
;; ============================================================

(defn store-summary
  "The cluster's `seon.db/store-inventory` over db `db`.

   Which attrs hold
   data RIGHT NOW (the concise map-out: `:seon.db/attr-groups` rows,
   namespace/attr/datom counts). Consumed DEFENSIVELY by key. Pure read."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]] :map]}
  [db]
  (db/store-inventory {:seon.db/db db}))

;; ============================================================
;; 4. Recent cross-agent activity — the UNFILTERED event stream.
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
  [db]
  (->> (db/query
         {:seon.db/db db
          :seon.db/query
          '[:find ?at ?aid ?src
            :where
            [?ev :seon.eval/agent ?a]
            [?a  :seon.agent/id ?aid]
            [?ev :seon.eval/at ?at]
            [(get-else $ ?ev :seon.eval/source "") ?src]]})
       (keep (fn [[at aid src]]
               (let [text (truncate src 80)]
                 ;; Skip blank-source rows (a bare `}` / whitespace-only form
                 ;; split out of a multi-line eval) — noise in a glance feed.
                 (when-not (str/blank? text)
                   {::at    at
                    ::kind  :eval
                    ::label aid
                    ::href  (str "/agent/" aid)
                    ::text  text}))))))

(defn- message-activity
  "Recent MESSAGE events across ALL agents (no per-agent filter). Attributed
   to the SENDER when it's an agent; a human-origin message is attributed to
   its first agent recipient (so the line still links into the fleet)."
  [db]
  (->> (db/query
         {:seon.db/db db
          :seon.db/query
          '[:find [(pull ?m [:seon.agent.message/at
                             :seon.agent.message/content
                             {:seon.agent.message/from [:seon.agent/id :seon.user/id]}
                             {:seon.agent.message/to [:seon.agent/id]}]) ...]
            :where [?m :seon.agent.message/at _]]})
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
                    ::text  (truncate (:seon.agent.message/content m) 80)}))))))

(defn recent-activity
  "The UNFILTERED cross-agent event stream over db `db`.

   Messages + evals
   from EVERY agent UNIONed, newest-first, capped at `n` (default 12). The
   reactive-context property made literal: a query that doesn't filter by
   `:seon.agent/id` sees the whole cluster. Each event links to its agent.
   Pure read."
  {:malli/schema [:function
                  [:=> [:catn [:seon.db/db :seon.db/db-val]] [:vector ::activity-event]]
                  [:=> [:catn [:seon.db/db :seon.db/db-val] [::n :int]] [:vector ::activity-event]]]}
  ([db] (recent-activity db 12))
  ([db n]
   (->> (concat (eval-activity db) (message-activity db))
        (filter #(instance? js/Date (::at %)))
        (sort-by #(.getTime ^js (::at %)))
        reverse
        (take n)
        vec)))

;; ============================================================
;; Rendering — hiccup (human) + ai (root's prompt). Children are SEQS
;; (doall'd) so the `seon.ui.html` splicer never hits a vector in child
;; position, and the render-agent-tile serialization backstop forces them
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
      (if embedding? "⌁ embeddings on" "embeddings off")]]))

(defn- root-card-hiccup
  "Root's OWN card — a compact, marked system label (NOT render-agent-tile;
   root's tile IS system-view, so re-rendering it would recurse)."
  [{::keys [turns] :keys [seon.agent/purpose]}]
  [:div {:class "flex flex-col gap-1 p-3 h-full"}
   [:div {:class "flex items-center gap-2"}
    [:span {:class "text-amber-400"} "★"]
    [:span {:class "text-sm font-mono text-text-100"} "root"]
    [:span {:class "text-[10px] uppercase tracking-wide text-amber-500/80 border border-amber-700/50 rounded px-1"}
     "system"]]
   [:div {:class "text-xs text-text-400"}
    (or purpose "supervisor — the fleet at a glance")]
   [:div {:class "mt-auto text-[10px] font-mono text-text-600"}
    (str turns " turns · you are here")]])

(defn- agent-card-hiccup
  "One agent card: the agent's `render-agent-tile` preview (its own live
   tile / welcome) + a footer with the derived-state dot, turn count, and an
   `open →`. The whole card is a stretched link to `/agent/{id}` (a DIV +
   inset-0 anchor, NOT a wrapping `<a>` — agent hiccup can contain `<a>`,
   and nested anchors split in the parser)."
  [db {:keys [seon.agent/id] ::keys [root? turns] state :seon.derive/state :as line}]
  (let [[dot cls] (state-dot state)
        body (if root?
               (root-card-hiccup line)
               (or (:seon.render/hiccup
                     (render/render-agent-tile {:seon.db/db db :seon.agent/id id}))
                   [:div {:class "p-3 text-xs text-text-500 italic"}
                    "no tile yet"]))]
    [:div {:class (str "relative flex flex-col h-44 border rounded overflow-hidden "
                       "transition-colors animate-appear "
                       (if root?
                         "border-amber-700/60 bg-base-900/40 hover:border-amber-500"
                         "border-base-800 hover:border-amber-700/70"))}
     [:div {:class "flex-1 min-h-0 overflow-hidden"} body]
     [:div {:class (str "shrink-0 flex items-center gap-2 px-3 py-1 "
                        "border-t border-base-800 bg-base-900/80 text-xs font-mono")}
      [:span {:class cls} dot]
      [:span {:class "text-text-400"} (name state)]
      [:span {:class "text-text-600"} (str "turn " turns)]
      [:span {:class "ml-auto text-amber-500"} "open →"]]
     [:a {:href (str "/agent/" id)
          :aria-label (str "open agent " id)
          :class "absolute inset-0"}]]))

(defn- grid-hiccup [db {::keys [agents]}]
  [:div {:class "p-3"}
   [:div {:class "grid gap-3"
          :style "grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));"}
    (doall (map #(agent-card-hiccup db %) agents))]])

(defn- store-hiccup [{:seon.db/keys [attr-groups attr-ns-count attr-count datom-count]}]
  [:div {:class "px-3 py-2 border-t border-base-800"}
   [:div {:class "flex items-baseline gap-3 mb-1"}
    [:span {:class "text-xs font-semibold text-text-200"} "store"]
    [:span {:class "text-[11px] font-mono text-text-500"}
     (str (or attr-ns-count 0) " namespaces · " (or attr-count 0) " attrs · "
          (or datom-count 0) " datoms")]
    [:a {:href "/data"
         :class "ml-auto text-[11px] font-mono text-amber-500 hover:text-amber-300"}
     "⛁ data browser →"]]
   (if (seq attr-groups)
     [:div {:class "flex flex-col gap-0.5"}
      (doall
        (for [{:seon.db/keys [attr-ns attrs]} (take 8 attr-groups)]
          [:div {:key (str attr-ns) :class "text-[11px] font-mono text-text-400"}
           [:span {:class "text-text-200"} (str attr-ns)] " "
           [:span {:class "text-text-600"}
            (str/join " "
                      (for [[a c] (take 6 attrs)] (str (name a) "(" c ")")))]]))]
     [:div {:class "text-[11px] font-mono text-text-600 italic"}
      "no agent-written data yet"])])

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
      (for [{:keys [seon.agent/id seon.agent/purpose]
             ::keys [turns root?] state :seon.derive/state} agents]
        (str "; - " (when root? "★ ") id " [" (name state) "] " turns " turns"
             (when purpose (str " — " (truncate purpose 60))))))))

(defn- store-ai [{:seon.db/keys [attr-groups attr-ns-count attr-count datom-count]}]
  (str/join
    "\n"
    (concat
      ["; STORE — which attrs hold data, grouped by namespace (see (seon.db/store-inventory))"
       (str "; " (or attr-ns-count 0) " namespaces · " (or attr-count 0)
            " attrs · " (or datom-count 0) " datoms")]
      (for [{:seon.db/keys [attr-ns attrs]} (take 8 attr-groups)]
        (str "; - " attr-ns ": "
             (str/join " " (for [[a c] (take 6 attrs)] (str (name a) "(" c ")"))))))))

(defn- activity-ai [events]
  (str/join
    "\n"
    (cons "; RECENT ACTIVITY (every agent — unfiltered)"
          (for [{::keys [at kind text label]} events]
            (str "; " (short-time at) " " label " "
                 (if (= :eval kind) "λ" "✉") " " text)))))

;; ============================================================
;; The public live-tile content fn — root's seeded canvas.
;; ============================================================

(schema/register! ::view-input
  [:map
   [:seon.db/db :seon.db/db]
   [:seon.agent/id {:optional true} :string]])

(defn system-view
  "Root's live-tile content (`:seon.render.live-tile/content` symbol).

   Called
   by `render/render-agent-tile` with the render input map (carrying the db
   value); returns the `:seon.render/html-response` envelope — the system
   dashboard hiccup for the human card + root's fleet understanding for its
   prompt. Reads the db EXPLICITLY (purity); composes [[fleet-summary]],
   the agent grid, [[store-summary]], and [[recent-activity]]."
  {:malli/schema [:=> [:cat ::view-input] :seon.render/html-response]}
  [{:seon.db/keys [db]}]
  (let [db       (or db @db/*conn*)
        fleet    (fleet-summary db)
        store    (store-summary db)
        activity (recent-activity db)]
    {:seon.render/hiccup
     [:div {:class "seon-tile flex flex-col bg-base-950 text-text-200"}
      (vitals-hiccup fleet)
      (grid-hiccup db fleet)
      (store-hiccup store)
      (activity-hiccup activity)]
     :seon.render/ai
     (str/join "\n;\n" [(fleet-ai fleet) (store-ai store) (activity-ai activity)])}))
