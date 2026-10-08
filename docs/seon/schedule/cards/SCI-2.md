---
title: "Card SCI-2 Arm contracted functions once in the base, not in every fork"
type: reference
status: active
created: 2026-10-08
---

# SCI-2 Arm contracted functions once in the base

After SCI-1. Task: `seon.sci.eval/an-inherited-test-never-sees-the-branch-redefinition-of-its-callee` (its producer:
the contract loop of `seon.sci.eval/fork-cluster-ctx`). Lane: Opus.

## What becomes true

`seon.sci.eval/fork-cluster-ctx` re-arms no function. Under context-owned bindings its loop
(`install-function-contract!` for every installed interpreted function, through `sci.core/bind-root!`) would give
every agent fork its own binding of every contracted Var, so every contracted call in every context takes the
per-context read path (+10-15 ns each) and the fork pays O(contracted functions) writes. Arming happens where the
definition is installed: `seon.sci.eval/install-row!` / `seon.sci.arm/arm!` in the context that owns the definition
(root's live context for main, the agent's own context for a branch definition). A fork inherits the armed root as
it inherits every root.

The loop exists today because "a sovereign branch must not inherit the source branch's recorder": the wrapper
`seon.sci.eval/install-function-contract!` builds (`seon.instrument/wrap-interpreted`) closes over the arming
context's `:seon.flow/commit-fault!` (from its program snapshot), its projection and a
`seon.call-preparation/snapshot` of the arming database. Move those out of the closure: the wrapper reads them from
the executing world (`seon.env/of` the active `sci.ctx-store/*ctx*`, as CLAUDE.md's "Supply defaults by declared
schema name, from the world" requires), so one armed root serves every context. Note `@sci-var` in
`install-function-contract!` is a host read: under SCI-1 it runs inside `with-ctx ctx`.

## Functions

- CHANGE `seon.sci.eval/fork-cluster-ctx`: delete the `doseq` over `::kernel/installed-functions` calling
  `install-function-contract!`.
- CHANGE `seon.sci.eval/install-function-contract!` and `seon.instrument/wrap-interpreted`: take commit-fault!,
  projection and call-preparation snapshot from the executing context's environment at call time, not from the
  arming context. If the call-preparation snapshot must stay per arming, stop and report: that is a design question
  for root, not a patch.

## Proof

`seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition` (a new agent's base no longer holds a
copied callee) and an existing contract-recording test proving a call in a sibling cluster records into its own
sink (`seon.sci.eval-test` recorder isolation; name it in the commit). Measure `fork-cluster-ctx` before and after
at the live program size (expected: from O(contracted functions) to O(1) plus acquisition).

## Cost

Fork: removes one `bind-root!` per contracted function (5,700-function scale: milliseconds per fork today).
Call: one environment read per armed call.

## Commit

`bin/lane commit LANE -m MSG --test seon.repl-differential-test --test seon.sci.eval-test src/seon/sci/eval.clj`.
