---
name: data-oriented-clojure
description: "Foundational mindset for writing Clojure/ClojureScript the Seon way — data-oriented, immutable, schema-first, EAV, errors-as-values, derive-don't-store. Use this BEFORE writing or reviewing ANY seon .clj/.cljs, designing a data model, or implementing an agent verb, AND whenever you catch an imperative/OO reflex: a mutable accumulator loop, a :type/:kind discriminator, a 'table' of records, bare (non-namespaced) map keys, a thrown exception in an agent-facing fn, :pre/:post or hand-rolled validation, stored/cached derived state or a 'mark-as-seen' flag, threading a db/conn through call sites, a bare top-level await, or a foo-v2/parallel namespace to 'house a fix'. Use when you're about to guess a library's behavior from memory instead of reading reference-code/. For EAV query syntax see the datahike skill; for ^:async/self-host see the clojurescript skill — this is the mindset that links them."
---

# Data-Oriented Clojure — the Seon mindset

You are working in a system that is **data-oriented, immutable, schema-first,
REPL-driven, and EAV/datalog-based**. If you arrived with Java/Python/JS
instincts, those instincts will betray you here in predictable ways. This skill
rewires the most common ones. It is mostly *why*, because once you see why the
grain runs this direction, the right code is obvious.

The deep, file:line-grounded version is
`docs/prds/agent-fsm/research/clojure-idioms-for-agents-2026-06-28.md`. Read it
when you want the evidence behind a claim. For the specifics this skill defers
to: **EAV queries / data modeling → the `datahike` skill; `^:async`/`await` and
self-host eval → the `clojurescript` skill; test patterns → `clojure-testing`.**

## The one habit that causes the most wrong code

**Don't guess a library's behavior from training memory — read the vendored
source in `reference-code/` and test in the REPL first.** Every dep
(datahike, malli, clojurescript, sci, integrant, reitit, core.async) is checked
out under `reference-code/`, grep-able, the same version we run. The default
failure mode is writing confident Clojure in a place/mutable mindset while
*guessing* how a `:malli/schema` validates or what `:db.fn/cas` does — and being
wrong. Ground the concept→file first, then write. A 30-second REPL experiment
beats hours of debugging. (`CLAUDE.md` "Slow Is Fast".)

Memory got these wrong recently, the source set them right: an inline tx-fn
carries a closure and can't cross the wire (use `:db.fn/cas`, pure data);
`as-of` reports its *origin* db's basis-t, not the as-of point; `memoize` on a db
value walks the entire index on a cache *hit*. All three in
`datahike-primer.md`.

## The reflexes, and what to write instead

Each pair is WRONG instinct → idiomatic Seon. The skill is the trigger; the
grounding doc has the file:line for every claim.

### Entity = attributes + connections. There are NO kinds.

The OO reflex the system most wants you to drop: a datahike entity has no
type/class/kind — schema attaches to *attributes*, and the namespaced keyword IS
the discriminator. If you catch yourself writing a `:type`/`:kind` field, a kind
taxonomy, or "for each kind", stop and reframe in attributes + connections +
provenance. The full four-moves (FIND by attribute presence / IDENTIFY by a
`:db.unique/identity` attr / RELATE-REMOVE by refs / SCOPE by `:seon.db/origin`)
are taught with worked queries in the **`datahike`** skill — read it there.

### Don't think in tables/rows — think in EAV bags of namespaced attrs

One entity carries attributes from several namespaces; adding a field needs no
migration. Generic code works on `:seon.ai/*`; provider code adds `:seon.ai.claude/*`
to the *same* entity. No "claude_messages table", no join, no migration.

### Every map key is a fully-namespaced keyword

```clojure
(defn process [{:keys [pattern paths]}] {:ok? true})        ; WRONG — bare keys
(defn process [{::keys [pattern paths]}] {::ok? true})      ; RIGHT — ::pattern resolves to this ns
```

Namespaced keys are load-bearing: a single Datalog query can join function specs
to the data they operate on, and "what accepts `:seon.agent.search/pattern`?"
becomes a DB query instead of a guess. `seon.db/transact!` won't even accept a
bare attribute. The keyword namespace IS a real code namespace that owns that
data's schema.

### Public fns carry `:malli/schema` — not `:pre`/`:post`, not hand-rolled checks

```clojure
(defn do-thing
  {:malli/schema [:=> [:cat ::do-thing-request] ::do-thing-response]}
  [{::keys [id option]}] ...)
```

Every schema'd public fn is **instrumented at runtime** — the program graph is
the roster (`seon.instrument/instrument-from-db!` at boot + after every hot
reload; the eval-tee wraps agent fns inline) and the wrapper validates args +
return on every call, *throwing* on a mismatch. The exceptions are structural,
never a name list (see the envelope section below); `SEON_INSTRUMENT` is an
emergency kill-switch, never a way to silence an error.
So a wrong schema is a runtime *bug*, not a doc nit: read the instrumentation
error and fix the root cause (you called it wrong, or the schema doesn't match
reality). `:pre`/`:post` gets none of this — not instrumented, not discoverable,
not generatively testable. Two sanctioned argument shapes: map-in/map-out
(preferred for accreting API surfaces) or fully-spec'd positional via `:catn`;
the invariant is that every arg is named, specced, validated — a bare/unspecced
arg is the violation, not a positional one.

Use concrete types. `:any`/`:some`/`:nil`/`[:maybe X]` are *banned* (rejected at
startup) — express optionality with `{:optional true}` in the `:map`, never
`[:maybe X]`. The one exception: a genuine third-party boundary where the value
is whatever an external lib hands back and there's no honest tighter type.

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

An agent's eval must survive a bad call and read the failure as *data* — the way
`seon.db/transact!` and `seon.agent.search/grep` do. Envelope verbs that are
`^:async` with non-simple shapes are STRUCTURALLY exempt from the throwing
instrumentation wrapper (`seon.instrument/async-unwrappable?` — computed from
the fn's real shape, never a name list) precisely because a throw would break
the `::ok?` contract; they validate in their own body. Exceptions-as-control-
flow break the loop; values let the agent inspect, recover, retry. (A genuine
*programmer* error deep in private code may still throw — errors-as-values is the
rule for the agent-facing surface.)

### Derive at render — don't store derived state or "mark-as-seen" flags

```clojure
;; WRONG — a stored counter / last-error / ack flag / notification queue
(swap! warnings conj {:msg "stale" :seen? false})

;; RIGHT — a section is a pure fn of the DB; renders only when the query has rows
(defn stale-section [db]
  (when-let [rows (seq (db/query {::db/db db ::db/query '[…]}))]
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
  (let [a (db/query {::db/db db …}) b (db/query {::db/db db …})] ...))
```

`d/q`/`d/pull`/`d/entity` are referentially transparent over a db value — it
can't change under you, so the "race" you think you have is usually re-reading
`@*conn*` three times instead of threading one snapshot. On the pod each deref
*reconstitutes* a fresh value from the store, so re-reading also costs. `db` is
the **first** parameter to functions; the live `*conn*` is *bound* for you —
never thread a connection or other opaque runtime object through agent-facing
call sites. When you truly need a fence against a concurrent writer, the
primitive is `:db.fn/cas` (pure data), not a re-read-and-check.

### Prefer `reduce`/`map`/`into` over mutable accumulator loops

An in-body `atom` or `loop`/`recur` to accumulate pure traversal state is a
place-oriented tell. Reach for `into` with a transducer, `reduce` (+ `reduced`
for early exit), or a pure recursive helper with accumulator args. State lives in
the call, not a mutable cell — output depends only on input.

### Async: CLJS native `^:async`/`await`, never core.async in the pod

`await` only works *inside* an `^:async` fn body (it's a macro asserting an async
env); a bare top-level `(await x)` throws "await can only be used in async
contexts". Agents get data, not Promises — the eval batch path auto-awaits. Never
run a second instrument pass in one process (it wedges async fns). Full detail in
the **`clojurescript`** skill — read it before touching pod async.

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

### In a `my.*` ns you author, the data/verb aliases just work

The short aliases `db/`, `plan/`, `message/`, `schema/` are wired into your
agent's HOME ns — and the system injects those same REAL `(:require …)` into
EVERY `my.<domain>` ns you author (even a bare `(ns my.expense)`), so
`db/query` etc. resolve there too — genuinely required, no magic, no manual
step. Writing the requires yourself is fine (then it's a no-op) and makes the
deps explicit:

```clojure
(ns my.expense
  (:require [seon.db :as db]
            [my.plan :as plan]
            [seon.agent.message :as message]
            [seon.schema :as schema]))

(defn total [xs] (reduce + xs))
(schema/register! ::amount :int)
```

**Full-qualification is the always-correct floor** — `seon.db/transact!`,
`seon.agent.message/user`, `my.ui/status-line` work from ANY ns with no require.
Use it whenever you skip the require, and ALWAYS for the `my.*` toolkit
(`my.ui/…`, `my.tile/…`, `my.data/…`, `my.kb/…`) — those are not aliased.

The lifecycle verbs `wait` `complete` `pause` `resume` `terminate` are refer'd in
your HOME ns only; call them from there, or fully-qualify
`seon.agent.lifecycle/complete`. Do NOT switch namespaces to reach a verb.

### Write a real test ns — `cljs.test/deftest`, not inline `assert`

When you "write a test", put it in a `my.<domain>-test` ns using
`cljs.test/deftest` + `is` — NOT a pile of inline `(assert …)` calls (one drive
produced 494 inline asserts for one "write a test" request). A deftest is
discoverable, re-runnable, and reports pass/fail as data:

```clojure
(ns my.expense-test (:require [cljs.test :refer [deftest is]]))
(deftest totals-sum
  (is (= 101 (my.expense/total [45 18 38]))))
```

Test patterns (async, fresh in-memory conn, awaiting capability verbs) live in
the **`clojure-testing`** skill.

## When to read which reference

| You're about to... | Read first |
|---|---|
| Model data, write a Datalog query, design identity/refs | the **`datahike`** skill + `docs/prds/agent-fsm/research/datahike-primer.md` |
| Write/debug pod async, `^:async`/`await`, self-host eval | the **`clojurescript`** skill |
| Write tests, fixtures, generators | the **`clojure-testing`** skill |
| Confirm any library's actual behavior | the vendored source in `reference-code/<lib>/` — never guess |
| See the file:line evidence behind every claim here | `docs/prds/agent-fsm/research/clojure-idioms-for-agents-2026-06-28.md` |
| Full conventions (Malli shapes, `.internal`, schema composition) | `docs/conventions.md` |
</content>
