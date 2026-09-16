# The repeated-error face named its signature twice — 2026-09-17

Lane: runner-face. Subject: `seon.test-runner-test/repeated-identical-errors-have-one-whole-face`
(`test/seon/test_runner_test.clj:802`), RED in batch 87, GREEN at batch 79.

## The red

```
FAIL in (repeated-identical-errors-have-one-whole-face) (test_runner_test.clj:802)
all events remain counted while only distinct causes render
expected: (= 2 (occurrences output "  signature:"))
actual: (not (= 2 4))
```

Two distinct causes render, so the ruled count is two signature lines. Four arrived:
each of the two whole faces carried the line twice.

## Cause

`61519c245` ("test runner: a reported throwable is a diagnostic, not an agent
projection") made `report-value` render a reported throwable as plain text by
delegating to `throwable-face` — the function that already carried the error
face's `  signature:` TRAILER:

```clojure
;; 61519c245, src/seon/test/runner.clj
(if (instance? Throwable reported-value)
  (throwable-face options reported-value (throwable-signature reported-value))
  ...)
```

`report-error!` (`src/seon/test/runner.clj:245`) prints that same trailer once per
distinct cause. So the signature line was emitted from two code paths per face:
once inside `actual:` (the reported value) and once as the face's own trailer.

Measured live before the fix (default's JVM, `#'seon.test.runner/report-event!`
driven with the fixture's own repeated refusal, 7 identical events):

```
ERROR in ... 1     signature lines 2
```

with a blank line between them, because `throwable-face`'s `with-out-str` text
ends in a newline. Two causes → 2 faces → 4 signature lines, exactly the red.

The drift detector's post-run schema restore (`c79a157fd`) emits NO signature
line: the only two `"  signature:"` print sites in the tree were
`throwable-face` and `report-error!`, both in `src/seon/test/runner.clj`. The
other four candidate commits since batch 79 do not touch the reporting path.

## Fix

`src/seon/test/runner.clj:112` — split the throwable's diagnostic BODY from the
error face's trailer:

- `throwable-text` — class, complete message, and frames bounded by the
  DECLARED `:seon.print/length`, with no signature line and no trailing newline.
- `throwable-face` — `throwable-text` plus the signature line, kept for the ONE
  caller that has no `report-error!` trailer of its own: a worker task that
  fails outside a test Var (`src/seon/test/runner.clj:1447`).
- `report-value` — uses `throwable-text`.

`61519c245`'s intent is preserved exactly: the reported throwable is still plain
text, still complete, still bounded by the declared print length, never by the
agent's token budget; `seon.test-support-test/a-long-refusal-reaches-the-failure-message-whole`
asserts only that `report-value` carries the complete `ex-message` with no
elision value, which still holds. Frame bytes are unchanged (`println` of the
frame object), so the `    at [ns fn file line]` form in every gate log is the
same.

## In-process numbers (default, pid 30138, adopted commit
`6aaab871-4c0d-57a3-83bc-79c1e128b59f`)

`#'seon.test.runner/report-event!` driven with the fixture's exact events —
seven identical refusals (signature `aaa…`, 64 chars) then one distinct refusal
(`bbb…`) — against the ADOPTED definitions:

| observation | before | after | ruled |
|---|---|---|---|
| `ERROR in` faces | 2 | 2 | 2 |
| `  signature:` lines | 4 | 2 | 2 |
| `:error` report counter | 8 | 8 | 8 |
| `"one repeated refusal"` in output | yes | yes | yes |
| distinct signature in output | yes | yes | yes |
| `"additional failure output elided by bin/test"` in the face | no | no | no |
| `"the same refusal reached the reporter again"` in the stored failure message | 1 | 1 | 1 |

Every assertion the target test makes is satisfied by the adopted definitions.

Three loadable tests that `seon.fn/tests-reaching` names for the changed
functions, run in process on default through
`(seon.test/run (#'seon.test/resolve-test 'sym) (seon.operator/connection "default"))`
after adoption:

| test | pass | fail | error |
|---|---|---|---|
| `seon.test-failure-facts-test/a-repeated-failure-upserts-its-entity` | 6 | 0 | 0 |
| `seon.test-failure-facts-test/a-green-run-retracts-its-failures` | 8 | 0 | 0 |
| `seon.test-failure-facts-test/a-recorded-test-renders-its-sites-and-changed-dependencies` | 11 | 0 | 0 |

(The first attempt at the first of these hit the declared 20 s
`:seon.test/remaining-ms` bound while the publication-keyed fixture base was
still building — the expected first-run-after-adoption cost, green on the
re-run.)

## Verification boundary

`seon.test-runner-test` CANNOT be loaded in default's JVM: it requires
`dev-cache` (`dev_cache.clj`, repo root), which requires
`clojure.tools.build.api`. That jar is an `:extra-deps` entry of the `:test`
alias, and `seon.test`'s `test-loader` (`src/seon/test.clj:106`) adds only the
alias's `:extra-paths` to its `DynamicClassLoader` — never its extra deps. Both
`(require 'seon.test-runner-test :reload)` and
`(#'seon.test/resolve-test 'seon.test-runner-test/…)` fail with

```
Could not locate clojure/tools/build/api__init.class ... on classpath
```

So the six tests `seon.fn/tests-reaching` names inside that namespace
(`repeated-identical-errors-have-one-whole-face`,
`assertionless-test-is-an-attributed-failure`,
`captures-counts-and-failure-identities-per-test`,
`result-facts-live-on-the-test-row-and-reruns-replace-them`,
`result-recording-is-total-under-concurrent-test-retraction`,
`the-effectful-sink-refuses-the-default-cluster`) were proven here by driving
the changed reporting functions directly with the fixture's own events, not by
`seon.test/run`. The cold gate re-run is the proof of record.

`seon.fn/tests-reaching` on the adopted database reports 34 tests reaching
`report-value` / `throwable-text` / `report-event!`; none is in
`seon.test-support-test`.
