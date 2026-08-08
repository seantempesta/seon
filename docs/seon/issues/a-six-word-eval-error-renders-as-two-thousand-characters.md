---
type: issue
status: open
severity: friction
tags: [issue, error, render, repl]
---

# A six-word evaluation error renders as 2,154 characters

## Problem

`Unable to resolve symbol: my.web/fetch` is six words. The value the agent
received for it was 2,154 characters. Across the tool-exercise lane's runs a
contract-violation eval result cost 480–820 estimated tokens (1,921–3,266
characters), because the Malli explanation is re-encoded through the print
faces and every layer of it survives into the agent's context.

An agent that makes an ordinary mistake — a typo, a wrong key — pays close to
a thousand tokens to be told so, and the sentence that says what happened is
buried in the middle. Cheap correct diagnosis is what makes the next defect
cheap to kill; an expensive one discourages the probe.

This is the SIZE half of the shape recorded in
[contract-violation-serializes-print-tree-inside-error-data](contract-violation-serializes-print-tree-inside-error-data.md),
which owns the structural half (evidence stored as EDN strings). Fixing that
one is likely to shrink this one, but the acceptance here is a measured
budget, not a shape.

## Evidence

Tool-exercise lane, 2026-08-07, cluster `tools` in an isolated operator root,
driven through real runs. Report:
[tool-exercise-2026-08-08.md](../../prds/sci-execution-runtime/research/tool-exercise-2026-08-08.md).

```text
"Unable to resolve symbol: my.web/fetch"   → 2,154 characters
contract violation eval results            → 1,921–3,266 characters
                                             (480–820 estimated tokens)
```

## Expected

An error's rendered face is bounded by what a reader needs: the kind, the
sentence, and the evidence that names what was missing. Depth beyond that is
retrievable by identity, the way any other oversized value is, rather than
inlined into every context that meets the error.

## Acceptance

- An unresolved-symbol evaluation result renders in under ~200 characters,
  measured verbatim from a real run.
- A contract violation's rendered face fits a stated token budget, with the
  full explanation retrievable by identity.
- Both faces are read verbatim in this note when it is closed.
