---
type: issue
status: open
severity: blocker
tags: [issue, flow, agent, evidence]
---

# Every armed agent holds one platform thread through the error fan-out

## Problem

**Agent density is capped by platform threads, not by memory.** Arming 1,000
agents on the `bench` cluster produced exactly 1,000 additional
`async-mixed-N` PLATFORM threads. The agent's two procs are correctly `:io`
and do run on virtual threads; the platform thread comes from the error
fan-out that `arm!` installs per agent:

```clojure
;; src/seon/flow.clj:699-703 — called once per arm!
(async/pipeline 1 fault-channel (map #(merge % tag)) (:error-chan started) false)
```

`async/pipeline`'s default type is `:compute`, and `pipeline*` starts `n`
`clojure.core.async/thread` workers
(`reference-code/core.async/src/main/clojure/clojure/core/async.clj:595-597`),
which run on core.async's **unbounded cached `:mixed` executor**
(`.../impl/dispatch.clj:71-96`). Each worker then parks forever on `<!!` of
its jobs channel. Measured stack, taken live at 1,000 armed agents:

```
java.base/jdk.internal.misc.Unsafe.park(Native Method)
...
clojure.core.async$fn__881.invokeStatic(async.clj:172)     ; <!!
clojure.core.async$pipeline_STAR_$fn__7259.invoke(async.clj:597)
```

This is exactly the scaling cliff `seon.flow/var-process` refuses at proc
construction ("the `:mixed` default pins one platform thread per proc forever
and is the one measured scaling cliff"). The refusal guards the proc door;
the fan-out walks in through a different one.

## Evidence

Live on cluster `bench`, own operator root at `fea41b7a8`, OpenJDK 26.0.1,
`-Xmx512m`, Apple M5 Max. Probe: `tmp/bench/agents.clj`, `tmp/bench/threads3.clj`,
`tmp/bench/threads4.clj`, `tmp/bench/threads5.clj`.

| armed agents | platform threads | `async-mixed` threads | RSS |
|---|---|---|---|
| 0 | 78 | 3 | 1,021 MiB |
| 100 | 155 | — | 1,031 MiB |
| 500 | 559 | — | 1,074 MiB |
| 1,000 | 1,059 | 1,000 | 1,100 MiB |

Threads are **not leaked**: after disarming all 1,000, the cached pool reaps
its idle workers at the 60 s timeout and the count returns to 0 with RSS
falling 1,128 → 1,057 MiB (≈72 KiB reclaimed per thread — a thread stack).
The cost is real only *while armed*, which is precisely the parked-agent case
the architecture claims is thread-free.

The measured marginal cost per parked agent is therefore ~81 KiB RSS, of
which ~72 KiB is the platform thread stack and ~27-41 KiB is heap. Removing
the thread would bring the parked agent close to the ~17 KB
(2 procs × 8.5 KB) figure `flow-mechanics-2026-07-28.md` measured.

## Expected owner

`seon.flow/join-error-fanout!` (`src/seon/flow.clj:687-703`).

## Acceptance criteria

- Arming N agents adds **zero** platform threads; `async-mixed` stays at its
  boot value as the fleet grows to 1,000.
- One agent graph's fault still reaches the cluster's one fault channel with
  its structural tag, and one graph's stop still never closes the committer's
  inbox.
- A standing regression asserts platform-thread count is flat across an
  arm/disarm cycle of a fleet, so the class cannot return through a second
  fan-out site.

Candidate shapes to evaluate (not a ruling): a `go`-loop instead of
`pipeline`, one `async/merge` over every agent's error channel with the tag
applied at the source graph, or `pipeline-async` on the `:io` executor. The
first two need no new mechanism.
