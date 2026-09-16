---
type: research
status: reviewed
created: 2026-09-17
tags: [review, orchestrator, collector, gc, S8]
---

# Orchestrator review — `ba2986d72` (collector completeness, S8 items 1 and 4)

Read: the landing note, `src/seon/operator.clj` (+334/−) hunks in full,
`src/seon/cluster/registry.clj` hunk headers, the schema additions in
`resources/seon/schemas/seon.operator.collect.edn` and
`seon.cluster.registry.edn`, and the four test files' stat.

**Accepted.**
- `root-verification` replaces the `and`-conjoined criterion: every roster
  branch reopens at its recorded commit and every referenced digest the store
  HELD BEFORE the sweep reads physically; evaluated unconditionally; the
  result names `:seon.operator.collect/unverified-branch` or
  `unverified-digest`; the message says which. `verification-pass-swept` is
  reported and decides nothing, with the docstring explaining why it is
  non-zero under live writers and that correctness is Datahike's safe point.
- The differential (`held-before` = the konserve key set before the sweep)
  is the right answer to the finding it made: 32 of 33 referenced digests on
  a fresh cluster were `:seon.db/read-result-digest` content digests that
  were never konserve keys. Counted as `unstored-digests`, never a refusal;
  the missing declaration is filed
  (`blob-roots-are-derived-from-digest-shape-not-from-a-declared-fact`).
- The dry run now also refuses a store whose roots do not reopen, which is
  correct: reporting candidates for a store that cannot answer for its roots
  would be absence-as-health.
- The real path returns the dry run's inventory from the same sweep
  (`collect-and-inventory!`, the `:konserve.gc/batch-issued` callback
  composed with a caller's own); `inventory-facts` projects it with absent,
  never nil.
- `{:dry-run? true}` is refused by name; documented keys derive from the
  declared request schema; maps stay open otherwise.
- Scratch-root proof: dry run and real collection report the same four
  numbers; the refused misspelling swept nothing (765 keys before and after).

**Rejected.** Nothing. **Noted for S8's next slice:** the cutoff is still
`(java.util.Date.)` at both call sites; the derived cutoff (oldest commit a
live fact names) is the next S8 commit, and today's write-volume research
shows why it matters (the now-cutoff reclaimed the cluster's own basis
commit).

**Gate requested:** batch 101 = platform, then the namespaces in
`tmp/orchestrator/gate-requests/collector.txt`.

## Addendum — `0f23d6fb6` (2026-09-17 18:10Z)

Batch 101 failed at load on `with-redefs` binding `#'` forms. The fix uses
`with-redefs-fn` over a Var map. Loading the namespace then exposed a real
§2.1 defect in the slice: `documented-request-keys` read the whole authored
schema population at call time, coupling every collection to every other
lane's resource placement; it is now a value in `seon.operator`, kept honest
by a drift test against the declared request schema (derive-or-die: enforced
by a checker). Five namespaces load through the runner's loader; 16 tests
green armed in process. Approved; batch 101 reruns.
