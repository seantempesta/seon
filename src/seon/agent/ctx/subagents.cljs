(ns seon.agent.ctx.subagents
  "Render derived subagent relationships into context.

   The blocks summarize direct children for their parent and orphaned live
   agents for the root, disappearing when their database queries are empty.
   They are read-only monitoring views; lifecycle decisions and run outcomes
   remain owned by the agent runtime."
  (:require
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
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
  [now [child-id purpose terminated-at] current-run turn-count closed-run
   crash-count breaker-n breaker-w]
  (let [state    (derive/state-from-primitives
                   (cond-> {:seon.agent.run/open? (some? current-run)}
                     (not= ::absent terminated-at)
                     (assoc :seon.agent/terminated-at terminated-at)
                     (and current-run
                          (not= ::absent (:seon.agent.run/paused-at current-run)))
                     (assoc :seon.agent.run/paused-at
                            (:seon.agent.run/paused-at current-run))))
        tripped? (>= crash-count breaker-n)
        detail
        (cond
          ;; RUNNING — live progress.
          current-run
          (let [limit (:seon.agent.run/turn-limit current-run)
                beat  (age-str now (:seon.agent.run/last-beat-at current-run))]
            (str "turn " turn-count "/" limit
                 (when beat (str " · beat " beat " ago"))))
          ;; IDLE / not-running — its latest run's outcome.
          :else
          (when closed-run
            (let [reason (:seon.agent.run/closed-reason closed-run)
                  result (:seon.agent.run/result closed-run)
                  rref   (:seon.agent.run/result-ref closed-run)]
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

(def ^:private direct-children-query
  '{:find [?cid ?purpose ?terminated]
    :in [$ ?pid]
    :where [[?p :seon.agent/id ?pid]
            [?c :seon.agent/parent ?p]
            [?c :seon.agent/id ?cid]
            [(get-else $ ?c :seon.agent/purpose "") ?purpose]
            [(get-else $ ?c :seon.agent/terminated-at
                       :seon.agent.ctx.subagents/absent) ?terminated]]
    :order-by [?cid :asc]
    :limit 21})

(def ^:private open-runs-query
  '[:find ?cid ?rid ?limit ?beat ?paused
    :in $ [?cid ...]
    :where
    [?c :seon.agent/id ?cid]
    [?c :seon.agent/run ?run]
    [?run :seon.agent.run/status :open]
    [?run :seon.agent.run/id ?rid]
    [?run :seon.agent.run/turn-limit ?limit]
    [(get-else $ ?run :seon.agent.run/last-beat-at
               :seon.agent.ctx.subagents/absent) ?beat]
    [(get-else $ ?run :seon.agent.run/paused-at
               :seon.agent.ctx.subagents/absent) ?paused]])

(def ^:private open-run-turn-counts-query
  '[:find ?cid (count ?turn)
    :in $ [?cid ...]
    :where
    [?c :seon.agent/id ?cid]
    [?c :seon.agent/run ?run]
    [?run :seon.agent.run/status :open]
    [?turn :seon.agent.turn/run ?run]
    (not [?turn :seon.agent.turn/scheduled? true])])

(def ^:private closed-runs-query
  '[:find ?cid ?rid ?started ?reason ?result ?result-ref
    :in $ [?cid ...]
    :where
    [?c :seon.agent/id ?cid]
    [?run :seon.agent.run/agent ?c]
    [?run :seon.agent.run/id ?rid]
    [?run :seon.agent.run/started-at ?started]
    [?run :seon.agent.run/closed-reason ?reason]
    [(get-else $ ?run :seon.agent.run/result "") ?result]
    [(get-else $ ?run :seon.agent.run/result-ref -1) ?result-ref]])

(def ^:private crash-counts-query
  '[:find ?cid (count ?run)
    :in $ [?cid ...] ?since
    :where
    [?c :seon.agent/id ?cid]
    [?run :seon.agent.run/agent ?c]
    [?run :seon.agent.run/closed-reason :crashed]
    [?run :seon.agent.run/closed-at ?closed]
    [(>= ?closed ?since)]])

(def ^:private breaker-selector
  [:seon.config.breaker/crash-count :seon.config.breaker/window-ms])

(defn- query-member
  ([query-form arguments]
   (query-member query-form arguments 4096 524288))
  ([query-form arguments max-results max-result-weight]
   {::protocol/operation protocol/query-operation
    ::protocol/query-form query-form
    ::protocol/arguments (vec arguments)
    :datahike.resource/max-work 2000000
    :datahike.resource/max-results max-results
    :datahike.resource/max-result-weight max-result-weight}))

(defn- pull-member [selector entity-id]
  {::protocol/operation protocol/pull-operation
   ::protocol/selector selector
   ::protocol/entity-id entity-id
   :datahike.resource/max-work 10000
   :datahike.resource/max-results 8
   :datahike.resource/max-result-weight 1024})

(defn- member-result [member]
  (when (true? (::protocol/success? member))
    (or (::protocol/result member)
        (:datahike.query/result member))))

(defn- latest-closed-runs [rows]
  (->> rows
       (group-by first)
       (map (fn [[child-id child-rows]]
              (let [[_ rid started reason result result-ref]
                    (last (sort-by (juxt #(.getTime ^js (nth % 2)) second)
                                   child-rows))]
                [child-id
                 (cond-> {:seon.agent.run/id rid
                          :seon.agent.run/started-at started
                          :seon.agent.run/closed-reason reason}
                   (seq result) (assoc :seon.agent.run/result result)
                   (not= -1 result-ref)
                   (assoc :seon.agent.run/result-ref result-ref))])))
       (into {})))

(defn- format-subagents-block
  [{::keys [children overflow? open-runs turn-counts closed-runs crash-counts
            now breaker-n breaker-w]
    :seon.agent/keys [id]}]
  (if (empty? children)
    ""
    (let [open-by-id
          (into {}
                (map (fn [[cid rid limit beat paused]]
                       [cid
                        (cond-> {:seon.agent.run/id rid
                                 :seon.agent.run/turn-limit limit
                                 :seon.agent.run/paused-at paused}
                          (not= ::absent beat)
                          (assoc :seon.agent.run/last-beat-at beat))]))
                open-runs)
          turns-by-id (into {} turn-counts)
          closed-by-id (latest-closed-runs closed-runs)
          crashes-by-id (into {} crash-counts)
          lines (map (fn [[cid :as child]]
                       (child-line now child (get open-by-id cid)
                                   (get turns-by-id cid 0)
                                   (get closed-by-id cid)
                                   (get crashes-by-id cid 0)
                                   breaker-n breaker-w))
                     children)
          footer (when overflow?
                   (str "\n; … more children (query [?c :seon.agent/parent "
                        "[:seon.agent/id \"" id "\"]]) "))
          shown-count (count children)
          body (str ";;; SUBAGENTS — the " shown-count
                    (when overflow? "+") " agent"
                    (when (or overflow? (> shown-count 1)) "s") " you spawned\n"
                    "; completion is a FACT in the DB — a child's result "
                    "survives your turns + restarts.\n"
                    (str/join "\n" lines)
                    footer)]
      (tokens/clip-str body section-token-cap))))

(defn ^:async subagents-block
  "The DIRECT children you spawned, one compact line each (Piece 3).

   Empty when you spawned none (the reactive vanish). Per child: id · derived
   state (dot+word) · purpose · and — running: `turn i/limit` + last-beat age;
   idle with a completed latest run: its `:seon.agent.run/result` (+ a ref
   pointer); closed abnormally: the closed-reason (so a dead child is
   visible, not just a succeeded one). A breaker-tripped child shows it.
   Acquisition is bounded at one database value; formatting uses ordinary data."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [{:seon.agent/keys [id] :as input} _invoke-selected!]
  (let [database (or (::db/db input)
                     (::db/db (db/current-tx-context))
                     (await (db/db)))
        stage-one
        (if (:seon.error/message database)
          database
          (await (db/execute-many
                  {::db/db database
                   ::db/members
                   [(query-member direct-children-query [id] 4096 262144)
                    (pull-member breaker-selector
                                 [:seon.config/id config/cluster-config-id])]
                   ::db/max-result-weight 524288})))
        [children-member breaker-member] (::db/results stage-one)
        children-result (member-result children-member)
        breaker-row (or (member-result breaker-member) {})]
    (cond
      (or (not (true? (::protocol/success? children-member)))
          (not (true? (::protocol/success? breaker-member))))
      (str "[subagents] render failed: " (pr-str (::db/results stage-one)))

      (empty? children-result)
      ""

      :else
      (let [overflow? (> (count children-result) max-children)
            children (vec (take max-children children-result))
            child-ids (mapv first children)
            now (js/Date.)
            breaker-n (get breaker-row :seon.config.breaker/crash-count 3)
            breaker-w (get breaker-row :seon.config.breaker/window-ms 1800000)
            since (js/Date. (- (.getTime now) breaker-w))
            stage-two
            (await (db/execute-many
                     {::db/db database
                      ::db/members
                      [(query-member open-runs-query [child-ids])
                       (query-member open-run-turn-counts-query [child-ids])
                       (query-member closed-runs-query [child-ids] 4096 2097152)
                       (query-member crash-counts-query [child-ids since])]
                      ::db/max-result-weight 3670016}))
            members (::db/results stage-two)
            results (when (every? #(true? (::protocol/success? %)) members)
                      (mapv member-result members))]
        (if-not results
          (str "[subagents] render failed: " (pr-str members))
          (let [[open-runs turn-counts closed-runs crash-counts] results]
            (format-subagents-block
              {::children children
               ::overflow? overflow?
               ::open-runs open-runs
               ::turn-counts turn-counts
               ::closed-runs closed-runs
               ::crash-counts crash-counts
               ::now now
               ::breaker-n breaker-n
               ::breaker-w breaker-w
               :seon.agent/id id})))))))

;; ============================================================
;; Piece 4 — orphaned-agents (ROOT-ONLY, wired in :seon.config/root-context).
;; ============================================================

(def ^:private orphan-query
  '[:find ?cid ?pid ?purpose
    :where
    [?c :seon.agent/parent ?p]
    [?p :seon.agent/id ?pid]
    [?p :seon.agent/terminated-at _]
    [?c :seon.agent/id ?cid]
    (not [?c :seon.agent/terminated-at _])
    [(get-else $ ?c :seon.agent/purpose "") ?purpose]])

(def ^:private orphan-open-run-query
  '[:find ?cid ?paused
    :where
    [?c :seon.agent/parent ?p]
    [?p :seon.agent/terminated-at _]
    [?c :seon.agent/id ?cid]
    (not [?c :seon.agent/terminated-at _])
    [?c :seon.agent/run ?run]
    [?run :seon.agent.run/status :open]
    [(get-else $ ?run :seon.agent.run/paused-at :running) ?paused]])

(defn ^:async orphaned-agents-block
  "LIVE agents whose parent is TERMINATED — root cluster only (Piece 4).

   One line each: id · derived state · purpose · parent id. Empty → absent
   (the reactive vanish). No action machinery — root (or the human) decides
   per case with the existing functions (no cascade-terminate, no reparenting:
   observe first). Root-only by config wiring (rides `:seon.config/root-context`,
   like `:core-faults`). Pure read of the db."
  {:malli/schema [:=> [:cat :seon.render/section-request :any] :string]}
  [input _invoke-selected!]
  (let [database (or (::db/db input)
                     (::db/db (db/current-tx-context))
                     (await (db/db)))
        acquired (if (:seon.error/message database)
                   database
                   (await (db/execute-many
                           {::db/db database
                            ::db/members
                            [(query-member orphan-query [])
                             (query-member orphan-open-run-query [])]
                            ::db/max-result-weight 1048576})))
        [orphan-member-result* run-member-result*] (::db/results acquired)
        rows (member-result orphan-member-result*)
        open-runs (member-result run-member-result*)
        open-by-id (into {} open-runs)]
    (cond
      (or (nil? rows) (nil? open-runs))
      (str "[orphaned-agents] render failed: "
           (pr-str (if (:seon.error/message acquired)
                     acquired
                     (::db/results acquired))))

      (empty? rows)
      ""

      :else
      (str ";;; ORPHANED AGENTS — " (count rows)
           " live agent" (when (> (count rows) 1) "s")
           " whose parent is TERMINATED (root-only)\n"
           "; Their parent is dead but they are not — decide per case "
           "(terminate / re-task / leave).\n"
           (str/join
             "\n"
             (map (fn [[cid pid purpose]]
                    (let [paused (get open-by-id cid ::absent)
                          state (cond
                                  (= ::absent paused) :idle
                                  (= :running paused) :running
                                  :else :paused)]
                      (str "; - " (state-dot state) " " cid " [" (name state) "]"
                           (when (and (string? purpose) (seq purpose))
                             (str " " (clip purpose 30)))
                           " · parent " pid " (terminated)")))
                  rows))))))
