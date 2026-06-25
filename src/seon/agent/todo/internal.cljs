(ns seon.agent.todo.internal
  "Private plumbing for `seon.agent.todo` — fail/owner-scoping helpers, the
   store-result envelope mapper, the open-todos query + projection, and the
   open-todos context-section render.

   Factored out of the public verb surface so the teaching ns shows ONLY
   add!/complete!/reopen!/list-open + their register! schemas (the
   `*.internal` convention drops these from rendered agent context —
   see `seon.ctx.namespaces/hidden-ns-name?`).

   Keyword-namespace note: this lives under `seon.agent.todo.internal`, so
   `::foo` would expand WRONG. Every helper references the owning ns's
   attrs fully-qualified (`:seon.agent.todo/id`, never `::id`)."
  (:require
    [clojure.string :as str]
    [seon.db :as db]))

(defn fail [msg] {:seon.agent.todo/ok? false :seon.agent.todo/error msg})

(defn scoped-owner
  "Explicit owner ref, else the calling agent from the ALS scope."
  [owner]
  (or owner (when-let [id (db/current-agent-id)] [:seon.agent/id id])))

(defn status-of
  "Current :seon.agent.todo/status of todo `id`, or nil when no such todo."
  [id]
  (ffirst (db/query {:seon.db/query '[:find ?s :in $ ?id
                                      :where
                                      [?t :seon.agent.todo/id ?id]
                                      [?t :seon.agent.todo/status ?s]]
                     :seon.db/args  [id]})))

(defn write-result
  "transact! envelope → :seon.agent.todo/write-response (tx-report stays
   off this surface)."
  [verb id env]
  (if (:seon.db/ok? env)
    {:seon.agent.todo/ok? true :seon.agent.todo/id id}
    (fail (str verb ": store failed — "
               (get-in env [:seon.db/error :seon.error/message])))))

(def open-keys
  "The resume projection of one open item — `[*]`-pulled then trimmed.
   (Not a pull PATTERN: naming a never-yet-transacted attr there throws.)"
  [:seon.agent.todo/id :seon.agent.todo/title
   :seon.agent.todo/created-at :seon.agent.todo/description])

(defn open-todos
  "Open todos in db value `db`, oldest first; `owner-eid` nil = all owners."
  [db owner-eid]
  (let [q (if owner-eid
            '[:find [?t ...] :in $ ?o
              :where
              [?t :seon.agent.todo/status :open]
              [?t :seon.agent.todo/owner ?o]]
            '[:find [?t ...] :where [?t :seon.agent.todo/status :open]])]
    (->> (apply db/query q db (when owner-eid [owner-eid]))
         (map #(select-keys (db/pull db '[*] %) open-keys))
         (sort-by #(.getTime ^js (:seon.agent.todo/created-at %)))
         vec)))

(defn age-str
  "Compact age of `at`: \"7m\" / \"3h\" / \"2d\"."
  [at]
  (let [m (max 0 (quot (- (js/Date.now) (.getTime ^js at)) 60000))]
    (cond (< m 60)   (str m "m")
          (< m 1440) (str (quot m 60) "h")
          :else      (str (quot m 1440) "d"))))

(defn open-todos-block
  "Context-section text for `owner`'s open todos in db value `db` — a
   `;; ── open todos ──` comment-block, one `;; <id> [<age>] <title>` line
   per item, oldest first; \"\" when none (the section vanishes when the
   work is done — nothing stored, nothing to acknowledge). Rides as `;;`
   comments so the whole context reads as eval'able Clojure."
  [db owner]
  (let [todos (when-let [oe (:db/id (db/entity db owner))]
                (open-todos db oe))]
    (if (empty? todos)
      ""
      (str ";; Your open work items — close one with\n"
           ";;   (seon.agent.todo/complete! {:seon.agent.todo/id \"<id>\"})\n"
           ";; when finished:\n"
           (str/join "\n"
                     (map (fn [{:seon.agent.todo/keys [id title created-at]}]
                            (str ";; " id " [" (age-str created-at) "] " title))
                          todos))))))

(defn open-todos-section
  "Context-section fn (`:open-todos`, core-default-ctx priority 45):
   [[open-todos-block]] for the CALLING agent — the `:seon.agent/id` in the
   render input, resolved as a `[:seon.agent/id id]` ref against the render's
   db value — absent `:seon.db/db` defaults to the current conn, the same
   convention as every other core section fn. Returns \"\" when the
   agent has no open items (the section vanishes — derived, nothing stored,
   nothing to acknowledge)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (open-todos-block (or db @db/*conn*) [:seon.agent/id id]))
