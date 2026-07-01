(ns seon.agent.run
  "The RUN entity + its lifecycle — a run is the bounded unit of work a
   trigger (an inbound message or a due schedule) opens. While a run is open
   the agent is derived-`:running`; the loop drives turns until a BOUND fires:
   the WORK bound (`turn-limit`, a bumpable count) or the WALL-CLOCK bound
   (`deadline`, an absolute instant) — whichever first.

   The run-id is the FENCING TOKEN: the agent's `:seon.agent/run` points at
   the current run, and every WORK tx LEADS with an in-tx CAS
   ([[seon.db/cas-assert]]) asserting the pointer STILL names this run — a
   write from a superseded or timed-out run (a different run-id, or a
   retracted pointer) aborts the whole tx at the single writer (the database,
   not a pre-read predicate, reports the lost authority). New messages
   `renew!` the lease (bump both bounds — the sliding window); `beat!` writes
   a heartbeat per turn.

   This namespace OWNS the `:seon.agent.run/*` schemas and the lifecycle:
   `open-run!` / `close-run!` / `renew!` / `beat!` / `current-run` /
   `snapshot` / `turn-limit-reached?` / `deadline-passed?` /
   `close-overdue-runs!` — the deadline WATCHDOG, the wall-clock half of the
   one ticker ([[seon.agent.loop/install-ticker!]]; the schedule half is
   `seon.agent.schedule/fire-due-schedules!`).

   Dependency direction (acyclic): it transacts via `seon.db` directly and
   references `:seon.agent/*` keywords from the global registry (no require —
   the id slot is typed `:seon.db/id`, not `:seon.agent/id`, so this ns has NO
   load-time dependency on seon.agent). It requires the [[seon.derive]] leaf so
   `current-run` is a thin `*conn*` adapter over the one derivation; seon.agent
   requires THIS ns, so the edge runs agent → run → derive, never the reverse."
  (:require
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.schema :as schema]))

;; ============================================================
;; Schema — the run entity, by owning namespace. Identity/ref shapes
;; reference the canonical :seon.db/id / :seon.db/ref (never inlined).
;; ============================================================

(schema/register! :seon.agent.run/id         [:and {:seon.db/identity true} :seon.db/id]) ; the FENCING TOKEN
(schema/register! :seon.agent.run/agent      :seon.db/ref)   ; back-ref → agent
(schema/register! :seon.agent.run/started-at :inst)          ; the wake time
(schema/register! :seon.agent.run/trigger    [:enum :message :schedule])
(schema/register! :seon.agent.run/cause      :seon.db/ref)   ; → the waking message (when :message)
(schema/register! :seon.agent.run/turn-limit :int)           ; WORK-QUANTITY bound (bumpable)
(schema/register! :seon.agent.run/deadline   :inst)          ; WALL-CLOCK bound (absolute)
(schema/register! :seon.agent.run/last-beat-at :inst)        ; heartbeat (liveness; per turn)
;; Pause marker: presence on the OPEN run ⇒ derived state :paused (read by
;; seon.derive/derive-state via the agent's primitives).
(schema/register! :seon.agent.run/paused-at  :inst)
;; Wall-clock budget banked at pause time (deadline − now). `resume!` re-extends
;; `deadline` by this so a long pause never blows the clock bound the instant
;; you wake the run (Gemini fix #1 — pause vs absolute deadline).
(schema/register! :seon.agent.run/remaining-ms :int)
(schema/register! :seon.agent.run/status     [:enum :open :closed])
;; Present iff :closed. :no-forms = the loop's empty-streak halt (the LLM
;; produced no actionable forms for a streak of turns — a clean close to
;; :idle, NOT a bound overrun). :crashed = a boot-recovery close of a run
;; orphaned by a pod crash (architecture crash-recovery), beyond the spec's
;; clean reasons.
(schema/register! :seon.agent.run/closed-reason
                  [:enum :completed :waited :turn-limit :deadline-exceeded
                         :terminated :superseded :error :no-forms :crashed])

;; Stored entity kind — required attrs reflect what `open-run!` writes
;; unconditionally; everything conditional is {:optional true} (absent = no
;; key, never nil).
(schema/register! :seon.agent.run
  [:map {:seon.db/entity true}
   [:seon.agent.run/id            :seon.agent.run/id]
   [:seon.agent.run/agent         :seon.agent.run/agent]
   [:seon.agent.run/started-at    :seon.agent.run/started-at]
   [:seon.agent.run/trigger       :seon.agent.run/trigger]
   [:seon.agent.run/status        :seon.agent.run/status]
   [:seon.agent.run/turn-limit    :seon.agent.run/turn-limit]
   [:seon.agent.run/deadline      :seon.agent.run/deadline]
   [:seon.agent.run/cause         {:optional true} :seon.agent.run/cause]
   [:seon.agent.run/last-beat-at  {:optional true} :seon.agent.run/last-beat-at]
   [:seon.agent.run/paused-at     {:optional true} :seon.agent.run/paused-at]
   [:seon.agent.run/remaining-ms  {:optional true} :seon.agent.run/remaining-ms]
   [:seon.agent.run/closed-reason {:optional true} :seon.agent.run/closed-reason]])

;; Slot schemas for the positional predicates below (registered so the
;; :catn labels resolve — the shared-shape / named-arg conventions).
(schema/register! :seon.agent.run/turn-count :int)   ; derived current-turn count
(schema/register! :seon.agent.run/now        :inst)  ; an explicit wall-clock instant

;; ============================================================
;; Defaults — seed a run's two bounds when the agent carries no override.
;; ============================================================

(def default-turn-limit
  "Work-bound seed when the agent has no `:seon.agent/default-turn-limit`."
  20)

(def default-deadline-ms
  "Wall-clock-bound seed (10 min) when the agent has no
   `:seon.agent/default-deadline-ms`. Generous on purpose — the turn-limit is
   the usual stopper; the deadline catches a stalled LLM."
  (* 10 60 1000))

;; ============================================================
;; Config-driven agent-init CP-1 — run-bound SEED attrs (agent-level).
;; These seed a run's own live bumpable bounds; nothing reads them yet
;; (purely additive). ::default-deadline-ms default = the live
;; [[default-deadline-ms]] const (600000 = 10 min).
;; ============================================================

(schema/register! ::default-turn-limit  [:int {:default 20 :min 1}])
(schema/register! ::default-deadline-ms [:int {:default 600000 :min 1}])

;; ============================================================
;; Process-run set — the run-ids THIS pod process opened. `defonce` so a
;; hot reload (same process) keeps it; empty on a fresh Node boot. The
;; transcript marks evals from runs NOT in this set as `::prior?` (their
;; in-memory `result/<id>` vars died with the previous process) — the
;; run-grained successor to the old `!sessions-opened-this-run`.
;; ============================================================

(defonce ^:private !runs-this-process (atom #{}))

(defn this-process-run?
  "True iff `run-id` was opened by THIS pod process (its `result/<id>` eval
   vars are live in the current runtime). False for a run reconstructed from
   the store on a prior boot."
  {:malli/schema [:=> [:catn [:seon.agent.run/id :seon.agent.run/id]] :boolean]}
  [run-id]
  (contains? @!runs-this-process run-id))

;; ============================================================
;; Reads — sync over the local db value.
;; ============================================================

(schema/register! :seon.agent.run/snapshot
  ;; The run's fingerprint — a plain data view (the internal ref pointers
  ;; agent/cause are omitted; readers drill via the run-id).
  [:map
   [:seon.agent.run/id            :seon.agent.run/id]
   [:seon.agent.run/status        :seon.agent.run/status]
   [:seon.agent.run/trigger       :seon.agent.run/trigger]
   [:seon.agent.run/started-at    :seon.agent.run/started-at]
   [:seon.agent.run/turn-limit    :seon.agent.run/turn-limit]
   [:seon.agent.run/deadline      :seon.agent.run/deadline]
   [:seon.agent.run/last-beat-at  {:optional true} :seon.agent.run/last-beat-at]
   [:seon.agent.run/paused-at     {:optional true} :seon.agent.run/paused-at]
   [:seon.agent.run/closed-reason {:optional true} :seon.agent.run/closed-reason]])

(schema/register! ::snapshot-request [:map [:seon.agent.run/id :seon.agent.run/id]])

(defn snapshot
  "The run's fingerprint as a plain map (or nil if the run-id doesn't
   resolve). Ref pointers (agent/cause) are dropped — they're internal."
  {:malli/schema [:=> [:cat ::snapshot-request] [:maybe :seon.agent.run/snapshot]]}
  [{run-id :seon.agent.run/id}]
  (when-let [r (db/entity {:seon.db/ref [:seon.agent.run/id run-id]})]
    (select-keys r [:seon.agent.run/id :seon.agent.run/status :seon.agent.run/trigger
                    :seon.agent.run/started-at :seon.agent.run/turn-limit
                    :seon.agent.run/deadline :seon.agent.run/last-beat-at
                    :seon.agent.run/paused-at :seon.agent.run/closed-reason])))

;; The agent-id VALUE schema in these request maps is base `:string`, NOT
;; `:seon.agent/id`: every register! here is LOAD-TIME, and seon.agent.run is a
;; LEAF (requires only db/derive/schema) that loads BEFORE seon.agent registers
;; `:seon.agent/id` — referencing it at cold boot throws. `:string` also admits
;; the literal "root" orchestrator-root id (the 14-char minted shape is enforced
;; at CREATE, via `:seon.agent/id` itself); these are run OPS over an existing id.
(schema/register! ::current-run-request [:map [:seon.agent/id :string]])

(defn current-run
  "The agent's CURRENT open run entity (the `:seon.agent/run` pointer, if it
   resolves to an `:open` run), or nil. A plain touched map; drill its refs
   via follow-up reads. A `*conn*`-reading map-in convenience over the one
   derivation leaf [[seon.derive/current-run]] — callers that already hold a
   db value call `seon.derive/current-run` directly with it."
  {:malli/schema [:=> [:cat ::current-run-request] [:maybe :map]]}
  [{id :seon.agent/id}]
  (derive/current-run @db/*conn* id))

(defn turn-limit-reached?
  "Work bound: has `turn-count` reached `turn-limit`? Pure — the caller
   passes the derived current-turn count, so this is wall-clock-free."
  {:malli/schema [:=> [:catn [:seon.agent.run/turn-count :seon.agent.run/turn-count]
                             [:seon.agent.run/turn-limit :seon.agent.run/turn-limit]]
                  :boolean]}
  [turn-count turn-limit]
  (>= turn-count turn-limit))

(defn deadline-passed?
  "Wall-clock bound: is `now` past `deadline`? Pure — the caller passes the
   instant, so it's testable without the system clock."
  {:malli/schema [:=> [:catn [:seon.agent.run/deadline :seon.agent.run/deadline]
                             [:seon.agent.run/now :seon.agent.run/now]]
                  :boolean]}
  [deadline now]
  (> (.getTime now) (.getTime deadline)))

;; ============================================================
;; Writes — fencing-guarded lifecycle. Each WORK tx LEADS with an in-tx CAS
;; ([[seon.db/cas-assert]]) asserting the agent's `:seon.agent/run` STILL names
;; this run-id; if it moved (superseded) or was retracted (watchdog-closed /
;; timed-out) the whole tx aborts at the writer — the CAS-fail envelope IS the
;; fencing error (no pre-read predicate). Errors are VALUES, never throws.
;; ============================================================

(defn- run-fence
  "The leading work-fence CAS op (DATA): assert the agent `id`'s
   `:seon.agent/run` pointer STILL names `run-id`. Lead a work-tx with this and
   the tx commits iff the pointer is unchanged; a moved/retracted pointer aborts
   the WHOLE tx (`:transact/cas`) → the `{:seon.db/ok? false}` envelope."
  [id run-id]
  (db/cas-assert [:seon.agent/id id] :seon.agent/run [:seon.agent.run/id run-id]))

(schema/register! ::open-run-request
  [:map
   [:seon.agent/id              :string]
   [:seon.agent.run/trigger     :seon.agent.run/trigger]
   [:seon.agent.run/cause       {:optional true} :seon.db/ref]
   [:seon.agent.run/turn-limit  {:optional true} :seon.agent.run/turn-limit]
   [:seon.agent.run/deadline    {:optional true} :seon.agent.run/deadline]])

(defn ^:async open-run!
  "Open a run for an EXISTING agent and point `:seon.agent/run` at it in the
   SAME tx — ATOMICALLY, via a compare-and-swap that asserts the agent had NO
   open run (the `:seon.agent/run` attr ABSENT). Idle→running is one
   serialized step: when two wakes (a message + a schedule fire, or two
   messages) race, the wire-server serializes the txs and the SECOND CAS sees
   the first's pointer, so its WHOLE tx fails — no duplicate `:open` run is
   ever committed (the loser gets the db error envelope; the wake handler
   renews instead). Seeds `turn-limit` from `:seon.agent/default-turn-limit`
   (else [[default-turn-limit]]) and `deadline` from now +
   `:seon.agent/default-deadline-ms` (else [[default-deadline-ms]]); explicit
   seeds in the request win. Does NOT flip any stored state — state is
   derived. Returns the run's [[snapshot]] on success, or the db error
   envelope — a CAS-loss included (errors are values). `^:async`."
  {:malli/schema [:=> [:cat ::open-run-request]
                  [:or :seon.agent.run/snapshot :seon.db/transact-response]]}
  [{id :seon.agent/id trigger :seon.agent.run/trigger cause :seon.agent.run/cause
    tl :seon.agent.run/turn-limit dl :seon.agent.run/deadline}]
  (let [a (db/entity {:seon.db/ref [:seon.agent/id id]})]
    (if (nil? a)
      {:seon.db/ok? false
       :seon.db/error {:seon.error/message
                       (str "open-run!: no agent " (pr-str id)
                            " — create the agent first.")}}
      (let [now        (js/Date.)
            ;; Run-bound SEED (config-driven agent-init, move 9): the new
            ;; agent-level config datoms `:seon.agent.run/default-turn-limit`
            ;; / `::default-deadline-ms` (CP-1 defaults 20 / 600000 = the live
            ;; consts) are the reactive config-on-record source; the legacy
            ;; runtime attr + the const remain fallbacks so a no-config agent
            ;; reads byte-identically to today.
            turn-limit (or tl
                           (:seon.agent.run/default-turn-limit a)
                           (:seon.agent/default-turn-limit a)
                           default-turn-limit)
            deadline   (or dl (js/Date. (+ (.getTime now)
                                           (or (::default-deadline-ms a)
                                               (:seon.agent/default-deadline-ms a)
                                               default-deadline-ms))))
            run-id     (db/new-id!)
            run-row    (cond-> {:db/id                     "run"
                                :seon.agent.run/id         run-id
                                :seon.agent.run/agent      [:seon.agent/id id]
                                :seon.agent.run/started-at now
                                :seon.agent.run/trigger    trigger
                                :seon.agent.run/status     :open
                                :seon.agent.run/turn-limit turn-limit
                                :seon.agent.run/deadline   deadline}
                         cause (assoc :seon.agent.run/cause cause))
            res        (await (db/transact!
                                {:seon.db/tx-data
                                 [run-row
                                  ;; ATOMIC WAKE GUARD: point the agent at the
                                  ;; just-created run ONLY IF it has no open run
                                  ;; — a CAS with old-value nil ("the
                                  ;; :seon.agent/run attr is ABSENT", which is
                                  ;; exactly derived-:idle). The run-row above
                                  ;; is processed first in THIS tx, so the
                                  ;; lookup-ref resolves against the just-added
                                  ;; run entity (a tempid is not resolvable in a
                                  ;; CAS new-value slot). A racing second open
                                  ;; sees the pointer set and the whole tx
                                  ;; fails — no second :open run is committed.
                                  [:db.fn/cas [:seon.agent/id id]
                                   :seon.agent/run nil
                                   [:seon.agent.run/id run-id]]]}))]
        (if (false? (:seon.db/ok? res))
          res
          (do (swap! !runs-this-process conj run-id)
              (snapshot {:seon.agent.run/id run-id})))))))

(schema/register! ::close-run-request
  [:map
   [:seon.agent.run/id            :seon.agent.run/id]
   [:seon.agent.run/closed-reason :seon.agent.run/closed-reason]])

(defn- close-tx-data
  "The tx-data [[close-run!]] commits: always the close-row (status :closed +
   reason). When the agent still OWNS this run, ALSO lead with the work-fence
   CAS ([[run-fence]] — assert the pointer STILL names this run) and retract the
   pointer, all in ONE tx — so a supersede landing in the owns?-read→commit
   window aborts the whole tx instead of retracting the NEW owner's pointer
   (the pointer only moves off this run via a concurrent close, which already
   closed it). Pure (no db read) so the fence wiring is unit-testable."
  [owns? agent-id run-id reason]
  (let [close-row {:seon.agent.run/id            run-id
                   :seon.agent.run/status        :closed
                   :seon.agent.run/closed-reason reason}]
    (if owns?
      [(run-fence agent-id run-id)
       close-row
       [:db/retract [:seon.agent/id agent-id] :seon.agent/run]]
      [close-row])))

(defn ^:async close-run!
  "Close a run (`:status :closed` + `closed-reason`). When the agent still
   OWNS this run, also retract its `:seon.agent/run` pointer so derived state
   falls to `:idle`. When it does NOT own it (a superseded run being cleaned
   up), the run is marked closed but the agent's live pointer is left
   untouched — fencing protects the current run.

   The owned retract is FENCED in-tx (the same work-fence CAS as `beat!`/
   `renew!`): the tx LEADS with a CAS asserting the agent's `:seon.agent/run`
   STILL names this run, so a supersede landing in the owns?-read→commit window
   aborts the WHOLE tx rather than retracting the NEW owner's pointer. The
   pointer only ever moves off this run via a (concurrent) `close-run!` retract,
   so a supersede in that window means another close already closed THIS run and
   a fresh run now owns the agent; the loser's CAS-aborted tx is a harmless
   no-op (this run is already closed; the new owner is left intact). Close +
   retract stay in ONE tx — never a `:closed` run with a live pointer, which
   would deadlock `open-run!`'s absent-pointer CAS. `^:async`."
  {:malli/schema [:=> [:cat ::close-run-request] :seon.db/transact-response]}
  [{run-id :seon.agent.run/id reason :seon.agent.run/closed-reason}]
  (let [r (db/entity {:seon.db/ref [:seon.agent.run/id run-id]})]
    (if (nil? r)
      {:seon.db/ok? false
       :seon.db/error {:seon.error/message (str "close-run!: no run " (pr-str run-id) ".")}}
      (let [agent-eid (:db/id (:seon.agent.run/agent r))
            agent-id  (:seon.agent/id (db/entity agent-eid))
            ;; OWNS? = the agent's current open run IS this run (read over the
            ;; live db value via the derive leaf). When owned, retract the
            ;; pointer in the SAME tx so derived state falls to :idle; when a
            ;; DIFFERENT run owns the agent (a superseded run being cleaned up),
            ;; mark this run closed but leave the live pointer untouched.
            owns?     (and agent-id
                           (= run-id (:seon.agent.run/id
                                       (derive/current-run @db/*conn* agent-id))))]
        (await (db/transact!
                 {:seon.db/tx-data (close-tx-data owns? agent-id run-id reason)}))))))

(schema/register! ::renew-request
  [:map
   [:seon.agent/id     :string]
   [:seon.agent.run/id :seon.agent.run/id]
   ;; Optional clock extension (ms) — defaults to the agent's
   ;; default-deadline-ms (else the global default).
   [:seon.agent.run/deadline-extension-ms {:optional true} :int]])

(defn ^:async renew!
  "Renew the lease (the sliding window): bump `turn-limit` by +1 and push
   `deadline` out to now + extension. The tx LEADS with the [[run-fence]] CAS —
   a write from a superseded/timed-out run aborts at the writer and returns the
   CAS-fail envelope (no pre-read predicate). `^:async`."
  {:malli/schema [:=> [:cat ::renew-request] :seon.db/transact-response]}
  [{id :seon.agent/id run-id :seon.agent.run/id
    ext :seon.agent.run/deadline-extension-ms}]
  (let [r      (db/entity {:seon.db/ref [:seon.agent.run/id run-id]})
        a      (db/entity {:seon.db/ref [:seon.agent/id id]})
        now    (js/Date.)
        ext    (or ext (:seon.agent/default-deadline-ms a) default-deadline-ms)
        new-tl (inc (:seon.agent.run/turn-limit r))
        new-dl (js/Date. (+ (.getTime now) ext))]
    (await (db/transact!
             {:seon.db/tx-data
              [(run-fence id run-id)
               {:seon.agent.run/id         run-id
                :seon.agent.run/turn-limit new-tl
                :seon.agent.run/deadline   new-dl}]}))))

(schema/register! ::beat-request
  [:map
   [:seon.agent/id     :string]
   [:seon.agent.run/id :seon.agent.run/id]])

(defn ^:async beat!
  "Heartbeat: write `last-beat-at` = now. The tx LEADS with the [[run-fence]]
   CAS, so a beat from a superseded/closed run aborts and returns the CAS-fail
   envelope — the loop reads `ok? false` as 'lost authority' and stops.
   `^:async`."
  {:malli/schema [:=> [:cat ::beat-request] :seon.db/transact-response]}
  [{id :seon.agent/id run-id :seon.agent.run/id}]
  (await (db/transact!
           {:seon.db/tx-data
            [(run-fence id run-id)
             {:seon.agent.run/id run-id :seon.agent.run/last-beat-at (js/Date.)}]})))

(schema/register! ::pause-request
  [:map
   [:seon.agent/id     :string]
   [:seon.agent.run/id :seon.agent.run/id]])

(defn ^:async pause!
  "Pause the open run: stamp `paused-at` = now (⇒ derived state `:paused`) and
   BANK the remaining wall-clock budget on `remaining-ms` (`deadline − now`,
   floored at 0). [[resume!]] re-extends `deadline` by it, so a long pause
   never instantly blows the clock bound. The tx LEADS with the [[run-fence]]
   CAS. `^:async`."
  {:malli/schema [:=> [:cat ::pause-request] :seon.db/transact-response]}
  [{id :seon.agent/id run-id :seon.agent.run/id}]
  (let [r        (db/entity {:seon.db/ref [:seon.agent.run/id run-id]})
        now      (js/Date.)
        deadline (:seon.agent.run/deadline r)
        remain   (max 0 (- (.getTime deadline) (.getTime now)))]
    (await (db/transact!
             {:seon.db/tx-data
              [(run-fence id run-id)
               {:seon.agent.run/id           run-id
                :seon.agent.run/paused-at    now
                :seon.agent.run/remaining-ms remain}]}))))

(schema/register! ::resume-request
  [:map
   [:seon.agent/id     :string]
   [:seon.agent.run/id :seon.agent.run/id]])

(defn ^:async resume!
  "Resume a PAUSED run: RETRACT `paused-at` (⇒ derived state back to
   `:running`) and re-extend `deadline` to now + the banked `remaining-ms`
   (a long pause never instantly blows the clock bound). GUARDED on
   `paused-at`: a run that is NOT paused has no banked budget, so resume! is
   a loud no-op (the error envelope) rather than an accidental deadline
   overwrite with the default window. The tx LEADS with the [[run-fence]] CAS
   too. `^:async`."
  {:malli/schema [:=> [:cat ::resume-request] :seon.db/transact-response]}
  [{id :seon.agent/id run-id :seon.agent.run/id}]
  (let [r (db/entity {:seon.db/ref [:seon.agent.run/id run-id]})]
    (if-not (:seon.agent.run/paused-at r)
      {:seon.db/ok? false
       :seon.db/error
       {:seon.error/message
        (str "resume!: run " (pr-str run-id) " is not paused "
             "(no :seon.agent.run/paused-at) — nothing to resume.")}}
      (let [remain (or (:seon.agent.run/remaining-ms r) default-deadline-ms)
            new-dl (js/Date. (+ (.getTime (js/Date.)) remain))]
        (await (db/transact!
                 {:seon.db/tx-data
                  [(run-fence id run-id)
                   {:seon.agent.run/id       run-id
                    :seon.agent.run/deadline new-dl}
                   [:db/retract [:seon.agent.run/id run-id]
                    :seon.agent.run/paused-at]]}))))))

;; ============================================================
;; The deadline WATCHDOG — the wall-clock half of the one ticker
;; ([[seon.agent.loop/install-ticker!]]). The DB is passive about wall-clock:
;; `now > deadline` is true in the world, but nothing fires until something
;; checks. This scan IS that check — the EXTERNAL enforcement of the clock
;; bound (a stalled LLM burns the clock and can't self-detect). A run whose
;; async turn overran its deadline is closed here; when the await returns the
;; loop's `next-event` (re-read over the latest db) sees it closed and bails,
;; and any in-flight work tx's leading CAS aborts. (A truly-SYNC
;; runaway needs worker termination — Phase 2, not here.)
;; ============================================================

(schema/register! ::close-overdue-request  [:map [:seon.agent/now :inst]])
(schema/register! ::close-overdue-response
  [:map [:seon.agent.run/closed [:vector :seon.agent.run/id]]])

(defn ^:async close-overdue-runs!
  "Close every OPEN, non-PAUSED run whose `deadline` is past `now`, with
   `:deadline-exceeded` — clearing the agent's `:seon.agent/run` pointer (via
   [[close-run!]]'s owned-retract) so derived state falls to `:idle`. Returns
   the run-ids it closed (map-out). IDEMPOTENT: a re-run finds the now-`:closed`
   runs gone from the scan. A PAUSED run (carrying `:seon.agent.run/paused-at`)
   is SKIPPED — `paused-at` froze the clock and its absolute `deadline` is
   stale until [[resume!]] re-extends it, so it must never be deadline-killed.
   Fencing is automatic (each close goes through [[close-run!]]). `^:async`."
  {:malli/schema [:=> [:cat ::close-overdue-request] ::close-overdue-response]}
  [{now :seon.agent/now}]
  (let [overdue (->> (db/query
                       {:seon.db/query
                        '[:find ?rid ?deadline
                          :where
                          [?r :seon.agent.run/status :open]
                          [?r :seon.agent.run/id ?rid]
                          [?r :seon.agent.run/deadline ?deadline]
                          (not [?r :seon.agent.run/paused-at _])]})
                     (filter (fn [[_ deadline]]
                               (> (.getTime now) (.getTime deadline))))
                     (mapv first))]
    (loop [[rid & more] overdue]
      (when rid
        (await (close-run! {:seon.agent.run/id            rid
                            :seon.agent.run/closed-reason :deadline-exceeded}))
        (recur more)))
    {:seon.agent.run/closed overdue}))

;; ============================================================
;; Boot CRASH-RECOVERY — the "restart to a known-good state" the run model
;; promises. A process crash mid-turn leaves an agent derived `:running` (an
;; `:open` run + the `:seon.agent/run` pointer set) but with NO loop driving
;; it: on reboot, arming wake triggers does NOT reconcile the orphan, so the
;; agent ignores every message (it is not `:idle`) — a deadlock the deadline
;; watchdog only breaks once the run is PAST deadline (a deadline-less run:
;; never). This scan closes EVERY open run at boot — by definition nothing is
;; driving any of them yet — clearing each owned agent's pointer so derived
;; state falls to `:idle` and it becomes wakeable. Reuses `::close-overdue-
;; response` (same map-out shape). The boot path calls it BEFORE arming
;; triggers / the ticker; it must NOT run on a mint in a LIVE process (that
;; would kill currently-running agents) — `seon.client` gates it on first boot.
;; ============================================================

(defn ^:async recover-crashed-runs!
  "Close EVERY `:open` run whose agent is NOT terminated, with `:crashed` —
   clearing each owned agent's `:seon.agent/run` pointer (via [[close-run!]])
   so its derived state falls to `:idle` and it can be woken. The boot
   reconciliation for runs orphaned by a pod crash (an `:open` run + pointer
   with no loop driving it). Takes NO time arg: at boot nothing is driving any
   open run, so ALL are closed — INCLUDING paused ones (a paused run's resume
   loop died with the process too). IDEMPOTENT + safe on every boot — a clean
   boot with no open runs closes nothing. Returns the run-ids it closed
   (map-out). `^:async`."
  {:malli/schema [:=> [:cat] ::close-overdue-response]}
  []
  (let [open-runs (->> (db/query
                         {:seon.db/query
                          '[:find [?rid ...]
                            :where
                            [?r :seon.agent.run/status :open]
                            [?r :seon.agent.run/id ?rid]
                            [?r :seon.agent.run/agent ?a]
                            (not [?a :seon.agent/terminated-at _])]})
                       vec)]
    (loop [[rid & more] open-runs]
      (when rid
        (await (close-run! {:seon.agent.run/id            rid
                            :seon.agent.run/closed-reason :crashed}))
        (recur more)))
    {:seon.agent.run/closed open-runs}))
