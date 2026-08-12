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
    `::interrupted-at` on the RUN and
    `:seon.cluster.eval/interrupted-at` on dangling receipts (those
    carrying no terminal fact), closes every open prior-process run,
    releases its custody, and retracts the agent pointer. Every settled
    receipt stays untouched. A form has AT MOST ONE settlement, ever.
    RECOVERY MARKS WHAT IT INTERRUPTED — the crash model's honesty
    clause — so \"which runs did the last recovery cut?\" is a query
    over `::interrupted-at` and never a process-local boot counter. The
    run stamp is not derivable from the receipt stamps: a process that
    died before its first receipt row existed leaves none.

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
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.fn :as seon.fn]
            [seon.program :as program]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form])
  (:import [java.nio.charset StandardCharsets]))

;;; ---------------------------------------------------------------------------
;;; The agent pointer — owned HERE. Port manifest: old `:seon.agent/*`
;;; attrs are DEAD for this model; the agent entity is re-decided at its
;;; own rung. The run model needs exactly an identity to point from and
;;; the current-run pointer opens race on.
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

(defn- result-blob-threshold
  [db]
  (db/q '[:find ?threshold .
          :where [_ :seon.config.eval.result/blob-threshold ?threshold]]
        db))

(defn- store-def-values!
  [connection evaluation]
  (let [threshold (result-blob-threshold @connection)]
    (reduce
     (fn [projection candidate]
       (if-let [serialized
                (blob/store-faithful-edn
                 (:seon.sci.eval/value candidate))]
         (let [size (long (count serialized))
               staged (when (and threshold (> size threshold))
                        (blob/stage! connection serialized))]
           (cond->
            (update projection :seon.sci.eval/defs
                    conj
                    (cond-> (assoc candidate :seon.def/size size)
                      staged
                      (assoc :seon.def/blob (:seon.blob/digest staged))

                      (or (nil? threshold) (<= size threshold))
                      (assoc :seon.def/value-edn serialized)))
             staged (update :seon.blob/staged-writes conj staged)))
         (update projection :seon.sci.eval/defs conj candidate)))
     {:seon.sci.eval/defs []
      :seon.blob/staged-writes []}
     (:seon.sci.eval/defs evaluation))))

(defn- result-window-page-size
  [db]
  (db/q '[:find ?size .
          :where [_ :seon.render.value/max-collection ?size]]
        db))

(def ^:private result-blob-fixed-growth-bytes 743)

(defn- utf8-size
  [value]
  (alength (.getBytes ^String value StandardCharsets/UTF_8)))

(defn- result-blob-smaller?
  [result-edn window-edn]
  (< (+ result-blob-fixed-growth-bytes
        (* 4 (utf8-size window-edn))
        (utf8-size result-edn))
     (* 4 (utf8-size result-edn))))

(defn- settlement-result
  [cluster evaluation]
  (if-let [result-edn (:seon.cluster.eval/result-edn evaluation)]
    (let [connection (:seon.db/connection cluster)
          result-size (long (count result-edn))
          database @connection
          threshold (result-blob-threshold database)
          window-edn
          (when (and threshold (> result-size threshold))
            (render.value/result-window-edn
             {:seon.sci.admit/caps (:seon.sci.admit/caps cluster)
              :seon.render.value/options
              {:seon.render.value/max-collection
               (result-window-page-size database)}}
             result-edn))]
      (if (and window-edn (result-blob-smaller? result-edn window-edn))
        (let [staged (blob/stage! connection result-edn)]
          (assoc evaluation
                 :seon.cluster.eval/result-edn window-edn
                 :seon.cluster.eval/result-blob (:seon.blob/digest staged)
                 :seon.cluster.eval/result-size result-size
                 :seon.blob/staged-writes [staged]))
        (assoc evaluation :seon.cluster.eval/result-size result-size)))
    evaluation))

(defn settlement-projection
  "Project an evaluation into receipt, defs, and staged-blob data."
  {:malli/schema
   [:=> [:cat :seon.cluster.loop/cluster :seon.cluster.loop/evaluation]
    [:tuple :map :map [:vector :seon.blob/staged-write]]]}
  [cluster evaluation]
  (let [receipt (settlement-result cluster evaluation)
        defs (store-def-values! (:seon.db/connection cluster) evaluation)]
    [(dissoc receipt :seon.blob/staged-writes)
     (merge evaluation
            (dissoc defs :seon.blob/staged-writes))
     (into (vec (:seon.blob/staged-writes receipt))
           (:seon.blob/staged-writes defs))]))

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

(defn opening-db
  "The database value this run opened on.

  The opening transaction is derived from the `opened-at` datom rather than
  stored as another run attribute. Datahike `as-of` includes that transaction,
  so the trigger that opened the run is visible and every later transaction is
  absent by construction."
  {:malli/schema [:=> [:cat :seon.db/database-value ::id]
                  [:or :seon.db/database-value :seon.error/value]]}
  [database id]
  (let [opening-tx
        (db/q '[:find ?tx .
                :in $ ?id
                :where
                [?run :seon.cluster.run/id ?id]
                [?run :seon.cluster.run/opened-at _ ?tx]]
              database id)]
    (if opening-tx
      (db/as-of database opening-tx)
      {:seon.error/kind ::missing-opening-datom
       :seon.error/message "The run has no opening datom."
       :seon.error/data {::id id}})))

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
  "Everything a dead process's custody leaves behind, marked at `now`.

  ONE `interrupted-at` per running receipt, AND ONE ON THE RUN. The run
  stamp is not a summary of the receipt stamps and cannot be derived
  from them: a process that died before its first form settled a
  receipt row leaves NO receipt to stamp, and that run was
  indistinguishable by query from a run that closed normally
  (whole-system-arc observer, 2026-08-08 — `945f3226` closed by
  recovery with one form, zero receipts, no error, and no marker
  anywhere durable). The crash model's honesty clause is that recovery
  marks what it interrupted, so recovery records the fact it alone
  knows. Presence is the state; there is no status label and nothing
  reads a boot counter to answer \"which runs did recovery cut?\"."
  [db run-eid now]
  (conj (mapv (fn [receipt]
                [:db/add (:db/id receipt)
                 :seon.cluster.eval/interrupted-at now])
              (running-receipts db run-eid))
        [:db/add run-eid ::interrupted-at now]))

;; The *-tx wrappers reference their *-call VARS (#'f): datahike applies
;; the var, so redefining a transition against the running system updates
;; behavior immediately — the flow-dynamics live-update pattern.
(declare claim-call release-call close-call plan-call refresh-call
         open-call receipt-start-call receipt-settle-call
         recover-call clear-defs-call generation-complete-call)

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
                        [::opening-commit-id {:optional true}
                         ::opening-commit-id]
                        [::opened-at ::opened-at]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id agent trigger opening-commit-id opened-at]} request
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
                      :seon.cluster.work/situation :call
                      ::opening-commit-id
                      (or opening-commit-id (db/commit-id db))
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
    RECOVERY, one shape: stamp the run and that custody's running
    receipts `interrupted-at` at `::now`, then retract/assert
    `::process` — one transaction, so the intermediate state never
    exists (custody revision, Revision 3). The run stamp is the same
    one `recover-call` writes, from the same `interrupt-stamps`: both
    paths recover a dead process's custody, so both leave the same
    durable evidence that they did.
  There are no observed-* fields; the mid-transaction db is the only
  truth consulted."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map
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
  {:malli/schema [:=> [:cat [:map
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
                       [:map
                        [::id ::id]
                        [::process ::process]]]
                  [:vector :some]]}
  [db request]
  (retract-custody (held-run db `release-call request)))

(defn close-tx
  "Transaction data closing the run held by `::process`."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]
                             [::process ::process]
                             [::closed-at ::closed-at]
                             [::undisposed-at {:optional true}
                              ::undisposed-at]]]
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
                       [:map
                        [::id ::id]
                        [::process ::process]
                        [::closed-at ::closed-at]
                        [::undisposed-at {:optional true}
                         ::undisposed-at]]]
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
    (cond-> (conj (retract-custody run)
                  [:db/add (:db/id run) ::closed-at (::closed-at request)]
                  [:db/retract agent-eid :seon.cluster.agent/run (:db/id run)])
      (::undisposed-at request)
      (conj [:db/add (:db/id run) ::undisposed-at
             (::undisposed-at request)]))))

(defn- plan-tx-for-author
  [author request]
  [[:db.fn/call #'plan-call
    (assoc request :seon.cluster.run.form/author author)]])

(defn plan-tx
  "Transaction data freezing one agent-authored form plan on the held run."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]
                             [::process ::process]
                             [::plan-digest ::plan-digest]
                             [::starting-ns {:optional true} ::starting-ns]
                             [::sources :seon.cluster.reply/sources]]]
                  [:vector :some]]}
  [request]
  (plan-tx-for-author :agent request))

(defn- system-plan-tx
  "Transaction data freezing one system-authored plan on the held run."
  [request]
  (plan-tx-for-author :system request))

;;; ---------------------------------------------------------------------------
;;; The two identities a (run, ordinal) pair mints
;;; ---------------------------------------------------------------------------

;;; ONE (run, ordinal) PAIR NAMES TWO ENTITIES — the frozen form and its
;;; receipt — AND BOTH IDENTITY ATTRIBUTES ARE `:db.unique/identity`. An
;;; agent holds only the ordinary string, so
;;; `seon.cluster.message/resolve-about` resolves it against EVERY
;;; installed identity attribute and makes a tie a refusal rather than a
;;; guess. Minting the same string for both families therefore made
;;; `my.message/decline` (and any `my.message/send` naming a problem)
;;; refuse `:seon.cluster.message/ambiguous-about` for every problem that
;;; ever existed — the form freeze always commits the twin.
;;;
;;; The receipt's bare pair is the AGENT-FACING name: it is what
;;; `seon.cluster.work/problem-id` returns and what the assignment
;;; message asks an owner to repair. The form entity is internal, so the
;;; form is the one that qualifies its string with its own attribute.
;;; The law both derivations keep: a derived identity string names at
;;; most one entity across all identity attributes.

(defn receipt-identity
  "The `:seon.cluster.eval/id` of the attempt at one run's ordinal.
  Agent-facing: this is the problem identity an owner is asked to repair,
  and the one `seon.cluster.work/problem-id` returns."
  {:malli/schema [:=> [:cat ::id :seon.cluster.eval/ordinal]
                  :seon.cluster.eval/id]}
  [run-id ordinal]
  (pr-str [run-id ordinal]))

(defn form-identity
  "The `:seon.cluster.run.form/id` of one run's frozen ordinal.
  Qualified by its own attribute so it can never collide with the
  receipt identity the same pair mints."
  {:malli/schema [:=> [:cat ::id :seon.cluster.run.form/ordinal]
                  :seon.cluster.run.form/id]}
  [run-id ordinal]
  (pr-str [:seon.cluster.run.form/id run-id ordinal]))

(defn plan-call
  "Freeze the plan, inside the transaction.
  Assert the digest and the
  owned ordered form entities. Refuses unless the run is open, held by
  exactly `::process`, and has NO existing `::plan-digest` —
  concurrent replies are mutually exclusive because the second one
  reads the first one's digest and refuses."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:map
                        [::id ::id]
                        [::process ::process]
                        [::plan-digest ::plan-digest]
                        [::starting-ns {:optional true} ::starting-ns]
                        [:seon.cluster.run.form/author
                         :seon.cluster.run.form/author]
                        [::sources :seon.cluster.reply/sources]]]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id plan-digest sources starting-ns]
         author :seon.cluster.run.form/author} request
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
        (or requested-starting-namespace agent-namespace)
        existing-form-count
        (long
         (or (db/q '[:find (count ?form) .
                    :in $ ?run
                    :where
                    [?form :seon.cluster.run.form/run ?run]]
                  db run-eid)
             0))]
    (when (some? (::plan-digest run))
      (refuse! `plan-call ::plan-frozen request))
    (when-not (= :call (:seon.cluster.work/situation run))
      (refuse! `plan-call ::not-call-situation request))
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
                       (fn [reply-ordinal form]
                         (let [ordinal (long (+ existing-form-count
                                                reply-ordinal))
                               form-id (form-identity id ordinal)
                               namespace-name (or (:seon.ns/name form)
                                                  starting-namespace)]
                           (cond-> {:db/id form-id
                                    :seon.cluster.run.form/id form-id
                                    :seon.cluster.run.form/run run-eid
                                    :seon.cluster.run.form/ordinal ordinal
                                    :seon.cluster.run.form/author author
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
                             [::opening-commit-id {:optional true}
                              ::opening-commit-id]
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
  [database request]
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
                     ::opening-commit-id (db/commit-id database)
                     ::opened-at opened-at}
              trigger (assoc ::trigger trigger)))
           (claim-tx {::id run-id
                      ::process process
                      ::live-processes #{process}
                      ::now opened-at})
           (system-plan-tx {::id run-id
                            ::process process
                            ::starting-ns starting-ns
                            ::plan-digest plan-digest
                            ::sources sources})])))

(defn append-generated-call
  "Append exactly one system-authored form to a held generated run.

  The requested ordinal must equal the number of forms already present. For
  every noninitial append, the preceding ordinal must already have a terminal
  receipt. Those two facts make prefix growth atomic and prevent both gaps and
  generation ahead of execution. A digest-backed run cannot enter this path."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/generated-form-request]
                  [:vector :some]]}
  [db request]
  (let [{::keys [id]
         ordinal :seon.cluster.run.form/ordinal
         source :seon.cluster.run.form/source
         namespace-name :seon.ns/name} request
        held (held-run db `append-generated-call request)
        run-eid (:db/id held)
        forms (db/q '[:find ?ordinal ?source ?namespace-name
                     :in $ ?run
                     :where
                     [?form :seon.cluster.run.form/run ?run]
                     [?form :seon.cluster.run.form/ordinal ?ordinal]
                     [?form :seon.cluster.run.form/source ?source]
                     [?form :seon.cluster.run.form/ns ?namespace]
                     [?namespace :seon.ns/name ?namespace-name]]
                   db run-eid)
        expected (long (count forms))
        prior-terminal?
        (or (zero? ordinal)
            (some?
             (db/q '[:find ?receipt .
                    :in $ ?run ?ordinal
                    :where
                    [?receipt :seon.cluster.eval/run ?run]
                    [?receipt :seon.cluster.eval/ordinal ?ordinal]
                    (or [?receipt :seon.cluster.eval/result-edn _]
                        [?receipt :seon.cluster.eval/error _]
                        [?receipt :seon.cluster.eval/interrupted-at _])]
                  db run-eid (dec ordinal))))]
    (when (some? (::plan-digest held))
      (refuse! `append-generated-call ::plan-frozen request))
    (when-not (= (if (zero? ordinal) :call :generate)
                 (:seon.cluster.work/situation held))
      (refuse! `append-generated-call ::not-generate-situation request))
    (when-not (= expected ordinal)
      (refuse! `append-generated-call ::generated-ordinal request))
    (when-not prior-terminal?
      (refuse! `append-generated-call ::generated-prefix-unsettled request))
    (let [form-id (form-identity id ordinal)
          namespace-id (str "namespace:" namespace-name)]
      (cond-> [{:db/id namespace-id :seon.ns/name namespace-name}
               {:db/id form-id
                :seon.cluster.run.form/id form-id
                :seon.cluster.run.form/run run-eid
                :seon.cluster.run.form/ordinal ordinal
                :seon.cluster.run.form/author :system
                :seon.cluster.run.form/source source
                :seon.cluster.run.form/ns namespace-id}
               [:db/add run-eid ::forms form-id]]
        (zero? ordinal)
        (into [[:db/retract run-eid :seon.cluster.work/situation :call]
               [:db/add run-eid :seon.cluster.work/situation :generate]
               [:db/add run-eid ::starting-ns namespace-id]])))))

(defn append-generated-tx
  "Transaction data appending one dependency-ready generated form."
  {:malli/schema [:=> [:cat :seon.cluster.run/generated-form-request]
                  :seon.store/transaction-data]}
  [request]
  [[:db.fn/call #'append-generated-call request]])

(defn generation-complete-call
  "Advance a held generated run to its model-call situation.

  The generated prefix must be non-empty and terminal through its final
  ordinal. The dedicated transition has no caller-supplied from/to values, so
  no other situation edge can be requested or accidentally constructed."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/generation-complete-request]
                  [:vector :some]]}
  [db request]
  (let [run (held-run db `generation-complete-call request)
        run-eid (:db/id run)
        form-count
        (long
         (or (db/q '[:find (count ?form) .
                    :in $ ?run
                    :where
                    [?form :seon.cluster.run.form/run ?run]]
                  db run-eid)
             0))
        final-ordinal (dec form-count)
        final-terminal?
        (and (pos? form-count)
             (some?
              (db/q '[:find ?receipt .
                     :in $ ?run ?ordinal
                     :where
                     [?receipt :seon.cluster.eval/run ?run]
                     [?receipt :seon.cluster.eval/ordinal ?ordinal]
                     (or [?receipt :seon.cluster.eval/result-edn _]
                         [?receipt :seon.cluster.eval/error _]
                         [?receipt :seon.cluster.eval/interrupted-at _])]
                   db run-eid final-ordinal)))]
    (when-not (= :generate (:seon.cluster.work/situation run))
      (refuse! `generation-complete-call ::not-generate-situation request))
    (when-not final-terminal?
      (refuse! `generation-complete-call ::generated-prefix-unsettled request))
    [[:db/retract run-eid :seon.cluster.work/situation :generate]
     [:db/add run-eid :seon.cluster.work/situation :call]]))

(defn generation-complete-tx
  "Transaction data advancing a generated run to its model call."
  {:malli/schema
   [:=> [:cat :seon.cluster.run/generation-complete-request]
    :seon.store/transaction-data]}
  [request]
  [[:db.fn/call #'generation-complete-call request]])

(defn generated-run-tx
  "Open, claim, and append the first form of a generated system run."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.run/generated-run-request]
                  :seon.store/transaction-data]}
  [database request]
  (let [{agent-id :seon.cluster.agent/id
         run-id ::id
         process ::process
         opened-at ::opened-at
         starting-ns ::starting-ns
         source :seon.cluster.run.form/source
         trigger ::trigger} request
        namespace-name (if (vector? starting-ns)
                         (second starting-ns)
                         starting-ns)]
    (into [] cat
          [(open-tx
            (cond-> {::id run-id
                     ::agent [:seon.cluster.agent/id agent-id]
                     ::opening-commit-id (db/commit-id database)
                     ::opened-at opened-at}
              trigger (assoc ::trigger trigger)))
           (claim-tx {::id run-id
                      ::process process
                      ::live-processes #{process}
                      ::now opened-at})
           (append-generated-tx
            {::id run-id
             ::process process
             :seon.cluster.run.form/ordinal 0
             :seon.cluster.run.form/source source
             :seon.ns/name namespace-name})])))

(defn refresh-tx
  "Transaction data refreshing one prior system-authored form."
  {:malli/schema
   [:=> [:cat :seon.cluster.run.form/id] [:vector :some]]}
  [prior-form-id]
  [[:db.fn/call #'refresh-call prior-form-id]])

(defn- current-transaction-instant
  "The transaction instant already allocated before a transaction call runs."
  [db]
  (:db/txInstant (db/pull db [:db/txInstant] (inc (db/basis-t db)))))

(defn- refresh-run-id
  [db prior-form-id]
  (str "refresh:"
       (schema/sha-256
        [(.getBytes (pr-str [prior-form-id (db/commit-id db)])
                    StandardCharsets/UTF_8)])))

(defn refresh-call
  "Append one ordinary system run from a prior refreshable form."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.cluster.run.form/id]
    [:vector :some]]}
  [db prior-form-id]
  (let [request {:seon.cluster.run.form/id prior-form-id}
        prior
        (db/pull db
                 '[* {:seon.cluster.run.form/run
                      [:db/id :seon.cluster.run/id
                       {:seon.cluster.run/agent
                        [:db/id :seon.cluster.agent/id]}]}
                   {:seon.cluster.run.form/ns [:db/id :seon.ns/name]}]
                 [:seon.cluster.run.form/id prior-form-id])]
    (when-not (:db/id prior)
      (refuse! `refresh-call ::no-such-form request))
    (when-not (= :system (:seon.cluster.run.form/author prior))
      (refuse! `refresh-call ::refresh-agent-authored request))
    (let [prior-run (:seon.cluster.run.form/run prior)
          prior-run-id (::id prior-run)
          ordinal (:seon.cluster.run.form/ordinal prior)
          receipt
          (db/pull db
                   '[* {:seon.cluster.eval/read-evidence [*]}]
                   [:seon.cluster.eval/id
                    (receipt-identity prior-run-id ordinal)])
          successor
          (db/q '[:find ?successor .
                  :in $ ?prior
                  :where
                  [?successor :seon.cluster.run.form/refreshes ?prior]]
                db (:db/id prior))]
      (when-not (and receipt (terminal? receipt))
        (refuse! `refresh-call ::refresh-receipt-not-terminal request))
      (when-not (seq (:seon.cluster.eval/read-evidence receipt))
        (refuse! `refresh-call ::refresh-read-evidence-missing request))
      (when successor
        (refuse! `refresh-call ::refresh-successor-exists request))
      (let [run-id (refresh-run-id db prior-form-id)
            run-tempid (str "seon.cluster.run/" run-id)
            form-id (form-identity run-id 0)
            namespace (:seon.cluster.run.form/ns prior)
            source (:seon.cluster.run.form/source prior)
            opened-at (current-transaction-instant db)
            plan-digest
            (schema/sha-256
             [(.getBytes (pr-str [source (:seon.ns/name namespace)])
                         StandardCharsets/UTF_8)])
            open-rows
            (open-call db
                       {::id run-id
                        ::agent (:db/id (::agent prior-run))
                        ::opening-commit-id (db/commit-id db)
                        ::opened-at opened-at})
            form {:db/id form-id
                  :seon.cluster.run.form/id form-id
                  :seon.cluster.run.form/run run-tempid
                  :seon.cluster.run.form/ordinal 0
                  :seon.cluster.run.form/author :system
                  :seon.cluster.run.form/source source
                  :seon.cluster.run.form/ns (:db/id namespace)
                  :seon.cluster.run.form/refreshes (:db/id prior)}]
        (into open-rows
              [[:db/add run-tempid ::plan-digest plan-digest]
               [:db/add run-tempid ::starting-ns (:db/id namespace)]
               form
               [:db/add run-tempid ::forms form-id]])))))

(defn- current-receipt
  "The receipt identified by run and ordinal, or nil.
  Identity is `(pr-str [id ordinal])` — AT MOST ONE ATTEMPT PER FORM,
  EVER, held by the identity itself: re-execution across any custody
  change is unrepresentable, strictly stronger than the epoch this
  replaced (custody revision 2026-07-28)."
  [db id ordinal]
  (db/pull db '[*] [:seon.cluster.eval/id (receipt-identity id ordinal)]))

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
   [:=> [:cat [:map
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
         [:map
          [::id ::id]
          [:seon.cluster.eval/ordinal :seon.cluster.eval/ordinal]
          [:seon.cluster.eval/at :seon.cluster.eval/at]]]
    [:vector :some]]}
  [db request]
  (let [{::keys [id]
         :seon.cluster.eval/keys [ordinal at]} request
        run (receipt-run db `receipt-start-call request)
        receipt-id (receipt-identity id ordinal)]
    (when (some? (current-receipt db id ordinal))
      (refuse! `receipt-start-call ::receipt-exists request))
    [{:seon.cluster.eval/id receipt-id
      :seon.cluster.eval/run (:db/id run)
      :seon.cluster.eval/ordinal ordinal
      :seon.cluster.eval/at at}]))

(defn- settlement-form
  [database request]
  (when-let [form-eid
             (db/q '[:find ?form .
                     :in $ ?run-id ?ordinal
                     :where
                     [?run :seon.cluster.run/id ?run-id]
                     [?form :seon.cluster.run.form/run ?run]
                     [?form :seon.cluster.run.form/ordinal ?ordinal]]
                   database (::id request)
                   (:seon.cluster.eval/ordinal request))]
    (let [form
          (db/pull database
                   [:db/id :seon.cluster.run.form/source
                    :seon.cluster.run.form/ns]
                   form-eid)]
      (update form :seon.cluster.run.form/ns :db/id))))

(defn- analyze-settlement
  [database request]
  (if-let [form (settlement-form database request)]
    (let [[form-facts program-row]
          (seon.fn/analyze-form
           database
           (:seon.cluster.run.form/source form)
           (:seon.cluster.run.form/ns form)
           (:seon.program/row request))]
      (cond-> (assoc request ::form-facts
                     (assoc form-facts :db/id (:db/id form)))
        program-row (assoc :seon.program/row program-row)))
    request))

(defn receipt-settle-tx
  "Transaction data settling one running receipt exactly once.
  Settling IS asserting terminal facts: `result-edn`, `error`, and/or
  `interrupted-at` — at least one, and there is no status label."
  {:malli/schema
   [:function
    [:=> [:cat :seon.cluster.eval/settle-request]
     [:vector :some]]
    [:=> [:cat :seon.db/database-value
          :seon.cluster.eval/settle-request]
     [:vector :some]]]}
  ([request]
   (receipt-settle-tx nil request))
  ([database request]
   (let [request
         (cond-> request
           (and (:seon.cluster.eval/result-edn request)
                (not (contains? request :seon.cluster.eval/result-size)))
           (assoc :seon.cluster.eval/result-size
                  (long (count (:seon.cluster.eval/result-edn request))))
           database (->> (analyze-settlement database)))]
     [[:db.fn/call #'receipt-settle-call request]])))

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
  (when-some [canonical (program/canonical-row value)]
    (declared-map db (dissoc canonical :seon.fn/arities :seon.fn/ast))))

(defn- declaration-written-by-run?
  "True when the declaration identified by `identity-attribute`/`identity-value`
  and one receipt of `run-id` were asserted by the same terminal transaction.

  A run's own write is never a concurrent change, whichever declaration family
  it belongs to. The question is asked of the transaction rather than of one
  family's content attribute, so there is no per-family attribute to forget."
  [db identity-attribute identity-value run-id]
  (boolean
   (db/q '[:find ?receipt .
           :in $ ?identity-attribute ?identity-value ?run-id
           :where
           [?declaration ?identity-attribute ?identity-value]
           [?declaration _ _ ?tx true]
           [?receipt :seon.cluster.eval/result-edn _ ?tx]
           [?receipt :seon.cluster.eval/run ?run]
           [?run :seon.cluster.run/id ?run-id]]
         (db/history db) identity-attribute identity-value run-id)))

(defn- declaration-diverged-since-open?
  "True when the current declaration differs from the one the request's run
  opened on and was not written by that run.

  Divergence is a claim ABOUT AN OPENING BASIS, so it is only measurable when
  the request names a run: a derivation with no run — a system-side or fixture
  caller building transaction data outside any run — opened on nothing and has
  no divergence to report. A request that DOES name a run whose opening
  database value cannot be read refuses loudly naming that missing basis,
  rather than reading the unreadable opening as an absent declaration and
  reporting a concurrent definition it never measured."
  [db request identity-attribute identity-value existing]
  (if-some [run-id (::id request)]
    (let [opening-database (opening-db db run-id)]
      (when (:seon.error/kind opening-database)
        (refuse! `receipt-settle-call ::run-opening-basis-unreadable request))
      (let [opening-existing
            (db/pull opening-database '[*] [identity-attribute identity-value])]
        (and (not= (declared-content db opening-existing)
                   (declared-content db existing))
             (not (declaration-written-by-run?
                   db identity-attribute identity-value run-id)))))
    false))

(def ^:private program-relation-attributes
  [:seon.fn/calls :seon.fn/keywords :seon.test/subject])

(defn- relation-assertions
  [entity row]
  (into []
        (concat
         (map (fn [target] [:db/add entity :seon.fn/calls target])
              (:seon.fn/calls row))
         (map (fn [used] [:db/add entity :seon.fn/keywords used])
              (:seon.fn/keywords row))
         (when-let [subject (:seon.test/subject row)]
           [[:db/add entity :seon.test/subject subject]]))))

(defn- row-tx
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
    (let [row (or (program/declaration-row row :contracted :agent)
                  (refuse! `receipt-settle-call
                           ::row-not-admitted request))
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
            schema-redefinition?
            (and (= identity :seon.schema/key)
                 existing
                 (not= (:seon.schema/form existing)
                       (:seon.schema/form row)))
            ;; ONE decision path owns "may this declaration change". The
            ;; concurrency question — did the installed row diverge from the
            ;; basis this run opened on — is measured identically for every
            ;; declaration family. What differs is only what a legal change
            ;; then costs, and for a schema key that cost is answered by the
            ;; usage guard, which names the attributes current data blocks on.
            concurrent-declaration?
            (and (#{:seon.fn/sym :seon.schema/key} identity)
                 existing
                 (not= (declared-content db existing)
                       (declared-content db row))
                 (declaration-diverged-since-open?
                  db request identity identity-value existing))
            _ (when concurrent-declaration?
                (refuse! `receipt-settle-call
                         ::program-row-changed-after-open request))
            _ (when schema-redefinition?
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
            relation-row (select-keys row program-relation-attributes)
            base-row (apply dissoc row program-relation-attributes)
            schema-declarations
            (if (= identity :seon.schema/key)
              (if schema-redefinition?
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
        (into schema-declarations
              (concat
               (cond
                 (nil? existing)
                 [(assoc base-row :db/id
                         (str (name identity) ":" identity-value))]

                 (= (declared-content db existing) (declared-content db row))
                 []

                 :else
                 (program/exact-replacement-tx existing base-row))
               (relation-assertions [identity identity-value]
                                    relation-row)))))))

(defn- def-owned-attributes
  "Attributes declared on one agent def, derived from its entity schema."
  []
  (into #{}
        (keep (fn [entry]
                (when (and (vector? entry)
                           (qualified-keyword? (first entry)))
                  (first entry))))
        (schema.form/map-entries (schema/schema-definition :seon.def/def))))

(defn- def-row
  "Validate one agent-owned def and derive its exact identity."
  [db request run-agent row]
  (when-not (schema/valid-candidate-value? :seon.def/def row)
    (refuse! `receipt-settle-call ::def-row-not-admitted request))
  (let [agent (db/pull db [:db/id :seon.cluster.agent/id]
                       (:seon.def/agent row))
        namespace-row (db/pull db [:db/id :seon.ns/name]
                               (:seon.def/ns row))
        agent-id (:seon.cluster.agent/id agent)
        expected-id (when-let [namespace-name (:seon.ns/name namespace-row)]
                      (str (symbol (str namespace-name)
                                   (str (:seon.def/name row)))))
        expected-key (pr-str [agent-id (:seon.def/id row)])]
    (when-not (= (:db/id run-agent) (:db/id agent))
      (refuse! `receipt-settle-call ::def-agent-mismatch request))
    (when-not (:db/id namespace-row)
      (refuse! `receipt-settle-call ::def-namespace-missing request))
    (when-not (= expected-id (:seon.def/id row))
      (refuse! `receipt-settle-call ::def-id-mismatch request))
    (when-not (= expected-key (:seon.def/key row))
      (refuse! `receipt-settle-call ::def-key-mismatch request))
    (when-not (= :agent (:seon.schema.admission/source row))
      (refuse! `receipt-settle-call ::def-source-not-agent request))
    row))

(defn- exact-def-row-tx
  "Exactly replace the attributes owned by one admitted agent def."
  [db row]
  (let [existing (db/pull db '[*] [:seon.def/key (:seon.def/key row)])
        entity-id (:db/id existing)
        retracts
        (when entity-id
          (into []
                (comp
                 (remove #{:seon.def/key})
                 (filter #(contains? existing %))
                 (map (fn [attribute]
                        [:db/retract entity-id attribute])))
                (sort (def-owned-attributes))))]
    (conj (vec retracts)
          (cond-> row entity-id (assoc :db/id entity-id)))))

(defn- def-rows-tx
  "Validate and exact-upsert this receipt's agent-scoped defs."
  [db request run-agent rows contracted-id]
  (let [rows (mapv #(def-row db request run-agent %) rows)
        keys (mapv :seon.def/key rows)]
    (when-not (= (count keys) (count (set keys)))
      (refuse! `receipt-settle-call ::def-key-duplicate request))
    (into []
          (comp
           (remove #(= contracted-id (:seon.def/id %)))
           (mapcat #(exact-def-row-tx db %)))
          rows)))

(defn- contracted-def-retractions
  "Retract this agent's def superseded by a contracted function."
  [db agent-eid contracted-id]
  (when contracted-id
    (into []
          (map (fn [def-eid] [:db.fn/retractEntity def-eid]))
          (db/q '[:find [?definition ...]
                  :in $ ?agent ?id
                  :where
                  [?definition :seon.def/agent ?agent]
                  [?definition :seon.def/id ?id]]
                db agent-eid contracted-id))))

(def ^:private receipt-terminal-attributes
  [:seon.cluster.eval/result-edn
   :seon.cluster.eval/result-blob
   :seon.cluster.eval/result-size
   :seon.cluster.eval/error
   :seon.cluster.eval/triage-edn
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

(defn- receipt-read-evidence-tx
  "Component read-evidence rows owned by one terminal receipt."
  [db receipt request]
  (when-let [evidence (seq (:seon.cluster.eval/read-evidence request))]
    ;; This transaction data is produced inside Datahike's transaction
    ;; function, after seon.db's outer encode seam has already run. Encode the
    ;; component rows here against the same database-derived declarations so
    ;; heterogeneous dependency plans and revisions reach Datahike in their
    ;; declared EDN-string representation.
    (schema.datahike/encode-transaction-in
     (schema/projection-from-database db)
     [{:db/id (:db/id receipt)
       :seon.cluster.eval/read-evidence
       (mapv (fn [ordinal entry]
               (assoc entry :db/id
                      (str "seon.cluster.eval/read-evidence/"
                           (:seon.cluster.eval/id receipt) "/" ordinal)))
             (range)
             evidence)}])))

(defn receipt-settle-call
  "Settle one running receipt, inside the transaction.
  The settle-once fence is PRESENCE: a receipt already carrying any
  terminal fact refuses `::receipt-terminal`, so a settled receipt
  never returns to running or changes outcome; a settle carrying no
  terminal fact refuses `::no-terminal-fact`, because \"settled with
  nothing settled\" is a caller bug."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:map
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
          [:seon.cluster.eval/read-evidence {:optional true}
           :seon.cluster.eval/read-evidence]
          [:seon.cluster.eval/ns {:optional true} :seon.cluster.eval/ns]
          [:seon.sci.eval/ending-ns {:optional true}
           :seon.sci.eval/ending-ns]
          [:seon.program/row {:optional true}
           :seon.program/row]
          [:seon.def/rows {:optional true} :seon.def/rows]]]
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
    (let [program-row (:seon.program/row request)
          contracted-id (:seon.fn/sym program-row)
          agent-eid (:db/id (::agent run))]
      (into [] cat
            [(if program-row (row-tx db request program-row) [])
             (relation-assertions (:db/id (::form-facts request))
                                  (::form-facts request))
             (def-rows-tx db request (::agent run)
                           (or (:seon.def/rows request) [])
                           contracted-id)
             (contracted-def-retractions db agent-eid contracted-id)
             (receipt-read-evidence-tx db receipt request)
             (receipt-terminal-assertions receipt request)]))))

(defn clear-defs-tx
  "Build transaction data explicitly clearing one agent's defs."
  {:malli/schema
   [:=> [:cat :seon.def/clear-request] [:vector :some]]}
  [request]
  [[:db.fn/call #'clear-defs-call request]])

(defn clear-defs-call
  "Retract every def owned by one declared agent."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.def/clear-request]
    [:vector :some]]}
  [db request]
  (let [agent (db/pull db [:db/id] (:seon.def/agent request))]
    (when-not (:db/id agent)
      (refuse! `clear-defs-call ::def-agent-missing request))
    (into []
          (map (fn [def-eid] [:db.fn/retractEntity def-eid]))
          (db/q '[:find [?definition ...]
                  :in $ ?agent
                  :where [?definition :seon.def/agent ?agent]]
                db (:db/id agent)))))

(defn recover-tx
  "Transaction data recovering one run from dead-process facts."
  {:malli/schema [:=> [:cat [:map
                             [::id ::id]
                             [::live-processes [:set ::process]]
                             [::now :inst]]]
                  [:vector :some]]}
  [request]
  [[:db.fn/call #'recover-call request]])

(defn recover-call
  "Settle and close one interrupted run during boot recovery.
  When the run's holder is NOT a live process — dead, or absent
  entirely — THE RUN gets `::interrupted-at` asserted at `::now`, every
  running receipt (one carrying NO terminal fact) gets
  `:seon.cluster.eval/interrupted-at` asserted at `::now`, every open effect
  receipt gets `:seon.effect/interrupted-at`, dead custody is released, the
  run is CLOSED at `::now`, and the owning agent's run pointer is retracted.
  The run stamp is what makes \"which runs did the last recovery cut?\" a
  query instead of a process-local boot counter, and it is the ONLY
  distinction available for a run whose dead process settled no receipt
  row at all.
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
                       [:map
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

              ;; THE FACT RECOVERY WROTE outranks every guess below it.
              ;; Without it a run whose dead process left no receipt
              ;; row read "It completed." — the render restating a
              ;; database that could not tell the two apart. The two
              ;; clauses above still say MORE (which form was cut), so
              ;; they come first; this is what remains when the process
              ;; died before any form-level evidence existed.
              (and (::interrupted-at unit) (some? (::closed-at unit)))
              (str "It was interrupted at "
                   (pr-str (::interrupted-at unit))
                   " — the process holding it died, recovery closed it, "
                   "and nothing was retried.")

              (::error unit)
              (str "It did not run: " (::error unit)
                   " Nothing was retried, and nothing it asked for ran.")

              (and (nil? (::plan-digest unit)) (some? (::closed-at unit)))
              (str "It was interrupted before the reply arrived, and "
                   "nothing was retried.")

              note (str "It paused, leaving this note: " note)

              (::undisposed-at unit)
              (str "It ended without my.run/complete or my.run/wait. "
                   "Its trigger remains unanswered; nothing was retried.")

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
