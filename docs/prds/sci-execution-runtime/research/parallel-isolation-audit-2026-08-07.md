---
type: research
status: complete
tags: [research, testing, runtime]
---

# Parallel Isolation Audit — 2026-08-07

## Verdict

Eight adversarial probes, seven run: **four found real cross-talk, four
confirmed genuine isolation**. But the four failures are not four bugs. They are
**two design defects**, each instantiated several times:

- **Defect I — the environment is ambient, not a value.** What makes one cluster
  or one test different from another (its declarations, its connection, its
  request identity) is carried by thread-local dynamic bindings read through
  process-global facades. An ambient carrier has no owner, so nothing can state
  which environment a given computation belongs to, and any carrier that fails
  to propagate it degrades SILENTLY to the process-wide default.
- **Defect II — derived state lives in a process-wide slot instead of on the
  value it derives from.** Compiled validators are a pure function of a
  projection, yet they live in one atom holding ONE generation for the whole
  JVM, guarded by a check-then-act read. Two environments do not merely evict
  each other; one can read the other's compiled result.

The owner's phrase "the specs all being shared" names Defect I precisely, and
the important discovery is that **schema is not the only casualty**. Every
ambient environment value in Seon rides the same carrier, so when the carrier
drops, they all drop together — probed and confirmed for schema declarations,
`seon.db/*conn*`, and `seon.effect/*request-context*` in one shot.

The platform's own forking primitives — Datahike branch forks and `sci/fork` —
are correctly isolated under concurrency. The
[2026-08-07 test-infrastructure ruling](../plan/README.md)'s central bet is
sound; what is not yet true is that a forked environment can be NAMED by the
code running inside it.

## Grounding

I read end to end, before designing any probe: [CLAUDE.md](../../../../CLAUDE.md);
the `Ruling 2026-08-07 (owner, morning) — the platform IS the test
infrastructure` block in [plan/README.md](../plan/README.md); the complete
[Mutable Process State Census — 2026-08-06](atom-census-2026-08-06.md); and
[test/seon/test_support.clj](../../../../test/seon/test_support.clj).

**The census's headline row is stale, and the way it is stale matters.**
`seon.schema/!schema-state` no longer exists; `seon.schema.edn/packaged-base-forms`
and `!source-files` are gone in favor of the pure `resource-population`
(`src/seon/schema/edn.clj:315-326`). The projection is no longer a process-global
ATOM. It is now a process-global REGISTRY FACADE over thread-local bindings
(`src/seon/schema.clj:537-541,653-666,680`). That is not an improvement — it is
the same defect made invisible. A global atom is at least visible from every
thread; a thread-local binding behind a global facade **disappears on a thread
hop and silently answers with different bytes**. A fix lane reading the census
today would go hunting for an atom that is not there.

Dependency ledger: Clojure 1.12.5 (dynamic bindings, `bound-fn*`, binding
conveyance in `future`/`go` but not in raw or virtual threads); Malli 0.20.0
(`malli.registry` mutable default); Datahike vendored at
`10540578248e`; SCI vendored at
`2db3358cba91`
(`reference-code/sci/src/sci/core.cljc:337`, generation-aware copy-on-write
`fork`); core.async `1.10.874-alpha3`.

## Findings grouped by design defect

### Defect I — the environment is ambient, not a value

**The pattern.** A computation's environment is established by `binding` and
read by a global accessor. Nothing in any function signature says which
environment it is operating in, so (a) the compiler cannot check it, (b) a
reader cannot see it, and (c) crossing a thread boundary loses it with no
error — the accessor simply falls through to a process-wide default that is
CORRECT under one environment and WRONG under two. Every one of the five
2026-08-06/07 projection-binding fixture bites is this shape.

**What the code should have been designed against.** The owning mechanism
already exists and is already named in the vocabulary table: the **per-cluster
acquired projection** on the cluster's SCI context
(`::projection-state`, `src/seon/sci/eval.clj:1442-1443`), reached as an
ordinary value. `seon.schema` already has the correct function shape
throughout — `matching-shapes-in`, `explain-shape-in`,
`identity-only-projection-in`, `projection-validator`, `projection-explainer`
all take the projection explicitly. This is not a redesign. It is deleting the
ambient half and making callers name their environment, exactly as `seon.db`
already models with a database value.

#### I.1 — an environment's declarations vanish on a thread hop

Shared state: `seon.schema/seon-registry` (`src/seon/schema.clj:653-666`),
installed as Malli's process-global default by `_registry-init` (`:680`),
resolving through `active-forms` → `candidate-forms` (`:588-598`) over the four
dynamic vars at `:537-541`.

`probe_registry_thread_fallback.clj` — deterministic FAIL. One environment, five
carriers:

```clojure
#:probe{:verdict :fail
        :evidence #:probe{:binding-thread true
                          :future         true
                          :go-block       true
                          :plain-thread   false
                          :virtual-thread false}}
```

`future` and `go` pass only because Clojure conveys bindings for them; that is
luck, not design. A raw `Thread` and a virtual thread from
`Executors/newVirtualThreadPerTaskExecutor` — the exact shape of the
process-root `:io` executor — fall back to the packaged process-wide population.
Nothing throws.

#### I.2 — the whole ambient environment is dropped on the IO half of the work launcher

Shared state: none — this is the CARRIER failing. `seon.flow/submit!!` wraps
compute work with `bound-fn*` (`src/seon/flow.clj:673`); `seon.flow/submit!`
wraps only `::complete!` (`:618`) and passes `::work-fn` through raw
(`:347-366`). Two halves of one function pair, opposite semantics, stated
nowhere.

`probe_work_launcher_binding.clj` — deterministic FAIL, two real launchers, one
fully bound submitter:

```clojure
:probe/submitter-thread {:schema-declarations true  :ambient-connection true  :effect-request-context true}
:probe/compute-work     {:schema-declarations true  :ambient-connection true  :effect-request-context true}
:probe/io-work          {:schema-declarations false :ambient-connection false :effect-request-context false}
```

**This is the finding that generalizes the audit.** `seon.db/*conn*`
(`src/seon/db.clj:65-67`, "the current cluster's live branch connection") and
`seon.effect/*request-context*` (`src/seon/effect.clj:26-28`, "the current
evaluation's durable identity") are not schema state at all, yet they vanish
together because they ride the same carrier. Every capability request crossing
the bounded evaluation on `:io` — fs, web, llm, db — runs with no cluster identity, no
declarations, and no request identity. Under one cluster this is invisible
because the fallback happens to be right, which is exactly why it survived.

#### I.3 — the predicate-function cache is a shared registry keyed by nothing

Shared state: `seon.schema/!predicate-functions` (`src/seon/schema.clj:534-535`),
written by `register-core-predicate!` (`:625-634`), read at every `[:fn sym]`
compile.

`probe_predicate_function_cache.clj` — deterministic FAIL:

```clojure
#:probe{:a-valid-under-first-registration  true
        :a-valid-after-second-registration false
        :b-valid-after-second-registration true}
```

Both projections were rebuilt from immutable form data, so the entire verdict
change came from the shared cache. The docstring's justification is sound — host
functions cannot be database facts — but this is not a resolution cache, it is a
**mutable registry keyed by the bare symbol with last-writer-wins semantics**,
and the 22 load-time sentinels catalogued in the census all write into it at
namespace load. This is a sub-pattern worth naming on its own:
**registration-by-load-side-effect**, where declaration authority is decided by
require order rather than by acquisition at a basis. The honest form is
resolve-by-qualified-symbol through `requiring-resolve`, retaining the **Var**
rather than the function value: reload-correct, and collision-free because a
qualified symbol names exactly one Var.

### Defect II — derived state in a process-wide slot instead of on its source value

**The pattern.** A cache holds a pure derivation of some value `V`, but lives in
a process-global slot with room for ONE `V`, and correctness is enforced by
comparing the slot's `V` against the caller's. That comparison is a check-then-act
on shared mutable state, so under concurrency it is a race; and even when it is
correct it means N environments thrash a cache sized for one.

**What the code should have been designed against.** Derived state hangs off the
value it derives from. A compiled-shape cache belongs on the projection value,
so cache identity is STRUCTURAL and no atom check can be wrong. The tree already
contains the correct instance of this pattern for calibration:
`seon.cluster/source-analysis-cache` pairs its manifest with the complete source
snapshot and replaces only after a before/after equality check
(`src/seon/cluster.clj:851-855,1007-1034`) — the derivation carries its source
with it.

#### II.1 — one environment reads another's compiled validator

Shared state: `seon.schema/!shape-generation` (`src/seon/schema.clj:2381-2385`);
same shape in `!identity-only-generation` (`:2476-2478`).

`matching-shapes-in` and `explain-shape-in` take an EXPLICIT projection, which
reads as isolated. Their validators come from `cached-compiler-in!` (`:2557-2569`),
which calls:

```clojure
(defn- ensure-shape-generation-for! [projection]
    (when-not (identical? projection
                           (:seon.schema.shape/projection @!shape-generation))
      (reset! !shape-generation {:seon.schema.shape/projection projection ...}))
    @!shape-generation)                                 ; <- second, independent deref
```

The check and the returned value are two separate derefs of one process-global
atom. A resets to its projection; B resets to B's; A's second deref returns
**B's generation**; A reads `(get (get generation :validators) schema-key)` and
gets a validator compiled against B's schema. The `swap!` on the write side is
identity-guarded, so nothing is corrupted — but the READ is not, so A silently
validates against B.

`probe_shape_generation_cache.clj` — two projections differing only in
`:probe/marker` (`[:= "a"]` versus `[:= "b"]`), both declaring `:probe/thing`;
two threads each assert their own value matches and the other's does not.
**Intermittent FAIL, 2 of 5 runs, both directions observed:**

```clojure
#:probe{:verdict :fail :violations 1
        :first-violations [#:probe{:side :a :iteration 2385 :expected :no-match :got :match}]}
#:probe{:verdict :fail :violations 1
        :first-violations [#:probe{:side :b :iteration 1749 :expected :match :got :no-match}]}
```

Three runs of the identical probe passed. **This is the design finding, not test
noise**: a suite exhibiting exactly this — a schema assertion that fails once in
three runs, at a different place each time — would be triaged as flakiness. The
flake IS the race, and the race exists only because derived state was put in a
shared slot.

## What is already right (calibration)

These were probed under concurrency, not assumed.

- **Datahike branch forks are a sound test isolation primitive.**
  `probe_branch_fork_parallel.clj`: 24 concurrent `with-database` fixtures, each
  declaring its own marker attribute, transacting, sleeping to widen the
  interleaving window, then reading its own marker plus all 23 foreign markers
  and checking its schema map for foreign attributes. Zero datom leaks, zero
  schema leaks, zero lease collisions, repeated. The bounded branch-lease pool
  (`test_support.clj:38-64`) holds: a lease returns only after successful branch
  retirement, so a teardown failure quarantines rather than reuses.
- **`sci/fork` is isolated under concurrency, not only serially.**
  `probe_sci_fork_parallel.clj`: 16 threads forking one base context, each
  re-defining the same `shared` Var 200 times plus a private Var. Zero cross-fork
  reads; the parent retained `:base` and never saw a fork's private definition.
  The 2026-08-04 verification was serial; this extends it.
- **Sharing the process-root executors across environments is correct.**
  Stopping a peer launcher left the other accepting and completing work. The
  executors are a resource handle, correctly scoped.
- **The ambient `matching-shapes` path is consistent**, for a structural reason
  worth understanding: `shape-projection` (`:2461-2473`) compares candidate forms
  by `=` in a SINGLE deref, so its read cannot tear. It pays for that by
  rebuilding the entire projection on every alternation between environments.
  `probe_parallel_environment_declarations.clj`: 0 violations in 1,500 iterations
  each. Wall-clock numbers are recorded in the probe result but no ratio is
  claimed — the first environment pays projection build and JIT warmup, so they
  are not like-for-like.
- **The explicit-projection function shape already exists** across `seon.schema`.
  The correct design is present; the ambient convenience layer is what breaks it.
- **`submit!!` conveying bindings is right.** The defect is one missing wrap on
  its sibling, not the model.

## What else is probably mis-designed the same way

Hypotheses for the design session, from the census plus the require graph. **None
of these were probed.** Each is listed with the class it pattern-matches and the
shortest falsifier.

### Pattern-matches Defect I (ambient environment / carrier loss)

1. **The `:interrupt-fn` arm is a ThreadLocal on a process-global guard.**
   `seon.sci.kernel/process-guard` is a `defonce` delay (`:81`) whose `::thread-arm`
   is a `ThreadLocal` (`own-arm`, `:190-200`). The guard is THE one door, and
   `time-limit` is THE only limit. If evaluated agent code hands work to another
   thread, that work plausibly runs **unarmed** — no time limit, no
   `interrupt!`. Falsifier: inside an armed eval, submit work to a virtual thread
   and check whether the arm's `::entries` counter advances and whether an
   interrupt reaches it. If it does not, the containment story has a hole that
   has nothing to do with tests.
2. **`seon.db`'s ambient-custody arity.** Ruling #41 lets every `seon.db`
   function elide db/conn "to the current database of the calling agent's
   cluster". That resolution is `*conn*` (`src/seon/db.clj:65`), already proven to
   vanish on `:io`. Falsifier: call an elided-arity `seon.db` read from IO work
   and observe whether it errors loudly or silently reads the wrong cluster. The
   second outcome is the serious one.
3. **`seon.effect/*request-context*` carries "the current evaluation's durable
   identity"** (`src/seon/effect.clj:26-28`). If it is nil on `:io`, receipts and
   request identities for exactly the capability requests that need them most
   (fs, web, llm) may be attributed wrongly or dropped. Falsifier: issue one fs
   request from IO work and inspect the committed receipt's provenance.
4. **`seon.render/*walk-context*`** (`src/seon/render.clj:495`) and
   `seon.cluster/*source-progress!*` / `*boot-progress!*` (`:77`, `:163`) are the
   same shape. Render is the likeliest to hop threads (SSE writers are `:io`).
5. **`seon.schema/*verified-release-identity*`** (`src/seon/schema.clj:543-552`)
   reads two environment variables at NAMESPACE LOAD into a `def`. That is an
   ambient process-wide value captured at the least controllable moment, and it
   gates admission of executable work. It should be an acquired fact like every
   other config dial.
6. **`clojure.test/*report-counters*` and the runner's capture atoms**
   (`src/seon/test/runner.clj:414,445-446`) are dynamic-binding based. They are
   correct for a serial runner and are exactly the shape that breaks first if the
   parallel runner ever runs test vars concurrently in one JVM. Falsifier: run two
   `run!` invocations concurrently and compare the reported totals against the sum.

### Pattern-matches Defect II (derived state in a process-wide slot)

7. **`seon.search/owners`** (`src/seon/search.clj:70-72`) is keyed by the derived
   Lucene index path. Two clusters whose derived paths collide — the same cluster
   name under two operator roots, or two test environments deriving the same
   path — would share one writer, one search manager, and one basis. Falsifier:
   open two owners at the same derived id and check whether the second gets the
   first's handle. This is a resource handle keyed by a DERIVED PATH rather than
   by cluster identity, which is the resource-flavored version of the same class.
8. **`seon.operator.runtime/root-store-holder`** holds stores plus reference
   counts (`resources/seon/operator/runtime.clj:13`, mutated at
   `src/seon/cluster.clj:632-656`). Any refcount is a read-modify-write; the
   question is whether release is a CAS or a check-then-act like
   `ensure-shape-generation-for!`. Falsifier: open and release the same process
   root from N threads and assert the store is closed exactly once.
9. **`seon.cluster/source-analysis-cache`** is the CORRECT instance of this
   pattern (snapshot-fenced) and should be read as the model — but its read path
   (`src/seon/cluster.clj:1007-1020`) deserves the same check-then-act inspection
   the shape cache failed.
10. **The shared-mutable-sample generators** already have issues filed
    ([flow-generators-reuse-one-mutable-sample](../../../seon/issues/flow-generators-reuse-one-mutable-sample.md),
    [opaque-contract-generators-share-live-process-objects](../../../seon/issues/opaque-contract-generators-share-live-process-objects.md)).
    They are the same class seen from the generative-testing side: a `defonce`
    delay handing one live mutable object to every environment that asks for a
    sample. Parallel generative tests would corrupt each other by construction.

### The structural question behind all of it

Both defects are one question the codebase has not answered uniformly: **what is
an environment, and how does a computation get one?** Today the answer differs by
caller — a database value passed explicitly (right), a `*conn*` binding (ambient),
a projection binding (ambient), a projection argument (right), a process-global
cache slot (wrong), a load-time `defonce` (wrong). Until one answer is chosen and
the others deleted, every new mechanism gets a coin flip. That is the design
conversation this audit is asking for, and it is larger than fixing four probes.

## Fix-first list

Ranked by what must be true before parallel isolation is real. Items 1 and 2 are
the two defects; 3 and 4 follow from them; 5 is bookkeeping.

1. **Make the environment an explicit value.** Owner: `seon.schema`, then every
   ambient carrier in the hypotheses list. Every schema operation takes the
   projection it compiles against; the convenience arities resolve it from the
   calling agent's cluster (the per-cluster acquired projection,
   `src/seon/sci/eval.clj:1442-1443`), never from a thread-local binding. This is
   the root cause of I.1, I.2, and I.3 together.
2. **Attach derived state to the value it derives from, and never check-then-act
   on a shared slot.** Owner: `seon.schema/!shape-generation` (`:2381-2385`) and
   `!identity-only-generation` (`:2476-2478`). If any process-global map survives
   as an interim, it is keyed by projection identity and read exactly once.
3. **Delete the conveyance asymmetry in the work launcher.** Owner:
   `seon.flow/submit!` (`:610-650`). Once item 1 lands the SUBMISSION carries its
   cluster's environment as data and no binding needs to cross a thread. If item
   1 is deferred, `bound-fn*` on `::work-fn` is a stopgap and must say so in the
   source.
4. **Replace registration-by-load-side-effect with acquisition.** Owner:
   `seon.schema/!predicate-functions` (`:534-535`) plus the 22 sentinels already
   indicted in
   [load-time-schema-sentinels-bypass-basis-acquisition](../../../seon/issues/load-time-schema-sentinels-bypass-basis-acquisition.md).
   Retain the Var from `requiring-resolve`, not a function value under a bare
   symbol.
5. **Refresh the atom census's schema rows.** `!schema-state`,
   `packaged-base-forms`, and `!source-files` no longer exist and the hazard
   changed shape from a visible global atom to an invisible thread-local behind a
   global facade.

Deliberately NOT on this list: the shared root executors, Datahike branch forks,
the branch-lease pool, and `sci/fork`. All four were probed under concurrency and
are correct.

## Probe inventory

| Probe file | Surface | Class | Verdict |
|---|---|---|---|
| `tmp/isolation-probes/probe_registry_thread_fallback.clj` | `seon-registry` as Malli default over thread-local bindings | I | **FAIL** deterministic |
| `tmp/isolation-probes/probe_work_launcher_binding.clj` | `submit!` vs `submit!!` conveyance of schema + `*conn*` + request context | I | **FAIL** deterministic |
| `tmp/isolation-probes/probe_predicate_function_cache.clj` | `!predicate-functions` last-writer-wins | I | **FAIL** deterministic |
| `tmp/isolation-probes/probe_shape_generation_cache.clj` | `!shape-generation` single slot + check-then-act read | II | **FAIL** intermittent 2/5 |
| `tmp/isolation-probes/probe_parallel_environment_declarations.clj` | ambient `matching-shapes` under two divergent environments | II | **PASS** |
| `tmp/isolation-probes/probe_branch_fork_parallel.clj` | 24 concurrent branch forks: datoms, schema, lease pool | — | **PASS** |
| `tmp/isolation-probes/probe_sci_fork_parallel.clj` | `sci/fork` copy-on-write Vars, 16 concurrent forks | — | **PASS** |
| `tmp/isolation-probes/probe_work_launcher_binding.clj` (2nd assertion) | peer launcher stop over shared executors | — | **PASS** |

## How the probes run, and the continuous runner

```bash
clojure -Sdeps '{:aliases {:probes {:extra-paths ["tmp/isolation-probes"]}}}' \
  -M:dev:test:probes -m probe-runner \
  probe-shape-generation-cache probe-shape-generation-cache ...
```

Each probe namespace exposes `run` returning `{:probe/verdict :pass|:fail ...}`.
No assertion macro, no test framework — data in, verdict out — so the same file
is a probe today and a regression in `test/` tomorrow. Design notes for the
continuous runner:

- JVM load is ~13 s and dominates, so probes batch into one launch.
- **Repetition is a first-class argument, and the reported result is a FAILURE
  RATE, not a boolean.** Naming a probe N times re-runs it; that is the only
  reason the shape-generation race was characterized rather than dismissed. A
  probe that reports pass once on an intermittent race is worse than no probe.
- Process-global surfaces (predicate cache, registry default, load-time
  sentinels) need N **JVMs**; concurrency surfaces (shape cache, branch forks,
  sci forks) need N **threads in one JVM**. A probe that must mutate a
  process-global cannot be falsified by threads.
- Graduation: a probe that reproduces a defect becomes the ONE regression for
  that class when the fix lands, and the probe file is deleted. A probe that
  finds nothing after sharpening is deleted too. The directory is a working edge,
  not an archive.

## Issue destinations

- [Make the schema environment an explicit argument, not an ambient binding](../../../seon/issues/schema-environment-is-ambient-not-explicit.md)
  — Defect I and II in `seon.schema`, one issue with three acceptance criteria
  because they are one design decision a fix lane could otherwise land
  inconsistently.
- [Carry the submitting environment across the IO half of the work launcher](../../../seon/issues/flow-io-work-does-not-carry-its-environment.md)
  — the carrier failure, dependent on the first.

The ten hypotheses above are deliberately NOT filed as issues. They are inputs to
the design session on what an environment is; filing them now would invite ten
independent patches to a problem that has one answer.
