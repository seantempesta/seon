---
title: "Card RP-15d: Convert the old turn fixtures, group 3, and delete the open family"
type: reference
status: active
created: 2026-10-08
---

# RP-15d Convert the old turn fixtures, group 3, and delete the open family

As RP-15b. Files (8): `test/seon/transact_feedback_test.clj` (`:21`), `test/seon/concurrency_streams_test.clj` (`:108`),
`test/seon/render_source_test.clj` (`:74`), `test/seon/no_provider_test.clj` (`:91`), `test/seon/run6_stall_test.clj`
(`:43`), `test/seon/cluster/evaluate_sources_test.clj` (`:142`), `test/seon/cluster/turn_test.clj` (`:1664`),
`test/seon/turn_work_test.clj` (`:75`, `:150`), plus `test/seon/background_test.clj` (found by
`rg -l 'turn/open-tx' test`, not in the inventory).

Then delete, with their last callers (`rg -n` each over `src test` answers nothing):
`seon.turn/open-tx` (`src/seon/turn.clj:686`), `open-call` (`:374`) if RP-14a's recording no longer calls it (it does:
keep `open-call` as the call row's writer inside the recording; delete only `open-tx`), `close-tx` (`:410`),
`close-call` (`:420`) once `close-folded-call` covers every settlement, `open-for-agent` (`:358`) if unused,
`generated-run-tx` (`:828`) if S4-02b deleted the opening, `system-run-tx` (`:747`), `system-run-if-admitted-call`
(`:767`).

Proof: D: zero calls on the deleted path; each namespace green.

Commit: `bin/lane commit <lane> -m "RP-15d: the last turn fixtures convert; the open/close helpers are deleted" --test seon.transact-feedback-test --test seon.concurrency-streams-test --test seon.render-source-test --test seon.no-provider-test --test seon.run6-stall-test --test seon.cluster.evaluate-sources-test --test seon.cluster.turn-test --test seon.turn-work-test --test seon.background-test src/seon/turn.clj <the nine test files>`

Dependencies: RP-15c. Group B. Reset: none.
