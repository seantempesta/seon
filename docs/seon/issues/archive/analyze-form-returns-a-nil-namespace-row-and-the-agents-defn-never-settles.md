---
type: issue
status: resolved
severity: blocker
tags: [issue, program-graph, settlement, agent, class/p1]
---

# `analyze-form` returns a `{:seon.ns/name nil}` program row and the agent's contracted `defn` never settles

## Problem

On cluster `ctxprobe` (2026-09-03T02:15Z, run `0f813acd-…`, agent root,
model deepseek-v4-flash) root evaluated its bootstrap task — a contracted
`(defn largest …)` preceded by `;` prose lines (form ordinal 5, source
saved at `tmp/ctxprobe-run-32696-forms.edn`). Settlement recorded
`seon.fn/analyze-form violated its contract (invalid-output): missing
required key` ×8: the output tuple's second element was
`{:seon.ns/name nil}` (`:seon.error/diagnostic-offending {1 #:seon.ns{:name nil}}`).
The `defn` was therefore never persisted as a program row; root re-pulled
`[:seon.fn/sym "my.agents.root/largest"]` three times, got nothing, and
burned a 44-form paid run without completing the task.

## Root cause and refutation

`seon.fn/analyze-form` pulled `:seon.ns/name` from the supplied
`namespace-ref` and treated the result as usable without proving that the
reference resolved. An absent pull therefore flowed into static analysis as a
nil namespace instead of becoming an evidence-complete boundary refusal. That
is the reproducible owner defect and explains how nil namespace state could
reach row construction.

The saved ordinal-5 source does **not** reproduce the invalid partial row on a
fresh current-source turn. The real run-loop regression added in `ee11cfa45`
feeds that exact leading-comment source through a scripted `ai/complete`, uses
the real SCI evaluator, settles `my.agents.agent-a/largest` with its spec and
docstring, and evaluates `(doc my.agents.agent-a/largest)`. This refutes the
prose prefix and the current settlement database value/reference as causes.

The historical `ctxprobe` process was a sovereign, long-lived JVM with older
loaded code and database state. The retained error proves that its
`analyze-form` returned the partial row, but it does not preserve enough
evidence to distinguish an older database value from an older namespace-ref
representation. The protected live cluster was not mutated or used as an
authority for the repair.

## Resolution

Resolved by `ee11cfa45` (`Refuse unresolved namespace analysis`).

`analyze-form` now checks the namespace pull at its own boundary. A resolvable
reference follows the existing analysis path; an unresolvable reference
returns `:seon.fn/namespace-unresolvable` with the offending ref and pull
evidence. Its public contract admits either the complete analysis tuple or a
`:seon.error/value`, so the function can no longer emit a partial program row
for this absence class.

Regressions cover both claims:

- `seon.fn-test/analyze-form-refuses-an-unresolvable-namespace-reference`
  proves absence is a typed refusal naming the exact ref.
- `seon.cluster.turn-test/a-prose-prefixed-contracted-defn-settles-and-doc-answers`
  proves the saved source shape settles through a real turn and `doc` answers.

Pre-fix focused evidence was 81 tests / 553 assertions with exactly two
failures, both assertions in the new typed-refusal regression. The real-turn
regression already passed. Post-fix:

```text
$ bin/test seon.fn-test seon.cluster.turn-test
Ran 81 tests containing 553 assertions.
0 failures, 0 errors.
```

## Related observation

The same historical run carried stale operator-Var maintenance messages. That
separate class remains tracked by
[`partial-hot-reload-produces-mixed-code-with-no-warning`](../partial-hot-reload-produces-mixed-code-with-no-warning.md).
