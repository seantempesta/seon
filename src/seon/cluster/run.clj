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
    carrying no terminal fact), closes every open prior-process run,
    releases its custody, and retracts the agent pointer. Every settled
    receipt stays untouched. A form has AT MOST ONE settlement, ever.
    The interrupted state surfaces as ONE derived warning, never
    per-eval markers.

  Crash walk: every transition here is ONE atomic transaction (a single
  `[:db.fn/call ...]`), so a kill at any instant leaves it either fully
  committed or absent — there is no partial window inside this
  namespace. The two windows that remain live OUTSIDE it: a run opened
  before its plan commits (recovery sees an open unplanned run — the
  known unowned issue), and a receipt started before its eval settles
  (recovery asserts its `interrupted-at`)."
  (:require [clojure.edn :as edn]
            [clojure.main :as main]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]))

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
  (db/pull db '[*] [::id id]))

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
  (->> (db/q '[:find [?receipt ...]
              :in $ ?run
              :where [?receipt :seon.cluster.eval/run ?run]]
            db run-eid)
       (map #(db/pull db '[*] %))
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
         open-call receipt-start-call receipt-settle-call
         receipt-refusal-call recover-call)

(defn- unanswered-background-results
  [db agent-eid]
  (->> (db/q
        '[:find ?receipt ?effect-id ?tx
          :in $ ?agent
          :where
          [?receipt :seon.effect/to ?agent ?tx]
          [?receipt :seon.effect/id ?effect-id]
          (or-join [?receipt]
                   [?receipt :seon.effect/result-edn]
                   [?receipt :seon.effect/interrupted-at])
          (not [_ :seon.cluster.run/background-results ?receipt])]
        db agent-eid)
       (sort-by (fn [[_ effect-id tx]] [tx effect-id]))
       (mapv first)))

(defn open-call
  "Open one run for an agent, inside the transaction.
  Refuses when the run id already exists, or when the agent's
  current-run pointer is present (an agent holds at most one open run).
  Returns the run entity assertion plus the agent pointer assertion —
  BOTH derived from the one `::agent` ref in the request; there is no
  separate agent-id field to disagree with it."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map
                        [::id ::id]
                        [::agent ::agent]
                        [::trigger {:optional true} ::trigger]
                        [::opened-at ::opened-at]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id agent trigger opened-at]} request
        agent-eid (:db/id (db/pull db [:db/id] agent))
        run-tempid (str "seon.cluster.run/" id)
        background-results (unanswered-background-results db agent-eid)]
    (cond
      (nil? agent-eid) (refuse! `open-call ::no-such-agent request)
      (some? (current-run db id)) (refuse! `open-call ::run-exists request)

      (some? (:seon.cluster.agent/run
              (db/pull db [:seon.cluster.agent/run] agent-eid)))
      (refuse! `open-call ::agent-already-running request)

      ; the pointer and the run's own ::agent are the SAME resolved
      ; entity, so they cannot disagree
      :else [(cond-> {:db/id run-tempid
                      ::id id
                      ::agent agent-eid
                      ::opening-commit-id (db/commit-id db)
                      ::opened-at opened-at}
               trigger (assoc ::trigger trigger)
               (seq background-results)
               (assoc ::background-results background-results))
             {:db/id agent-eid :seon.cluster.agent/run run-tempid}])))

(defn claim-tx
  "Transaction data claiming `::id` for `::process`."
  {:malli/schema [:=> [:cat [:map
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
  {:malli/schema [:=> [:cat :seon.db/database-value
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
  {:malli/schema [:=> [:cat :seon.db/database-value
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
  {:malli/schema [:=> [:cat :seon.db/database-value
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
                 (db/pull db [:seon.cluster.agent/run] agent-eid))]
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
                             [::starting-ns {:optional true} ::starting-ns]
                             [::sources :seon.cluster.reply/sources]]]
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
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map {:closed true}
                        [::id ::id]
                        [::process ::process]
                        [::plan-digest ::plan-digest]
                        [::starting-ns {:optional true} ::starting-ns]
                        [::sources :seon.cluster.reply/sources]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id plan-digest sources starting-ns]} request
        run (held-run db `plan-call request)
        run-eid (:db/id run)
        agent-namespace
        (db/q '[:find ?namespace-name .
               :in $ ?agent
               :where
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?namespace :seon.ns/name ?namespace-name]]
             db (:db/id (::agent run)))
        requested-starting-namespace
        (cond
          (vector? starting-ns) (second starting-ns)
          starting-ns (:seon.ns/name
                       (db/pull db [:seon.ns/name] starting-ns)))
        starting-namespace
        (or requested-starting-namespace agent-namespace)]
    (when (some? (::plan-digest run))
      (refuse! `plan-call ::plan-frozen request))
    (when-not starting-namespace
      (refuse! `plan-call ::starting-namespace-missing request))
    (let [;; THE PARSE-TIME NAMESPACE IS PROJECTED, NEVER DERIVED HERE.
          ;; The splitter carries the reader's namespace-in-effect; this
          ;; freeze upserts that `:seon.ns` by its identity attribute and
          ;; points the form at it, so "who owns this form" is one join
          ;; away from the same entity an agent's assignment names. A
          ;; form the reader could not attribute simply has no ref, and
          ;; the routing owner falls back to the run's author.
          namespaces (into []
                           (comp (map #(or (:seon.ns/name %)
                                          starting-namespace))
                                 (keep identity)
                                 (distinct)
                                 (map (fn [namespace-name]
                                        {:db/id (str "namespace:"
                                                     namespace-name)
                                         :seon.ns/name namespace-name})))
                           (cons {:seon.ns/name starting-namespace} sources))
          forms (into []
                      (map-indexed
                       (fn [ordinal form]
                         (let [form-id (pr-str [id ordinal])
                               namespace-name (or (:seon.ns/name form)
                                                  starting-namespace)]
                           (cond-> {:db/id form-id
                                    :seon.cluster.run.form/id form-id
                                    :seon.cluster.run.form/run run-eid
                                    :seon.cluster.run.form/ordinal ordinal
                                    :seon.cluster.run.form/source
                                    (:seon.cluster.run.form/source form)}
                             namespace-name
                             (assoc :seon.cluster.run.form/ns
                                    (str "namespace:" namespace-name))))))
                      sources)]
      (into [[:db/add run-eid ::plan-digest plan-digest]
             [:db/add run-eid ::starting-ns
              (str "namespace:" starting-namespace)]]
            cat
            [namespaces
             forms
             (map (fn [form]
                    [:db/add run-eid ::forms (:db/id form)])
                  forms)]))))

(defn open-tx
  "Transaction data opening one run for an agent."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]
                             [::agent ::agent]
                             [::trigger {:optional true} ::trigger]
                             [::opened-at ::opened-at]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'open-call request]])

(defn system-run-tx
  "Open, claim, and plan one system-authored run for an existing agent.

  The caller owns the ordered sources and their digest. The ordinary run
  transaction functions retain every custody, pointer, and plan fence."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/system-run-request]
                  :seon.store/transaction-data]}
  [_database request]
  (let [{agent-id :seon.cluster.agent/id
         run-id ::id
         process ::process
         opened-at ::opened-at
         starting-ns ::starting-ns
         plan-digest ::plan-digest
         sources ::sources
         trigger ::trigger} request]
    (into [] cat
          [(open-tx
            (cond-> {::id run-id
                     ::agent [:seon.cluster.agent/id agent-id]
                     ::opened-at opened-at}
              trigger (assoc ::trigger trigger)))
           (claim-tx {::id run-id
                      ::process process
                      ::live-processes #{process}
                      ::now opened-at})
           (plan-tx {::id run-id
                     ::process process
                     ::starting-ns starting-ns
                     ::plan-digest plan-digest
                     ::sources sources})])))

(defn- current-receipt
  "The receipt identified by run and ordinal, or nil.
  Identity is `(pr-str [id ordinal])` — AT MOST ONE ATTEMPT PER FORM,
  EVER, held by the identity itself: re-execution across any custody
  change is unrepresentable, strictly stronger than the epoch this
  replaced (custody revision 2026-07-28)."
  [db id ordinal]
  (db/pull db '[*] [:seon.cluster.eval/id (pr-str [id ordinal])]))

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
   [:=> [:cat :seon.db/database-value
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
          [:seon.cluster.eval/result-blob {:optional true}
           :seon.cluster.eval/result-blob]
          [:seon.cluster.eval/result-size {:optional true}
           :seon.cluster.eval/result-size]
          [:seon.cluster.eval/error {:optional true}
           :seon.cluster.eval/error]
          [:seon.cluster.eval/interrupted-at {:optional true}
           :seon.cluster.eval/interrupted-at]
          [:seon.error/kind {:optional true} :seon.error/kind]
          [:seon.cluster.eval/output {:optional true}
           :seon.cluster.eval/output]
          [:seon.cluster.eval/ns {:optional true} :seon.cluster.eval/ns]
          [:seon.sci.eval/ending-ns {:optional true}
           :seon.sci.eval/ending-ns]
          [:seon.sci.eval/program-row {:optional true}
           :seon.sci.eval/program-row]]]
    [:vector :some]]}
  [request]
  [[:db.fn/call
    #'receipt-settle-call
    (if (and (:seon.cluster.eval/result-edn request)
             (not (contains? request :seon.cluster.eval/result-size)))
      (assoc request :seon.cluster.eval/result-size
             (long (count (:seon.cluster.eval/result-edn request))))
      request)]])

(defn receipt-refusal-tx
  "Transaction data terminalizing a receipt after its settlement refused.
  The caller supplies a bounded, registered-schema-valid flat error;
  there is no program row or disposition that can repeat the original
  refusal. This transition also closes the receipt's run, so the event
  derives no follow-up close pass. `terminal-refused!` checks the
  transaction's returned outcome rather than treating construction as
  proof that it committed."
  {:malli/schema
   [:=> [:cat
         [:map {:closed true}
          [::id ::id]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [::closed-at ::closed-at]
          [:seon.cluster.eval/result-edn
           :seon.cluster.eval/result-edn]
          [:seon.cluster.eval/result-blob {:optional true}
           :seon.cluster.eval/result-blob]
          [:seon.cluster.eval/result-size {:optional true}
           :seon.cluster.eval/result-size]
          [:seon.cluster.eval/error :seon.cluster.eval/error]
          [:seon.error/kind :seon.error/kind]]]
    [:vector :some]]}
  [request]
  [[:db.fn/call
    #'receipt-refusal-call
    (if (not (contains? request :seon.cluster.eval/result-size))
      (assoc request :seon.cluster.eval/result-size
             (long (count (:seon.cluster.eval/result-edn request))))
      request)]])

(defn- affected-schema-attributes
  "Database attributes derived by the affected schema forms."
  [projection affected]
  (set
   (schema.form/database-attributes
    (select-keys (:seon.schema.projection/forms projection) affected))))

(defn- current-schema-data-attributes
  "Installed affected database attributes carrying current datoms in `db`."
  [db projection schema-keys]
  (let [affected
        (schema/dependent-schema-keys projection schema-keys)]
    (into []
          (comp
           (filter #(contains? (:schema db) %))
           (filter #(seq (db/datoms db :aevt %))))
          (sort (affected-schema-attributes projection affected)))))

(defn- assert-schema-data-unused!
  "Refuse schema change while affected attributes carry current data."
  [db projection schema-keys]
  (let [attributes
        (current-schema-data-attributes db projection schema-keys)]
    (when (seq attributes)
      (throw
       (ex-info
        (str "Schema change refused: current data uses " attributes ".")
        {:seon.schema/error :seon.schema/current-data-blocks-change
         :seon.schema/keys schema-keys
         :seon.schema/data-attributes attributes
         :seon.error/kind :user-input})))))

(defn- schema-attribute-change-tx
  "Deterministic Datahike diff between complete schema projections."
  [db current-projection candidate-projection]
  (let [declarations-in
        (fn [projection]
          (if projection
            (into {}
                  (map (juxt :db/ident identity))
                  (schema.datahike/malli->datahike-schema-in
                   projection
                   (schema.datahike/database-attributes-in projection)))
            {}))
        current-declarations (declarations-in current-projection)
        candidate-declarations (declarations-in candidate-projection)
        changed-attributes
        (into #{}
              (filter #(not= (get current-declarations %)
                             (get candidate-declarations %)))
              (into (set (keys current-declarations))
                    (keys candidate-declarations)))
        retracted
        (into []
              (comp
               (filter #(contains? current-declarations %))
               (filter #(contains? (:schema db) %))
               (map (fn [attribute]
                      [:db.fn/retractEntity attribute])))
              (sort changed-attributes))]
    (into retracted
          (keep candidate-declarations)
          (sort changed-attributes))))

(defn- cardinality-many?
  [db attribute]
  (= :db.cardinality/many
     (get-in db [:schema attribute :db/cardinality])))

(defn- component-ref?
  [db attribute]
  (true? (get-in db [:schema attribute :db/isComponent])))

(defn- ref-attribute?
  [db attribute]
  (= :db.type/ref
     (get-in db [:schema attribute :db/valueType])))

(declare declared-map)

(defn- identity-ref
  [db value]
  (cond
    (and (vector? value) (= 2 (count value))) value
    (map? value) (identity-ref db (:db/id value))
    (number? value)
    (some (fn [identity-attribute]
            (when-some [identity-value
                        (get (db/pull db [identity-attribute] value)
                             identity-attribute)]
              [identity-attribute identity-value]))
          program/identity-attributes)
    :else value))

(defn- declared-one
  [db attribute value]
  (cond
    (component-ref? db attribute) (declared-map db value)
    (ref-attribute? db attribute) (identity-ref db value)
    :else value))

(defn- declared-value
  [db attribute value]
  (if (cardinality-many? db attribute)
    (into #{} (map #(declared-one db attribute %)) (or value []))
    (declared-one db attribute value)))

(defn- declared-map
  [db value]
  (into {}
        (keep (fn [[attribute attribute-value]]
                (when (not= :db/id attribute)
                  [attribute (declared-value db attribute attribute-value)])))
        value))

(defn- declared-content
  [db value]
  ;; Contract AST and arity rows are deterministic projections of the
  ;; declaration's `:seon.fn/spec`. Their component entity ids are database
  ;; mechanics, not declared content.
  (some-> value
          program/canonical-row
          (dissoc :seon.fn/arities :seon.fn/ast)
          (declared-map db)))

(defn- program-row-tx
  "Validate and exact-upsert one reader-produced durable declaration."
  [db request row]
  (if-let [deleted-identities (:seon.program/delete-identities row)]
    (let [schema-keys
          (into #{}
                (keep (fn [[identity-attribute identity-value]]
                        (when (= :seon.schema/key identity-attribute)
                          identity-value)))
                deleted-identities)
          current-projection
          (when (seq schema-keys) (schema/projection-from-database db))
          candidate-projection
          (reduce schema/projection-without-schema
                  current-projection
                  (sort schema-keys))
          _ (when (seq schema-keys)
              (assert-schema-data-unused!
               db current-projection schema-keys))
          schema-tx
          (if (seq schema-keys)
            (schema-attribute-change-tx
             db current-projection candidate-projection)
            [])
          declarations
          (into []
                (keep (fn [[identity-attribute identity-value]]
                        (when-let [declaration
                                   (db/pull db [:db/id]
                                           [identity-attribute identity-value])]
                          declaration)))
                deleted-identities)]
      (into schema-tx
            (map (fn [declaration]
                   [:db/retractEntity (:db/id declaration)]))
            declarations))
    (let [row (or (program/declaration-row
                   (assoc row :seon.schema.admission/source :agent)
                   :contracted)
                  (refuse! `receipt-settle-call
                           ::program-row-not-admitted request))
          [identity identity-value] (program/row-identity row)
          namespace-ref (or (:seon.fn/ns row)
                            (:seon.test/ns row))
          existing (when identity (db/pull db '[*] [identity identity-value]))]
      (when (and namespace-ref
                 (not (:db/id (db/pull db [:db/id] namespace-ref))))
        (refuse! `receipt-settle-call ::program-namespace-missing request))
      (let [current-projection
            (when (#{:seon.fn/sym :seon.schema/key} identity)
              (schema/projection-from-database db))
            schema-replacement?
            (and (= identity :seon.schema/key)
                 existing
                 (not= (:seon.schema/form existing)
                       (:seon.schema/form row)))
            _ (when schema-replacement?
                (assert-schema-data-unused!
                 db current-projection #{identity-value}))
            candidate-projection
            (case identity
              :seon.schema/key
              (schema/projection-with-schema
               current-projection identity-value
               (edn/read-string (:seon.schema/form row))
               {:seon.schema.admission/source :agent})

              :seon.fn/sym
              (schema/projection-with-function-contract
               current-projection (symbol identity-value)
               (edn/read-string (:seon.fn/spec row))
               {:seon.schema.admission/source :agent})

              nil)
            schema-declarations
            (if (= identity :seon.schema/key)
              (if schema-replacement?
                (schema-attribute-change-tx
                 db current-projection candidate-projection)
                (let [current-attributes
                      (schema.datahike/database-attributes-in
                       current-projection)
                      candidate-attributes
                      (schema.datahike/database-attributes-in
                       candidate-projection)
                      required
                      (into []
                            (comp
                             (remove (set current-attributes))
                             (remove #(contains? (:schema db) %)))
                            (sort candidate-attributes))]
                  (schema.datahike/malli->datahike-schema-in
                   candidate-projection required)))
              [])]
        (into
         schema-declarations
         (cond
           (nil? existing)
           [(assoc row :db/id (str (name identity) ":" identity-value))]

           (= (declared-content db existing) (declared-content db row))
           []

           :else
           (program/exact-replacement-tx existing row)))))))

(def ^:private receipt-terminal-attributes
  [:seon.cluster.eval/result-edn
   :seon.cluster.eval/result-blob
   :seon.cluster.eval/result-size
   :seon.cluster.eval/error
   :seon.cluster.eval/interrupted-at
   :seon.error/kind
   :seon.cluster.eval/output
   :seon.cluster.eval/ns
   :seon.sci.eval/ending-ns])

(defn- receipt-terminal-assertions
  "Terminal assertions present in `request`, targeting `receipt`."
  [receipt request]
  (into
   []
   (keep (fn [attribute]
           (when-some [value (get request attribute)]
             [:db/add (:db/id receipt) attribute value])))
   receipt-terminal-attributes))

(defn receipt-settle-call
  "Settle one running receipt, inside the transaction.
  The settle-once fence is PRESENCE: a receipt already carrying any
  terminal fact refuses `::receipt-terminal`, so a settled receipt
  never returns to running or changes outcome; a settle carrying no
  terminal fact refuses `::no-terminal-fact`, because \"settled with
  nothing settled\" is a caller bug."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:map {:closed true}
          [::id ::id]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.cluster.eval/result-edn {:optional true}
           :seon.cluster.eval/result-edn]
          [:seon.cluster.eval/result-blob {:optional true}
           :seon.cluster.eval/result-blob]
          [:seon.cluster.eval/result-size {:optional true}
           :seon.cluster.eval/result-size]
          [:seon.cluster.eval/error {:optional true}
           :seon.cluster.eval/error]
          [:seon.cluster.eval/interrupted-at {:optional true}
           :seon.cluster.eval/interrupted-at]
          [:seon.error/kind {:optional true} :seon.error/kind]
          [:seon.cluster.eval/output {:optional true}
           :seon.cluster.eval/output]
          [:seon.cluster.eval/ns {:optional true} :seon.cluster.eval/ns]
          [:seon.sci.eval/ending-ns {:optional true}
           :seon.sci.eval/ending-ns]
          [:seon.sci.eval/program-row {:optional true}
           :seon.sci.eval/program-row]]]
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
    (into
     (if-let [row (:seon.sci.eval/program-row request)]
       (program-row-tx db request row)
       [])
     (receipt-terminal-assertions receipt request))))

(defn receipt-refusal-call
  "Terminalize a running receipt after its terminal transaction refused.
  Never refuses on database STATE: a missing or already-terminal receipt
  contributes no transaction data, so the durable error recorder sharing
  this transaction still commits. The caller's bounded request already
  satisfies the registered attribute schemas. A running receipt gets the
  same terminal assertions as ordinary settlement and its run closes in
  this same transaction. Presence prevents overwrite, and closing here
  leaves no derived `:close` work whose wake could be mistaken for a
  retry."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:map {:closed true}
          [::id ::id]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [::closed-at ::closed-at]
          [:seon.cluster.eval/result-edn
           :seon.cluster.eval/result-edn]
          [:seon.cluster.eval/result-blob {:optional true}
           :seon.cluster.eval/result-blob]
          [:seon.cluster.eval/result-size {:optional true}
           :seon.cluster.eval/result-size]
          [:seon.cluster.eval/error :seon.cluster.eval/error]
          [:seon.error/kind :seon.error/kind]]]
    [:vector :some]]}
  [db {::keys [id]
       :seon.cluster.eval/keys [ordinal]
       :as request}]
  (let [receipt (current-receipt db id ordinal)
        run-eid (get-in receipt [:seon.cluster.eval/run :db/id])
        run (when run-eid (db/pull db '[*] run-eid))
        agent-eid (get-in run [::agent :db/id])
        pointer-eid (get-in (when agent-eid
                              (db/pull db [:seon.cluster.agent/run] agent-eid))
                            [:seon.cluster.agent/run :db/id])]
    (if (or (nil? receipt) (terminal? receipt))
      []
      (into
       (receipt-terminal-assertions receipt request)
       (concat
        (when (and run (open? run))
          (cond-> [[:db/add run-eid ::closed-at (::closed-at request)]]
            (::process run)
            (conj [:db/retract run-eid ::process (::process run)])))
        (when (= run-eid pointer-eid)
          [[:db/retract agent-eid :seon.cluster.agent/run run-eid]]))))))

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
  "Settle and close one interrupted run during boot recovery.
  When the run's holder is NOT a live process — dead, or absent
  entirely — every running receipt (one carrying NO terminal fact) gets
  `:seon.cluster.eval/interrupted-at` asserted at `::now`, every open effect
  receipt gets `:seon.effect/interrupted-at`, dead custody is released, the
  run is CLOSED at `::now`, and the owning agent's run pointer is retracted.
  EVERY settled receipt is left byte-untouched:
  the receipt read and the stamp share this one transaction, so a
  stale-basis recovery stamping a settled receipt is unrepresentable
  (custody revision, Revision 4). A run held by a live process, a
  closed run, and a missing run all need nothing — recovery is
  idempotent and never refuses. NOTHING here re-opens, re-plans, or
  re-executes.

  THE CLOSE IS THE WHOLE POINT (owner ruling 25(b), 2026-07-29). An
  interrupted run used to be left OPEN with its unsettled ordinals, so
  the next pass derived `:resume` and executed a plan suffix that had
  never started before the crash — with a fresh sci ctx that had lost
  every def, require and alias the prefix established, and with nothing
  stopping a capability-shaped form from making a post-crash external
  call. Both were live-reproduced
  (`research/repl-workflows-2026-07-29.md` §7). Closing here makes the
  whole class unrepresentable rather than handled: there is no cold
  resume to restore a context for, because there is no resume. This is
  the crash model's \"the agent adapts\" clause made literal — the
  interruption is in the agent's next context and the agent decides.

  IT NEVER REFUSES, and that includes wreckage. `close-call` refuses
  `::agent-pointer-broken` because a live close with a mismatched
  pointer is a caller bug; at recovery it is just what a dead process
  left behind, and a boot that threw on it would wedge the cluster it
  was trying to rescue. So the pointer is retracted exactly when it
  points at this run, and the run closes either way."
  {:malli/schema [:=> [:cat :seon.db/database-value
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
      (let [agent-eid (:db/id (::agent run))
            pointer (when agent-eid
                      (:seon.cluster.agent/run
                       (db/pull db [:seon.cluster.agent/run] agent-eid)))
            interrupted
            (into (interrupt-stamps db (:db/id run) now)
                  (effect/interruption-stamps db (:db/id run) now))]
        (cond-> (into interrupted
                      (when (some? holder)
                        (retract-custody run)))
          true
          (conj [:db/add (:db/id run) ::closed-at now])
          ;; exactly when it points HERE — see the docstring: recovery
          ;; settles wreckage, it does not refuse it
          (= (:db/id run) (:db/id pointer))
          (conj [:db/retract agent-eid :seon.cluster.agent/run
                 (:db/id run)]))))))

;;; ---------------------------------------------------------------------------
;;; The family default renders — what a run, a form and a receipt LOOK
;;; LIKE to an agent reading its own neighbourhood.
;;;
;;; Declared on the registered entity maps in `resources/seon/schema.edn`
;;; (`:seon.render/ai` properties), which is `seon.schema`'s own idiom
;;; for `:seon.fn`, `:seon.ns` and `:seon.schema` — so a family declares
;;; its default where it declares everything else about itself, and
;;; `seon.render` finds it with no table.
;;;
;;; Each is a PLAIN FUNCTION: one unit map in, prose out. It reads the
;;; pulled entity it was handed and the database value riding beside it,
;;; and nothing else. `:seon.render/distance` is on the unit and these
;;; do not read it — a projection that ignores the budget is correct,
;;; and how far the walk goes is the walk's business.
;;;
;;; THEY CARRY THE DOCTRINE, and that is the point of writing them well.
;;; "Nothing was retried" used to be a sentence in one context block that
;;; only the prompt ever saw; here it belongs to the run, so every reader
;;; of a run — a prompt, a page, a debug view, another agent's
;;; neighbourhood — is told the same true thing by the same function.
;;; ---------------------------------------------------------------------------

(defn- run-forms
  [db run-eid]
  (db/q '[:find [(pull ?form [*]) ...]
         :in $ ?run
         :where [?form :seon.cluster.run.form/run ?run]]
       db run-eid))

(defn- run-receipts
  [db run-eid]
  (db/q '[:find [(pull ?receipt [*]) ...]
         :in $ ?run
         :where [?receipt :seon.cluster.eval/run ?run]]
       db run-eid))

(defn- receipt-value
  [receipt]
  (some-> (:seon.cluster.eval/result-edn receipt)
          (#(try (edn/read-string %)
                 (catch Throwable _
                   nil)))))

(defn- unfinished-warning
  "The first form recovery closed before it started, and the missing count."
  [forms receipts]
  (let [terminal-ordinals
        (into #{}
              (comp (filter terminal?)
                    (map :seon.cluster.eval/ordinal))
              receipts)
        last-value (some->> receipts
                            (sort-by :seon.cluster.eval/ordinal)
                            last
                            receipt-value)
        missing
        (sort
         (remove terminal-ordinals
                 (map :seon.cluster.run.form/ordinal forms)))]
    ;; A completed/wait disposition deliberately closes the run and
    ;; leaves any later authored forms unstarted. That is not recovery.
    (when-let [ordinal (when-not (contains? #{:completed :wait}
                                            (:my.run/disposition last-value))
                         (first missing))]
      {:seon.cluster.eval/ordinal ordinal
       ::missing-results (count missing)})))

(defn render-ai
  "`:seon.render/ai` — one run, as the agent's own history of it.

  STATE IS PRESENCE, read exactly as the model stores it: a run with a
  process is held, one with a `closed-at` is over, one with an error
  never got a plan, and a cut fold is derived from its forms and
  receipts through the one `interrupted-warning`. There is no status
  attribute to restate and this invents none.

  The interruption sentences moved HERE from the retired `:interruption`
  context block: they are facts about a run, so they belong to the run's
  own lens and reach every consumer rather than one prompt. Both crash
  shapes say MAY, because rows 6 and 7 of the crash walk are
  indistinguishable from the facts and a confident claim would be a lie
  the agent then reasons from."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [db (get unit :seon.db/db)
        id (get unit ::id)]
    (when id
      (let [opened (get unit ::opened-at)
            receipts (when db (run-receipts db (:db/id unit)))
            forms (when db (run-forms db (:db/id unit)))
            cut (when db (interrupted-warning forms receipts))
            never-started
            (when (and db
                       (::closed-at unit)
                       (::plan-digest unit)
                       (nil? cut))
              (unfinished-warning forms receipts))
            ;; THE PAUSE NOTE IS A CONDITION OF THE RUN, which is why it
            ;; is read here and not only on the receipt: a run is one hop
            ;; from its agent and a receipt is two, so an agent asking
            ;; for the ordinary reach must still be handed the note it
            ;; left itself. The disposition IS the last form's admitted
            ;; value — already durable — so this reads it back and
            ;; stores nothing.
            note (some->> receipts
                          (sort-by :seon.cluster.eval/ordinal)
                          last
                          receipt-value
                          (#(when (and (map? %)
                                       (= :wait (:my.run/disposition %)))
                              (:my.run/note %))))
            state
            (cond
              cut (str "It was interrupted at form "
                       (:seon.cluster.eval/ordinal cut)
                       " — that form's effect may have happened, "
                       (::missing-results cut)
                       " result(s) are missing, and nothing was retried.")

              never-started
              (str "It was interrupted before form "
                   (:seon.cluster.eval/ordinal never-started)
                   " started — "
                   (::missing-results never-started)
                   " form(s) never ran, and nothing was retried.")

              (::error unit)
              (str "It did not run: " (::error unit)
                   " Nothing was retried, and nothing it asked for ran.")

              (and (nil? (::plan-digest unit)) (some? (::closed-at unit)))
              (str "It was interrupted before the reply arrived, and "
                   "nothing was retried.")

              note (str "It paused, leaving this note: " note)

              (some? (::closed-at unit)) "It completed."
              (some? (::process unit)) (str "It is running now, held by "
                                            (::process unit) ".")
              :else "It is open.")]
        ;; `pr-str` and never the platform's `toString`: an inst printed
        ;; through the default formatter carries the RENDERING machine's
        ;; timezone and locale, so two derivations of one database value
        ;; would differ by where they ran — which equality suppression
        ;; and re-derivable capture both forbid. EDN is also the truth an
        ;; agent already reads.
        (str "Run " id (when opened (str ", opened " (pr-str opened))) ". "
             state)))))

(defn render-html
  "`:seon.render/html` — one run, with the same facts as its AI twin."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [text (render-ai unit)]
    [:article {:class "seon-family-entry seon-run-entry"}
     [:p text]]))

(defn render-form-ai
  "`:seon.render/ai` — one planned form, as the agent wrote it."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [source (get unit :seon.cluster.run.form/source)]
    (str "Form " (get unit :seon.cluster.run.form/ordinal) ": " source)))

(defn render-form-html
  "`:seon.render/html` — one form, with the same facts as its AI twin."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [text (render-form-ai unit)]
    [:article {:class "seon-family-entry seon-form-entry"}
     [:p text]]))

(defn render-receipt-ai
  "`:seon.render/ai` — the exact output and bare result of one form.

  Printed output precedes the result. Failures reconstruct Clojure's
  standard concise REPL error from the recorded `ex-triage` data.
  A receipt with no terminal value says nothing: a running REPL has not
  printed a result, and recovery interruption is not English narration."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (let [result (get unit :seon.cluster.eval/result-edn)
        output (get unit :seon.cluster.eval/output)
        triage-edn (get unit :seon.cluster.eval/triage-edn)
        error
        (when (get unit :seon.cluster.eval/error)
          (or
           (when triage-edn
             (try
               (-> triage-edn
                   edn/read-string
                   main/ex-str
                   str/trim-newline)
               (catch Throwable _
                 nil)))
           (get unit :seon.cluster.eval/error)))
        terminal (or error result)]
    (when (or (seq output) (some? terminal))
      (str output
           (when (and (seq output)
                      (some? terminal)
                      (not (str/ends-with? output "\n")))
             "\n")
           terminal))))

(defn render-receipt-html
  "`:seon.render/html` — one receipt, with the same facts as its AI twin."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [text (render-receipt-ai unit)]
    [:article {:class "seon-family-entry seon-receipt-entry"}
     [:p text]]))
