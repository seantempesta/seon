---
title: "Run 25: the first live chain on the rebuilt lifecycle (cde025f68): seon.task/chain! built Define → Design → Prove → Implement for a seon.ref defect in one write, and one :running launched Define. Define never made a model call: its turn proc spun at about one core for 11 minutes in the :prove situation (step-proved? false → prove-turn → run-proofs, no write on its branch after the attempt row), the Run 18 class with the new judge. Set :held; nothing merged"
type: research
status: complete (lane run25, 2026-10-13); chain STALLED in Define before its first model call; recorded, not patched
created: 2026-10-13
---

# Run 25: the first live chain, `seon.ref/a-pathless-reference-section-is-a-missing-file-error`

**Broken first: the Define attempt live-locked before any model call (F69).**

- Attempt `341f9a9016cf` (agent `attempt/341f9a9016cf`, branch `:attempt-341f9a9016cf`) was assigned 1 s after
  launch. Its branch's last transaction is t 536871340 at 10:04:46 UTC, the attempt row. It has **no turns and no
  test run**.
- From 10:04:46 to 10:16:15 the turn proc thread `seon-flow/default/attempt/341f9a9016cf/seon.agent/turn` used
  **2,985 ms of CPU per 3,000 ms**. The `db<->flow` thread used about 1,224 ms per 3,000 ms.
  `bin/seon status` showed main's basis constant at t 536871338, `:open-turns 0`, JVM 140-275 %.
- One capture of three stack samples, 400 ms apart, at about 10:15:
  - `seon.cluster.agent/turn-step` → `seon.turn/step` → `seon.turn/turn` → `seon.turn/prove-turn` →
    `seon.turn/run-proofs`
  - `seon.turn/next-agent-work` → `seon.turn/step-proved?` → `seon.context/current-results` →
    `seon.task/obligations` → `seon.task/effective`
  - `seon.turn/next-agent-work` → `seon.turn/step-proved?` → `seon.turn/step-tests` → `seon.turn/task-obligations`
    → `seon.turn/held-task` → `seon.task/agent-task`
- Profile at 10:16: `seon.schema/call-with-projection-state` **52,689 calls, 684 s self**;
  `seon.db/vector-query-call` 4.19 M calls, 252 s; `seon.db/commit-database` 158,044 calls.
- `seon.problems/open-faults` → `[]`. Nothing named the loop: no error, no problem.
- Setting the subject `:held` at 10:16:15 stopped it within 8 s (M13, `cde025f68`, held as designed).

**Reading of the stacks, not verified by a test.** `next-agent-work` derives `:prove` while
`(not (step-proved? …))`. `prove-turn` runs Define's proof, `seon.step-test/define-handoff`, the template default
that reads the `:seon.test/task` input. Since no run was ever written on the branch, the run either never records
or is answered from a record without a write, and the next pass derives `:prove` again. That is Run 18's F53 shape
under `b74344579`'s "proved = results at current reach". Which of `run-proofs`'s outcomes it was, I did not
determine. I kept probe load to one capture, as asked.

**Nothing merged.** `seon.ref/section` still throws on `"#the-goal"` on HEAD.

## The chain as built

`seon.task/chain!` over `[seon.step/define seon.step/design seon.step/prove seon.step/implement]` (Survey skipped
per the owner's decision).

1. **First try, on the filed task's own name** (`seon.ref/a-reference-with-no-path-is-a-missing-file`): refused,
   `:seon.db.write/validation-refusal`, "seon.ref is refused: revision-conflict". The stored task differs from the
   chain's subject, because chain! adds `:seon.task/children`. This is the documented rule ("a stored task that
   differs refuses"). It means a *filed* defect cannot become a chain subject under its own name (O8).
2. **Second, a new subject** `seon.ref/a-pathless-reference-section-is-a-missing-file-error` (`0464278497d3`):
   - The intent has `## Problem` and `## What exists`, written by me in Survey's place, citing `seon.ref/section`,
     `seon.ref/locate`, `seon.ref/section-error`, `:seon.ref/section-error`, `seon.ref/nearest-files` and the five
     tests. `seon.ref/references` over it resolved every reference.
   - Customization `{seon.step/implement {:seon.task/proofs #{seon.ref-test/a-reference-with-no-path-is-a-missing-file
     seon.ref-test/a-reference-with-no-path-answers-an-error-that-holds-no-path}}}`, the filed defect's red proofs.
   - It wrote one transaction (51 ms): the subject `:held`, plus four children with sequential `:needs`.
3. Obligations after construction:

   | Step | Proofs |
   |---|---|
   | Define `091238a6bab2` | `#{seon.step-test/define-handoff}` |
   | Design `db3fb485cbef` | `#{seon.step-test/design-handoff}` |
   | Prove `4997d5e8de30` | `#{seon.step-test/prove-handoff}`, product `#{}` |
   | Implement `d1e55d43f55e` | the two red proofs |

## Before launching

- Default pid from `data/source/cde025f68…`, reset. Health `:degraded` (warnings). Not stopped or reset by this
  lane.
- Gap on HEAD (JVM): `(seon.ref/section "#the-goal")` throws "seon.ref/locate refused argument 0 `path` … Called
  from seon.ref/section (ref.clj:618)".
- **Red proofs on HEAD, interpreted, on `run25-probe`** (run of 4 candidates, 4,309 ms):
  - `seon.ref-test/a-reference-with-no-path-is-a-missing-file`: 1 pass / 2 fail.
  - `seon.ref-test/a-reference-with-no-path-answers-an-error-that-holds-no-path`: 0 / 0 / 2.
  - Also red, and considered but not chosen: `seon.contract-coverage-test/query-params-is-contracted` and
    `…/runtime-duration-is-contracted`, 0/1 each (contract tasks, not behavior defects).
- **Pre-launch gate, interpreted**: the 5 tests reaching `seon.ref/section` and `seon.ref/section-error`, minus the
  two proofs, are 3 tests. Run `d02929d45191`, 802 ms, all green:
  `seon.ref-test/a-missing-anchor-names-the-closest-spelled-heading-first` 2/0/0;
  `…/a-missing-heading-reply-names-the-nearest-heading` 1/0/0;
  `…/a-referenced-doc-shows-its-heading-size-and-one-read` 15/0/0. Probe retired.
- Config: `apply!` `{no-provider false, max-episode-runs 60}` → 4 operations, `converged? false` (C3). Effective
  values confirmed, model `deepseek-flash`.

## Timeline (UTC, 2026-10-13)

| At | Event |
|---|---|
| 10:0x | `chain!` attempt 1 refused (revision conflict); attempt 2 writes the chain |
| 10:04:45.89 | root transacts `[:db/add [:seon.task/id "0464278497d3"] :seon.task/execution :running]`. This was the launch act |
| 10:04:46 | Define attempt `341f9a9016cf` assigned (its branch's last write) |
| 10:04:46 → 10:16:15 | Define's turn proc spins; 0 model calls; no write on its branch or on main |
| 10:15 | one capture: branches, top threads, three stack samples, task states (this lane's only reads during the stall) |
| 10:16:15 | subject `:held`; config restored; spin stopped by 10:16:25 |

## Grades

- **Hand acts after launch: 1**: the `:held` that stopped the live-lock, as instructed. Wakes: 0. Calls: Define
  **0**, others never assigned. **Tokens: 0.** Errors by signature: **none recorded**; the failure was silent.
- **Each stage left the task readier**: not reached. Define never ran.
- **Correctness of what merged**: nothing merged.

## Observations (recorded, not patched)

| # | Observed | Where |
|---|---|---|
| **F69 (binding)** | A fresh Define attempt live-locks in `:prove` before its first model call: `next-agent-work` → `step-proved?` false → `prove-turn` → `run-proofs`, with no transaction on its branch, about one core for 11 min, silent (no error or problem), stopped only by `:held` | `seon.turn/next-agent-work`, `seon.turn/step-proved?`, `seon.turn/run-proofs` (with `seon.step-test/define-handoff`'s `:seon.test/task` input); F54's unbounded-loop class again |
| O8 | `chain!` cannot take a filed task as its subject under its own name: the stored task lacks the chain's children, so the write refuses as a revision conflict. A filed defect must be re-filed under a new name to become a chain | `seon.task/chain!` / `adopt-tasks-tx` revision rule |
| O9 | Each `prove-turn` pass's obligations read walks `seon.task/effective` and `seon.schema/call-with-projection-state` (684 s self over the stall). The no-progress loop is also the hottest code in the JVM | `seon.task/obligations` on the per-pass path |
| O10 | A probe branch retired before launch (`:retiring? true`) was still present 12 min later; `db<->flow` was busy at about 40 % throughout | `seon.cluster.agent` retirement pass |
| C3, F27 | `apply!` `converged? false`; checking 4 tests cold took 4.3 s and 7.0 GB | as before |

## Residue on default (disposable)

- Subject `0464278497d3` is `:held` (verified). Define has one attempt with no end; the other steps were never
  assigned.
- **Kept as evidence for F69**: `:attempt-341f9a9016cf` and `:base/attempt-341f9a9016cf`.
- `run25-probe` (`:agent-4a075d283b90`): `my.agent/retire-branch!` answered `:retiring? true` before launch, but
  the branch and its `:base/…` were still in `d/branches` at 10:16, after the chain's stall (O10). In Runs 18-24,
  retired probes left the list by the next read. Possibly the same stalled `db<->flow` pass; not checked.
- The chain's tasks are database facts only, with no task-file write, since nothing merged.
- Config restored to `no-provider true` and `max-episode-runs 100` (verified).
- Shared tree: unchanged by the chain.
