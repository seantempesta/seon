---
type: research
status: open
created: 2026-09-17
tags: [publication, projection, seon.db]
---

# Publication reports carry the commit's projection

## Evidence and seam

The post-reset platform log recorded the same armed return-contract refusal
for eight `seon.cluster.source-test` tests: `publish!` embedded a flat
`:seon.schema/missing-projection` read error under
`:seon.program/unresolved-report`, so `:seon.source/published` then reported
the absent required `:seon.db/basis-t`. The path was
`seon.cluster.source/database` -> Datahike `commit-as-db` ->
`seon.fn/unresolved-callers` -> three `seon.db/q` reads.

The dependency seam is Datahike's immutable commit database value. Seon's read
seam takes declarations first from that value's metadata
(`src/seon/db.clj:1081-1095` after this change), before considering a handed
projection. A raw `commit-as-db` value carried neither. This violated
AGENTS.md §2.1: the read worked or failed according to dynamic caller state,
not according to the database value handed to it.

`seon.db/carry-derived-projection` now derives one projection from the exact
commit's installed `:seon.schema` and function-contract rows through
`seon.schema/projection-from-database`, then attaches that immutable snapshot
to the value (`src/seon/db.clj:219`). `seon.cluster.source/database` applies
that constructor immediately after `commit-as-db`
(`src/seon/cluster/source.clj:164`). `seon.cluster.registry/reset-cluster!`'s
raw `commit-as-db` remains unchanged: it hands the value directly to
Datahike's `force-branch!` and performs no Seon read.

Publication and incremental upsert now share `unresolved-report!`
(`src/seon/cluster/source.clj:176`). A flat read error becomes the existing
`::publish-readback-failed` refusal naming `seon.fn/unresolved-callers` and
retaining its result. An unreadable report can no longer inhabit a successful
`:seon.source/published` value.

## Why the operator path passed

The reset operator was not proof that the raw commit value was self-contained.
`seon.cluster/refresh-source!` wraps the complete source refresh in
`schema/call-with-projection` using the packaged declaration projection
(`src/seon/cluster.clj:2479-2492`). Before this fix, the raw commit database's
queries therefore fell through to that dynamically handed projection. The
operator log returned basis transaction `536870921` and analyzed count `6386`.
The isolated source tests did not have that binding and exposed the missing
world. This was a caller-dependent success, not an armed-versus-unarmed
contract difference; the platform failure itself was produced by armed
contracts.

## Regression and fast evidence

`test/seon/cluster/source_database_test.clj` uses the canonical database
fixture with its declared commit-bearing fresh-store mode. It calls
`seon.cluster.source/database` and both `seon.db/q` and
`seon.fn/unresolved-callers` on a fresh Java thread, which inherits no Clojure
dynamic projection binding. The test asserts a real integer query result, a
carried projection, and an integer `:seon.db/basis-t`.

Fast results in the isolated HEAD-plus-owned-files worktree:

- `seon.cluster.source-database-test`: 1 test, 3 assertions, green.
- `seon.fn-test`: 60 tests, 420 assertions, green.
- `seon.cluster.source-test`: 17 tests, 85 assertions; 3 failures and 10
  errors on the unmodified held fixture file.

All eight originally named report failures now reach the foreign fixture
boundary instead: their first exception line is
`clojure.lang.ExceptionInfo: :malli.core/invalid-schema` in `malli.core`, for
`stale-incremental-upsert-preserves-the-newer-publication`,
`incremental-publication-does-not-change-an-existing-cluster`,
`incremental-upsert-seals-one-activation-on-the-expected-commit`,
`failed-and-stale-builds-preserve-the-published-head`,
`existing-clusters-remain-on-their-chosen-source-commit`,
`publication-advances-one-branch-and-retires-scratch`,
`incremental-upsert-derives-scalar-safety-from-the-installed-schema`, and
`an-activation-closure-with-empty-member-collections-seals`. None reaches the
old missing-`:seon.db/basis-t` return-contract refusal. The held
`test/seon/cluster/source_test.clj` belongs to
`post-reset-stale-fixtures-2` and was not edited.

The requested path-overlay command was attempted first. The shared tree
refused because the held `source_test.clj` is a changed caller. The clean
worktree then refused because no published program graph exists and named the
orchestrator-only `bin/test --prepare-head-base` dependency. The tallies above
come from plain `bin/test-fast` in that isolated worktree, whose only source
and test changes were this lane's three owned files. Cold and platform gates
remain the orchestrator's proof.

The supported MCP JVM evaluation tools were absent from this session, and
`bin/seon status` reported `default` as a stale advertisement with zero live
clusters. No default process was started, stopped, or reset.

## 2026-09-17 follow-up: the carried registry must be complete

The clean cold gate proved that deriving only the commit's persisted projection
was still not a complete world. Under armed contracts, Malli failed before
`unresolved-report!` entered its body. A diagnostic at that boundary captured
the exact exception data:

```clojure
{:type :malli.core/invalid-schema
 :message :malli.core/invalid-schema
 :data {:schema :seon.db/database-value
        :form :seon.db/database-value}}
```

The partial source stores persisted the source attributes but not the packaged
runtime schema population, so the commit-derived registry could not compile an
armed function's `:cat`. The first section's statement that the commit-derived
projection alone was sufficient is therefore superseded by this section.

Three fixes were weighed, simplest first:

1. **Recommended and implemented:** materialize the commit projection as a
   delta over `seon.schema.edn/packaged-forms`. This guarantees that registered
   runtime schemas needed by loaded code are present while definitions actually
   installed in the commit override that base. Its cost is one packaged
   projection build when an otherwise raw database value first acquires its
   world; it gives up treating an intentionally partial database population as
   sufficient for arbitrary production reads.
2. Carry the publication's already complete projection into `source/database`.
   This avoids rebuilding the packaged base, but makes every caller responsible
   for pairing the right projection with the right commit and leaves the public
   bare-database constructor unsafe to misuse.
3. Change only the source fixtures to publish a complete population. This makes
   this test harness honest, but by itself leaves any other bare commit database
   vulnerable to the same deep Malli failure.

The implementation uses both required halves. `carry-derived-projection`
builds the packaged runtime projection, derives the commit projection, computes
the latter's delta against the runtime base, and materializes their composition
through the existing projection mechanism. No global registry or dynamic
fallback is handed to the value. The source fixture now calls the production
`seon.cluster/populate-source!` seam, so its physical Datahike declarations,
canonical schema rows, program rows, and config facts are complete too. A
declaration-only fixture was falsified when `seon.issue/index!` correctly
reported that no schema row declared `:seon.issue/cites`.

## 2026-09-17 follow-up: stale fixture expectations

The three original fixture failures were stale expectations, not deletion-path
defects:

- An incremental plan for a changed `seon.id` file correctly contains the file,
  namespace, and every function declaration whose byte span can move. The old
  assertion hand-listed only `seon.id/id`; the regression now derives the
  namespace's function identities from the canonical manifest.
- `sci.eval/install-row!` is a post-commit installer. The deletion test handed
  it a database in which the function still existed, so the strict
  `install-delete-mismatch` check at `src/seon/sci/eval.clj:914` was correct.
  The test now retracts the declaration before installation.
- The test row itself names the synthetic function through
  `:seon.fn/references`. The writer correctly refused a function-only
  retraction and identified that exact referrer. The final transaction retracts
  the test referrer and function together; the installer then unmaps the SCI
  Var, and the database contains no tombstone or residual `:core` row.

Completing the fixture also exposed two bookkeeping assumptions. Issue indexing
adds its legitimate transaction between incremental row application and the
single activation seal, so that assertion now states the complete three-write
sequence. A hard-coded ten-second latch wait could expire while the complete
population was indexing. The fixture now publishes its blocked event before
starting that population and awaits it through `test-support/await-event!` and
the declared loud event backstop; the candidate branch and expected head have
already been fixed at that point, so the stale compare-and-set remains the
authority under test. Its complete post-release publication is bounded at five
declared event-backstop intervals and cancels loudly on expiry; one interval is
insufficient for the known complete program indexing and readback sequence.

## 2026-09-17 follow-up: fast evidence

The requested `--paths` invocation remained unavailable in the isolated
worktree because no published program graph exists; only the orchestrator may
run `bin/test --prepare-head-base`. Iteration therefore used plain
`bin/test-fast` in the isolated HEAD worktree containing only this lane's diff.

- Diagnostic run before the fixture corrections: the exact missing schema was
  `:seon.db/database-value` as recorded above.
- Complete source run before the last two bookkeeping corrections: 18 tests,
  166 assertions, 2 failures, 0 errors. All eight named publication regressions
  were green; the failures were the transaction-count and fixed-wait assertions
  described above.
- The required one-JVM run reached all three namespaces: 27 tests, 255
  assertions, 0 failures, 1 error. All four
  `seon.sci.kernel-arm-carriage-test` tests and all five `seon.env-test` tests
  were green; no "A different SCI context is already armed on this thread"
  error remained. Its sole error was the stale-publication event bound loaded
  before the final fixture timing correction.
- A source-only run with that correction reached every named publication
  regression green, then exposed the complete fixture's database-write
  dependency. Two consecutive focused armed retries refused the canonical
  `seon.fn/population` transaction after 30,002 ms and 30,004 ms against
  `:seon.config.db/write-time-limit-ms 30000`. The outcome is explicitly
  unknown, so retrying or raising the bound inside this lane would be dishonest.
  `seon.cluster.source-test` is therefore stopped at this named dependency,
  not claimed green.

The foreign boundary is the shared main worktree's uncommitted `src/seon/db.clj`
edits outside this seam. Verification and edits were isolated from them in
`tmp/publication-report-projection-followup-wt`; no other lane's file or session
was operated.
