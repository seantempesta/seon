---
type: research
status: complete
tags: [storage, operator, datahike, research]
---

# Disk burn forensics — 2026-08-04

## Verdict

The emergency had two independent multipliers.

- Scratch-root count was unbounded and unobserved. The preserved census has
  1,884 entries occupying 242.67 GiB: 64 isolated operator roots account for
  138.03 GiB and 463 other directories account for 103.76 GiB. The two largest
  roots are 88.09 GiB and 29.92 GiB.
- The shared Datahike file store is 374.43 GiB apparent / 374.76 GiB allocated.
  A read-only reachability mark measured 357.36 GiB (95.44%) unreachable when
  every present branch head and every fact-referenced blob key is retained.
  The live-head set occupies 17.08 GiB. The current no-cutoff GC call can
  reclaim only 3.97 GiB (1.06%) because it retains every live branch's complete
  ancestry.

A fresh root is not intrinsically a gigabyte object now. The measured complete
root was 48.47 MiB while running and 51.30 MiB after clean shutdown. Its JVM
used the shared content-addressed dependency classes by absolute path; no class
cache was copied or linked into the root. A complete `current-src` publication
created a 34.31 MiB allocated Konserve store, of which 30.41 MiB apparent
(97.1%) was persistent-sorted-set index leaves.

No evidence root and no byte under `data/` was deleted or mutated during this
investigation. The only deleted paths were the explicitly permitted
`tmp/disk-forensics-probe` instrument roots after supervised shutdown.

## Method and units

- Directory totals are `du -sk` allocated KiB. GiB means 1,073,741,824 bytes.
- Konserve file totals are `stat` apparent bytes over every `.ksv` file.
- The leak census was captured before further investigation in
  [disk-burn-tmp-survivor-catalog-2026-08-04.tsv](disk-burn-tmp-survivor-catalog-2026-08-04.tsv).
  It records class, allocated KiB, epoch mtime, local mtime, and path for every
  surviving top-level `tmp/` entry plus every `tmp/test-runs/run.*` child.
- Shared-store reachability was measured by invoking Datahike's actual private
  mark implementation (`datahike.gc/reachable-in-branch`) from a read-only
  process, adding the same fact-derived blob keys as
  `seon.cluster.registry/collect!`, mapping keys to actual Konserve files, and
  summing bytes. `konserve.gc/sweep!` was never invoked.
- Historical fixture source is cited at the last commit that contained it when
  the creating namespace has since been deleted.

## 1. One isolated operator root

### Lifecycle measurement

Command:

```text
bin/seon --root tmp/disk-forensics-probe init
bin/seon --root tmp/disk-forensics-probe start probe
bin/seon --root tmp/disk-forensics-probe down
```

The boot published `current-src` commit
`6a727ade-093f-5974-849e-13a3d358602a`, reached `probe` readiness, and shut down
through the operator. The root was then removed as an instrument.

| Point | Complete root | Konserve store | Build publication | Cluster-local | Logs |
|---|---:|---:|---:|---:|---:|
| after `init` | 39.79 MiB | 34.26 MiB | 5.52 MiB | absent | absent |
| after `start probe` | 48.47 MiB | 41.64 MiB | 5.52 MiB | 1.29 MiB | 959 B |
| after `down` | 51.30 MiB | 44.48 MiB | 5.52 MiB | 1.29 MiB | 959 B |

At readiness the store contained 1,433 `.ksv` files, 38.53 MiB apparent
(40,401,231 bytes), and 41.64 MiB allocated. The cluster-local
1.29 MiB was almost entirely Lucene (`_0.cfs` was 1,333,471 bytes). The build
publication file `data/clusters/build/current-src.edn` was 5,790,131 bytes.

The live database value measured:

| Fact | Count |
|---|---:|
| datoms | 79,776 |
| entities | 14,752 |
| `:seon.fn` rows | 2,381 |
| `:seon.ns` rows | 270 |
| `:seon.schema` rows | 1,551 |
| `:seon.test` rows | 958 |
| blob-reference datoms | 1 |

### What one complete publication stores

A second init-only instrument publication was classified by loading all 1,369
Konserve values and summing their backing files. It occupied 32,860,427
apparent bytes / 35,975,168 allocated bytes.

| Stored object | Files | Apparent bytes | Share |
|---|---:|---:|---:|
| persistent-sorted-set index leaves | 1,350 | 31,891,561 | 97.1% |
| database snapshot/commit records | 12 | 659,216 | 2.0% |
| branch heads | 3 | 281,816 | 0.9% |
| other metadata maps | 3 | 27,731 | 0.1% |
| branch roster | 1 | 103 | <0.1% |

The publication is therefore index datoms, not blobs and not opaque Konserve
"segments." This file backend stores one serialized value per `.ksv` file;
the dominant values are persistent-sorted-set leaves. The only database blob
reference did not create a large per-root blob population.

### Dependency classes are shared

The probe contained no dependency-class directory and no dependency-class
symlink. Its JVM command line named:

```text
-Dseon.dependency-cache.path=/Users/sean/src/seon/target/dev-dependency-classes/8f0...
```

and its classpath referenced that same absolute directory. Thus operator roots
neither copy nor link `target/dev-dependency-classes`; they consume the shared
content-addressed cache directly. At measurement time the shared cache was
5,247,516 KiB (5.00 GiB), with a representative digest directory 99,012 KiB
(96.69 MiB). Roughly 53 retained digest generations, not per-root duplication,
explain that separate cache total.

## 2. The 375 GiB shared `data/`

### Physical allocation

| Path | Allocated KiB | GiB |
|---|---:|---:|
| `data/` | 393,209,060 | 375.0 |
| `data/clusters/` | 392,991,704 | 374.8 |
| `data/clusters/store/` | 392,961,024 | 374.8 |
| `data/preserved-clusters/` | 213,460 | 0.20 |
| `data/tune/` | 4,020 | 0.004 |

The single shared store is the allocation. Per-cluster directories are only
Lucene/log/operator projections and cannot honestly be assigned a share of
the persistent Datahike nodes: branches structurally share index nodes.

| Cluster directory | Allocated KiB | Branch head max tx | Head max eid |
|---|---:|---:|---:|
| `default` | 1,712 | 536871810 | 14,836 |
| `expected-refusal-log-0804` | 1,084 | 536870955 | 14,654 |
| `message-order-proof-0804` | 1,080 | 536871001 | 15,048 |
| `admit-identity-0804` | 1,076 | 536870954 | 14,473 |
| `message-order-fix-0804` | 1,072 | 536871046 | 15,114 |
| `mcp-window-fix-0804` | 1,072 | 536870954 | 14,459 |
| `repldogfood0804` | 1,060 | 536871062 | 14,407 |
| `codex-repl-dogfood-0804` | 1,060 | 536870959 | 24,196 |
| `xcurate0804` | 1,056 | 536871000 | 14,095 |
| `transcript-order-0804` | 1,056 | 536870954 | 14,058 |
| `repl-var-face-0804` | 1,056 | 536870955 | 14,119 |
| `opusns` | 1,056 | 536870955 | 14,060 |
| `fix-agent-config-fork-0804` | 1,056 | 536870957 | 14,063 |
| `fix-agent-config-0804` | 1,056 | 536870955 | 14,080 |
| `dbfaces0804` | 1,056 | 536870993 | 14,342 |
| `transcript-order-live-0804` | 1,052 | 536870955 | 14,114 |
| `transcript-curation-0804` | 1,052 | 536870955 | 14,065 |
| `session-curation-ns-20260804` | 1,052 | 536870954 | 14,058 |
| `session-curation-effects` | 1,052 | 536871038 | 14,384 |
| `opuseffect0804` | 1,052 | 536870983 | 14,126 |
| `curation-opus` | 1,052 | 536870954 | 14,058 |
| `config-audit-0804` | 1,052 | 536870954 | 14,061 |
| `capvarwalk0804` | 1,052 | 536870955 | 14,059 |
| `transcript-error-face` | 4 | 536870928 | 14,105 |
| `test-call-edge-093670eff` | no directory | 536870922 | 12,327 |
| `current-src` | build directory 5,648 | 536870923 | 14,589 |
| `db` | no cluster directory | 536870912 | 0 |

There were 27 branch heads. Every branch was created from the same 2026-08-03
base and all but `db` had exactly one parent at the measured head.

### Konserve internals

The store held 151,017 files, all `.ksv`, totaling 402,045,363,107 apparent
bytes (374.43 GiB) and 402,392,088,576 allocated bytes (374.76 GiB).

| Apparent file size | Files | Apparent bytes | GiB |
|---|---:|---:|---:|
| under 4 KiB | 174 | 481,897 | <0.001 |
| 4–64 KiB | 137,629 | 2,301,423,258 | 2.14 |
| 64 KiB–1 MiB | 10,518 | 1,559,878,857 | 1.45 |
| 1–16 MiB | 447 | 2,770,700,960 | 2.58 |
| 16–256 MiB | 2,002 | 224,824,559,904 | 209.38 |
| 256 MiB and larger | 247 | 170,588,318,231 | 158.88 |

The largest one-value `.ksv` file was 1,243,022,121 bytes. The 2,249 files at
least 16 MiB contain 98.3% of all bytes. These are serialized database index
values and retained historical copies, not an accumulation of millions of
small log or blob files.

### Measured reachability and predicted reclaim

| Retention policy used by mark | Existing files reclaimable | Reclaimable bytes | Reclaim | Bytes retained |
|---|---:|---:|---:|---:|
| current call, cutoff epoch (`Date(0)`) | 29,131 | 4,264,415,700 (3.97 GiB) | 1.06% | 370.46 GiB |
| present heads only, cutoff now | 136,800 | 383,708,687,032 (357.36 GiB) | 95.44% | 18,336,676,075 (17.08 GiB) |

The mark also included 21 blob keys derived from schema facts and branch
history, exactly matching the Seon extension. The head-only retained set maps
to 14,217 existing files. Datahike's mark reports some inlined or previously
absent addresses as reachable; for that reason the table uses the exact
`all-files minus unreachable-files` physical count rather than the raw
reachable-set count.

This is a prediction for the requested policy “only live branch roots
retained,” not authorization to execute it. It deliberately invalidates old
commit IDs and database values. Because `:keep-history?` is true, each live
head's temporal index roots remain marked; the 17.08 GiB retained figure
already includes live logical fact history.

### Source-grounded safe GC recipe for this store

Datahike's public API dispatches GC through the writer
(`reference-code/datahike/src/datahike/writer.cljc:446-453`). The actual mark:

- reads every branch from `:branches` and always retains each head
  (`reference-code/datahike/src/datahike/gc.cljc:22-42,136-146`);
- retains current and temporal indexes, schema metadata, and secondary indexes
  (`gc.cljc:43-71`);
- follows parents only while the commit record is newer than `remove-before`
  (`gc.cljc:41-42,72-74`); and
- defaults to epoch, explicitly meaning no history erasure
  (`gc.cljc:83-87,118-120`).

Konserve deletes only non-whitelisted objects older than the writer safe point
(`reference-code/konserve/src/konserve/gc.cljc:8-39`). Datahike computes that
safe point from in-flight write sequences and explicitly requires GC to run in
the writer JVM, never a cron/sidecar process
(`reference-code/datahike/src/datahike/gc.cljc:89-117,121-146`).

For Seon's store the safe operator run is therefore:

1. Freeze an explicit retention decision: every current branch remains; no old
   commit ID/database value is promised after the run. If the owner instead
   chooses a history window, use its exact `remove-before` instant and rerun
   this mark to obtain a different prediction.
2. Run in the one process holding the process-root store flock and authoritative
   self writer. Do not connect a second JVM and do not invoke Datahike GC
   directly from a sidecar.
3. Call the one Seon owner, `seon.cluster.registry/collect!`, extended to accept
   the ruled `remove-before` argument. Do not bypass it: lines
   `src/seon/cluster/registry.clj:288-325` query the schema registry for every
   attribute whose declared form is `:seon.blob/digest` and retain all
   referenced blob keys; lines 327-353 extend Datahike's one safe-point sweep.
4. Before enabling the sweep, run the same mark-only census and persist branch
   heads, retained/reclaimable key counts, and bytes. For head-only retention,
   the expected pre-sweep values are the two rows above.
5. Execute the writer operation once. Keep the process/root store flock for the
   entire mark and sweep. Datahike permits concurrent writes because the safe
   point protects objects written by an in-flight commit; an operational pause
   is still reasonable to make the before/after evidence stable, but is not the
   safety mechanism.
6. Reopen/query every one of the 27 branches, read each referenced blob, and
   compare every branch head with the pre-run roster. Run a second identical GC
   pass; it must sweep zero.

`collect!` currently calls no-argument `d/gc-storage` at
`src/seon/cluster/registry.clj:353`, so step 3 requires a small owner change
before the scheduled run. Running the existing function would reclaim only
the measured 3.97 GiB.

## 3. Leak census and attribution

### Complete survivor census

The durable TSV is the exhaustive row-level census. Its measured class totals
are:

| Surviving class | Entries | Allocated GiB |
|---|---:|---:|
| isolated operator roots, otherwise unnamed | 64 | 138.032 |
| other directories | 463 | 103.763 |
| other files | 49 | 0.704 |
| logs | 160 | 0.078 |
| JFR files | 7 | 0.027 |
| `test-runs` container | 1 | 0.024 |
| `test-runs/run.*` | 4 | 0.024 |
| text files | 92 | 0.012 |
| web evidence | 42 | 0.002 |
| Clojure source | 187 | 0.001 |
| EDN | 46 | 0.001 |
| sockets | 766 | <0.001 |
| symlinks | 3 | <0.001 |

The largest survivors were `render-live-proof` 88.09 GiB,
`seon-runtime-artifacts` 37.74 GiB, `render-adversarial-root` 29.92 GiB,
`reset-proof` 14.66 GiB, `search-live-proof` 12.48 GiB, and
`seon-jvm-artifacts` 10.22 GiB. The named historical classes already removed
by the stopped orchestrator sweep have zero survivors; their attribution below
comes from their creating source and the deletion transcript, not invented
post-deletion sizes.

### Priority 1: `tmp/render-live-proof` — 88.09 GiB

- Birth: 2026-08-03 06:39:06 -0400; last root mtime: 07:27:32.
- Allocation: 92,373,420 KiB total; 92,368,756 KiB is the shared Datahike
  store; build output is 4,000 KiB; the log is only 615 bytes.
- Store: 12,628 files. Of apparent bytes, 67.76 GB was in 919 files of
  16–256 MiB and 26.11 GB was in 52 files at least 256 MiB. The largest file
  was 993,317,792 bytes.
- Time attribution is decisive: 16.87 GiB landed at 06:40, 20.92 GiB at
  06:41, 21.95 GiB at 06:42, 7.97 GiB at 06:43, and 20.09 GiB at 06:44.
  Later render-proof work from 07:23 through 07:29 added only about 0.16 GiB.
- The head contains only 44,687 current datoms; all string values combined are
  a few MiB, led by 1.34M source characters in `:seon.fn/source` and 1.07M in
  `:seon.test/source`. There is no giant live datom payload.

This root is a render live-proof operator root whose evidence files are
`tmp/render-live-proof/{v1.html,v2.html,tab-*.html,package-*.edn}` and whose
cluster is `render-proof`. Its 88 GiB was created by the complete
`current-src` publication before the live render proof began: successive
persistent-index publications were retained as large `.ksv` values. It is not
an 88 GiB render package, log, dependency cache, or live database value. The
fresh publication measurement after the root-fusion work is 34 MiB allocated,
so this specific amplification is historical; the missing lifecycle/reaper is
still current.

### Priority 2: `tmp/render-adversarial-root` — 29.92 GiB

- Birth: 2026-08-03 07:36:17 -0400; last root mtime: 07:37:00.
- Allocation: 31,370,312 KiB total; the store is 30,366,868 KiB and
  `data/clusters/render-adversarial/logs/seon.log` is 1,012,267,319 bytes
  (965 MiB).
- Store: 1,360 files. There are 367 files totaling 24.49 GB at 16–256 MiB and
  18 files totaling 6.38 GB at least 256 MiB; maximum 501,799,098 bytes.
- Only 0.025 GiB existed after publication at 07:37. The adversarial run wrote
  3.322 GiB at 07:39 and 25.611 GiB at 07:40.
- The log contains 132 `SEON CORE FAULT` records and 264
  `StackOverflowError` strings. Every repeat names render proc
  `:seon.render.web/render`, pass/count 28, and recursion at
  `user$eval10784$fn__10785$fn__10786`.

The creating probe is `tmp/render_adversarial_probe.clj:69-102`: it installs an
infinite-loop SCI renderer in `interrupted-neighbour-probe`. The live process
record identifies PID 5428, generation
`bac76e57-afa7-4762-9f81-7bc4e2dda0ae`, cluster `render-adversarial`.
At incident time every repeated Flow fault printed the complete fault and
committed its source, including the render proc state and
`:seon.sci.eval/ctx`; 132 repeats produced both the 965 MiB log and about
28.93 GiB of historical store values. Commit `7f09f6569` later added bounded
fault text plus durable/process-local signature deduplication, now visible at
`src/seon/cluster.clj:1381-1477` and `src/seon/flow.clj:741-789`. The root
itself still had no declared ephemeral owner and survived.

### Ranked historical leak classes

| Rank | Name class | Creating path | Measured survivor state | Why cleanup failed or did not exist | Correct construction |
|---:|---|---|---|---|---|
| 1 | `branch-sigint-*`, `branch-sigint-reuse-*`, `branch-sigint-failure-*` | `test-old/seon/dev/branch_test.clj:264-314,316-360,362-404` | zero after sweep; deletion transcript recorded hundreds | Each test creates a UUID root. `finally` forcibly destroys only the immediate owner and immediately deletes the directory; it neither captures/reaps descendants nor awaits owner exit. If the suite JVM is terminated, `finally` does not run at all. Removing the root also removes the process records needed to identify surviving children. | Capture exact child identities at spawn; on test/suite exit stop descendants, await `ProcessHandle.onExit`, then remove records and finally the claimed root. The fixture owns this in `finally`; no external name sweep. |
| 2 | `test-runs/run.*` (D15) | `bin/test:157-170,224-301`; cleanup mechanics `script/seon/dev/changed_test.clj:243-281` | four survivors, each 6,200 KiB, mtimes 19:12–19:14 | Interrupted/nonzero runs are deliberately retained, while changed-test teardown discovers descendants by sampled PID sets and polls absence. The signal trap exits without an ordered descendant teardown. Root retention can therefore precede child exit and no declaration distinguishes evidence retention from abandoned root. | Publish complete child ownership/readiness; on exit reap exact children first, await their completion events, then mark the root retained or remove a successful root. Keep the clock only as a loud external backstop. |
| 3 | `bootstrap-v2-api-*` plus `*-api-graph-*` pairs | commit `c07d321e4`, `src/seon/test/bootstrap_v2.clj:801-840` | zero after sweep; transcript recorded dozens of pairs | `clean-api-test` creates two Datalevin directories and only calls `d/close` in `finally`; it never deletes either directory. The shared `with-embedded-datalevin` fixture at lines 602-622 has the same omission for domain/graph pairs. | Each fixture owns both directories and deletes them in its outer `finally`, after listener shutdown and both connections close. |
| 4 | `blob-test-*` | commit `10cc8fd11^`, `test/my/blob_test.cljc:16-18,61-72` (earlier `d701afeae`, `test/my/blob_test.cljs:36-48`) | zero after sweep; transcript recorded dozens | The CLJS `use-fixtures :after` removes the PID root only on normal framework teardown. A killed/aborted suite never calls it; the directory has no independent ownership claim or process-exit cleanup. | Wrap the actual asynchronous fixture body in a promise `finally` that restores the storage view and removes its exact root; register the root with the suite exit owner as a second line. |
| 5 | `autocomplete-test-*` | commit `fb5f64123^`, `test/seon/repl/autocomplete_test.cljs:14-21` | zero after sweep; transcript recorded dozens | Same framework-after-only shape: a PID directory is removed in `:after`, but interruption bypasses it. | The async fixture itself owns deletion in `finally`; suite exit reaps any still-claimed fixture roots after children stop. |
| 6 | `render-live-proof` | live-proof commands evidenced by `tmp/render-live-proof/*`; operator boot path `bin/seon --root` | 88.09 GiB preserved | The manually created operator root has no declared ephemeral owner/reap disposition. A clean `down` stops processes but does not delete the root, and no observable size made its 88 GiB visible. | Claim before creation, sample size facts, and have the creating experiment release/reap its exact root on exit after supervised `down`. |
| 7 | `render-adversarial-root` | `tmp/render_adversarial_probe.clj:69-102` plus `bin/seon --root` | 29.92 GiB preserved | The intentional infinite renderer triggered the then-unbounded repeated-fault path; the manually created root had no ownership/reaping claim after the probe. | Keep current bounded/deduplicated fault path; claim and reap the experiment root on owner exit after child shutdown. |

Issue notes are filed separately for each class. The existing
[changed-test process cleanup issue](../../../seon/issues/changed-test-process-cleanup-polls-observable-exit.md)
owns `test-runs/run.*`, and the existing
[shared bootstrap-drive issue](../../../seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md)
owns the live-workspace deletion incident and declared-claim requirement.

## 4. The missing governor

The repair is observation and cleanup-by-construction, not a quota.

### Declared directory claims

Every directory that a process, test, run, drive, or experiment may delete is
created only after a durable claim is committed in the creator's stable parent
database. The claim database must live outside the claimed directory; a root
cannot be its own only ownership record.

The data model is attributes and connections, not a `:type` discriminator:

- `:seon.fs.directory/path` — canonical identity of the exact directory;
- `:seon.fs.directory/claim` — current claim identity;
- `:seon.fs.claim/owner` — ref to the creating process, test run, bootstrap
  drive, or experiment fact;
- `:seon.fs.claim/process` — exact `(pid, start-instant, generation)` process
  record when process-owned;
- `:seon.fs.claim/parent` — connection to the stable parent claim;
- `:seon.fs.claim/reap-on-owner-exit?` — explicit lifecycle promise; and
- `:seon.fs.claim/released-at` is not stored as mutable liveness. Release
  retracts the active claim connection; transaction history supplies when.

“Who owns this path?”, “is its exact process alive?”, “may it be reaped?”, and
“which claimed children must stop first?” become queries. A name prefix, mtime,
or `ps | grep` is never ownership. The reaper receives an exact claim, derives
the current `ProcessHandle` identity, runs the owner's shutdown (`bin/seon down`
for operator roots), awaits child exit events, retracts process records, then
uses the existing no-follow bounded deletion walk. If a live claim exists or
ownership is absent/ambiguous, deletion refuses loudly and records the
attempt. This directly prevents the bootstrap-drive incident where a ps-grep
heuristic removed a live experiment workspace.

For a top-level isolated operator root, the launching process records the root
claim in its own stable parent database before `mkdir`; the child root records
only descendants. That avoids the circular and unsafe design where deleting a
root also deletes the only fact proving who owned it.

### Size and free-space facts

Measurement events are facts; “current size” is derived from the latest event:

- `:seon.fs.measurement/directory` — ref to the claimed directory;
- `:seon.fs.measurement/apparent-bytes`, `/allocated-bytes`, and `/file-count`;
- `:seon.fs.measurement/filesystem` plus `/usable-bytes` and `/total-bytes`;
- normal transaction provenance supplies process and observation order.

Samples occur after init, after cluster start/stop, on `bin/seon status`, and
before an ephemeral claim is released. They are bounded summaries, never a
stored recursive file roster. `bin/seon status` renders, for every root, exact
owner claim, process identity/liveness, reap disposition, apparent and
allocated size, last sample basis, and filesystem free space. It ranks roots
by bytes and marks stale measurements honestly.

Startup measures the target filesystem before publication and compares usable
bytes with a declared config fact. Crossing the observation floor does not
refuse or throttle work: it commits a warning fact and prints a prominent
stderr/status warning naming free bytes and the largest claimed roots. In
development the same event follows R41's loud path; production records and
renders degradation. There is no quota, automatic speculative deletion, or
per-agent storage hobbling.

### Event-driven reaping

Ephemeral claims register with the existing process/experiment lifecycle at
creation. Cleanup is driven by the owner's `ProcessHandle.onExit` or explicit
completion, not a polling scheduler. The ordered transition is:

```text
owner exit/completion
  -> stop every exact claimed child
  -> await every child exit
  -> close database/file handles
  -> retract child process records and active claims
  -> preserve declared evidence OR delete the exact released root
  -> record the terminal size measurement and outcome
```

Filesystem work and child waits belong on the existing I/O workload. Nothing
adds a central scheduler. A last-resort external-process timeout may report a
cleanup bug loudly, but it never substitutes for the observable exit event.

## Issues filed

- [Branch SIGINT fixtures can erase ownership before descendants exit](../../../seon/issues/branch-sigint-fixtures-erase-ownership-before-child-exit.md)
- [Blob test roots depend on a framework after-hook](../../../seon/issues/blob-test-roots-depend-on-framework-after-hook.md)
- [Autocomplete test roots depend on a framework after-hook](../../../seon/issues/autocomplete-test-roots-depend-on-framework-after-hook.md)
- [Bootstrap v2 API fixtures never delete their Datalevin directories](../../../seon/issues/bootstrap-v2-api-fixtures-never-delete-directories.md)
- [Render live-proof roots have no declared lifecycle owner](../../../seon/issues/render-live-proof-roots-have-no-lifecycle-owner.md)
- [Render adversarial roots outlive their fault experiment](../../../seon/issues/render-adversarial-roots-outlive-their-experiment.md)
- [Observe and claim every deletable directory](../../../seon/issues/deletable-directories-have-no-claim-or-size-facts.md)
- [Await changed-test process exits instead of polling clocks](../../../seon/issues/changed-test-process-cleanup-polls-observable-exit.md)
- [Preserve every live bootstrap drive root and raw report](../../../seon/issues/shared-bootstrap-drive-root-disappears-during-live-experiments.md)
- [Give storage GC the cutoff that makes it actually reclaim](../../../seon/issues/storage-gc-runs-without-a-cutoff-so-it-reclaims-almost-nothing.md)
