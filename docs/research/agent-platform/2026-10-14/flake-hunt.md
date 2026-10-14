---
title: Flake hunt for two suspects from 1e278c103
type: research
---

# Flake hunt: two suspects from 1e278c103

Verdicts first. Commit under test: HEAD `1e278c103` (candidate built from the git archive by `bin/seon check`).
Per-member timings are `:seon.test.member/run-ms` read from the `SEON_TEST_RESULT_FILE` EDN each run wrote.
Logs and EDN results are in `tmp/flake-hunt/` (gitignored); check diagnostics are under `data/clusters/default/logs/`.

1. `seon.fn-test/every-indexed-outward-path-crosses-its-total-render-projection` (bound 5 s): **not a flake and not reproduced at 5,168 ms.** It is a near-bound cost when run alone: 3.9 to 4.8 s in 5 of 5 runs, 95% of the bound at worst. Inside `seon.fn-test` it ran faster (3.4 to 3.9 s). Treat the 5,168 ms observation as unexplained and keep watching the trend.
2. `seon.test/check-run-refused-once-with-active-connections-on-store-deletion`: **not reproduced.** 0 of 8 runs printed "Cannot delete a database with active connections". One of 8 failed with a different infrastructure flake (below). That flake is intermittent and needs its own task.

## Suspect 1: render projection test, per run

| Run | Command | run-ms (member) | Outcome | Log |
|---|---|---|---|---|
| alone-1 | `--test seon.fn-test/every-indexed-outward-path-crosses-its-total-render-projection` | 3951 | pass 3/3 | tmp/flake-hunt/fn-alone-1.log |
| alone-2 | same | 3938 | pass 3/3 | tmp/flake-hunt/fn-alone-2.log |
| alone-3 | same | 4344 | pass 3/3 | tmp/flake-hunt/fn-alone-3.log |
| alone-4 | same | 4756 | pass 3/3 | tmp/flake-hunt/fn-alone-4.log |
| alone-5 | same | 4691 | pass 3/3 | tmp/flake-hunt/fn-alone-5.log |
| batch-1 | `--test seon.fn-test` (84 members) | 3751 | test passed; run red on other members | tmp/flake-hunt/fn-batch-1.log |
| batch-2 | same | 3403 | test passed; run red on other members | tmp/flake-hunt/fn-batch-2.log |
| batch-3 | same | 3916 | test passed; run red on other members | tmp/flake-hunt/fn-batch-3.log |

Notes:
- Alone runs: mean 4.34 s, range 3.94 to 4.76 s, against a 5 s bound. The trend is consistently close to the bound, which points to real cost (the test's work), not an intermittent stall. Each run had two other throwaway JVMs in the queue or held, so some of the spread may be host load.
- The batch that ran was 84 members, not the 202-test batch in which 5,168 ms was seen. Our namespace batch reproduced no over-bound time. A 202-test batch (or a whole-suite `--policy all`) would be needed to test that exact condition, and that is heavier than this lane should run.
- The batch runs were red on two other tests in every run (deterministic, not flakes):
  - `seon.fn-test/publication-is-first-party-only`: expected `["src" "test"]`, actual `["src" "test" "script"]`.
  - `seon.fn-test/sci-evaluation-has-one-first-party-owning-namespace`: expected `#{"seon.sci.eval"}`, actual `#{"seon.dev.harness-costs" "seon.turn.batch" "seon.sci.eval"}`.
  These are real reds on HEAD and belong to their owners, not to this hunt.

## Suspect 2: active-connections refusal, per run

Command for all 8: `bin/seon check --test seon.context-test/a-plain-eval-renders-each-must-read-from-its-world`.

| Run | run-ms (member) | Outcome | Log |
|---|---|---|---|
| 1 | 999 | pass 2/2 | tmp/flake-hunt/small-1.log |
| 2 | 1108 | pass 2/2 | tmp/flake-hunt/small-2.log |
| 3 | 1014 | pass 2/2 | tmp/flake-hunt/small-3.log |
| 4 | 1077 | pass 2/2 | tmp/flake-hunt/small-4.log |
| 5 | 1005 | pass 2/2 | tmp/flake-hunt/small-5.log |
| 6 | 1158 | pass 2/2 | tmp/flake-hunt/small-6.log |
| 7 | 1227 | pass 2/2 | tmp/flake-hunt/small-7.log |
| 8 | n/a | **failed before any test ran** | tmp/flake-hunt/small-8.log, diagnostic `data/clusters/default/logs/operator-check-20261014T100546.167.edn` |

Run 8 failure: `clojure -Spath -M:dev:test` (classpath resolution, before any JVM test) threw `java.lang.ClassCastException: HashMap$Node cannot be cast to HashMap$TreeNode` inside Maven's `DefaultModelValidator.validateId`. This is a concurrent-mutation race in the shared Maven/Clojure dependency resolution, not a database error. The check refused with "Command failed." Its identical retry was not run in this hunt, so its pass-on-retry is unverified.

Verdict for suspect 2: the named refusal was not reproduced in 8 of 8 runs. The one failure is a separate intermittent classpath-resolution flake (1 of 8). Suggested task: serialize or isolate `clojure -Spath` across concurrent check JVMs, or cache the resolved classpath per commit. That is an owner decision, not something this lane changed.

## Not done

No source, test or task files were edited. No SCI requests were made on default. Only `bin/seon check` throwaway JVMs ran. Default was not stopped, reset or restarted.
