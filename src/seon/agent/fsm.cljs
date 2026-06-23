(ns seon.agent.fsm
  "The agent FINITE STATE MACHINE — the wake trigger + the loop.

   The loop is the WHOLE stop policy: each iteration re-reads `{state wake}`
   from the agent record and halts on any of —
     - state ≠ :active                  → external (orchestrator changed it)
     - wake ≠ my-wake                   → superseded (a newer wake owns it)
     - turns-this-wake ≥ effective-cap  → cap (base + inbounds this wake)
     - turn :error                      → error → :idle
     - terminal verb fired (wait/complete left state ≠ :active) → halt
     - zero actionable forms (with empty-streak < 2 thinking guard) → :idle CLEAN
     - else                             → recur.
   Finally: if still :active and my-wake still owns it, set :idle.

   Wake = read-then-write-with-recheck (no atom, no CAS). An inbound datom
   fires the per-tx listener; the handler reads the agent's state from the
   local db snapshot. If wakeable (state ∉ {:active :terminated}), it mints a
   fresh wake-id, transacts `:active` + that wake-id, then starts the loop
   stamped with it. Two simultaneous idle wakes both write :active + their
   wake-id; last-writer-wins; the losing loop re-reads a different wake and
   bails. No double loop, no atom.

   The per-loop cap is a SLIDING WINDOW: `effective-cap = max-turns-per-loop +
   inbounds-during-this-wake`, so every inbound (human OR peer) grants +1
   turn — the agent always gets a turn to SEE and answer a message that
   landed mid-LLM-call. All DERIVED (no stored grant, no cap-note message).

   Requires `seon.agent` (the record schemas + state helpers + the wake gate
   `inbound-msg-datom?`) and `seon.agent.turn` (`run-turn!`). The boot path
   (`seon.client`) requires THIS ns to `install-wake-trigger!`."
  (:require
    [seon.agent :as agent]
    [seon.agent.turn :as turn]
    [seon.db :as db]
    [seon.log :as seon-log]
    [seon.warn :as warn]))

(defn- log [agent-id stage & info]
  (seon-log/info-console!
    (str "seon.agent.fsm/" agent-id)
    stage
    (if (= 1 (count info)) (first info) (vec info))))

;; ============================================================
;; Per-loop cap = SLIDING WINDOW (all DERIVED). turns-this-wake counts the
;; turns stamped with this wake-episode; effective-cap is the base cap plus
;; every inbound (human or peer) that landed since this wake's first turn —
;; so a message that arrives mid-LLM-call always earns a turn to be seen.
;; ============================================================

(def default-max-turns-per-loop
  "Base per-loop turn cap when the agent has no `:seon.agent/max-turns-per-loop`
   attr and `SEON_MAX_TURNS_PER_LOOP` is unset."
  20)

(defn max-turns-per-loop
  "The agent's BASE per-loop cap — `:seon.agent/max-turns-per-loop` on the
   entity, else env `SEON_MAX_TURNS_PER_LOOP`, else
   [[default-max-turns-per-loop]]."
  [id]
  (or (:seon.agent/max-turns-per-loop
        (db/entity {:seon.db/ref [:seon.agent/id id]}))
      (some-> (.. js/process -env -SEON_MAX_TURNS_PER_LOOP)
              js/parseInt
              (#(when-not (js/isNaN %) %)))
      default-max-turns-per-loop))

(defn turns-this-wake
  "How many turns belong to this wake-episode — `count turns where
   :seon.agent.turn/wake = my-wake`. Derived; nothing stored."
  [id my-wake]
  (or (ffirst
        (db/query
          {:seon.db/query
           '[:find (count ?t)
             :in $ ?aid ?wake
             :where
             [?a :seon.agent/id ?aid]
             [?a :seon.agent/sessions ?s]
             [?s :seon.agent.session/turns ?t]
             [?t :seon.agent.turn/wake ?wake]]
           :seon.db/args [id my-wake]}))
      0))

(defn first-turn-at-this-wake
  "The `:at` of the FIRST turn stamped with this wake-episode, or nil when no
   turn has run yet this wake. Bounds the inbound window for the sliding cap."
  [id my-wake]
  (->> (db/query
         {:seon.db/query
          '[:find ?at
            :in $ ?aid ?wake
            :where
            [?a :seon.agent/id ?aid]
            [?a :seon.agent/sessions ?s]
            [?s :seon.agent.session/turns ?t]
            [?t :seon.agent.turn/wake ?wake]
            [?t :seon.agent.turn/at ?at]]
          :seon.db/args [id my-wake]})
       (map first)
       (sort-by #(.getTime ^js %))
       first))

(defn inbounds-during-this-wake
  "Count of inbound messages that grant +1 turn this wake: to ∋ me, from ≠
   me, origin ∈ {:human :agent} (the wake gate's set), hops < `warn/hop-cap`,
   `:at ≥` the first turn of this wake. Before the first turn lands there is
   no window yet ⇒ 0 (the base cap alone bounds the very first turns)."
  [id my-wake]
  (let [my-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]}))
        since  (first-turn-at-this-wake id my-wake)]
    (if (and my-eid since)
      (or (ffirst
            (db/query
              {:seon.db/query
               '[:find (count ?m)
                 :in $ ?me ?cap ?since
                 :where
                 [?m :seon.agent.message/to ?me]
                 [?m :seon.agent.message/from ?f]
                 [(not= ?f ?me)]
                 [(get-else $ ?m :seon.agent.message/hops 0) ?h]
                 [(< ?h ?cap)]
                 ;; origin ∈ {:human :agent} — :core nudges never grant a
                 ;; turn (legacy rows have no origin ⇒ default :human).
                 [(get-else $ ?m :seon.agent.message/origin :human) ?o]
                 [(not= ?o :core)]
                 [?m :seon.agent.message/at ?at]
                 [(>= (.getTime ?at) (.getTime ?since))]]
               :seon.db/args [my-eid warn/hop-cap since]}))
          0)
      0)))

(defn effective-cap
  "The sliding-window per-loop cap: base [[max-turns-per-loop]] +
   [[inbounds-during-this-wake]]. Every inbound (human or peer) extends the
   window so the agent gets a turn to see and answer it."
  [id my-wake]
  (+ (max-turns-per-loop id)
     (inbounds-during-this-wake id my-wake)))

;; ============================================================
;; The loop — re-reads the agent record each iteration; the WHOLE stop
;; policy is the cond below. Finally: if still :active and my-wake still owns
;; it, set :idle.
;; ============================================================

(defn ^:async run-loop!
  "Run agentic turns for `id`, stamped with `my-wake`, until a stop policy
   fires (see the ns doc). `my-wake` is the wake-episode this loop owns; the
   loop bails the moment a newer wake supersedes it. On exit, if the agent is
   still :active under my-wake, it is reset to :idle (so a no-forms/cap halt
   leaves a clean neutral state). Errors are values — never throws into the
   trigger."
  [{:seon.agent/keys [id] :as input} my-wake]
  (try
    (await
      (loop [empty-streak 0]
        (let [ent   (db/entity {:seon.db/ref [:seon.agent/id id]})
              state (:seon.agent/state ent)
              wake  (:seon.agent/wake ent)]
          (cond
            (not= :active state)
            (do (log id "halt" (str "external — state=" state)) :halt-external)

            (not= wake my-wake)
            (do (log id "halt" "superseded — a newer wake owns the agent")
                :halt-superseded)

            (>= (turns-this-wake id my-wake)
                (effective-cap id my-wake))
            (do (log id "halt"
                     (str "cap — " (turns-this-wake id my-wake) "/"
                          (effective-cap id my-wake) " turns this wake"))
                :halt-cap)

            :else
            (let [r      (await (turn/run-turn! input))
                  status (:seon.agent.turn/status r)
                  ;; State AFTER the turn. The turn never writes
                  ;; :seon.agent/state (the loop owns it), so a normal turn
                  ;; leaves it :active; a lifecycle verb (wait/complete/
                  ;; terminate) inside the eval flips it to a parked/terminal
                  ;; value. (:idle here would be an external reset — caught by
                  ;; the top-of-loop :active check next iteration.)
                  after  (:seon.agent/state
                           (db/entity {:seon.db/ref [:seon.agent/id id]}))
                  forms  (or (:seon.agent/eval-count r) 0)]
              (cond
                (= :error status)
                (do (log id "halt" "turn :error → :idle") :halt-error)

                ;; A lifecycle verb (agent/wait / complete) left state in a
                ;; terminal/parked value (:waiting / :completed / :terminated).
                (and (not= :active after) (not= :idle after))
                (do (log id "halt" (str "verb — state=" after)) :halt-verb)

                (zero? forms)
                (if (< empty-streak 2)
                  (recur (inc empty-streak)) ; thinking-mode guard
                  (do (log id "halt" "no actionable forms → :idle (clean)")
                      :halt-quiet))

                :else
                (recur 0)))))))
    (catch :default e
      (log id "loop error" (str e))
      :halt-throw)
    (finally
      ;; If still :active under MY wake, this loop owns the reset to :idle.
      ;; A superseded loop leaves the winner's :active alone (its wake ≠
      ;; my-wake); a verb-halt already moved state off :active.
      (let [ent (db/entity {:seon.db/ref [:seon.agent/id id]})]
        (when (and (= :active (:seon.agent/state ent))
                   (= my-wake (:seon.agent/wake ent)))
          (await (agent/set-state! {:seon.agent/id id :seon.agent/state :idle})))))))

;; ============================================================
;; Wake — the per-tx listener fires on every transact; we filter for new
;; INBOUND messages (to ∋ me, from ≠ me, waking origin — agent/inbound-msg-datom?)
;; and, if the agent is wakeable, mint a fresh wake-id, flip to :active, and
;; start a loop stamped with it. NO atom: optimistic concurrency via the DB.
;;
;; Hop guard lives HERE (at wake): a message whose hops reached warn/hop-cap
;; wakes nothing — loud console.error + the clustered check-hop-exhausted
;; warning surface the refusal.
;; ============================================================

(defn wake-handler
  "Return the tx-listener handler for `input`'s agent. On an inbound datom
   that passes [[seon.agent/inbound-msg-datom?]], read the agent's state from
   the local db snapshot; if wakeable (state ∉ {:active :terminated}), mint a
   fresh wake, flip to :active, and start `run-loop!` stamped with that wake.
   No atom — two idle wakes race in the DB and the losing loop re-reads a
   different wake and bails."
  [{:seon.agent/keys [id] :as input}]
  (fn [{:seon.db/keys [db attr-index]}]
    (let [my-eid  (:db/id (db/entity {:seon.db/db db
                                      :seon.db/ref [:seon.agent/id id]}))
          inbound (when my-eid
                    (->> (:seon.agent.message/to attr-index)
                         (filter :seon.db/added?)
                         (filter #(agent/inbound-msg-datom? db % my-eid))))
          {waking false exhausted true}
          (group-by (fn [{eid :seon.db/e}]
                      (>= (or (:seon.agent.message/hops
                                (db/entity {:seon.db/db db :seon.db/ref eid}))
                              0)
                          warn/hop-cap))
                    inbound)]
      ;; Hop guard AT wake — refuse, loudly. The message stays in the DB
      ;; (check-hop-exhausted renders it); the loop does NOT start for it.
      (doseq [{eid :seon.db/e} exhausted]
        (let [msg (db/entity {:seon.db/db db :seon.db/ref eid})]
          (js/console.error
            (str "seon.agent.fsm: WAKE REFUSED for agent " id
                 " — message " (:seon.agent.message/id msg)
                 " hops=" (:seon.agent.message/hops msg)
                 " reached hop-cap " warn/hop-cap
                 " (agent↔agent ping-pong guard). A human message"
                 " resets the chain (hops 0)."))))
      (when (seq waking)
        (let [state (:seon.agent/state
                      (db/entity {:seon.db/db db
                                  :seon.db/ref [:seon.agent/id id]}))]
          (if (contains? #{:active :terminated} state)
            ;; Not wakeable. :active → the running loop's sliding cap picks
            ;; up the message; :terminated → unwakeable until state changes.
            (log id "wake skipped"
                 (str "state=" state " (not wakeable — "
                      (if (= :active state)
                        "running loop's sliding cap covers it"
                        "terminated; change state first")
                      ")"))
            ;; Wakeable: mint a fresh wake, flip to :active, start the loop.
            ;; setTimeout breaks the ALS scope — re-enter `with-agent` so the
            ;; loop's downstream calls see (db/current-agent-id).
            (js/setTimeout
              (fn []
                (-> (js/Promise.resolve
                      (db/with-agent id
                        (fn ^:async wake! []
                          (let [wake (await (agent/fresh-wake! {:seon.agent/id id}))]
                            (await (agent/set-state!
                                     {:seon.agent/id id :seon.agent/state :active}))
                            (await (run-loop! input wake))))))
                    (.catch (fn [e]
                              (js/console.error
                                (str "seon.agent.fsm: wake loop threw for "
                                     id ": " (or (.-message e) e)))))))
              0)))))))

(defn install-wake-trigger!
  "Register the inbound-message trigger for this agent — wakes a loop via
   [[wake-handler]] when a message lands with to ∋ me AND from ≠ me AND a
   waking origin. Idempotent: unlistens any prior handler for the same
   agent-id first, so hot-reload doesn't leave stale closures wired to the
   tx bus.

   Input map:
     :seon.agent/id              the agent's id string
     :seon.agent/llm-fn          ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state   bootstrap compile-state"
  [{:seon.agent/keys [id] :as input}]
  ;; One stable listener key per agent so re-arming REPLACES the prior
  ;; listener (a hot reload must not leave two listeners firing for one
  ;; agent).
  (let [k [:seon.agent/user-message-trigger id]]
    (try (db/unlisten! {:seon.db/key k}) (catch :default _ nil))
    (db/listen!
      {:seon.db/key     k
       :seon.db/handler (wake-handler input)})))
