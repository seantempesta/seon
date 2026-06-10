(ns seon.agent.todo
  "Work items — the user's asks to an agent and the agent's own notes-to-self,
   stored as `:seon.agent.todo` entities and queried at boot so a RESUMING agent
   sees exactly where it left off. THE EXEMPLAR store/retrieve ns: attrs via
   `schema/register!`, map-in/map-out fns, errors as `:seon.agent.todo/ok?`
   envelopes (never throws), a pure derived view for context rendering."
  (:require
    [clojure.string :as str]
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

;; --- Internals

(defn- fail [msg] {::ok? false ::error msg})

(defn- scoped-owner
  "Explicit owner ref, else the calling agent from the ALS scope."
  [owner]
  (or owner (when-let [id (db/current-agent-id)] [:seon.agent/id id])))

(defn- status-of
  "Current ::status of todo `id`, or nil when no such todo."
  [id]
  (ffirst (db/query {:seon.db/query '[:find ?s :in $ ?id
                                      :where [?t ::id ?id] [?t ::status ?s]]
                     :seon.db/args  [id]})))

(defn- write-result
  "transact! envelope → ::write-response (tx-report stays off this surface)."
  [verb id env]
  (if (:seon.db/ok? env)
    {::ok? true ::id id}
    (fail (str verb ": store failed — "
               (get-in env [:seon.db/error :seon.error/message])))))

(def ^:private open-keys
  "The resume projection of one open item — `[*]`-pulled then trimmed.
   (Not a pull PATTERN: naming a never-yet-transacted attr there throws.)"
  [::id ::title ::created-at ::description])

(defn- open-todos
  "Open todos in db value `db`, oldest first; `owner-eid` nil = all owners."
  [db owner-eid]
  (let [q (if owner-eid
            '[:find [?t ...] :in $ ?o
              :where [?t ::status :open] [?t ::owner ?o]]
            '[:find [?t ...] :where [?t ::status :open]])]
    (->> (apply db/query q db (when owner-eid [owner-eid]))
         (map #(select-keys (db/pull db '[*] %) open-keys))
         (sort-by #(.getTime ^js (::created-at %)))
         vec)))

(defn- age-str
  "Compact age of `at`: \"7m\" / \"3h\" / \"2d\"."
  [at]
  (let [m (max 0 (quot (- (js/Date.now) (.getTime ^js at)) 60000))]
    (cond (< m 60)   (str m "m")
          (< m 1440) (str (quot m 60) "h")
          :else      (str (quot m 1440) "d"))))

;; --- Public API

(defn ^:async add!
  "Store an open work item. Owner defaults to the calling agent (ALS scope);
   blank title refused. Resolves to {::ok? true ::id _} or a fail envelope."
  {:malli/schema [:=> [:cat ::add-request] ::write-response]}
  [{::keys [title description owner from]}]
  (let [owner (scoped-owner owner)]
    (cond
      (or (nil? title) (str/blank? title))
      (fail "add!: blank :seon.agent.todo/title refused — say what the work item is.")

      (nil? owner)
      (fail (str "add!: no :seon.agent.todo/owner and no agent in scope — pass an "
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
             (write-result "add!" id))))))

(defn ^:async complete!
  "Mark a todo done, stamping ::completed-at. Unknown id → fail envelope;
   already-done is idempotent success."
  {:malli/schema [:=> [:cat ::complete-request] ::write-response]}
  [{::keys [id]}]
  (case (status-of id)
    nil   (fail (str "complete!: no todo " (pr-str id)
                     " — (seon.agent.todo/list-open {}) shows the open ids."))
    :done {::ok? true ::id id}
    (->> (await (db/transact!
                  {:seon.db/tx-data [{::id           id
                                      ::status       :done
                                      ::completed-at (js/Date.)}]}))
         (write-result "complete!" id))))

(defn ^:async reopen!
  "Flip a done todo back to open. Clearing ::completed-at is an explicit
   `[:db/retract …]` — absent means absent, nil is never stored."
  {:malli/schema [:=> [:cat ::reopen-request] ::write-response]}
  [{::keys [id]}]
  (case (status-of id)
    nil   (fail (str "reopen!: no todo " (pr-str id) "."))
    :open {::ok? true ::id id}
    (->> (await (db/transact!
                  {:seon.db/tx-data
                   [{::id id ::status :open}
                    [:db/retract [::id id] ::completed-at]]}))
         (write-result "reopen!" id))))

(defn list-open
  "Open todos, oldest first, scoped to ::owner (default: the calling agent);
   {::all? true} lists every owner's. Sync read of the current conn."
  {:malli/schema [:=> [:cat ::list-request] ::list-response]}
  [{::keys [owner all?]}]
  (let [owner (scoped-owner owner)]
    (cond
      (nil? db/*conn*)
      (fail "list-open: no conn bound — runs inside an agent's universe.")

      (and (nil? owner) (not all?))
      (fail (str "list-open: no :seon.agent.todo/owner and no agent in scope — pass "
                 "an owner ref, {::all? true}, or use (seon.db/with-agent …)."))

      :else
      (let [db @db/*conn*]
        {::ok?   true
         ::todos (if all?
                   (open-todos db nil)
                   (if-let [oe (:db/id (db/entity db owner))]
                     (open-todos db oe)
                     []))}))))   ; unknown owner = no items, derived view

(defn open-todos-block
  "Context-section text for `owner`'s open todos in db value `db` — one
   `<id> [<age>] <title>` line, oldest first; \"\" when none (the section
   vanishes when the work is done — nothing stored, nothing to acknowledge)."
  {:malli/schema [:=> [:catn [::db :seon.db/db-val] [::owner :seon.db/ref]]
                  :string]}
  [db owner]
  (let [todos (when-let [oe (:db/id (db/entity db owner))]
                (open-todos db oe))]
    (if (empty? todos)
      ""
      (str "Your open work items — (seon.agent.todo/complete! {:seon.agent.todo/id <id>}) "
           "when finished:\n"
           (str/join "\n"
                     (map (fn [{::keys [id title created-at]}]
                            (str id " [" (age-str created-at) "] " title))
                          todos))))))

(defn open-todos-section
  "Context-section fn (`:open-todos`, substrate-default-ctx priority 45):
   [[open-todos-block]] for the CALLING agent — the `:seon.agent/id` in the
   render input, resolved as a `[:seon.agent/id id]` ref against the render's
   db value. Returns \"\" when the agent has no open items (the section
   vanishes — derived, nothing stored, nothing to acknowledge)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (open-todos-block db [:seon.agent/id id]))
