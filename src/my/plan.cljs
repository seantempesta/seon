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
         {:my.plan/title \"store the seed expenses\" :my.plan/after [\"schema\"]
          :my.plan/ref \"rows\"}
         {:my.plan/title \"summary fn + tile\" :my.plan/after [\"rows\"]
          :my.plan/expect \"tile renders totals with zero warnings\"}]})
     (my.plan/active! {:my.plan/id \"<schema-step-id>\"})
     ;; …do the work, VERIFY the expect, then:
     (my.plan/done!   {:my.plan/id \"<schema-step-id>\"})"
  (:refer-clojure :exclude [next])
  (:require
    [clojure.string :as str]
    [my.plan.internal :as internal]
    [seon.agent]   ; load-order: request schemas reference :seon.agent/id
    [seon.db :as db]
    [seon.schema :as schema]))

;; --- Attribute schemas — one register! per attr; shared shapes referenced,
;; --- never inlined.

(schema/register! ::id [:and {:seon.db/identity true} :seon.db/id])
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
                               [::ref      {:optional true} :string]
                               [::after    {:optional true} [:vector :string]]
                               [::expect   {:optional true} ::expect]
                               [::children {:optional true} [:vector [:ref ::node]]]]}}
   [:ref ::node]])

(schema/register! ::plan-request
  [:map
   [::title        ::title]
   [::goal         {:optional true} ::goal]
   [::pace         {:optional true} ::pace]
   [::expect       {:optional true} ::expect]
   [:seon.agent/id {:optional true} :seon.agent/id]  ; injected: you (omit)
   [::children     {:optional true} [:vector ::plan-node]]])

(schema/register! ::ids [:map-of :any ::id])   ; author-label / :root → minted id

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
   [::root?        {:optional true} ::id]
   [::all?         {:optional true} ::all?]
   [:seon.agent/id {:optional true} :seon.agent/id]])  ; injected: you (omit)

;; root? → one subtree map; else → a vector of root subtrees; nil when nothing.
(schema/register! ::tree-response [:maybe [:or :map [:vector :map]]])

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

;; --- Public API

(defn ^:async step!
  "Mint one OPEN plan step (agent = caller; blank title refused).

   Omit `:seon.agent/id` and the boundary fills in YOU; pass another id
   to scope elsewhere. `:parent`/`:needs` lookup-refs place it in the
   tree/DAG; `:expect` states its falsifiable outcome — all optional.
   → {::ok? true ::id _} or a fail envelope."
  {:malli/schema [:=> [:cat ::step-request] ::write-response]}
  [{::keys [title description expect from parent needs] agent-id :seon.agent/id}]
  (let [agent (internal/agent-ref agent-id)]
    (cond
      (or (nil? title) (str/blank? title))
      (internal/fail "step!: blank :my.plan/title refused — say what the step is.")

      (nil? agent)
      (internal/fail (str "step!: no :seon.agent/id resolved — pass one, or call "
                          "from inside an agent turn (the boundary fills in you)."))

      :else
      (let [id (db/new-id!)]
        (->> (await (db/transact!
                      {:seon.db/tx-data
                       [(cond-> {::id         id
                                 ::title      title
                                 ::status     :open
                                 ::created-at (js/Date.)
                                 ::agent      agent}
                          description (assoc ::description description)
                          expect      (assoc ::expect expect)
                          from        (assoc ::from from)
                          parent      (assoc ::parent parent)
                          (seq needs) (assoc ::needs needs))]}))
             (internal/write-result "step!" id))))))

(defn ^:async plan!
  "Author a WHOLE plan in ONE transact — goal, pace, nested steps, deps.

   The root carries `:goal` (why — it outlives your transcript) and
   `:pace` (`:multi-session` = don't race it to done in one run).
   `:children` nests (`:parent` edges); `:ref` labels a node; `:after`
   names labels it runs after (`needs` edges — earlier OR later labels);
   any node may carry `:expect`. → {::ok? true ::root <root-id>
   ::ids <label→id>} or a fail envelope."
  {:malli/schema [:=> [:cat ::plan-request] ::plan-response]}
  [{::keys [title] agent-id :seon.agent/id :as request}]
  (let [agent (internal/agent-ref agent-id)]
    (cond
      (or (nil? title) (str/blank? title))
      (internal/fail "plan!: blank :my.plan/title refused — name the plan.")

      (nil? agent)
      (internal/fail (str "plan!: no :seon.agent/id resolved — pass one, or call "
                          "from inside an agent turn (the boundary fills in you)."))

      :else
      (let [{:keys [tx labels root-id error]} (internal/compile-plan agent request)]
        (if error
          (internal/fail error)
          (let [env (await (db/transact! {:seon.db/tx-data tx}))]
            (if (:seon.db/ok? env)
              {::ok? true ::root root-id ::ids labels}
              (internal/fail (str "plan!: store failed — "
                                  (get-in env [:seon.db/error :seon.error/message]))))))))))

(defn ^:async active!
  "Take a step up: mark it `:active` — your rendered position anchor.

   One position at a time: any other `:active` step of the same agent is
   demoted back to `:open`. A `:done` step must be `reopen!`ed first."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id]}]
  (case (internal/status-of id)
    nil     (internal/fail (str "active!: no step " (pr-str id)
                                " — (my.plan/next {}) shows the ready ids."))
    :done   (internal/fail (str "active!: " (pr-str id)
                                " is :done — reopen! it first."))
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
           (internal/write-result "active!" id)))))

(defn ^:async done!
  "Mark a step done; may unblock its dependents next turn.

   VERIFY the step's `::expect` first — done means the outcome holds, not
   that you performed an action. Stamps `::completed-at`. Already-done is
   idempotent success; unknown id → fail envelope."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id]}]
  (case (internal/status-of id)
    nil   (internal/fail (str "done!: no step " (pr-str id)
                              " — (my.plan/list-open {}) shows the open ids."))
    :done {::ok? true ::id id}
    (->> (await (db/transact!
                  {:seon.db/tx-data [{::id           id
                                      ::status       :done
                                      ::completed-at (js/Date.)}]}))
         (internal/write-result "done!" id))))

(defn ^:async reopen!
  "Flip a done/blocked step back to open; retract its `::completed-at`.

   Absent means absent — nil is never stored."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id]}]
  (case (internal/status-of id)
    nil   (internal/fail (str "reopen!: no step " (pr-str id) "."))
    :open {::ok? true ::id id}
    (->> (await (db/transact!
                  {:seon.db/tx-data
                   [{::id id ::status :open}
                    [:db/retract [::id id] ::completed-at]]}))
         (internal/write-result "reopen!" id))))

(defn ^:async needs!
  "Add dependency edge(s) — the step is ready only after each `:on` is done.

   Cardinality-many. Remove one via
   `[:db/retract [:my.plan/id id] :my.plan/needs ref]`."
  {:malli/schema [:=> [:cat ::needs-request] ::write-response]}
  [{::keys [id on]}]
  (case (internal/status-of id)
    nil (internal/fail (str "needs!: no step " (pr-str id) "."))
    (->> (await (db/transact!
                  {:seon.db/tx-data
                   (mapv (fn [ref] [:db/add [::id id] ::needs ref]) on)}))
         (internal/write-result "needs!" id))))

(defn ^:async move!
  "Re-parent a step — the new parent replaces the old.

   `:parent` is cardinality-one. Identity, status, and deps unchanged."
  {:malli/schema [:=> [:cat ::move-request] ::write-response]}
  [{::keys [id parent]}]
  (case (internal/status-of id)
    nil (internal/fail (str "move!: no step " (pr-str id) "."))
    (->> (await (db/transact! {:seon.db/tx-data [{::id id ::parent parent}]}))
         (internal/write-result "move!" id))))

(defn ^:async drop!
  "Retract a step AND its whole subtree.

   Plain `parent` ref ⇒ no cascade, so it walks descendants. History keeps
   them (undo via db/as-of). → {::ok? true ::dropped <count>} or a fail
   envelope."
  {:malli/schema [:=> [:cat ::id-request] ::drop-response]}
  [{::keys [id]}]
  (await (internal/retract-subtree! id)))

(defn next
  "Your focus queue: READY leaves (open, unblocked), oldest first.

   Omit `:seon.agent/id` and the boundary fills in YOU — the work to act
   on now. [] when no agent id resolves. `active!` the one you take up."
  {:malli/schema [:=> [:cat ::next-request] [:vector ::step-ref]]}
  [{agent-id :seon.agent/id}]
  (if-let [agent (internal/agent-ref agent-id)]
    (let [db @db/*conn*]
      (if-let [oe (internal/agent-eid db agent)]
        (internal/ready-leaves db oe)
        []))
    []))

(defn tree
  "The plan as nested EDN — the structural read you re-plan over.

   Children under `:my.plan/_parent`, dep ids inline. {::root? id} → that
   subtree; {::all? true} → every agent's forest; default → the calling
   agent's forest."
  {:malli/schema [:=> [:cat ::tree-request] ::tree-response]}
  [{::keys [root? all?] agent-id :seon.agent/id}]
  (let [db @db/*conn*]
    (cond
      root? (internal/pull-subtree db root?)
      all?  (mapv #(internal/pull-subtree db %) (internal/all-root-ids db))
      :else (if-let [agent (internal/agent-ref agent-id)]
              (if-let [oe (internal/agent-eid db agent)]
                (mapv #(internal/pull-subtree db %) (internal/root-ids db oe))
                [])
              []))))

(defn status
  "Derived view of one step — done/blocked/ready + subtree roll-up.

   done? (subtree complete), blocked? (stored :blocked OR an unmet need),
   ready? (open unblocked leaf), and the {::done ::total} subtree roll-up.
   Pass ::id."
  {:malli/schema [:=> [:cat ::id-request] ::status-response]}
  [{::keys [id]}]
  (internal/status-view @db/*conn* id))

(defn list-open
  "Unfinished steps (open/active/blocked), oldest first, per agent.

   Omit `:seon.agent/id` and the boundary fills in YOU; {::all? true}
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
