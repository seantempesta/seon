(ns seon.cluster.run
  "The run data model: claimable database state, transitions inside the
  transaction.

  CONTRACT LAYER (orchestrator-authored; revised 2026-07-27 after
  quality-review-1 live-reproduced two correctness holes — takeover
  eligibility and agent-pointer fencing — and the first Gemini hook
  review corroborated the nil-epoch takeover). The schemas and function
  contracts are SEALED: the implementation lane fills the `*-call`
  bodies until test/seon/cluster/run_test.clj is green and may not
  loosen a schema or a test. Friction is reported, never resolved by
  weakening.

  The model (crash rulings, plan README sessions 3 + 2026-07-27;
  custody revision 2026-07-28,
  docs/prds/sci-execution-runtime/plan/custody-revision-contracts-2026-07-28.md):

  - A run is the bounded work unit a trigger opens. Its state is DERIVED
    from primitives — open = no closed-at; held = `::process` present —
    never a stored status label. CUSTODY IS PRESENCE: there is no epoch
    and no lease, because the flock + single writer make a competing
    claimant unrepresentable and the settle-once presence fences close
    every order a hypothetical late committer could take
    (research/zombie-constructibility-2026-07-28.md §6).
  - EVERY transition decision happens INSIDE the transaction. Each
    transition is one pure function of the mid-transaction database
    value and a small request map, invoked as `[:db.fn/call f request]`
    on the one serial writer: it reads current run state from `db`,
    REFUSES ineligible transitions by throwing (the whole transaction
    aborts atomically), and returns plain tx-data otherwise. There are
    no observed-* request fields and no caller pre-reads — the invalid
    states are unrepresentable, not double-checked.
  - The run's own connections are the authority: close derives the
    agent pointer to retract from the run's `::agent` ref; open derives
    the pointer CAS from the same `::agent` value that lands on the
    entity. Correlated caller inputs do not exist.
  - `::now` is the run loop's clock, an explicit request input so every
    transition stays a deterministic pure function (generative tests
    supply it). The transition fences STATE; time is first-party input
    from core code — agents never reach this layer.
  - Crashes are rare and NOTHING re-executes: boot recovery asserts
    `:seon.cluster.eval/interrupted-at` on dangling receipts (those
    carrying no terminal fact) and releases custody held by dead
    processes, leaving every settled receipt untouched. A form has AT
    MOST ONE settlement, ever. The interrupted state surfaces as ONE
    derived warning, never per-eval markers.

  Crash walk: every transition here is ONE atomic transaction (a single
  `[:db.fn/call ...]`), so a kill at any instant leaves it either fully
  committed or absent — there is no partial window inside this
  namespace. The two windows that remain live OUTSIDE it: a run opened
  before its plan commits (recovery sees an open unplanned run — the
  known unowned issue), and a receipt started before its eval settles
  (recovery asserts its `interrupted-at`)."
  (:require [datahike.api :as d]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; The agent pointer — owned HERE. Port manifest: old `:seon.agent/*`
;;; attrs are DEAD for this model; the agent entity is re-decided at its
;;; own rung. The run model needs exactly an identity to point from and
;;; the current-run pointer opens race on.
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Pure derivations
;;; ---------------------------------------------------------------------------

(defn open?
  "True when the run has not closed."
  {:malli/schema [:=> [:cat [:map [::closed-at {:optional true}
                                   ::closed-at]]]
                  :boolean]}
  [run]
  (not (contains? run ::closed-at)))

(defn held?
  "True when a process holds the run. CUSTODY IS PRESENCE: `::process`
  present = held, absent = unheld — there is no lease clock and no
  epoch (custody revision 2026-07-28)."
  {:malli/schema [:=> [:cat [:map
                             [::process {:optional true} ::process]]]
                  :boolean]}
  [run]
  (some? (::process run)))

(defn terminal?
  "True when the receipt carries a terminal fact.
  THE ONE PRESENCE QUESTION over receipts (owner ruling 2026-07-28): a
  receipt settles by asserting `result-edn` or `error`, and is cut by
  `interrupted-at`. A receipt carrying none of the three is running —
  there is no status label to read. `seon.cluster.work/next-ordinal`
  asks the same question as a query; this is its map twin."
  {:malli/schema [:=> [:cat :map] :boolean]}
  [receipt]
  (boolean (or (:seon.cluster.eval/result-edn receipt)
               (:seon.cluster.eval/error receipt)
               (:seon.cluster.eval/interrupted-at receipt))))

(defn interrupted-warning
  "Derive the ONE interrupted warning for a run, or nil when clean.
  Non-nil exactly when a receipt carrying
  `:seon.cluster.eval/interrupted-at` exists among the supplied
  receipts:
  {:seon.cluster.eval/ordinal first-interrupted-ordinal
   :seon.cluster.run/missing-results count-of-forms-at-or-after-it}.
  The caller supplies one run's forms and receipts and knows which run
  they belong to. This is the whole resume presentation — never
  per-eval markers."
  {:malli/schema [:=> [:cat
                       [:sequential
                        [:map [:seon.cluster.run.form/ordinal
                               :seon.cluster.run.form/ordinal]]]
                       [:sequential
                        [:map
                         [:seon.cluster.eval/ordinal
                          :seon.cluster.eval/ordinal]
                         [:seon.cluster.eval/interrupted-at
                          {:optional true}
                          :seon.cluster.eval/interrupted-at]]]]
                  [:maybe [:map
                           [:seon.cluster.eval/ordinal
                            :seon.cluster.eval/ordinal]
                           [::missing-results ::missing-results]]]]}
  [forms receipts]
  (when-let [ordinal
             (->> receipts
                  (filter :seon.cluster.eval/interrupted-at)
                  (map :seon.cluster.eval/ordinal)
                  sort
                  first)]
    {:seon.cluster.eval/ordinal ordinal
     ::missing-results
     (count
      (filter #(>= (:seon.cluster.run.form/ordinal %) ordinal)
              forms))}))

;;; ---------------------------------------------------------------------------
;;; Transitions — pure functions OF THE MID-TRANSACTION DATABASE VALUE,
;;; each invoked as [:db.fn/call f request] on the one serial writer.
;;;
;;; Shared contract, every `*-call`:
;;; - reads the current run/agent state from `db` (datahike.api/pull or
;;;   entity over the db value it is handed);
;;; - THROWS ex-info {:seon.error/kind :seon.cluster.run/refused, ...}
;;;   naming the violated rule when the transition is ineligible — the
;;;   writer aborts the whole transaction atomically;
;;; - otherwise returns plain tx-data (the serial writer makes the read
;;;   atomic with the write; no nested CAS is needed or wanted).
;;;
;;; Implemented 2026-07-27 (ba5cb0c1e): `held-run` is the ONE custody
;;; fence shared by release/close/plan. The `*-tx` wrappers
;;; are the contract's own one-liners.
;;; ---------------------------------------------------------------------------

(defn- refuse!
  "Abort the whole transaction, naming the rule the request violated."
  [transition rule request]
  (throw (ex-info (str "run transition refused: " (name rule))
                  {:seon.error/kind ::refused
                   ::transition transition
                   ::rule rule
                   ::request request})))

(defn- current-run
  "The run's current facts on `db`, or nil when no such run exists.
  The mid-transaction pull IS the eligibility read: a missing lookup ref
  pulls to nil rather than throwing, so absence is an ordinary value."
  [db id]
  (d/pull db '[*] [::id id]))

(defn- held-run
  "The run's current facts when `request` names its exact custody.
  The shared fence of release/close/plan: the run must exist, be open,
  and be held by exactly `::process`. `::not-the-holder` is the ONE
  loud custody refusal — a displaced or absent holder never resurrects
  custody by asserting it."
  [db transition request]
  (let [{::keys [id process]} request
        run (current-run db id)]
    (cond
      (nil? run) (refuse! transition ::no-such-run request)
      (not (open? run)) (refuse! transition ::run-closed request)
      (not= process (::process run))
      (refuse! transition ::not-the-holder request)
      :else run)))

(defn- retract-custody
  "The retraction op dropping `run`'s custody.
  Retracts the value the mid-transaction read actually found, so the
  op is exact rather than attribute-wide."
  [run]
  [[:db/retract (:db/id run) ::process (::process run)]])

(defn- running-receipts
  "Every receipt of run entity `run-eid` carrying no terminal fact,
  read from the mid-transaction database value — the read and the
  stamp share one transaction, so a settled receipt can never be
  stamped from a stale basis (custody revision, Revision 4)."
  [db run-eid]
  (->> (d/q '[:find [?receipt ...]
              :in $ ?run
              :where [?receipt :seon.cluster.eval/run ?run]]
            db run-eid)
       (map #(d/pull db '[*] %))
       (remove terminal?)))

(defn- interrupt-stamps
  "One `interrupted-at` assertion per running receipt of `run-eid`."
  [db run-eid now]
  (mapv (fn [receipt]
          [:db/add (:db/id receipt)
           :seon.cluster.eval/interrupted-at now])
        (running-receipts db run-eid)))

;; The *-tx wrappers reference their *-call VARS (#'f): datahike applies
;; the var, so redefining a transition against the running system updates
;; behavior immediately — the flow-dynamics live-update pattern.
(declare claim-call release-call close-call plan-call
         open-call receipt-start-call receipt-settle-call recover-call)

(defn open-call
  "Open one run for an agent, inside the transaction.
  Refuses when the run id already exists, or when the agent's
  current-run pointer is present (an agent holds at most one open run).
  Returns the run entity assertion plus the agent pointer assertion —
  BOTH derived from the one `::agent` ref in the request; there is no
  separate agent-id field to disagree with it."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::agent ::agent]
                        [::opened-at ::opened-at]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id agent opened-at]} request
        agent-eid (:db/id (d/pull db [:db/id] agent))
        run-tempid (str "seon.cluster.run/" id)]
    (cond
      (nil? agent-eid) (refuse! `open-call ::no-such-agent request)
      (some? (current-run db id)) (refuse! `open-call ::run-exists request)

      (some? (:seon.cluster.agent/run
              (d/pull db [:seon.cluster.agent/run] agent-eid)))
      (refuse! `open-call ::agent-already-running request)

      ; the pointer and the run's own ::agent are the SAME resolved
      ; entity, so they cannot disagree
      :else [{:db/id run-tempid
              ::id id
              ::agent agent-eid
              ::opened-at opened-at}
             {:db/id agent-eid :seon.cluster.agent/run run-tempid}])))

(defn claim-tx
  "Transaction data claiming `::id` for `::process`."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::live-processes [:set ::process]]
                             [::now :inst]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'claim-call request]])

(defn claim-call
  "Claim the run, inside the transaction; eligibility IS the read.
  CUSTODY IS PRESENCE, and CAS-on-absence is the mid-transaction read:
  - the run must exist and be open (a closed run is never claimable);
  - unheld (no `::process`) → claim: assert the process;
  - held by a process in `::live-processes` → refuse `::run-held` (a
    live claim is not stealable; there is no second live claimant to
    steal for — the refusal is the model stating that);
  - held by a DEAD process (outside `::live-processes`) → TAKEOVER =
    RECOVERY, one shape: stamp that custody's running receipts
    `interrupted-at` at `::now`, then retract/assert `::process` — one
    transaction, so the intermediate state never exists (custody
    revision, Revision 3).
  There are no observed-* fields; the mid-transaction db is the only
  truth consulted."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]
                        [::live-processes [:set ::process]]
                        [::now :inst]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id process live-processes now]} request
        run (current-run db id)
        holder (::process run)]
    (cond
      (nil? run) (refuse! `claim-call ::no-such-run request)
      (not (open? run)) (refuse! `claim-call ::run-closed request)
      (nil? holder) [[:db/add (:db/id run) ::process process]]
      (contains? live-processes holder)
      (refuse! `claim-call ::run-held request)
      :else (into (interrupt-stamps db (:db/id run) now)
                  [[:db/retract (:db/id run) ::process holder]
                   [:db/add (:db/id run) ::process process]]))))

(defn release-tx
  "Transaction data cleanly releasing `::process`'s custody."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'release-call request]])

(defn release-call
  "Release custody, inside the transaction.
  Retracts the process. Refuses unless the run is open and held by
  exactly `::process`."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]]]
                  [:vector :some]]}
  [db request]
  (retract-custody (held-run db `release-call request)))

(defn close-tx
  "Transaction data closing the run held by `::process`."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::closed-at ::closed-at]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'close-call request]])

(defn close-call
  "Close the run, inside the transaction.
  Assert closed-at, retract custody, retract the owning agent's
  current-run pointer. The agent is the run's OWN `::agent` connection
  read from `db` — the request carries no agent id, so a wrong one
  cannot exist. Refuses unless the run is open and held by exactly
  `::process` — AND refuses `::agent-pointer-broken` when the owning
  agent's pointer does not point at this run: a broken relation is
  settled loudly, never by silently omitting the retraction."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]
                        [::closed-at ::closed-at]]]
                  [:vector :some]]}
  [db request]
  (let [run (held-run db `close-call request)
        ; the run's OWN connection names the agent whose pointer this
        ; close retracts — the request carries no agent id to disagree
        agent-eid (:db/id (::agent run))
        pointer (:seon.cluster.agent/run
                 (d/pull db [:seon.cluster.agent/run] agent-eid))]
    (when-not (= (:db/id run) (:db/id pointer))
      (refuse! `close-call ::agent-pointer-broken request))
    (conj (retract-custody run)
          [:db/add (:db/id run) ::closed-at (::closed-at request)]
          [:db/retract agent-eid :seon.cluster.agent/run (:db/id run)])))

(defn plan-tx
  "Transaction data freezing one ordered form plan on the held run."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::plan-digest ::plan-digest]
                             [::sources [:vector [:string {:min 1}]]]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'plan-call request]])

(defn plan-call
  "Freeze the plan, inside the transaction.
  Assert the digest and the
  owned ordered form entities. Refuses unless the run is open, held by
  exactly `::process`, and has NO existing `::plan-digest` —
  concurrent replies are mutually exclusive because the second one
  reads the first one's digest and refuses."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]
                        [::plan-digest ::plan-digest]
                        [::sources [:vector [:string {:min 1}]]]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id plan-digest sources]} request
        run (held-run db `plan-call request)
        run-eid (:db/id run)]
    (when (some? (::plan-digest run))
      (refuse! `plan-call ::plan-frozen request))
    (let [forms (into []
                      (map-indexed
                       (fn [ordinal source]
                         (let [form-id (pr-str [id ordinal])]
                           {:db/id form-id
                            :seon.cluster.run.form/id form-id
                            :seon.cluster.run.form/run run-eid
                            :seon.cluster.run.form/ordinal ordinal
                            :seon.cluster.run.form/source source})))
                      sources)]
      (into [[:db/add run-eid ::plan-digest plan-digest]]
            cat
            [forms
             (map (fn [form]
                    [:db/add run-eid ::forms (:db/id form)])
                  forms)]))))

(defn open-tx
  "Transaction data opening one run for an agent."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::agent ::agent]
                             [::opened-at ::opened-at]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'open-call request]])

(defn- current-receipt
  "The receipt identified by run and ordinal, or nil.
  Identity is `(pr-str [id ordinal])` — AT MOST ONE ATTEMPT PER FORM,
  EVER, held by the identity itself: re-execution across any custody
  change is unrepresentable, strictly stronger than the epoch this
  replaced (custody revision 2026-07-28)."
  [db id ordinal]
  (d/pull db '[*] [:seon.cluster.eval/id (pr-str [id ordinal])]))

(defn- receipt-run
  "The open run of a receipt request, or refuse."
  [db transition request]
  (let [{::keys [id]} request
        run (current-run db id)]
    (cond
      (nil? run) (refuse! transition ::no-such-run request)
      (not (open? run)) (refuse! transition ::run-closed request)
      :else run)))

(defn receipt-start-tx
  "Transaction data starting one absent receipt.
  A started receipt carries no terminal fact — that absence IS running."
  {:malli/schema
   [:=> [:cat [:map {:closed true}
               [::id ::id]
               [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
               [:seon.cluster.eval/at :seon.cluster.eval/at]]]
    [:vector :some]]}
  [request]
  [[:db.fn/call #'receipt-start-call request]])

(defn receipt-start-call
  "Start one receipt, inside the transaction.
  The receipt must be absent. Identity derives from run and ordinal
  alone, so an ordinal that ever had a receipt refuses forever —
  nothing re-executes."
  {:malli/schema
   [:=> [:cat :any
         [:map {:closed true}
          [::id ::id]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.cluster.eval/at :seon.cluster.eval/at]]]
    [:vector :some]]}
  [db request]
  (let [{::keys [id]
         :seon.cluster.eval/keys [ordinal at]} request
        run (receipt-run db `receipt-start-call request)
        receipt-id (pr-str [id ordinal])]
    (when (some? (current-receipt db id ordinal))
      (refuse! `receipt-start-call ::receipt-exists request))
    [{:seon.cluster.eval/id receipt-id
      :seon.cluster.eval/run (:db/id run)
      :seon.cluster.eval/ordinal ordinal
      :seon.cluster.eval/at at}]))

(defn receipt-settle-tx
  "Transaction data settling one running receipt exactly once.
  Settling IS asserting terminal facts: `result-edn`, `error`, and/or
  `interrupted-at` — at least one, and there is no status label."
  {:malli/schema
   [:=> [:cat
         [:map {:closed true}
          [::id ::id]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.cluster.eval/result-edn {:optional true}
           :seon.cluster.eval/result-edn]
          [:seon.cluster.eval/error {:optional true}
           :seon.cluster.eval/error]
          [:seon.cluster.eval/interrupted-at {:optional true}
           :seon.cluster.eval/interrupted-at]
          [:seon.error/kind {:optional true} :seon.error/kind]
          [:seon.cluster.eval/output {:optional true}
           :seon.cluster.eval/output]]]
    [:vector :some]]}
  [request]
  [[:db.fn/call #'receipt-settle-call request]])

(defn receipt-settle-call
  "Settle one running receipt, inside the transaction.
  The settle-once fence is PRESENCE: a receipt already carrying any
  terminal fact refuses `::receipt-terminal`, so a settled receipt
  never returns to running or changes outcome; a settle carrying no
  terminal fact refuses `::no-terminal-fact`, because \"settled with
  nothing settled\" is a caller bug."
  {:malli/schema
   [:=> [:cat :any
         [:map {:closed true}
          [::id ::id]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.cluster.eval/result-edn {:optional true}
           :seon.cluster.eval/result-edn]
          [:seon.cluster.eval/error {:optional true}
           :seon.cluster.eval/error]
          [:seon.cluster.eval/interrupted-at {:optional true}
           :seon.cluster.eval/interrupted-at]
          [:seon.error/kind {:optional true} :seon.error/kind]
          [:seon.cluster.eval/output {:optional true}
           :seon.cluster.eval/output]]]
    [:vector :some]]}
  [db request]
  (let [{::keys [id]
         :seon.cluster.eval/keys [ordinal]} request
        run (receipt-run db `receipt-settle-call request)
        receipt (current-receipt db id ordinal)]
    (cond
      (nil? receipt)
      (refuse! `receipt-settle-call ::no-such-receipt request)

      (not= (:db/id run) (:db/id (:seon.cluster.eval/run receipt)))
      (refuse! `receipt-settle-call ::receipt-run-mismatch request)

      (not= ordinal (:seon.cluster.eval/ordinal receipt))
      (refuse! `receipt-settle-call ::receipt-ordinal-mismatch request)

      (terminal? receipt)
      (refuse! `receipt-settle-call ::receipt-terminal request)

      (not (terminal? request))
      (refuse! `receipt-settle-call ::no-terminal-fact request))
    (into []
          (keep (fn [attribute]
                  (when-some [value (get request attribute)]
                    [:db/add (:db/id receipt) attribute value])))
          [:seon.cluster.eval/result-edn
           :seon.cluster.eval/error
           :seon.cluster.eval/interrupted-at
           :seon.error/kind
           :seon.cluster.eval/output])))

(defn recover-tx
  "Transaction data recovering one run from dead-process facts."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::live-processes [:set ::process]]
                             [::now :inst]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'recover-call request]])

(defn recover-call
  "Boot recovery for one run, inside the transaction.
  When the run's holder is NOT a live process — dead, or absent
  entirely — every running receipt (one carrying NO terminal fact) gets
  `:seon.cluster.eval/interrupted-at` asserted at `::now`, and dead
  custody is released. EVERY settled receipt is left byte-untouched:
  the receipt read and the stamp share this one transaction, so a
  stale-basis recovery stamping a settled receipt is unrepresentable
  (custody revision, Revision 4). A run held by a live process, a
  closed run, and a missing run all need nothing — recovery is
  idempotent and never refuses. NOTHING here re-opens, re-plans, or
  re-executes."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::live-processes [:set ::process]]
                        [::now :inst]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id live-processes now]} request
        run (current-run db id)
        holder (::process run)]
    (if (or (nil? run)
            (not (open? run))
            (contains? live-processes holder))
      []
      (into (interrupt-stamps db (:db/id run) now)
            (when (some? holder)
              (retract-custody run))))))
