---
type: research
status: active
tags: [research, sci, render, caching]
---

# The bounded evaluation, renderers through it, and ctx/schema sharing

Research lane, 2026-07-31. Question set: (1) what the door actually is
today, file:line; (2) what it costs to put agent-authored RENDERER
functions through it; (3) what a sci `ctx` can share across agents;
(4) whether compiled schema state can be derived once per cluster basis
and shared; (5) ranked recommendations.

Probes are in `tmp/sci-fork-sharing-probe.clj`,
`tmp/sci-door-cost-probe.clj`, `tmp/sci-stale-interrupt-fn-probe.clj`,
`tmp/sci-fn-entries-probe.clj`. All run load-only (`clojure -M:dev`), no
cluster writes.

## 0. The headline, up front

**The time limit does not survive the function that was defined under
it.** Sci closes `:interrupt-fn` over the fn object at CREATION time
(`reference-code/sci/src/sci/impl/fns.cljc:40`, `:64`, `:152` —
`interrupt-fn# (:interrupt-fn ~'ctx)` is a `let` outside the returned
`fn`, and the per-entrance check `(when-not (nil? interrupt-fn#)
(interrupt-fn#))` at `:52`, `:77`, `:166` reads that closed-over value,
never the calling ctx). Assoc'ing `:interrupt-fn` onto a ctx therefore
guards only the fn objects created DURING that evaluation.

Falsified (`tmp/sci-stale-interrupt-fn-probe.clj`, 500 ms limit):

```
1 inline (loop [] (recur))          => interrupted (outcome :time)
2a (defn spin [] (loop [] (recur))) => #'user/spin
2b (spin)   in a LATER evaluate     => HUNG (no limit fired in 5 s)
3  fn created on an unarmed ctx,
   then called through evaluate     => HUNG (no limit fired in 5 s)
```

Both hangs are live today, not hypothetical:

- **Case 2** is the ordinary run fold. `seon.cluster.loop` forks one ctx
  per run (`src/seon/cluster/loop.cljc:967`) and every form of the plan
  evaluates on it (`src/seon/sci/eval.clj:763` uses a supplied ctx AS
  GIVEN, by contract). Form 1's `defn` closes over form 1's
  interrupt-fn, whose scheduled task was cancelled by `stop!`
  (`src/seon/sci/eval.clj:272`, called in `finally` at `:935`), so its
  `reached?` volatile can never become true again. Form 2 calling that
  fn spins forever on a `:compute` platform thread.
- **Case 3** is acquisition. `sci.eval/acquire!` is called on a ctx that
  is a bare `(sci/fork (sci.eval/base))` with no `:interrupt-fn`
  (`src/seon/cluster/loop.cljc:967-970`), and it re-evaluates every
  agent-authored `defn`/`deftest` source through `sci/eval-form`
  (`src/seon/sci/eval.clj:513`, `:527`, `:548`). Every function an agent
  wrote in a previous run is therefore re-created permanently unguarded.
  Calling any of them — from a later eval, or from a renderer — has no
  time limit at all.

The only backstop that fires is the caller's submission deadline:
`submit-evaluation!!` waits `(* 2 time-limit-ms)`
(`src/seon/cluster/loop.cljc:304-319`) and on expiry marks the
submission `::wedged?` (`src/seon/flow.clj:509-518`). That *reports* the
wedge; it does not stop the thread. The spinning compute thread is
permanently consumed. With `:seon.config.flow.compute/concurrency`
bounded ≈ cores, N such spins wedge the whole cluster's compute.

`acquire!`'s unguarded `eval-form` is additionally a direct execution
bypass, not only a mis-arming: sci evaluates a `def`'s metadata map
(`reference-code/sci/src/sci/impl/analyzer.cljc:830-838` →
`reference-code/sci/src/sci/impl/evaluator.cljc:28`), so arbitrary agent
code embedded in a `defn` attr-map runs during acquisition, outside any
armed boundary.

## 1. The door today

There is **no `src/seon/sci/interrupt.clj`** and no `seon.sci.interrupt`
namespace anywhere in `src/` (drift — see §6). The door is two files:

| Piece | Owner | file:line |
|---|---|---|
| `:interrupt-fn` construction + arming | `seon.sci.eval/arm` | `src/seon/sci/eval.clj:246-282` |
| deadline flip | one shared daemon `ScheduledThreadPoolExecutor` (`"seon-sci-time-limit"`, `setRemoveOnCancelPolicy true`) | `src/seon/sci/eval.clj:231-238`, `:259-262` |
| uncatchable stop | `sci.interrupt/interrupt!` | `src/seon/sci/eval.clj:269` ↔ `reference-code/sci/src/sci/interrupt.cljc:32-42` |
| interrupt recognition | `seon.sci.eval/interrupted?` (reads `:sci.impl/interrupt` from ex-data) | `src/seon/sci/eval.clj:213-221` |
| installation on the ctx | `(assoc (or ctx (sci/fork (base))) :interrupt-fn interrupt-fn)` | `src/seon/sci/eval.clj:763-764` |
| interrupt-aware core | `sci.interrupt/clojure-core` + `clojure-string` in the base ctx | `src/seon/sci/eval.clj:154-156` |
| output capture + cap | `sci/out`/`sci/err` → one `StringWriter`, capped by `:seon.config.eval.result/max-string` | `src/seon/sci/eval.clj:288-297`, `:787-789` |
| value caps (depth/collection/string/nodes) | `seon.sci.admit/admit`, called INSIDE the armed boundary before disarm | `src/seon/sci/admit.clj:304-354`, invoked `src/seon/sci/eval.clj:886-894` |
| per-node re-arm during realization | `((:interrupt-fn state))` at every projected node | `src/seon/sci/admit.clj:264` |
| disarm | `stop!` in `finally` | `src/seon/sci/eval.clj:935-936` |

Config dials (all database facts, declared once in the EDN population):

- `:seon.config.eval/time-limit-ms` — `resources/seon/schema/config.edn:15`,
  shipped `30000` at `config/default.edn:39`; read into the cluster value
  and passed per request at `src/seon/cluster/loop.cljc:1032-1033`;
  the per-request schema is `:seon.sci.eval/time-limit-ms [:int {:min 1}]`
  (`resources/seon/schema/eval.edn:7`).
- `:seon.config.eval.result/max-depth|max-collection|max-string|max-nodes`
  — `resources/seon/schema/admit.edn:5-8`, shipped `12 / 64 / 4096 / 4096`
  at `config/default.edn:20-26`, composed as `:seon.sci.admit/caps`
  (`resources/seon/schema/admit.edn:20`).
- `:seon.config/on-core-error` — travels WITH the request
  (`resources/seon/schema/eval.edn:22-24`), consumed at
  `src/seon/sci/admit.clj:275-280`.

**Executor / call path.** `seon.cluster.loop` → `submit-evaluation!!`
(`src/seon/cluster/loop.cljc:304-319`) → `seon.flow/submit!!`
(`src/seon/flow.clj:481-523`) with `::workload :compute` and
`::time-limit-ms (* 2 request-limit)`. The launcher graph runs the
work-fn on a **virtual-thread-per-task executor owned by the launcher**
(`src/seon/flow.clj:135-137`, `:416`, `::task-executor`), with fixed-buffer
backpressure at `:seon.config.flow.compute/queue-depth` and parallelism
`:seon.config.flow.compute/concurrency` (`src/seon/flow.clj:409-425`).
Note the divergence from `seon.sci.eval/evaluate`'s own docstring
(`src/seon/sci/eval.clj:728-731`), which claims the caller's thread
"must be a `:compute` platform thread": it is in fact a virtual thread
from `virtual-task-executor`. A spinning eval on a virtual thread does
not park, so it pins its carrier — same wedge, different accounting.

**Is there any path where agent-authored code executes outside the
door?** Yes, three, all confirmed:

1. `acquire!` / `install-program-row!` — `sci/eval-form` on an unarmed
   ctx (`src/seon/sci/eval.clj:513`, `:527`, `:548`; caller
   `src/seon/cluster/loop.cljc:967-970`). Evaluates agent source and
   agent-authored `def` metadata with no limit.
2. Any previously-defined agent fn called from a later evaluation —
   §0 case 2, the stale closed-over interrupt-fn.
3. `seon.render/render` invokes `(projection unit)` after
   `requiring-resolve` on a **qualified symbol taken from the unit's own
   data** (`src/seon/render.clj:204-209`). It is not a bypass for agent
   SCI code — probed: `(requiring-resolve 'my.agents.foo/render-ai)`
   throws `Could not locate my/agents/foo…on classpath`, caught at
   `src/seon/render.clj:206`, so an agent-namespaced renderer today
   returns `::unresolvable` and never runs. It IS an arbitrary-JVM-var
   invocation surface: any qualified symbol that resolves on the
   classpath is invoked with one argument. That is safe only while every
   `:seon.render/ai` / `:seon.render/html` declaration is first-party
   (block declarations are installed by `seon.render.block/install-tx`,
   `src/seon/render/block.clj:1059`). The moment an agent can write a
   render declaration fact, this becomes a direct call into
   first-party JVM code and must be constrained to symbols the agent
   could have authored.

So: **agent-authored renderers cannot execute at all today**, which is
why the owner's ruling is a build, not a repair — but the door they must
route through is broken for exactly the shape a renderer has (a fn
defined once, invoked many times later).

## 2. Renderers through the door — measured constraints

Measurements (`tmp/sci-door-cost-probe.clj`, M-series, `clojure -M:dev`,
warmed 200 iterations; renderer body
`(defn render-html [u] [:div {:id (str "b-" (:id u))} (str (:title u))])`):

| Path | Cost |
|---|---|
| A — full `seon.sci.eval/evaluate` (read + arm + eval + admit) | **100.8 µs/op** |
| B — `sci/eval-form` of the pre-read form, interrupt-fn armed | 3.2 µs/op |
| C — same, no interrupt-fn | 2.5 µs/op |
| D — `arm` + `stop!` alone (schedule + cancel) | **4.0 µs/op** |
| F — the interrupt-fn itself | **7.8 ns/call** |

Readings:

- **The per-entrance check is free at renderer scale.** 7.8 ns × a
  renderer's fn entries. A block-shaped renderer costs single-digit to
  low-hundreds of entries; even 10 000 entries is 78 µs. Sci's own note
  that the hook "is executed on every `fn` body entrance, so it's
  worthwile to optimize performance"
  (`reference-code/sci/doc/interrupt.md:50`) is satisfied by the existing
  design — the flag is flipped by a scheduled task and the fn does no
  clock arithmetic (`src/seon/sci/eval.clj:246-262`).
- **Arming is NOT free at renderer scale.** 4.0 µs per arm/disarm is
  ~125% of the renderer's whole interpreted cost (B = 3.2 µs). One
  `ScheduledThreadPoolExecutor.schedule` + `cancel` per block per
  re-render, on a shared single-threaded timer, is a real contention
  point when a keyframe re-renders every block.
- **Per-render `evaluate` is the wrong unit: 100.8 µs, ~40× the actual
  interpretation.** The overhead is the reader (`one-event`,
  `src/seon/sci/eval.clj:385-400`), `reader-context` /
  `namespace-bindings` snapshots (`:772`, `:876`), the program-row
  machinery (`:320-343`, `:452-464`) and `admit`. None of that is
  meaningful for a render: a render has no declaration to publish, no
  namespace context to commit, and its output is hiccup or prose, not an
  agent-visible EDN projection.

**Design constraint, therefore:** a renderer must go through the door's
*protections*, not through `evaluate`'s *plan-form pipeline*. The right
shape is a third operation in `seon.sci.eval` beside `evaluate` and
`acquire!` — call it the render invocation — that (a) arms once per
render PASS (not per block), (b) calls an already-installed sci fn value
directly with no reading, no program-row derivation and no namespace
snapshotting, (c) bounds the returned hiccup/text with the existing
`admit` caps while still armed, and (d) returns a flat `:seon.error`
value on interrupt. Amortising the arm across a pass turns 4.0 µs/block
into 4.0 µs/pass.

That only works once §0 is fixed, because (b) invokes a fn created
earlier — precisely the case whose interrupt-fn is stale.

**The dial.** Reuse the one door's dials; do not add a render-specific
set. `:seon.config.eval/time-limit-ms` already exists, is per-request in
the schema (`resources/seon/schema/eval.edn:7`), and the request carries
the number — so a render pass can pass a smaller value from the same
dial family without a second registration owner. If the owner wants a
distinct render budget, the honest form is ONE additional leaf under the
same `:seon.config.eval` prefix consumed by the same `arm`, never a
parallel caps/limit set: `:seon.sci.admit/caps` must stay the single
output-bound owner (it already doubles as the printed-output cap,
`src/seon/sci/eval.clj:288-297`). A second dial roster would be the
`flow-config-dials-have-two-registration-owners.md` defect repeated.

**Renderer failure today.** `seon.render/render` is total by contract
(`src/seon/render.clj:41-49`) and returns two flat error values:
`::projection-failed` naming the symbol and the throwable class
(`src/seon/render.clj:210-217`) and `::unresolvable` (`:218-222`). A
renderer interrupt would arrive as a throwable and be caught by that
same `catch Throwable` at `:210` — which is **wrong for the interrupt**:
`seon.sci.admit` deliberately re-throws sci's interrupt rather than
swallowing it (`src/seon/sci/admit.clj:269-270`), and the router must do
the same or a time-limited renderer would be silently reported as an
ordinary projection failure while the interrupt marker is discarded. The
block consumer already degrades correctly: `seon.render.block/surface`
hands the unit to the router and reports what the router says
(`src/seon/render/block.clj:366-404`), so a flat error value per block is
already the omit/degrade path the ruling asks for. What is missing is
`interrupted?` awareness at `src/seon/render.clj:210`.

## 3. Ctx sharing across agents

`fork` is one line: `(update ctx :env (fn [env] (atom @env)))` —
`reference-code/sci/src/sci/core.cljc:318-323`. It allocates ONE atom
and copies a reference to the same persistent map. Measured:

- **fork: 72 ns/op** (`tmp/sci-fork-sharing-probe.clj` step 6, 100 000 forks)
- **`sci/init {}`: 0.16 ms** (step 7)
- the forked env value is `identical?` to the parent's at fork time (step 5b)

So fork is cheap enough **per eval**, let alone per agent. It is not a
cost question.

**What is structurally shareable — and what leaks.** Everything in
`:env` is a persistent map, so structure is shared, but the *values* in
it are mutable `sci.lang.Var` objects. Probed (`tmp/sci-fork-sharing-probe.clj`):

```
1. base defn visible in both forks:                       [1 1]
2. NEW def in fork a, seen by b?   Unable to resolve symbol: lib/only-a
2b.                seen by base?   Unable to resolve symbol: lib/only-a
3. RE-def of an EXISTING base var from fork a
     -> b sees: 999    base sees: 999          <-- LEAK
4. alter-var-root on a base var from fork a
     -> b sees: 7                              <-- LEAK
```

Source confirms and explains it: `init-var!` creates a Var **only when
the name is absent** (`reference-code/sci/src/sci/impl/analyzer.cljc:780-793`),
and `eval-def` then reuses `prev` and calls `(vars/bindRoot prev init)`
— mutating the shared Var object in place before assoc'ing it back
(`reference-code/sci/src/sci/impl/evaluator.cljc:33-43`).

The rule is therefore exact, and it is not the one `sci/fork`'s
docstring implies:

> **fork isolates NEW names only. Any name already present in the shared
> parent is a shared mutable cell, and re-defining it in a fork changes
> it for the parent and every sibling.**

Consequences for a shared per-agent ctx design:

- **Installed core namespaces are safe to share** as long as nothing
  re-defines them — but an agent CAN: `(in-ns 'clojure.string) (defn
  join [& _] :pwned)` would mutate the shared `clojure.string` Var for
  every agent in the process. Today this is contained only because each
  run forks `base` and `base` is rebuilt per process, not per run — the
  mutation survives for the life of the JVM. This is a live containment
  gap independent of the renderer work.
- **First-party installed fns** (`my.run/wait`, `my.message/send`,
  `seon.schema/register!`, the whole `clojure.test` namespace —
  `src/seon/sci/eval.clj:144-182`) are `copy-var` Vars in the shared
  base: same exposure.
- **Per-agent namespace state IS cleanly separable**, because it is a
  distinct namespace name (`my.agents.<id>`,
  `src/seon/sci/eval.clj:191-198`) and the maintained SCI fork exposes an
  exact snapshot/install seam for it:
  `namespace-state` / `install-namespace-state!`
  (`reference-code/sci/src/sci/core.cljc:723-741`),
  `namespace-bindings` / `install-namespace-bindings!` (`:684-702`,
  `:743+`), `namespace-interns` (`:704-721`). `eval.clj` already uses all
  of these (`:504`, `:561`, `:687`, `:782`).

**Recommended shape: fork-per-agent-per-basis, not fork-per-eval.**
Because fork is 72 ns and `acquire!` is the expensive part (it queries
four Datalog patterns, topologically orders namespaces and re-evaluates
every agent fn — `src/seon/sci/eval.clj:564-724`), the unit worth caching
is *one ctx per (agent, database basis-t)*, invalidated when the basis
advances past the agent's own program rows. A render pass then forks that
ctx (72 ns) and arms once. What must NOT be shared is a ctx whose env
already contains another agent's Vars, because of the re-def leak above.

## 4. Schema sharing

**How compiled schema state reaches an eval today.** Two mechanisms, and
they are not the same one:

1. A **process-global** activation: `seon.schema/activate!` validates and
   atomically installs a complete `{schema-key form}` set into the one
   `!schema-state` atom and the Malli default registry
   (`src/seon/schema.cljc:2010-2025`, `activate-projection!` at
   `:1995-2006`, `!schema-state` at `:460-464`). It is called exactly once
   in `src/`, at load time from the JVM declaration snapshot:
   `src/seon/cluster.clj:75` — `(schema/activate! (schema/snapshot))`.
   Nothing per-cluster calls it. This is the collapse the open issue
   `docs/seon/issues/eval-time-schema-and-test-rows-have-no-recurring-proof.md`
   names: a process-global activation cannot express two clusters at two
   bases.
2. An **immutable per-basis projection VALUE** threaded on the ctx under
   `:seon.schema/projection`. Built by
   `schema/projection-from-database db [reusable]`
   (`src/seon/schema.cljc:1859-1894`) from exactly four Datalog reads
   (`:seon.schema/key`+`/form`, `:seon.fn/spec`, `:seon.fn/source`), and
   reused without recompilation when the canonical fingerprint of the
   queried rows equals the supplied projection's
   (`src/seon/schema.cljc:1842-1848`,
   `:seon.schema.projection/fingerprint`). `acquire!` builds it once
   (`src/seon/sci/eval.clj:572-573`), threads it through every install
   (`:676-683`), the run fold carries it (`src/seon/cluster/loop.cljc:974`,
   `:1030`, `:1157`, `:1191`), and `evaluate` prefers the ctx's
   (`src/seon/sci/eval.clj:794-796`).

**Can compiled predicates be derived once per cluster basis and shared
by every agent's evals?** Yes, and mechanism (2) already IS that
mechanism. Verified that nothing agent-specific enters compilation:
`projection-from-database` takes only `db`; its inputs are schema rows,
function contract rows and function source rows, plus process-global
`(core-predicate-functions)` (`src/seon/schema.cljc:1856`); no agent id,
no ctx, no namespace. Predicate owners resolve by `requiring-resolve` of
the predicate symbol (`src/seon/schema/edn.clj:317-321`,
`src/seon/schema.cljc:117`) — process-global compiled fns, pure.
Per-evaluation isolation is achieved not by a private projection but by
a **registration delta overlay**: `begin-registration-delta` seeds an
atom from exactly that projection's forms and publishes nothing
(`src/seon/schema.cljc:2059-2075`), and `call-with-registration-delta`
binds it dynamically for one synchronous eval (`:2077-2100`). That is the
correct shape and needs no change.

**What invalidates a shared projection:** a committed change to any
`:seon.schema/key`+`/form`, `:seon.fn/spec` or `:seon.fn/source` datom —
i.e. the fingerprint at `src/seon/schema.cljc:1842-1848`. The reuse test
is already content-based rather than basis-based, so a basis that
advances without touching those rows costs one fingerprint comparison
and no recompilation.

**In R45's terms (derive-once / install / attach)**
(`docs/prds/sci-execution-runtime/research/preprocessing-design-2026-07-23.md`):

| Stage | Exists | Gap |
|---|---|---|
| **derive-once** — compile the projection from db rows | YES, `projection-from-database` + fingerprint reuse (`schema.cljc:1859-1894`, `:1842-1848`) | no holder: it is re-derived per RUN at `src/seon/sci/eval.clj:572` because nothing caches it per (cluster, basis) |
| **install** — put agent program + bindings into a ctx | YES, `acquire!` (`src/seon/sci/eval.clj:564-724`) using the maintained SCI namespace-state APIs | runs unarmed (§0 case 3); runs per RUN rather than per (agent, basis) |
| **attach** — hand the derived value to an evaluation | YES, `:seon.schema/projection` on the ctx (`:794-796`) plus the per-eval overlay (`schema.cljc:2059-2100`) | correct as-is |

So schema sharing is not a new mechanism to build; it is a **holder** to
add (a per-cluster derived-projection cache keyed by fingerprint) and a
process-global activation to retire in favour of the value that already
exists. The renderer question needs the same holder: a render pass must
attach a projection, not activate one.

## 5. Recommendations, ranked

Each names its owner file and a falsifiable acceptance test.

**R1 (blocker). The interrupt-fn must not be stale.** Owner:
`src/seon/sci/eval.clj:246-282` + `:763`. Sci closes the fn over the ctx
at creation, so an armed ctx cannot re-arm a previously created fn. Two
candidate shapes, both keeping ONE mechanism:

- (a) install a **process-stable indirection** as the ctx's
  `:interrupt-fn` — one fn closed over a `ThreadLocal` holding the
  current arming — so every fn ever created on any Seon ctx reads the
  *current* thread's deadline. `arm` then sets/clears the ThreadLocal
  instead of producing a new closure. This makes case 2, case 3 and the
  renderer case all correct with one change, and keeps the 7.8 ns cost
  (a ThreadLocal get is ~1-2 ns).
- (b) re-create every fn under the current arming — i.e. fork + reinstall
  per eval. Rejected: that is `acquire!` per form, ~orders of magnitude
  more expensive, and it re-introduces the "form 2 cannot see form 1's
  defs" defect the current contract exists to prevent
  (`src/seon/sci/eval.clj:72-81`).

Recommend (a). **Acceptance:** `tmp/sci-stale-interrupt-fn-probe.clj`
cases 2b and 3 both return `:seon.cluster.eval/interrupted-at` within
~1× the limit instead of HUNG, as a `deftest` under `test/seon/sci/`.

**R2 (blocker). `acquire!` must evaluate inside an armed boundary.**
Owner: `src/seon/sci/eval.clj:564-724` and its caller
`src/seon/cluster/loop.cljc:967-970`. Acquisition re-evaluates arbitrary
agent source (and agent-authored `def` metadata,
`reference-code/sci/src/sci/impl/evaluator.cljc:28`) on a ctx with no
`:interrupt-fn`. Arm once for the whole acquisition with the same
`:seon.config.eval/time-limit-ms` family; a refusal returns a flat
`:seon.error` value and the run reports it rather than the JVM wedging.
**Acceptance:** a test that commits an agent fn whose attr-map spins,
then proves `acquire!` returns an error value within the limit.

**R3 (high). Renderer invocation is a third operation, not `evaluate`.**
Owner: `src/seon/sci/eval.clj` (new op) + `src/seon/render.clj:204-209`
(the seam that would call it). Measured: `evaluate` is 100.8 µs vs 3.2 µs
of actual interpretation, and `arm`/`stop!` is 4.0 µs. Arm once per
render pass, invoke installed fn values directly, bound the output with
the existing `:seon.sci.admit/caps`. **Acceptance:** a benchmark in
`tmp/` showing per-block cost within ~2× of raw `sci/eval-form` (target
< 10 µs/block) with the interrupt still cutting a spinning renderer.

**R4 (high). `seon.render/render` must not swallow the interrupt.**
Owner: `src/seon/render.clj:210-217`. Its `catch Throwable` currently
turns an uncatchable sci interrupt into `::projection-failed`, discarding
the marker. Mirror `src/seon/sci/admit.clj:269-270`: re-throw when
`seon.sci.eval/interrupted?`, or emit a distinct `::projection-interrupted`
flat value so the block omits/degrades for the right reason.
**Acceptance:** a test that a time-limited renderer yields an error value
whose kind names the interrupt, and that the router still never throws.

**R5 (high). Retire the process-global schema activation in favour of
the per-cluster projection value, and add the holder.** Owner:
`src/seon/schema.cljc:1995-2025` + `src/seon/cluster.clj:75`, with the
cache beside the cluster's other per-cluster state. The projection is
already pure, per-basis and fingerprint-reusable
(`src/seon/schema.cljc:1842-1894`); what is missing is that
`acquire!` re-derives it per run (`src/seon/sci/eval.clj:572`).
**Acceptance:** two clusters at different bases each observe their own
projection in the same JVM; and a second run at an unchanged basis
performs zero schema recompilation (assert the returned projection is
`identical?` to the cached one). This closes part of
`eval-time-schema-and-test-rows-have-no-recurring-proof.md`.

**R6 (medium). Cache one ctx per (agent, basis) rather than forking and
acquiring per run.** Owner: `src/seon/cluster/loop.cljc:960-972`. fork is
72 ns; `acquire!` is four Datalog queries plus a re-evaluation of every
agent fn. Renderers re-executing on every re-render make per-run
acquisition untenable. **Acceptance:** measured acquisition count per run
drops to zero when no `:seon.fn/source`/`:seon.ns/*` datom changed since
the cached basis; and the re-def leak (§3) is proven not to cross agents,
by a test where agent A re-defines `clojure.string/join` and agent B
still sees the original.

**R7 (medium). Shared-base Var mutation is a containment gap.** Owner:
`src/seon/sci/eval.clj:142-189`. Probe step 3/4 plus
`evaluator.cljc:33-43`: an agent can `(in-ns 'clojure.string) (defn join
…)` or `alter-var-root` a base Var and change it for every agent for the
life of the JVM. Options are to make the base's namespaces
non-redefinable, or to make each agent's ctx carry a copied core rather
than the shared one. Needs an owner ruling — it trades memory against
isolation. **Acceptance:** a test that a re-def inside one agent's
evaluation leaves another agent's resolution of the same symbol
unchanged.

**R8 (low, but file it). The recorded diagnostic misreads the
zero-binding loop, and the limit overshoots.** Measured
(`tmp/sci-fn-entries-probe.clj`, 500 ms limit):

```
(loop [] (recur))            fn-entries 1          duration 2070 ms  :time
(loop [i 0] (recur (inc i))) fn-entries 38,846,465 duration  505 ms  :time
((fn f [] (f)))              fn-entries 8,441      duration   12 ms  :error (stack)
(doall (range))              fn-entries 7,911,924  duration  503 ms  :time
(reduce + (range))           fn-entries 22,045,735 duration  505 ms  :time
(Thread/sleep 3000)          fn-entries 0          duration    1 ms  :error (unresolved)
```

The interrupt-aware core is doing its job (`(doall (range))` and
`(reduce + (range))` both cut at ~505 ms, which is exactly why
`sci.interrupt/clojure-core` is installed at `src/seon/sci/eval.clj:155`).
But `(loop [] (recur))` — sci's own documented example
(`reference-code/sci/doc/interrupt.md:32-33`, which claims "~1 second")
— overshoots 4× and records ONE fn entry. That contradicts the meaning
`resources/seon/schema/eval.edn:1-6` and
`src/seon/sci/eval.clj:22-32` assign to the diagnostic ("12 entries reads
as blocked in a host call"): here a pure interpreted spin reads as
blocked. The mechanism was not run to ground in this lane. **Acceptance:**
an issue note with this table; the fix is either upstream in sci's
zero-binding loop path or a documented correction to what `fn-entries`
means. Also note `(Thread/sleep 3000)` is unreachable — the base ctx's
`:classes` are only `Throwable`/`Error` (`src/seon/sci/eval.clj:186-189`),
which is a real containment strength worth keeping stated.

**Open decisions for the orchestrator.**

1. **One time-limit dial or a render-specific one?** Recommend one:
   `:seon.config.eval/time-limit-ms` is already per-request in the
   schema, so a render pass can pass a smaller number from the same
   owner. A second dial roster would repeat
   `flow-config-dials-have-two-registration-owners.md`.
2. **fork-per-eval or fork-per-agent-per-basis?** Recommend the latter
   (R6): fork costs 72 ns, so the question is really where `acquire!`
   runs, and renderers make per-run acquisition untenable.
3. **R1 shape (a) ThreadLocal indirection vs (b) re-create fns.** This is
   the owner design gate: (a) changes what "armed" means process-wide;
   (b) preserves per-eval closure semantics at a cost that reintroduces a
   deleted defect. Recommend (a).
4. **R7 base-Var isolation** — memory vs isolation, needs a ruling.
5. **Render declarations as agent-writable facts** (§1 item 3). If agents
   will ever author `:seon.render/ai` symbols, `seon.render/render` needs
   a computed admissibility rule (the symbol's namespace must be one the
   agent authored), never a hand list.

## 6. Skill and document drift found

Reported, not edited (this lane is read-only outside this file).

- **Root `AGENTS.md` vocabulary table** points `:interrupt-fn` at
  "`reference-code/sci/doc/interrupt.md:6` ↔ `seon.sci.interrupt`" and
  `interrupt!` at `reference-code/sci/src/sci/interrupt.cljc:32`. The sci
  refs are correct (`interrupt.md:6-9`; `interrupt.cljc:32-42`). **The
  Seon side is wrong: there is no `seon.sci.interrupt` namespace and no
  `src/seon/sci/interrupt.clj`.** `src/seon/sci/` contains exactly
  `admit.clj`, `eval.clj`, `reader.cljc`. The owner is
  `seon.sci.eval/arm` (`src/seon/sci/eval.clj:246-282`) and
  `seon.sci.eval/interrupted?` (`:213-221`). Same table's `time-limit`
  row cites `interrupt.md:32` — that line is inside the wall-clock
  example, which is right in spirit; `interrupt.md:23-35` is the honest
  span.
- **`resources/seon/schema/eval.edn:8-9`** documents
  `:seon.sci.eval/ctx` as "forked per evaluation so one eval's defs
  cannot reach another's". That is the opposite of the landed contract:
  `evaluate` uses a supplied ctx AS GIVEN and the run forks ONE ctx per
  run precisely so form 2 sees form 1's defs
  (`src/seon/sci/eval.clj:72-81`, `:761-764`;
  `src/seon/cluster/loop.cljc:952-967`). An agent reading the schema
  comment would reason from a false isolation guarantee.
- **`src/seon/sci/eval.clj:728-731`** ("Runs on the CALLER's thread,
  which must be a `:compute` platform thread") no longer matches the
  launcher: work runs on `virtual-task-executor`
  (`src/seon/flow.clj:135-137`, `:416`, `:425`). Docstrings render into
  agent context, so this is a lying docstring, not a comment nit.
- **`src/seon/sci/eval.clj:49-52`** still says `seon.sci.admit` "carries
  a private copy" of `interrupted?` that should be re-pointed. It has
  already been re-pointed: `src/seon/sci/admit.clj:269` calls
  `(requiring-resolve 'seon.sci.eval/interrupted?)`. Stale seal note.
- No `.claude/skills/` or `.agents/skills/` SKILL.md makes a claim about
  the door, `:interrupt-fn`, `fork`, or the eval dials, so no skill is
  currently poisoned on this subject. `seon-flow-architecture` is the
  skill that would naturally acquire R1/R3 once they land.
