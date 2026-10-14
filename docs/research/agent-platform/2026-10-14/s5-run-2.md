---
title: "S5 run 2: the filed-task chain for the unresolved-alias nearest defect livelocked at Design's attempt-start prove pass with 0 model calls. chain! of a filed task wrote the first task :held with no next (D3, two hand acts to pass it); then the prove pass recorded the Design proof red under a reach digest step-proved? never computes, so next-agent-work answered :prove forever while the turn thread spun at ~1 core and the JVM allocated ~3 GB/s (D4). Abandoned, config restored"
type: research
status: complete (lane s5-run-2 acting as root, 2026-10-14 10:57-11:06 UTC); abandoned at Design, residue cleaned, config restored and verified
created: 2026-10-14
---

# S5 run 2

Script: `docs/research/agent-platform/2026-10-14/s5-run-plan.md`, as amended by root's brief: no stage proof run on
main, `seon.task/from-filed` for the chain, `seon.task/chain-handles` for the watch walk. Default pid 44616 booted
10:56:01 UTC from `data/source/4dd6fab76…` (HEAD at the time), `:seon.boot/missing-layers []`.

**Broken first.**

1. **D4: the Design attempt's prove pass livelocks. Its recorded result is keyed by a reach digest the reader never
   computes, so `seon.turn/next-agent-work` answers `:prove` forever, with no model call.**
   - Attempt `9107c5cedfe0` (agent `attempt/9107c5cedfe0`) started at about 11:00:41. Its prove pass recorded one
     test run, `020ebb9dfc62`, on `:chain-f1f6dc8e6652` at 11:00:41.188. It is the right verdict: the Design proof
     ran red because the plan has no What exists or Approach.

     | `seon.step.design-test/handoff` member | Value |
     |---|---|
     | pass / fail / error | 8 / 1 / 0 |
     | run-ms | 537 |
     | calls | 72,413 |
     | alloc | 508 MB |
     | `:seon.test.member/reach-digest` | `93f6b5e1ce4eba07…` |
     | `:seon.test.run/input-digest` | `f3a42e8142dd0509…`, the same value as run 1's input-less run |

   - Read on the branch head right after, in JVM mode:

     ```clojure
     (seon.test.runner/reach-digests head ['seon.step.design-test/handoff])
     ;; plain: 641a034389b3d82a…
     (seon.test.runner/reach-digests head ['seon.step.design-test/handoff] {} 'seon.error/an-unresolved-alias-name-offers-its-nearest-core-name)
     ;; keyed: 6434ebe6d64750e9…  (task-inputs names the test, so it is a task-reading proof)
     (seon.turn/step-proved? head "attempt/9107c5cedfe0")                        ;; false
     (seon.turn/next-agent-work head {:seon.agent/id "attempt/9107c5cedfe0"})   ;; {:seon.turn.work/situation :prove}
     ```

     The recorded digest `93f6…` is neither the plain `641a…` nor the keyed `6434…`.
     - The task identity read now is `[revision "fd5c4721…", next proofs [seon.step.prove-test/handoff],
       program-revisions "9ba005a2…"]`.
     - The branch as of the run's basis-t (`536871843`) gives keyed `82f46da5…` and an identity with nil revision
       and nil program digest. So `as-of` cannot reconstruct what the recorder keyed.
     - On main, the recorded digests of this test are `93f6…` (this run) and `b10dad84…` (run 1's input-less run).
   - **Effect: a livelock.**
     - The runner finds its own result and records no second run: one run on the branch in four minutes.
     - `step-proved?` (through `seon.context/current-results` at the keyed digest) never sees that result, so the agent
       re-enters `prove-turn` on every step.
     - Sampled 40 times over 2 s, the turn thread `seon-flow/default/attempt/9107c5cedfe0/seon.agent/turn` cycles
       through `seon.turn/next-agent-work` → `prove-turn` → `seon.test/branch-changes` / `seon.test/run` →
       `seon.turn/task-end` → `seon.context/step-verdict` → `seon.program/changed-identities` /
       `seon.task/assigned-obligations` / `seon.test.runner/reach-digests`.
     - Its CPU reached 209 s by 11:04:17, about 3.5 min after start. The thread is a hot loop, not slow work.
   - At the same time, the root pass `seon-flow/default/seon.flow/db<->flow` spent 83 s CPU in
     `seon.cluster.agent/taskless-agents` → `seon.cluster.source/unmerged-count` → `compare-branch` →
     `seon.program/changed-identities` → `declaration-changed-identities` (also seen in `merge-base`).
   - JVM-window counters from three probes: 25.8 GB allocated and 22 s CPU in 8 s; 53.5 GB and 33 s in 15 s; 89 GB
     and 80 s in 25 s, with GC 7.3 s and JIT 19 s in that last window. That is about 3 GB/s for as long as the chain
     ran. After the branch was retired: about 60 MB/s.
   - **Owner.** The task-reading reach digest written by the recorder (the runner's record path under
     `:seon.test/task`) and the one read by `seon.test.runner/reach-digests` with a task (`task-identity`: task
     revision, next proofs, `program-revisions-digest` of the branch) must be one computation over one value.
     - The run's input-digest equals the input-less digest, so the recorder probably keys with no task input, or over
       its test-world database (no revision, no program digest), rather than over the requesting branch.
     - **Proof shape:** after a fresh `chain!` Design attempt's prove pass, `(seon.turn/step-proved? head agent)` is
       true and the next situation is a model turn.
     - The livelock itself is a second defect class: a pass that changes nothing must not be re-proposed. The
       prove-turn's `report :closed 0` leaves the derivation unchanged, so `next-agent-work` answers the same work
       again with no bound. That is the "Bound admission" law in CLAUDE.md.
2. **D3: `seon.task/chain!` of a filed task writes the first task `:held`, with no `:seon.task/next` and no
   `:seon.task/created-by`.**
   - Form: `(seon.task/chain! {:seon.db/connection c :seon.store/store s} (seon.task/from-filed database filed))`,
     with `filed = (seon.task/task-declaration database "f1f6dc8e6652")` (keys `:seon.task/ns :seon.task/name
     :seon.task/plan`, no proofs).
   - Answer, in 165 ms: `{:seon.chain/branch :chain-f1f6dc8e6652 :seon.chain/first "f1f6dc8e6652"
     :seon.task/handles ["f1f6dc8e6652"]}`. That is one handle where three were expected.
   - On the branch, tasks `-2` (`34ca6ac297bf`, `:next` → `c12a57408f49`, `created-by` root) and `-3` were written
     correctly.
   - The first task, the inherited filed entity 935077, got the new plan, instructions, proofs and references. Its
     `:execution` stayed `:held`, with no `:next` and no `created-by`.
   - Cause: `chain-entities` sets all three on the first map. `adopt-tasks-tx` → `task-delta` against the stored
     basis (d024947fe) writes only the declaration's members for an existing row. "A new row keeps … a supplied
     execution" is true for new rows only.
   - The chain fact was written, and root's pass correctly started nothing.
   - Owner: `seon.task/chain!` / `seon.task/adopt-tasks-tx` (S3f-1). Proof shape: `chain!` of `from-filed` of a filed
     entry answers three handles and the first is `:running`.
3. **The filed plan backticks `seon.agent/id` as a misspelling example, and it does not resolve** (run plan problem
   1; `seon.step/problems` gives one `:unresolved`). Root fixed the text in the first map before `chain!`.
4. **A branch write does not wake root's pass.** After the D3 repair, which was a branch transaction, no attempt
   started for 37 s. Root had to `offer!` `:seon.flow/plan-prime` on the cluster's `:seon.cluster.wake/channel`.
   This is expected (wakes listen on main), but it means any branch repair of a chain needs a second hand act.

## Readiness (§4, symbols and committed state only; no test run on main)

| Row | Answer |
|---|---|
| boot | source-root `4dd6fab76…` = HEAD at 10:57, missing-layers `[]` |
| resolve | `chain!`, `from-filed`, `chain-handles`, `done-refusals`, `advance-tx`, `design/handoff`, `prove/handoff`, `my.root/merge!`, `my.root/close!`, `seon.cluster.registry/retire-branch!` all true; `my.root/run!` false |
| templates | `(#{seon.step.design-test/handoff} #{seon.step.prove-test/handoff} nil)` |
| tests present | all 9 named tests have entities (not run, per brief) |
| subject still red | `:installed-nearest nil` |
| files free | `src/seon/error.clj`, `test/seon/error_test.clj` held by no lane (s3b, boot-publish listed) |
| filed entry | `seon.error/an-unresolved-alias-name-offers-its-nearest-core-name`, handle `f1f6dc8e6652`, entity 935077, no proofs |
| config after apply | model `"deepseek-flash"`, no-provider absent, 40, relaunches 0, max-running 4 (`converged? false`, C3) |

## Run

- 10:57-10:58, setup: run 1's residue was abandoned.
  - Attempt `35a165b24683` closed `:closed` with "S5 run 1 evidence, superseded by run 2", through
    `seon.task/close!` with the chain-branch connection. `my.root/close!` reads main only, so it cannot see a chain
    attempt.
  - Chain fact `e80fbb64f0e0` retracted; `:chain-e80fbb64f0e0` retired.
- 10:58: config applied and verified.
- 10:59:07: `chain!` (Broken 2) answered one handle.
- 11:00:03: hand act 1, a branch transaction on 935077: `:seon.task/execution :running`, `:seon.task/next 952574`,
  `:seon.task/created-by [:seon.agent/id "root"]`. `chain-handles` then answered all three.
- 11:00:40: hand act 2, the plan-prime offer. At 11:00:52 attempt `9107c5cedfe0` was open; Design `:running`,
  Prove/Implement `:held`.
- 11:00:41: the prove pass recorded run `020ebb9dfc62`, Design red.
- 11:01-11:04: no turn, no AI attempt, no end. Livelock diagnosed (Broken 1). Root was told at 11:04 ("running,
  task 1, livelocked").
- 11:04:44: config restored. 11:04:59: the attempt closed with reason "S5 run 2: Design livelocked at :prove
  (recorded reach digest is not the one step-proved? reads); abandoned by root"; the chain fact retracted.
  11:05:05: `:chain-f1f6dc8e6652` retired. The agent's threads were gone at 11:05:10.

## Grades

| Task | Model calls | Prompt tokens (cached) | Completion | Wall time | Agent forms | End | Done-refusals seen |
|---|---|---|---|---|---|---|---|
| Design `f1f6dc8e6652` | 0 | 0 | 0 | 4 min 18 s open (11:00:41 to 11:04:59), closed by root | 0 | `:closed` (abandoned) | none rendered (no model turn, so no block reached a model); the recorded red has 1 failing assertion of 9 |
| Prove `34ca6ac297bf` | not started | | | | | `:held` | |
| Implement `c12a57408f49` | not started | | | | | `:held` | |
| **Total** | **0** | **0** | **0** | | | | |

**Compared with:**

| Measure | Rough run 1 | Design prototype 1 | S5 run 1 | S5 run 2 |
|---|---|---|---|---|
| Model calls | 38 | 12 | 0 | 0 |
| Prompt tokens | 570k (310k cached) | 217k | 0 | 0 |
| Hand acts after `chain!` | 6 | 0 | 0 that moved the chain | 2, then abandonment |
| Furthest point | Implement (write-back conflict) | Design done | Design attempt open, idle (D1/D2) | Design prove pass ran red, then livelock (D4) |
| Merge | Prove auto-merged, Implement refused | n/a | none | none |

Progress over run 1: D1/D2 are fixed. The prove pass now runs under the task and the Design proof is correctly red at
the start, where run 1 had a stale green. The failure moved one step later, to reading that result back.

**Hand acts after `chain!`, each with the mechanism that should have removed it:**

1. A branch transaction setting the first task's execution, next and created-by. Mechanism: S3f-1 `chain!` of a filed
   task (D3).
2. A plan-prime offer on the wake channel. Mechanism: the same (a correct `chain!` makes the chain fact's own wake
   sufficient).
3. Abandonment: close, retract and retire. Mechanism: D4, the task-reading reach digest and the unbounded `:prove`
   re-proposal.

Setup acts, not counted: run 1's residue abandonment, the filed-plan fix ("filed plan quality"), config apply and
restore.

**S5 pass criteria:** none met.

**Subject on main afterwards:** unchanged. `seon.error/reply-value` for `str/subs` still holds no
`:seon.error/nearest` (`:installed-nearest nil` at readiness, and nothing merged).

## Context defects (exact excerpts)

No chain agent took a model turn, so there is no agent block or reply to grade. From root's path:

- **`my.root/close!` cannot close a chain attempt.** It reads `[:seon.task/assignment key]` on main
  (`open-assignment`), but chain attempts live on the chain branch. Root has to call `seon.task/close!` with the
  branch connection.
  - That connection's own branch counts as "main" in `seon.task/delete-branch!` (`main?` compares with the
    connection's `:config :branch`), so the branch is never deleted there, and `my.agent/retire-branch!` is a
    separate act.
  - TASK-CHAIN §2 end says root's tools enumerate chain attempts on their branches; that has not landed.
- **`seon.ref/references` takes a `:seon.ref/source` map, not a string.** The first readiness probe passed the plan
  text and got a contract refusal. `seon.step/problems` is the string entrance; the run plan's §2 sentence ("Checked
  on default: `seon.ref/references` problems are `[]`") names the wrong function.
- **`seon.step.design/handoff` refuses a pulled task.** `(seon.db/pull head '[*] [:seon.task/id h])` gives
  `:seon.task/references` and `:seon.task/proofs` as vectors, and the contract wants sets ("expected :seon.task/task
  (a set of count at least 1), got a vector"). A root probe of the handoff on a live task cannot use pull's value
  directly. Pull's decoding of a cardinality-many attribute and the task schema disagree on the collection type.
- **A JVM probe of `seon.test.runner/reach-digests` against `as-of` gives nil task identity parts.** So "what did
  the recorder key" cannot be answered from history today. The recorded run should carry its task identity, or the
  digest inputs, as data.
- **Cost:** a `next-agent-work` derivation on the chain branch took 13 ms in a cold JVM probe. The live loop around
  it is what costs: three passes' worth of `changed-identities` per step, unbounded.

## Errors by signature

| Signature / type | Where | Count |
|---|---|---|
| none recorded as a fault by the livelock | open faults stayed 2 (both before the run) | 0 new |
| `:seon.instrument/contract-error` `seon.ref/references` string argument | root probe | 1 |
| `:seon.instrument/contract-error` `seon.test.runner/task-inputs` map for `:seon.task/sym` | root probe | 1 |
| `:seon.instrument/contract-error` `seon.step.design/handoff` vector references/proofs | root probe | 1 |

The livelock produced no error, no fault and no health signal: "silence reads as unknown" did not fire. Root saw it
only by sampling threads.

## Final merge outcome

None. The chain never left Design. S3b-2 (`0331e584a`, merge-chains! / finish-chains!) landed during the run and was
not installed; root restarts after D3 and D4 are fixed and briefs run 3.

## Residue on default

- None from either run: both chain facts retracted, both chain branches retired, both open attempts closed with
  reasons.
- Main still holds run 1's input-less results (`b10dad84…`) for the template proofs, and this run's red under
  `93f6…`.
- JVM `user` namespace: `s5r2-watch`, `s5r2-filed`, `s5r2-chain`, `s5r2-launch` (host helpers, not program).
- Config: no-provider `true`, `max-episode-runs` 100, `relaunches` 2, model `"deepseek-flash"`, verified with
  `seon.config/effective` at 11:04:59.

## To file (this lane commits docs only; root's D3/D4 lane owns the fixes)

- D3: `seon.task/chain-of-a-filed-task-runs-its-first-task` (owner `seon.task/adopt-tasks-tx` / `seon.task/chain!`).
- D4a: `seon.test.runner/a-task-reading-result-is-recorded-at-the-reach-its-readers-compute` (owner: the runner's
  record path under `:seon.test/task` and `seon.test.runner/task-identity`).
- D4b: `seon.turn/a-prove-pass-that-changes-nothing-is-not-proposed-again` (owner `seon.turn/next-agent-work`; bound
  the re-proposal and name the missing terminal event).
- A silent livelock has no health signal (a turn thread hot with no recorded progress).
- `my.root/close!` / `merge-requests` read main only for chain attempts.
- Pull's cardinality-many vectors against `:seon.task/task` sets.
