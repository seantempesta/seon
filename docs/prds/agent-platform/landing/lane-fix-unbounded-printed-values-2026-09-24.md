# Lane fix-unbounded-printed-values (2026-09-24)

## Broken first

- The largest sink is NOT in this lane's files: `seon.flow/proc-failed!`
  (`flow.clj:210-219`) and `seon.cluster.store/listener-failed!`
  (`store.clj:610-617`) `prn` whole `Throwable->map` values when a fault cannot
  be stored. That printed ~3.18 GB of the 3.75 GB log (lines 64192-64558,
  pid 68592). Filed: `docs/seon/issues/unstored-panic-prints-whole-throwable-map-to-stderr.md`
  (fix: `render.value/floor`, as `boot.clj:320` already does). Needs flow.clj
  and store.clj owners.
- The pid 70372 death at 18:08Z (line 89843, `Java heap space`) has NO giant
  line in the log (no line over 44 KB between 69118 and 89846). The giant lines
  named in the assignment (64194-67798) belong to pid 68592 (died at 69114).
  The 70372 cause is not proven by the log; the heap dump was not opened.
- Remaining whole-value memory cost in the runner (not a print, kept by the law
  "keep the whole cause" but measured): for a 60 MB message,
  `failure-identity` 4,441 ms and `failure-report` 1,442 ms (unbounded
  `pr-str` of expected/actual via `printable {}`) on default.

## Answers

- Q1: two print sites. (a) `flow.clj:213` `prn` on stderr (above). (b) the
  runner's own reporter, `seon.test.runner/failure-message`, printed an `is`
  `:message` verbatim to `*test-out*` (line 67798: 557,665,187 chars after
  `FAIL in (generated-read-evidence-rejects-turn-activity)`); expected/actual
  and Throwables were already bounded (`report-value`, `value/floor`; the
  "… further ex-data entries" blocks). clojure.test's default reporter is not
  used for :fail/:error (`report-event!`).
- Q2: ex-data carrying the world is correct in memory. The bounded printers
  exist: `seon.render.value/floor` (throwables) and `value/render-ai` via
  `report-value` (ordinary values). The two sinks bypassed them.
- Q3: neither site is on the one-happy-path deletion list: `seon.fault/fault!`
  from the `var-process` wrapper and `seon.test/run` are the kept owners.
- Q4: `seon.sci.eval/shown-result` (`sci/eval.clj:3113-3128`) calls
  `error/prepare-result` with `render/request-profile`, which passes a
  request-carried `:seon.render/profile` through unvalidated
  (`render.clj:129`); the flow evaluation request carried one without
  `:seon.render.profile/id`. Not in owned files; recorded in the issue above.

## Fix

`src/seon/test/runner.clj` `message-text`: a string message within the
profile's token budget (counted as characters, 15,000 for the agent profile)
prints verbatim; longer or non-string messages go through `report-value`
(profile-bounded, elision named). The whole message stays on the captured
failure (`failure-report`), which `prepare-failures!` stages to the blob tier
(62d97cfcc, test `a-huge-assertion-message-is-a-blob-...`).

## Evidence

- Before (default, pid 99265): `failure-message` of a 5,000,000-char message
  returned 5,000,040 chars; after: a 51,000,000-char message returns 24,374
  chars in 2.9 ms (with a thrown ex-data value of 51 MB: 485 chars).
  Short messages unchanged (`"plain\nmessage\nexpected: 1\nactual: 2"`).
- Regression `seon.test.runner-test/a-huge-message-and-cause-print-bounded-text-and-keep-the-whole-message`:
  hook gate run 6ab28a5663dc, 8 executed, 49 pass, 0 fail; `bin/test-check
  default --test ...` passed (reused). First version with a 60 MB message in
  both message and actual exceeded the 5 s bound (6,392 ms, the identity cost
  above); the committed test uses a 51 MB message only.
- Net lines: runner +15/-1, test +33, docs new.

## Limits

Core-fault half of the class regression (a fault whose ex-data holds >50 MB)
is not covered: its sink is `flow.clj`, outside this lane.
