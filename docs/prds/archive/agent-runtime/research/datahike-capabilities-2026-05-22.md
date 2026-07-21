---
type: research
status: active
tags: [research, agent, database]
---

# Datahike-native capabilities the V1 agent REPL should leverage

## TL;DR (for the next agent in fresh context)

The current spec already eliminated `:seon.eval/touches` in favor of
`:tx-meta {:seon.eval/id ...}` + `d/history`. That same primitive-first
discipline can collapse more: **`:db/isComponent`** removes the need for
manual cascade-retract in `forget!`; **`:db/noHistory`** kills the
"don't persist this in history" tile (no warnings-as-entities needed);
the **reverse-ref pull syntax** (`{:seon.agent/_agent [...]}`) gives the
agent "who points at me?" with zero schema additions; **`:seon.eval/id`
MUST be registered as a real schema attribute** (datahike `flush-tx-meta`
rejects unregistered keys at `db/transaction.cljc:634`); **wildcard pull
`[*]`** already inlines refs as `{:db/id N}` and includes `:db/id`, so
the agent's "show me everything" learning surface is free. The
`:seon.test/last-passed-at` / `:last-failed-at` queued-simplification
absolutely works — tag the test-run tx with `:seon.eval/test [:seon.test/sym
...]`, then "latest pass" is a 5-tuple datom query against `d/history`.

The remaining V1 surface should be 100% built on:
`d/transact` (with `:tx-meta`), `d/q`, `d/pull` (with reverse refs and
component recursion), `d/history`/`d/as-of`/`d/since`, `d/entity` (lazy
with reverse-attr support), `d/listen!` (post-tx hook = test-auto-run),
and the 5-tuple datom pattern `[?e ?a ?v ?tx ?op]`. Nothing else.

---

## 1. `:tx-meta` is the canonical way to attach metadata to a transaction

### What datahike gives you

`d/transact` accepts a map `{:tx-data [...] :tx-meta {...}}`. Every key
of `:tx-meta` becomes a datom asserted on the **transaction entity**
itself (the tx-id), so every tx is its own queryable entity.

Source: `datahike/db/transaction.cljc:618-637` (`flush-tx-meta`):

```clojure
(defn flush-tx-meta
  "Generates add-operations for transaction meta data."
  [{:keys [tx-meta db-before] :as report}]
  (let [tid (current-tx report)
        {:keys [attribute-refs?]} (dbi/-config db-before)]
    (reduce-kv
     (fn [entities attribute value]
       (let [straight-a (if attribute-refs? (dbi/-ref-for db-before attribute) attribute)]
         (if (some? straight-a)
           (conj entities [:db/add tid straight-a value tid])
           (log/raise "Bad transaction meta attribute " attribute " at " tx-meta ", not defined in system or current schema"
                      {:error :transact/schema :attribute attribute :context tx-meta}))))
     []
     tx-meta)))

```

**Key facts** (verified in source):

- `:db/txInstant` is auto-merged into every tx-meta map at
  `db/transaction.cljc:894-895`. You get a tx timestamp for free,
  always.
- **`tx-meta` datoms are ONLY appended to the transaction when
  `:keep-history?` is true** (`db/transaction.cljc:898-901`). Without
  history, the metadata exists only on the tx-report — it's not
  queryable.
- **Every tx-meta key must be a registered schema attribute.** Line 634
  raises `:transact/schema` if `straight-a` is nil. This is non-optional
  — agents calling transact with an unregistered `:seon.eval/id` will
  hit `Bad transaction meta attribute :seon.eval/id` and the tx fails.

### What the spec should use

The spec's "tx-meta = eval-id pointer" pattern is correct. To make it
work:

1. `:seon.eval/id` MUST appear in the schema bootstrap (it's already
   declared at `agent-repl-mvp.md:98` and line 420 — confirm both
   register paths run before first transact).
2. The agent DB **must** be opened with `:keep-history? true`. The spec
   already says this (`agent-repl-mvp.md:406`); make it a hard
   precondition in the bootstrap that throws clearly if absent.
3. `:tx-meta {:seon.eval/test ref}` for test-run txs follows the
   identical pattern — register `:seon.eval/test` as `:seon.db/ref`,
   tag the test-run tx, then "latest pass for test T" =
   `[?tx :seon.eval/test T] [?tx :db/txInstant ?at]` against `d/history`.

### What to AVOID building

- Don't denormalize `:seon.eval/touches` / `:seon.eval/forgot` /
  `:seon.eval/test`-as-fact onto the eval entity. The tx IS the eval;
  history queries recover both directions.
- Don't invent `:seon.eval/at` as a separate stored field for "session
  start". `:db/txInstant` is the canonical timestamp on every tx-id.
  Keep `:seon.eval/at` if you want a millisecond `:long` distinct from
  `:db/txInstant`'s `:inst` (the spec's current rationale at line
  108-109 is reasonable), but understand it's a denormalization, not a
  primitive.
- Don't write your own "session boundary" entity. A resume-marker tx is
  itself queryable as `[?tx :seon.eval/resume-marker? true]` against
  `d/history`. The spec already does this at line 1501; keep it.

---

## 2. Pull syntax: reverse refs, recursion, components, wildcard

### What datahike gives you

Datahike's pull-api is a direct port of Datomic's. Read the test cases
in `test/datahike/test/pull_api_test.cljc` to learn the syntax — every
case is a one-liner with expected output.

**Reverse refs** — `_attr` prefix means "find entities pointing at me
via attr". Source: `impl/entity.cljc:30-39` (`-lookup-backwards`) and
`pull_api.cljc:206-251` (`pull-expand-reverse-frame`).

```clojure
;; pull_api_test.cljc:80
(d/pull test-db '[:name :_child] 2)
;; => {:name "David" :_child [{:db/id 1}]}

;; with subpattern — pull_api_test.cljc:84
(d/pull test-db '[:name {:_child [:name]}] 2)
;; => {:name "David" :_child [{:name "Petr"}]}

;; cardinality-many forward implies reverse returns a vector
;; pull_api_test.cljc:90
(d/pull test-db '[:name :_father] 1)
;; => {:name "Petr" :_father [{:db/id 2} {:db/id 3}]}

```

**Component reverse refs collapse to single value** — Source:
`pull_api.cljc:146` (`multi? (if forward? (multival? ...) (not component?))`).
If the attribute has `:db/isComponent true`, reverse-pulling yields a
single map, not a vector:

```clojure
;; pull_api_test.cljc:135 — :_part returns ONE map (because :part is component)
(d/pull test-db [:name :_part] 11)
;; => {:name "Part A.A" :_part {:db/id 10}}

```

**Recursion** — `...` for unbounded, integer for bounded depth. Cycles
are auto-detected and yield `{:db/id N}` placeholders (`pull_api.cljc:55-65`
`pull-seen-eid`):

```clojure
;; pull_api_test.cljc:275
(d/pull db '[:db/id :name {:friend ...}] 4)
;; => entire chain Lucy → Elizabeth → Matthew → Eunan → Kerri

;; pull_api_test.cljc:278
(d/pull db '[:db/id :name {:friend 2 :enemy 2}] 4)
;; bounded to depth 2

;; pull_api_test.cljc:283 — cycle handling
;; when Kerri's :friend → Lucy creates a loop, the second visit returns {:db/id 4}

```

Verified depth tolerance: `test-deep-recursion` at `pull_api_test.cljc:310`
pulls 1500 levels deep. No stack issues.

**Wildcard `*`** — returns every datom on the entity, with refs inlined
as `{:db/id N}` and `:db/id` automatically included
(`pull_api.cljc:253-272`):

```clojure
;; pull_api_test.cljc:144
(d/pull test-db '[*] 1)
;; => {:db/id 1 :name "Petr" :aka ["Devil" "Tupen"] :child [{:db/id 2} {:db/id 3}]}

;; combine wildcard + reverse — pull_api_test.cljc:149
(d/pull test-db '[* :_child] 2)
;; => {:db/id 2 :name "David" :_child [{:db/id 1}] :father {:db/id 1}}

```

**Limit, default, rename** (`pull_api_test.cljc:152-198`):

```clojure
[(limit :aka 500)]                ; cap multi-valued attr
[[:foo :default "bar"]]           ; default if absent
[[:name :as "Name"]]              ; rename in output
[[:x :as "Name" :default "Nothing"]]  ; combine

```

### What the spec should use

The spec's current section functions hand-roll several queries that
should be one pull:

- `current-ns-section` (line 838) runs three `db/query` calls + two
  filter-by-namespace passes. With a back-ref this is one pull:
  `(d/pull db [:seon.ns/source {:seon.fn/_ns [:seon.fn/source] :seon.schema/_ns [...] ...}] [:seon.ns/name ns])`
  if you add an `:seon.fn/ns` ref attr and use the reverse. **Action:**
  promote `:seon.fn/ns` from `:keyword` to `:seon.db/ref → :seon.ns`
  (D-decision candidate; see §7).
- `recent-evals-section` (line 924) and `my-evals` (line 244) — the
  agent's evals are reachable as `{:seon.eval/_agent [...]}` reverse
  ref. The spec already declares `:seon.eval/agent` as a ref attr
  (line 422); the spec's helper functions just don't use the reverse
  yet.
- `my-messages` / `my-logs` — same pattern. The helper fns should be
  one `d/pull` with reverse-ref subpatterns, not hand-rolled queries.
- The "agent record IS the hub" mental model (line 222) and the table
  at line 239 demand reverse-ref pull syntax — without it, the model
  isn't really "one pull, no joins" as advertised.

### What to AVOID building

- Don't build a `seon.agent/my-something` wrapper that hides a
  hand-coded datalog query when `(d/pull db '[*] [:seon.agent/id id])`
  with reverse-ref subpatterns would do it. Teach the agent the pull
  syntax in `system-section` — three concepts (wildcard, underscore-
  prefix reverse, map-subpattern) generalize across the whole API.
- Don't worry about cycle detection in your data — pull handles it.
  Even `:seon.fn/refs` cycles (a fn that recursively references itself
  transitively) won't blow up; they return `{:db/id N}` at the cycle
  point.
- Don't worry about pull recursion depth limits for normal use. 1500
  levels deep is verified.

---

## 3. History, as-of, since: what each enables

### What datahike gives you

| View | Source | Returns | The 5-tuple pattern works? |
|---|---|---|---|
| Current | `(d/db conn)` / `@conn` | latest state, only `:added` datoms | Yes, but only `_ true` matches |
| History | `(d/history db)` | ALL assertions AND retractions | Yes, full `[?e ?a ?v ?tx ?op]` |
| As-of | `(d/as-of db tx-or-date)` | state as it was at point | Yes, but historical only |
| Since | `(d/since db tx-or-date)` | only changes after point | Yes, additions since |

**The 5-tuple datom pattern** is the load-bearing primitive for "what
changed when":

```clojure
;; time_variance_test.cljc:102
[?r :age ?a _ false]   ; ?op = false → matches only RETRACTIONS

;; time_variance_test.cljc:107 — join retracted-value to its tx instant
[?e :age ?a ?t ?op]
[?t :db/txInstant ?d]

;; time_variance_test.cljc:140 — temporal filter via tx-instant
[?e :age ?a ?tx]
[?tx :db/txInstant ?t]
[(before? ?t ?fd)]

```

**Multiple DBs in one query** — the `:in` clause accepts named DBs:

```clojure
;; time_variance.md:217
(d/q '[:find ?n ?a
       :in $ $since
       :where
       [$ ?e :name ?n]
       [$since ?e :age ?a]]
     @conn
     (d/since @conn first-date))

```

**`:db/noHistory true`** per-attribute opt-out (`time_variance_test.cljc:224`,
`schema.cljc:115`). Even with `:keep-history? true`, attrs flagged
`:db/noHistory` don't accrue retraction records. Useful for high-churn
attrs the agent doesn't want history for (e.g. `:seon.agent/turn-count`,
`:seon.agent/state`).

**`d/listen!`** — post-commit callback receives the full tx-report
including `:tx-data` (the datoms written), `:tempids`, and `:tx-meta`.
Source: `core.cljc:206-217` and `writer.cljc:130-134`.

```clojure
(d/listen! conn :test-auto-run
  (fn [{:keys [tx-data tx-meta db-after]}]
    ;; tx-data is a seq of Datom records, each [e a v tx added?]
    ...))

```

### What the spec should use

- **`d/history` is the only thing that powers eval reversibility / "what
  did this eval touch?"** The spec's `agent-repl-mvp.md:441-442`
  ("the eval entity IS the tx entity ... `(d/history db)` recovers what
  it touched") is exactly right. Reinforce by including a worked example
  in the system-section so the agent learns the idiom.
- **`d/listen!` is the trigger surface for D4 (targeted-test auto-run).**
  Don't build a custom post-eval hook system; register one listener,
  read `:tx-data` for fn-asserting datoms, dispatch tests. The listener
  callback runs after commit (`writer.cljc:130`), so the agent never
  sees stale state.
- **`:db/noHistory true` on `:seon.agent/turn-count` and
  `:seon.agent/state`** would prevent the history index from filling
  with per-turn churn. The spec doesn't currently say either way —
  recommend adding `:db/noHistory true` for the high-frequency agent
  scalars.
- **`d/as-of` enables "what did the agent see at turn N?"** The spec
  doesn't use this anywhere yet, but it's the right primitive for any
  future "replay a session" or "diff turn N vs turn N+1" feature.

### What to AVOID building

- Don't compute a `:seon.eval/test-pass-at` / `last-failed-at` field on
  test entities. With history + a `:seon.eval/test` ref attr on the
  test-run tx, "latest pass" is one `d/history` query. The queued
  simplification at `STATUS.md:73-77` is correct and should be adopted.
- Don't build "what changed since last render?" as a stored sequence.
  Capture `:max-tx` at render-time, store it in a render scratch
  variable, and on next render `(d/since db prior-max-tx)`. No accreting
  state.
- Don't manually scan `d/history` for retractions to power the
  reversibility classifier. The 5-tuple `[?e ?a ?v ?tx false]` pattern
  gives you exactly that subset.

---

## 4. Schema constraints — what's free vs constrained

### What datahike gives you

Source: `schema.cljc:218-251` (`find-invalid-schema-updates`). The
**only attribute updates ever permitted** under `:schema-flexibility
:write`:

```clojure
;; schema.cljc:236-238 — always-updatable attrs
:db/doc          nil   ; no restriction
:db/noHistory    nil   ; no restriction
:db/isComponent  nil   ; no restriction

;; schema.cljc:224-228 — cardinality :one → :many ALLOWED ONLY IF :db/unique unset
:db/cardinality
(when (and (= new-value :db.cardinality/many)
           (#{:db.unique/value :db.unique/identity} (:db/unique attr-schema)))
  ...invalid)

;; schema.cljc:230-233 — :db/unique can ONLY be ADDED (never changed/removed) and ONLY ON cardinality-one
:db/unique
(when (or (not (:db/unique attr-schema))
          (not= :db.cardinality/one (:db/cardinality attr-schema)))
  ...invalid)

```

Everything else — including `:db/valueType` — is **immutable** once
set. The fallthrough at line 249 returns the `[old-value new-value]`
diff for any other changed attr, marking it invalid.

The "rename to retype" pattern is the only escape hatch. There is no
in-place type change.

**Free additions** — any attribute that doesn't exist yet can be
added at any time. There is no global schema migration ritual; just
`(d/transact conn [{:db/ident :seon.new/attr :db/valueType ... :db/cardinality ...}])`.

**`:db/ident` is a `:db.unique/identity` keyword** (`schema.cljc:94-96`).
That's the mechanism by which seon's `:seon.fn/sym`, `:seon.schema/key`,
`:seon.test/sym`, `:seon.agent/id`, `:seon.eval/id`, `:seon.ns/name`,
`:seon.ctx/name` all work as lookup refs — they're attrs with
`:db/unique :db.unique/identity` (which seon's bridge derives from
`:seon.db/identity true` per `db/datahike/schema.clj:113`).

### What the spec should use

- The current spec text at `agent-repl-mvp.md:34-40` accurately
  enumerates the constraints. Keep that paragraph as the source of
  truth for the agent's `redefine-spec` workflow.
- **Reject schema-redefine attempts that would fail at the
  datahike-level BEFORE invoking transact**, with a clear error citing
  the violated rule from `find-invalid-schema-updates`. The current
  spec's "spec rejection" predicate (line 1741-1746) should add this
  pre-flight check.

### What to AVOID building

- Don't try to wrap "in-place valueType change" with copy-old-data
  → retract-old-attr → create-new-attr. Datahike's stance is "rename
  instead" — the agent should be taught that pattern, not a hidden
  migration.
- Don't build per-attribute "history retention" knobs beyond
  `:db/noHistory true`. Datahike doesn't have time-bounded retention;
  the only knob is on/off per attr. (There is `:db.history.purge/before`
  but that's a destructive operation; see `time_variance.md:279`.)

---

## 5. Identity, lookup refs, components, cascading retraction

### What datahike gives you

**Lookup refs** — `[unique-attr value]` is accepted anywhere an entity
id is expected, both in transact and query:

```clojure
;; transact — time_variance_test.cljc:69
(d/transact conn [{:db/id [:name "Alice"] :age 30}])

;; query bindings — time_variance_test.cljc:73
(d/q '[:find ?a :in $ ?e :where [?e :age ?a]] @conn [:name "Alice"])

;; full entity retraction by lookup ref — time_variance_test.cljc:78
(d/transact conn [[:db/retractEntity [:name "Alice"]]])

;; ref values in entity maps can be lookup refs — pull_api_test.cljc:140
{:c/concept-id "abc"}   ; used in test as ref value

```

**Intra-tx lookup-ref resolution** — datahike resolves lookup refs
within a single transaction. The spec's `agent-repl-mvp.md:1461`
("Datahike resolves intra-tx lookup refs (e.g. `:seon.test/target
[:seon.fn/sym ...]`) inside the transaction, so dependency order in the
vector is enough") is correct. Verified by the writing path —
`db/transaction.cljc:665` calls `entid-strict` on sequential `:db/id`
values during entity processing.

**`[:db/retractEntity eid]`** retracts every datom for that entity.
With history on, the retractions are recorded as `?op = false` datoms.

**`:db/isComponent true`** on a ref attr means **retracting the parent
auto-retracts the components**. Source: `db/transaction.cljc:716-729`
(retract-entity walks `:isComponent` refs).

Combined with `[:db/retractEntity ...]`, this makes the spec's D5
("forget for namespaces") almost free: declare `:seon.ns` →
`:seon.fn` / `:seon.schema` / `:seon.test` as a component-ref tree
and `[:db/retractEntity [:seon.ns/name :seon.trading]]` cascades.

**`:db.fn/cas`** — compare-and-swap for atomic conditional updates.
Source: `db/transaction.cljc:689-711`. Useful for the `:seon.agent/state`
:idle → :running transition if multiple turn-handlers ever race
(currently single-agent, so deferred).

### What the spec should use

- **Add `:db/isComponent true` to whichever ref attr models "parent
  ns owns these fns/schemas/tests"** if you want D5's cascade-forget
  for free. Currently the spec doesn't declare such a ref — fns are
  matched by `:seon.fn/ns :keyword` (line 350). Recommendation:
  promote `:seon.fn/ns`, `:seon.test/ns`, `:seon.schema/ns` (derive
  from `:seon.schema/key`'s namespace) to `:seon.db/ref` →
  `:seon.ns` entities, marked component. Then a single
  `[:db/retractEntity [:seon.ns/name ns]]` op cascades through the tree.
- **Use `:db/isComponent true` on `:seon.agent/ctx`** (D11). Per the
  D11 spec, ctx entities are owned by exactly one agent. Marking the
  ref component means: retracting an agent retracts its ctx entities
  automatically; `(d/pull db '[*] agent-id)` inlines ctx entity maps
  (not just `{:db/id N}` placeholders) per
  `pull_api.cljc:163-167` (`(and component? forward?) ... expand-frame`).
  Recommendation: re-read the spec at line 2042 and add the component
  flag.

### What to AVOID building

- Don't manually walk the ns → fn/schema/test tree to retract. Use
  `:db/isComponent` and `[:db/retractEntity ...]`.
- Don't generate UUIDs for relationships when a lookup ref works.
  Every spec attr that's `:seon.db/identity true` (yields
  `:db/unique :db.unique/identity`) is usable as a lookup ref.
- Don't write your own tempid resolution. `:db/id -1`, `:db/id "foo"`,
  and intra-tx lookup refs all work natively.

---

## 6. The eval entity = the tx entity (already in spec — confirm

mechanics)

### What datahike gives you

`flush-tx-meta` writes tx-meta keys as datoms on the current tx-id
(`db/transaction.cljc:629-633`). Each datom is `[:db/add tid attr value
tid]` — the value IS the tx, and the `:tx` field on the datom is also
the tx. So pulling the tx entity returns the eval-entity map:

```clojure
;; after a transact with :tx-meta {:seon.eval/id "K9p..." :seon.eval/agent ref}
(d/pull db '[*] tx-id)
;; => {:db/id 536870915
;;     :db/txInstant #inst "..."
;;     :seon.eval/id "K9p..."
;;     :seon.eval/agent {:db/id 5}}

;; or by the unique :seon.eval/id
(d/pull db '[*] [:seon.eval/id "K9p..."])
;; => same map; the eval IS the tx

```

To get the datoms written by that tx, query history with the 5-tuple:

```clojure
(d/q '[:find ?e ?a ?v ?op
       :in $ ?tx
       :where [?e ?a ?v ?tx ?op]]
     (d/history db) tx-id)
;; => the assertions and retractions made by that one eval

```

### What the spec should use

The spec already gets this right (lines 437-443, 1483-1492). The
recommendation is just: include the above pull-by-id and history query
as worked examples in the **system-section** so the agent learns to
debug their own eval log without you having to write a "show me what
this eval did" helper. The two primitives (`d/pull` on tx-id and `d/q`
on `d/history`) cover every diagnostic the agent will ever want.

### What to AVOID building

- Don't write a wrapper called `(eval-summary eval-id)`. Show the agent
  the two-line pattern; teach by example, not by API surface.
- Don't store an `:seon.eval/asserted-attrs` denormalization. The
  history query is fast (it's a direct index hit on the tx).

---

## 7. Recommended additional schema attributes to make queries cheap

These would unlock single-pull views the spec currently hand-rolls:

```clojure
;; Make ns → fn/schema/test a component-ref tree (enables cascade-forget)
(schema/register! :seon.fn/ns
  [:seon.db/ref {:seon.db/component true}])         ; → :seon.ns
(schema/register! :seon.schema/ns
  [:seon.db/ref {:seon.db/component true}])         ; → :seon.ns
(schema/register! :seon.test/ns
  [:seon.db/ref {:seon.db/component true}])         ; → :seon.ns

;; Make agent ctx a component ref (already cardinality-many)
(schema/register! :seon.agent/ctx
  [:vector [:seon.db/ref {:seon.db/component true}]])

;; Test-run tagging — already discussed as queued-simplification
(schema/register! :seon.eval/test :seon.db/ref)     ; → :seon.test (tx-meta key)
(schema/register! :seon.eval/replay? :boolean)      ; tx-meta key (already in spec)
(schema/register! :seon.eval/resume-marker? :boolean) ; tx-meta (in spec)

;; Add :db/noHistory to high-churn agent scalars
;; (encode in :seon.db/no-history? props on the registered Malli schemas)
:seon.agent/turn-count        ; cycles each turn
:seon.agent/turns-since-user  ; same
:seon.agent/state             ; :idle ↔ :running

```

This requires the seon bridge to translate `:seon.db/component true`
(would be a new bridge property) and `:seon.db/no-history? true` to
`:db/isComponent true` and `:db/noHistory true` respectively.
`db/datahike/schema.clj:107-114` is where the bridge currently translates
`:seon.db/identity` / `:seon.db/unique` — add the two new keys to that
function.

---

## 8. The full datahike API surface the V1 spec should rely on

From `src/datahike/api/specification.cljc` and `src/datahike/core.cljc`.
Every fn below is `:stability :stable` and works in CLJS pure-mode (per
`api.cljc:9-22`):

| API | Sync/async (CLJS) | Used for |
|---|---|---|
| `d/transact` / `d/transact!` | async (Promise) | every write; ALWAYS include `:tx-meta` |
| `d/q` | sync | datalog queries |
| `d/pull` / `d/pull-many` | sync | structured reads with reverse refs / recursion / wildcards |
| `d/entity` | sync | lazy attribute access; `(:foo ent)` for forward, `(:_foo ent)` for reverse (verified `impl/entity.cljc:75`) |
| `d/db` / `@conn` | sync | current state |
| `d/history` | sync | full datom log; powers reversibility classifier + "what did this eval touch" |
| `d/as-of` | sync | state at point in time |
| `d/since` | sync | changes after point in time |
| `d/datoms` | sync | direct index lookup; for the renderer's bulk reads |
| `d/listen!` / `d/unlisten!` | sync (registry side-effect) | post-tx hooks; this is D4's auto-test trigger |
| `d/with` / `d/db-with` | sync | dry-run a transact on an immutable db value (for "preview my change" features) |

Async/sync split is verified by `:referentially-transparent? true` on
the queries (`api.cljc:14-15`) and `false` on the writes (line 11).

**APIs to NOT reach for in V1**:

- `d/filter` (line 449) — wraps the db in `FilteredDB`, only useful for
  multi-tenant isolation. Single-agent V1 has no need.
- `d/seek-datoms` / `d/rseek-datoms` / `d/index-range` — exposed but
  cover index-internal access patterns not needed by the renderer.
- `:db.purge/*` operations — permanent deletion, GDPR-only
  (`time_variance.md:274`). The spec's `forget!` should use
  `:db/retractEntity` (preserves history) NOT `:db.purge/entity`
  (destroys history).
- `d/explain` is `:cljs nil` per `api/specification.cljc:325` — not
  available in the pod. Don't promise the agent an EXPLAIN feature.

---

## 9. Spec change recommendations

Apply these edits to `agent-repl-mvp.md`. Each is independent;
sequence as the agent prefers.

### Required (correctness)

1. **Register `:seon.eval/id`, `:seon.eval/agent`, `:seon.eval/replay?`,
   `:seon.eval/resume-marker?`, `:seon.eval/test` as real schema
   attributes in the bootstrap.** Datahike rejects unregistered
   tx-meta keys (`db/transaction.cljc:634`). Add a section after
   "First boot" titled **"tx-meta schema preconditions"** that lists
   the keys + types and notes the explicit "bootstrap runs the
   schema/register! calls before the first tx-meta-carrying tx" rule.

2. **Assert `:keep-history? true` in `bootstrap-phase!`.** tx-meta
   datoms ONLY persist when history is enabled
   (`db/transaction.cljc:898-901`). If the agent ever opens with
   `:keep-history? false`, every history-driven feature silently
   degrades. Add `(when-not (:keep-history? cfg) (throw ...))` to
   the bootstrap with a clear message.

### Strong (eliminate complexity)

3. **Adopt the queued simplification in `STATUS.md:71-77`: drop
   `:seon.test/last-passed-at` / `:last-failed-at` / `:last-failure`.**
   Replace with tx-meta `:seon.eval/test [:seon.test/sym ...]` on
   test-run txs. Update the failing-test warning predicate to query
   `d/history` for the most recent tx matching that test ref.
   Concrete pattern:

   ```clojure
   '[:find (max ?tx) ?ok
     :in $ ?test
     :where [?tx :seon.eval/test ?test]
            [?tx :seon.eval/ok? ?ok]]

   ```

4. **Add `:db/isComponent true` (via a new `:seon.db/component` bridge
   prop) to `:seon.agent/ctx`** at the spec's line 2042 / D11. This
   makes `(d/pull db '[*] agent-id)` inline the ctx entities (rather
   than returning `{:db/id N}` placeholders), AND makes agent-retract
   cascade-clean their ctx entities. Single edit in D11; two consequent
   wins.

5. **Promote `:seon.fn/ns`, `:seon.test/ns`, `:seon.schema/ns` to
   `:seon.db/ref` (component) → `:seon.ns` entities.** Currently
   `:seon.fn/ns` is `:keyword` (line 350); this works but precludes
   reverse-ref pulls. Change to ref, mark component, and:
   - `current-ns-section` becomes one pull on the ns entity with
     `{:seon.fn/_ns [:seon.fn/source] :seon.schema/_ns [...] :seon.test/_ns [...]}`.
   - D5 (forget for namespaces) is one `[:db/retractEntity [:seon.ns/name ns]]`.
   This is the single largest simplification available.

6. **Add `:db/noHistory true` to high-churn agent scalars.** Specifically
   `:seon.agent/turn-count`, `:seon.agent/turns-since-user`,
   `:seon.agent/state`. The history index will otherwise accumulate one
   row per turn forever. Encode via a `:seon.db/no-history?` bridge
   property.

### Recommended (teach the primitives)

7. **In the `system-section` template (line 829), show the agent
   three pull patterns** they should learn day one:

   ```clojure
   ; their record + everything attached
   (d/pull db '[* {:seon.eval/_agent [:seon.eval/id :seon.eval/source :seon.eval/result-edn]}]
                [:seon.agent/id "..."])

   ; an ns and everything it owns (after rec #5)
   (d/pull db '[:seon.ns/source
                {:seon.fn/_ns [:seon.fn/sym :seon.fn/source]
                 :seon.schema/_ns [:seon.schema/key :seon.schema/source]
                 :seon.test/_ns [:seon.test/sym :seon.test/source]}]
                [:seon.ns/name :seon.trading])

   ; what an eval did (the eval IS the tx)
   (d/q '[:find ?e ?a ?v ?op :in $ ?tx :where [?e ?a ?v ?tx ?op]]
        (d/history db) [:seon.eval/id "..."])

   ```
   These are the load-bearing idioms; once learned they cover most
   diagnostic queries the agent will ever want.

8. **Replace the spec's hand-coded `my-evals` / `my-messages` helper
   queries (line 244, 289)** with one-line pulls using reverse refs.
   Helpers can still exist for ergonomics, but their bodies should be
   single pulls — not multi-step datalog. Keeps the surface area honest
   about "the agent record is the hub, everything is one pull away."

9. **Reword the "Self-recovery" pattern (line 264-273)** to use a
   single transact with retract-old-ctx + add-new-ctx, rather than
   "transact a new vector". Cardinality-many ref attrs accumulate by
   default; the spec's `:seon.agent/ctx <default-refs>` syntax would
   leave old refs around unless the bridge generates explicit retracts
   first. Worth re-reading datahike's cardinality-many semantics in
   `db/transaction.cljc:557-572` to confirm the intended retract-and-add
   shape.

10. **Replace `agent-repl-mvp.md:1318-1333` ("Provenance — `why is this
    in my context?`")** to point the agent at `d/pull` on the section
    entity. The "explain-section" helper is fine as a convenience, but
    the underlying mechanism should be visible — pull the entity, call
    the function, return the string with annotation.

### Optional / future

11. **Add `(d/with @conn tx-data)` as the "preview my change without
    committing" primitive.** Useful when the agent wants to compute
    consequences of a hypothetical schema change before deciding to
    commit. Doesn't change the V1 surface; just becomes available to
    the agent once they know the API.

12. **For per-DB versioning / branching** (the substrate-upgrade D1
    question, line 1716), datahike has `d/branch!` / `d/merge-db!`
    natively (`api/specification.cljc:555-580`). This may be the right
    primitive for "older DB on newer runtime" without inventing a
    custom diff/merge protocol. Read `doc/versioning.md` when D1 is
    next picked up.

---

## 10. What datahike does NOT give you (so the spec must keep)

For honesty: a few things the spec does that aren't redundant with
datahike primitives:

- **The `:seon.eval/source` / `:seon.eval/narration` / `:seon.eval/ok?`
  / `:seon.eval/error` / `:seon.eval/duration-ms` / `:seon.eval/ns`
  fields.** These are real eval-level data, NOT derivable from
  `d/history` over the tx. Keep them as attrs on the eval entity (=
  tx entity).
- **Rendering / section-fn composition.** Datahike has no notion of
  views. The spec's composer over `:seon.ctx` entities is the right
  shape — datahike just provides the storage + query for the entities.
- **The bootstrap-from-compiled-code mechanism (D10).** Datahike can't
  bootstrap itself from CLJS source; that's the substrate's job. The
  spec is correct that bootstrap = one ordered transact.
- **Result auto-save to globalThis (line 1154).** Datahike persists
  serializable data only; live values (Promises, JS objects, fns)
  need an off-DB store. The globalThis stash is the right mechanism;
  no datahike feature replaces it.
- **rewrite-clj parsing.** Datahike doesn't read Clojure source. The
  spec's reliance on rewrite-clj for the parse path is correct and not
  duplicative.

---

## Sources cited

All file:line references are from `/Users/sean/src/datahike/` at the
HEAD checked out in the working tree:

- `src/datahike/api.cljc` (full file, 83 lines)
- `src/datahike/api/specification.cljc:200-505` (transact, q, pull,
  entity, datoms, history, as-of, since signatures)
- `src/datahike/core.cljc:126-256` (with, listen!, unlisten!, db, conn?)
- `src/datahike/pull_api.cljc:30-325` (full pull mechanism; especially
  146 for component, 163-167 for component-expand, 206-251 for reverse,
  253-272 for wildcard)
- `src/datahike/db/transaction.cljc:618-637` (flush-tx-meta), 689-711
  (compare-and-swap), 713-729 (retract-entity component cascade),
  885-970 (transact-tx-data loop; 894-895 auto-:db/txInstant; 898-901
  history-gated meta-entities)
- `src/datahike/schema.cljc:84-145` (implicit-schema-spec, especially
  118-121 for :db/txInstant), 218-251 (find-invalid-schema-updates)
- `src/datahike/impl/entity.cljc:30-39` (reverse-ref entity lookup),
  75 (CLJS reverse-attr `.get`)
- `src/datahike/writer.cljc:120-145` (commit + listener invocation)
- `doc/schema.md` (full schema reference)
- `doc/time_variance.md` (history / as-of / since / no-history / purge)
- `doc/schema-migration.md` (norms; not needed for V1)
- `test/datahike/test/pull_api_test.cljc` (every pull idiom: reverse
  79-97, component 99-142, wildcard 144-150, limit 152-180, default
  182-198, recursion 226-328)
- `test/datahike/test/query_pull_test.cljc:13-148` (pull-in-find,
  pull-with-var-pattern, pull-with-lookup-ref)
- `test/datahike/test/query_rules_test.cljc:10-161` (rules in `:in %`
  — useful if the spec ever wants reusable query fragments; not V1)
- `test/datahike/test/time_variance_test.cljc:60-389` (history /
  as-of / since / 5-tuple `[?e ?a ?v ?tx ?op]` / `:db/noHistory` /
  `:db/retractEntity` cascades, upsert-history behavior)

Seon-side references confirming the bridge:

- `/Users/sean/src/seon/src/seon/db/datahike/schema.clj:107-114`
  (seon-db-props → db-props translation; extension point for
  `:seon.db/component` and `:seon.db/no-history?`)
- `/Users/sean/src/seon/src/seon/db/datahike/schema.clj:172-208`
  (`:seon.db/ref` handling, including the `:malli.core/schema`
  fallback path for registered-keyword refs)
