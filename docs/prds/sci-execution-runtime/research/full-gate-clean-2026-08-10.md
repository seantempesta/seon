---
type: research
status: active
tags: [research, testing]
---

# Clean complete-suite attribution — 2026-08-10

## Verdict

The complete suite is not green. The quiet-tree run at commit `48eb25ab7`
executed **1,151 tests containing 11,874 assertions: 80 failures and 10
errors**. Wall time was **4,827.02 seconds** (`real`; 80 minutes 27.02 seconds),
with `user 3,975.65` and `sys 491.11` seconds.

Complete output is retained at `tmp/full-gate-2026-08-10b.log`. The failed
test root is `tmp/test-runs/run.OJ80Ze`. The runner named 22 failing vars at
the log tail.

The 90 red outcomes reduce to **17 distinct classes**:

- **9 new classes** required new issue notes;
- **7 already-filed classes** match existing issue notes, including resolved
  notes whose invariant has recurred; and
- **1 class is attributable to today's `3f382c83a` render-profile activation**.

These are exclusive attribution buckets. The issue note for the today-commit
class is new, but it is counted under “today's commits,” not again under “new.”
No red class is attributable to print-face commit `977f3a033`.

## Per-class attribution

| # | Failures | Errors | Class | Attribution and evidence | Suspected owner |
|---:|---:|---:|---|---|---|
| 1 | 0 | 1 | Background binary settlement publishes no required event | **NEW** — [Publish terminal evidence for every background binary result](../../../seon/issues/background-binary-settlement-does-not-publish-required-event.md). `background-binary-results-remain-exact-across-the-inline-threshold` times out at `background_blob_test.clj:148`; log lines 790–832. | `seon.effect` background settlement and blob receipt commit/listener |
| 2 | 0 | 1 | First injected core fault is not observed at the durable fact/message boundary | **NEW** — [Make the first injected core fault observable at the fault committer](../../../seon/issues/fault-committer-misses-the-first-injected-fault.md). The panic line prints, then `armed_test.clj:403` times out; lines 986–1038. This is assigned to the current `fault-facts` owner, without conflating it with that lane's recurrence/overflow classes. | `fault-facts`: fault committer plus notification transaction |
| 3 | 1 | 0 | A live contracted definition is absent from the co-hosted cluster context | **ALREADY FILED** — [Isolate session deltas from other runs' context mutations](../../../seon/issues/shared-context-session-delta-crosses-run-attribution.md). `two-clusters-in-one-jvm-own-distinct-live-program-contexts` cannot resolve `my.agents.agent-a/shared-live`; lines 1054–1058. | per-run/per-cluster SCI context owner |
| 4 | 1 | 2 | Publication/boot fixtures lag activation lookup-ref prerequisites | **NEW** — [Keep source-publication fixtures complete under activation lookup refs](../../../seon/issues/activation-closure-fixtures-lag-lookup-ref-prerequisites.md). One incremental publication lookup-ref error, one partial-cluster assertion that assumes executable-only missing facts, and one blank activation derivation; lines 1101–1160 and 4038–4080. This is recurrence at the contract introduced by [New cluster boot fails on a stale published source](../../../seon/issues/archive/new-cluster-boot-fails-on-a-stale-published-source.md), but the distinct current class is incomplete fixtures. | activation/source fixture owner and `seon.cluster.source` request boundary |
| 5 | 5 | 0 | Reply/reader refusals do not retain the expected typed semantic result | **ALREADY FILED** — [Settle an unreadable reply as a form the agent can see](../../../seon/issues/an-unreadable-reply-closes-a-run-with-no-forms-and-no-trace.md). Program restart sees `evaluation-failed` instead of `lint-rejected`; the explicit reader policy returns nil forms and generic `unreadable` instead of refused-tag values; lines 1370–1374 and 4435–4449. The active `unreadable-reply` lane owns this boundary. | `unreadable-reply`: `seon.cluster.reply`, reader, and loop settlement |
| 6 | 6 | 0 | Concurrency receipt diagnostic selects successful receipts as failures | **NEW** — [Make the concurrency receipt diagnostic select only failed receipts](../../../seon/issues/concurrency-receipt-diagnostic-classifies-success-as-failure.md). Six scenarios return empty errors and absent optional failure fields at line 270; first evidence at lines 1959–1963. | concurrency-independence harness query |
| 7 | 52 | 0 | Completion messages open unplanned provider-backed follow-up runs | **NEW** — [Keep concurrency plans from opening provider-backed follow-up runs](../../../seon/issues/concurrency-plans-open-unplanned-follow-up-runs.md). Six unanswered-trigger, 45 transcript-set, and one provider-call failure all carry ordinal-5 completion traffic; lines 1965–2306. | system-plan/concurrency harness terminal and trigger facts |
| 8 | 6 | 0 | Refresh does not preserve the exact cache directory for a recorded live JVM | **ALREADY FILED recurrence** — [Long-lived JVM dependency-class loss is repaired by immutable AOT caches](../../../seon/issues/archive/long-lived-jvm-loses-soft-referenced-dynamic-classes.md) owns the exact invariant. The refresh deletes the old path, sees zero live processes, and the child reports `Execution`; lines 2482–2514. | dependency-cache refresh plus exact process-identity census |
| 9 | 4 | 0 | Export fallback reopens an already-connected branch | **NEW** — [Rebuild an export without reopening an already-connected branch](../../../seon/issues/export-fallback-reopens-an-already-connected-branch.md). Clone refusal falls into rebuild and receives `branch :cluster-export-verb already has a connection`; lines 2566–2592. | fresh operator export fallback and database branch custody |
| 10 | 0 | 3 | Reset/init operator composition exceeds the process backstop | **ALREADY FILED / ACTIVE OWNER** — [Force refork can destroy the branch then fail silently](../../../seon/issues/force-refork-can-destroy-the-branch-then-fail-silently-leaving-no-cluster.md) and the measured [suite fat-tail attribution](suite-fat-tail-2026-08-10.md) own this composition boundary. Forced reset, init lifecycle, and source-less reset all trip `fresh_operator_test.clj:568`; lines 2634–2671, 2683–2720, and 2743–2780. `suite-speed-tail` explicitly owns the class. | `suite-speed-tail`: `script/seon/fresh_operator.clj` composition and process helper |
| 11 | 1 | 0 | Readiness observation omits the terminal `ready` phase | **ALREADY FILED recurrence** — [Fresh operator readiness abandoned an eventual child](../../../seon/issues/archive/fresh-operator-readiness-abandoned-eventual-child.md). The instrumentation proof receives phases through `web` but not `ready`; lines 2674–2678. | fresh operator readiness-versus-exit observer |
| 12 | 1 | 0 | Oversight test pins a stale plumbing-proc roster | **NEW** — [Derive the oversight fleet proof from the live proc roster](../../../seon/issues/oversight-fleet-test-pins-a-stale-proc-roster.md). The observed roster accretes `:seon.search/index`; lines 3311–3315. | oversight test and live graph definition |
| 13 | 1 | 0 | Public `settle!` has no complete contract | **NEW** — [Give `seon.cluster.loop/settle!` a complete public contract](../../../seon/issues/settle-is-public-without-a-complete-contract.md). The census reports exactly `[settle!]`; lines 3449–3453. | loop contract owner, coordinated with `unreadable-reply` |
| 14 | 1 | 1 | Active agent render profile drops the session transcript from web packages | **TODAY'S COMMIT `3f382c83a`** — [Keep the session transcript reachable under the active render profile](../../../seon/issues/render-profile-activation-elides-the-session-transcript.md). The message package omits `surface-transcript`, and the thinking-stream proof times out; lines 3712–3726. A 46-test / 384-assertion render gate was green with the print-face worktree before profile activation (`tmp/orchestrator/ui-print-css-stdout.log:27705-27724`), so `977f3a033` is excluded. A literal `3f382c83a^` checkout was not run; attribution is to the recorded pre-activation gate plus the activation diff, not a claimed parent checkout reproduction. | render profile selection and required transcript reachability |
| 15 | 1 | 0 | Nested values never reach their declared render face | **ALREADY FILED** — [Decide whether contract fit selects a producer for a nested value](../../../seon/issues/contract-fit-render-selection-never-reaches-a-nested-value.md). The nested transaction report renders generically and lacks `Committed transaction`; lines 3788–3790. | render function selection for nested nodes |
| 16 | 0 | 1 | Schedule graph test supplies no environment to the production constructor | **NEW** — [Construct the schedule graph test from a real environment-bearing handle](../../../seon/issues/schedule-graph-test-constructs-a-handle-without-an-environment.md). `env/scope` refuses the impossible minimal handle; lines 3962–4002. | schedule/agent graph fixture |
| 17 | 0 | 1 | `index-step` contract contains an unnamed callable | **ALREADY FILED** — [Name `index-step`'s predicate so its contract can be made durable](../../../seon/issues/a-search-contract-predicate-cannot-be-made-durable.md). `canonical-definition` throws at `schema.clj:292`; lines 4475–4478. | `seon.search/index-step` contract declaration |

Accounting check: failures are `1 + 1 + 5 + 6 + 52 + 6 + 4 + 1 + 1 + 1 + 1 + 1 = 80`;
errors are `1 + 1 + 2 + 3 + 1 + 1 + 1 = 10`.

## Most load-bearing red

The activation-closure/source-publication class is the most load-bearing red.
It crosses complete publication, incremental refresh, and partial-cluster boot:
the source database value from which clusters fork cannot be treated as proven
while its canonical fixtures disagree about the lookup-ref prerequisites and
activation owner. The 58 concurrency failures are numerically dominant but are
two already-understood harness defects; they do not invalidate the underlying
receipt and database facts shown in their own output.

## Boundaries and non-attributions

- The run was quiet at `48eb25ab7`; later checkout changes are not evidence
  about its result.
- `suite-speed-tail`, `unreadable-reply`, and `fault-facts` are current owners,
  not assumed fixes. Their classes remain red until a subsequent gate proves
  otherwise.
- The print-face commit `977f3a033` has no attributed red. Its changed worktree
  passed both web tests before `3f382c83a` made agent profile selection live.
- The complete log, not a tail/head pipe, is the evidence authority.
