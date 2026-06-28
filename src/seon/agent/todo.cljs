(ns seon.agent.todo
  "Your plan + work queue as a TREE. A todo is one `:seon.agent.todo` entity; a
   `:seon.agent.todo/parent` ref makes the list a PLAN (top = milestones,
   leaves = the actions you actually do); a `:seon.agent.todo/depends-on` ref
   SEQUENCES work (\"do B after A\" = B depends-on A). Progress, blocked-ness,
   and \"what's next\" are all DERIVED — you store leaf facts (`:open`/`:done`),
   the queue re-derives every turn. THE EXEMPLAR store/retrieve ns: attrs via
   `schema/register!`, map-in/map-out fns, errors as `:seon.agent.todo/ok?`
   envelopes (never throws), pure derived views for context rendering.

   WHEN: any task with two or more steps. Mint the steps BEFORE you start, then
   close each as it lands. Two ways to structure:

     ;; one-liners — sequencing is just a dependency:
     (seon.agent.todo/add! {:seon.agent.todo/title \"research vendor X\"})  ; => {…/id \"a\"}
     (seon.agent.todo/add! {:seon.agent.todo/title \"write the brief\"
                            :seon.agent.todo/depends-on [[:seon.agent.todo/id \"a\"]]})

     ;; or author a WHOLE plan in ONE transact — children nest into the tree,
     ;; :ref labels a node, :after names labels it runs after (a depends-on edge):
     (seon.agent.todo/plan!
       {:seon.agent.todo/title \"Process inbox → KB\"
        :seon.agent.todo/children
        [{:seon.agent.todo/title \"process notes-a.md\" :seon.agent.todo/ref \"a\"}
         {:seon.agent.todo/title \"process notes-b.md\" :seon.agent.todo/ref \"b\"}
         {:seon.agent.todo/title \"synthesize findings\" :seon.agent.todo/after [\"a\" \"b\"]}]})

   Then run the loop off `next` — it surfaces only READY leaves (open, unblocked,
   no incomplete children), oldest first, so you cannot pick blocked work or
   re-do finished work. `done!` a leaf when it lands; that may unblock its
   dependents next turn. `tree` shows the whole structure when you re-plan;
   `status` is the derived view of one node. Your open items also render in the
   open-todos section every turn — an empty section is the done-signal."
  (:refer-clojure :exclude [next])
  (:require
    [clojure.string :as str]
    [seon.agent.todo.internal :as internal]
    [seon.db :as db]
    [seon.schema :as schema]))

;; --- Attribute schemas — one register! per attr; shared shapes
;; --- (:seon.db/id, :seon.db/ref) referenced, never inlined.

(schema/register! ::id [:and {:seon.db/identity true} :seon.db/id])
(schema/register! ::title [:string {:min 1}])
(schema/register! ::description :string)
(schema/register! ::status [:enum :open :done])
(schema/register! ::created-at :inst)
(schema/register! ::completed-at :inst)
(schema/register! ::owner :seon.db/ref)   ; the agent this item belongs to (the SCOPE ref)
(schema/register! ::from :seon.db/ref)     ; who asked (the user or an agent)
(schema/register! ::message :seon.db/ref)  ; the inbound message this address-todo tracks
(schema/register! ::parent :seon.db/ref)            ; the TREE edge — plain ref, no cascade
(schema/register! ::depends-on [:vector :seon.db/ref]) ; the DAG edges — plain cardinality-many

;; --- The work-queue semantics: ONE rule set, plain data you can read AND
;; --- extend. Pass it as `%` in your own queries. The whole point: the queue
;; --- (next/blocked/ready/roll-up) is pure Datalog over the two refs — nothing
;; --- derivable is stored.

(def rules
  "Datalog rules over the two refs:
   - descendant — the tree's transitive closure (recursive, cycle-safe).
   - leaf — a todo nothing names as parent (an action, not a milestone).
   - open-work — ?t is open itself, or some open leaf sits in its subtree.
   - blocked — some dependency still has open work (transitivity rides on
     done-ness, so no recursive DAG walk and no cycle risk in the queue path).
   - ready — an open, unblocked leaf: real work you can do RIGHT NOW.
   Negations (`leaf`, `not (blocked …)`) only FILTER bound tuples, so every
   query binds its entity with a positive clause BEFORE invoking these."
  '[[(descendant ?a ?n) [?n :seon.agent.todo/parent ?a]]
    [(descendant ?a ?n) [?m :seon.agent.todo/parent ?a] (descendant ?m ?n)]
    [(leaf ?t) (not-join [?t] [?c :seon.agent.todo/parent ?t])]
    [(open-work ?t) [?t :seon.agent.todo/status :open] (leaf ?t)]
    [(open-work ?t) (descendant ?t ?l) [?l :seon.agent.todo/status :open] (leaf ?l)]
    [(blocked ?t) [?t :seon.agent.todo/depends-on ?d] (open-work ?d)]
    [(ready ?t) [?t :seon.agent.todo/status :open] (leaf ?t) (not (blocked ?t))]])

;; --- Request/response schemas. ::ok? is the envelope discriminator;
;; --- failures carry a guiding ::error (errors are values — branch, don't catch).

(schema/register! ::ok? :boolean)
(schema/register! ::error :string)
(schema/register! ::all? :boolean)
(schema/register! ::dropped :int)
(schema/register! ::on [:vector :seon.db/ref])      ; depends! edge targets
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

(schema/register! ::add-request
  [:map
   [::title       ::title]
   [::description {:optional true} ::description]
   [::owner       {:optional true} :seon.db/ref]   ; default: the ALS agent
   [::from        {:optional true} :seon.db/ref]
   [::parent      {:optional true} ::parent]
   [::depends-on  {:optional true} ::depends-on]])

;; plan! — a plan is data. :children nests (each level = a :parent edge); :ref
;; labels a node; :after names labels this node runs after (a depends-on edge).
;; Recursive, self-contained shape (a child may carry its own :children).
(schema/register! ::plan-node
  [:schema {:registry {::node [:map
                               [::title ::title]
                               [::ref        {:optional true} :string]
                               [::after      {:optional true} [:vector :string]]
                               [::children   {:optional true} [:vector [:ref ::node]]]]}}
   [:ref ::node]])

(schema/register! ::plan-request
  [:map
   [::title    ::title]
   [::children {:optional true} [:vector ::plan-node]]])

(schema/register! ::ids [:map-of :any ::id])   ; author-label / :root → minted id

(schema/register! ::plan-response
  [:map
   [::ok?   ::ok?]
   [::root  {:optional true} ::id]
   [::ids   {:optional true} ::ids]
   [::error {:optional true} ::error]])

(schema/register! ::depends-request [:map [::id ::id] [::on ::on]])
(schema/register! ::move-request    [:map [::id ::id] [::parent ::parent]])

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

(schema/register! ::todo-ref
  [:map [::id ::id] [::title ::title] [::created-at ::created-at]])

(schema/register! ::tree-request
  [:map
   [::root? {:optional true} ::id]
   [::all?  {:optional true} ::all?]])

;; root? → one subtree map; else → a vector of root subtrees; nil when nothing.
(schema/register! ::tree-response [:maybe [:or :map [:vector :map]]])

;; --- The stored entity kind. `{:seon.db/entity true}` DECLARES that
;; --- rows of this shape live in the DB (it's what puts the kind in the
;; --- catalog); request/response envelopes above carry no marker.

(schema/register! ::todo
  [:map {:seon.db/entity true}
   [::id          ::id]
   [::title       ::title]
   [::created-at  ::created-at]
   [::description {:optional true} ::description]
   [::message     {:optional true} ::message]
   [::parent      {:optional true} ::parent]
   [::depends-on  {:optional true} ::depends-on]])

(schema/register! ::todos [:vector ::todo])

(schema/register! ::list-request
  [:map
   [::owner {:optional true} :seon.db/ref]   ; default: the ALS agent
   [::all?  {:optional true} ::all?]])       ; true = every owner's items

(schema/register! ::list-response
  [:map
   [::ok?   ::ok?]
   [::todos {:optional true} ::todos]
   [::error {:optional true} ::error]])

;; --- Public API

(defn ^:async add!
  "Mint one OPEN work item. Owner defaults to the calling agent (ALS scope);
   blank title refused. `:parent` and `:depends-on` (lookup-refs) place it in
   the tree / DAG at birth; both default absent (a free-standing ready leaf).
   Resolves to {::ok? true ::id _} or a fail envelope."
  {:malli/schema [:=> [:cat ::add-request] ::write-response]}
  [{::keys [title description owner from parent depends-on]}]
  (let [owner (internal/scoped-owner owner)]
    (cond
      (or (nil? title) (str/blank? title))
      (internal/fail "add!: blank :seon.agent.todo/title refused — say what the work item is.")

      (nil? owner)
      (internal/fail (str "add!: no :seon.agent.todo/owner and no agent in scope — pass an "
                          "owner ref or call inside (db/with-agent …)."))

      :else
      (let [id (db/new-id!)]
        (->> (await (db/transact!
                      {:seon.db/tx-data
                       [(cond-> {::id         id
                                 ::title      title
                                 ::status     :open
                                 ::created-at (js/Date.)
                                 ::owner      owner}
                          description       (assoc ::description description)
                          from              (assoc ::from from)
                          parent            (assoc ::parent parent)
                          (seq depends-on)  (assoc ::depends-on depends-on))]}))
             (internal/write-result "add!" id))))))

(defn ^:async plan!
  "Author a WHOLE plan in ONE transact. `:children` nests (each level becomes a
   `:parent` edge); `:ref` labels a node; `:after` names labels this node runs
   after (a `depends-on` edge). Cross-sibling links compile to string tempids,
   so `:after` may reference any label in the plan, defined earlier OR later.
   Returns {::ok? true ::root <root-id> ::ids <label→id>} or a fail envelope."
  {:malli/schema [:=> [:cat ::plan-request] ::plan-response]}
  [{::keys [title children]}]
  (let [owner (internal/scoped-owner nil)]
    (cond
      (or (nil? title) (str/blank? title))
      (internal/fail "plan!: blank :seon.agent.todo/title refused — name the plan.")

      (nil? owner)
      (internal/fail (str "plan!: no agent in scope — call inside (db/with-agent …)."))

      :else
      (let [{:keys [tx labels root-id error]} (internal/compile-plan owner title children)]
        (if error
          (internal/fail error)
          (let [env (await (db/transact! {:seon.db/tx-data tx}))]
            (if (:seon.db/ok? env)
              {::ok? true ::root root-id ::ids labels}
              (internal/fail (str "plan!: store failed — "
                                  (get-in env [:seon.db/error :seon.error/message]))))))))))

(defn ^:async done!
  "Mark a leaf done, stamping ::completed-at. Unknown id → fail envelope;
   already-done is idempotent success. Completing a node may unblock its
   dependents — they appear in `next` next turn."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id]}]
  (case (internal/status-of id)
    nil   (internal/fail (str "done!: no todo " (pr-str id)
                              " — (seon.agent.todo/list-open {}) shows the open ids."))
    :done {::ok? true ::id id}
    (->> (await (db/transact!
                  {:seon.db/tx-data [{::id           id
                                      ::status       :done
                                      ::completed-at (js/Date.)}]}))
         (internal/write-result "done!" id))))

(defn ^:async reopen!
  "Flip a done todo back to open. Clearing ::completed-at is an explicit
   `[:db/retract …]` — absent means absent, nil is never stored."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]}
  [{::keys [id]}]
  (case (internal/status-of id)
    nil   (internal/fail (str "reopen!: no todo " (pr-str id) "."))
    :open {::ok? true ::id id}
    (->> (await (db/transact!
                  {:seon.db/tx-data
                   [{::id id ::status :open}
                    [:db/retract [::id id] ::completed-at]]}))
         (internal/write-result "reopen!" id))))

(defn ^:async depends!
  "Add dependency edge(s) to an EXISTING todo — it now runs after each :on ref.
   One cardinality-many add per ref. Remove one with
   (db/transact! {:seon.db/tx-data [[:db/retract [:seon.agent.todo/id id]
   :seon.agent.todo/depends-on [:seon.agent.todo/id dep]]]})."
  {:malli/schema [:=> [:cat ::depends-request] ::write-response]}
  [{::keys [id on]}]
  (case (internal/status-of id)
    nil (internal/fail (str "depends!: no todo " (pr-str id) "."))
    (->> (await (db/transact!
                  {:seon.db/tx-data
                   (mapv (fn [ref] [:db/add [::id id] ::depends-on ref]) on)}))
         (internal/write-result "depends!" id))))

(defn ^:async move!
  "Re-parent a node — `:parent` is cardinality-one, so asserting the new parent
   replaces the old. Identity, status, and deps are unchanged; only its place
   in the tree moves."
  {:malli/schema [:=> [:cat ::move-request] ::write-response]}
  [{::keys [id parent]}]
  (case (internal/status-of id)
    nil (internal/fail (str "move!: no todo " (pr-str id) "."))
    (->> (await (db/transact! {:seon.db/tx-data [{::id id ::parent parent}]}))
         (internal/write-result "move!" id))))

(defn ^:async drop!
  "Retract a node AND its whole subtree (`parent` is a plain ref ⇒ no cascade,
   so this walks descendants and retracts each). History keeps them — undo via
   db/as-of. Returns {::ok? true ::dropped <count>} or a fail envelope."
  {:malli/schema [:=> [:cat ::id-request] ::drop-response]}
  [{::keys [id]}]
  (await (internal/retract-subtree! id rules)))

(defn next
  "Your focus queue: READY leaves (open, unblocked, real work) owned by the
   calling agent, oldest first. The ONE thing to act on — blocked work is never
   offered, done work is gone. Sync read of the current conn; [] outside an
   agent scope."
  {:malli/schema [:=> [:cat :map] [:vector ::todo-ref]]}
  [_]
  (if-let [owner (internal/scoped-owner nil)]
    (let [db @db/*conn*]
      (if-let [oe (internal/owner-eid db owner)]
        (internal/ready-leaves db oe rules)
        []))
    []))

(defn tree
  "The plan as nested EDN (children under each node via `:seon.agent.todo/_parent`,
   dep ids inline). {::root? id} → that one subtree (a map); {::all? true} → the
   whole forest across owners; default → the calling agent's forest (a vector of
   root subtrees). The structural read you re-plan over. Sync read of the
   current conn."
  {:malli/schema [:=> [:cat ::tree-request] ::tree-response]}
  [{::keys [root? all?]}]
  (let [db @db/*conn*]
    (cond
      root? (internal/pull-subtree db root?)
      all?  (mapv #(internal/pull-subtree db %) (internal/all-root-ids db))
      :else (if-let [owner (internal/scoped-owner nil)]
              (if-let [oe (internal/owner-eid db owner)]
                (mapv #(internal/pull-subtree db %) (internal/root-ids db oe))
                [])
              []))))

(defn status
  "Derived view of one node: done? (subtree complete), blocked? (a dependency
   still has open work), ready? (an open unblocked leaf), and the {::done ::total}
   roll-up over its subtree leaves. Sync read; nil id-arg-less — always pass ::id."
  {:malli/schema [:=> [:cat ::id-request] ::status-response]}
  [{::keys [id]}]
  (internal/status-view @db/*conn* id rules))

(defn list-open
  "Open todos, oldest first, scoped to ::owner (default: the calling agent);
   {::all? true} lists every owner's. Flat — includes parents and blocked items
   (use `next` for the ready-leaf focus queue). Sync read of the current conn."
  {:malli/schema [:=> [:cat ::list-request] ::list-response]}
  [{::keys [owner all?]}]
  (let [owner (internal/scoped-owner owner)]
    (cond
      (nil? db/*conn*)
      (internal/fail "list-open: no conn bound — runs inside an agent's universe.")

      (and (nil? owner) (not all?))
      (internal/fail (str "list-open: no :seon.agent.todo/owner and no agent in scope — pass "
                          "an owner ref, {::all? true}, or use (db/with-agent …)."))

      :else
      (let [db @db/*conn*]
        {::ok?   true
         ::todos (if all?
                   (internal/open-todos db nil)
                   (if-let [oe (:db/id (db/entity db owner))]
                     (internal/open-todos db oe)
                     []))}))))   ; unknown owner = no items, derived view
