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

## Retention declaration (proposed; orchestrator ratification pending)

c10 Seon commit: `1cc00a4a5`. Post-commit five-namespace require exits zero;
MCP again observes default alive, same PID, 15 errored receipts.

The optional `:seon.config.db/snapshot-window-ms` dial is absent in ordinary
shipped defaults; `config/development.edn` proposes 0. Implicit collection
refuses missing cluster policies rather than silently retaining every ancestor.
Every configuration row in captured heads must declare a window; the largest
window wins, anchored to the newest captured head timestamp. This immutable
cutoff is supplied to Datahike; it is not a pre-check of Datahike's deletion
selection. Subsequent writes are protected by the existing safe point and the
mark's current branch heads. Idle time alone does not age snapshots out.
Explicit cutoff arities remain available. No adoption hook or cleanup job added.

Exact synthetic file-store diagnostic:
`tmp/a2-storage-retention/retention-probe.clj`; output in the matching `.log`.
It writes 1,000 string values three times with native `:db/noHistory true`,
then uses `registry/retention-cutoff` and `collect!`. This uses the owning JVM
functions and persistent-set storage; it is not full source publication or an
armed canonical fixture proof.

| phase | bytes | logical keys | current datoms | write ms |
|---|---:|---:|---:|---:|
| declared scratch config, before data | 14,668 | 10 | 20 | — |
| write 1 | 510,095 | 11 | 1,021 | 79.344 |
| write 2 | 759,786 | 12 | 1,022 | 42.703 |
| write 3 | 1,009,529 | 13 | 1,023 | 34.823 |
| sweep | 500,313 | 4 | 1,023 | — |

Derived cutoff `#inst "2026-09-22T15:16:45.215Z"`; dry mark 39 ms,
9 candidates, then actual sweep 9. Value attribute current/history counts
both 1,000. Missing policy positively refused with
`{:seon.config/error-key :seon.config.db/snapshot-window-ms,
:seon.config/rule :seon.config/required-absent}`. Probe process exited zero.
The full adoption clock remains blocked at the foreign static-analysis boundary
above, so no proportional-live-datoms publication claim is made.

## f8 / c12

Retention proposal landed `289c9b587`; CLI status afterward still observes
the unchanged default JVM. Datahike f8 is
`cc2b2bc7dbe774ea1bcc7487e8225a0d420d0e17`, pushed to personal `origin/main`.
Simply extending `store-fixed-record-keys` failed for an omitted setting on a
no-history store: `load-config` had already inserted true. Connect now preserves
that omission; shared/opening connections validate explicit fixed-key requests
against the acquired value and release their acquired reference on refusal.

Focused fork task:
`clojure -M:test -m kaocha.runner --focus datahike.test.storage-retention-test
--skip clj-hht --skip specs --skip norm --skip integration --skip kabel`:
2 tests, 22 assertions, zero failures/errors, persistent-set binding.
Initial red (2 tests, 11 assertions, 1 error) exposed the normalization defect;
the owner was corrected. Both true/false stores cover omitted, matching and
conflicting requests, including a live shared connection.

§6.3 proof **fails its waiting premise**: two holders are identical; after one
release the other reads; once shutdown is held behind an explicit channel,
connect returns `:connection-is-being-released`. Releasing the channel completes
the drain within the 2,000 ms bound and reconnect reads successfully. Exact
probe and regression are the committed fork test
`test/datahike/test/storage_retention_test.clj`. Therefore Seon's
`contains?` pre-check and one-owner contract remain. No wait/retry added.

c12 removes `stored-main-keep-history?`, its second konserve open and the
Seon mismatch decision. Reopen omits creation settings; an explicitly requested
history setting is handed to Datahike, whose typed refusal retains `:conflicts`.
The physical store flock and genesis-completion check remain unchanged.

Seon scoped verification: `bin/test-fast --paths src/seon/cluster/store.clj
test/seon/cluster/store_test.clj -- seon.cluster.store-test`, snapshot
`tmp/test-runs/run.CCBrVO`, run `c6471a051c01`: 18 executed, zero reused,
73 assertions, zero failures/errors. This includes the real file-store
no-history reopen and explicit mismatch regression. All test processes exited.
