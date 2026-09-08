---
type: research
status: active
tags: [research, schema, runtime, test]
---

# Cluster-scoped registry — stopped baseline, 2026-09-08

## Restart: shape projection holder removed, 20:47 UTC

The orchestrator superseded the stop boundary. `shape-projection` now uses
the handed immutable projection, including the cluster's projection-state
carrier. It no longer retains a process-global forms/projection pair or
rebuilds a projection after consulting that shared pair. Missing custody
raises `:seon.schema/missing-projection`.

Armed fast loop: **20 tests / 202 assertions**, zero failures/errors.
The path-isolated gate `bin/test --paths src/seon/schema.clj --
seon.schema-test` also passed **20 / 202**, base preparation 54,352 ms,
coordinator/tests 59 seconds, snapshot `82d8ce7cd` plus this file's diff.
The edit hook refused publication; this is not a live-adoption proof.
The remaining sections below record the initial, superseded stop.

## Database projection ownership, 20:57 UTC

Removed `!database-projections` and its process-wide LRU. The one-argument
database acquisition returns a projection to its caller; the existing
two-argument acquisition reuses the caller's projection when its fingerprint
matches. No replacement global holder was introduced.

`database-projections-reuse-only-the-handed-value` uses a canonical database,
transacts a schema declaration, and verifies unchanged-value identity reuse,
new-value declaration visibility, and the old database/projection remaining
unchanged. It replaces the former mocked derivation-count test.

Both `bin/test-fast seon.schema-test` and `bin/test --paths
src/seon/schema.clj test/seon/schema_test.clj -- seon.schema-test` passed
**20 tests / 200 assertions**, zero failures/errors. Isolated coordinator/tests
took 67 seconds; the successful run root was removed by the runner.
The edit hook's publication timed out (exit 124); no live-adoption proof is
claimed for this slice yet.

No implementation landed. The assignment's stop boundary was reached during
the initial armed baseline, before any production or test edits.

## Verification boundary

`bin/test-fast seon.schema-test seon.instrument-test` (JVM PID 79987)
armed 932 registered / 931 instrumented / 909 program-armable Vars.
At 20:38:14 UTC, `database-projections-follow-exact-committed-identity`
failed during canonical fixture construction:

```text
java.lang.IndexOutOfBoundsException
seon.fn/exact-source (fn.clj:142)
seon.fn/var-row (fn.clj:350)
seon.fn/analysis-rows-by-file (fn.clj:738)
seon.fn/build-manifest (fn.clj:1340)
seon.test-support/source-manifest (test_support.clj:155)
```

The process exited 1 after entering `seon.instrument-test`, reporting
`bin/test-fast: initialization or execution failed: nil`. No aggregate tally
was emitted. No instrumentation assertion or new isolation regression is
claimed. No isolated gate or platform run followed the stop boundary.
The exception does not identify the offending source file: concurrent edits
are a hypothesis, not a verified attribution. This is the already recorded
[source-span fixture boundary](../../../seon/issues/bin-test-shared-base-compiles-other-lanes-half-edits.md).

## Partial census and intended seam

| Owner | Observed path |
|---|---|
| `src/seon/schema.clj:1062` | `seon-registry` selects call-bound forms through a process-global Malli default |
| `src/seon/schema.clj:1085` | `relink-registry!` calls `mr/set-default-registry!` |
| `src/seon/schema/edn.clj:371` | `load!` calls `relink-registry!` |
| `src/seon/instrument.clj:643` | collection calls `mi/-collect!`, which compiles through the default and writes Malli's function-schema atom |
| `src/seon/instrument.clj:752` | `apply!` wraps globally; its `:record` branch unstruments globally |
| `src/seon/instrument.clj:777` | `remove!` calls global `mi/unstrument!` |
| `src/seon/schedule.clj:504` | `m/schema :seon.maintenance.request/value` has no explicit registry; this file has foreign uncommitted edits |
| `src/seon/schema.clj:3129` | `!ambient-shape-projection` retains a shared forms/projection pair |
| `src/seon/schema.clj:2473` | `!database-projections` retains projections in an LRU keyed by committed-value identity |
| `src/seon/schema.clj:743` | `!fallback-counts` contains process diagnostics, not cluster authority |

This is a partial source census, not clearance of all Malli calls. Calls on
already compiled Malli schemas must be distinguished from calls on unresolved
forms: compiled schemas carry their registry. The existing projection-local
`projection-cache-value` is the pattern for retaining compiled contracts.

The assignment chooses one JVM wrapper per loaded Var, with validation using
the calling operation's carried projection and with cluster removal unable to
strip shared wrappers. That seam has not been implemented or proven. The
operator's repeated arm calls, reload behavior, and test preservation machinery
must remain coherent when replacing global Malli collection. No new global
cluster-state holder is justified.

Dependency ledger: `reference-code/malli/src/malli/instrument.clj` owns
`-collect!`, `-strument!`, and the `::original` wrapper stamp;
`reference-code/malli/src/malli/core.cljc:3059` owns the function-schema atom
and `-register-function-schema!` writes compiled schemas into it.
First-party call sites are the instrument and schema owners above.

## Live evidence and scope

MCP JVM mode on default returned `{:wrapped 912 :global-schema-namespaces 98}`
in 2 ms. Runtime status answered for PID 36758, with render proc ping unknown.
The read-only `bin/seon status` waited behind PID 11563's source adoption of
`test/seon/test_runner_test.clj`; no foreign holder was interrupted.

AGENTS.md was supplied end to end and read from disk; PRD §10, the roadmap
entry and working edge, the concurrency landing, and the data-modeling,
datahike, data-oriented Clojure, REPL and testing skills were consulted.
The broader requested end-to-end source/issue reading was not completed
before the stop boundary. No completed archaeology or exhaustive audit is
claimed.

Only this note and an observation appended to the existing fixture-boundary
issue were changed. The instrumentation issue remains open. No production
file, protected file, foreign session, or cluster was changed.
The fast JVM exited 1. The owned read-only status process was terminated and
reaped with exit 143 after 182 seconds of lock waiting. No owned background
process or scratch root remains.
