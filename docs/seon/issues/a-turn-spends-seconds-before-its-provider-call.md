---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [issue, runtime, turn, render, projection, stop]
---

# A turn spends tens of seconds before its provider call, and a stop cannot interrupt it

## Evidence (lane mcp-and-stop, 2026-09-22/23, scratch root `tmp/mcp-stop-root`, HEAD `b1e9b5918` snapshot)

- Live root turn: a message to `root` reached the (stubbed) `seon.ai/complete`
  after **57,145 ms** (`probe-entered` minus message commit). A second probe had
  not reached the provider after **110,087 ms**.
- Canonical fixture (`seon.cluster.agent-test`): trigger to prompt acquisition
  **5,400 ms**; the provider was not reached within the 20 s event backstop.
- Thread dumps (`tmp/mcp-stop-evidence/prelude-before.json`, `during-3.json`,
  `td1.json`): the turn thread is RUNNABLE in
  `seon.db/carried-projection` (`db.clj:1292`) → `schema/load-projection`
  (`schema.clj:2839`) → `projection-from-rows` (`:2778`) → `build-projection`,
  reached from `render/request-projection` (`render.clj:114`),
  `render/selection` (`:596`) and `render.walk/history` → `render-call` →
  `db/pull-many` → `with-declarations` → `read-declarations`. The whole
  projection is re-derived from rows repeatedly inside one prompt acquisition.
- Because that work never blocks, the stop's thread interrupt
  (`seon.cluster.agent/interrupt-turn!`) is not observed until the turn reaches
  a blocking call. Measured `bin/seon stop` during the prelude: **16,921 ms**
  and **~70,000 ms** (client bound 30 s fired; the JVM exited about 40 s later).
  During a provider wait the same stop takes **920 ms**.

## Cause

The same class as the carried-projection re-derivation in
[warm-restart-hangs-in-agent-arm-waiting-on-an-atom-monitor](warm-restart-hangs-in-agent-arm-waiting-on-an-atom-monitor.md):
a database value without a memoized projection pays the full derivation at
every read. Datahike already identifies the value (commit id,
`:cache-context`); the projection is a function of it.

## Owners and wanted behavior

1. `src/seon/db.clj` `carried-projection`: one derivation per database value
   the turn reads (memoized by the value's identity), so the prelude is
   proportional to the prompt, not to the program.
2. `src/seon/turn.clj` `phase` (`:3411`): an `InterruptedException` is the
   orderly stop, not a phase failure; it must propagate instead of becoming a
   `:seon.turn.loop/phase-failed` value, and a CPU-bound prelude should check
   `Thread/interrupted` at its phase boundaries.

Regression: a stop during prompt acquisition of a real (unstubbed) prompt
returns within one second and leaves no open turn.

Sighting 2026-09-23 (lane render-cache-per-branch, scratch root, no provider key): four
messages to `root` transacted from the MCP JVM woke one turn; the armed profile shows
`seon.turn/open-turn` → `system-turn` at 11,312 ms inclusive (under it
`seon.cluster.agent/acquire-context!` x2 5,204 ms, `seon.db/with-declarations` x2395
6,215 ms) before the provider call refused for the missing key.
