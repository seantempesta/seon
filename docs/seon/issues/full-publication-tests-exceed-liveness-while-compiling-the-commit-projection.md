---
type: issue
status: open
severity: friction
tags: [issue, test-fixture, publication, schema, performance]
---

# Full publication exceeds the test liveness bound at commit projection acquisition

On 2026-09-17, Stage 1's plain fast run in the detached worktree at
`f77fa320f` with its owned selector/cache/publication overlay exited 124.
The reporter observed 320 seconds without progress after beginning
`seon.cluster.source-test/stale-incremental-upsert-preserves-the-newer-publication`.
No complete suite tally exists. The test performs two complete publications;
it carries no `:seon.test/long-ms`, unlike several other complete-publication
tests in the same namespace. This observation alone does not justify changing
the bound or establish that compilation is redundant.

The virtual-thread-aware dump records the main thread in Malli schema
compilation reached through `seon.schema/derive-projection-from-database`,
`seon.db/carry-derived-projection`, `seon.cluster.source/database`, and
`seon.cluster.source/publish!`. No deadlocked thread ids were reported.
The owned run's earlier dump showed schema-shape encoding; the work had moved.
Neither timing nor these samples establish a projection-cache root cause.

Evidence is in `tmp/test-system-stage1-inputs/fast-8.log`,
`tmp/test-system-stage1-inputs/fast-8-all-threads.json`, and the retained
watchdog logs under `tmp/test-system-stage1-inputs/liveness-8/`.
Reproduce through the canonical armed harness:

```sh
timeout 2400 bin/test-fast seon.test.selection-test seon.fn-test seon.cluster.source-test
```

The database and schema files were concurrently held, so this lane changed
neither their implementation nor test liveness overrides. Verify the actual
publication cost and its repeated phases before choosing between fixing
redundant work and declaring the integration test's intended duration.
Acceptance is an armed complete run with a declared bound and a positive
publication verdict, not a larger silent wait.

## Slice 1 observation — 2026-09-22 redesign assignment

The armed `--paths` fast snapshot at `f08558cb5` ran
`seon.shell.jvm-test/an-evaluations-deadline-reaps-the-child-it-admitted`
in 177.3 s (2026-09-20T18:26:17.187Z–18:29:14.504Z, PID 41038).
The test passed. A sample at process elapsed 947.15 s had the main thread in
`seon.issue/index-tx`, reached during its fixture's complete publication.
`seon.test-support/populate-published-root!` publishes afresh when
`seon.test.published-base` is absent (`test/seon/test_support.clj:102–129`);
that is the fast harness's declared behavior. The shell tests' physical
blob/process observations remain intact. Their metadata now declares the
publication work and its bound; no fixture cache or second publication path
is added. Fixture implementation belongs to the concurrently assigned
bridge lane and is unchanged by this slice.
