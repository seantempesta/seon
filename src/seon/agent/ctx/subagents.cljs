(ns seon.agent.ctx.subagents
  "Two derived agent-relationship context sections (multiagent-context Pieces
   3 + 4), both pure functions of the db that render NOTHING when their query
   is empty (the reactive-context vanish — no stored state, nothing to clear):

     - `subagents-block` — the GENERAL section: the DIRECT children the
       rendering agent spawned (`:seon.agent/parent` = me), one compact line
       each with derived state + progress-or-result-or-death. A parent's
       monitoring surface: completion is a FACT in the DB (`:seon.agent.run/
       result`), so a parent that was mid-turn or restarted still sees every
       child result. The renderer is deliberately dormant in the minimal
       manifest until solo-agent drives graduate.

     - `orphaned-agents-block` — the ROOT-ONLY section: live agents whose
       parent is TERMINATED. Root (or the human) decides per case with the
       existing functions — no cascade-terminate, no reparenting (observe first).
       Wired into `:seon.config/root-context` (like `:core-faults`).

   Sizes shown are TOKENS (`seon.ai.tokens/estimate`), never chars. Both
   sections read the passed db EXPLICITLY (purity) and are byte-stable given a
   db — the beat-age / breaker `now` is the only wall-clock input (a display
   surface, not the pure scan core)."
  (:require
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db :as db]
    [seon.derive :as derive]))

;; ============================================================
;; Shared bits.
;; ============================================================

(def ^:private state-dot
  "Dot glyph per derived state (the dot+word convention)."
  {:idle "●" :running "●" :paused "●" :terminated "○"})

(def ^:private max-children
  "How many direct children to render inline before the truncation footer —
   bounds the section as a fleet grows." 20)

(def ^:private section-token-cap
  "Total TOKEN budget for the section body (clipped with a loud footer)." 800)

(defn- clip
  "Collapse whitespace and clamp `s` to a token `budget` with a trailing `…`."
  [s budget]
  (-> (str s) (str/replace #"\s+" " ") str/trim (tokens/clip-str budget)))

(defn- child-ids
  "The DIRECT children of `parent-id` in `db` — agents whose `:seon.agent/parent`
   resolves to `parent-id`. Sorted for a stable render."
  [db parent-id]
  (->> (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find [?cid ...] :in $ ?pid
                    :where
                    [?p :seon.agent/id ?pid]
                    [?c :seon.agent/parent ?p]
                    [?c :seon.agent/id ?cid]]
                  :seon.db/args [parent-id]})
       sort vec))

(defn- latest-closed-run
  "The child's most-recently-STARTED CLOSED run entity in `db`, or nil."
  [db child-id]
  (some->> (db/query {:seon.db/db db
                      :seon.db/query
                      '[:find ?r ?started :in $ ?aid
                        :where
                        [?a :seon.agent/id ?aid]
                        [?r :seon.agent.run/agent ?a]
                        [?r :seon.agent.run/status :closed]
                        [?r :seon.agent.run/started-at ?started]]
                      :seon.db/args [child-id]})
           (sort-by #(.getTime ^js (second %)))
           last
           first
           (#(db/entity {:seon.db/db db :seon.db/ref %}))))

(defn- age-str
  "Human `Ns`/`Nm`/`Nh` age of instant `t` before `now`, or nil."
  [now t]
  (when (and (instance? js/Date t) (instance? js/Date now))
    (let [s (max 0 (quot (- (.getTime now) (.getTime t)) 1000))]
      (cond
        (< s 60)   (str s "s")
        (< s 3600) (str (quot s 60) "m")
        :else      (str (quot s 3600) "h")))))

(defn- child-line
  "One compact `;`-comment line for a direct child, derived from state + its
   latest run. Running → progress (turn i/limit, beat age); idle+completed →
   the run `:result` (+ a ref pointer); idle+abnormal → the closed-reason (a
   parent MUST see a child that DIED, not just one that succeeded)."
  [db now child-id breaker-n breaker-w]
  (let [ent      (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id child-id]})
        purpose  (:seon.agent/purpose ent)
        state    (derive/derive-state db child-id)
        cur      (derive/current-run db child-id)
        tripped? (derive/schedule-breaker-tripped? db child-id now breaker-n breaker-w)
        detail
        (cond
          ;; RUNNING — live progress.
          cur
          (let [rid   (:seon.agent.run/id cur)
                turn  (derive/run-turn-count db rid)
                limit (:seon.agent.run/turn-limit cur)
                beat  (age-str now (:seon.agent.run/last-beat-at cur))]
            (str "turn " turn "/" limit
                 (when beat (str " · beat " beat " ago"))))
          ;; IDLE / not-running — its latest run's outcome.
          :else
          (when-let [lr (latest-closed-run db child-id)]
            (let [reason (:seon.agent.run/closed-reason lr)
                  result (:seon.agent.run/result lr)
                  rref   (:db/id (:seon.agent.run/result-ref lr))]
              (if (= :completed reason)
                (str "✓ completed"
                     (when (and (string? result) (seq result))
                       (str ": " (clip result 120)))
                     (when rref (str " [→ eid " rref "]")))
                (str "✗ " (name reason))))))]
    (str "; - " (state-dot state) " " child-id " [" (name state) "]"
         (when (and (string? purpose) (seq purpose))
           (str " " (clip purpose 30)))
         (when detail (str " · " detail))
         (when tripped?
           (str " · ⚠ schedule-wake paused (breaker: ≥" breaker-n
                " crashes/" breaker-w "ms)")))))

(defn subagents-block
  "The DIRECT children you spawned, one compact line each (Piece 3).

   Empty when you spawned none (the reactive vanish). Per child: id · derived
   state (dot+word) · purpose · and — running: `turn i/limit` + last-beat age;
   idle with a completed latest run: its `:seon.agent.run/result` (+ a ref
   pointer); closed abnormally: the closed-reason (so a dead child is
   visible, not just a succeeded one). A breaker-tripped child shows it. Pure
   read of the db; token-bounded (TOKENS, never chars)."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [db  (or db @db/*conn*)
        ids (when id (child-ids db id))]
    (if (empty? ids)
      ""
      (let [now       (js/Date.)
            breaker-n (config/schedule-breaker-crash-count)
            breaker-w (config/schedule-breaker-window-ms)
            shown     (take max-children ids)
            hidden    (- (count ids) (count shown))
            lines     (map #(child-line db now % breaker-n breaker-w) shown)
            footer    (when (pos? hidden)
                        (str "\n; … +" hidden " more child"
                             (when (> hidden 1) "ren")
                             " (query [?c :seon.agent/parent [:seon.agent/id \""
                             id "\"]]) "))
            body      (str ";;; SUBAGENTS — the " (count ids) " agent"
                           (when (> (count ids) 1) "s") " you spawned\n"
                           "; completion is a FACT in the DB — a child's result "
                           "survives your turns + restarts.\n"
                           (str/join "\n" lines)
                           footer)]
        (tokens/clip-str body section-token-cap)))))

;; ============================================================
;; Piece 4 — orphaned-agents (ROOT-ONLY, wired in :seon.config/root-context).
;; ============================================================

(defn- orphan-rows
  "[child-id parent-id] pairs in `db`: LIVE agents (not terminated) whose
   `:seon.agent/parent` IS terminated. Sorted for a stable render."
  [db]
  (->> (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find ?cid ?pid
                    :where
                    [?c :seon.agent/parent ?p]
                    [?p :seon.agent/id ?pid]
                    [?p :seon.agent/terminated-at _]
                    [?c :seon.agent/id ?cid]
                    (not [?c :seon.agent/terminated-at _])]})
       (sort-by first)
       vec))

(defn orphaned-agents-block
  "LIVE agents whose parent is TERMINATED — root cluster only (Piece 4).

   One line each: id · derived state · purpose · parent id. Empty → absent
   (the reactive vanish). No action machinery — root (or the human) decides
   per case with the existing functions (no cascade-terminate, no reparenting:
   observe first). Root-only by config wiring (rides `:seon.config/root-context`,
   like `:core-faults`). Pure read of the db."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db]}]
  (let [db   (or db @db/*conn*)
        rows (orphan-rows db)]
    (if (empty? rows)
      ""
      (str ";;; ORPHANED AGENTS — " (count rows)
           " live agent" (when (> (count rows) 1) "s")
           " whose parent is TERMINATED (root-only)\n"
           "; Their parent is dead but they are not — decide per case "
           "(terminate / re-task / leave).\n"
           (str/join
             "\n"
             (map (fn [[cid pid]]
                    (let [ent     (db/entity {:seon.db/db db
                                              :seon.db/ref [:seon.agent/id cid]})
                          purpose (:seon.agent/purpose ent)
                          state   (derive/derive-state db cid)]
                      (str "; - " (state-dot state) " " cid " [" (name state) "]"
                           (when (and (string? purpose) (seq purpose))
                             (str " " (clip purpose 30)))
                           " · parent " pid " (terminated)")))
                  rows))))))
