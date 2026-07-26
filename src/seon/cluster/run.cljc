(ns seon.cluster.run
  "The nucleus run data model: claimable database state, pure transitions.

  CONTRACT LAYER (orchestrator-authored, 2026-07-26 s3 — the first
  per-namespace construction unit). The schemas and the function
  contracts here are SEALED: the N2 implementation lane fills the
  bodies until test/seon/cluster/run_test.clj is green and may not
  loosen a schema or a test. Friction with a contract is reported,
  never resolved by weakening it.

  The model (crash rulings, plan README session 3):

  - A run is the bounded work unit a trigger opens. Its state is
    DERIVED from primitives — open = no closed-at; claimed = a process
    with a live lease — never a stored status label.
  - Custody is `:db.fn/cas`: one process wins the claim; claim epoch is
    a monotonic fence; a displaced process's later transaction fails
    the fence at the writer.
  - Crashes are rare and NOTHING re-executes: boot recovery marks
    dangling `:running` eval receipts `:interrupted` and releases
    claims held by dead processes. A form has AT MOST ONE terminal
    receipt, ever. The interrupted state surfaces to the agent as one
    derived warning, not per-eval markers.
  - Every transition is a pure function returning transaction data;
    the caller commits through the one writer. Errors are values."
  (:require [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; The nucleus agent pointer — owned HERE, deliberately not ported.
;;; Port manifest: old `:seon.agent/*` attrs are DEAD for the nucleus;
;;; the agent entity model is re-decided at its own rung. The run model
;;; needs exactly an identity to point from and the current-run pointer
;;; its opens race on.
;;; ---------------------------------------------------------------------------

(schema/register! :seon.cluster.agent/id
                  [:string {:min 1 :seon.db/identity true}])
(schema/register! :seon.cluster.agent/run :seon.db/ref)

;;; ---------------------------------------------------------------------------
;;; The run entity
;;; ---------------------------------------------------------------------------

(schema/register! ::id [:string {:min 1 :seon.db/identity true}])
(schema/register! ::agent :seon.db/ref)
(schema/register! ::opened-at :inst)
(schema/register! ::closed-at :inst)
; the claiming process's identity — (pid, start-instant) projected to one
; string by the process owner; the run model treats it as opaque
(schema/register! ::process [:string {:min 1}])
(schema/register! ::claim-epoch [:int {:min 1}])
(schema/register! ::lease-until :inst)
; frozen reply plan identity; CAS from absent — concurrent replies are
; mutually exclusive by construction
(schema/register! ::plan-digest [:string {:min 1}])

(schema/register!
 ::run
 [:map {:seon.db/entity true}
  [::id ::id]
  [::agent ::agent]
  [::opened-at ::opened-at]
  [::closed-at {:optional true} ::closed-at]
  [::process {:optional true} ::process]
  [::claim-epoch {:optional true} ::claim-epoch]
  [::lease-until {:optional true} ::lease-until]
  [::plan-digest {:optional true} ::plan-digest]])

;;; The plan's forms — one entity per ordered form, owned by the run.

(schema/register! :seon.cluster.run.form/id
                  [:string {:min 1 :seon.db/identity true}])
(schema/register! :seon.cluster.run.form/run :seon.db/ref)
(schema/register! :seon.cluster.run.form/ordinal [:int {:min 0}])
(schema/register! :seon.cluster.run.form/source [:string {:min 1}])
(schema/register! ::forms
                  [:set {:seon.db/component true} :seon.db/ref])

;;; Eval receipts — the ONLY receipt entities (effect attribution is the
;;; transaction's provenance metadata). Identity is the attempt address.

(schema/register! :seon.cluster.eval/id
                  [:string {:min 1 :seon.db/identity true}])
(schema/register! :seon.cluster.eval/run :seon.db/ref)
(schema/register! :seon.cluster.eval/ordinal [:int {:min 0}])
(schema/register! :seon.cluster.eval/claim-epoch [:int {:min 1}])
(schema/register! :seon.cluster.eval/at :inst)
(schema/register! :seon.cluster.eval/status
                  [:enum :running :done :error :interrupted])
(schema/register! :seon.cluster.eval/result-edn :string)
(schema/register! :seon.cluster.eval/error :string)

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
  {:malli/schema [:=> [:cat [:map] :inst] :boolean]}
  [run now]
  (boolean
   (and (some? (::process run))
        (some? (::lease-until run))
        (> (inst-ms (::lease-until run)) (inst-ms now)))))

(defn expired?
  "True when the run is open and its holder's lease lapsed at `now`."
  {:malli/schema [:=> [:cat [:map] :inst] :boolean]}
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
  {:malli/schema [:=> [:cat [:sequential :map] [:sequential :map]]
                  [:maybe :map]]}
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
;;; Pure transitions — each returns transaction data for the one writer
;;; ---------------------------------------------------------------------------

(defn open-tx
  "Open one run for an agent: identity, agent ref, opened-at.
  Composes the agent's current-run CAS (absent → this run) so
  concurrent opens race and exactly one wins."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::agent ::agent]
                             [::opened-at ::opened-at]
                             [:seon.cluster.agent/id [:string {:min 1}]]]]
                  [:vector :some]]}
  [request]
  (let [{::keys [id agent opened-at]} request
        agent-id (:seon.cluster.agent/id request)
        run-ref [::id id]]
    [{::id id
      ::agent agent
      ::opened-at opened-at}
     [:db.fn/cas
      [:seon.cluster.agent/id agent-id]
      :seon.cluster.agent/run
      nil
      run-ref]]))

(defn claim-tx
  "Claim an unheld run through the custody CAS.
  Process absent→holder, epoch observed→+1, lease asserted. A live
  foreign claim is not stealable. Takeover of an EXPIRED claim supplies
  the observed holder and lease: the CAS asserts those exact heartbeat
  facts unchanged while replacing them, so a holder that renewed in the
  meantime wins and the takeover fails loudly. Absent observed fields =
  the fresh/reacquire case (process CAS from absent)."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::observed-epoch [:maybe ::claim-epoch]]
                             [::observed-process {:optional true} ::process]
                             [::observed-lease-until {:optional true}
                              ::lease-until]
                             [::lease-until ::lease-until]]]
                  [:vector :some]]}
  [request]
  (let [{::keys [id process observed-epoch lease-until]} request
        run-ref [::id id]]
    [[:db.fn/cas run-ref ::claim-epoch
      observed-epoch (inc (or observed-epoch 0))]
     [:db.fn/cas run-ref ::process nil process]
     [:db/add run-ref ::lease-until lease-until]]))

(defn heartbeat-tx
  "Renew the holder's lease under the run fence."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::claim-epoch ::claim-epoch]
                             [::lease-until ::lease-until]]]
                  [:vector :some]]}
  [request]
  (let [{::keys [id process claim-epoch lease-until]} request
        run-ref [::id id]]
    [[:db.fn/cas run-ref ::claim-epoch claim-epoch claim-epoch]
     [:db.fn/cas run-ref ::process process process]
     [:db/add run-ref ::lease-until lease-until]]))

(defn release-tx
  "Cleanly release custody: retract process + lease, keep the epoch."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::claim-epoch ::claim-epoch]]]
                  [:vector :some]]}
  [request]
  (let [{::keys [id process claim-epoch]} request
        run-ref [::id id]]
    [[:db.fn/cas run-ref ::claim-epoch claim-epoch claim-epoch]
     [:db.fn/cas run-ref ::process process process]
     [:db/retract run-ref ::process process]
     [:db.fn/retractAttribute run-ref ::lease-until]]))

(defn close-tx
  "Close the run in one fenced transaction.
  Asserts closed-at, retracts custody, retracts the agent's current-run
  pointer."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::claim-epoch ::claim-epoch]
                             [::closed-at ::closed-at]
                             [:seon.cluster.agent/id [:string {:min 1}]]]]
                  [:vector :some]]}
  [request]
  (let [{::keys [id process claim-epoch closed-at]} request
        agent-id (:seon.cluster.agent/id request)
        run-ref [::id id]]
    [[:db.fn/cas run-ref ::claim-epoch claim-epoch claim-epoch]
     [:db.fn/cas run-ref ::process process process]
     [:db/add run-ref ::closed-at closed-at]
     [:db/retract run-ref ::process process]
     [:db.fn/retractAttribute run-ref ::lease-until]
     [:db/retract
      [:seon.cluster.agent/id agent-id]
      :seon.cluster.agent/run
      run-ref]]))

(defn plan-tx
  "Freeze one ordered form plan through the absent→digest CAS.
  Commits the owned form entities with it; a losing concurrent reply
  commits nothing."
  {:malli/schema [:=> [:cat [:map {:closed true}
                             [::id ::id]
                             [::process ::process]
                             [::claim-epoch ::claim-epoch]
                             [::plan-digest ::plan-digest]
                             [::sources [:vector [:string {:min 1}]]]]]
                  [:vector :some]]}
  [request]
  (let [{::keys [id process claim-epoch plan-digest sources]} request
        run-ref [::id id]
        forms (mapv (fn [ordinal source]
                      (let [form-id (pr-str [id ordinal])]
                        {:db/id form-id
                         :seon.cluster.run.form/id form-id
                         :seon.cluster.run.form/run run-ref
                         :seon.cluster.run.form/ordinal ordinal
                         :seon.cluster.run.form/source source}))
                    (range)
                    sources)]
    (into [[:db.fn/cas run-ref ::claim-epoch claim-epoch claim-epoch]
           [:db.fn/cas run-ref ::process process process]
           [:db.fn/cas run-ref ::plan-digest nil plan-digest]]
          (concat
           forms
           (map (fn [{form-id :seon.cluster.run.form/id}]
                  [:db/add run-ref ::forms form-id])
                forms)))))

(defn recover-tx
  "Boot recovery for one run against dead-process facts.
  Every `:running` receipt becomes `:interrupted`, and custody held by
  a process outside `live-processes` is released. Returns [] for a run
  needing nothing. NOTHING here re-opens, re-plans, or re-executes."
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
