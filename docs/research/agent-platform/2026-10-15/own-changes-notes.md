---
title: "own-changes: a chain branch's change as git's diff(merge-base, HEAD) — evidence patch"
type: research
status: stopped by root before commit (superseded by a merge-base taken from Datahike's commit graph)
created: 2026-10-15
---

# own-changes: evidence for chain-step-cost's fix

Lane `own-changes`, 2026-10-15, at a76a9c141. Patch: `own-changes.patch` beside this note (7 paths, +83/-45),
implementing `chain-step-cost.md`'s smallest fix. Not committed: root stopped the lane for a merge base derived
from Datahike's commit graph (forward merge as a merge commit with `:parents`).

## What the patch does

`seon.program/own-changed-identities [merge-base head bounds]`: walk `head`'s own history from its fork entity's
`:seon.branch/base-t` (read on `head` itself via `seon.cluster.registry/fork-of`, so the t can never come from
another branch's counter; no fork entity, i.e. the cluster's own branch, walks from the merge base's t), keep
the identities whose digest differs between `merge-base` and `head`. Callers converted:
`seon.context/change`, `seon.cluster.agent/done-judged`, `seon.test/branch-delta`, `seon.merge/merge-main!`
(`ours`; its `branch-base-t` walk removed), `seon.test/change-base` (now the base ref); `seon.test/fork-commit`
and its `:seon.test/commit-facts` cache kind deleted; `seon.step/changed` docstring only (it calls
`branch-delta`).

## The red deftest

`seon.merge-forward-test/after-a-forward-merge-the-branch-changed-only-its-own-declarations`: chain! a branch,
define one function on it, advance main by 8 one-function transactions (main's t passes every branch t),
`seon.merge/merge-main!`, then assert `seon.step/changed` on the branch and the judged identities are exactly
the branch's one function. Red on HEAD (run 3db53244255b, HEAD + the test file): both `seon.step/changed` and
HEAD's judged set (`changed-identities` from the base ref's basis-t) answered #{} — they MISSED the branch's own
function (the other half of broken 1: branch edits below main's t are skipped). With only one main transaction
the same test was green on HEAD (run a1e0a588346b): `seon.test/branch-delta`'s behavior-digest filter hides
main's moved functions, so the fixture must advance main's counter past the branch's.

## Measured on :chain-f1f6dc8e6652 (attempt/e93ca95c9fdb), read-only, seon.profile/measure, warm

| | before (HEAD) | after (patch, SCI on lane branch) |
|---|---|---|
| step's change set, `branch-delta` from `change-base` | 628 changes / 198 tests, 108 ms, 230 MB | 24 identities -> 10 changes / 1 test (8 fns), 31 ms, 62 MB |
| `branch-delta` from the base ref's t (old `seon.step/changed`) | 91 / 11, 82 ms, 154 MB | (same as above) |
| `seon.context/step-verdict` | `:working`, 685 identities, refusals [stray-red, uncontracted], 200 ms, 420 MB | `:done`, 24 identities, done-refusals [], 30 ms, 66 MB |

The after column for step-verdict re-evaluated `seon.context/change` in SCI on the lane's own branch; that
eval request's automatic test step ran 25 reaching tests and allocated 7.1 GB (a finding on its own).

## Check result (HEAD + patch)

`bin/seon check` with seon.merge-forward-test, seon.merge-test, seon.step-test, seon.context-test,
seon.test-reaching-test, seon.turn.test-step-verdict-test, seon.program-test, seon.cluster.db-flow-test,
seon.turn-test, seon.cluster.turn-test, my.program-test, seon.context-cost-test, seon.pass-cost-test:
run a0bdd04e716e, 45 executed, 275 pass, 5 fail, 1 error, hit the 600 s check limit with 253 pending (the new
deftest among them, so unproven green in that run). Both reds are red on HEAD too (run c1bf0404e62b):
`seon.cluster.db-flow-test/a-prove-step-whose-product-main-holds-merges-and-its-successor-forks-main` (outcome
nil, not :merged) and `seon.cluster.db-flow-test/every-agent-start-has-a-task` (never exits; it holds the check's
whole remaining budget, so it should be named alone or fixed before any check naming db-flow-test).

## Every reader of a branch base found

Merge base (base ref, `seon.cluster.registry/base-commit-id`): `seon.turn/fork-commit`, `seon.context/change`
(prefers the attempt row's `:seon.task/merge-base`), `seon.step/changed`, `seon.cluster.agent/done-judged`,
`seon.cluster.agent` (attempt fork point, `fork-attempt!` and its caller), `seon.task` (chain merge base at
assignment and `:seon.task/merge-base` of a successor), `seon.merge` (assignment row merge base),
`seon.cluster.source/merge-base`, `seon.cluster.source` prepare-merge's base commit, `my.program` write-back
(`:seon.source/base-commit-id`).
Fork t (`:seon.branch/base-t`, `seon.cluster.registry/fork-of`, `seon.cluster.registry/advanced-base-t`):
`seon.cluster.source/branch-base-t` (used by `seon.cluster.source/merge-base` and prepare-merge's
`changed-identities` walk, the remaining bare-t caller), `seon.merge/merge-main!` (`since`),
`seon.cluster.registry/branch-fork-entity` (nested forks inherit it), `my.agent` and `my.program` (fork target).
Walked fork commit: `seon.test/fork-commit` (via `seon.test/change-base`, used by `seon.test/branch-changes`,
`seon.test/branch-tests` and the check-reach selection).
Basis-t of a base value passed as a bare t to `seon.program/changed-identities`: `seon.context/change`,
`seon.cluster.agent/done-judged`, `seon.test/branch-delta` — the three callers that break its docstring
precondition after a forward merge.
