(ns my.plan
  "Your plan + work queue as a TREE. A step is one `:my.plan` entity; a
   `:my.plan/parent` ref makes a PLAN (top = milestones, leaves =
   actions); a `:my.plan/depends-on` ref SEQUENCES work (B depends-on A
   = do B after A). Progress, blocked-ness, and what's-next are DERIVED from the
   leaf `:open`/`:done` facts every turn — nothing derivable is stored. THE
   EXEMPLAR store/retrieve ns: `schema/register!` per attr, map-in/map-out fns,
   errors as `:my.plan/ok?` value envelopes (never throws), derived views.

   Use it for any task of two+ steps: mint the steps up front, `done!` each as it
   lands. Add one at a time (sequencing is a dependency), or author a whole plan
   in one `plan!` (`:children` nests; `:ref` labels a node; `:after` names labels
   it runs after):

     (my.plan/step! {:my.plan/title \"write brief\"
                     :my.plan/depends-on [[:my.plan/id \"a\"]]})
     (my.plan/plan!
       {:my.plan/title \"Process inbox → KB\"
        :my.plan/children
        [{:my.plan/title \"notes-a\" :my.plan/ref \"a\"}
         {:my.plan/title \"synthesize\" :my.plan/after [\"a\"]}]})

   Run the loop off `next` (READY leaves only); `tree`/`status` are derived read
   views. Open items also render every turn in the plan section — an empty
   section is the done-signal."
  (:refer-clojure :exclude [next])
  (:require
    [clojure.string :as str]
    [my.plan.internal :as internal]
    [seon.db :as db]
    [seon.schema :as schema]))

;; --- Attribute schemas — one register! per attr; shared shapes referenced, never inlined.

(schema/register! ::id [:and {:seon.db/identity true} :seon.db/id])
(schema/register! ::title [:string {:min 1}])
(schema/register! ::description :string)
(schema/register! ::status [:enum :open :done])
(schema/register! ::created-at :inst)
(schema/register! ::completed-at :inst)
(schema/register! ::owner :seon.db/ref)   ; SCOPE ref — the agent this item belongs to
(schema/register! ::from :seon.db/ref)     ; who asked
(schema/register! ::message :seon.db/ref)  ; the inbound message an address-step tracks
(schema/register! ::parent :seon.db/ref)            ; TREE edge — plain ref, no cascade
(schema/register! ::depends-on [:vector :seon.db/ref]) ; DAG edges — cardinality-many

;; --- Work-queue semantics: ONE rule set, plain data you can read AND extend
;; --- (pass it as `%`). The queue is pure Datalog over the two refs.

(def rules
  "Datalog rules over the two refs — read AND extend them:
   descendant (transitive tree closure, cycle-safe), leaf (no children),
   open-work (open leaf in the subtree), blocked (a dependency has open-work),
   ready (open unblocked leaf — work to do now). Negations (`leaf`, `not blocked`)
   only FILTER bound tuples, so bind the entity positively BEFORE invoking these."
  '[[(descendant ?a ?n) [?n :my.plan/parent ?a]]
    [(descendant ?a ?n) [?m :my.plan/parent ?a] (descendant ?m ?n)]
    [(leaf ?t) (not-join [?t] [?c :my.plan/parent ?t])]
    [(open-work ?t) [?t :my.plan/status :open] (leaf ?t)]
    [(open-work ?t) (descendant ?t ?l) [?l :my.plan/status :open] (leaf ?l)]
    [(blocked ?t) [?t :my.plan/depends-on ?d] (open-work ?d)]
    [(ready ?t) [?t :my.plan/status :open] (leaf ?t) (not (blocked ?t))]])

;; --- Request/response schemas. ::ok? discriminates the envelope; failures
;; --- carry a guiding ::error (errors are values — branch, don't catch).

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

(schema/register! ::step-request
  [:map
   [::title       ::title]
   [::description {:optional true} ::description]
   [::owner       {:optional true} :seon.db/ref]   ; default: the ALS agent
   [::from        {:optional true} :seon.db/ref]
   [::parent      {:optional true} ::parent]
   [::depends-on  {:optional true} ::depends-on]])

;; plan! shape — recursive: each child may carry its own :children.
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

(schema/register! ::step-ref
  [:map [::id ::id] [::title ::title] [::created-at ::created-at]])

(schema/register! ::tree-request
  [:map
   [::root? {:optional true} ::id]
   [::all?  {:optional true} ::all?]])

;; root? → one subtree map; else → a vector of root subtrees; nil when nothing.
(schema/register! ::tree-response [:maybe [:or :map [:vector :map]]])

;; --- The stored entity kind. `{:seon.db/entity true}` DECLARES that rows of
;; --- this shape live in the DB (puts the kind in the catalog).

(schema/register! ::step
  [:map {:seon.db/entity true}
   [::id          ::id]
   [::title       ::title]
   [::created-at  ::created-at]
   [::description {:optional true} ::description]
   [::message     {:optional true} ::message]
   [::parent      {:optional true} ::parent]
   [::depends-on  {:optional true} ::depends-on]])

(schema/register! ::steps [:vector ::step])

(schema/register! ::list-request
  [:map
   [::owner {:optional true} :seon.db/ref]   ; default: the ALS agent
   [::all?  {:optional true} ::all?]])       ; true = every owner's items

(schema/register! ::list-response
  [:map
   [::ok?   ::ok?]
   [::steps {:optional true} ::steps]
   [::error {:optional true} ::error]])

;; --- Public API

(defn ^:async step!
  "Mint one OPEN work item (owner = calling agent; blank title refused).
   `:parent`/`:depends-on` lookup-refs place it in the tree/DAG, both optional.
   → {::ok? true ::id _} or a fail envelope."
  {:malli/schema [:=> [:cat ::step-request] ::write-response]}
  [{::keys [title description owner from parent depends-on]}]
  (let [owner (internal/scoped-owner owner)]
    (cond
      (or (nil? title) (str/blank? title))
      (internal/fail "step!: blank :my.plan/title refused — say what the work item is.")

      (nil? owner)
      (internal/fail (str "step!: no :my.plan/owner and no agent in scope — pass an "
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
             (internal/write-result "step!" id))))))

(defn ^:async plan!
  "Author a WHOLE plan in ONE transact — nested children + deps.

   `:children` nests (`:parent` edges); `:ref` labels a node; `:after` names
   labels it runs after (`depends-on` edges). `:after` may name any label,
   defined earlier OR later. → {::ok? true ::root <root-id> ::ids <label→id>}
   or a fail envelope."
  {:malli/schema [:=> [:cat ::plan-request] ::plan-response]}
  [{::keys [title children]}]
  (let [owner (internal/scoped-owner nil)]
    (cond
      (or (nil? title) (str/blank? title))
      (internal/fail "plan!: blank :my.plan/title refused — name the plan.")

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
  "Mark a leaf done; may unblock its dependents next turn.

   Stamps `::completed-at`. Already-done is idempotent success; unknown id →
   fail envelope."
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
  "Flip a done step back to open; retract its `::completed-at`.

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

(defn ^:async depends!
  "Add dependency edge(s) — the step now runs after each `:on` ref.

   Cardinality-many. Remove one via
   `[:db/retract id :my.plan/depends-on dep]`."
  {:malli/schema [:=> [:cat ::depends-request] ::write-response]}
  [{::keys [id on]}]
  (case (internal/status-of id)
    nil (internal/fail (str "depends!: no step " (pr-str id) "."))
    (->> (await (db/transact!
                  {:seon.db/tx-data
                   (mapv (fn [ref] [:db/add [::id id] ::depends-on ref]) on)}))
         (internal/write-result "depends!" id))))

(defn ^:async move!
  "Re-parent a node — the new parent replaces the old.

   `:parent` is cardinality-one. Identity, status, and deps unchanged."
  {:malli/schema [:=> [:cat ::move-request] ::write-response]}
  [{::keys [id parent]}]
  (case (internal/status-of id)
    nil (internal/fail (str "move!: no step " (pr-str id) "."))
    (->> (await (db/transact! {:seon.db/tx-data [{::id id ::parent parent}]}))
         (internal/write-result "move!" id))))

(defn ^:async drop!
  "Retract a node AND its whole subtree.

   Plain `parent` ref ⇒ no cascade, so it walks descendants. History keeps
   them (undo via db/as-of). → {::ok? true ::dropped <count>} or a fail
   envelope."
  {:malli/schema [:=> [:cat ::id-request] ::drop-response]}
  [{::keys [id]}]
  (await (internal/retract-subtree! id rules)))

(defn next
  "Your focus queue: READY leaves (open, unblocked), oldest first.

   For the calling agent — the work to act on now. [] outside an agent scope."
  {:malli/schema [:=> [:cat :map] [:vector ::step-ref]]}
  [_]
  (if-let [owner (internal/scoped-owner nil)]
    (let [db @db/*conn*]
      (if-let [oe (internal/owner-eid db owner)]
        (internal/ready-leaves db oe rules)
        []))
    []))

(defn tree
  "The plan as nested EDN — the structural read you re-plan over.

   Children under `:my.plan/_parent`, dep ids inline. {::root? id} →
   that subtree; {::all? true} → every owner's forest; default → the calling
   agent's forest."
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
  "Derived view of one node — done/blocked/ready + subtree roll-up.

   done? (subtree complete), blocked? (a dependency has open work), ready?
   (open unblocked leaf), and the {::done ::total} subtree roll-up. Pass
   ::id."
  {:malli/schema [:=> [:cat ::id-request] ::status-response]}
  [{::keys [id]}]
  (internal/status-view @db/*conn* id rules))

(defn list-open
  "Open steps, oldest first, scoped to `::owner` (default: caller).

   {::all? true} lists every owner's. Flat — includes parents and blocked
   items (use `next` for the ready-leaf focus queue)."
  {:malli/schema [:=> [:cat ::list-request] ::list-response]}
  [{::keys [owner all?]}]
  (let [owner (internal/scoped-owner owner)]
    (cond
      (nil? db/*conn*)
      (internal/fail "list-open: no conn bound — runs inside an agent's universe.")

      (and (nil? owner) (not all?))
      (internal/fail (str "list-open: no :my.plan/owner and no agent in scope — pass "
                          "an owner ref, {::all? true}, or use (db/with-agent …)."))

      :else
      (let [db @db/*conn*]
        {::ok?   true
         ::steps (if all?
                   (internal/open-steps db nil)
                   (if-let [oe (:db/id (db/entity db owner))]
                     (internal/open-steps db oe)
                     []))}))))   ; unknown owner = no items, derived view
