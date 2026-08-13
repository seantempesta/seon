---
name: data-oriented-clojure
description: "Foundational mindset for writing Clojure the Seon way — data-oriented, immutable, schema-first, EAV, errors-as-values, derive-don't-store. Use this BEFORE writing or reviewing ANY Seon .clj/.cljc OR maintaining a vendored Clojure fork that Seon owns under reference-code/; this does not trigger for unrelated third-party work. Also use it when designing a data model or capability, or whenever you catch an imperative/OO reflex: a mutable accumulator loop, a :type/:kind discriminator, a 'table' of records, bare map keys, a thrown exception at an agent-facing boundary, :pre/:post or hand-rolled validation, stored derived state, threading a db/conn through call sites, a caller pre-read where a transaction function belongs, an unordered collection driving a tied decision, or a parallel namespace to house a fix. Use before guessing library behavior instead of reading reference-code/. For EAV mechanics see datahike; for schema EDN design see data-modeling."
---

# Data-Oriented Clojure — the Seon mindset

You are working in a system that is **data-oriented, immutable, schema-first,
REPL-driven, and EAV/datalog-based**. If you arrived with Java/Python/JS
instincts, those instincts will betray you here in predictable ways. This skill
rewires the most common ones. It is mostly *why*, because once you see why the
grain runs this direction, the right code is obvious.

For the specifics this skill defers to: **EAV queries → the `datahike` skill;
what shape to declare and why → `data-modeling`; test patterns →
`clojure-testing`.** The grounding is the vendored source under
`reference-code/` and the live code in `src/`.

**Where you are:** fresh `src/` and `test/` are the system, `bin/test` is the
gate, and **the CLJS build is OFF — CLJ and the JVM only**. The old source trees
were deleted; quarry them through `git show` and `git log`, never through an
in-tree checkout (`AGENTS.md:247-254`).

## The one habit that causes the most wrong code

**Don't guess a library's behavior from training memory — read the vendored
source in `reference-code/` and test in the REPL first.** Every dep that
matters (datahike, malli, core.async + flow, sci) is checked out under
`reference-code/`, grep-able, the same version we run. Use `clojure -M:dev` for
a load-only JVM probe; create an explicit `:memory` database or use
`seon.test-support/with-database` when database behavior is the subject
(`test/seon/test_support.clj:184-216`). The default
failure mode is writing confident Clojure in a place/mutable mindset while
*guessing* how a `:malli/schema` validates or what `:db.fn/cas` does — and being
wrong. Ground the concept→file first, then write. A 30-second REPL experiment
beats hours of debugging. (See the shared instructions, "Slow Is Fast".)

Memory got these wrong recently, the source set them right: an inline tx-fn
carries a resolved function while `:db.fn/cas` is pure transaction data;
`as-of` reports its *origin* db's basis-t, not the
as-of point; `memoize` on a db value walks the entire index on a cache *hit*;
and under `:schema-flexibility :write` an attribute is NOT installed lazily —
transacting an uninstalled attribute throws.

**Current schema path:** first-party attribute/entity/value schemas are the EDN
files under `resources/seon/schemas/`, loaded by `seon.schema.edn/load!` as one
validated population. Shipped Clojure does not author those schemas with
load-time `schema/register!`. Runtime agent registrations still pass through
the same admission gate (`src/seon/schema/edn.clj:143-225,234-324`).

**One config authority:** declare a config attribute once in that EDN
population. `seon.schema.edn/derive-config-forms` derives the open manifest,
effective, agent-overlay, and database-entity composites from the leaf registrations.
`config/default.edn` supplies one complete shipped decision map; never maintain
a second dial roster (`src/seon/schema/edn.clj:95-130`;
`src/seon/config.clj:137-229`).

**Program state has four boundaries.** Read the single checked semantic source,
[`references/program-state.md`](references/program-state.md), before changing
or describing any of these boundaries.

## The reflexes, and what to write instead

Each pair is WRONG instinct → idiomatic Seon.

### Entity = attributes + connections. There are NO kinds.

The OO reflex the system most wants you to drop: a datahike entity has no
type/class/kind — schema attaches to *attributes*, and the namespaced keyword IS
the discriminator. If you catch yourself writing a `:type`/`:kind` field, a kind
taxonomy, or "for each kind", stop and reframe in attributes + connections +
provenance. The full four-moves (FIND by attribute presence / IDENTIFY by a
`:db.unique/identity` attr / RELATE-REMOVE by refs / SCOPE through the
transaction's `:seon.db/user` and `:seon.db/process` refs)
are taught with worked queries in the **`datahike`** skill — read it there.

### Don't think in tables/rows — think in EAV bags of namespaced attrs

One entity carries attributes from several namespaces; adding a field needs no
migration. Generic code works on `:seon.ai/*`; provider code adds its own
qualified attributes to the *same* entity. No provider-specific table, no
migration.

### Every map key is a fully-namespaced keyword

```clojure
(defn process [{:keys [pattern paths]}] {:ok? true})        ; WRONG — bare keys
(defn process [{::keys [pattern paths]}] {::ok? true})      ; RIGHT — ::pattern resolves to this ns
```

Namespaced keys are load-bearing: a single Datalog query can join function
contracts to the data they operate on, and "what accepts
`:seon.agent.search/pattern`?" becomes a database query instead of a guess. The
keyword namespace is a real code namespace that owns that data's schema.

### Public fns carry `:malli/schema` — not `:pre`/`:post`, not hand-rolled checks

```clojure
(defn do-thing
  {:malli/schema [:=> [:cat ::do-thing-request] ::do-thing-response]}
  [{::keys [id option]}] ...)
```

The `:malli/schema` is the contract of record: tests check it, generators
derive from it, review reads it, and `seon.instrument/apply!` instruments every
loaded public var carrying one in development. The selection is computed —
public + contracted — with no namespace allow list. Reload and wrapper-restoration
mechanics live in the **`repl`** skill (`src/seon/instrument.clj:180-215`).
`:pre`/`:post` gets none of this — not
discoverable, not generatively testable, not a database fact. Two sanctioned
argument shapes: map-in/map-out
(preferred for accreting API surfaces) or fully-spec'd positional via `:catn`;
the invariant is that every arg is named, specced, validated — a bare/unspecced
arg is the violation, not a positional one.

Hot reload changes the loaded Var only; it does not change database program
facts. The edit hook publishes safe source changes to the one `:current-src`
branch and performs a complete scratch rebuild for structural changes. Existing
clusters remain sovereign. `bin/seon init CLUSTER --force` is the destructive refork
(`AGENTS.md`, “Hot reload is not program-graph indexing”).

Use concrete types. The omission ruling is exact: `[:maybe]` is allowed in
in-memory function RETURN contracts (stored attributes stay nil-free — the
bridge forces absence there). Stored optional fields use `{:optional true}` in
a map and omit the key. Authored contracts refuse `:any`, `:some`, and `:nil`;
declare a named predicate schema for genuine polymorphism
(`src/seon/schema/internal.cljc:20,59-105`).

Private helpers may use ordinary positional arguments and remain unspecced.
That convenience never weakens a public function's complete input/output
contract.

### Docstring line 1 is a complete ≤72-char sentence — it renders as the summary

A public fn's docstring FIRST LINE is the summary shown wherever the fn renders
compactly (the compact namespace card shows ONLY line 1). So make it a *complete
standalone sentence*, ≤72 chars (78 hard cap), ending in terminal punctuation —
never a mid-sentence hard-wrap. State the action + data effect, not the mechanism
(mechanism → the body, after a blank line). Imperative for side-effecting verbs
(`Store…`, `Mint…`), noun-phrase for pure queries (`Agent ids whose…`); backtick
identifiers. `seon.dev.docstring` (dev hook, warn-only) flags a line 1 that's
missing, >78 chars, or lacks terminal punctuation. Namespace docstrings stay
concise and current-state; the long-form stewardship format lives only in
`docs/seon/concepts/namespace-stewardship.md`.

### Agent-facing verbs return error envelopes — they never throw

```clojure
{::ok? true  …data…}                          ; success
{::ok? false ::error "guiding msg" ::raw-error "detail"}   ; failure
```

An agent's eval must survive a bad call and read the failure as data. The
agent-facing boundary catches dependency failures and returns a flat
`:seon.error` value. Exceptions-as-control-flow break the loop; values let the
agent inspect, recover, and retry. A programmer error inside private core code
may still throw into the core fault path.

### Derive at render — don't store derived state or "mark-as-seen" flags

```clojure
;; WRONG — a stored counter / last-error / ack flag / notification queue
(swap! warnings conj {:msg "stale" :seen? false})

;; RIGHT — a section is a pure fn of the DB; renders only when the query has rows
(defn stale-section [db]
  (when-let [rows (seq (d/q '[:find ?e :where [?e :seon.error/fault]] db))]
    (render rows)))
```

The system is self-healing because nothing is stored that needs clearing — when
the underlying problem is fixed the query returns empty and the surface vanishes.
No acknowledgement state, no stored "last error", no separate notification
system. Caching is a *perf* escape hatch for a measured expensive derivation,
not a reason to bifurcate into "stored fast path + derived slow path". Do not
`memoize` on a Datahike database value: its equality implementation compares
the EAVT index (`reference-code/datahike/src/datahike/db.cljc:703-715`).
Cross-agent coordination falls out naturally: a section that does not filter
by agent-id sees the whole cluster.

The same reflex in temporal form: **storing who/when-wrote-this on a domain
entity** (`created-by`, `created-at`, `source-turn`) — the transaction entity
already records it (auto-stamped tx-meta + `:db/txInstant`); derive it by
joining the datom's tx. See the **`datahike`** skill, "Transaction metadata".

### A db is a VALUE, not a place — thread it, db-first

```clojure
;; WRONG — re-deref at each leaf (two DIFFERENT values; phantom "races")
(let [a (q1 @*conn*) b (q2 @*conn*)] ...)

;; RIGHT — snapshot once, thread the value
(let [db @*conn*]
  (let [a (d/q query-a db) b (d/q query-b db)] ...))
```

`d/q`/`d/pull`/`d/entity` are referentially transparent over a db value — it
can't change under you, so the "race" you think you have is usually re-reading
the connection three times instead of threading one snapshot. `db` is the
**first** parameter to functions; never thread a connection or other opaque
runtime object through an agent-facing call site.

At the co-located write seam, use Datahike's actual API. `d/transact` accepts
BOTH `{:tx-data [...] :tx-meta {...}}` and the raw vector/sequence shorthand.
A map without `:tx-data` is invalid. This is dependency source, not a project
preference: `reference-code/datahike/src/datahike/api/impl.cljc:30-48`.

**The strongest form of this: don't pre-read at all.** When a decision depends
on current state, make the decision INSIDE the transaction — a
`[:db.fn/call f request]` transition reads the mid-transaction database value,
refuses an ineligible request by throwing (aborting the whole transaction
atomically), and returns plain tx-data otherwise. No observed-* request fields,
no caller pre-reads, no window between deciding and acting. `:db.fn/cas` is the
same idea for a single value. `src/seon/cluster/run.clj:562-730` is the worked
example.

### Prefer `reduce`/`map`/`into` over mutable accumulator loops

An in-body `atom` or `loop`/`recur` to accumulate pure traversal state is a
place-oriented tell. Reach for `into` with a transducer, `reduce` (+ `reduced`
for early exit), or a pure recursive helper with accumulator args. State lives in
the call, not a mutable cell — output depends only on input.

### Unordered collections never decide order or break ties

A set is honest membership data and dishonest ordering data. The same applies
to a hash map when its entry walk controls a sequence. If a cost, score, or
priority comparison can tie, preserve an existing semantic order in a vector
or add an explicit domain tie-break; never let “first” mean the first value
encountered while walking a set or hash map.

The failure mode is deceptive: hash iteration can leak a symbol's or object's
hash into the chosen plan. Equivalent input then changes behavior after a
rename or between JVM boots, making a deterministic defect look intermittent.
Datahike's planner did exactly this by converting source-ordered operations to
a set before stable cost sorting; the repair preserves the operation vector and
removes the selected identical operation
(`reference-code/datahike/src/datahike/query/plan.cljc:1544-1599`,
`:1607-1663`; full failure and repair:
`docs/seon/issues/archive/datahike-planner-and-caches-carry-three-smaller-defects.md`
“Stable equal-cost selection”).

Audit every tie-break, “pick the first”, `min-key`/`max-key`, plan selection,
priority choice, and ordered output derived by walking a set or hash map. Ask:
when primary keys tie, which declared order decides? If the answer is collection
iteration, the transformation is not deterministic.

### Concurrency: plain synchronous Clojure, `core.async.flow` for owners

The JVM path is plain synchronous code — virtual threads park, so there is no
callback colouring and no `^:async` marker anywhere in `src/`. When you need a
long-running owner with its own lifecycle, the substrate is
`core.async.flow` (`seon.flow`), using ITS vocabulary: procs with `step-fn`s,
bounded channels, `conns`, a `graph-def`, `executor-for :io` vs
`executor-for :compute`. Do not invent a scheduler noun and do not reach for a
bare thread or future where a proc belongs. `^:async`/`await` was a pod
construct; the CLJS build is off and no new code uses it.

### `:or` doesn't fill a present-nil key — use explicit `or`

```clojure
(defn impl [{::keys [model] :or {model d}}] ...)   ; WRONG — {::model nil} slips through
(defn impl [{::keys [model]}] (let [model (or model d)] ...))  ; RIGHT
```

Pairs with "optional = absent, never store nil"; to clear a persisted field,
retract it explicitly — omitting a key means "leave unchanged".

### Fix in place — no `foo-v2`, no parallel namespace to "house a fix"

The repo is on a feature branch; atomic refactors are the *cheap* option. If a
fn/schema/ns exists and you're fixing it, the fix lives in the existing one. A
parallel `foo-v2` leaves two versions, doubles the bug surface, and its
explanatory comment outlives everyone who knew the reason. Same as "register the
shape once, reference everywhere": duplication guarantees drift.

### Schema EDN and function code have separate homes

Keep one source file per namespace and mirror its path under `test/`. JVM-only
owners use `.clj`; use `.cljc` only for a genuinely portable capability core or
pure transformation. When plumbing obscures a public namespace's contract, it
may move to a sibling `<ns>.internal`; never create that sibling to hide
callable functions or to avoid strengthening the existing owner in place.

Put shipped schema declarations under `resources/seon/schemas/`, then require
only what function code actually calls:

```clojure
(ns seon.expense
  (:require [datahike.api :as d]))

(defn total [xs] (reduce + xs))
```

```clojure
;; resources/seon/schemas/ — owning expense family
{:seon.expense/amount :int}
```

`seon.schema.edn/load!` reads the directory-backed classpath population. Section comments are
editorial only: duplicate keys refuse, every reference must resolve, and
predicate schemas require registered predicates plus honest generators
(`src/seon/schema/edn.clj:1-15,49-51,143-225,234-324`).

### Write a real test ns — `clojure.test/deftest`, not inline `assert`

When you "write a test", put it in a `<ns>-test` namespace under `test/`,
mirroring the source path, using `clojure.test/deftest` + `is` — NOT a pile of
inline `(assert …)` calls. A deftest is discoverable by `bin/test`, re-runnable, and
reports pass/fail as data. A test the gate cannot discover is NOT coverage:

```clojure
;; test/seon/expense_test.clj
(ns seon.expense-test (:require [clojure.test :refer [deftest is]]))
(deftest totals-sum
  (is (= 101 (seon.expense/total [45 18 38]))))
```

Test fixtures, event backstops, and generative patterns live in the
**`clojure-testing`** skill.

## When to read which reference

| You're about to... | Read first |
|---|---|
| Write a Datalog query, transact, fence a transition | the **`datahike`** skill |
| Decide what shape to declare and why | the **`data-modeling`** skill |
| Write tests, fixtures, generators | the **`clojure-testing`** skill |
| Build a long-running owner, channel, or executor | `reference-code/core.async/.../flow/` + `src/seon/flow.clj` |
| Confirm any library's actual behavior | the vendored source in `reference-code/<lib>/` — never guess |
| Change program-graph source indexing | `src/seon/fn/analyzer.clj:1-174` + `reference-code/clj-kondo/src/clj_kondo/core.clj:67-217` |
| What is settled, unsettled, and next | `docs/prds/sci-execution-runtime/plan/README.md` |
