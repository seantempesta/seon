---
type: issue
status: open
severity: friction
tags: [issue, render, sci, class/n1, wave/error-face-budget]
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
[contract-violation-serializes-print-tree-inside-error-data](archive/contract-violation-serializes-print-tree-inside-error-data.md),
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

## N1 disposition — 2026-08-12

Still open. `4bc8104d8` bounds terminal producer output in `seon.render`, but
this member still needs `seon.error` / `seon.instrument` to retain the full
contract explanation by identity and render the short face from bounded
ordinary evidence. Re-run one real unresolved-symbol receipt and paste both
faces here before closure.

## Live falsifier after the contract-constructor repair — 2026-08-13

This member remains open. The shared contract-refusal constructor repair
reduced the reproduced wrong-argument artifact from 9,266 to 1,296
characters, but a genuinely unresolved symbol still misses this note's
`<200`-character acceptance. SCI evaluation of
`my.web/no-such-fetch` returned a 4,493-character retrievable artifact. Its
agent-facing text was:

```clojure
{:seon.error/message "Unable to resolve symbol: my.web/no-such-fetch", :seon.sci.eval/evaluation-failed
  :evaluation, :seon.error/data {:seon.error/diagnostic-evidence #:seon.eval{:fn-entries
      0, :host-interop-count 0, :duration-ms 3, :allocated-bytes 2749248,
      :outcome :error}, :seon.error/diagnostic-expected :successful-evaluation,
    :seon.error/diagnostic-member :throwable, :seon.sci.eval/throwable "clojure.lang.ExceptionInfo",
    :seon.error/diagnostic-evidence-availability :seon.error/known, :seon.error/throw-site-message
    "Unable to resolve symbol: my.web/no-such-fetch", :seon.sci.eval/data
    {:type :sci/error, :line 1, :column 1, :file nil, :phase "analysis", :sci.impl/symbol
      my.web/no-such-fetch}, :seon.sci.admit/record #:seon.eval{:fn-entries
      0, :host-interop-count 0, :duration-ms 3, :allocated-bytes 2749248,
      :outcome :error}, :seon.error/diagnostic-layer :sci, :seon.sci.eval/symbol
    my.web/no-such-fetch, :seon.error/diagnostic-offending my.web/no-such-fetch,
    :seon.error/diagnostic-operation :evaluation, :seon.error/diagnostic-cause
    "Unable to resolve symbol: my.web/no-such-fetch"}, :seon.error/kind :seon.sci.eval/evaluation-failed}
```

The remaining choke point is the MCP evaluation output: it still emits the
admitted error map instead of selecting `seon.error/render-ai` through the
terminal `seon.render/render-ai` fit. `src/seon/cluster.clj`, which owns that
projection, is modified-uncommitted by another lane and was protected from
this class slice, so this evidence is recorded without crossing that boundary.
