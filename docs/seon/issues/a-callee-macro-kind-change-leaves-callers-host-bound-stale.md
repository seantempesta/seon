---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, publication, wave/program-graph-indexing]
---

# A callee's macro-kind change leaves other files' callers' host-bound fact stale

## Problem

`seon.fn/host-bound-callers` classifies a caller as host-bound when it uses a
JVM macro that SCI does not interpret. For a dependency macro, whether the
target is a macro comes from the loaded Var's `:macro` metadata (`host-macro?`).
When a first-party declaration changes between a function and a macro while
keeping its symbol, only that declaration's file is reanalyzed. Callers in
unchanged files keep their old `:seon.fn/host-bound?` value. The indexer
recomputes dependent files only for *removed* definitions: see
`seon.fn/analyzed-files` and its `dependent-paths` step.

The analyzer's own identity is keyed on the source seal as
`:seon.source/analyzer-digest`
(lane-fix-analysis-key-omits-analyzer-2026-09-24). That fix does not cover
this dependency on a callee's kind.

## Evidence

This defect was found by reading the code. No reproduction has been run.
Callers read `:macro` through `host-macro?` in `src/seon/fn.clj`. The only
reselection outside the changed files is `dependent-paths`, and it is seeded
by `removed` in `analyzed-files`. The related class, in which a callee
declaration change invalidates unchanged callers' analysis, is recorded in
[a-file-digest-does-not-identify-complete-caller-analysis](a-file-digest-does-not-identify-complete-caller-analysis.md).

## Owner

The `seon.fn` indexer. The candidate fix is to seed `dependent-paths` with
declarations whose `:seon.fn/macro?` changed, in addition to removed ones.
The fix is not yet designed.
