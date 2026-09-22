---
type: landing
status: partial landing; comparator stop gate and publication proof pending
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

## Step 4 census and ownership boundary

Exact forms and complete MCP envelopes: `tmp/a2-storage-retention/live-census.json`.
Explicit root `/Users/sean/src/seon`, cluster `default`, mode JVM, read-only;
custody `(seon.cluster.boot/connection "default")`. Grouping includes entity,
value, transaction and added flag, avoiding the distinct-value aggregate error.
At basis 536871142 the largest history/current differences are:

| attribute | current | history |
|---|---:|---:|
| `:seon.cluster.eval/read-evidence` | 11,628 | 57,686 |
| `:datahike.read/dependency-plan` | 11,628 | 57,686 |
| `:datahike.read/revision` | 11,628 | 57,686 |
| `:seon.db/source-argument-position` | 11,628 | 57,686 |
| `:seon.db/read-result-digest` | 11,376 | 56,194 |
| `:seon.db/read-request` | 11,376 | 56,194 |
| `:seon.fn/call-arities` | 63,437 | 64,478 |
| `:seon.fn/calls` | 62,152 | 63,089 |

Whole-history aggregation took 2,151 ms; its work is proportional to the current
and temporal datom population, not suitable for an adoption hot path. The first
attempt counted distinct values and is superseded by this complete-tuple census.
At basis 536871143, occurrence count/first-at/last-at/data-blob and instrument
actual each have **0 current and 0 history datoms**, occurrence IDs `[]`, timestamp
pairs `[]` (4 ms). The historical 22,537-occurrence incident was before this reset;
no present timestamps can honestly be reported for it.

The named fault churn declarations are in the protected error-schema family.
A user clarification is pending; none was edited. Exact proposed reset change:
`:count [:int {:min 1 :seon.db/no-history? true}]` and
`:last-at [:inst {:seon.db/no-history? true}]` in
`resources/seon/schemas/seon.error.occurrence.edn`. `first-at` is stable and does
not churn. The current top rows belong to read-evidence/compaction and several
are explicitly deleted in c1; this lane does not repair the retiring mechanism.
**RESET NEEDED** when the fault attribute declarations land; not applied here.

Ownership options (simplest first):
1. **Recommended:** error-schema owner/orchestrator lands the two exact properties
   in its slice. Guarantee: no overlapping file edits. Cost: coordinated reset;
   gives up completing that schema slice in this lane.
2. Release just these two declarations to this lane. Guarantee: same no-history
   behavior and test; cost: explicit ownership coordination; gives up exclusive
   error-schema ownership for those spans.
3. Defer no-history until the error schema cut completes. Guarantee: old temporal
   semantics retained; cost: continued temporal growth; gives up the churn target.

## §6.2 comparator proof — STOP; f1 and c2 not landed

c12 Seon commit: `907b231fe`. Exact form and complete results:
`tmp/a2-storage-retention/comparator-probe.clj` and `.log`. A fresh file fixture
uses `:datahike.index/persistent-set`, `:keep-history? true`, and
`:schema-flexibility :read` to reach the native-value storage path without
prematurely exposing `:db.type/any` in the schema language. This is a dependency
storage diagnostic, not proof of public any-type schema admission.

| exact operation | result | ms |
|---|---|---:|
| `(datom/compare-value {:a 1} {:a 2})` | `ClassCastException`, PersistentArrayMap is not Comparable | 0.119 |
| `(datom/cmp-nil {:a 1} {:a 2})` | **0**, unequal maps equated | 0.228 |
| `(d/transact c [[:db/add 1 :a2/value {:a 1}]])` | committed, tx 536870913 | 64.362 |
| replace with `{:a 2}` | refused, ClassCastException in `cmp-temporal-datoms-eavt-quick:350` → persistent-set `temporal-upsert:164` | 2.088 |
| one tx adds `{:a 3}`, then `{:a 4}` | same comparator refusal | 1.823 |
| history for `(1, :a2/value)` | `[[{:a 1} true]]` | 0.562 |
| as-of first committed tx | `{:a2/value {:a 1}}` | 3.034 |
| exact `[:db/retract 1 :a2/value {:a 1}]` | committed, tx 536870914 | 52.059 |
| release, reconnect, pull | nil (exact retraction survived) | 0.570 |
| history after reconnect | `[[{:a 1} true] [{:a 1} false]]` | 0.292 |

The current code has neither a total comparison for maps nor a safe fallback:
class-name fallback collapses distinct same-class values. A map-only sort, hash
order, or printed-order patch does not establish deterministic equality-consistent
ordering for the unrestricted `any?` language (nested mixed collections,
equality across concrete collection implementations, hash collisions, and
non-Comparable host values). No small sound correction has been demonstrated;
this is the explicitly required §6.2 design boundary. **The codec, in-writer decode,
population plumbing and wake ref resolution are unchanged.** c2 RESET is not
installed or needed by these commits; it remains a future reset item.

Exactly three options, simplest first:
1. **Retain the codec (recommended at this boundary).** Guarantee: current admitted
   shapes and temporal behavior continue. Cost: existing encode/decode and
   population work remain; gives up c2's deletion and A1-12's immediate prerequisite.
2. Define and prove native-value ordering in the fork. Guarantee: native history,
   retraction and reconnect only after equality/order/serialization properties
   pass for an explicitly bounded value language. Cost: a separately scoped
   comparator/admission design plus property and persistent-index tests; gives up
   treating unrestricted `any?` as an already-supported storage guarantee.
3. Redesign individually justified mixed declarations. Guarantee: each accepted
   smaller language has existing native ordering. Cost: per-declaration producer,
   consumer and schema changes/reset; gives up some currently admitted value shapes.

The publication measurement script now records completed operation milliseconds,
logical keys and directory bytes after every adoption and a final real sweep,
using the declared development overlay. The helper calls the existing operator
PREPL client on the scratch `head` advertisement. Shell syntax is checked;
full execution remains unverified because HEAD still references the retired
`error/properties` in a foreign test. Do not re-enable hook publication based on
these partial measurements.

## Final focused verification and cleanup

After the definition-digest producer landed, registry tests reached their bodies.
The first repeat (`e31636262bd0`, 13 executed / 68 assertions / 4 errors) exposed
three retired implicit-epoch expectations and one unrelated config reconciliation
writer still using the absent `:seon.db/process` attribute. Blob-retention tests
now explicitly request epoch retention. The policy-read test obtains a complete
row from `config/compile-manifest` and writes it with `transacted!`; it tests the
stored policy consumer, not config reconciliation. No partial config invented.

Final `bin/test-fast --paths src/seon/cluster/registry.clj
resources/seon/schemas/seon.cluster.registry.edn resources/seon/schemas/seon.config.db.edn
config/default.edn test/seon/cluster/registry_test.clj -- seon.cluster.registry-test`:
run `f0e16a178b3c`, 13 executed / 0 reused / **71 assertions, zero failures/errors**.
Both this run and c12 armed 1,690/1,690 registered contracts (1,684 program-armable).
The dry-run zero→one behavior now has an armed Seon test pass, in addition to
the earlier scratch diagnostic. No platform suite or cold gate run by this lane.

A repeated synthetic sweep supplies the missing actual sweep clock
(`tmp/a2-storage-retention/retention-repeat.log`). The reopened diagnostic store
already has a retention declaration, so the form's `:absent` label in this repeat
is not an absence test: it returns the existing cutoff. Additional schema/config
transactions account for seven additional current datoms versus the first run.

| phase | bytes | keys | current datoms | ms |
|---|---:|---:|---:|---:|
| before replacement series | 1,000,251 | 6 | 1,027 | — |
| replacement 1 | 1,250,298 | 7 | 1,028 | 80.868 |
| replacement 2 | 1,500,399 | 8 | 1,029 | 36.529 |
| replacement 3 | 1,750,551 | 9 | 1,030 | 35.513 |
| actual sweep, 5 keys | 501,131 | 4 | 1,030 | **43.903** |

The 1,000 value datoms are all current; history also has exactly 1,000.
First post-sweep bytes 500,313 → second post-sweep 501,131, a delta of 818 B
with seven additional metadata/config/transaction datoms. This is synthetic
storage proportionality evidence, not adoption evidence. Heap measurement is
now included in the full publication helper; no heap result was observed for
full adoption because publication is blocked before that path.

Source size from pre-lane HEAD: registry 664 → 535 lines; store 593 → 572;
combined 1,257 → 1,107 (**−150**). Fork and test additions are reported in
individual commits. No edit to `db.clj`, schema codec, `wake.clj`, error owners,
or either deliberately dirty foreign document.

All owned runner/probe sessions exited. No test launcher remained at cleanup;
`lsof +D` found no holders in the three owned probe stores. Exact scratch PID
checks also found none. Owned probe roots and failed publication root were
removed without following symlinks; the owned worktree was removed after saving
its final diff. Publication logs were copied to
`tmp/a2-storage-retention/publication-start.log` and `publication-init-zero.log`;
forms/results and test logs remain in that evidence directory. `default` was
never stopped, reset, restarted, signalled, hot-reloaded or adopted.
