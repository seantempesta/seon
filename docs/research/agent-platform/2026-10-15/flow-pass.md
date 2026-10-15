# flow-pass: root's db<->flow pass after one branch's write

Lane flow-pass (Opus), 2026-10-15, on default at e9e5834ed..38fbff4eb. Follows the "what's left" finding of
[eval-cost](eval-cost.md); it is H0 of [alignment-hybrid](alignment-hybrid.md).

## What the pass read, and why

`seon.cluster.agent/run-pass!` derives `seon.cluster.agent/lifecycle-facts` up to three times per pass. On default
main holds no assignment whose branch exists, so all of it is `seon.cluster.agent/taskless-agents`: every agent on
main with a branch the store holds and no assignment (128 of 181 rows; 53 name deleted branches). It answers one
question per agent: has it ended (main's `:seon.agent/admission-closed-tx`, else the one its branch head holds), and,
once ended, does its branch hold anything main lacks (`seon.cluster.source/unmerged-count`, zero deletes the branch).

Measured in JVM mode on default (instrumented), one derivation, warm:

| stage | count | ms |
|---|---:|---:|
| main pulls of the end | 128 | 3.8 |
| `branch-admission-end`: open each branch head (`seon.db/branch-database`) and query it | 127 | 181 |
| `unmerged-count` (merge base, digest maps, three-way) for the ended ones | 8 | 173 |
| whole `taskless-agents` | | 222-230 ms, 376-387 MB |

Neither input changed since the last pass for 127 of 128 agents: an agent's eval request writes only its own branch
(main's commit is unchanged, checked on default), so every other branch head is the commit the last pass read.
Both reads are functions of content: the end is a function of the branch head commit, the unmerged count a
function of (branch head commit, main commit).

## The fix (committed with this note)

- `branch-admission-end` and the assignment path's `branch-end` take the head commit (`seon.cluster.registry/branch-commit-id`,
  read from the roster the pass holds) and are `seon.cache` lookups keyed `[commit agent-id]` / `[commit assignment]`;
  the value is a map, `{}` for no end, so the common answer is retained.
- `seon.cluster.source/unmerged-count` takes the branch commit and is a `seon.cache` lookup keyed
  `[branch commit, main commit]` (`seon.cluster.source/compare-commit` is the comparison `compare-branch` makes once it
  holds the commit). Callers converted: `taskless-agents`, `lifecycle-facts`, `seon.merge` (accepted?).
- No wake payload, no polling, no TTL: a head that did not move is the same key.

Same JVM, same data, a local copy of the new derivation against the installed one (128 agents, nothing changed):

| derivation | ms | alloc |
|---|---:|---:|
| installed `taskless-agents` | 222 / 230 | 376 / 387 MB |
| keyed by commit | 10.1 / 10.2 | 11.5 / 11.4 MB |

Per pass after one branch's write: one branch open (~1.4 ms) plus the residual below. Proof:
`seon.cluster.host-test/a-lifecycle-pass-after-one-branch-wrote-reads-only-that-branch` (4 agents, one ended with an
unmerged definition; after one agent's write: 1 `seon.db/commit-database`, 0 `seon.cluster.source/merge-base-database`;
red on HEAD with 5 and 1).

## What stays proportional to N

- The roster read: one head-record read per held branch (`registry/branch-commit-id`, ~30 us raw, ~0.24 ms
  instrumented; ~8 ms for 128). Only a wake that names its branch would remove it; the in-port coalesces wakes.
- A write to main (root's own eval requests) moves main's commit, so every ended agent holding unmerged work is
  compared again (8 today, ~20 ms each). They are held for root; retiring them removes it.

## Stale admissions on main (not retired here)

128 held agents; 118 open with no running graph, 8 ended on their branch holding unmerged work, 1 ended on main
(eval-cost) holding unmerged work, 1 running (this lane). Last activity is the branch head's newest transaction (UTC).

Forms, for root or the owning lane:

- An open lane agent: `bin/lane release LANE --agent AGENT` (root closes its admission on main,
  `seon.cluster.host/close-admissions!`); the next pass deletes its branch when nothing is unmerged.
- As the agent itself: `(my.agent/retire-branch! {:seon.store/branch BRANCH})`, through MCP with `agent` set to it.
- Ended with unmerged work (marked below): root decides to keep it or drop the work; the branch stays until then.

| agent | branch | last activity | end |
|---|---|---|---|
| `overlay-opus2` | `:agent-ee6b768e1a88` | 2026-10-14T06:11 | **ended on its branch, unmerged** |
| `pitch-probe-flash-high` | `:agent-b0c333cabd17` | 2026-10-14T06:14 | **ended on its branch, unmerged** |
| `rootuhnya-wait-root` | `:agent-692a722dade4` | 2026-10-14T06:18 | open |
| `perf-analyzer` | `:agent-c48ba19c43f6` | 2026-10-14T06:25 | open |
| `s3d-1a` | `:agent-2b41440e537b` | 2026-10-14T06:33 | open |
| `db-caches` | `:agent-df3fd6486ece` | 2026-10-14T06:46 | open |
| `s3d1b-before` | `:agent-86dbd2d76d7a` | 2026-10-14T06:56 | open |
| `s3d-1b` | `:agent-ef21ab4ac095` | 2026-10-14T06:59 | open |
| `b1-test-reuse` | `:agent-6cc461ad3b4b` | 2026-10-14T07:06 | open |
| `reconcile-writer` | `:agent-cada33c27ed0` | 2026-10-14T07:40 | open |
| `overlay-impl` | `:agent-390ddc075683` | 2026-10-14T07:41 | open |
| `repl-trial-probe` | `:agent-94987a0f8eaf` | 2026-10-14T07:43 | **ended on its branch, unmerged** |
| `repl-trial-probe2` | `:agent-a3325fdcb748` | 2026-10-14T07:46 | **ended on its branch, unmerged** |
| `repl-trial` | `:agent-4ae8677af013` | 2026-10-14T07:49 | open |
| `s1d-2` | `:agent-333534afb175` | 2026-10-14T08:01 | open |
| `reader-charlit` | `:agent-db40eed7160c` | 2026-10-14T08:02 | open |
| `teststep-verdict` | `:agent-ca772bbd5410` | 2026-10-14T08:08 | open |
| `request-view` | `:agent-65370bb2e6d7` | 2026-10-14T08:33 | open |
| `s3a-1` | `:agent-8ef88ae64f16` | 2026-10-14T08:36 | open |
| `writeback-fix` | `:agent-324856ff6af6` | 2026-10-14T08:44 | **ended on its branch, unmerged** |
| `writeback-fix-2` | `:agent-7da818aa0ec0` | 2026-10-14T08:44 | **ended on its branch, unmerged** |
| `writeback-fix-3` | `:agent-c7404e5c6530` | 2026-10-14T08:44 | **ended on its branch, unmerged** |
| `lane-s3b` | `:agent-afd76d2e5b43` | 2026-10-14T09:10 | open |
| `s3d-3` | `:agent-7a98edd79838` | 2026-10-14T09:10 | open |
| `lane-s2b-1` | `:agent-fae472ca43aa` | 2026-10-14T09:11 | open |
| `merge-repair` | `:agent-51b85c36b95e` | 2026-10-14T09:30 | open |
| `lane-s2a` | `:agent-049eeb8a453d` | 2026-10-14T09:30 | open |
| `lane-s3a-2` | `:agent-467c67d1e43c` | 2026-10-14T09:47 | open |
| `s5-prep` | `:agent-7a84abcff92b` | 2026-10-14T09:48 | open |
| `s4b-design` | `:agent-68bdcfe39751` | 2026-10-14T09:49 | open |
| `s3a-2` | `:agent-19a8eb6255ee` | 2026-10-14T09:49 | open |
| `s4b-prove` | `:agent-717e540b8335` | 2026-10-14T09:51 | open |
| `s3a-3` | `:agent-92815b57c258` | 2026-10-14T09:58 | open |
| `red-values` | `:agent-297fa8219f4b` | 2026-10-14T10:02 | open |
| `s3a-2b` | `:agent-4b4d876f2f57` | 2026-10-14T10:04 | open |
| `boot-publish` | `:agent-b05c489bcfdb` | 2026-10-14T11:09 | open |
| `problem-basis` | `:agent-64c221f6c346` | 2026-10-14T11:48 | open |
| `model-reply-error` | `:agent-2aea9ce1b33c` | 2026-10-14T11:50 | open |
| `s3c-1` | `:agent-d537ba31b083` | 2026-10-14T11:51 | open |
| `lane-s3c-1` | `:agent-4757bab3ffc6` | 2026-10-14T11:52 | open |
| `premortem-s5` | `:agent-ddddcea34761` | 2026-10-14T11:54 | open |
| `prove-reach` | `:agent-7706a1a4d3e2` | 2026-10-14T12:03 | open |
| `identity-stable` | `:agent-955728e65c0c` | 2026-10-14T12:07 | open |
| `s5-run-4-probe` | `:agent-c0a600208bcb` | 2026-10-14T12:16 | **ended on its branch, unmerged** |
| `taught-forms` | `:agent-7fe9b83e3dca` | 2026-10-14T12:19 | open |
| `require-alias` | `:agent-2c875499926b` | 2026-10-14T12:20 | open |
| `rerun-context` | `:agent-25336852abcf` | 2026-10-14T12:25 | open |
| `reply-total` | `:agent-48ef480ebd81` | 2026-10-14T12:38 | open |
| `teststep-cost` | `:agent-b44f932f24bc` | 2026-10-14T12:54 | open |
| `prompt-cost` | `:agent-02a5b60b4584` | 2026-10-14T13:00 | open |
| `clean-teststep` | `:agent-d414a72328d1` | 2026-10-14T13:00 | open |
| `clean-doc` | `:agent-6ea4947f9bf8` | 2026-10-14T13:00 | open |
| `turn-error-end` | `:agent-2fdd5bad856a` | 2026-10-14T13:01 | open |
| `clean-print` | `:agent-2249dedfc521` | 2026-10-14T13:02 | open |
| `clean-eval-2` | `:agent-984cfc47ba60` | 2026-10-14T13:03 | open |
| `clean-eval` | `:agent-afa315d58c5b` | 2026-10-14T13:04 | open |
| `clean-asof` | `:agent-84acd76b9999` | 2026-10-14T13:05 | open |
| `teststep-land` | `:agent-b979affada0b` | 2026-10-14T13:17 | open |
| `context-cost` | `:agent-e76548d1f5b5` | 2026-10-14T13:27 | open |
| `rvland-probe` | `:agent-ca3517a4506f` | 2026-10-14T13:32 | open |
| `wbo-probe` | `:agent-a5cadbdb265d` | 2026-10-14T13:42 | open |
| `s3f-2` | `:agent-b77a8214d279` | 2026-10-14T13:43 | open |
| `request-fixed-cost` | `:agent-790e1eaa5f79` | 2026-10-14T13:43 | open |
| `teststep-3a` | `:agent-4413e636fdd9` | 2026-10-14T13:45 | open |
| `request-view-tests` | `:agent-3e9455bf1e1d` | 2026-10-14T13:55 | open |
| `error-refs` | `:agent-894d47636b29` | 2026-10-14T14:03 | open |
| `rvt-probe-1` | `:agent-da25b87db1ed` | 2026-10-14T14:04 | open |
| `request-fixed-cost-2-sub2` | `:agent-7bec3648aeb5` | 2026-10-14T14:13 | open |
| `context-cost-2` | `:agent-ff656d99bfa5` | 2026-10-14T14:15 | open |
| `request-fixed-cost-2` | `:agent-09ca5d329291` | 2026-10-14T14:23 | open |
| `changes-render` | `:agent-9fe74f64cdb0` | 2026-10-14T14:25 | open |
| `request-fixed-cost-2-sub4` | `:agent-1f3352fef2b0` | 2026-10-14T14:26 | open |
| `frictions-a` | `:agent-8dc6db5ee9d8` | 2026-10-14T14:29 | open |
| `nested-limit` | `:agent-497a97805f61` | 2026-10-14T14:37 | open |
| `outside-end` | `:agent-97556808d629` | 2026-10-14T14:39 | open |
| `request-fixed-cost-2-sub3` | `:agent-cdc07d5e8e3c` | 2026-10-14T14:49 | open |
| `alias-digest-3` | `:agent-99562089bbdf` | 2026-10-14T15:06 | open |
| `alias-digest-2` | `:agent-2bc5d804b542` | 2026-10-14T15:06 | open |
| `alias-digest` | `:agent-423ed7f1c544` | 2026-10-14T15:06 | open |
| `alias-digest-4` | `:agent-317bd1cf4e0f` | 2026-10-14T15:07 | open |
| `alias-digest-5` | `:agent-084bd8226dab` | 2026-10-14T15:08 | open |
| `alias-digest-6` | `:agent-ef354285b20a` | 2026-10-14T15:08 | open |
| `alias-digest-7` | `:agent-fac5ab3c72fb` | 2026-10-14T15:08 | open |
| `alias-digest-8` | `:agent-13a90d0e7124` | 2026-10-14T15:08 | open |
| `alias-digest-9` | `:agent-24c1b1b225f3` | 2026-10-14T15:09 | open |
| `alias-digest-10` | `:agent-df9787075545` | 2026-10-14T15:09 | open |
| `done-path` | `:agent-4b5d9ce84059` | 2026-10-14T15:09 | open |
| `alias-digest-12` | `:agent-bcae0f27e45b` | 2026-10-14T15:09 | open |
| `alias-digest-13` | `:agent-b9377d57c499` | 2026-10-14T15:09 | open |
| `alias-digest-11` | `:agent-ccc2cabeaabf` | 2026-10-14T15:10 | open |
| `alias-digest-14` | `:agent-37b58a3d9577` | 2026-10-14T15:10 | open |
| `alias-digest-15` | `:agent-6c3b7627a438` | 2026-10-14T15:11 | open |
| `alias-digest-16` | `:agent-a1d59e4221e0` | 2026-10-14T15:11 | open |
| `alias-digest-17` | `:agent-d564097647e5` | 2026-10-14T15:12 | open |
| `lane-done-path` | `:agent-17c7de399a09` | 2026-10-14T15:19 | open |
| `lane-branch-predicates` | `:agent-9801842243f3` | 2026-10-14T15:23 | open |
| `chain-pass` | `:agent-7660ad043a90` | 2026-10-14T15:25 | open |
| `lane-branch-predicates-impl` | `:agent-6f6bbe060b50` | 2026-10-14T15:27 | open |
| `predicates-plan-astra` | `:agent-1ae16830ed51` | 2026-10-14T15:45 | open |
| `lane-predicates-plan-opus` | `:agent-d6cb1bbbcc4e` | 2026-10-14T15:48 | open |
| `lane-predicates-hybrid` | `:agent-565f8930596e` | 2026-10-14T15:54 | open |
| `lane-content-key-growth` | `:agent-8ac896496feb` | 2026-10-14T15:55 | open |
| `lane-predicates-phases` | `:agent-1cb90ead04d4` | 2026-10-14T16:15 | open |
| `block-cost` | `:agent-0e3cdf767bb4` | 2026-10-14T17:04 | open |
| `lane-block-cost` | `:agent-b9bb03bf0d32` | 2026-10-14T17:08 | open |
| `lane/open-attempt` | `:agent-9b536fcad452` | 2026-10-14T17:17 | open |
| `overlay-delta` | `:agent-64e6bbe8ad5b` | 2026-10-14T17:57 | open |
| `branch-rows` | `:agent-f9e07df6c4b3` | 2026-10-14T18:12 | open |
| `seen-branch` | `:agent-c0107e37e87c` | 2026-10-14T18:24 | open |
| `lazy-member` | `:agent-01d1b35c653f` | 2026-10-14T18:37 | open |
| `seen-cache` | `:agent-f22898b3aaf4` | 2026-10-14T18:37 | open |
| `block-cache-2` | `:agent-148234a65ef1` | 2026-10-14T18:53 | open |
| `prove-loop` | `:agent-c10ffa67d9ed` | 2026-10-14T19:11 | open |
| `ns-unmap-shape` | `:agent-d42e08a8e584` | 2026-10-15T00:13 | open |
| `own-delta-plan-opus` | `:agent-3f6edfcc461a` | 2026-10-15T00:17 | open |
| `error-chain-ref` | `:agent-be0074cd14b1` | 2026-10-15T01:12 | open |
| `failure-report-ref` | `:agent-935516c50e0b` | 2026-10-15T01:13 | open |
| `argon-context` | `:agent-fdab81718106` | 2026-10-15T01:40 | open |
| `argon-context-fresh` | `:agent-aa723bba69ab` | 2026-10-15T01:59 | open |
| `owner-demo` | `:agent-30ec3e31e4e5` | 2026-10-15T02:15 | open |
| `reach-reply` | `:agent-0e582f5a0bb7` | 2026-10-15T02:43 | open |
| `hunt-1015b` | `:agent-a98ae7df0ca7` | 2026-10-15T03:30 | open |
| `own-changes` | `:agent-00edf30a0a36` | 2026-10-15T03:37 | open |
| `merge-callees` | `:agent-5018cd0c90c4` | 2026-10-15T06:40 | open |
| `step-merge` | `:agent-be25833245ad` | 2026-10-15T06:41 | open |
| `guessed-schema` | `:agent-862174c0ee20` | 2026-10-15T07:17 | open |
| `eval-cost` | `:agent-442b1cdf86ab` | 2026-10-15T07:29 | **ended on main, unmerged** |
| `flow-pass` | `:agent-aab26ec91f9c` | 2026-10-15T07:35 | open (graph running) |
