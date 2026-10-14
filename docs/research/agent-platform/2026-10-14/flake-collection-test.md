---
title: Flake hunt — an-eval-request-after-a-collection-reads-no-collected-value
type: research
---

# Flake hunt: `seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value`

Evidence only; no source edits. Taken on HEAD `b40b5881a` (commit `284ec61e5` is the held-value retention design).

## Question

`held-value-retention.md` §6 records the test as flaky: red once in run `e335e49d815e`, green alone in `6ffecfb787d4` and `0e7638709e0e`. The question is whether the red is a flaky fixture or timing, or a real retention failure (a held value read after collection, and which function held it).

## Test identity

- Var: `seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value` (`test/seon/cluster/armed_test.clj`).
- Introduced by `f51240f34` ("An acquisition reads only the identity of the value it acquired before..."). `git log -L` on the test name lists that commit alone, so the test body has not changed since. The commit's fix is the one the test guards: `seon.sci.eval/acquired-database?` used to read the held value's index after a collection, which produced `Node not found in storage.`.
- The test body: boot a cluster on a published root, acquire the head with `(+ 1 2)`, transact 30 declaration writes, run `registry/collect!` with `(Date.)` as the window (zero), invalidate the store's node cache, `System/gc`, then request `(+ 1 2)` again and expect `[3]` with no error.

## Runs (`bin/seon check --at HEAD --test seon.cluster.armed-test/an-eval-request-after-a-collection-reads-no-collected-value`, serial, one check at a time)

| Run | Check total | Test run id | Result | Test run ms | Slot wait ms |
|---|---|---|---|---|---|
| head-1 | 158,171 | `a8cc1bb76e47` | pass (3 assertions) | 73,864 | 48,162 |
| head-2 | 184,405 | `3f594708653e` | pass (3 assertions) | 69,937 | 78,022 |
| head-3 | 190,199 | `d07d2edc9152` | pass (3 assertions) | 66,861 | 86,941 |
| head-4 | 448,789 | `aaea8b3d2b9c` | pass (3 assertions) | 126,867 | 227,073 |

Each run's summary read `executed 1 / reused 0 / pass 3 / fail 0 / error 0 / passed? true`. No failing assertion and no error map were produced in any run, so there is nothing to quote verbatim beyond these summaries. Logs are in the scratchpad (`flake/head-N.log`).

Stopped after four runs by the coordinator's instruction (runs 5 and 6 were cancelled while queued; no check of mine remains running).

## Classification

**Not reproduced at HEAD: 4 of 4 green.** The red in `e335e49d815e` has no stored error map in this hunt, so it cannot be classified as a retention failure from this evidence.

What the evidence does show:

- The run-4 outlier (448 s check, 127 s test body) is contention, not a retention result. Its slot wait was 227 s and its test body ran about 1.8x the other runs' time. The other concurrent check JVMs on this machine (several `bin/seon check` processes belonging to other lanes were running during the hunt) account for it. The test's assertions still passed.
- The one prior red is consistent with the documented alternative cause in `batch-verification.md` and `storage-nodes-missing.md`: a `Node not found in storage` read racing a collection or contention, not a value held across eval requests. The retention failure this test guards would show `Node not found in storage` on every request after the collection, as it did on 2026-10-11 (red `9f66336d8a2d`); a single-request red like `e335e49d815e` is the signature of a race, not a steady hold.
- The test's zero-window collection (`remove-before (Date.)`) makes it sensitive to any concurrent store work, so a flake is expected under load. That is a timing property of the fixture, which points to flaky fixture/timing as the likelier class for the one red.

Classification: flaky fixture/timing (contention-sensitive), with the real-retention hypothesis not excluded by this evidence. Settling it needs the stored error map from `e335e49d815e`, or a red captured under load with its reply. The doc's suggested correction (keep the reply's error map in the test, `seon.test-support/mcp-replies`) would make the next red name its reader.

## Follow-up

- Keep the error map from any future red (the correction in `held-value-retention.md` §6). Without it a red cannot separate the holder from the race.
- Re-run under deliberate load only after the error-map change lands, so the result is attributable.
