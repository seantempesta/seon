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

  The model (crash rulings, plan README sessions 3 + 2026-07-27):

  - A run is the bounded work unit a trigger opens. Its state is DERIVED
    from primitives — open = no closed-at; claimed = a process with a
    live lease — never a stored status label.
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
  - Crashes are rare and NOTHING re-executes: boot recovery marks
    dangling `:running` eval receipts `:interrupted` and releases
    custody held by dead processes, leaving every terminal receipt
    untouched. A form has AT MOST ONE terminal receipt, ever. The
    interrupted state surfaces as ONE derived warning, never per-eval
    markers.

  Crash walk: every transition here is ONE atomic transaction (a single
  `[:db.fn/call ...]`), so a kill at any instant leaves it either fully
  committed or absent — there is no partial window inside this
  namespace. The two windows that remain live OUTSIDE it: a run opened
  before its plan commits (recovery sees an open unplanned run — the
  known unowned issue), and a receipt written `:running` before its
  eval settles (recovery marks it `:interrupted`)."
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

(defn claimed?
  "True when a process holds the run under a live lease at `now`."
  {:malli/schema [:=> [:cat [:map
                             [::process {:optional true} ::process]
                             [::lease-until {:optional true} ::lease-until]]
                       :inst]
                  :boolean]}
  [run now]
  (boolean
   (and (some? (::process run))
        (some? (::lease-until run))
        (> (inst-ms (::lease-until run)) (inst-ms now)))))

(defn expired?
  "True when the run is open and its holder's lease lapsed at `now`."
  {:malli/schema [:=> [:cat [:map
                             [::closed-at {:optional true} ::closed-at]
                             [::process {:optional true} ::process]
                             [::lease-until {:optional true} ::lease-until]]
                       :inst]
                  :boolean]}
  [run now]
  (boolean
   (and (open? run)
        (some? (::process run))
        (some? (::lease-until run))
        (<= (inst-ms (::lease-until run)) (inst-ms now)))))

(defn interrupted-warning
  "Derive the ONE interrupted warning for a run, or nil when clean.
  Non-nil exactly when an `:interrupted` receipt exists among the
  supplied receipts:
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
                         [:seon.cluster.eval/status
                          :seon.cluster.eval/status]]]]
                  [:maybe [:map
                           [:seon.cluster.eval/ordinal
                            :seon.cluster.eval/ordinal]
                           [::missing-results ::missing-results]]]]}
  [forms receipts]
  (when-let [ordinal
             (->> receipts
                  (filter #(= :interrupted
                              (:seon.cluster.eval/status %)))
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
;;; fence shared by heartbeat/release/close/plan. The `*-tx` wrappers
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
  "The run's current facts when `request` names its exact live custody.
  The shared fence of heartbeat/release/close/plan: the run must exist,
  be open, and be held by exactly `::process` at exactly
  `::claim-epoch` under a lease live at the supplied `::now`. Refuses
  otherwise — an expired, displaced, or stale holder never resurrects
  custody by asserting it."
  [db transition request]
  (let [{::keys [id process claim-epoch now]} request
        run (current-run db id)]
    (cond
      (nil? run) (refuse! transition ::no-such-run request)
      (not (open? run)) (refuse! transition ::run-closed request)
      (not= process (::process run))
      (refuse! transition ::not-the-holder request)

      (not= claim-epoch (::claim-epoch run))
      (refuse! transition ::stale-epoch request)

      (not (claimed? run now))
      (refuse! transition ::lease-expired request)

      :else run)))

(defn- retract-custody
  "Retraction ops dropping `run`'s process and lease, keeping the epoch.
  Retracts the values the mid-transaction read actually found, so the
  ops are exact rather than attribute-wide."
  [run]
  (cond-> [[:db/retract (:db/id run) ::process (::process run)]]
    (some? (::lease-until run))
    (conj [:db/retract (:db/id run) ::lease-until (::lease-until run)])))

;; The *-tx wrappers reference their *-call VARS (#'f): datahike applies
;; the var, so redefining a transition against the running system updates
;; behavior immediately — the flow-dynamics live-update pattern.
(declare claim-call heartbeat-call release-call close-call plan-call
         open-call receipt-start-call receipt-settle-call)

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
  "Transaction data claiming `::id` for `::process` until `::lease-until`."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::lease-until ::lease-until]
                             [::now :inst]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'claim-call request]])

(defn claim-call
  "Claim the run, inside the transaction; eligibility IS the read.
  - the run must exist and be open (a closed run is never claimable);
  - unheld (no `::process`) → claim: epoch (inc (or current 0)),
    process and lease asserted;
  - held under a LIVE lease at `::now` → refuse, regardless of who
    holds it (a live claim is not stealable; the holder renews through
    heartbeat, never through a second claim);
  - held under a LAPSED lease at `::now` → takeover: same assertions,
    epoch incremented past the previous holder's, custody replaced.
  There are no observed-* fields; the mid-transaction db is the only
  truth consulted."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]
                        [::lease-until ::lease-until]
                        [::now :inst]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id process lease-until now]} request
        run (current-run db id)]
    (cond
      (nil? run) (refuse! `claim-call ::no-such-run request)
      (not (open? run)) (refuse! `claim-call ::run-closed request)
      ; a LIVE claim is not stealable; a lapsed one is taken over, and
      ; the unheld case falls through the same branch
      (claimed? run now) (refuse! `claim-call ::lease-live request)
      :else [[:db/add (:db/id run) ::claim-epoch
              (inc (or (::claim-epoch run) 0))]
             [:db/add (:db/id run) ::process process]
             [:db/add (:db/id run) ::lease-until lease-until]])))

(defn heartbeat-tx
  "Transaction data renewing `::process`'s lease under its epoch."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::claim-epoch ::claim-epoch]
                             [::lease-until ::lease-until]
                             [::now :inst]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'heartbeat-call request]])

(defn heartbeat-call
  "Renew the holder's lease, inside the transaction.
  Refuses unless the run is open and currently held by exactly
  `::process` at exactly `::claim-epoch` — a displaced or stale holder's
  heartbeat fails loudly, it never resurrects custody."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]
                        [::claim-epoch ::claim-epoch]
                        [::lease-until ::lease-until]
                        [::now :inst]]]
                  [:vector :some]]}
  [db request]
  (let [run (held-run db `heartbeat-call request)]
    [[:db/add (:db/id run) ::lease-until (::lease-until request)]]))

(defn release-tx
  "Transaction data cleanly releasing `::process`'s custody."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::claim-epoch ::claim-epoch]
                             [::now :inst]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'release-call request]])

(defn release-call
  "Release custody, inside the transaction.
  Retracts process + lease, keeps the epoch. Refuses unless the run is
  open and held by exactly `::process` at exactly `::claim-epoch`."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]
                        [::claim-epoch ::claim-epoch]
                        [::now :inst]]]
                  [:vector :some]]}
  [db request]
  (retract-custody (held-run db `release-call request)))

(defn close-tx
  "Transaction data closing the run held by `::process`."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::claim-epoch ::claim-epoch]
                             [::closed-at ::closed-at]
                             [::now :inst]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'close-call request]])

(defn close-call
  "Close the run, inside the transaction.
  Assert closed-at, retract custody, retract the owning agent's
  current-run pointer. The agent is the run's OWN `::agent` connection
  read from `db` — the request carries no agent id, so a wrong one
  cannot exist. Refuses unless the run is open and held by exactly
  `::process` at exactly `::claim-epoch` — AND refuses
  `::agent-pointer-broken` when the owning agent's pointer does not
  point at this run: a broken relation is settled loudly, never by
  silently omitting the retraction."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]
                        [::claim-epoch ::claim-epoch]
                        [::closed-at ::closed-at]
                        [::now :inst]]]
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
                             [::claim-epoch ::claim-epoch]
                             [::plan-digest ::plan-digest]
                             [::sources [:vector [:string {:min 1}]]]
                             [::now :inst]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'plan-call request]])

(defn plan-call
  "Freeze the plan, inside the transaction.
  Assert the digest and the
  owned ordered form entities. Refuses unless the run is open, held by
  exactly `::process` at exactly `::claim-epoch`, and has NO existing
  `::plan-digest` — concurrent replies are mutually exclusive because
  the second one reads the first one's digest and refuses."
  {:malli/schema [:=> [:cat :any
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]
                        [::claim-epoch ::claim-epoch]
                        [::plan-digest ::plan-digest]
                        [::sources [:vector [:string {:min 1}]]]
                        [::now :inst]]]
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
  "The receipt identified by run, ordinal, and epoch, or nil."
  [db id ordinal claim-epoch]
  (d/pull db '[*]
          [:seon.cluster.eval/id (pr-str [id ordinal claim-epoch])]))

(defn- receipt-run
  "The open run at the receipt request's exact epoch, or refuse."
  [db transition request]
  (let [{::keys [id claim-epoch]} request
        run (current-run db id)]
    (cond
      (nil? run) (refuse! transition ::no-such-run request)
      (not (open? run)) (refuse! transition ::run-closed request)
      (not= claim-epoch (::claim-epoch run))
      (refuse! transition ::stale-epoch request)
      :else run)))

(defn receipt-start-tx
  "Transaction data starting one absent receipt at `:running`."
  {:malli/schema
   [:=> [:cat [:map {:closed true}
               [::id ::id]
               [::claim-epoch ::claim-epoch]
               [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
               [:seon.cluster.eval/at :seon.cluster.eval/at]]]
    [:vector :some]]}
  [request]
  [[:db.fn/call #'receipt-start-call request]])

(defn receipt-start-call
  "Start one receipt, inside the transaction.
  The receipt must be absent and its epoch must be the run's exact
  current epoch. Identity derives from run, ordinal, and epoch."
  {:malli/schema
   [:=> [:cat :any
         [:map {:closed true}
          [::id ::id]
          [::claim-epoch ::claim-epoch]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.cluster.eval/at :seon.cluster.eval/at]]]
    [:vector :some]]}
  [db request]
  (let [{::keys [id claim-epoch]
         :seon.cluster.eval/keys [ordinal at]} request
        run (receipt-run db `receipt-start-call request)
        receipt-id (pr-str [id ordinal claim-epoch])]
    (when (some? (current-receipt db id ordinal claim-epoch))
      (refuse! `receipt-start-call ::receipt-exists request))
    [{:seon.cluster.eval/id receipt-id
      :seon.cluster.eval/run (:db/id run)
      :seon.cluster.eval/ordinal ordinal
      :seon.cluster.eval/claim-epoch claim-epoch
      :seon.cluster.eval/at at
      :seon.cluster.eval/status :running}]))

(defn receipt-settle-tx
  "Transaction data settling one running receipt exactly once."
  {:malli/schema
   [:=> [:cat
         [:map {:closed true}
          [::id ::id]
          [::claim-epoch ::claim-epoch]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.cluster.eval/status
           [:enum :done :error :interrupted]]
          [:seon.cluster.eval/result-edn {:optional true}
           :seon.cluster.eval/result-edn]
          [:seon.cluster.eval/error {:optional true}
           :seon.cluster.eval/error]
          [:seon.error/kind {:optional true} :seon.error/kind]
          [:seon.cluster.eval/output {:optional true}
           :seon.cluster.eval/output]]]
    [:vector :some]]}
  [request]
  [[:db.fn/call #'receipt-settle-call request]])

(defn receipt-settle-call
  "Settle one running receipt, inside the transaction.
  The run and receipt must both name the request's exact current epoch;
  a terminal receipt never returns to running or changes outcome."
  {:malli/schema
   [:=> [:cat :any
         [:map {:closed true}
          [::id ::id]
          [::claim-epoch ::claim-epoch]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.cluster.eval/status
           [:enum :done :error :interrupted]]
          [:seon.cluster.eval/result-edn {:optional true}
           :seon.cluster.eval/result-edn]
          [:seon.cluster.eval/error {:optional true}
           :seon.cluster.eval/error]
          [:seon.error/kind {:optional true} :seon.error/kind]
          [:seon.cluster.eval/output {:optional true}
           :seon.cluster.eval/output]]]
    [:vector :some]]}
  [db request]
  (let [{::keys [id claim-epoch]
         :seon.cluster.eval/keys [ordinal status]} request
        run (receipt-run db `receipt-settle-call request)
        receipt (current-receipt db id ordinal claim-epoch)]
    (cond
      (nil? receipt)
      (refuse! `receipt-settle-call ::no-such-receipt request)

      (not= (:db/id run) (:db/id (:seon.cluster.eval/run receipt)))
      (refuse! `receipt-settle-call ::receipt-run-mismatch request)

      (not= ordinal (:seon.cluster.eval/ordinal receipt))
      (refuse! `receipt-settle-call ::receipt-ordinal-mismatch request)

      (not= claim-epoch (:seon.cluster.eval/claim-epoch receipt))
      (refuse! `receipt-settle-call ::stale-receipt-epoch request)

      (not= :running (:seon.cluster.eval/status receipt))
      (refuse! `receipt-settle-call ::receipt-terminal request))
    (cond-> [[:db/add (:db/id receipt) :seon.cluster.eval/status status]]
      (:seon.cluster.eval/result-edn request)
      (conj [:db/add (:db/id receipt) :seon.cluster.eval/result-edn
             (:seon.cluster.eval/result-edn request)])
      (:seon.cluster.eval/error request)
      (conj [:db/add (:db/id receipt) :seon.cluster.eval/error
             (:seon.cluster.eval/error request)])
      (:seon.error/kind request)
      (conj [:db/add (:db/id receipt) :seon.error/kind
             (:seon.error/kind request)])
      (:seon.cluster.eval/output request)
      (conj [:db/add (:db/id receipt) :seon.cluster.eval/output
             (:seon.cluster.eval/output request)]))))

(defn recover-tx
  "Boot recovery for one run against dead-process facts.
  Every `:running` receipt becomes `:interrupted`; custody held by a
  process outside `::live-processes` is released; EVERY terminal
  receipt (`:done`/`:error`/`:interrupted`) is left byte-untouched.
  Returns [] for a run needing nothing. Pure over supplied values (the
  boot pass reads once and recovers every run from one basis), so this
  stays a plain data function rather than a `:db.fn/call`. NOTHING here
  re-opens, re-plans, or re-executes."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::run [:map]]
                             [::receipts [:sequential :map]]
                             [::live-processes [:set ::process]]]]
                  [:vector :some]]}
  [request]
  (let [{::keys [run receipts live-processes]} request
        interrupted
        (keep (fn [receipt]
                (when (= :running (:seon.cluster.eval/status receipt))
                  [:db.fn/cas
                   [:seon.cluster.eval/id
                    (:seon.cluster.eval/id receipt)]
                   :seon.cluster.eval/status
                   :running
                   :interrupted]))
              receipts)
        process (::process run)
        lease-until (::lease-until run)
        dead-holder? (and (some? process)
                          (not (contains? live-processes process)))
        run-ref [::id (::id run)]]
    (into []
          cat
          [interrupted
           (when dead-holder?
             (cond-> [[:db/retract run-ref ::process process]]
               (some? lease-until)
               (conj [:db/retract
                      run-ref ::lease-until lease-until])))])))
