# Lane fix-status-render (2026-09-24)

## Broken (found, not fixed here)

1. **The save gate runs the previous compiled test body.** A red test cannot be
   repaired by saving its file: three saves in a row gated the replaced
   assertion. Filed as
   `docs/seon/issues/save-gate-runs-the-previous-compiled-test-body.md`
   (blocking). The owner is `seon.cluster/save-gate!` / `candidate-gate!`,
   outside this lane.
2. **The render fault's cause was lost.** Root turn 46118 (situation `:call`,
   16:58:34Z) recorded `seon.render/unknown` with refused member
   `:seon.render.history/entries`. Its occurrence data was capped
   (`:seon.error/data-size 25879`, remainder `:over-bound`) and has **no data
   blob**. So the `:seon.render.unknown/reason` (time-limit or refused) and the
   producer are not recoverable. The claimed `:time-limit` reason is unproven.
   This breaks the "keep the whole cause" law, owned by the fault/error writer
   (`seon.error` commit and occurrence evidence), outside this lane. Not filed
   separately yet; root should rank it.
3. **Root's prompt acquisition is over one second.** A live idle probe of
   `seon.cluster.prompt/prompt` for "root" took 5079 ms and returned 66 294
   chars. The profile growths name `seon.schema/canonical-value-string`
   x723 270 (9006 ms cumulative) and `seon.db/with-declarations` x5093, and they
   include concurrent work. The time limit is 30 000 ms, so under load (a
   167 s `seon.test/run` was live) the 16:58 refusal is plausible, but it is
   not reproduced. The owners are `seon.render.web/derive-context!` and
   `seon.render.walk/history`, not this lane's files.
4. `seon.db/transact!` InterruptedException (open fault 57e8b3f1…) comes from
   the *previous* process 69730 at 16:53:16Z, one second before this JVM
   started. That was a transaction interrupted by JVM replacement.

## The premise, refuted

`bin/seon status` does not fail and does not render. It answers in 0.43 s wall
with health `:faulted` and lists two open faults. "The selected renderer did
not return an observation." is the *message of one listed fault* (turn
46118's prompt render), not a status failure. `request! :status` returns
`seon.cluster/mcp-runtime-observation` data directly. No renderer is on its
path, so there was no status producer to fix.

Warm in-JVM timings (default, 17:12Z, jvm eval):

- `request! :status`: 103, 104, 122 ms.
- `mcp-runtime-observation "default"`: 104, 101 ms.
- `readiness`: 39 ms, of which `problems/problems` is 41 ms.
- `profile/summary`: 53 ms.

With the fixture cluster added under concurrent gate load, one run measured
2002 ms.

## Regression

`seon.cluster.boot-test/status-answers-faulted-health-and-lists-the-open-fault`:

- It records one fault through `seon.fault/fault!` (`:record`) on a canonical
  fixture branch.
- It registers the fixture cluster's instance for the request only. The
  instance borrows the live instance's config, advertisement, socket and
  executors, because the armed `:seon.boot/instance` contract of `readiness`
  refuses a bare `{:seon.boot/cluster-connection c}`. That refusal panicked
  through `request-world` (observed).
- It asserts that `request!` `:status` answers with no error, health
  `:faulted`, and the recorded signature among the open faults. The fixture
  branch inherits the parent's open faults; see
  `test-fixture-worlds-inherit-the-live-clusters-errors-and-messages.md`.

Proof: `bin/test-check default --test …` run 8a7b2438cacf, 4 pass, 0 fail,
3578 ms. Earlier, run d5b22cfb54c7 (17 217 ms, under load) passed the
health/no-error assertions. Its one failure was the exact-equality form, which
this body replaces.

## Numbers

- Source lines: 0 (`boot.clj` and `render.clj` unchanged).
- Test lines: +43.
- New issue: one.
- RESET not needed.

## Limits

- The 16:58 render refusal was not reproduced, and its reason is unrecoverable
  (item 2).
- The regression passed on default only after a foreign adoption reloaded the
  file (item 1).
