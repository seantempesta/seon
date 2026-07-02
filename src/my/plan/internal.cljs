(ns my.plan.internal
  "Private plumbing for `my.plan` — fail/owner-scoping helpers, the
   write-result envelope mapper, the derived work-queue queries (ready leaves,
   roll-up, blocked/ready over the shared rule set), the reverse-ref tree pull,
   the plan! tempid compiler, the drop! subtree walk, and the open-steps
   context-section render.

   Factored out of the public verb surface so the teaching ns shows ONLY the
   verbs + their register! schemas + the readable `rules` data (the `*.internal`
   convention drops these from rendered agent context — see
   `seon.agent.ctx.namespaces/hidden-ns-name?`).

   Keyword-namespace note: this lives under `my.plan.internal`, so
   `::foo` would expand WRONG. Every helper references the owning ns's attrs
   fully-qualified (`:my.plan/id`, never `::id`). The derived queries
   take the shared `rules` as a PARAMETER — it lives in the public ns so an
   agent can read and extend it, and passing it keeps the dep one-way."
  (:require
    [clojure.string :as str]
    [seon.db :as db]))

(defn fail [msg] {:my.plan/ok? false :my.plan/error msg})

(defn scoped-owner
  "Explicit owner ref, else the calling agent from the ALS scope."
  [owner]
  (or owner (when-let [id (db/current-agent-id)] [:seon.agent/id id])))

(defn owner-eid
  "Resolve an owner ref (lookup-ref/eid) to its eid in db value `db`, or nil."
  [db owner]
  (:db/id (db/entity db owner)))

(defn status-of
  "Current :my.plan/status of step `id`, or nil when no such step."
  [id]
  (ffirst (db/query {:seon.db/query '[:find ?s :in $ ?id
                                      :where
                                      [?t :my.plan/id ?id]
                                      [?t :my.plan/status ?s]]
                     :seon.db/args  [id]})))

(defn write-result
  "transact! envelope → :my.plan/write-response (tx-report stays
   off this surface)."
  [verb id env]
  (if (:seon.db/ok? env)
    {:my.plan/ok? true :my.plan/id id}
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
                    [?t :my.plan/owner ?a]
                    (ready ?t)
                    [?t :my.plan/id ?id]
                    [?t :my.plan/title ?title]
                    [?t :my.plan/created-at ?created]]
                  :seon.db/args [rules owner-eid]})
       (sort-by #(.getTime ^js (nth % 2)))
       (mapv (fn [[id title created]]
               {:my.plan/id         id
                :my.plan/title      title
                :my.plan/created-at created}))))

(defn root-ids
  "Ids of `owner-eid`'s steps with no parent edge — the forest roots."
  [db owner-eid]
  (vec (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find [?id ...]
                    :in $ ?a
                    :where
                    [?t :my.plan/owner ?a]
                    [?t :my.plan/id ?id]
                    (not-join [?t] [?t :my.plan/parent _])]
                  :seon.db/args [owner-eid]})))

(defn all-root-ids
  "Ids of EVERY step with no parent edge, any owner — the whole forest's roots."
  [db]
  (vec (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find [?id ...]
                    :where
                    [?t :my.plan/id ?id]
                    (not-join [?t] [?t :my.plan/parent _])]})))

(def tree-pattern
  "ONE recursive reverse-ref pull: a node + its whole subtree (children under
   `:my.plan/_parent`, the reverse of the plain `parent` ref → a
   vector) + each node's dependency ids inline."
  '[:my.plan/id :my.plan/title :my.plan/status
    {:my.plan/_parent ...}
    {:my.plan/depends-on [:my.plan/id]}])

(defn pull-subtree
  "Nested-EDN subtree rooted at step `id` (nil when `id` doesn't resolve)."
  [db id]
  (db/pull db tree-pattern [:my.plan/id id]))

(defn descendant-ids
  "Ids of every node strictly under `id` (transitive, via the `descendant` rule)."
  [db id rules]
  (vec (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find [?cid ...]
                    :in $ % ?root
                    :where
                    [?r :my.plan/id ?root]
                    (descendant ?r ?c)
                    [?c :my.plan/id ?cid]]
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
                          [?p :my.plan/id ?pid]
                          (descendant ?p ?l)
                          (leaf ?l)
                          [?l :my.plan/status ?s]]
                        :seon.db/args [rules id]})
        self (db/query {:seon.db/db db
                        :seon.db/query
                        '[:find ?p ?s
                          :in $ % ?pid
                          :where
                          [?p :my.plan/id ?pid]
                          (leaf ?p)
                          [?p :my.plan/status ?s]]
                        :seon.db/args [rules id]})
        rows  (into (set desc) self)
        total (count rows)
        done  (count (filter #(= :done (second %)) rows))]
    {:my.plan/done  done
     :my.plan/total total
     :my.plan/done? (and (pos? total) (= done total))}))

(defn blocked?
  "True iff some dependency of `id` still has open work (one hop over
   `depends-on` into the `open-work` rule — transitivity rides on done-ness)."
  [db id rules]
  (boolean (seq (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?t :in $ % ?id
                             :where [?t :my.plan/id ?id] (blocked ?t)]
                           :seon.db/args [rules id]}))))

(defn ready?
  "True iff `id` is an open, unblocked leaf — real work the agent can do now."
  [db id rules]
  (boolean (seq (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?t :in $ % ?id
                             :where [?t :my.plan/id ?id] (ready ?t)]
                           :seon.db/args [rules id]}))))

(defn status-view
  "Derived one-node view: done?/blocked?/ready? + the subtree progress."
  [db id rules]
  (let [{:my.plan/keys [done total done?]} (rollup db id rules)]
    {:my.plan/id       id
     :my.plan/done?    done?
     :my.plan/blocked? (blocked? db id rules)
     :my.plan/ready?   (ready? db id rules)
     :my.plan/progress {:my.plan/done  done
                                :my.plan/total total}}))

;; --- plan! — compile a nested plan to ONE flat, tempid-linked transact. ----

(defn compile-plan
  "`title` + nested `children` → `{:tx <flat tempid-keyed tx-data>
                                   :labels <author-label/:root → minted id>
                                   :root-id <id>}`, or `{:error msg}` when an
   `:my.plan/after` names an unknown label.

   Cross-sibling edges link by STRING TEMPID, never same-tx lookup-refs (a
   lookup-ref to a not-yet-asserted sibling throws `:entity-id/missing`).
   Tempids are order-independent, so an `:after` may name any label in the plan.
   Pass 1 assigns every node a tempid + a minted `:my.plan/id` and
   records its author label; pass 2 emits one tx-map per node."
  [owner title children]
  (let [nodes      (volatile! [])
        label->tid (volatile! {})]
    (letfn [(walk [node parent-tid]
              (let [tid   (str "t" (count @nodes))
                    id    (db/new-id!)
                    label (:my.plan/ref node)]
                (when label (vswap! label->tid assoc label tid))
                (vswap! nodes conj {:tid        tid
                                    :id         id
                                    :title      (:my.plan/title node)
                                    :parent-tid parent-tid
                                    :after      (:my.plan/after node)
                                    :label      label})
                (doseq [c (:my.plan/children node)] (walk c tid))
                tid))]
      (walk {:my.plan/title    title
             :my.plan/children children} nil)
      (let [l->t    @label->tid
            ns      @nodes
            unknown (->> ns (mapcat :after) distinct (remove l->t) seq)]
        (if unknown
          {:error (str "plan!: :my.plan/after names unknown label(s) "
                       (str/join ", " (map pr-str unknown))
                       " — each :after must match some node's :my.plan/ref.")}
          {:tx      (mapv (fn [{:keys [tid id title parent-tid after]}]
                            (cond-> {:db/id                      tid
                                     :my.plan/id         id
                                     :my.plan/title      title
                                     :my.plan/status     :open
                                     :my.plan/owner      owner
                                     :my.plan/created-at (js/Date.)}
                              parent-tid  (assoc :my.plan/parent parent-tid)
                              (seq after) (assoc :my.plan/depends-on
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
    (fail (str "drop!: no step " (pr-str id) "."))
    (let [db  @db/*conn*
          ids (distinct (conj (descendant-ids db id rules) id))
          env (await (db/transact!
                       {:seon.db/tx-data
                        (mapv (fn [i] [:db.fn/retractEntity [:my.plan/id i]]) ids)}))]
      (if (:seon.db/ok? env)
        {:my.plan/ok? true :my.plan/dropped (count ids)}
        (fail (str "drop!: store failed — "
                   (get-in env [:seon.db/error :seon.error/message])))))))

;; --- open-steps context-section render (the flat resume view; the
;; --- hierarchical dual render is a later unit). ---------------------------

(def open-keys
  "The resume projection of one open item — `[*]`-pulled then trimmed.
   (Not a pull PATTERN: naming a never-yet-transacted attr there throws.)"
  [:my.plan/id :my.plan/title
   :my.plan/created-at :my.plan/description
   :my.plan/message])

(defn open-steps
  "Open steps in db value `db`, oldest first; `owner-eid` nil = all owners."
  [db owner-eid]
  (let [q (if owner-eid
            '[:find [?t ...] :in $ ?o
              :where
              [?t :my.plan/status :open]
              [?t :my.plan/owner ?o]]
            '[:find [?t ...] :where [?t :my.plan/status :open]])]
    (->> (apply db/query q db (when owner-eid [owner-eid]))
         (map #(select-keys (db/pull db '[*] %) open-keys))
         (sort-by #(.getTime ^js (:my.plan/created-at %)))
         vec)))

(def recent-done-limit
  "How many just-finished items the resume view recalls — the bounded
   anti-redo band. COUNT-bounded (not a wall-clock window) so the rendered line
   set is byte-identical until the agent actually closes another step, keeping
   the block cache-stable across renders."
  5)

(defn recent-done
  "The `owner-eid`'s most-recently-completed steps (newest first), capped at
   [[recent-done-limit]] — a derived memory of what was just accomplished so the
   agent doesn't re-do closed setup. Only done items carry ::completed-at, so
   that attr doubles as the done filter; [] when nothing's been finished."
  [db owner-eid]
  (->> (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find ?id ?title ?at
                    :in $ ?o
                    :where
                    [?t :my.plan/owner ?o]
                    [?t :my.plan/status :done]
                    [?t :my.plan/id ?id]
                    [?t :my.plan/title ?title]
                    [?t :my.plan/completed-at ?at]]
                  :seon.db/args [owner-eid]})
       (sort-by #(.getTime ^js (nth % 2)) >)
       (take recent-done-limit)
       (mapv (fn [[id title at]]
               {:my.plan/id           id
                :my.plan/title        title
                :my.plan/completed-at at}))))

(defn stamp
  "Compact ABSOLUTE creation time of `at` — UTC `YYYY-MM-DD HH:MM`, derived
   only from the datom (NOT `now`), so a row renders byte-identical every turn
   while the agent still reads recency (it compares against the turn clock).
   A relative \"3m ago\" string would change on every render and bust the
   stable-prefix cache for an unchanged block."
  [at]
  (-> (.toISOString ^js at) (subs 0 16) (str/replace "T" " ")))

(defn open-section
  "The `; <id> [<created-at>] <title>` lines for `steps` (oldest first), a `✉`
   leading items auto-minted from your human's messages — or \"\" when none."
  [steps]
  (if (empty? steps)
    ""
    (str "; Your open work items — a memory aid, not an obligation. Close one with\n"
         ";   (my.plan/done! {:my.plan/id \"<id>\"})\n"
         "; once addressed. A ✉ item tracks a message from your human.\n"
         (str/join "\n"
                   (map (fn [{:my.plan/keys [id title created-at message]}]
                          (str "; " (when message "✉ ") id " [" (stamp created-at) "] " title))
                        steps)))))

(defn done-section
  "The `; ✓ [<completed-at>] <title>` lines for already-finished `dones`
   (newest first) — a recall band so you don't re-do setup you've already
   completed. \"\" when nothing's been finished."
  [dones]
  (if (empty? dones)
    ""
    (str "; Recently completed — already done, do NOT redo:\n"
         (str/join "\n"
                   (map (fn [{:my.plan/keys [title completed-at]}]
                          (str "; ✓ [" (stamp completed-at) "] " title))
                        dones)))))

(defn plan-body
  "Context-section text for `owner` in db value `db`: the open work items
   (oldest first) PLUS a bounded `Recently completed` recall band (newest
   first) so the agent sees what it already finished and doesn't re-do closed
   setup — both DERIVED from the leaf facts, nothing stored. \"\" when the
   agent has neither open nor recently-done items (the section vanishes — a
   truly-idle agent shows nothing to acknowledge). Rides as `;` comments so
   the whole context reads as eval'able Clojure."
  [db owner]
  (if-let [oe (:db/id (db/entity db owner))]
    (let [open (open-section (open-steps db oe))
          done (done-section (recent-done db oe))]
      (str/join "\n" (remove str/blank? [open done])))
    ""))

(defn plan-block
  "Context-section fn (`:plan`, seon.config/default-ctx-blocks priority 45):
   [[plan-body]] for the CALLING agent — the `:seon.agent/id` in the
   render input, resolved as a `[:seon.agent/id id]` ref against the render's
   db value — absent `:seon.db/db` defaults to the current conn, the same
   convention as every other core section fn. Returns \"\" when the
   agent has no open items (the section vanishes — derived, nothing stored,
   nothing to acknowledge)."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (plan-body (or db @db/*conn*) [:seon.agent/id id]))
