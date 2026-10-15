---
title: "Merge by reach closure: one merge list for the block, the done rule and the merge (rulings 5, 5a, 5b, 6)"
type: plan
status: "phase 0 done (c8215bfe1); 1a done (49b2ac05d); 1b done (3c5f650a0); 2 a patch (63bca068d, waits for src/seon/context.clj); 3 cause found by reading, fix and red proof a patch (merge-reuse-phase3.patch), unrun: default down"
created: 2026-10-15
---

# Merge by reach closure

Authority: [branch-model rulings](branch-model-rulings.md) 5, 5a, 5b, 5c, 6. One plan + narrow check (AGENTS.md).

## What exists, and its data flow

- The changes since the merge base: `seon.test/branch-delta` (one `seon.program/changed-identities` history walk, less
  behavior-equal declarations) and, at the merge, `seon.cluster.source/prepare-merge!`'s `compare-branch` three-way
  (`seon.program/three-way`: changed-on-branch, added, retracted, conflict).
- The reach graph: `seon.test.runner` builds one cached graph per program revision (`reach-graph`: nodes with
  `::out` calls/references/subject and `::keys` keywords of the function and its contract; schema nodes with the
  keywords their forms name). `seon.test.runner/reach-memberships` answers names only; schema keys are not exposed.
- Results by reach digest: `seon.test.runner/current-results` (a branch result keyed by the digest its own reach
  gives, so the same declaration content on the merge candidate has the same identity, ruling 6).
- Selection already exists: `prepare-merge!`'s `:selection` merges only the selected identities (their namespace rows
  carried when changed) and refuses a selected declaration reaching an unselected change. Nothing passes it today:
  `seon.merge/merge-onto` merges the whole delta, which is how run 8 carried `:tmp/probe`, ns `user` and
  `seon.error/config-expectation-present-generator`.
- The block: `seon.context/branch-changes` lists the changes and marks "reached by no test" (c94977458) with its own
  `tested-names` over `reach-memberships`: a second derivation of the same question.

## The one function

`seon.cluster.source/merge-list [database changed seeds]` (in source: `seon.merge` requires it, and `prepare-merge!` and `done-refusals` live there) → `{:seon.merge/will-merge identity-set :seon.merge/stays
identity-set}`: `database` the branch head, `changed` the branch's own changes since its merge base (git's three-way:
a change main made is never the branch's), `seeds` the tests that curate the merge. Closure = every name the seeds
reach (`seon.test.runner/reach-closure`, new beside `reach-memberships`: the reach graph's names from the seeds plus
the schema keys of their contracts and keywords, fixed point through the schemas those name), each name as its
function, test and namespace identity, each key as its schema identity. `will-merge` = `changed` ∩ closure, plus every
retraction (a deletion carries no behavior to test, and leaving it would revert the agent's delete); `stays` = the
rest. Program entities only: `changed` comes from the program partition; tasks, attempts, evaluations, messages and
config never enter it. Cost: one cached reach graph walk from the seeds (linear in the reached closure) and one set
intersection; no test runs.

Seeds (5a) = the task's `:seon.task/proofs` plus the green tests the branch reaches a change with: the tests in
`changed` whose current result (`current-results`) is green, and the tests reaching a changed function
(`seon.fn/reverse-closure`) whose current result is green. A red or never-run test seeds nothing: to get something
merged, test it.

## Phases (each with its red proof)

0. **Done (c8215bfe1).** `merge-main!` held only a connection a running graph had open, so a held chain refused with
   a nil base; it now holds the branch for its action (`seon.cluster.registry/on-branch`), and a chain attempt's final
   merge reads the base ref the forward merge advances (one merge base). Proof:
   `seon.merge-test/root-merges-main-forward-into-a-chain-branch` (red run 0c2194efbe70, green 4584305870cd).
1. **The merge list and the merge carrying only it.** `seon.test.runner/reach-closure`, `seon.merge/merge-list`, and
   `seon.merge/merge-onto` passing `:selection (:seon.merge/will-merge list)` to `prepare-merge!`; the merge answer
   carries `:seon.merge/stays`. Proof: `seon.merge-test/a-merge-carries-only-what-its-tests-reach` (a fixture
   branch with a tested change, an untested scratch def, a probe schema `:tmp/probe` and a `user` namespace: only the
   tested change and its closure are in `will-merge`; the rest stay).
2. **The block shows the same list** (`seon.context/branch-changes` calls `merge-list`; `tested-names` deleted), each
   staying item with its include form (a test reaching it) and drop form (`my.program/ns-unmap!`); the done rule's
   report names the same list. `seon.context` is held by lane prompt-prefix: a patch until it is free. Proof: a row in
   `seon.context-test` asserting the block's `:seon.context/changes` equals `merge-list` for the fixture.
3. **Clean or not, then CI (5b, 6).** The content-keyed lookup already exists: `seon.test/run` (in
   `run-request`) answers each runnable member from `seon.test/carried-results`, which looks its reuse identity up in
   `seon.cache` (`seon.test.runner/retain-results!`) and then in the recorded runs of main and every live agent branch
   main's rows name (`seon.test/source-branches`, homed by `seon.cluster.host/register!`), and records the answer as the
   candidate's own member with `:seon.test.member/reused-run` (`seon.test/carried-member`). So the 10-15 finding
   ("selection reads S alone") is not the cause, and the later readers need no change: `merge-verdict` reads
   `seon.test.runner/run-results` of S's run, which holds the carried member and its counts; `accept-merge!` judges
   the same run. The cause found by reading (not yet reproduced: default was down for the whole slice, so no REPL or
   `bin/seon check` run, which needs a live root): the reuse identity is `[sym reach-digest input-digest evaluator]`,
   and `evaluator` is the request's (`(if (root-agent? execution) :compiled :interpreted)`). A chain agent's test step
   records `:interpreted`; the candidate gate acquires S with no agent id (`cluster.agent/acquire-context! source nil`),
   so it asks `:compiled`. The identities never meet and every seed reruns. A second component to verify on the same
   run: `:seon.test.run/input-digest` is each database's `:seon.source/test-input-digest`; a chain branch forked before
   a publication that changed a non-declaration gate input holds the older digest, and S (forked from main's head)
   the newer one. That one is a real input change and correctly reruns.
   **Fix (a), content-keyed:** the reuse identity drops the evaluator, `[sym reach-digest input-digest]`: the reach
   digest names the content that ran, and a compiled run differing from the entities is already excluded
   (`:seon.test/loaded-source-drift` skips carrying). Touches `seon.test/recorded-outcomes`, `carried-results`,
   `file-two-outcomes!` and its call site, `seon.test.runner/retain-results!` and the eviction helper of
   `seon.test.branch-reuse-test`; about 25 changed lines, nothing added. It retires the claim "A result proves what its
   evaluator ran" for carried results only; `seon.test/select`'s own same-branch reuse keeps its evaluator filter.
   Root decides that sentence. Patch with the red proof:
   [merge-reuse-phase3.patch](merge-reuse-phase3.patch) (applies on 08686ce37; unrun).
   Proof: `seon.merge-test/a-merge-reuses-the-chain-branchs-green-at-an-unchanged-reach` (in the patch): main homes a
   chain agent on its branch, the agent runs its seed there (`:interpreted`, green), `prepare-merge!` runs the gate;
   asserted on data: the seed's reach digest on S equals the branch's, the two runs' input digest and evaluator agree
   (red on HEAD by the evaluator, expected), and S's member names the branch's run as `:seon.test.member/reused-run`.
   Next: with default up, run the proof red on HEAD, apply the fix, run it green with the reaching tests
   (`seon.test.branch-reuse-test`, `seon.merge-test`, `seon.test-test`, `seon.test.selection-test`,
   `seon.test-reaching-test`, `seon.cluster.branch-evidence-test`), and measure the gate's executed and reused counts and
   ms before and after on a real chain branch, plus a `seon.profile/measure` row on `prepare-merge!`. Still open from
   the original phase: a conflict among `stays` never blocks; not clean stays as the merge branch, offered back through
   `merge-main!` and the block; help (5c) lands with it.

## Gaps recorded

- Predicates named inside schema forms (`[:fn seon.x/pred?]`) are not reach graph edges: the reach graph reads only
  keywords from schema forms. Phase 1 carries a predicate only when a seed reaches it by call; giving schema nodes
  `::out` symbols changes every reach digest and belongs in a restart batch.
- `prepare-merge!` refuses on any conflict of the whole three-way, staying items included (phase 3).
- A non-chain assignment's stored `:seon.task/merge-base` is a second representation of the base ref; it retires with
  per-assignment merges (TASK-CHAIN S3b).
