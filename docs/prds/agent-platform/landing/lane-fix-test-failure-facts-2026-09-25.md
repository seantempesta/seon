---
type: report
status: measured
created: 2026-09-25
tags: [agent-platform, test, landing]
---

# Lane fix-test-failure-facts-opus (2026-09-25)

The failure-facts tests drive real nested `seon.test/run` requests of a host
probe. Admitted runs record immutable member outcomes and reports
(`runner/complete-members`). The tests now assert that model, and they build
their worlds through the canonical writers.

## Broken or unproven

- **Bounds.** Each nested run costs 3.6–4.8 s. Members with nested runs measured
  6.0–11.6 s on the REPL (see the table below), and root's run 1852 measured
  8.7, 8.6, 6.2 and 5.1 s, all against the 5,000 ms bound. The owner of that
  cost is `docs/seon/issues/a-nested-test-run-costs-four-seconds.md`. I did not
  raise any bound.
- **Not yet run by root.** Root's run (verify-1852) was against commit
  72578e81a. This revision is newer and has only been run at the lane's REPL,
  as described below.
- **One harness gap at the REPL.**
  `recording-keeps-an-absent-reach-member-as-its-symbol` errored there, because
  `(seon.schema/handed-projection)` is nil outside a member. It was green in
  root's run 1852, and this revision leaves that test unchanged.

## Causes, verified at the REPL

1. **The probe row was admitted as `:agent`.** d3edec1cf moved `with-probe` onto
   `support/program-row`, which defaults to `:agent`, so `resolve-test` ran the
   row's SCI source instead of the host Var. The fix, landed in 72578e81a,
   admits the probe row as `:core`. Re-verified: the nested run now records the
   host Var's messages, contexts and line 39.
2. **The tests asserted a retired model.** Admitted runs never write the legacy
   latest-result family: no `:seon.test/failures` entities on the test row, no
   seen-count, no retraction on green (runner.clj `record-tx` routes to
   `complete-members`; `docs/seon/issues/admitted-test-results-are-invisible-to-legacy-consumers.md`).
   A run's claims are `:seon.test.failure/reports` on its result and
   `:seon.test.member/failures` on the member. REPL, one probe world:
   - Two red runs produce the same two report ids.
   - The oversized actual is stored as `actual-blob` with size 10019.
   - The green run gives pass 1, fail 0 and no reports, and `recorded-result`
     returns green.
   - Three distinct reports remain in member history.
   - The test row's `:seon.test/failures` is nil.

   The tests are converted to that model:
   - `one-failing-is-becomes-one-failure-report`
   - `a-repeated-failure-is-the-same-report`
   - `a-green-run-is-the-latest-result-and-keeps-red-history`
   - the blob, interpreted-site and sites tests
3. **Content-keyed reuse.** A run whose program is unchanged reuses its green
   result. Flipping the host Var's `mode` atom changes no input, so
   `a-recorded-test-…`'s red run returned the green result with
   `:seon.test/unchanged true`. The producer is correct. The test now changes
   the program first (the probe calls a new `reached`) and then runs red.
4. **The redeclaration digest was stale.** Since 8e731ac68, `program-row`
   digests the stored row merged with the new one. An exact replacement leaves
   the new row alone, so the digest must be taken of that row. The new
   `replacement-tx` re-digests the exact row through `support/revised-row`;
   `redeclare-probe!`, `redeclare!` and the interpreted test all use it.

## REPL proof

The committed file was loaded into a scratch namespace with `Compiler/load`
under its own source name (so file sites resolve), and each test was run with
`clojure.test/test-var`. The parent handle was default's live handle, extended
to a full execution handle. Each test ran on its own disposable `with-database`
branch.

| test | pass/fail/error | ms |
|---|---|---|
| what-made-it-red-names-changed-functions | 7/0/0 | 11,619 |
| a-recorded-test-renders-its-sites-and-changed-dependencies | 11/0/0 | 10,784 |
| an-interpreted-test-failure-records-no-false-site | 5/0/0 | 6,481 |
| one-failing-is-becomes-one-failure-report | 18/0/0 | 6,358 |
| a-repeated-failure-is-the-same-report | 4/0/0 | 9,273 |
| a-green-run-is-the-latest-result-and-keeps-red-history | 7/0/0 | 9,247 |
| an-oversized-actual-settles-into-a-blob | 6/0/0 | 6,400 |
| failures-render-their-site-and-claim | 6/0/0 | 6,119 |
| failure-readers-use-the-structured-claims | 5/0/0 | 6,024 |
| the other seven | green | 0–2,774 |
| recording-keeps-an-absent-reach-member-as-its-symbol | 0/0/1 | harness gap above |

The custody issue is closed. I verified it by acquiring a child branch under
another branch's custody from a commit holding an uninstallable row: the
`install-row!` error was recorded on the child branch, with no throw.

## Change and size

The only file changed is `test/seon/test_failure_facts_test.clj`. Against
72578e81a it is +80/−58 lines, and against the original d3edec1cf it is about
+150/−105. There are no production edits.

## Root runs

```
bin/test-check default --ns seon.test-failure-facts-test
```

Expected result: every assertion green. Bound failures on the nested-run
members are expected until the nested-run cost issue is fixed.
