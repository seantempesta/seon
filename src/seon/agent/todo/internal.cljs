(ns seon.agent.todo.internal
  "Private plumbing for `seon.agent.todo` — fail/owner-scoping helpers, the
   write-result envelope mapper, the derived work-queue queries (ready leaves,
   roll-up, blocked/ready over the shared rule set), the reverse-ref tree pull,
   the plan! tempid compiler, the drop! subtree walk, and the open-todos
   context-section render.

   Factored out of the public verb surface so the teaching ns shows ONLY the
   verbs + their register! schemas + the readable `rules` data (the `*.internal`
   convention drops these from rendered agent context — see
   `seon.agent.ctx.namespaces/hidden-ns-name?`).

   Keyword-namespace note: this lives under `seon.agent.todo.internal`, so
   `::foo` would expand WRONG. Every helper references the owning ns's attrs
   fully-qualified (`:seon.agent.todo/id`, never `::id`). The derived queries
   take the shared `rules` as a PARAMETER — it lives in the public ns so an
   agent can read and extend it, and passing it keeps the dep one-way."
  (:require
    [clojure.string :as str]
    [seon.db :as db]))

(defn fail [msg] {:seon.agent.todo/ok? false :seon.agent.todo/error msg})

(defn scoped-owner
  "Explicit owner ref, else the calling agent from the ALS scope."
  [owner]
  (or owner (when-let [id (db/current-agent-id)] [:seon.agent/id id])))

(defn owner-eid
  "Resolve an owner ref (lookup-ref/eid) to its eid in db value `db`, or nil."
  [db owner]
  (:db/id (db/entity db owner)))

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

;; --- Derived work-queue queries — all pure Datalog over the two refs, with
;; --- the shared `rules` passed as `%`. Nothing here is stored; blocked-ness,
;; --- ready-ness, and roll-up recompute from the leaf facts every read.

(defn ready-leaves
  "Ready leaves (open, childless, unblocked) owned by `owner-eid`, oldest
   first, as `[{:id :title :created-at} …]`. Sorted in CLJS — `:seon.db/order-by`
   is not a `seon.db/query` request key."
  [db owner-eid rules]
  (->> (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find ?id ?title ?created
                    :in $ % ?a
                    :where
                    [?t :seon.agent.todo/owner ?a]
                    (ready ?t)
                    [?t :seon.agent.todo/id ?id]
                    [?t :seon.agent.todo/title ?title]
                    [?t :seon.agent.todo/created-at ?created]]
                  :seon.db/args [rules owner-eid]})
       (sort-by #(.getTime ^js (nth % 2)))
       (mapv (fn [[id title created]]
               {:seon.agent.todo/id         id
                :seon.agent.todo/title      title
                :seon.agent.todo/created-at created}))))

(defn root-ids
  "Ids of `owner-eid`'s todos with no parent edge — the forest roots."
  [db owner-eid]
  (vec (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find [?id ...]
                    :in $ ?a
                    :where
                    [?t :seon.agent.todo/owner ?a]
                    [?t :seon.agent.todo/id ?id]
                    (not-join [?t] [?t :seon.agent.todo/parent _])]
                  :seon.db/args [owner-eid]})))

(defn all-root-ids
  "Ids of EVERY todo with no parent edge, any owner — the whole forest's roots."
  [db]
  (vec (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find [?id ...]
                    :where
                    [?t :seon.agent.todo/id ?id]
                    (not-join [?t] [?t :seon.agent.todo/parent _])]})))

(def tree-pattern
  "ONE recursive reverse-ref pull: a node + its whole subtree (children under
   `:seon.agent.todo/_parent`, the reverse of the plain `parent` ref → a
   vector) + each node's dependency ids inline."
  '[:seon.agent.todo/id :seon.agent.todo/title :seon.agent.todo/status
    {:seon.agent.todo/_parent ...}
    {:seon.agent.todo/depends-on [:seon.agent.todo/id]}])

(defn pull-subtree
  "Nested-EDN subtree rooted at todo `id` (nil when `id` doesn't resolve)."
  [db id]
  (db/pull db tree-pattern [:seon.agent.todo/id id]))

(defn descendant-ids
  "Ids of every node strictly under `id` (transitive, via the `descendant` rule)."
  [db id rules]
  (vec (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find [?cid ...]
                    :in $ % ?root
                    :where
                    [?r :seon.agent.todo/id ?root]
                    (descendant ?r ?c)
                    [?c :seon.agent.todo/id ?cid]]
                  :seon.db/args [rules id]})))

(defn rollup
  "Done/total over the leaves in `id`'s subtree (INCLUDING `id` itself when it
   is a leaf). `done?` = every such leaf done. Relation-find `[?l ?s]` (NOT a
   collection-find, which dedups by status and would mis-count)."
  [db id rules]
  (let [desc (db/query {:seon.db/db db
                        :seon.db/query
                        '[:find ?l ?s
                          :in $ % ?pid
                          :where
                          [?p :seon.agent.todo/id ?pid]
                          (descendant ?p ?l)
                          (leaf ?l)
                          [?l :seon.agent.todo/status ?s]]
                        :seon.db/args [rules id]})
        self (db/query {:seon.db/db db
                        :seon.db/query
                        '[:find ?p ?s
                          :in $ % ?pid
                          :where
                          [?p :seon.agent.todo/id ?pid]
                          (leaf ?p)
                          [?p :seon.agent.todo/status ?s]]
                        :seon.db/args [rules id]})
        rows  (into (set desc) self)
        total (count rows)
        done  (count (filter #(= :done (second %)) rows))]
    {:seon.agent.todo/done  done
     :seon.agent.todo/total total
     :seon.agent.todo/done? (and (pos? total) (= done total))}))

(defn blocked?
  "True iff some dependency of `id` still has open work (one hop over
   `depends-on` into the `open-work` rule — transitivity rides on done-ness)."
  [db id rules]
  (boolean (seq (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?t :in $ % ?id
                             :where [?t :seon.agent.todo/id ?id] (blocked ?t)]
                           :seon.db/args [rules id]}))))

(defn ready?
  "True iff `id` is an open, unblocked leaf — real work the agent can do now."
  [db id rules]
  (boolean (seq (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?t :in $ % ?id
                             :where [?t :seon.agent.todo/id ?id] (ready ?t)]
                           :seon.db/args [rules id]}))))

(defn status-view
  "Derived one-node view: done?/blocked?/ready? + the subtree progress."
  [db id rules]
  (let [{:seon.agent.todo/keys [done total done?]} (rollup db id rules)]
    {:seon.agent.todo/id       id
     :seon.agent.todo/done?    done?
     :seon.agent.todo/blocked? (blocked? db id rules)
     :seon.agent.todo/ready?   (ready? db id rules)
     :seon.agent.todo/progress {:seon.agent.todo/done  done
                                :seon.agent.todo/total total}}))

;; --- plan! — compile a nested plan to ONE flat, tempid-linked transact. ----

(defn compile-plan
  "`title` + nested `children` → `{:tx <flat tempid-keyed tx-data>
                                   :labels <author-label/:root → minted id>
                                   :root-id <id>}`, or `{:error msg}` when an
   `:seon.agent.todo/after` names an unknown label.

   Cross-sibling edges link by STRING TEMPID, never same-tx lookup-refs (a
   lookup-ref to a not-yet-asserted sibling throws `:entity-id/missing`).
   Tempids are order-independent, so an `:after` may name any label in the plan.
   Pass 1 assigns every node a tempid + a minted `:seon.agent.todo/id` and
   records its author label; pass 2 emits one tx-map per node."
  [owner title children]
  (let [nodes      (volatile! [])
        label->tid (volatile! {})]
    (letfn [(walk [node parent-tid]
              (let [tid   (str "t" (count @nodes))
                    id    (db/new-id!)
                    label (:seon.agent.todo/ref node)]
                (when label (vswap! label->tid assoc label tid))
                (vswap! nodes conj {:tid        tid
                                    :id         id
                                    :title      (:seon.agent.todo/title node)
                                    :parent-tid parent-tid
                                    :after      (:seon.agent.todo/after node)
                                    :label      label})
                (doseq [c (:seon.agent.todo/children node)] (walk c tid))
                tid))]
      (walk {:seon.agent.todo/title    title
             :seon.agent.todo/children children} nil)
      (let [l->t    @label->tid
            ns      @nodes
            unknown (->> ns (mapcat :after) distinct (remove l->t) seq)]
        (if unknown
          {:error (str "plan!: :seon.agent.todo/after names unknown label(s) "
                       (str/join ", " (map pr-str unknown))
                       " — each :after must match some node's :seon.agent.todo/ref.")}
          {:tx      (mapv (fn [{:keys [tid id title parent-tid after]}]
                            (cond-> {:db/id                      tid
                                     :seon.agent.todo/id         id
                                     :seon.agent.todo/title      title
                                     :seon.agent.todo/status     :open
                                     :seon.agent.todo/owner      owner
                                     :seon.agent.todo/created-at (js/Date.)}
                              parent-tid  (assoc :seon.agent.todo/parent parent-tid)
                              (seq after) (assoc :seon.agent.todo/depends-on
                                                 (mapv l->t after))))
                          ns)
           :labels  (into {:root (:id (first ns))}
                          (keep (fn [{:keys [label id]}] (when label [label id])) ns))
           :root-id (:id (first ns))})))))

(defn ^:async retract-subtree!
  "Retract `id` AND its whole subtree (the plain `parent` ref does NOT cascade,
   so we walk descendants and `retractEntity` each). Unknown id → fail envelope.
   History keeps every retracted node — undo is a `db/as-of` away."
  [id rules]
  (if (nil? (status-of id))
    (fail (str "drop!: no todo " (pr-str id) "."))
    (let [db  @db/*conn*
          ids (distinct (conj (descendant-ids db id rules) id))
          env (await (db/transact!
                       {:seon.db/tx-data
                        (mapv (fn [i] [:db.fn/retractEntity [:seon.agent.todo/id i]]) ids)}))]
      (if (:seon.db/ok? env)
        {:seon.agent.todo/ok? true :seon.agent.todo/dropped (count ids)}
        (fail (str "drop!: store failed — "
                   (get-in env [:seon.db/error :seon.error/message])))))))

;; --- open-todos context-section render (the flat resume view; the
;; --- hierarchical dual render is a later unit). ---------------------------

(def open-keys
  "The resume projection of one open item — `[*]`-pulled then trimmed.
   (Not a pull PATTERN: naming a never-yet-transacted attr there throws.)"
  [:seon.agent.todo/id :seon.agent.todo/title
   :seon.agent.todo/created-at :seon.agent.todo/description
   :seon.agent.todo/message])

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

(defn open-todos-body
  "Context-section text for `owner`'s open todos in db value `db` — single-`;`
   prose guidance + one `; <id> [<age>] <title>` line per item, oldest first;
   a `✉` marker leads items auto-minted from one of your human's messages (a
   memory aid, not an obligation — `done!` it once you've addressed them).
   \"\" when none (the section vanishes when the work is done — nothing stored,
   nothing to acknowledge). Rides as `;` comments so the whole context reads
   as eval'able Clojure."
  [db owner]
  (let [todos (when-let [oe (:db/id (db/entity db owner))]
                (open-todos db oe))]
    (if (empty? todos)
      ""
      (str "; Your open work items — a memory aid, not an obligation. Close one with\n"
           ";   (seon.agent.todo/done! {:seon.agent.todo/id \"<id>\"})\n"
           "; once addressed. A ✉ item tracks a message from your human.\n"
           (str/join "\n"
                     (map (fn [{:seon.agent.todo/keys [id title created-at message]}]
                            (str "; " (when message "✉ ") id " [" (age-str created-at) "] " title))
                          todos))))))

(defn open-todos-block
  "Context-section fn (`:open-todos`, default-seed-blocks priority 45):
   [[open-todos-body]] for the CALLING agent — the `:seon.agent/id` in the
   render input, resolved as a `[:seon.agent/id id]` ref against the render's
   db value — absent `:seon.db/db` defaults to the current conn, the same
   convention as every other core section fn. Returns \"\" when the
   agent has no open items (the section vanishes — derived, nothing stored,
   nothing to acknowledge)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (open-todos-body (or db @db/*conn*) [:seon.agent/id id]))
