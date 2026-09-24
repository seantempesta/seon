---
type: issue
status: resolved
severity: defect
tags: [issue, sci, eval, bounds]
---

# Printed output of an agent evaluation has no bound

## Problem

`seon.sci.eval/evaluate` binds SCI's `*out*` and `*err*` to one unbounded
`java.io.StringWriter` and returns everything printed as
`:seon.cluster.eval/output`. Nothing bounds it. A print loop grows the heap
without limit, and the whole text reaches storage and the REPL response
(`seon.repl` `:seon.repl/out`). The namespace docstring says the output is
"bounded by the same `max-string` cap the projection uses". That claim is
false: admission dropped its display caps (`seon.config` `result-cap-attributes`
comment), and output was never routed through the value renderer.

## Evidence

Default JVM (pid 90471, archive `ce7384682`), branch `fix-entry-eval-2`,
2026-09-24:

- `(do (println "hi") 1)` → `:seon.cluster.eval/output "hi\n"`, shown `"1"`.
  Output is captured. The toy loop in §8b of
  `docs/research/agent-platform/decoration-language-v0-2026-09-24.md` read only
  `:seon.eval/shown`, so it looked as if output was dropped.
- `(do (dotimes [_ 20000] (print "xxxxxxxxxx")) :done)` → a 200,744-character
  MCP result. The whole 200 KB was returned verbatim.
- `seon.config/defaults`: `max-bytes` 8,388,608, `max-string` 262,144.

## Proposed fix (patch kept, not applied: the item was cancelled by root)

`tmp/fix-entry-2-output-bound.patch` (+~40 lines in `evaluate`) replaces the
`StringWriter` with a writer that streams UTF-8 bytes under the caps'
`:seon.config.eval.result/max-bytes`. The first fragment that would cross the
bound throws admission's declared `:seon.sci.admit/error` shape
(`:seon.sci.admit/bound-bytes`, `:seon.sci.admit/bytes`,
`:seon.error/member :seon.config.eval.result/max-bytes`). The text kept is
exact, and the loop stops. Presentation clipping stays with the value renderer.

The patch also corrects the false docstring, and it adds the regression
`printed-output-rides-with-the-value-under-the-storage-bound` in
`test/seon/sci/entry_gate_test.clj`. On a `git archive` HEAD+patch root, run
`cedb990fbe64` of `seon.sci.entry-gate-test` executed 4 tests: 18 pass, 0 fail, 7,575 ms.

Open question for the owner: should the bound be `max-bytes` (storage, 8 MB)?
Or should printed output be a value rendered through `seon.render.value` under
the render profile?

Not covered: compiled host code that prints to `clojure.core/*out*`. It is not
captured, because only SCI's `*out*` is bound.

## Resolution

Landed in `fbbc9c596` as ruled by root: output streams under `max-bytes`.
