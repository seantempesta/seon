# Waste ranking on default (pid 30874), 2026-10-07

Measured through MCP eval_clj runs as a disposable branch agent (waste-rank-probe) via seon.mcp/call, 60-90 s limits; seon.profile counter diffs, JVM allocated bytes, heap after GC, store bytes, jcmd class histograms, three JFR recordings (≤60 s, 64-frame stacks). Allocation is JVM-wide (other lanes ran concurrently; a 1 s idle window allocated 0). Tasks: commit 4e48f5785. Raw data beside this file.

## Large values and errors through the agent REPL path

| case | ms | alloc MB | armed calls | reply tokens | what Seon stores or does |
|---|---|---|---|---|---|
| (+ 1 2) | 82-103 | 135-184 | ~11.1k | 28 | ~100-130 KB on disk per commit |
| (vec (range 1e5)) | 505 | 900 | 1,315,014 | 119 | 5.27 MB stored copy; show/storage 386-400 ms |
| (vec (range 1e6)) / 1e7 | 226 / 1,082 | 569 / 2,206 | ~15k | 120 | not stored (over 8 MB); 1e7 is the agent's own evaluation |
| into {} 1e5 / 1e6 | 276 / 1,008 | 654 / 2,104 | ~15k | 245-261 | 1e6 mostly the agent's own evaluation |
| 1 MB string | 224 | 318 | 13k | 611 | +1,083 KB stored copy |
| 50 MB string | 4,600 | 8,749 | 12.6k | 689 | 4.4 s is the agent's own work |
| (range), (iterate inc 0) | ~210 | ~440 | 14k | 60 | bounded |
| infinite (map f (range)) | 277 | 914 | 214k | 165 | 157,403 elements realized to show 32 |
| lazy seq throwing at 5,000 | 109 | 329 | 16.6k | 60 | bounded |
| 10,000-deep vector | 218-314 | 357-452 | 81-122k | 332 | VALUE LOST: settlement refused no-terminal-fact; 1,138-char diagnostic stored |
| (seon.db/db), map holding a connection | ~105 | 387-476 | — | 39-46 | bounded |
| (/ 1 0), unknown-attribute query | 87-128 | 143-164 | 13-17k | 73-161 | fine |
| stack overflow | 1,254 | 2,076 | 361,649 | 351 | keeps 7,969 SCI frames |
| thrown 1,000-link chain | 874 | 2,100 | 111k | 678 | whole chain classified (walk-links 1.74 GB, first-party-in 0.76 GB, malli regex 0.40 GB) |

Violations (Seon's own work/storage grows with the value, chain or stack):
- V1 seon.sci.eval/value-storage (eval.clj:3524-3545): admit-value under the 8 MB bound, discards that EDN, re-serializes with artifact-edn: second and third walk plus a stored copy on every evaluation.
- V2 seon.sci.kernel/failure-value (kernel.clj:788) with refusal walk-links/stack-frames/first-party-in (refusal.clj:441, :161, :130): links × frames, each frame through an armed regex contract.
- V3 a deep value: settlement refused, value not delivered — the path is not total (cause unproven; likely a recursive walk in recording/settlement).
- V4 a refusal repeats its whole offending request (existing task in seon.error.refusal).

## Ordinary operations

| operation | ms | alloc MB | armed calls |
|---|---|---|---|
| (+ 1 2) | 79-103 | 135-151 | ~11.1k (45 queries, 44 pulls, one clj-kondo pass; commit writes 28 datoms, transaction-report-datom? checked 560 times) |
| first use of a new agent | 655 | 1,686 | 40k |
| contracted defn | 452 | 973 | 92k (transact! 277 ms; three write-report validations ~30+28+20 ms) |
| db query | 100 | 167 | 18.5k |
| error reply (/ 1 0) | 87 | 143 | 13.3k |
| deftest | 202 | 397 | 24k |
| one test run cold / again | 3,540 / 566 | 8,856 / 1,228 | 290k / 83k |
| seon.health/report | 527 | 1,084 | 217k |
| pull [*] of 715 evaluations | 1,529 | — | 938k |

## Ranked offenders

By time (session delta ~50 min): rehearse-source! 22.8 s/call; full-source-refresh! 18 s/call; seon.db/q 28,483 calls 45.7 s; analyze-entities 5.5 s/call; my.test/check 13 s/call; fn.analyzer/analyze 71 ms per evaluation; fork-cluster-ctx 333 ms; acquire! 185 ms; base-ctx 1 s; pull-call 55.7k calls 13.8 s; invoke-kondo 44 ms per evaluation; with-publication! 46 ms; validate-write-report 30 ms; encode-call-output-in 6.6 ms × 1,717; write-report-error 28 ms; open-turn 8.8 s; write-owned-values-error 20 ms; carried-projection 390k calls 6.35 s; latest-evaluations 793 ms; acquire-context! 76 ms; derive-step 19 ms; compiled-storage 552k calls 4.4 s; projection-cache-value 3.94M calls 3.4 s.

By allocation (JFR, Seon frames): value-storage 864 MB; compiled-storage 1.85 GB; walk-links 1.74 GB; vector-query-call 15.1 GB in a busy 45 s window (mostly Datahike SinceDB/HistoricalDB filter_txInstant history scans; caller unproven at 64 frames); fn.analyzer/parsed-facts 2.05 GB; invoke-kondo 1.87 GB; base-bindings 1.09 GB; encode-query-request 1.0 GB; regenerate-agent-context! 0.99 GB; print-canonical! 246 MB.

## Memory

Live heap 2.41 GB (6.36M Datoms 254 MB, 3.2M Symbols, 14.7M Longs mostly the probe's own 1e7 vector). Cache 511.6/512 MB: reach-entries 288 MB at 49% hits; decoded-edn 87.9 MB with 41.6k misses and 21.7k evictions; reach-digest 35% hits; health/derived 22% hits with 8.1 s loading. Leak: ~200-229 KB retained per (+ 1 2), linear over 200 runs (~600 Datoms per run); retiring the branch released nothing within 3 s.

## Fix order (worst first)

1. V3 deep value loses the run.
2. V1 value-storage walks and stored copy.
3. V2 failure frames and links (fold into the errors spec slice).
4. ~200 KB heap retained per evaluation.
5. transaction-report-datom? ~20 checks per datom (existing seon.db task).
6. Three write-report validations per commit.
7. Per-value decode scope and cache claim.
8. (+ 1 2) overhead — re-measure after agent-loop work, which deletes much of it.
9. reach-entries cache (after the cc6e2050d reach fix).
10. Profile cells validation (the histogram? health entry).
11. History-scan caller: re-record JFR with 256-frame stacks.
12. Test-run cost (existing seon.test task).

Gaps: the prompt-render probe was refused for missing caps (and its refusal printed the whole 767-token request map); some armed-call counts include concurrent lanes; committing 7 task files took a 21 s check (16.5 s publication) because task files count as resources.
