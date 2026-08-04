---
type: issue
status: open
severity: friction
tags: [issue, operator, repl, mcp]
---

# Stop reporting an MCP-proven live prepl as unreachable

## Problem

`bin/seon status` can identify a cluster as alive and print its prepl port,
then report that the same recorded JVM's prepl is unreachable and suppress the
live branch roster. A fresh MCP stdio client can successfully evaluate through
that coordinate at the same time, so the operator is losing or
misinterpreting a reachable prepl observation.

## Evidence

On 2026-08-03, immediately after a reset-boundary default boot, status printed
PID `95639`, prepl port `54028`, state `alive`, and then:

`roster unreadable: A recorded JVM is alive but its prepl is unreachable; the
offline reader was not allowed to contend for its flock.`

A newly launched `bin/mcp-server` process then used that advertisement and
`eval_clj` returned `42` from `(+ 40 2)`. Its `runtime_status` call reported the
same PID and port, observed health and Flow replies, and readiness in 1,506 ms.

`script/seon/fresh_operator.clj:1072-1112` chooses the recorded-process error
when no probed JVM contributes both `:seon.fresh-operator/reachable?` and
`:seon.fresh-operator/persisted-branches-observed?`. The status renderer prints
that result at `script/seon/fresh_operator.clj:2021-2035` even when the cluster
row itself remains alive.

The REPL edge dogfood pass independently reproduced the contradiction on
2026-08-04. MCP completed every door probe through `edgefaces0804` on prepl
port 56068. Immediately afterward, root-scoped status printed PID 11892 and
that same port as `alive`, then emitted the identical `roster unreadable` /
`prepl is unreachable` sentence. This rules out a default-root mix-up and a
stale advertisement: the successful MCP requests and contradictory operator
observation addressed the same isolated root and process.

## Owner

The root-scoped operator observation shared by `bin/seon status` and MCP
inventory.

## Acceptance

- One reachable prepl observation drives both the cluster row and live roster
  read; the two projections cannot disagree about reachability.
- A recurring test starts a real operator JVM, proves an eval through its
  advertised prepl, and asserts status reports a live-JVM roster rather than
  the recorded-process fallback.
- Failure remains loud: a genuinely unreachable recorded JVM still refuses an
  offline flock contender and explains why the roster is unavailable.
