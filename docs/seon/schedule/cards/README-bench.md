---
title: "Context bench cards (BENCH-1 … BENCH-11)"
type: reference
status: active
created: 2026-10-08
---

# Context bench (BENCH-1 … BENCH-11)

Design: [competency-spec.md](../../../research/agent-platform/2026-10-08/competency/competency-spec.md) §1-§6;
ledger rows in [agent-loop-ledger.md](../agent-loop-ledger.md) §3.11. Owner (2026-10-08 night): a standardized test
to find which context presentation agents respond best to, graded on turns, errors and success, with tasks written
well. Order puts the first experiment (E1, task wording; spec §5) as early as possible; each card is about 15
minutes. Group K is `test/seon/competency*.clj`, `resources/seon/tasks/seon.competency.edn`; group D is
`src/seon/context.clj`, `src/seon/ref.clj`, `src/seon/ai.clj`.

## Order

1. **BENCH-1 A run's grade from the database.** `seon.competency/grade [database agent-id task]` in
   `test/seon/competency.clj`: green, proof counts, model calls and calls to green, forms, agent and platform errors,
   no-form calls, repeated forms, tokens, wall time. Proof `seon.competency-grade-test/a-fixture-model-run-grades-green`
   (one fixture-provider call turns T1's proof green). **Landed** `bb89f96c8`.
2. **BENCH-2 Core tasks in two wordings (C2 plus T1's twin).** In `seon.competency.edn`: `area-is-contracted-typed`
   (T1's intent with backticked `comp.geo/area` as the target, the contract as a code span, done-when, and must-read
   `#{malli.core/validator}`-style names only where reading them helps) beside the existing prose T1; T2-T4 in both
   wordings (`…-prose`, `…-typed`), each proof tagged with both task symbols. Setups: none for T1, T2, T4; `comp.text/slug`
   on main for T3. Oracles in `seon.competency-oracle-test`: each task red to green through MCP. Check first at the
   REPL that a typed intent's references resolve in the block (`(seon.context/current-state)` on a fixture agent):
   an unresolved reference is a task defect. Proofs `seon.competency-oracle-test/t2-goes-red-to-green`, `…/t3-…`,
   `…/t4-…`. Group K.
3. **BENCH-3 Launch one run, and the table.** `seon.competency/launch! {task variant n}` in `test/seon/competency.clj`
   for root's JVM REPL: for each of `n` fresh agents `bench-<variant-name>-<task-name>-<i>`, the first MCP eval creates
   its branch, the agent's own `my.agent/settings!` sets the variant's dials plus provider and cap (RESPEC R-9's
   workaround, deleted when root's route lands), `seon.task/assign!` at the home, one outside message, wait until the
   call rows close or the cap is spent, `grade`, then `my.agent/retire-branch!`. Answers the grades.
   `seon.competency/table [grades]` folds them per cell (green share, median and range of calls to green, errors and
   wasted calls per run, tokens, cost from `:seon.ai.model/*` prices). `grade` gains `:seon.competency/variant`
   (the agent's overrides). Proof `seon.competency-grade-test/a-launch-grades-each-run` (fixture provider, n 2). Group K.
4. **BENCH-4 E0 and E1 (root, paid, under US$0.10).** On DeepSeek flash: E0 = T1 typed × 5; E1 = T1-T4 × {prose, typed}
   × 5. Commit the table as `docs/research/agent-platform/<date>/competency/bench-E1.md` with run agent ids, HEAD and
   dials. Root decides whether E0-E1 run before C0 (spec §5).
5. **CX-12 Wire formats** (existing card, group D) → **E2** (`:transcript` against `:chat`).
6. **BENCH-6 Reference depth dial.** Schema `resources/seon/schemas/seon.config.context.edn` with
   `:seon.config.context/references` `[:enum :summary :names :source]`, default `:summary` in `config/default.edn`;
   `seon.context/current-state` passes it to `seon.ref/references`: `:names` keeps name and operations, `:source`
   adds the definition's stored source (`:seon.fn/source`). Proof `seon.context-test/each-reference-depth-shows-its-fields`.
   Then C3 (T5-T8, T7 is the depth task) → **E3**. Group D.
7. **BENCH-7 Block placement dial.** `:seon.config.context/block-at` `[:enum :last :first]`; `model-context` answers the
   block as a second system part (`:first`) or after the exchanges (`:last`). With CX-12, `seon.ai/messages` places it.
   Proof `seon.context-test/the-block-sits-where-its-dial-says`. → **E4**.
8. **BENCH-8 Help variant dial.** `:seon.config.context/help` `[:enum :full :short]`; `seon.bootstrap/help-value`
   carries both line sets as data and the dial selects one. Proof `seon.bootstrap-test/short-help-names-only-installed-forms`
   (S1-10's class regression over both sets). → **E5**. Group H.
9. **BENCH-9 History window dial.** `:seon.config.context/history` `[:or [:enum :all] [:int {:min 1}]]`; `model-context`
   keeps the last N exchanges and, when it drops any, one line naming the dropped count and the requery form. Proof
   `seon.context-test/a-history-window-names-what-it-dropped`. Interacts with the provider cache: measured in the
   table's cached tokens. → **E6**.
10. **BENCH-10 Proof presentation dial.** `:seon.config.context/proofs` `[:enum :source :names]`; `seon.context/proofs`
    drops source and failures under `:names`. Proof `seon.context-test/proof-names-hide-the-test-source`. → **E7**.
11. **BENCH-11 Paging columns.** After D1: `grade` adds followed-cut, re-ran and guessed from
    `:seon.cluster.eval/result-reads`; D2's T14 tasks. → **E8**.

Later rows stay in the ledger: T9 after F7, T15 after A1 and F6, conflict tasks after the merge rows.

## Findings recorded while carding

- The edit hook evaluates a changed test file in the lane's SCI branch, where `seon.test-support` is not loaded, so
  every `support/…` form answers "Unable to resolve symbol" beside a correct edit (lane bench, 2026-10-08). The JVM
  check runs it correctly; the hook's reply is noise an agent would act on.
- The first call of a freshly defined `seon.competency/grade` on the lane's branch took 1,485 ms; warm it is 5 ms
  (`seon.context/proofs` 3 ms). The first-call cost is acquisition after the definition, not the grade's work.
