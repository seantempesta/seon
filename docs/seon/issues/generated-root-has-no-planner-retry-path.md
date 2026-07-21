---
type: issue
status: open
severity: friction
tags: [issue, agent, flow]
---

# A failed planner run leaves a generated root open with no re-drive

## Problem

`seon.ai/generate-code!` commits the generated root with its one planning
assignment message and claim. `my.plan/publish-generated-program!` keys the
DAG publication off `run → :seon.agent.run/cause → the root's message`. When
the planner's first run closes `:error` before any program publishes (live
case: a Kimi K3 timeout at the 300 s planning variant cap), nothing re-wakes
the planner: the root-scoped scheduler observes an empty frontier forever,
and a NEW wake message to the planner would open a run whose cause is not
the root message, so its reply would evaluate but never publish the DAG.
The root stays `:open` with its claim set and the caller never receives a
terminal result.

## Evidence

Live gencode-cluster drive 2026-07-21 02:01:59Z
(`logs/clusters/gencode/pod/…log`): root `tn6d6i8ywnek`, planner
`red-pugs-spend`, `OpenAI-compat request timed out / aborted` after 300 s,
`halt turn :error → close run :error`; the root remained `:open`/claimed and
was restored by every later boot (`restored-roots [… "tn6d6i8ywnek"]`)
without any re-drive. An earlier identical strand is root `lg145imfv2ms`
(planner turn killed by the parse-forms instrumentation regression, since
fixed and archived).

## 2026-07-21 live rerun — three strand causes closed, one gap remains

Eight fresh-database gencode drives narrowed the strand to four distinct
causes; three are fixed in place:

1. **Terminal over-fence (fixed).** `generated-terminal-transaction-builder`
   added `::db/expected-db` (whole-database equality) on top of the root
   `:db.fn/cas` status fence, so ANY unrelated concurrent datom (fault
   records, turn capture) failed the terminal with "The database changed
   before commit" and stranded the root `:open` — live roots `p6l5kjtx4ixv`
   and the 2026-07-21 02:xx strands. The CAS in the same allocation
   transaction is the one delivery fence; the expected-db assoc is removed
   and a lost CAS is classified by rereading the committed status. Proven
   live: the previously stranded root closed `:blocked` with terminal
   message `qeaz44dd7x7x`, and every later failed drive delivered its
   `:blocked` terminal without stranding.
2. **Evidence-query node budget (fixed).** The eval-evidence query bounded
   `::db/max-results` by an exact per-row formula, but datahike counts
   result NODES by each row's pulled shape (live: 5 ids → 6 nodes,
   13 → 16, 18 → 45), so real programs failed publication
   ("query-results budget exceeded", roots `gwtqk9t9on4v`,
   `mu9ahqmrx3wj`). The bound is now a flat 4096 fail-closed cap; the
   input is already bounded by the exact ordered eval-id list and the
   result-weight cap.
3. **Failed-eval recording dropped rows (fixed).** `seon.eval` froze
   `::ending-ns` as `(when (::ok? result) @current-ns)`, so every FAILED
   form's recording transaction carried `:seon.eval/ns nil` against the
   registered `:symbol` schema and the whole row was silently lost
   ("Transaction data fails its registered schema :symbol" faults for
   planners `vast-hairs-chew`, `fiery-wings-work`, `social-kings-stand`,
   `witty-toes-ask`, `some-birds-sort`). Per the archived
   `incomplete-eval-row-retires-execution-child` precedent, this pod-side
   contract violation also retires the active execution child — matching
   every "The execution child exited before returning a result" planner
   failure observed while the generated program contained failing forms.
   Failed evals now record with the batch's current namespace;
   require-edge publication stays success-gated.

## Acceptance (remaining gap)

The ORIGINAL strand class is still open: a planner run that closes
`:error` BEFORE any evaluation/publication attempt (the live Kimi K3
300 s timeout — no reply, so `publish-generated-program!` never runs)
re-wakes nothing. The root-scoped scheduler (or terminal owner) must
observe a planner run that closed `:error` with no published namespace
DAG and either re-issue the planning assignment through the existing
message/claim mechanism (bounded retries) or commit the `:blocked`
terminal with the run's error evidence so the caller receives an honest
result. No new registry or second scheduler; the existing root observer
and `commit-generated-terminal!` are the owners.
