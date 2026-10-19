---
type: issue
status: fix implemented in the SCI fork (20ef88d7 on branch fork-method-tables); gitlink bump pending root's restart batch
severity: blocker
created: 2026-10-20
tags: [issue, sci, branch-runtime, review3-astra]
---

# A multimethod change mutates sibling runtimes

The pinned SCI `sci.core/fork` copies the environment map and isolates Var bindings, but keeps the same `clojure.lang.MultiFn` value. `sci.impl.multimethods/multi-fn-add-method-impl` calls `.addMethod` on that shared mutable object. `seon.sci.eval/fork-cluster-ctx` uses this fork, so a method write can affect its parent and sibling without writing their Vars or databases.

Acquire a context from the fixture database, define `(defmulti mm identity)` and `(defmethod mm :a [_] 1)` privately in it, then make two acquired forks. In the first fork evaluate `(defmethod mm :a [_] 2)`. Calling `mm` in first fork, second fork and parent returns `[2 2 2]`, expected `[2 1 1]`. A raw SCI fork control has the same failure. These are interpreted declarations, not JVM host-bound definitions, and the mutation does not use `alter-var-root` or a global test redefinition.

Red proofs: `seon.runtime-review3-astra-extra-test/changing-a-multimethod-is-local-to-the-fork` and `seon.runtime-review3-astra-real-test/a-multimethod-change-is-local-to-an-acquired-fork`. Real-path candidate `84f7fb4dd309dca3ff587987c73b8dc85a3047dc`, run `45b51eefd740`. SCI pinned revision `33039c6e49b47096a68d2964db9be382525bf75e`.

There is no `defmulti` in production `src` at the reviewed HEAD; agents can still define one. The requirement covers their programs and compositions. The library's mutable method-table semantics need an explicit ownership model compatible with branch isolation; a Var fork alone does not provide it.

Evidence and limits: [independent review](../../research/agent-platform/2026-10-20/runtime-review3-astra.md). Reproducible probe source: [review patch](../../research/agent-platform/2026-10-20/runtime-review3-astra-probes.patch); it is evidence, not an installed change.

The protocol case also reproduces: define a protocol and record on the acquired parent, fork twice, then `extend-type` the inherited record in the first fork. `[first-fork sibling parent]` returns `[2 2 2]`, expected `[2 1 1]`. `seon.runtime-review3-astra-real-test/a-protocol-extension-is-local-to-an-acquired-fork` is red in run `be988cc070d1`, candidate `52780d2fa7a3058a29482e52eed847a6b06f7b98`. SCI's protocol-extension implementation reaches the same mutable method-table owner.

## Fix (lane runtime-fix3, 2026-10-20)

At the owner, the SCI fork: a method table is part of its Var's value, so a context writes it the way `sci.core/fork`
has it write the Var. `sci.impl.multimethods/multi-fn-add-method-var-impl` adds the method in place when the executing
context owns the MultiFn (it owns the Var and has no binding of its own, or it made the MultiFn in its current
generation); otherwise it copies the MultiFn (dispatch, default, hierarchy, methods, preferences), adds the method to
the copy and binds the copy as the context's own value of the Var (an `:extend` write, bookkeeping, not a definition).
`defmethod` of a symbol and `sci.impl.protocols/extend` call it with the Var, so `extend-type`, `extend-protocol` and a
record's inline methods are covered. SCI commit `20ef88d7` (branch `fork-method-tables` of `reference-code/sci`); the
SCI clj suite has the same two pre-existing failures as `33039c6e` (432 tests).

Seon proof: `seon.sci.eval-test/a-method-written-in-an-acquired-fork-is-its-own` (defmethod and protocol rows through
`seon.sci.eval/fork-cluster-ctx`): red on HEAD `ac55beffa` (check run `5dbb667dc906`, 2 failures), green with the bump
(candidate `b394f586`, run `0894a51dab8a`, 10 tests with `seon.sci.runtime-test` and the every-entrance row, 35 pass).
The bump and the test are [runtime-fix3-method-tables.patch](../../research/agent-platform/2026-10-20/runtime-fix3-method-tables.patch)
for root's next restart batch (a fork bump commits only there); the SCI change is also
[runtime-fix3-method-tables-sci.patch](../../research/agent-platform/2026-10-20/runtime-fix3-method-tables-sci.patch).
Not covered: `remove-method` and `prefer-method` (host functions on the value, which cannot name its Var) and `derive`
on the global hierarchy still write shared state.
