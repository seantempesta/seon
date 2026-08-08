---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, lifecycle, database, testing]
---

# Refork held a store across the arm that released it

## Problem

Two defects in one operation, both hidden behind an error value nobody read.

1. `seon.cluster/refork!` was a SECOND path to the operator's refork. It
   GUESSED its two required operator inputs instead of receiving them: the
   managed root by walking two parents up from the cluster root, the
   repository root from a system property. For any root not shaped like the
   operator's `<managed>/data/clusters`, the guess named an unrelated
   directory, `cleanup-cluster-under-lock!` found no exact root claim there,
   and the whole refork returned a flat error value. Its declared
   `:malli/schema` promised `:seon.cluster.registry/branch-result` — no error
   arm — so the lie was never caught, and the caller's
   `(:seon.cluster/created? result)` read `nil` from the error map in silence.

2. `refork-under-lock!` acquired ONE operation store and held it across both
   arms. Its first arm, `cleanup-cluster-under-lock!`, stops the live
   instance, and the last instance out releases the process-root store with
   its flock (`seon.cluster/release-root-store!`). The branch-creation arm
   then ran against a released connection. `bin/seon init NAME --force` hits
   this in production whenever the target cluster is the only instance in the
   JVM — the old branch is destroyed and the fork then fails, which is the
   worst possible place to stop.

Defect 2 was invisible for as long as defect 1 existed: the only caller of the
composed verb never reached the second arm.

## Evidence

`bin/test seon.cluster.boot-test`, both failures pre-existing at `4f5b8c5ac`
(worktree baseline recorded in
[env-phase1-w1-notes-2026-08-07.md](../../../prds/sci-execution-runtime/research/env-phase1-w1-notes-2026-08-07.md)):

```text
FAIL explicit-refork-destroys-the-old-branch-and-forks-current-source
expected: (:seon.cluster/created? result)     actual: nil
expected: (nil? (db/q … "history-refork-destroys" …))
actual: (not (nil? 24500))          ; the destroyed branch's data survived
```

A direct probe of `cluster/refork!` on a live instance returned the guess in
its own error data:

```clojure
#:seon.error{:kind :seon.operator/cluster-cleanup-incomplete
             :message "The cluster has no exact external root claim."
             :data {:seon.operator.claim/root "/Users/sean/src/seon/tmp"
                    :seon.boot/cluster-name "probe"}}
```

With the guess removed and the regression driving the composed operator verb
in the operator's own layout, defect 2 surfaced immediately and by name:

```text
the composed refork must create the branch:
#:seon.error{:kind :seon.operator/failed
             :message "Connection has been released."
             :data {:type :connection-has-been-released}}
```

## Owner

`src/seon/operator.clj` owns the refork composition and its under-lock arms.
`src/seon/cluster.clj` owns cluster lifecycle and must not carry a second
operator entry point.

## Acceptance

- The refork operation exists exactly once, and every required operator input
  is supplied by the caller rather than derived from a path shape.
- No arm of a destructive composition holds a resource that an earlier arm can
  invalidate.
- A forced refork destroys the old branch's data and forks the published
  commit, proven on a real store by the COMPOSED verb.

## Resolution

Resolved by the commit containing this note.

- `seon.cluster/refork!` is DELETED. `seon.operator/refork!` is the one owner;
  `bin/seon init NAME --force` already supplied the repository root, the
  managed root, and the published commit explicitly
  (`script/seon/fresh_operator.clj:1941`), so nothing was ported.
- `refork-under-lock!` no longer holds a store across cleanup. Each arm
  acquires its own: cleanup validates the supplied store AFTER its stop, and
  the fork acquires fresh from the managed root afterwards. This reverses the
  "retains one operation-store interval across cleanup and branch creation"
  choice recorded in
  [operator-refork-reacquired-lifecycle-lock.md](operator-refork-reacquired-lifecycle-lock.md);
  that note's lock invariant is untouched — public entry points still acquire
  the lifecycle lock once and internal arms are still `-under-lock!`.
- `explicit-refork-destroys-the-old-branch-and-forks-current-source` is the
  class regression, rewritten to run the composed verb on a real store in the
  operator's own layout with a real root claim, and to print the returned
  value when a refusal is what came back. `bin/test seon.cluster.boot-test`:
  27 tests, 138 assertions, 0 failures, 0 errors, three consecutive runs.
- Live proof in an isolated operator root (`bin/seon --root
  tmp/refork-operator`): started cluster `rk`, committed
  `:seon.cluster.message/id "live-refork-marker"` (eid 24544, t 536870980),
  ran `bin/seon --root tmp/refork-operator init rk --force`
  (`● rk reforked :cluster-rk from :current-src commit 6a768a09-…`), restarted,
  and read back `{:marker-survived nil, :functions 2638, :published #uuid
  "6a768a09-cda2-5c0f-abdb-f322a1f80929"}`. The root was torn down afterwards.
