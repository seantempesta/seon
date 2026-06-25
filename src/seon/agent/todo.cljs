(ns seon.agent.todo
  "Work items — the user's asks to an agent and the agent's own notes-to-self,
   stored as `:seon.agent.todo` entities and queried at boot so a RESUMING agent
   sees exactly where it left off. THE EXEMPLAR store/retrieve ns: attrs via
   `schema/register!`, map-in/map-out fns, errors as `:seon.agent.todo/ok?`
   envelopes (never throws), a pure derived view for context rendering.

   WHEN: any task with two or more steps. Mint one todo per step BEFORE you
   start — title = the step, from = the asker's ref — then complete! each id
   as that step lands. Your open items render in the open-todos section
   every turn, each line carrying its id, so a resumed or distracted you
   always sees what is left; an empty section is the done-signal. Nothing
   to clear, nothing to remember across turns.

   The full arc — add!'s response carries the durable id, and that exact
   id is what complete! takes:

     (let [step (seon.agent.todo/add!
                  {:seon.agent.todo/title \"register the my.kb.note schema\"
                   :seon.agent.todo/from  [:seon.user/id \"user\"]})]
       (.then step (fn [{:seon.agent.todo/keys [id]}]
                     ;; the step's real work goes here, then close it:
                     (seon.agent.todo/complete! {:seon.agent.todo/id id}))))
     ;; => {:seon.agent.todo/ok? true :seon.agent.todo/id \"k7x2-0jh99tq40d\"}

   In ordinary turn-by-turn work you rarely need the .then: add! on its own
   line prints its envelope on the value line — read the id there, or off
   the open-todos section next turn, and pass it to complete! when the
   step is done. reopen! flips a done item back to open. When you reply
   mid-task, include how many items are still open so your human knows
   where you are."
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
(schema/register! ::owner :seon.db/ref)  ; the agent this item belongs to
(schema/register! ::from :seon.db/ref)   ; who asked (the user or an agent)

;; --- Request/response schemas. ::ok? is the envelope discriminator;
;; --- failures carry a guiding ::error (errors are values — branch, don't catch).

(schema/register! ::ok? :boolean)
(schema/register! ::error :string)
(schema/register! ::all? :boolean)

(schema/register! ::write-response
  [:map
   [::ok?   ::ok?]
   [::id    {:optional true} ::id]
   [::error {:optional true} ::error]])

(schema/register! ::add-request
  [:map
   [::title       ::title]
   [::description {:optional true} ::description]
   [::owner       {:optional true} :seon.db/ref]   ; default: the ALS agent
   [::from        {:optional true} :seon.db/ref]])

(schema/register! ::complete-request [:map [::id ::id]])
(schema/register! ::reopen-request   [:map [::id ::id]])

;; --- The stored entity kind. `{:seon.db/entity true}` DECLARES that
;; --- rows of this shape live in the DB (it's what puts the kind in the
;; --- catalog); request/response envelopes above carry no marker.

(schema/register! ::todo
  [:map {:seon.db/entity true}
   [::id          ::id]
   [::title       ::title]
   [::created-at  ::created-at]
   [::description {:optional true} ::description]])

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
  "Store an open work item. Owner defaults to the calling agent (ALS scope);
   blank title refused. Resolves to {::ok? true ::id _} or a fail envelope."
  {:malli/schema [:=> [:cat ::add-request] ::write-response]}
  [{::keys [title description owner from]}]
  (let [owner (internal/scoped-owner owner)]
    (cond
      (or (nil? title) (str/blank? title))
      (internal/fail "add!: blank :seon.agent.todo/title refused — say what the work item is.")

      (nil? owner)
      (internal/fail (str "add!: no :seon.agent.todo/owner and no agent in scope — pass an "
                          "owner ref or call inside (seon.db/with-agent …)."))

      :else
      (let [id (db/new-id!)]
        (->> (await (db/transact!
                      {:seon.db/tx-data
                       [(cond-> {::id         id
                                 ::title      title
                                 ::status     :open
                                 ::created-at (js/Date.)
                                 ::owner      owner}
                          description (assoc ::description description)
                          from        (assoc ::from from))]}))
             (internal/write-result "add!" id))))))

(defn ^:async complete!
  "Mark a todo done, stamping ::completed-at. Unknown id → fail envelope;
   already-done is idempotent success."
  {:malli/schema [:=> [:cat ::complete-request] ::write-response]}
  [{::keys [id]}]
  (case (internal/status-of id)
    nil   (internal/fail (str "complete!: no todo " (pr-str id)
                              " — (seon.agent.todo/list-open {}) shows the open ids."))
    :done {::ok? true ::id id}
    (->> (await (db/transact!
                  {:seon.db/tx-data [{::id           id
                                      ::status       :done
                                      ::completed-at (js/Date.)}]}))
         (internal/write-result "complete!" id))))

(defn ^:async reopen!
  "Flip a done todo back to open. Clearing ::completed-at is an explicit
   `[:db/retract …]` — absent means absent, nil is never stored."
  {:malli/schema [:=> [:cat ::reopen-request] ::write-response]}
  [{::keys [id]}]
  (case (internal/status-of id)
    nil   (internal/fail (str "reopen!: no todo " (pr-str id) "."))
    :open {::ok? true ::id id}
    (->> (await (db/transact!
                  {:seon.db/tx-data
                   [{::id id ::status :open}
                    [:db/retract [::id id] ::completed-at]]}))
         (internal/write-result "reopen!" id))))

(defn list-open
  "Open todos, oldest first, scoped to ::owner (default: the calling agent);
   {::all? true} lists every owner's. Sync read of the current conn."
  {:malli/schema [:=> [:cat ::list-request] ::list-response]}
  [{::keys [owner all?]}]
  (let [owner (internal/scoped-owner owner)]
    (cond
      (nil? db/*conn*)
      (internal/fail "list-open: no conn bound — runs inside an agent's universe.")

      (and (nil? owner) (not all?))
      (internal/fail (str "list-open: no :seon.agent.todo/owner and no agent in scope — pass "
                          "an owner ref, {::all? true}, or use (seon.db/with-agent …)."))

      :else
      (let [db @db/*conn*]
        {::ok?   true
         ::todos (if all?
                   (internal/open-todos db nil)
                   (if-let [oe (:db/id (db/entity db owner))]
                     (internal/open-todos db oe)
                     []))}))))   ; unknown owner = no items, derived view
