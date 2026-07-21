---
type: research
status: active
tags: [research, agent]
---

# `my.*` utils audit + minimal composable-building-block proposal

> Audit-then-propose. READ-ONLY survey of the live `my.*` surface and the 14 gym
> scenarios, then a minimal, justified set of composable `my.*` data-processing
> verbs. No implementation here — the proposal hands off to Core (schema + verb)
> and the UI/context lane (worked example + gym scenario).

## TL;DR

- **The real, recurring data-processing pain in the gym is aggregation over
  stored rows** — `SUM` (x1=101, x3=106), `MAX`/argmax ("which single one costs
  the most" → Adobe), and `group-by-then-sum` (x3 dining filter, `source-stats`
  topic-counts). Agents must hand-roll these as raw datalog aggregates, straight
  into two documented footguns: the `(sum ?r)` **dedup collapse** (needs `:with
  ?e` — `my.kb/source-stats` literally warns about it at `src/my/kb.cljs:170-180`)
  and the **argmax value-vs-entity trap** (`(max ?c)` gives you `45`, not the
  Adobe row, so you need a second join). This hand-rolled wrangling is the
  "top source of REPL errors" the owner wants to remove.
- **Nothing reusable exists for it.** The ONLY aggregation example on the agent
  surface is `my.kb/source-stats` — and it is hardcoded to the sample
  `:my.kb.source/*` domain, a recipe to *copy* (and re-derive the footguns), not a
  generic verb to *call*.
- **Proposal (matches the owner's lean — ONE composable pipeline util + a few
  named reductions):** a small always-on `my.data` ns with **one composition root**
  `rows` (attribute-presence → a vector of self-describing entity maps) and **three
  named reductions over those maps** — `sum-by`, `max-by` (returns the ROW, killing
  the argmax trap), `group-sum`. Pulling to MAPS first makes the `:with` dedup
  footgun structurally impossible (Clojure `reduce` doesn't dedup), and every verb
  is sync + map-in/map-out so it threads with plain `filter`/`map`. This is
  4 small verbs, not a CRUD facade — it expresses the analyses datalog makes
  error-prone, exactly the `my.recall`-vs-CRUD justification in [[toolkit]].
- **Refactor, don't fork:** rewrite `my.kb/source-stats` to call these verbs (kills
  the domain-specific aggregation + proves composability) — the "don't be a dumbass"
  rule, not a `source-stats-v2`.
- **Composition scenario `x-category-argmax`:** "Which spending category is my
  biggest, and how much did I spend there?" — unpassable without composing
  `rows` → `group-sum` → `max-by` (3 pieces). A single guessed number can't land
  the exact biggest-category + its exact total.

---

## Audit 1 — the existing `my.*` surface

### What actually exists in `src/my/` (live code)

| ns | file | LOC | what it is | always-on? |
|---|---|---|---|---|
| `my.kb` | `src/my/kb.cljs` | 228 | The global KB manual: shared `:my.kb/*` provenance schema + **runnable recipes** (`remember-sources!`, `titles`, `title+rating`, `titles-by-author`, **`source-stats`**, `source-detail`, `inventory`, `build-kb-example!`). Exercised by `my.kb-test` so the recipes can't bit-rot. | **YES — renders FULL every turn** (the `my.*` rule, see Audit 4) |
| `my.kb.shared` | `src/my/kb/shared.cljs` | 111 | Cluster-wide standing instructions singleton (`:my.kb.shared/*`); `instructions` + `instructions-block` render fn. | YES — full |
| `my.skills` | `src/my/skills.cljs` | 357 | Loadable-skills system: `load`/`unload`/`list` over `:seon.agent.ctx` blocks + the boot SKILL.md corpus scan + `catalog-block`/`skill-block` renders. | YES — full (the verbs); skill BODIES are loadable on demand |

That is the **entire** real `my.*` surface today. The catalog in [[toolkit]]
(`my.files`, `my.search`, `my.shell`, `my.todo`, `my.test`, `my.code`,
`my.schedule`, `my.recall`, `my.canvas`, `my.blob`, `my.agent`) is a **TARGET
design** — those namespaces are **not yet renamed/built**. The live agent verbs
the scenarios actually call still live under `seon.agent.*` and `seon.*`:

| target `my.*` (toolkit) | live ns today | scenarios that use it |
|---|---|---|
| `my.todo` | `seon.agent.todo` (`add!`/`plan!`/`done!`/`reopen!`/`depends!`/`move!`/`drop!`/`next`/`tree`/`status`/`list-open`) | todo-multistep, todo-resume |
| `my.files` | `seon.agent.fs` | consults-findings (A reads files) |
| `my.search` | `seon.agent.search` | consults-findings, s32 |
| `my.test` | `seon.test.runner` | todo-multistep, todo-prompt-thin |
| `db` / schema | `seon.db`, `seon.schema` (aliased `db`) | every store/aggregate scenario |
| `message` | `seon.agent.message` (`message/user`) | every reply scenario |

So the action surface (files/search/todo/test/message/db) is **present**; what is
**absent everywhere** is a **data-processing / aggregation** surface. `my.kb` is
the closest thing (it carries the worked db chains), and its `source-stats` is the
single aggregation worked-example — domain-locked and footgun-laden.

### The one existing aggregation example (`my.kb/source-stats`, `src/my/kb.cljs:169-180`)

```clojure
(defn source-stats []
  {::count        (or (db/query '[:find (count ?e) . :where [?e :my.kb.source/id]]) 0)
   ::rating-total (or (db/query '[:find (sum ?r) . :with ?e         ; <-- :with footgun
                                  :where [?e :my.kb.source/rating ?r]]) 0)
   ::topic-counts (into {} (db/query '[:find ?topic (count ?e)      ; <-- group-by-count
                                       :where [?e :my.kb.source/topics ?topic]]))})
```

Its own docstring is the smoking gun: *"an aggregate runs over the DEDUPLICATED
projected tuples, so `(sum ?r)` alone collapses two sources rated 5 into one;
`:with ?e` keeps each entity's row distinct."* Every agent doing x1/x3 must
re-learn this. There is no generic `sum-by`/`max-by`/`group-sum` to call — and no
`max-by`/argmax example **at all**, even though x1 demands one.

---

## Audit 2 — what the 14 gym scenarios require the agent to DO

`test/seon/gym/scenarios/*.edn`. The data-processing shape each scenario forces,
and whether a `my.*` util exists for it.

| scenario | core data-processing shape | exists as a `my.*` util? |
|---|---|---|
| **x1-subscriptions-total-and-max** | `rows(:my.subscription/*)` → **SUM** monthly (=101) **+ argMAX** ("most expensive" = Adobe @45, the **ENTITY** not the value) | **NO** — hand-rolled `(sum ?c)`+`:with`; argmax has NO example anywhere |
| **x3-expense-reuse-and-category-total** | `rows(:my.expense/*)` → **filter** to `:dining` → **SUM** (=106) | **NO** — hand-rolled filter+sum; the dedup footgun bites |
| **x12-narrow-question-no-over-retrieval** | look up ONE entity by attr value; do NOT bulk-pull off-kind | partial — `db/entity`/`db/query` exist; precision is behavioral |
| **todo-multistep-tracking** | mint ≥2 todos, complete them; design schema; write deftest | YES — `seon.agent.todo` (`add!`/`done!`) + `seon.test.runner` |
| **todo-resume** | author ns+schema+fn+test+row+todo; survive restart; reuse fn | YES (verbs) — but no aggregation involved |
| **finding-storage-shape** | design `my.kb.<domain>` schema + shared `:my.kb/*` provenance; store | YES — `my.kb` is the worked example |
| **s21-log-workout-existing-schema** | add ONE row reusing established `:my.workout/*`; no fork | YES — `db/transact!` |
| **err-recovery-unregistered-attr** | `register!` new attrs + transact onto an existing entity | YES — `seon.schema`/`db` |
| **s01-stub-pipeline-smoke** | reply + park (`wait`) | YES — `message`/`lifecycle` |
| **todo-prompt-thin** (3 `:todo` sub-scenarios) | meters→miles fn + test; REUSE a stored fn; store unprompted | partial — `my.test` exists; fn-reuse never observed live |
| **envelope-honesty** | read the `{:seon.db/ok? false}` error envelope as a value | YES — `db/transact!` contract |
| **blank-message-refusal** | read the message-refusal envelope | YES — `message!` contract |
| **s32-consult-before-research** | `query`/`pull` stored findings, reply (no re-derive) | YES — `db` + `my.kb` |
| **consults-findings-run8** (S-12) | A: grep repo → store findings w/ provenance; B: `query` them first | YES (store/query) — no aggregation |

### The distilled gap

The store/retrieve/plan/test/reply legs are **covered**. The **aggregation /
analysis leg is not**, and it is exactly the leg with the worst hand-roll
error-rate:

- **SUM over a kind** — x1, x3, `source-stats`. Footgun: datalog `:with` dedup.
- **argMAX / "which one is biggest"** — x1. Footgun: `(max ?c)` returns the *value*
  (45), not the Adobe row — you must join the value back to find the name. **Zero
  worked examples exist for this**, and it is a named requirement of a live paid
  scenario.
- **group-by → aggregate** — x3 (filter→sum by category), `source-stats`
  (topic-counts). Footgun: `(into {} (db/query …))` shape juggling.
- **filter a kind then aggregate** — x3 dining-only total.

Every one of these is "turn stored rows into the number the human asked for" — and
every one is currently a from-scratch datalog aggregate with a documented trap.

---

## Audit 3 — the intended catalog ([[toolkit]] / [[data-model]])

- **The composability rule** ([[toolkit]] §"composability backbone"): *the output
  of one verb is a valid input to the next, with no reshaping at the arrow.* Four
  shared shapes carry threading: `:seon.path/*`, `:seon.db/ref`, `:seon.items/*`
  (self-describing collections), and the `<ns>/ok?` + `:seon/error` envelope. The
  **worked chain** there is search→files→transform→transact — i.e. it threads
  *located items*, but it stops at `db/transact!`. **There is no worked
  read→aggregate→answer chain** in the catalog, which is precisely the gym's x1/x3
  shape.
- **`my.kb` is deliberately CRUD-free** ([[toolkit]] §my.kb): "*there is
  deliberately no `remember!`/`recall`/`forget` CRUD facade … `recall` is
  `query`.*" — the bar a new util must clear: it must express something **datalog
  makes error-prone**, not re-wrap `query`. `my.recall` (semantic KNN) clears it
  by expressing nearest-by-meaning. **Aggregation clears the same bar**: `sum-by`
  /`max-by`/`group-sum` over pulled maps express the analyses datalog's
  dedup-and-value-not-entity semantics make a trap — they are NOT a `query`
  re-wrap.
- **`:seon.items/*`** ([[toolkit]] §3) is the existing "self-describing
  collection" shape — a vector of maps + count + truncated?. A `rows` loader's
  output IS a `:seon.items/*` envelope, so it threads into the catalog with no new
  shape.
- **No `:kind`** ([[data-model]] §3): a util must find rows by **attribute
  presence**, never a stored kind. `rows` takes the attribute keyword and scans its
  index — the canonical presence query.
- **Global vs per-agent is the data's ref** ([[data-model]] §5.1): aggregation
  verbs are scope-agnostic — they operate over whatever rows `rows`/a filter hands
  them, so they work for global `my.kb` and per-agent `my.todo` identically.

---

## Proposal — `my.data`: one composition root + three named reductions

A single small **always-on** namespace, `my.data` (≈700 tok rendered full, same
budget class as `my.kb`). It is the **read→aggregate→answer** worked example the
catalog is missing — the analysis half of the composability story, paralleling the
search→transform→write half already in [[toolkit]].

Why a new ns and not "fold into `my.kb`": `my.kb` is the *knowledge base + DB
manual* (provenance shapes, KB recipes). Generic aggregation is computation over
*any* `:my.<domain>/*` data, not knowledge — a distinct concern. But the two are
adjacent, so **`my.kb/source-stats` is REFACTORED to call `my.data`** (proving the
verbs + deleting the duplicated, footgun-laden aggregation — the in-place fix, not
a fork). *(Alternative considered: put the 4 verbs in `my.kb` since it is already
the DB manual. Rejected — it muddies "knowledge" with "computation" and grows the
always-on `my.kb` budget. Flagging for the owner to confirm the ns boundary.)*

All four verbs are **sync** (db auto-injected, reads only — no `^:async`, no
Promise to trip on) and **map-in/map-out** with fully-namespaced keys.

### Verb 1 — `rows` (the composition root)

```clojure
(schema/register! ::attr :keyword)                ; the presence attr to scan
(schema/register! ::rows-request  [:map [:my.data/attr ::attr]])
(schema/register! ::rows-response                  ; IS a :seon.items/* envelope
  [:map [:my.data/ok? :seon.result/ok?]
        [:seon.items/items [:vector :map]]
        [:seon.items/count :int]])

(defn rows
  "Every entity carrying ATTR, pulled to a vector of self-describing maps —
   attribute-presence as DATA. The root of every analysis pipeline: once rows are
   MAPS, you reduce with plain Clojure (no datalog :with dedup footgun)."
  {:malli/schema [:=> [:cat ::rows-request] ::rows-response]}
  [{:my.data/keys [attr]}] …)  ; pull [*] for each ?e where [?e attr _]
```

`(my.data/rows {:my.data/attr :my.subscription/name})` → `{:my.data/ok? true
:seon.items/items [{:my.subscription/name "Netflix" :my.subscription/monthly-usd 18 …} …]
:seon.items/count 5}`. **Why always-on:** it is the entry point to every pipeline
and the canonical "find by attribute presence → data" move; demonstrating it once
teaches the whole pattern. Its output is a `:seon.items/*` envelope, so it threads
into existing catalog verbs with zero rekey, and into plain `filter`/`map`.

### Verb 2 — `sum-by`

```clojure
(schema/register! ::agg-key :keyword)
(schema/register! ::sum-by-request
  [:map [:my.data/items [:vector :map]] [:my.data/key ::agg-key]])
(defn sum-by
  "Total of KEY across the given item maps. Operates on MAPS, so the datalog
   (sum ?x)/:with dedup collapse cannot happen — two rows of 5 stay 5+5."
  {:malli/schema [:=> [:cat ::sum-by-request] :int]}
  [{:my.data/keys [items key]}] (reduce + 0 (keep key items)))
```

`(my.data/sum-by {:my.data/items (:seon.items/items subs) :my.data/key :my.subscription/monthly-usd})`
→ `101`. **Why it earns its place:** x1 + x3 both need it; it is the verb that
makes the `:with` footgun *unreachable* by construction.

### Verb 3 — `max-by` (kills the argmax trap)

```clojure
(schema/register! ::max-by-request
  [:map [:my.data/items [:vector :map]] [:my.data/key ::agg-key]])
(defn max-by
  "The item MAP whose KEY is largest (min-by is the dual) — returns the ENTITY,
   not the value, so 'which one is biggest' is one call, not a (max ?x)+rejoin."
  {:malli/schema [:=> [:cat ::max-by-request] [:maybe :map]]}
  [{:my.data/keys [items key]}] (when (seq items) (apply max-key key items)))
```

`(my.data/max-by {:my.data/items (:seon.items/items subs) :my.data/key :my.subscription/monthly-usd})`
→ `{:my.subscription/name "Adobe CC" :my.subscription/monthly-usd 45 …}`. **Why it
earns its place:** x1 *requires* the most-expensive ENTITY (Adobe), and the
value-not-entity trap is the single nastiest hand-roll in the gym with **no
existing example**. Returning the row means the agent reads `:my.subscription/name`
off it directly.

### Verb 4 — `group-sum`

```clojure
(schema/register! ::group-key :keyword)
(schema/register! ::group-sum-request
  [:map [:my.data/items [:vector :map]] [:my.data/group ::group-key] [:my.data/key ::agg-key]])
(defn group-sum
  "Sum KEY within each distinct value of GROUP → {group-value total}. The reusable
   generalization of source-stats' topic-counts and x3's dining filter+sum."
  {:malli/schema [:=> [:cat ::group-sum-request] [:map-of :any :int]]}
  [{:my.data/keys [items group key]}]
  (reduce (fn [m it] (update m (group it) (fnil + 0) (or (key it) 0))) {} items))
```

`(my.data/group-sum {:my.data/items (:seon.items/items exp) :my.data/group :my.expense/category :my.data/key :my.expense/amount-usd})`
→ `{:dining 106 :transport 40 :groceries 73}`. **Why it earns its place:** x3 and
`source-stats` both need group-then-aggregate; and it is the second half of the
composition scenario (group, then `max-by` over the groups).

### How they compose (the worked chain — the read→answer mirror of toolkit's write chain)

```clojure
;; x1 in two threaded calls — no datalog aggregate, no footgun:
(let [subs (:seon.items/items (rows {:my.data/attr :my.subscription/name}))]
  {:total    (sum-by {:my.data/items subs :my.data/key :my.subscription/monthly-usd})   ; 101
   :priciest (:my.subscription/name
              (max-by {:my.data/items subs :my.data/key :my.subscription/monthly-usd}))}) ; "Adobe CC"

;; x3 — rows → plain filter → sum-by:
(-> (rows {:my.data/attr :my.expense/amount-usd}) :seon.items/items
    (->> (filter #(= :dining (:my.expense/category %))))
    (->> (hash-map :my.data/items) (merge {:my.data/key :my.expense/amount-usd}))
    sum-by)  ; 106
```

Every arrow is total: `rows` emits maps, `filter`/`map` keep them maps, the
reductions consume maps. The agent never writes a datalog aggregate.

### Lane split

| piece | lane | why |
|---|---|---|
| `my.data` ns: the 4 verb `defn`s + `:malli/schema` + `schema/register!` of `:my.data/*` and the `::*-request`/`::*-response` shapes | **Core** | [[agent-fsm/CLAUDE.md]]: Core owns "the `my.*` schemas" and instrumented verbs; reuse `:seon.items/*` + `:seon.result/ok?` shapes (register-once) |
| `:my.data` wired into the namespaces render (full vs signature) via #42 | **Core** | it is a `my.*` ns, so `full-source-ns?` already renders it FULL (`src/seon/agent/ctx/namespaces.cljs:152-172`); #42's config-driven trim must include it |
| Refactor `my.kb/source-stats` to call `my.data` + update its docstring | **Core** | in-place fix of the duplicated aggregation (own the `my.*` schemas/code) |
| `my.data-test` (recipes-as-tests, the `my.kb` pattern) | **Core** | colocated `{:test …}`/deftest so the verbs can't bit-rot |
| The worked-example prose / guidance (when to reach for `rows`→reduce) | **UI/context** | owns the always-on context content + the agent-facing manual; fold a one-line "aggregate via my.data, never a raw (sum ?x)" pointer into the always-on base |
| The composition gym scenario (below) | **UI/context** | owns `test/seon/gym/scenarios/` |

---

## The composition scenario — `x-category-argmax`

A single paid scenario that **cannot pass without composing 3 pieces**
(`rows` → `group-sum` → `max-by`). It is x3's store reused, with a question that
forces a per-group aggregate THEN an argmax over the groups — neither a single
`sum-by` nor a single `max-by` suffices.

```clojure
;; test/seon/gym/scenarios/x-category-argmax.edn  (sketch)
{:seon.gym.scenario/id     :x-category-argmax
 :seon.gym.scenario/doc    "A logs expenses across categories (new :my.expense/*). Fresh B must DISCOVER the kind, sum per category, and name the single BIGGEST category AND its total — group-then-argmax, unpassable without composing rows→group-sum→max-by."
 :seon.gym.scenario/tier   :paid
 :seon.gym.scenario/status :active
 :seon.gym.scenario/axes   [:consults-findings :replies-honestly :terminates]
 :seon.gym.scenario/schema-registrations
 [[:my.expense/merchant :string]
  [:my.expense/amount-usd :int]
  [:my.expense/category :keyword]]
 :seon.gym.scenario/fixtures   ; dining=106, groceries=73, transport=40  → biggest = dining @106
 [{:my.expense/merchant "Thai Place"   :my.expense/amount-usd 28 :my.expense/category :dining}
  {:my.expense/merchant "Sushi Bar"    :my.expense/amount-usd 52 :my.expense/category :dining}
  {:my.expense/merchant "Cafe Luna"    :my.expense/amount-usd 26 :my.expense/category :dining}
  {:my.expense/merchant "Trader Joe's" :my.expense/amount-usd 73 :my.expense/category :groceries}
  {:my.expense/merchant "Shell"        :my.expense/amount-usd 40 :my.expense/category :transport}]
 :seon.gym.scenario/turns
 [{:seon.gym.turn/agent :a :seon.gym.turn/message "Log my spending: $28 Thai, $52 sushi, $26 cafe (all eating out); $73 groceries; $40 gas."}
  {:seon.gym.turn/agent :b :seon.gym.turn/message "Which kind of spending is my biggest, and how much did it come to?"}]
 :seon.gym.scenario/predicates
 [;; mechanical: B read the store, used the aggregation surface, replied, idle.
  {:seon.gym.predicate/id :b-discovery-reads-store-first
   :seon.gym.predicate/kind :first-eval-matches :seon.gym.predicate/agent :b
   :seon.gym.predicate/axis :consults-findings
   :seon.gym.predicate/pattern "\\bdb/(query|pull|entity|store-inventory)|\\bdata/(rows|group-sum|max-by)"}
  {:seon.gym.predicate/id :b-used-the-aggregation-surface  ; evidence of composing, not asserting a string
   :seon.gym.predicate/kind :eval-count-matching :seon.gym.predicate/agent :b
   :seon.gym.predicate/axis :consults-findings
   :seon.gym.predicate/pattern "data/group-sum|group-by|:my\\.expense/category"
   :seon.gym.predicate/expect [:count>= 1]}
  ;; …the standard :b-replied-to-the-user + :both-agents-end-idle datalog legs (as in x3)…
  ;; judged answer (the only place the computed values live):
  {:seon.gym.predicate/id :judge-biggest-category
   :seon.gym.predicate/kind :llm-judge :seon.gym.predicate/agent :b
   :seon.gym.predicate/axis :replies-honestly
   :seon.gym.predicate/rubric "PASS only if the reply names DINING (eating out) as the biggest category AND states its total as 106 USD (allow 105-107). FAIL if it names another category, omits the total, or reports a wrong number."
   :seon.gym.predicate/reference "Per-category totals: dining 106 (28+52+26), groceries 73, transport 40. Biggest = dining @106 — knowable ONLY by grouping the stored rows by category, summing each, and taking the argmax. A single guessed number cannot land the exact biggest-category AND its exact total."}]}
```

**Why it forces composition:** the answer is a *per-group* sum (so a flat `sum-by`
gives 219, wrong) followed by an *argmax over the groups* (so a flat `max-by` over
rows gives the $73 Trader Joe's row, wrong category). Only `rows` → `group-sum` →
`max-by` (or the exact hand-rolled equivalent the verbs encapsulate) yields
dining@106. Predicates stay STRUCTURAL/behavioral (store-read + aggregation-op
evidence) per gym-integrity §3.4 — the computed 106/dining live ONLY on the judge
axis, and nothing in fixtures/predicates leaks the recipe. An agent that
hand-rolls the correct datalog still passes the judge; the `data/*` eval-count leg
rewards using the surface but the `group-by` alternation keeps it non-coaching.

---

## Notes / smells flagged (not fixed — audit is read-only)

- **`my.kb/source-stats` duplicates aggregation it warns about** (`src/my/kb.cljs:169-180`):
  the `:with ?e` footgun is documented in a docstring instead of removed by a
  reusable verb. The proposal's refactor is the in-place fix.
- **The toolkit catalog has a worked WRITE chain but no worked READ→ANSWER chain**
  ([[toolkit]] §"worked chain" stops at `transact!`). x1/x3 prove the read→answer
  chain is the more common agent need. `my.data`'s worked chain fills it.
- **`my.recall` (semantic KNN) and `my.data` (aggregation) are siblings** — both
  express "what datalog can't / makes error-prone" over stored rows. If `my.recall`
  lands, its `:seon.items/*` hits thread straight into `sum-by`/`group-sum` (recall
  → aggregate), a free composition.
- **Target rename pending:** the live verbs are still `seon.agent.todo` etc., not
  `my.todo`. `my.data` should be authored under the `my.*` convention from the
  start (it has no `seon.*` floor predecessor — it is pure derivation over `db`),
  so it does not wait on the broader `seon.agent.* → my.*` rename.
</content>
</invoke>
