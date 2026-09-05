---
type: prd
status: active
tags: [prd, runtime, platform, sci, database]
---

# Call preparation — the platform supplies what a function declares

Owner direction (2026-08-05): *"at the eval function level we are auto
injecting the db for all functions… it's a battery included feature and
we can expand what we offer through this mechanism and it's easy for
agents to override or not use"*, then: *"I'd prefer if metadata wasn't
required. Explicit declaration that the function needs a db is enough"*,
then: *"we should auto inject the conn too if they require it as their
input schema… querying for what needs what efficiently and then
providing this as a runtime or platform benefit."*

## 1. The vision, in one sentence

**A function's `:malli/schema` is a request to the platform**: whatever
supplied default it declares as an input, the runtime supplies when the
caller did not — so writing a function that needs the database means
writing `[:map [:seon.db/db :seon.db/database-value]]` and nothing else.

This is a platform benefit, not a library convenience. The agent writes
ordinary Clojure with an ordinary contract; the runtime does the wiring.
No annotation, no import, no threading a db through call sites, no
"how do I get a connection here" question — the question that today has
42 answers in first-party code and would have as many again in agent code.

## 2. What already exists (verified 2026-08-05)

Both halves exist and have never been connected.

**The declaration is already a fact, already derived.**
`:seon.fn.arity/input-refs` is computed from each function's schema at
index time (`src/seon/program.cljc:262-279`) and read at
`src/seon/sci/eval.clj:850,921`. Nobody maintains it, and it already
answers "what does this function want" as a query. The two batteries the
owner named have **51 existing declared consumers** in first-party code
alone: 42 functions declare `:seon.db/database-value` and 9 declare
`:seon.db/connection` (the worked examples in the repository
authority). They currently receive those values by being threaded them.

**The supplied defaults are already assembled per evaluation.**
`seon.effect/*request-context*` (`src/seon/effect.clj:26`) is bound at
`src/seon/sci/eval.clj:1600-1608` for every run form and carries
`:seon.db/connection`, `:seon.cluster.run/id`,
`:seon.cluster.run.form/ordinal`, `:seon.cluster.agent/id`,
`:seon.boot/cluster-name`, `:seon.flow/work-launcher`, and
`:seon.sci.admit/caps`. `seon.db/*conn*` (`db.clj:65`) holds the live
branch connection. (`seon.db/*read-evidence-sink*` is an observation SINK,
not an input — it is never a battery.)

**Nothing joins them.** Only `seon.db`'s own functions hand-resolve
`*conn*` (`resolve-database-value` / `current-database-value`). That is
the single bespoke instance, and generalizing it means DELETING it —
never leaving a second path.

So this PRD builds one join, not a subsystem: the context already knows
the answers; the graph already knows the questions.

## 3. The batteries

A supplied default is a key plus the declared provider that computes it from
the current evaluation context. Providers are fully qualified
symbols in a declared registry (ruling #50's producer representation —
never an inline fn, never a hand list in code). **Adding a battery is
declaring one row.** That is the whole extensibility story.

| Key | Schema | Provider computes from | Consumers today |
|---|---|---|---|
| `:seon.db/db` | `:seon.db/database-value` | `*conn*` at current basis | 42 |
| `:seon.db/connection` | same | `*conn*` / `*request-context*` | 9 |
| `:seon.cluster.agent/id` | `:seon.cluster.agent/id` | `*request-context*` | — |
| `:seon.boot/cluster-name` | `:seon.cluster/name` | `*request-context*` | — |
| `:seon.cluster.run/id` | `:seon.cluster.run/id` | `*request-context*` | — |
| `:seon.cluster.run.form/ordinal` | (ordinal) | `*request-context*` | — |

The first two are the owner's ask and are worth landing alone. The rest
are already in `*request-context*` and cost a declaration each; land only those a
real caller wants (the scheduled-fire path is the first candidate for
run/agent identity).

**Custody is a real decision, not an accident.** `:seon.db/db` is an
immutable value; `:seon.db/connection` is WRITE custody. The
repository authority frames the 9 connection-declaring functions as
exactly "which functions need cluster custody". Auto-supplying custody to
anything that declares it is consistent with ruling #20 and the
no-hobbling ruling — the bounded evaluation bounds effects, not callability —
but it is recorded here as a deliberate choice so nobody later reads it
as an oversight.

## 4. Querying for what needs what — efficiently

The naive implementation queries the program graph per call. That is the
wrong shape and would tax every invocation.

**The plan is derived once per function identity and cached, never
recomputed per call.** Concretely:

1. `:seon.fn.arity/input-refs` is ALREADY a committed fact — no new
   derivation at index time, nothing stored that is derived.
2. A function's **injection plan** is that fact intersected with the
   battery registry: the (usually empty, occasionally 1-2 element) set of
   supplied default keys it declares. This is a set intersection over data already
   in hand.
3. The plan is memoized per function identity in a process-local cache,
   invalidated when the program row changes (hot reload / redefinition
   already have that seam). This is the explicitly sanctioned case:
   *cache measured expensive derivations; never bifurcate into
   stored-fast and derived-slow paths.* The cache is an accelerator over
   the same derivation, not a second authority.
4. Per call, the work is: one hash lookup for the plan; if empty (the
   overwhelmingly common case) inject nothing and proceed; otherwise fill
   only keys the caller omitted.

**The empty-plan fast path is the design's performance claim** and must
be measured, not asserted: the cost of an interpreted call with no
declared batteries should be indistinguishable from today.

## 5. Semantics (the rules that make it safe)

- **The schema IS the declaration.** No injection metadata, no marker
  key, no opt-in annotation on consuming functions — owner-ruled. The
  only declarations are provider-side, one per battery.
- **The caller always wins.** Injection fills absences only; a supplied
  value is never overwritten. That is the override story.
- **Undeclared means untouched.** A function that did not declare a
  battery receives nothing.
- **Unused is free.** Maps are open (#48), so a declared-but-unused key
  changes nothing.
- **Unavailable is an error, never nil.** `*request-context*` is nil outside a
  run form (bare probes, some system paths). A declared battery that
  cannot be provided returns a flat `:seon.error` naming the missing
  supplied default — never a silently injected nil, which would violate
  no-stored-nil and hand the function a lie.
- **One mechanism.** `seon.db`'s bespoke elision is DELETED once the
  general seam exists, or the PRD records precisely why the connection
  case is genuinely different.

## 6. Seams — where injection happens

Decide by measurement, in this order:

1. **Agent-authored form calls** (the sci invocation seam) — the primary
   surface and the owner's "at the eval function level".
2. **Scheduled fires** — the fire's argument map gains declared
   batteries, so a maintenance function declaring `:seon.db/db` simply
   gets one (contract already requested by the scheduler lane).
3. **The effect request handler** — invocations that request capabilities.

Deep internal calls (a function calling another function inside one
evaluation) are NOT injected per call in v1: the dynamic vars remain
bound for the whole evaluation, so a deep callee can resolve ambiently
exactly as `seon.db` does today. If the measured per-call plan lookup is
negligible, expanding to every interpreted call is a later, evidence-led
step — not a v1 assumption.

## 7. Proof obligations

- a declared-and-absent battery is injected; the function queries live
  data with no db passed;
- a caller-supplied value is never overwritten;
- an undeclared function receives nothing;
- an injected-but-unused key changes no behavior;
- an unavailable default yields a flat error naming what was missing;
- a scheduled fire's function receives its declared battery;
- **measurement**: empty-plan call overhead versus today, reported as a
  number;
- `seon.db`'s bespoke elision is gone (or its exemption is argued).

## 8. Why this is the right shape

It is the same move the program graph keeps rewarding: a question that
looks like it needs a convention or a registry turns out to be already
recorded as a fact. "What does this function need?" was answerable before
anyone asked. This PRD only makes the runtime *act* on the answer — and
because acting is driven by declarations rather than a list, every future
battery is one row, and every future function that wants one writes an
ordinary contract and gets it.
