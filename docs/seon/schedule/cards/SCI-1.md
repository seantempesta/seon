---
title: "Card SCI-1 Seon on context-owned SCI Var bindings: the gitlink bump and what must land with it"
type: reference
status: done (f8f2618e8)
created: 2026-10-08
---

# SCI-1 Seon on context-owned SCI Var bindings (lands with the gitlink bump)

Task: `seon.sci.eval/an-inherited-test-never-sees-the-branch-redefinition-of-its-callee`. Research:
docs/research/agent-platform/2026-10-08/sci-fork-vars/sci-vars-impl.md (lane sci-vars, option A, owner ruling
"correct now, fast later"). SCI fork `seon-env-hook` at `cff2cefb` (remote `fork`). Lane: Opus (boot-critical: every
SCI Var read and write changes meaning). Root's restart batch: this card and the gitlink bump are one commit; Seon at
the new pin without it misrecords redefinitions.

## What changed in SCI (`cff2cefb`)

- A Var keeps its identity across `sci.core/fork`. A context that does not own a Var writes its own binding
  (`sci.impl.vars/Binding`: root, meta, watches) under `:sci/var-bindings` in its env; analyzed code anywhere reads
  the binding of the executing context (`sci.ctx-store/*ctx*`). `sci.core/bind-root!`, `sci.core/intern` of an
  existing name, `alter-var-root`, `alter-meta!`/`reset-meta!` and `def` all follow that rule;
  `sci.core/bind-root!` returns the same Var (no copy).
- Ownership (`sci.impl.vars/owns?`): no bound context, a built-in, or a Var whose own `:sci/generation` equals the
  context's. `sci.core/fork` renews the generation of BOTH contexts. A context's binding keeps the Var's own
  `:sci/generation` in its metadata; a redefinition in a fork no longer stamps the fork's generation.
- A host read (`deref`, `meta`, `.getRawRoot`, invoking an SCI fn) with no `*ctx*` bound reads the Var's own
  binding, and a host write with no `*ctx*` writes the Var in place, visible to every context without its own
  binding.

## Functions (all in `src/seon/sci/eval.clj` unless named)

1. CHANGE `seon.sci.eval/turn-interns`: "this turn's definitions" are the Vars the turn context wrote, not the Vars
   stamped with its generation: Vars born in its generation (new names, as today) plus every Var whose own binding in
   the turn context (`sci.impl.vars/context-binding`) is not `identical?` to its binding in the context it forked from
   (`::base-ctx`, or the program snapshot's context). The `::program-namespaces` identity shortcut stays for new names
   only; redefinitions do not change the namespace map any more.
2. CHANGE `seon.sci.eval/transfer-evaluated-roots!`: for a name the receiving context already holds, `sci/intern`
   already writes the same Var's binding there; keep it, and do the following `reset-meta!` inside
   `(sci.ctx-store/with-ctx ctx ...)` (today it runs with no context, so it would write the shared Var in place). For
   a new name, install the evaluated Var object itself (`sci/add-namespace! ctx ns {name var}`) so closures that
   captured it and the base agree (sci-sol.md, "transfers must preserve identity"); drop the `:sci/generation`
   carry in the merged metadata (SCI keeps it).
3. CHANGE `seon.sci.eval/regenerate-agent-context!` and `seon.sci.eval/acquire!`: delete the
   `(swap! (:env …) assoc :sci/generation generation)` restores. Restoring an old generation hands the context
   ownership of Vars its earlier forks still share, and its next write leaks into them. "Own" becomes "this context
   has its own binding of the Var or the Var was born in a generation this context held" (carry the agent's set of
   born generations, or the Vars themselves, which `turn-interns` already returns). `own?` and the `reset-meta!` in
   `regenerate-agent-context!` run inside `with-ctx regenerated`.
4. CHANGE every Seon entrance that reads or writes SCI Vars from JVM code to bind the context it means:
   `seon.sci.eval/fork-cluster-ctx` (the contract loop's `(meta @candidate)`), `seon.sci.eval/install-evaluated-rows!`,
   `seon.sci.eval/install-selected!`, `seon.sci.eval/acquire!`, `seon.sci.arm/arm!`, and the test runner's dispatch of
   an SCI deftest (`seon.test.runner`, `clojure.test/test-var` on a resolved SCI Var) wrap their bodies in
   `(sci.ctx-store/with-ctx ctx ...)`. `seon.sci.observe` already binds it for evaluation.
5. CHANGE `seon.sci.eval-test/sci-fork-copies-existing-var-roots-on-write`: the redefinition keeps the Var
   (`identical?`), each context reads its own root; rename it to `sci-fork-bindings-are-per-context`.

## Proof

`seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition`,
`seon.repl-differential-test/an-inherited-caller-sees-a-same-pass-redefinition`,
`seon.repl-differential-test/another-writer-on-the-cluster-head-neither-hides-nor-freezes-a-callee`,
`seon.sci.eval-test/a-branch-change-leaves-its-compiled-callers-compiled` and the renamed fork test stay green with
the re-analysis still in place (SCI-3 deletes it). Then one live eval request on a throwaway root at the candidate:
`:ping` and a `defn` redefining an inherited function, with `:seon.repl/ms` and `:seon.repl/alloc-mb` beside the
parent commit's numbers for the same two forms (the measurement this lane could not take without editing `src/`).

## Cost

Per Var read: unchanged for Vars no fork wrote; +10-15 ns for a Var some fork wrote (sci-vars-impl.md table), about
0.2-0.8 ms on an eval request making tens of thousands of such reads. `turn-interns` stays O(namespace entries) per
turn; the binding comparison is one identity check per Var.

## Dependencies and order

Root's batch: bump `reference-code/sci` to `cff2cefb` (or the fork head then), this card, `bin/seon check` naming the
five tests above, then `start --head`. `bin/lane commit LANE -m MSG --test seon.repl-differential-test --test
seon.sci.eval-test reference-code/sci src/seon/sci/eval.clj src/seon/sci/arm.clj src/seon/test/runner.clj
test/seon/sci/eval_test.clj`.
