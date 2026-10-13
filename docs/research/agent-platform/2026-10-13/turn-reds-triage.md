---
title: "Triage of the 33 named reds in commit 7f96b6cba"
status: measured; one check run on HEAD 7f96b6cba, replaces the inferred E rows
basis: `bin/seon check --at HEAD --include-long` of the 11 namespaces, run 637bbb669483, 506 s test time, 9:00 wall; pass 584 / fail 68 / error 19; 32 red vars (the commit message counted 33 slots)
---

# Triage of the reds named in 7f96b6cba (2026-10-13)

## Measured reds on HEAD 7f96b6cba (32 vars)

Classes: B = test asserts a retired assumption (fix the test), C = owner behavior or context gap (fix the owner or file a task),
D = bound/timing, F = fixed in the slices below. Every row is from the run's expected/actual.

| Test | Class | Measured failure |
|---|---|---|
| armed/boot-seeds-the-root-agent-and-starts-the-loop | B+D | expected five root maintenance tasks, actual three: `seon.schedule` declares footprint, rotate-logs, compact only (reap-dead-roots and process-census have 0 refs in src/resources). Also 40.5 s against the 5 s bound. |
| armed/booting-spends-no-model-call | D/C | `run-count` nil (no bootstrap turn observed) and 39.0 s against the 5 s bound; still uses `with-redefs` on `turn/next-agent-work`. |
| armed/an-escaped-throwable-becomes-a-fact-and-a-message | D/C | `await-fact` for the error fact timed out at 5 s; message nil. Boot of the same fixture takes 39-44 s. |
| armed/the-first-cluster-proc-fault-at-resume-becomes-a-fact | D/C | 44.1 s against 5 s; `await-fact` timeout. |
| armed/two-clusters-in-one-jvm-own-distinct-live-program-contexts | D/C | 39.7 s against 5 s; `shared-live` call returned an error instead of 42. |
| evaluate-sources/ordered-evaluation-retains-one-explicit-basis-without-publication | C | `(:seon.turn/refused refused)` is nil, expected `:seon.turn/recorded-content-conflict`: the turn owner no longer refuses the conflicting recorded content (test.clj:202). |
| program-restart/an-agent-definition-survives-restart-and-another-agent-calls-it | C | cold `help` binding expands to `(seon.bootstrap/help-value)`, test expects `(seon.bootstrap/situation)`; then a ClassCastException (PersistentHashMap to String) in `authored-result` for restart-a ordinal 14. |
| turn-test/a-lost-model-call-leaves-a-durable-readable-reason | C | the agent's next prompt (the `seon.context` data block) holds no lost-call reason at all: the block has task, proofs, step, unread, turns only. |
| turn-test/a-model-request-is-help-own-exchanges-and-the-block-last | C | 4 model turns, expected 3; task status `:working` after the green proof, expected `:done` (target "all green completes the task" not on HEAD). |
| turn-test/a-refused-delivery-becomes-a-durable-error-fact | B | `:seon.eval/shown` is now the printed `:seon.message/undeliverable` error map, not the string "There is no agent named ..."; `:seon.message/unknown-recipient` nil. |
| turn-test/a-run-prompts-from-its-opening-database-value | B/C | prompt is the data block, so the `:seon.message/_to` read-form assertions are retired; and "message B" (committed after the run opened) IS in run A's prompt (`:unread` is read at render, not at the opening value). |
| turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes | C | `message-tx` nil, so the send inside `do` left no message. |
| turn-test/accepted-first-party-definition-reaches-an-existing-agent-next-turn | C | peer's model-origin evaluations are empty: `[]`. |
| turn-test/acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles | B | `:seon.sci.eval/installed` is 7, expected 6 (count of installed forms drifted by one). |
| turn-test/import-addition-is-ordinary-data-and-reacquires-exactly | C | a fresh acquisition does not restore the local `String` import (nil). |
| turn-test/import-only-ns-unmap-installs-exactly-after-its-context-commit | C | a fresh acquisition resolves `String` (not nil): the persisted mask is not reinstalled. Same owner as the row above (`seon.sci.eval` acquisition of namespace imports). |
| turn-test/reasoning-starvation-persists-usage-finish-and-the-named-error | C | error fact lacks `:seon.ai/exhausted-finish-reason` (nil). |
| turn-test/refused-terminal-program-transactions-settle-and-do-not-refire | B/C | the second prompt no longer contains the refusal text; the block shows `:seon.context/uncontracted` but not the refusal fact. |
| turn-test/runtime-schema-key-changes-pass-the-one-usage-guarded-decision | C | a form was refused (`:seon.cluster.eval/error` present). |
| turn-test/runtime-schema-registration-commits-the-evaluated-form-and-attribute | C | `(:seon.schema/ns persistent-entity)` has no `:db/id`: `seon.db/pull` refused nil entity id at turn_test.clj:997. |
| concurrency-independence/n-agents-fold-independently-on-one-live-cluster | D | `db<->flow` never published the scenario agent (entry nil), then `pause-proc` on a nil graph; 43.7 s against 5 s. |
| gen.loop/completion-prose-does-not-erase-failed-evaluations | D | 5.45 s against 5 s and the planner terminal census saw 1 form of the expected 7 (observation timeout). |
| render-source/preview-cache-is-memory-only-and-invalidates-on-read-changes | B | `db/pull` of a missing turn answers an invalid-read error map (nil value) where the test expects nil; preview text no longer holds "Before". |
| render-source/source-contracts-execute-in-memory-and-terminal-transcripts-never-reexecute | C | `output-refs` of the authored source is empty (`#{}`), no `:seon.render/source`; evaluations 0 of 2. |
| test-census/no-test-redefines-a-var | backlog | 113 `with-redefs` sites; left red as instructed. |
| turn-backstop/completion-observer-uses-the-admitted-provider-and-evaluation-parts | B -> F | awaited the retired `:seon.agent/fault-channel`; now asserts the failure the one fault route recorded. |
| turn-continue/a-done-agent-opens-no-work-turn | C | 3 model turns, expected 1; `:seon.agent/admission-closed-tx` nil: a task's end no longer closes admission (same owner as the `:done` row above); 35.3 s against 30 s. |
| turn-continue/a-model-defines-through-its-eval-tool | C | same: 2 model calls, admission-closed-tx nil; 34.8 s against 30 s. |
| turn-continue/provider-progress-has-its-own-bound | D/C | event `provider-still-pending-at-eval-limit` not published in 5 s. |
| turn-continue/read-results-arrive-before-done | C | assertion at turn_continue_test.clj:178 (same completion path). |
| turn-loop/a-clean-last-form-without-a-disposition-is-loud-terminal-evidence | B -> F | request lacked `:seon.cluster.eval/ns`, now required by the `evaluation-terminal-data` contract. |
| turn-loop/a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again | B -> F | `settle-batch!` takes a third `program` argument; the test passed two. |

## Roots

1. Task completion: a green proof does not complete the task or close admission on HEAD (turn-test a-model-request..., turn-continue x3).
   Owner `seon.turn`; this is the agent-loop P5 target, not a stale test. Task to file or tag.
2. Boot-based fixtures take about 40 s against a 5 s bound (armed x5, concurrency-independence): one performance owner (boot), not five test edits.
3. SCI acquisition does not restore local imports or masks (turn-test import x2): owner `seon.sci.eval`.
4. The context block no longer carries event reasons (lost call, terminal refusal): owner `seon.context`.
