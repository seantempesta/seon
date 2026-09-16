---
type: issue
status: open
severity: friction
tags: [issue, schema, render, test]
---

# Evaluation reader refuses its pulled renderer ref

On 2026-09-16, the default JVM's in-process
`seon.render.faults-test/the-opening-derives-repair-reads-through-function-refs`
recorded 2 passes, 0 failures, 1 error. `seon.eval/of-agent` refused its return
at `[0 :seon.eval/renderer-fn]`: expected an integer, got a map.

`src/seon/eval.clj:18` declares its output as `:seon.eval/entity` and its
default selector at line 26 includes `*`. The new renderer-fn field in
`resources/seon/schemas/seon.eval.edn:2` aliases `:seon.db/ref`, while pull
returns a ref map. This observation does not establish whether a newer
adoption has repaired the mismatch; it is the exact failed live boundary.

The evaluation reader/schema owner must accept the actual pulled ref shape
or return a declared normalized ref consistently. Verify on the canonical
armed fixture after adoption, including an evaluation with a renderer ref.
Do not suppress the ref or disable the output contract. The error-graph lane
left these owners unchanged; its direct error render regression passes.
