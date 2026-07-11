(ns my.plan.internal
  "Private plumbing for `my.plan` — fail/agent-scoping helpers, the
   write-result envelope mapper, the shared Datalog rule set, the derived
   work-queue queries (ready leaves, roll-up, blocked/ready), the position
   ANCHOR derivation, the reverse-ref tree pull, the plan! tempid compiler,
   the drop! subtree walk, and the WINDOWED plan-block context render
   (anchor + open frontier + recently-completed tail; the completed
   interior stays in the DB, out of the prompt).

   Factored out of the public function surface so the teaching ns shows ONLY the
   functions + their register! schemas (the `*.internal` convention drops these
   from rendered agent context — see
   `seon.agent.ctx.namespaces/hidden-ns-name?`).

   Keyword-namespace note: this lives under `my.plan.internal`, so `::foo`
   would expand WRONG. Every helper references the owning ns's attrs
   fully-qualified (`:my.plan/id`, never `::id`). The rule set lives HERE
   (the render fns need it and the dep is one-way); `my.plan/rules` re-defs
   the same value so an agent can still read and extend it."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [seon.db :as db]
    [seon.repair.candidates :as cand]
    [seon.schema :as schema]))

(def rules
  "Datalog rules over the plan graph — `my.plan/rules` re-defs this value:
   descendant (transitive tree closure, cycle-safe), leaf (no children),
   unfinished (:open/:active/:blocked — anything not :done), open-work
   (unfinished leaf in the subtree), blocked (an explicit :blocked status OR
   a `needs` target with open work), ready (work to do now — an open unblocked
   leaf, OR an open unblocked non-leaf whose subtree is fully drained: its one
   remaining action is verify-and-close). An :active step is in hand, not
   re-listed as ready. Negations (`leaf`, `not blocked`, `not open-work`) only
   FILTER bound tuples, so bind the entity positively BEFORE invoking these."
  '[[(descendant ?a ?n) [?n :my.plan/parent ?a]]
    [(descendant ?a ?n) [?m :my.plan/parent ?a] (descendant ?m ?n)]
    [(leaf ?t) (not-join [?t] [?c :my.plan/parent ?t])]
    [(unfinished ?t) [?t :my.plan/status :open]]
    [(unfinished ?t) [?t :my.plan/status :active]]
    [(unfinished ?t) [?t :my.plan/status :blocked]]
    [(open-work ?t) (unfinished ?t) (leaf ?t)]
    [(open-work ?t) (descendant ?t ?l) (unfinished ?l) (leaf ?l)]
    [(blocked ?t) [?t :my.plan/status :blocked]]
    [(blocked ?t) [?t :my.plan/needs ?d] (open-work ?d)]
    [(ready ?t) [?t :my.plan/status :open] (leaf ?t) (not (blocked ?t))]
    [(ready ?t) [?t :my.plan/status :open] (not (leaf ?t))
     (not (open-work ?t)) (not (blocked ?t))]])

(defn fail [msg] {:my.plan/ok? false :my.plan/error msg})

(defn agent-ref
  "Lookup ref for agent id `id` — nil when no id resolved.

   The id arrives as the functions' DECLARED `:seon.agent/id` request key
   (filled at the eval boundary when the caller omits it — see
   `seon.instrument/injectables`); no ambient read happens here."
  [id]
  (when id [:seon.agent/id id]))

(defn agent-eid
  "Resolve an agent ref (lookup-ref/eid) to its eid in db value `db`, or nil."
  [db agent]
  (:db/id (db/entity db agent)))

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
    (fail (str verb ": db write failed — "
               (get-in env [:seon.db/error :seon.error/message])))))

;; --- Loud unknown-key guard (registry class: silent unknown-key acceptance
;; --- in my.* request maps). A request map is OPEN — the eval boundary
;; --- composes injectable keys in, and foreign-namespace keys pass through —
;; --- so a MISSPELLED `:my.plan/*` key (e.g. `:my.plan/steps` for
;; --- `:my.plan/children`) is silently dropped and mints a childless plan.
;; --- The accepted key set is DERIVED from the registered schemas (never a
;; --- hand list), so it can't drift; the fix suggestion reuses the ONE
;; --- candidate ranker (`seon.repair.candidates`).

(defn schema-map-keys
  "The accepted map-entry keys of registered schema `k`, DERIVED from its
   definition — every key of every nested `[:map …]` (so a `:schema`/
   registry-wrapped node contributes its keys). Never a hand list."
  [k]
  (let [acc (volatile! #{})]
    (walk/postwalk
      (fn [x]
        (when (and (vector? x) (= :map (first x)))
          (doseq [e (rest x)]
            (when (and (vector? e) (keyword? (first e)))
              (vswap! acc conj (first e)))))
        x)
      (schema/schema-definition k))
    @acc))

(defn my-plan-key?
  "True iff keyword `k` is namespaced under `my.plan` (or `my.plan.*`)."
  [k]
  (boolean (when-let [ns (namespace k)]
             (or (= ns "my.plan") (str/starts-with? ns "my.plan.")))))

(defn unknown-key-fail
  "Fail envelope for the FIRST `my.plan`-namespaced key in `request` that
   `accepted` doesn't contain — naming the key + a did-you-mean over the
   accepted `my.plan` keys ([[cand/rank-candidates]]) + the full accepted
   set — or nil when every my.plan key is accepted. Foreign-namespace and
   injectable keys pass (the open-map convention stays intact)."
  [verb request accepted]
  (when-let [bad (->> (keys request)
                      (filter my-plan-key?)
                      (remove accepted)
                      first)]
    (let [targets (filterv my-plan-key? accepted)
          sugg    (->> (cand/rank-candidates (name bad) (mapv name targets))
                       (mapv (fn [{to :seon.repair/to}] (str ":my.plan/" to))))]
      (fail (str verb ": unknown key " bad
                 (when (seq sugg)
                   (str " — did you mean " (str/join " or " sugg) "?"))
                 " Accepted my.plan keys: "
                 (str/join " " (sort targets)) ".")))))

(defn check-request-keys
  "Nil, or a fail envelope, when `request` carries an unknown `my.plan` key —
   accepted set DERIVED from the registered `schema-kw` request schema."
  [verb request schema-kw]
  (unknown-key-fail verb request (schema-map-keys schema-kw)))

(defn check-plan-keys
  "The recursive plan! key guard: the top `request` map against
   `:my.plan/plan-request`, then every `:my.plan/children` node (at any
   depth) against `:my.plan/plan-node` — first offender → fail envelope,
   else nil. Catches a misspelled key that would otherwise vanish and mint
   a childless plan."
  [verb request]
  (let [node-keys (schema-map-keys :my.plan/plan-node)
        check-node (fn check-node [node]
                     (or (unknown-key-fail verb node node-keys)
                         (some #(when (map? %) (check-node %))
                               (:my.plan/children node))))]
    (or (unknown-key-fail verb request (schema-map-keys :my.plan/plan-request))
        (some #(when (map? %) (check-node %)) (:my.plan/children request)))))

;; --- Derived work-queue queries — all pure Datalog over the graph, with
;; --- [[rules]] as `%`. Nothing here is stored; blocked-ness, ready-ness,
;; --- roll-up, and the position anchor recompute from the facts every read.

(defn ready-leaves
  "Ready steps (open, unblocked — a leaf, or a drained non-leaf to verify and
   close) owned by `agent-eid`, oldest first, as `[{:id :title :created-at} …]`.
   Sorted in CLJS — `:seon.db/order-by` is not a `seon.db/query` request key."
  [db agent-eid]
  (->> (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find ?id ?title ?created
                    :in $ % ?a
                    :where
                    [?t :my.plan/agent ?a]
                    (ready ?t)
                    [?t :my.plan/id ?id]
                    [?t :my.plan/title ?title]
                    [?t :my.plan/created-at ?created]]
                  :seon.db/args [rules agent-eid]})
       (sort-by #(.getTime ^js (nth % 2)))
       (mapv (fn [[id title created]]
               {:my.plan/id         id
                :my.plan/title      title
                :my.plan/created-at created}))))

(defn active-steps
  "The `agent-eid`'s :active steps, oldest first, as
   `[{:id :title :expect?} …]`. ONE is the intended cardinality (active!
   demotes the rest); a vector so a hand-transacted second active still
   surfaces instead of vanishing."
  [db agent-eid]
  (->> (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find ?t ?id ?title ?created
                    :in $ ?a
                    :where
                    [?t :my.plan/agent ?a]
                    [?t :my.plan/status :active]
                    [?t :my.plan/id ?id]
                    [?t :my.plan/title ?title]
                    [?t :my.plan/created-at ?created]]
                  :seon.db/args [agent-eid]})
       (sort-by #(.getTime ^js (nth % 3)))
       (mapv (fn [[e id title _]]
               (let [expect (:my.plan/expect (db/entity db e))]
                 (cond-> {:my.plan/id id :my.plan/title title}
                   expect (assoc :my.plan/expect expect)))))))

(defn ancestor-chain
  "Step `id`'s ancestor chain as `[root … parent self]` of trimmed maps
   (id/title + goal/pace when present) — the path the anchor narrates.
   Walks the plain `parent` ref; cycle-safe via a seen set."
  [db id]
  (loop [chain () cur id seen #{}]
    (if (or (nil? cur) (seen cur))
      (vec chain)
      (let [e (db/entity db [:my.plan/id cur])
            node (cond-> {:my.plan/id cur :my.plan/title (:my.plan/title e)}
                   (:my.plan/goal e) (assoc :my.plan/goal (:my.plan/goal e))
                   (:my.plan/pace e) (assoc :my.plan/pace (:my.plan/pace e)))]
        (recur (cons node chain)
               (:my.plan/id (:my.plan/parent e))
               (conj seen cur))))))

(defn descendant-ids
  "Ids of every node strictly under `id` (transitive, via `descendant`)."
  [db id]
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
  "Done/total over the leaves in `id`'s subtree (INCLUDING `id` itself when
   it is a leaf). `done?` = every such leaf done. Relation-find `[?l ?s]`
   (NOT a collection-find, which dedups by status and would mis-count)."
  [db id]
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
  "True iff `id` is stored :blocked OR some `needs` target has open work."
  [db id]
  (boolean (seq (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?t :in $ % ?id
                             :where [?t :my.plan/id ?id] (blocked ?t)]
                           :seon.db/args [rules id]}))))

(defn ready?
  "True iff `id` is open, unblocked, and ready — an actionable leaf or a
   drained non-leaf whose only remaining action is verify-and-close."
  [db id]
  (boolean (seq (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?t :in $ % ?id
                             :where [?t :my.plan/id ?id] (ready ?t)]
                           :seon.db/args [rules id]}))))

(defn status-view
  "Derived one-node view: done?/blocked?/ready? + the subtree progress."
  [db id]
  (let [{:my.plan/keys [done total done?]} (rollup db id)]
    {:my.plan/id       id
     :my.plan/done?    done?
     :my.plan/blocked? (blocked? db id)
     :my.plan/ready?   (ready? db id)
     :my.plan/progress {:my.plan/done  done
                        :my.plan/total total}}))

;; --- The position ANCHOR — derived, never stored. "Where the agent IS":
;; --- the :active step (or the first ready leaf), its ancestor chain to the
;; --- root, and the root's done/total roll-up.

(defn anchor
  "The `agent-eid`'s derived position, or nil when no plan work exists.

   `{:my.plan/step <active-or-first-ready> :my.plan/chain [root … self]
     :my.plan/active? <bool> :my.plan/progress <root rollup>}`. The :active
   step wins; with none, the oldest ready leaf is the presumed next
   position."
  [db agent-eid]
  (let [active (first (active-steps db agent-eid))
        step   (or active (first (ready-leaves db agent-eid)))]
    (when step
      (let [chain (ancestor-chain db (:my.plan/id step))
            root  (first chain)]
        {:my.plan/step     step
         :my.plan/chain    chain
         :my.plan/active?  (some? active)
         :my.plan/progress (rollup db (:my.plan/id root))}))))

;; --- plan! — compile a nested plan to ONE flat, tempid-linked transact. ----

(defn compile-plan
  "Nested plan spec `root` → `{:tx <flat tempid-keyed tx-data>
                               :labels <author-label/:root → minted id>
                               :root-id <id>}`, or `{:error msg}` when an
   `:my.plan/after` names an unknown label.

   `root` carries the root's title/goal/pace; every node may carry
   `:my.plan/expect` and its own `:children`. Cross-sibling edges link by
   STRING TEMPID, never same-tx lookup-refs (a lookup-ref to a
   not-yet-asserted sibling throws `:entity-id/missing`). Tempids are
   order-independent, so an `:after` may name any label in the plan. Pass 1
   assigns every node a tempid + a minted `:my.plan/id` and records its
   author label; pass 2 emits one tx-map per node."
  [agent root]
  (let [nodes      (volatile! [])
        label->tid (volatile! {})]
    (letfn [(walk [node parent-tid]
              (let [tid   (str "t" (count @nodes))
                    id    (db/new-id!)
                    label (:my.plan/ref node)]
                (when label (vswap! label->tid assoc label tid))
                (vswap! nodes conj {:tid         tid
                                    :id          id
                                    :title       (:my.plan/title node)
                                    :goal        (:my.plan/goal node)
                                    :pace        (:my.plan/pace node)
                                    :description (:my.plan/description node)
                                    :expect      (:my.plan/expect node)
                                    :parent-tid  parent-tid
                                    :after       (:my.plan/after node)
                                    :label       label})
                (doseq [c (:my.plan/children node)] (walk c tid))
                tid))]
      (walk root nil)
      (let [l->t    @label->tid
            ns      @nodes
            unknown (->> ns (mapcat :after) distinct (remove l->t) seq)]
        (if unknown
          {:error (str "plan!: :my.plan/after names unknown label(s) "
                       (str/join ", " (map pr-str unknown))
                       " — each :after must match some node's :my.plan/ref.")}
          {:tx      (mapv (fn [{:keys [tid id title goal pace description expect
                                       parent-tid after]}]
                            (cond-> {:db/id              tid
                                     :my.plan/id         id
                                     :my.plan/title      title
                                     :my.plan/status     :open
                                     :my.plan/agent      agent
                                     :my.plan/created-at (js/Date.)}
                              goal        (assoc :my.plan/goal goal)
                              pace        (assoc :my.plan/pace pace)
                              description (assoc :my.plan/description description)
                              expect      (assoc :my.plan/expect expect)
                              parent-tid  (assoc :my.plan/parent parent-tid)
                              (seq after) (assoc :my.plan/needs
                                                 (mapv l->t after))))
                          ns)
           :labels  (into {:root (:id (first ns))}
                          (keep (fn [{:keys [label id]}] (when label [label id])) ns))
           :root-id (:id (first ns))})))))

(defn ^:async retract-subtree!
  "Retract `id` AND its whole subtree (the plain `parent` ref does NOT
   cascade, so we walk descendants and `retractEntity` each). Unknown id →
   fail envelope. History keeps every retracted node — undo is a `db/as-of`
   away."
  [id]
  (if (nil? (status-of id))
    (fail (str "drop!: no step " (pr-str id) "."))
    (let [db  @db/*conn*
          ids (distinct (conj (descendant-ids db id) id))
          env (await (db/transact!
                       {:seon.db/tx-data
                        (mapv (fn [i] [:db.fn/retractEntity [:my.plan/id i]]) ids)}))]
      (if (:seon.db/ok? env)
        {:my.plan/ok? true :my.plan/dropped (count ids)}
        (fail (str "drop!: store failed — "
                   (get-in env [:seon.db/error :seon.error/message])))))))

;; --- Tree pull (the structural read behind my.plan/tree). -----------------

(def tree-pattern
  "ONE recursive reverse-ref pull: a node + its whole subtree (children
   under `:my.plan/_parent`, the reverse of the plain `parent` ref → a
   vector) + each node's dependency ids inline."
  '[:my.plan/id :my.plan/title :my.plan/status :my.plan/goal :my.plan/expect
    :my.plan/pace
    {:my.plan/_parent ...}
    {:my.plan/needs [:my.plan/id]}])

(defn pull-subtree
  "Nested-EDN subtree rooted at step `id` (nil when `id` doesn't resolve)."
  [db id]
  (db/pull db tree-pattern [:my.plan/id id]))

(defn root-ids
  "Ids of `agent-eid`'s steps with no parent edge — the forest roots."
  [db agent-eid]
  (vec (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find [?id ...]
                    :in $ ?a
                    :where
                    [?t :my.plan/agent ?a]
                    [?t :my.plan/id ?id]
                    (not-join [?t] [?t :my.plan/parent _])]
                  :seon.db/args [agent-eid]})))

(defn all-root-ids
  "Ids of EVERY step with no parent edge, any agent — the forest's roots."
  [db]
  (vec (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find [?id ...]
                    :where
                    [?t :my.plan/id ?id]
                    (not-join [?t] [?t :my.plan/parent _])]})))

;; --- The WINDOWED plan-block render (`:plan` context section). ------------
;; --- Constant-size for any plan depth: position anchor + open frontier +
;; --- a small recently-completed tail; the completed interior is DROPPED
;; --- from the prompt (it stays queryable — tree/status/db-query read it).

(def open-keys
  "The frontier projection of one unfinished item — `[*]`-pulled then
   trimmed. (Not a pull PATTERN: naming a never-yet-transacted attr there
   throws.)"
  [:my.plan/id :my.plan/title :my.plan/status
   :my.plan/created-at :my.plan/description :my.plan/message])

(defn open-steps
  "Unfinished (:open/:active/:blocked) steps in db value `db`, oldest first;
   `agent-eid` nil = all agents."
  [db agent-eid]
  (let [q (if agent-eid
            '[:find [?t ...] :in $ % ?o
              :where (unfinished ?t) [?t :my.plan/agent ?o]]
            '[:find [?t ...] :in $ %
              :where [?t :my.plan/id _] (unfinished ?t)])]
    (->> (db/query {:seon.db/db db
                    :seon.db/query q
                    :seon.db/args (if agent-eid [rules agent-eid] [rules])})
         (map #(select-keys (db/pull db '[*] %) open-keys))
         (sort-by #(.getTime ^js (:my.plan/created-at %)))
         vec)))

(def frontier-limit
  "Max ready steps the frontier renders — the constant-size guarantee. The
   overflow renders as one `… and N more ready` line; `(my.plan/next {})`
   reads the full queue."
  7)

(def recent-done-limit
  "How many just-finished steps the resume tail recalls — the bounded
   anti-redo band. COUNT-bounded (not a wall-clock window) so the rendered
   line set is byte-identical until the agent actually closes another step,
   keeping the block cache-stable across renders."
  5)

(defn recent-done
  "The `agent-eid`'s most-recently-completed steps (newest first), capped at
   [[recent-done-limit]] — a derived memory of what was just accomplished so
   the agent doesn't re-do closed setup. Only done steps carry
   ::completed-at, so that attr doubles as the done filter; [] when
   nothing's been finished."
  [db agent-eid]
  (->> (db/query {:seon.db/db db
                  :seon.db/query
                  '[:find ?id ?title ?at
                    :in $ ?o
                    :where
                    [?t :my.plan/agent ?o]
                    [?t :my.plan/status :done]
                    [?t :my.plan/id ?id]
                    [?t :my.plan/title ?title]
                    [?t :my.plan/completed-at ?at]]
                  :seon.db/args [agent-eid]})
       (sort-by #(.getTime ^js (nth % 2)) >)
       (take recent-done-limit)
       (mapv (fn [[id title at]]
               {:my.plan/id           id
                :my.plan/title        title
                :my.plan/completed-at at}))))

(defn stamp
  "Compact ABSOLUTE creation time of `at` — UTC `YYYY-MM-DD HH:MM`, derived
   only from the datom (NOT `now`), so a row renders byte-identical every
   turn while the agent still reads recency (it compares against the turn
   clock). A relative \"3m ago\" string would change on every render and
   bust the stable-prefix cache for an unchanged block."
  [at]
  (-> (.toISOString ^js at) (subs 0 16) (str/replace "T" " ")))

(defn anchor-section
  "The position-anchor lines for [[anchor]] map `a` — \"\" when nil.

   Line 1 names the goal (root title, `goal:` narrative + pace when
   present); line 2 is the you-are-here: the :active step (or the next
   ready one) with the root's done/total roll-up; a `verify before done!`
   line follows when the anchored step carries `:my.plan/expect`."
  [a]
  (if (nil? a)
    ""
    (let [{:my.plan/keys [step chain active? progress]} a
          root   (first chain)
          {:my.plan/keys [done total]} progress
          goal   (:my.plan/goal root)
          pace   (:my.plan/pace root)
          expect (:my.plan/expect step)]
      (str "; PLAN «" (:my.plan/title root) "»"
           (when goal (str " — goal: " goal))
           (when pace (str " [" (name pace) "]")) "\n"
           "; → " (if active? "NOW (active)" "next ready") ": "
           (:my.plan/id step) " «" (:my.plan/title step) "» — "
           done " of " total " steps done"
           (when expect (str "\n;   verify before done!: " expect))))))

(defn frontier-section
  "The open-frontier lines: `actives` (▸-marked) then up to
   [[frontier-limit]] `readies`, one `; <id> [<created-at>] <title>` line
   each (a `✉` marks a step auto-minted from your human's message) — or
   \"\" when both are empty."
  [actives readies]
  (if (and (empty? actives) (empty? readies))
    ""
    (let [shown (take frontier-limit readies)
          more  (- (count readies) (count shown))
          line  (fn [marker {:my.plan/keys [id title created-at message]}]
                  (str "; " marker (when message "✉ ") id
                       (when created-at (str " [" (stamp created-at) "]"))
                       " " title))]
      (str "; Open frontier — close each step with (my.plan/done! {:my.plan/id \"<id>\"})\n"
           "; the MOMENT its work lands (never batch closes at the end);\n"
           "; take one up with (my.plan/active! {:my.plan/id \"<id>\"}); add a\n"
           "; DISCOVERED step UNDER this plan (never a new parentless root):\n"
           "; (my.plan/step! {:my.plan/title \"…\" :my.plan/parent [:my.plan/id \"<an id here>\"]})\n"
           (str/join "\n"
                     (concat (map #(line "▸ " %) actives)
                             (map #(line "" %) shown)))
           (when (pos? more)
             (str "\n; … and " more " more ready — (my.plan/next {}) lists them all."))))))

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

(def empty-plan-teaching
  "The `:plan` block's OWN teaching for the no-plan-yet state.

   Colocation (owner directive 2026-07-10): the empty state is exactly
   when decompose-first must be taught — once a plan exists the anchor +
   frontier lines carry the workflow themselves, and this header is
   absent. Byte-stable (cache-safe)."
  (str "; ── plan ── (empty)\n"
       "; Multi-step work: decompose FIRST, before starting the work —\n"
       ";   (my.plan/plan! {:my.plan/title \"…\" :my.plan/goal \"…\"\n"
       ";                   :my.plan/children [{:my.plan/title \"step 1\"} …]})\n"
       "; mints the whole plan in one call; it renders here and survives\n"
       "; restarts. Close each step the MOMENT its work lands\n"
       "; ((my.plan/done! {:my.plan/id \"<id>\"})); add a discovered step\n"
       "; UNDER the plan: (my.plan/step! {:my.plan/title \"…\"\n"
       ";                                 :my.plan/parent [:my.plan/id \"<id>\"]})."))

(defn plan-body
  "Windowed plan text for `agent` in db value `db`.

   Three bands, all DERIVED, nothing stored: (1) the position anchor —
   where you ARE in which goal, (2) the open frontier — the :active step +
   the ready queue (capped), (3) a small recently-completed tail (resume
   grounding). A 1000-step plan renders at constant size: the completed
   interior is dropped from the prompt but stays queryable (`tree`,
   `status`, `db/query`). Rides as `;` comments so the whole context reads
   as eval'able Clojure. NO plan data ⇒ [[empty-plan-teaching]] — the
   block teaches its own workflow exactly when nothing else can."
  [db agent]
  (let [body
        (if-let [oe (agent-eid db agent)]
          (let [a          (anchor db oe)
                actives    (active-steps db oe)
                active-ids (into #{} (map :my.plan/id) actives)
                unfinished (open-steps db oe)
                actives*   (filterv #(active-ids (:my.plan/id %)) unfinished)
                readies    (filterv (fn [{:my.plan/keys [id status]}]
                                      (and (not (active-ids id))
                                           (= :open status)
                                           (ready? db id)))
                                    unfinished)]
            (str/join "\n" (remove str/blank?
                                   [(anchor-section a)
                                    (frontier-section actives* readies)
                                    (done-section (recent-done db oe))])))
          "")]
    (if (str/blank? body) empty-plan-teaching body)))

(defn plan-block
  "Context-section fn (`:plan`, seon.config/default-ctx-blocks priority 45):
   [[plan-body]] for the CALLING agent — the `:seon.agent/id` in the render
   input, resolved as a `[:seon.agent/id id]` ref against the render's db
   value — absent `:seon.db/db` defaults to the current conn, the same
   convention as every other core section fn. An agent with no plan data
   gets [[empty-plan-teaching]] — the block's own decompose-first
   workflow header (colocation, 2026-07-10); everything else is derived,
   nothing stored, nothing to acknowledge."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (plan-body (or db @db/*conn*) [:seon.agent/id id]))

;; --- The `:seon.render/html` TWIN — the human's live plan tile. -----------
;; --- Colocated with [[plan-block]] (the transcript precedent). Zero prompt
;; --- cost: `*.internal` nses never render into agent context
;; --- (seon.agent.ctx.namespaces/hidden-ns-name?). Where the :ai block
;; --- WINDOWS (anchor + capped frontier + recent-done tail), the tile shows
;; --- the WHOLE forest — the human explores what the prompt windows away.
;; ---
;; --- STRUCTURE vs SIGNAL (2026-07-11): [[build-forest]] assembles ONLY the
;; --- renderable nested TREE (parent→children layout, waiters from the
;; --- inverted `needs` edges, timestamps + message-origin, oldest-first) —
;; --- a projection the windowing :ai block never needs and the flat shared
;; --- fns cannot give (they answer counts/positions, not a tree). Every
;; --- derived SIGNAL — roll-up, done-ness, ready, blocked, the you-are-here
;; --- position — comes from the SAME shared db fns the :ai block uses
;; --- ([[rollup]] / [[ready?]] / [[blocked?]] / [[anchor]]), ONE derivation
;; --- mechanism over the fixed recursive `descendant` rule. (Until the
;; --- datahike-CLJS recursive-rule engine fix — fork 1598a824 — those rules
;; --- yielded nothing past depth 1, so the tile carried a parallel pure-walk
;; --- re-derivation; that workaround is now deleted and the tile agrees with
;; --- the :ai block on every tree by construction.) Plain pulled data only
;; --- (no lazy Entity walk): a `my.*` render symbol is SCI-re-interpreted
;; --- from its stored source at render time, and the plain-data primitives
;; --- are the SCI-proven path ([[plan-body]] uses the same ones).
;; ---
;; --- Interactivity rides Datastar SIGNALS (the client-side signal store
;; --- survives the SSE whole-element morph; `__ifmissing` keeps a re-morph
;; --- from resetting them): $planstep = the one expanded step id,
;; --- $planclosed = collapsed subtree ids (space-delimited), $planfull =
;; --- reveal the completed interior, $plandone = the recent-done list.

(defn- toggle-step-expr
  "The `data-on:click` expression toggling step `id`'s detail panel."
  [id]
  (str "$planstep = ($planstep === '" id "' ? '' : '" id "')"))

(defn- toggle-closed-expr
  "The `data-on:click` expression toggling subtree `id`'s collapse."
  [id]
  (str "$planclosed = ($planclosed.includes(' " id " ') ? "
       "$planclosed.replace(' " id " ', ' ') : $planclosed + ' " id " ')"))

(defn- step-order
  "Rows/nodes sorted oldest-first (created-at, then id — stable)."
  [rows]
  (sort-by (fn [r] [(or (some-> (:my.plan/created-at r) .getTime) 0)
                    (:my.plan/id r)])
           rows))

(defn- row->node
  "Pulled step row `r` → one walked node map (children filled by caller)."
  [eid->row waiters r children]
  (let [needs (->> (:my.plan/needs r)
                   (keep (fn [n] (eid->row (:db/id n))))
                   (mapv (fn [nr] {:my.plan/id    (:my.plan/id nr)
                                   :my.plan/title (:my.plan/title nr)})))]
    (cond-> {:my.plan/id       (:my.plan/id r)
             :my.plan/title    (:my.plan/title r)
             :my.plan/status   (:my.plan/status r)
             :my.plan/children children}
      (:my.plan/goal r)         (assoc :my.plan/goal (:my.plan/goal r))
      (:my.plan/pace r)         (assoc :my.plan/pace (:my.plan/pace r))
      (:my.plan/expect r)       (assoc :my.plan/expect (:my.plan/expect r))
      (:my.plan/description r)  (assoc :my.plan/description
                                       (:my.plan/description r))
      (:my.plan/message r)      (assoc :my.plan/message? true)
      (:my.plan/created-at r)   (assoc :my.plan/created-at
                                       (:my.plan/created-at r))
      (:my.plan/completed-at r) (assoc :my.plan/completed-at
                                       (:my.plan/completed-at r))
      (seq needs)               (assoc :my.plan/needs needs)
      (seq (waiters (:db/id r))) (assoc :my.plan/waiters
                                        (waiters (:db/id r))))))

(defn- build-forest
  "The `agent-eid`'s whole plan forest as nested node maps.

   ONE query for the step eids, one `[*]` pull per step (plain data —
   the same primitives [[open-steps]] uses), then a pure assembly:
   children from the forward `parent` ref, `waiters` (what waits on a
   step) from the inverted `needs` edges. Cycle-safe; oldest-first."
  [db agent-eid]
  (let [rows     (->> (db/query {:seon.db/db db
                                 :seon.db/query
                                 '[:find [?t ...]
                                   :in $ ?a
                                   :where
                                   [?t :my.plan/agent ?a]
                                   [?t :my.plan/id _]]
                                 :seon.db/args [agent-eid]})
                      (mapv #(db/pull db '[*] %)))
        eid->row (into {} (map (fn [r] [(:db/id r) r])) rows)
        kids     (group-by #(get-in % [:my.plan/parent :db/id]) rows)
        waiters  (reduce (fn [m r]
                           (reduce (fn [m n]
                                     (update m (:db/id n) (fnil conj [])
                                             (:my.plan/title r)))
                                   m (:my.plan/needs r)))
                         {} rows)
        node     (fn node [r seen]
                   (let [seen (conj seen (:my.plan/id r))
                         children
                         (->> (kids (:db/id r))
                              step-order
                              (remove #(seen (:my.plan/id %)))
                              (mapv #(node % seen)))]
                     (row->node eid->row waiters r children)))]
    (->> rows
         (remove :my.plan/parent)
         step-order
         (mapv #(node % #{})))))

;; --- Per-node SIGNALS delegate to the shared rule-backed db fns
;; --- ([[rollup]] / [[ready?]] / [[blocked?]]) — the SAME derivations the
;; --- :ai block uses. `db` is the frozen render db value; a node carries its
;; --- `:my.plan/id` so every signal is one shared-fn call keyed by that id.
;; --- (Roll-up self-includes a leaf, so `(:my.plan/done? (rollup db id))` is
;; --- the done-ness of BOTH a leaf and a drained non-leaf — one call.)

(defn- need-line-html
  "One `waits on` line for need `n` — done-glyph + title + id (done-ness of
   the target derived via the shared [[rollup]] over db value `db`)."
  [db n]
  (let [done? (:my.plan/done? (rollup db (:my.plan/id n)))]
    [:li {:class "flex items-center gap-1"}
     [:span {:class (str "shrink-0 " (if done? "text-success" "text-warning"))}
      (if done? "✓" "○")]
     [:span {:class "text-text-200 truncate"} (:my.plan/title n)]
     [:span {:class "text-text-500 shrink-0"} (:my.plan/id n)]]))

(defn- step-detail-html
  "Walked `node`'s expand-in-place detail panel — `data-show`n by $planstep."
  [db node]
  (let [id  (:my.plan/id node)
        row (fn [label body]
              [:div {:class "flex gap-2"}
               [:span {:class "text-text-500 shrink-0"} label]
               [:div {:class "text-text-200 min-w-0"} body]])]
    [:div {:data-show (str "$planstep === '" id "'")
           :style "display:none;border-left:1px solid #3d3a36;margin-left:3px;padding-left:10px"
           :class "flex flex-col gap-1 text-2xs py-1"}
     [:div {:class "text-text-500"}
      (str id
           (when-let [c (:my.plan/created-at node)]
             (str " · created " (stamp c)))
           (when-let [d (:my.plan/completed-at node)]
             (str " · done " (stamp d))))]
     (when-let [g (:my.plan/goal node)] (row "goal" g))
     (when-let [d (:my.plan/description node)] (row "desc" d))
     (when-let [x (:my.plan/expect node)] (row "verify" x))
     (when-let [needs (seq (:my.plan/needs node))]
       (row "waits on"
            (into [:ul {:class "flex flex-col gap-1"}]
                  (map #(need-line-html db %))
                  needs)))
     (when-let [ws (seq (:my.plan/waiters node))]
       (row "blocks" (str/join ", " ws)))
     (when (:my.plan/message? node)
       (row "origin" "✉ auto-minted from a message"))]))

(defn- step-row-html
  "One tree row (+ its detail panel + its children `ul`) for walked `node`.

   Glyphs: `●` active (amber, NOW), `✓` done (dim; hidden until $planfull),
   `○` open (`ready` tag when actionable), `◌` blocked. A non-leaf carries
   its subtree `done/total` roll-up and a collapse chevron ($planclosed,
   click-stopped so it doesn't also toggle the detail). Signals delegate to
   the shared [[rollup]]/[[ready?]]/[[blocked?]] over db value `db`."
  [db node next-id depth]
  (let [id       (:my.plan/id node)
        children (:my.plan/children node)
        leaf?    (empty? children)
        ru       (rollup db id)
        done?    (:my.plan/done? ru)
        active?  (= :active (:my.plan/status node))
        next?    (= id next-id)
        blocked? (and (not done?) (blocked? db id))
        [glyph gcls] (cond
                       active?  ["●" "text-signal"]
                       done?    ["✓" "text-text-500"]
                       blocked? ["◌" "text-warning"]
                       :else    ["○" "text-text-400"])
        tcls     (cond
                   active?  "text-text-50"
                   done?    "text-text-500"
                   blocked? "text-text-400"
                   :else    "text-text-200")]
    [:li (cond-> {:class "flex flex-col"}
           done? (assoc :data-show "$planfull" :style "display:none"))
     [:div {:class (str "flex items-center gap-2 py-0.5 rounded cursor-pointer"
                        (when active? " bg-base-850"))
            :style (str "padding-left:" (* 12 depth) "px"
                        (when active? ";border-left:2px solid #f0b429"))
            (keyword "data-on:click") (toggle-step-expr id)}
      (if leaf?
        [:span {:class "shrink-0" :style "display:inline-block;width:1ch"}]
        [:span {:class "text-text-500 shrink-0 select-none"
                (keyword "data-on:click__stop") (toggle-closed-expr id)
                :data-text (str "$planclosed.includes(' " id " ') ? '▸' : '▾'")}
         "▾"])
      [:span {:class (str "shrink-0 " gcls)} glyph]
      (when (:my.plan/message? node) [:span {:class "text-info shrink-0"} "✉"])
      [:span {:class (str "truncate " tcls)} (:my.plan/title node)]
      (when active? [:span {:class "text-2xs text-signal shrink-0"} "NOW"])
      (when (and next? (not active?))
        [:span {:class "text-2xs text-signal shrink-0"} "next"])
      (when (and (not done?) (not active?) (not next?)
                 (ready? db id))
        [:span {:class "text-2xs text-success shrink-0"} "ready"])
      (when-not leaf?
        [:span {:class "text-2xs text-text-500 tabular-nums shrink-0"}
         (str (:my.plan/done ru) "/" (:my.plan/total ru))])]
     (step-detail-html db node)
     (when-not leaf?
       [:ul {:class "flex flex-col"
             :data-show (str "!$planclosed.includes(' " id " ')")}
        (map #(step-row-html db % next-id (inc depth)) children)])]))

(defn- root-card-html
  "One plan-root card: title + pace + roll-up, goal line, thin amber
   progress bar, then the full step tree (roll-up via shared [[rollup]])."
  [db root next-id]
  (let [{:my.plan/keys [done total]} (rollup db (:my.plan/id root))
        pct  (if (pos? total) (quot (* 100 done) total) 0)
        goal (:my.plan/goal root)
        pace (:my.plan/pace root)]
    [:div {:class "flex flex-col gap-1"}
     [:div {:class "flex items-center gap-2 cursor-pointer"
            (keyword "data-on:click") (toggle-step-expr (:my.plan/id root))}
      [:span {:class "text-sm font-semibold text-text-50 truncate"}
       (:my.plan/title root)]
      (when pace
        [:span {:class "text-2xs text-text-500 shrink-0"}
         (str "[" (name pace) "]")])
      [:span {:class "text-2xs text-text-400 tabular-nums shrink-0"}
       (str done "/" total " done")]]
     (when goal
       [:div {:class "text-2xs text-text-400 truncate"} (str "goal: " goal)])
     [:div {:class "bg-base-800 w-full"
            :style "height:3px;border-radius:2px;overflow:hidden"}
      [:div {:style (str "height:3px;background:#f0b429;width:" pct "%")}]]
     (step-detail-html db root)
     [:ul {:class "flex flex-col"}
      (map #(step-row-html db % next-id 0) (:my.plan/children root))]]))

(defn plan-block-html
  "Live, explorable HTML twin of [[plan-block]] — the `/agent/{id}` tile.

   Renders the agent's WHOLE plan forest (the :ai block windows; the tile
   does not): per root a title/goal/pace header with a done/total roll-up
   and a thin amber progress bar, then the step tree — `●` active (NOW,
   highlighted), `○` open (`ready`-tagged), `◌` blocked, `✓` done (hidden
   until the show-completed toggle), `✉` message-minted. STRUCTURE comes
   from [[build-forest]] (the renderable nested tree); every SIGNAL —
   roll-up, done, ready, blocked, and the you-are-here position — derives
   from the SAME shared db fns the :ai block uses ([[rollup]]/[[ready?]]/
   [[blocked?]]/[[anchor]], see the derivation note above); the
   recently-completed tail reuses [[recent-done]]. Interactivity is
   Datastar signals (they live
   client-side, so they survive the SSE whole-element morph): click a
   step to expand its detail in place (description, verify-before-done
   `expect`, needs edges both ways, timestamps, id); chevrons collapse
   subtrees; $planfull reveals the completed interior; the recently-
   completed tail expands on click. No plan → a quiet one-liner (the
   teaching text in [[empty-plan-teaching]] is for the model, not the
   human)."
  {:malli/schema [:=> [:cat :seon.render/section-request]
                  :seon.render.live-tile/hiccup]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (let [db     (or db @db/*conn*)
        oe     (agent-eid db [:seon.agent/id id])
        forest (if oe (build-forest db oe) [])]
    (if (empty? forest)
      [:div {:class "text-text-500 italic text-2xs font-mono py-1"}
       "no plan yet"]
      (let [;; The you-are-here — the SAME [[anchor]] the :ai block reads:
            ;; an :active step wins; else the oldest ready leaf is next.
            a          (anchor db oe)
            next-id    (when-not (:my.plan/active? a)
                         (:my.plan/id (:my.plan/step a)))
            ;; Rows hidden behind $planfull = the done non-root nodes;
            ;; done-ness derives from the shared [[rollup]].
            done-count (count (filter #(:my.plan/done? (rollup db (:my.plan/id %)))
                                      (mapcat #(tree-seq (fn [_] true)
                                                         :my.plan/children %)
                                              (mapcat :my.plan/children
                                                      forest))))
            dones      (recent-done db oe)]
        [:div {:class "flex flex-col gap-2 text-xs font-mono"
               :data-signals__ifmissing
               "{planstep: '', planclosed: '', planfull: false, plandone: false}"}
         (map #(root-card-html db % next-id) forest)
         (when (pos? done-count)
           [:div {:class (str "flex items-center gap-1 text-2xs text-text-400 "
                              "cursor-pointer select-none")
                  (keyword "data-on:click") "$planfull = !$planfull"}
            [:span {:data-text "$planfull ? '▾' : '▸'"} "▸"]
            [:span {:data-text (str "$planfull ? 'hide completed steps' : "
                                    "'show all " done-count
                                    " completed steps in place'")}
             (str "show all " done-count " completed steps in place")]])
         (when (seq dones)
           [:div {:class "flex flex-col"}
            [:div {:class (str "flex items-center gap-1 text-2xs text-text-500 "
                               "cursor-pointer select-none")
                   (keyword "data-on:click") "$plandone = !$plandone"}
             [:span {:data-text "$plandone ? '▾' : '▸'"} "▸"]
             [:span (str "recently completed (" (count dones) ")")]]
            [:ul {:class "flex flex-col" :data-show "$plandone"
                  :style "display:none"}
             (map (fn [{:my.plan/keys [title completed-at]}]
                    [:li {:class "text-2xs text-text-500 truncate"}
                     (str "✓ [" (stamp completed-at) "] " title)])
                  dones)]])]))))
