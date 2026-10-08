---
title: "Card F4c: The step has a bound and continues"
type: reference
status: active
created: 2026-10-08
---

# F4c: The step has a bound and continues

**Q-C answered (owner 2026-10-08 late): the dial defaults to 10 s.** Depends on F4b. Group B. Model: Opus.

## Files

- `src/seon/turn.clj`
- `resources/seon/schemas/seon.config.test.edn`
- `test/seon/cluster/turn_test.clj`

## Decided

- Dial: `:seon.config.test/step-time-limit-ms [:int {:min 1 :seon.config/dial true}]` in `seon.config.test.edn`
  (beside `:auto-check-cases`), declared with `:seon.config/default 10000` in the schema, so no `config/default.edn`
  line is needed (`seon.config/registration-defaults`, `src/seon/config.clj:165-183`; that file is held by dials-16gb). Read once per step with `seon.config/effective` the way `seon.test/run` reads its
  bound (`test.clj:2283-2287`).
- The bound is enforced by the runner, not by a second timer: `run-proofs` passes
  `:seon.test/check-time-limit-ms` = the dial; `run-reaching` passes the dial minus the proofs' measured elapsed
  (`:seon.test/elapsed-ms` of the proof run); at or below zero, the reaching request is not started. The runner
  already lets a started member run to its exit and reports the rest as `:seon.test/pending` /
  `:seon.test/unfinished` (`seon.test/run` docstring, `test.clj:2236`; run-result schema `seon.test.edn:337-356`).
- The queue is the proc's state: `step` puts `{:seon.turn.loop/queued (into pending unfinished) :seon.source/commit-id
  (:seon.source/commit-id run)}` under `:seon.turn.loop/queue` in the proc state it returns (Flow proc state, never an
  atom). The next step, before its own requests, runs the queued symbols as `:seon.test/identities` — only when its
  own pass did not trigger a step (Q-B); when it did, the queue is dropped: the new requests already cover them on the
  newer program (the last version wins).
- Schema: `:queue [:map [:seon.turn.loop/queued [:vector :seon.test/sym]] [:seon.source/commit-id :seon.source/commit-id]]`
  in `seon.turn.loop.edn`; claim it.

## Proofs

`seon.cluster.turn-test/tests-past-the-step-bound-continue-on-the-next-pass`: set the cluster dial to 1 ms with
`test-support/apply-config!` (`test/seon/test_support.clj`, as `seon.config-test` uses it at `config_test.clj:86`); a change reaching two tests; assert the first reply's summary lists the
second as pending and the next pass's reply shows it run.

`seon.cluster.turn-test/a-newer-change-supersedes-queued-tests`: as above, then a second changing pass; assert the
queued symbol is not run against the older commit (its latest result's `:seon.source/commit-id`, read with
`latest-results`, is the newer one).

`--test seon.cluster.turn-test seon.turn-test seon.config-test`.

## Cost

The next pass waits no longer than the dial.

## Commit

`bin/lane commit f4c -m "F4c: the test step is bounded and continues on the next pass" --test seon.cluster.turn-test seon.turn-test seon.config-test src/seon/turn.clj resources/seon/schemas/seon.config.test.edn resources/seon/schemas/seon.turn.loop.edn config/default.edn test/seon/cluster/turn_test.clj`
