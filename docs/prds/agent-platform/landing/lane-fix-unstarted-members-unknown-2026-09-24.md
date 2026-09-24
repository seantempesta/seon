---
type: landing
status: landed-unverified-in-part
lane: fix-unstarted-members-unknown
created: 2026-09-24
---

# Lane fix-unstarted-members-unknown — landing (2026-09-24)

Census: docs/research/agent-platform/red-baseline-census-2026-09-24.md (Class 1, the 34 deleted-test reds).
The root ruled on the design for items 1–2 and gave this lane seon.test.run.edn and runner.clj.

## Broken or unproven first

- **`seon.test.one-request-test/an-unexited-member-is-never-started-again-until-its-exit-is-recorded`
  was red in the one namespace run after cb632bd81** (run 6143190cb108).
  - What failed: the second request executed the member instead of holding it as pending.
  - That member has `completed-tx` without `terminated-tx`. My `admit-run` change touches only the
    branch for members with no `completed-tx`, and on reading I found no path from this change to that
    failure.
  - It has no earlier recorded result on default, so I cannot tell whether the red predates the change.
  - The owner stopped test runs to ease load. Root, please run it at the parent (2a543c27a/1e9f1ec9d)
    and at cb632bd81.
- Other reds in that namespace run are foreign:
  - "Worker-global state changed … seon.cluster.prompt/prompt, seon.sci.eval/evaluate": another lane's
    instrumentation drift.
  - "Cannot interpret seon.sci.eval-test/deadlined-in: … clojure.core/future-call": census class 2b,
    lane fix-sci-unresolved-host-names.
- **I broke the one-JVM rule by mistake.** A retry loop ran `clojure -M -e nil` five times, which
  started five short-lived JVMs. None is left running.
- Defect seen, not investigated: in an earlier draft of the regression, a member stopped unfinished
  never had its `terminated-tx` recorded. The draft waited 20 s after its 15 s SCI bound. The watcher's
  `settle!` may be refused as an outcome change; the evidence was lost when the fixture branch was
  unlinked. The final regression does not depend on it.
- Default's `problems` is slower under this load: 600 ms now, against 225 ms before. The two readings
  were taken under different load: default was at about 900 % CPU from other lanes. The only
  per-call cost I added is one pull per candidate run in `admit-run`, plus one attribute in the
  `latest-results` run pull.
- The final regression took 111 s under load, over its declared 60 s. It still reported
  `passed? true`, so the bound check covers only the member body, not the whole request.
- Publication raced other lanes throughout. One foreign blocker held it for several minutes:
  analysis errors on `seon.flow/submit!!` in turn.clj and env_test.clj. One test request failed with
  "Node not found in storage" (address 6ab5924b…). It was transient: the next read and request
  succeeded.

## Landed

1. **Item 3, 2a543c27a.** `problems/failed-tests` (problems.clj:385) counts only symbols that are
   current `:seon.test/sym` rows.
   - Regression: `seon.test.interrupted-request-test/a-deleted-tests-red-member-is-history-not-a-failed-test`.
2. **Items 1–2, cb632bd81.**
   - `seon.test.run.edn` declares `:seon.test.run/stopped` as an enum of `:deadline`,
     `:member-unfinished`, `:recording-refused` and `:platform-red`, and adds it to `:seon.test.run/run`.
     It was published and adopted on default as a keyword attribute of cardinality one.
   - `seon/test.clj` changes:
     - Each place that sets `::stopped` in `run-batch` also names its `::stop-cause`.
     - `release-not-started!` writes only `[:db/add run :seon.test.run/stopped cause]`. Unstarted
       members keep no outcome, so each reads as the S0 unknown, never green, and pending next time.
     - `admit-run`: an unstarted member of a stopped run no longer covers a later request.
   - `runner.clj`: `latest-results` pulls `:seon.test.run/stopped`. The unknown's message reads
     "Never started: request <id> stopped (<cause>) before this test." Nothing else changes.
   - Regression: `seon.test.one-request-test/a-stopped-requests-unstarted-member-is-unknown-and-an-identical-request-reruns-it`.
     - Three agent tests, `a-quick`, `b-runs-until-released` and `c-after`, run under a 10 s request
       bound. B's own bound is 15 s, so the request stops on B unfinished.
     - First request: C is pending with no captured result. C's latest result is the unknown, with no
       counts. The run carries `:member-unfinished`, the message names it, and `verified?` is false.
     - Second request, identical, after a marker lets B finish quickly: C is admitted and rerun, it is
       not pending, and it passes.

## Evidence (default pid 99265, JVM eval, read-only)

| | failed-tests | "Not started" reds | unknown | from deleted symbols |
|---|---:|---:|---:|---:|
| before (lane start) | 658 | 215 | 1 | 34 |
| after item 3 | 625–627 | — | 65 | 0 |
| after cb632bd81 | 571 | 128 (old runs, until rerun) | 135 (all "Never started … (cause)") | 0 |

- Recorded stops since the change: `:deadline` 5, `:member-unfinished` 2, `:recording-refused` 1.
- Live proof: the save gate's own request b56d5a17d8d2 stopped on `:deadline`. Its 48 unstarted
  members read as `:seon.test/result-unknown` naming run b56d5a17d8d2, and `verified?` is false for them.
- Test runs:
  - `bin/test-check default --test seon.test.one-request-test/a-stopped-requests-… --include-long`:
    run 8a5299f58fc1, 14 pass, 0 fail.
  - `bin/test-check default --ns seon.test.interrupted-request-test --include-long`: run 3c242f023980,
    37 pass, 0 fail, 4 executed.
  - `--test …a-deleted-tests-red-member-is-history-not-a-failed-test`: run 68d564aa7539, 5 pass.

## Still to run (root, in a quiet window)

```
bin/test-check default --test seon.test.one-request-test/an-unexited-member-is-never-started-again-until-its-exit-is-recorded --include-long
bin/test-check default --ns seon.test.one-request-test --include-long
bin/test-check default --ns seon.test-test
bin/test-check default --ns seon.test.accretion-test
```

## Size

Source and schema: +34 / −31 (test.clj +18/−27, runner +5/−2, problems +5/−2, schema +2).
Tests: +93.
Commits: 2a543c27a, 1e9f1ec9d (first note), cb632bd81, and this note.
No branches or background shells were left. No reset is needed.
