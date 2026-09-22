---
type: landing
status: independent rows 2, 6, 7, 8 landed; B4 retirement seams held; latency targets unmet
created: 2026-09-22
---

# Publication envelope — wave A file half

Hook publication is paused. No change in this lane is live on the repository's
`default`; no operation resets or adopts that cluster. Scratch roots have their
own JVMs and are not evidence of default adoption or browser paint.

## Scope and held seams

- **Row 2 file path converted:** `full-source-refresh!` carries analyzed rows,
  reads prior declarations directly by file, and reconciles removed identities
  without reconstructing or comparing manifests. `artifact-by-path`,
  `manifest-function-symbols`, and `replace-manifest-artifacts` are deleted.
  `build-manifest` is now a B4 export adapter over the same analysis owner.
  `database-manifest`, `manifest-data`, `published-index-rows`, the manifest
  request/export keys and their schemas remain for B4: `src/seon/test/runner.clj`
  `program-manifest`, `src/seon/test/cache.clj` `manifest`, and
  `test/seon/test_support.clj` `source-manifest`. Their final retirement belongs
  with B4 commit 4, as authorized by the follow-up. No schema resource changes
  in this row; no stale schema removal is claimed.
- **Row 6 aggregate retirement held:** `src/seon/test.clj` still finds its
  publication row by `:seon.source/digest` (884), pulls both aggregate members
  (890), and reads input digests (906, 1372, 2160). `test/runner.clj` reads the
  aggregate source digest (2379) and artifact input digests (3615).
  `test/fast.clj:31` reads the snapshot digest. Those files are excluded.
  Consequently the seal and both aggregate producers remain. The independent
  scalar-upsert path can leave with its callers and schema keys.
- **Row 7 file path converted:** `source-snapshot`, `current-source-snapshot`,
  `require-publication-resources!`, and the second observation are deleted.
  Capture supplies the exact text consumed by the existing analyzer mirror.
  Pathless discovery compares current inventory plus stored paths; `.clj-kondo`
  changes analyze all source paths; dependency-file/gitlink changes refuse with
  `:seon.cluster.source/reset-needed`. The input-reader move remains held:
  `input-paths`, `input-roots`, `gitlink-digests`, `toolchain-dependencies` are
  still in excluded `src/seon/test/cache.clj`, not in the released `fn.clj`
  regions. The source owner calls that existing implementation; it does not
  copy it. `source/snapshot` and its schema remain for `seon.test.fast`.
- **Row 8 independent:** retain Clojure reload, remove cyclic name-order
  fallback and blind retry, compute one closure across both database values,
  verify reloaded file digests before recording adoption, produce arming
  identities. The existing `instrument/apply!` still ignores the new member
  and uses broad arming. Bounded arming is NOT claimed.

## Dependency and work boundary

Clojure gitlink `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`,
`reference-code/clojure/src/clj/clojure/core.clj:6064–6087`, selects `load-one`
for `:reload`; it receives an already-selected namespace, compiles that file,
retains existing namespace/Var identities, and does not select dependents.
Seon's `load-development-definitions!` is the caller. A changed declaration
selects roots; stored `:seon.ns/requires` selects their reverse closure;
`reload-order` orders that set. Each reload pays that namespace's compilation.
The comparison source is clj-reload `61c6fa77855efc0b4d052f06c7cc8b2b8817c4aa`,
`src/clj_reload/parse.clj:163–176`: its topological sorter refuses a cycle.
No library or second dependency graph was added.

## Initial evidence and foreign boundaries

`bin/seon status` and MCP `runtime_status` at the repository root both returned
unknown/degraded default readiness, missing source commit, branch, connection,
configuration, SCI context, work launcher, graph and web serving. MCP itself
was available. Live probes therefore used scratch roots.

The original measurement script failed before adoption on its first scratch
boot: `:seon.db/process` was absent from installed schema. The second snapshot
included `6ec971b07`'s repair and progressed to the deleted
`seon.search/supplied-handle` config reference. A third attempt at `4343e804a`
still found the remaining reference; `62487dbc3` removes it. None of those
failed starts supplies an adoption timing. Their exact logs are retained in
`tmp/publication-envelope-evidence/baseline-*-refusal.log`.

The shared-tree load subsequently failed in the other lane's untracked
`resources/seon/schemas/seon.sci.execution.edn`: `:seon.fn/affected` belonged in
`seon.fn.edn`. No foreign file was edited. The user-authorized snapshot
`tmp/publication-envelope-wt`, HEAD `62487dbc3` plus only this lane's paths,
loads `seon.cluster`, `seon.cluster.source`, and `seon.fn` successfully.

Focused invocation:
`bin/test-fast --paths src/seon/cluster.clj test/seon/cluster_test.clj -- seon.cluster.source-test seon.cluster-test seon.cluster.publication-delta-test`.
It acquired the projection and armed 1,687 contracts, then refused admission:
`Test recording requires a published current-src.` Zero tests executed; this
is not green. The installed fast runner uses `seon.test.source-root` for its
recording authority and provides no independent scratch recording-root member.
No cold gate or suite was launched.

## REPL forms

Before, on `tmp/publication-envelope-before-root`, cluster `head`, JVM,
read-only:

```clojure
(let [v (resolve 'seon.cluster/reload-order)]
  {:available? (some? v)
   :order (when v (v '#{sample.a sample.b}
                    '{sample.a #{sample.b} sample.b #{sample.a}}))})
; 1 ms, {:available? true :order [sample.a sample.b]}
```

After, on `tmp/publication-envelope-root`, cluster `default` (the SCRATCH
root's cluster), JVM, read-only:

```clojure
(try
  (seon.cluster/reload-order '#{sample.a sample.b}
                            '{sample.a #{sample.b} sample.b #{sample.a}})
  (catch clojure.lang.ExceptionInfo failure (ex-data failure)))
; 1 ms, {:seon.boot/refused true
;        :seon.error/message "Development reload requires contain a cycle."
;        :seon.boot/offense {:seon.ns/requires #{sample.a sample.b}}}
```

These are host algorithm observations during scratch boot, not successful
publication, arming, SCI execution or browser observations.

## Measurements, scratch boot, commits

The measurement script was attempted before the reductions and the required
scratch reset exercised the combined reduced source. Both reached a published
`current-src`, then failed creating a cluster namespace without its required
`:seon.program/definition-digest`. The CLI additionally reported an unreadable
printed exception (`No dispatch macro for: '\'`). This is beyond the owned
publication/adoption span. Healthy scratch boot is NOT claimed.

| Requested clock | Before rows 6/8 | After rows 6/8 | Reloads / re-armed Vars |
|---|---|---|---|
| explicit no-change adoption | unavailable: boot refused | unavailable: boot refused | unknown / unknown |
| leaf docstring adoption | unavailable: boot refused | unavailable: boot refused | unknown / unknown |
| `seon.id` adoption | unavailable: boot refused | unavailable: boot refused | unknown / unknown |
| from-zero start/reset | baseline CLI 168,900 ms, failed | scratch process start to refusal 168,184 ms, failed | no successful adoption |
| raw `seon.id` closure compilation | 3,042.041917 ms | reload primitive retained | 90 namespaces; unarmed compile only |

These are failed end-to-end measurement rows, NOT target passes or evidence that
the envelope is gone. No-change and leaf/core acceptance remain outstanding.
Row 2/7 have no after measurement because their code has not changed.

The 90-namespace figure is now from the published stored graph, not the data
pack's static estimate. All 90 source namespaces were first loaded, then timed
individually through `require :reload`; no publication or contract arming is
inside that clock. Heap in use afterward: 762,877,088 bytes (not a peak/RSS
measurement). This is already above one second before envelope work. Exact
per-namespace rows and commit identity are in
[the clock artifact](publication-envelope-seon-id-compile-2026-09-22.edn).
The companion script supports `compile-offline` against the persisted scratch
publication when boot cannot acquire a cluster. Invocation from the baseline
snapshot:

```sh
clojure -Sdeps '{:paths ["src" "resources" "script"]}' -M \
  /Users/sean/src/seon/docs/prds/agent-platform/research/measure-publication-reloads-2026-09-22.clj \
  /Users/sean/src/seon/tmp/publication-envelope-baseline-root compile-offline
```

The original measurement script now uses its companion around successful
adoptions to count changed armed roots, including newly armed Vars, and writes
the closure compile table before stopping the scratch JVM. Those adoption
counter paths are authored but unexercised here because boot refused.

Scratch schema proof command (HEAD plus owned paths):
`bin/seon --root /Users/sean/src/seon/tmp/publication-envelope-root reset --force`.
Raw log: `tmp/publication-envelope-evidence/scratch-reset.log`.


## Accepted independent changes and proof limits

Row 6 deletes `seon.cluster.source/assert-scalar-rows!`, `populate-upserts!`,
`upsert!`, and `:seon.source/upsert-row`, `/upsert-rows`, `/upsert-request`.
The three tests protecting the removed scalar path and the obsolete predicate
rename/tombstone test leave with it. The surviving branch-isolation regression
now calls `publish!` directly through its fixture population function.
Source −54 lines; schema −17 net; tests −294 net. The seal,
`publication-input-digest!`, `test-input-digest`, and aggregate source digest
remain because their live B4 consumers remain. This is a partial row 6, not
aggregate retirement.

The required from-zero scratch attempt **did** publish the canonical schema and
program before the foreign boot refusal. A later read of that exact scratch
publication returned `[]` for schema keys `/upsert-row`, `/upsert-rows`, and
`/upsert-request`. This proves their resource removal has no missing-reader
failure at publication; it does not prove healthy cluster boot.

Row 8 deletes `source-change-phase` and `retrying-source-change`; the obsolete
retry test leaves. It retains `require :reload`, commit-id equality on every
request, and broad arming. The producer supplies only function identities from
reloaded interns and schema referrers, excluding retired identities. The digest
check reads the hosting checkout's files, not a candidate directory that
`require` did not load. `development-namespaces` keeps its public two-argument
arity and adds a before/after arity; adoption calls it once.

A diagnostic armed probe on the persisted canonical scratch publication
registered/instrumented 1,518 Vars. Direct-referrer selection included
`my.web/fetch` for `:my.web/fetch-request`; the transitive selection for
`:seon.source/commit-id` included `development-source-refresh!` through
`:seon.source/published` (28 function identities). A reflexive rule with repeated
head variables initially selected only direct referrers; binding distinct rule
columns repaired that probe. This is measured behavior, not a new dependency
mechanism or an inferred recursive-query guarantee.

The same armed probe accepted matching digests and refused a mismatching source
root with `:seon.cluster.source/phase :adoption`, naming
`src/seon/cluster.clj`. A three-namespace program analyzed by `seon.fn/rows` and
written through the canonical writer on a temporary branch proved the combined
graph: after retracting caller→leaf, after-only selected `#{leaf}`, whereas
before+after selected `#{leaf caller outer}`. The connection was released and
the branch unlinked in `finally`. Exact forms/results:
`tmp/publication-envelope-evidence/probe.clj` and `probe-accepted.log`.
A forced failure through a fully booted development adoption remains unproved.

Publication-only probes (no development cluster, explicitly **not adoption**)
republished the changed owned file in 10,486.75525 ms and returned an unchanged
commit in a fresh host at 872.032291 ms. No leaf/sub-second claim follows.
The first armed probe correctly rejected a set against an older published
vector contract; publication of the changed file updated the program contract
before the successful probe. A hot source edit alone was not treated as adoption.

## Focused recorded tests

The isolated checkout used the existing published fixture cache read-only and
its own scratch store as the recording authority (local symlinks, no change to
the runner and no repository-default write). The same `bin/test-fast --paths`
request then **recorded 22 executed, 0 reused, 41 assertions, 0 failures,
17 errors**, run `d086058add44`. The armed cycle regression passed. Every setup
error named the stale fixture base's deleted `seon.search/ping-map-fn?`; the
base was 49 commits behind the tested snapshot. This is wanted fixture
acquisition behavior at B4's held boundary, not a reason to restore search or
change the new regression's expectation. Raw log:
`tmp/publication-envelope-evidence/focused-test.log`.
The additional transitive-schema regression is authored; its recorded-fixture
execution is pending that repair, with its producer exercised by the armed
scratch probe above. No suite or cold gate was run.


## Commit ledger

- `60b94954a` — independent row 6 scalar-upsert retirement, schema keys and
  callers together; measurement scripts and raw compile table. Exact committed
  HEAD load passed with exit 0 in the isolated checkout:
  `clojure -M -e "(require 'seon.cluster 'seon.cluster.source 'seon.fn)"`.
  Evidence: `tmp/publication-envelope-evidence/row6-head-load.log`.
- `dcc15e5b3` — row 8 reload/adoption changes and regressions. Exact committed
  HEAD load passed with exit 0 in the isolated checkout using the same command;
  evidence: `tmp/publication-envelope-evidence/row8-head-load.log`.

The recorded test request predates the final producer-query correction. The
final armed scratch probe, after publishing that correction, is the producer's
behavior evidence; the final HEAD load is separate. Row 8 adds 34 net source
lines to replace missing digest/referrer checks while deleting the retry; its
tests remove obsolete retry assertions and add cycle, graph, digest and schema
referrer cases. No source reduction is claimed for that row.

## Cleanup and handoff

All owned commands completed. Exact-root status returned no processes for all
three scratch roots, and an OS census found no Java process with any owned root
or snapshot argument. Three scratch stores and three worktrees were then
removed without following symlinks. Their logs, clocks, thread captures and
probe forms remain under `tmp/publication-envelope-evidence/`; the census and
removed paths are in `cleanup.json`. The main repository's default was never
reset/adopted by this lane, and hook publication remains paused.

The remaining work is rows 2/7 and row 6 aggregate retirement after their held
file seams are released, then the end-to-end adoption clock and a healthy cold
boot. This landing does not satisfy the overall sub-second leaf-edit goal.

## Released fn ownership: row 2 continuation

The release at `39a337013` was used only in manifest readers and their index
consumers. The reverse walk and declaration analyzer were not edited.
The live scratch MCP probe returned `{:connection? true :manifest? true}` in
2 ms before replacement. On the row-2 scratch JVM, resolving
`seon.fn/analyze-rows` returned true in 2 ms. Boot and the measured file requests
then exercised that owner, rather than merely loading edited source.

The committed measurement script needed its storage observer updated from the
removed `seon.cluster.boot/running-instances` reference to
`seon.operator.runtime/running-instances`. Its optional retention sweep refuses
because the unconfigured `head2` fork lacks snapshot-window-ms; this is recorded
separately and no longer prevents clock completion or shutdown. Live compile
under installed contracts also refuses; the earlier unarmed 90-namespace compile
artifact remains the compile-cost evidence.

| Row 2 clock | Before ms | After ms | Before Vars | After Vars |
|---|---:|---:|---:|---:|
| explicit no-change | 307.452 | 160.004 | 0 | 0 |
| leaf docstring (`my.note`) | 4684.308 | 5285.171 | 3 | 1680 |
| core docstring (`seon.id`) | 15855.848 | 17076.340 | 1552 | 1554 |

Before and after used isolated HEAD snapshots, with only owned source overlaid
after. Initial readiness: before 110319.719 ms; after 116265.214 ms. Both reached
readiness and then logged the foreign root-turn `:malli.core/invalid-schema`
panic, signature `3b0fee54475a2dcc98b2ffd5bcb195947072c329eb2fcf2662537b3b7c66866d`.
Neither was chased in this lane. The first baseline clock overlapped focused-test
startup; the table uses the repeated baseline, after that runner exited.
These are observations, not a speedup claim. Broad arming and B4 aggregates remain.

The focused scratch-root run `0db0f34a5d33` recorded 24 executed, 0 reused,
43 assertions, 0 failures, 19 errors. Every error is the same foreign canonical
fixture acquisition refusal: removed `seon.search/ping-map-fn?` in the 67-commit-old
published base. Three-question triage: these tests exercise surviving behavior;
this is neither an obsolete assertion to delete nor a failure in the changed
publication owner. The new row-reconciliation regression therefore remains
unproven by the installed test fixture. Real scratch boot and successful leaf/core
adoption are separate positive evidence. No suite or cold gate was run.

## Row 7 continuation and final proof boundary

Row 2 implementation: `cfa76f4dd`. Its exact `git archive` loads
`seon.cluster`, `seon.cluster.source`, and `seon.fn` (exit 0). The measured
row-2-after `fn.clj` and `cluster.clj` blobs equal that archive's blobs.
The before snapshot is `af865af2e`; row-2-after is `b2dc1de06` plus owned paths;
row-7-after is `980b36700` plus owned paths. Other committed lanes changed between
these snapshots, so these clocks are observations of each landing state, not a
controlled attribution of every millisecond to this lane.

The row-7 schema-resource change declares the carried rows, source directory,
and captured analyzer text in `:seon.fn/index-request`. B4's manifest entries
remain. Required from-zero command, in the owned source snapshot:

```
bin/seon --root /Users/sean/src/seon/tmp/publication-envelope-root reset --force
```

Exit 0; readiness **89,310 ms**, missing layers `[]`, source commit
`6ab2c9cc-7f92-5442-b2f7-cf36a3866202`. The subsequent read-only MCP JVM form
used `(seon.cluster.boot/connection "default")` on THAT SCRATCH ROOT, read
`:seon.schema/form` for `:seon.fn/index-request`, and found the installed
`:seon.program/rows`, `:seon.fn/root`, `:seon.fn.analyzer/sources` members.
Resolving the retired snapshot and resource-check Vars returned false. **4 ms**.
The earlier `extends-schema?` root-turn panic is historical: this snapshot
includes the recorder lane's repair; no such panic appeared in this scratch
reset log. This is readiness and schema evidence, not a platform-tier pass.

On the row-7 measurement JVM, the read-only capture/classification probe returned
in **13 ms**:

```clojure
{:captured-digest-matches true
 :leaf :selected
 :analyzer-config :all
 :dependency {:seon.cluster.source/refused :seon.cluster.source/reset-needed
              :seon.cluster.source/rule :seon.cluster.source/reset-needed
              :seon.source/changed-paths ["deps.edn"]}}
```

Exact forms/envelopes: `tmp/publication-envelope-evidence/row7-mcp-probes.json`.
The first probe during boot returned missing projection state, not a pass;
the later ready-JVM probe above supplies the positive evidence.

The row-7 focused run `1b74e6218a04` recorded **35 executed, 0 reused, 71 assertions,
0 failures, 25 errors**. All 25 errors are the same stale-base
`seon.search/ping-map-fn?` fixture refusal. The new capture-race and typed
classification tests completed without failure/error (315 ms and 1 ms,
respectively). Discovery excludes documentation and preserves individual schema
resource paths. The old empty-request/no-inventory expectation was converted to
an explicit unchanged-path/one-capture expectation: an empty request now means
pathless discovery. Its database-backed body remains unproven by the stale fixture.
The owned snapshots, actual scratch publication/adoption, and live probes were
used despite this foreign boundary; no foreign session or file was changed.

Late last-reader audit found the row-6 `upsert` test helper still referenced by
`source_evidence_test.clj` and the stale-upsert case in `source_lineage_test.clj`.
They test retired machinery and leave here (README §6 question 1). The surviving
failed/stale-publication race remains and computes its third digest through
`seon.id`; it no longer names the deleted fixture constant. This repairs a missed
test-side reader from the earlier row-6 slice, not a new publication mechanism.

## Final clocks with actual reload events

The final script explicitly loads and arms the two target namespaces outside the
clocks. An installed watch on the existing publication-progress atom observes
`development reload NS` events; it is removed after each request. Var counts
compare armed roots before/after the request. They are whole-JVM observations,
including newly armed Vars, not a claim that the A1 selection consumer landed.
The earlier root-comparison-only namespace counts are superseded by these events.

[Raw clocks, namespace lists and Var symbols](publication-envelope-clocks-2026-09-22.edn).
Row-2-after is also row-7-before. Rows 6 and 8 were already committed when ownership
was released: their original unavailable before/after evidence above remains
unavailable; later clocks cannot reconstruct those historical JVMs.

| State | Request | elapsed ms | namespaces reloaded | Vars re-armed |
|---|---|---:|---:|---:|
| before row 2 | explicit no-change | 290.208 | 0 | 0 |
| before row 2 | leaf docstring | 4416.786 | 1 | 3 |
| before row 2 | `seon.id` docstring | 15626.076 | 158 | 1552 |
| after row 2 / before row 7 | explicit no-change | 303.917 | 0 | 0 |
| after row 2 / before row 7 | leaf docstring | 4697.414 | 1 | 3 |
| after row 2 / before row 7 | `seon.id` docstring | 15747.582 | 158 | 1551 |
| after row 7 | explicit no-change | 300.562 | 0 | 0 |
| after row 7 | leaf docstring | 5527.306 | 1 | 138 |
| after row 7 | `seon.id` docstring | 21038.291 | 374 | 1672 |

**The sub-second leaf target is not met.** The seal and aggregate input hashing
remain at B4's seam; caller selection/lint and broad arming remain their current
owners. The repaired root turn also loads more tests: the final core reload set
contains all 90 namespaces from the original sample plus additional namespaces,
including tests. No dependency-selection narrowing was implemented from the
unapproved per-declaration proposal.

## Per-namespace compile distribution

The original committed 90-namespace sample totals **3042.042 ms** (individual
measurements sum to 3041.436 ms; loop/timer overhead is 0.606 ms). Median **29.442**,
p90 **74.511**, p95 **91.474**, maximum **119.157 ms** (nearest-rank percentiles).
[All 90 costs, sorted](publication-envelope-compile-distribution-2026-09-22.csv)
derive directly from the retained raw EDN, not a new estimate.

| Namespace | compile ms |
|---|---:|
| `seon.turn` | 119.157 |
| `seon.test.runner` | 111.851 |
| `seon.db` | 103.563 |
| `seon.render.web` | 102.188 |
| `seon.fn` | 91.474 |
| `seon.sci.eval` | 86.927 |
| `seon.config` | 80.774 |
| `seon.render.transcript` | 80.765 |
| `seon.error` | 78.964 |
| `seon.cluster` | 74.511 |

These ten total **930.173 ms (30.6%)**; the cost is distributed, not dominated by
one namespace. `seon.id` itself is **3.453 ms (0.11%)**. If the proposed declaration
classification proves that a particular body/docstring change needs only its own
namespace, the measured compile component could fall from about 3042 ms to about
3.5 ms for this example. That is a conditional compile-only sizing bound, not a
prediction of end-to-end publication latency or permission to narrow macro,
protocol, type/record, inline or `definline` reloads.

A second successful [live compile sample](publication-envelope-loaded-compile-2026-09-22.edn)
measures the final **374-namespace** loaded closure at **8570.820 ms**, heap-used
**1,404,152,560 bytes** after compilation. The original 90-namespace unarmed sample
reported **762,877,088 bytes**. These are point-in-time heap observations, not
allocation deltas or comparable retained-memory measurements. Earlier live compile
attempts refused under the old installed contracts; only this successful sample
supplies the later compile observation.

## Final commit and cleanup record

- Row 2: `cfa76f4dd`; archived HEAD load exit 0.
- Row 7: `975af0a60`; archived HEAD load exit 0 using exactly
  `clojure -M -e "(require 'seon.cluster 'seon.cluster.source 'seon.fn)"`.
- Prior independent slices: row 6 `60b94954a`, row 8 `dcc15e5b3`.
- Row-7 production files: publication span of `cluster.clj`, input capture in
  `cluster/source.clj`, the input adapter in `fn.clj`, and the three optional
  index-request members in `resources/seon/schemas/seon.fn.edn`. No reverse-walk,
  declaration-analyzer, boot, flow, SCI, instrument or B4 source files were edited.
- All owned launch/load/test shells returned terminal exit codes. Scratch JVM
  shutdowns observed exact-process exit. An `lsof` scan of live Java/Babashka
  processes found no holder before removing four owned roots, three source
  snapshots, and two archives. Symlink targets were not traversed. Logs, probes,
  patches and cleanup evidence remain under `tmp/publication-envelope-evidence/`;
  `cleanup-current.json` names every removed root. No shared test snapshot remains
  from the initial failed recording attempt.

Hook publication remains paused. Nothing in these commits is live on repository
`default`. The remaining B4 file ownership and performance limitations above are
still open; this note does not claim completion of README 1.2b's latency gate.
