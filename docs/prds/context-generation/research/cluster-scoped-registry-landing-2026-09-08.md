---
type: research
status: active
tags: [research, schema, runtime, test]
---

# Cluster-scoped registry — stopped baseline, 2026-09-08

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
