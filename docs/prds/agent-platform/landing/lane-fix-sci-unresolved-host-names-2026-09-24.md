---
type: landing
status: complete
lane: fix-sci-unresolved-host-names
created: 2026-09-24
---

# Lane fix-sci-unresolved-host-names (2026-09-24)

An interpreted affected caller analyzes in SCI against its namespace row's
bindings (`seon.sci.eval/install-function-from-database!`) plus the compiled
names `install-caller-host-bindings!` (b855a8a17) copies. The row records only
the `ns` form. So an alias that a namespace makes with a top-level `require`
was invisible to the interpreted caller. The fix binds the loaded namespace's
own aliases as well; the row's aliases win.

## Still broken

- **The census's 2b names were already fixed; the live cause was a different
  name.** The same-shape probe (below) is clean on default for
  `seon.cluster/publication-base!` (`spit`),
  `seon.test.interrupted-request-test/admitted-run!` (`finished`) and
  `seon.flow-test/install-test-work-launcher!` (`test-environment`). The spit
  reds ran at 18:26Z and the test-environment reds at 18:34Z, both before
  b855a8a17 was committed at 18:31Z. The `finished` reds ran at 20:26Z; I did
  not establish why that run missed the private def, and the probe now resolves
  it. On rerun, all 24 failed on one other name instead:
  `Cannot interpret my.edit/exact!: Unable to resolve symbol: effect/request!`.
- **After the fix, 19 of the 24 are red with the census's class 2c.** The
  message is `Host-bound declaration seon.test.runner/recorded-member-results
  must change through the loaded source files`. That refusal is correct:
  `src/seon/test/runner.clj` has another lane's uncommitted edit, which is
  published but not adopted. Five more reds are foreign: a fixture write
  refused on `:seon.program/definition-digest`, an `:seon.error/declared-schema`
  refusal, a 20.5 s over-bound test, and two assertions. None says
  "Unable to resolve".
- **The namespace row's aliases fact is incomplete (out of scope; the analyzer
  owns it).** `seon.fn/namespace-context` (fn.clj:1164) reads only the ns-form
  clauses (`seon.fn.analyzer/with-namespace-clauses`, analyzer.clj:558). So
  `my.edit`, `my.shell` and `my.fs`, which each have
  `(require '[seon.effect :as effect])` at top level, store no `effect` alias.
  - An agent evaluating `effect/...` in one of those namespaces in SCI still
    fails the same way. This lane's fix covers interpreted callers only, not
    agent evaluation.
  - Root must choose between two fixes. The row could record file-level
    aliases, which changes what `:seon.ns/source` corresponds to. Or the three
    files could move the require into the `ns` form, which the `seon.effect`
    load-order comment in `src/my/edit.clj:29` currently prevents.
  - I did not file an issue for this, because no issue path is in my ownership.
    Root, please record one.
- **`interpreted-callers-resolve-unchanged-names-to-compiled-vars` is red for a
  foreign reason** (run 634cc5fcdea9). `seon.cluster/serve!` now loads as
  `:unavailable`, not `:interpreted`. 55af9e04b reclassified `serve!` as
  host-bound (see lane-fix-env-macro-callers), so the test's `callers` list is
  stale. That test predates this lane and I did not edit it.

## Change

`src/seon/sci/eval.clj` (+16/−3):
- New private function `caller-bindings`, contracted. It is `row-bindings`
  with the loaded host namespace's `ns-aliases` merged under the row's own
  aliases. With no loaded namespace, it returns the row's bindings unchanged.
- `install-function-from-database!` installs `caller-bindings` in both of its
  `sci/install-namespace-bindings!` calls.
- `install-caller-host-bindings!` derives alias targets from `caller-bindings`,
  so a dependency target that only a top-level require names also gets its
  public Vars.

`test/seon/sci/branch_execution_test.clj` (+31/−0): new regression
`an-interpreted-caller-resolves-its-compiled-namespace-aliases-privates-and-core`.
- It stamps new digests on `my.edit/exact!` (alias `effect` from a top-level
  require) and `seon.cluster/publication-base!` (`spit`).
- It asserts that both rows load as `:interpreted`, and that no acquisition
  refusal says "Unable to resolve" or "does not name an installed SCI Var".

## Evidence (default pid 99265; outputs in `tmp/fix-sci-unresolved-host-names/`)

| Probe | Result |
|---|---|
| JVM probe `probe.clj`: a Datahike branch of default, one changed digest, `eval/acquire!`, branch deleted in `finally`. `my.edit/exact!`, before the fix | 1 refusal, `Unable to resolve symbol: effect/request!`, 1,090 ms |
| same probe, after the fix | 0 refusals; 970, 1,021 and 1,064 ms |
| same probe on `admitted-run!`, `publication-base!` and `install-test-work-launcher!`, before the fix | 0 refusals; 913, 973 and 748 ms |
| the 24 census 2b reds, before (run 147d4892fa90, `before-24.txt`) | 0 pass / 24 error, all `my.edit/exact!` … `effect/request!` |
| the 24, after (run c3d25d9d1da9, `after-24.txt`) | 0 unresolved-name lines; 19 are class 2c `recorded-member-results`, 5 are foreign |
| regression with the fix (run 9eea3372365b) | pass 2 / fail 0, 6,359 ms |
| regression with the fix disabled by `(and false …)` (`regression-without-fix.txt`) | red: `["Cannot interpret my.edit/exact!: Unable to resolve symbol: effect/request!"]`, and `exact!` absent from load results |
| 10 of the census's 76 stale 2a reds (run 486a7fb40049, `stale-10-after.txt`) | 8 green; 0 LocalBinding. The 2 reds are foreign: a `:seon.agent/branch` fixture refusal, and a `flow-test:1157` `:seon.instrument/check` assertion |

The acquisition is over one second before and after (748–1,140 ms). It is
proportional to the interpreted reverse closure, not to this change. The
change adds one `ns-aliases` read per interpreted namespace install, and the
timings above show no measurable difference. Other lanes' test runs were
loading the JVM at the same time.

Net lines: +47/−3 (source +13, test +31). `src/seon/fn.clj` is unchanged.
RESET NEEDED: no. I created no MCP branches. Each probe branch was deleted in
`finally`.

## Limits

- Hook publication raced other lanes: "Source changed during development
  adoption" and "adoption basis moved". Before each run, I checked with
  `resolve` that the edited Var and the test were loaded.
- The regression uses `my.edit/exact!` as its alias example. If that file
  moves its require into the `ns` form, the regression still passes, but it
  no longer exercises the alias path.
