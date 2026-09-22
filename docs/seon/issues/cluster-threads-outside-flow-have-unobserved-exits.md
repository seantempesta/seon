---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, flow, threads, unobserved-exit]
---

# Cluster threads outside flow have unobserved exits

**Evidence ([flow usage audit](../../../docs/research/agent-platform/flow-usage-audit-2026-09-23.md) D8, source).**

- (a) The turn backstop watcher loops on the executor outside every graph
  (`src/seon/turn.clj` turn-backstop watcher, audit row 5).
- (b) The per-agent schedule timer is a virtual thread running `Thread/sleep` then
  `offer!` (`src/seon/schedule.clj` `arm-timer`); cancel is an interrupt, and its exit is
  never joined (audit row 3).
- (c) One SSE feed writer virtual thread per browser tab (`src/seon/render/web.clj`
  feed, `Thread/ofVirtual`); `web/stop!` does not join them (audit row 19).
- (d) The http-kit worker executor (`Executors/newVirtualThreadPerTaskExecutor`,
  `render/web.clj` serve) is never shut down on stop (audit row 20).

**Wanted.** (a) a backstop proc; (b) `async/timeout`, no thread; (c) feed threads
registered in the service and joined under a bound in `web/stop!`; (d) `web/stop!`
shuts the executor down and awaits termination under a bound. One regression per item.

**Owner.** `turn.clj`, `schedule.clj`, `render/web.clj`. Audit §5 steps 7, 9, 14.
