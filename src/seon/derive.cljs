(ns seon.derive
  "The ONE leaf of DB-derived projections — every pure read that turns a db
   value + an agent/run id into derived state lives HERE, exactly once.

   Why a leaf: the agent's derived state, turn counts, current-run, and the
   armable roster were each re-implemented 5+ times across `seon.agent`,
   `seon.agent.ctx`, `seon.render.default`, `seon.agent.run`, and
   `seon.agent.schedule`, every copy justified by dodging the
   `agent → ctx → render` require cycle. A pure `(derive-x db id)` needs
   NOTHING from those namespaces — only `seon.db` to read and `seon.schema`
   to name the shapes — so it sits BELOW all of them and the cycle evaporates.
   Every consumer requires THIS ns and passes the db value it already holds.

   The ONE state rule is [[state-from-primitives]] (terminated → :idle →
   :paused → :running); [[derive-state]] reads the primitives off a db and
   applies it; [[armable-agent-ids]] / [[agent-idle?]] are FILTERS over it,
   never re-encodings — so the rule cannot drift.

   Every fn takes an EXPLICIT db value (the basis-t the caller is reading
   against). A caller that holds no db threads `@seon.db/*conn*` at the call
   site (the wrappers in `seon.agent`/`seon.agent.run` do exactly that).

   Dependency direction (acyclic): requires ONLY `seon.db` + `seon.schema`.
   It MUST NOT require `seon.agent`, `seon.agent.ctx`, `seon.render.*`, or
   `seon.agent.loop` — that is the whole point."
  (:require
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; The derived state enum + the ONE projection rule. State is DERIVED from
;; primitives, never stored: presence of `:seon.agent/terminated-at` ⇒
;; :terminated; no open run ⇒ :idle; an open run carrying
;; `:seon.agent.run/paused-at` ⇒ :paused; else :running.
;; ============================================================

(schema/register! :seon.derive/state
  [:enum :idle :running :paused :terminated])

;; The three primitives `state-from-primitives` projects. Keys carry the real
;; attr names; their VALUE schemas are base types so this shape has no
;; load-time dependency on the attr registrations in seon.agent /
;; seon.agent.run. `open?` is "is there an open run".
(schema/register! :seon.derive/primitives
  [:map
   [:seon.agent/terminated-at {:optional true} :inst]
   [:seon.agent.run/open?     {:optional true} :boolean]
   [:seon.agent.run/paused-at {:optional true} :inst]])

(defn state-from-primitives
  "THE state rule (pure): project the three primitives onto the derived state
   keyword. The caller reads the primitives from a db and hands them in;
   `:seon.agent/terminated-at` present ⇒ :terminated; no open run ⇒ :idle; an
   open run with `:seon.agent.run/paused-at` ⇒ :paused; else :running."
  {:malli/schema [:=> [:catn [:seon.derive/primitives :seon.derive/primitives]]
                  :seon.derive/state]}
  [{:seon.agent/keys [terminated-at]
    open?     :seon.agent.run/open?
    paused-at :seon.agent.run/paused-at}]
  (cond
    terminated-at :terminated
    (not open?)   :idle
    paused-at     :paused
    :else         :running))

;; ============================================================
;; Reads — each takes an EXPLICIT db value + an id, sync over that value.
;; ============================================================

(defn current-run
  "The agent's CURRENT open run as a plain touched map (the `:seon.agent/run`
   pointer, resolved to its run entity, returned only when that run is
   `:open`), or nil. Drill its refs via follow-up reads."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :seon.agent/id]]
                  [:maybe :map]]}
  [db agent-id]
  (let [a       (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id agent-id]})
        run-eid (:db/id (:seon.agent/run a))]
    (when run-eid
      (let [r (db/entity {:seon.db/db db :seon.db/ref run-eid})]
        (when (= :open (:seon.agent.run/status r)) r)))))

(defn derive-state
  "The agent's DERIVED FSM state (:idle/:running/:paused/:terminated) over the
   db value `db` — reads `terminated-at`, whether it has an OPEN run
   ([[current-run]]), and that run's `paused-at`, then applies
   [[state-from-primitives]]. The ONE reader the readline / inspector / loop /
   wake gate share."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :seon.agent/id]]
                  :seon.derive/state]}
  [db agent-id]
  (let [a   (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id agent-id]})
        run (current-run db agent-id)]
    (state-from-primitives
      (cond-> {:seon.agent.run/open? (some? run)}
        (:seon.agent/terminated-at a)   (assoc :seon.agent/terminated-at
                                               (:seon.agent/terminated-at a))
        (:seon.agent.run/paused-at run) (assoc :seon.agent.run/paused-at
                                               (:seon.agent.run/paused-at run))))))

(defn run-turn-count
  "How many WORK turns are stamped with the run identified by `run-id` (the
   run's derived current-turn — the WORK budget the loop checks against
   turn-limit). Counts `:seon.agent.turn/run` datoms over the explicit db value
   but EXCLUDES schedule-fire turns (`:seon.agent.turn/scheduled? true`): a cron
   fire opens a turn so its eval RENDERS in the transcript, yet it is not an LLM
   drive and must never burn a turn from the work budget. No installed-schema
   gate (the loop is the active model; `:seon.agent.turn/run` is always
   registered by boot)."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent.run/id :seon.agent.run/id]]
                  :int]}
  [db run-id]
  (or (db/query {:seon.db/db db
                 :seon.db/query
                 '[:find (count ?t) . :in $ ?rid
                   :where
                   [?r :seon.agent.run/id ?rid]
                   [?t :seon.agent.turn/run ?r]
                   (not [?t :seon.agent.turn/scheduled? true])]
                 :seon.db/args [run-id]})
      0))

(defn agent-turn-count
  "Turn count for an agent across ALL its runs — agent ← run
   (`:seon.agent.run/agent`) ← turn (`:seon.agent.turn/run`), counted over the
   explicit db value. Query-based (not reverse-ref nav) so it works on a plain
   pulled/touched agent map and on a FilteredDB."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :seon.agent/id]]
                  :int]}
  [db agent-id]
  (or (db/query {:seon.db/db db
                 :seon.db/query
                 '[:find (count ?t) . :in $ ?aid
                   :where
                   [?a :seon.agent/id ?aid]
                   [?r :seon.agent.run/agent ?a]
                   [?t :seon.agent.turn/run ?r]]
                 :seon.db/args [agent-id]})
      0))

(defn last-beat
  "The run's heartbeat instant (`:seon.agent.run/last-beat-at`) over `db`, or
   nil when the run never beat / doesn't resolve."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent.run/id :seon.agent.run/id]]
                  [:maybe :inst]]}
  [db run-id]
  (:seon.agent.run/last-beat-at
    (db/entity {:seon.db/db db :seon.db/ref [:seon.agent.run/id run-id]})))

(defn agent-idle?
  "Is the agent DERIVED `:idle` (wakeable — not terminated, no open run)? A
   FILTER over [[derive-state]], never a re-encoding of the rule."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :seon.agent/id]]
                  :boolean]}
  [db agent-id]
  (= :idle (derive-state db agent-id)))

(defn armable-agent-ids
  "Agent ids whose DERIVED state is `:idle` — the agents a trigger can WAKE.
   A FILTER over [[derive-state]] (one rule, never a re-encoded idle query),
   sorted asc for deterministic boot logs."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]] [:vector :seon.agent/id]]}
  [db]
  (->> (db/query {:seon.db/db db
                  :seon.db/query '[:find [?id ...] :where [?a :seon.agent/id ?id]]})
       (filter #(= :idle (derive-state db %)))
       sort
       vec))

;; ============================================================
;; Error-storm detection — a DERIVED health signal. An agent thrashing on
;; broken evals (a burst of consecutive failures, or a majority-failing
;; recent window) is a QUERY of the recent eval log, never a stored counter,
;; so it VANISHES the moment the agent's next evals succeed (the window
;; slides past the failures). Drives BOTH the human header signal
;; (`seon.ui.header`) and the agent-facing `seon.warn` check — one rule, two
;; surfaces. CONTENT-FREE noise evals (empty / closing-delimiter-only source
;; — a segmentation artifact that defined nothing) are excluded so a
;; mis-split `}` never reads as thrash.
;; ============================================================

(def error-storm-window
  "How many of the agent's most-recent REAL evals the rate test examines." 8)

(def error-storm-min-fail
  "Absolute failure floor — below this no rate storm flags (a 2/3 window is
   not a storm)." 4)

(def error-storm-consec
  "Trailing consecutive REAL-eval failures that flag a storm regardless of
   rate (the agent is stuck repeating one broken form)." 4)

(schema/register! :seon.derive/error-storm
  [:map
   [:seon.agent/id      :string]   ; the thrashing agent
   [:seon.derive/failed :int]      ; failures within the examined window
   [:seon.derive/window :int]      ; real evals examined (≤ error-storm-window)
   [:seon.derive/consec :int]])    ; trailing consecutive failures

(defn- real-eval-oks
  "The agent's REAL evals as a vec of `ok?` booleans, oldest→newest.
   CONTENT-FREE noise (blank / closing-delimiter-only source) is excluded —
   it is a segmentation artifact, not a sign the agent is thrashing."
  [db agent-id]
  (->> (db/query
         {:seon.db/db db
          :seon.db/query
          '[:find ?at ?ok ?src
            :in $ ?id
            :where
            [?a :seon.agent/id ?id]
            [?r :seon.agent.run/agent ?a]
            [?t :seon.agent.turn/run ?r]
            [?t :seon.agent.turn/evals ?e]
            [?e :seon.eval/at ?at]
            [?e :seon.eval/ok? ?ok]
            [(get-else $ ?e :seon.eval/source "") ?src]]
          :seon.db/args [agent-id]})
       (sort-by first)
       (remove (fn [[_ _ src]]
                 (let [s (clojure.string/trim (str src))]
                   (or (clojure.string/blank? s)
                       (boolean (re-matches #"[)\]}]+" s))))))
       (mapv (fn [[_ ok _]] (boolean ok)))))

(defn error-storm
  "nil, or an `:seon.derive/error-storm` map when agent `agent-id` is
   thrashing: among its last [[error-storm-window]] REAL evals MORE THAN HALF
   failed (and ≥[[error-storm-min-fail]] absolute), OR its last
   [[error-storm-consec]]+ real evals ALL failed. Pure read of the recent
   eval log — self-heals as soon as new evals succeed."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :seon.agent/id]]
                  [:maybe :seon.derive/error-storm]]}
  [db agent-id]
  (let [oks    (real-eval-oks db agent-id)
        recent (vec (take-last error-storm-window oks))
        n      (count recent)
        failed (count (remove true? recent))
        consec (count (take-while false? (reverse oks)))]
    (when (or (>= consec error-storm-consec)
              (and (>= failed error-storm-min-fail)
                   (> failed (quot n 2))))
      {:seon.agent/id      agent-id
       :seon.derive/failed failed
       :seon.derive/window n
       :seon.derive/consec consec})))

(defn error-storms
  "Every agent currently in an error storm, as `:seon.derive/error-storm`
   maps (empty when the fleet is healthy) — ONE derived read shared by the
   human header signal and the agent-facing warn check. No stored counters:
   a storm clears itself the moment the agent's evals recover."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]]
                  [:vector :seon.derive/error-storm]]}
  [db]
  (->> (db/query {:seon.db/db db
                  :seon.db/query '[:find [?id ...] :where [?a :seon.agent/id ?id]]})
       (keep #(error-storm db %))
       (sort-by :seon.agent/id)
       vec))

;; ============================================================
;; The agent FINGERPRINT — one map of the whole derived state. A pure DERIVED
;; READ (no writes); state via [[state-from-primitives]], run/turn/todo fields
;; via cheap queries. Run-scoped fields are present only while a run is open.
;; One `@*conn*` deref is threaded through every read so the fingerprint is
;; one db view.
;; ============================================================

(defn- total-turns
  "Count of every turn the agent owns across all its runs (= [[agent-turn-count]])."
  [db id]
  (agent-turn-count db id))

(defn- open-todo-count
  "Count of the agent's open LEAF todos — the real work still owed. With
   `my.todo` trees, milestone PARENTS stay stored `:open` (their done-ness is
   DERIVED — done once every child is), so a flat `:status :open` count
   over-counts them. Counting only leaves (no child names it as parent — the
   `leaf` predicate from `seon.agent.todo/rules`, inlined to keep this ns's
   acyclic seon.db/seon.schema-only dependency) yields the actionable work.
   Blocked-but-open leaves are still owed, so they count (use `next` for the
   READY-only focus queue)."
  [db id]
  (or (db/query {:seon.db/db db
                 :seon.db/query
                 '[:find (count ?todo) . :in $ ?aid
                   :where
                   [?a :seon.agent/id ?aid]
                   [?todo :seon.agent.todo/owner ?a]
                   [?todo :seon.agent.todo/status :open]
                   (not-join [?todo] [?child :seon.agent.todo/parent ?todo])]
                 :seon.db/args [id]})
      0))

(defn- last-human-inbound-at
  "The `:at` of the latest inbound `:human`-origin message to the agent, or
   nil when none."
  [db id]
  (let [my-eid (:db/id (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]}))]
    (when my-eid
      (->> (db/query {:seon.db/db db
                      :seon.db/query
                      '[:find ?at :in $ ?me
                        :where
                        [?m :seon.agent.message/to ?me]
                        [?m :seon.agent.message/origin :human]
                        [?m :seon.agent.message/at ?at]]
                      :seon.db/args [my-eid]})
           (map first)
           (sort-by #(.getTime ^js %))
           last))))

(defn last-closed-reason
  "The `closed-reason` of the agent's most-recently-STARTED closed run, or nil
   when none. Gated on the attr being installed."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :seon.agent/id]]
                  [:maybe :seon.agent.run/closed-reason]]}
  [db id]
  (when (contains? (db/installed-schema db) :seon.agent.run/closed-reason)
    (->> (db/query {:seon.db/db db
                    :seon.db/query
                    '[:find ?reason ?started :in $ ?aid
                      :where
                      [?a :seon.agent/id ?aid]
                      [?r :seon.agent.run/agent ?a]
                      [?r :seon.agent.run/closed-reason ?reason]
                      [?r :seon.agent.run/started-at ?started]]
                    :seon.db/args [id]})
         (sort-by #(.getTime ^js (second %)))
         last
         first)))

(schema/register! :seon.derive/status-request
  [:map
   ;; Leaf-purity (see note below): this is a LOAD-TIME register! in a leaf ns
   ;; — seon.agent requires THIS, so `:seon.agent/id` is NOT registered yet at
   ;; cold boot and can't be referenced here. The base type `:string` still
   ;; admits the literal "root" id (the orchestrator-root); the 14-char minted
   ;; shape is enforced where the agent is CREATED (`:seon.agent/id` itself).
   ;; This is a derived READ. The defn `:malli/schema` slots below (resolved at
   ;; instrumentation time, after seon.agent loads) DO reference `:seon.agent/id`.
   [:seon.agent/id  :string]
   ;; optional explicit wall-clock for ms-remaining (defaults to (js/Date.))
   [:seon.agent/now {:optional true} :inst]])

;; Leaf-purity: the run/* + message/* field KEYS are the real attr names, but
;; their VALUE schemas are BASE TYPES (not the registered `:seon.agent.run/*`
;; enums) so this register! has NO load-time dependency on seon.agent.run's
;; registrations — run requires THIS leaf, so it loads AFTER. The precise enums
;; are validated where the run is WRITTEN; this is a derived read. (Same rule
;; `:seon.derive/primitives` follows.)
(schema/register! :seon.derive/status
  [:map
   [:seon.agent/state                 :seon.derive/state]   ; DERIVED enum
   [:seon.agent/total-turns           :int]
   [:seon.agent.todo/open-count       :int]
   [:seon.agent.run/status        {:optional true} :keyword]   ; :open/:closed
   [:seon.agent.run/trigger       {:optional true} :keyword]   ; :message/:schedule
   [:seon.agent.run/turn-limit    {:optional true} :int]
   [:seon.agent.run/deadline      {:optional true} :inst]
   [:seon.agent.run/last-beat-at  {:optional true} :inst]
   [:seon.agent.run/closed-reason {:optional true} :keyword]   ; closed-reason enum
   [:seon.agent.run/turn            {:optional true} :int]     ; derived current-turn
   [:seon.agent.run/turns-remaining {:optional true} :int]
   [:seon.agent.run/ms-remaining    {:optional true} :int]
   [:seon.agent.message/last-human-at {:optional true} :inst]])

(defn derive-status
  "The agent's full DERIVED status in one map (map-in / map-out). A pure
   DERIVED READ — no writes. State comes from [[state-from-primitives]] over
   the primitives; run/turn/todo fields derive from cheap queries against ONE
   threaded db value. Run-scoped fields are present only while there IS an open
   run; `:seon.agent/now` (optional) fixes the clock for `ms-remaining`."
  {:malli/schema [:=> [:cat :seon.derive/status-request] :seon.derive/status]}
  [{id :seon.agent/id now :seon.agent/now}]
  (let [now           (or now (js/Date.))
        db            @db/*conn*
        a             (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})
        terminated-at (:seon.agent/terminated-at a)
        cur           (current-run db id)
        paused-at     (:seon.agent.run/paused-at cur)
        state         (state-from-primitives
                        (cond-> {:seon.agent.run/open? (some? cur)}
                          terminated-at (assoc :seon.agent/terminated-at terminated-at)
                          paused-at     (assoc :seon.agent.run/paused-at paused-at)))
        last-human    (last-human-inbound-at db id)
        last-closed   (last-closed-reason db id)
        base          (cond-> {:seon.agent/state           state
                               :seon.agent/total-turns     (total-turns db id)
                               :seon.agent.todo/open-count (open-todo-count db id)}
                        last-human  (assoc :seon.agent.message/last-human-at last-human)
                        last-closed (assoc :seon.agent.run/closed-reason last-closed))]
    (if-not cur
      base
      (let [run-id     (:seon.agent.run/id cur)
            turn-cnt   (run-turn-count db run-id)
            turn-limit (:seon.agent.run/turn-limit cur)
            deadline   (:seon.agent.run/deadline cur)
            last-beat  (:seon.agent.run/last-beat-at cur)]
        (cond-> (assoc base
                       :seon.agent.run/status          (:seon.agent.run/status cur)
                       :seon.agent.run/trigger         (:seon.agent.run/trigger cur)
                       :seon.agent.run/turn-limit      turn-limit
                       :seon.agent.run/deadline        deadline
                       :seon.agent.run/turn            turn-cnt
                       :seon.agent.run/turns-remaining (max 0 (- turn-limit turn-cnt)))
          last-beat (assoc :seon.agent.run/last-beat-at last-beat)
          deadline  (assoc :seon.agent.run/ms-remaining
                           (if paused-at
                             ;; Paused: the wall clock is FROZEN — surface the
                             ;; budget banked at pause time (`remaining-ms`),
                             ;; not deadline−now (which keeps decaying / goes
                             ;; negative while the run is held).
                             (or (:seon.agent.run/remaining-ms cur) 0)
                             (- (.getTime ^js deadline) (.getTime ^js now)))))))))
