---
type: research
status: active
tags: [research, agent, schema, database]
---

# `my.todo` + `my.kb` agent-facing API — the namespace IS the manual

> Design-research for `feature/agent-fsm`. DESIGN ONLY. Present-tense (the
> system as it is when built). Sits ON the hierarchical+dependency `my.todo`
> model ([[hierarchical-todo-deps-2026-06-27]]) and its dual render
> ([[todo-dual-render-2026-06-27]]). Every datahike claim is grounded by
> `file:line` in the vendored source; the grounding section at the end states
> the one rule that decides the whole `plan!` shape — **tempid strings, not
> same-tx lookup-refs**.

## TL;DR — the verdict in eight lines

1. **Verdict: (c) both — but with a division of labour.** The data-structure
   API (`plan!`) is what turns "create + restructure a whole plan" into ONE
   declarative transact; the self-teaching namespace (verbs + docstrings +
   `:test` examples, rendered in full) is what makes the agent *able* to reach
   for it. Neither alone is enough: a rich API the agent can't see is dead; a
   pile of examples over N manual `add!`/`depends!` calls re-grows the
   thread-the-id boilerplate the data API exists to kill.
2. **See the whole tree (re-plan):** `tree` → ONE recursive reverse-ref pull
   (`{:my.todo/_parent ...}`) returns the whole forest as nested EDN with inline
   dep ids. This is the structural read the agent re-reasons over when it
   changes its mind. The live `next`/dashboard ([[todo-dual-render-2026-06-27]])
   is the *focus* view (one ready leaf); `tree` is the *structure* view.
3. **The killer verb — `plan!`:** takes a nested `{:my.todo/title … :my.todo/children […] :my.todo/after […]}`
   tree and transacts the WHOLE plan (parents + leaves + sequencing deps) in ONE
   ACID write, returning a label→id map. The agent authors a plan as data; the
   verb compiles it to tempid-linked tx-data; datahike does the linking.
4. **The datahike rule that shapes `plan!` (falsified the obvious approach):**
   `:my.todo/parent`/`:my.todo/depends-on` are PLAIN refs, and **same-tx
   lookup-refs are order-dependent and THROW** when the target isn't yet
   asserted (`entid-strict`, utils.cljc:141-148). So the cross-sibling edges of
   a one-shot plan must link by **string tempid** (`:db/id "t-x"`,
   transaction.cljc:67-70, 1186-1201), NOT `[:my.todo/id "x"]`. `plan!` compiles
   to a FLAT tempid-keyed vector — order-independent, robust.
5. **Pure nesting is tree-only, honestly.** `{:my.todo/_parent [child-maps]}`
   elegantly builds the *tree* (each child auto-gets `:my.todo/parent parent-eid`
   via `explode`, transaction.cljc:665-667) but CANNOT express a sibling→sibling
   `depends-on` edge — those cross the tree. The flat-tempid form expresses both;
   it is the shape `plan!` emits.
6. **Restructure is data-based too:** change-your-mind = `drop!` a subtree (one
   retract walk) + `plan!` a new one, OR surgical `move!` (re-parent) /
   `depends!` (re-wire). Minimal manual ops; the structure carries the context.
7. **Focused edits stay one-liners:** `add!` / `done!` / `reopen!` / `depends!`
   / `move!` / `drop!` / `produced!`, each map-in/map-out, never-throw, threaded
   by lookup-ref across calls (across-tx lookup-refs always resolve — the
   fragility is *same-tx only*).
8. **`my.kb` has no CRUD facade — its API IS `seon.db`.** The self-teaching
   `my.kb` ns is schema + provenance shapes + worked `db` chains + `:test`s (the
   DB manual). The file→KB job is a `my.todo/plan!` whose per-file leaves
   `transact!` `:my.kb.*` rows + `produced!` them, and a synthesize leaf that
   `:my.todo/after` all of them.

---

## 1. See the whole tree — the read API for re-planning

Two reads, two needs. The owner's steer: the *focus* view keeps the agent on
rails turn-to-turn; *re-planning* needs the full structure.

### 1.1 `tree` — the whole forest as nested EDN (the re-plan read)

`tree` is ONE recursive reverse-ref pull. Because `:my.todo/parent` is a plain
ref, the reverse attr `:my.todo/_parent` is multi (pull_api.cljc:146-147, the
`(not component?)` branch), so children come back as a vector; `...` recurses the
whole subtree (`recurse-attr`, pull_api.cljc:158-161).

```clojure
(db/pull db
  '[:my.todo/id :my.todo/title :my.todo/status
    {:my.todo/_parent ...}                       ; recurse CHILDREN, unbounded
    {:my.todo/depends-on [:my.todo/id]}]         ; dep edges, as id refs
  [:my.todo/id "plan-1"])
```

Returns the structure the agent re-reasons over — a tree, not N flat rows:

```clojure
{:my.todo/id "plan-1" :my.todo/title "Process inbox → KB" :my.todo/status :open
 :my.todo/_parent
 [{:my.todo/id "f-1" :my.todo/title "process notes-a.md" :my.todo/status :done}
  {:my.todo/id "f-2" :my.todo/title "process notes-b.md" :my.todo/status :open}
  {:my.todo/id "syn" :my.todo/title "synthesize findings" :my.todo/status :open
   :my.todo/depends-on [{:my.todo/id "f-1"} {:my.todo/id "f-2"}]}]}
```

The forest = roots (todos with no parent) each pulled this way:

```clojure
'[:find [?id ...] :in $ ?a
  :where [?t :my.todo/agent ?a] [?t :my.todo/id ?id]
         (not-join [?t] [?t :my.todo/parent _])]
```

The `my.todo/tree` verb wraps this: `{:my.todo/root? <id>}` pulls one subtree;
`{:my.todo/all? true}` pulls the whole forest; each node carries its derived
`{:my.todo/done N :my.todo/total M}` roll-up (the rollup query is
[[hierarchical-todo-deps-2026-06-27]] §2a). **The agent reads `tree`, sees the
WHOLE structure, then makes clear focused edits** — exactly the
draw-into-context-then-edit loop the owner asked for.

**Lazy-install nuance (flagged):** `seon.db/pull` silently filters a registered
attr that has never been transacted (db.cljs:885-899), so on a flat world (no
todo has a parent yet) `{:my.todo/_parent ...}` simply yields no children — the
read degrades gracefully to a flat list. Once any `plan!`/`add! :parent` runs,
`:my.todo/parent` is installed and the recursion lights up.

### 1.2 `next` — the focus queue (one ready leaf)

`next` is the live work queue: ready actionable leaves, oldest first
([[hierarchical-todo-deps-2026-06-27]] §2c). It is what the dashboard block
highlights every turn. **Order in CLJS, not in the query** — see the correction
in §6.3 (`:seon.db/order-by` is not a `seon.db/query` request key).

---

## 2. Data-based bulk create + restructure — `plan!` (the killer feature)

### 2.1 The agent-facing shape — a plan is data

```clojure
(my.todo/plan!
  {:my.todo/title "Process inbox → KB"
   :my.todo/children
   [{:my.todo/title "process notes-a.md" :my.todo/ref "a"}
    {:my.todo/title "process notes-b.md" :my.todo/ref "b"}
    {:my.todo/title "synthesize findings" :my.todo/after ["a" "b"]}]})
;; => {:my.todo/ok? true
;;     :my.todo/root "Kpx-2606271830"
;;     :my.todo/ids  {"a" "Lq2-2606271830" "b" "Mr8-2606271830"
;;                    :root "Kpx-2606271830" "synthesize findings" "Nt4-2606271830"}}
```

- `:my.todo/children` nests arbitrarily deep (a child may itself carry
  `:my.todo/children`). Each nesting level becomes a `:my.todo/parent` edge.
- `:my.todo/ref` is an AUTHOR-LOCAL label (any string) the agent uses to name a
  node so siblings can sequence after it.
- `:my.todo/after` is a vector of labels this node `depends-on` — sequencing IS a
  dependency, so the queue needs no priority attr. `:after` may reference a label
  defined ANYWHERE in the plan (earlier OR later, any depth): all labels share
  one tempid namespace within the single tx, and tempids are order-independent.

The agent writes the plan once, declaratively. The verb returns the label→real-id
map so subsequent focused edits (`depends!`, `done!`) address nodes by their real
`:my.todo/id`.

### 2.2 The EXACT tx-data `plan!` compiles to (datahike-grounded)

`plan!` walks the plan tree, mints a real `:my.todo/id` per node (`db/new-id!`),
assigns each node a **string tempid** (`"t-<label-or-counter>"`), and emits ONE
flat vector. The plan above compiles to exactly:

```clojure
(db/transact!
  {:seon.db/tx-data
   [{:db/id "t-root" :my.todo/id "Kpx-2606271830"
     :my.todo/title "Process inbox → KB" :my.todo/status :open
     :my.todo/agent [:seon.agent/id "iCg-2606101519"] :my.todo/created-at #inst "…"}

    {:db/id "t-a" :my.todo/id "Lq2-2606271830"
     :my.todo/title "process notes-a.md" :my.todo/status :open
     :my.todo/agent [:seon.agent/id "iCg-2606101519"]
     :my.todo/parent "t-root" :my.todo/created-at #inst "…"}

    {:db/id "t-b" :my.todo/id "Mr8-2606271830"
     :my.todo/title "process notes-b.md" :my.todo/status :open
     :my.todo/agent [:seon.agent/id "iCg-2606101519"]
     :my.todo/parent "t-root" :my.todo/created-at #inst "…"}

    {:db/id "t-syn" :my.todo/id "Nt4-2606271830"
     :my.todo/title "synthesize findings" :my.todo/status :open
     :my.todo/agent [:seon.agent/id "iCg-2606101519"]
     :my.todo/parent "t-root" :my.todo/depends-on ["t-a" "t-b"]
     :my.todo/created-at #inst "…"}]})
;; => {:seon.db/ok? true
;;     :seon.db/tempids {"t-root" 1042 "t-a" 1043 "t-b" 1044 "t-syn" 1045 …}}
```

Three kinds of reference appear, and each resolves by a DIFFERENT, verified path:

| reference | value | resolves via | when |
|---|---|---|---|
| owning agent | `:my.todo/agent [:seon.agent/id "iCg-…"]` | lookup-ref → `entid` → avet (utils.cljc:129) | the agent is a PRIOR-tx (committed) entity, so the lookup-ref always resolves |
| tree edge | `:my.todo/parent "t-root"` | tempid string (main loop, transaction.cljc:1198-1201) | links to a sibling BORN in this tx — order-independent |
| dep edges | `:my.todo/depends-on ["t-a" "t-b"]` | each vector element a tempid (multival explode → tempid branch) | same |

`db/transact!`'s response carries `:seon.db/tempids` mapping each `"t-x"` → its
real eid, so a caller that wants eids back has them; `plan!` discards them and
returns the `:my.todo/id` strings (the agent's currency).

### 2.3 Why FLAT-tempid, not nested maps or lookup-refs (the falsification)

The obvious "transact a nested structure" dream is PARTIALLY a trap. Two honest
findings:

**(a) Nested reverse-ref maps build the TREE but not the DAG.** A nested
`{:my.todo/_parent [child-maps]}` works for the pure tree: `explode`
(transaction.cljc:646-685) hits the branch `(if (and (dbu/ref? db straight-a-ident)
(map? v)) (assoc v (dbu/reverse-ref a-ident) eid) …)` at lines 665-667, which
injects `:my.todo/parent parent-eid` onto each child — and `maybe-wrap-multival`
(625-644) treats the reverse attr as multival so a vector of children iterates.
This is true for a PLAIN ref (component-ness only governs cascade-retract via
`retract-components`, 730-733 — it is irrelevant to the write). BUT a sibling→
sibling `:my.todo/depends-on` edge cannot be expressed by nesting: the two
endpoints are peers in the tree, not parent/child. So pure nesting handles the
hierarchy and stalls on the sequencing — which is the whole point of a plan.

**(b) Same-tx lookup-refs are order-dependent and THROW.** The tempting fix —
nest the tree and link deps with `[:my.todo/id "a"]` — is fragile. `entid`
resolves a lookup-ref by `(dbi/datoms db :avet eid)` against the *running
transient* db (utils.cljc:115-129); `transact-add` resolves a ref value with
`entid-strict` (transaction.cljc:701), which RAISES `:entity-id/missing`
(utils.cljc:141-148) when the target's `:my.todo/id` datom hasn't been asserted
yet in this tx. So `[:my.todo/id "a"]` resolves ONLY if node `a` was processed
earlier in the loop — a property of map/vector iteration order, not something to
build correctness on.

**The robust mechanism is the string tempid** (`tempid?` = string-or-negative,
transaction.cljc:67-70). A tempid used as a ref value before its `:db/id`-carrying
entity is seen gets a fresh eid allocated and remembered
(transaction.cljc:1198-1201); the entity that later carries `:db/id "t-x"`
resolves to that SAME eid (`entity-map->op-vec`, the `(tempid? resolved-eid)`
branch at 864). Order-independent, so `:my.todo/after` can name a label defined
later in the plan. **Hence `plan!` compiles to a flat tempid-keyed vector, and
the design recommends tempids over both nesting (DAG-incapable) and same-tx
lookup-refs (order-fragile).**

### 2.4 Restructure — change-your-mind, data-based

| flow | verbs | what happens |
|---|---|---|
| replace a subtree | `drop!` then `plan!` | retract the old subtree (one walk), author a fresh plan under the same/another parent |
| re-parent a node | `move!` | retract the old `:my.todo/parent`, add the new one (a node keeps its identity + deps) |
| re-sequence | `depends!` (add) / a `[:db/retract id :my.todo/depends-on dep]` (remove) | rewire the DAG without touching the tree |

`drop!` matters because `:my.todo/parent` is PLAIN: `[:db.fn/retractEntity node]`
does NOT cascade to children (`retract-components`, transaction.cljc:730-733,
only re-emits `retractEntity` for COMPONENT datoms). So `drop!` first queries the
subtree (the `descendant` rule) and retracts each node explicitly:

```clojure
;; drop! body — retract a node and everything under it (plain ref ⇒ no cascade).
(let [ids (db/query {:seon.db/query
                     '[:find [?cid ...] :in $ % ?root
                       :where [?r :my.todo/id ?root]
                              (descendant ?r ?c) [?c :my.todo/id ?cid]]
                     :seon.db/args [my.todo/rules root-id]})]
  (db/transact! {:seon.db/tx-data
                 (mapv (fn [i] [:db.fn/retractEntity [:my.todo/id i]])
                       (conj ids root-id))}))
```

History keeps every retracted node (bitemporal store), so a fat-fingered `drop!`
is recoverable via `db/as-of` / re-transact — undo is free, no new verb.

---

## 3. Focused-edit verbs — small, threadable, never-throw

Thin over `seon.db`, scoped per-agent by `:my.todo/agent` from the `with-agent`
scope (`db/current-agent-id`), each map-in/map-out, each returning a
`{:my.todo/ok? …}` envelope, none throwing (errors are values — toolkit shape
#4, the shared `:seon/error`).

| verb | in | out | one-line role |
|---|---|---|---|
| `plan!` | `{:my.todo/title :my.todo/children?}` | `{:my.todo/ok? :my.todo/root :my.todo/ids}` | author a whole plan in ONE tx (§2) |
| `add!` | `{:my.todo/title :my.todo/parent? :my.todo/depends-on? :my.todo/from?}` | `{:my.todo/ok? :my.todo/id}` | mint one node; structure it at birth |
| `done!` | `{:my.todo/id}` | envelope | stamp `:completed-at`; idempotent |
| `reopen!` | `{:my.todo/id}` | envelope | flip `:done`→`:open`; retract `:completed-at` |
| `depends!` | `{:my.todo/id :my.todo/on [<ref>…]}` | envelope | add dep edge(s) to an existing node |
| `move!` | `{:my.todo/id :my.todo/parent <ref>}` | envelope | re-parent (retract old + add new) |
| `drop!` | `{:my.todo/id}` | `{:my.todo/ok? :my.todo/dropped <int>}` | retract a subtree (§2.4) |
| `produced!` | `{:my.todo/id :my.todo/kb [<ref>…]}` | envelope | link the KB rows this work created |
| `tree` | `{:my.todo/root? :my.todo/all?}` | nested EDN forest + roll-ups | the structural read (§1.1) |
| `next` | `{}` | `[{:my.todo/id :my.todo/title …}]` | the focus queue (§1.2) |

`add!`/`depends!`/`move!`/`produced!` accept lookup-refs `[:my.todo/id "a"]` as
ref values — and these are ACROSS-tx (the target is already committed), so they
always resolve. The id `add!` returns threads straight into the next call with no
reshape (toolkit's composability backbone).

```clojure
;; structuring is one-liners — sequencing is just a dependency:
(my.todo/add! {:my.todo/title "research vendor X"})            ; => {…/id "a"}
(my.todo/add! {:my.todo/title "write the brief"
               :my.todo/depends-on [[:my.todo/id "a"]]})       ; runs AFTER a
(my.todo/depends! {:my.todo/id "b" :my.todo/on [[:my.todo/id "a"]]})  ; or later
```

---

## 4. THE SELF-TEACHING NAMESPACE — `my.todo` rendered in full

This is the centerpiece: the actual ns. Shown whole every turn, it teaches every
operation. The `.internal` plumbing (queries, rollup, render, the tempid
compiler) is hidden (`hidden-ns-name?`), so ONLY this renders — keeping the
budget lean while the `:test` examples carry the manual. Docstrings follow the
house rule: concise, current-state, no changelog, no re-explaining the visible
`:malli/schema` (memory: docstrings = true current-state).

```clojure
(ns my.todo
  "Your plan + work queue as a TREE. A todo is one entity; a :my.todo/parent ref
   makes the list a plan (top = milestones, leaves = actions); a :my.todo/depends-on
   ref sequences work (\"do B after A\" = B depends-on A). Progress, blocked-ness,
   and \"what's next\" are all DERIVED — you store leaf facts, the dashboard
   re-derives every turn. Read `tree` to see the whole structure when you re-plan;
   work off `next` (the one ready leaf) turn-to-turn."
  (:require [my.todo.internal :as in]
            [seon.db :as db]
            [seon.schema :as schema]))

;; --- shapes: shared shapes (:seon.db/id, :seon.db/ref) REFERENCED, never inlined.

(schema/register! ::id          [:string {:seon.db/identity true}])
(schema/register! ::title       [:string {:min 1}])
(schema/register! ::status      [:enum :open :done])
(schema/register! ::agent       :seon.db/ref)            ; → owning agent (the scope ref)
(schema/register! ::parent      :seon.db/ref)            ; → parent todo (the TREE edge)
(schema/register! ::depends-on  [:vector :seon.db/ref])  ; → prereqs (the DAG edges)
(schema/register! ::produced    [:vector :seon.db/ref])  ; → kb rows this work created
(schema/register! ::created-at  :inst)
(schema/register! ::completed-at :inst)
(schema/register! ::from        :seon.db/ref)            ; → who asked

(schema/register! ::todo
  [:map {:seon.db/entity true}
   [::id ::id] [::title ::title] [::status ::status] [::agent ::agent]
   [::parent      {:optional true} ::parent]
   [::depends-on  {:optional true} ::depends-on]
   [::produced    {:optional true} ::produced]
   [::created-at ::created-at]
   [::completed-at {:optional true} ::completed-at]
   [::from {:optional true} ::from]])

;; --- the work-queue semantics: ONE rule set, plain data you can read and extend.

(def rules
  "Datalog rules over the two refs: descendant (the tree), leaf, open-work,
   blocked, ready (the queue). Shipped as data — read it to see how 'what's next'
   is computed; pass it as % in your own queries."
  '[[(descendant ?a ?n) [?n :my.todo/parent ?a]]
    [(descendant ?a ?n) [?m :my.todo/parent ?a] (descendant ?m ?n)]
    [(leaf ?t) (not-join [?t] [?c :my.todo/parent ?t])]
    [(open-work ?t) [?t :my.todo/status :open] (leaf ?t)]
    [(open-work ?t) (descendant ?t ?l) [?l :my.todo/status :open] (leaf ?l)]
    [(blocked ?t) [?t :my.todo/depends-on ?d] (open-work ?d)]
    [(ready ?t) [?t :my.todo/status :open] (leaf ?t) (not (blocked ?t))]])

;; --- request/response envelopes (own-ns ok?, shared :seon/error on failure).

(schema/register! ::ok? :seon.result/ok?)
(schema/register! ::write-response
  [:map [::ok? ::ok?] [::id {:optional true} ::id] [:seon/error {:optional true} :seon/error]])

(defn ^:async plan!
  "Author a WHOLE plan in one transact. :my.todo/children nests (each becomes a
   :my.todo/parent edge); :my.todo/ref labels a node; :my.todo/after names labels
   this node runs after (a depends-on edge). Links compile to string tempids, so
   :after may reference any label in the plan. Returns the label→id map."
  {:malli/schema [:=> [:cat ::plan-request] ::plan-response]
   :test (fn []
           (let [{:my.todo/keys [ok? ids root]}
                 (await (plan! {:my.todo/title "demo"
                                :my.todo/children
                                [{:my.todo/title "a" :my.todo/ref "a"}
                                 {:my.todo/title "b" :my.todo/after ["a"]}]}))]
             (is ok?)
             (is (string? root))
             ;; b is blocked until a is done — next never offers it:
             (is (= #{"a"} (set (map :my.todo/id (next {})))))))}
  [{:my.todo/keys [title children]}]
  (->> (in/compile-plan title children)        ; → flat tempid-keyed tx-data
       (hash-map :seon.db/tx-data) db/transact! await
       (in/plan-result title children)))

(defn ^:async add!
  "Mint one open node. :my.todo/parent and :my.todo/depends-on (lookup-refs) place
   it in the tree/DAG at birth; both default absent (a free-standing ready leaf)."
  {:malli/schema [:=> [:cat ::add-request] ::write-response]
   :test (fn []
           (let [{:my.todo/keys [id]} (await (add! {:my.todo/title "ship it"}))]
             (is (= :open (in/status-of id)))))}
  [m] (await (in/mint! m)))

(defn ^:async done!
  "Mark a leaf done (stamps :completed-at). Idempotent; unknown id → fail envelope.
   Completing a node may unblock its dependents — they appear in `next` next turn."
  {:malli/schema [:=> [:cat ::id-request] ::write-response]
   :test (fn []
           (let [{:my.todo/keys [id]} (await (add! {:my.todo/title "x"}))]
             (await (done! {:my.todo/id id}))
             (is (= :done (in/status-of id)))))}
  [{:my.todo/keys [id]}] (await (in/set-status! id :done)))

(defn ^:async depends!
  "Add dependency edge(s) to an EXISTING node — it now runs after each :on ref.
   Remove one with (db/transact! {:seon.db/tx-data [[:db/retract [:my.todo/id id]
   :my.todo/depends-on [:my.todo/id dep]]]})."
  {:malli/schema [:=> [:cat ::depends-request] ::write-response]
   :test (fn []
           (let [a (:my.todo/id (await (add! {:my.todo/title "a"})))
                 b (:my.todo/id (await (add! {:my.todo/title "b"})))]
             (await (depends! {:my.todo/id b :my.todo/on [[:my.todo/id a]]}))
             (is (not (some #{b} (map :my.todo/id (next {})))))))} ; b now blocked
  [{:my.todo/keys [id on]}] (await (in/add-deps! id on)))

(defn ^:async move!
  "Re-parent a node (retract its old :my.todo/parent, add the new one). Its
   identity, status, and deps are unchanged — only its place in the tree moves."
  {:malli/schema [:=> [:cat ::move-request] ::write-response]
   :test (fn []
           (let [p (:my.todo/root (await (plan! {:my.todo/title "p"})))
                 c (:my.todo/id   (await (add! {:my.todo/title "c"})))]
             (await (move! {:my.todo/id c :my.todo/parent [:my.todo/id p]}))
             (is (= p (in/parent-id-of c)))))}
  [{:my.todo/keys [id parent]}] (await (in/reparent! id parent)))

(defn ^:async drop!
  "Retract a node AND its whole subtree (parent is a plain ref ⇒ no cascade, so
   this walks descendants and retracts each). History keeps them — undo via
   db/as-of. Returns the count dropped."
  {:malli/schema [:=> [:cat ::id-request] ::drop-response]
   :test (fn []
           (let [{:my.todo/keys [root]}
                 (await (plan! {:my.todo/title "p"
                                :my.todo/children [{:my.todo/title "k"}]}))]
             (is (= 2 (:my.todo/dropped (await (drop! {:my.todo/id root})))))))}
  [{:my.todo/keys [id]}] (await (in/retract-subtree! id)))

(defn ^:async produced!
  "Link the KB rows this todo created (provenance). Makes \"what did processing
   file X yield?\" a one-hop pull and lets a synthesize step gather its inputs
   structurally."
  {:malli/schema [:=> [:cat ::produced-request] ::write-response]
   :test (fn []
           (let [t  (:my.todo/id (await (add! {:my.todo/title "t"})))
                 kb (await (db/transact! {:seon.db/tx-data
                              [{:my.kb.shared/id (db/new-id!)
                                :my.kb.shared/title "f" :my.kb.shared/body "…"}]}))]
             (is (:my.todo/ok? (await (produced!
                    {:my.todo/id t
                     :my.todo/kb [[:my.kb.shared/id (-> kb :seon.db/tempids vals first)]]}))))))}
  [{:my.todo/keys [id kb]}] (await (in/link-produced! id kb)))

(defn tree
  "The whole plan as nested EDN (children under each node, dep ids inline) +
   per-node {:done :total} roll-ups. {:my.todo/root? id} = one subtree;
   {:my.todo/all? true} = the forest. The read you re-plan over."
  {:malli/schema [:=> [:cat ::tree-request] ::tree-response]
   :test (fn []
           (let [{:my.todo/keys [root]}
                 (await (plan! {:my.todo/title "p"
                                :my.todo/children [{:my.todo/title "k"}]}))]
             (is (= 1 (count (:my.todo/_parent
                               (tree {:my.todo/root root})))))))}
  [m] (in/pull-tree m))

(defn next
  "Your focus queue: ready leaves (open, unblocked, real work), oldest first.
   The ONE thing to act on — blocked work is never offered, done work is gone."
  {:malli/schema [:=> [:cat :map] [:vector ::todo-ref]]
   :test (fn [] (is (vector? (next {}))))}
  [_] (in/ready-leaves))
```

What an agent learns from reading this whole ns: how to author a plan (`plan!`
`:test`), how sequencing blocks work (`depends!`/`plan!` `:test`s assert `next`
excludes blocked work), how to close and unblock (`done!`), how to restructure
(`move!`/`drop!`), how to read the structure (`tree`), and how the queue is
DERIVED (the `rules` def is right there, readable and extendable). The `:test`
examples ARE the worked manual — show, don't tell.

---

## 5. `my.kb` API + the file→KB workflow

### 5.1 `my.kb` — no CRUD facade; the API IS `seon.db`

There is deliberately no `remember!`/`recall`/`forget` wrapper: `remember!`
would just be `transact!` with a stamped `:my.kb/verified-at`, `recall` is
`query`, and a facade re-grows the memory-blob anti-pattern while hiding the
schema-design skill that IS the product (toolkit `my.kb`). So the self-teaching
`my.kb` ns is the global entity + shared provenance shapes + a worked DOMAIN
schema + `:test`s that show transact/query — it is also the DB manual.

```clojure
(ns my.kb
  "The global knowledge base — what every agent already knows, queryable as data.
   Rows carry NO agent ref, so the base is shared. There is no store/recall CRUD:
   you store with db/transact!, recall with db/query (or my.recall for meaning),
   and DESIGNING a my.kb.<domain> schema is the same skill as modeling the human's
   data. This ns also holds the worked db chains — it is your DB manual."
  (:require [seon.db :as db] [seon.schema :as schema]))

;; shared provenance — every kb row mixes domain attrs WITH these.
(schema/register! :my.kb/source-path :string)          ; where the fact came from
(schema/register! :my.kb/source-line :int)
(schema/register! :my.kb/verified-at :inst)
(schema/register! :my.kb/confidence  [:enum :extracted :inferred :verified])

;; the generic note (global: no agent ref exists on the entity).
(schema/register! :my.kb.shared/id    [:string {:seon.db/identity true}])
(schema/register! :my.kb.shared/title :string)
(schema/register! :my.kb.shared/body  :string)         ; markdown
(schema/register! :my.kb.shared
  [:map {:seon.db/entity true}
   [:my.kb.shared/id :my.kb.shared/id]
   [:my.kb.shared/title :my.kb.shared/title]
   [:my.kb.shared/body :my.kb.shared/body]])

;; a DOMAIN schema — model the data, don't dump it in a blob. Mixes its own
;; attrs with the shared :my.kb/* provenance. The :test shows store + recall.
(schema/register! :my.kb.research/id      [:string {:seon.db/identity true}])
(schema/register! :my.kb.research/claim   :string)
(schema/register! :my.kb.research/topic   :keyword)
(schema/register! :my.kb.research
  [:map {:seon.db/entity true}
   [:my.kb.research/id :my.kb.research/id]
   [:my.kb.research/claim :my.kb.research/claim]
   [:my.kb.research/topic :my.kb.research/topic]
   [:my.kb/source-path {:optional true} :my.kb/source-path]
   [:my.kb/confidence  {:optional true} :my.kb/confidence]
   [:my.kb/verified-at {:optional true} :my.kb/verified-at]])

(defn ^:async note!
  "Convenience: store a markdown note in the global KB (a thin transact! over
   :my.kb.shared). For domain facts, transact a :my.kb.<domain> row directly —
   that's the recall path (store-inventory + query find it by attribute)."
  {:malli/schema [:=> [:cat ::note-request] :seon.db/transact-response]
   :test (fn []
           (let [r (await (note! {:my.kb.shared/title "vendor X"
                                  :my.kb.shared/body "ships on LMDB"}))]
             (is (:seon.db/ok? r))
             (is (seq (db/query '[:find ?b :where [?e :my.kb.shared/body ?b]])))))}
  [{:my.kb.shared/keys [title body]}]
  (await (db/transact! {:seon.db/tx-data
                        [{:my.kb.shared/id (db/new-id!)
                          :my.kb.shared/title title :my.kb.shared/body body}]})))
```

The recall idiom (the manual the agent reads): `store-inventory` lists which
`:my.kb.*` attrs exist → `db/query` over them → `my.recall` when the match is by
MEANING not attribute.

### 5.2 The file→KB job — both namespaces, one plan

The owner's "process these files → store findings" is a `my.todo/plan!` whose
per-file leaves write `my.kb` rows and a synthesize leaf depends-on them all:

```clojure
;; 1. ONE declarative plan: a leaf per file + a synthesize leaf after them all.
(let [files (->> (await (my.files/list-dir {:seon.path/abs "/inbox"}))
                 :seon.items/items
                 (filter #(str/ends-with? (:seon.path/abs %) ".md")))]
  (await (my.todo/plan!
           {:my.todo/title "Process inbox → KB"
            :my.todo/children
            (conj (mapv (fn [f] {:my.todo/title (str "process " (:seon.path/abs f))
                                 :my.todo/ref   (:seon.path/abs f)})   ; label = path
                        files)
                  {:my.todo/title "synthesize findings"
                   :my.todo/after (mapv :seon.path/abs files)})})))   ; after ALL files
```

Then the loop runs itself off `next` — each per-file leaf is ready, the
synthesize leaf is blocked until every file is done:

```clojure
;; one ready file leaf this turn:
(let [{:my.todo/keys [id title]} (first (my.todo/next {}))
      path     (subs title (count "process "))
      content  (:my.files/content (await (my.files/read-file {:seon.path/abs path})))
      findings (extract-findings content)                  ; the agent's own pure fn
      rows     (mapv #(assoc % :my.kb.research/id (db/new-id!)
                               :my.kb/source-path path
                               :my.kb/confidence  :extracted
                               :my.kb/verified-at (js/Date.))
                     findings)
      {:seon.db/keys [tempids]} (await (db/transact! {:seon.db/tx-data rows}))]
  (await (my.todo/produced! {:my.todo/id id
                             :my.todo/kb (mapv #(vector :my.kb.research/id
                                                        (:my.kb.research/id %)) rows)}))
  (await (my.todo/done! {:my.todo/id id})))
```

When the LAST file closes, `(blocked synth)` flips false (no dep has open-work),
so `next` surfaces the synthesize leaf, which gathers its inputs by walking the
tree it already owns — ONE reverse-ref pull over `:my.todo/produced`:

```clojure
(->> (db/pull db '[{:my.todo/_parent [:my.todo/status {:my.todo/produced [*]}]}]
              [:my.todo/id parent-id])
     :my.todo/_parent (mapcat :my.todo/produced)   ; every KB row the leaves wrote
     synthesize→one-summary-row
     (hash-map :seon.db/tx-data) db/transact!)
```

The two provenance directions are complementary and each stored ONCE: the KB row
knows its FILE (`:my.kb/source-path`); the todo knows its KB OUTPUTS
(`:my.todo/produced`). Progress shows the whole time via the derived roll-up —
nothing stored, self-healing.

---

## 6. datahike grounding — the rule set + the limits hit honestly

### 6.1 Capabilities relied on (verified by `file:line`)

| capability | where (verified) | verdict |
|---|---|---|
| **nested map under a ref → reverse-ref injected to parent eid** | `explode` transaction.cljc:646-685, branch **665-667**; `maybe-wrap-multival` 625-644 | ✓ `{:my.todo/_parent […]}` builds the tree (any ref, component-agnostic) |
| **component-ness governs only cascade-retract, not writes** | `retract-components` transaction.cljc:730-733; `retract-entity` 897-914 | ✓ plain `:my.todo/parent` ⇒ no cascade; `drop!` walks the subtree |
| **string tempid links entities in one tx, order-independent** | `tempid?` 67-70; ref-value tempid branch 1198-1201; map `:db/id` tempid `entity-map->op-vec` 845-871 (line 864) | ✓ the shape `plan!` emits |
| **multival ref tempid elements** (`:depends-on ["t-a" "t-b"]`) | explode multival → each `[:db/add e :depends-on "t-x"]` → tempid branch 1198-1201 | ✓ |
| **lookup-ref resolves a PRIOR-tx entity** (`:my.todo/agent [:seon.agent/id …]`) | `entid` utils.cljc:109-129 (avet); `entid-strict` value-coerce transaction.cljc:701 | ✓ across-tx threading always resolves |
| **bridge: `[:vector :seon.db/ref]` → many; component only on `{:seon.db/component true}`** | `form->cardinality` internal.cljs:267-275; `malli->datahike-attr` 341-350; ref special-case 169-176 | ✓ both refs plain & navigable |
| **recursive reverse-ref pull `{:my.todo/_parent ...}`** | pull_api.cljc:146-147 (reverse multi), 158-161 (`recurse-attr`), 200-207 (reverse via avet) | ✓ whole subtree as nested EDN |
| **`db/transact!` returns `:seon.db/tempids`** (label→eid) | `seon.db` transact response (db.cljs) | ✓ `plan!` maps labels back |
| **`db/new-id!` (14-char id)** | db.cljs:291-308 | ✓ every `:my.todo/id` |

### 6.2 The one rule that decides the design — tempid vs same-tx lookup-ref

A lookup-ref `[:my.todo/id "x"]` used as a ref VALUE resolves through `entid` →
`(dbi/datoms db :avet [a v])` (utils.cljc:129) against the *running transient*
db, and `entid-strict` RAISES `:entity-id/missing` (utils.cljc:141-148) when the
target's identity datom is not yet asserted. Within ONE tx the entities are
processed sequentially, so a lookup-ref to a sibling born in the same tx resolves
ONLY if that sibling was processed earlier — order-dependent and fragile. A
**string tempid** has no such constraint (allocated-on-first-sight, resolved
whenever the `:db/id`-carrying entity appears). **Therefore: same-tx cross-links
use tempids; across-tx threading uses lookup-refs.** `plan!` is one tx → tempids;
`add!`/`depends!`/`move!` link to already-committed nodes → lookup-refs.

### 6.3 Limits hit (stated, not worked around)

- **Pure nested-map transact is TREE-ONLY.** It expresses the hierarchy
  elegantly but cannot carry a sibling→sibling `depends-on` (those cross the
  tree). The clean declarative shape for tree+DAG is the flat tempid-keyed vector
  (§2.2) — which is why `plan!` compiles to that, not to a nested map.
- **`:seon.db/order-by` is NOT a `seon.db/query` request key.**
  `seon.db/query`'s map-in form destructures only `::query`/`::args`/`::db`/
  `::conn` (db.cljs:586-610) and runs `(apply d/q query db args)` — an
  `:seon.db/order-by` sibling key is silently ignored. (The
  [[hierarchical-todo-deps-2026-06-27]] `next-ready`/`recent-done` samples that
  pass `:seon.db/order-by` are a BUG — corrected here.) Order in CLJS with
  `sort-by` (exactly as today's `open-todos` does, `agent/todo/internal.cljs:61`)
  — simpler and avoids the question of whether datahike's map-form `:order-by`
  even rides through the wrapper.
- **`seon.db/pull` filters a never-transacted registered attr** (db.cljs:885-899)
  — so `{:my.todo/_parent ...}` on a flat (parent-less) world returns no children
  rather than throwing. Graceful, but means the reverse recursion is inert until
  the first parent edge is written. (Not a bug — the desired degrade.)
- **`drop!` is a query+retract, not a cascade** (plain ref). The walk is O(subtree)
  — fine for plan-sized trees; flagged so the builder doesn't expect
  `retractEntity` to cascade.

## 7. Verdict — (a) vs (b) vs (c)

**(c) both, with this division of labour:**

- The **data-structure API (`plan!`)** is what the owner's "transact a nested
  structure, not N manual ops" dream becomes once datahike's grain is respected:
  the agent authors a plan as data, the verb compiles it to ONE tempid-linked,
  order-independent, ACID transact. It exploits exactly what datahike makes easy
  (tempid linking, the tempids response, the reverse-ref tree pull) and routes
  AROUND what it makes awkward (same-tx lookup-refs, DAG-via-nesting). This is
  genuine elegance, not a thin sugar.
- The **self-teaching namespace (verbs + docstrings + `:test`)** is what makes
  that API reachable. Shown in full every turn, with the `rules` data inline and
  a worked `:test` per verb, the ns teaches sequencing, blocking, restructuring,
  and the derived queue without a wall-of-text manual — show, don't tell.

Neither is sufficient alone: a powerful `plan!` the agent can't see is dead
weight; a beautiful set of examples over manual `add!`/`depends!`/thread-the-id
calls re-grows the boilerplate `plan!` exists to remove. Together — the `my.todo`
ns rendered whole (the action manual) + the live dashboard block (the focus +
progress, [[todo-dual-render-2026-06-27]]) — the agent has everything to drive
the planning system in all major respects: see the whole tree (`tree`), draw it
into context, bulk-author or restructure declaratively (`plan!`/`drop!`/`move!`),
make focused edits (`add!`/`depends!`/`done!`), and stay on rails (`next`).

The honest caveat the design surfaces rather than hides: the "pure nested map"
half of the dream is tree-only; the flat-tempid compiler is the load-bearing
mechanism, and `:seon.db/order-by` does not ride through the wrapper. Both are
corrected here, not papered over.

## Cross-links

- [[hierarchical-todo-deps-2026-06-27]] — the tree+dep model, the `rules`, the
  derived queries (`next-ready`/`rollup`); §2c/§2.2 `:seon.db/order-by` samples
  corrected in §6.3 here · [[todo-dual-render-2026-06-27]] — the live dashboard
  (focus + progress) this read API pairs with · [[toolkit]] (`my.todo`/`my.kb`
  verb budgets, the never-throw envelope) · [[data-model]] §5.2-5.3 (the
  `:my.todo/*` / `:my.kb.*` schemas) · [[library-grounding]] (work-in-the-grain).
