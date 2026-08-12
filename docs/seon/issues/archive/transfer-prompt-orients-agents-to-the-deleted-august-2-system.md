---
type: issue
status: resolved
severity: blocker
tags: [issue, documentation, agent]
---

# Replace the transfer prompt's deleted August 2 current-state handoff

## Problem

`docs/TRANSFER_PROMPT.md` is the first orientation named by the root and local
runbooks, but it tells new agents to use deleted source trees, treats the fast
test tier as the full suite, and presents an August 2 handoff as current. The
handoff asserts the opposite of several 2026-08-05 landings.

## Evidence

- `docs/TRANSFER_PROMPT.md:35-43,133-146` directs archaeology through the
  absent `src-old/` directory. Root `AGENTS.md:250-254` says the old trees were
  deleted and must be quarried with `git show`/`git log`.
- `docs/TRANSFER_PROMPT.md:161-170` says bare `bin/test` is the full suite.
  `docs/conventions.md:492-505` correctly defines bare as the fast non-long tier
  and `bin/test --full` as the complete checkpoint.
- `docs/TRANSFER_PROMPT.md:201-224` calls the August 2 addendum and rulings the
  current charter and describes one shared SCI context plus session restore.
  The current edge at
  `docs/prds/sci-execution-runtime/plan/unsettled.md:19-60` records per-turn
  forks, `:seon.def/*`, and deletion of the old session-image path; live source
  is `src/seon/sci/eval.clj:1309-1367`.
- `docs/TRANSFER_PROMPT.md:247-249` says one monolithic schema resource replaced
  the family directory. Current `src/seon/schema/edn.clj:1-15,49-51` loads the
  directory-backed `resources/seon/schemas/` population.
- `docs/TRANSFER_PROMPT.md:299-312` names `store/transact!` and says
  `seon.effect` does not exist. Current `src/seon/db.clj:1-14` owns database
  writes and `src/seon/effect.clj:1-12` is the single system-side capability
  request owner.
- `docs/conventions.md:13-15,621-623` repeats the deleted checkout-directory
  claim, even though its open-map, database, and test guidance otherwise
  matches current source.

## Owner

`docs/TRANSFER_PROMPT.md` and the two obsolete quarry sentences in
`docs/conventions.md`, aligned to root `AGENTS.md` and the current working edge.

## Acceptance

- Orientation starts from the current working edge and current ruling batches;
  it contains no dated “current state” snapshot that silently ages.
- Quarry instructions use Git history, and test commands distinguish fast from
  full.
- The runtime synopsis names the program-only base, per-turn fork, facts for the agent's defs,
  split schema population, `seon.db`, and `seon.effect` accurately.
- Historical lessons remain clearly historical and retain legitimate old-name
  referents.

## Resolution

Resolved by `4648721e3`. The named current-state handoff now describes the
post-rename runtime, landed the agent's defs, completed four-lane wave, pending green
bare gate, and next grader wave. The two stale quarry statements in
`docs/conventions.md` now use Git history. Both changed Markdown files passed
`seon.dev.markdown/validate-file` and `git diff --check` before the path-limited
commit; stable orientation and historical lessons were left intact.
