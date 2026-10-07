# Commit floor: what one ordinary commit writes (2026-09-28, lane commit-floor)

Live default JVM (pid 34120, 4b29a0689, settlement working). Agent "commit-floor" via MCP `eval_clj`,
branch `agent-c67543ede734` (retired at the end). Method: `touch` a marker, one eval, 2 s, count files
newer than the marker in `data/store` (bytes = apparent, LZ4). An idle 10 s window before the runs wrote
0 files, so other writers were quiet. Attribution: read the branch head with `konserve.core/get` and walked
each of its six index roots, hashing every child address (`hasch.core/uuid`) to its file name.

## 1. Per operation (6 repeats each)

| operation | files (6 runs) | KB (6 runs) | commits | datoms |
|---|---|---|---|---|
| idle 10 s | 0 | 0 | 0 | 0 |
| `(+ 1 2)` | 25 23 26 28 23 26 | 128 151 148 135 120 136 | 1 | 30 |
| `(defn fN "x" [a] (inc a)) (fN 1)` | 82* 68 126 61 67 64 | 483* 410 801 394 419 409 | 1 | 64 |

\* the first defn also created the namespace (87 datoms). The 126-file / 801 KB run is unexplained (an
ambient writer or a larger commit; not repeated). One MCP call = one commit on the agent's branch
(op-count deltas 30 and 64); JVM-mode probes are commits too (30 datoms each).

## 2. Attribution of ONE `(+ 1 2)` commit (26 files, 135.6 KB, every file accounted for)

| object | files | KB | share |
|---|---|---|---|
| commit record (`[cid db]`) | 1 | 21.7 | 16% |
| branch head (`[branch db]`) | 1 | 21.6 | 16% |
| aevt nodes (8 leaves 20.9 KB + 7 branches 42.8 KB) | 15 | 63.7 | 47% |
| avet nodes (3 leaves + 3 branches) | 6 | 17.0 | 13% |
| eavt nodes (2 leaves + 1 branch) | 3 | 11.5 | 8% |
| temporal indexes, schema-meta | 0 | 0 | 0 |

The commit wrote 30 datoms over 19 distinct attributes (11 `seon.cluster.eval/*`+`seon.eval/*` x2 evals, 8
`seon.turn/*`+tx). For the defn: 64 datoms (values total 877 chars) over 44 distinct attributes, 62 index
nodes, 366 KB of nodes plus the same 43 KB of head and commit record: about 6 KB written per datom.

## 3. Why

1. Heads: `:fuse-index-roots? true` inlines all six root nodes into the db record
   (`reference-code/datahike/src/datahike/writing.cljc:143-193`); `commit!` then writes that record twice,
   as the commit record `[cid db-to-store]` and as the branch head `[branch-key db-to-store]`
   (writing.cljc:523-528). Head size = sum of root fan-outs: eavt 112, aevt 115, avet 42, temporal 22+21+7
   = 319 children at ~68 B (a 16-byte address plus a full datom separator, incompressible) = 21.6 KB. It
   grows to 6 x 128 x 68 = 52 KB at full roots. Roots would be written once as ordinary nodes if unfused.
   Roots that did not change are still re-written (the temporal ones were untouched here, yet sit in both).
2. Index nodes: copy-on-write path rewriting. Every distinct index region touched rewrites its leaf and its
   parent branch. aevt sorts attribute-first, so a commit touching N attributes touches about N leaf/branch
   pairs (19 attrs -> 8 pairs, several attributes share a leaf; 44 -> ~25). A level-1 branch holds 127
   separator datoms + 127 addresses (~7 KB, up to 34 and 90 KB seen with larger separator values) versus a
   1-2 KB leaf, so the branch is 5x the leaf bytes. eavt is cheap (new entity ids are adjacent: 3 nodes);
   avet touches only indexed attributes.
3. So the floor is O(distinct attributes touched x (branching-factor x slot bytes) per index) + 2 x head,
   not proportional to datoms; datoms are ~1 KB of the 135 KB.

## 4. Fixes, by size of win (nothing changed by this lane)

1. Seon schema, largest: fewer distinct attributes per commit. Eval and turn facts are 19-44 one-datom
   attributes each in aevt; each costs ~8 KB (leaf + branch). A stored eval value as one map-valued
   attribute or fewer, denser attributes cuts the aevt+avet share (60% of the commit) roughly in proportion.
   Owner call: it gives up per-field queryability of eval rows.
2. Datahike fork: head duplication (32% of an eval commit). Write the commit record and branch head from one
   object, or omit fused roots from the commit record (it only needs `:merkle-roots`, the roots being
   recoverable through the head at that cid). `:commit-graph? false` would drop one copy but is ruled
   inadmissible (`resources/seon/tasks/seon.cluster.agent.edn`); `:fuse-index-roots? false` is creation-time
   and trades ~21 KB per commit for one extra GET per cold open.
3. PSS/Datahike config, untested hypothesis: a smaller `:branching-factor` (128 -> 32; `seon.cluster.store`
   :207) shrinks each rewritten node ~4x for one more level (net ~2x on nodes, head <= 6 x 32 x 68 = 13 KB).
   Creation-time, needs a throwaway-store measurement first; more, smaller files cost more APFS 4 KB blocks.
4. Reclamation is separate: this per-commit floor is written to garbage almost immediately (the previous
   commit's rewritten nodes are unreachable after the next commit) and `last-collection` has never run.

## 5. Not established

Which of the 30/64 datoms are needed at all (owner rule #1 question) was not audited; `:seon.eval/shown`
and `:seon.cluster.eval/source` are stored per eval (2 evals per commit is itself unexplained: `id` x2,
`run` x2). The 801 KB defn outlier. The test-agent branches a defn schedules were not attributed (no files
outside the agent's own tree were found for the 64-file run).
