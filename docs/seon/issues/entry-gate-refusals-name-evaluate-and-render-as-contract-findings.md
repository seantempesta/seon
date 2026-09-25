---
type: issue
status: open
severity: friction
tags: [issue, sci, render, errors]
---

# Entry-gate refusals name `evaluate` and render as contract findings

Observed 2026-09-25 by lane impl-repl-s1-revise-2 on default (pid 32831), MCP
`eval_clj` in SCI mode on branch `impl-repl-s1-revise-2`. Every entry-gate
refusal an agent receives ends with:

```
Contract finding: seon.sci.eval/evaluate returned this error, and none of its declared error alternatives matches it.
```

Forms: `(defn pending "Missing contract." [x] x)` (missing contract) and
`(do (defn f "Identity." {:malli/schema [:=> [:cat :int] :int]} [x] x) (alter-var-root #'f (constantly inc)))`
(unindexed change).

## Cause

The constructors `unindexed-change`, `multiple-declarations` and
`missing-contract` in `src/seon/sci/eval.clj` build their refusals with
`:seon.error/operation 'seon.sci.eval/evaluate`. `evaluate` returns
`:seon.sci.eval/evaluation`, so `seon.render/declared-producers`
(`src/seon/render.clj:506-515`) finds none of `evaluate`'s alternatives
satisfied and selects `seon.error/render-undeclared-ai`
(`src/seon/error.clj:1860`). The errors are declared, as
`:seon.sci.eval/unindexed-change-error`, `:multiple-declarations-error` and
`:declaration-absent-error`, but not under the operation they name.

## Smallest fix

Name the operation whose contract returns the declared error. That is the
constructor itself, as `declaration-absent` already does. The alternative is
admission's owner, with its throws declared. Then update the assertion
`(= 'seon.sci.eval/evaluate (:seon.error/operation refusal))` at
`test/seon/sci/entry_gate_test.clj:57`. Check that the agent face shows the
declared renderer's output and no contract-finding line.

The same refused `defn pending` also measured 247 ms and 648 MB allocated on
its first evaluation on the branch. A later refusal of the same kind cost
26 ms and 28 MB. Profile that first cost with the fix.
