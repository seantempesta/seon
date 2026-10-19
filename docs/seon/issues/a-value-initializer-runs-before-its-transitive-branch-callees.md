---
type: issue
status: fixed (lane runtime-fix3, item 3)
severity: blocker
created: 2026-10-20
tags: [issue, sci, branch-runtime, review3-astra]
---

# A value initializer runs before its transitive branch callees

`seon.sci.eval/dependency-order` orders a value after its direct calls, but for a function follows only expanded macros. `seon.sci.eval/produced-roots` then evaluates a value while some ordinary transitive callees still have compiled roots. Binding them later cannot repair the value it already computed.

A small loaded source namespace defines `h` returning 1, `g` calling `h`, `f` calling `g`, and `(def a-value (f))`. Every function is contracted and the definitions are admitted through the ordinary index. Change `h` to 2 on a disposable branch. Through `seon.sci.eval/base-ctx`, `[a-value (f) (g)]` returns `[1 2 2]`, expected `[2 2 2]`. A second branch changing `h` to 3 and a continuation through `seon.sci.eval/acquire!` both return `[1 3 3]`, expected `[3 3 3]`. Main still returns `[1 1 1]`.

Red proofs: `seon.runtime-review3-astra-new-test/a-value-executes-its-whole-runtime-during-production` and `seon.runtime-review3-astra-real-test/a-loaded-value-initializer-executes-the-whole-branch`. Real-path candidate `84f7fb4dd309dca3ff587987c73b8dc85a3047dc`, run `45b51eefd740`.

The existing generator tests values holding functions, not eager values calling a transitive chain during initialization. Producing roots before binding the destination prevents a partial destination on failure, but does not make the temporary production runtime complete.

Evidence and limits: [independent review](../../research/agent-platform/2026-10-20/runtime-review3-astra.md). Reproducible probe source: [review patch](../../research/agent-platform/2026-10-20/runtime-review3-astra-probes.patch); it is evidence, not an installed change.

## Fix (lane runtime-fix3, 2026-10-20)

`seon.sci.eval/dependency-order` orders a due value or macro after every due row its reach holds (the reach index's
`::reached-by`, computed once per binding in `seon.sci.eval/bind-analysed!`), not only its direct calls; an open reader
(unknown reach) reads every due function that does not expand it. Proof `seon.sci.runtime-test/a-value-is-produced-after-its-whole-reach`
(`(def a-value (f))`, f -> g -> h, branch and continuation through the production binder): red on HEAD (`[1 2 2]`),
green with the fix.
