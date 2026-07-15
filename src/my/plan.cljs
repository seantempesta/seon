(ns my.plan
  "Your PLANNING system — a per-agent dependency graph, not a todo list.
   One step = one `:my.plan` entity: a `:my.plan/parent` ref nests steps
   under a root (whose `:my.plan/goal` says WHY and `:my.plan/pace` says
   whether this spans sessions), `:my.plan/needs` refs make dependency
   edges (a step is READY only when every need is `:done`), and
   `:my.plan/status` `:active` marks where you ARE (one step at a time —
   `active!` demotes the rest). `:my.plan/expect` states the falsifiable
   outcome of a step — VERIFY it before `done!`, don't close on \"I
   performed an action\". Progress, blocked-ness, readiness, and your
   position anchor are all DERIVED from the step facts every turn — nothing
   derivable is stored.

   Every turn your plan section renders WINDOWED: the position anchor
   (goal + where you are + N of M done), the open frontier (active + ready
   steps), and a short recently-completed tail — the completed interior
   stays queryable (`tree`, `status`), out of the prompt. After a restart,
   re-ground from that anchor, not from transcript archaeology.

   For any multi-step task: lay the WHOLE plan down first, `active!` the
   step you take up, verify its `expect`, `done!` it, move on. Worked
   example:

     (my.plan/plan!
       {:my.plan/title \"Ship the expense tracker\"
        :my.plan/goal  \"a tracker my human keeps using across sessions\"
        :my.plan/pace  :multi-session
        :my.plan/children
        [{:my.plan/title \"design + register the schema\" :my.plan/ref \"schema\"
          :my.plan/expect \"register! returns and a test row transacts\"}
         {:my.plan/title \"write the seed expenses\" :my.plan/after [\"schema\"]
          :my.plan/ref \"rows\"}
         {:my.plan/title \"summary fn + surface\" :my.plan/after [\"rows\"]
          :my.plan/expect \"surface renders totals with zero warnings\"}]})
     (my.plan/active! {:my.plan/id \"<schema-step-id>\"})
     ;; …do the work, VERIFY the expect, then:
     (my.plan/done!   {:my.plan/id \"<schema-step-id>\"})"
  (:refer-clojure :exclude [next])
  (:require
    [clojure.string :as str]
    [my.plan.internal :as internal]
    [seon.agent]   ; load-order: request schemas reference :seon.agent/id
    [seon.db :as db]
    [seon.db.id :as db.id]
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

(schema/register! ::write-response
  [:map
   [::ok?   ::ok?]
   [::id    {:optional true} ::id]
   [::error {:optional true} ::error]])

(schema/register! ::id-request [:map [::id ::id]])

(schema/register! ::step-request
  [:map
   [::title         ::title]
   [::description   {:optional true} ::description]
   [::expect        {:optional true} ::expect]
   [:seon.agent/id  {:optional true} :seon.agent/id]  ; injected: you (omit) — or another agent's id
   [::from          {:optional true} :seon.db/ref]
   [::parent        {:optional true} ::parent]
   [::needs         {:optional true} ::needs]])

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
   [::children     {:optional true} [:vector ::plan-node]]])

(schema/register! ::ids
  [:map-of [:or :string [:= :root]] ::id])     ; author-label / :root → minted id

(schema/register! ::plan-response
  [:map
   [::ok?   ::ok?]
   [::root  {:optional true} ::id]
   [::ids   {:optional true} ::ids]
   [::error {:optional true} ::error]])

(schema/register! ::needs-request [:map [::id ::id] [::on ::on]])
(schema/register! ::move-request  [:map [::id ::id] [::parent ::parent]])

(schema/register! ::drop-response
  [:map
   [::ok?      ::ok?]
   [::dropped  {:optional true} ::dropped]
   [::error    {:optional true} ::error]])

(schema/register! ::status-response
  [:map
   [::id       ::id]
   [::done?    ::done?]
   [::blocked? ::blocked?]
   [::ready?   ::ready?]
   [::progress ::progress]])

(schema/register! ::step-ref
  [:map [::id ::id] [::title ::title] [::created-at ::created-at]])

(schema/register! ::next-request
  [:map [:seon.agent/id {:optional true} :seon.agent/id]])  ; injected: you (omit)

(schema/register! ::tree-request
  [:map
   [::root         {:optional true} ::id]   ; a root STEP ID — that subtree only
   [::all?         {:optional true} ::all?]
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
   [::all?         {:optional true} ::all?]])        ; true = every agent's steps

(schema/register! ::list-response
  [:map
   [::ok?   ::ok?]
   [::steps {:optional true} ::steps]
   [::error {:optional true} ::error]])

(schema/register! ::active? :boolean)
(schema/register! ::position-request
  [:map
   [:seon.db/db    {:optional true} :seon.db/db-val]
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
        (let [created-at (js/Date.)
              env
              (await
                (db.id/allocate!
                  {::db.id/allocations
                   [{::db.id/key ::id
                     ::db.id/identity-attr ::id}]
                   ::db.id/transaction-builder
                   (fn [ids]
                     (let [id (get ids ::id)]
                       {:seon.db/tx-data
                        [(cond-> {::id         id
                                  ::title      title
                                  ::status     :open
                                  ::created-at created-at
                                  ::agent      agent}
                           description (assoc ::description description)
                           expect      (assoc ::expect expect)
                           from        (assoc ::from from)
                           parent      (assoc ::parent parent)
                           (seq needs) (assoc ::needs needs))]}))
                   :seon.db/conn db/*conn*}))
              id (get-in env [::db.id/ids ::id])]
          (internal/write-result "step!" id env))))))

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
          db         @db/*conn*
          oe         (when agent (internal/agent-eid db agent))
          open-roots (when oe (internal/open-forest db oe))
          dup-root   (some #(when (= (:my.plan/title %) title) %) open-roots)]
      (cond
        (or (nil? title) (str/blank? title))
        (internal/fail "plan!: blank :my.plan/title refused — name the plan.")

        (nil? agent)
        (internal/fail (str "plan!: no :seon.agent/id resolved — pass one, or call "
                            "from inside an agent turn (the boundary fills in you)."))

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
                      {::db.id/allocations
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
                           {:seon.db/tx-data
                            (::internal/transaction-data compiled)}))
                       :seon.db/conn db/*conn*}))]
              (if (:seon.db/ok? env)
                (let [compiled
                      (internal/compile-plan agent request (::db.id/ids env) now)]
                  {::ok? true
                   ::root (::internal/root-id compiled)
                   ::ids (::internal/labels compiled)})
                (internal/fail
                  (str "plan!: store failed — "
                       (get-in env
                               [:seon.db/error :seon.error/message])))))))))))

(defn ^{:async true :seon.fn/agent-facing? true} active!
  "Mark a plan step `:active`, the one you are working on now.

   The active step is your rendered position anchor. One position at a
   time: any other `:active` step of the same agent is demoted back to
   `:open`. A `:done` step must be `reopen!`ed first."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id] :as request}]
  (or
    (internal/check-request-keys "active!" request ::id-request)
    (case (internal/status-of id)
      nil     (internal/fail (str "active!: no step " (pr-str id)
                                  " — (my.plan/next {}) shows the ready ids."))
      :done   (internal/fail (str "active!: " (pr-str id)
                                  " is :done — reopen! it first: "
                                  "(my.plan/reopen! {:my.plan/id " (pr-str id) "})."))
      :active {::ok? true ::id id}
      (let [db     @db/*conn*
            agent  (:db/id (::agent (db/entity db [::id id])))
            others (when agent
                     (mapv :my.plan/id (internal/active-steps db agent)))]
        (->> (await (db/transact!
                      {:seon.db/tx-data
                       (into [{::id id ::status :active}]
                             (map (fn [o] {::id o ::status :open}))
                             (remove #{id} others))}))
             (internal/write-result "active!" id))))))

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
    (case (internal/status-of id)
      nil   (internal/fail (str "done!: no step " (pr-str id)
                                " — (my.plan/list-open {}) shows the open ids."))
      :done {::ok? true ::id id}
      (->> (await (db/transact!
                    {:seon.db/tx-data [{::id           id
                                        ::status       :done
                                        ::completed-at (js/Date.)}]}))
           (internal/write-result "done!" id)))))

(defn ^{:async true :seon.fn/agent-facing? true} reopen!
  "Flip a done/blocked step back to open; retract its `::completed-at`.

   Absent means absent — nil is never stored."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id] :as request}]
  (or
    (internal/check-request-keys "reopen!" request ::id-request)
    (case (internal/status-of id)
      nil   (internal/fail (str "reopen!: no step " (pr-str id)
                                " — (my.plan/tree {}) lists every step id; "
                                "reopen! flips a :done/:blocked step back to :open."))
      :open {::ok? true ::id id}
      (->> (await (db/transact!
                    {:seon.db/tx-data
                     [{::id id ::status :open}
                      [:db/retract [::id id] ::completed-at]]}))
           (internal/write-result "reopen!" id)))))

(defn ^{:async true :seon.fn/agent-facing? true} needs!
  "Add dependency edges; a step is ready only when its needs are done.

   Each `:on` ref becomes a `::needs` edge. Cardinality-many. Remove one via
   `[:db/retract [:my.plan/id id] :my.plan/needs ref]`."
  {:malli/schema [:=> [:cat ::needs-request] ::write-response]}
  [{::keys [id on] :as request}]
  (or
    (internal/check-request-keys "needs!" request ::needs-request)
    (case (internal/status-of id)
      nil (internal/fail (str "needs!: no step " (pr-str id)
                              " — (my.plan/tree {}) lists every step id. needs! "
                              "adds dependency edges: {:my.plan/id \"<step>\" "
                              ":my.plan/on [[:my.plan/id \"<dep>\"]]}."))
      (let [db      @db/*conn*
            missing (internal/unresolved-step-refs db on)]
        (if (seq missing)
          (internal/fail
            (str "needs!: dependency step(s) do not exist " (pr-str missing)
                 " — (my.plan/tree {}) lists every step id; retry with "
                 ":my.plan/on [[:my.plan/id \"<dependency-id>\"]]."))
          (->> (await (db/transact!
                        {:seon.db/tx-data
                         (mapv (fn [ref] [:db/add [::id id] ::needs ref]) on)}))
               (internal/write-result "needs!" id)))))))

(defn ^{:async true :seon.fn/agent-facing? true} move!
  "Move a step under a new parent step.

   `:parent` is cardinality-one, so the new parent replaces the old.
   Identity, status, and deps unchanged."
  {:malli/schema [:=> [:cat ::move-request] ::write-response]}
  [{::keys [id parent] :as request}]
  (or
    (internal/check-request-keys "move!" request ::move-request)
    (case (internal/status-of id)
      nil (internal/fail (str "move!: no step " (pr-str id)
                              " — (my.plan/tree {}) lists every step id (the step "
                              "AND its new :my.plan/parent must both exist)."))
      (let [db      @db/*conn*
            missing (internal/unresolved-step-refs db [parent])]
        (if (seq missing)
          (internal/fail
            (str "move!: parent step does not exist " (pr-str parent)
                 " — (my.plan/tree {}) lists every step id; retry with "
                 ":my.plan/parent [:my.plan/id \"<parent-id>\"]."))
          (->> (await (db/transact! {:seon.db/tx-data [{::id id ::parent parent}]}))
               (internal/write-result "move!" id)))))))

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
    (await (internal/retract-subtree! id))))

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
        (let [nodes (if (map? tree) [tree] (vec tree))]
          (or
            (internal/check-doc-keys "reconcile!" nodes)
            (let [db-value @db/*conn*
                  now      (js/Date.)
                  preview  (internal/compile-reconcile
                             db-value "reconcile!" agent
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
                              {::db.id/allocations
                               (allocation-declarations allocation-keys)
                               ::db.id/transaction-builder
                               (fn [ids]
                                 (let [compiled
                                       (internal/compile-reconcile
                                         db-value "reconcile!" agent
                                         nodes ids now)]
                                   (when-let [compile-error
                                              (::internal/error compiled)]
                                     (throw
                                       (ex-info
                                         "plan reconciliation changed during allocation"
                                         {:my.plan/error compile-error
                                          :seon.error/kind :core-bug})))
                                   {:seon.db/tx-data
                                    (::internal/transaction-data compiled)}))
                               :seon.db/conn db/*conn*}))
                          (await (db/transact! {:seon.db/tx-data tx})))]
                    (if (:seon.db/ok? env)
                      (let [compiled
                            (if (seq allocation-keys)
                              (internal/compile-reconcile
                                db-value "reconcile!" agent
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
                             (get-in env [:seon.db/error
                                          :seon.error/message])))))))))))))

(defn ^:seon.fn/agent-facing? next
  "Get the next plan steps to work on.

   Your focus queue: READY leaves (open, unblocked), oldest first. Omit
   `:seon.agent/id` and the boundary fills in YOU — the work to act
   on now. [] when no agent id resolves. `active!` the one you take up."
  {:malli/schema [:=> [:cat ::next-request] [:vector ::step-ref]]}
  [{agent-id :seon.agent/id}]
  (if-let [agent (internal/agent-ref agent-id)]
    (let [db @db/*conn*]
      (if-let [oe (internal/agent-eid db agent)]
        (internal/ready-leaves db oe)
        []))
    []))

(defn position
  "Get one agent's current plan position and root progress.

   The active step wins; otherwise the oldest ready step is next. The result
   carries the root title/goal, focused step, whether it is active, and the
   root subtree's derived done/total progress. Omit `:seon.agent/id` for your
   own plan; root may pass another agent. No plan is a successful response
   with `::position` absent."
  {:malli/schema [:=> [:cat ::position-request] ::position-response]}
  [{db-value :seon.db/db agent-id :seon.agent/id}]
  (if-let [agent (internal/agent-ref agent-id)]
    (let [database (or db-value @db/*conn*)]
      (if-let [agent-eid (internal/agent-eid database agent)]
        (if-let [{:my.plan/keys [step chain active? progress]}
                 (internal/anchor database agent-eid)]
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
        (internal/fail (str "position: unknown agent " (pr-str agent-id) "."))))
    (internal/fail (str "position: no :seon.agent/id resolved — pass one, or "
                        "call from inside an agent turn."))))

(defn ^:seon.fn/agent-facing? tree
  "Get the whole plan as nested EDN, the structural read for re-planning.

   Children under `:my.plan/_parent`, dep ids inline. {::root id} → that
   subtree; {::all? true} → every agent's forest; default → the calling
   agent's forest."
  {:malli/schema [:=> [:cat ::tree-request] ::tree-response]}
  [{::keys [root all?] agent-id :seon.agent/id}]
  (let [db @db/*conn*]
    (cond
      root  (internal/pull-subtree db root)
      all?  (mapv #(internal/pull-subtree db %) (internal/all-root-ids db))
      :else (if-let [agent (internal/agent-ref agent-id)]
              (if-let [oe (internal/agent-eid db agent)]
                (mapv #(internal/pull-subtree db %) (internal/root-ids db oe))
                [])
              []))))

(defn ^:seon.fn/agent-facing? document
  "Get your open plan as one document to edit and `reconcile!`.

   `tree`'s nested shape (children under `:my.plan/_parent`, dep ids
   inline) with every `:done` step EXCLUDED — history can't be edited
   away. Every node keeps its `:my.plan/id`, so the edit round-trips:
   (my.plan/reconcile! {:my.plan/tree (my.plan/document {})}) is a no-op.
   {::root id} → that open subtree; default → your whole open forest."
  {:malli/schema [:=> [:cat ::tree-request] ::tree-response]}
  [request]
  (internal/prune-done (tree request)))

(defn ^:seon.fn/agent-facing? status
  "Check one step's status: done, blocked, ready, and progress.

   Derived view — done? (subtree complete), blocked? (stored :blocked OR an unmet need),
   ready? (open unblocked leaf), and the {::done ::total} subtree roll-up.
   Pass ::id."
  {:malli/schema [:=> [:cat ::id-request] ::status-response]}
  [{::keys [id]}]
  (internal/status-view @db/*conn* id))

(defn ^:seon.fn/agent-facing? list-open
  "List unfinished steps (open, active, blocked), oldest first.

   Per agent: omit `:seon.agent/id` and the boundary fills in YOU; {::all? true}
   lists every agent's. Flat — includes parents and blocked steps (use
   `next` for the ready-leaf focus queue)."
  {:malli/schema [:=> [:cat ::list-request] ::list-response]}
  [{::keys [all?] agent-id :seon.agent/id}]
  (let [agent (internal/agent-ref agent-id)]
    (cond
      (nil? db/*conn*)
      (internal/fail "list-open: no conn bound — runs inside an agent's universe.")

      (and (nil? agent) (not all?))
      (internal/fail (str "list-open: no :seon.agent/id resolved — pass one, "
                          "{::all? true}, or call from inside an agent turn."))

      :else
      (let [db @db/*conn*]
        {::ok?   true
         ::steps (if all?
                   (internal/open-steps db nil)
                   (if-let [oe (internal/agent-eid db agent)]
                     (internal/open-steps db oe)
                     []))}))))   ; unknown agent = no steps, derived view
