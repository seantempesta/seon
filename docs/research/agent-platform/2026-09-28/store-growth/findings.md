# Store growth: measure and attribution (2026-09-28 ~20:00 local, lane store-growth)

Read-only probe of the live default JVM (pid 29460) and `data/store`. Scripts and raw key map
are in `tmp/store-growth/` (`snap.sh`, `kinds*.clj`, `time.clj`, `keys.edn` = 338k rows of
key/bytes/mtime, not committed).

## 1. The store now

| | value |
|---|---|
| files | 346,605 (all `.ksv`; flat directory, one file per konserve key) |
| apparent bytes | 1.07 GB (LZ4-compressed values) |
| `du` bytes | 1.90 GB (APFS 4 KB blocks: 0.83 GB, 43%, is block rounding, median file is 1.5 KB) |
| index nodes (UUID keys) | 337,622 files, 1.009 GB = 98% of bytes; avg 3.0 KB, p50 1.5 KB, p90 5.2 KB, p99 33 KB; 286k files under 4 KB |
| blobs (sha-256 string keys) | 249 files, 13.8 MB |
| branch heads (keyword keys) | 458 -> 471 during this lane, ~22 KB each (six index roots are fused into the head) |
| roster (`:branches`) | 45-46 live; 42 are `agent-*`; 412 -> 426 head keys are NOT in the roster |
| collection | never run (`last-collection` refusal in `bin/seon status`) |

Sample of 4,000 nodes (JVM, `konserve.core/get` + persistent-sorted-set `_keys`): 84% of nodes and
60% of node bytes are leaves, 15%/33% branches, 0.75% non-node maps. By first-datom attribute
namespace (share of sampled node bytes): `seon.fn` 34%, `seon.test` 9%, `seon.cluster.eval` 7%,
`seon.program` 7%, `seon.lint` 4%, `seon.db.vector` 4%, `seon.fn.file` 4%. Top attributes:
`:seon.fn/source`, `:seon.fn/calls`, `:seon.fn/call-arities`,
`:seon.program/analyzed-source-digest`, `:seon.test/source`. That is program-entity data, not
turns or evaluations.

Node creation (files still on disk, by mtime, 10-minute buckets): 10k to 49k per bucket, average
~20k/10 min, spikes of 48k (23:50Z) and 49k (01:00Z). The spikes are not attributed here (the
initial boot and one restart/adoption are the candidates; unverified).

Dead branch heads (not in roster, head key and its pinned roots remain until collection):
rehearsal 197, building-source 143, checkpoint 40, agent 24, merge 4, program 3.
No dead per-test branch heads exist: test branches are unlinked and leave nothing.

## 2. Per ordinary operation (files/bytes newer than a marker touched just before; the shared
store had ambient writes from other lanes, ~0-20 files per 10 s in quiet windows and 1,400-file
bursts in others, so treat the small rows as upper bounds; each row is one run)

| operation | files | bytes | note |
|---|---|---|---|
| MCP eval `(+ 1 2)` (twice) | 23 (first: ambient-inflated 1,360) | 183 KB | the run's settlement write FAILS (schema resource absent); the files are the fault transaction: two ~24 KB head-sized objects and a 39 KB object plus ~20 small nodes |
| `defn` in scratch ns + call | 38 | 223 KB | same failing settle, plus fault |
| `my.test/run-owned` (seon.error.refusal-test, 17 tests) | 25 | 297 KB | failing settle; its would-be tx carried ~150+ `:seon.profile.measurement` upserts (visible in the fault's `:operations`) |
| `bin/test --ns seon.error.refusal-test` (4.3 s) | 48 | 207 KB | runs in the main JVM; no test branch left behind |
| `bin/seon check src/seon/error/refusal.clj` (17.8 s; publish 15.7 s, adopt 1.7 s) | 2,568 measured to exit; 3,331 by 30 s | 11.5 MB to exit; 14.8 MB by 30 s | `(cluster.boot)` new JVM pid 34120; leaves 4 dead heads (`building-source-*` x2, `rehearsal-*` and `rehearsal-*-default`, 18-22 KB each, none in the roster); 8,000+ store keys in 4 minutes with two more checks/tests by others |

Reading: an ordinary eval/test op costs ~25-50 files and 0.2-0.3 MB, and most of that is a
constant per-transaction floor (a ~22-24 KB branch head with six fused root nodes rewritten on every
commit, plus a root-to-leaf path in each of eavt/aevt/avet and history-only temporal indexes). One
`bin/seon check` costs as much as ~70-100 ordinary operations. Leftover: after p0-settle lands the
settlement write will succeed and the floor will be paid for real (plus measurement upserts).

## 3. Attribution

Of the 3,331 nodes a check wrote (30 s window), by first-datom attribute: `seon.fn` 37% of bytes,
`seon.test` 14%, `seon.fn.file` 11%, `seon.program` 8%, `seon.ns` 6%, `seon.lint` 3%; the top attributes
are `:seon.fn/source`, `:seon.test/source`, `:seon.fn.file/derivation-digest`, `:seon.fn/calls`,
`:seon.fn/call-arities`. Same mix as the whole store (section 1). So the store is made mostly of the
whole program republished, over and over, onto disposable branches. The write is proportional to the
program size (679 identities adopted, thousands of entities), not to the one-file change.

Producers that write the program per operation:

1. `bin/seon check` (`seon.cluster/rehearse-source!`, publish + adopt on a fork), `src/seon/cluster.clj:2857-2905`.
   It calls `full-source-refresh!` with `:seon.source/changed-paths []` (cluster.clj:2887-2893)
   onto a real-store branch `rehearsal-<token>` and forks `rehearsal-<token>-default`
   (cluster.clj:2873-2874), and the checking JVM's own publish adds `building-source-*` scratch
   branches (`src/seon/cluster/source.clj:369-374`, retired at source.clj:391-400 by unlink, which
   leaves the head key). ~20 checks today x ~3k files x ~4 KB = ~60k files, ~250 MB; a rehearsal
   is the largest single writer per operation.
2. Restart/adoption publishes (`seon.cluster.source/publish!`, source.clj:560, and
   `adopt-entities!` mean 1.5 s, max 4.25 s in status): same whole-program facts, onto the
   default store (the 48k-file buckets are consistent with this; not proven).
3. Per-commit constant floor: every transaction rewrites the ~22 KB branch head and the
   index paths (`datahike/writing.cljc:73-160`, `db->stored`; root fusion inlines all six roots).
   Each run settles one transaction (`src/seon/turn.clj:3521-3560`, `settle-batch!`), plus the
   `significant-tx` measurement upserts (`src/seon/profile/measurement.clj:142-182`, one per executed
   definition whose window moved; noHistory) which touch scattered leaves in aevt/avet, cost
   proportional to the number of executed definitions (100-300 for one namespace's tests), not to
   what changed. Not measurable today because settlement fails.

## 4. Top 3 fixes (stop the writes)

1. Rehearse without writing to the store: run `rehearse-source!` (cluster.clj:2857) and the
   scratch publish (source.clj:369) on an in-memory Datahike store (`:backend :memory`, same schema
   and config), or on a copy that is dropped, not on branches of `data/store`; at minimum pass the
   real changed paths instead of `[]` so publish is incremental. Saves ~2,500-3,300 files and
   11.5-14.8 MB per check (~100% of a check's store writes, ~1-3 KB avg node -> 4 KB block each, ~14 MB du).
2. Make the adopt/publish of an unchanged program write nothing: compare the archive's source digest to
   the stored commit before `full-source-refresh!` (source.clj:560 and cluster.clj `adopt-entities!`)
   and publish only entities whose digest changed (derivation-digest, analyzed-source-digest,
   `:seon.fn/calls`/`call-arities`, `:seon.fn/source`, `:seon.test/source` are 60%+ of node bytes and
   are recomputed and rewritten for unchanged definitions). Expected: per restart ~ tens of MB
   (one 10-min bucket is 40-110 MB) down to the diff.
3. Put the measurements beside the run, not in the program store, and bound them: in
   `significant-tx` (measurement.clj:169-182) keep the upsert only when the row's numbers moved
   beyond a threshold or once per N minutes, and fold into the settlement only definitions the run
   changed, not every executed one. Expected: removes ~100-300 scattered-leaf rewrites (~0.3 MB and
   ~100 files) per test-heavy run, and the same again every `run-owned`.

Cheap adjunct: when a rehearsal/building branch is retired, delete its head key too
(`seon.cluster.registry/retire-branch!`), so 426 dead heads (~9 MB) stop pinning roots; it does not
by itself reclaim nodes, only collection does.

## 5. Findings outside this lane

* Every ordinary eval currently fails settlement with "Schema EDN resource is absent: seon/schemas"
  (fault 7b2cf983e1a4; same signature on every call). p0-settle owns it. Each fault repeats a
  ~180 KB write; the repeated signature should not add rows.
* Reading a head key straight with `konserve.core/get` (`:cluster-default`, `:agent-fa9f8f1834b6`)
  throws "Node not found in storage" for a fused-root address (root fusion writes no separate object).
  Only matters for probes; not chased.
* `seon.fn/analyzed-files` mean 2.0 s, `seon.cluster/adopt-entities!` 1.5 s: already in health.
