---
type: research
status: active
tags: [agent, flow, schema]
---

# Resolving a fn's required keys from context + auto-run

TL;DR — A tool/render fn declares the context it needs (`:seon.db/db`, the
calling agent `:seon.agent/id`, the time `:seon.render/at`) as OPTIONAL keys
on its request map. The ONE instrumentation wrapper (`seon.instrument`)
RESOLVES every declared-but-absent required key from the current context
(`db/current-agent-id` + `@db/*conn*`) just BEFORE input validation. Explicit
caller args always win. A small explicit map `required-key → (fn [ctx]
value)` is the whole extension surface. Auto-run is a new seed block that
finds the current ns's render fns and runs each through the same wrapper.

## Vocabulary — required keys resolved from context, NOT "injection magic"

Map this to Clojure primitives (owner directive): the mechanism is a fn
declaring its REQUIRED KEYS as optional request-map entries, and the eval
boundary RESOLVING each absent one from context — the same way `db` read fns
already default `conn` from `*conn*`. There is no new noun to learn:

- a required key is a normal namespaced map key with a registered schema;
- resolving it is `assoc`-ing a value the boundary reads from context before
  Malli validates the map;
- AsyncLocalStorage is NOT the feature — it is merely the internal SOURCE for
  the CURRENT-agent value (`db/current-agent-id`, set once by the loop's
  `db/with-agent`). The fn body never reads it; the boundary does, once,
  visibly. "One boundary that resolves context keys" replaces "every fn body
  reaches into an ambient var."

### Composition — a required key resolves to an entity ref

The agent's entity is the root of a small graph: it REFS a per-namespace
entity whose schema is scribed in that namespace (e.g. a `my.plan` entity
whose `:my.plan/*` attrs are `register!`ed in the `my.plan` ns; likewise a
per-ns kb/tile entity). A required key like `:seon.agent/id` resolves to the
calling agent's ref, and a fn that holds that ref reaches its per-namespace
entity by following it (`agent → :my.plan/of-agent → the plan entity`). So
"resolve the required keys from context" and "each ns owns its slice of the
agent's entity graph" are the same design seen twice: the key names the ref,
the ref names the data, the ns owning the data owns its schema. A fn
declaring `:seon.agent/id` therefore reads/writes PER-AGENT (it scopes to the
resolved ref); one that omits it is GLOBAL (`my.kb`). You know where data
goes by reading the arglist — not from an invisible binding.

## Why — the problem being killed

Today a tool/render fn body that needs the db or "which agent am I" either
takes it as an explicit positional/`:or {conn *conn*}` arg (db fns) or reads
an invisible ALS var deep in the body. The fn's spec does not honestly state
its dependencies, and the invisible read means the same fn can't be replayed
at `as-of t` or driven with a different db in a test without spelunking.

The fix: make the dependency a DECLARED optional request key; inject it once,
at the eval boundary, from a named registry. The spec becomes the honest
statement of what the fn needs; the eval log shows real data flowing; the
value is reproducible.

## The contract (binding — from `docs/seon/architecture/context.md`
§"Explicit dependencies")

- A map-in fn declares an injectable as an `{:optional true}` request key:
  `:seon.db/db`, `:seon.agent/id` ("me"), `:seon.render/at` (now / basis-t).
  Optional to the CALLER; the wrapper guarantees it PRESENT in the body.
- At the eval boundary, the wrapper inspects the fn's request `:map` schema;
  for every injectable key the schema DECLARES that the caller LEFT ABSENT,
  it fills the current value from the eval context. Declared-and-present is
  NEVER overwritten (explicit args win — tests / forensic replay / auto-run
  pass a different db/agent).
- The injectable REGISTRY is a small explicit map `injectable-key →
  (fn [eval-ctx] value)`. Adding a dependency = one registry entry + fns
  declare the key. ONE mechanism, no second wrapper.

Scope-by-signature falls out: a fn declaring `:seon.agent/id` writes/reads
PER-AGENT (stamps `:my.plan/agent me`, filters by it); one that doesn't is
GLOBAL (`my.kb`). You know where data goes by reading the arglist.

## WHERE it rides — the ONE instrumentation wrapper (real fns/lines)

The injection is a step on the SAME Malli instrumentation wrapper that already
validates every schema'd fn. Grounded in the live source:

- `src/seon/instrument.cljc`
  - `async-fschema` (L204-267) — the custom `reify m/Schema / m/FunctionSchema`
    whose `-instrument-f` (L241-267) IS the wrapper malli installs. Its body
    `(fn [& args] …)` (L249) is where input validation (L251-258), the call
    (L259 `(apply f args)`), and output validation (L260-267, on Promise
    resolve) happen. **The inject step goes at the TOP of this `fn`, before
    the `when wrap-in` input check (L251).**
  - `register-target!` (L269-305) — routes each fn to a wrapper:
    - skip-syms → nothing (L294)
    - sync fn → raw schema form + malli STOCK wrapper (L295-296)
    - async simple-fixed-arity `:=>` → `async-fschema` object (L301-303)
    - async variadic/multi-arity → raw form `{:scope #{:input}}` (L304-305)
  - `instrument-from-db!` (L317-388) — the boot pass; reads `:seon.fn/spec`
    rows, routes each through `register-target!`, then `mi/instrument!` once.

### The as-built change (Phase 1)

Generalize `async-fschema` into ONE `injecting-fschema` that handles the
map-in case for BOTH sync and async fns, adding the inject step:

1. inject declared-absent injectables into `(first args)` when it's a map;
2. validate input (unchanged);
3. call `f` (unchanged);
4. if the return is thenable → validate output on resolve (unchanged async
   path); else validate output SYNCHRONOUSLY (the sync path async-fschema
   previously passed through — restored so sync fns keep output validation).

`register-target!` routes ANY simple-fixed-arity `:=>` fn (sync OR async)
through `injecting-fschema`. Variadic / multi-arity fns keep the existing
stock/`{:scope #{:input}}` behavior — they don't take a single request map,
so injection does not apply. This is behavior-preserving for a fn that
declares no injectables (the map has no injectable keys → no injection →
identical validation), so it is safe to route every simple map-in fn through
it. NO parallel wrapper — the async wedge fix (`instrument-from-db-once!`,
L403-417) is untouched.

### Reading the declared keys (verified in the live pod)

```clojure
(let [s    (m/schema fn-schema)                 ; the :=> (or :function → per-arity)
      info (m/-function-info s)                 ; {:min :max :input :output}
      arg0 (first (m/children (:input info)))]  ; the request-schema ref/inline
  (mapv first (m/entries (m/deref arg0))))      ; the declared map keys
;; => [:seon.db/db :seon.agent/id :my.foo/x]   ; refs AND inline :map both work
```

`m/deref` resolves a registered ref (`::foo-request` → its `:map`) and passes
an inline `:map` through unchanged; `m/entries` returns ALL entries (optional
included). Intersect with the registry key-set → the injectables this fn
declares. Computed ONCE per fn at register time (memoized on the schema), not
per call.

## The eval-ctx SOURCE — knowable without per-call ALS threading

"Which agent is running" is knowable at any point inside an agent's eval:
`seon.agent.turn/run-turn!` (L474) wraps the whole eval pipeline in
`(db/with-agent id …)`, so `(db/current-agent-id)` returns the running
agent's id throughout eval — VERIFIED live: `(db/with-agent "X" #(db/current-agent-id))
=> "X"`. The db value is `@db/*conn*` (a fresh db value at call time — the
same default every db read fn already uses via `:or {conn *conn*}`).

The registry (CLJS-only, in `instrument.cljc`'s `:cljs` branch, `db` already
required):

```clojure
(def injectables
  {:seon.db/db     (fn [_] @db/*conn*)              ; the current db VALUE
   :seon.agent/id  (fn [_] (db/current-agent-id))   ; "me" from the with-agent scope
   :seon.render/at (fn [_] (db/basis-t))})          ; now / basis-t
```

`eval-ctx` arg is reserved for future per-call context; today the providers
read ALS/*conn* directly, so eval-ctx is `nil`. A provider returning `nil`
(e.g. `current-agent-id` outside a with-agent scope) MUST NOT inject the key
— injecting `nil` would violate "optional = absent, never store nil". So the
inject rule is: fill the key ONLY when the provider yields a non-nil value.

### Frozen-db nuance — solved by explicit-wins, NOT a new dynamic

The design's "frozen db per turn" (reproducible `as-of t`) matters for the
render pass, which HAS the frozen db. Rather than add a second dynamic var
for it, the render pass (auto-run + the existing block fns) passes the frozen
db EXPLICITLY; explicit-wins keeps it. Live agent tool calls omit db and get
`@*conn*` injected (fresh value — correct, matches every db fn's default).
One registry, no new dynamic — respects the ONE-mechanism rule.

## `:seon.render/at` — needs registering

`:seon.db/db` (`:any`) and `:seon.agent/id` are registered; `:seon.render/at`
is NOT (`schema-definition` → nil). Register it as an `:inst` (or basis-t
`:int`) when the first fn declares it. Phase 1 ships db + id (the two proven
injectables); `:seon.render/at` lands with its first consumer.

## Auto-run — a new seed block, reusing the block mechanism

The current-ns render fns become context via a NEW seed block (ONE render
mechanism — a block fn, NOT a second render path):

- New file `src/seon/agent/ctx/render_fns.cljs`, sibling of `namespaces.cljs`,
  with `render-fns-block` (`:seon.render/ai`) + `render-fns-block-html`
  (`:seon.render/html` twin).
- Wired into `seon.config/default-ctx-blocks` at priority ~30 (after
  `:namespaces` = 20, before `:canvas` = 35 — group 3 in context.md's
  order, right after the stable code it belongs to).
- The block:
  1. resolves the agent's current ns (`ctx/current-ns`, same as
     `namespaces-block` L418-426);
  2. queries the program graph for that ns's fns whose OUTPUT schema is a
     render type — `:seon.fn/sym` + `:seon.fn/spec` rows for the ns, filtered
     to those whose parsed `:=>` output, resolved via malli, is a `:map`
     declaring `:seon.render/ai` / `:seon.render/html` (or the render type
     directly);
  3. runs each through the injecting wrapper by calling it with the frozen db
     passed explicitly, inside `(db/with-agent id …)` so `:seon.agent/id`
     injects — bounded via the exec service + errors-as-values (a throw →
     a `:seon/error` block, never a loop crash);
  4. collects the `:seon.render/ai` strings (ai block) / `:seon.render/html`
     hiccup (html twin), positioned after the stable code blocks.

Auto-run render fns are SYNC (they join into the prompt string like every
other block fn). A render fn that needs to be async stashes and re-references
per the pod's Promise rules — out of scope for v1.

## Where the real code fights the design (flagged)

1. **`skip-syms` opt-out fns get NO wrapper** — the pure capability-wrapper
   nses (`seon.agent.fs`, `seon.agent.search`, `seon.agent.message`) and the
   listed `my.plan` verbs (`step!`/`done!`/…) register nothing, so they get
   NO injection. If a `my.plan` verb needs `:seon.agent/id` injected to scope
   per-agent, it must EITHER read `db/current-agent-id` in its body (it is
   inside the with-agent scope — works) OR be removed from `skip-syms` and
   converted to a real injected map-in fn. Phase 2 decision per verb; note it
   in the my.plan conversion. This is not a blocker — the ALS source is still
   there for a skipped verb's body to read directly.

2. **`instrument-from-db-once!` is a hard once-per-process gate** (the async
   double-wrap wedge, L390-417). New fns defined AFTER boot are wrapped inline
   by the eval-tee, so a freshly-authored render fn IS wrapped. Auto-run
   depends on this holding — a current-ns render fn the agent just wrote must
   be instrumented (wrapped) for injection to fire. Verify in the Phase 2
   live drive that an agent-authored render fn gets its db/id injected.

3. **`:seon.db/db` is `:any`** — injecting a db VALUE satisfies it trivially;
   no bridge work needed. If a stricter db schema is ever wanted, it is a
   separate change.

## Test / live-proof plan

- Phase 1 unit (`seon.instrument-test` or a focused ns): a fn declaring
  optional `:seon.db/db`+`:seon.agent/id`, called WITHOUT them, receives
  injected values; called WITH explicit ones, keeps them; a fn NOT declaring
  them is untouched; a provider yielding nil does not inject the key.
- Phase 1 live: an agent evals a probe map-in fn (declaring the keys) without
  passing db/id and it reads its own id + a live db.
- Phase 2 live: a real DeepSeek agent authors a `defn` returning a render in
  its ns → it auto-appears as context + tile with no explicit call; it calls
  a storage fn without db/id → works, writing to ITS record.

## Implementation status (as of 2026-07-02)

Phase 0 (this doc) is committed on `feature/agent-fsm`. Phase 1 was BUILT and
PROVEN but is being MOVED to the fresh `feature/agent-scope` branch (agent-fsm
is finishing/merging); the working-tree code below is uncommitted so the
coordinator can move it. What exists and is verified:

- `src/seon/instrument.cljc` — the required-key resolution on the ONE wrapper:
  - `injectables` — the registry (`:seon.db/db` → `@db/*conn*`,
    `:seon.agent/id` → `db/current-agent-id`).
  - `declared-injectables` — reads a fn's `:=>` first-arg map keys ∩ registry
    (via `m/-function-info` → `m/deref` → `m/entries`); handles inline maps,
    registered refs, non-map args.
  - `injecting-fschema` — GENERALIZES the old `async-fschema`: resolves
    declared-absent required keys into the first (map) arg before input
    validation, then validates output synchronously (sync return) OR on
    Promise resolution (async return). ONE wrapper, sync + async.
  - `register-target!` — routes EVERY simple fixed-arity `:=>` fn (sync or
    async) through `injecting-fschema`; variadic/multi-arity keep the stock
    path (injection is the single-map-arg case only).
- `test/seon/instrument_inject_test.cljs` — the contract unit test (resolves
  absent keys, explicit-wins, nil-provider-leaves-absent, no-injectable
  untouched, `declared-injectables` static reads).

Live proofs (default pod, 7890): a probe map-in fn declaring both keys,
called inside `db/with-agent "X"` WITHOUT them, received `{:got-id "X"
:got-db true}`; called WITH a valid explicit id, kept it (`:got-id
"OTHER…"`); called OUTSIDE a scope, left id absent (`:got-id nil`); an
`^:async` probe injected + awaited-validated identically. Full CLJS suite
green with the change in the build: **932 tests / 4298 assertions, 0
failures**.

LANDED on `feature/agent-ctx` (2026-07-02, roadmap item 1 remainder):
`:seon.render/at` registered (`seon.render`, basis-t `:int` — the tx-id is
the reproducible coordinate; wall-clock derives from `:db/txInstant`) with
its `injectables` entry (`(some-> db/*conn* deref db/basis-t)`); the
`my.plan` verbs REMOVED from `skip-syms` and converted to resolved map-in
fns — request schemas declare `:seon.agent/id {:optional true}`, the
in-body `scoped-agent` ambient read is deleted (`internal/agent-ref` just
builds the lookup ref), and the semantic failures keep their `::ok?`
envelopes (shape-invalid input now surfaces as the structured instrument
error at the eval boundary). Live-proven on the default pod: `step!` with
no id → the datom stamped `:my.plan/agent → root`; a probe declaring
`:seon.render/at` → the live basis-t injected.

Still NOT started: the `render_fns` auto-run seed block; the Phase 2 live
DeepSeek drive.
