# Branch cleanup of default, 2026-10-12

## Before and after

- Before: 77 entries in `datahike.api/branches` (the dataset below was read at the start): `:db`, `:cluster-default`, `:current-src`, 2 checkpoints, 36 `:agent-*`, 4 `:attempt-*` (the other attempt branches were already gone) and the `:base/*` ref of each work branch.
- After: 16 entries: `:db`, `:cluster-default`, `:current-src`, 2 checkpoints, 6 `:agent-*` (chain-2, chain-2-base, tr-revise, program-context, mr-u0-u1, step-context) and the 5 `:base/*` refs that belong to the work branches kept (step-context included).
- Open merge requests on default: none (`:seon.task/merge-request` with a merge task lacking `:seon.task/outcome`: empty set). No branch had a running or held connection: every `my.agent/retire-branch!` answered without a refusal.

## Deleted (all through `my.agent/retire-branch!` as root, about 50 ms each)

`seon.cluster.registry/retire-branch!` deletes the work branch and its `:base/` ref in one call, so the `:base/*` refs went with their owners.

- probe-after, probe-r4, probe-restart, probe-cost, probe-e4, pc-probe-a, pc-probe-b: probes, admission open, no task.
- cost-acquire, cost-acquire-fresh-a..d, cost-acquire-probe, cost-acquire-7eb0569c..., cost-acquire-7112c8f5..., cost-gb, cost-gb-1, cost-gb-2, cost-probe, cost-record: finished cost lanes (measurements are in the landing notes).
- narrow-check, narrow-check-4, narrow-tr: finished narrow-check lanes.
- plan-opus, plan-astra, plan-tr-opus, plan-tr-astra, hybrid-tr: plans and hybrid written, lanes finished.
- chain-2-probe: leftover probe of chain 2.
- branch-repl, branch-repl-p1, p2, p3: the branch-repl lane's probes; they retired without a refusal, so none held a connection.
- `:attempt-214c0023a606` (ended `:cancelled`), `:attempt-53e02239f052`, `:attempt-54b843674b8e`, `:attempt-82e6d1f654b1` (each `:sent-back`): `:seon.task.attempt/end` set and no open merge request. `:attempt-cb2ea4c5e3c0` (closed as 6d400a0b8f15), `:attempt-6015ca2624e7` and `:attempt-6d2c806b579a` (`:done`) were already gone.
- Already gone before this lane: findings, hybrid-m1, hybrid-done-merge, cost-record2 and its fresh-0..2, probe-cache-1/2 (agent rows closed, `:seon.agent/admission-closed-tx` set). There was no cn-renames or m1 branch.

## Kept

- `:db`, `:cluster-default`, `:current-src`, both `:checkpoint-default-*`: never deleted (main, the cluster, claimed or checkpoint).
- chain-2 (`:agent-346ef57b0624`) and chain-2-base (`:agent-68f59baeaed6`): outside the named patterns; chain 2 may still be consulted.
- tr-revise, program-context, mr-u0-u1: outside the named patterns; no ended task or closed merge request found for them. Root should decide.
- step-context (`:agent-06b26d0c02b5`): an open agent row, possibly this session's own world.
- `:base/*` refs: none was named by `:seon.task.attempt/fork` (that attribute holds a fork-id uuid, not a branch). Each base ref is derived by `seon.cluster.registry/base-branch` from its work branch, so only the bases of kept branches remain.

## Errors

- Resolved with `seon.problems/resolve!`: signature 3b59158eb8b31e6e578b5bd04c66db14ee74f418c7c7200f1a3dd3e90195a120 (`seon.sci.eval/acquisition-refusal`, 7 occurrences, resolving transaction 536871643). The function takes the full 64-character signature, not the 8-character prefix.
- Still open (4):
  - 117f391d785b41df14b0ebd1d4b1d50d351c7205df3e935cb1c83eebbc121d48: `my.agent/retire-branch!` contract error from agent chain-2-probe at 06:50 (a call that did not match `:my.agent/branch-request`).
  - 759d40be224b919f6fa4f856cc6fde854292594f7bdccafc95ca63a0b1bf1134: `seon.cluster.registry/retire-branch!`, "Connection has been released.", 06:58 on `:cluster-default`, before this lane's calls (those began after 07:00 and answered without error).
  - bd510b99623dbc1feab4bd1a14d2470b761da1362051955ae1262a9c8fcebb89: `seon.sci.eval/install-row!`, 04:32.
  - db142341dca227f1a3970c3921870571d9a832f8133c83d8377e42457772b8dc: `seon.cluster.agent/fork-attempt!`, 06:41.

## Health after

`runtime_status`: state `:faulted` (open faults, not a boot problem); ready, no missing layers, no regressions, heap 1.09 GB of 4 GB, cache 475 of 512 MB, 0 open turns. Counts: 11 untested-hot warnings, 9 agent-error signatures, 46 errored receipts, 7 failed tests, 5 open faults before my resolve (4 now), 9 unowned namespaces, 1 file without a namespace.

Findings: `retire-branch!` takes about 50 ms a branch (30 branches in 1.6 s, mostly GC); `my.agent/retire-branch!` answers an empty map on success, so a caller must read the roster to confirm. Registered by me: none; the temporary `def`s in `chain2.proofs` were unmapped.
