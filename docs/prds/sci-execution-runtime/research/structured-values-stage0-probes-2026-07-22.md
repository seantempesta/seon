---
type: research
status: active
tags: [research, architecture, database]
---

# Structured-values Stage-0 probe results (2026-07-22 night)

Orchestrator-accepted, DESIGN-CHANGING: one-file-per-node FAILS the
durable contract (~8.5 ms + 4 KiB per small node; 10k-node initial value
≈ 87 s publication) — Stage 2 must NOT graduate on per-node .ksv objects;
Stage 4 tune-or-replace (packed immutable segments or a batched durable
backend) pulls FORWARD ahead of Stage 2 durable work. Everything else
PASSES: hasch cheap, leaves-first/head-last crash-safe at every prefix,
GC exact (0.3 ms/key), d/with speculatively pure, mutation-path economics
excellent (4-6-node path ≈ 34-52 ms). Content addressing + pointer-last
stay. Evidence in tmp/sv-probes/ (measurements.edn + probe scripts).

Stage 4’s tune-or-replace note activates early. Stage 2’s DAG semantics survive, but its one-file-per-node filestore layout does not survive the decisive durability measurement.

### Synced APFS filestore

Pinned Konserve, 32-entry immutable map-leaf nodes, default `sync-blob? true`, temp-file publication, atomic move, and store sync:

| Keys | Durable write | Per key | Reopen read | Files | Logical bytes | Allocated |
|---:|---:|---:|---:|---:|---:|---:|
| 1k | 8.41 s | 8.41 ms | 200 ms | 1,000 | 0.61 MB | 4.10 MB |
| 10k | 86.94 s | 8.69 ms | 564 ms | 10,000 | 6.46 MB | 40.96 MB |
| 50k | 420.63 s | 8.41 ms | 2.50 s | 50,000 | 34.50 MB | 204.80 MB |

Full measurements: [measurements.edn](/Users/sean/src/seon/tmp/sv-probes/measurements.edn:13). Probe implementation: [filestore_bench.clj](/Users/sean/src/seon/tmp/sv-probes/filestore_bench.clj:48).

The design estimates 6k–26k nodes for an initial 100 MiB value. At the measured rate, node publication alone takes approximately:

- 6k nodes: 50–52 seconds
- 10k nodes: 87 seconds
- 26k nodes: 219–226 seconds

That excludes Datahike transaction, indexing, hashing, and wire costs. Conversely, a DAG-native 4–6-node path mutation projects to roughly 34–52 ms, so structural sharing remains valuable after replacing or packing publication.

Fresh-JVM reopening of 50k keys connected in 0.60 ms and read everything in 2.589 seconds. This was cold at the JVM/Konserve level, but the OS page cache was not purged ([reopen result](/Users/sean/src/seon/tmp/sv-probes/measurements.edn:39)).

### Correctness probes

- Crash-prefix: passed all prefixes 0–8. Prefixes 0–7 retained the old head and left only unreachable orphans; the new head appeared only after all eight nodes existed. Zero visible missing references ([probe](/Users/sean/src/seon/tmp/sv-probes/correctness_probes.clj:36)).
- GC correctness: retained all three whitelisted keys and deleted exactly ten orphans.
- Scaled GC: deleted 5,000 of 10,000 files in 1.565 seconds, leaving exactly 5,000—0.313 ms/deleted key ([result](/Users/sean/src/seon/tmp/sv-probes/measurements.edn:63)).
- `d/with`: passed. One speculative datom produced zero write-hook events and no file/byte change. The real commit control fired four write events and grew storage from 7 to 10 files ([result](/Users/sean/src/seon/tmp/sv-probes/measurements.edn:72)).

### Verdict

The leaves-first/head-last publication contract is correct, GC works, reads are reasonable, and `d/with` remains pure. The failure is specifically durable per-file publication: approximately 8.5 ms and one 4 KiB allocation per small node.

Therefore:

- Do not graduate Stage 2 with one `.ksv` object per DAG node.
- Pull Stage 4’s tune-or-replace work forward before Stage 2’s durable implementation.
- Preserve content addressing and pointer-last publication, but publish nodes in packed immutable segments or another genuinely batched durable backend.

All created scripts, stores, and evidence are confined to [tmp/sv-probes](/Users/sean/src/seon/tmp/sv-probes/).
