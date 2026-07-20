(ns my.plan
  "Maintain an agent's durable dependency-aware plan graph.

   This namespace is the public planning surface for creating and reconciling
   plan trees, selecting active work, recording dependencies and outcomes, and
   deriving readiness, blockage, progress, and bounded context views. Plans are
   database facts scoped to one agent, never transcript or todo-list state."
  (:refer-clojure :exclude [next])
  (:require
    [clojure.string :as str]
    [my.plan.internal :as internal]
    [seon.agent]   ; load-order: request schemas reference :seon.agent/id
    [seon.agent.message :as message]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.schema :as schema]))

;; --- Attribute schemas — one register! per attr; shared shapes referenced,
;; --- never inlined.

(schema/register! ::id
                  [:and {:seon.db/identity true
                         :seon.db.id/generator
                         :seon.db.id.generator/compact}
                   ::db.id/compact-value])
(schema/register! ::title [:string {:min 1}])
(schema/register! ::description :string)
(schema/register! ::status [:enum :open :active :done :blocked]) ; :active = where you ARE
(schema/register! ::created-at :inst)
(schema/register! ::completed-at :inst)
(schema/register! ::agent :seon.db/ref)   ; SCOPE ref — the agent this step belongs to
(schema/register! ::from :seon.db/ref)     ; who asked
(schema/register! ::message :seon.db/ref)  ; the inbound message an address-step tracks
(schema/register! ::namespace :seon.db/ref) ; generated code's owning namespace
(schema/register! ::claim :string)          ; assignment message id used as CAS fence
(schema/register! ::parent :seon.db/ref)             ; TREE edge — plain ref, no cascade
(schema/register! ::needs [:vector :seon.db/ref])    ; DAG edges — ready only when all :done
(schema/register! ::goal :string)    ; root-level WHY — outlives the transcript window
(schema/register! ::expect :string)  ; falsifiable outcome — how you'd know the step failed
(schema/register! ::pace [:enum :one-shot :multi-session]) ; root-level scope

(def rules
  "The shared Datalog rule set (descendant/leaf/unfinished/open-work/
   blocked/ready) — read and extend it; pass it as `%`. The literal lives
   in `my.plan.internal/rules`; this def IS that value."
  internal/rules)

;; --- Request/response schemas. ::ok? discriminates the envelope; failures
;; --- carry a guiding ::error (errors are values — branch, don't catch).

(schema/register! ::ok? :boolean)
(schema/register! ::error :string)
(schema/register! ::all? :boolean)
(schema/register! ::dropped :int)
(schema/register! ::on [:vector :seon.db/ref])      ; needs! edge targets
(schema/register! ::done? :boolean)
(schema/register! ::blocked? :boolean)
(schema/register! ::ready? :boolean)
(schema/register! ::progress [:map [::done :int] [::total :int]])
(schema/register! ::direct-error [:map [:seon.error/message :string]])

(schema/register! ::ready-namespace-state
  [:map
   [::id ::id]
   [:seon.ns/name :symbol]])
(schema/register! ::namespace-step-state
  [:map
   [::id ::id]
   [:seon.ns/name :symbol]
   [::status ::status]
   [::claim {:optional true} ::claim]])
(schema/register! ::generated-root-state
  [:map
   [::id ::id]
   [::status ::status]
   [::progress ::progress]
   [::blocked? ::blocked?]
   [:my.plan.internal/namespace-steps [:vector ::namespace-step-state]]
   [:my.plan.internal/ready-steps [:vector ::ready-namespace-state]]])
(schema/register! ::generated-root-candidate
  [:map
   [::id ::id]
   [:seon.agent/id :seon.agent/id]])
(schema/register! ::generated-root-candidates-request
  [:map {:closed true} [::db/db :seon.db/db]])
(schema/register! ::generated-root-candidates-response
  [:or [:vector ::generated-root-candidate] ::direct-error])

(schema/register! ::write-response
  [:map
   [::ok?   ::ok?]
   [::id    {:optional true} ::id]
   [::error {:optional true} ::error]])

(schema/register! ::id-request
  [:map [::id ::id] [::db/db {:optional true} :seon.db/db]])

(schema/register! ::step-request
  [:map
   [::title         ::title]
   [::description   {:optional true} ::description]
   [::expect        {:optional true} ::expect]
   [:seon.agent/id  {:optional true} :seon.agent/id]  ; injected: you (omit) — or another agent's id
   [::from          {:optional true} :seon.db/ref]
   [::parent        {:optional true} ::parent]
   [::needs         {:optional true} ::needs]
   [::db/db         {:optional true} :seon.db/db]])

;; plan! node shape — recursive: each child may carry its own :children.
(schema/register! ::plan-node
  [:schema {:registry {::node [:map
                               [::title ::title]
                               [::ref         {:optional true} :string]
                               [::after       {:optional true} [:vector :string]]
                               [::description {:optional true} ::description]
                               [::expect      {:optional true} ::expect]
                               [::children    {:optional true} [:vector [:ref ::node]]]]}}
   [:ref ::node]])

(schema/register! ::plan-request
  [:map
   [::title        ::title]
   [::goal         {:optional true} ::goal]
   [::pace         {:optional true} ::pace]
   [::expect       {:optional true} ::expect]
   [:seon.agent/id {:optional true} :seon.agent/id]  ; injected: you (omit)
   [::db/db         {:optional true} :seon.db/db]
   [::children     {:optional true} [:vector ::plan-node]]])

(schema/register! ::ids
  [:map-of [:or :string [:= :root]] ::id])     ; author-label / :root → minted id

(schema/register! ::plan-response
  [:map
   [::ok?   ::ok?]
   [::root  {:optional true} ::id]
   [::ids   {:optional true} ::ids]
   [::error {:optional true} ::error]])

(schema/register! ::needs-request
  [:map [::id ::id] [::on ::on] [::db/db {:optional true} :seon.db/db]])
(schema/register! ::move-request
  [:map [::id ::id] [::parent ::parent]
   [::db/db {:optional true} :seon.db/db]])

(schema/register! ::drop-response
  [:map
   [::ok?      ::ok?]
   [::dropped  {:optional true} ::dropped]
   [::error    {:optional true} ::error]])

(schema/register! ::status-response
  [:or
   [:map
    [::id       ::id]
    [::done?    ::done?]
    [::blocked? ::blocked?]
    [::ready?   ::ready?]
    [::progress ::progress]]
   [:map
    [::ok? [:= false]]
    [::error ::error]]])

(schema/register! ::step-ref
  [:map [::id ::id] [::title ::title] [::created-at ::created-at]])

(schema/register! ::next-request
  [:map
   [:seon.agent/id {:optional true} :seon.agent/id]
   [::db/db {:optional true} :seon.db/db]])

(schema/register! ::tree-request
  [:map
   [::root         {:optional true} ::id]   ; a root STEP ID — that subtree only
   [::all?         {:optional true} ::all?]
   [::db/db         {:optional true} :seon.db/db]
   [:seon.agent/id {:optional true} :seon.agent/id]])  ; injected: you (omit)

;; ::root → one subtree map; else → a vector of root subtrees; nil when nothing.
(schema/register! ::tree-response [:maybe [:or :map [:vector :map]]])

;; reconcile! — edit your whole OPEN plan as ONE document. A document node is
;; `tree`/`document`'s shape (children under ::_parent; ::children accepted
;; too when authoring by hand); ::status rides along read-only (active!/done!
;; own it); ::ref/::after label deps exactly as in plan!.
(schema/register! ::doc-node
  [:schema {:registry {::dnode [:map
                                [::id          {:optional true} ::id]
                                [::title       ::title]
                                [::status      {:optional true} ::status]
                                [::description {:optional true} ::description]
                                [::expect      {:optional true} ::expect]
                                [::goal        {:optional true} ::goal]
                                [::pace        {:optional true} ::pace]
                                [::ref         {:optional true} :string]
                                [::after       {:optional true} [:vector :string]]
                                [::needs       {:optional true}
                                 [:vector [:map [::id ::id]]]]
                                [::children    {:optional true}
                                 [:vector [:ref ::dnode]]]
                                [::_parent     {:optional true}
                                 [:vector [:ref ::dnode]]]]}}
   [:ref ::dnode]])

(schema/register! ::added :int)
(schema/register! ::updated :int)
(schema/register! ::resolved-root :boolean)  ; id-less root resolved onto the open root
(schema/register! ::diff
  [:map [::added ::added] [::dropped ::dropped] [::updated ::updated]])

(schema/register! ::program :map)
(schema/register! ::eval-batch :map)
(schema/register! ::namespace-ids [:map-of :symbol ::id])
(schema/register! ::publish-generated-program-request
  [:map {:closed true}
   [::db/db :seon.db/db]
   [:seon.agent.run/id ::db.id/compact-value]
   [:seon.agent.turn/id ::db.id/compact-value]
   [::program ::program]
   [::eval-batch ::eval-batch]])
(schema/register! ::publish-generated-program-response
  [:or
   [:map
    [::ok? [:= true]]
    [::root {:optional true} ::id]
    [::diff {:optional true} ::diff]
    [::namespace-ids {:optional true} ::namespace-ids]
    [:seon.eval/ids {:optional true} [:vector ::db.id/compact-value]]]
   [:map
    [::ok? [:= false]]
    [::root {:optional true} ::id]
    [::error ::error]]])
(schema/register! ::generated-terminal-request
  [:map {:closed true}
   [::db/db :seon.db/db]
   [::id ::id]
   [::status [:enum :done :blocked]]
   [:seon.agent.message/content :seon.agent.message/content]])
(schema/register! ::generated-terminal-response
  [:or
   [:map
    [::ok? [:= true]]
    [::id ::id]
    [::status ::status]
    [:seon.agent.message/id
     {:optional true} :seon.agent.message/id]]
   [:map
    [::ok? [:= false]]
    [::id {:optional true} ::id]
    [::error ::error]]])

;; ::tree stays structurally permissive at the boundary — a malformed
;; document is EXPECTED input (hand-authored or model edits) and must come
;; back as a guiding fail envelope, never an instrumentation throw. The
;; `:seon.render/prefill-fn` PROPERTY names the projection of this argument's
;; CURRENT value (`document`) — the registry-driven draft-head affordance:
;; a driver that resolves this request schema can pre-fill the ::tree hole
;; with the live open tree so the model EDITS instead of regenerating.
(schema/register! ::reconcile-request
  [:map
   [::tree         {:optional true
                    :seon.render/prefill-fn 'my.plan/document}
    [:or :map [:vector :map]]]
   [::db/db         {:optional true} :seon.db/db]
   [:seon.agent/id {:optional true} :seon.agent/id]])  ; injected: you (omit)

(schema/register! ::reconcile-response
  [:map
   [::ok?           ::ok?]
   [::root          {:optional true} ::id]
   [::ids           {:optional true} ::ids]
   [::diff          {:optional true} ::diff]
   [::resolved-root {:optional true} ::resolved-root]
   [::error         {:optional true} ::error]])

;; --- The stored entity kind. `{:seon.db/entity true}` DECLARES that rows of
;; --- this shape live in the DB (puts the kind in the catalog). Required =
;; --- what step!/plan! write unconditionally. ::agent is the SCOPING ref:
;; --- per-agent data points DATA→AGENT (the step refs its owner; the agent
;; --- entity is never edited to gain a domain) — read it back either way:
;; --- forward [?t :my.plan/agent ?a], reverse pull :my.plan/_agent.

(schema/register! ::step
  [:map {:seon.db/entity true}
   [::id           ::id]
   [::title        ::title]
   [::status       ::status]
   [::agent        ::agent]
   [::created-at   ::created-at]
   [::description  {:optional true} ::description]
   [::goal         {:optional true} ::goal]
   [::expect       {:optional true} ::expect]
   [::pace         {:optional true} ::pace]
   [::from         {:optional true} ::from]
   [::message      {:optional true} ::message]
   [::namespace    {:optional true} ::namespace]
   [::claim        {:optional true} ::claim]
   [::parent       {:optional true} ::parent]
   [::needs        {:optional true} ::needs]
   [::completed-at {:optional true} ::completed-at]])

;; list-open's PROJECTION of one unfinished step (internal/open-keys) — the
;; windowed read, not the stored row (no ::agent: the read is already scoped;
;; a PULLED ref renders as {:db/id n}, not the transact-side ref form).
(schema/register! ::open-step
  [:map
   [::id          ::id]
   [::title       ::title]
   [::status      ::status]
   [::created-at  ::created-at]
   [::description {:optional true} ::description]
   [::message     {:optional true} [:map [:db/id :int]]]])

(schema/register! ::steps [:vector ::open-step])

(schema/register! ::list-request
  [:map
   [:seon.agent/id {:optional true} :seon.agent/id]  ; injected: you (omit)
   [::db/db         {:optional true} :seon.db/db]
   [::all?         {:optional true} ::all?]])        ; true = every agent's steps

(schema/register! ::list-response
  [:map
   [::ok?   ::ok?]
   [::steps {:optional true} ::steps]
   [::error {:optional true} ::error]])

(schema/register! ::active? :boolean)
(schema/register! ::position-request
  [:map
   [::db/db {:optional true} :seon.db/db]
   [:seon.agent/id {:optional true} :seon.agent/id]])
(schema/register! ::position
  [:map
   [::root     ::id]
   [::title    ::title]
   [::goal     {:optional true} ::goal]
   [::step     ::id]
   [::step-title ::title]
   [::active?  ::active?]
   [::progress ::progress]])
(schema/register! ::position-response
  [:map
   [::ok?      ::ok?]
   [::position {:optional true} ::position]
   [::error    {:optional true} ::error]])

(defn- allocation-declarations
  [allocation-keys]
  (mapv (fn [allocation-key]
          {::db.id/key allocation-key
           ::db.id/identity-attr ::id})
        allocation-keys))

(def ^:private plan-row-selector
  [:db/id ::id ::title ::description ::status ::created-at ::completed-at
   ::goal ::expect ::pace ::from ::message ::claim
   {::agent [:seon.agent/id]}
   {::namespace [:seon.ns/name]}
   {::parent [::id]}
   {::needs [::id]}])

(def ^:private agent-plan-rows-query
  '[:find [(pull ?step ?selector) ...]
    :in $ ?selector ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?step :my.plan/agent ?agent]])

(def ^:private agent-exists-query
  '[:find ?agent . :in $ ?agent-id :where [?agent :seon.agent/id ?agent-id]])

(def ^:private current-run-id-query
  '[:find ?run-id .
    :in $ ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status :open]])

(def ^:private duplicate-root-query
  '[:find [(pull ?root ?selector) ...]
    :in $ % ?selector ?agent-id ?title
    :where
    [?agent :seon.agent/id ?agent-id]
    [?root :my.plan/agent ?agent]
    [?root :my.plan/title ?title]
    (unfinished ?root)
    (not-join [?root] [?root :my.plan/parent _])])

(def ^:private generated-root-rules
  '[[(generated-root ?root ?message)
     [?root :my.plan/message ?message]
     [?message :seon.agent.message/id ?message-id]
     [?root :my.plan/claim ?message-id]
     [?root :my.plan/goal _]
     (not-join [?root] [?root :my.plan/parent _])]])

(def ^:private generated-root-for-run-query
  '[:find ?root-id .
    :in $ % ?run-id
    :where
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/cause ?message]
    (generated-root ?root ?message)
    [?root :my.plan/id ?root-id]])

(def ^:private generated-namespace-for-run-query
  '[:find [?root-id ?step-id ?namespace] .
    :in $ % ?run-id
    :where
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/cause ?message]
    [?step :my.plan/message ?message]
    [?step :my.plan/id ?step-id]
    [?step :my.plan/namespace ?namespace-entity]
    [?namespace-entity :seon.ns/name ?namespace]
    [?step :my.plan/parent ?root]
    (generated-root ?root ?root-message)
    [?root :my.plan/id ?root-id]])

(def ^:private generated-eval-selector
  '[:seon.eval/id :seon.eval/ns :seon.eval/source :seon.eval/status
    :seon.eval/progress?
    :seon.test.runner/test :seon.test.runner/pass
    :seon.test.runner/fail :seon.test.runner/error
    {:seon.test.runner/tests [:seon.test/sym]}])

(def ^:private generated-eval-evidence-query
  '[:find ?status-tx (pull ?eval ?selector)
    :in $ ?selector ?run-id ?turn-id [?eval-id ...]
    :where
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/id ?turn-id]
    [?turn :seon.agent.turn/run ?run]
    [?turn :seon.agent.turn/evals ?eval]
    [?eval :seon.eval/id ?eval-id]
    [?eval :seon.eval/status _ ?status-tx]])

(def ^:private generated-root-candidates-query
  '[:find ?root-id ?coordinator-id
    :in $ %
    :where
    (generated-root ?root ?message)
    [?root :my.plan/id ?root-id]
    [?root :my.plan/agent ?coordinator]
    [?coordinator :seon.agent/id ?coordinator-id]])

(declare plan-ref-id)

(defn- query-member
  [query arguments]
  {::protocol/operation protocol/query-operation
   ::protocol/query-form query
   ::protocol/arguments arguments
   :datahike.resource/max-work 5000000
   :datahike.resource/max-results 200000
   :datahike.resource/max-result-weight 2097152})

(defn- pull-member
  [selector eid]
  {::protocol/operation protocol/pull-operation
   ::protocol/selector selector
   ::protocol/entity-id eid
   :datahike.resource/max-work 250000
   ;; Datahike charges one result node per pulled attribute/ref node, not one
   ;; per pulled entity. Weight remains the tighter payload bound.
   :datahike.resource/max-results 4096
   :datahike.resource/max-result-weight 131072})

(defn- member-result [member]
  (or (::protocol/result member)
      (:datahike.query/result member)))

(def ^:private all-plan-rows-query
  '[:find [(pull ?step ?selector) ...]
    :in $ ?selector
    :where [?step :my.plan/id _]])

(def ^:private descendant-rows-query
  '[:find [(pull ?step ?selector) ...]
    :in $ % ?selector ?root-id
    :where
    [?root :my.plan/id ?root-id]
    (descendant ?root ?step)])

(def ^:private blocked-step-query
  '[:find ?step .
    :in $ % ?step-id
    :where
    [?step :my.plan/id ?step-id]
    (blocked ?step)])

(def ^:private ready-step-query
  '[:find ?step .
    :in $ % ?step-id
    :where
    [?step :my.plan/id ?step-id]
    (ready ?step)])

(defn ^:async ^:private acquire-root-plan-rows
  [request root-id]
  (let [database (or (::db/db request) (await (db/db)))]
    (if (:seon.error/message database)
      database
      (let [response
            (await
             (db/execute-many
              {::db/db database
               ::db/members
               [(pull-member plan-row-selector [::id root-id])
                (query-member descendant-rows-query
                              [rules plan-row-selector root-id])]
               ::db/max-result-weight 2097152}))
            [root-member descendants-member] (::db/results response)]
        (if (and (not (:seon.error/message response))
                 (true? (::protocol/success? root-member))
                 (true? (::protocol/success? descendants-member)))
          {::db/db database
           ::rows (into (cond-> [] (member-result root-member)
                          (conj (member-result root-member)))
                        (or (member-result descendants-member) []))}
          {:seon.error/message "Plan subtree read failed."
           :seon.error/data response
           :seon.error/kind :core-bug})))))

(defn ^:async ^:private acquire-generated-root-plan-rows
  "Find a generated planner root or namespace assignment at the pinned value.

   Ordinary turns stop after the indexed cause query. Once applicable, acquire
   exactly one current value for the named database after eval recording and
   use that same value for the subtree, exact-ID eval evidence, and fenced
   write."
  [{database ::db/db
    run-id :seon.agent.run/id
    turn-id :seon.agent.turn/id
    batch ::eval-batch}]
  (let [root-id
        (await
         (db/query
          {::db/db database
           ::db/query generated-root-for-run-query
           ::db/args [generated-root-rules run-id]
           ::db/max-work 250000
           ::db/max-results 1
           ::db/max-result-weight 4096}))
        namespace-match
        (when-not (or (:seon.error/message root-id) root-id)
          (await
           (db/query
            {::db/db database
             ::db/query generated-namespace-for-run-query
             ::db/args [generated-root-rules run-id]
             ::db/max-work 250000
             ::db/max-results 1
             ::db/max-result-weight 4096})))
        [namespace-root-id namespace-step namespace-name]
        (when (vector? namespace-match) namespace-match)
        root-id (or root-id namespace-root-id)]
    (cond
      (:seon.error/message root-id) root-id
      (:seon.error/message namespace-match) namespace-match
      (nil? root-id) {::db/db database ::root nil ::rows []}
      :else
      (let [current
            (await (db/db {::db/database-name (:db-name database)}))]
        (if (:seon.error/message current)
          (assoc current ::root root-id)
          (let [acquired
                (await (acquire-root-plan-rows {::db/db current} root-id))]
            (if (:seon.error/message acquired)
              (assoc acquired ::db/db current ::root root-id)
              (let [eval-ids (vec (:seon.eval/ids batch))
                    evidence
                    (if (seq eval-ids)
                      (await
                       (db/query
                        {::db/db current
                         ::db/query generated-eval-evidence-query
                         ::db/args [generated-eval-selector run-id turn-id
                                    eval-ids]
                         ::db/max-work 1000000
                         ::db/max-results (max 1 (count eval-ids))
                         ::db/max-result-weight 1048576}))
                      [])]
                (if (:seon.error/message evidence)
                  (assoc evidence ::db/db current ::root root-id)
                  (assoc acquired
                         ::root root-id
                         ::namespace-step namespace-step
                         :seon.ns/name namespace-name
                         ::eval-rows
                         (mapv (fn [[status-tx row]]
                                 (assoc row ::internal/status-tx status-tx))
                               evidence)))))))))))

(defn ^:async ^:no-doc generated-root-candidates
  "Find every durable generated root from its own assignment marker.

   Namespace children are deliberately irrelevant: a root remains observable
   between planner assignment and namespace-DAG publication."
  {:malli/schema [:=> [:cat ::generated-root-candidates-request]
                  ::generated-root-candidates-response]}
  [{database ::db/db}]
  (let [rows
        (await
         (db/query
          {::db/db database
           ::db/query generated-root-candidates-query
           ::db/args [generated-root-rules]
           ::db/max-work 1000000
           ::db/max-results 10000
           ::db/max-result-weight 1048576}))]
    (if (:seon.error/message rows)
      rows
      (->> rows
           (map (fn [[root-id coordinator-id]]
                  {::id root-id :seon.agent/id coordinator-id}))
           (sort-by (juxt ::id :seon.agent/id))
           vec))))

(defn ^:async ^:no-doc generated-root-state
  "Derive stable scheduler state for one generated-code plan root."
  {:malli/schema [:=> [:cat ::id-request]
                  [:or ::generated-root-state ::direct-error]]}
  [{::keys [id] :as request}]
  (let [acquired (await (acquire-root-plan-rows request id))]
    (if (:seon.error/message acquired)
      acquired
      (or (internal/namespace-root-state-from-rows (::rows acquired) id)
          {:seon.error/message
           (str "No generated-code plan root " (pr-str id) ".")}))))

(defn ^:async ^:private acquire-status
  [request step-id]
  (let [database (or (::db/db request) (await (db/db)))]
    (if (:seon.error/message database)
      database
      (let [response
            (await
             (db/execute-many
              {::db/db database
               ::db/members
               [(pull-member plan-row-selector [::id step-id])
                (query-member descendant-rows-query
                              [rules plan-row-selector step-id])
                (query-member blocked-step-query [rules step-id])
                (query-member ready-step-query [rules step-id])]
               ::db/max-result-weight 2097152}))
            [root-member descendants-member blocked-member ready-member]
            (::db/results response)]
        (if (and (not (:seon.error/message response))
                 (= 4 (count (::db/results response)))
                 (every? #(true? (::protocol/success? %))
                         (::db/results response)))
          {::db/db database
           ::rows (into (cond-> [] (member-result root-member)
                          (conj (member-result root-member)))
                        (or (member-result descendants-member) []))
           ::blocked? (boolean (member-result blocked-member))
           ::ready? (boolean (member-result ready-member))}
          {:seon.error/message "Plan status read failed."
           :seon.error/data response
           :seon.error/kind :core-bug})))))

(defn ^:async ^:private acquire-all-plan-rows
  [request]
  (let [database (or (::db/db request) (await (db/db)))]
    (if (:seon.error/message database)
      database
      (let [rows (await (db/query {::db/db database
                                   ::db/query all-plan-rows-query
                                   ::db/args [plan-row-selector]
                                   :datahike.resource/max-work 5000000
                                   :datahike.resource/max-results 200000
                                   :datahike.resource/max-result-weight 2097152}))]
        (if (:seon.error/message rows)
          rows
          {::db/db database ::rows (vec rows)})))))

(defn ^:async ^:private acquire-plan-creation
  [{agent-id :seon.agent/id ::keys [title] :as request}]
  (let [database (or (::db/db request) (await (db/db)))]
    (if (:seon.error/message database)
      database
      (let [response
            (await
             (db/execute-many
              {::db/db database
               ::db/members
               [(query-member agent-exists-query [agent-id])
                (query-member duplicate-root-query
                              [rules plan-row-selector agent-id title])]
               ::db/max-result-weight 262144}))
            [agent-member roots-member] (::db/results response)]
        (if (and (not (:seon.error/message response))
                 (= 2 (count (::db/results response)))
                 (every? #(true? (::protocol/success? %))
                         (::db/results response)))
          {::db/db database
           ::agent-exists? (boolean (member-result agent-member))
           ::rows (vec (or (member-result roots-member) []))}
          {:seon.error/message "Plan creation read failed."
           :seon.error/data response
           :seon.error/kind :core-bug})))))

(defn ^:async ^:private acquire-agent-plan-rows
  [{agent-id :seon.agent/id :as request}]
  (let [database (or (::db/db request) (await (db/db)))]
    (if (:seon.error/message database)
      database
      (let [members (cond-> [(query-member agent-plan-rows-query
                                           [plan-row-selector agent-id])]
                  agent-id (conj (query-member agent-exists-query [agent-id])))
        response
        (await
         (db/execute-many
          {::db/db database
           ::db/members members
           ::db/max-result-weight 2097152}))
        [member agent-member] (::db/results response)]
    (if (and (not (:seon.error/message response))
             (true? (::protocol/success? member))
             (or (nil? agent-id)
                 (true? (::protocol/success? agent-member))))
      {::db/db database
       ::rows (vec (or (::protocol/result member)
                       (:datahike.query/result member)))
       ::agent-exists? (boolean
                         (or (::protocol/result agent-member)
                             (:datahike.query/result agent-member)))}
      {:seon.error/message "Plan database read failed."
       :seon.error/data response
       :seon.error/kind :core-bug})))))

(defn ^:async ^:private acquire-selected-step
  [request]
  (let [database (or (::db/db request) (await (db/db)))]
    (if (:seon.error/message database)
      database
      (let [row (await (db/pull database plan-row-selector [::id (::id request)]))]
        (if (:seon.error/message row)
          row
          {::db/db database ::row row})))))

(defn ^:async ^:private acquire-target-steps
  [request refs]
  (let [database (or (::db/db request) (await (db/db)))
        ids (into [] (keep plan-ref-id) refs)]
    (if (:seon.error/message database)
      database
      (let [rows (if (seq ids)
                   (await (db/pull-many database plan-row-selector
                                        (mapv #(vector ::id %) ids)))
                   [])]
        (if (:seon.error/message rows)
          rows
          {::db/db database ::rows (vec (remove nil? rows))})))))

(defn- acquisition-error
  [fn-name acquired]
  (when-let [message (:seon.error/message acquired)]
    (internal/fail (str fn-name ": " message))))

(defn- expected-write
  [acquired tx-data]
  {::db/db (::db/db acquired)
   ::db/expected-db (::db/db acquired)
   ::db/tx-data tx-data})

(defn- expected-allocation-write
  [acquired tx-data]
  {::db/expected-db (::db/db acquired)
   ::db/tx-data tx-data})

(defn- plan-ref-id [ref]
  (cond
    (and (vector? ref) (= ::id (first ref))) (second ref)
    (map? ref) (::id ref)
    (string? ref) ref))

;; --- Public API

(defn ^{:async true :seon.fn/agent-facing? true} step!
  "Add a new step to the plan.

   Mints one `:open` step (a blank title is refused). Omit
   `:seon.agent/id` and the boundary fills in YOU; pass another id
   to scope elsewhere. `:parent`/`:needs` lookup-refs place it in the
   tree/DAG; `:expect` states its falsifiable outcome — all optional.
   → {::ok? true ::id _} or a fail envelope."
  {:malli/schema [:=> [:cat ::step-request] ::write-response]}
  [{::keys [title description expect from parent needs] agent-id :seon.agent/id
    :as request}]
  (or
    (internal/check-request-keys "step!" request ::step-request)
    (let [agent (internal/agent-ref agent-id)]
      (cond
        (or (nil? title) (str/blank? title))
        (internal/fail "step!: blank :my.plan/title refused — say what the step is.")

        (nil? agent)
        (internal/fail (str "step!: no :seon.agent/id resolved — pass one, or call "
                            "from inside an agent turn (the boundary fills in you)."))

        :else
        (let [target-refs (into (cond-> [] parent (conj parent)) needs)
              target-ids (into #{} (keep plan-ref-id) target-refs)
              acquired (await (acquire-target-steps request target-refs))
              read-error (acquisition-error "step!" acquired)
              found-ids (into #{} (map ::id) (::rows acquired))
              missing (into [] (remove found-ids) target-ids)
              created-at (js/Date.)
              env
              (when-not (or read-error (seq missing))
                (await
                  (db.id/allocate!
                    {::db/db (::db/db acquired)
                     ::db.id/allocations
                     [{::db.id/key ::id
                       ::db.id/identity-attr ::id}]
                     ::db.id/transaction-builder
                     (fn [ids]
                       (let [id (get ids ::id)]
                         (expected-allocation-write
                           acquired
                           [(cond-> {::id id ::title title ::status :open
                                     ::created-at created-at ::agent agent}
                              description (assoc ::description description)
                              expect (assoc ::expect expect)
                              from (assoc ::from from)
                              parent (assoc ::parent parent)
                              (seq needs) (assoc ::needs needs))])))})))
              id (get-in env [::db.id/ids ::id])]
          (or read-error
              (when (seq missing)
                (internal/fail
                 (str "step!: related plan step(s) do not exist "
                      (pr-str missing) ".")))
              (internal/write-result "step!" id env)))))))

(defn ^{:async true :seon.fn/agent-facing? true} plan!
  "Create your plan ONCE: goal, pace, nested steps, and deps.

   CREATE only — author the whole tree in ONE call. To REVISE it later use
   `reconcile!` (edits a `document`, diffing by id); add a step with `step!`;
   close one with `done!`. A second `plan!` with the same title is REFUSED
   (it would duplicate) — and you never need it: the result
   ({::ok? true ::root <root-id> ::ids <label→id>}) arrives on your NEXT turn
   and your plan renders in the `:plan` block, so read it there — do not
   re-call `plan!` to \"check the response\". `:children` nests (`:parent`
   edges); `:ref` labels a node; `:after` names labels it runs after (`needs`
   edges — earlier OR later labels); the root's `:goal` outlives your
   transcript, `:pace :multi-session` means don't race it done in one run;
   any node may carry `:expect`. Or a fail envelope."
  {:malli/schema [:=> [:cat ::plan-request] ::plan-response]}
  [{::keys [title] agent-id :seon.agent/id :as request}]
  (or
    (internal/check-plan-keys "plan!" request)
    (let [agent      (internal/agent-ref agent-id)
          acquired   (await (acquire-plan-creation request))
          read-error (acquisition-error "plan!" acquired)
          rows       (when-not read-error (::rows acquired))
          dup-root   (first rows)]
      (cond
        (or (nil? title) (str/blank? title))
        (internal/fail "plan!: blank :my.plan/title refused — name the plan.")

        (nil? agent)
        (internal/fail (str "plan!: no :seon.agent/id resolved — pass one, or call "
                            "from inside an agent turn (the boundary fills in you)."))

        read-error read-error

        (not (::agent-exists? acquired))
        (internal/fail (str "plan!: unknown agent " (pr-str agent-id) "."))

        ;; Idempotency guard: plan! only CREATES, and a SAME-TITLE re-statement
        ;; (a model re-emitting its plan — common, since eval results arrive
        ;; next turn) would silently duplicate the whole tree. Refuse only the
        ;; duplicate (a distinct new plan is a legitimate forest), routing to
        ;; the UPDATE doors and surfacing the existing root id the caller needs.
        dup-root
        (internal/fail
          (str "plan!: you already have an open plan titled " (pr-str title)
               " (root " (pr-str (:my.plan/id dup-root)) "). plan! CREATES — "
               "re-stating it here duplicates it, it does NOT update. See it "
               "with (my.plan/document {}). To revise the whole tree, edit that "
               "and (my.plan/reconcile! {:my.plan/tree …}) — it diffs by "
               ":my.plan/id (added/dropped/updated) and keeps your progress. Add "
               "one step with my.plan/step!; close one with my.plan/done!."))

        :else
        (let [now     (js/Date.)
              preview (internal/compile-plan agent request {} now)
              error   (::internal/error preview)]
          (if error
            (internal/fail error)
            (let [env
                  (await
                    (db.id/allocate!
                      {::db/db (::db/db acquired)
                       ::db.id/allocations
                       (allocation-declarations
                         (::internal/allocation-keys preview))
                       ::db.id/transaction-builder
                       (fn [ids]
                         (let [compiled
                               (internal/compile-plan agent request ids now)]
                           (when-let [compile-error (::internal/error compiled)]
                             (throw
                               (ex-info "plan compilation changed during allocation"
                                        {:my.plan/error compile-error
                                         :seon.error/kind :core-bug})))
                           (expected-allocation-write
                             acquired
                             (::internal/transaction-data compiled))))}))]
              (if-not (:seon.error/message env)
                (let [compiled
                      (internal/compile-plan agent request (::db.id/ids env) now)]
                  {::ok? true
                   ::root (::internal/root-id compiled)
                   ::ids (::internal/labels compiled)})
                (internal/fail
                  (str "plan!: store failed — "
                       (:seon.error/message env)))))))))))

(def ^:private generated-terminal-root-selector
  '[::id ::status
    {::agent [:seon.agent/id]}
    {::from [:seon.agent/id :seon.user/id]}])

(defn- participant-ref
  [participant]
  (cond
    (:seon.agent/id participant)
    [:seon.agent/id (:seon.agent/id participant)]

    (:seon.user/id participant)
    [:seon.user/id (:seon.user/id participant)]))

(defn- generated-terminal-transaction-builder
  [database message-transaction root-id terminal-status completed-at]
  (let [build-message
        (:seon.agent.message/transaction-builder message-transaction)]
    (fn [ids]
      (let [message-request (build-message ids)]
        (-> message-request
            (assoc ::db/expected-db database)
            (update
             ::db/tx-data
             (fn [message-data]
               (into
                [[:db.fn/cas [::id root-id]
                  ::status :open terminal-status]]
                (concat
                 (when (= :done terminal-status)
                   [{::id root-id ::completed-at completed-at}])
                 message-data)))))))))

(defn ^:async ^:private generated-terminal-race-result
  [database root-id allocation-error]
  (let [current
        (await (db/db {::db/database-name (:db-name database)}))]
    (if (:seon.error/message current)
      allocation-error
      (let [root
            (await
             (db/pull
              {::db/db current
               ::db/pull-pattern [::status]
               ::db/ref [::id root-id]}))
            status (::status root)]
        (if (contains? #{:done :blocked} status)
          {::ok? true ::id root-id ::status status}
          allocation-error)))))

(defn ^:async ^:no-doc commit-generated-terminal!
  "Atomically close one generated root and address its caller.

   Root status is the delivery fence. The ordinary message row and the
   `:open` to terminal CAS share one expected-database allocation transaction,
   so a committed terminal status always means its result message committed."
  {:malli/schema [:=> [:cat ::generated-terminal-request]
                  ::generated-terminal-response]}
  [{database ::db/db
    root-id ::id
    terminal-status ::status
    content :seon.agent.message/content}]
  (let [root
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern generated-terminal-root-selector
           ::db/ref [::id root-id]}))
        current-status (::status root)
        sender (participant-ref (::agent root))
        recipient (participant-ref (::from root))]
    (cond
      (:seon.error/message root)
      (assoc (internal/fail (:seon.error/message root)) ::id root-id)

      (contains? #{:done :blocked} current-status)
      {::ok? true ::id root-id ::status current-status}

      (not= :open current-status)
      (assoc
       (internal/fail
        (str "commit-generated-terminal!: root " (pr-str root-id)
             " has no open terminal-delivery fence."))
       ::id root-id)

      (nil? sender)
      (assoc
       (internal/fail
        (str "commit-generated-terminal!: root " (pr-str root-id)
             " has no coordinator identity in :my.plan/agent."))
       ::id root-id)

      (nil? recipient)
      (assoc
       (internal/fail
        (str "commit-generated-terminal!: root " (pr-str root-id)
             " has no caller identity in required :my.plan/from."))
       ::id root-id)

      :else
      (let [message-transaction
            (await
             (message/message-transaction-for
              database
              {:seon.agent.message/from sender
               :seon.agent.message/to [recipient]
               :seon.agent.message/content content}))]
        (if (:seon.error/message message-transaction)
          (assoc (internal/fail (:seon.error/message message-transaction))
                 ::id root-id)
          (let [allocation
                (await
                 (db.id/allocate!
                  {::db/db database
                   ::db.id/allocations
                   (:seon.agent.message/allocations message-transaction)
                   ::db.id/transaction-builder
                   (generated-terminal-transaction-builder
                    database message-transaction root-id terminal-status
                    (js/Date.))}))]
            (if (:seon.error/message allocation)
              (await
               (generated-terminal-race-result
                database root-id
                (assoc
                 (internal/fail (:seon.error/message allocation))
                 ::id root-id)))
              {::ok? true
               ::id root-id
               ::status terminal-status
               :seon.agent.message/id
               (get-in allocation [::db.id/ids
                                   :seon.agent.message/id])})))))))

(defn ^:async ^:private block-generated-root!
  [database root-id error-message]
  (let [blocked
        (await
         (commit-generated-terminal!
          {::db/db database
           ::id root-id
           ::status :blocked
           :seon.agent.message/content
           (pr-str {::id root-id
                    ::status :blocked
                    ::error error-message})}))]
    (assoc
     (internal/fail
      (str error-message
           (when-not (::ok? blocked)
             (str " The generation root could not be closed and addressed: "
                  (::error blocked)))))
     ::root root-id)))

(defn ^:async ^:no-doc publish-generated-program!
  "Publish one generated turn's already-parsed namespace evidence.

   The turn supplies its exact pinned database value, parsed program, and
   eval-batch result. The pinned value identifies either the planner root or an
   assigned namespace step through the ordinary run cause and message. An
   absent match is the only no-op. Once matched, this operation acquires one
   current database value after eval recording, reads only the exact ordered
   eval identities, and fences one DAG/completion write against that value.
   No result or derived evidence facts are copied."
  {:malli/schema
   [:=> [:cat ::publish-generated-program-request]
    ::publish-generated-program-response]}
  [{program ::program
    batch ::eval-batch
    :as request}]
  (let [acquired (await (acquire-generated-root-plan-rows request))
        root-id (::root acquired)
        database (::db/db acquired)
        namespace-step (::namespace-step acquired)
        namespace-name (:seon.ns/name acquired)
        incomplete-response
        (fn []
          (cond-> {::ok? true
                   ::root root-id
                   :seon.eval/ids (vec (:seon.eval/ids batch))}
            namespace-step
            (assoc ::namespace-ids {namespace-name namespace-step})))]
    (cond
      (:seon.error/message acquired)
      (if (and root-id database)
        (await
         (block-generated-root!
          database root-id
          (str "publish-generated-program!: "
               (:seon.error/message acquired))))
        (internal/fail
         (str "publish-generated-program!: "
              (:seon.error/message acquired))))

      (nil? root-id)
      {::ok? true}

      (:seon.eval/fenced? batch)
      (if namespace-step
        (incomplete-response)
        (await
         (block-generated-root!
          database root-id
          "publish-generated-program!: the planner eval batch lost its run fence.")))

      (or (:seon.error/message batch) (:seon/error batch))
      (if namespace-step
        (incomplete-response)
        (await
         (block-generated-root!
          database root-id
          (str "publish-generated-program!: planner evaluation failed: "
               (or (:seon.error/message batch)
                   (get-in batch [:seon/error :seon.error/message])
                   (pr-str (:seon/error batch)))))))

      :else
      (let [now (js/Date.)
            completion
            (internal/namespace-completion
             program batch (::eval-rows acquired))
            completed-namespaces (::internal/completed-namespaces completion)]
        (if namespace-step
          (let [transaction-data
                (internal/namespace-completion-transaction
                 (::rows acquired) namespace-step
                 (contains? completed-namespaces namespace-name) now)
                written
                (when (seq transaction-data)
                  (await
                   (db/transact!
                    (expected-write acquired transaction-data))))]
            (if (:seon.error/message written)
              (assoc
               (internal/fail
                (str "publish-generated-program!: store failed — "
                     (:seon.error/message written)))
               ::root root-id)
              (incomplete-response)))
          (let [preview
                (internal/compile-namespace-dag
                 (::rows acquired) root-id program {} now
                 completed-namespaces)
                compile-error (::internal/error preview)]
            (if compile-error
              (await
               (block-generated-root!
                database root-id
                (str "publish-generated-program!: " compile-error)))
              (let [allocation-keys (::internal/allocation-keys preview)
                    transaction-data (::internal/transaction-data preview)
                    written
                    (cond
                      (seq allocation-keys)
                      (await
                       (db.id/allocate!
                        {::db/db database
                         ::db.id/allocations
                         (allocation-declarations allocation-keys)
                         ::db.id/transaction-builder
                         (fn [ids]
                           (let [compiled
                                 (internal/compile-namespace-dag
                                  (::rows acquired) root-id program ids now
                                  completed-namespaces)]
                             (when-let [error (::internal/error compiled)]
                               (throw
                                (ex-info
                                 "namespace DAG changed during allocation"
                                 {:my.plan/error error
                                  :seon.error/kind :core-bug})))
                             (expected-allocation-write
                              acquired
                              (::internal/transaction-data compiled))))}))

                      (seq transaction-data)
                      (await
                       (db/transact!
                        (expected-write acquired transaction-data)))

                      :else nil)]
                (if (:seon.error/message written)
                  (assoc
                   (internal/fail
                    (str "publish-generated-program!: store failed — "
                         (:seon.error/message written)))
                   ::root root-id)
                  (let [compiled
                        (internal/compile-namespace-dag
                         (::rows acquired) root-id program
                         (or (::db.id/ids written) {}) now
                         completed-namespaces)]
                    {::ok? true
                     ::root root-id
                     ::diff (::internal/diff compiled)
                     ::namespace-ids (::internal/namespace-ids compiled)
                     :seon.eval/ids (vec (:seon.eval/ids batch))}))))))))))

(defn ^{:async true :seon.fn/agent-facing? true} active!
  "Mark a plan step `:active`, the one you are working on now.

   The active step is your rendered position anchor. One position at a
   time: any other `:active` step of the same agent is demoted back to
   `:open`. A `:done` step must be `reopen!`ed first."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id] :as request}]
  (or
    (internal/check-request-keys "active!" request ::id-request)
    (let [acquired (await (acquire-selected-step request))
          read-error (acquisition-error "active!" acquired)
          row (::row acquired)]
      (or
        read-error
        (case (::status row)
          nil
          (internal/fail (str "active!: no step " (pr-str id)
                              " — (my.plan/next {}) shows the ready ids."))
          :done
          (internal/fail (str "active!: " (pr-str id)
                              " is :done — reopen! it first: "
                              "(my.plan/reopen! {:my.plan/id " (pr-str id) "})."))
          :active {::ok? true ::id id}
          (let [agent-id (get-in row [::agent :seon.agent/id])
                others (await (db/query
                                {::db/db (::db/db acquired)
                                 ::db/query
                                 '[:find [?id ...]
                                   :in $ ?agent-id
                                   :where
                                   [?agent :seon.agent/id ?agent-id]
                                   [?step :my.plan/agent ?agent]
                                   [?step :my.plan/status :active]
                                   [?step :my.plan/id ?id]]
                                 ::db/args [agent-id]}))]
            (if (:seon.error/message others)
              (internal/fail (str "active!: " (:seon.error/message others)))
            (->> (await
                   (db/transact!
                     (expected-write
                       acquired
                       (into [{::id id ::status :active}]
                             (map (fn [other] {::id other ::status :open}))
                             (remove #{id} others)))))
                 (internal/write-result "active!" id)))))))))

(defn ^{:async true :seon.fn/agent-facing? true} done!
  "Record that a plan step is finished and complete.

   VERIFY the step's `::expect` first — done means the outcome holds, not
   that you performed an action. Stamps `::completed-at` and may unblock
   the step's dependents next turn. Already-done is idempotent success;
   unknown id → fail envelope."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id] :as request}]
  (or
    (internal/check-request-keys "done!" request ::id-request)
    (let [acquired (await (acquire-selected-step request))
          read-error (acquisition-error "done!" acquired)
          row (::row acquired)]
      (or
        read-error
        (case (::status row)
          nil (internal/fail (str "done!: no step " (pr-str id)
                                  " — (my.plan/list-open {}) shows the open ids."))
          :done {::ok? true ::id id}
          (->> (await
                 (db/transact!
                   (expected-write
                     acquired
                     [{::id id ::status :done ::completed-at (js/Date.)}])))
               (internal/write-result "done!" id)))))))

(defn ^{:async true :seon.fn/agent-facing? true} blocked!
  "Mark a plan step blocked while retaining its assignment evidence.

   Use this only when the expected outcome cannot currently be satisfied, not
   for an ordinary failed attempt. The stored `:blocked` status preserves the
   claim, assignment message, evals, and run evidence for diagnosis. `reopen!`
   returns the step to the ready frontier and clears its old claim."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id] :as request}]
  (or
   (internal/check-request-keys "blocked!" request ::id-request)
   (let [acquired (await (acquire-selected-step request))
         read-error (acquisition-error "blocked!" acquired)
         row (::row acquired)]
     (or
      read-error
      (case (::status row)
        nil (internal/fail (str "blocked!: no step " (pr-str id)
                                " — (my.plan/tree {}) lists every step id."))
        :done (internal/fail (str "blocked!: " (pr-str id)
                                  " is :done — reopen! it first."))
        :blocked {::ok? true ::id id}
        (->> (await
              (db/transact!
               (expected-write acquired [{::id id ::status :blocked}])))
             (internal/write-result "blocked!" id)))))))

(defn ^{:async true :seon.fn/agent-facing? true} reopen!
  "Flip a done/blocked step back to open; retract its `::completed-at`.

   Absent means absent — nil is never stored. A claimed namespace step also
   retracts its old assignment fence so the ready frontier may claim it again."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id] :as request}]
  (or
    (internal/check-request-keys "reopen!" request ::id-request)
    (let [acquired (await (acquire-selected-step request))
          read-error (acquisition-error "reopen!" acquired)
          row (::row acquired)]
      (or
        read-error
        (case (::status row)
          nil (internal/fail (str "reopen!: no step " (pr-str id)
                                  " — (my.plan/tree {}) lists every step id; "
                                  "reopen! flips a :done/:blocked step back to :open."))
          :open {::ok? true ::id id}
          (->> (await
                 (db/transact!
                   (expected-write
                     acquired
                     [{::id id ::status :open}
                      [:db/retract [::id id] ::completed-at]
                      [:db/retract [::id id] ::claim]])))
               (internal/write-result "reopen!" id)))))))

(defn ^{:async true :seon.fn/agent-facing? true} needs!
  "Add dependency edges; a step is ready only when its needs are done.

   Each `:on` ref becomes a `::needs` edge. Cardinality-many. Remove one via
   `[:db/retract [:my.plan/id id] :my.plan/needs ref]`."
  {:malli/schema [:=> [:cat ::needs-request] ::write-response]}
  [{::keys [id on] :as request}]
  (or
    (internal/check-request-keys "needs!" request ::needs-request)
    (let [acquired (await (acquire-target-steps request (cons [::id id] on)))
          read-error (acquisition-error "needs!" acquired)
          by-id (internal/rows-by-id (::rows acquired))]
      (or
        read-error
        (if-not (by-id id)
          (internal/fail (str "needs!: no step " (pr-str id)
                              " — (my.plan/tree {}) lists every step id. needs! "
                              "adds dependency edges: {:my.plan/id \"<step>\" "
                              ":my.plan/on [[:my.plan/id \"<dep>\"]]}."))
          (let [missing (into [] (remove #(by-id (plan-ref-id %))) on)]
            (if (seq missing)
              (internal/fail
                (str "needs!: dependency step(s) do not exist " (pr-str missing)
                     " — (my.plan/tree {}) lists every step id; retry with "
                     ":my.plan/on [[:my.plan/id \"<dependency-id>\"]]."))
              (->> (await
                     (db/transact!
                       (expected-write
                         acquired
                         (mapv (fn [ref] [:db/add [::id id] ::needs ref]) on))))
                   (internal/write-result "needs!" id)))))))))

(defn ^{:async true :seon.fn/agent-facing? true} move!
  "Move a step under a new parent step.

   `:parent` is cardinality-one, so the new parent replaces the old.
   Identity, status, and deps unchanged."
  {:malli/schema [:=> [:cat ::move-request] ::write-response]}
  [{::keys [id parent] :as request}]
  (or
    (internal/check-request-keys "move!" request ::move-request)
    (let [acquired (await (acquire-target-steps request [[::id id] parent]))
          read-error (acquisition-error "move!" acquired)
          by-id (internal/rows-by-id (::rows acquired))]
      (or
        read-error
        (if-not (by-id id)
          (internal/fail (str "move!: no step " (pr-str id)
                              " — (my.plan/tree {}) lists every step id (the step "
                              "AND its new :my.plan/parent must both exist)."))
          (if-not (by-id (plan-ref-id parent))
            (internal/fail
              (str "move!: parent step does not exist " (pr-str parent)
                   " — (my.plan/tree {}) lists every step id; retry with "
                   ":my.plan/parent [:my.plan/id \"<parent-id>\"]."))
            (->> (await
                   (db/transact!
                     (expected-write acquired [{::id id ::parent parent}])))
                 (internal/write-result "move!" id))))))))

(defn ^{:async true :seon.fn/agent-facing? true} drop!
  "Delete a step and its whole subtree from the plan.

   Retracts the step and every descendant, without marking anything
   complete. Plain `parent` ref ⇒ no cascade, so it walks descendants. History keeps
   them (undo via db/as-of). → {::ok? true ::dropped <count>} or a fail
   envelope."
  {:malli/schema [:=> [:cat ::id-request] ::drop-response]}
  [{::keys [id] :as request}]
  (or
    (internal/check-request-keys "drop!" request ::id-request)
    (let [acquired (await (acquire-root-plan-rows request id))
          read-error (acquisition-error "drop!" acquired)
          rows (::rows acquired)]
      (or read-error
          (if-not ((internal/rows-by-id rows) id)
            (internal/fail (str "drop!: no step " (pr-str id)
                                " — (my.plan/tree {}) lists every step id."))
            (let [ids (distinct
                        (conj (internal/descendant-ids-from-rows rows id) id))
                  env (await
                        (db/transact!
                          (expected-write
                            acquired
                            (mapv (fn [step-id]
                                    [:db.fn/retractEntity [::id step-id]])
                                  ids))))]
              (if-not (:seon.error/message env)
                {::ok? true ::dropped (count ids)}
                (internal/fail
                  (str "drop!: store failed — "
                       (:seon.error/message env))))))))))

(defn ^{:async true :seon.fn/agent-facing? true} reconcile!
  "Revise your existing OPEN plan from ONE edited whole-plan document.

   This is the UPDATE door (`plan!` is create-once). Pass `:my.plan/tree` —
   an edited `document` value (the same nested EDN shape `document` returns;
   `:my.plan/children` also accepted when authoring by hand). It diffs by
   `:my.plan/id` and keeps your progress: a node WITH an id updates in place
   (title/description/expect/goal/pace/parent/needs — never status:
   `active!`/`done!` own that); a node WITHOUT one resolves to the open step
   it re-states when unambiguous — an id-less ROOT resolves to your one open
   root (`::resolved-root true`), an id-less child to a title-identical open
   sibling; an AMBIGUOUS match fails naming the candidate ids (identity is
   never re-minted by an omitted id); a genuinely new node is minted; an open
   step ABSENT from the document is dropped (`drop!` semantics); `:done` steps
   are immune (absent by construction; submitting one fails). `:ref`/`:after`
   label deps work as in `plan!`. ONE transaction for the whole delta. Against
   an EMPTY tree this IS plan authoring — one code path with `plan!`.
   → {::ok? true ::root _ ::ids _ ::diff {::added _ ::dropped _
   ::updated _}} or a fail envelope."
  {:malli/schema [:=> [:cat ::reconcile-request] ::reconcile-response]}
  [{::keys [tree] agent-id :seon.agent/id :as request}]
  (or
    (internal/check-request-keys "reconcile!" request ::reconcile-request)
    (let [agent (internal/agent-ref agent-id)]
      (cond
        (nil? tree)
        (internal/fail (str "reconcile!: pass the edited document as "
                            ":my.plan/tree (EDN) — get it from "
                            "(my.plan/document {})."))

        (nil? agent)
        (internal/fail (str "reconcile!: no :seon.agent/id resolved — pass "
                            "one, or call from inside an agent turn (the "
                            "boundary fills in you)."))

        :else
        (let [nodes (if (map? tree) [tree] (vec tree))
              acquired (await (acquire-agent-plan-rows request))
              read-error (acquisition-error "reconcile!" acquired)]
          (or
            read-error
            (internal/check-doc-keys "reconcile!" nodes)
            (let [rows (::rows acquired)
                  now      (js/Date.)
                  preview  (internal/compile-reconcile
                             rows "reconcile!" agent
                             nodes {} now)
                    tx       (::internal/transaction-data preview)
                    labels   (::internal/labels preview)
                    root-id  (::internal/root-id preview)
                    diff     (::internal/diff preview)
                    error    (::internal/error preview)]
                (cond
                  error      (internal/fail error)
                  (empty? tx) (cond-> {::ok? true ::root root-id ::ids labels
                                       ::diff diff}
                                (::internal/resolved-root? preview)
                                (assoc ::resolved-root true))
                  :else
                  (let [allocation-keys (::internal/allocation-keys preview)
                        env
                        (if (seq allocation-keys)
                          (await
                            (db.id/allocate!
                              {::db/db (::db/db acquired)
                               ::db.id/allocations
                               (allocation-declarations allocation-keys)
                               ::db.id/transaction-builder
                               (fn [ids]
                                 (let [compiled
                                       (internal/compile-reconcile
                                         rows "reconcile!" agent
                                         nodes ids now)]
                                   (when-let [compile-error
                                              (::internal/error compiled)]
                                     (throw
                                       (ex-info
                                         "plan reconciliation changed during allocation"
                                         {:my.plan/error compile-error
                                          :seon.error/kind :core-bug})))
                                   (expected-allocation-write
                                     acquired
                                     (::internal/transaction-data compiled))))}))
                          (await (db/transact!
                                   (expected-write acquired tx))))]
                    (if-not (:seon.error/message env)
                      (let [compiled
                            (if (seq allocation-keys)
                              (internal/compile-reconcile
                                rows "reconcile!" agent
                                nodes
                                (::db.id/ids env) now)
                              preview)]
                        (cond-> {::ok? true
                                 ::root (::internal/root-id compiled)
                                 ::ids (::internal/labels compiled)
                                 ::diff (::internal/diff compiled)}
                          (::internal/resolved-root? compiled)
                          (assoc ::resolved-root true)))
                      (internal/fail
                        (str "reconcile!: store failed — "
                             (:seon.error/message env)))))))))))))

(defn ^{:async true :seon.fn/agent-facing? true} next
  "Get the next plan steps to work on.

   Your focus queue: READY leaves (open, unblocked), oldest first. Omit
   `:seon.agent/id` and the boundary fills in YOU — the work to act
   on now. [] when no agent id resolves. `active!` the one you take up."
  {:malli/schema [:=> [:cat ::next-request] [:vector ::step-ref]]}
  [{agent-id :seon.agent/id :as request}]
  (if-let [_agent (internal/agent-ref agent-id)]
    (let [database (or (::db/db request) (await (db/db)))
          rows (if (:seon.error/message database)
                 database
                 (await
                  (db/query
                   {::db/db database
                    ::db/query
                    '[:find [(pull ?step [:my.plan/id :my.plan/title
                                          :my.plan/created-at]) ...]
                      :in $ % ?agent-id
                      :where
                      [?agent :seon.agent/id ?agent-id]
                      [?step :my.plan/agent ?agent]
                      (ready ?step)]
                    ::db/args [rules agent-id]
                    :datahike.resource/max-results 1000
                    :datahike.resource/max-result-weight 262144})))]
      (if (:seon.error/message rows)
        (internal/fail (str "next: " (:seon.error/message rows)))
        (->> rows
             (sort-by #(.getTime ^js (::created-at %)))
             vec)))
    []))

(defn ^:async position
  "Get one agent's current plan position and root progress.

   The active step wins; otherwise the oldest ready step is next. The result
   carries the root title/goal, focused step, whether it is active, and the
   root subtree's derived done/total progress. Omit `:seon.agent/id` for your
   own plan; root may pass another agent. No plan is a successful response
   with `::position` absent."
  {:malli/schema [:=> [:cat ::position-request] ::position-response]}
  [{agent-id :seon.agent/id :as request}]
  (if-let [agent (internal/agent-ref agent-id)]
    (let [database (or (::db/db request) (await (db/db)))
          run-id (when-not (:seon.error/message database)
                   (await
                    (db/query
                     {::db/db database
                      ::db/query current-run-id-query
                      ::db/args [agent-id]
                      :datahike.resource/max-work 100000
                      :datahike.resource/max-results 8
                      :datahike.resource/max-result-weight 1024})))
          acquired (cond
                     (:seon.error/message database) database
                     (:seon.error/message run-id) run-id
                     :else
                     (await
                      (internal/acquire-plan-block
                       (cond-> {::db/db database :seon.agent/id agent-id}
                         run-id (assoc :seon.agent.run/id run-id)))))
          read-error (acquisition-error "position" acquired)]
      (or read-error
      (if (:seon.agent/entity acquired)
        (if-let [{:my.plan/keys [step chain active? progress]}
                 (::internal/anchor acquired)]
          (let [root (first chain)]
            {::ok? true
             ::position
             (cond-> {::root       (:my.plan/id root)
                      ::title      (:my.plan/title root)
                      ::step       (:my.plan/id step)
                      ::step-title (:my.plan/title step)
                      ::active?    active?
                      ::progress   progress}
               (:my.plan/goal root) (assoc ::goal (:my.plan/goal root)))})
          {::ok? true})
        (internal/fail (str "position: unknown agent " (pr-str agent-id) ".")))))
    (internal/fail (str "position: no :seon.agent/id resolved — pass one, or "
                        "call from inside an agent turn."))))

(defn ^{:async true :seon.fn/agent-facing? true} tree
  "Get the whole plan as nested EDN, the structural read for re-planning.

   Children under `:my.plan/_parent`, dep ids inline. {::root id} → that
   subtree; {::all? true} → every agent's forest; default → the calling
   agent's forest."
  {:malli/schema [:=> [:cat ::tree-request] ::tree-response]}
  [{::keys [root all?] agent-id :seon.agent/id :as request}]
  (let [acquired (await (cond
                          root (acquire-root-plan-rows request root)
                          all? (acquire-all-plan-rows request)
                          :else (acquire-agent-plan-rows request)))
        rows (::rows acquired)]
    (cond
      (acquisition-error "tree" acquired)
      (acquisition-error "tree" acquired)
      root  (internal/subtree-from-rows rows root)
      all?  (internal/forest-from-rows rows)
      :else (if-let [agent (internal/agent-ref agent-id)]
              (-> rows (internal/rows-for-agent agent)
                  internal/forest-from-rows)
              []))))

(defn ^{:async true :seon.fn/agent-facing? true} document
  "Get your open plan as one document to edit and `reconcile!`.

   `tree`'s nested shape (children under `:my.plan/_parent`, dep ids
   inline) with every `:done` step EXCLUDED — history can't be edited
   away. Every node keeps its `:my.plan/id`, so the edit round-trips:
   (my.plan/reconcile! {:my.plan/tree (my.plan/document {})}) is a no-op.
   {::root id} → that open subtree; default → your whole open forest."
  {:malli/schema [:=> [:cat ::tree-request] ::tree-response]}
  [request]
  (internal/prune-done (await (tree request))))

(defn ^{:async true :seon.fn/agent-facing? true} status
  "Check one step's status: done, blocked, ready, and progress.

   Derived view — done? (subtree complete), blocked? (stored :blocked OR an unmet need),
   ready? (open unblocked leaf), and the {::done ::total} subtree roll-up.
   Pass ::id."
  {:malli/schema [:=> [:cat ::id-request] ::status-response]}
  [{::keys [id] :as request}]
  (let [acquired (await (acquire-status request id))]
    (if-let [error (acquisition-error "status" acquired)]
      error
      (assoc (internal/status-view-from-rows (::rows acquired) id)
             ::blocked? (::blocked? acquired)
             ::ready? (::ready? acquired)))))

(defn ^{:async true :seon.fn/agent-facing? true} list-open
  "List unfinished steps (open, active, blocked), oldest first.

   Per agent: omit `:seon.agent/id` and the boundary fills in YOU; {::all? true}
   lists every agent's. Flat — includes parents and blocked steps (use
   `next` for the ready-leaf focus queue)."
  {:malli/schema [:=> [:cat ::list-request] ::list-response]}
  [{::keys [all?] agent-id :seon.agent/id :as request}]
  (let [agent (internal/agent-ref agent-id)]
    (cond
      (and (nil? agent) (not all?))
      (internal/fail (str "list-open: no :seon.agent/id resolved — pass one, "
                          "{::all? true}, or call from inside an agent turn."))

      :else
      (let [database (or (::db/db request) (await (db/db)))
            rows
            (if (:seon.error/message database)
              database
              (await
               (db/query
                {::db/db database
                 ::db/query
                 (if all?
                   '[:find [(pull ?step ?selector) ...]
                     :in $ % ?selector
                     :where
                     [?step :my.plan/id _]
                     (unfinished ?step)]
                   '[:find [(pull ?step ?selector) ...]
                     :in $ % ?selector ?agent-id
                     :where
                     [?agent :seon.agent/id ?agent-id]
                     [?step :my.plan/agent ?agent]
                     (unfinished ?step)])
                 ::db/args (cond-> [rules internal/open-keys]
                             (not all?) (conj agent-id))
                 :datahike.resource/max-results 10000
                 :datahike.resource/max-result-weight 1048576})))]
        (if (:seon.error/message rows)
          (internal/fail (str "list-open: " (:seon.error/message rows)))
          {::ok? true
           ::steps (->> rows
                        (sort-by #(.getTime ^js (::created-at %)))
                        vec)})))))
