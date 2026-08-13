---
type: issue
status: open
severity: friction
tags: [issue, database, runtime, wave/my-branch]
---

# `seon.db` has no branch or commit reads

## Problem

Ruling #41 makes `seon.db` the one database namespace: every first-party core
data read and write goes through it, and direct `datahike.api` calls survive
only inside `seon.db`, the store/registry branch-custody owners, and the
system-side listeners. `seon.db` today owns the temporal verbs over ONE
database value (`db`, `as-of`, `since`, `history`, `commit-id`,
`committed-value-identity`) but owns nothing that names a BRANCH or a COMMIT.

So the outer-scoped capability the branch verbs need — root enumerating the
store's branches and obtaining any branch's database value (head or pinned) —
has no `seon.db` surface at all. Every caller must reach past it to
`datahike.api/branches`, `datahike.api/branch-as-db`,
`datahike.api/commit-as-db`, and `datahike.api/parent-commit-ids`, or to
`datahike.versioning/branch-history`. That is exactly the second-path shape
ruling #41 exists to prevent, and it blocks the `my.branch` verb wave (W-C of
the [agent's defs and checkout PRD](../../prds/sci-execution-runtime/plan/agent-desk-and-checkout-prd-2026-08-05.md)),
whose `log`, `diff`, and `status` verbs are all cross-branch reads.

## Evidence

- `src/seon/db.clj:547-560,887-926` — the complete temporal surface: `db`,
  `history`, `as-of`, `since`. Each takes a database VALUE or elides to the
  current one. None takes a branch keyword or a commit id.
- `src/seon/db.clj:857-885` — `commit-id` and `committed-value-identity` read
  a commit id OUT of a value; nothing reads a value FROM a commit id.
- `reference-code/datahike/src/datahike/versioning.cljc:182-189` (`branches`),
  `:499-510` (`branch-as-db`), `:469-488` (`commit-as-db`), and `:463-467`
  (`parent-commit-ids`) are the dependency's own answers, all exported through
  `datahike.api` (`reference-code/datahike/src/datahike/api/specification.cljc:916,1029,1057`).
  `branch-as-db` and `commit-as-db` accept a connection, a database value, OR a
  raw store, so root's single main connection is sufficient — no second
  connection to the foreign branch is needed.
- `reference-code/datahike/src/datahike/versioning.cljc:191-210` —
  `branch-history` is the exception: it is NOT in the api specification, it
  reads the branch from `(:config @conn)` so it only walks the branch its
  connection is ATTACHED to, and it always returns a core.async channel even
  in the synchronous default. Root holding only the main connection therefore
  cannot use it for a foreign branch.
- Measured workaround, `tmp/env-probes/env_probes/branch_verbs.clj`
  (`store-log`): walking `branch-as-db` → `parent-commit-ids` → `commit-as-db`
  from the MAIN connection reproduces the same four-commit history for a
  foreign branch that the attached `branch-history` reports, and obtaining a
  foreign branch's head value costs a median 0.219 ms
  ([design report](../../prds/sci-execution-runtime/research/branch-verbs-design-2026-08-07.md)).
  Every caller currently has to write that loop itself.

## Owner

`src/seon/db.clj`, alongside the existing temporal verbs. The branch
LIFECYCLE owner does not move: `seon.cluster.registry` keeps `branch!`,
`retire-branch!`, and `collect!` (`src/seon/cluster/registry.clj:160-294`), and
`seon.cluster.store/open-branch!` keeps connection custody
(`src/seon/cluster/store.clj:375-406`). What is missing is READS.

## Acceptance criteria

- `seon.db` owns the branch/commit reads with Datahike's own names and
  Datahike's own two interfaces (positional and argument map), returning flat
  `:seon.error` values on failure and SCI-admit-clean results: the branch
  roster, a branch's database value, a commit's database value, and a commit's
  parents.
- Root obtains any branch's head or pinned database value through `seon.db`
  alone, holding only the process root's main store connection.
- The commit walk that `branch-history` cannot serve for a foreign branch has
  ONE owner in `seon.db` rather than being re-derived per caller; whether that
  owner also supersedes `branch-history` for the attached case is the
  implementer's call, but two walkers must not survive.
- No new direct `datahike.api` branch/commit read call sites appear outside
  `seon.db` and the named custody owners.
