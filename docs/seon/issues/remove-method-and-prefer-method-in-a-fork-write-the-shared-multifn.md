---
type: issue
status: open
severity: low
created: 2026-10-20
tags: [issue, sci, branch-runtime]
---

# `remove-method` and `prefer-method` in a fork write the shared MultiFn

Found by lane review4-opus on HEAD `342e14357` (SCI `20ef88d7`), 2026-10-20; evidence in
[runtime-review4-opus](../../research/agent-platform/2026-10-20/runtime-review4-opus.md) (N-FS). Residual of
[a multimethod change mutates sibling runtimes](a-multimethod-change-mutates-sibling-runtimes.md), which lists these
calls as not covered.

A parent context (`seon.sci.eval/base-ctx`) defines a multimethod; two forks are taken with
`seon.sci.eval/fork-cluster-ctx`; the first fork writes; the query runs in `[fork sibling parent]`:

| Write in the first fork | Answer | Expected |
|---|---|---|
| `(remove-method rt4.mm/mm :a)` (methods `:a` 1, `:default` 0) | **`[0 0 0]`** | `[0 1 1]` |
| `(prefer-method rt4.pm/mm :rt4.pm/q :rt4.pm/p)` (`::x` isa both) | **`[:q :q :q]`** | `[:q :ambiguous :ambiguous]` |

Root cause: SCI copies `clojure.core/remove-method`, `prefer-method` and `remove-all-methods` from the host
(`copy-core-var` in `sci.impl.namespaces`); they call `.removeMethod`/`.preferMethod` on the MultiFn value, which a
fork shares with its parent until a `defmethod` in the fork copies it (`sci.impl.multimethods/multi-fn-add-method-var-impl`).
They cannot name the Var, so the fork's copy-on-write does not reach them.

Correction to the parent issue's note: `derive` on the global hierarchy is per context (`[true false false]`), because
`sci.impl.hierarchies` writes through `vars/alter-var-root`, which writes a non-owned Var as the context's binding.
Also per context: `alter-var-root`, `alter-meta!`, `intern`, `ns-unmap` and `alias` (each `[changed original
original]`).

Scope: agent-defined multimethods only (`src` holds no `defmulti`); the host's `print-method` is SCI's own guarded
copy. Proof to write: rows beside `seon.sci.eval-test/a-method-written-in-an-acquired-fork-is-its-own`.
