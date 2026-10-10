---
type: review
date: 2026-10-10
status: changes-requested
---

# Review of d9eb15c05

**Changes requested.** Reviewed `seon.cluster.source/merge-base-survives-collection`, the commit, and
`docs/seon/specs/merge.md`. Datahike checkout and gitlink both identify
`e65599de5eb393ca842304b6487453616214ea26`. The two counterexamples below follow from source; they were not
reproduced through MCP (default had no live JVM). The passing checks below cover narrower cases.

1. **P1 — a nested fork's creation point need not be common to the target.**
   `seon.cluster.registry/fork!` records the immediate parent's current t.
   `seon.cluster.source/branch-base-t` uses that t when the target has no entry for the child, without checking
   ancestry. Example: main has `f=0`; A forks and changes `f=1`; B forks A and changes an independent `g`.
   Comparing B to main reconstructs `f=1` as the base. `seon.program/changed-identities` searches only after B's
   fork, so A's inherited change to `f` disappears from the diff and merge. The true common ancestor has `f=0`.
   An inherited deletion disappears the same way. This is reachable through `my.agent/create-branch!`, which
   uses its supplied connection, and through `seon.cluster.agent/acquire-context!`'s explicit source commit.
   **Fix:** record the intended target and a proven common base on the source's lineage, inheriting the
   parent-to-target base for a nested fork. Advance it only for the corresponding target. Until that relationship
   is represented, refuse unsupported source/target ancestry explicitly. Add the A→B→main case to comparison
   coverage, with inherited modification and deletion.

2. **P1 — branch-name reuse can silently apply another incarnation's t.**
   `seon.cluster.registry/retire-branch!` removes the roster entry, not the target's fork entity.
   `seon.cluster.source/branch-base-t` unconditionally prefers the target's entry; its only identity check is
   `branch-head-t < stored-t`. Transaction numbers cannot establish branch identity. Example (t values shown
   relative to a common starting point): old A is merged at its t=20 while main reaches only t=5; A retires;
   new A forks main and eventually reaches t=21. The old t=20 now passes the check and suppresses all new A
   changes through t=20. If main already advanced past 20 before reuse, the stale record passes immediately
   and reads main's inherited history at an unrelated point. The new refusal test covers only t greater than
   the current head, not either accepted wrong-history case.
   **Fix:** mint a fork identity and carry it with every advanced base; match that identity before using the
   target's t. A mismatch must use a separately proven new-fork base or refuse, regardless of t ordering.
   Test reuse both below and above the stale t, including the transition from refusal to silent acceptance.

## Semantics and remaining checks

Datahike `datahike.versioning/branch!` shares the selected database roots and max-tx;
`datahike.db.transaction/current-tx` increments that branch's max-tx. Equal t values after divergence can name
different facts. `datahike.api.impl/as-of` filters that database's own temporal indexes, inclusively;
`datahike.api.impl/history` exposes its assertions and retractions. Neither follows merge parents.
`datahike.versioning/merge!` and `datahike.writing/merge-writer!` transact caller-supplied data on the target
and record parents; they do not import the source's transaction history.

| Case | Verdict |
|---|---|
| Fresh direct fork of the target | Correct data snapshot with retained history. An AsOfDB is not the original commit identity or historical schema object. |
| Full merge back to that target; repeated full merges | Correct while source incarnation and lineage remain fixed: the target stores the **source** value's t, then reads it on that same source. No comparison with target-head t occurs. |
| Fork of another agent's branch | Incorrect in the counterexample above; the docstring's “whatever the target is” claim is false. |
| Main merged into the branch | No dedicated synchronization operation exists. The generic prepare path cannot normally use main as source: `ensure-cluster!` records a cluster fork, not its own `:seon.branch/fork`, so comparison refuses. A raw Datahike merge would not make the present base algorithm general. |
| Reused name | Refusal catches only some cases; finding 2 compares numbers belonging to different incarnations. |
| Concurrent distinct direct branches | Their base entities have distinct names. The writer serializes the transactions; disjoint declaration changes can both land, while `declarations-held-tx` rejects changed expected digests. No cross-branch t ordering is required. |

**Atomicity holds:** `seon.cluster.source/accept-merge!` places the guard, advanced base and replacements in
one `seon.db/transact!`. Datahike's transaction validation and writer error path discard the whole transaction
on refusal. A refused merge leaves the previous base intact; an accepted merge can still atomically store a
semantically wrong base. Selection advances past the entire source value even when only some identities merge;
unselected changes are consequently considered settled. The old parent walk had that same consequence because
the entire source commit was recorded as a parent; this is not a new regression.

**Deletion/GC hold for a correct base:** `seon.program/changed-identities` uses history plus since and resolves
deleted identities from history; the integer t avoids AsOfDB's origin max-tx. `datahike.gc/reachable-in-branch`
marks the head's temporal indexes even when it stops following old parents. No old merge-base walk or callers
remain in src/test. Other commit-based publication/write-back reads remain; they are not this deleted walk.

**Adoption remains limited:** the new fork entity is correctly in `:seon.data`, and fresh publication/boot passed.
Only new work branches get its facts. Existing branches, including those motivating the incident, receive no
backfill and now refuse for missing fork evidence. A schema adoption alone cannot recover that evidence.
Before claiming existing-store recovery, backfill only from provable retained ancestry and explicitly reconcile
branches whose evidence is already gone. No old-store upgrade was verified here.

**Cost:** the reported 35 ms versus 5 ms digest read remains filed as
`seon.cluster.source/as-of-merge-base-digest-reads-are-slower`; not remeasured. Temporal filtering explains
additional work, not permission to ignore the regression.

## Verification

Run sequentially on archived HEAD `d9eb15c05`, with no default writes or lifecycle commands:

- `bin/seon check --at HEAD --test seon.cluster.registry-test/an-idle-branch-keeps-the-commit-it-forked-from-through-collection`
  — run `f74100b71f3d`: 2 pass, 0 fail/error, 4,887 ms; total 93,988 ms, ready 49,519 ms, adopt 221 ms.
  Seed was empty, not an existing-store upgrade.
- `bin/seon check --at HEAD --test seon.cluster.merge-test/a-candidate-merges-only-its-tested-green-program`
  — run `8fc65844bec8`: 13 pass, 0 fail/error, 7,707 ms; total 74,584 ms, ready 5,390 ms, adopt 237 ms.
  Seed was a same-commit template. These checks are seconds-long; no CPU/allocation profile was supplied.
- `clj-kondo --lint src script test --cache false --parallel` — 10,644 ms, 0 errors, 1,462 warnings.

Foreign boundary: default unavailable at initial status/MCP check; unrelated working-tree changes were untouched.
The reviewed commit reports six pre-existing reds, not rerun: `seon.cluster.source-test/flat-scratch-write-refusal-retires-the-candidate`,
`seon.cluster.source-test/incremental-publication-does-not-change-an-existing-cluster`,
`seon.cluster.source-test/publication-hands-its-commit-projection-unstamped`,
`seon.cluster.write-back-test/a-merge-writes-each-change-into-its-own-file`,
`seon.cluster.write-back-test/a-require-added-at-the-repl-merges-and-writes-back-its-ns-form`, and
`seon.cluster.write-back-test/an-agent-replacement-merges-and-writes-back-its-exact-span`.
This review changes documentation only: zero source/test lines and no new test obligations.
