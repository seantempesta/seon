---
type: issue
status: resolved
severity: friction
tags: [issue, docs, sci, wave/docs-honesty]
---

# Give the REPL reload workflow an executable instrumentation call

## Problem

The REPL skill tells an agent to run `seon.instrument/apply!` after reloading
a contracted Var, but it omits the function's required request map. Following
the workflow verbatim therefore produces a contract violation at the proof
step.

## Evidence

`.agents/skills/repl/SKILL.md:166-167` names a zero-context call. The current
function at `src/seon/instrument.clj:532-572` requires
`:seon.config/on-core-error` and accepts the active admission caps. A live
scratch-cluster attempt on 2026-08-13 refused the zero-argument call with
`Wrong number of args (0)`; the request-map call completed and restored 753
instrumented Vars.

## Owner

`.agents/skills/repl/SKILL.md` owns the reload-before-retest workflow and
should show how to derive the request from the selected live cluster rather
than inventing defaults.

## Acceptance

Execute the documented reload and instrumentation forms verbatim in a live
scratch cluster, then rerun the same contracted function without an arity or
missing-context refusal.

## Resolved — issues-sweep, 2026-09-08

The skill now gives the complete reload/re-arm form with the selected connection, immutable database projection, and database-derived core-error mode. Executed through default MCP JVM evaluation on 2026-09-08: registered 901, instrumented 900 (the primitive required-cap function is intentionally excluded by Malli). The same contracted oversight call then returned “delayed: unknown”. This proves hot-reloaded JVM behavior, not program publication or browser paint. The skill passes both its bundled validator and the repository Markdown validator.

[Landing evidence](../../../prds/context-generation/research/issues-sweep-landing-2026-09-08.md).
