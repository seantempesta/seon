---
type: issue
status: open
severity: blocker
tags: [issue, schema, testing, isolation, runtime]
---

# Make the schema environment an explicit argument, not an ambient binding

## Problem

Three process-global schema owners make two "isolated" environments — two
co-hosted clusters, or two parallel tests — share their declarations:

1. `seon.schema/seon-registry` is installed as Malli's process-global default
   registry, but the declaration population it resolves through is selected by
   THREAD-LOCAL dynamic vars. On any thread hop the binding disappears and the
   registry silently falls back to the packaged process-wide population. No
   error is raised; the schema simply resolves to different bytes.
2. `seon.schema/!shape-generation` holds ONE compiled validator/explainer
   generation for the whole process, and `ensure-shape-generation-for!` reads
   the atom twice — an identity check, then an independent deref — so one
   environment can be handed another environment's validator for the same
   schema key.
3. `seon.schema/!predicate-functions` is keyed by the bare qualified symbol,
   so two environments registering the same predicate overwrite each other
   process-wide and last writer wins.

Together these are the owner's "specs all being shared". They are the mechanism
behind the five 2026-08-06/07 projection-binding fixture bites, and they block
the [2026-08-07 test-infrastructure ruling](../../prds/sci-execution-runtime/plan/README.md):
no amount of fixture care makes a thread-hopping test correct while the
environment is ambient.

## Evidence

Probes and full results:
[parallel-isolation-audit-2026-08-07.md](../../prds/sci-execution-runtime/research/parallel-isolation-audit-2026-08-07.md).

- `src/seon/schema.clj:537-541` — the four dynamic vars that select the
  population; `:588-598` is the resolution chain; `:653-666` is the registry
  facade; `:680` installs it as Malli's default.
- `tmp/isolation-probes/probe_registry_thread_fallback.clj` — deterministic
  FAIL. One environment, five carriers: binding thread `true`, `future` `true`,
  `go` block `true`, plain `Thread` `false`, virtual thread `false`. The virtual
  thread is the shape of the process-root `:io` executor.
- `src/seon/schema.clj:2546-2555` — `ensure-shape-generation-for!` checks
  `(identical? projection (:seon.schema.shape/projection @!shape-generation))`
  and then returns a SECOND `@!shape-generation`.
- `tmp/isolation-probes/probe_shape_generation_cache.clj` — intermittent FAIL,
  2 of 5 runs, both directions observed: side `:a` matched a value only valid
  under side `:b`'s projection, and side `:b` failed to match its own.
- `src/seon/schema.clj:534-535,625-634` — the predicate cache and its writer.
- `tmp/isolation-probes/probe_predicate_function_cache.clj` — deterministic
  FAIL: after a second registration of the same symbol, a value valid under the
  first registration no longer validates, though both projections were rebuilt
  from immutable form data.

## Owner

`seon.schema`. The mechanism that should own the environment already exists:
the per-cluster acquired projection state on the cluster's SCI context
(`src/seon/sci/eval.clj:1442-1443`), and the explicit-projection function shape
(`matching-shapes-in`, `explain-shape-in`, `identity-only-projection-in`,
`projection-validator`, `projection-explainer`). This is not a redesign; it is
deleting the ambient half.

## Status 2026-08-08 — two criteria closed; criterion 1 is blocked, with a cause

Evidence:
[schema-environment-explicit-2026-08-08.md](../../prds/sci-execution-runtime/research/schema-environment-explicit-2026-08-08.md).
This issue STAYS OPEN for the one criterion that is not a `seon.schema`
change.

Closed at cause, one class regression each in `test/seon/schema_test.clj`:

- **Predicate resolution** is now the Var a qualified symbol names
  (`f2903354a`). A qualified symbol names exactly one Var, so it is
  collision-free by construction, and the Var is retained rather than its
  value, so it is reload-correct. `!predicate-functions`,
  `core-predicate-functions`, `snapshot-state`, and `restore-state!` are
  deleted. Regression:
  `one-predicate-symbol-cannot-name-two-environments-callables`, whose
  decisive arm is the probe's own second registration, now refused.
- **Compiled state hangs off the projection** (`37700ec64`). Every projection
  carries its own holder, installed fresh at construction and never
  inherited. `!identity-only-generation`, `ensure-shape-generation-for!`, and
  the validator/explainer halves of `!shape-generation` are deleted.
  Regression: `two-projections-never-exchange-a-compiled-validator`.

NOT closed — **the registry facade**. Restricting Malli's process-global
default to the packaged bootstrap population was implemented, proven green on
the schema suites and on `cohost-boot-test` (two real clusters, one JVM,
instrumentation live), and then REVERTED on one measured failure:
`malli.instrument/-collect!` registers a Var's `:malli/schema` through
`m/-register-function-schema!`, which resolves against that default, and that
is how `seon.instrument` sees contracts a cluster declared but the packaged
resources do not. Instrumentation is a live consumer of the defect, and
`seon.schema` cannot repair it — its only options are answering wrongly on a
thread hop or refusing a caller with no other way to ask. Recorded on the owner's existing note,
[instrumentation-compiles-under-one-clusters-projection](instrumentation-compiles-under-one-clusters-projection.md),
which BLOCKS this criterion and should land before the Phase 3 sweep. The
reverted change is recorded in the facade's own comment in
`src/seon/schema.clj` so it can be re-applied as that issue's falsifier, and
its thread-hop regression was measured non-vacuous before being held rather
than left to pass emptily.

The three graduated probe files are deleted; the registry class is carried by
that issue's acceptance criteria.

Also still open, and NOT a `seon.schema` change: the four projection dynamic vars
(`*candidate-forms-overlay*`, `*projection*`, `*projection-state*`,
`*packaged-forms*`). Their ~25 `call-with-*` call sites live in
`seon.cluster`, `seon.sci.eval`, `seon.db`, `seon.config`, `seon.reconcile`,
`seon.error`, `seon.schema.edn`, and the test bracket, and the mechanism that
replaces them — the call-preparation hook — is landed but not yet consumed.
`malli-form?` is the sharpest instance and is filed separately
([malli-form-predicate-resolves-the-declaration-population-itself](malli-form-predicate-resolves-the-declaration-population-itself.md)):
Malli invokes a registered predicate with one argument, so it cannot be
handed a projection, and `*packaged-forms*` is load-bearing until the
environment carries it. Owner: the Phase 3 production sweep, landing the
deletion in the same change as the hook consumption.

## Acceptance criteria

- Every schema operation takes the projection it compiles against; the
  convenience arities resolve it from the calling agent's cluster rather than
  from a thread-local binding.
- The compiled-shape cache hangs off the projection value it was compiled
  against, so cache identity is structural; if any process-global map survives
  it is keyed by projection identity and read exactly once.
- Predicate resolution retains the Var found by `requiring-resolve` for a
  qualified symbol rather than caching a function value under a bare symbol, so
  it is both reload-correct and collision-free.
- `probe_registry_thread_fallback`, `probe_shape_generation_cache`, and
  `probe_predicate_function_cache` graduate into `test/` as ONE regression per
  class and pass with repetition, and the probe files are deleted.
