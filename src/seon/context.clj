(ns seon.context
  "Agent-owned context selection, compiled AI projections, and prompt capture.

  THE PROMPT'S PROSE LIVES HERE NOW, one named block projection per
  piece (context-blocks contract §3.5, sealed 2026-07-28) — and it is
  SHRINKING TOWARD THE SCAFFOLD, which is the 2026-07-28 post-midnight
  ruling working as intended. What survives here says what an agent
  cannot see by looking: who it is, how its reply is evaluated, who its
  peers are, which message this run answers. What LEFT — `interruption-ai`
  and `continuity-ai` — restated neighbourhood facts, and now lives in
  the lenses of the families that own those facts
  (`seon.turn/render-ai`, `render-receipt-ai`), where a page, a
  debug view and another agent's neighbourhood are told the same true
  thing by the same function instead of only the prompt.
  `seon.cluster.prompt` keeps only selection, validation, ordered
  reduction and the returned rendered-context value; every sentence it
  used to own is a block whose stored `:seon.render/ai` symbol points
  at one of these functions through the ONE router. Replacing a
  block's projection symbol changes the next prompt with no edit to
  the prompt, a route, or a page consumer — the structural falsifier.

  EACH PROJECTION IS PURE OVER ITS UNIT and returns `[:maybe :string]`
  (in-memory return — ruling 1, 2026-07-28: nil is omission, read with
  `get`, never `contains?`). Each owns both its facts-query and its
  guidance — the colocation rule — so the query and the prose that
  explains it can never drift. The stored block symbol is the
  authority; a projection may later move beside its facts owner
  (`seon.problems/block` already models that) with only a data edit.

  THE CAPTURE is ruling 4: the exact prompt text, the rendered database
  basis, the ordered contribution records and the trusted-input
  snapshot commit in ONE turn-owned transaction BEFORE the unobservable
  remote call. `capture-tx` is PURE — tx-data out, the LOOP commits —
  and identity is derived through `seon.id` from turn and basis transaction,
  so re-deriving the same prompt at the same basis upserts rather than
  double-writing, and a released run re-entering `:call` at a new basis
  creates the new capture the honestly-different prompt deserves.

  Crash walk: everything here is pure. The capture's crash story is the
  loop's (contract §5): kill before the capture commits — no capture,
  no attempt, a plain interrupted run; kill between capture and attempt
  — capture with no attempt row, evidence the call may never have
  fired; kill after — today's attempt-row story. Nothing re-executes."
  (:require [seon.ai.tokens :as tokens]
            [seon.turn :as turn]
            [seon.db :as db]
            [seon.id :as id]
            [seon.error :as error]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Agent-owned selection of existing evaluations
;;; ---------------------------------------------------------------------------

(defn- selection-refusal
  {:malli/schema [:=> [:cat :seon.context/selection-refused :map :map]
                  :seon.context/selection-refused-error]}
  [rule request evidence]
  (assoc
   (error/diagnostic
    {:seon.error/at (java.util.Date.)
     :seon.error/layer ::selection
     :seon.error/operation 'seon.context/selection-refusal
     :seon.context/selection-agent-id (:seon.agent/id request)
     :seon.error/offending request
     :seon.error/message "Select terminal evaluations from an existing agent's closed turn with an available contribution identity."
     :seon.error/diagnostic-layer ::selection
     :seon.error/diagnostic-operation 'seon.context/selection-refusal
     :seon.error/diagnostic-member rule
     :seon.error/diagnostic-expected
     "An existing agent's closed run with terminal evaluations and an available contribution identity."
     :seon.error/diagnostic-offending request
     :seon.error/diagnostic-cause rule
     :seon.error/diagnostic-evidence evidence})
   ::selection-refused rule))

(defn selection
  "Return an agent's selected contributions in position order.

  Evaluation refs point at the original stored evaluations; their existing
  run ordinals determine form order. No source or result is copied."
  {:malli/schema
   [:=> [:catn [:database :seon.db/database-value]
                [:agent-id :seon.agent/id]]
    [:or :seon.context/selection :seon.context/selection-refused-error :seon.db/error-result]]}
  [database agent-id]
  (let [agent-data
        (db/pull database
                 [:db/id
                  {:seon.context.contribution/_agent
                   [:seon.context.contribution/id
                    :seon.context.contribution/position
                    {:seon.context.contribution/evaluations [:db/id]}]}]
                 [:seon.agent/id agent-id])]
    (cond
      (and (:seon.error/at agent-data) (:seon.error/layer agent-data) (:seon.error/operation agent-data)) agent-data
      (nil? agent-data)
      (selection-refusal ::no-such-agent
                         {:seon.agent/id agent-id} {})
      :else
      (->> (:seon.context.contribution/_agent agent-data)
           (map (fn [contribution]
                  (-> contribution
                      (assoc :seon.context.contribution/agent (:db/id agent-data))
                      (update :seon.context.contribution/evaluations
                              #(set (map :db/id %))))))
           (sort-by (juxt :seon.context.contribution/position
                          :seon.context.contribution/id))
           vec))))

;; Debt: db/pull and db/q still declare :seon.error/value through seon.db/error-result.
;; Selection and comparison pass those named callee refusals through unchanged.
(defn- transaction-read
  [value]
  (if (and (:seon.error/at value) (:seon.error/layer value) (:seon.error/operation value))
    (throw (ex-info (:seon.error/message value) value))
    value))

(defn- eligible-run
  [database request]
  (let [agent-id (:seon.agent/id request)
        run-id (:seon.turn/id request)
        agent-data (transaction-read
                    (db/pull database [:db/id]
                             [:seon.agent/id agent-id]))
        run-data (transaction-read
                  (db/pull database
                           [:db/id :seon.turn/closed-tx
                            {:seon.turn/agent [:db/id]}
                            {:seon.cluster.eval/_run
                             [:db/id :seon.eval/shown
                              :seon.cluster.eval/error
                              :seon.cluster.eval/interrupted-at]}]
                           [:seon.turn/id run-id]))
        evaluations (:seon.cluster.eval/_run run-data)
        evaluation-refs (set (map :db/id evaluations))
        rule (cond
               (nil? agent-data) ::no-such-agent
               (nil? run-data) ::no-such-run
               (not= (:db/id agent-data)
                     (get-in run-data [:seon.turn/agent :db/id]))
               ::foreign-run
               (not (:seon.turn/closed-tx run-data)) ::run-open
               (empty? evaluations) ::no-evaluations
               (not-every? turn/terminal? evaluations) ::unfinished-evaluation)]
    {:agent-data agent-data
     :run-data run-data
     :evaluation-refs evaluation-refs
     :rule rule}))

(defn- refuse-selection!
  [rule request]
  (let [refusal (selection-refusal
                 rule request
                 {:seon.agent/id (:seon.agent/id request)
                  :seon.turn/id (:seon.turn/id request)
                  :seon.context.contribution/id
                  (:seon.context.contribution/id request)})]
    (throw (ex-info (:seon.error/message refusal) refusal))))

(defn append-tx
  "Append a closed run's terminal evaluations to its agent's context selection.

  Invoke as `[:db.fn/call seon.context/append-tx request]`: Datahike supplies
  the mid-transaction database, so ownership, eligibility, and the next
  position are decided atomically. Repeating an identical contribution id
  preserves its position; using it for different evaluations refuses."
  {:malli/schema
   [:=> [:catn [:database :seon.db/database-value]
                [:request :seon.context/append-request]]
    :seon.store/transaction-data]}
  [database request]
  (let [contribution-id (:seon.context.contribution/id request)
        {:keys [agent-data evaluation-refs rule]} (eligible-run database request)
        existing (transaction-read
                  (db/pull database
                           [{:seon.context.contribution/agent [:db/id]}
                            {:seon.context.contribution/evaluations [:db/id]}]
                           [:seon.context.contribution/id contribution-id]))
        rule (or rule
                 (when (and existing
                    (or (not= (:db/id agent-data)
                              (get-in existing
                                      [:seon.context.contribution/agent :db/id]))
                        (not= evaluation-refs
                              (set (map :db/id
                                        (:seon.context.contribution/evaluations
                                         existing))))))
                   ::contribution-conflict))]
    (cond
      rule (refuse-selection! rule request)
      existing []
      :else
      (let [position
            (transaction-read
             (db/q '[:find (max ?position) .
                     :in $ ?agent
                     :where
                     [?contribution :seon.context.contribution/agent ?agent]
                     [?contribution :seon.context.contribution/position ?position]]
                   database (:db/id agent-data)))]
        [{:seon.context.contribution/id contribution-id
          :seon.context.contribution/agent (:db/id agent-data)
          :seon.context.contribution/position (inc (or position -1))
          :seon.context.contribution/evaluations evaluation-refs}]))))

(defn remove-tx
  "Remove a contribution from context while preserving its evaluations."
  {:malli/schema [:=> [:catn [:database :seon.db/database-value]
                             [:request :seon.context/remove-request]]
                  :seon.store/transaction-data]}
  [database request]
  (let [contribution (transaction-read
                      (db/pull database
                               [:db/id {:seon.context.contribution/agent
                                        [:seon.agent/id]}]
                               [:seon.context.contribution/id
                                (:seon.context.contribution/id request)]))]
    (cond
      (nil? contribution) []
      (not= (:seon.agent/id request)
            (get-in contribution [:seon.context.contribution/agent
                                  :seon.agent/id]))
      (refuse-selection! ::foreign-contribution request)
      :else [[:db/retractEntity (:db/id contribution)]])))

(defn compact-tx
  "Replace one contribution's evaluation refs with a refreshed closed run.

  The caller supplies the refs it observed. Datahike invokes this function
  against the mid-transaction database, so a concurrent selection change
  refuses instead of being overwritten. Identity and position are untouched."
  {:malli/schema
   [:=> [:catn [:database :seon.db/database-value]
                [:request :seon.context/compact-request]]
    :seon.store/transaction-data]}
  [database request]
  (let [contribution-id (:seon.context.contribution/id request)
        expected-refs (:seon.context.contribution/evaluations request)
        {:keys [agent-data evaluation-refs rule]} (eligible-run database request)
        existing (transaction-read
                  (db/pull database
                           [:db/id
                            {:seon.context.contribution/agent [:db/id]}
                            {:seon.context.contribution/evaluations [:db/id]}]
                           [:seon.context.contribution/id contribution-id]))
        current-refs
        (set (map :db/id (:seon.context.contribution/evaluations existing)))
        rule (or rule
                 (when-not existing ::no-such-contribution)
                 (when (and existing
                            (not= (:db/id agent-data)
                                  (get-in existing
                                          [:seon.context.contribution/agent
                                           :db/id])))
                   ::foreign-contribution)
                 (when (and existing (not= expected-refs current-refs))
                   ::stale-contribution))]
    (cond
      rule (refuse-selection! rule request)
      (= current-refs evaluation-refs) []
      :else
      (into []
            (concat
             (map (fn [evaluation]
                    [:db/retract (:db/id existing)
                     :seon.context.contribution/evaluations evaluation])
                  current-refs)
             (map (fn [evaluation]
                    [:db/add (:db/id existing)
                     :seon.context.contribution/evaluations evaluation])
                  evaluation-refs))))))

(defn- ordered-run-evaluations
  [database run-eid]
  (mapv second
        (sort-by first
                 (db/q '[:find ?ordinal ?evaluation
                         :in $ ?run
                         :where
                         [?evaluation :seon.cluster.eval/run ?run]
                         [?evaluation :seon.cluster.eval/ordinal ?ordinal]]
                       database run-eid))))

(defn- ordered-run-source
  [database run-eid]
  (mapv (fn [[ordinal source namespace-name]]
          {:seon.cluster.eval/ordinal ordinal
           :seon.cluster.eval/source source
           :seon.ns/name namespace-name})
        (sort-by first
                 (db/q '[:find ?ordinal ?source ?namespace-name
                         :in $ ?run
                         :where
                         [?form :seon.cluster.eval/run ?run]
                         [?form :seon.cluster.eval/ordinal ?ordinal]
                         [?form :seon.cluster.eval/source ?source]
                         [?form :seon.cluster.eval/ns ?namespace]
                         [?namespace :seon.ns/name ?namespace-name]]
                       database run-eid))))

(defn comparison
  "Compare assembled context with current evaluations of the same source.

  Accept cached evaluations or a saved run. Different ordered source or
  namespace facts are explicitly distinguished; nothing is evaluated here."
  {:malli/schema
   [:=> [:catn [:database :seon.db/database-value]
                [:request :seon.context/comparison-request]]
    [:or :seon.context/comparison :seon.context/selection-refused-error :seon.db/error-result]]}
  [database request]
  (let [agent-id (:seon.agent/id request)
        run-id (:seon.turn/id request)
        evaluated-sources (:seon.turn.loop/evaluated-sources request)
        in-memory? (some? evaluated-sources)
        contribution-id (:seon.context.contribution/id request)
        contribution
        (db/pull database
                 [{:seon.context.contribution/agent [:db/id]}
                  {:seon.context.contribution/evaluations
                   [:db/id {:seon.cluster.eval/run [:db/id]}]}]
                 [:seon.context.contribution/id contribution-id])
        agent-data (db/pull database [:db/id]
                       [:seon.agent/id agent-id])
        refreshed
        (when-not in-memory? (db/pull database
                 [:db/id :seon.turn/closed-tx
                 {:seon.turn/agent [:db/id]}]
                 [:seon.turn/id run-id]))
        eligibility (when (:seon.turn/closed-tx refreshed)
                      (eligible-run database request))
        baseline-runs
        (set (keep #(get-in % [:seon.cluster.eval/run :db/id])
                   (:seon.context.contribution/evaluations contribution)))
        rule (cond
               (and (:seon.error/at contribution) (:seon.error/layer contribution) (:seon.error/operation contribution)) contribution
               (and (:seon.error/at agent-data) (:seon.error/layer agent-data) (:seon.error/operation agent-data)) agent-data
               (and (:seon.error/at refreshed) (:seon.error/layer refreshed) (:seon.error/operation refreshed)) refreshed
               (nil? contribution) ::no-such-contribution
               (nil? agent-data) ::no-such-agent
               (not= (:db/id agent-data)
                     (get-in contribution
                             [:seon.context.contribution/agent :db/id]))
               ::foreign-contribution
               (and (not in-memory?) (nil? refreshed)) ::no-such-run
               (and (not in-memory?)
                    (not= (:db/id agent-data)
                          (get-in refreshed [:seon.turn/agent :db/id])))
               ::foreign-run
               (not= 1 (count baseline-runs)) ::contribution-run-ambiguous
               (and in-memory?
                    (not-every? #(turn/terminal? (:seon.sci.eval/evaluation %))
                                evaluated-sources))
               ::unfinished-evaluation
               (:rule eligibility) (:rule eligibility))]
    (cond
      (map? rule) rule
      rule (selection-refusal rule request
                              {:seon.context.contribution/id contribution-id
                               :seon.turn/id run-id})
      (and (not in-memory?) (not (:seon.turn/closed-tx refreshed)))
      {:seon.context.comparison/status :pending}
      :else
      (let [baseline-run (first baseline-runs)
            current-source
            (if in-memory?
              (mapv (fn [{ordinal :seon.cluster.eval/ordinal
                          form :seon.turn.loop/admitted-form}]
                      {:seon.cluster.eval/ordinal ordinal
                       :seon.cluster.eval/source (:seon.cluster.eval/source form)
                       :seon.ns/name (second (:seon.cluster.eval/ns form))})
                    evaluated-sources)
              (ordered-run-source database (:db/id refreshed)))
            same-source? (= (ordered-run-source database baseline-run) current-source)]
        (if-not same-source?
          {:seon.context.comparison/status :different-source}
          (cond-> {:seon.context.comparison/status :ready
           :seon.context.comparison/baseline-evaluations
           (ordered-run-evaluations database baseline-run)}
            in-memory? (assoc :seon.turn.loop/evaluated-sources evaluated-sources)
            (not in-memory?)
            (assoc :seon.context.comparison/refreshed-evaluations
                   (ordered-run-evaluations database (:db/id refreshed)))))))))

;;; ---------------------------------------------------------------------------
;;; Message custody in one run's rendered context
;;; ---------------------------------------------------------------------------

(defn message-custody
  "Classify one message relative to the run whose prompt is being rendered.

  DERIVED FROM `:t`, LIKE EVERY OTHER ANSWEREDNESS QUESTION. A turn's
  own transaction is the basis its context projected from, so a message
  is in this turn's context exactly when its wake datom's `:t` is at or
  before the turn's; it is what this turn is ABOUT when no earlier turn
  of the same agent had already seen it; and it arrived too late for
  this turn when its `:t` is greater. The recorded trigger reference
  this replaces could not express two messages in one transaction, and
  it swallowed a message transacted with the turn that answered it."
  {:malli/schema
   [:=> [:cat :seon.db/database-value
         [:maybe :seon.turn/id]
         :seon.agent/id
         :int]
    :keyword]}
  [database run-id agent-id message-eid]
  (if-not run-id
    ::history
    (let [message-t
          (db/q '[:find ?tx .
                  :in $ ?agent-id ?message
                  :where
                  [?agent :seon.agent/id ?agent-id]
                  [?message :seon.message/to ?agent ?tx]]
                database agent-id message-eid)
          run-t (db/q '[:find ?tx .
                        :in $ ?run-id
                        :where
                        [?run :seon.turn/id ?run-id ?tx]]
                      database run-id)]
      (cond
        (or (nil? message-t) (nil? run-t)) ::history
        (> message-t run-t) ::pending
        :else
        (let [previous-t
              (or (db/q '[:find (max ?tx) .
                          :in $ ?agent-id ?run-t
                          :where
                          [?agent :seon.agent/id ?agent-id]
                          [?run :seon.turn/agent ?agent]
                          [?run :seon.turn/id _ ?tx]
                          [(< ?tx ?run-t)]]
                        database agent-id run-t)
                  0)]
          (if (> message-t previous-t) ::current-trigger ::history))))))

;;; ---------------------------------------------------------------------------
;;; The pre-provider capture
;;; ---------------------------------------------------------------------------

(defn capture-ai
  "Omit a recorded prompt from later AI context.

  A capture is durable evidence of what an earlier turn saw, not new context
  for a later turn. The walk remains total and still visits this entity; this
  family lens alone decides that the AI projection has nothing relevant to
  say."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [_unit]
  nil)

(defn capture-html
  "Expose recorded prompt or refusal evidence in one debug disclosure."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/hiccup]}
  [unit]
  (let [prompt (:seon.context.capture/prompt unit)
        basis-t (:seon.context.capture/basis-t unit)]
    [:details {:class "seon-family-entry seon-context-capture-entry"}
     [:summary
      (if prompt
        (str "Context capture at database basis " basis-t
             " — approximately " (tokens/estimate prompt)
             " tokens on the uncalibrated chars/4 basis")
        (str "Context derivation refused at database basis " basis-t))]
     [:pre {:class "seon-context-capture-prompt"}
      (or prompt (:seon.error/message unit))]]))

(defn- contribution-row
  "One durable contribution row: evidence, not content. No stored output tag
  (a prompt capture is necessarily AI), no stored text (hash + position + the prompt blob
  reconstruct it). A FAILED contribution is presence of the error keys."
  {:malli/schema [:=> [:cat :seon.context.capture/id :seon.context/contribution]
                  :seon.context.contribution/contribution]}
  [capture-id record]
  (let [position (:seon.context.contribution/position record)
        failure (get record :seon.error/value)]
    (cond-> {:seon.context.contribution/id
             (id/digest 12 [:seon.context.contribution/id capture-id position])
             :seon.context.contribution/position position
             :seon.render.block/name (:seon.render.block/name record)
             :seon.context.contribution/hash
             (:seon.context.contribution/hash record)
             :seon.context.contribution/tokens
             (:seon.context.contribution/tokens record)}
      (seq (:seon.context.contribution/evaluations record))
      (assoc :seon.context.contribution/evaluations
             (:seon.context.contribution/evaluations record))
      failure
      (assoc :seon.context.contribution/error (:seon.error/message failure)))))

(defn capture-tx
  "Transaction data for one context capture. PURE — the loop commits.

  A successful arm records the exact prompt and ordered contribution evidence.
  A refusal arm records the immutable database basis plus typed error, with no
  invented prompt. Both use the same `(run-id, basis-t)` identity."
  {:malli/schema [:=> [:cat :seon.context/capture-request]
                  :seon.store/transaction-data]}
  [request]
  (let [{run-id :seon.turn/id
         rendered :seon.cluster.prompt/rendered-context
         database :seon.db/db
         failure :seon.error/value} request
        rendered-arm (find request :seon.cluster.prompt/rendered-context)
        refusal-arm (find request :seon.error/value)
        _ (when (= (some? rendered-arm) (some? refusal-arm))
            (throw
             (ex-info
              "Context capture requires exactly one rendered or refusal arm."
              {:seon.context/capture-request request
               :seon.context/rendered-arm? (some? rendered-arm)
               :seon.context/refusal-arm? (some? refusal-arm)})))
        db (or (:seon.db/db rendered) database)
        ; the database interface, not a map key: an as-of/history value
        ; carries no top-level :max-tx entry. `basis-t` is the reader for
        ; every value shape; `database-value-identity` is not — its output
        ; contract requires a commit id, which an as-of value does not have.
        basis-t (long (db/basis-t db))
        capture-id (id/digest 12 [:seon.context.capture/id run-id basis-t])]
    [(cond-> {:seon.context.capture/id capture-id
              :seon.context.capture/run [:seon.turn/id run-id]
              :seon.context.capture/basis-t basis-t}
       rendered
       (assoc :seon.context.capture/prompt
              (:seon.cluster.prompt/text rendered)
              ;; THE CALIBRATION JOIN'S OTHER HALF: the provider's own
              ;; `prompt_tokens` lands on the attempt for this same run.
              :seon.ai.tokens/characters
              (count (:seon.cluster.prompt/text rendered))
              :seon.context.capture/contributions
              (mapv (fn [record] (contribution-row capture-id record))
                    (:seon.context/contributions rendered)))
       failure
       (assoc :seon.error/message (:seon.error/message failure)))]))

;;; ---------------------------------------------------------------------------
;;; The one digest seam
;;; ---------------------------------------------------------------------------

;; `seon.cluster.prompt` builds the in-memory records and owns token
;; estimation. The capture owner derives only the durable digest evidence.

(defn contribution-hash
  "SHA-256 hex of the contribution's exact UTF-8 text — the one digest
  owner (`schema/sha-256`) applied to the one evidence field."
  {:malli/schema [:=> [:cat :string] :seon.context.contribution/hash]}
  [text]
  (schema/sha-256 [(.getBytes ^String text "UTF-8")]))
