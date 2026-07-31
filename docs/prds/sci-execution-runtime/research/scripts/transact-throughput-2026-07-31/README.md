---
type: reference
status: active
tags: [reference, datahike, architecture]
---

# Reproducing the transact-throughput decomposition

Findings and the ranked fix table live in
`../../transact-throughput-regression-2026-07-31.md`. Raw stdout of the runs
recorded there is in `raw-output.txt`.

Every script writes its own throwaway stores under `tmp/perf-fsync/` and
touches no cluster. Run each from the repository root:

```bash
clojure -M:dev -i docs/prds/sci-execution-runtime/research/scripts/transact-throughput-2026-07-31/fsync-pricing.clj
```

| script | what it measures | wall time |
|---|---|---|
| `fsync-pricing.clj` | one `FileChannel.force`, one directory force, one konserve `k/assoc` with and without `:sync-blob?`, and a Datahike file commit with history on/off | ~2 min |
| `growth-and-coalescing.clj` | objects per commit and commit latency as the store grows to 161k datoms; the 1 → 1,024 concurrent-caller curve; `commit-wait-time` 0/5/25 | ~40 min |
| `options.clj` (needs `options-lib.clj` beside it) | the ranked fix table, rows A–I | ~15 min |
| `options-admissible.clj` | rows J–K: the same write-amplification options with Seon's commit graph and history kept | ~5 min |

`options.clj` and `options-admissible.clj` build a ~21,000-datom store per row
so the rows are comparable; expect several minutes per row.
