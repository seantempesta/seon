# Branch tests see the branch's definitions (2026-10-08, lane branch-tests)

Task `seon.sci.eval/an-inherited-test-never-sees-the-branch-redefinition-of-its-callee`.

## Cause (measured on default 6cc5dbbb4, scratch pair `bt.slug` / `bt.slug-test`, agents bt-a, bt-b)

A SCI fork copies a Var on its first write (`sci.impl.utils/owned-var`); bodies analyzed
before the copy keep the old Var. A branch `defn` of `bt.slug/slug` lands in two contexts:

1. the pass fork (agent ctx): `seon.sci.eval/reinterpret-inherited-callers!` re-analyzed
   inherited callers but not tests: the test Var stayed the root's (generation probe:
   test gen = root gen, slug gen = agent gen; calling the test's `:test` fn answered false);
2. the agent's live base (`seon.sci.eval/install-evaluated-rows!` via
   `transfer-evaluated-roots!`): the settlement interns a new Var there and re-analyzes
   nothing. The test step's world (`seon.test/clean-request-handle`) forks that base, and
   `seon.sci.eval/acquired-database?` answers true for the world (equal program), so no
   re-acquisition repairs it: the world's test called the root's `slug` ("") every pass.

A SCI detail matters for the repair: `sci.impl.analyzer/init-var!` keeps an inherited Var,
so a `deftest` re-evaluated in a fork names the inherited Var in its body
(`(var slug-lowers)`). Re-interpretation therefore copies each Var into the generation
first, as `install-selected!` already does for callers.

## Fix

One selector and one re-interpreter (`stale-reach`, `reinterpret-reach!`) used at both
landings: the pass fork (callers and tests the fork holds as the base's) and the live base
(when a definition landed in a new Var there, the agent-authored callers and tests reaching
it, outside the batch). Cost follows the reverse closure and the test reach, which the
re-interpretation now uses (LR-STALE3 measured that read at 11-17 ms for 50 callers).

## Proof

`seon.repl-differential-test/an-inherited-test-sees-the-branch-redefinition`: red at the
fork-only variant (both passes red), green with both landings (bin/seon check, 2 tests,
7 assertions, 4974 ms).
