---
title: "Merge by reach closure: one merge list for the block, the done rule and the merge (rulings 5, 5a, 5b, 6)"
type: plan
status: "phase 0 done (c8215bfe1); phase 1 in flight; phases 2-3 open"
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

`seon.merge/merge-list [database changed seeds]` → `{:seon.merge/will-merge identity-set :seon.merge/stays
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
3. **Clean or not, then CI (5b, 6).** A conflict among `stays` never blocks; the candidate runs only seeds whose reach
   digest on the candidate differs from the branch's recorded result (`current-results` on the candidate: a reused
   green is absent from the run); not clean stays as the merge branch, offered back to the agent through
   `merge-main!` + its block. Help (5c) lands with it. Proof: a measured `seon.profile/measure` row that a merge whose
   seeds are all green at an unchanged digest executes zero tests.

## Gaps recorded

- Predicates named inside schema forms (`[:fn seon.x/pred?]`) are not reach graph edges: the reach graph reads only
  keywords from schema forms. Phase 1 carries a predicate only when a seed reaches it by call; giving schema nodes
  `::out` symbols changes every reach digest and belongs in a restart batch.
- `prepare-merge!` refuses on any conflict of the whole three-way, staying items included (phase 3).
- A non-chain assignment's stored `:seon.task/merge-base` is a second representation of the base ref; it retires with
  per-assignment merges (TASK-CHAIN S3b).
