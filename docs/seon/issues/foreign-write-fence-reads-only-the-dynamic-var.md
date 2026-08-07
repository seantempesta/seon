---
type: issue
status: open
severity: blocker
tags: [issue, database, runtime]
---

# The foreign-write custody fence reads only `seon.db/*conn*`

## Problem

`seon.db/transact!` is the one place that refuses a write to a connection
outside the calling agent's cluster. Its entire test is
`(when (some? *conn*) ...)` — the fence exists only while the dynamic var is
bound, and its comparison operand is that var.

The [seon.env PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)
deletes `seon.db/*conn*` in Phase 3 ("Deletion list"). Deleting the var
without simultaneously re-rooting this fence on the environment's
`:seon.db/connection` does not produce a compile error and does not fail a
test that asserts the ALLOWED path: the guard clause simply becomes
permanently false and every foreign-branch write is admitted. The failure is
silent, it is a custody failure rather than a crash, and it is exactly the
shape the audited defect had (a fallback that "happens to be right" with one
cluster).

Two secondary properties of the current fence should be decided
deliberately rather than inherited:

- it fences WRITES only, and reads over an explicit foreign database value are
  deliberately open (verified — see evidence). That is the intended shape for
  root's cross-branch reads and should be stated in the surviving owner's
  docstring rather than left implicit;
- an UNBOUND caller (no `*conn*`) may write to any live connection. Under the
  environment model "unbound" becomes "no environment supplied", which the
  PRD's construction-time refusal is supposed to make unrepresentable for
  running code. The conversion must not turn "system caller" into a bypass
  that agent-reachable code can enter.

## Evidence

- `src/seon/db.clj:163-174` — `foreign-connection-error`, the whole fence:
  `(when (some? *conn*) (let [ambient-connection-id (connection-id *conn*) ...`.
- `src/seon/db.clj:1186` — its ONLY call site,
  `(or (foreign-connection-error connection) (transact-call connection transaction))`
  in the two-argument `transact!`.
- `src/seon/db.clj:65-67` — `*conn*` is the dynamic var slated for deletion.
- Live probe `tmp/env-probes/env_probes/branch_verbs.clj`
  (`:probe/iv-custody-fence`, all assertions passing): with `*conn*` bound to
  branch A's connection, a write to branch B's connection returns
  `{:seon.error/kind :seon.db/foreign-connection}` carrying both
  `[store-id branch]` connection ids and leaves no datom on branch B, while a
  read of branch B's database value succeeds in the same binding; with `*conn*`
  unbound the identical write COMMITS. Full verdict and timings in the
  [branch verbs design report](../../prds/sci-execution-runtime/research/branch-verbs-design-2026-08-07.md).
- `reference-code/datahike/src/datahike/store.cljc:44-55` — `connection-id`,
  the `[store-id branch]` identity the fence compares. Two branches of one
  physical store differ only in the branch keyword, which is why the fence is
  the only thing separating two clusters' writers.

## Owner

`seon.db` (`src/seon/db.clj:163-174,1163-1187`), converted as part of the
seon.env Phase 3 sweep that deletes `*conn*`.

## Acceptance criteria

- The fence's custody operand comes from the supplied environment
  (`:seon.db/connection`), not from a dynamic var, and an absent environment
  is a refusal rather than an admission.
- One class regression, not a per-call-site test: a write through an explicit
  connection whose `[store-id branch]` identity differs from the environment's
  returns a flat `:seon.db/foreign-connection` value and commits nothing, and
  the same test asserts that a READ of that other branch's database value
  still succeeds. The regression must be written so that deleting the fence
  makes it fail.
- The surviving `transact!` docstring states the two settled properties:
  foreign writes refused, foreign reads open.
