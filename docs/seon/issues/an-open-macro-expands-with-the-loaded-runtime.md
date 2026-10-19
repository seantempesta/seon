---
type: issue
status: fixed (lane runtime-fix3, item 3)
severity: high
created: 2026-10-20
tags: [issue, sci, branch-runtime, review3-astra]
---

# An open macro expands with the loaded runtime

`seon.sci.eval/bind-analysed!` makes a macro due only when its name is in the closed `reached` set. `seon.sci.runtime/reach-index` excludes open names from that set. A loaded macro using runtime resolution therefore stays compiled even on a changed branch; its expansion sees main's names. Its callers can be analysed and still expand the wrong macro.

The loaded source fixture defines `h` returning 1, `(defmacro open-macro [] ((resolve 'seon.review3-fixture/h)))`, and `open-caller` expanding it. Change `h` to 2 in the database and acquire the branch normally. `[h-call macro-call caller-call]` returns `[2 1 1]`, expected `[2 2 2]`. The stored macro is `:seon.fn/macro? true`, `:seon.fn/host-bound? false`. This is not the accepted host-bound exception.

Red proofs: `seon.runtime-review3-astra-extra-test/a-loaded-open-macro-reads-its-branches-helper` and `seon.runtime-review3-astra-real-test/a-loaded-open-macro-executes-the-whole-branch`. Real-path candidate `84f7fb4dd309dca3ff587987c73b8dc85a3047dc`, run `45b51eefd740`.

This is the open-macro case missing from R3's closed-macro fix. The existing generator's macro never performs runtime name resolution. Its key also needs to describe what an open expansion can read; fixing only the due condition does not prove reuse correct.

Evidence and limits: [independent review](../../research/agent-platform/2026-10-20/runtime-review3-astra.md). Reproducible probe source: [review patch](../../research/agent-platform/2026-10-20/runtime-review3-astra-probes.patch); it is evidence, not an installed change.

## Fix (lane runtime-fix3, 2026-10-20)

An open macro (its reach unknown) runs analysed: `seon.sci.eval/bind-analysed!` makes it due when it has no analysed
root, so the shared program context analyses it once (`my.background/background` and `my.test/run` on the loaded
program) and every fork inherits it, resolving late in its own context; its callers are re-keyed by every change the
runtime holds, since any may be what it reads. Proof `seon.sci.runtime-test/an-open-macro-expands-with-its-runtime`
(a compiled macro finding `h` by `resolve`): red on HEAD, green with the fix.
