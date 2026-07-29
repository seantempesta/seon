---
name: data-oriented-clojure
description: "Foundational mindset for writing Clojure the Seon way — data-oriented, immutable, schema-first, EAV, errors-as-values, derive-don't-store. Use this BEFORE writing or reviewing ANY seon .clj/.cljc, designing a data model, or implementing a capability, AND whenever you catch an imperative/OO reflex: a mutable accumulator loop, a :type/:kind discriminator, a 'table' of records, bare map keys, a thrown exception at an agent-facing boundary, :pre/:post or hand-rolled validation, stored derived state, threading a db/conn through call sites, a caller pre-read where a transaction function belongs, or a parallel namespace to house a fix. Use before guessing library behavior instead of reading reference-code/. For EAV mechanics see datahike; for schema EDN design see data-modeling."
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

**Where you are (owner rulings, 2026-07-27):** fresh `src/` and `test/` are the
system, `bin/test` is the gate, and **the CLJS build is OFF — CLJ and the JVM
only**. `src-old/` is the quarry: read it for idiom, never extend it, and never
add a `.cljs`.

## The one habit that causes the most wrong code

**Don't guess a library's behavior from training memory — read the vendored
source in `reference-code/` and test in the REPL first.** Every dep that
matters (datahike, malli, core.async + flow, sci) is checked out under
`reference-code/`, grep-able, the same version we run; `clojure -M:test -e "…"`
gives you a JVM with in-memory Datahike in seconds. The default
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

**Current schema path:** first-party attribute/entity/value schemas are EDN
maps under `resources/seon/schema/`, loaded by `seon.schema.edn/load!` as one
validated population. Shipped Clojure does not author those schemas with
load-time `schema/register!`. Runtime agent registrations still pass through
the same admission gate.

**One config authority:** declare a config attribute once in that EDN
population. `seon.schema.edn/derive-config-forms` derives the manifest,
effective, and database-entity composites from the leaf registrations.
`config/default.edn` supplies one complete shipped decision map; never maintain
a second dial roster.

**Selective corpus admission:** only durable declarations become program-graph
rows — contracted functions (`defn` with `:malli/schema`), schema registrations,
and tests. Arbitrary evals, scratch defs, and atoms are process-local. Receipts
retain history but never reconstruct code. `src/seon/fn.clj` is the computed
selection.

## The reflexes, and what to write instead

Each pair is WRONG instinct → idiomatic Seon. The skill is the trigger; the
grounding doc has the file:line for every claim.

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
public + contracted — with no namespace allow list. Re-run `apply!` after
re-evaluating a defn because hot reload replaces the wrapper. `:pre`/`:post`
gets none of this — not
discoverable, not generatively testable, not a database fact. Two sanctioned
argument shapes: map-in/map-out
(preferred for accreting API surfaces) or fully-spec'd positional via `:catn`;
the invariant is that every arg is named, specced, validated — a bare/unspecced
arg is the violation, not a positional one.

Use concrete types. The omission ruling is exact: `[:maybe]` is allowed in
in-memory function RETURN contracts (stored attributes stay nil-free — the
bridge forces absence there). Stored optional fields use `{:optional true}` in
a map and omit the key. `:any` is reserved for a genuine third-party boundary
where no tighter honest type exists.

### Docstring line 1 is a complete ≤72-char sentence — it renders as the summary

A public fn's docstring FIRST LINE is the summary shown wherever the fn renders
compactly (the compact namespace card shows ONLY line 1). So make it a *complete
standalone sentence*, ≤72 chars (78 hard cap), ending in terminal punctuation —
never a mid-sentence hard-wrap. State the action + data effect, not the mechanism
(mechanism → the body, after a blank line). Imperative for side-effecting verbs
(`Store…`, `Mint…`), noun-phrase for pure queries (`Agent ids whose…`); backtick
identifiers. `seon.dev.docstring` (dev hook, warn-only) flags a line 1 that's
missing, >78 chars, or lacks terminal punctuation. Full rule + example:
`docs/conventions.md` "Function Docstrings".

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
system. Caching is a *perf* escape hatch (memoize an expensive derivation), not a
reason to bifurcate into "stored fast path + derived slow path" — `:memory` reads
are sub-millisecond; measure before caching. Cross-agent coordination falls out
for free: a section that doesn't filter by agent-id sees the whole cluster.

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
same idea for a single value. `src/seon/cluster/run.cljc` is the worked
example.

### Prefer `reduce`/`map`/`into` over mutable accumulator loops

An in-body `atom` or `loop`/`recur` to accumulate pure traversal state is a
place-oriented tell. Reach for `into` with a transducer, `reduce` (+ `reduced`
for early exit), or a pure recursive helper with accumulator args. State lives in
the call, not a mutable cell — output depends only on input.

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

Put shipped schema declarations in `resources/seon/schema/*.edn`, then require
only what function code actually calls:

```clojure
(ns seon.expense
  (:require [datahike.api :as d]))

(defn total [xs] (reduce + xs))
```

```clojure
;; resources/seon/schema/expense.edn
{:seon.expense/amount :int}
```

`seon.schema.edn/load!` reads the classpath directory. File boundaries are
editorial only: duplicate keys refuse, every reference must resolve, and
predicate schemas require registered predicates plus honest generators.

### Write a real test ns — `clojure.test/deftest`, not inline `assert`

When you "write a test", put it in a `<ns>-test` namespace under `test/`,
mirroring the source path, using `clojure.test/deftest` + `is` — NOT a pile of
inline `(assert …)` calls (one drive produced 494 inline asserts for one "write
a test" request). A deftest is discoverable by `bin/test`, re-runnable, and
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
| Full conventions (Malli shapes, `.internal`, schema composition) | `docs/conventions.md` |
| What is settled, unsettled, and next | `docs/prds/sci-execution-runtime/plan/README.md` |
