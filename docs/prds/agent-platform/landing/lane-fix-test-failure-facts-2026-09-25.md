---
type: report
status: unverified
created: 2026-09-25
tags: [agent-platform, test, landing]
---

# Lane fix-test-failure-facts-opus (2026-09-25)

## Not yet proven

- **The fix is unrun.** Under the load rule, the lane ran no test request. Root
  runs the commands below. A nested `seon.test/run` could not be reproduced
  faithfully at the REPL. With a hand-built parent handle off default's head,
  member acquisition threw "Program acquisition could not record its
  refusals." This is filed as
  `docs/seon/issues/acquisition-refusal-records-write-under-the-callers-custody.md`.
  So the lane has NOT observed pass-count 1 from a nested run of the host probe.
- **Bounds.** The failure-facts members that drive nested runs cost about 4 s
  per run. On 005.txt they measured 8.0, 8.8 and 10.5 s against the 5,000 ms
  bound. After this change `a-repeated-failure-upserts-its-entity` (3 runs),
  `a-green-run-retracts-its-failures`,
  `a-recorded-test-renders-its-sites-and-changed-dependencies` and
  `what-made-it-red-names-changed-functions` (2 runs each) are still expected
  to fail their bounds. The cost is the nested run's, filed as
  `docs/seon/issues/a-nested-test-run-costs-four-seconds.md`. Bounds were not
  raised.

## Cause (producer), verified

1. **Host probe became an agent row.** d3edec1cf moved `with-probe` onto
   `support/program-row`, and that admits `:agent`. The REPL confirmed it:
   `:seon.schema.admission/source :agent`, even with the `:seon.fn/file`
   override. For an `:agent` row, `seon.test/resolve-test` (test.clj:1455)
   takes the `sci/resolve` branch and runs the row's SCI-interpreted source
   `(deftest probe (is (= 1 2)) (is (= :a :b)))`. It never runs the interned
   Var whose body follows `mode`. The effects in 005.txt:
   - A green run still fails twice (pass-count 0, and the old
     failing-assertions remain).
   - No messages and no site are recorded.
   - The interpreted test reports 2 fails instead of 1.

   The fix restores `:core` on the probe row, which was the design before
   65893d82f/d3edec1cf. SCI acquisition skips `:core` test rows (sci/eval.clj,
   around lines 2552–2560, `agent-authored?` filter), and `resolve-test`
   `requiring-resolve`s the host Var. The REPL showed that the definition
   digest is independent of the admission value, and the writer accepted the
   `:core` row.
2. **The reach tests predate the retype.** `:seon.test/reach` has been
   `:db.type/symbol` since 0e8f7d323/2a30b98b8 (verified live:
   `{:db/valueType :db.type/symbol}`). The tests still carried
   `[:seon.fn/sym x]` vectors and queried `?f :seon.fn/sym`. `record-tx` needs
   a set (runner.clj `wanted-reach (when (set? ...))`), so a vector records
   `reach-unknown`. A REPL `record-tx` with symbol sets produced exactly
   `[[:seon.test/reach member.facts/b false]]` on the drop. The producer is
   correct.
3. **The `changed-since-green` readers are stale.** The function returns a
   vector of symbols, but the tests mapped `:seon.fn/sym` over it. It reads
   `:seon.test.member/*` rows from admitted runs, and a hand `completion`
   passed to `commit-results!` never creates those rows.
   `what-made-it-red-names-changed-functions` therefore now uses real nested
   runs of the host probe. The rendered changed form quotes its symbol, which
   is correct and evaluable, and the test now expects `(quote s)`.

`record-latest-tx`, `prepare-failures!` and `commit-results!` all record
correctly. No write refusal and no silent recording tx refusal was found. The
nested run DID execute its body, but it executed the wrong one.

## Change

The only file changed is `test/seon/test_failure_facts_test.clj`, +89/−65:

- `with-probe` writes the host `:core` row.
- `redeclare-probe!` re-declares the probe and its sibling functions from one
  analyzed source, keeping `:core` and the file.
- `stored-reach` reads the symbol-typed reach within the test's namespace.
- The interpreted test writes its own `:agent` row.
- `recording-mints-an-absent-identity…` is renamed to
  `recording-keeps-an-absent-reach-member-as-its-symbol`. It asserts that the
  symbol is recorded and that no row is minted. Tombstone minting was retired
  with the retype.

## REPL evidence (default, lane-tff scratch ns, disposable with-database branches)

- The `program-row` admission probe returned `:agent`. The digest was the same
  under the `:agent` and `:core` namespace rows (`c1d78b5a…`).
- `record-tx` with symbol sets: first pass wrote reach a, b, check; second pass
  wrote only `b` retracted. The stored set was `[a check]`.
- Writer: accepted the `:core` probe row plus its `:agent` replacement.
  `redeclare-probe!` ran ×4 and every write was accepted. Admission stayed
  `:core` with the file, a's digest changed on each redeclaration, reach
  included `a` and then `reached`, and `changed-since-green` returned unknown
  before any green. Timings: 1.6–3.5 s per disposable branch body, mostly
  acquisition and `with-declarations`.

## Root runs (serial, quiet window)

```
bin/test-check default --ns seon.test-failure-facts-test
```

Expected: every assertion green. Bound failures are possible on the four
multi-run members named above, from the nested-run cost issue.

## Commits

One commit: the test file, this note and the two issues.
