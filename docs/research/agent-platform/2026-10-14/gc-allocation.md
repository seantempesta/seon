---
type: research
status: proposed
date: 2026-10-14
---

# Collection allocation: metadata enumeration dominates the dry run

`seon.maintenance/collect!` delegates reachability to Datahike and candidate selection to Konserve.
The current mark already shares visited commits and index addresses. The proposed composition is one
Konserve key inventory, passed as a value to its existing consumers, instead of three enumerations.

Disposition: design and measured evidence only; no production change or fork patch. The measured
allocation producer is Konserve's compressed metadata reader, outside the assignment's small Datahike
fork change. Removing the duplicate scans crosses Konserve's result interface and Seon's verification
contract. Neither change has a quiet, same-input before/after proof yet. Do not mark the task complete.

Task: `seon.maintenance/a-collection-dry-run-allocates-in-proportion-to-what-it-marks`.
Related task: `seon.maintenance/collection-marks-slowly-and-records-nothing`.
Prior observation: [chain branch mechanics](chain-on-one-branch.md#1-branch-mechanics).

## Measurement and limits

One completed whole-store dry run, through MCP `eval_clj`, explicit `agent=root`, `cluster=default`,
`mode=jvm`, bridge timeout 120000 ms. The connection was explicitly obtained with
`seon.cluster.boot/connection`; the public maintenance request names the same managed root.
The request set both the required repository root and `:seon.operator.collect/dry-run? true`.
An initial request missing the required repository-root key was refused by its input contract before
collection entered; it performed no collection. The corrected request is preserved in
[the probe](gc-allocation-probe.clj). No real sweep, reset, stop, reload, source definition, branch
creation, or retirement was performed by this lane. MCP itself records JVM evaluations as transactions.

Runtime: PID 2041, started 2026-10-13T10:01:53.730Z; archive
`cde025f68be2c15f497891999d1f62ad6b30ee0c`; checkout HEAD at report preparation
`af11ed4582ef47cd512e647156453b47db0a496d`.
The live resource URL confirms Datahike pin `c9e57c6fb65365d5ce92b59854fb02ea59054fe7`.
Konserve checkout pin: `26444d372bf97de219d55c06d5d23bd6f5cfc634`.
The live and checkout registry source files compared equal.
`bin/seon status` and MCP `runtime_status` both answered; default was already faulted
(one open fault, 35 errored receipts, three agent-error signatures). No tool was missing.

Measured window: 2026-10-14T01:56:04.679255Z–01:56:34.514271Z.
Starting default commit: `6acee134-ea22-5107-aa17-6acac12b19f6`.
Result handle: `result/e7b88e911142b`.

| Full public dry run | Observed |
|---|---:|
| `seon.profile/measure` wall time | 29,818.55 ms |
| JVM-window allocation | 56,382.298128 MB |
| Calling platform-thread allocation | 18,533,671,560 bytes |
| Process CPU window | 32,364.748 ms |
| GC count / accumulated collection time reported by measure | 49 / 189 ms |
| Registry `:mark-duration-ms` | 25,300 ms |
| Logical keys reported | 115,111 |
| Physical store bytes reported | 409,652,017 |
| Candidate keys, derived from projection formula | 75,900 |
| Recorded branch heads | 21 |
| Swept objects / reclaimed bytes | 0 / 0 |
| Roots verified / complete / dry run | true / true / true |

MB here is decimal. Allocation is cumulative allocation, **not retained heap**. JVM counters and the
profile rankings include concurrent work. Two unrelated `seon.cluster.registry/branch!` calls appeared
in the profile, with 21,473 ms summed self time, consistent with the existing sweep-permit contention
class but not an isolated concurrency proof. Branch count grew during this investigation. This was
not a quiet benchmark and cannot establish a performance commit's gain. The 25,300 ms field is not
native mark time: its producer times writer dispatch, native mark, extension, and dry sweep together.
The displayed projected duration was 404,800 ms; its fixed multiplier is already a separate task.

JFR recorded allocation samples at throttle 300/s and execution samples at 10 ms, without changing
production Vars. It produced 8,675 allocation samples and 182 execution samples. Sample weights total
55,249.196456 MB; do not equate them with exact per-phase allocation counters. Async continuations
lose caller frames, and stacks were captured at the JVM's existing depth. In particular, the second
key listing cannot be exactly separated from other async reads from these stacks alone.

| Phase / owner | Time evidence | Allocation evidence |
|---|---|---:|
| Native mark, `datahike.gc/reachable-in-branch` | Allocation samples at +0.433–0.846 s; execution samples through +0.850 s | 330.153 MB with this ancestor visible; incomplete |
| Inventory denominator, `seon.cluster.registry/collect-and-inventory!` → `konserve.core/keys` | Allocation samples at +1.002–13.369 s | 17,323.355 MB attributed by stack |
| Blob extension, `seon.cluster.registry/referenced-blobs` | Two calls; summed instrumented self time 82 ms; samples in both extension and later verification | 986.321 MB attributed by stack, both calls combined |
| Candidate enumeration, `konserve.gc/sweep!` → `konserve.core/keys` | Other listing stacks at +13.847–25.456 s | 1,252.715 MB directly visible; the async read allocation below is not fully attributable |
| Physical byte count, `seon.fs/tree-bytes` | Instrumented self time 317 ms | Not isolated |
| Verification inventory, `seon.maintenance/konserve-key-set` | Instrumented self time 3,372 ms; allocation samples +26.443–29.678 s | 16,891.255 MB attributed by stack |
| Remaining async/other stacks | Across the window | 18,465.396 MB unassigned |

These sample intervals are first/last observed samples, **not exact phase entry/exit times**. Instrumented
self times exclude instrumented callees but include waits. No exact allocation figure is invented for
an uninstrumented async phase. The two directly attributed redundant listings alone account for
34,214.610 MB of sample weight.

A separate read-only native mark probe, using the installed private owner and its shared sets, returned
`result/e16e6a3e21257`. It did not call sweep or repeat the public dry run. On the later, changed store:

| Measured phase | Wall ms | JVM-window allocation MB | Caller-thread bytes |
|---|---:|---:|---:|
| `seon.maintenance/collection-cutoff` | 681.92 | 853.330104 | 851,920,416 |
| All captured branches' native marks | 648.44 | 1,525.900160 | 6,731,256 |

The cutoff was 2026-10-13T10:02:13.568Z. The mark saw 31 branches, 298 visited branch/commit keys,
42,021 index addresses, and 42,338 reachable keys. The visited count includes branch names, not just
unique commit IDs. This phase comparison has different heads and cache state; it is diagnostic only.
It shows why the earlier 27 s figure must not be reported as current native marking cost. Native mark
still allocates substantially; decoder cost and stored record size remain relevant within that 1.53 GB.
Neither warm acquisition nor unrelated work is silently subtracted.

Exact phase form (submit through the same JVM MCP entrance):

```clojure
(let [conn (seon.cluster.boot/connection "default")
      held (:seon.store/store (#'seon.cluster.boot/selected-instance "default"))
      cutoff (seon.profile/measure #(#'seon.maintenance/collection-cutoff held))
      database @conn
      branches (datahike.api/branches conn)
      visited (atom #{})
      seen (atom #{})
      measured
      (seon.profile/measure
        #(let [channels (mapv (fn [branch]
                               (#'datahike.gc/reachable-in-branch
                                 (:store database) branch (:seon.profile/value cutoff)
                                 (:config database) visited seen))
                             branches)
               results (mapv clojure.core.async/<!! channels)]
           (doseq [result results]
             (when (instance? Throwable result) (throw result)))
           {:branches (count branches) :visited-commit-keys (count @visited)
            :index-addresses (count @seen)
            :reachable-keys (count (apply clojure.set/union results))}))]
  {:cutoff (dissoc cutoff :seon.profile/called :seon.profile/ranked-by-calls :seon.profile/ranked-by-self)
   :native-mark (dissoc measured :seon.profile/called :seon.profile/ranked-by-calls :seon.profile/ranked-by-self)})
```

## Owner and proportionality

The read path, at the pinned revisions:

1. `seon.maintenance/collect!` acquires the held store through `seon.maintenance/with-root-store!`.
   `seon.maintenance/dry-run-store!` derives the retention cutoff, inventories, counts physical bytes,
   captures root evidence, enumerates keys again, and verifies roots.
2. `seon.maintenance/collection-cutoff` composes `seon.cluster.registry/retention-cutoff` with
   `seon.cluster.registry/retained-before`. Named exact snapshots can move retention earlier than the
   configured window; removing that work or advancing the cutoff changes what survives.
3. `seon.cluster.registry/collect-and-inventory!` calls `datahike.api/gc-storage`, dispatched by
   `datahike.writer/gc-storage!`. Its extension captures branch heads, lists **all keys just to count
   them**, then derives blob references through `seon.cluster.registry/referenced-blobs`.
4. `datahike.gc/gc-storage!` owns the sweep permit and safe point, clears the schema write cache,
   reads branches, and creates **one shared** visited-commit atom and seen-node atom for this collection.
   `datahike.gc/reachable-in-branch` resolves compact heads through `datahike.writing/read-head`,
   decodes retained commit records, and marks current and temporal indexes. It seeds fused roots into
   storage-bound copies through `datahike.index.interface/with-storage` and
   `datahike.index.interface/-seed-root!`; it does not reload an inlined root as a separate object.
   The index method `datahike.index.persistent-set/mark-unseen` prunes already-seen subtrees.
   Secondary key maps enter `datahike.index.secondary/mark-from-key-map`.
5. `konserve.gc/sweep!` performs the **second full key listing**, selects keys outside the reachable set
   older than the safe point, and returns their keys. The true `:konserve.gc/dry-run?` option takes the
   branch that calls neither deletion callbacks nor deletion operations. The Seon option is translated
   to this precise option by the registry owner.
6. `seon.maintenance/konserve-key-set` does the **third full key listing** for root verification.
   `seon.maintenance/root-verification` distinguishes stored blob references from content digests that
   were never stored, reopens recorded commits, and physically reads stored blobs. Preserve that meaning.

The shared traversal fix already landed as Datahike `25dcaabb7b047dc130decc4864f76c31e5569361`.
The existing proof is `datahike.test.gc-test/marking-with-a-shared-seen-set-walks-each-node-once`.
Reimplementing it would not fix this observation. Some old task prose describes the pre-fix algorithm.
Compact branch names can still cause a head record to be resolved separately from its commit-key visit;
that is not evidence of branch-times-whole-history traversal.

Native work should follow distinct retained commit records and the unique reachable index DAG from
live heads (including temporal roots and the commits retained by policy). Denote their encoded/decoded
volume by C and N. It also visits child address edges of newly visited internal nodes; fixed branching
factor makes this proportional to the visited graph. Blob work follows relevant AEVT subtrees and
schema-derived digest attributes. Sweeping additionally **must** inspect K stored keys, including
unreachable keys: the whole operation cannot honestly promise cost solely proportional to N.
The target is O(C + N + relevant blob data + K), with one K enumeration and no payload-sized scratch
allocation for each tiny metadata item. Each collection recomputes from its captured roots and storage
inventory; the proposal introduces no persistent cache.

### The allocating producer

`konserve.impl.defaults/list-keys` opens every object and calls
`konserve.impl.defaults/read-blob` in `:read-meta` mode. The header chooses a compressor; metadata is
passed through `konserve.compressor.Lz4Compressor/-deserialize`, which creates a fresh
`net.jpountz.lz4.LZ4FrameInputStream`. Its `readHeader` allocates both `compressedBuffer` and `rawBuffer`
at the frame's maximum block size, independent of the small metadata value being decoded.

This is confirmed by the installed lz4-java 1.8.0 bytecode (`javap -c -p`): two `newarray byte`
instructions at offsets 249 and 259 take `maxBlockSize`. Konserve already switched writes from 4 MiB
to 64 KiB blocks in `c73cbde`; simply reducing that default is not an unimplemented fix. Even with
64 KiB frames, two buffers × 115,111 keys × three listings imply **45.26 GB** of buffer allocation
before Fressian and async machinery. This is an explanatory calculation, not a census of frame sizes.

JFR attributes 42,901.821328 MB inclusively to LZ4 input stacks (77.65% of all sampled weight);
49,131.328600 MB is byte arrays. Top identical stacks alone contribute 16,637.751248 MB under
verification key enumeration, 15,693.563728 MB under inventory key enumeration, and 10,397.468768 MB
under async `konserve.impl.defaults/read-blob` continuations. The byte arrays and LZ4 totals overlap.
`konserve.serializers.FressianSerializer/-deserialize` also rebuilds its handler lookup per metadata
decode, but the current evidence makes the buffer allocation the first owner to address.

## Proposed change and required proof

Phase status: investigated; implementation and red performance proof pending.

1. **First, remove redundant work without changing storage encoding.** Extend Konserve's existing
   sweep result interface so its one enumeration supplies both candidate keys and the observed key
   inventory/count to callers. Preserve its existing candidate-set API by an explicit additive result
   option or a new named result entry point, selected during interface design. Datahike passes that
   result through; Seon's registry consumes it instead of calling `konserve.core/keys` for a count.
   The dry-run verifier consumes the same inventory instead of enumerating again. For real collection,
   capture the pre-sweep stored-blob evidence at that same enumeration, never infer it after deletion.
   Keep existing cutoff, safe-point, root reopening and error behavior. This deletes two full metadata
   scans; it adds no cache, retry, gate, or second collector. Expected allocation savings are supported
   by 34.21 GB of directly attributed sample weight, but a gain is not yet proven.
2. **Then fix remaining per-item decoder cost at Konserve/LZ4.** Investigate the dependency's supported
   decoder options before proposing a replacement. The installed constructor owns two block-sized
   arrays and exposes no caller-supplied buffers in the bytecode inspected. Do not add a pool/cache
   beside `seon.cache`, hand-write an LZ4 parser, or turn compression off globally. Independently encoded
   small metadata is a possible storage-layout change, not a local GC tweak: it needs format/version
   compatibility and a measured win on old and new objects. Existing compressed objects must remain
   readable. No stored encoding change is proposed for immediate implementation.
3. **Make phase evidence truthful at the owner.** Keep the existing duration name's historical meaning
   documented and introduce separately named phase observations if needed. Capture mark, extension,
   inventory/selection and verification windows explicitly. Allocation from JVM counters must retain
   its `:jvm-window` / includes-unrelated-work scope; it must not be advertised as exclusive ownership.
   Publish the declared result schema and its receipt projection together. The task's request for
   allocation beside mark time is not implemented by this research commit.

The performance proof must call the same public `seon.maintenance/collect!` request under
`seon.profile/measure` on parent and candidate, against the same captured store contents in a quiet,
owned disposable root. Keep the dry-run bit true. Measure acquisition separately, and the full first
and warm calls; do not use the 648 ms native-only probe to prove a full-collection improvement.
Compare candidate identities, branch evidence, cutoff, root verification, zero deletion and key counts,
as well as elapsed time and allocation. Exact candidate equality can be checked at the registry
entrance alongside the public proof, without adding bulk keys to the public result.

Tag the new proof with this task and make it red on parent before implementation. Derive its budget
from the fixture's key count and measured necessary decoding cost, then assert both full-call elapsed
time and allocation with `seon.profile/measure`. A successful reduction must also improve measured
end-to-end wall time; two-thirds fewer enumerations alone is supporting evidence. Scale K while
holding the reachable graph fixed, then scale shared branches while holding distinct nodes fixed.
Reuse the shared-mark regression and the existing maintenance root-verification tests. Test old/new
metadata round trips in Konserve if phase 2 changes encoding. No production default sweep is a proof.

The current 29.82 s cost is unjustified ordinary work; two discarded inventories are unnecessary.
One filesystem inventory still visits 115k objects, so a sub-second whole-store guarantee needs a
measured per-key cost, not an invented timeout. This assignment explicitly authorized the slow dry-run
investigation, and the two existing tasks retain the over-ten-second finding.

## Reproduction and verification

Run the checked-in probe only through the stated MCP entrance. JFR data is preserved locally at
`tmp/gc-allocation/dry-run.jfr`, SHA-256
`602e43601caf674352036ac574a174ecb7ef95f6c3c9ce191cae4679adbd199c`.
The durable measurements and exact probe are in this commit; the large raw JFR/JSON remain local
diagnostic evidence. To reproduce the committed aggregation:

```sh
jfr print --json --stack-depth 96 --events jdk.ObjectAllocationSample,jdk.ExecutionSample tmp/gc-allocation/dry-run.jfr > tmp/gc-allocation/dry-run.json
python3 docs/research/agent-platform/2026-10-14/gc-allocation-jfr.py tmp/gc-allocation/dry-run.json 2026-10-14T01:56:04.679255+00:00
```

`clj-kondo --lint src script test --cache false`: 8,730 ms, zero errors, 1,662 warnings.
The Python aggregation ran successfully on all 8,857 recorded events. The probe file adds explicit
requires for static analysis; its measured `let` form is unchanged. No production or test source
lines changed. This is not a performance implementation and claims no measured gain.

The requested lane commit names
`seon.maintenance-test/collection-results-project-branch-maps-to-components`, a pure projection check
that exercises the report shape and performs no collection. There are no changed production
declarations with reaching tests. Other maintenance tests that perform real sweeps are intentionally
outside this dry-run assignment. The lane check result is reported in the final handoff.

Foreign boundaries: default's concurrent lanes invalidate quiet attribution; working-tree edits in
`src/seon/batch.clj`, `src/seon/turn/world.clj`, and `test/seon/context_test.clj` were observed while
lint ran. None is included in this lane's candidate. Existing dirty task files, untracked reports,
and the HTTP-kit submodule were left untouched. No other lane was resumed, messaged, or edited.
