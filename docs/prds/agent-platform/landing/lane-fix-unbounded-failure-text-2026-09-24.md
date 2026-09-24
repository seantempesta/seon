---
type: landing
lane: fix-unbounded-failure-text
created: 2026-09-24
status: three commits; slice 2 refuted (no instrument refusal change); default not yet running them
---

# Lane fix-unbounded-failure-text (2026-09-24)

## Broken first

- **RESET NEEDED.** Default still holds the 105 MB `:seon.test.failure/message` datom
  ([issue](../../../seon/issues/a-hundred-megabyte-failure-message-rides-every-new-entity-commit.md)).
  Default also runs committed archive `ce7384682` with hook publication `:off`, so none of
  these commits is installed there until root restarts it at HEAD.
- **A refusal's cost is still O(value), and the text is bounded.** Building one contract
  refusal prints the whole offending leaf inside `seon.sci.admit/leaf!` before the byte
  bound refuses it. That costs about 1.2 s for a 1 MB argument and 2–5 s for 10 MB. Filed:
  [bounded admission prints a whole leaf before its bound](../../../seon/issues/bounded-admission-prints-a-whole-leaf-before-its-bound.md).
  The owner is `seon.sci.admit`, which is outside this lane.
- **`seon.test-failure-facts-test` is red at HEAD, in a class outside this lane.** Its
  `with-probe` fixture writes namespace and test rows with no
  `:seon.program/definition-digest`, so every member refuses at the write
  ([existing issue](../../../seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md)).
  With the canonical helpers (`support/namespace-row`, `support/program-row`) the write
  succeeds. The next refusal is "The test has no executable admission provenance", and
  one nested `seon.test/run` takes 6.5 s. I reverted my fixture edit, and the regressions
  moved to `seon.test-runner-test`.
- In `seon.test-runner-test` on the scratch root, two members are red:
  `result-facts-live-on-the-test-row-and-reruns-replace-them` (duration 6.2–11.9 s) and
  `result-recording-is-total-under-concurrent-test-retraction` ("Test completion has no
  surviving test definition" for `seon.test-runner-failure-fixture/assertionless-example`,
  plus duration). Neither reaches a changed line. Default has no recorded result for
  either, and no parent root was run, so "pre-existing" is not proven.
- The scratch-root restart after `init --changed` refused with "Changed paths lie outside
  the JVM's source directory" (`reference-code/datahike`, a symlink into the shared
  checkout). A fresh root was needed. Evidence is in `tmp/fix-unbounded-start2.log`.

## Slices

1. **`abc4591a2` runner: store each testing label as its printed string.** At
   `src/seon/test/runner.clj:195`, labels print by `str`, as
   `clojure.test/testing-contexts-str` does (`reference-code/clojure/src/clj/clojure/test.clj:307`).
   Regression: `seon.test-runner-test/a-value-testing-label-is-captured-as-its-printed-string`.
   A `[:seon.fn/sym a/b]` label is captured as `[[0 "[:seon.fn/sym a/b]"]]`, and `run-var!`
   does not refuse.
2. **Refuted: no change to `seon.instrument`.** A refusal's ex-message is already bounded.
   `error/scalar-text` prints only self-bounded scalars, and a refusal over a 10 MB vector
   had a 162-character message. Its explanations already carry the path, the expected-shape
   fingerprint and a byte-bounded `project-observation`. The ex-data keeps the
   *identical* offending object (`identical?` true). That is a reference, not a copy, and
   the `violation` docstring says so ("Retain the actual offending values; the error render
   pair owns projection"). The 39,004,102-character `pr-str` of that ex-data came from the
   printer, `seon.test/throwable-text`. Slice 3 fixes that printer. Replacing the ex-data
   value would contradict the error-render design and `error/fit-fact-payload`, which bounds
   the stored fault.
3. **`daa833e32` test: an execution failure's stored text is the value renderer's bounded
   floor.** `seon.test/throwable-text` is deleted. The execution-failure text
   (`src/seon/test.clj:192`), the member-cleanup text and the runner's interrupted-request
   text (`record-interrupted!`) now use `seon.render.value/floor`. That is the existing
   projection-free bounded Throwable renderer: 8 links, 12 ex-data entries of 400 characters
   each, 8,192 characters total. Regression:
   `seon.test-runner-test/a-huge-execution-failure-becomes-bounded-text`. A fixture's
   contract refusal over a 1 MB argument escapes the body. The resulting failure message is
   1,739 characters, names the refusal, and stays under 10,000.
4. **`30b570d91` instrument: `instrumented` reads each namespace's mappings without copying
   them.** It reduces over `Namespace.getMappings` with the `ns-interns` filter (`.ns` is
   the namespace, `reference-code/clojure/src/clj/clojure/core.clj:4237`), and reads the
   wrapper from the Var root.

**Next slice, not done:** the provenance-revision fix for `program-written-since?`
([issue](../../../seon/issues/the-admission-program-write-check-scans-program-attribute-history.md)).
It needs a declared provenance key, the `seon.test` caller and the runner, about 20 lines.

## Evidence (before / after)

| probe | before | after |
|---|---|---|
| `pr-str` of a 10 MB refusal's ex-data (what `throwable-text` stored), default | 39,004,102 chars | `floor`: 1,842 chars, 2.6 ms |
| stored failure text, 1 MB refusal via `bounded-result` (scratch) | unbounded (default's datom: 105,624,664 chars) | 1,739 chars; 1,160–1,422 ms, all in the admission (issue above) |
| `instrumented`, default (609 ns, 484,943 mappings), 8 runs | 13.8–15.2 ms / 77.98 MB (unarmed copy); installed 13.3–14.3 ms / 78.02 MB | 3.2–4.4 ms / 0.17 MB (unarmed copy); equal set of 1,833 Vars |
| `instrumented`, scratch root (load average 14) | 51–178 ms / 74.6 MB | installed 37–70 ms / 0.2 MB; equal set of 1,843 |

Tests ran on the scratch root `tmp/fix-unbounded-root`, source
`tmp/fix-unbounded-src` = `git archive HEAD` (`7b1d44c07`) plus my five files, with
`reference-code` and `target` linked. The fresh boot took 251 s (ready). That is over ten
seconds, in the from-zero boot class, and the dependency cache missed with `:pins-unavailable`.
- `--ns seon.test-runner-test`: run `12aaa571d163`, 8 executed. The label regression was
  green. The huge regression passed its assertions but ran 5.3 s at 10 MB, so it was cut
  to 1 MB. The rerun `f0ec228dadf4` was green, and reuse run `852055563abc` confirmed both
  at 7 passes. The two foreign reds are listed above.
- `--test seon.instrument-test/…`: `applying-twice-is-applying-once` (`2a7c7d23a49a`, 3
  pass), `one-uncompilable-contract-refuses-alone-and-the-rest-arm` (`e59a2c37fb9f`, 8
  pass), `the-selection-is-declared-vars-with-schemas-and-nothing-else` (`b0356356cc1c`,
  16 pass).
- `seon.test.interrupted-request-test/a-thrown-request-records-its-unfinished-members-as-failed-obligations`
  (declared long): `6886d24cec6e`, 8 pass, 28.8 s. It asserts that the cause chain still
  names both messages and the ex-data entry.
- `--ns seon.instrument-test` whole: `1658c06cd1a1`. Only 10 members started before the
  check-time bound ran out. Five were red, and none of them selects the changed function.
  This is not proof in either direction.

Net source lines: +28 −34 (instrument +18 −8, test +3 −17, runner +7 −9). Tests: +44.

## Verification limits

No parent root was run, so the foreign reds are unproven as pre-existing. The stored datom
itself was not observed through a full `seon.test/run` recording, because the synthetic
fixture route is broken (above). The regression asserts the text that `bounded-result`
hands to the writer. The runner stores that text unchanged as `:seon.test.failure/message`.
I did not measure default after the change, because default cannot load these commits.
Scratch namespace `lane.unbounded` was removed from default. The scratch roots were stopped
and deleted. Logs are kept in `tmp/fix-unbounded-*.log`.
