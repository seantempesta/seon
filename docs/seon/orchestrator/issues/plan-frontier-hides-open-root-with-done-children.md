---
type: issue
status: completed
tags: [issue, agent, context]
---

# Plan frontier/anchor hides an OPEN root whose children are all done

## Resolution (2026-07-06)

Fixed in `my.plan.internal/rules`: the `ready` rule gained a second clause —
an `:open`, unblocked, NON-leaf whose subtree carries no `open-work` (all
descendants terminal) is now READY (its remaining action is verify-and-close).
The original leaf clause is untouched, so genuinely-incomplete subtrees still
frontier their open leaves, not the parent (`(not (open-work ?t))` is false
while any descendant is unfinished). Such a drained root now surfaces in
`ready?`/`ready-leaves`/`anchor` and renders in the plan block's open-frontier
band ("N of N steps done"). Live-verified on a fresh conn; covered by
`open-root-with-all-done-children-is-ready-to-close` in `test/my/plan_test.cljs`.

Found 2026-07-06 in the same live drive (cluster `mad-drive`, root's own
plan `rya-2607061944`). MEDIUM severity. Same "plan renders incompletely"
family as [[findings-renders-open-plan-as-fact]].

## What happens

`my.plan.internal`'s `ready`/`frontier` rule requires `(leaf ?t)`
(`src/my/plan/internal.cljs:43`). So a plan **root** that still carries
`:my.plan/status :open` but whose children are all `:done` appears in
**neither** band: not in "done" (it isn't), not in the open frontier (it
isn't a leaf). The one item that actually needs closing becomes invisible.

Observed: root's plan sat silently `:open` across two full delegation
cycles; root only discovered it by running `my.plan/tree` directly, which
contributed to its mid-turn "take stock" confusion.

## Fix direction

The frontier should surface an open non-leaf whose children are all
terminal — it is precisely the node whose remaining action is "verify and
close." Fix the frontier/anchor derivation so an all-children-done-but-open
parent renders as actionable (ready-to-close), rather than requiring
`(leaf ?t)`.
