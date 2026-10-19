---
type: issue
status: open
severity: defect
created: 2026-10-20
tags: [issue, sci, retention, branch-runtime, review4-astra]
---

# Forked method updates retain superseded method tables

SCI pin `20ef88d7ea08813a5ab8172467672356c8b6e9b7`, Seon `342e14357`. The method-table isolation fix retains old copied MultiFns in each continuing environment's `:sci/multi-fns` map. Every fork renews the generation but preserves this map. `sci.impl.multimethods/multi-fn-add-method-var-impl` associates the next copy with the new generation; nothing removes the previous keys.

A finite reproduction uses `seon.sci.eval/fork-cluster-ctx`, not an alternate fork implementation. Define one multimethod and one method returning 0 on the parent. Repeat 200 times: fork the current context, replace that same method with one returning the iteration number, and retain only the newest context. The final context contains:

```clojure
{:tables 200, :methods 1, :answer 200, :parent 0}
```

One current method table is needed for this one multimethod. The ownership map instead strongly retains 199 superseded copies, including their method functions. Garbage collection cannot remove those map keys. The original parent is unchanged, so the addition-isolation fix works; retention is the defect. Larger tables amplify the retained history. This state is outside the central Caffeine budget.

Red measurement: `seon.runtime-review4-astra-test/forked-method-updates-retain-only-the-current-table`, candidate `b76ec541697bb08cfb7ff0638f096589e4f6713c`, run `2b4997cbe95c`. The answer, parent isolation and one-method assertions pass; the retained-table bound is red, 200 against 1. Source inspection locates the retention at `sci.impl.multimethods/owns-multi-fn?`, `sci.impl.multimethods/multi-fn-add-method-var-impl` and `sci.core/fork`.

This establishes retained-object growth with the number of updates, not a measured retained-byte count or an observed OOM. It is filed as a memory risk, not evidence that default crashed. The owner needs to retain ownership only for live tables; a larger cache budget does not bound this environment map.

[Review](../../research/agent-platform/2026-10-20/runtime-review4-astra.md) and [reproducible probe patch](../../research/agent-platform/2026-10-20/runtime-review4-astra-probes.patch).
