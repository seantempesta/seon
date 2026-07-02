---
type: research
status: active
tags: [research, agent, schema, database]
---

# Hierarchical + dependency-aware `my.todo` — datahike-native design

> Design-research for `feature/agent-fsm`. DESIGN ONLY. Present-tense (the
> system as it is when built). Every query claim is grounded in the vendored
> datahike source by `file:line`; the "datahike grounding" section at the end
> lists every capability relied on plus the limits hit.

## TL;DR — the design in six lines

1. **Two plain refs do everything**: `:my.todo/parent` (one → the TREE) and
   `:my.todo/depends-on` (cardinality-many → the DAG). Both PLAIN refs (no
   `:db/isComponent`) so the graph is navigated by QUERY, never owned/cascaded.
2. **Nothing derivable is stored**: no `:blocked?`, no progress counter, no
   `:kind`. `:my.todo/status :open|:done` is the only state, and only LEAVES
   carry meaningful status — a parent's done-ness and a todo's blocked-ness are
   pure Datalog over the two refs.
3. **One small rule set** (`my.todo/rules`) gives `descendant` (recursive),
   `leaf`, `open-work`, `blocked`, `actionable`, `ready` — the whole work-queue
   semantics. Recursive rules + reverse-ref recursive pull are both verified in
   the CLJS engine.
4. **Sequencing IS a dependency** (`do B after A` = `B depends-on A`), so the
   queue needs no priority/order attr — `next` surfaces ready leaves in
   created-at order and the agent can't pick blocked work.
5. **The whole forest reads in ONE recursive reverse-ref pull**
   (`{:my.todo/_parent ...}`) → a nested EDN tree with inline dep adjacency.
6. **File→KB workflow**: parent "process files" → per-file child leaves → a
   `synthesize` leaf `depends-on` all of them; each child reads a file
   (`my.files/read-file`), writes `my.kb` rows, and links them via
   `:my.todo/produced`; the synthesize step unblocks only when every file is
   done, and the parent's roll-up shows progress every turn.

The point (owner steer): keep a long-running agent FOCUSED. The agent's context
shows it exactly one thing — `next` (the ready leaf to work) — plus a
self-healing progress bar; it never re-reasons about what's left, what's blocked,
or where it was.

---

## 1. Schema (replaces `data-model §5.3`)

```clojure
;; ns my.todo — per-agent plan TREE + dependency DAG.
;; Shared shapes (:seon.db/id, :seon.db/ref) are REFERENCED, never inlined.

(schema/register! :my.todo/id          [:string {:seon.db/identity true}]) ; -> :seon.db/id length
(schema/register! :my.todo/title       [:string {:min 1}])
(schema/register! :my.todo/status      [:enum :open :done])     ; value enum (a flavor, §3-fine)
(schema/register! :my.todo/agent       :seon.db/ref)            ; -> owning agent  (the SCOPE ref)
(schema/register! :my.todo/parent      :seon.db/ref)            ; -> parent todo   (the TREE edge)
(schema/register! :my.todo/depends-on  [:vector :seon.db/ref])  ; -> prereq todos  (the DAG edges)
(schema/register! :my.todo/produced    [:vector :seon.db/ref])  ; -> kb entries it created (provenance)
(schema/register! :my.todo/created-at  :inst)
(schema/register! :my.todo/completed-at :inst)
(schema/register! :my.todo/from        :seon.db/ref)            ; -> who asked (user/agent)
(schema/register! :my.todo/message     :seon.db/ref)            ; -> the inbound message it tracks

(schema/register! :my.todo
  [:map {:seon.db/entity true}
   [:my.todo/id          :my.todo/id]
   [:my.todo/title       :my.todo/title]
   [:my.todo/status      :my.todo/status]
   [:my.todo/agent       :my.todo/agent]
   [:my.todo/parent      {:optional true} :my.todo/parent]
   [:my.todo/depends-on  {:optional true} :my.todo/depends-on]
   [:my.todo/produced    {:optional true} :my.todo/produced]
   [:my.todo/created-at  :my.todo/created-at]
   [:my.todo/completed-at {:optional true} :my.todo/completed-at]
   [:my.todo/from        {:optional true} :my.todo/from]
   [:my.todo/message     {:optional true} :my.todo/message]])
```

### Why each attr earns its place (the no-store-derivable audit)

| attr | facet (bridge-derived) | stored because… |
|---|---|---|
| `:my.todo/status` | keyword / one | the LEAF's own completion — the one irreducible state. A parent's status defaults `:open` and is never flipped by the agent (its done-ness is derived). |
| `:my.todo/agent` | ref / one | the scope ref; a relational fact, not derivable. |
| `:my.todo/parent` | ref / one / **plain** | the tree edge. PLAIN: a parent does NOT own a child's lifecycle, so retracting a parent does NOT cascade (contrast `:seon.agent/ctx`, a component). |
| `:my.todo/depends-on` | ref / **many** / **plain** | the DAG edges. `[:vector :seon.db/ref]` → cardinality-many; no `{:seon.db/component true}` → no cascade. Adding one edge is a single cardinality-many `:db/add`. |
| `:my.todo/produced` | ref / many / plain | todo→kb provenance: which KB entries this work produced. Not derivable (the KB row records the FILE it came from, not the todo). |
| `:my.todo/created-at` | inst / one | the natural queue order (sequencing otherwise rides on `depends-on`). |

| NOT an attr (DERIVED) | derivation |
|---|---|
| blocked? / ready? | `(blocked ?t)` / `(ready ?t)` rules over `:my.todo/depends-on` (§2b). |
| progress / roll-up | `descendant`-leaf count vs done-leaf count (§2a). No counter. |
| done-ness of a parent | "no open leaf in the subtree" (`open-work` rule, §2a). |
| kind (milestone vs action) | `leaf` rule — a todo with no children IS an action; one with children IS a milestone. Never a stored `:kind` (data-model §3). |

**Bridge facts (grounded).** `[:vector :seon.db/ref]` → `:db.cardinality/many`
(`form->cardinality`, `src/seon/db/internal.cljs:267-275`) on a `:db.type/ref`
(the `:seon.db/ref` special-case, `internal.cljs:169/219`); `:db/isComponent`
is set ONLY for `{:seon.db/component true}` (`internal.cljs:350`), which neither
ref carries — so both are **plain**. `retract-components`
(`reference-code/datahike/src/datahike/db/transaction.cljc:730`) only re-emits
`[:db.fn/retractEntity child]` for COMPONENT datoms, so retracting any todo
leaves its parent/deps/dependents untouched. The graph is read by query, not by
ownership — exactly the design intent.

---

## 2. Derived status — all Datalog, grounded per query

One shared rule set, seeded with the `my.todo` ns and passed as `%`:

```clojure
(def rules
  '[;; --- the TREE: transitive descendants over :my.todo/parent ----------
    [(descendant ?ancestor ?node)
     [?node :my.todo/parent ?ancestor]]                 ; base: a direct child
    [(descendant ?ancestor ?node)
     [?mid  :my.todo/parent ?ancestor]
     (descendant ?mid ?node)]                            ; step: transitive

    ;; --- a LEAF is a todo nothing names as parent -----------------------
    [(leaf ?t)
     (not-join [?t] [?child :my.todo/parent ?t])]

    ;; --- the open LEAVES under ?t, INCLUDING ?t itself when ?t is one ----
    [(open-leaf ?t ?l)
     [(ground ?t) ?l]                                    ; self
     [?l :my.todo/status :open]
     (leaf ?l)]
    [(open-leaf ?t ?l)
     (descendant ?t ?l)                                  ; a descendant leaf
     [?l :my.todo/status :open]
     (leaf ?l)]

    ;; --- ?t has unfinished work iff any open leaf sits in its subtree ----
    [(open-work ?t)
     (open-leaf ?t ?_l)]

    ;; --- ?t is BLOCKED iff some dependency still has open work -----------
    [(blocked ?t)
     [?t :my.todo/depends-on ?dep]
     (open-work ?dep)]

    ;; --- ACTIONABLE = an open leaf (real work, not a parent rollup) ------
    [(actionable ?t)
     [?t :my.todo/status :open]
     (leaf ?t)]

    ;; --- READY = actionable AND not blocked -----------------------------
    [(ready ?t)
     (actionable ?t)
     (not (blocked ?t))]])
```

### (a) Roll-up — progress = done-fraction of descendant leaves

```clojure
;; ONE query → [leaf-eid status] per descendant leaf; reduce in CLJS.
(defn rollup [db parent-id]
  (let [rows (db/query {:seon.db/db db
                        :seon.db/query
                        '[:find ?l ?s
                          :in $ % ?pid
                          :where
                          [?p :my.todo/id ?pid]
                          (descendant ?p ?l)
                          (leaf ?l)
                          [?l :my.todo/status ?s]]
                        :seon.db/args [rules parent-id]})
        total (count rows)
        done  (count (filter #(= :done (second %)) rows))]
    {:my.todo/done done :my.todo/total total
     :my.todo/done? (and (pos? total) (= done total))}))
```

- **Recursive `descendant`** is the engine's recursive-rule machinery. Legacy
  engine: `solve-rule` (`query.cljc:1231`) + `expand-rule` (`:1182`) +
  `rule-gen-guards` emitting `-differ?` cycle guards (`:1210-1215`), reached via
  `resolve-clause` (`:2183-2190`). Planner (loaded in CLJS,
  `query.cljc:30-32`): `execute-recursive-rule` — a semi-naive fixpoint with a
  `seen` set (`reference-code/datahike/src/datahike/query/execute.cljc:3017`;
  the CLJS seen-key is `(str (vec projected))`, `:2862`), so it is **cycle-safe
  even if the parent graph somehow cycled** (it can't; one parent per node).
- **`(count ?l)`** would also give the totals as a scalar aggregate (`count` in
  `built-in-aggregates`, `query.cljc:603`); the relation-find above is used
  because it carries both counts in one hit. **GOTCHA — do NOT use the
  collection find `[?s ...]` to count**: collection results are de-duplicated
  (`distinct-tuples`, `query.cljc:255-261`), so `[?s ...]` collapses to at most
  `{:open :done}`. Count over the RELATION (`?l ?s`) or use `(count ?l)`.
- "`:done` when all descendants done" = `(and (pos? total) (= done total))` — a
  parent with no open leaf in its subtree. No stored flag; complete a child and
  the fraction recomputes (self-healing).

### (b) Ready vs blocked

```clojure
(defn blocked? [db id]
  (boolean (seq (db/query {:seon.db/db db
                           :seon.db/query
                           '[:find ?t :in $ % ?id
                             :where [?t :my.todo/id ?id] (blocked ?t)]
                           :seon.db/args [rules id]}))))
```

`(blocked ?t)` is ONE hop over `:my.todo/depends-on` into `(open-work ?dep)`.
**Transitivity falls out of done-ness, not a transitive depends-on rule**: if
dep B is itself waiting on C, B simply isn't done (B has open work), so A is
blocked because B isn't done — no recursive walk of the DAG is needed, and so
there is **no DAG-cycle risk** in the blocked/ready path. Grounding: `not-join`
/ `not` are resolved by the legacy engine (`query.cljc:2586-2595`); `ground` =
`identity` is a built-in (`:552`); `leaf` leans on `not-join` (`:2586`).

### (c) "What should I do next" — the focus view

```clojure
;; Ready actionable leaves for the agent, oldest first — the one thing it acts on.
(defn next-ready [db agent-eid]
  (db/query {:seon.db/db db
             :seon.db/query
             '[:find ?id ?title ?created
               :in $ % ?a
               :where
               [?t :my.todo/agent ?a]
               (ready ?t)
               [?t :my.todo/id ?id]
               [?t :my.todo/title ?title]
               [?t :my.todo/created-at ?created]]
             :seon.db/args [rules agent-eid]
             :seon.db/order-by [[2 :asc]]}))   ; order-by col 2 (?created), asc
```

`:order-by` is a first-class query key (`query.cljc:102` extra-ks +
`parse-order-by` `:2828-2860`, accepts a column index or a `:find` var). This
single query IS the agent's work queue: only ready leaves, in order, blocked
work excluded automatically.

### (d) Critical path / dependency depth (optional — HONEST limit)

Datalog gives REACHABILITY, not longest-path. A transitive `depends-before`
rule (same recursive shape as `descendant`, cycle-safe via the same fixpoint
seen-set) returns the SET of all transitive prerequisites of a todo:

```clojure
[(depends-before ?t ?p) [?t :my.todo/depends-on ?p]]
[(depends-before ?t ?p) [?t :my.todo/depends-on ?m] (depends-before ?m ?p)]
```

But **datahike's aggregates cannot compute longest-path / per-path max** (the
`built-in-aggregates` set — `count/sum/min/max/avg/median/stddev/distinct`,
`query.cljc:567-603` — aggregate a flat column, not paths). So compute the
dependency DEPTH (critical-path length) in CLJS by memoized recursion over the
`depends-before` adjacency if ever needed. The work queue does NOT need it —
`ready` already paces the agent correctly — so it stays an optional add-on, not
a core verb.

---

## 3. Concise data representation — the whole forest in ONE pull

A todo's whole subtree + its dep adjacency in a single recursive reverse-ref
pull:

```clojure
(db/pull db
  '[:my.todo/id :my.todo/title :my.todo/status
    {:my.todo/_parent ...}                       ; recurse CHILDREN, unbounded
    {:my.todo/depends-on [:my.todo/id]}]         ; dep edges as id refs
  [:my.todo/id "plan-1"])
```

Returns a nested tree (children under each node) with inline dep ids — the agent
or a render fn reasons over structure, not N flat rows:

```clojure
{:my.todo/id "plan-1" :my.todo/title "Process inbox files" :my.todo/status :open
 :my.todo/_parent
 [{:my.todo/id "f-1"  :my.todo/title "Process notes-a.md" :my.todo/status :done}
  {:my.todo/id "f-2"  :my.todo/title "Process notes-b.md" :my.todo/status :open}
  {:my.todo/id "syn"  :my.todo/title "Synthesize findings" :my.todo/status :open
   :my.todo/depends-on [{:my.todo/id "f-1"} {:my.todo/id "f-2"}]}]}
```

Grounding (`reference-code/datahike/src/datahike/pull_api.cljc`):

- **Reverse-ref `:my.todo/_parent`** is the reverse navigation —
  `filter-reverse-attrs` (`:206`) + `expand-reverse-subpattern-frame` (`:209`)
  + `pull-expand-reverse-frame` (`:223`). It returns a VECTOR (multiple
  children): a reverse pull of a NON-component ref is multi
  (`multi? (if forward? (multival? …) (not component?))`, `:146-147`) — and
  `:my.todo/parent` is non-component, so children come back as a vector.
- **Unbounded recursion `...`** walks the whole subtree — `recurse-attr`
  (`:97`) + `push-recursion` depth tracking (`:47`); `{:my.todo/_parent 2}`
  bounds it to depth 2 if desired.
- **Cycle/limit safety**: a re-seen eid yields `{:db/id eid}` and stops
  (`seen-eid?`/`pull-seen-eid`, `:55-65`); each level is capped at
  `+default-limit+` 1000 children (`:15`). Todo trees are acyclic and far under
  1000-wide, so `...` terminates cleanly at the leaves.

To get the agent's whole FOREST, first find the roots (todos with no parent),
then pull each:

```clojure
'[:find [?id ...] :in $ ?a
  :where
  [?t :my.todo/agent ?a] [?t :my.todo/id ?id]
  (not-join [?t] [?t :my.todo/parent _])]      ; roots = no parent edge
```

---

## 4. Agent-facing verbs (`my.todo`) — small, threadable, never-throw

Thin over `seon.db`; every verb map-in/map-out, scoped by `:my.todo/agent` from
the `with-agent` scope (`seon.db/current-agent-id`), returns a `{:my.todo/ok?
…}` envelope or raw data, never throws (errors are values — toolkit shape #4).

| verb | in | out | note |
|---|---|---|---|
| `add!` | `{:title, :parent? <ref>, :depends-on? [<ref>…], :from?, :message?}` | `{:my.todo/ok? true :my.todo/id …}` | mint a node; `:parent`/`:depends-on` structure it AT creation. |
| `done!` | `{:id}` | `{:my.todo/ok? true :my.todo/id …}` | idempotent; stamps `:completed-at`; the in-place rename of today's `complete!`. |
| `reopen!` | `{:id}` | envelope | flips `:done`→`:open`; retracts `:completed-at` explicitly. |
| `depends!` | `{:id, :on [<ref>…]}` | envelope | add dep edge(s) to an EXISTING todo — one cardinality-many `:db/add`; the "easily structure dependencies" verb. (Remove = `[:db/retract id :my.todo/depends-on dep]`.) |
| `produced!` | `{:id, :kb [<ref>…]}` | envelope | link KB entries this todo created (provenance). |
| `next` | `{}` | `[{:my.todo/id :title :created-at} …]` | the focus queue: ready leaves, oldest first (§2c). |
| `tree` | `{:root? <id>, :all?}` | nested EDN forest (§3) + per-node `{:done :total}` roll-up | the one structural read for context/render. |
| `status` | `{:id}` | `{:id :done? :blocked? :ready? :progress {:done :total}}` | the derived view of one node (§2). |

Eight verbs. `add!` + `depends!` cover all structuring; `next` + `tree` +
`status` cover all reading; `done!`/`reopen!`/`produced!` cover state. No
predicate-verb sprawl — `blocked?`/`ready?` ride inside `status`/`next`.

```clojure
;; structuring is one-liners — sequencing is just a dependency:
(my.todo/add! {:my.todo/title "research vendor X"})              ; => {…/id "a"}
(my.todo/add! {:my.todo/title "write the brief"
               :my.todo/depends-on [[:my.todo/id "a"]]})         ; runs AFTER a
;; add a dep to something that already exists:
(my.todo/depends! {:my.todo/id "b" :my.todo/on [[:my.todo/id "a"]]})
```

`add!`'s `:depends-on`/`:parent` accept lookup-refs `[:my.todo/id "a"]` (a
valid `:seon.db/ref` value, the `[:tuple :keyword …]` arm — data-model §2.1), so
the id `add!` returns threads straight into the next `add!` with no reshape.

---

## 5. KB integration — the file → KB workflow that pushes the system

The ask: *"process these files → store findings in the KB."* This is exactly a
todo tree with one dependency edge, and it keeps the agent on rails across a long
job:

```clojure
;; 1. one parent (a milestone — it will have children, so `leaf` is false for it)
(let [p   (:my.todo/id (await (my.todo/add! {:my.todo/title "Process inbox → KB"})))
      ;; 2. one child LEAF per file (each ready immediately — no deps)
      kids (for [f (->> (my.files/list-dir {:seon.path/abs "/inbox"})
                        :seon.items/items
                        (filter #(str/ends-with? (:seon.path/abs %) ".md")))]
             (:my.todo/id
               (await (my.todo/add! {:my.todo/title (str "process " (:seon.path/abs f))
                                     :my.todo/parent [:my.todo/id p]}))))]
  ;; 3. a synthesize LEAF that depends-on EVERY per-file child
  (await (my.todo/add! {:my.todo/title       "synthesize findings"
                        :my.todo/parent      [:my.todo/id p]
                        :my.todo/depends-on  (mapv #(vector :my.todo/id %) kids)})))
```

Then the loop runs itself off `next`:

```clojure
;; each turn the agent calls (my.todo/next {}) → the ONE ready leaf to work.
;; A per-file leaf is ready (no deps); the synthesize leaf is BLOCKED until all
;; kids are done. Working one file:
(let [{:my.todo/keys [id]} (first (my.todo/next {}))           ; e.g. "process /inbox/notes-a.md"
      path     "/inbox/notes-a.md"
      content  (:my.files/content (await (my.files/read-file {:seon.path/abs path})))
      findings (extract-findings content)                       ; the agent's own pure fn
      tx       (mapv (fn [f] (merge f {:my.kb.research/id     (db/new-id!)
                                       :my.kb/source-path     path
                                       :my.kb/confidence      :extracted
                                       :my.kb/verified-at     (js/Date.)}))
                     findings)
      {:seon.db/keys [tempids]} (await (db/transact! {:seon.db/tx-data tx}))]
  ;; link the KB rows this todo produced, then close the leaf:
  (await (my.todo/produced! {:my.todo/id id :my.todo/kb (kb-refs tx)}))
  (await (my.todo/done! {:my.todo/id id})))
```

When the LAST per-file leaf closes, `(blocked synth)` flips false (no dep has
`open-work`), so `next` surfaces the synthesize leaf. Its work gathers its inputs
by walking the tree it already owns:

```clojure
;; synthesize reads everything its siblings produced — one reverse-ref pull:
(->> (db/pull db '[{:my.todo/_parent [:my.todo/status {:my.todo/produced [*]}]}]
              [:my.todo/id parent-id])
     :my.todo/_parent
     (mapcat :my.todo/produced)            ; every KB row the file-leaves wrote
     synthesize→one-summary-kb-row
     (hash-map :seon.db/tx-data)
     db/transact!)
```

The my.todo ↔ my.kb relationship made explicit:

- **`:my.todo/produced`** (todo → kb, plain cardinality-many ref) is the
  provenance edge: "this work yielded these facts." It makes "what did processing
  file X produce?" a one-hop pull and lets the synthesize step collect its inputs
  structurally instead of re-querying by path string.
- The KB row keeps its OWN provenance (`:my.kb/source-path`, `:my.kb/confidence`,
  `:my.kb/verified-at` — toolkit `my.kb`). The two directions are complementary:
  the KB row knows its FILE; the todo knows its KB OUTPUTS. Store one direction
  only (the todo→kb ref); the reverse is a `:my.todo/_produced` pull if ever
  needed.
- **Progress is visible the whole time**: the parent's roll-up (§2a) renders a
  `done/total` bar in the agent's context block every turn — purely derived, so
  completing a file advances it with nothing to store or clear (self-healing).
  This is the "keep the agent on track on a long task" payoff: one focus item
  (`next`) + one progress signal (`rollup`), both functions of the DB.

File-reading verb used: **`my.files/read-file`** (floor `seon.agent.fs`,
allowlist-gated; takes a `:seon.path/abs` / `:seon.path/located`); discovery via
`my.files/list-dir`/`walk-dir` or `my.search/grep` (toolkit §my.files/my.search).

---

## 6. Migration note (for the builder — roadmap Phase 6; NOT done here)

In-place rename, no parallel system, fresh world (no data port — the store is
wiped on `bin/seon cluster reset`, so old `:seon.agent.todo` rows simply don't
exist in a reset cluster — memory: "no porting old data in refactors").

1. **Rename the ns** `seon.agent.todo` → `my.todo` (+ `.internal`) and rewrite
   every `:seon.agent.todo/*` attr → `:my.todo/*`, with `owner` → `agent`. The
   live edits today: `src/seon/agent/todo.cljs` (the verbs + register! calls),
   `src/seon/agent/todo/internal.cljs` (the open-todos query + render block), and
   the two consumers below.
2. **Add the new shape**: register `:my.todo/parent`, `:my.todo/depends-on`,
   `:my.todo/produced`; add the shared `my.todo/rules` def and the derived verbs
   (`next`/`tree`/`status`/`depends!`/`produced!`/`rollup`). `complete!` → `done!`
   in place.
3. **Repoint `seon.derive`**: `open-todo-count` (`src/seon/derive.cljs:184-195`)
   queries `:seon.agent.todo/owner`/`status` → change to `:my.todo/agent`/
   `:my.todo/status`. Consider adding a `ready-count` (the focus number) using
   `rules`.
4. **Rewrite the render block** (`todo/internal.cljs:72-103`,
   `open-todos-block`): from a flat open-list to the `tree` + roll-up view (the
   plan tree with a derived progress line per parent and the `next` item
   highlighted). Keep the "vanishes when empty" property (derived, nothing
   stored).
5. **Seed as a `:toolkit-seed` worked-example** (toolkit §two-tiers): the
   `my.todo` register!/rules/verb forms are the editable seed; the engine floor
   (`seon.db`) stays `:core-seed`. The `*.internal` suffix keeps the plumbing out
   of rendered context (`hidden-ns-name?`).

The rule var `my.todo/rules` is plain data shipped with the seed, so an agent can
read it (it's part of the rendered `my.todo` source) and even extend it.

---

## datahike grounding — every capability + its source line

| capability relied on | where it lives (verified) | verdict |
|---|---|---|
| **plain ref vs component** (no cascade on `parent`/`depends-on`) | bridge `src/seon/db/internal.cljs:344-350` (`:db/isComponent` only on `{:seon.db/component true}`); `transaction.cljc:730` (`retract-components` cascades COMPONENT datoms only) | ✓ both refs plain → query-navigated, never owned/cascaded |
| **cardinality-many ref** for `depends-on`/`produced` | `internal.cljs:267-275` (`form->cardinality`: `[:vector …]` → many) on the `:seon.db/ref` special-case (`:169/219`) | ✓ one `:db/add` appends an edge |
| **lookup-ref as a ref value** (`[:my.todo/id "a"]`) | data-model §2.1; resolved by `entid-strict` (`db/utils.cljc:141`) | ✓ ids thread between verbs with no eid lookup |
| **recursive rule** (`descendant`, transitive closure) | legacy `solve-rule` `query.cljc:1231` + `expand-rule` `:1182` + `-differ?` cycle guards `rule-gen-guards :1210-1215`, via `resolve-clause :2183-2190`; planner `execute-recursive-rule` semi-naive fixpoint `query/execute.cljc:3017` (CLJS seen-key `:2862`) | ✓ works in CLJS; cycle-safe via the fixpoint seen-set |
| **planner runs in CLJS** (so either engine resolves rules) | planner modules required for `:cljs` `query.cljc:30-32`; `planner-eligible-db?` accepts a plain `DB` `:3463-3471`; `*force-legacy*` defaults false in CLJS `:57-62` | ✓ the pod's local db value is a `DB` → planner eligible |
| **`not` / `not-join`** (`leaf`, roots, `ready`) | legacy resolution `query.cljc:2586-2595`; `subtract-rel` negation `:906-916` | ✓ |
| **`ground` / `identity`** (bind self in `open-leaf`) | built-ins `query.cljc:547,552` | ✓ |
| **aggregates** `count` (roll-up totals) | `built-in-aggregates` `query.cljc:567-603` | ✓ |
| **recursive reverse-ref pull** `{:my.todo/_parent ...}` | `pull_api.cljc:206` (`filter-reverse-attrs`), `:209/223` (reverse expand), `:97` (`recurse-attr`), reverse-ref is multi `:146-147` | ✓ whole subtree as nested EDN |
| **pull cycle/limit guards** | `seen-eid?`/`pull-seen-eid` `pull_api.cljc:55-65`; `+default-limit+` 1000 `:15` | ✓ (trees acyclic & < 1000-wide anyway) |
| **`:order-by`** (the `next` queue order) | `query.cljc:102` (extra-ks) + `parse-order-by :2828-2860` | ✓ |

### Limits hit (stated honestly)

- **Collection-find dedups** — `:find [?s ...]` collapses duplicates
  (`distinct-tuples`, `query.cljc:255-261`), so it CANNOT be used to count by
  status. Count over the relation (`?l ?s`) or via `(count ?l)`. (Reflected in
  §2a.)
- **No longest-path / per-path aggregate** — datahike aggregates flatten a
  column (`query.cljc:567-603`); critical-path DEPTH (§2d) must be computed in
  CLJS over the `depends-before` adjacency. Reachability (the dep SET) IS pure
  Datalog; longest-path is not.
- **`get-else` rejects a nil default** (`-get-else`, `query.cljc:408-411`) —
  irrelevant to this design (no `get-else` used), but noted so the builder
  doesn't reach for it with a nil fallback.
- **No DAG-cycle risk in the work-queue path** — `blocked`/`ready` are one-hop
  over `depends-on` (transitivity rides on done-ness, not a recursive DAG walk),
  so a malformed dep cycle cannot wedge the queue; only the optional
  `depends-before` rule (§2d) walks the DAG, and the engine's fixpoint seen-set
  makes even that cycle-safe.

## Cross-links

- [[data-model]] §5.3 (the schema this replaces), §2 (ref kinds), §3
  (kind-by-presence) · [[toolkit]] (`my.todo`/`my.kb`/`my.files` verbs) ·
  [[library-grounding]] (the datahike/bridge read-first map) · [[roadmap]]
  Phase 6 (the migration slot).
