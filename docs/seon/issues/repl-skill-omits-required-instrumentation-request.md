---
type: issue
status: open
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
