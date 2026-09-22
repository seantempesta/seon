---
type: landing
status: in progress
created: 2026-09-22
---

# A2 storage retention

`default` remains read-only, PID 38968, start 2026-09-22T12:04:10.581Z.
Hook publication remains paused. No edited definition has been reloaded there.
Initial CLI and MCP status both answered; 15 errored receipts were reported.

## f4 and c10

Konserve `8cd9144f4338c1fbdb5e531bc996189d71a86fc5`, pushed to personal
`origin/main`, replaces pin `07377c27c8288b7484f0aa7b82e8158b415985be`.
`src/konserve/gc.cljc:8` selects once from logical key metadata, partitioned by
the supplied batch size; dry-run returns those keys without invoking deletion.
Focused fork task `clojure -M:test -n konserve.gc-callback-test`: 3 tests,
18 assertions, zero failures/errors. Empty selection, whitelist, timestamp,
changed candidate set, preservation and equality with actual sweep covered.

Datahike pinned gitlink at entry: `6dd49e5e`; `src/datahike/gc.cljc:22`
marks every branch head and follows parent commits while their timestamps
exceed remove-before. This function is in **gc.cljc**, not versioning.cljc.
`gc-storage!` passes sweep options through at line 166, under the existing
reachability permit and separate writer safe point. Work scales with retained
commit/index nodes and logical keys; no cleanup scheduler or second mark added.

Seon deletes the exception/token dry run and physical directory inventory.
The report carries observed logical key count, selected candidate keys, branch
heads, duration and swept count. Physical bytes remain the operator footprint.
Logical count is a point-in-time observation during the protected mark; ordinary
writes can still add keys, so it is not a transactional denominator for a later
sweep. Registry error-schema declarations belonging to the concurrent error
lane are left untouched; obsolete private error declarations can be retired by
that owner.

### Scratch owner probe

Exact form: `tmp/a2-storage-retention/c10-probe.clj`; full result:
`tmp/a2-storage-retention/c10-probe.log`. Fresh file store, real
`seon.cluster.store/open-store!`, real `registry/collect!`, epoch cutoff,
no canonical program publication. JVM diagnostic, **not armed integration**.

| observation | logical keys | candidates | swept | ms |
|---|---:|---|---:|---:|
| quiet dry run after sweep | 9 | `#{}` | 0 | 9 |
| after `k/assoc :a2/orphan :present` | 10 | `#{:a2/orphan}` | 0 | 8 |
| actual sweep | 10 | `#{:a2/orphan}` | 1 | 11 |

The orphan reread after dry run returned `:present`. Store release completed;
probe JVM exited zero. HEAD-plus-owned-files worktree:
`tmp/a2-storage-retention-wt`, base `42728bdec`.

### Verification limits and publication clock

Required five-namespace require succeeded on the shared source tree.
`bin/test-fast --paths deps.edn src/seon/cluster/registry.clj
resources/seon/schemas/seon.cluster.registry.edn test/seon/cluster/registry_test.clj
-- seon.cluster.registry-test` snapshot `tmp/test-runs/run.2u1qZr` armed
1,688 contracts, ran 12 tests / 12 assertions, 0 failures / 11 errors,
run `27c4c8f8dbf4`. The shared fixture fails in
`seon.schema/canonical-schema-rows:3516` before the collection test body;
this is the definition-digest boundary, not a GC result. No foreign source edited.

| committed publication script attempt | result | completed clock |
|---|---|---:|
| original init-before-start sequence | no exact-root JVM; no publication | 234 ms |
| start-first sequence on scratch root | static analysis refuses retired `error/properties` at `test/seon/cluster_test.clj:159` | 23,366 ms |

Logs: `tmp/a2-storage-retention-root/start.log`,
`tmp/a2-storage-retention/publication-c10.log`. Scratch PID 11251 exited;
status found no exact-root JVM afterward. No adoption/bytes goal is claimed.
The committed script is corrected to use the installed start-first operator;
its later measurements remain pending a publishable HEAD.
