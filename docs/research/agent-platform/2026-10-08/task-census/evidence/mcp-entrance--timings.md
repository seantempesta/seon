# MCP entrance timings (lane mcp-entrance-opus, 2026-09-28)

Default pid 75624 then 85484 (52e163bee). All forms evaluated through the old bridge's JVM mode; `seon.mcp`
was evaluated as the scratch copy `scratch.mcp-entrance.impl` (identical apart from a local nil-free
`jvm-request`, which patch `turn-batch-jvm.patch` makes the real one). Milliseconds, warm unless noted.

| Operation | Samples (ms) |
|---|---|
| Old path, JVM side: root `evaluate-source!` + `run-observation`, `(+ 1 2)`, idle root | 88 + 42, 114 + 37 |
| Old path, back-to-back root runs (second waits on root's system turn) | 152, 7046, 6551, 6586; 6904 |
| `seon.mcp/call` eval_clj sci, branch agent, `(+ 1 2)` | 66.5, 63.0, 59.6 |
| Raw pipeline for the same (evaluate-source! + run-observation) | 60.8, 58.5, 54.9 |
| `seon.mcp/call` eval_clj jvm, branch agent | 63.3, 55.4, 75.2 |
| `seon.mcp/call` eval_clj jvm, root | 103.4, 87.0, 89.4 |
| `seon.mcp/call` eval_clj sci, root (first after restart, cold) | 511 |
| `seon.mcp/tools` | 0.9, 0.34, 0.89 |
| `seon.mcp/call` runtime_status | 365.9, 253.0, 237.0 |
| New agent, first use: create-branch! / host! / first run | 29 / 160 / 74 (first ever in a fresh JVM: 2007 total) |
| Refusals (unknown tool, invalid arguments) | 3.2, 3.3 |
| Contracts compile check over HEAD + change (7 files) | 534 |

Entrance overhead over the pipeline: about 5 ms per call.

Round trip: `(seon.cluster/mcp-valf "default" seon.config/defaults s false [])` on an unmarked thread, `s` 380,000
chars of `héllo "wörld" ✓\n\t\\ ` repeated, read back with `clojure.edn/read-string`: equal, 480,002 printed chars.
