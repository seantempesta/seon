# Lane fix-db-decode — native `seon.db/q` results skip the decode walk (2026-09-24)

Report: `docs/research/agent-platform/sci-context-efficiency-opus-2026-09-24.md`, root cause 5, slice 2.

## Broken first

- **The edit does not reach default.** Default (pid 90471) runs the committed archive `ce73846828a5`
  with hook publication off. Proof below comes from a `git archive` snapshot of HEAD `341305b4f` plus this
  diff, booted as its own root (`tmp/fix-db-decode/snap`, since stopped). Adoption on default is owed:
  **RESET NEEDED** (or `start --head`) before default shows the new numbers.
- **The new regression never ran through `seon.test/run`.** The snapshot root booted before the
  regression was added. After a restart, the test was "not eligible" because its program rows were not
  adopted. `--gate` refused adoption on the pre-existing reds. The regression's assertion (`identical?`
  to `d/q` for three native query shapes) was run directly in the snapshot JVM on its live store and held.
- **`seon.db-test` is red at HEAD, independent of this change.** The snapshot showed 19 reds, mostly fixture
  writes refused. The EDN-codec fixture cannot write a symbol, so no test currently proves decoding.
  Filed as `docs/seon/issues/db-test-edn-codec-fixture-writes-are-refused.md`. The same failure shows on
  default without this diff. My HEAD-baseline rerun of the snapshot was invalid, because a shell file
  swap was not republished. The claim "same reds without the diff" rests on default's run, not on a
  clean A/B. `collection-bound-query-evidence-covers-every-input-attribute` was red in one of three
  snapshot runs on its 5 s bound (5.7–6.2 s, niced cold root; its query aggregates, so it walks exactly as
  before).
- Snapshot boot from zero took 101.6 s, with a dependency-class cache miss (pins unavailable in a
  non-git snapshot). The restart took 31.3 s. These are properties of the snapshot, not of this slice.

## Cause (verified at the REPL, default JVM)

Seon's `q` spent all of its extra cost in `decode-query-result`. For the file-reference query:
`d/q-with-evidence` took 0.0034 ms / 0.01 MB; encode took 0.02 ms; `query-find-attributes` took 0.012 ms;
**decode took 5.02 ms / 20.9 MB**; the unwrapped `q` took 5.16 ms / 21.2 MB. Every attribute was `nil`, and
every tuple was still rebuilt through `decode-query-tuple` → `decode-attribute-maps`, which returned each
scalar unchanged. Contract arming is not the cause.

## Fix (`src/seon/db.clj`)

`native-query-result?` is derived from the parsed query and schema. There is no hand list. It is true
when no find element has an EDN-encoded attribute (`query-find-attributes`, i.e. `edn-encoded?`) and
every find element is a variable bound in a data pattern. Such values are stored datom components that
the walk returned unchanged. Pull, aggregate and function-bound elements keep the walk, which
preserves map decoding. `decode-query-result` returns Datahike's own value in that case. Error shapes
are untouched, because the skip sits after every refusal. The regression is
`seon.db-test/a-query-with-nothing-to-decode-returns-datahikes-value`.

## Numbers (ms / MB allocated per call, warm, n = 200)

| query (rows) | before, default JVM | walk → predicate (default JVM, same values) | after, snapshot JVM* |
|---|---|---|---|
| file-reference (19,232) | 5.04 / 21.33 | 4.92 / 20.87 → 0.008 / 0.031 | 0.92 / 0.55 |
| identity `or` (6,859) | 1.85 / 7.77 | 1.67 / 7.29 → 0.009 / 0.034 | 0.51 / 0.57 |
| test symbols coll (2,107) | 0.145 / 0.50 | 0.044 / 0.12 → 0.002 / 0.010 | 0.37 / 0.42 |

\*The snapshot JVM is niced and uncached. Its raw `d/q` is 5–17× slower than default's (0.038 vs 0.0022 ms),
so only its allocation column compares across JVMs. Projected on default: about 0.12 / 0.1 / 0.10 ms. The
remaining ~0.4 MB per call is fixed `q` overhead (parse, alignment, declarations, evidence), which is
outside this slice. Results are `identical?` to `d/q` for the three native queries. Pull-in-find and
`(count …)` still walk and stay `=` to `d/q`.

## Size and commits

`src/seon/db.clj` +22 / −3, `test/seon/db_test.clj` +13. The issue note and this landing note are new.
Commit: see `git log -- src/seon/db.clj` (the "fix-db-decode" message).
