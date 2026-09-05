---
type: issue
status: open
severity: friction
tags: [issue, render, wave/agent-context]
---

# Check a render candidate's input and output on the same arity

## Problem

Candidate discovery can accept a function when one arity accepts the argument
and a different arity declares the requested output. No callable arity then
satisfies the combined claim.

## Evidence

Live JVM MCP probe on default, basis 536871002, 2026-09-05:
[script](../../prds/context-generation/research/scripts/design-lab-orientation-2026-09-05.clj).
Contract: int -> int, and two strings -> string. Input [7] passes
function-accepts-in? and requested output :string passes function-returns-in?;
the same-arity conjunction is false. No trial definition was installed.
Source: src/seon/schema.clj:3080-3131 and src/seon/render.clj:178-207.

## Owner

seon.schema's contract checks and seon.render candidate selection.

## Acceptance

One arity must satisfy both conditions, including supplied defaults. A regression
rejects the demonstrated false candidate and accepts a genuinely matching arity.
The inspection table identifies the selected arity and reasons for rejection.

## Implementation evidence

The context-lab prerequisite adds
`seon.schema/function-accepts-and-returns-in?`, which reads each compiled Malli
arity's paired `:input` and `:output` from `malli.core/-function-info` and accepts
only when that one arity satisfies both claims. `seon.render/candidates` now uses
that joint predicate. The older independent predicates remain available to their
existing callers.

Candidate selection still checks the one producer argument it will hand to the
invocation boundary. This change does not simulate or duplicate call preparation;
declared supplied defaults remain owned by the existing invocation hook.

After explicitly reloading `seon.schema` and `seon.render` in the default
cluster's JVM, a 2026-09-05 MCP probe reported the loaded source at
`seon/schema.clj:3131`, rejected the cross-arity candidate, accepted the genuine
same-arity candidate, returned false for an absent function, and selected only
`probe.render/matching`.

The final focused `bin/test seon.schema-test seon.render-simplification-test`
run in isolated root `tmp/test-runs/run.oGLqOV` executed 32 tests and 322
assertions with zero primary-worker failures. Its isolated confirmation of the
pre-existing
`renderer-invocation-is-sci-only-and-live-var-backed` test then errored at the
`seon.sci.eval` boundary with `Selected function has no durable root
descriptor.` The earlier run retained at `tmp/test-runs/run.xWml89` reported
the same confirmation error. The new schema and actual-candidate regressions
both passed in the primary worker; the confirmation error prevents a green
namespace-level gate at this working-tree snapshot.

A fresh JVM reran that failing test while dynamically restoring the old two
independent candidate predicates. It still reached `eval.clj:747` with the
same missing-root error after 78 passing assertions. The arity repair therefore
does not cause the confirmation failure. Current evaluation derives the program
row from the generation-stamped SCI Var and carries its restorable root in
`:seon.sci.eval/defs`; the failing fixture transacts only the program row and
calls the cold `install-row!` seam. The production loop settles the definitions
and calls `install-evaluated-row!`. The SCI owner should reconcile that fixture
with the evaluated-install seam and ensure the evaluated path does not perform
a second cold root lookup after transferring the exact evaluated root.

After the final fixture-cost cleanup, a direct JVM run of the two added
`clojure.test` vars reported 2 tests, 7 passing assertions, 0 failures, and 0
errors. Both fixtures build from `seon.schema.edn/packaged-forms`, so the
regressions do not invoke the declaration-population fallback.
