---
title: "Task estimates up front: a proposed TASK-CHAIN amendment"
type: proposal
status: proposed
created: 2026-10-17
---

# Task estimates up front

Owner ruling, 2026-10-17: "I want the estimates for the tasks put up front and it's a failing test if any thing
exceeds them." Lane cost-bounds. Phase A, the ordinary operations, is installed as code
(`seon.cost-estimate-test/estimates`); this note proposes Phase B, estimates on tasks, for root to take to the owner.
It adds a lifecycle attribute, so CLAUDE.md requires amending TASK-CHAIN §1 and §2.4 with a red deftest. Nothing
here is installed.

## What exists

- **Every test member already stores its cost.** COST-REG slice 1: `seon.test` runs each member on its own body
  thread and stores `:seon.test.member/calls` (its own call sink), `:seon.test.member/alloc-bytes` (body-thread
  allocation) and `:seon.test.member/run-ms` on the member. Calls and allocation repeat exactly warm (COST-REG
  measurements); wall ms spreads 5-10x at single-digit ms.
- **Every reply already reports its request cost.** `seon.batch/request-cost` (bound under `:seon.repl/cost-result`):
  wall ms, attributed allocation, armed calls, reads, transactions and stages.
- **One done rule.** `seon.cluster.source/done-refusals` judges every task over the recorded results in
  `:seon.source/judged`; it runs no test.
- **The operation table.** `seon.cost-estimate-test/estimates`: one row per ordinary operation with its estimate
  (MB, calls, reads, ms) and a one-line derivation; `ordinary-operations-cost-no-more-than-their-estimates` is red for
  every overrun, and `no-ordinary-operation-grows-past-its-accepted-overrun` runs on every src commit (bin/lane),
  accepting an overrun only while its task is open and its measure stays within 1.5x of what was recorded.

So the measurement exists at both grains a task can be judged at (each proof member, each request). What is missing
is an estimate the task carries and a refusal that reads it.

## Options, priced (simplest viable first)

### Option 1: a task names a row of the operation table

The task's plan references a row (`seon.cost-estimate-test/estimates`, `:op`) and its proofs include
`seon.cost-estimate-test/ordinary-operations-cost-no-more-than-their-estimates`. No new attribute.

- Guarantee: a task whose work is one of the ordinary operations cannot be done while that operation is over its
  estimate; the five cost tasks filed today already work this way.
- Cost: zero code; the table grows by one row per new operation class.
- Sacrifice: it covers only operations in the table, not the cost of a task's own new function. A task that makes
  some other function slow is caught only if an ordinary operation reaches it. The table test proves all rows at
  once, so one task's proof stays red until every row is fixed (each row needs its own deftest to be a per-task proof).

### Option 2 (recommended): `:seon.task/estimate`, judged on the stored member costs by the done rule

A new owned content member on the task:

```clojure
:seon.task/estimate {:seon.task.estimate/alloc-mb 24.0   ; per proof or reaching member, body thread
                     :seon.task.estimate/calls 4000     ; per member, its own call sink
                     :seon.task.estimate/ms 100.0        ; per member, judged at the wall slack
                     :seon.task.estimate/why "One form analyzed and one test run: proportional to 1 form."}
```

- Written up front by whoever writes the task (root or a Design step), as plain data with its derivation; owned (never
  flows to the next task, like `proofs`). Absent means the cluster's default estimate, a config dial
  (`:seon.config.task/estimate`, shipped from the table's `:redefine-one-form` row), so every task has one.
- `done-refusals` gains one clause: each executed member of `:seon.source/members` (the task's proofs and the tests
  reaching its change, the set it already judges) whose stored `:seon.test.member/alloc-bytes` or
  `:seon.test.member/calls` exceeds the estimate, or whose `:seon.test.member/run-ms` exceeds the wall slack times
  it, is a refusal of one new type, `:seon.source/over-estimate`, carrying the member, the measure, observed and
  estimate as data. It runs no test: the numbers are already on the members it reads.
- The merge judges the same `done-refusals` over the same judged input, so an over-estimate change never reaches
  main; the agent sees the refusal in its block beside the red proofs and fixes the code (or `my.task/send-back!`s
  with a counterexample when the estimate is wrong). Only root changes an estimate, by editing the held task and
  releasing it (`my.root/run!`), the same as any other task change: no acceptance attribute.
- Red deftest for the amendment: a new row in `seon.cluster.source-test/done-refusals-judges-every-task-alike`, "a
  green proof whose stored member allocates past the task's estimate is refused with `:seon.source/over-estimate`",
  red until the clause exists. Plus the writer's schema row: `seon.task/create-all!` admits `:seon.task/estimate` and
  refuses one without `:seon.task.estimate/why`.
- Cost: one schema (four keys), one config dial, one refusal clause (a fold over members already in hand), the task
  writer's admission, and §1/§2.4 of TASK-CHAIN amended. About 60 lines plus the table row. Per judgement: zero
  reads beyond the members `done-refusals` already holds.
- Sacrifice: members measure a test body, not the agent's whole request; a request-level overrun (the step, the
  writer) shows only through Phase A's table. Allocation counts the body thread only (work handed to virtual threads
  is missed: COST-REG open question 2).

### Option 3: request-level estimates on the attempt

Each eval request of the attempt is judged against an estimate (the operation table's row for its kind), and the
attempt is refused if any request exceeded it. Needs a classifier from request to operation kind (a defn, a def, a
check), a stored per-request cost on the branch, and a new clause over the attempt's requests.

- Guarantee: the widest; catches the 1.17 GB `defn` of today directly.
- Cost: a request-kind classifier (a heuristic, which CLAUDE.md "Curate with the proof that already exists" warns
  against), a stored per-request cost entity per attempt, a new judged input; several hundred lines.
- Sacrifice: blames the agent for platform cost it did not cause (today every row is over without any agent change);
  needs the ratchet scheme of Phase A on every task. Not recommended until Phase A's rows are within estimate.

## Recommendation

Option 2, with Option 1 kept for the operation classes in the table. It reuses the cost every member already stores
and the one done rule, adds no runner or classifier, and fails a task the moment its proofs or the tests reaching its
change exceed what the task said up front. Phase A's ratchet keeps the platform's own rows from growing meanwhile.

## Questions for the owner

1. Default estimate when a task gives none: the dial shipped from the table's `:redefine-one-form` row (24 MB, 4,000
   calls, 100 ms per member), or refuse a task without an estimate at the writer?
2. Should a reaching test the task did not name be judged against the task's estimate (recommended: yes, it is the
   regression this ruling is about), or only the task's own proofs?
3. Wall time: judged at 3x the estimate (the table's slack), or not judged for done at all (allocation and calls only)?
