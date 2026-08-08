---
type: issue
status: open
severity: blocker
tags: [issue, runtime, boot, schema, concurrency]
---

# The operator instruments the whole JVM under one cluster's projection state

## Problem

Malli instrumentation alters Var roots PROCESS-WIDE. The operator applies it
inside one selected cluster's `call-with-projection-state`, so N co-hosted
clusters share ONE set of contract wrappers compiled against ONE cluster's
projection.

This is Defect II of the
[parallel isolation audit](../../prds/sci-execution-runtime/research/parallel-isolation-audit-2026-08-07.md)
— derived state parked in a process-wide slot — at the boot boundary.

## Evidence

`script/seon/fresh_operator.clj:1298-1316`, `refresh-instrument-form`:

```clojure
anchor      (first (filter (fn [instance]
                             (and (map? instance)
                                  (:seon.boot/cluster-connection instance)))
                           (vals instances)))
anchor-name (get-in anchor [:seon.boot/advertisement :seon.boot/cluster-name])
(when anchor (instrument-form anchor anchor-name))
```

`instrument-form` (`:1275-1296`) then reads
`(:seon.sci.eval/projection-state (:seon.sci.eval/ctx anchor))` and calls
`seon.instrument/apply!` inside `schema/call-with-projection-state` for THAT
cluster. `apply!` (`src/seon/instrument.clj:355-397`) collects over
`(all-ns)` and calls `mi/instrument!`, which replaces Var roots for the whole
process; the caps and the `:seon.config/on-core-error` dial come from the
anchor's effective config too.

`add-form` (`:1430`) runs this BEFORE starting a new cluster, and every
`start`/`stop` path re-runs it (`:2082`). The dial and the compiled validators
of whichever cluster happens to be first therefore govern every other cluster
in the JVM.

Verified 2026-08-07: two clusters were booted into one JVM with instrumentation
live and both passed, because both forked the same published commit and their
projections agree in content. The hazard is real but currently unobservable —
it becomes observable the moment two co-hosted clusters hold genuinely
different declarations, which is exactly the
[test-infrastructure spec](../../prds/sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md)'s
four-worker target.

Ordering note worth keeping: `launch-form` (`:1389`) instruments AFTER
`start!`, `add-form` (`:1430`) BEFORE it. So a fresh-JVM boot runs unchecked
and a co-hosted boot runs checked — which is why
[a-cohosted-second-cluster-cannot-boot](a-cohosted-second-cluster-cannot-boot.md)
only ever surfaced on the second cluster.

## 2026-08-08 — this now BLOCKS the schema-environment fix, and the mechanism


## is narrower than "instrumentation is process-wide"

Found by implementing
[schema-environment-is-ambient-not-explicit](schema-environment-is-ambient-not-explicit.md)'s
first acceptance criterion and measuring what broke. Evidence:
[schema-environment-explicit-2026-08-08.md](../../prds/sci-execution-runtime/research/schema-environment-explicit-2026-08-08.md).

Restricting `seon.schema`'s registry facade to the packaged bootstrap
population — deleting the thread-local half, which is what that criterion
asks for — was implemented and proven green on the schema suites and on
`cohost-boot-test` (two real clusters, one JVM, instrumentation live). It was
REVERTED on exactly one failure out of a 178-test consumer run:

```
ERROR in (seon.instrument-test/applying-uses-the-acquired-projection-without-publishing-it)
clojure.lang.ExceptionInfo: :malli.core/register-function-schema
  at malli.core$_register_function_schema_BANG_ (core.cljc:3068)
  at malli.instrument$_collect_BANG_ (instrument.clj:50)
```

The specific dependency, which the section above does not name:
`malli.instrument/-collect!` reads a Var's `:malli/schema` metadata and calls
`malli.core/-register-function-schema!`, and that function does two
process-global things — it RESOLVES the schema against Malli's default
registry (Seon's facade, hence the thread-local selection), and it WRITES the
compiled contract into `malli.core/-function-schemas*`, one process-wide atom
keyed by namespace and symbol.

Two consequences:

1. Reading the cluster-selecting facade is the ONLY way `seon.instrument`
   currently sees a contract a cluster declared and the packaged resources do
   not. So the schema defect cannot be fixed in `seon.schema` — its only
   choices are answering wrongly on a thread hop, or refusing a caller with
   no other way to ask.
2. `malli.core/-function-schemas*` is a SECOND process-global slot of this
   issue's own class, one the audit did not reach. Even after the operator
   stops picking an anchor, two co-hosted clusters declaring the same
   function contract differently overwrite each other there.

The repair follows: `apply!` compiles the contracts it instruments from the
projection it was given, rather than delegating to `malli.instrument`'s
collection, which cannot be told which environment it is collecting for.

The reverted schema change is recorded verbatim in the registry facade's
comment in `src/seon/schema.clj` and is the falsifier for this issue: with it
re-applied, `applying-uses-the-acquired-projection-without-publishing-it`
must pass.

## Owner

`script/seon/fresh_operator.clj` (`refresh-instrument-form`,
`instrument-form`) and `seon.instrument/apply!`. The repair belongs to the
[seon.env PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)'s
Phase 3 slice "move the compiled caches onto the projection": derived state
hangs off the value it derives from, so two projections cannot exchange a
validator.

## Acceptance criteria

- Instrumenting under cluster A's projection cannot change what cluster B's
  contracts validate, with two co-hosted clusters holding DIFFERENT
  declarations (the current regression at
  `test/seon/cluster/cohost_boot_test.clj` proves the same-declaration case
  and is the place to extend).
- The `:seon.config/on-core-error` dial and admission caps that govern a
  cluster's contract reports are that cluster's own, not the anchor's.
- No selection of "the first running instance" survives in the operator.
