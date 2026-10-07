---
type: notes
status: baseline recorded (S0 landed in 7737b8844)
created: 2026-10-07
lane: s0-probe
---

# S0 baseline at 7737b8844 (lane s0-probe, 2026-10-07)

The probe is `seon.pass-probe` (`test/seon/pass_probe.clj`); design `design-notes.md` (v2 section governs). Records:
`baseline-7737b8844.edn` (full records at 1/10/100/200 earlier passes and every definition/check pass; every pass as a
compact curve row). Measured on a throwaway root (`bin/seon --root <scratchpad>/s0-probe/root start --head`,
cluster `default`, fresh store, JDK 26.0.1, Datahike `dbca76d5`), one run of the matrix (16.4 min wall):

```clojure
(require 'seon.pass-probe)
(seon.pass-probe/run-mcp "default" "sci" 200 #{1 10 200} true)
(seon.pass-probe/run-mcp "default" "jvm" 200 #{1 10 200} true)
(seon.pass-probe/run-check "default" 'seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol 3)
(seon.pass-probe/run-model "default" 200 #{1 10 200})
```

Every record complete, no error replies. Single runs, not distributions.

## Broken things first

1. **The model pass is history-proportional at ~140 MB and ~50 ms per earlier exchange.** 10.8 s / 27.6 GB /
   5.5 M armed calls at 200 exchanges: over the 10 s line. At 60 exchanges the ranked growth is
   `seon.turn/system-turn` 944 ms (337 ms at 1) and `seon.cluster.prompt/prompt` 708 ms, with
   `seon.schema/call-with-projection` 55 → 821 calls and `seon.db/with-declarations` 212 → 749. Filed
   `seon.turn/a-model-pass-grows-with-every-earlier-exchange` (077c5a6fc425); S4 deletes both owners.
2. **The model's prompt is about twice its shown history**: 230,821 tokens at 200 exchanges of ~2 KB shown text
   (~125 k tokens of shown values). Same task.
3. **A cold one-test check is 1.9 s / 4.4 GB** (`seon.test/clean-request-handle` 1,163 ms of `seon.test/run`'s
   1,713 ms); warm 111-131 ms / 145-172 MB, member reused (not executed). Budget: < 300 ms cold, < 150 ms warm,
   < 20 MB. Existing tasks on `clean-request-handle` (health lists five: b714adde3f53, ec0365dc824e,
   ea22291d4ddb, f7e19059f5d3, 24bd82435cac).
4. **No ordinary pass is near the 5 MB allocation budget**: MCP SCI 76-111 MB, MCP JVM 49-57 MB. MCP SCI grows
   ~0.18 MB per earlier pass (76 → 111 MB over 200), the pass-alloc finding.
5. **Every model pass is 7 durable commits** (wake, system turn, open, capture, attempt, plan, settlement); budget 1.
   Store per model pass 0.6 MB at 1 exchange → 1.9 MB at 200 (the capture stores the whole prompt).
6. **A first JVM-mode `defn` costs 240 ms / 517 MB** (later ones 39-43 ms / 57-73 MB); a cold MCP SCI `defn`
   351 ms / 532 MB (later 192-227 ms / 266-417 MB). Budget 300 ms / 30 MB.
7. Four `seon.cluster.agent-test` provider deftests are red on HEAD alone (park-wake, restamp-recovery,
   wait-closes-in-terminal-tx, pause-during-in-flight-call; runs 207d1aa3b406, 4d5c0aa0366e): filed
   `seon.cluster.agent/provider-agent-tests-never-see-their-terminal-fact` (0f6497445f67). Unchanged by this slice.
8. `my.agent/retire-branch!` from the retiring agent itself answers `run transition refused: admission-closed`
   (existing task 7116117e6aaf); `seon.cluster.host/retire!` closed both of my default agents (`s0-probe-a`,
   `s0-probe-h`, admission-closed txs 536871276-7).

## Baseline table (§3 rows)

Allocation is the JVM total over the window, an upper bound on Seon's work (it includes the agent's form and the
fixture). "Prior" is the passes the agent had already run (history). Store bytes are the final apparent sizes of the
distinct files the window touched. Commits are distinct durable commit ids.

| row | entrant | prior | wall ms | alloc MB | armed calls | attempts / durable commits | store KB | tokens shown | budget |
|---|---|---|---|---|---|---|---|---|---|
| ordinary | MCP SCI | 1 / 10 / 100 / 200 | 55 / 55 / 49 / 59 | 76 / 79 / 96 / 111 | 9.9 k-10.4 k | 1 / 1 | 117-132 | 641 | 200 ms, 5 MB, 1 tx |
| ordinary | MCP JVM | 1 / 10 / 100 / 200 | 41 / 36 / 36 / 47 | 54 / 49 / 54 / 57 | 4.6 k-5.9 k | 1 / 1 | 105-118 | 639 | 200 ms, 5 MB, 1 tx |
| ordinary | model | 1 / 10 / 100 / 200 | 1,190 / 580 / 3,837 / 10,806 | 2,852 / 1,152 / 10,033 / 27,569 | 128 k / 235 k / 2.2 M / 5.5 M | 7 / 7 | 616 / 713 / 1,338 / 1,912 | 703 / 9.8 k / 123 k / 231 k | 200 ms, 5 MB, 1 tx |
| definition | MCP SCI | 2 / 13 / 205 | 351 / 192 / 227 | 532 / 266 / 417 | 75 k / 65 k / 95 k | 1 / 1 | 274-288 | 43 | 300 ms, 30 MB |
| definition | MCP JVM | 2 / 13 / 205 | 240 / 39 / 43 | 517 / 57 / 73 | 32 k / 5.4 k / 5.4 k | 1 / 1 | 106-111 | 37 | 300 ms, 30 MB |
| definition | model | 2 / 13 / 205 | 768 / 795 / 11,576 | 1,445 / 1,596 / 28,740 | 175 k / 339 k / 5.8 M | 7 / 7 | 779 / 930 / 2,073 | 1.7 k / 14 k / 236 k | 300 ms, 30 MB |
| after a definition | MCP SCI | 3 / 14 / 206 | 97 / 83 / 97 | 129 / 132 / 162 | 98 k | 1 / 1 | 123-125 | 21 | 150 ms, 10 MB |
| after a definition | MCP JVM | 3 / 14 / 206 | 42 / 35 / 36 | 32 / 49 / 56 | 4.5 k | 1 / 1 | 103-111 | 16 | 150 ms, 10 MB |
| after a definition | model | 3 / 14 / 206 | 744 / 949 / 11,761 | 1,496 / 2,107 / 29,491 | 320 k / 496 k / 5.9 M | 7 / 7 | 695 / 744 / 1,961 | 2.1 k / 15 k / 237 k | 150 ms, 10 MB |
| one-test check | MCP SCI `my.test/check` | cold / warm / warm | 1,896 / 131 / 111 | 4,418 / 172 / 145 | 169 k / 50 k / 46 k | 8 / 8 cold (3 agent branch, 5 checkpoint branch); 1 / 1 warm | 650 / 213 / 207 | 735 / 365 / 365 | 300 ms cold, 150 ms warm, 20 MB |
| opening, input, context append, cold transcript acquisition | — | UNAVAILABLE until S4 (today's wake and refresh are inside the model row) | | | | | | | |
| two encodings | — | UNAVAILABLE until S8 | | | | | | | |

The provider's own service time is 0-8 ms per model pass (fixture), so the model row is Seon's work.
The cold check's member executed (`seon.test/bounded-result` 21 ms); the warm checks reused the recorded green.

### History-growth curves (ordinary passes, prior → wall ms / alloc MB)

- MCP SCI: 1 → 55 / 76, 5 → 54 / 75, 10 → 55 / 79, 20 → 53 / 82, 50 → 50 / 91, 100 → 49 / 96, 150 → 54 / 111,
  200 → 59 / 111. Time flat; allocation +0.18 MB per earlier pass.
- MCP JVM: 1 → 41 / 54, 5 → 37 / 50, 10 → 36 / 49, 50 → 38 / 55, 100 → 36 / 54, 200 → 47 / 57. Flat.
- Model: 1 → 1,190 / 2,852 (cold), 5 → 475 / 877, 10 → 580 / 1,152, 20 → 793 / 1,766, 50 → 1,555 / 3,970,
  100 → 3,837 / 10,033, 150 → 7,064 / 17,736, 200 → 10,806 / 27,569. Allocation per pass grows linearly in
  history (~140 MB per exchange), so a whole history's cost is quadratic.

Every curve row is in `baseline-7737b8844.edn` under `:seon.pass-probe/curves` (columns named there).

## The profile mark (fixed at the producer in 7737b8844)

Throwaway root, 2,593-2,596 live cells, same form (scratchpad `prof-cost.clj`): reading the profile no longer calls
the per-cell predicates.

| call | before (f61b37f37) | after (7737b8844) |
|---|---|---|
| `seon.profile/cells` | 3.9 ms, 2.3 MB, ~36 k predicate calls | 0.47 ms, 0.26 MB, 0 |
| `seon.profile/begin` | 4.1 ms, 2.2 MB, ~36 k | 0.84 ms, 0.31 MB, 0 |
| `seon.profile/explain` | 9.5 ms, 4.7 MB, ~57 k (incl. 2,593 `entries`) | 2.2 ms, 0.42 MB, 0 |
| an empty mark's growth | — | 3 calls: `explain` ×1, `snapshot?` ×2 (the two contract checks of the mark) |

## Method notes and limits

- Allocation: `getTotalThreadAllocatedBytes` delta; a virtual thread answers -1 to per-thread reads and its bytes
  land on its carrier (`ForkJoinPool-1-worker` in `::allocated-by-thread`). The unattributed remainder (~9-22 MB in
  MCP passes) is the entrance thread the probe starts and joins, which has exited by the end reading. At 200
  exchanges a model pass allocated 21.5 GB on carriers and 5.4 GB on `async-mixed` (the writer).
- Checked calls are JVM-wide outermost entries; on a quiet root they are the pass's.
- Durable commits are distinct `[:meta :datahike/commit-id]` values of written commit records, by branch, from a
  konserve write hook on the physical store; test worlds' overlay stores keep no hooks, so `seon.pass-probe-test`
  asserts only allocation, calls, attempts, tokens and fixture requests.
- Tokens: `seon.ai.tokens/estimate` with the shipped 3.2 characters per token, over the MCP reply's text items or
  the model request's message contents.
- NOT measured on default: default runs `d59363530`, which predates 7737b8844, so `seon.pass-probe` does not
  resolve there. After root's restart the same forms run as JVM-mode MCP forms, one entrant per call to stay
  under the bridge's 32 s timeout, e.g. `(seon.pass-probe/run-mcp "default" "sci" 10 #{1 10} true)` and
  `(seon.pass-probe/run-model "default" 10 #{1 10})`; the JVM entrant's definition kind only on a throwaway root
  (`(seon.pass-probe/run-mcp "default" "jvm" 10 #{} false)` on default).
- Cold here means first on a fresh agent of a freshly booted root; a proc-restart transcript acquisition does not
  exist until S4.

## Design check

- Held: every reading is an owner's (HotSpot, `seon.profile`, `seon.db/transact-call`'s cell, konserve hooks,
  `seon.ai.tokens`); the probe composes them in one `window` around real entrances.
- Wrong in v1 and corrected before code: branch-key writes as commits, one-submission history, a probe-side
  profile copy (all from the reviews).
- Awkward: the model window ends on Flow's idle report read from the armed agent's `:report-chan`, which nothing
  else consumes; the probe drains it before each wake. A second consumer of that channel would race the probe.
- Added after the commit: `::top-ms` (the ranked growth) on every record, used for the attribution above; it lands
  with these notes.
