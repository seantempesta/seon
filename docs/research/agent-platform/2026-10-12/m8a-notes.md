---
title: "M9 and M8a notes: publication is due work; a requested green end merges with no hand act"
type: research
status: goal lane, 2026-10-12. M9 05d3eb2ed; M8a in the commit that adds this file. Both need root's restart batch to be live on default.
---

# M9 and M8a notes

## What is now automatic

A done end whose branch holds the agent's merge request is merged by db<->flow (`seon.cluster.agent/next-actions`
`:merge` → `seon.merge/merge!`: the gate `seon.cluster.source/prepare-merge!` with the files proven against
`seon.merge/checkout`). The next pass publishes it (`:publish` → `seon.merge/publish!`: write-back, one
`git commit --only` of those paths under the claim `merge-<assignment>` in bin/lane's ledger, then
`:seon.task/git-commit`). The pass after that deletes the branch. A path a lane claims, or one that is dirty,
waits. A refusal is recorded as `:seon.task/refusal`, never as an `:error` end over `:merged`.

## Left, for root

- Restart default on HEAD (`start --head`), then run one single-Implement chain (plan §0 item 4) on a
  subject whose namespace has a file in the checkout, with `:seon.config.fs/working-root` = the checkout
  (it is today). Expected hand acts: zero after assignment. Watch for: the first code merge in the JVM
  (1.3 s with its gate) and the publishing pass (260 ms / 175 MB, mostly the analyzer reindex).
- The patch `goal-test-checkout.patch.md` (test_support, lane fixture-hour): with-running-agent clusters
  inherit the live checkout as their working root.
- M8 (rest): `request-merge!`, the request rows, `land-end!`, `land!`; then "declarations merged" must be
  derived without the merge request on main (`seon.cluster.agent/publication-due?` reads it today).
- A requested merge in a cluster whose checkout cannot hold the files refuses at the gate (an `:error` end
  and a fault). New-namespace and scratch-namespace merges stay blocked on
  `seon.cluster.source/an-agents-repl-scratch-namespace-never-blocks-its-landing`.
- Unrun: `seon.cluster.agent-test` (lane agent-hangs) and six long `seon.cluster.write-back-test` members.
