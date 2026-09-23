---
type: landing
status: landed
created: 2026-09-23
tags: [agent-platform, turn, stability, nil-trigger, backstop]
---

# Lane turn-stability: nil trigger and the 630 s completion fault

Parent `7722d6265`. Default (pid 58161) was read-only throughout; every probe on it
ran in the throwaway JVM namespace `tmp.turn-stability-probe` with `read_only`.

## Fault 1: `settle-batch!` refused `[0 :seon.message/trigger]` nil

- Producer: `seon.turn/resume-turn` built each settle request with
  `:seon.message/trigger trigger`, where `trigger` is
  `(seon.cluster.message/trigger db run-id)`, which answers nil for a run no
  message caused. The declared request (`resources/seon/schemas/seon.turn.loop.edn:50`)
  has the key optional, so a nil value is refused. The consumer was right.
- Stored facts on default: the runs that refused (`958adc16c4b1` on the previous pid,
  `355fab5ff381` on pid 58161) are the fourth run of each boot, with situation
  `:call` and no `:seon.turn/trigger`. The trigger is absent because it should be.
- Fix: the request leaves the key out when there is no trigger:
  `trigger (assoc :seon.message/trigger trigger)` in the existing `cond->`.
  There is no guard at the consumer.
- Regression: `seon.turn-test/a-run-no-message-caused-settles-its-forms`. It resumes a
  run with no trigger and the one form `(+ 1 2)`, then asserts that the outcome is
  `:closed`/`:released` and the shown text is `"3"`.
  - Red on the parent (run `fb4b4968d11f`). The error is the same one default logged:
    `seon.turn/settle-batch! refused requests at [0 :seon.message/trigger]: expected a string, got nil`.
  - Green on this commit: run `f285ac509384` passed both assertions and stayed within
    the bound. Later runs (`1ea430d1dc96`, `27a093a1a6c3`) also passed both
    assertions but went over the 5 s body bound under load average 15. The cost
    is in the resume path, not the test (see "Out of scope").

## Fault 2: "did not publish evaluation completion within 630000 ms"

- There is no missing evaluation event. The 630 s fault is the armed backstop of a
  pass that already escaped with fault 1. Clocks from stored error facts:
  - settle refusal at 01:20:59Z, then backstop at 01:31:28Z (629 s later);
  - settle refusal at 02:20:41Z, then backstop at 02:31:11Z (630 s later).

  This lane predicted the second pair from the first before it fired.
- Mechanism: `step` → `arm-turn-completion-backstop!`. Each form's
  `await-turn-part!` publishes the part `:seon.sci.eval/evaluation` with
  `timeout-ms + work-ms`. Only a pass that sets `succeeded?` sends `:cancel`. An
  escaped pass leaves the observer armed on purpose (docstring: "so quiescence cannot
  hide the failed turn"). So `offer-turn-backstop-fault!` fires with the last admitted
  part as the "expected" event. The event that never arrives is the backstop's
  `:seon.agent/cancel`, and an escaped pass never sends it.
- Thread dump of default (ThreadMXBean, all threads): no thread had `seon.turn`,
  `seon.sci` or `seon.ai` frames. Root was not blocked. Its turn proc pings `reply`
  with buffer count 0. Run `355fab5ff381` stays open, and each later wake resumes it
  and hits fault 1 again: the settle signature has a second occurrence at 02:30:04Z.
  The fault-1 fix removes that loop.
- Not changed here: with the cause fixed, an escaped pass still produces a second,
  mislabelled fault ~630 s later. That is the escaped-pass backstop's design and
  belongs to hangs option B. It is reported, not rebuilt.

## Paid provider calls

No loop. There were 4 `:seon.ai.attempt` rows on 2026-09-23 (01:09:56, 01:10:21,
02:19:56, 02:20:27), two per boot, and the count was unchanged after the 02:30 re-resume.
The re-resume re-evaluates stored source and makes no provider call.

## Proof

- Scratch source `tmp/turn-stability/src` is a `git archive 7722d6265` with this lane's two
  files overlaid. It has `dependency-pins.txt` from the index, and
  `reference-code` and `target/dev-dependency-classes` symlinked to the checkout,
  `.cpcache` copied. Root `tmp/turn-stability/root`, cluster `turnstab`.
- Parent vs own through the real publication path: `bin/seon --root R init --dev
  turnstab --changed src/seon/turn.clj` with HEAD's file, then with this lane's.
  Both exited 0 (reload plus arming).
- Hot path, the same probe on parent and own: a scratch-only test
  `probe-triggered-resume-timing` (the regression plus a trigger message, so the
  parent can settle; it is not committed), four runs each, profiled with
  `seon.profile/begin`/`explain`:

| ms | parent 7722d6265 | own |
|---|---|---|
| `seon.turn/resume-turn` | 5472, 5115, 4916, 4821 | 5112, 4557, 4879, 5583 |
| `seon.turn/settle-batch!` | 823, 814, 677, 880 | 732, 673, 777, 848 |

  The two are the same within noise (load average 13–15). The change is one `cond->`
  clause on a request map.
- No contract was added or touched.

## TIMINGS (over 1 s)

| operation | wall ms | justification |
|---|---|---|
| scratch from-zero `start` | 120,570 (ready 90,077) | **>10 s defect**: from-zero publication of the whole program with a dependency-class cache miss (`:no-matching-cache`). Class `docs/seon/issues/from-zero-boot-takes-minutes.md` |
| `init --dev --changed test/seon/turn_test.clj` | 8,950 / 10,270 | one-file publication: `full-source-refresh!` 7.2 s, `analyze-rows` 3.3 s; class `a-three-file-changed-path-publication-takes-a-minute.md` |
| `init --dev --changed src/seon/turn.clj` | 16,560 / 16,620 | **>10 s defect**: `publish!` 9.8 s, `index!` 9.7 s for one 5.6k-line file plus reload; same class |
| `bin/test-check` one test | 9,229–12,950 | publication-free; `seon.test/run` about 7–8 s, the rest prepl and process start |
| resume turn of one `(+ 1 2)` form | 3,555–6,151 | **unjustified, out of scope**: `read-evidence` 2.1 s (x2), `installation-covers-program-change?` 1.3 s, `acquire!` 1.5 s (`derive-base-ctx` 0.86 s), `evaluate` 1.2 s |
| `config/apply!` in the first regression draft | 2,192 | removed: `seed-cluster!` already applies config, and the branch already has the executing cluster |

## Out of scope (reported, not edited)

- The one-form resume turn takes seconds. The owners are `seon.db/read-evidence`,
  `seon.sci.eval/installation-covers-program-change?` and `seon.sci.eval/acquire!`.
  Proposed note: `docs/seon/issues/a-one-form-resume-turn-takes-seconds.md`.
- `seon.turn-test/a-refused-generated-form-records-its-refusal` is red on the parent.
  Its expectation looks for `"generated-read-depends-on-turns"`, but the shown text
  now carries only the message. It also ran 5.8 s against a 5 s bound.
- `seon.cluster.turn-test/a-red-form-routes-to-its-namespace-owner-and-the-fold-continues`
  errors on the parent: its fixture agent `agent-b` lacks the required `:seon.agent/branch`.
- The escaped-pass backstop fires a second fault that names the wrong event (above).

RESET NEEDED: no. Default needs this commit adopted, or a restart onto it, to stop
fault 1. Until then run `355fab5ff381` stays open and refuses on each resume.
