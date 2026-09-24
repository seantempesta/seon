---
type: landing
lane: fix-admit-bound
created: 2026-09-24
status: one commit; not installed on default (hook publication off)
---

# Lane fix-admit-bound (2026-09-24)

Admission already had one bound: `write!` counted the UTF-8 bytes of each
fragment before appending it. A string leaf, though, was printed whole by
`canonical-edn` before its size reached that check. The fix keeps the one check.
It measures a string leaf's printed size from the string itself, refuses it
there when it is over the bound, and prints it only when it fits.

## Broken first

- **Not installed on default.** Default runs committed archive `ce7384682` with
  hook publication `:off`, so this commit is not loaded there. It takes effect
  when root restarts default at HEAD (`start --head`). No reset is needed for
  this lane.
- **The cost is not proportional to the bound. It is a much cheaper
  proportion of the value.** Checking a string needs one scan of its chars
  with no allocation, about 0.4 ns per char. A cost set by the bound alone
  would mean reporting `:seon.sci.admit/bytes` as the bytes reached partway
  through the string. That would break the declared measurement that
  `seon.error` relies on: `data-size` is the source's size (`error.clj:590`),
  and `flow_test.clj:1241` asserts that the bytes are at least the message
  length. Those owners are outside this lane. Choosing the bound-only cost
  would be a semantic change to those keys, and their owner would have to
  make it.
- The only leaf this covers is a string. Other leaves (keywords, symbols,
  object reps, failed-node messages) are still printed before they are
  counted. None of them was observed large.
- The seon.test/run proof is missing (see Verification limits).

## Change

`src/seon/sci/admit.clj`:

- `write!` is split in two. The bound check and the refusal it throws are now
  `refuse-over-bound!`, the single place that checks the bound. `write!` calls
  it before appending.
- `string-leaf!` takes the string node's size from three parts: the empty
  node's bytes (`empty-string-leaf-bytes`), `utf8-length` of the string, and
  `escaped-char-count`. That last part adds one byte for each
  `char-escape-string` char (`reference-code/clojure/src/clj/clojure/core_print.clj:203-221`).
  The check uses that size. Under the bound, the node is emitted through
  `leaf!` exactly as before.
- The refusal's `:seon.error/operation` now names `seon.sci.admit/refuse-over-bound!`
  instead of `write!`. No source, test or doc cites the old name.

Regression: `seon.sci.admit-test/an-over-bound-string-is-refused-without-printing-it`.
It admits a 10 MB string under a 4096-byte bound. It asserts `:over-bound`,
that the bytes reached equal exactly `n` plus the printed empty node, and that
the call takes under 100 ms.

Net lines: source +53 −12 (+41), test +22.

## Evidence

The HEAD file and the changed file were each loaded into a scratch namespace
(`lane.fix-admit-bound.old` and `.new`), with `::` keys rewritten to
`:seon.sci.admit/` and the predicate registration left out. They were loaded in
default's JVM, without redefining any of default's Vars.

- **Identical answers.** Across 48 cases, every answer was equal. The cases
  covered escapes, multi-byte chars, surrogate pairs, a lone surrogate, a nested
  map, an ex-info, and bounds from 10 to 100,000 bytes. 31 of the cases were over
  the bound. Answers included `:seon.sci.admit/edn`, the print node, the reason
  and the bytes.
- **Timing** (ms, two runs each, `admit` on `[s]`):

  | string | bound | before | after |
  |---|---|---|---|
  | 1 KB | 8 MB | 0.23 / 0.08 | 0.19 / 0.16 |
  | 1 MB | 8 MB (stored whole) | 25.8 / 23.2 | 26.7 / 23.6 |
  | 10 MB | 8 MB (over) | 248.5 / 227.5 | 10.8 / 4.3 |
  | 1 KB | 4096 | 0.10 / 0.04 | 0.06 / 0.04 |
  | 1 MB | 4096 (over) | 22.9 / 23.2 | 0.62 / 0.48 |
  | 10 MB | 4096 (over) | 228.4 / 229.9 | 4.17 / 4.28 |

  The under-bound path shows no regression.
- **The installed contract-refusal path, before only.** On default,
  `seon.error/project-observation` with a 10 MB string and a 4096-byte bound
  took 300.9 / 312.7 / 297.6 ms. The profile put 869 of 911 ms in
  `seon.sci.admit/canonical-edn`, which is the work this change removes. There
  is no after number on default, because the change is not installed there.
- **Test file.** The whole `admit_test` file was run against each scratch
  namespace with `clojure.test/run-tests`. Old: 62 pass, 2 fail, 4 error, and
  the new regression is red at 280.55 ms. New: 63 pass, 1 fail, 4 error, and
  the regression is green. The three remaining red tests are the same on both.
  They fail because this harness runs outside `seon.test/run`: two have no
  handed projection and one has no custody. The identity-only-record test did
  not have its predicate registered here.

## Verification limits

- No `seon.test/run` or `bin/test-check` run proves this commit. Default's
  program is archive `ce7384682`, so a run there would test the old code and
  would not see the new member. No scratch root was booted.
- The test file was run by plain `clojure.test`, not armed through the Seon
  runner.
- The scratch namespaces `lane.fix-admit-bound`, `.old`, `.new`, `.old-test`
  and `.new-test` remain in default's JVM. They are inert, and no program rows
  reference them.
